package de.splatgames.aether.weaver.runtime;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import de.splatgames.aether.weaver.api.model.WeaveCandidate;
import de.splatgames.aether.weaver.api.spi.ClassSource;
import de.splatgames.aether.weaver.api.spi.DiagnosticListener;
import de.splatgames.aether.weaver.api.spi.DiscoveryContext;
import de.splatgames.aether.weaver.engine.model.WeaveClass;
import de.splatgames.aether.weaver.engine.parse.WeaveClassParser;
import de.splatgames.aether.weaver.runtime.config.WeaverConfig;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.lang.classfile.ClassFile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Turns the weave manifests on a classpath into the parsed weaves a weaver is built from.
 *
 * <p>The step both runtime drivers share: {@link WeavingClassLoader#create} runs it before building
 * its weaver, and the agent runs it once from {@code premain} or {@code agentmain}. A
 * {@link Discovered} carries the two things
 * {@link de.splatgames.aether.weaver.engine.WeaverBuilder} then wants — the weaves themselves, and
 * a source over their own class files.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class WeaveDiscovery {

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private WeaveDiscovery() {
        throw new AssertionError("no instances");
    }

    /**
     * Reads the classpath's weave manifests, parses what they name, and drops what configuration
     * switches off.
     *
     * <p>Candidates come from {@link ManifestWeaveSource}, which reports {@code AW2300},
     * {@code AW2302} and {@code AW2303} against the given listener. A candidate the manifest names
     * but whose class is not in the artefact that named it is reported here as {@code AW2300} and
     * skipped: rebuild the artefact, whose manifest has outlived the class. A candidate whose bytes
     * parse but no longer carry a {@code @Weave} annotation is skipped by {@link WeaveClassParser}
     * with no diagnostic at all — a manifest naming a class that has since dropped the annotation
     * looks like an ordinary class to the parser. A candidate that does carry the annotation but
     * names no usable target, or otherwise fails the parser's own checks, is reported before being
     * skipped.
     *
     * <p>Configuration is applied after parsing, not before, so a weave that is switched off is
     * still checked and still reports its own errors. The deployment that switches it back on does
     * not discover a fault that has been present all along.
     *
     * <p>Nothing here is guarded: an unchecked exception raised while collecting the candidates,
     * while fetching a candidate's bytes, or while parsing them leaves this method rather than
     * becoming a diagnostic. A jar that cannot be read yields a
     * {@link java.io.UncheckedIOException} from the second of those.
     *
     * @param loader   the class loader whose classpath is searched; must not be {@code null}
     * @param config   the configuration deciding which discovered weaves are kept; must not be
     *                 {@code null}
     * @param listener the listener every diagnostic named here is reported to; must not be
     *                 {@code null}
     * @return the weaves that survived parsing and configuration, in classpath order, with a source
     *         over precisely those weave classes' bytes; empty when nothing was found or everything
     *         was switched off
     * @throws NullPointerException if any argument is {@code null}
     */
    @NotNull
    public static Discovered discover(@NotNull final ClassLoader loader,
                                      @NotNull final WeaverConfig config,
                                      @NotNull final DiagnosticListener listener) {
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(listener, "listener");

        final List<WeaveCandidate> candidates = new ManifestWeaveSource()
                .candidates(new DiscoveryContext(loader, listener::report))
                .toList();

        final WeaveClassParser parser = new WeaveClassParser(listener);
        final List<WeaveClass> weaves = new ArrayList<>(candidates.size());
        final Map<String, byte[]> bytes = new LinkedHashMap<>();

        for (final WeaveCandidate candidate : candidates) {
            final byte[] classFile = candidate.bytes().orElse(null);
            if (classFile == null) {
                listener.report(Diagnostic.builder(DiagnosticCode.MANIFEST_MALFORMED)
                        .message(candidate.className() + " is named by a manifest but is not in "
                                + "the artefact that named it")
                        .detail("discovered via " + candidate.origin().describe())
                        .remedy("rebuild the artefact; its manifest has outlived the class")
                        .build());
                continue;
            }
            // The candidate's own origin, not a new one wrapped around its description. The
            // wrapping produced "weave manifest (weave manifest (weaves.json))" in every explain
            // report, which the report is what found.
            parser.parse(ClassFile.of().parse(classFile), candidate.origin())
                    .ifPresent(weave -> {
                        // Filtered after parsing, not before: a weave that is switched off must
                        // still be well-formed, or the first deployment that switches it back on
                        // discovers a problem that has been there for months.
                        if (config.isEnabled(weave.binaryName(), weave.tags())) {
                            weaves.add(weave);
                            bytes.put(candidate.internalName(), classFile);
                        }
                    });
        }
        return new Discovered(List.copyOf(weaves), ClassSource.ofMap(bytes));
    }

    /**
     * What a discovery run found: the weaves, and the bytes they were parsed from.
     *
     * <p>The class source holds the discovered weave classes and nothing else.
     *
     * @param weaves  the weaves, in the order they were discovered
     * @param classes a source over those weave classes' own bytes, keyed by internal name
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public record Discovered(@NotNull @Unmodifiable List<WeaveClass> weaves,
                             @NotNull ClassSource classes) {

        /**
         * Copies the weave list so that the record cannot be changed through the caller's own
         * reference to it.
         *
         * @throws NullPointerException if either component is {@code null}
         */
        public Discovered {
            weaves = List.copyOf(Objects.requireNonNull(weaves, "weaves"));
            Objects.requireNonNull(classes, "classes");
        }

        /**
         * Reports whether anything was found.
         *
         * @return {@code true} when the weave list is empty; the class source is not consulted
         */
        @Contract(pure = true)
        public boolean isEmpty() {
            return this.weaves.isEmpty();
        }

        /**
         * Returns a copy carrying a different weave list.
         *
         * <p>The class source is handed on unchanged, so it still holds the bytes of any weave the
         * narrowed list drops. That is harmless — the source is looked up by name — and it is what
         * keeps narrowing cheap.
         *
         * @param narrowed the weaves the copy is to carry; must not be {@code null}
         * @return a copy over the same class source
         * @throws NullPointerException if {@code narrowed} is {@code null}
         */
        @Contract(value = "_ -> new", pure = true)
        @NotNull
        public Discovered with(@NotNull final List<WeaveClass> narrowed) {
            return new Discovered(narrowed, this.classes);
        }

        /**
         * Returns a description counting the weaves.
         *
         * @return a description of the form {@code Discovered[3 weaves]}, with the singular used
         *         for one weave
         */
        @Override
        @NotNull
        public String toString() {
            return "Discovered[" + this.weaves.size() + " weave"
                    + (this.weaves.size() == 1 ? "" : "s") + ']';
        }
    }
}
