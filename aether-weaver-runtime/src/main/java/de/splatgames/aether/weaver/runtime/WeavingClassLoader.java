package de.splatgames.aether.weaver.runtime;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import de.splatgames.aether.weaver.api.spi.ClassSource;
import de.splatgames.aether.weaver.api.spi.DiagnosticListener;
import de.splatgames.aether.weaver.engine.Weaver;
import de.splatgames.aether.weaver.engine.dump.ClassDump;
import de.splatgames.aether.weaver.runtime.config.ErrorPolicy;
import de.splatgames.aether.weaver.runtime.config.WeaverConfig;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.nio.file.Path;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.security.cert.Certificate;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * A {@link URLClassLoader} that weaves the classes it defines itself.
 *
 * <p>One of the project's drivers, alongside build-time weaving and the {@code -javaagent}
 * transformer, and the one for an application that controls its own class loading: a plugin host, a
 * launcher, a test harness. Delegation is ordinary parent-first, so only classes this loader
 * defines are woven, and a target its parent can also see is defined by the parent, unwoven,
 * without a diagnostic — a target the parent answered for is one this loader is never asked about.
 * Roots meant to be woven therefore belong on this loader alone.
 *
 * <p>Registered as parallel capable, so the lock a definition takes is per class name. Measured on
 * Temurin 25.0.3: a {@link URLClassLoader} subclass that omits the registration locks on the loader
 * itself instead, which would serialise every definition in a host loading classes on several
 * threads. Parallel capability is not inherited from {@link URLClassLoader}.
 *
 * <p>Classes defined here land in the unnamed module, which reads every module unconditionally, so
 * a woven class can reach the engine without a read edge being added for it.
 *
 * <h2>What it reports</h2>
 *
 * <ul>
 *   <li>{@code AW2401} once, when the loader is constructed in a JVM started with
 *       {@code -XX:AOTCache}, {@code -XX:AOTCacheOutput} or {@code -XX:AOTConfiguration} and not
 *       {@code -XX:AOTMode=off}. Weave at build time instead: the woven classes are then the
 *       classes, and the cache has nothing unusual to deal with.
 *   <li>{@code AW3002} when a class comes from a signed artefact. It is defined from the original
 *       bytes, because woven bytes are not covered by the signature the artefact carries. Weave
 *       before signing, or set {@code aether.weaver.policy.allowSigned=true} to accept that the
 *       signature no longer describes what runs; with the override set nothing is reported and the
 *       class is woven.
 *   <li>{@code AW4090} when weaving a class throws. Under
 *       {@link de.splatgames.aether.weaver.runtime.config.ErrorPolicy#REPORT} the original bytes
 *       are defined and the run continues; under
 *       {@link de.splatgames.aether.weaver.runtime.config.ErrorPolicy#FAIL} the load fails with a
 *       {@link ClassNotFoundException}. Diagnostics from discovery and from the engine reach the
 *       same listener.
 * </ul>
 *
 * <h2>Handles</h2>
 *
 * <p>A local jar is read through a {@link JarFile} this loader owns and closes, so nothing of its
 * own survives {@link #close()}. Reading such a jar through its {@link URLConnection} instead
 * would leave a handle in the JVM-wide jar cache that neither the stream nor {@code super.close()}
 * releases, which on Windows keeps the artefact locked for the life of the process; owning the
 * handle is what this loader does to avoid that. A remote jar root, or one no {@link Path} can
 * express, has no such handle for this loader to own: {@link #findClass} then falls back to the
 * connection with caching at its default, and that handle survives {@link #close()} regardless.
 * Close this loader before deleting or replacing a local artefact it has read.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class WeavingClassLoader extends URLClassLoader {

    static {
        // Per class name rather than per loader: a plugin host loading classes on several threads
        // would otherwise serialise every definition behind one lock.
        ClassLoader.registerAsParallelCapable();
    }

    /** The weaver every class this loader defines is offered to. */
    private final Weaver weaver;

    /** The signed-artefact policy and the error policy consulted per class. */
    private final WeaverConfig config;

    /** The listener every diagnostic this loader reports goes to. */
    private final DiagnosticListener listener;

    /**
     * Writes before-and-after copies of what was woven, or {@code null} when the configuration
     * names no dump directory.
     */
    private final @Nullable ClassDump dump;

    /**
     * The loader weave discovery read through, closed together with this one, or {@code null} when
     * the weaver was built by the caller instead.
     */
    private final @Nullable URLClassLoader discovery;

    /** The jar handles this loader owns, keyed by the artefact URL, released by {@link #close()}. */
    private final @NotNull Map<String, JarFile> jars = new ConcurrentHashMap<>();

    /**
     * Creates a loader around a weaver the caller already built.
     *
     * <p>Nothing is discovered and no manifest is read: the weaver handed in decides what is
     * woven, and what it cannot see it does not weave. Use
     * {@link #create(URL[], ClassLoader, WeaverConfig, DiagnosticListener)} to have the roots'
     * manifests read instead. {@code AW2401} is reported here if the JVM was started with an AOT
     * cache flag.
     *
     * @param roots    the URLs this loader defines from, copied on entry; must not be {@code null}
     * @param parent   the loader to delegate to first, or {@code null} for the bootstrap loader
     * @param weaver   the weaver every class is offered to; must not be {@code null}
     * @param config   the configuration deciding policy and error handling; must not be
     *                 {@code null}
     * @param listener the listener diagnostics are reported to; must not be {@code null}
     * @throws NullPointerException if {@code roots}, {@code weaver}, {@code config} or
     *                              {@code listener} is {@code null}
     */
    public WeavingClassLoader(final URL @NotNull [] roots,
                              @Nullable final ClassLoader parent,
                              @NotNull final Weaver weaver,
                              @NotNull final WeaverConfig config,
                              @NotNull final DiagnosticListener listener) {
        this(roots, parent, weaver, config, listener, null);
    }

    /**
     * Creates a loader that also owns the loader discovery read through.
     *
     * @param roots     the URLs this loader defines from, copied on entry; must not be {@code null}
     * @param parent    the loader to delegate to first, or {@code null} for the bootstrap loader
     * @param weaver    the weaver every class is offered to; must not be {@code null}
     * @param config    the configuration deciding policy and error handling; must not be
     *                  {@code null}
     * @param listener  the listener diagnostics are reported to; must not be {@code null}
     * @param discovery the loader to close together with this one, or {@code null} when there is
     *                  none
     * @throws NullPointerException if {@code roots}, {@code weaver}, {@code config} or
     *                              {@code listener} is {@code null}
     */
    private WeavingClassLoader(final URL @NotNull [] roots,
                               @Nullable final ClassLoader parent,
                               @NotNull final Weaver weaver,
                               @NotNull final WeaverConfig config,
                               @NotNull final DiagnosticListener listener,
                               @Nullable final URLClassLoader discovery) {
        super(Objects.requireNonNull(roots, "roots").clone(), parent);
        this.weaver = Objects.requireNonNull(weaver, "weaver");
        this.config = Objects.requireNonNull(config, "config");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.discovery = discovery;
        this.dump = config.dumpDirectory() == null ? null : new ClassDump(config.dumpDirectory());

        warnAboutAotCache(listener);
    }

    /**
     * Discovers the weaves on the given roots, builds a weaver for them, and returns a loader over
     * the same roots.
     *
     * <p>Discovery reads through a second loader over the same roots, which never defines anything:
     * reading manifests through the loader being built would define its classes before its weaver
     * existed. That loader stays open for this one's lifetime, because it backs the fallback branch
     * of the class source handed to the weaver — consulted only when a weave class's own bytes are
     * missing from what discovery found — and {@link #close()} closes it.
     *
     * <p>The weaver is built as a load-time one, so a class arriving with another plan's weave
     * record is woven again and warned about rather than refused.
     *
     * <p>Everything discovery and plan building report — {@code AW2300}, {@code AW2302} and
     * {@code AW2303} among them — arrives on {@code listener} during this call, before this loader
     * has defined anything.
     *
     * @param roots    the URLs to discover on and define from, copied on entry; must not be
     *                 {@code null}
     * @param parent   the loader to delegate to first, or {@code null} for the bootstrap loader
     * @param config   the configuration deciding which weaves are kept and how failures are
     *                 handled; must not be {@code null}
     * @param listener the listener diagnostics are reported to; must not be {@code null}
     * @return a loader whose weaver carries whatever the roots' manifests declared, which may be
     *         nothing
     * @throws NullPointerException if {@code roots}, {@code config} or {@code listener} is
     *                              {@code null}
     */
    @Contract(value = "_, _, _, _ -> new")
    @NotNull
    public static WeavingClassLoader create(final URL @NotNull [] roots,
                                            @Nullable final ClassLoader parent,
                                            @NotNull final WeaverConfig config,
                                            @NotNull final DiagnosticListener listener) {
        Objects.requireNonNull(roots, "roots");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(listener, "listener");

        // A separate loader, used for discovery only and never to define anything. Reading
        // manifests through the loader being built would define its classes before its weaver
        // exists, which is the one mistake this driver cannot recover from. It stays open for the
        // weaver's lifetime — the hierarchy resolver reads through it on every weave — and is
        // handed to the result so that closing the one closes the other.
        final URLClassLoader search = new URLClassLoader(roots.clone(), parent);
        final WeaveDiscovery.Discovered found = WeaveDiscovery.discover(search, config, listener);

        final Weaver weaver = Weaver.builder()
                    .driver(Weaver.Driver.LOAD)
                .weaves(found.weaves())
                // The weave classes' own bytes first, then everything the roots can see, which is
                // what the hierarchy resolver needs for the targets.
                .classSource(found.classes().orElse(ClassSource.ofClassLoader(search)))
                .verification(config.verification())
                .explain(config.explain())
                .diagnostics(listener)
                .build();

        return new WeavingClassLoader(roots, parent, weaver, config, listener, search);
    }

    /**
     * Reads a class from this loader's own roots, weaves it, and defines it.
     *
     * <p>The bytes are looked up with {@code findResource} rather than {@code getResource}, so no
     * delegation happens here. Delegating would read bytes from an artefact the parent has already
     * defined a class from, and the woven copy would duplicate a type already in use.
     *
     * <p>The defined class keeps the {@link CodeSource} the bytes were read from, certificates
     * included, and its package is defined from the artefact's manifest so that a sealed package
     * stays sealed.
     *
     * @param name the binary name to define; must not be {@code null}
     * @return the defined class, unwoven when the weaver had nothing to apply, when weaving failed
     *         under {@link de.splatgames.aether.weaver.runtime.config.ErrorPolicy#REPORT}, or when
     *         the artefact is signed and {@code aether.weaver.policy.allowSigned} is not set
     * @throws ClassNotFoundException if no root holds the class, if its bytes cannot be read, or if
     *                                weaving failed under
     *                                {@link de.splatgames.aether.weaver.runtime.config.ErrorPolicy#FAIL}
     */
    @Override
    @NotNull
    protected Class<?> findClass(@NotNull final String name) throws ClassNotFoundException {
        final String path = name.replace('.', '/') + ".class";
        // findResource, not getResource: this loader's own roots, with no delegation. Delegating
        // here would read bytes from an artefact the parent has already defined a class from, so
        // the woven copy would duplicate a type that is already in use.
        final URL url = findResource(path);
        if (url == null) {
            throw new ClassNotFoundException(name);
        }

        final byte[] original;
        final CodeSource codeSource;
        try {
            final URLConnection connection = url.openConnection();
            // A jar is read through a handle this loader owns and closes. Reading it through the
            // connection instead puts a JarFile in the JVM-wide JarFileFactory cache, which nothing
            // here can ever close: not the stream, and not super.close(), because that releases only
            // the handles URLClassPath opened. On Windows the artefact then stays locked for the
            // life of the process. Opening it per class instead of caching is not the alternative —
            // measured on a 173-entry jar, that costs 198.7 µs against 0.404 µs, a factor of 492.
            final Owned owned = connection instanceof final JarURLConnection jar
                    ? read(jar, path)
                    : null;
            if (owned != null) {
                original = owned.bytes();
                codeSource = new CodeSource(owned.root(), owned.certificates());
                definePackageOf(name, owned.manifest(), owned.root());
            } else {
                try (InputStream stream = connection.getInputStream()) {
                    original = stream.readAllBytes();
                }
                // After the entry has been read, which is when a jar's certificates become available.
                codeSource = codeSourceOf(url, path, connection);
                definePackageOf(name, connection);
            }
        } catch (final IOException unreadable) {
            throw new ClassNotFoundException(name + " could not be read from " + url, unreadable);
        }

        final byte[] bytes = apply(name, original, codeSource);
        return defineClass(name, bytes, 0, bytes.length, codeSource);
    }

    /**
     * Weaves one class's bytes, applying the signed-artefact policy and the error policy.
     *
     * <p>{@link RuntimeException} and {@link LinkageError} are caught, and nothing else. Any other
     * {@link Error} propagates out of the load unchanged, and neither error policy applies to it.
     *
     * @param name       the binary name being defined
     * @param original   the bytes read from the artefact
     * @param codeSource the code source the bytes came from, consulted for certificates
     * @return the woven bytes, or {@code original} when the artefact is signed and the override is
     *         unset ({@code AW3002}), when the weaver had nothing to apply, or when weaving failed
     *         under {@link de.splatgames.aether.weaver.runtime.config.ErrorPolicy#REPORT}
     *         ({@code AW4090})
     * @throws ClassNotFoundException if weaving failed and the error policy is
     *                                {@link de.splatgames.aether.weaver.runtime.config.ErrorPolicy#FAIL};
     *                                the message names {@code aether.weaver.onError=fail} and the
     *                                failure is the cause
     */
    private byte @NotNull [] apply(@NotNull final String name,
                                   final byte @NotNull [] original,
                                   @NotNull final CodeSource codeSource)
            throws ClassNotFoundException {
        if (isSigned(codeSource) && !this.config.policy().allowSigned()) {
            this.listener.report(Diagnostic.builder(DiagnosticCode.POLICY_DENIED_SIGNED_ARTEFACT)
                    .message(name + " comes from a signed artefact and was defined unwoven")
                    .detail("signed by " + codeSource.getLocation())
                    .detail("woven bytes are not covered by the signature that was applied to the "
                            + "artefact, so a consumer verifying it would find a class the signer "
                            + "never saw")
                    .remedy("weave before signing, or set aether.weaver.policy.allowSigned=true to "
                            + "accept that the signature no longer describes what runs")
                    .build());
            return original;
        }

        final byte[] woven;
        try {
            woven = this.weaver.weave(name.replace('.', '/'), original);
        } catch (final RuntimeException | LinkageError failure) {
            this.listener.report(Diagnostic.builder(DiagnosticCode.INTERNAL_ERROR)
                    .message("weaving " + name + " failed: " + failure.getMessage())
                    .detail(failure.getClass().getName())
                    .build());
            if (this.config.onError() == ErrorPolicy.FAIL) {
                // Thrown, not halted. Unlike the agent's transformer, this driver's exceptions
                // are not discarded by the JVM, so being strict costs the caller a
                // ClassNotFoundException rather than costing the process its life.
                throw new ClassNotFoundException(name + " could not be woven and "
                        + "aether.weaver.onError=fail", failure);
            }
            return original;
        }
        if (woven == null) {
            return original;
        }
        if (this.dump != null) {
            this.dump.write(name.replace('.', '/'), original, woven, this.listener);
        }
        return woven;
    }

    /**
     * One class file read through a jar handle this loader owns, together with what that handle
     * reports about the artefact it came from.
     *
     * @param bytes        the class file's contents
     * @param certificates the entry's certificates, or {@code null} when the entry is unsigned
     * @param manifest     the artefact's manifest, or {@code null} when it has none
     * @param root         the artefact's own URL, used as the code source location
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private record Owned(byte @NotNull [] bytes,
                         Certificate @Nullable [] certificates,
                         @Nullable Manifest manifest,
                         @NotNull URL root) {
    }

    /**
     * Reads a class file through a jar handle this loader owns rather than through the connection.
     *
     * <p>The entry's certificates are taken after its bytes have been read, which is when a jar
     * yields them.
     *
     * @param jar  the connection naming the artefact and the entry
     * @param path the entry to read, as a resource path
     * @return what was read, or {@code null} when the artefact is not a local file, or is one no
     *         {@link Path} can express, and so has no handle to own; the caller then reads through
     *         the connection instead
     * @throws IOException if the jar cannot be opened, if the entry cannot be read, or if the entry
     *                     is no longer in the artefact that {@code findResource} found it in
     */
    @Nullable
    private Owned read(@NotNull final JarURLConnection jar, @NotNull final String path)
            throws IOException {
        final URL root = jar.getJarFileURL();
        if (!"file".equals(root.getProtocol())) {
            // A remote artefact has no file handle to own; the connection stays in charge of it.
            return null;
        }
        final Path file;
        try {
            file = Path.of(root.toURI());
        } catch (final URISyntaxException | IllegalArgumentException unusable) {
            return null;
        }

        final JarFile opened = jarFor(root, file);
        final JarEntry entry = opened.getJarEntry(path);
        if (entry == null) {
            throw new IOException(path + " vanished from " + root + " between lookup and read");
        }
        final byte[] bytes;
        try (InputStream stream = opened.getInputStream(entry)) {
            bytes = stream.readAllBytes();
        }
        return new Owned(bytes, entry.getCertificates(), opened.getManifest(), root);
    }

    /**
     * Returns this loader's handle on an artefact, opening one the first time it is asked for.
     *
     * <p>Verifying and version-aware, so a signed entry yields its certificates and a multi-release
     * artefact yields the entry the platform's own loader would have chosen. Two threads may open
     * the same artefact at once; the loser closes its handle rather than leaking it.
     *
     * @param root the artefact URL the handle is keyed by
     * @param file the artefact as a local path
     * @return the shared handle, released by {@link #close()} and not by the caller
     * @throws IOException if the artefact cannot be opened
     */
    @NotNull
    private JarFile jarFor(@NotNull final URL root, @NotNull final Path file) throws IOException {
        final JarFile existing = this.jars.get(root.toString());
        if (existing != null) {
            return existing;
        }
        // Verifying, and version-aware so a multi-release artefact yields the same entry the
        // platform's own loader would have chosen.
        final JarFile opened =
                new JarFile(file.toFile(), true, JarFile.OPEN_READ, Runtime.version());
        final JarFile raced = this.jars.putIfAbsent(root.toString(), opened);
        if (raced == null) {
            return opened;
        }
        // Another thread won; this handle would otherwise be the very leak this method exists to
        // prevent.
        opened.close();
        return raced;
    }

    /**
     * Builds the code source for bytes read through a connection.
     *
     * <p>Correct only once the entry has been read, which is when a jar's certificates become
     * available.
     *
     * @param url        the URL the class file was found at
     * @param path       the resource path within the root
     * @param connection the connection the bytes were read from
     * @return the artefact and its certificates for a jar, or the root with no signers otherwise
     * @throws IOException if the jar's certificates cannot be read
     */
    @NotNull
    private static CodeSource codeSourceOf(@NotNull final URL url,
                                           @NotNull final String path,
                                           @NotNull final URLConnection connection)
            throws IOException {
        if (connection instanceof final JarURLConnection jar) {
            final Certificate[] certificates = jar.getCertificates();
            return new CodeSource(jar.getJarFileURL(), certificates);
        }
        return new CodeSource(rootOf(url, path), (CodeSigner[]) null);
    }

    /**
     * Strips a resource path off the URL it was found at, leaving the root it was found under.
     *
     * @param url  the URL the class file was found at
     * @param path the resource path to strip
     * @return the root, or {@code url} unchanged when it does not end with {@code path} or what
     *         remains is not a URL; a code source naming the class file rather than its root is
     *         narrower than the truth, so it grants fewer permissions rather than more
     */
    @Contract(pure = true)
    @NotNull
    private static URL rootOf(@NotNull final URL url, @NotNull final String path) {
        final String text = url.toString();
        if (!text.endsWith(path)) {
            return url;
        }
        try {
            return URI.create(text.substring(0, text.length() - path.length())).toURL();
        } catch (final IOException | IllegalArgumentException unusable) {
            // A code source naming the class file rather than its root is narrower than the truth,
            // which grants fewer permissions rather than more.
            return url;
        }
    }

    /**
     * Defines the package of a class read through a connection, taking sealing and versioning from
     * the artefact's manifest where there is one.
     *
     * @param name       the binary name of the class being defined
     * @param connection the connection the bytes were read from
     * @throws IOException if the jar's manifest cannot be read
     */
    private void definePackageOf(@NotNull final String name,
                                 @NotNull final URLConnection connection) throws IOException {
        final int dot = name.lastIndexOf('.');
        if (dot <= 0) {
            return;
        }
        final String packageName = name.substring(0, dot);
        if (getDefinedPackage(packageName) != null) {
            return;
        }
        final Manifest manifest =
                connection instanceof final JarURLConnection jar ? jar.getManifest() : null;
        final URL root =
                connection instanceof final JarURLConnection jar ? jar.getJarFileURL() : null;
        definePackageOf(name, manifest, root);
    }

    /**
     * Defines the package of a class being loaded, unless it is already defined.
     *
     * <p>Given a manifest and a root, the package is defined from them, so a package the artefact
     * sealed stays sealed. Without them it is defined with no attributes at all. A class in the
     * default package has no package to define. Losing the race against another thread is expected
     * of a parallel-capable loader and is not treated as a failure.
     *
     * @param name     the binary name of the class being defined
     * @param manifest the artefact's manifest, or {@code null} when there is none
     * @param root     the artefact's URL, or {@code null} when there is none
     */
    private void definePackageOf(@NotNull final String name,
                                 @Nullable final Manifest manifest,
                                 @Nullable final URL root) {
        final int dot = name.lastIndexOf('.');
        if (dot <= 0) {
            return;
        }
        final String packageName = name.substring(0, dot);
        if (getDefinedPackage(packageName) != null) {
            return;
        }
        try {
            if (manifest != null && root != null) {
                // Sealing lives in the manifest. A sealed package silently unsealed is a security
                // property the artefact declared and this loader dropped.
                definePackage(packageName, manifest, root);
            } else {
                definePackage(packageName, null, null, null, null, null, null, null);
            }
        } catch (final IllegalArgumentException raced) {
            // Another thread defined it between the check and here, which is expected of a
            // parallel-capable loader and is not a failure.
        }
    }

    /**
     * Reports whether a code source carries certificates.
     *
     * @param codeSource the code source to examine
     * @return {@code true} when it names at least one certificate
     */
    @Contract(pure = true)
    private static boolean isSigned(@NotNull final CodeSource codeSource) {
        final Certificate[] certificates = codeSource.getCertificates();
        return certificates != null && certificates.length > 0;
    }

    /**
     * Reports {@code AW2401} when this JVM was started with an AOT cache flag, and says nothing
     * otherwise.
     *
     * <p>Called once per construction rather than per class, because the condition is a property of
     * the arguments the JVM was started with. {@link AotCache} decides which flags count.
     *
     * @param listener the listener the report goes to
     */
    private static void warnAboutAotCache(@NotNull final DiagnosticListener listener) {
        final String flag = AotCache.active();
        if (flag == null) {
            return;
        }
        listener.report(Diagnostic.builder(
                        DiagnosticCode.AOT_CACHE_WITH_WEAVING_CLASS_LOADER)
                .message("a weaving class loader was created in a JVM started with " + flag)
                .detail("classes in the cache are loaded eagerly: a class the application never "
                        + "referenced was measured being defined by the application class loader "
                        + "at 0.019s, where the same run without the cache never loaded it at all")
                .detail("so any target a parent of this loader can also see is very likely to be "
                        + "defined, unwoven, before this loader gets to it")
                .detail("what this loader does define is still woven correctly — the JVM rejects a "
                        + "cached copy whose bytes differ — but it rejects it without saying so, so "
                        + "the cache also stops paying for exactly these classes")
                .remedy("weave at build time instead: the woven classes are then the classes, and "
                        + "the AOT cache has nothing unusual to deal with")
                .build());
    }

    /**
     * Closes this loader, the loader weave discovery read through, and every jar handle this
     * loader opened.
     *
     * <p>All three are closed even if the first two refuse, and every jar handle is closed even if
     * one of them refuses. Releasing them is what lets an artefact this loader has read be deleted
     * or replaced.
     *
     * @throws IOException if closing this loader, the discovery loader or a jar handle fails; a
     *                     refusing jar handle carries the later ones as suppressed exceptions, and
     *                     replaces a refusal from either of the first two rather than being
     *                     suppressed on it
     */
    @Override
    public void close() throws IOException {
        try {
            super.close();
        } finally {
            try {
                if (this.discovery != null) {
                    this.discovery.close();
                }
            } finally {
                // Every handle, even if one refuses: a single artefact left open is a locked file
                // on Windows and an exhausted descriptor table everywhere else.
                IOException failed = null;
                for (final JarFile jar : this.jars.values()) {
                    try {
                        jar.close();
                    } catch (final IOException stubborn) {
                        if (failed == null) {
                            failed = stubborn;
                        } else {
                            failed.addSuppressed(stubborn);
                        }
                    }
                }
                this.jars.clear();
                if (failed != null) {
                    throw failed;
                }
            }
        }
    }

    /**
     * Returns a description naming the number of roots and the weaver's plan fingerprint.
     *
     * <p>The fingerprint is the weaver's plan identity, the same value it stamps into every class
     * it writes, so a woven class can be matched against the plan that produced it.
     *
     * @return a description of the form {@code WeavingClassLoader[roots=2, fingerprint=...]}
     */
    @Override
    @NotNull
    public String toString() {
        return "WeavingClassLoader[roots=" + getURLs().length
                + ", fingerprint=" + this.weaver.fingerprint() + ']';
    }
}
