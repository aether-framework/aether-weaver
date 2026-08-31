package de.splatgames.aether.weaver.processor;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import de.splatgames.aether.weaver.api.model.InjectorSpec;
import de.splatgames.aether.weaver.api.model.PointSpec;
import de.splatgames.aether.weaver.api.spi.Reporter;
import de.splatgames.aether.weaver.api.manifest.ManifestReader;
import de.splatgames.aether.weaver.api.manifest.ManifestWriter;
import de.splatgames.aether.weaver.api.manifest.WeaveManifest;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.annotation.processing.Filer;
import javax.lang.model.element.TypeElement;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Collects what a compilation declares and writes it to {@value WeaveManifest#RESOURCE}.
 *
 * <p>The manifest is the only channel from compile time to run time: the runtime discovers weaves
 * and extensions by finding this resource on a classpath root, so a module whose manifest is
 * missing or stale behaves as though it declared nothing. Entries accumulate here across a
 * compilation and are written once, in the round where processing is over.
 *
 * <p>Writing merges over whatever the output directory already holds rather than replacing it. An
 * incremental build recompiles a subset of the sources, sees only that subset, and would otherwise
 * drop every weave it did not visit — the failure that works after a clean build and not
 * afterwards. Merging is by class name for a weave and by holder for an extension, so a recompiled
 * class replaces its own entry and leaves the rest alone.
 *
 * <p>Two things reach the file that a reader may not expect. A declaration that was refused with an
 * error is still recorded, because the entry is built from what the source said and not from
 * whether the checks passed; and an {@code @Extension} class that contributes nothing still causes
 * a manifest to be written, because the holder is registered before its contributions are counted.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
final class ManifestEmitter {

    /** Written into the manifest's {@code generator} field to say what produced it. */
    private static final String GENERATOR = "aether-weaver-processor/0.1.0";

    /** The compiler's resource writer, used both to read the existing manifest and to write. */
    private final Filer filer;

    /** Weave entries by binary class name, in the order the compilation produced them. */
    private final Map<String, WeaveManifest.Weave> collected = new LinkedHashMap<>();

    /** Extension entries by holder binary name, including holders that contributed none. */
    private final Map<String, List<WeaveManifest.Extension>> extensions = new LinkedHashMap<>();

    /**
     * Creates an emitter that writes through the given filer.
     *
     * @param filer the processing environment's filer; must not be {@code null}
     * @throws NullPointerException if {@code filer} is {@code null}
     */
    ManifestEmitter(@NotNull final Filer filer) {
        this.filer = Objects.requireNonNull(filer, "filer");
    }

    /**
     * Records one weave class, replacing any entry already held for the same class name.
     *
     * @param weave the entry to record; must not be {@code null}
     * @throws NullPointerException if {@code weave} is {@code null}
     */
    void add(@NotNull final WeaveManifest.Weave weave) {
        Objects.requireNonNull(weave, "weave");
        this.collected.put(weave.className(), weave);
    }

    /**
     * Records everything one extension holder contributes, replacing what it held for that holder.
     *
     * <p>A holder is registered even when {@code contributed} is empty, which is what makes
     * {@link #isEmpty()} false for a compilation whose only {@code @Extension} class was refused:
     * that compilation writes a manifest with empty lists. An empty list does not erase the
     * holder's entries in the manifest already on disk, because merging matches on the entries the
     * new manifest carries and an empty list carries none.
     *
     * <p>The holder key is used for nothing else here. Entries from every holder are flattened into
     * one list before the manifest is built, and {@link WeaveManifest#merge(WeaveManifest)} groups
     * them by the holder each entry names.
     *
     * @param holder      the holder's binary name; must not be {@code null}
     * @param contributed the entries it contributes, possibly empty; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    void addExtensions(@NotNull final String holder,
                       @NotNull final List<WeaveManifest.Extension> contributed) {
        Objects.requireNonNull(holder, "holder");
        Objects.requireNonNull(contributed, "contributed");
        this.extensions.put(holder, List.copyOf(contributed));
    }

    /**
     * Reports whether this compilation saw anything worth writing a manifest for.
     *
     * @return {@code true} when no weave and no extension holder has been recorded; a holder that
     *         contributed no entries still counts as recorded
     */
    boolean isEmpty() {
        return this.collected.isEmpty() && this.extensions.isEmpty();
    }

    /**
     * Writes the manifest, merged over the one the output directory already holds.
     *
     * <p>Does nothing when {@link #isEmpty()}. Otherwise the collected entries become a fresh
     * manifest, the existing file is read if there is one, and the fresh entries win wherever both
     * name the same class or holder. The result goes to {@value WeaveManifest#RESOURCE} under
     * {@link StandardLocation#CLASS_OUTPUT}.
     *
     * <p>The error count is not consulted: a compilation that reported errors still writes a
     * manifest, holding entries for the declarations that failed.
     *
     * <p>{@code AW2300} is reported when the resource cannot be created or written, and its remedy
     * asks the reader to check that the processor can write to the compilation output directory.
     * A second call in one compilation reports it too: {@link Filer#createResource} throws for a
     * pathname it has already created. The same code can also arrive from a different place — a
     * manifest already on disk that cannot be parsed is reported as {@code AW2300} by
     * {@link ManifestReader}, and one from a newer schema as {@code AW2301}, both through
     * {@link #readExisting(MessagerReporter)}. In that case there is nothing wrong with the output
     * directory; the answer is to rebuild whatever left the file there, and the write goes ahead
     * without the unreadable file's entries.
     *
     * <p>A {@code null} reporter throws {@code NullPointerException}. Beyond that, a failure to
     * create the resource, open its writer or write the merged manifest is reported as
     * {@code AW2300} rather than thrown. But {@link #readExisting(MessagerReporter)} is called
     * before that {@code try} begins, and a previous build's manifest that parses as JSON yet
     * carries a negative version or a member entry with a blank name throws
     * {@code IllegalArgumentException} out of {@link ManifestReader#read} — a failure this method
     * does not catch, so it escapes {@code write} instead of becoming a diagnostic.
     *
     * @param reporter where to report a failure; must not be {@code null}
     * @throws NullPointerException     if {@code reporter} is {@code null}
     * @throws IllegalArgumentException if a previous build's manifest parses as JSON but names a
     *                                   negative version or a member with a blank name
     */
    void write(@NotNull final MessagerReporter reporter) {
        Objects.requireNonNull(reporter, "reporter");
        if (isEmpty()) {
            // Nothing was compiled that declares a weave or an extension. Writing an empty
            // manifest would make every module in a build ship one, and would turn "no manifest" —
            // a useful signal that the processor is not configured — into a file that says nothing.
            return;
        }

        final List<WeaveManifest.Extension> contributed = new ArrayList<>();
        this.extensions.values().forEach(contributed::addAll);
        final WeaveManifest fresh = WeaveManifest.of(GENERATOR,
                new ArrayList<>(this.collected.values()), contributed);
        final WeaveManifest existing = readExisting(reporter);
        final WeaveManifest merged = existing == null ? fresh : existing.merge(fresh);

        try {
            final FileObject file = this.filer.createResource(
                    StandardLocation.CLASS_OUTPUT, "", WeaveManifest.RESOURCE);
            try (Writer out = file.openWriter()) {
                out.write(ManifestWriter.write(merged));
            }
        } catch (final IOException | IllegalStateException failed) {
            reporter.report(Diagnostic.builder(DiagnosticCode.MANIFEST_MALFORMED)
                    .message("the weave manifest could not be written: " + failed.getMessage())
                    .detail("without it the runtime has no way to discover this module's weaves")
                    .remedy("check that the annotation processor can write to the compilation "
                            + "output directory")
                    .build());
        }
    }

    /**
     * Reads the manifest the output directory already holds, if there is a readable one.
     *
     * <p>Absence is the ordinary case on a clean build, so it is not reported: an
     * {@link IOException} from opening the resource, an {@link IllegalArgumentException} or an
     * {@link IllegalStateException} from the filer, and a blank file all return {@code null}
     * quietly. A file that is present, non-blank and unparseable as JSON produces a diagnostic
     * instead, and that one comes from {@link ManifestReader} rather than from here — {@code AW2300}
     * for a document that does not parse, {@code AW2301} for one from a schema this release does not
     * read. But a file that parses as JSON and still names a negative version or a member with a
     * blank name is not turned into a diagnostic at all: {@link ManifestReader#read} lets the
     * {@link IllegalArgumentException} from {@link WeaveManifest}'s own validation propagate, and
     * this method does not catch it, so it escapes {@link #write(MessagerReporter)} instead.
     *
     * <p>The reporter is adapted to a {@link de.splatgames.aether.weaver.api.spi.Reporter}, whose
     * single method takes a diagnostic and no position; a diagnostic about the file on disk has no
     * element in this compilation to point at.
     *
     * <p>Returning {@code null} for an unreadable file means the fresh manifest is written on its
     * own, so an incremental build whose previous manifest was corrupt silently loses the weaves
     * it did not recompile. The diagnostic is what tells the reader to rebuild.
     *
     * @param reporter where {@link ManifestReader} reports its own diagnostics; must not be
     *                 {@code null}
     * @return the parsed manifest, or {@code null} when there is none, it is blank, it cannot be
     *         opened or it cannot be parsed as JSON
     * @throws IllegalArgumentException if the file parses as JSON but names a negative version or a
     *                                   member with a blank name
     */
    @Nullable
    private WeaveManifest readExisting(@NotNull final MessagerReporter reporter) {
        final String text;
        try {
            final FileObject file = this.filer.getResource(
                    StandardLocation.CLASS_OUTPUT, "", WeaveManifest.RESOURCE);
            try (InputStream stream = file.openInputStream()) {
                text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (final IOException | IllegalArgumentException | IllegalStateException absent) {
            return null;
        }
        if (text.isBlank()) {
            return null;
        }
        final Reporter bridge = reporter::report;
        return ManifestReader.read(text, "the previous build's manifest", bridge);
    }

    // --- building an entry from what the processor already knows ---------------------------

    /**
     * Builds one weave entry from values the caller has already resolved.
     *
     * <p>Nothing is defaulted or validated here beyond what
     * {@link de.splatgames.aether.weaver.api.manifest.WeaveManifest.Weave} enforces for itself. The
     * caller has already turned an omitted {@code @Weave} element into the string the manifest is
     * to carry, because an element the source never wrote is invisible to
     * {@link Anchors#enumOf(javax.lang.model.element.AnnotationMirror, String)} and the manifest
     * needs a value either way.
     *
     * <p>{@code weave} is checked for {@code null} and otherwise not read; {@code binaryName} and
     * {@code members} are what the element has already been reduced to.
     *
     * @param weave      the weave class; must not be {@code null}
     * @param binaryName the weave's binary name, which is how the runtime identifies it
     * @param kind       the weave kind as its enum constant's simple name
     * @param priority   the declared priority
     * @param require    the requirement policy as its enum constant's simple name
     * @param phase      the phase as its enum constant's simple name
     * @param tags       the declared tags, in source order
     * @param targets    the resolved targets as binary names, in resolution order
     * @param members    the weave's shadowed, merged and generated members
     * @param injectors  the handler specifications to convert, in declaration order
     * @return the entry to record
     * @throws NullPointerException if {@code weave} is {@code null}, or as thrown by the entry's
     *                              own constructor
     */
    @NotNull
    static WeaveManifest.Weave entry(@NotNull final TypeElement weave,
                                     @NotNull final String binaryName,
                                     @NotNull final String kind,
                                     final int priority,
                                     @NotNull final String require,
                                     @NotNull final String phase,
                                     @NotNull final List<String> tags,
                                     @NotNull final List<String> targets,
                                     @NotNull final List<WeaveManifest.Member> members,
                                     @NotNull final List<InjectorSpec> injectors) {
        Objects.requireNonNull(weave, "weave");
        final List<WeaveManifest.Injector> entries = new ArrayList<>(injectors.size());
        for (final InjectorSpec spec : injectors) {
            entries.add(injector(spec));
        }
        return new WeaveManifest.Weave(binaryName, kind, priority, require, phase, tags, targets,
                members, entries);
    }

    /**
     * Converts one handler specification into its manifest form.
     *
     * <p>Three of the fields are not a straight copy.
     *
     * <ul>
     *   <li>The kind is upper-cased with {@link java.util.Locale#ROOT}, so the text does not depend
     *       on the locale the build ran under. The manifest is asserted to be byte-identical
     *       between two builds of the same sources.
     *   <li>The handler becomes its name followed by its descriptor. That pair names one method
     *       exactly, so the runtime needs no overload resolution to find it.
     *   <li>The target method is stored in the selector's canonical descriptor form when
     *       {@link de.splatgames.aether.weaver.api.select.MethodSelector#isFullyQualified()} holds,
     *       and as the text the author wrote otherwise. That form needs the owner, the return type
     *       and every parameter to be a resolved type, and a class name resolves to none of them: the
     *       parser produces {@code Exact} only for a primitive, and every class name — the owner
     *       included — becomes {@code Named}, whose {@code isResolved()} is {@code false}. So a
     *       selector leaving the owner or the return type open, naming its parameters loosely, or
     *       naming the wildcard as its method name is not the exception; naming any class at all
     *       already forfeits the canonical form, and unresolved is the ordinary case for what the
     *       manifest carries.
     * </ul>
     *
     * <p>A point that names no target stores an empty string rather than being omitted, because
     * every point occupies a position in the list.
     *
     * @param spec the specification to convert; must not be {@code null}
     * @return the injector entry
     */
    @NotNull
    private static WeaveManifest.Injector injector(@NotNull final InjectorSpec spec) {
        final List<WeaveManifest.Point> points = new ArrayList<>(spec.points().size());
        for (final PointSpec point : spec.points()) {
            points.add(new WeaveManifest.Point(
                    point.point(),
                    point.hasTarget() ? point.rawTarget() : "",
                    point.ordinal(),
                    point.shift().name(),
                    point.by(),
                    point.access().name(),
                    point.slice()));
        }
        return new WeaveManifest.Injector(
                spec.kind().id().toUpperCase(java.util.Locale.ROOT),
                spec.id(),
                spec.handler().name() + spec.handler().type().descriptorString(),
                spec.method().canonical().orElse(spec.rawMethod()),
                points,
                spec.require(),
                spec.allow(),
                spec.group());
    }
}
