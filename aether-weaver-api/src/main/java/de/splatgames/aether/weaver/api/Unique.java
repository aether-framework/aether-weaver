package de.splatgames.aether.weaver.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Lets a merged member be renamed rather than refused when the target already declares its name.
 *
 * <p>Every field and method of an instance weave that is not a handler, a {@link Shadow}, an
 * {@link Accessor} or an {@link Invoker} is copied into the target as it stands. A copy whose name
 * the target already uses is refused with {@code AW1080}, because merging it would replace working
 * code with an uninitialised duplicate. {@code @Unique} says that the weave does not care what the
 * member ends up called, only that it exists: the collision is resolved by renaming the weave's
 * copy, and the build continues.
 *
 * <h2>The name the member ends up with</h2>
 *
 * <p>Nothing is renamed unless there is a collision. Where there is one, the merged member is
 * emitted as its declared name followed by {@code $aw$} and eight lowercase hexadecimal digits
 * taken from the SHA-256 digest of the declaring weave's binary name — {@code counter} declared by
 * one weave becomes something of the form {@code counter$aw$1a2b3c4d}. The suffix depends only on
 * the weave class's name, so it is the same in every build and the same for every member of that
 * weave, and two weaves that both merge a colliding {@code counter} get two different names.
 *
 * <p>Every reference to the member inside the weave's own copied bodies is rewritten to the new
 * name, so the weave's code keeps working unchanged. Nothing outside the weave can name the member:
 * that is the point of the rename, and it is why a member other code has to reach should be given a
 * name of its own rather than declared {@code @Unique}.
 *
 * <p>A field collides on its name alone; a method collides on its name and descriptor together, so
 * a merged method that only differs from the target's by an overload is not a collision and is not
 * renamed.
 *
 * <h2>What the rename is reported as</h2>
 *
 * <p>A rename that happens is reported as {@code AW1094}, informational, naming both the declared
 * name and the one the member was given. Nothing needs doing about it; the name appears in stack
 * traces and profiles of the woven class, which is why it is said once. {@link #silent()} turns
 * that message off and changes nothing else.
 *
 * <h2>Two weaves merging the same member</h2>
 *
 * <p>When two weaves merge a member of the same name and type into one target, the collision is
 * between the weaves rather than with the target, and it is reported as {@code AW1080} unless
 * <em>every</em> one of them is {@code @Unique}. Marking some of them does not help: a renamed
 * member and a plainly named one still collide on the plain name, because the plain one is only
 * renamed if the target itself already declares it.
 *
 * <h2>Where it has no effect</h2>
 *
 * <ul>
 *   <li><b>A static weave.</b> A {@code @Weave(kind = Kind.STATIC)} weave is never merged into its
 *       target, so no member of it can collide with one. {@code @Unique} there is reported as
 *       {@code AW1091}, an error that discards the whole weave rather than only the offending
 *       member; nothing else the weave declares is parsed or applied either.
 *   <li><b>A handler.</b> A method carrying {@link Inject}, {@link Redirect} or {@link Wrap} is
 *       described by its injection rather than as a merged member, and this annotation is not read
 *       on one. A handler that collides with a method the target declares is {@code AW1080} with no
 *       rename available: the injected call sites name the handler, and a renamed handler would be
 *       called under a name that no longer exists.
 *   <li><b>A {@link Shadow}, {@link Accessor} or {@link Invoker}.</b> A shadow names a member the
 *       target already has and a generated accessor or invoker is reached by the name it was
 *       declared under, so neither can be renamed. A generated member colliding with one the target
 *       declares is {@code AW1095}.
 * </ul>
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @Weave(Session.class)
 * public final class SessionMetrics {
 *
 *     // Session may or may not already declare a field called `calls`; either way this one
 *     // is merged, under a mangled name when it has to be.
 *     @Unique
 *     private int calls;
 *
 *     @Unique(silent = true)
 *     private void record() {
 *         this.calls++;   // rewritten to whichever name the field was given
 *     }
 *
 *     @Inject(method = "invoke()", at = @At(Point.HEAD), require = 1)
 *     private void onInvoke() {
 *         record();
 *     }
 * }
 * }</pre>
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see Shadow
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface Unique {

    /**
     * Suppresses the {@code AW1094} message that a rename would otherwise produce.
     *
     * <p>Only the message is suppressed. The rename happens either way, under the same name, and
     * the weave behaves identically; this element decides nothing except whether the build says so.
     * It is also read only when a rename actually occurs, so setting it on a member that never
     * collides has no observable effect at all.
     *
     * @return whether to weave the rename without reporting it
     */
    boolean silent() default false;
}
