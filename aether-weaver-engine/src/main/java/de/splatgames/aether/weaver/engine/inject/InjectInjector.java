package de.splatgames.aether.weaver.engine.inject;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import de.splatgames.aether.weaver.api.model.HandlerRef;
import de.splatgames.aether.weaver.api.model.InjectorKind;
import de.splatgames.aether.weaver.api.model.InjectorSpec;
import de.splatgames.aether.weaver.api.spi.HandlerBinding;
import de.splatgames.aether.weaver.api.spi.InjectionContext;
import de.splatgames.aether.weaver.api.spi.MethodView;
import de.splatgames.aether.weaver.api.spi.CodeView;
import de.splatgames.aether.weaver.api.spi.Injector;
import de.splatgames.aether.weaver.api.spi.Injector.Disposition;
import de.splatgames.aether.weaver.api.spi.Injector.Emitter;
import de.splatgames.aether.weaver.api.spi.PlanEntryView;
import de.splatgames.aether.weaver.api.spi.Reporter;
import de.splatgames.aether.weaver.api.spi.TargetView;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.TypeKind;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.Opcode;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Emits a call to a handler beside the instructions a declaration matched, leaving those
 * instructions in place.
 *
 * <p>This is the injector that adds rather than substitutes: this injector answers {@code KEEP} for
 * every element it is offered, and the handler call is appended in front of it. A co-located
 * {@code @Redirect} on the same element can still remove it — {@code KEEP} from one emitter does
 * not outvote a {@code REPLACE} from another. Where the handler declares a
 * callback, the emission is the longer one that also writes the cancellation branch; where it does
 * not, the call is an ordinary invocation with its arguments pushed from the target's locals.
 *
 * <p>Two decisions are made here that the rest of the pipeline cannot make. Whether an instance
 * handler is legal depends on the weave rather than on the declaration, so it is checked against the
 * target. Whether the position leaves a value on the stack for a {@code @Result} parameter depends
 * on the instruction that precedes the position, so it is read out of the body.
 *
 * <h2>Thread safety</h2>
 *
 * <p>Stateless, and so is every emitter it returns. The element counter an emission is driven by
 * belongs to the caller; for {@link #codeTransform(HandlerRef, Set)} and its overloads it is the
 * returned transform, which is therefore one per method body.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class InjectInjector implements Injector {

    /**
     * Creates the injector.
     *
     * <p>One is built per declaration by the plugin that registers this kind, which is affordable
     * because there is nothing to build.
     */
    public InjectInjector() {
        // Stateless.
    }

    /**
     * Returns the kind this injector answers for.
     *
     * @return {@link InjectorKind#INJECT}
     */
    @Contract(pure = true)
    @Override
    @NotNull
    public InjectorKind kind() {
        return InjectorKind.INJECT;
    }

    /**
     * Checks the two requirements that can be settled from the declaration and the target class.
     *
     * <p>An instance handler is refused as {@code AW1005} unless the class the call will name is the
     * target itself, which happens only for a weave that dissolves into its target and so makes the
     * handler one of the target's own methods. The test compares
     * {@code PlanEntryView.handlerOwner()} rather than the handler's declaring class, because those
     * two differ in exactly that case.
     *
     * <p>A handler that returns anything is refused as {@code AW1041}: the return value would have
     * nowhere to go, since the target's own instructions are still there and the call is emitted
     * beside them.
     *
     * <p>Both are reported in one call rather than the first stopping the second: an error reported
     * here abandons the declaration exactly once, however many problems it named, so a handler that
     * is wrong in both ways hears about both.
     *
     * <p>Nothing about parameters or captures is decided here. Those are questions about one target
     * method at one position, and the position is not known yet.
     *
     * @param entry    the declaration being checked; must not be {@code null}
     * @param target   the class being woven; must not be {@code null}
     * @param reporter where to report; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    @Override
    public void validate(@NotNull final PlanEntryView entry,
                         @NotNull final TargetView target,
                         @NotNull final Reporter reporter) {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(reporter, "reporter");

        final HandlerRef handler = entry.handler();
        // An instance handler is legal exactly when the weave dissolves into the target, which is
        // what makes the handler one of the target's own methods. That is a fact about the WEAVE, so
        // it cannot be read off the declaration — which is why this takes the entry.
        if (!handler.isStatic() && !target.type().equals(entry.handlerOwner())) {
            reporter.report(Diagnostic.builder(DiagnosticCode.STATIC_WEAVE_INSTANCE_HANDLER)
                    .message(handler.describe() + " must be static to be called from "
                            + target.binaryName())
                    .remedy("declare the handler static, or declare the weave "
                            + "@Weave(kind = Kind.INSTANCE) so that it is dissolved into its "
                            + "target — an instance handler is only callable once it IS a method "
                            + "of the class calling it")
                    .build());
        }
        if (!ConstantDescs.CD_void.equals(handler.type().returnType())) {
            reporter.report(Diagnostic.builder(DiagnosticCode.HANDLER_RETURN_TYPE_NOT_VOID)
                    .message(handler.describe() + " must return void")
                    .remedy("an @Inject handler influences the target through its Callback, not "
                            + "through a return value")
                    .build());
        }
        // Whether the handler's parameters fit the target, and whether its @Local captures resolve,
        // are questions about ONE target method at ONE position — so they are checked where the
        // method and the sites are known rather than here.
    }

    /**
     * Builds the emitter for this declaration in one method.
     *
     * <p>The body is read here, once, when the declaration captures a result — an ordinary
     * declaration without {@code @Result} never touches it. Either way, the emitter that comes out
     * of this is a lookup by element index. That split is forced by the emitter contract: an
     * emitter sees one element at a time and cannot look backwards, and deciding what a
     * {@code @Result} capture will receive requires looking at the instruction before the matched
     * position.
     *
     * <p>Returns {@link Emitter#NOTHING} when that inspection failed, after the failure has been
     * reported. Declining here rather than partway through a body is the only safe point, because
     * instructions already written cannot be withdrawn.
     *
     * @param context everything known about the method being woven; must not be {@code null}
     * @return the emitter, or {@link Emitter#NOTHING} when the declaration was refused
     * @throws NullPointerException if {@code context} is {@code null}
     */
    @Override
    @NotNull
    public Emitter emitter(@NotNull final InjectionContext context) {
        Objects.requireNonNull(context, "context");
        final Map<Integer, HandlerBinding> bindings = new java.util.LinkedHashMap<>();
        for (final int site : context.sites()) {
            bindings.put(site, context.argumentsAt(site));
        }
        final Map<Integer, TypeKind> captured =
                capturedKinds(context, context.entry().spec().capturesResult());
        if (captured == null) {
            return Emitter.NOTHING;
        }
        return emitterFor(context.entry().handler(), context.entry().handlerOwner(), bindings,
                captured, context.returnType(), context.entry().spec().id());
    }

    /**
     * Works out what a {@code @Result} parameter receives at each site.
     *
     * <p>A declaration that captures nothing answers with an empty map. So does one whose target
     * carries no body, which the built-in pipeline never offers — it reports {@code AW1023} for a
     * method with no code long before an injector is asked for an emitter — but an injector reached
     * through the SPI is handed a context and not a promise.
     *
     * <p>A site that does not follow a call, or follows one that returns {@code void}, is
     * {@code AW1104}, and one such site abandons the whole declaration rather than only that site.
     * The two cases carry different details — the first is a point that is not an
     * {@code INVOKE_AFTER} at all, the second a call with no result to observe — but a single
     * remedy string is built for both.
     *
     * @param context        the method being woven; must not be {@code null}
     * @param capturesResult whether the declaration asked for the preceding call's result
     * @return the captured kind per site, empty when nothing is captured, or {@code null} when a
     *         site was reported as unusable
     */
    @Nullable
    private static Map<Integer, TypeKind> capturedKinds(@NotNull final InjectionContext context,
                                                        final boolean capturesResult) {
        if (!capturesResult) {
            return Map.of();
        }
        final CodeView body = context.method().code().orElse(null);
        if (body == null) {
            return Map.of();
        }
        final List<CodeElement> elements = body.elements();
        final Map<Integer, TypeKind> captured = new java.util.LinkedHashMap<>();
        for (final int site : context.sites()) {
            final ClassDesc produced = producedBefore(elements, site);
            if (produced == null || ConstantDescs.CD_void.equals(produced)) {
                context.diagnostics().report(
                        Diagnostic.builder(DiagnosticCode.INVOKE_AFTER_VOID_CALL)
                                .message(context.entry().handler().describe()
                                        + " declares @Result, and the position it matched leaves "
                                        + "nothing on the stack")
                                .detail(produced == null
                                        ? "element " + site + " does not follow a call"
                                        : "the call returns void")
                                .remedy("@Result receives what the matched call produced, so it "
                                        + "belongs at INVOKE_AFTER of a call that returns "
                                        + "something. Drop the annotation to inject beside the "
                                        + "call instead, or point it at a call with a result")
                                .build());
                return null;
            }
            captured.put(site, TypeKind.from(produced));
        }
        return captured;
    }


    /**
     * Reports whether the handler's first parameter is satisfied by a value already on the stack.
     *
     * <p>One only for a declaration that captures the preceding call's result, and zero otherwise:
     * an injection reads nothing else off the stack, because the position it lands at is not one it
     * takes over.
     *
     * <p>A position that captures a result but follows a {@code void} call answers zero rather than
     * one. That is deliberate and it is not an approximation of the truth: the mismatch is
     * {@code AW1104}, which is reported from {@code emitter} and says what is wrong. Answering one
     * here would let the argument binding fail first, with a message about the handler's shape
     * rather than about the position it was pointed at.
     *
     * @param spec   the declaration being applied; must not be {@code null}
     * @param method the target method; must not be {@code null}
     * @param code   the target's body; must not be {@code null}
     * @param site   the matched element index
     * @return {@code 1} when a usable result precedes the site and the declaration captures it,
     *         {@code 0} otherwise
     * @throws NullPointerException if {@code spec}, {@code method} or {@code code} is {@code null}
     */
    @Contract(pure = true)
    @Override
    public int stackOperandsAt(@NotNull final InjectorSpec spec,
                               @NotNull final MethodView method,
                               @NotNull final CodeView code,
                               final int site) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(code, "code");
        if (!spec.capturesResult()) {
            return 0;
        }
        final ClassDesc produced = producedBefore(code.elements(), site);
        // A void call reports AW1104 from the emitter, which has somewhere to report to. Answering
        // 0 here keeps the binding from failing first with a less useful message about the shape.
        return produced == null || ConstantDescs.CD_void.equals(produced) ? 0 : 1;
    }

    /**
     * Returns a transform that calls a handler taking no arguments at each of the given sites.
     *
     * <p>The shortest of four overloads that narrow to the same emission. This one and the two below
     * it exist to drive a single transform without a weaving run around it, which is how the
     * engine's own tests exercise the emission; outside this class, only those tests call any of
     * the four, and the pipeline reaches this injector through {@link #emitter(InjectionContext)}
     * instead. Inside the class the four still call one another: this overload and the one below
     * it both narrow to the {@code (handler, sites, binding, returnType, id)} overload, which in
     * turn narrows to the map-based one.
     *
     * @param handler the handler to call; must not be {@code null}
     * @param sites   the element indices to inject at; must not be {@code null}
     * @return a transform for one method body
     * @throws NullPointerException if either argument is {@code null}
     */
    @Contract(pure = true)
    @NotNull
    public CodeTransform codeTransform(@NotNull final HandlerRef handler,
                                       @NotNull final Set<Integer> sites) {
        return codeTransform(handler, sites,
                new HandlerBinding(List.of(), HandlerBinding.CallbackKind.NONE),
                ConstantDescs.CD_void, "");
    }

    /**
     * Returns a transform that calls a handler with one binding at each of the given sites.
     *
     * <p>The target's return type is taken to be {@code void}, so a binding that declares a callback
     * emits a cancellation branch that returns without a value.
     *
     * @param handler the handler to call; must not be {@code null}
     * @param sites   the element indices to inject at; must not be {@code null}
     * @param binding how to push the handler's arguments, used at every site; must not be
     *                {@code null}
     * @return a transform for one method body
     * @throws NullPointerException if any argument is {@code null}
     */
    @Contract(pure = true)
    @NotNull
    public CodeTransform codeTransform(@NotNull final HandlerRef handler,
                                       @NotNull final Set<Integer> sites,
                                       @NotNull final HandlerBinding binding) {
        return codeTransform(handler, sites, binding, ConstantDescs.CD_void, "");
    }

    /**
     * Returns a transform that calls a handler with one binding, for a target with a known return
     * type.
     *
     * <p>Every site gets the same binding, which is what separates this from the map-based overload:
     * a real run binds per site, because what the stack holds can differ between two positions of one
     * declaration.
     *
     * @param handler    the handler to call; must not be {@code null}
     * @param sites      the element indices to inject at; must not be {@code null}
     * @param binding    how to push the handler's arguments, used at every site; must not be
     *                   {@code null}
     * @param returnType the target method's return type, which decides the cancelled path; must not
     *                   be {@code null}
     * @param id         the identifier the callback carries; must not be {@code null}
     * @return a transform for one method body
     * @throws NullPointerException if any argument is {@code null}
     */
    @Contract(pure = true)
    @NotNull
    public CodeTransform codeTransform(@NotNull final HandlerRef handler,
                                       @NotNull final Set<Integer> sites,
                                       @NotNull final HandlerBinding binding,
                                       @NotNull final ClassDesc returnType,
                                       @NotNull final String id) {
        Objects.requireNonNull(sites, "sites");
        Objects.requireNonNull(binding, "binding");
        final Map<Integer, HandlerBinding> uniform = new LinkedHashMap<>();
        sites.forEach(site -> uniform.put(site, binding));
        return codeTransform(handler, handler.owner(), uniform, returnType, id);
    }

    /**
     * Returns a transform that calls a handler with a binding chosen per site.
     *
     * <p>The transform counts elements itself, which is what an emitter needs and what a
     * {@link CodeTransform} is not given. That counter is state, so one transform belongs to one
     * method body: reusing an instance across two methods continues the count into the second, so a
     * site index lower than the count carried over is never reached, while one at or above it fires
     * against whichever element of the second body ends up at that index instead of the one the
     * caller meant.
     *
     * <p>Nothing here captures a result. That is not a limitation of the emission but of the entry
     * point: which sites capture and what they capture is worked out in
     * {@link #emitter(InjectionContext)} from the whole body, and this overload is handed bindings
     * rather than a body.
     *
     * @param handler    the handler to call; must not be {@code null}
     * @param owner      the class the {@code invoke} names; must not be {@code null}
     * @param bindings   the binding to use at each site, keyed by element index; must not be
     *                   {@code null}
     * @param returnType the target method's return type; must not be {@code null}
     * @param id         the identifier the callback carries; must not be {@code null}
     * @return a transform for one method body
     * @throws NullPointerException if any argument is {@code null}, or if {@code bindings} holds a
     *                              {@code null} key or value
     */
    @Contract(pure = true)
    @NotNull
    public CodeTransform codeTransform(@NotNull final HandlerRef handler,
                                       @NotNull final ClassDesc owner,
                                       @NotNull final Map<Integer, HandlerBinding> bindings,
                                       @NotNull final ClassDesc returnType,
                                       @NotNull final String id) {
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(bindings, "bindings");
        Objects.requireNonNull(returnType, "returnType");
        Objects.requireNonNull(id, "id");
        final Map<Integer, HandlerBinding> resolved = Map.copyOf(bindings);
        final Opcode opcode = opcodeFor(handler);

        return new CodeTransform() {

            /** The emission this transform drives, built once and stateless. */
            private final Emitter emitter =
                    // This entry point exists for tests that drive one transform
                    // directly; @Result travels through the SPI path only.
                    emitterFor(handler, owner, resolved, Map.of(), returnType, id);

            /** How many elements have been offered, which is the coordinate a site is named in. */
            private int index;

            /**
             * Offers one element to the emission and writes it unless the emission replaced it.
             *
             * @param builder the code being written
             * @param element the element being transformed
             */
            @Override
            public void accept(final java.lang.classfile.CodeBuilder builder,
                               final CodeElement element) {
                if (this.emitter.emitAt(builder, element, this.index++) == Disposition.KEEP) {
                    builder.accept(element);
                }
            }

            /**
             * Names the handler and the opcode, so a transform is identifiable while debugging.
             *
             * @return a description of this transform
             */
            @Override
            public String toString() {
                return "InjectInjector.codeTransform[" + opcode + ' ' + handler.name() + ']';
            }
        };
    }

    /**
     * Builds the emission both entry points share.
     *
     * <p>Everything it needs is copied into immutable maps first, so the returned emitter reads only
     * what it closed over and can be asked for elements in any order. An element with no binding is
     * kept and nothing is written, which is what makes it safe to offer this emitter every element of
     * the body rather than only the matched ones.
     *
     * <p>A captured result is duplicated on the stack and the duplicate is stored into a fresh local
     * before the handler's operands are pushed. What is left on the stack for the target to go on
     * using is the original — a {@code @Result} handler observes a call, it does not consume its
     * result — and the local is needed because the receiver of an instance handler has to be pushed
     * underneath the value, which cannot be arranged while the value is on top.
     *
     * @param handler    the handler to call; must not be {@code null}
     * @param owner      the class the {@code invoke} names; must not be {@code null}
     * @param bindings   the binding per site; must not be {@code null}
     * @param captured   the captured result kind per site, empty when nothing is captured; must not
     *                   be {@code null}
     * @param returnType the target method's return type; must not be {@code null}
     * @param id         the identifier the callback carries; must not be {@code null}
     * @return the emitter
     */
    @NotNull
    private static Emitter emitterFor(@NotNull final HandlerRef handler,
                                      @NotNull final ClassDesc owner,
                                      @NotNull final Map<Integer, HandlerBinding> bindings,
                                      @NotNull final Map<Integer, TypeKind> captured,
                                      @NotNull final ClassDesc returnType,
                                      @NotNull final String id) {
        final Map<Integer, HandlerBinding> resolved = Map.copyOf(bindings);
        final Map<Integer, TypeKind> results = Map.copyOf(captured);
        final Opcode opcode = opcodeFor(handler);
        return (builder, element, index) -> {
            final HandlerBinding binding = resolved.get(index);
            if (binding == null) {
                return Disposition.KEEP;
            }
            // The target still needs the value: a @Result handler observes the call, it does
            // not consume its result. So the copy the handler receives is a duplicate, parked in a
            // local until the receiver -- which has to sit underneath it -- has been pushed.
            final TypeKind result = results.get(index);
            final int copy;
            if (result == null) {
                copy = -1;
            } else {
                if (result.slotSize() == 2) {
                    builder.dup2();
                } else {
                    builder.dup();
                }
                copy = builder.allocateLocal(result);
                builder.storeLocal(result, copy);
            }
            if (binding.takesCallback()) {
                CallbackEmission.emit(builder, handler, owner, opcode, binding, result, copy,
                        returnType,
                        // The value is on the stack exactly where the target is
                        // about to return one, which the element itself says.
                        element instanceof final ReturnInstruction returning
                                && returning.typeKind() != TypeKind.VOID, id);
            } else {
                binding.emitReceiver(builder);
                if (copy >= 0) {
                    builder.loadLocal(result, copy);
                }
                binding.emitArguments(builder);
                final List<HandlerBinding.WriteBack> pending = binding.emitCaptures(builder);
                builder.invoke(opcode, owner, handler.name(), handler.type(), false);
                HandlerBinding.emitWriteBacks(builder, pending);
            }
            return Disposition.KEEP;
        };
    }

    /**
     * Reports what the instruction before a site left on the stack.
     *
     * <p>The walk backwards skips everything that is not an {@link Instruction} — labels, line
     * numbers and frames sit between a call and the position after it, and a matched site is an index
     * into all of the body's elements rather than into its instructions. The first real instruction
     * decides: an {@link InvokeInstruction} answers with its return type, and anything else answers
     * {@code null} — including an {@code invokedynamic} call site, which is a distinct kind of
     * instruction and is not recognized here as producing anything, so a site that follows one is
     * treated the same as a site that follows no call at all.
     *
     * <p>Only the immediately preceding instruction is considered. A value left further back is not
     * what {@code @Result} means, and reaching past one instruction would guess at a stack this does
     * not model.
     *
     * @param elements the body's elements; must not be {@code null}
     * @param site     the matched element index
     * @return the return type of the call immediately before the site, or {@code null} when the site
     *         does not follow one
     */
    @Contract(pure = true)
    @Nullable
    private static ClassDesc producedBefore(@NotNull final List<CodeElement> elements,
                                            final int site) {
        for (int index = site - 1; index >= 0; index--) {
            final CodeElement element = elements.get(index);
            if (element instanceof final InvokeInstruction invoke) {
                return invoke.typeSymbol().returnType();
            }
            if (element instanceof Instruction) {
                return null;
            }
        }
        return null;
    }

    /**
     * Chooses the invocation opcode for a handler.
     *
     * <p>Three answers for three cases, and the third is the one worth naming: a private instance
     * handler is invoked with {@code invokespecial} rather than {@code invokevirtual}. That case
     * arises for a weave that dissolves into its target, where the handler has become a private
     * method of the very class the call is emitted into.
     *
     * @param handler the handler to call; must not be {@code null}
     * @return the opcode the emitted call uses
     */
    @Contract(pure = true)
    @NotNull
    private static Opcode opcodeFor(@NotNull final HandlerRef handler) {
        if (handler.isStatic()) {
            return Opcode.INVOKESTATIC;
        }
        return handler.isPrivate() ? Opcode.INVOKESPECIAL : Opcode.INVOKEVIRTUAL;
    }

    /**
     * Reports that this injector changes no class member.
     *
     * <p>Stated rather than inherited, because an injection is exactly the case a reader might expect
     * to add something to the class. It does not: the handler already exists, and all that is written
     * is a call to it.
     *
     * @return {@code false}
     */
    @Contract(pure = true)
    @Override
    public boolean isStructural() {
        return false;
    }

    /**
     * Returns the injector's name.
     *
     * <p>Fixed, since two instances differ in nothing.
     *
     * @return {@code "InjectInjector"}
     */
    @Override
    @NotNull
    public String toString() {
        return "InjectInjector";
    }
}
