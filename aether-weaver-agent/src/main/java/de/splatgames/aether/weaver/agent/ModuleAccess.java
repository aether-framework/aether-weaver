package de.splatgames.aether.weaver.agent;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import de.splatgames.aether.weaver.api.spi.DiagnosticListener;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.instrument.Instrumentation;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Grants the read edge a woven class needs when the weave it now calls lives in a named module.
 *
 * <p>Reached from {@code WeavingTransformer} whenever weaving a class returns a non-{@code null}
 * array, which happens both for a class a weave actually changed and, under
 * {@link de.splatgames.aether.weaver.engine.verify.VerificationPolicy#REPORT}, for a class the
 * verifier refused and handed back unchanged. A weave class on the classpath needs nothing done
 * here: the JVM itself grants the named target's module the
 * read edge to the unnamed module by the time the class has been defined, without
 * {@link Instrumentation#redefineModule(Module, Set, Map, Map, Set, Map)} ever being called. This
 * was measured on OpenJDK 25 (Temurin 25.0.3+9, Linux).
 *
 * <p>A weave class in a named module is the case that needs the edge added: without
 * {@link Instrumentation#redefineModule(Module, Set, Map, Map, Set, Map)} the woven class still
 * loads and the first execution of the woven instruction throws {@link IllegalAccessError}; with it
 * the call runs. Also measured on OpenJDK 25 (Temurin 25.0.3+9, Linux). Either outcome is reported
 * as {@code AW2402}.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
final class ModuleAccess {

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private ModuleAccess() {
        throw new AssertionError("no instances");
    }

    /**
     * Decides whether the target has to be made to read the weave before the woven code can link.
     *
     * <p>An unnamed weave answers {@code false} whatever the target is, because the JVM grants that
     * edge itself as it defines the woven class. The edge is therefore still absent at the moment
     * this is asked.
     *
     * @param target the module of the class being woven; may be {@code null}
     * @param weave  the module of the weave class; may be {@code null}
     * @return {@code true} only when both modules are present and named and the target does not
     *         read the weave already
     */
    @Contract(pure = true)
    static boolean needsReadEdge(@Nullable final Module target, @Nullable final Module weave) {
        if (target == null || weave == null || !target.isNamed()) {
            // An unnamed target reads everything. There is nothing to grant.
            return false;
        }
        if (!weave.isNamed()) {
            // The measured case: the JVM grants a transformed class the edge to the unnamed
            // module itself. Calling redefineModule here would be a no-op that looked like a
            // safeguard, and safeguards nobody needs are how real ones stop being noticed.
            return false;
        }
        return !target.canRead(weave);
    }

    /**
     * Adds the read edge when one is needed, and reports {@code AW2402} whether it was added or
     * refused.
     *
     * <p>A {@link RuntimeException} out of {@link Instrumentation#redefineModule} is caught and
     * reported rather than thrown: this runs inside {@code transform}, where the JVM discards
     * anything thrown, and a refusal leaves the class woven, so the failure arrives later as an
     * {@link IllegalAccessError} out of the first execution of the woven instruction, which is what
     * the diagnostic's detail line says. An {@link Error} out of {@code redefineModule} is not
     * caught and leaves this method the same way it left {@code redefineModule}.
     *
     * @param inst      the instrumentation to redefine the module through; must not be {@code null}
     * @param target    the module of the class being woven; may be {@code null}
     * @param weave     the module of the weave class; may be {@code null}
     * @param className the internal name of the class being woven, which the diagnostic reports as
     *                  a binary name; must not be {@code null}
     * @param listener  where {@code AW2402} is reported; must not be {@code null}
     * @return {@code true} when the edge was added, {@code false} when none was needed or the JVM
     *         refused to add it
     * @throws NullPointerException if {@code inst}, {@code className} or {@code listener} is
     *                              {@code null}
     */
    static boolean grant(@NotNull final Instrumentation inst,
                         @Nullable final Module target,
                         @Nullable final Module weave,
                         @NotNull final String className,
                         @NotNull final DiagnosticListener listener) {
        Objects.requireNonNull(inst, "inst");
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(listener, "listener");

        if (!needsReadEdge(target, weave)) {
            return false;
        }
        try {
            inst.redefineModule(target, Set.of(weave), Map.of(), Map.of(), Set.of(), Map.of());
        } catch (final RuntimeException refused) {
            // Reported rather than thrown: this runs inside transform, where anything thrown is
            // discarded by the JVM. The class is still woven, and the failure will surface as an
            // IllegalAccessError — so saying so now is the only chance to connect the two.
            listener.report(Diagnostic.builder(DiagnosticCode.MODULE_GRAPH_EXPANDED)
                    .message("module " + target.getName() + " could not be made to read "
                            + weave.getName() + " for " + className.replace('/', '.') + ": "
                            + refused.getMessage())
                    .detail("the woven class will verify and then throw IllegalAccessError the "
                            + "first time the injected instruction runs")
                    .remedy("put the weave class on the classpath instead of in a named module — "
                            + "the JVM then grants the edge itself")
                    .build());
            return false;
        }

        // Reported even on success. Expanding an application's module graph is a change to what
        // its code is permitted to reach, and a change of that kind that leaves no trace is one
        // nobody reviewing the deployment can find.
        listener.report(Diagnostic.builder(DiagnosticCode.MODULE_GRAPH_EXPANDED)
                .message("module " + target.getName() + " now reads " + weave.getName()
                        + ", so that " + className.replace('/', '.')
                        + " can reach the code woven into it")
                .detail("this is needed only because the weave class lives in a named module; on "
                        + "the classpath the JVM grants the edge itself")
                .build());
        return true;
    }
}
