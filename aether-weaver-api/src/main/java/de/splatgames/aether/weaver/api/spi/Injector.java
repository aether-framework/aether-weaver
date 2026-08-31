package de.splatgames.aether.weaver.api.spi;

import de.splatgames.aether.weaver.api.model.InjectorKind;
import de.splatgames.aether.weaver.api.model.InjectorSpec;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.lang.classfile.ClassBuilder;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;

/**
 * Turns one resolved declaration into the instructions that are written at the positions it
 * matched.
 *
 * <p>An injection point decides <em>where</em>; an injector decides <em>what</em>. One injector
 * answers for one {@link InjectorKind} — {@code inject}, {@code redirect} and {@code wrap} are the
 * three the framework registers itself, and a plugin adds a kind of its own by registering an
 * {@link InjectorFactory} from {@link WeaverPlugin#contribute(PluginContext)}. A declaration whose
 * {@link InjectorSpec#kind()} nothing registered an injector for is reported as {@code AW4090} and
 * skipped, which is what a user sees when the plugin that owns the kind is missing from the
 * classpath — but only once that declaration's points have matched a position, since a declaration
 * that matched nothing is dropped before the kind is looked up at all.
 *
 * <h2>What the engine asks, and in what order</h2>
 *
 * <p>Every step below happens for one declaration against one class being woven, and only after
 * the declaration's target method has been found and at least one position inside it has been
 * matched. A declaration is refused before an injector is consulted at all when its selector matches
 * no method ({@code AW1020}) or several ({@code AW1021}), or when the method it names is native
 * ({@code AW1025}), has no body ({@code AW1023}) or is compiler-generated ({@code AW1024}). A
 * declaration whose points matched nothing is dropped before the injector is even looked up, so
 * neither {@link #validate} nor {@link #emitter(InjectionContext)} is called for it.
 *
 * <ol>
 *   <li>{@link #validate(PlanEntryView, TargetView, Reporter)}, once per declaration per class. Every
 *       diagnostic it reports is forwarded to the run's listener, and reporting one whose severity
 *       is {@link de.splatgames.aether.weaver.api.diagnostic.Severity#ERROR} abandons this
 *       declaration: nothing is emitted for it and the rest of the class is woven without it.
 *   <li>{@link #stackOperandsAt(InjectorSpec, MethodView, CodeView, int)}, once per matched
 *       position, before the handler's arguments are bound. The answer decides how many values
 *       {@link HandlerBinding} treats as already being on the stack.
 *   <li>The handler binding itself is computed once per distinct answer from the previous step,
 *       shared by every position that answered the same number. A handler whose shape does not fit
 *       the target is refused here — {@code AW1040}, {@code AW1005}, {@code AW1070} and
 *       {@code AW1071} are the codes — and the declaration is abandoned without reaching emission.
 *   <li>{@link #emitter(InjectionContext)}, as the rewrite of a method begins.
 *   <li>{@link Emitter#emitAt(CodeBuilder, CodeElement, int)}, for every element of that method's
 *       body in order.
 * </ol>
 *
 * <p>An injector instance is obtained from {@link InjectorFactory#create(InjectorKind)} once per
 * declaration per class being woven, and nothing caches it, so an injector may hold state for the
 * length of one declaration's work but must not assume it is the only instance of itself.
 *
 * <h2>Emission</h2>
 *
 * <p>The engine owns the walk over the body. It offers each element to every emitter working on
 * that method, in the order the plan established — highest {@code @Weave} priority first, then
 * weave class name, then handler name, then handler descriptor — and writes the element itself
 * afterwards, unless some emitter answered {@link Disposition#REPLACE}. An emitter therefore never
 * calls {@code accept} on the builder for the element it was handed; it appends the instructions it
 * wants around that element and says whether the element still belongs in the output. Any single
 * {@link Disposition#REPLACE} suppresses the element for every emitter at that position.
 *
 * <p>An emitter is offered every element of the body, including the ones this declaration did not
 * match, so the {@code index} argument is what tells it whether it is at one of its own positions.
 * The indices to compare against are {@link InjectionContext#sites()}.
 *
 * <p>Two properties of the walk catch implementations out:
 *
 * <ul>
 *   <li><b>Methods are selected by name.</b> The rewrite is applied to every method of the target
 *       whose name equals the resolved target method's name, so a class with overloads sharing that
 *       name has {@link #emitter(InjectionContext)} called once per overload, each time with a
 *       context whose {@link InjectionContext#method()} is the one the declaration resolved to. An
 *       emitter that acts on an element purely because its index is one of
 *       {@link InjectionContext#sites()} will therefore also act at that index inside the
 *       overloads, where the index means something else.
 *   <li><b>Exception-table entries can be withheld.</b> Where a declaration of kind {@code inject}
 *       adds code inside a protected range, the whole method's exception table is held back from
 *       every emitter and rebuilt after the last element, and the split is reported as
 *       {@code AW1131}. Only the {@code inject} kind is considered when deciding this, so a
 *       contributed kind that inserts code inside a protected range leaves the range whole and
 *       raises no {@code AW1131}.
 * </ul>
 *
 * <h2>When an injector cannot proceed</h2>
 *
 * <p>An injector reports and returns rather than throwing. Discovering during
 * {@link #emitter(InjectionContext)} that the declaration cannot be served means reporting through
 * {@link InjectionContext#diagnostics()} and returning {@link Emitter#NOTHING}; the class is still
 * written, without this declaration's contribution.
 *
 * <p>Throwing is contained only in one of the five steps above, and only under a condition that is
 * a property of the class rather than of the injector:
 *
 * <ul>
 *   <li>A throw from {@link #emitter(InjectionContext)} or from the emitter it returned is reported
 *       as {@code AW3117} and the class is left exactly as it was — but only when at least one
 *       declaration being applied to that class names a kind carrying a namespace. Where every
 *       declaration on the class is of a built-in kind, nothing contains the throw.
 *   <li>A throw from {@link #validate(PlanEntryView, TargetView, Reporter)}, either
 *       {@code stackOperandsAt} overload, or {@link InjectorFactory#create(InjectorKind)} is not
 *       contained at any point. It leaves the weaver with no diagnostic reported, and what becomes
 *       of it is the driver's business.
 *   <li>A {@link VirtualMachineError} is rethrown rather than reported or contained.
 *   <li>An {@link IllegalArgumentException} thrown from emission is intercepted around the whole
 *       class rewrite, ahead of the containment above, and reported as {@code AW4003} when its
 *       message names a code-length failure and as {@code AW4004} otherwise.
 * </ul>
 *
 * <h2>Threading</h2>
 *
 * <p>Every method here runs on the thread that called the weaver. Under the load-time driver that
 * is the thread loading the class, and that loader is parallel-capable, so one plugin's factory can
 * be asked for injectors on several threads at once.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * public final class TraceInjector implements Injector {
 *
 *     static final InjectorKind TRACE = InjectorKind.of("acme:trace");
 *
 *     @Override
 *     public InjectorKind kind() {
 *         return TRACE;
 *     }
 *
 *     @Override
 *     public void validate(PlanEntryView entry, TargetView target, Reporter reporter) {
 *         if (!entry.handler().isStatic()) {
 *             reporter.report(DiagnosticCode.STATIC_WEAVE_INSTANCE_HANDLER,
 *                     entry.handler().describe() + " must be static");
 *         }
 *     }
 *
 *     @Override
 *     public Emitter emitter(InjectionContext context) {
 *         Set<Integer> where = Set.copyOf(context.sites());
 *         HandlerRef handler = context.entry().handler();
 *         ClassDesc owner = context.entry().handlerOwner();
 *         return (builder, element, at) -> {
 *             if (where.contains(at)) {
 *                 HandlerBinding binding = context.argumentsAt(at);
 *                 binding.emitArguments(builder);
 *                 binding.emitCaptures(builder);
 *                 builder.invokestatic(owner, handler.name(), handler.type());
 *             }
 *             return Disposition.KEEP;   // the engine writes the element itself
 *         };
 *     }
 * }
 * }</pre>
 *
 * <p>Implemented by plugins and called by the engine.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see InjectorFactory
 * @see InjectionContext
 * @see InjectionPoint
 */
