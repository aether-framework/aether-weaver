package de.splatgames.aether.weaver.api.spi;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import de.splatgames.aether.weaver.api.model.HandlerRef;
import de.splatgames.aether.weaver.api.model.LocalSpec;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.lang.classfile.CodeBuilder;
import java.lang.classfile.TypeKind;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * How one handler's arguments are put on the stack at one injection site.
 *
 * <p>A binding is the answer to a question asked once per site: given this handler's parameter list
 * and this target method, which of the target's locals have to be loaded, in what order, from which
 * slots, does a callback have to be constructed, and are any local variables captured. It is
 * produced by {@link #bind(HandlerRef, MethodView, List, int, Reporter)}, which either returns a
 * binding or reports why the handler cannot be called and returns {@code null}. An injector reaches
 * one through {@link InjectionContext#argumentsAt(int)} and emits it with the {@code emit} methods
 * below.
 *
 * <h2>The shape a handler must have</h2>
 *
 * <p>A handler's parameters are read as four consecutive runs, in this order and no other. Every
 * rule below is checked by {@link #bind(HandlerRef, MethodView, List, int, Reporter)} before any
 * byte is written, so a handler that breaks one is reported and dropped rather than emitted into a
 * class that will not verify. Every one of these diagnostics is an error, and a build-time driver
 * fails the build on an error.
 *
 * <ol>
 *   <li><b>Values the stack already holds</b> — {@code skipLeading} of them. These are the operands
 *       of the operation being taken over, or the value the injection captures, and the engine
 *       decides how many by asking the injector. Their types are not compared against anything
 *       here; the injector that asked for them arranged for them to be there.
 *   <li><b>A prefix of the target method's own parameters</b>, in the target's declaration order:
 *       the first one, the first two, and so on, or none at all. Never a subset and never a suffix.
 *   <li><b>At most one callback</b>, which the engine constructs.
 *   <li><b>Every {@code @Local} capture</b>, one per parameter carrying the annotation.
 * </ol>
 *
 * <p>Two derived numbers hold the whole scheme together. The captures occupy the tail, so the part
 * of the parameter list everything else reasons about ends at
 * {@code visible = parameterCount - locals.size()}; and inside that part the callback is the last
 * position, so the number of the target's parameters the handler claims is
 * {@code declaredArguments = visible - skipLeading - (callback ? 1 : 0)}.
 *
 * <h2>Each rule, and what a violation reports</h2>
 *
 * <ul>
 *   <li><b>Every {@code @Local} parameter is one of the last {@code locals.size()} positions.</b>
 *       An annotated parameter anywhere earlier is reported as {@code AW1040}, naming the position
 *       and how many trailing positions were expected to hold captures. Move the captured
 *       parameters to the end. Checked first, because every other number is computed from the
 *       boundary it establishes.
 *   <li><b>There are at least {@code skipLeading} parameters in front of the captures.</b> A
 *       handler with fewer is reported as {@code AW1040}, saying how many values the operation
 *       supplies and how many parameters were declared.
 *   <li><b>A callback matches what the target returns.</b> A plain
 *       {@link de.splatgames.aether.weaver.api.callback.Callback} on a target that returns a value
 *       is {@code AW1070} — cancelling would leave the method with nothing to return; declare
 *       {@link de.splatgames.aether.weaver.api.callback.ReturnableCallback} of that type instead. A
 *       {@code ReturnableCallback} on a {@code void} target is {@code AW1071}; declare a plain
 *       {@code Callback}.
 *   <li><b>The handler claims no more parameters than the target has.</b> Otherwise {@code AW1040},
 *       naming both counts.
 *   <li><b>An instance handler needs an instance to be called on.</b> A non-static handler against
 *       a static target is reported as {@code AW1005}: a static method has no {@code this} for a
 *       merged handler to be invoked against. Declare the handler {@code static}, or target an
 *       instance method.
 *   <li><b>Each claimed parameter has exactly the target's type at that position.</b> Compared as
 *       {@link ClassDesc} equality, so there is no widening, no boxing and no subtyping: a target
 *       parameter of {@code long} is not matched by an {@code int} parameter and a target parameter
 *       of {@code String} is not matched by a {@code CharSequence} one. A mismatch is
 *       {@code AW1040}, naming the position and both types.
 * </ul>
 *
 * <p>The rules are checked in that order and the first failure ends the attempt, so a handler that
 * breaks two of them shows the earlier diagnostic.
 *
 * <p>A prefix rather than a subset is not a convention that could have gone the other way: a
 * parameter has no identity in a compiled method beyond its position, so there is nothing to name a
 * subset with. A handler that wants the target's second parameter and not its first declares both
 * and ignores one.
 *
 * <h2>Slots, which are not positions</h2>
 *
 * <p>The loads a binding carries name local variable slots, not parameter indices. Slot zero holds
 * {@code this} in an instance method, so its parameters begin at one, and a {@code long} or a
 * {@code double} occupies two slots. In {@code void takesWide(int a, long b, int c)} declared on a
 * class, the three parameters live in slots 1, 2 and 4. Binding walks every parameter of the
 * target, including the ones the handler does not claim, precisely so that the slots of the ones it
 * does claim are right: using the parameter index instead would load the high half of a
 * {@code long} where the next parameter was meant — a valid {@code int} in a valid slot, which the
 * verifier accepts and nothing reports.
 *
 * <h2>Emitting one</h2>
 *
 * <p>The emit methods push a call's arguments in the order the JVM needs them, and the caller keeps
 * the parts only it knows about:
 *
 * <ol>
 *   <li>{@link #emitReceiver(CodeBuilder)} — the target instance, for a handler that is not static.
 *   <li>the {@code skipLeading} values the stack already supplies, pushed by the caller — an
 *       injector that asked {@link #bind(HandlerRef, MethodView, List, int, Reporter)} for a
 *       nonzero {@code skipLeading} is the one that knows what those values are and where they
 *       came from, so this class neither pushes nor describes them; they belong between the
 *       receiver and the claimed target parameters, in that position and no other.
 *   <li>{@link #emitArguments(CodeBuilder)} — the claimed target parameters.
 *   <li>the callback, if {@link #takesCallback()}, which the caller constructs and pushes itself.
 *   <li>{@link #emitCaptures(CodeBuilder)} — the captured locals, after the callback and never
 *       before it, since swapping the two is an argument transposition the verifier only catches
 *       when the types happen to differ.
 *   <li>the {@code invoke} instruction.
 *   <li>{@link #emitWriteBacks(CodeBuilder, List)} with what {@link #emitCaptures(CodeBuilder)}
 *       returned, which copies each mutable capture back into the target's variable.
 * </ol>
 *
 * <p>A caller ignoring the second step and pushing the {@code skipLeading} values anywhere else —
 * after {@link #emitArguments(CodeBuilder)}, say — emits a call whose arguments are transposed
 * against what {@link #bind(HandlerRef, MethodView, List, int, Reporter)} checked.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * public class Ledger {                                   // the target
 *     void charge(BigDecimal amount, int retries) { ... }
 * }
 *
 * @Weave(Ledger.class)
 * public final class AuditWeave {
 *
 *     @Inject(method = "charge(java.math.BigDecimal,int)", at = @At(Point.HEAD))
 *     private static void onCharge(BigDecimal amount,
 *                                  Callback cb,
 *                                  @Local(name = "retries") int retries) {
 *         if (amount.signum() <= 0) {
 *             cb.cancel();
 *         }
 *     }
 * }
 * }</pre>
 *
 * <p>Three parameters and one {@code @Local}, so {@code visible} is 2 and the capture occupies the
 * one position it may. Nothing is on the stack at {@code HEAD}, so {@code skipLeading} is 0, which
 * makes the {@code Callback} the last of the visible parameters and leaves exactly one claimed
 * target parameter: {@code amount}, the target's first, with its exact type. The binding loads
 * slot 1 and nothing else.
 *
 * <p>Claiming both target parameters would be equally valid: {@code (BigDecimal amount, int retries,
 * Callback cb)} is a prefix of length two. What is not expressible is asking for {@code retries}
 * without {@code amount}, which is the suffix {@code AW1040} refuses — and a capture is how a
 * handler reaches one parameter without the ones in front of it. Resolving it by name matches
 * against the variables live at the injection point, which at {@code HEAD} are the target's own
 * parameters, and needs the target to carry a local variable table; a target compiled without one
 * is {@code AW1052}.
 *
 * @param loads        the target's locals to push, in argument order; held as an unmodifiable copy
 * @param callbackKind which callback the handler declares, if any
 * @param captures     the {@code @Local} captures to push after the callback, in parameter order;
 *                     held as an unmodifiable copy
 * @param receiver     whether the handler is an instance method and needs the target instance
 *                     pushed first
 * @author Erik Pförtner
 * @since 0.1.0
 * @see InjectionContext#argumentsAt(int)
 * @see LocalSpec
 */
public record HandlerBinding(@NotNull @Unmodifiable List<Load> loads,
                             @NotNull CallbackKind callbackKind,
                             @NotNull @Unmodifiable List<Load> captures,
                             boolean receiver) {

    /** The plain callback, recognised by descriptor rather than by loading the type. */
    private static final ClassDesc CD_CALLBACK =
            ClassDesc.of("de.splatgames.aether.weaver.api.callback.Callback");

    /** The value-returning callback, recognised the same way. */
    private static final ClassDesc CD_RETURNABLE_CALLBACK =
            ClassDesc.of("de.splatgames.aether.weaver.api.callback.ReturnableCallback");

    /**
     * Takes unmodifiable copies of both lists.
     *
     * <p>A binding is handed to an injector and kept for the length of a class transformation, so
     * it does not share a list with whoever built it.
     *
     * @throws NullPointerException if any component is {@code null}, or if either list holds a
     *                              {@code null} element
     */
    public HandlerBinding {
        loads = List.copyOf(Objects.requireNonNull(loads, "loads"));
        Objects.requireNonNull(callbackKind, "callbackKind");
        captures = List.copyOf(Objects.requireNonNull(captures, "captures"));
    }

    /**
     * Creates a binding with no captures and no receiver.
     *
     * <p>The shape of a static handler that captures no local: arguments and at most a callback.
     *
     * @param loads        the target's locals to push, in argument order; must not be {@code null}
     * @param callbackKind which callback the handler declares; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}, or if {@code loads} holds a
     *                              {@code null} element
     */
    public HandlerBinding(@NotNull @Unmodifiable final List<Load> loads,
                          @NotNull final CallbackKind callbackKind) {
        this(loads, callbackKind, List.of(), false);
    }

    /**
     * Reports whether the handler declares a callback.
     *
     * <p>An injector uses this to decide whether it has to construct one and to test it for
     * cancellation after the call; a handler without one is simply invoked.
     *
     * @return {@code true} unless {@link #callbackKind()} is {@link CallbackKind#NONE}
     */
    @Contract(pure = true)
    public boolean takesCallback() {
        return this.callbackKind != CallbackKind.NONE;
    }

    /**
     * Which callback a handler declares, if any.
     *
     * <p>Decided by the erased type of one parameter — the last of the ones in front of the
     * captures — compared for equality against the two callback types. Equality, not assignability:
     * a subtype of either is not recognised, and a parameter of some other type simply means the
     * handler takes no callback.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public enum CallbackKind {

        /** The handler declares no callback and cannot stop the target. */
        NONE,

        /**
         * The handler declares a {@link de.splatgames.aether.weaver.api.callback.Callback}.
         *
         * <p>Valid only against a target that returns {@code void}; anything else is
         * {@code AW1070}.
         */
        PLAIN,

        /**
         * The handler declares a
         * {@link de.splatgames.aether.weaver.api.callback.ReturnableCallback}.
         *
         * <p>Valid only against a target that returns a value; a {@code void} target is
         * {@code AW1071}.
         */
        RETURNABLE
    }

    /**
     * Binds a handler that captures no locals to a target whose stack supplies nothing.
     *
     * @param handler  the handler to call; must not be {@code null}
     * @param target   the method being woven; must not be {@code null}
     * @param reporter where to report a handler that does not fit; must not be {@code null}
     * @return the binding, or {@code null} when the handler cannot be called, in which case the
     *         reason has been reported
     * @throws NullPointerException if any argument is {@code null}
     */
    @Nullable
    public static HandlerBinding bind(@NotNull final HandlerRef handler,
                                      @NotNull final MethodView target,
                                      @NotNull final Reporter reporter) {
        return bind(handler, target, List.of(), reporter);
    }

    /**
     * Binds a handler to a target whose stack supplies nothing.
     *
     * @param handler  the handler to call; must not be {@code null}
     * @param target   the method being woven; must not be {@code null}
     * @param locals   the handler's {@code @Local} captures, one per annotated parameter; must not
     *                 be {@code null}
     * @param reporter where to report a handler that does not fit; must not be {@code null}
     * @return the binding, or {@code null} when the handler cannot be called, in which case the
     *         reason has been reported
     * @throws NullPointerException if any argument is {@code null}
     */
    @Nullable
    public static HandlerBinding bind(@NotNull final HandlerRef handler,
                                      @NotNull final MethodView target,
                                      @NotNull final List<LocalSpec> locals,
                                      @NotNull final Reporter reporter) {
        return bind(handler, target, locals, 0, reporter);
    }

    /**
     * Binds a handler to a target, checking every rule of the handler's shape.
     *
     * <p>Returns {@code null} for a handler that cannot be called, having reported exactly one
     * diagnostic saying why: {@code AW1040} for a parameter list that does not fit, {@code AW1005}
     * for an instance handler against a static target, {@code AW1070} or {@code AW1071} for a
     * callback that does not match what the target returns. The rules and their order are on the
     * class.
     *
     * <p>The binding this returns carries no captures. Resolving a {@code @Local} needs the site as
     * well as the method, so captures are resolved per site and attached with
     * {@link #withCaptures(List)}.
     *
     * @param handler     the handler to call; must not be {@code null}
     * @param target      the method being woven; must not be {@code null}
     * @param locals      the handler's {@code @Local} captures, one per annotated parameter, whose
     *                    positions must be the handler's last {@code locals.size()} parameters;
     *                    must not be {@code null}
     * @param skipLeading how many of the handler's leading parameters the stack already supplies
     *                    at the site, which is the arity of a replaced operation for a redirect and
     *                    one for an injection capturing a call's result
     * @param reporter    where to report a handler that does not fit; must not be {@code null}
     * @return the binding, or {@code null} when the handler cannot be called
     * @throws NullPointerException     if any reference argument is {@code null}
     * @throws IllegalArgumentException if {@code skipLeading} is negative, which is a defect in the
     *                                  injector that asked rather than a mistake in a weave
     */
    @Nullable
    public static HandlerBinding bind(@NotNull final HandlerRef handler,
                                      @NotNull final MethodView target,
                                      @NotNull final List<LocalSpec> locals,
                                      final int skipLeading,
                                      @NotNull final Reporter reporter) {
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(locals, "locals");
        Objects.requireNonNull(reporter, "reporter");
        if (skipLeading < 0) {
            throw new IllegalArgumentException(
                    "the stack cannot supply a negative number of parameters: " + skipLeading);
        }

        final MethodTypeDesc handlerType = handler.type();
        final MethodTypeDesc targetType = target.type();

        // The captures occupy the tail of the parameter list, so everything below reasons about the
        // part in front of them.
        final int visible = handlerType.parameterCount() - locals.size();
        if (!capturesOccupyTheTail(locals, visible, handlerType, handler, reporter)) {
            return null;
        }
        if (visible < skipLeading) {
            reporter.report(mismatch(handler, target,
                    "the operation it replaces supplies " + skipLeading
                            + " values, and the handler declares only " + visible
                            + " parameters before its captures"));
            return null;
        }

        // A trailing callback is not part of the prefix: it is supplied by the engine, not by the
        // target, so it is taken off the end before the shapes are compared.
        final CallbackKind callbackKind = trailingCallbackOf(handlerType, visible, skipLeading);
        final int declaredArguments =
                visible - skipLeading - (callbackKind == CallbackKind.NONE ? 0 : 1);

        if (!callbackFits(callbackKind, targetType, handler, target, reporter)) {
            return null;
        }
        if (declaredArguments > targetType.parameterCount()) {
            reporter.report(mismatch(handler, target,
                    "it takes " + declaredArguments + " target parameters and "
                            + target.describe() + " has only " + targetType.parameterCount()));
            return null;
        }

        // Slot zero is `this` in an instance method, so parameters begin at one.
        int slot = target.isStatic() ? 0 : 1;
        final List<Load> loads = new ArrayList<>(declaredArguments + 1);

        if (!handler.isStatic() && target.isStatic()) {
            // A merged instance handler is invoked on the target instance. A static target method
            // has no `this`, so there is nothing to invoke it against — and no amount of argument
            // arrangement produces one.
            reporter.report(Diagnostic.builder(DiagnosticCode.STATIC_WEAVE_INSTANCE_HANDLER)
                    .message(handler.describe() + " is an instance method, and "
                            + target.describe() + " is static, so there is no receiver to call it "
                            + "on")
                    .remedy("declare the handler static, or target an instance method. A static "
                            + "method has no `this` for a merged handler to be invoked against")
                    .build());
            return null;
        }

        for (int i = 0; i < targetType.parameterCount(); i++) {
            final ClassDesc targetParameter = targetType.parameterType(i);
            if (i < declaredArguments) {
                final ClassDesc handlerParameter = handlerType.parameterType(skipLeading + i);
                if (!handlerParameter.equals(targetParameter)) {
                    reporter.report(mismatch(handler, target,
                            "parameter " + (skipLeading + i) + " is "
                                    + handlerParameter.displayName()
                                    + " but the target's is " + targetParameter.displayName()));
                    return null;
                }
                loads.add(new Load(slot, TypeKind.from(targetParameter)));
            }
            // Advance even past parameters the handler does not take: the slots of the ones it
            // DOES take depend on the widths of everything before them.
            slot += TypeKind.from(targetParameter).slotSize();
        }
        return new HandlerBinding(loads, callbackKind, List.of(), !handler.isStatic());
    }

    /**
     * Reports which callback the handler declares, from the last parameter in front of its
     * captures.
     *
     * @param handlerType the handler's descriptor; must not be {@code null}
     * @param visible     one past the last parameter that is not a capture
     * @param skipLeading how many leading parameters the stack supplies
     * @return the callback kind, or {@link CallbackKind#NONE} when there is no parameter left to
     *         look at or it is neither callback type
     */
    @Contract(pure = true)
    @NotNull
    private static CallbackKind trailingCallbackOf(@NotNull final MethodTypeDesc handlerType,
                                                   final int visible,
                                                   final int skipLeading) {
        if (visible <= skipLeading) {
            return CallbackKind.NONE;
        }
        // The LAST parameter of the visible part, not the last parameter of the method. With
        // captures present the callback is no longer last, and looking at the real tail finds an
        // @Local and concludes there is no callback at all.
        final ClassDesc last = handlerType.parameterType(visible - 1);
        if (CD_RETURNABLE_CALLBACK.equals(last)) {
            return CallbackKind.RETURNABLE;
        }
        return CD_CALLBACK.equals(last) ? CallbackKind.PLAIN : CallbackKind.NONE;
    }

    /**
     * Checks that every capture sits in the tail of the parameter list, reporting {@code AW1040}
     * for the first one that does not.
     *
     * @param locals      the captures to check; must not be {@code null}
     * @param visible     the first position a capture may occupy
     * @param handlerType the handler's descriptor; must not be {@code null}
     * @param handler     the handler, for the diagnostic; must not be {@code null}
     * @param reporter    where to report; must not be {@code null}
     * @return {@code true} when every capture is in the tail
     */
    private static boolean capturesOccupyTheTail(@NotNull final List<LocalSpec> locals,
                                                 final int visible,
                                                 @NotNull final MethodTypeDesc handlerType,
                                                 @NotNull final HandlerRef handler,
                                                 @NotNull final Reporter reporter) {
        for (final LocalSpec local : locals) {
            if (local.parameter() >= visible && local.parameter() < handlerType.parameterCount()) {
                continue;
            }
            reporter.report(Diagnostic.builder(DiagnosticCode.HANDLER_PARAMETERS_NOT_PREFIX)
                    .message(handler.describe() + " declares @Local on parameter "
                            + local.parameter() + ", which is not one of its last "
                            + locals.size() + " parameters")
                    .detail("handler: " + handlerType.displayDescriptor())
                    .remedy("a handler's parameters are, in order: the target's argument prefix, "
                            + "then an optional Callback, then the @Local captures. Move the "
                            + "captured parameters to the end")
                    .build());
            return false;
        }
        return true;
    }

    /**
     * Checks the declared callback against what the target returns.
     *
     * <p>The two failures are opposites and each has its own code, because the remedy differs:
     * {@code AW1070} for cancelling a value-returning method with nothing to return, and
     * {@code AW1071} for offering a value to a method that returns none.
     *
     * @param kind       the callback the handler declares; must not be {@code null}
     * @param targetType the target's descriptor; must not be {@code null}
     * @param handler    the handler, for the diagnostic; must not be {@code null}
     * @param target     the target, for the diagnostic; must not be {@code null}
     * @param reporter   where to report; must not be {@code null}
     * @return {@code true} when the callback fits, or when there is none
     */
    private static boolean callbackFits(@NotNull final CallbackKind kind,
                                        @NotNull final MethodTypeDesc targetType,
                                        @NotNull final HandlerRef handler,
                                        @NotNull final MethodView target,
                                        @NotNull final Reporter reporter) {
        final boolean returns = !ConstantDescs.CD_void.equals(targetType.returnType());
        if (kind == CallbackKind.PLAIN && returns) {
            reporter.report(Diagnostic.builder(DiagnosticCode.CANCEL_ON_NON_VOID_TARGET)
                    .message(handler.describe() + " takes a plain Callback, but "
                            + target.describe() + " returns "
                            + targetType.returnType().displayName())
                    .remedy("declare ReturnableCallback<" + targetType.returnType().displayName()
                            + "> instead — cancelling a value-returning method without a value "
                            + "would leave it with nothing to return")
                    .build());
            return false;
        }
        if (kind == CallbackKind.RETURNABLE && !returns) {
            reporter.report(Diagnostic.builder(DiagnosticCode.CALLBACK_TYPE_MISMATCH)
                    .message(handler.describe() + " takes a ReturnableCallback, but "
                            + target.describe() + " returns void")
                    .remedy("declare a plain Callback — there is no value for a void method to "
                            + "return instead")
                    .build());
            return false;
        }
        return true;
    }

    /**
     * Pushes the target instance, for a handler that is not static.
     *
     * <p>Emits {@code aload 0} — the {@code this} of the method being woven — and nothing at all
     * when {@link #receiver()} is {@code false}. It goes first, underneath every argument, exactly
     * as for any other instance call.
     *
     * @param builder the code being written; must not be {@code null}
     * @throws NullPointerException if {@code builder} is {@code null}
     */
    public void emitReceiver(@NotNull final CodeBuilder builder) {
        Objects.requireNonNull(builder, "builder");
        if (this.receiver) {
            builder.aload(0);
        }
    }

    /**
     * Pushes the target parameters the handler claims, in order.
     *
     * <p>One load per entry of {@link #loads()}, each from the slot and of the kind the entry
     * records. Nothing else is pushed: the receiver, the callback and the captures are separate
     * steps, and a handler that claims no target parameters emits nothing here.
     *
     * @param builder the code being written; must not be {@code null}
     * @throws NullPointerException if {@code builder} is {@code null}
     */
    public void emitArguments(@NotNull final CodeBuilder builder) {
        Objects.requireNonNull(builder, "builder");
        for (final Load load : this.loads) {
            builder.loadLocal(load.kind(), load.slot());
        }
    }

    /**
     * Pushes the captured locals and returns the write-backs the caller still owes.
     *
     * <p>A capture that only reads is one load. A capture the handler may assign to is a carrier
     * object: the carrier is constructed around the variable's current value, one reference is left
     * on the stack as the handler's argument and a second is parked in a scratch local, because
     * after the call there is no other way back to the object that holds the new value.
     *
     * <p>The returned list is what {@link #emitWriteBacks(CodeBuilder, List)} needs, and it is
     * empty when no capture is mutable. A caller that discards it emits a handler call whose
     * assignments to captured variables are never copied back, and the target carries on with its
     * old values.
     *
     * <p>Called after the callback has been pushed and before the {@code invoke}.
     *
     * @param builder the code being written; must not be {@code null}
     * @return the pending write-backs, in the order the mutable captures were pushed
     * @throws NullPointerException if {@code builder} is {@code null}
     */
    @NotNull
    public List<WriteBack> emitCaptures(@NotNull final CodeBuilder builder) {
        Objects.requireNonNull(builder, "builder");
        final List<WriteBack> pending = new ArrayList<>();
        for (final Load capture : this.captures) {
            if (!capture.isMutable()) {
                builder.loadLocal(capture.kind(), capture.slot());
                continue;
            }
            final ClassDesc ref = Objects.requireNonNull(capture.ref());
            final int scratch = builder.allocateLocal(TypeKind.REFERENCE);
            builder.new_(ref)
                    .dup()
                    .loadLocal(capture.kind(), capture.slot())
                    .invokespecial(ref, ConstantDescs.INIT_NAME,
                            MethodTypeDesc.of(ConstantDescs.CD_void, capture.carried()))
                    .dup()
                    .astore(scratch);
            pending.add(new WriteBack(scratch, capture));
        }
        return List.copyOf(pending);
    }

    /**
     * Copies each carrier's value back into the target's local variable.
     *
     * <p>Emitted immediately after the handler call and before anything else — before a
     * cancellation branch in particular, since a handler that both cancels and writes a local
     * expects both to have happened, and one of the paths out of a cancellation does not come back
     * through this point.
     *
     * <p>Each write-back reads the carrier from its scratch slot, calls {@code get} on it, and
     * stores the result into the variable's own slot. A reference capture is cast first: the
     * generic carrier erases, so {@code get} returns {@code Object} while the slot's frame says
     * otherwise. Without the cast the class fails verification; with it, a handler that stored the
     * wrong type gets a {@link ClassCastException} at the store, naming the variable's real type,
     * rather than somewhere in the target's later code.
     *
     * @param builder the code being written; must not be {@code null}
     * @param pending what {@link #emitCaptures(CodeBuilder)} returned; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}, or if a write-back names a
     *                              capture with no carrier, which {@link #emitCaptures(CodeBuilder)}
     *                              never produces
     */
    public static void emitWriteBacks(@NotNull final CodeBuilder builder,
                                      @NotNull @Unmodifiable final List<WriteBack> pending) {
        Objects.requireNonNull(builder, "builder");
        Objects.requireNonNull(pending, "pending");
        for (final WriteBack back : pending) {
            final Load capture = back.capture();
            final ClassDesc ref = Objects.requireNonNull(capture.ref());
            builder.aload(back.slot())
                    .invokevirtual(ref, "get", MethodTypeDesc.of(capture.carried()));
            if (capture.kind() == TypeKind.REFERENCE && capture.type() != null) {
                // The generic carrier erases, so `get` returns Object and the slot's frame says
                // otherwise. Without this the class fails verification; with it, a handler that
                // wrote the wrong type gets a ClassCastException here — at the store, naming the
                // variable's real type — rather than somewhere in the target's later code.
                builder.checkcast(capture.type());
            }
            builder.storeLocal(capture.kind(), capture.slot());
        }
    }

    /**
     * Returns a copy of this binding carrying the given captures.
     *
     * <p>{@link #bind(HandlerRef, MethodView, List, int, Reporter)} produces a binding with none,
     * because which slot a {@code @Local} resolves to depends on the injection site rather than on
     * the method alone. The resolved captures are attached here, once per site, so that two sites
     * of one declaration can capture different slots while sharing everything else.
     *
     * @param resolved the captures, in handler parameter order; must not be {@code null}
     * @return a new binding differing only in its captures
     * @throws NullPointerException if {@code resolved} is {@code null} or holds a {@code null}
     *                              element
     */
    @Contract(value = "_ -> new", pure = true)
    @NotNull
    public HandlerBinding withCaptures(@NotNull @Unmodifiable final List<Load> resolved) {
        return new HandlerBinding(this.loads, this.callbackKind,
                Objects.requireNonNull(resolved, "resolved"), this.receiver);
    }

    /**
     * Returns how many of the target's parameters the handler claims.
     *
     * <p>The size of {@link #loads()}, which counts neither the callback, nor the captures, nor the
     * {@code skipLeading} values the stack already supplied, and is therefore smaller than the
     * handler's parameter count whenever any of those is present. The receiver is not part of the
     * comparison either way: a JVM method descriptor never counts {@code this}, so
     * {@link #receiver()} affects neither {@link #loads()} nor the handler's own parameter count.
     *
     * @return the number of target parameters this binding pushes
     */
    @Contract(pure = true)
    public int arity() {
        return this.loads.size();
    }

    /**
     * Builds the diagnostic every shape mismatch reports.
     *
     * <p>One code for the whole family, {@code AW1040}, with the specific reason as a detail and
     * both descriptors printed underneath it: what distinguishes these failures is a signature, and
     * a reader comparing two descriptors line by line needs them next to each other.
     *
     * @param handler the handler that does not fit; must not be {@code null}
     * @param target  the method it was bound against; must not be {@code null}
     * @param because the specific reason, as a detail line; must not be {@code null}
     * @return the diagnostic to report
     */
    @Contract(pure = true)
    @NotNull
    private static Diagnostic mismatch(@NotNull final HandlerRef handler,
                                       @NotNull final MethodView target,
                                       @NotNull final String because) {
        return Diagnostic.builder(DiagnosticCode.HANDLER_PARAMETERS_NOT_PREFIX)
                .message(handler.describe() + " does not fit " + target.describe())
                .detail(because)
                .detail("handler: " + handler.type().displayDescriptor())
                .detail("target:  " + target.type().displayDescriptor())
                .remedy("a handler's parameters must be a PREFIX of the target's — take the first "
                        + "n, in order, or take none. A parameter has no identity in a compiled "
                        + "method beyond its position, so there is nothing to name a subset with")
                .build();
    }

    /**
     * One value to push, named by the local variable slot it lives in.
     *
     * <p>Used for two things that emit differently. A load with no carrier is pushed as it is,
     * which is what a claimed target parameter and a read-only capture both need. A load with a
     * carrier is a {@code @Local(mutable = true)} capture: the value is wrapped so that the handler
     * can assign to it, and copied back afterwards.
     *
     * @param slot the local variable slot, counting {@code this} as slot zero and a {@code long} or
     *             {@code double} as two slots; never negative
     * @param kind what to load, which decides the opcode and the width
     * @param ref  the carrier class to wrap the value in, or {@code null} to push it by value
     * @param type the reference type to cast the carrier's contents back to before storing, or
     *             {@code null} when no cast is needed
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public record Load(int slot, @NotNull TypeKind kind, @Nullable ClassDesc ref,
                       @Nullable ClassDesc type) {

        /**
         * Checks the kind and the slot.
         *
         * @throws NullPointerException     if {@code kind} is {@code null}
         * @throws IllegalArgumentException if {@code slot} is negative
         */
        public Load {
            Objects.requireNonNull(kind, "kind");
            if (slot < 0) {
                throw new IllegalArgumentException("a local slot cannot be negative: " + slot);
            }
        }

        /**
         * Creates a load that pushes the slot's value as it is.
         *
         * @param slot the local variable slot; must not be negative
         * @param kind what to load; must not be {@code null}
         * @throws NullPointerException     if {@code kind} is {@code null}
         * @throws IllegalArgumentException if {@code slot} is negative
         */
        public Load(final int slot, @NotNull final TypeKind kind) {
            this(slot, kind, null, null);
        }

        /**
         * Reports whether this load wraps its value in a carrier the handler can assign to.
         *
         * @return {@code true} when a carrier class was given
         */
        @Contract(pure = true)
        public boolean isMutable() {
            return this.ref != null;
        }

        /**
         * Returns the type the carrier holds.
         *
         * <p>This is the parameter of the carrier's constructor and the return type of its
         * {@code get}: the primitive itself for a primitive carrier, and {@link Object} for a
         * reference, since the generic carrier erases. It is what the descriptors of both calls are
         * built from, which is why a reference capture needs a cast on the way back out.
         *
         * @return the carried type
         * @throws IllegalStateException if this load has no carrier, which carries nothing to name
         */
        @Contract(pure = true)
        @NotNull
        public ClassDesc carried() {
            if (this.ref == null) {
                throw new IllegalStateException("a by-value load carries nothing");
            }
            return this.kind == TypeKind.REFERENCE
                    ? ConstantDescs.CD_Object
                    : this.kind.upperBound();
        }
    }

    /**
     * A carrier parked in a scratch slot, waiting to be read back into the variable it came from.
     *
     * <p>Produced by {@link HandlerBinding#emitCaptures(CodeBuilder)} and consumed by
     * {@link HandlerBinding#emitWriteBacks(CodeBuilder, List)}. It exists because the two halves
     * are separated by the handler call: the carrier has to be reachable after the call, and a
     * stack that has just been consumed by an {@code invoke} is not where it can wait.
     *
     * @param slot    the scratch local holding the carrier
     * @param capture the capture it was built for, which names the variable to store into
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public record WriteBack(int slot, @NotNull Load capture) {

        /**
         * Checks that the capture is present.
         *
         * @throws NullPointerException if {@code capture} is {@code null}
         */
        public WriteBack {
            Objects.requireNonNull(capture, "capture");
        }
    }
}
