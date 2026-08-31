package de.splatgames.aether.weaver.api.model;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Records where a weave declaration was found, so that a diagnostic about it can name the artefact
 * to go and fix rather than only the class that is wrong.
 *
 * <p>An origin is provenance, not identity. Two weaves discovered from the same manifest share one
 * origin, and the same weave class discovered twice from two artefacts carries two origins that
 * differ in {@link #location()}. Nothing keys on an origin; it exists to be printed.
 *
 * <h2>The two components</h2>
 *
 * <p>{@link #source()} names the mechanism that produced the declaration and is a fixed string
 * chosen by that mechanism rather than by the user. {@link #location()} narrows it to the
 * particular file, URL or class the mechanism was reading at the time, and is {@code null} when the
 * mechanism has nothing more specific to say. The distinction matters to the reader: a source with
 * no location tells them which subsystem to look at, and a source with a location tells them which
 * file to open.
 *
 * <p>This project produces three pairs. The runtime's manifest weave source uses
 * {@code "weave manifest"} with a location computed by first cutting the manifest's URL at
 * {@code !/} when the URL has one, then taking the last {@code /}-separated segment of what
 * remains — the jar file's name for a manifest read out of a jar, or the manifest's own last path
 * segment (typically {@code "weaves.json"}) for one read from a directory — never the manifest's
 * full URL. The test kit uses {@code "testkit"} with the weave class's binary name. The Maven
 * plugin's own class directory uses {@code "the module's own classes"} with the weave class's
 * binary name. A driver or a {@link de.splatgames.aether.weaver.api.spi.WeaveSource} of your own
 * chooses its own pair.
 *
 * <h2>Where it surfaces</h2>
 *
 * <p>Every diagnostic reported while a weave class is being read carries
 * {@code "discovered via " + describe()} as a detail line, and the explain report prints the origin
 * beside the weave's priority. Neither adds a wrapper of its own: an origin is passed on unchanged
 * from the candidate that carried it, because describing an already-described origin produces
 * {@code "weave manifest (weave manifest (weaves.json))"}.
 *
 * <h2>Validation</h2>
 *
 * <p>{@link #source()} must be present and must contain a non-whitespace character;
 * {@link #location()} may be {@code null} but, when given, is used exactly as written. Neither is
 * trimmed, escaped or interpreted.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * Origin origin = Origin.of("weave manifest", "audit-1.4.jar");
 *
 * origin.describe();
 * // weave manifest (audit-1.4.jar)
 *
 * Origin.of("testkit", null).describe();
 * // testkit
 * }</pre>
 *
 * @param source   the mechanism that produced the declaration; never blank
 * @param location where that mechanism found it, or {@code null} when it has nothing more specific
 *                 to name
 * @author Erik Pförtner
 * @since 0.1.0
 * @see WeaveCandidate
 */
public record Origin(String source, @Nullable String location) {

    /**
     * Checks that a source was given.
     *
     * @throws NullPointerException     if {@code source} is {@code null}
     * @throws IllegalArgumentException if {@code source} is empty or contains only whitespace
     */
    public Origin {
        Objects.requireNonNull(source, "source");
        if (source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
    }

    /**
     * Returns an origin with the given source and location.
     *
     * <p>Equivalent to the canonical constructor and rejects the same arguments; it exists so that
     * a call site reads as {@code Origin.of("weave manifest", where)} rather than as an allocation.
     *
     * @param source   the mechanism that produced the declaration; must not be {@code null} or
     *                 blank
     * @param location where that mechanism found it, or {@code null}
     * @return the origin
     * @throws NullPointerException     if {@code source} is {@code null}
     * @throws IllegalArgumentException if {@code source} is empty or contains only whitespace
     */
    @Contract(value = "_, _ -> new", pure = true)
    @NotNull
    public static Origin of(@NotNull final String source, final @Nullable String location) {
        return new Origin(source, location);
    }

    /**
     * Returns this origin as one line of human-readable text.
     *
     * <p>The source alone when there is no location, and {@code source (location)} when there is.
     * This is the form every diagnostic detail and every explain report uses, so an origin printed
     * by two different subsystems looks the same in both.
     *
     * @return the source, followed by the location in parentheses when one is present
     */
    @Contract(pure = true)
    @NotNull
    public String describe() {
        return this.location == null ? this.source : this.source + " (" + this.location + ')';
    }
}