@ApiStatus.OverrideOnly
public interface Injector {

    /**
     * Returns the kind this injector answers for.
     *
     * <p>Informational rather than dispatching: the engine finds an injector by the identifier the
     * declaration named, through the registry the owning {@link InjectorFactory} was registered in,
     * and never compares the result against this method. A factory serving several kinds is free to
     * hand back one instance whose {@link #kind()} names only one of them, and nothing reports the
     * disagreement.
     *
     * @return the kind, matching one of the owning factory's {@link InjectorFactory#kinds()}
     */
    @Contract(pure = true)
    @NotNull
    InjectorKind kind();

    /**
     * Reports whether this injector changes the shape of the class rather than only the bodies of
     * its methods.
     *
     * <p>The weaving pipeline this release ships rewrites bodies only: it asks every injector for an
     * {@link Emitter} and never for a structural contribution, and no stage of it reads this method.
     * The three built-in injectors override it to return {@code false} explicitly. Structural
     * changes — merged members, accessors and invokers — are folded into the target by a separate
     * stage driven by the weave class itself, which is why
     * {@link InjectorKind#MERGE}, {@link InjectorKind#ACCESSOR} and {@link InjectorKind#INVOKER}
     * name identifiers that no injector is registered for.
     *
     * @return {@code false} unless overridden
     */
    @Contract(pure = true)
    default boolean isStructural() {
        return false;
    }

