/**
 * The load-time driver: the entry points a JVM calls to start weaving, and the transformer they install.
 *
 * <p>Everything in this package runs inside the JVM that is being woven, on classes the JVM is defining or
 * retransforming, rather than over a directory of class files. {@link de.splatgames.aether.weaver.agent.WeaverAgent}
 * is the only public type here; {@code WeavingTransformer}, {@code ModuleAccess} and
 * {@code RetransformApplicability} are package-private and exist only to serve it.
 *
 * <p>The package contributes the driver and nothing else. Reading weave manifests, parsing the configuration and
 * turning weave classes into a plan all belong elsewhere: {@link de.splatgames.aether.weaver.runtime.WeaveDiscovery}
 * finds the weaves, {@link de.splatgames.aether.weaver.runtime.config.ConfigParser} and
 * {@link de.splatgames.aether.weaver.runtime.config.ConfigLayers} resolve the configuration, and
 * {@link de.splatgames.aether.weaver.engine.Weaver} plans and rewrites the bytes. What this package adds is the
 * three things only a running JVM needs: an entry point, a
 * {@link java.lang.instrument.ClassFileTransformer}, and the handling of classes that were already loaded before
 * the agent arrived.
 *
 * <h2>How a JVM reaches this package</h2>
 *
 * <p>The jar this module builds carries a manifest naming
 * {@link de.splatgames.aether.weaver.agent.WeaverAgent} as both {@code Premain-Class} and {@code Agent-Class}, and
 * declaring {@code Can-Retransform-Classes} and {@code Can-Redefine-Classes}. A {@code -javaagent} option therefore
 * enters at
 * {@link de.splatgames.aether.weaver.agent.WeaverAgent#premain(String, java.lang.instrument.Instrumentation)}, and a
 * dynamic attach at
 * {@link de.splatgames.aether.weaver.agent.WeaverAgent#agentmain(String, java.lang.instrument.Instrumentation)}. Both
 * run the same installation, and the attach path does two things more, both because classes are already in memory.
 *
 * <h2>What one installation does</h2>
 *
 * <p>In this order.
 *
 * <ol>
 *   <li><b>The configuration is resolved</b> from two layers, {@code system properties} first and
 *       {@code agent arguments} second. A scalar setting takes the later layer's value when the later layer set
 *       one; the per-weave and per-injection override maps are merged key by key, and where both layers name the
 *       same weave or injection the two overrides are merged component-wise rather than one replacing the other;
 *       and the verification policy's relaxations from both layers accumulate, so a relaxation the first layer
 *       grants cannot be withdrawn by the second. The keys are the ones
 *       {@link de.splatgames.aether.weaver.runtime.config.ConfigParser} names, written with the
 *       {@code aether.weaver.} prefix as a system property and without it in the agent's argument string, so
 *       {@code -javaagent:aether-weaver-agent.jar=explain=true,verification=report} and
 *       {@code -Daether.weaver.explain=true -Daether.weaver.verification=report} ask for the same run. A key or a
 *       pair that cannot be read is reported as {@code AW2310} and ignored, except a {@code dump} value that is
 *       not a valid path: that throws {@link java.nio.file.InvalidPathException} out of the installation, unread
 *       and unreported, and leaves every entry after it unread as well.
 *   <li><b>A configuration with weaving switched off ends the installation.</b> Nothing is installed, and one line
 *       ending {@code disabled by configuration} is printed.
 *   <li><b>The weaves are discovered</b> from the weave manifests visible to
 *       {@link de.splatgames.aether.weaver.agent.WeaverAgent}'s own class loader, or to the system class loader when
 *       that class loader is the bootstrap one. A manifest naming a class the artefact does not hold is reported as
 *       {@code AW2300} and that weave is passed over; the remaining candidates are parsed and then filtered by the
 *       tag filter and the per-weave overrides.
 *   <li><b>On a dynamic attach only, the plan is checked against the classes already loaded.</b> A weave that would
 *       change a loaded target's member set is reported as {@code AW2101}, once per weave, naming the loaded targets
 *       and the one thing that made it structural — usually a member of the weave, but an {@code INSTANCE} weave
 *       with handlers and no other structural cause is reported for merging its handlers into the target, naming
 *       no member at all. It is reported and kept: narrowing the plan would change the
 *       weaver's fingerprint, so one weave set would stamp classes differently depending on how the agent was
 *       started.
 *   <li><b>Discovery coming back empty ends the installation.</b> Nothing is installed, and one line carrying
 *       {@code no weaves to apply} and the configuration summary is printed. That line reads the same whether no
 *       manifest named a weave, none of the candidates parsed, or every one of them was switched off.
 *   <li><b>The weaver is built</b> with {@link de.splatgames.aether.weaver.engine.Weaver.Driver#LOAD}, the discovered
 *       weaves, the configured verification policy and the configured explain flag. A plan that fails to build with
 *       a {@link java.lang.RuntimeException} is printed to {@link java.lang.System#err}, together with the
 *       diagnostics collected so far, and the exception is rethrown out of the entry point. An {@link java.lang.Error}
 *       building the plan skips both the printed line and the diagnostic drain and propagates directly.
 *   <li><b>The transformer is installed</b> with retransformation requested.
 *   <li><b>On a dynamic attach only, the applicable loaded classes are retransformed</b> in a single
 *       {@link java.lang.instrument.Instrumentation#retransformClasses(Class...)} call. A loaded class is a candidate
 *       when the plan holds an injection entry for it, holds no structural entry against it, and
 *       {@link java.lang.instrument.Instrumentation#isModifiableClass(Class)} accepts it. A refusal loses all the
 *       candidates together and is reported as {@code AW2101} rather than thrown, because the transformer is already
 *       installed and everything loading afterwards is still woven. That candidacy test and the one behind the
 *       {@code AW2101} of the previous step are not the same test, so a class skipped here was not necessarily
 *       reported there.
 *   <li><b>The collected diagnostics are printed</b>, then the explain report when one was asked for, then a closing
 *       line naming the version, the number of weaves and targets, the plan fingerprint, the configuration summary
 *       and which of the two entry points was called.
 * </ol>
 *
 * <h2>What happens to each class afterwards</h2>
 *
 * <p>A class the transformer is offered without a name is declined, because the plan is keyed by class name.
 * Otherwise the weaver is asked for the class; when it answers with no bytes the transformer answers the JVM the
 * same way, which leaves the JVM's own bytes in place rather than handing back a copy the JVM would have to verify
 * again.
 *
 * <p>When the weaver does produce bytes, two further things happen before they are returned. The target's module is
 * made to read the module {@link de.splatgames.aether.weaver.agent.WeaverAgent} itself was loaded into, if that edge
 * is missing and both modules are named; adding it, and a {@link java.lang.RuntimeException} refusing to add it, are
 * both reported as {@code AW2402}. An {@link java.lang.Error} out of that attempt is not caught here and is caught,
 * and reported as {@code AW4090}, by the class-weaving catch described below instead. Then the original bytes, the
 * woven bytes and a textual difference between them are written to the configured dump directory, if one was
 * configured; a dump that cannot be written is reported as {@code AW4090} and costs nothing but the dump.
 *
 * <p>Anything thrown while weaving one class is caught. Measured on OpenJDK 25 (Temurin 25.0.3+9, Linux), a
 * {@link java.lang.RuntimeException} and an {@link java.lang.Error} thrown out of
 * {@link java.lang.instrument.ClassFileTransformer#transform(Module, ClassLoader, String, Class,
 * java.security.ProtectionDomain, byte[])} are both discarded by the JVM without a message and the class is defined
 * from the original bytes, which makes that catch the last point at which the error policy can still be acted on.
 * The failure is reported as {@code AW4090} and the policy decides what follows:
 * {@link de.splatgames.aether.weaver.runtime.config.ErrorPolicy#REPORT}
 * lets that class load unwoven and the application carry on, while
 * {@link de.splatgames.aether.weaver.runtime.config.ErrorPolicy#FAIL} prints the failure and its stack trace to
 * {@link java.lang.System#err} and halts the process with status 70. It halts rather than exits, so no shutdown hook
 * runs and nothing buffered elsewhere is flushed.
 *
 * <p>A class that arrives already carrying a different plan's weave record is not refused on this driver. The engine
 * reports it as {@code AW2202} and weaves it again, so both plans apply and any weave they have in common runs
 * twice.
 *
 * <h2>When a diagnostic is printed, and when it is not</h2>
 *
 * <p>Every diagnostic raised anywhere on this path goes to one list. That list is drained at each of the four points
 * where installation ends: the two returns that install nothing, the rethrow, and the closing line. Draining prints
 * every diagnostic at every severity, unfiltered, with {@code Aether Weaver: } in front of the first line of each,
 * sending {@link de.splatgames.aether.weaver.api.diagnostic.Severity#ERROR} to {@link java.lang.System#err} and
 * everything else to {@link java.lang.System#out}.
 *
 * <p>Nothing drains that list again. A diagnostic raised after the closing line is added to it and stays there, so
 * under {@code premain} everything ordinary class loading later produces — {@code AW2202}, {@code AW2402},
 * {@code AW4090} — reaches the listener and is not printed. Under {@code agentmain} the already-loaded targets are
 * retransformed before the closing line, so whatever weaving them raised is printed; only classes defined afterwards
 * fall into the same silence.
 *
 * <p>A failure under {@link de.splatgames.aether.weaver.runtime.config.ErrorPolicy#FAIL} is the exception. Its three
 * lines and its stack trace go to {@link java.lang.System#err} directly rather than through that list, so they are
 * printed whenever it fires, at whatever point in the run.
 *
 * <h2>What this package does not do</h2>
 *
 * <ul>
 *   <li><b>It never undoes anything.</b> Once the transformer is added it is never removed, and a retransformation
 *       the JVM refuses is reported rather than treated as a reason to remove it.
 *   <li><b>It never ends the weaving session.</b> {@link de.splatgames.aether.weaver.engine.Weaver#finish()} is not
 *       called on this path, so a plugin observing the weaver never receives a {@code WeavingFinished} event. A
 *       load-time driver has no end: classes go on arriving for as long as the application does, which is also why
 *       an explain report asked for under {@code premain} renders {@code not woven yet} for every declaration whose
 *       target has not yet loaded by the time the report is built — under {@code premain} the transformer is
 *       already installed but no application class has loaded yet, so ordinarily every declaration falls into that
 *       case, but a targeted class defined in that narrow window still renders what it matched.
 *   <li><b>{@code premain} touches nothing that is already defined.</b> Classes the JVM had defined before the agent
 *       ran are not retransformed, and only {@code agentmain} reports {@code AW2101} or retransforms anything
 *       explicitly.
 *   <li><b>It reads only part of the resolved configuration.</b> Weaving being enabled, the verification policy, the
 *       error policy, the dump directory, the explain flag and the summary are read here; the tag filter and the
 *       per-weave {@code enabled} overrides are consulted by discovery. This list is not exhaustive: settings such
 *       as {@code weave[...].priority}, {@code injector[...].enabled}, {@code phase} and {@code policy.*} are
 *       parsed and resolved but read by nothing on this path.
 *   <li><b>It is not the only driver.</b> {@link de.splatgames.aether.weaver.runtime.WeavingClassLoader} weaves the
 *       classes it defines itself, and the Maven plugin weaves a directory of class files at build time. This
 *       package is the one that needs an {@link java.lang.instrument.Instrumentation}.
 * </ul>
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
package de.splatgames.aether.weaver.agent;
