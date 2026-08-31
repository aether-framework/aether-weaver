package de.splatgames.aether.weaver.api.spi;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.lang.constant.ClassDesc;
import java.util.List;

/**
 * Everything one declaration knows about the one method it is about to be woven into.
 *
 * <p>An {@link Injector} is handed a context by {@link Injector#emitter(InjectionContext)} and
 * answers with an {@link Injector.Emitter}. The context is created after planning has finished and
 * before the target method is rewritten, so every question it answers is already decided: the
 * positions have been found, the ordinal and the shift have been applied, the handler's parameters
 * have been matched against the target's, and the local captures have been resolved. An injector
 * reads what it needs, builds its emitter, and emits.
 *
 * <h2>When it exists</h2>
 *
 * <p>One context per declaration per target method. {@link Injector#emitter(InjectionContext)} is
 * called as the rewrite of that method begins, before any of its elements has been passed on, and
 * the emitter it returns is then offered every element of the body in order — including the ones
 * this declaration did not match — with one exception, and it is a property of the whole method
 * rather than of one entry: once a protected range splits any exception-table entry of the method,
 * every exception-table entry of that method, including ones no split touches, is withheld from
 * every emitter and reconstructed directly once every other element has been walked; when nothing
 * splits, every entry is offered normally. A withheld entry's element index is still counted, so
 * the index an emitter is called with for a later element is unaffected. Deciding what to do
 * at a position belongs in the emitter; deciding what a position <em>is</em> belongs here, while the
 * whole body is still readable through {@link #method()}.
 *
 * <p>The work happens on the thread that called the weaver. Under the load-time driver that is the
 * thread loading the class.
 *
 * <h2>When something is wrong</h2>
 *
 * <p>An injector that discovers during emission that it cannot proceed reports through
 * {@link #diagnostics()} and returns {@link Injector.Emitter#NOTHING}. The class is still written —
 * without this declaration's contribution, and with whatever the other declarations on the same
 * method contributed — and the driver decides what an error means for the build. Throwing instead
 * is contained only once a contributed injector — one whose kind identifier carries a namespace —
 * is present on the class; once it is, a throw from any injector's {@link
 * Injector#emitter(InjectionContext)} or from the emitter it returned, built-in or contributed
 * alike, is reported as {@code AW3117} and the class is left exactly as it was, half-built bytes
 * discarded. This covers every throwable except two. A {@link VirtualMachineError} is rethrown
 * rather than reported or contained, on this path and on the planning one alike. And an
 * {@link IllegalArgumentException} is caught around the whole class rewrite rather than around one
 * injector's call, so it intercepts before containment applies — including one thrown by a
 * contributed emitter's own code — and is reported as {@code AW4003} or {@code AW4004} against the
 * class rather than the declaration: the two codes are alternatives on whether the exception's
 * message names a code-length failure, not two spellings of an engine-only cause.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @Override
 * public Emitter emitter(InjectionContext context) {
 *     Map<Integer, HandlerBinding> bindings = new LinkedHashMap<>();
 *     for (int site : context.sites()) {
 *         bindings.put(site, context.argumentsAt(site));
 *     }
 *     HandlerRef handler = context.entry().handler();
 *     ClassDesc owner = context.entry().handlerOwner();
 *     return (builder, element, index) -> {
 *         HandlerBinding binding = bindings.get(index);
 *         if (binding == null) {
 *             return Disposition.KEEP;
 *         }
 *         binding.emitReceiver(builder);
 *         binding.emitArguments(builder);
 *         builder.invokestatic(owner, handler.name(), handler.type());
 *         return Disposition.KEEP;
 *     };
 * }
 * }</pre>
 *
 * <p>Instances are supplied by the engine.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see Injector
 * @see HandlerBinding
 */
@ApiStatus.NonExtendable
public interface InjectionContext {