    /**
     * Checks the declaration against the class it is about to be woven into.
     *
     * <p>Called once per declaration per target class, after its positions have been found and
     * before any argument binding is computed. This is where a requirement that depends on the
     * target belongs — whether the handler may be an instance method, whether the declaration's
     * points name something this kind can act on — because the alternative is discovering it during
     * emission, where the only recourse is {@link Emitter#NOTHING} and a class that is already half
     * described by a diagnostic.
     *
     * <p>The {@code reporter} handed in is not the run's listener directly. It forwards every
     * diagnostic to the run, and it also watches their severities: if any diagnostic reported
     * through it carries {@link de.splatgames.aether.weaver.api.diagnostic.Severity#ERROR}, this
     * declaration is abandoned and neither {@code stackOperandsAt} nor
     * {@link #emitter(InjectionContext)} is called for it. Warnings and information do not abandon
     * anything. Reporting several problems in one call is therefore worthwhile: all of them reach
     * the user, and the declaration is dropped exactly once.
     *
     * <p>A diagnostic reported here is attributed to the declaration by its message alone; nothing
     * adds the weave class or the handler for the implementation. The built-in injectors open every
     * message with {@link de.splatgames.aether.weaver.api.model.HandlerRef#describe()} for that
     * reason.
     *
     * <p>The default implementation checks nothing, which is correct for an injector whose
     * requirements are all satisfied by the declaration on its own.
     *
     * @param entry    the declaration being checked, including its handler and the class the call
     *                 will name
     * @param target   the class being woven, as it was read
     * @param reporter where to report a problem; an error reported here abandons the declaration
     */
    default void validate(@NotNull final PlanEntryView entry,
                          @NotNull final TargetView target,
                          @NotNull final Reporter reporter) {
        // Nothing by default: an injector with no target-dependent requirements needs no check.
    }

