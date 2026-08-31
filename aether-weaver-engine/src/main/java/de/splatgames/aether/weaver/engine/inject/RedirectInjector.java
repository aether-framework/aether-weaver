package de.splatgames.aether.weaver.engine.inject;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import de.splatgames.aether.weaver.api.model.HandlerRef;
import de.splatgames.aether.weaver.api.model.InjectorKind;
import de.splatgames.aether.weaver.api.model.InjectorSpec;
import de.splatgames.aether.weaver.api.model.PointSpec;
import de.splatgames.aether.weaver.api.At;
import de.splatgames.aether.weaver.api.Point;
import de.splatgames.aether.weaver.api.spi.CodeView;
import de.splatgames.aether.weaver.api.spi.HandlerBinding;
import de.splatgames.aether.weaver.api.spi.InjectionContext;
import de.splatgames.aether.weaver.api.spi.Injector;
import de.splatgames.aether.weaver.api.spi.Injector.Disposition;
import de.splatgames.aether.weaver.api.spi.Injector.Emitter;
import de.splatgames.aether.weaver.api.spi.MethodView;
import de.splatgames.aether.weaver.api.spi.PlanEntryView;
import de.splatgames.aether.weaver.api.spi.Reporter;
import de.splatgames.aether.weaver.api.spi.TargetView;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.instruction.StackInstruction;
import java.lang.constant.ClassDesc;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Replaces a matched operation with a call to the handler, which never sees the original.
 *
 * <p>The site itself does most of the work. Whatever the operation was about to consume is already
 * on the operand stack, in the order the handler declares it, so replacing the operation with a
 * call to a static handler that begins with those same values needs no shuffling: the engine pushes
 * only what the handler declares beyond them. A handler dissolved into the target's own instance is
 * the exception — {@link #makeRoomForTheReceiver(CodeBuilder, List)} spills those operands to fresh
 * locals and reloads them once the receiver has been pushed beneath them. That is why
 * {@code RedirectedOperation} reports the receiver of an instance operation as an input, and why the
 * handler's parameters have to be that prefix rather than any subset of it.
 *
 * <p>A problem found once emission has begun abandons the whole declaration rather than the one
 * position it was found at: the emitter is built from all of this declaration's positions at once,
 * and {@link Emitter#NOTHING} is the only way back out.
 *
 * <p>Stateless, and one instance serves one declaration against one class.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see WrapInjector
 */
public final class RedirectInjector implements Injector {

    /** The built-in points that name an operation rather than a position. */
    private static final Set<String> REDIRECTABLE =
            Set.of(Point.INVOKE.name(), Point.FIELD.name(), Point.NEW.name());

    /** Creates an injector. */
    public RedirectInjector() {
        // Stateless.
    }

    /**
     * Returns the kind this injector answers for.
     *
     * @return {@link InjectorKind#REDIRECT}
     */
    @Contract(pure = true)
    @Override
    @NotNull
    public InjectorKind kind() {
        return InjectorKind.REDIRECT;
    }

    /**
     * Checks what the declaration asks for against the class it is about to be woven into.
     *
     * <p>Three refusals, each reported at once so that a declaration wrong in several ways costs
     * one build rather than three.
     *
     * <ul>
     *   <li>{@code AW1005} for a handler that is neither static nor a member of the target itself.
     *   <li>{@code AW1061} for a built-in point naming a position rather than an operation.
     *   <li>{@code AW1102} for any shift.
     * </ul>
     *
     * @param entry    the declaration being checked; must not be {@code null}
     * @param target   the class being woven; must not be {@code null}
     * @param reporter where to report; an error reported here abandons the declaration; must not be
     *                 {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    @Override
    public void validate(@NotNull final PlanEntryView entry,
                         @NotNull final TargetView target,
                         @NotNull final Reporter reporter) {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(reporter, "reporter");

        final InjectorSpec spec = entry.spec();
        final HandlerRef handler = entry.handler();
        if (!handler.isStatic() && !target.type().equals(entry.handlerOwner())) {
            reporter.report(Diagnostic.builder(DiagnosticCode.STATIC_WEAVE_INSTANCE_HANDLER)
                    .message(handler.describe() + " must be static to replace an operation in "
                            + target.binaryName())
                    .remedy("declare the handler static, or declare the weave "
                            + "@Weave(kind = Kind.INSTANCE) so that it is dissolved into its "
                            + "target — an instance handler is only callable once it IS a method "
                            + "of the class calling it")
                    .build());
        }
        for (final PointSpec point : spec.points()) {
            if (isBuiltIn(point) && !REDIRECTABLE.contains(point.point())) {
                reporter.report(Diagnostic.builder(DiagnosticCode.OPERATION_TARGET_UNSUPPORTED)
                        .message(handler.describe() + " redirects at " + point.point()
                                + ", which names a position rather than an operation")
                        .detail("a redirect can replace: " + String.join(", ", REDIRECTABLE))
                        .remedy("there is nothing at a bare position for a handler to stand in "
                                + "for — @Inject is what adds code there. A contributed point is "
                                + "not checked here and is judged by the shape it resolves to")
                        .build());
            }
            if (point.shift() != At.Shift.NONE) {
                reporter.report(Diagnostic.builder(DiagnosticCode.SHIFT_NOT_SUPPORTED)
                        .message(handler.describe() + " shifts its injection point by "
                                + point.shift() + ", which a redirect cannot do")
                        .remedy("a redirect replaces the operation it matches, so there is nothing "
                                + "for a shift to mean — a shifted position names a neighbouring "
                                + "instruction that the handler's signature does not describe")
                        .build());
            }
        }
    }

    /**
     * Reports whether a point is one of the framework's own.
     *
     * @param point the point to classify; must not be {@code null}
     * @return {@code true} when its identifier carries no namespace
     */
    @Contract(pure = true)
    private static boolean isBuiltIn(@NotNull final PointSpec point) {
        return point.point().indexOf(':') < 0;
    }

    /**
     * Returns how many of the handler's leading parameters the site already supplies.
     *
     * <p>A position holding no operation answers zero rather than refusing anything. The zero then
     * feeds {@link HandlerBinding#bind} with no operands claimed, so the handler's whole visible
     * parameter list is compared, position by position, against the target's own declared
     * parameters for exact {@link ClassDesc} equality, reported as {@code AW1040} on a mismatch.
     * Nothing in that comparison special-cases a receiver: an instance redirect whose handler
     * begins with the operation's own receiver type has that parameter compared against the
     * target's first real argument instead, and the outcome turns on whether those two types
     * happen to be equal; a redirect of a static call or a field read has no receiver to begin
     * with, so the same comparison is the whole story there too.
     * {@link #emitter(InjectionContext)} is reached, and {@code AW1061} named for this position,
     * only in the narrower case where that binding happens to succeed anyway.
     *
     * @param method the target method; must not be {@code null}
     * @param code   its body; must not be {@code null}
     * @param site   the matched element index
     * @return the operation's arity, or {@code 0} where the position holds no operation
     * @throws NullPointerException if {@code method} or {@code code} is {@code null}
     */
    @Contract(pure = true)
    @Override
    public int stackOperandsAt(@NotNull final MethodView method,
                               @NotNull final CodeView code,
                               final int site) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(code, "code");
        final RedirectedOperation operation = RedirectedOperation.at(code.elements(), site);
        return operation == null ? 0 : operation.arity();
    }

    /**
     * Works out what has to change at each of this declaration's positions, and returns an emitter
     * that applies it.
     *
     * <p>Everything that can be refused is refused here, before a single instruction is written,
     * because an emitter walking the body cannot take back what it has already appended. Two
     * refusals are possible per position: {@code AW1061} where the position holds no operation, and
     * {@code AW1040} where the handler does not begin with the operation's inputs. Either abandons
     * the whole declaration.
     *
     * <p>A call and a field access are replaced where they stand. An instantiation is a span, and
     * the plan for it is three entries rather than one: the {@code new} and its {@code dup} are
     * dropped, and the handler's call takes the place of the constructor call, since that is the
     * first point at which the arguments the handler receives exist. The binding used there is the
     * one the engine computed for the {@code new}, which is the index it was asked about.
     *
     * @param context the declaration, its positions and their bindings; must not be {@code null}
     * @return the emitter, or {@link Emitter#NOTHING} where the target has no body or a position
     *         was refused
     * @throws NullPointerException if {@code context} is {@code null}
     */
    @Contract(pure = true)
    @Override
    @NotNull
    public Emitter emitter(@NotNull final InjectionContext context) {
        Objects.requireNonNull(context, "context");

        final CodeView body = context.method().code().orElse(null);
        if (body == null) {
            return Emitter.NOTHING;
        }
        final List<CodeElement> elements = body.elements();
        final HandlerRef handler = context.entry().handler();

        final Map<Integer, Replacement> replacements = new LinkedHashMap<>();
        for (final int site : context.sites()) {
            final RedirectedOperation operation = RedirectedOperation.at(elements, site);
            if (operation == null) {
                context.diagnostics().report(
                        Diagnostic.builder(DiagnosticCode.OPERATION_TARGET_UNSUPPORTED)
                                .message(handler.describe() + " matched a position that is not an "
                                        + "operation a redirect can replace")
                                .detail("element " + site + ": " + elements.get(site))
                                .remedy("a redirect replaces a call, a field access or an "
                                        + "instantiation; @Inject is what adds code at an "
                                        + "arbitrary position")
                                .build());
                return Emitter.NOTHING;
            }
            if (!operation.isMatchedBy(handler.type())) {
                context.diagnostics().report(
                        Diagnostic.builder(DiagnosticCode.HANDLER_PARAMETERS_NOT_PREFIX)
                                .message(handler.describe() + " does not have the shape of "
                                        + operation.describe())
                                .detail("operation: " + operation.signature().displayDescriptor())
                                .detail("handler:   " + handler.type().displayDescriptor())
                                .remedy("a redirect handler begins with the operation's own "
                                        + "inputs, in order — the receiver first for an instance "
                                        + "operation — and returns what the operation returned. "
                                        + "The enclosing method's parameters may follow them")
                                .build());
                return Emitter.NOTHING;
            }
            if (operation.kind() != RedirectedOperation.Kind.INSTANTIATION) {
                replacements.put(site,
                        Replacement.call(context.argumentsAt(site), operation.inputs()));
                continue;
            }

            // An instantiation is a span. The `new` and the `dup` that follows it are scaffolding
            // for a reference the handler now produces itself, so both disappear; everything
            // between them and the constructor call computes the arguments and must stay, because
            // those are exactly the values the handler is about to receive.
            final int initializer = RedirectedOperation.initializerOf(elements, site);
            if (initializer < 0) {
                context.diagnostics().report(
                        Diagnostic.builder(DiagnosticCode.OPERATION_TARGET_UNSUPPORTED)
                                .message(handler.describe() + " redirects an instantiation whose "
                                        + "constructor call could not be found")
                                .remedy("this is a body shape the engine does not understand; "
                                        + "report it with the class file rather than working "
                                        + "around it")
                                .build());
                return Emitter.NOTHING;
            }
            replacements.put(site, Replacement.drop());
            if (isDup(elements, site + 1)) {
                replacements.put(site + 1, Replacement.drop());
            }
            replacements.put(initializer,
                    Replacement.call(context.argumentsAt(site), operation.inputs()));
        }
        return emitterFor(Map.copyOf(replacements), handler, context.entry().handlerOwner());
    }

    /**
     * Puts the target instance underneath operands that are already on the stack.
     *
     * <p>{@link HandlerBinding#emitReceiver(CodeBuilder)} cannot serve here: it pushes the receiver
     * on top of whatever is present, and a receiver has to be beneath the arguments. The operands
     * are therefore spilled into fresh locals from the top down, the receiver is pushed onto the
     * emptied stack, and the operands are reloaded in declaration order.
     *
     * @param builder  the code being written; must not be {@code null}
     * @param operands the operation's inputs, in stack order; must not be {@code null}
     */
    private static void makeRoomForTheReceiver(@NotNull final CodeBuilder builder,
                                               @NotNull final List<ClassDesc> operands) {
        final int[] slots = new int[operands.size()];
        for (int i = operands.size() - 1; i >= 0; i--) {
            final TypeKind kind = TypeKind.from(operands.get(i));
            slots[i] = builder.allocateLocal(kind);
            builder.storeLocal(kind, slots[i]);
        }
        builder.aload(0);
        for (int i = 0; i < operands.size(); i++) {
            builder.loadLocal(TypeKind.from(operands.get(i)), slots[i]);
        }
    }

    /**
     * Reports whether the element at an index is the {@code dup} that follows a {@code new}.
     *
     * <p>Asked rather than assumed, because a {@code new} whose result is discarded carries no
     * {@code dup} and dropping the next element regardless would remove an instruction that
     * belongs to the target.
     *
     * @param elements the body; must not be {@code null}
     * @param index    the element index to test
     * @return {@code true} when the index is inside the body and holds a {@code dup}
     */
    @Contract(pure = true)
    private static boolean isDup(@NotNull final List<CodeElement> elements, final int index) {
        return index < elements.size()
                && elements.get(index) instanceof StackInstruction stack
                && stack.opcode() == Opcode.DUP;
    }

    /**
     * Builds the emitter over a finished plan.
     *
     * <p>The opcode follows the handler's own modifiers: a private instance handler is reached with
     * {@code invokespecial}, since a private method has no virtual dispatch to go through, and any
     * other instance handler with {@code invokevirtual}.
     *
     * <p>Only what the handler declares beyond the operation's own inputs is pushed. The
     * write-backs {@link HandlerBinding#emitCaptures(CodeBuilder)} returns are not emitted, so a
     * mutable capture is read into the handler and not copied back into the target's slot.
     *
     * @param replacements what happens at each element index this declaration acts on; must not be
     *                     {@code null}
     * @param handler      the handler to call; must not be {@code null}
     * @param owner        the class declaring it; must not be {@code null}
     * @return the emitter, which keeps every element it holds no plan for
     */
    @Contract(pure = true)
    @NotNull
    private static Emitter emitterFor(@NotNull final Map<Integer, Replacement> replacements,
                                      @NotNull final HandlerRef handler,
                                      @NotNull final ClassDesc owner) {
        final Opcode opcode = handler.isStatic()
                ? Opcode.INVOKESTATIC
                : (handler.isPrivate() ? Opcode.INVOKESPECIAL : Opcode.INVOKEVIRTUAL);
        return (builder, element, index) -> {
            final Replacement replacement = replacements.get(index);
            if (replacement == null) {
                return Disposition.KEEP;
            }
            if (replacement.binding() == null) {
                // Scaffolding: the `new` and its `dup` simply stop existing.
                return Disposition.REPLACE;
            }
            final HandlerBinding binding = replacement.binding();
            if (binding.receiver()) {
                makeRoomForTheReceiver(builder, replacement.operands());
            }
            // The operation's own operands are already on the stack. Only what the handler
            // declares BEYOND them has to be pushed.
            binding.emitArguments(builder);
            binding.emitCaptures(builder);
            builder.invoke(opcode, owner, handler.name(), handler.type(), false);
            return Disposition.REPLACE;
        };
    }

    /**
     * Reports that this injector rewrites bodies rather than the shape of the class.
     *
     * @return {@code false}, always
     */
    @Contract(pure = true)
    @Override
    public boolean isStructural() {
        return false;
    }

    /**
     * Returns the injector's name.
     *
     * @return {@code "RedirectInjector"}, carrying no state because there is none
     */
    @Override
    @NotNull
    public String toString() {
        return "RedirectInjector";
    }

    /**
     * What becomes of one element of the body.
     *
     * <p>A {@code null} binding is the scaffolding case: the element is removed and nothing takes
     * its place. Anything else names the handler call that stands in for it.
     *
     * @param binding  the handler binding to emit, or {@code null} to drop the element
     * @param operands the operation's inputs in stack order, needed only to spill and reload them
     *                 for an instance handler
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private record Replacement(@Nullable HandlerBinding binding,
                               @NotNull List<ClassDesc> operands) {

        /**
         * Replaces an element with a call to the handler.
         *
         * @param binding  the binding computed for this declaration; must not be {@code null}
         * @param operands the operation's inputs in stack order; must not be {@code null}
         * @return the replacement
         * @throws NullPointerException if either argument is {@code null}
         */
        @Contract(value = "_, _ -> new", pure = true)
        @NotNull
        static Replacement call(@NotNull final HandlerBinding binding,
                                @NotNull final List<ClassDesc> operands) {
            return new Replacement(Objects.requireNonNull(binding, "binding"),
                    List.copyOf(Objects.requireNonNull(operands, "operands")));
        }

        /**
         * Removes an element without putting anything in its place.
         *
         * @return the replacement
         */
        @Contract(value = " -> new", pure = true)
        @NotNull
        static Replacement drop() {
            return new Replacement(null, List.of());
        }
    }
}
