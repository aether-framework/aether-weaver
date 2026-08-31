package de.splatgames.aether.weaver.engine.inject;

import de.splatgames.aether.weaver.api.At;
import de.splatgames.aether.weaver.api.Point;
import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import de.splatgames.aether.weaver.api.model.HandlerRef;
import de.splatgames.aether.weaver.api.model.InjectorKind;
import de.splatgames.aether.weaver.api.model.InjectorSpec;
import de.splatgames.aether.weaver.api.model.PointSpec;
import de.splatgames.aether.weaver.api.spi.CodeView;
import de.splatgames.aether.weaver.api.spi.InjectionContext;
import de.splatgames.aether.weaver.api.spi.Injector;
import de.splatgames.aether.weaver.api.spi.MethodView;
import de.splatgames.aether.weaver.api.spi.PlanEntryView;
import de.splatgames.aether.weaver.api.spi.Reporter;
import de.splatgames.aether.weaver.api.spi.TargetView;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.classfile.CodeElement;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.StackInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDesc;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.DynamicConstantDesc;
import java.lang.constant.MethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Replaces a matched operation with a call to a handler that is handed the operation itself.
 *
 * <p>The site's own operands are already on the stack and the handler declares exactly those, in
 * order, so the only value emission has to push is the handle to what was there before. That handle
 * is the {@code ldc} of a dynamic constant whose bootstrap is {@code OperationSupport.operation}.
 * The JVM resolves a dynamic constant once per constant-pool entry and caches the result, and
 * {@link #operationFor} builds an identical
 * {@code DynamicConstantDesc} for an identical operation and chain, which the constant-pool builder
 * folds into one entry — so every site sharing that entry hands the handler the same instance.
 *
 * <p>The constant is also what makes several wraps at one position nest instead of colliding. Two
 * calls cannot stand where one set of operands does, so only the first entry at a shared position
 * emits, and the handlers behind it are static arguments of its constant, innermost first. A
 * handler therefore reaches the target's own operation only when nothing is nested inside it.
 *
 * <p>Stateless, and one instance serves one declaration against one class.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see RedirectInjector
 */
public final class WrapInjector implements Injector {

    /** The class holding the bootstrap that builds the operation handed to a handler. */
    private static final ClassDesc CD_OPERATION_SUPPORT =
            ClassDesc.of("de.splatgames.aether.weaver.api.callback.OperationSupport");

    /**
     * The handler parameter that carries the operation, compared by descriptor because that is all
     * a {@link HandlerRef} records; a handler's type argument is erased and is never checked.
     */
    private static final ClassDesc CD_OPERATION =
            ClassDesc.of("de.splatgames.aether.weaver.api.callback.Operation");

    /** The first parameter of any bootstrap. */
    private static final ClassDesc CD_LOOKUP =
            ClassDesc.of("java.lang.invoke.MethodHandles$Lookup");

    /** The element type of the bootstrap's trailing chain parameter. */
    private static final ClassDesc CD_METHOD_HANDLE =
            ClassDesc.of("java.lang.invoke.MethodHandle");

    /**
     * The bootstrap the emitted constant names.
     *
     * <p>Its three leading parameters are the ones every dynamic constant supplies, followed by the
     * description and the chain. The chain parameter is an array and the method is variadic, so the
     * remaining static arguments are collected into it however many levels the nesting has.
     */
    private static final DirectMethodHandleDesc BOOTSTRAP = MethodHandleDesc.ofMethod(
            DirectMethodHandleDesc.Kind.STATIC, CD_OPERATION_SUPPORT, "operation",
            MethodTypeDesc.of(CD_OPERATION, CD_LOOKUP, ClassDesc.of("java.lang.String"),
                    ClassDesc.of("java.lang.Class"), ClassDesc.of("java.lang.String"),
                    CD_METHOD_HANDLE.arrayType()));

    /** The built-in points that name an operation rather than a position. */
    private static final Set<String> WRAPPABLE =
            Set.of(Point.INVOKE.name(), Point.FIELD.name(), Point.NEW.name());

    /** Creates an injector. */
    public WrapInjector() {
        // Stateless.
    }

    /**
     * Returns the kind this injector answers for.
     *
     * @return {@link InjectorKind#WRAP}
     */
    @Contract(pure = true)
    @Override
    @NotNull
    public InjectorKind kind() {
        return InjectorKind.WRAP;
    }

    /**
     * Checks the declaration's handler and points, reporting every fault it finds rather than the
     * first.
     *
     * <ul>
     *   <li>{@code AW1005} for a handler that is not static. Unlike a redirect there is no
     *       exemption for a handler declared by the target.
     *   <li>{@code AW1062} where the handler declares parameters and the last of them is not the
     *       operation. A handler that declares parameters and no operation at all trips this check
     *       as well as the next, and is reported under both codes.
     *   <li>{@code AW1063} where no parameter is the operation.
     *   <li>{@code AW1061} for a built-in point naming a position rather than an operation.
     *   <li>{@code AW1102} for any shift.
     * </ul>
     *
     * <p>The parameter checks look only at the handler's descriptor, so they hold whatever the
     * matched position turns out to be; whether the parameters ahead of the operation are the right
     * ones is settled against the operation itself during emission.
     *
     * @param entry    the declaration being checked; must not be {@code null}
     * @param target   the class being woven, named in the {@code AW1005} message; must not be
     *                 {@code null}
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
        if (!handler.isStatic()) {
            reporter.report(Diagnostic.builder(DiagnosticCode.STATIC_WEAVE_INSTANCE_HANDLER)
                    .message(handler.describe() + " must be static to wrap an operation in "
                            + target.binaryName())
                    .detail("a wrap may end up nested inside another weave's wrap, and an inner "
                            + "level is reached through Operation.call — which carries the "
                            + "operation's own arguments and no receiver")
                    .remedy("declare the handler static; what it needs from the operation is "
                            + "already in its parameters, and state it needs beyond that belongs "
                            + "in a static field of the weave")
                    .build());
        }
        if (!handler.type().parameterList().isEmpty()
                && !CD_OPERATION.equals(handler.type().parameterType(
                        handler.type().parameterCount() - 1))) {
            reporter.report(Diagnostic.builder(DiagnosticCode.WRAP_PARAMETERS_AFTER_OPERATION)
                    .message(handler.describe() + " declares parameters after its Operation")
                    .detail("handler: " + handler.type().displayDescriptor())
                    .remedy("the Operation must be last. A @Redirect handler may append the "
                            + "enclosing method's parameters, and a wrap handler may not: an inner "
                            + "level receives only what Operation.call carries, so such a handler "
                            + "would work as the outermost wrap and fail as a nested one")
                    .build());
        }
        if (!handler.type().parameterList().contains(CD_OPERATION)) {
            reporter.report(Diagnostic.builder(DiagnosticCode.WRAP_OPERATION_MISSING)
                    .message(handler.describe() + " declares no Operation parameter")
                    .detail("handler: " + handler.type().displayDescriptor())
                    .remedy("add a trailing Operation<R> parameter, where R is the operation's "
                            + "result type boxed — or use @Redirect, which replaces the operation "
                            + "instead of wrapping it and needs no handle to it")
                    .build());
        }
        for (final PointSpec point : spec.points()) {
            if (isBuiltIn(point) && !WRAPPABLE.contains(point.point())) {
                reporter.report(Diagnostic.builder(DiagnosticCode.OPERATION_TARGET_UNSUPPORTED)
                        .message(handler.describe() + " wraps at " + point.point()
                                + ", which names a position rather than an operation")
                        .detail("a wrap can take: " + String.join(", ", WRAPPABLE))
                        .remedy("there is nothing at a bare position for a handler to wrap — "
                                + "@Inject is what adds code there. A contributed point is not "
                                + "checked here and is judged by the shape it resolves to")
                        .build());
            }
            if (point.shift() != At.Shift.NONE) {
                reporter.report(Diagnostic.builder(DiagnosticCode.SHIFT_NOT_SUPPORTED)
                        .message(handler.describe() + " shifts its injection point by "
                                + point.shift() + ", which a wrap cannot do")
                        .remedy("a wrap takes over the operation it matches, so there is nothing "
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
     * <p>One more than a redirect would answer: the trailing operation parameter is pushed at the
     * site as well. For a handler of the shape this injector accepts that accounts for the whole
     * parameter list, so nothing is bound from the target's locals.
     *
     * @param method the target method; must not be {@code null}
     * @param code   its body; must not be {@code null}
     * @param site   the matched element index
     * @return the operation's arity plus one, or {@code 0} where the position holds no operation
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
        return operation == null ? 0 : operation.arity() + 1;
    }

    /**
     * Works out what has to change at each of this declaration's positions, and returns an emitter
     * that applies it.
     *
     * <p>Refusals happen here, before anything is written: {@code AW1061} where a position holds no
     * operation, and {@code AW1040} where the handler is not the operation's inputs followed by one
     * operation parameter. Either abandons the whole declaration.
     *
     * <p>A position shared with other wrap declarations is emitted at only by the first of them,
     * compared by identity against this declaration's own entry. The others record no replacement
     * and write nothing, because their handlers are already static arguments of the constant the
     * first one emits. A context that reports no sharing at all leaves every declaration emitting,
     * which is the same behaviour as being alone at the position.
     *
     * <p>A call and a field access are replaced where they stand. An instantiation is a span: the
     * {@code new} and its {@code dup} are dropped and the handler's call takes the place of the
     * constructor call, which is the first point at which the arguments exist.
     *
     * @param context the declaration, its positions and what else matched them; must not be
     *                {@code null}
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
                                        + "operation a wrap can take over")
                                .detail("element " + site + ": " + elements.get(site))
                                .remedy("a wrap takes over a call, a field access or an "
                                        + "instantiation; @Inject is what adds code at an "
                                        + "arbitrary position")
                                .build());
                return Emitter.NOTHING;
            }
            if (!wraps(operation, handler)) {
                context.diagnostics().report(
                        Diagnostic.builder(DiagnosticCode.HANDLER_PARAMETERS_NOT_PREFIX)
                                .message(handler.describe() + " does not have the shape of "
                                        + operation.describe())
                                .detail("operation: " + operation.signature().displayDescriptor())
                                .detail("handler:   " + handler.type().displayDescriptor())
                                .remedy("a wrap handler begins with the operation's own inputs, in "
                                        + "order — the receiver first for an instance operation — "
                                        + "then one Operation parameter, and returns what the "
                                        + "operation returned")
                                .build());
                return Emitter.NOTHING;
            }

            final List<PlanEntryView> chain = wrapsAt(context, site);
            if (!chain.isEmpty() && chain.getFirst() != context.entry()) {
                // A nested level. Its handler is already in the outermost entry's constant, and
                // emitting here would put a second call at a site that holds one.
                continue;
            }
            final ConstantDesc constant = operationFor(operation, chain);
            if (operation.kind() != RedirectedOperation.Kind.INSTANTIATION) {
                replacements.put(site, Replacement.call(constant));
                continue;
            }

            // An instantiation is a span, exactly as it is for a redirect: the `new` and its `dup`
            // produce a reference the handler now yields itself, and the arguments do not exist
            // until the constructor call, so that is where the handler goes.
            final int initializer = RedirectedOperation.initializerOf(elements, site);
            if (initializer < 0) {
                context.diagnostics().report(
                        Diagnostic.builder(DiagnosticCode.OPERATION_TARGET_UNSUPPORTED)
                                .message(handler.describe() + " wraps an instantiation whose "
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
            replacements.put(initializer, Replacement.call(constant));
        }
        return emitterFor(Map.copyOf(replacements), handler, context.entry().handlerOwner());
    }

    /**
     * Lists the wrap declarations that matched one position, outermost first.
     *
     * <p>Declarations of other kinds are filtered out: they do not nest, and a redirect sharing the
     * position would otherwise decide which wrap believes itself outermost.
     *
     * @param context the injection context; must not be {@code null}
     * @param site    the element index to ask about
     * @return the wrap entries at that position, in the order the context reports them
     */
    @Contract(pure = true)
    @NotNull
    private static List<PlanEntryView> wrapsAt(@NotNull final InjectionContext context,
                                               final int site) {
        final List<PlanEntryView> wraps = new ArrayList<>();
        for (final PlanEntryView entry : context.entriesAt(site)) {
            if (InjectorKind.WRAP.equals(entry.spec().kind())) {
                wraps.add(entry);
            }
        }
        return List.copyOf(wraps);
    }

    /**
     * Builds the dynamic constant the outermost handler receives.
     *
     * <p>The static arguments are the description, the operation's own handle, and then one handle
     * per nested handler counted down from the innermost. The chain stops before index zero: that
     * entry is the one whose call stands at the site, so it is the caller of the operation rather
     * than a level inside it.
     *
     * @param operation the operation being taken over; must not be {@code null}
     * @param chain     the wrap entries at this position, outermost first, or empty where the
     *                  context reports no sharing; must not be {@code null}
     * @return the constant to load at the site
     */
    @Contract(pure = true)
    @NotNull
    private static ConstantDesc operationFor(@NotNull final RedirectedOperation operation,
                                             @NotNull final List<PlanEntryView> chain) {
        final List<ConstantDesc> arguments = new ArrayList<>();
        arguments.add(operation.describe());
        arguments.add(operation.handle());
        for (int level = chain.size() - 1; level >= 1; level--) {
            final PlanEntryView inner = chain.get(level);
            arguments.add(MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.STATIC,
                    inner.handlerOwner(), inner.handler().name(), inner.handler().type()));
        }
        return DynamicConstantDesc.ofNamed(BOOTSTRAP, "operation", CD_OPERATION,
                arguments.toArray(new ConstantDesc[0]));
    }

    /**
     * Reports whether a handler has the shape of a wrap over one operation.
     *
     * <p>Stricter than a redirect on both counts: the arity is exact, so nothing may follow the
     * operation parameter and nothing may be omitted from in front of it, and the parameter in that
     * last position has to be the operation itself.
     *
     * @param operation the operation at the position; must not be {@code null}
     * @param handler   the handler; must not be {@code null}
     * @return {@code true} when the handler is the operation's inputs followed by one operation
     *         parameter, and returns something the operation's result position accepts
     */
    @Contract(pure = true)
    private static boolean wraps(@NotNull final RedirectedOperation operation,
                                 @NotNull final HandlerRef handler) {
        final MethodTypeDesc type = handler.type();
        if (type.parameterCount() != operation.arity() + 1
                || !CD_OPERATION.equals(type.parameterType(type.parameterCount() - 1))) {
            return false;
        }
        return operation.isMatchedBy(type);
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
     * <p>Two instructions per replaced operation: the constant, and an {@code invokestatic} that is
     * not conditional on anything, because a handler that is not static is refused by
     * {@link #validate(PlanEntryView, TargetView, Reporter)}. Nothing else is pushed, since the
     * handler's remaining parameters are the operands the site already left on the stack.
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
        return (builder, element, index) -> {
            final Replacement replacement = replacements.get(index);
            if (replacement == null) {
                return Disposition.KEEP;
            }
            if (replacement.operation() == null) {
                // Scaffolding: the `new` and its `dup` simply stop existing.
                return Disposition.REPLACE;
            }
            // The operation's own operands are already on the stack, in the order the handler
            // declares them. Only the handle to what is underneath has to be pushed.
            builder.loadConstant(replacement.operation());
            builder.invoke(Opcode.INVOKESTATIC, owner, handler.name(), handler.type(), false);
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
     * @return {@code "WrapInjector"}, carrying no state because there is none
     */
    @Override
    @NotNull
    public String toString() {
        return "WrapInjector";
    }

    /**
     * What becomes of one element of the body.
     *
     * <p>A {@code null} operation is the scaffolding case: the element is removed and nothing takes
     * its place. Anything else is the constant to load before the handler call.
     *
     * @param operation the constant to load, or {@code null} to drop the element
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private record Replacement(@Nullable ConstantDesc operation) {

        /**
         * Replaces an element with a call to the handler.
         *
         * @param operation the constant the handler receives; must not be {@code null}
         * @return the replacement
         * @throws NullPointerException if {@code operation} is {@code null}
         */
        @Contract(value = "_ -> new", pure = true)
        @NotNull
        static Replacement call(@NotNull final ConstantDesc operation) {
            return new Replacement(Objects.requireNonNull(operation, "operation"));
        }

        /**
         * Removes an element without putting anything in its place.
         *
         * @return the replacement
         */
        @Contract(value = " -> new", pure = true)
        @NotNull
        static Replacement drop() {
            return new Replacement(null);
        }
    }
}