    /**
     * Returns how many values the stack already holds at one matched position for the handler to
     * consume.
     *
     * <p>This is the {@code skipLeading} count of {@link HandlerBinding}: the number of leading
     * handler parameters that are satisfied by values already on the operand stack rather than by
     * the target's locals. A {@code redirect} answers with the arity of the operation it is about to
     * replace, a {@code wrap} with that arity plus one for the {@link
     * de.splatgames.aether.weaver.api.callback.Operation} handle, and an {@code inject} with one
     * when it captures the result of the preceding call and zero otherwise.
     *
     * <p>Answering more than the stack really holds produces a handler call the verifier rejects,
     * and answering fewer silently binds the wrong parameters — nothing cross-checks the number
     * against the body, because the injector that asked for the values is the one that arranges for
     * them to be there.
     *
     * <p>Called once per matched position, and the binding computed from it is shared between
     * positions that answer with the same number. A position whose answer leads to a binding
     * failure costs one diagnostic for the whole group of positions sharing that number, not one per
     * position.
     *
     * <p>The default implementation returns {@code 0}, which is correct for an injector whose
     * handler reads nothing off the stack.
     *
     * @param method the target method
     * @param code   its body, in which {@code site} is an index
     * @param site   the matched element index, one of {@link InjectionContext#sites()}
     * @return the number of values already on the stack at that position; never negative
     */
    @Contract(pure = true)
    default int stackOperandsAt(@NotNull final MethodView method,
                                @NotNull final CodeView code,
                                final int site) {
        return 0;
    }

    /**
     * Returns how many values the stack already holds at one matched position, with the declaration
     * available.
     *
     * <p>This is the overload the engine calls; the three-argument form exists for an injector whose
     * answer does not depend on the declaration, and the default here delegates to it. An injector
     * whose answer does depend on the declaration — an {@code inject} answers {@code 1} only when
     * the declaration captures the preceding call's result — overrides this one, and overriding this
     * one alone is enough, since nothing else calls the shorter form.
     *
     * @param spec   the declaration being applied
     * @param method the target method
     * @param code   its body, in which {@code site} is an index
     * @param site   the matched element index, one of {@link InjectionContext#sites()}
     * @return the number of values already on the stack at that position; never negative
     */
    @Contract(pure = true)
    default int stackOperandsAt(@NotNull final InjectorSpec spec,
                                @NotNull final MethodView method,
                                @NotNull final CodeView code,
                                final int site) {
        return stackOperandsAt(method, code, site);
    }

    /**
     * Builds the emitter that writes this declaration's contribution into one method.
     *
     * <p>Called as the rewrite of a method begins, before any of its elements has been passed on.
     * Everything needed to decide what to write is already settled and readable from the context:
     * the matched positions, the argument binding at each of them, the target's own shape, and the
     * handler to call. The work that belongs here is the work that depends on the whole body — the
     * body is still readable through {@link InjectionContext#method()}, and it is not readable from
     * inside the emitter, which sees one element at a time.
     *
     * <p>Called once per method whose name matches the resolved target method's name, so a target
     * with overloads sharing that name produces one call per overload, each with the same context.
     * An injector that must not act inside an overload it did not match has to distinguish them
     * itself; the context describes only the method the declaration resolved to.
     *
     * <p>Returning {@link Emitter#NOTHING} is how an injector declines to contribute after finding
     * a problem, which it reports through {@link InjectionContext#diagnostics()} first. The rest of
     * the class is still woven.
     *
     * @param context everything this declaration knows about the method it is being woven into
     * @return the emitter for this declaration in this method; {@link Emitter#NOTHING} to
     *         contribute nothing
     */
    @Contract(pure = true)
    @NotNull
    Emitter emitter(@NotNull InjectionContext context);

