package de.splatgames.aether.weaver.runtime;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import de.splatgames.aether.weaver.api.model.Origin;
import de.splatgames.aether.weaver.api.model.WeaveCandidate;
import de.splatgames.aether.weaver.api.spi.ClassSource;
import de.splatgames.aether.weaver.api.spi.DiscoveryContext;
import de.splatgames.aether.weaver.api.spi.WeaveSource;
import de.splatgames.aether.weaver.api.manifest.ManifestReader;
import de.splatgames.aether.weaver.api.manifest.WeaveManifest;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Discovers weaves from the {@value WeaveManifest#RESOURCE} files a class loader can see.
 *
 * <p>The annotation processor writes one manifest into every module that declares a weave, so a
 * classpath carries as many as it has such artefacts. Every one the discovery context's loader can
 * reach is read, and each weave named in it becomes a {@link WeaveCandidate} whose bytes are read
 * from that manifest's own root rather than from the classpath as a whole. Two artefacts may
 * declare the same class name, and each candidate then still carries the copy shipped beside its
 * own manifest.
 *
 * <p>No weave class is parsed or validated here. A candidate is a class name and somewhere to read
 * it from; {@link de.splatgames.aether.weaver.engine.parse.WeaveClassParser} decides what it is.
 *
 * <h2>What it reports</h2>
 *
 * <ul>
 *   <li>{@code AW2302} whenever {@link #locate} returns no manifest URL — either because none is
 *       on the classpath, naming the absent annotation processor as the usual reason, or because
 *       the classpath itself could not be searched, in which case it accompanies {@code AW2300}
 *       below and the missing-processor framing does not fit. No candidate is produced either
 *       way. Add {@code aether-weaver-processor} as a provided-scope dependency of every module
 *       that declares a weave.
 *   <li>{@code AW2300} when the classpath cannot be searched, in which case the whole run yields
 *       no candidates, or when a single manifest cannot be read or the artefact holding it cannot
 *       be identified, in which case only that manifest's root is lost and the remaining
 *       manifests are still read.
 *   <li>{@code AW2303} when two artefacts declare the same weave class name. Both candidates are
 *       kept, because which class of that name is loaded is the classpath's decision and not this
 *       framework's. Remove the duplicate dependency, or rename one of the weaves.
 * </ul>
 *
 * <p>A manifest whose text will not parse is reported by {@link ManifestReader} instead, and its
 * root is skipped on the same terms.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class ManifestWeaveSource implements WeaveSource {

    /** The reported name, namespaced so that another party's source cannot collide with it. */
    private static final String NAME = "aether:manifest";

    /**
     * Creates a source that reads whatever the discovery context it is handed can see.
     */
    public ManifestWeaveSource() {
        // Nothing to configure: where to look is the context's business.
    }

    /**
     * Returns the name of this source.
     *
     * @return {@value #NAME}
     */
    @Override
    @Contract(pure = true)
    @NotNull
    public String name() {
        return NAME;
    }

    /**
     * Reads every manifest the context's loader can see and returns one candidate per weave named.
     *
     * <p>The whole classpath is read before the stream is returned rather than as it is consumed,
     * so a lazy stream cannot hold every classpath root open until the caller finishes with it.
     * Only the bytes of a candidate are left for later.
     *
     * @param context the loader to search and the reporter to report to; must not be {@code null}
     * @return the candidates, manifest by manifest in classpath order and weave by weave within
     *         each manifest; empty when no manifest was found, when none named a weave, or when
     *         every manifest found was unusable
     * @throws NullPointerException if {@code context} is {@code null}
     */
    @Override
    @NotNull
    public Stream<WeaveCandidate> candidates(@NotNull final DiscoveryContext context) {
        Objects.requireNonNull(context, "context");

        final List<URL> manifests = locate(context);
        if (manifests.isEmpty()) {
            context.reporter().report(Diagnostic.builder(DiagnosticCode.MANIFEST_NOT_FOUND)
                    .message("no " + WeaveManifest.RESOURCE + " was found on the classpath")
                    .detail("the manifest is written by aether-weaver-processor during "
                            + "compilation, so its absence usually means the processor is not on "
                            + "the annotation processor path")
                    .remedy("add aether-weaver-processor as a provided-scope dependency of every "
                            + "module that declares a weave")
                    .build());
            return Stream.of();
        }

        // Collected eagerly, per root, rather than streamed lazily. A candidate's bytes are read
        // later, but the manifest that named it is read now — a lazy stream would hold every
        // classpath root open until the caller finished with it, on the class-loading path.
        final List<WeaveCandidate> candidates = new ArrayList<>();
        final Map<String, String> seen = new LinkedHashMap<>();
        for (final URL manifest : manifests) {
            read(manifest, context, candidates, seen);
        }
        return candidates.stream();
    }

    /**
     * Lists the manifests the context's loader can reach, in classpath order.
     *
     * @param context the loader to search and the reporter to report to
     * @return the manifest URLs, or an empty list when the classpath could not be searched, which
     *         is reported as {@code AW2300}
     */
    @NotNull
    private static List<URL> locate(@NotNull final DiscoveryContext context) {
        try {
            return Collections.list(context.loader().getResources(WeaveManifest.RESOURCE));
        } catch (final IOException unreadable) {
            context.reporter().report(Diagnostic.builder(DiagnosticCode.MANIFEST_MALFORMED)
                    .message("the classpath could not be searched for "
                            + WeaveManifest.RESOURCE + ": " + unreadable.getMessage())
                    .build());
            return List.of();
        }
    }

    /**
     * Reads one manifest and appends a candidate for each weave it names.
     *
     * <p>A manifest that cannot be read is reported as {@code AW2300}, and one that fails to parse
     * is reported by {@link ManifestReader}, which returns {@code null} rather than throwing;
     * either way only this root is skipped, so one such artefact cannot switch off every weave in
     * the application. A manifest that parses but holds an invalid member or an invalid version —
     * an absent, non-string or blank name, a blank injection point, or a version that narrows to a
     * negative one — is not covered by that limit: {@link ManifestReader#read} throws
     * {@link IllegalArgumentException} for it instead, uncaught here, and the whole
     * {@link #candidates} run ends without reading any manifest after it.
     *
     * @param manifest   the manifest to read
     * @param context    the loader the root is resolved against and the reporter to report to
     * @param candidates the list each weave named by this manifest is appended to
     * @param seen       the class names already claimed, each mapped to the artefact that claimed
     *                   it; a second claim is reported as {@code AW2303} and the candidate is
     *                   appended anyway
     */
    private static void read(@NotNull final URL manifest,
                             @NotNull final DiscoveryContext context,
                             @NotNull final List<WeaveCandidate> candidates,
                             @NotNull final Map<String, String> seen) {
        final String text;
        try (InputStream stream = open(manifest)) {
            text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException unreadable) {
            context.reporter().report(Diagnostic.builder(DiagnosticCode.MANIFEST_MALFORMED)
                    .message("the manifest at " + manifest + " could not be read: "
                            + unreadable.getMessage())
                    .build());
            return;
        }

        final String where = describe(manifest);
        final WeaveManifest parsed = ManifestReader.read(text, where, context.reporter());
        if (parsed == null) {
            // Reported by the reader and skipped here. One stale library must not be able to
            // switch off every weave in the application.
            return;
        }

        final ClassSource root = rootOf(manifest, context);
        for (final WeaveManifest.Weave weave : parsed.weaves()) {
            final String previous = seen.putIfAbsent(weave.className(), where);
            if (previous != null) {
                context.reporter().report(Diagnostic.builder(DiagnosticCode.DUPLICATE_WEAVE_CLASS)
                        .message(weave.className() + " is declared by two artefacts: " + previous
                                + " and " + where)
                        .detail("each is read from its own artefact, so neither takes the other's "
                                + "bytes — but only one class of that name can be loaded, and "
                                + "which one is the classpath's decision rather than this "
                                + "framework's")
                        .remedy("remove the duplicate dependency, or rename one of the weaves")
                        .build());
            }
            candidates.add(new WeaveCandidate(weave.className(), root,
                    Origin.of("weave manifest", where)));
        }
    }

    /**
     * Opens a manifest with URL caching switched off.
     *
     * <p>Measured on Temurin 25.0.3: reading an entry through a cached {@code jar:} connection
     * leaves one descriptor open on the artefact after the stream has been closed, and none when
     * caching is off. So the stream this call returns leaves nothing of its own open once closed.
     * The loader that resolved the manifest's URL may still hold a descriptor on the same jar
     * independently, in its own {@code URLClassPath}, for as long as that loader stays open;
     * switching caching off here does nothing about that separate handle.
     *
     * @param resource the manifest to open
     * @return the stream, which the caller closes
     * @throws IOException if the connection or its stream cannot be opened
     */
    @NotNull
    private static InputStream open(@NotNull final URL resource) throws IOException {
        final java.net.URLConnection connection = resource.openConnection();
        connection.setUseCaches(false);
        return connection.getInputStream();
    }

    /**
     * Resolves the artefact a manifest came from into a source for the classes it declares.
     *
     * @param manifest the manifest whose artefact is wanted
     * @param context  the reporter to report to
     * @return a source over that artefact, or {@link ClassSource#NONE} when the artefact could not
     *         be identified, which is reported as {@code AW2300}; every candidate from that
     *         manifest then finds no bytes
     */
    @NotNull
    private static ClassSource rootOf(@NotNull final URL manifest,
                                      @NotNull final DiscoveryContext context) {
        final URL root = trim(manifest);
        if (root == null) {
            context.reporter().report(Diagnostic.builder(DiagnosticCode.MANIFEST_MALFORMED)
                    .message("the artefact holding " + manifest + " could not be identified, so "
                            + "the weaves it declares cannot be read")
                    .build());
            return ClassSource.NONE;
        }
        return sourceOf(root);
    }

    /**
     * Builds a source over one classpath root.
     *
     * <p>A local directory and a local jar are read directly. Anything else — a remote root, or a
     * URL no {@link Path} can express — is read through a {@link URLClassLoader} over that root
     * alone, parented to the bootstrap loader, so nothing else on the application classpath can
     * answer for a candidate. That loader is reachable only as the returned {@link ClassSource},
     * which has no close operation, so it is not closed.
     *
     * @param root the root URL
     * @return a source over that root
     */
    @NotNull
    private static ClassSource sourceOf(@NotNull final URL root) {
        if ("file".equals(root.getProtocol())) {
            try {
                final Path path = Path.of(root.toURI());
                return Files.isDirectory(path)
                        ? ClassSource.ofPath(path)
                        : jarSource(path);
            } catch (final URISyntaxException | IllegalArgumentException unusable) {
                // Falls through to the loader below, which accepts URLs a path cannot express.
            }
        }
        // A remote root has no file handle to hold, so the old shape is still the right one here.
        return ClassSource.ofClassLoader(new URLClassLoader(new URL[]{root}, null));
    }

    /**
     * Builds a source that reads a class from a jar, opening and closing the jar per lookup so that
     * discovery leaves no handle on the artefact.
     *
     * @param jar the jar to read from
     * @return a source that yields the entry a loader on this runtime would pick, empty when the
     *         jar holds no such entry, and throws {@link UncheckedIOException} when the jar or the
     *         entry cannot be read
     */
    @NotNull
    private static ClassSource jarSource(@NotNull final Path jar) {
        return internalName -> {
            final String resource = internalName + ".class";
            // Version-aware, so a multi-release artefact yields the entry a real loader would pick.
            try (JarFile file = new JarFile(jar.toFile(), true, JarFile.OPEN_READ,
                    Runtime.version())) {
                final JarEntry entry = file.getJarEntry(resource);
                if (entry == null) {
                    return Optional.empty();
                }
                try (InputStream stream = file.getInputStream(entry)) {
                    return Optional.of(stream.readAllBytes());
                }
            } catch (final IOException unreadable) {
                throw new UncheckedIOException(
                        "could not read " + resource + " from " + jar, unreadable);
            }
        };
    }

    /**
     * Reduces a manifest URL to the root of the artefact holding it.
     *
     * <p>A URL containing {@code !/} is taken to be a {@code jar:} one, and that scheme is stripped
     * from it, so the root a {@link URLClassLoader} receives is a plain file URL.
     *
     * @param manifest the manifest URL
     * @return the root, or {@code null} when the URL neither contains {@code !/} nor ends with
     *         {@value WeaveManifest#RESOURCE}, or when what remains of it is not a URL
     */
    @Contract(pure = true)
    @Nullable
    private static URL trim(@NotNull final URL manifest) {
        final String text = manifest.toString();
        try {
            final int bang = text.indexOf("!/");
            if (bang >= 0) {
                // jar:file:/path/a.jar!/META-INF/… — the root is the jar itself, and a URLClassLoader
                // takes it as a plain file URL rather than a jar: one.
                return java.net.URI.create(text.substring("jar:".length(), bang)).toURL();
            }
            if (text.endsWith(WeaveManifest.RESOURCE)) {
                return java.net.URI.create(
                        text.substring(0, text.length() - WeaveManifest.RESOURCE.length())).toURL();
            }
            return null;
        } catch (final MalformedURLException | IllegalArgumentException unusable) {
            return null;
        }
    }

    /**
     * Names the artefact a manifest came from, for the diagnostics this source reports and for each
     * candidate's {@link Origin}.
     *
     * <p>Only the last path segment before a {@code !/} survives, so a jar is described by its file
     * name alone and neither its path nor the manifest entry appears. A manifest in a directory has
     * no {@code !/}, and its last segment is the manifest itself, so such a root is described as
     * {@code weaves.json} rather than by the directory.
     *
     * @param manifest the manifest URL
     * @return the artefact's file name, or the whole URL up to the {@code !/} when it has no last
     *         segment to take
     */
    @Contract(pure = true)
    @NotNull
    private static String describe(@NotNull final URL manifest) {
        final String text = manifest.toString();
        final int bang = text.indexOf("!/");
        final String artefact = bang >= 0 ? text.substring(0, bang) : text;
        final int slash = artefact.lastIndexOf('/');
        return slash >= 0 && slash + 1 < artefact.length()
                ? artefact.substring(slash + 1)
                : artefact;
    }

    /**
     * Returns a description naming this source.
     *
     * @return {@code ManifestWeaveSource[aether:manifest]}
     */
    @Override
    @NotNull
    public String toString() {
        return "ManifestWeaveSource[" + NAME + ']';
    }
}
