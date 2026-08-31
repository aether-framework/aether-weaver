package de.splatgames.aether.weaver.agent;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import de.splatgames.aether.weaver.api.diagnostic.Severity;
import de.splatgames.aether.weaver.api.spi.DiagnosticListener;
import de.splatgames.aether.weaver.engine.text.ConsoleText;
import de.splatgames.aether.weaver.engine.Weaver;
import de.splatgames.aether.weaver.engine.model.WeaveClass;
import de.splatgames.aether.weaver.runtime.WeaveDiscovery;
import de.splatgames.aether.weaver.runtime.config.ConfigLayers;
import de.splatgames.aether.weaver.runtime.config.ConfigParser;
import de.splatgames.aether.weaver.runtime.config.WeaverConfig;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;

/**
 * The entry point a JVM calls to start weaving classes as they load.
 *
 * <p>The agent jar's manifest names this class as both {@code Premain-Class} and
 * {@code Agent-Class}, so the same class answers {@code -javaagent} at startup and a dynamic attach
 * to a running JVM. Both paths do the same work; a dynamic attach does two things more, described
 * on {@link #agentmain(String, Instrumentation)}.
 *
 * <h2>What one startup does</h2>
 *
 * <p>Configuration is resolved from two layers, system properties first and the agent's own
 * argument string second. The later layer wins: {@code -Daether.weaver.enabled=false} together with
 * {@code -javaagent:...=enabled=true} weaves, and the same pair with the values swapped between the
 * layers does not. A configuration that switches the agent off prints a summary line — after
 * printing whatever was collected while resolving the configuration, such as a malformed setting —
 * and installs nothing.
 *
 * <p>Weaves are then discovered from the manifests on this class's own class loader, or from the
 * system class loader when this class came from the bootstrap loader. Discovery can come back
 * empty for several reasons: no manifest names a weave, a named class is absent from the artefact
 * that named it ({@code AW2300}), a candidate does not parse as a weave, or every weave a manifest
 * names was switched off by a per-weave override or by {@code tags.include} and
 * {@code tags.exclude}. Whichever it is, a summary line says so — after printing whatever was
 * collected while resolving the configuration and running discovery — and nothing is installed.
 *
 * <p>Otherwise a weaver is built with the load-time driver, so a class that arrives already
 * carrying a different plan's weave record is warned about as {@code AW2202} and woven again rather
 * than refused, and a {@code WeavingTransformer} is installed with retransformation enabled. The
 * run closes with a line naming the version, the number of weaves and targets, the plan
 * fingerprint, the configuration summary and which of the two entry points was called.
 *
 * <p>{@code explain=true} prints the plan on that same closing pass rather than at the end of the
 * run, because a load-time driver has no end: classes go on arriving for as long as the application
 * does. Under {@code premain} the transformer is already installed by the time the report is
 * built, but no application class has loaded yet — only classes the plan does not target, needed
 * to render the report itself, may load in between — so every declaration renders
 * {@code not woven yet} in place of what it matched. Under {@code agentmain}
 * the report is built after the already-loaded targets have been retransformed, so a declaration
 * whose target was among them renders what it matched instead.
 *
 * <h2>What a failure looks like from outside</h2>
 *
 * <p>A plan that cannot be built is printed to {@link System#err} and the exception is rethrown.
 * Under {@code -javaagent} that ends the JVM before the application's {@code main} runs: measured on
 * OpenJDK 25 (Temurin 25.0.3+9, Linux), the JVM reports
 * {@code FATAL ERROR in native method: processing of -javaagent failed} and aborts. Under a dynamic
 * attach it reaches the attaching tool as an {@code AgentInitializationException} and the target JVM
 * carries on unwoven.
 *
 * <h2>What is printed, and what is not</h2>
 *
 * <p>Diagnostics are collected into a list that is drained at each of the four points where
 * installation ends: the two returns that install nothing, the rethrow, and the closing line. Under
 * {@code premain} nothing calls {@code RetransformApplicability.report} or retransforms an
 * already-loaded class, so the closing line prints everything the configuration, the discovery and
 * building the plan had to say — plugin loading and planning diagnostics reach the same listener
 * when the weaver is built, which is neither configuration nor discovery but happens before this
 * line on both entry points. Under {@code agentmain} the same closing line comes after the
 * applicability report and the explicit retransformation, so it additionally prints
 * {@code AW2101} from either of those, along with every diagnostic that weaving the already-loaded
 * targets raised.
 *
 * <p>A diagnostic raised after the closing line is added to the same list, which nothing drains
 * again. Under {@code premain} that is every diagnostic weaving a class produces from then on —
 * for example {@code AW2202} for a class that arrives already woven by another plan,
 * {@code AW4090} for a weave that failed under {@code onError=report} or for a dump that could
 * not be written, and {@code AW2402} for an expanded module graph — all of which reach the
 * listener and stop there. Under {@code agentmain} the same is true only for classes defined
 * after the closing line; a diagnostic from weaving one of the already-loaded targets was raised
 * before the closing line and was printed.
 *
 * <p>Nothing calls {@code Weaver.finish()} on this path, so plugins never see a
 * {@code WeavingFinished} event under the agent.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class WeaverAgent {

    /** The version this agent prints on its startup line. */
    private static final String VERSION = "0.1.0";

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private WeaverAgent() {
        throw new AssertionError("no instances");
    }

    /**
     * Starts weaving before the application's {@code main} method runs.
     *
     * <p>Called by the JVM for {@code -javaagent}. Every class defined from here on passes through
     * the transformer, unless the configuration disables the agent, discovery finds no weave to
     * apply, or building the weave plan fails — in each case nothing is installed, and on the last
     * of those the JVM aborts before the application's {@code main} runs. Classes the JVM had
     * already defined by this point are not retransformed.
     *
     * @param arguments the text after {@code =} on the {@code -javaagent} option, or {@code null}
     *                  when none was given
     * @param inst      the instrumentation the JVM supplies; must not be {@code null}
     */
    public static void premain(@Nullable final String arguments,
                               @NotNull final Instrumentation inst) {
        install(arguments, inst, "premain", false);
    }

    /**
     * Starts weaving in a JVM that is already running.
     *
     * <p>Called by the JVM for a dynamic attach. Two things happen here that do not happen under
     * {@code -javaagent}, both because classes are already loaded. Weaves that would change a
     * loaded target's member set are reported as {@code AW2101} and left in the plan. Loaded
     * classes the plan can still be applied to are then retransformed explicitly, since installing
     * a transformer alone would change nothing that is already in memory.
     *
     * @param arguments the text after {@code =} in the agent options the attaching tool passed, or
     *                  {@code null} when none was given
     * @param inst      the instrumentation the JVM supplies; must not be {@code null}
     */
    public static void agentmain(@Nullable final String arguments,
                                 @NotNull final Instrumentation inst) {
        install(arguments, inst, "agentmain", true);
    }

    /**
     * Resolves the configuration, discovers the weaves and installs the transformer.
     *
     * <p>Returns without installing anything on two of its three early exits, and rethrows on the
     * third. Nothing is undone on the way out: once the transformer is added it stays added, and a
     * retransformation the JVM refuses afterwards is reported rather than treated as a reason to
     * remove it.
     *
     * @param arguments the agent's argument string, or {@code null}
     * @param inst      the instrumentation the JVM supplied; must not be {@code null}
     * @param mode      the entry point's name, printed on the closing line
     * @param dynamic   {@code true} for a dynamic attach, which adds the applicability report and
     *                  the explicit retransformation
     * @throws RuntimeException whatever the weaver builder threw, after printing it
     */
    private static void install(@Nullable final String arguments,
                                @NotNull final Instrumentation inst,
                                @NotNull final String mode,
                                final boolean dynamic) {
        final List<Diagnostic> collected = new ArrayList<>();
        final DiagnosticListener listener = collected::add;

        final ConfigLayers layers = ConfigLayers.of()
                .add("system properties",
                        ConfigParser.ofSystemProperties(System.getProperties(), listener::report))
                // Agent arguments last: they are the most specific thing anyone said about THIS
                // run, and a system property set for the whole machine must not beat them.
                .add("agent arguments", ConfigParser.ofAgentArguments(arguments, listener::report));
        final WeaverConfig config = layers.resolve();

        if (!config.enabled()) {
            print(collected);
            System.out.println(ConsoleText.forStream(
                    "Aether Weaver " + VERSION + " — disabled by configuration.", System.out));
            return;
        }

        final ClassLoader loader = WeaverAgent.class.getClassLoader();
        final WeaveDiscovery.Discovered found = WeaveDiscovery.discover(
                loader == null ? ClassLoader.getSystemClassLoader() : loader, config, listener);
        if (dynamic) {
            // Decided here, from the plan, rather than left to the JVM's
            // UnsupportedOperationException at retransformation time — which names the class rather
            // than the weave, says nothing about which member was added, and arrives once the
            // operator has already attached to a running process.
            //
            // And it only reports. Narrowing the plan would change the fingerprint, so the same
            // weave set attached dynamically would stamp classes differently from the same set under
            // premain — driver-dependent output, which is the one thing the architecture rules out.
            RetransformApplicability.report(found.weaves(), loadedNames(inst), listener);
        }
        final List<WeaveClass> weaves = found.weaves();
        if (weaves.isEmpty()) {
            print(collected);
            System.out.println(ConsoleText.forStream("Aether Weaver " + VERSION + " — no weaves to apply ("
                    + config.summary() + ").", System.out));
            return;
        }

        final Weaver weaver;
        try {
            weaver = Weaver.builder()
                    .driver(Weaver.Driver.LOAD)
                    .weaves(weaves)
                    .classSource(found.classes())
                    .verification(config.verification())
                    .explain(config.explain())
                    .diagnostics(listener)
                    .build();
        } catch (final RuntimeException refused) {
            // Before the transformer exists, so this is an ordinary startup failure with a
            // readable message rather than something that happens inside class loading.
            print(collected);
            System.err.println(ConsoleText.forStream("Aether Weaver " + VERSION + " — the plan could not be built: "
                    + refused.getMessage(), System.err));
            throw refused;
        }

        inst.addTransformer(new WeavingTransformer(weaver, config.onError(),
                config.dumpDirectory(), listener, inst, WeaverAgent.class.getModule()), true);

        if (dynamic) {
            // Installing the transformer is not enough here. A class that is already loaded never
            // reaches it again on its own, and "already loaded" is the whole reason somebody
            // attached — so the applicable targets are retransformed explicitly. Without this the
            // agent attaches, reports success, and changes nothing anyone can see.
            retransform(inst, weaver, listener);
        }

        print(collected);
        if (config.explain()) {
            // Printed here rather than at the end of the run, because a load-time driver has no
            // end: classes keep arriving for as long as the application runs. So the report a
            // -javaagent prints is complete about the plan and silent about what each point
            // matched, and says "not woven yet" rather than pretending it found nothing.
            weaver.report().ifPresent(report ->
                    report.configuration(config.summary(), layers.settings()));
            System.out.println(ConsoleText.forStream(weaver.explain(), System.out));
        }
        System.out.println(ConsoleText.forStream("Aether Weaver " + VERSION + " — " + weaves.size() + " weave"
                + (weaves.size() == 1 ? "" : "s") + ", " + weaver.plan().targets().size()
                + " target" + (weaver.plan().targets().size() == 1 ? "" : "s")
                + ", fingerprint " + weaver.fingerprint() + ", " + config.summary()
                + " (" + mode + ").", System.out));
    }

    /**
     * Retransforms the already-loaded classes the plan can still be applied to.
     *
     * <p>A loaded class is a candidate only if the plan holds an injection entry for it, holds no
     * dissolving weave against it, and {@link Instrumentation#isModifiableClass(Class)} accepts it.
     * A weave dissolves when it is an instance weave with members or with a handler of its own, and
     * skipping those is what keeps this from asking for a redefinition the JVM answers with
     * {@link UnsupportedOperationException}. That test and the one behind {@code AW2101} are not
     * the same test, so a class skipped here was not necessarily reported. A class the plan reaches
     * only through an extension is not a candidate either, since the plan holds no entry for it.
     *
     * <p>All candidates go in one {@link Instrumentation#retransformClasses(Class...)} call, so a
     * refusal loses all of them together. It is reported as {@code AW2101} and not thrown: the
     * transformer is installed by this point and everything loading from here on is still woven.
     *
     * @param inst     the instrumentation to retransform through; must not be {@code null}
     * @param weaver   the weaver whose plan decides which classes are candidates; must not be
     *                 {@code null}
     * @param listener where a refusal is reported; must not be {@code null}
     */
    private static void retransform(@NotNull final Instrumentation inst,
                                    @NotNull final Weaver weaver,
                                    @NotNull final DiagnosticListener listener) {
        final List<Class<?>> targets = new ArrayList<>();
        for (final Class<?> loaded : inst.getAllLoadedClasses()) {
            final String internalName = loaded.getName().replace('.', '/');
            if (weaver.plan().entriesFor(internalName).isEmpty()
                    || !weaver.plan().structuralFor(internalName).isEmpty()
                    || !inst.isModifiableClass(loaded)) {
                continue;
            }
            targets.add(loaded);
        }
        if (targets.isEmpty()) {
            return;
        }
        try {
            inst.retransformClasses(targets.toArray(new Class<?>[0]));
        } catch (final java.lang.instrument.UnmodifiableClassException
                | UnsupportedOperationException | LinkageError refused) {
            // Reported rather than thrown: the transformer is installed and everything that loads
            // from here on is still woven. Throwing would undo a working agent over a class that
            // was already running.
            listener.report(Diagnostic.builder(DiagnosticCode.STRUCTURAL_WEAVE_NEEDS_PRELOAD)
                    .message("the JVM refused to retransform " + targets.size() + " already-loaded "
                            + "target" + (targets.size() == 1 ? "" : "s") + ": "
                            + refused.getMessage())
                    .detail("classes that load from now on are still woven")
                    .remedy("weave at build time, or start the JVM with -javaagent")
                    .build());
        }
    }

    /**
     * Collects the binary names of every class the JVM has defined so far.
     *
     * <p>A snapshot, taken while the application is running and before the transformer is
     * installed. A class defined in the window between this call and the transformer's installation
     * is not reported as already loaded here, but {@link #retransform(Instrumentation, Weaver,
     * DiagnosticListener)} takes its own, later snapshot of loaded classes after the transformer
     * exists, so such a class can still reach it there.
     *
     * @param inst the instrumentation to ask; must not be {@code null}
     * @return the binary names, such as {@code com.acme.Ledger}
     */
    @NotNull
    private static java.util.Set<String> loadedNames(@NotNull final Instrumentation inst) {
        final java.util.Set<String> names = new java.util.HashSet<>();
        for (final Class<?> loaded : inst.getAllLoadedClasses()) {
            names.add(loaded.getName());
        }
        return names;
    }

    /**
     * Prints the diagnostics collected so far and empties the list.
     *
     * <p>Every one of them is printed, at every severity: {@link Severity#ERROR} goes to
     * {@link System#err} and everything else to {@link System#out}, and nothing is filtered or
     * suppressed. The text is {@link Diagnostic#format()}, which is several lines for a diagnostic
     * carrying details or a remedy, prefixed with {@code "Aether Weaver: "} on its first line only.
     * It is written as it stands, where this class's own lines go through {@link ConsoleText}
     * first, so a character the stream's charset cannot encode reaches the encoder's substitute:
     * measured on OpenJDK 25 (Temurin 25.0.3+9, Linux), an em dash printed to a {@code US-ASCII}
     * stdout arrives as {@code ?}.
     *
     * <p>This is called only while the agent is installing. A diagnostic reported afterwards is
     * added to the same list, which nothing drains again.
     *
     * @param diagnostics the diagnostics collected since the last call; must not be {@code null},
     *                    and is cleared
     */
    private static void print(@NotNull final List<Diagnostic> diagnostics) {
        for (final Diagnostic diagnostic : diagnostics) {
            final java.io.PrintStream out =
                    diagnostic.severity() == Severity.ERROR ? System.err : System.out;
            out.println("Aether Weaver: " + diagnostic.format());
        }
        diagnostics.clear();
    }
}