    /**
     * Writes instructions around one element of a method body.
     *
     * <p>Created by {@link Injector#emitter(InjectionContext)} and then called for every element of
     * that body in order, including elements this declaration did not match, and including elements
     * another declaration matched. Several emitters may be working on one method; each is called at
     * every element, in the plan's order, and each writes into the same {@link CodeBuilder}.
     *
     * <p>An emitter appends instructions and does not copy the element it was handed: the engine
     * writes the element after the last emitter has been asked, unless one of them answered
     * {@link Disposition#REPLACE}. Whatever an emitter appends before returning therefore lands
     * ahead of the element, and whatever it appends when replacing lands instead of it.
     *
     * <p>Emission must be total: the engine has no way to undo instructions already written, which
     * is why the two rules for an emitter that finds a problem are to report through
     * {@link InjectionContext#diagnostics()} and to have declined earlier, at
     * {@link Injector#emitter(InjectionContext)}, rather than partway through a body.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     * @see Injector#emitter(InjectionContext)
     */
    @FunctionalInterface
    interface Emitter {

        /**
         * An emitter that writes nothing and keeps every element.
         *
         * <p>What an injector returns from {@link Injector#emitter(InjectionContext)} when it has
         * decided not to contribute — after reporting why through
         * {@link InjectionContext#diagnostics()}, since an injector that declines silently produces
         * a class that is not woven and a build that never says so. The rest of the class, including
         * the contributions of other declarations on the same method, is woven as usual.
         */
        Emitter NOTHING = (builder, element, index) -> Disposition.KEEP;

        /**
         * Writes whatever this declaration contributes at one element, and says whether the element
         * survives.
         *
         * <p>{@code index} counts elements of the body being rewritten, from zero, in the order the
         * class file holds them — the same coordinate system {@link CodeView#elements()} and
         * {@link InjectionContext#sites()} use. An element withheld from the emitters, which is what
         * happens to a method's exception-table entries once injected code splits a protected range,
         * still consumes its index, so the index of every later element is unaffected.
         *
         * @param builder where instructions are appended; the element itself must not be written to
         *                it, since the engine writes the element
         * @param element the element being offered
         * @param index   its position in the body, counted from zero
         * @return {@link Disposition#KEEP} to let the engine write the element, or
         *         {@link Disposition#REPLACE} to suppress it
         */
        @NotNull
        Disposition emitAt(@NotNull CodeBuilder builder, @NotNull CodeElement element, int index);
    }

    /**
     * What becomes of the element an emitter was offered.
     *
     * <p>Decided per element and per emitter, and combined across the emitters working on one
     * method by a single rule: any {@link #REPLACE} wins. A redirect that replaces a call and an
     * injection that also matched that position both emit, and the call is gone — which is what both
     * of them asked for.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     * @see Emitter#emitAt(CodeBuilder, CodeElement, int)
     */
    enum Disposition {

        /**
         * The element is written after every emitter has been asked.
         *
         * <p>The answer for an emitter that adds instructions around an element rather than standing
         * in for it, and the only answer {@link Emitter#NOTHING} ever gives.
         */
        KEEP,

        /**
         * The element is not written; what this emitter appended stands in its place.
         *
         * <p>Suppresses the element for every emitter working on the method, not only for the one
         * that answered. An emitter that replaces an element is responsible for leaving the operand
         * stack as the element would have left it, since nothing after this position knows that the
         * element is gone.
         */
        REPLACE
    }

    /**
     * Adds members to the class being built, for an injector whose contribution is structural.
     *
     * <p>The pipeline this release ships never calls this method: it rewrites method bodies through
     * {@link Emitter} and makes no structural contribution on an injector's behalf. Merged members,
     * accessors and invokers reach a target through a separate stage driven by the weave class, so
     * an injector that overrides this method alone changes nothing about the class that is written.
     *
     * <p>The default implementation adds nothing.
     *
     * @param builder the class being built
     * @param entry   the declaration being applied
     * @param target  the class being woven, as it was read
     */
    default void contribute(@NotNull final ClassBuilder builder,
                            @NotNull final PlanEntryView entry,
                            @NotNull final TargetView target) {
        // Nothing by default: most injectors only rewrite bodies, which is not a structural change.
    }
}