    /**
     * Returns the declaration being applied.
     *
     * <p>The whole of it: the parsed {@code @Inject}, {@code @Redirect} or contributed annotation
     * as {@link PlanEntryView#spec()}, the handler to call as {@link PlanEntryView#handler()}, and
     * the class to name in the call as {@link PlanEntryView#handlerOwner()} — which is the target
     * itself only for a weave that dissolves into it and whose handler is one of the members moved
     * there; for every other declaration, including one that dissolves but whose handler lives in a
     * shared helper class rather than in the weave itself, it is the class the handler actually
     * belongs to.
     *
     * @return the plan entry this emitter serves
     */
    @Contract(pure = true)
    @NotNull
    PlanEntryView entry();

    /**
     * Returns every declaration that matched the same element of this method, including this one.
     *
     * <p>This is how an injector finds out that it is not alone at a position. The entries are in
     * the order the plan established — highest {@code @Weave} priority first, then weave class
     * name, then handler name, then handler descriptor — so the first entry of the list is the
     * outermost, and a declaration that is not the first one at a shared position is nested inside
     * another. Entries of every kind are listed, so an injector interested in one kind filters by
     * {@link PlanEntryView#spec()}.
     *
     * <p>The default implementation answers with an empty list, which says nothing about how many
     * declarations matched; an injector that treats an empty answer as "only me" behaves correctly
     * against a context that does not track sharing.
     *
     * @param site the element index to ask about
     * @return the declarations at that element; the engine's own implementation includes this
     *         declaration's own entry, so the list is never empty for one of this declaration's
     *         own sites, and is otherwise empty either for an element no declaration matched or
     *         because the implementation does not track sharing at all, which is what the default
     *         method below does
     */
    @Contract(pure = true)
    @NotNull
    @Unmodifiable
    default List<PlanEntryView> entriesAt(final int site) {
        return List.of();
    }

    /**
     * Returns the class being woven.
     *
     * @return the target class as it was read, before any of this run's changes
     */
    @Contract(pure = true)
    @NotNull
    TargetView target();

    /**
     * Returns the method being woven.
     *
     * <p>{@link MethodView#code()} is the body the site indices count in, and it is present:
     * a method with no code cannot be an injection target and never reaches an injector.
     *
     * @return the target method
     */
    @Contract(pure = true)
    @NotNull
    MethodView method();

    /**
     * Returns the positions in the body this declaration matched.
     *
     * <p>Each is an index into {@code method().code().orElseThrow().elements()}, and each is the
     * index an emitter is called with for that element. The order is the order the positions were
     * resolved in — point specification by point specification, each in the order the injection
     * point returned its matches — which is not necessarily ascending, so an injector that needs
     * them in body order sorts them. Indices do not repeat.
     *
     * @return the matched element indices, never empty for a declaration that reached emission
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    List<Integer> sites();

    /**
     * Returns the argument binding computed for one site.
     *
     * <p>A binding says how to put the handler's arguments on the stack at that position, and it is
     * per site rather than per declaration because what the stack holds can differ from one matched
     * position to the next. It has already been checked: by the time an injector sees it, the
     * handler's parameters fit the target's, any callback matches the target's return type, and the
     * captured locals have been resolved against the variables live at that site.
     *
     * @param site one of {@link #sites()}
     * @return the binding for that site
     * @throws IllegalArgumentException if {@code site} is not one of {@link #sites()}
     */
    @Contract(pure = true)
    @NotNull
    HandlerBinding argumentsAt(int site);

    /**
     * Returns the return type of the target method.
     *
     * <p>The target's, not the handler's — this is what an injector needs to emit a return from an
     * injected position, and {@link java.lang.constant.ConstantDescs#CD_void} when the method
     * returns nothing.
     *
     * @return the target method's return type
     */
    @Contract(pure = true)
    @NotNull
    ClassDesc returnType();

    /**
     * Returns where to report a problem found while emitting.
     *
     * <p>The same sink the rest of the run reports to, so a diagnostic raised here reaches the
     * driver alongside the ones raised while planning. An injector that reports an error is
     * expected to emit nothing, since the alternative is a class that is wrong in a way the
     * diagnostic did not describe.
     *
     * @return the reporter for this run
     */
    @Contract(pure = true)
    @NotNull
    Reporter diagnostics();
}
