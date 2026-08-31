package de.splatgames.aether.weaver.agent;

import de.splatgames.aether.weaver.api.Weave;
import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import de.splatgames.aether.weaver.api.spi.DiagnosticListener;
import de.splatgames.aether.weaver.engine.model.WeaveClass;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Reports the weaves a dynamic attach cannot apply, because their targets are already loaded and
 * they would change a loaded class's member set.
 *
 * <p>Reached only from {@code agentmain}. A {@code -javaagent} run weaves its targets as they are
 * defined for the first time, where none of these limits apply.
 *
 * <p>Adding a method, adding a field and clearing {@code ACC_FINAL} on an existing field are each
 * refused with an {@link UnsupportedOperationException} whose message begins
 * {@code class redefinition failed}. Replacing a method body is accepted and takes effect, and
 * writing the same field back with its flags unchanged is accepted, so it is the flag change and
 * not the rewriting of the field that the JVM objects to. Measured on OpenJDK 25 (Temurin 25.0.3+9,
 * Linux).
 *
 * <p>Nothing is removed from the plan here. Narrowing it would change the weaver's fingerprint, so
 * one weave set would stamp classes differently depending on how the agent was started.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
final class RetransformApplicability {

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private RetransformApplicability() {
        throw new AssertionError("no instances");
    }

    /**
     * Reports {@code AW2101} once per weave that changes a member set and has at least one target
     * among the already-loaded classes.
     *
     * <p>The message names those targets and the one thing that made the weave structural. A weave
     * whose targets are all still unloaded is passed over in silence, because such a target is
     * defined for the first time rather than redefined and is woven in full.
     *
     * @param weaves        the plan as discovered; must not be {@code null}
     * @param alreadyLoaded the binary names of the classes the JVM has already defined; must not be
     *                      {@code null}
     * @param listener      where {@code AW2101} is reported; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    static void report(@NotNull final List<WeaveClass> weaves,
                       @NotNull final Set<String> alreadyLoaded,
                       @NotNull final DiagnosticListener listener) {
        Objects.requireNonNull(weaves, "weaves");
        Objects.requireNonNull(alreadyLoaded, "alreadyLoaded");
        Objects.requireNonNull(listener, "listener");

        for (final WeaveClass weave : weaves) {
            final String reason = refusalFor(weave);
            if (reason == null) {
                continue;
            }
            final List<String> missed = weave.targets().stream()
                    .map(target -> binaryNameOf(target.type()))
                    .filter(alreadyLoaded::contains)
                    .toList();
            if (missed.isEmpty()) {
                // Nothing to say. A structural weave whose targets have not been loaded yet is
                // applied in full — the classes are being defined for the first time rather than
                // redefined — and a diagnostic here would report a problem that does not exist.
                continue;
            }
            listener.report(Diagnostic.builder(DiagnosticCode.STRUCTURAL_WEAVE_NEEDS_PRELOAD)
                    .message(weave.binaryName() + " cannot be applied to "
                            + String.join(", ", missed) + ", which "
                            + (missed.size() == 1 ? "is" : "are") + " already loaded: " + reason)
                    .detail("the JVM forbids changing a loaded class's member set, so this is a "
                            + "limit of retransformation rather than of the weave")
                    .detail("classes that have not been loaded yet are still woven in full, "
                            + "including this weave")
                    .remedy("weave at build time with the Maven plugin, or start the JVM with "
                            + "-javaagent so that the targets are woven as they load")
                    .build());
        }
    }

    /**
     * Turns a target's descriptor into the binary name that
     * {@link java.lang.instrument.Instrumentation#getAllLoadedClasses()} reports.
     *
     * <p>Strips the leading {@code L} and the trailing {@code ;} and replaces {@code /} with
     * {@code .}, so it holds for a class type and for nothing else.
     *
     * @param type the target type; must not be {@code null}
     * @return the binary name, such as {@code com.acme.Ledger}
     */
    @Contract(pure = true)
    @NotNull
    private static String binaryNameOf(@NotNull final java.lang.constant.ClassDesc type) {
        final String descriptor = type.descriptorString();
        return descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
    }

    /**
     * Names the first thing this weave does that a loaded class cannot be made to do.
     *
     * <p>A merged member, a generated accessor and a generated invoker each count. Members are
     * examined in the order the weave holds them and the first one with an answer decides, so a
     * weave with several of them is reported by one. A {@code @Shadow} counts as soon as it is
     * declared {@code mutable} on a field, without asking whether the target's field is actually
     * final; an ordinary shadow, or a mutable shadow of a method, adds nothing and rewrites only
     * references inside merged code. An instance weave with at least one injector is refused after
     * that, whether or not the handler any of its injectors names is one this weave itself merges
     * into the target.
     *
     * @param weave the weave to examine; must not be {@code null}
     * @return the reason, phrased to follow a colon in the diagnostic, or {@code null} when nothing
     *         this weave does would be refused by the JVM on an already-loaded target
     */
    @Contract(pure = true)
    private static String refusalFor(@NotNull final WeaveClass weave) {
        for (final var member : weave.members()) {
            final String what = switch (member) {
                case de.splatgames.aether.weaver.engine.model.WeaveMember.Merged merged ->
                        "it merges " + (merged.isField() ? "the field '" : "the method '")
                                + merged.name() + '\'';
                case de.splatgames.aether.weaver.engine.model.WeaveMember.Accessor accessor ->
                        "it generates the accessor '" + accessor.name() + '\'';
                case de.splatgames.aether.weaver.engine.model.WeaveMember.Invoker invoker ->
                        "it generates the invoker '" + invoker.name() + '\'';
                case de.splatgames.aether.weaver.engine.model.WeaveMember.Shadowed shadowed ->
                        // A shadow adds nothing; it only rewrites references inside merged code.
                        // On its own it is not a reason to refuse — but mutable = true rewrites the
                        // TARGET's field flags, which is a change to an already-defined class.
                        shadowed.mutable() && shadowed.isField()
                                ? "it removes final from the target's field '"
                                        + shadowed.targetName() + '\''
                                : null;
            };
            if (what != null) {
                return what;
            }
        }
        // An instance weave with a handler still merges that handler into the target, which is a
        // new method on an already-defined class. Deciding by kind alone would be a rule about the
        // annotation; this is a rule about what the weave does.
        if (weave.kind() == Weave.Kind.INSTANCE && !weave.injectors().isEmpty()) {
            return "its handlers are merged into the target, which adds methods to it";
        }
        return null;
    }
}
