package de.splatgames.aether.weaver.agent;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import de.splatgames.aether.weaver.api.spi.DiagnosticListener;
import de.splatgames.aether.weaver.engine.text.ConsoleText;
import de.splatgames.aether.weaver.engine.Weaver;
import de.splatgames.aether.weaver.engine.dump.ClassDump;
import de.splatgames.aether.weaver.runtime.config.ErrorPolicy;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.Objects;

/**
 * The transformer {@code WeaverAgent} installs, through which every class the JVM defines or
 * retransforms afterwards passes.
 *
 * <p>Three things happen per woven class, in this order: the weaver produces the new bytes, the
 * target's module is made to read the module this agent itself was loaded into if it has to be,
 * and the before and after bytes are dumped when a dump directory was configured. A class the plan
 * says nothing about is answered with {@code null}, which tells the JVM to keep the bytes it
 * already has.
 *
 * <p>A failure anywhere in that sequence is caught. Measured on OpenJDK 25 (Temurin 25.0.3+9,
 * Linux): a {@link RuntimeException} and an {@link Error} thrown out of
 * {@link #transform(Module, ClassLoader, String, Class, ProtectionDomain, byte[])} are both
 * discarded by the JVM without a message, and the class is defined from the original bytes, so the
 * catch here is the last point at which the configured error policy can still be acted on.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
final class WeavingTransformer implements ClassFileTransformer {

    /**
     * The exit status a run halted by {@link ErrorPolicy#FAIL} leaves behind.
     *
     * <p>Measured on OpenJDK 25 (Temurin 25.0.3+9, Linux): a {@code Runtime.halt(70)} called from
     * inside a class file transformer ends the process with status 70.
     */
    private static final int HALT_STATUS = 70;

    /** Produces the woven bytes, and reports everything it finds to {@link #listener}. */
    private final Weaver weaver;

    /** Whether a weaving failure ends the JVM or lets the class through unwoven. */
    private final ErrorPolicy onError;

    /** Where the before and after bytes are written, or {@code null} when none was configured. */
    private final @Nullable ClassDump dump;

    /** Where every diagnostic raised while weaving is reported. */
    private final DiagnosticListener listener;

    /** Used to expand the module graph, and nothing else. */
    private final Instrumentation instrumentation;

    /**
     * The module this agent itself lives in, passed to {@link ModuleAccess#grant} as the module a
     * woven target may need read access to; {@code null} means no read edge is ever granted. This is
     * not the module the weave class that declared the call belongs to — it is whatever module
     * {@code WeaverAgent} was loaded into, which coincides with the weave class's module when both
     * are on the class path.
     */
    private final @Nullable Module weaveModule;

    /**
     * Assembles the transformer from what the agent resolved.
     *
     * @param weaver          the weaver to run each class through; must not be {@code null}
     * @param onError         what to do when weaving a class fails; must not be {@code null}
     * @param dumpDirectory   where to dump before and after bytes, or {@code null} to dump nothing
     * @param listener        where diagnostics are reported; must not be {@code null}
     * @param instrumentation the instrumentation to expand the module graph through; must not be
     *                        {@code null}
     * @param weaveModule     the module {@code WeaverAgent} itself was loaded into, passed on as the
     *                        module a woven target may need to read; {@code null} to never expand
     *                        the module graph
     * @throws NullPointerException if {@code weaver}, {@code onError}, {@code listener} or
     *                              {@code instrumentation} is {@code null}
     */
    WeavingTransformer(@NotNull final Weaver weaver,
                       @NotNull final ErrorPolicy onError,
                       @Nullable final Path dumpDirectory,
                       @NotNull final DiagnosticListener listener,
                       @NotNull final Instrumentation instrumentation,
                       @Nullable final Module weaveModule) {
        this.weaver = Objects.requireNonNull(weaver, "weaver");
        this.onError = Objects.requireNonNull(onError, "onError");
        this.dump = dumpDirectory == null ? null : new ClassDump(dumpDirectory);
        this.listener = Objects.requireNonNull(listener, "listener");
        this.instrumentation = Objects.requireNonNull(instrumentation, "instrumentation");
        this.weaveModule = weaveModule;
    }

    /**
     * Weaves one class, or hands it back untouched.
     *
     * <p>A class the JVM offers without a name is declined outright: the plan is keyed by class
     * name and has nothing to compare such a class against.
     *
     * <p>The module graph is expanded and the dump is written whenever {@code weaver.weave} returns
     * a non-{@code null} array, which happens both for a class a weave actually changed and, under
     * {@link de.splatgames.aether.weaver.engine.verify.VerificationPolicy#REPORT}, for a class the
     * verifier refused and handed back unchanged. A run in which no weave matches the class at all
     * leaves the module graph and the dump directory exactly as it found them.
     *
     * @param module         the module the class is being defined in; may be {@code null}
     * @param loader         the loader defining the class; {@code null} for the bootstrap loader
     * @param className      the class's internal name, such as {@code com/acme/Ledger}; may be
     *                       {@code null}
     * @param beingRedefined the class as it stands on a redefinition or retransformation, and
     *                       {@code null} on a first definition; not consulted
     * @param domain         the protection domain of the class; not consulted
     * @param buffer         the class as it stands; not modified
     * @return the woven class, or {@code null} to leave the JVM's own bytes in place, which is also
     *         the answer when {@code className} is {@code null} and when weaving failed under
     *         {@link ErrorPolicy#REPORT}
     */
    @Override
    public byte @Nullable [] transform(@Nullable final Module module,
                                       @Nullable final ClassLoader loader,
                                       @Nullable final String className,
                                       @Nullable final Class<?> beingRedefined,
                                       @Nullable final ProtectionDomain domain,
                                       final byte @NotNull [] buffer) {
        // Contract point 2. A hidden class or a lambda proxy has no name, and there is nothing a
        // plan keyed by name could match against it.
        if (className == null) {
            return null;
        }
        try {
            final byte[] woven = this.weaver.weave(className, buffer);
            if (woven == null) {
                // Contract point 1. Returning `buffer` here would be correct-looking and would make
                // the JVM re-verify every class in the application.
                return null;
            }
            // Only after the class really was woven. Expanding the module graph for a class
            // nothing was done to would widen what an application may reach, for nothing.
            ModuleAccess.grant(this.instrumentation, module, this.weaveModule, className,
                    this.listener);
            if (this.dump != null) {
                this.dump.write(className, buffer, woven, this.listener);
            }
            return woven;
        } catch (final Throwable failure) {
            // Contract point 3. The JVM discards whatever is thrown from here and continues with
            // the original bytes, so this is the only place the policy can still be applied.
            return handle(className, failure);
        }
    }

    /**
     * Applies the error policy to a class that could not be woven.
     *
     * <p>{@code AW4090} is reported first, carrying the failure's message and the name of its
     * class, and that report is all a run under {@link ErrorPolicy#REPORT} produces: the class then
     * loads unwoven and the application carries on. The report goes to the listener the agent
     * installed, which the agent drains only while it is installing. A failure raised while
     * {@code agentmain} retransforms the already-loaded targets happens before that drain and is
     * printed; a failure raised while an ordinary class loads afterward, under either entry point,
     * happens after the drain and prints nothing at all. Under {@link ErrorPolicy#FAIL} three lines
     * and the stack trace go to {@link System#err} and the process is halted with
     * {@link #HALT_STATUS}, regardless of which of the two this failure was.
     *
     * <p>Halted rather than exited, because this runs on a class-loading thread and a shutdown hook
     * would run application code in a state the application never designed for. No hook runs, and
     * nothing buffered elsewhere is flushed.
     *
     * @param className the internal name of the class that failed; must not be {@code null}
     * @param failure   what was thrown; must not be {@code null}
     * @return {@code null}, so that the caller hands the JVM the original bytes; under
     *         {@link ErrorPolicy#FAIL} the method does not return
     */
    private byte @Nullable [] handle(@NotNull final String className,
                                     @NotNull final Throwable failure) {
        this.listener.report(Diagnostic.builder(DiagnosticCode.INTERNAL_ERROR)
                .message("weaving " + className.replace('/', '.') + " failed: "
                        + failure.getMessage())
                .detail(failure.getClass().getName())
                .build());

        if (this.onError == ErrorPolicy.REPORT) {
            return null;
        }
        System.err.println(ConsoleText.forStream("Aether Weaver: halting — weaving " + className.replace('/', '.')
                + " failed and onError=fail.", System.err));
        System.err.println(ConsoleText.forStream("  Continuing would run an application whose weaves did not apply, "
                + "which surfaces later as behaviour nobody can connect to a cause.", System.err));
        System.err.println(ConsoleText.forStream(
                "  Set aether.weaver.onError=report to continue instead.", System.err));
        failure.printStackTrace(System.err);
        System.err.flush();
        // halt, not exit: this runs during class loading, and shutdown hooks would execute
        // application code in a state the application never designed for.
        Runtime.getRuntime().halt(HALT_STATUS);
        return null;
    }

    /**
     * Describes this transformer for a log or a debugger, naming the error policy because it is the
     * only part of the configuration that changes what this transformer does with a class it cannot
     * weave.
     *
     * @return a description of this transformer
     */
    @Override
    @NotNull
    public String toString() {
        return "WeavingTransformer[onError=" + this.onError + ']';
    }
}
