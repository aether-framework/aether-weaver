package de.splatgames.aether.weaver.api.model;

import de.splatgames.aether.weaver.api.spi.ClassSource;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.Optional;

/**
 * A class that has been named as a weave but not yet read.
 *
 * <p>Discovery and parsing are separate steps, and this is what passes between them. A
 * {@link de.splatgames.aether.weaver.api.spi.WeaveSource} answers with candidates rather than with
 * parsed weaves because a source knows where class files live and nothing about what a weave class
 * has to look like; a candidate therefore asserts only that something claimed this class is a
 * weave, and carries with it the means to fetch its bytes and the provenance to blame when the
 * claim turns out to be wrong.
 *
 * <p>Being a candidate implies nothing about the class. It may not exist in the artefact that named
 * it, it may exist and carry no {@code @Weave} annotation, and it may be a weave that the
 * configuration in force switches off. Each of those is decided after the bytes are read, not here.
 *
 * <h2>Naming</h2>
 *
 * <p>{@link #className()} is the binary name with dots, as it would be written in source and as a
 * manifest records it: {@code com.acme.audit.LedgerWeave}, and {@code com.acme.Outer$Inner} for a
 * nested class. {@link #internalName()} is the same name with dots replaced by slashes, which is
 * the form the class file format and every lookup in {@link ClassSource} use. Neither carries a
 * {@code .class} suffix; {@link ClassSource} appends that itself.
 *
 * <h2>Fetching the bytes</h2>
 *
 * <p>{@link #bytes()} delegates to the {@link ClassSource} the candidate was built with, looked up
 * under {@link #internalName()}. An empty result means the source does not have the class, which is
 * the ordinary outcome for a manifest that has outlived the artefact it describes; the runtime
 * reports that case as {@code AW2300} and skips the candidate rather than failing the whole
 * discovery. A source is free to throw an {@link java.io.UncheckedIOException} instead when a class
 * is present but unreadable, and the sources built by {@link ClassSource#ofClassLoader(ClassLoader)}
 * and {@link ClassSource#ofPath(java.nio.file.Path)} do exactly that, because a broken classpath
 * entry reported as an absent class sends the reader looking in the wrong place.
 *
 * <p>Nothing is cached. Two calls to {@link #bytes()} perform two lookups, and a source built by
 * {@link ClassSource#ofMap(java.util.Map)} hands out a fresh copy each time, so a caller that
 * mutates the returned array does not disturb the next one.
 *
 * <h2>Duplicates</h2>
 *
 * <p>Two candidates may name the same class, one per artefact that declared it. They are not
 * merged: each keeps its own {@link ClassSource} and its own {@link Origin}, so each reads the
 * bytes its own artefact shipped. Only one class of that name can ultimately be loaded, and which
 * one is the classpath's decision; the runtime's manifest source reports the situation as
 * {@code AW2303} and lets both candidates through.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * WeaveCandidate candidate = new WeaveCandidate(
 *         "com.acme.audit.LedgerWeave",
 *         ClassSource.ofPath(Path.of("target", "classes")),
 *         Origin.of("build directory", "target/classes"));
 *
 * candidate.internalName();          // com/acme/audit/LedgerWeave
 * candidate.bytes();                 // reads target/classes/com/acme/audit/LedgerWeave.class
 * candidate.toString();              // WeaveCandidate[com.acme.audit.LedgerWeave from build ...]
 * }</pre>
 *
 * @param className the binary name of the class, written with dots; never blank
 * @param source    where the class file can be fetched from
 * @param origin    what named this class as a weave, for diagnostics
 * @author Erik Pförtner
 * @since 0.1.0
 * @see de.splatgames.aether.weaver.api.spi.WeaveSource
 */
public record WeaveCandidate(@NotNull String className,
                             @NotNull ClassSource source,
                             @NotNull Origin origin) {

    /**
     * Checks that the candidate names a class.
     *
     * <p>The name is not validated beyond being non-blank. A string that is not a usable binary
     * class name simply fails to resolve against the {@link ClassSource}, and is reported there
     * with the artefact that produced it.
     *
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if {@code className} is empty or contains only whitespace
     */
    public WeaveCandidate {
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(origin, "origin");
        if (className.isBlank()) {
            throw new IllegalArgumentException("a weave candidate must name a class");
        }
    }

    /**
     * Reads the class file for this candidate from its source.
     *
     * <p>Performed on every call; nothing is remembered between them.
     *
     * @return the class file bytes, or an empty {@link Optional} when the source does not have the
     *         class
     * @throws java.io.UncheckedIOException if the source has the class but cannot read it
     */
    @NotNull
    public Optional<byte[]> bytes() {
        return this.source.find(internalName());
    }

    /**
     * Returns {@link #className()} in the class file format's own spelling.
     *
     * @return the class name with every dot replaced by a slash, and no {@code .class} suffix
     */
    @Contract(pure = true)
    @NotNull
    public String internalName() {
        return this.className.replace('.', '/');
    }

    /**
     * Returns the class name and where it was declared, in one line.
     *
     * <p>The {@link ClassSource} is deliberately left out: it is usually a lambda whose own
     * {@code toString} names nothing a reader could act on, and {@link Origin#describe()} already
     * says which artefact to look in.
     *
     * @return {@code WeaveCandidate[<className> from <origin>]}
     */
    @Override
    @NotNull
    public String toString() {
        return "WeaveCandidate[" + this.className + " from " + this.origin.describe() + ']';
    }
}
