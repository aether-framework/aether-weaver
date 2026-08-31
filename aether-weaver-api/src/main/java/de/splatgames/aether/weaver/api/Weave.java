package de.splatgames.aether.weaver.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a weave and names the classes it modifies.
 *
 * <p>This is the annotation that makes everything else in the package mean something. A class
 * without it is an ordinary class: its {@link Inject}, {@link Redirect} and {@link Wrap} methods
 * are never read, its {@link Shadow} and {@link Unique} members bind to nothing, and neither the
 * annotation processor nor the engine looks at it. A class with it is a declaration of what should
 * happen to another class, and every other annotation here is read relative to that declaration.
 *
 * <p>A weave class is not a runtime participant in the way an ordinary class is. An instance weave
 * is <em>dissolved</em>: its members and handlers are copied into each target, references inside
 * them are rebound to the target's own members, and the weave class itself is never loaded. A
 * static weave stays where it is and its handlers are called across the class boundary. Which of
 * the two applies is {@link #kind()}, and a great many rules elsewhere in this package turn on it.
 *
 * <h2>Naming the targets</h2>
 *
 * <p>Two ways, and exactly one of them per weave. {@link #value()} takes class literals and
 * {@link #targets()} takes binary names as text. Declaring both is reported as {@code AW1002} and
 * declaring neither as {@code AW1001}; in both cases the weave contributes nothing.
 *
 * <p>A class literal is checked by the compiler, follows a rename and survives the class moving to
 * another package, so it is the form to prefer. {@link #targets()} exists for a class that is not
 * on the compile classpath. Naming a class as text when it <em>is</em> on the compile classpath is
 * reported as {@code AW1009}, an informational message suggesting the literal.
 *
 * <h2>When a target cannot be resolved</h2>
 *
 * <p>Three separate situations, which produce three different outcomes.
 *
 * <ul>
 *   <li><b>The text is not a usable binary class name.</b> The engine reports {@code AW1004} and
 *       discards the weave whole, not just that entry — a nested class is written with a dollar
 *       sign, as in {@code "com.acme.Outer$Inner"}, and any other, resolvable entries the weave
 *       names do not save it once one entry has failed this way.
 *   <li><b>The name is well formed but the class is not on the compile classpath.</b> The
 *       annotation processor reports {@code AW1004} unless the weave declares
 *       {@code require = Require.OPTIONAL}, which is exactly what that setting is for: a target
 *       that is deliberately absent at compile time and present at run time. The engine never
 *       makes this check, because at weave time a class is either handed to the weaver or it is
 *       not.
 *   <li><b>The class exists but is never presented to the weaver.</b> Nothing is reported and
 *       nothing is woven. A weave applies to a class when that class passes through the weaver;
 *       one that never loads is simply not modified, whatever {@link #require()} says.
 * </ul>
 *
 * <h2>What a target may not be</h2>
 *
 * <p>Some classes are refused however they are named.
 *
 * <ul>
 *   <li><b>Another weave class</b> — {@code AW1087}, reported by the annotation processor and by
 *       conflict detection. The engine's own policy gate carries a check for the same thing, but the
 *       single call site that constructs the target it decides against never marks a target as a
 *       declared weave class, so that third check can never fire; the annotation processor and
 *       conflict detection are what actually catch this. An instance weave is folded into its own
 *       targets, so by the time anything could be woven into it there is nothing there. Target the
 *       class the other weave targets, and order the two with {@link #priority()}.
 *   <li><b>Aether Weaver itself</b> — {@code AW3003}, under every configuration.
 *   <li><b>{@code java.*}</b> — {@code AW3001}, under every configuration; those classes load
 *       before any transformer can be installed. The other JDK prefixes — {@code javax.},
 *       {@code jdk.}, {@code sun.} and {@code com.sun.} — are refused with the same code unless
 *       the exact package is reopened in configuration.
 *   <li><b>A class from a signed artefact</b> — {@code AW3002} unless signed artefacts are
 *       explicitly allowed, because modifying one invalidates its signature.
 *   <li><b>A class file older than major version 50</b> — {@code AW2003}; its stack map frames are
 *       absent, and the engine's transforms assume they are present.
 * </ul>
 *
 * <p>An anonymous or local class is woven but reported as {@code AW1092}, a warning: the trailing
 * number in its name counts the anonymous and local classes of its enclosing class in source order,
 * so an unrelated lambda added earlier in that file silently makes the weave modify a different
 * class.
 *
 * <h2>What the weave class itself may look like</h2>
 *
 * <p>The shape is constrained because an instance weave's members are copied verbatim into a class
 * that already has a shape of its own. Each of these is checked by the annotation processor and
 * again by the engine.
 *
 * <ul>
 *   <li><b>It extends {@link Object} and nothing else</b> — {@code AW1006} otherwise. The target
 *       already has a superclass; reach the superclass's members through {@link Shadow}.
 *   <li><b>It implements no interface</b> — {@code AW1084}. Adding an interface to a target is not
 *       a 0.1.0 capability.
 *   <li><b>It declares no type parameters</b> — {@code AW1007}. Members are copied verbatim, and a
 *       type variable has nothing to bind to in the target.
 *   <li><b>It declares no constructor</b> — {@code AW1081}. The target has its own, and two cannot
 *       be merged. Initialise merged state from an {@link Inject} at the target constructor's
 *       {@link Point#HEAD}. A compiler-generated default constructor is not a declared one and is
 *       ignored.
 *   <li><b>It declares no static initialiser</b> — {@code AW1082}.
 *   <li><b>It is {@code final}</b> — {@code AW1008}, a warning, since a weave class is never
 *       subclassed and never instantiated. An {@code abstract} weave is exempt, because
 *       {@link Accessor} and {@link Invoker} have an abstract spelling and an abstract class
 *       cannot be final.
 * </ul>
 *
 * <h2>What the members of a weave become</h2>
 *
 * <p>Every field and method is classified once, and the first thing that applies wins. The order the
 * check is made in is not the same for a field and a method, and is not the same in the engine and in
 * the annotation processor's own diagnostics for a method — a method that carries both {@link Shadow}
 * and {@link Accessor} or {@link Invoker} is classified differently by the two.
 *
 * <ul>
 *   <li>A method carrying {@link Inject}, {@link Redirect} or {@link Wrap} is a <b>handler</b>,
 *       checked first for a method. It is described by its injection rather than as a member, and an
 *       instance weave still merges it into the target so that the injected call can reach it.
 *   <li>For a method, {@link Accessor} and {@link Invoker} are checked next, before {@link Shadow}: a
 *       method carrying both {@link Accessor} or {@link Invoker} and {@link Shadow} is generated onto
 *       the target as an accessor or invoker, and its {@link Shadow} is never read by the engine. The
 *       annotation processor's own diagnostics classify the same method the other way, checking
 *       {@link Shadow} first — the two disagree about a method carrying both.
 *   <li>For a field there is no such conflict: {@link Shadow} is checked before {@link Accessor} or
 *       {@link Invoker} in both the engine and the processor. A member carrying {@link Shadow} names
 *       something the target already declares and is never copied.
 *   <li>A method or field checked against none of the above and carrying {@link Accessor} or
 *       {@link Invoker} is generated onto the target rather than copied from the weave.
 *   <li>Everything else is <b>merged</b>: copied into the target as it stands, with a name that
 *       {@link Unique} may have changed.
 * </ul>
 *
 * <p>A merged field's initialiser does not travel with it. The field arrives with the JVM's default
 * value and {@code AW1093} says so, because an initialiser belongs to a constructor and a weave has
 * none to merge; write the value from an {@link Inject} at the target constructor's
 * {@link Point#HEAD}. Merging {@code toString}, {@code equals}, {@code hashCode} or {@code main}
 * replaces behaviour the platform itself calls and is reported as {@code AW1083}, a warning.
 *
 * <p>Merging members requires the weave's own class file, not just its parsed shape: a method's
 * body exists nowhere else. A weave that merges anything without its bytes available is reported as
 * {@code AW1096}.
 *
 * <h2>Order, when two weaves meet</h2>
 *
 * <p>{@link #priority()} decides. Where two declarations apply at one place they are sorted by
 * priority descending, then by weave class name, then by handler name, then by handler descriptor,
 * so the order is the same in every build and a weave added later cannot silently reorder the ones
 * already there. {@link Inject} states what that order means for injected calls and {@link Wrap}
 * what it means for nesting.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @Weave(value = Ledger.class, priority = 100, tags = {"audit"})
 * public final class LedgerAudit {
 *
 *     // Ledger declares: private java.math.BigDecimal balance;
 *     @Shadow
 *     private java.math.BigDecimal balance;
 *
 *     @Unique
 *     private int charges;
 *
 *     @Inject(method = "charge(java.math.BigDecimal)", at = @At(Point.HEAD), require = 1)
 *     private void onCharge(java.math.BigDecimal amount) {
 *         this.charges++;
 *         Audit.log(this.balance, amount);
 *     }
 * }
 * }</pre>
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see Inject
 * @see Redirect
 * @see Wrap
 * @see Shadow
 * @see Group
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Weave {

    /**
     * The classes this weave modifies, written as class literals.
     *
     * <p>The form to prefer. A literal is resolved by the compiler, so a target that does not
     * exist, is misspelt or has moved is a compilation error before any of this framework runs, and
     * a rename in an IDE carries the weave with it.
     *
     * <p>Mutually exclusive with {@link #targets()}: declaring both is {@code AW1002} and declaring
     * neither is {@code AW1001}. Several literals are allowed, and the weave then applies to each
     * of them independently — every handler, every {@link Shadow} and every merged member is
     * checked against each target in turn.
     *
     * @return the target classes, or an empty array when {@link #targets()} names them instead
     */
    Class<?>[] value() default {};

    /**
     * The classes this weave modifies, written as binary names.
     *
     * <p>For a target that is not on the compile classpath, which is the only reason to use this
     * form; a target that is there is reported as {@code AW1009} with the literal to write instead.
     * A nested class takes a dollar sign, as in {@code "com.acme.Outer$Inner"}, and text that is
     * not a usable binary class name at all is {@code AW1004}.
     *
     * <p>Whether an unresolvable name fails the compilation is {@link #require()}: with
     * {@link Require#REQUIRED}, the default, the annotation processor reports {@code AW1004} for a
     * name it cannot resolve; with {@link Require#OPTIONAL} it accepts the absence.
     *
     * <p>Mutually exclusive with {@link #value()}.
     *
     * @return the target class names, or an empty array when {@link #value()} names them instead
     */
    String[] targets() default {};

    /**
     * Whether the weave is dissolved into its target or stays a class of its own.
     *
     * <p>The single most consequential element here: it decides whether the weave's members exist
     * on the target at all, and therefore whether {@link Shadow}, {@link Unique} and instance
     * handlers mean anything. Each constant states what it implies.
     *
     * @return the kind of weave
     */
    Kind kind() default Kind.INSTANCE;

    /**
     * Orders this weave against others that reach the same place.
     *
     * <p>Higher runs first. Where two declarations apply at one position, they are sorted by
     * priority descending and then by weave class name, handler name and handler descriptor, which
     * makes the order the same in every build. A weave with a higher priority is emitted first
     * among injections and ends up outermost among wraps.
     *
     * <p>Priority also decides whether a {@link Shadow} may name a member that a different weave
     * merges: shadowing a member added by a weave whose priority is not strictly higher is
     * {@code AW1034}. Equal priority is not enough there, because the remaining tie-breakers are
     * stable but arbitrary.
     *
     * <p>Negative values are allowed and mean the weave runs after the ones that say nothing.
     *
     * @return the ordering priority, higher first
     */
    int priority() default 0;

    /**
     * Whether a {@link #targets()} name that cannot be resolved at compile time fails the build.
     *
     * <p>Read by the annotation processor and by nothing else in the weaving path. It has no effect
     * on {@link #value()}, whose literals the compiler has already resolved, and none at weave
     * time, where a class is either handed to the weaver or is not.
     *
     * @return whether an unresolvable target is an error
     */
    Require require() default Require.REQUIRED;

    /**
     * Free-form labels by which a deployment can switch this weave on or off.
     *
     * <p>The tags travel with the weave: they are recorded on the parsed weave, written to a
     * generated manifest, and compared against the runtime configuration's include and exclude
     * lists when weaves are discovered. A weave carrying any excluded tag is skipped; if an include
     * list is configured, a weave is kept only when it carries at least one tag from it — so an
     * untagged weave is skipped as soon as any include list exists. With neither list configured
     * every weave is kept and the tags do nothing.
     *
     * <p>A configuration entry naming this weave's class directly overrides the tag decision in
     * both directions.
     *
     * <p>Duplicates collapse: the tags are held as a set.
     *
     * @return the weave's tags
     */
    String[] tags() default {};

    /**
     * The stage this weave declares itself to belong to.
     *
     * <p>Carried rather than acted upon. The value is recorded on the parsed weave and written to a
     * generated manifest, and no stage of planning, conflict detection or injection selects weaves
     * by it. What orders two declarations meeting at one place is {@link #priority()}.
     *
     * @return the declared phase
     */
    Phase phase() default Phase.DEFAULT;

    /**
     * Whether a weave's members become members of its target.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    enum Kind {

        /**
         * The weave is dissolved into each of its targets.
         *
         * <p>Its merged fields and methods are copied onto the target, its handlers become methods
         * of the target, and every reference among them — including a {@link Shadow} — is rebound
         * to the target's own members. The weave class itself is never loaded.
         *
         * <p>This is what makes an instance handler work: it is callable because it <em>is</em> a
         * method of the class calling it. It is also what makes {@link Shadow} and {@link Unique}
         * meaningful, and what puts a merged member at risk of colliding with one the target
         * already declares ({@code AW1080}).
         *
         * <p>The cost is that the target's member set changes, which the JVM does not permit on an
         * already-loaded class. An agent attaching to a running JVM reports one {@code AW2101} per
         * weave that has any already-loaded target, naming every such target together in that single
         * diagnostic rather than reporting one per target, and weaves the rest in full.
         */
        INSTANCE,

        /**
         * The weave stays a class of its own and its handlers are called across the boundary.
         *
         * <p>Nothing is merged, so the target's member set is unchanged and the weave can be
         * applied by retransformation. In exchange, several declarations stop meaning anything and
         * are refused rather than ignored:
         *
         * <ul>
         *   <li>A non-static handler is {@code AW1005}: there is no instance of the weave to call
         *       it on. Declare it {@code static} and take the target as the first parameter.
         *   <li>A {@link Shadow} member is {@code AW1090} and a {@link Unique} member is
         *       {@code AW1091}: neither has anything to bind to in a weave that is never merged.
         *   <li>The injected call is an ordinary cross-class invocation and obeys ordinary access
         *       rules, so the handler and its class must be {@code public} or share the target's
         *       package. A private handler can never be reached. Either is {@code AW1042}, and it
         *       is checked by the annotation processor alone — a build that skips the processor
         *       gets an {@link IllegalAccessError} at the injected call's first execution instead.
         * </ul>
         */
        STATIC
    }
}
