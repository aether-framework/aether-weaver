/**
 * The runtime driver: finding the weaves a classpath declares, and weaving the classes a loader defines itself.
 *
 * <p>Two things, only one of which is a driver. {@link de.splatgames.aether.weaver.runtime.WeavingClassLoader} is
 * the driver: an application that controls its own class loading hands it the roots it wants woven and receives a
 * {@link java.net.URLClassLoader} that weaves the classes it defines from them, apart from the exceptions listed
 * in the closing section of this page.
 * {@link de.splatgames.aether.weaver.runtime.WeaveDiscovery} is the step in front of it, and it is shared rather
 * than private to that driver — the load-time agent calls the same method once from its own entry point, so both
 * runtime drivers find their weaves the same way and answer a wrong classpath with the same diagnostics.
 *
 * <h2>What is here</h2>
 *
 * <ul>
 *   <li>{@link de.splatgames.aether.weaver.runtime.ManifestWeaveSource} — the only implementation of
 *       {@link de.splatgames.aether.weaver.api.spi.WeaveSource} in this project. It reads every
 *       {@value de.splatgames.aether.weaver.api.manifest.WeaveManifest#RESOURCE} a loader can reach and answers
 *       with one {@link de.splatgames.aether.weaver.api.model.WeaveCandidate} per weave named, each reading its
 *       bytes from the artefact whose manifest named it rather than from the classpath as a whole. Two artefacts
 *       may declare the same class name and each candidate still carries the copy shipped beside its own
 *       manifest. No weave class is parsed or validated here: a candidate is a class name and somewhere to read
 *       it from. The manifest document naming those candidates is parsed and validated by
 *       {@link de.splatgames.aether.weaver.api.manifest.ManifestReader}, which is where {@code AW2300} and
 *       {@code AW2301} for a malformed manifest are raised.
 *   <li>{@link de.splatgames.aether.weaver.runtime.WeaveDiscovery} — the shared step. It constructs a
 *       {@link de.splatgames.aether.weaver.runtime.ManifestWeaveSource}, hands each candidate's bytes to
 *       {@link de.splatgames.aether.weaver.engine.parse.WeaveClassParser}, drops what configuration switches off,
 *       and returns a {@link de.splatgames.aether.weaver.runtime.WeaveDiscovery.Discovered} carrying the parsed
 *       weaves together with a {@link de.splatgames.aether.weaver.api.spi.ClassSource} over precisely those weave
 *       classes' own bytes.
 *   <li>{@link de.splatgames.aether.weaver.runtime.WeavingClassLoader} — the driver, and the only type here that
 *       defines a class.
 *   <li>{@code AotCache} — package-private, and consulted from one place: the constructor of
 *       {@link de.splatgames.aether.weaver.runtime.WeavingClassLoader}. It reads the arguments this JVM was
 *       started with rather than the state of any cache, and decides which flags count as naming one.
 * </ul>
 *
 * <h2>A run through the class loader</h2>
 *
 * <p>{@link de.splatgames.aether.weaver.runtime.WeavingClassLoader#create(java.net.URL[], java.lang.ClassLoader,
 * de.splatgames.aether.weaver.runtime.config.WeaverConfig,
 * de.splatgames.aether.weaver.api.spi.DiagnosticListener)} does the following, in this order, before it returns.
 *
 * <ol>
 *   <li><b>A second {@link java.net.URLClassLoader} over the same roots is opened for discovery alone</b> and
 *       never defines anything. Reading the manifests through the loader being built would define its classes
 *       before its weaver existed. That loader stays open for the returned loader's lifetime, because it backs
 *       the fallback branch of the class source the weaver is given, and
 *       {@link de.splatgames.aether.weaver.runtime.WeavingClassLoader#close()} closes it.
 *   <li><b>The weaves are discovered</b> through that loader. Everything discovery reports arrives on the given
 *       listener during this call, before the returned loader has defined anything.
 *   <li><b>The weaver is built</b> as a {@link de.splatgames.aether.weaver.engine.Weaver.Driver#LOAD} one, so a
 *       class arriving with another plan's weave record is reported as {@code AW2202} and woven again rather than
 *       refused, and both plans then apply. It is given the discovered weaves, the weave classes' own bytes
 *       falling back to everything the roots can see, and the configured verification policy and explain flag.
 *   <li><b>The loader is constructed</b>, which is where {@code AW2401} is reported.
 * </ol>
 *
 * <p>The public constructor skips the first three steps entirely: it takes a
 * {@link de.splatgames.aether.weaver.engine.Weaver} the caller already built, reads no manifest, and still
 * reports {@code AW2401}. What that weaver cannot see, it does not weave.
 *
 * <p>Afterwards, per class: delegation is ordinary parent-first, so only classes this loader defines are woven
 * and a target its parent can also see is defined by the parent, unwoven and without a diagnostic. Bytes are
 * looked up on the loader's own roots with no delegation, and its package is defined from the artefact's
 * manifest so that a sealed package stays sealed, before the signed-artefact policy is applied and the weaver
 * is asked. The dump is written only when one was configured and the weaver returned bytes to weave — a signed
 * artefact denied by policy, a weave that threw under
 * {@link de.splatgames.aether.weaver.runtime.config.ErrorPolicy#REPORT}, and a weaver that answered {@code null}
 * all skip it. The class is then defined keeping the {@link java.security.CodeSource} the bytes were read
 * from.
 *
 * <h2>What this package reports</h2>
 *
 * <ul>
 *   <li><b>{@code AW2302}</b> — no manifest could be listed at all, reported by
 *       {@link de.splatgames.aether.weaver.runtime.ManifestWeaveSource}. The usual reason is that
 *       {@code aether-weaver-processor} was not on the annotation processor path of the modules declaring
 *       weaves, and the diagnostic says so; add it as a provided-scope dependency of each of them. It is also
 *       reported when the classpath itself could not be searched, where it accompanies {@code AW2300} and its
 *       missing-processor framing does not fit. No candidate is produced either way.
 *   <li><b>{@code AW2300}</b> — something that should have been readable was not, under four distinct
 *       conditions. The classpath could not be searched, and the whole run then yields no candidate. One manifest
 *       could not be read, or the artefact holding it could not be identified, and only that root is lost while
 *       the remaining manifests are still read. Or, from
 *       {@link de.splatgames.aether.weaver.runtime.WeaveDiscovery} rather than the source, a candidate's class is
 *       not in the artefact that named it, and that weave alone is skipped; rebuild the artefact, whose manifest
 *       has outlived the class. An artefact that could not be identified draws both: once for the artefact, and
 *       then once more for each weave its manifest declared, none of which can find any bytes.
 *   <li><b>{@code AW2303}</b> — two artefacts declare the same weave class name. Both candidates are kept,
 *       because which class of that name is loaded is the classpath's decision. Remove the duplicate dependency,
 *       or rename one of the weaves.
 *   <li><b>{@code AW2401}</b> — a {@link de.splatgames.aether.weaver.runtime.WeavingClassLoader} was constructed
 *       in a JVM started with {@code -XX:AOTCache}, {@code -XX:AOTCacheOutput} or {@code -XX:AOTConfiguration},
 *       and not with {@code -XX:AOTMode=off}, which vetoes them. Reported once per construction, since the
 *       condition is a property of the arguments the JVM was started with. Weave at build time instead: the woven
 *       classes are then the classes.
 *   <li><b>{@code AW3002}</b> — a class comes from a signed artefact. It is defined from the original bytes,
 *       because woven bytes are not covered by the signature the artefact carries. Weave before signing, or set
 *       {@code aether.weaver.policy.allowSigned=true} to accept that the signature no longer describes what runs;
 *       with the override set nothing is reported and the class is woven.
 *   <li><b>{@code AW4090}</b> — weaving one class threw, or a dump could not be written. The first is raised in
 *       {@link de.splatgames.aether.weaver.runtime.WeavingClassLoader} and the error policy then decides what
 *       becomes of the class; the second is raised by {@link de.splatgames.aether.weaver.engine.dump.ClassDump}
 *       on the same listener and costs nothing but the dump, the woven bytes being defined regardless.
 * </ul>
 *
 * <p>A manifest whose text will not parse is not reported here at all. It is reported by
 * {@link de.splatgames.aether.weaver.api.manifest.ManifestReader}, which raises its own {@code AW2300} for a
 * document that will not parse and {@code AW2301} for one stating a schema version this release does not read.
 * Either way that root alone is skipped, so one stale library cannot switch off every weave in the application.
 *
 * <p>A weave class whose bytes parse but no longer carry a {@code @Weave} annotation is skipped with no
 * diagnostic of any kind: to
 * {@link de.splatgames.aether.weaver.engine.parse.WeaveClassParser} it is an ordinary class. A class that does
 * carry the annotation but fails the parser's own checks is reported by the parser before being skipped.
 *
 * <h2>Failures that are not diagnostics</h2>
 *
 * <p>Discovery guards nothing. An unchecked exception raised while collecting candidates, fetching a candidate's
 * bytes or parsing them leaves
 * {@link de.splatgames.aether.weaver.runtime.WeaveDiscovery#discover(java.lang.ClassLoader,
 * de.splatgames.aether.weaver.runtime.config.WeaverConfig,
 * de.splatgames.aether.weaver.api.spi.DiagnosticListener)} rather than becoming a diagnostic. Two of those are
 * worth naming: a jar whose entry cannot be read yields an {@link java.io.UncheckedIOException} from the source
 * this package builds over that jar, and a manifest that parses as JSON but holds an invalid member or a version
 * that narrows to a negative value yields an {@link java.lang.IllegalArgumentException} from
 * {@link de.splatgames.aether.weaver.api.manifest.ManifestReader}, which ends the whole discovery run so that no
 * manifest after it is read.
 *
 * <p>On the class-loading path, a class no root holds, or whose bytes cannot be read, is a
 * {@link java.lang.ClassNotFoundException} as it would be from any loader. So is a class whose weaving threw
 * under {@link de.splatgames.aether.weaver.runtime.config.ErrorPolicy#FAIL}: the load fails at whoever triggered
 * it, with a message naming {@code aether.weaver.onError=fail} and the failure as the cause. Only
 * {@link java.lang.RuntimeException} and {@link java.lang.LinkageError} are caught around a weave; any other
 * {@link java.lang.Error} leaves the load unchanged and neither error policy applies to it.
 *
 * <h2>Handles this package holds</h2>
 *
 * <p>A local jar is read through a {@link java.util.jar.JarFile} the loader owns and closes, so nothing of its
 * own survives {@link de.splatgames.aether.weaver.runtime.WeavingClassLoader#close()}. Discovery does the same
 * for a jar it reads a candidate from, opening and closing it per lookup. A remote root, or one no
 * {@link java.nio.file.Path} can express, has no such handle for the loader to own: it falls back to reading
 * through the connection, and that handle survives {@code close()} regardless. Close the loader before deleting
 * or replacing a local artefact it read.
 *
 * <h2>What is deliberately not here</h2>
 *
 * <ul>
 *   <li><b>No configuration is read or parsed.</b> Both entry points take a
 *       {@link de.splatgames.aether.weaver.runtime.config.WeaverConfig} that is already resolved, and
 *       {@link de.splatgames.aether.weaver.runtime.config} is where one comes from. Which parts of it are acted
 *       on differs by type and is stated there.
 *   <li><b>No bytecode is planned or rewritten.</b> That is
 *       {@link de.splatgames.aether.weaver.engine.Weaver}, which this package builds, feeds and asks.
 *   <li><b>No service-loader lookup.</b> {@link de.splatgames.aether.weaver.runtime.WeaveDiscovery} constructs
 *       {@link de.splatgames.aether.weaver.runtime.ManifestWeaveSource} directly and this module registers no
 *       provider, so a source of a caller's own is one that caller constructs and calls itself.
 *   <li><b>Nothing is printed.</b> The explain flag is handed to the weaver and no report is rendered here; every
 *       diagnostic named above goes to the {@link de.splatgames.aether.weaver.api.spi.DiagnosticListener} the
 *       caller supplied and nowhere else.
 *   <li><b>Nothing is undone.</b> A class defined unwoven — because the parent answered for it, because its
 *       artefact is signed, or because weaving it threw under
 *       {@link de.splatgames.aether.weaver.runtime.config.ErrorPolicy#REPORT} — stays that way for the life of
 *       the loader.
 * </ul>
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
package de.splatgames.aether.weaver.runtime;
