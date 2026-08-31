package de.splatgames.aether.weaver.maven;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import de.splatgames.aether.weaver.api.diagnostic.WeaveException;
import de.splatgames.aether.weaver.api.spi.DiagnosticListener;
import de.splatgames.aether.weaver.engine.Weaver;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Weaves classes out of dependency jars into a directory of their own.
 *
 * <p>Reached from the {@code weave} goal and only when its {@code weaveDependencies} parameter is
 * set. The jars are never written to: a class the weaver changed is written under the output
 * directory at its own package path, and a class it did not change is not copied at all, so the
 * output holds the modified classes and nothing else and deleting it undoes the whole operation.
 *
 * <p>Nothing here puts that directory on any classpath. Whatever assembles a classpath afterwards
 * has to place it ahead of the dependency jars, which is what the remedy of {@code AW2501} says.
 *
 * <h2>Signed artefacts</h2>
 *
 * <p>A signed jar is refused as {@code AW3002} and not one of its classes is read. Constructing
 * this with {@code allowSigned} weaves it anyway and reports {@code AW3020} instead, once for each
 * such jar and whether or not any of its classes turn out to be modified.
 *
 * <h2>What a run reports</h2>
 *
 * <p>{@code AW2501} once at the end of {@link #weave(List)}, naming every modified class, and only
 * when there was at least one. {@code AW4090} for a jar that cannot be read and again for a class
 * the weaver refuses; either leaves the rest of the run going.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
final class DependencyWeaver {

    /** The weaver holding the plan, already used on the module's own classes. */
    private final Weaver weaver;

    /** Where a woven copy is written, at the class's own package path below it. */
    private final Path output;

    /** Whether a signed artefact is woven anyway rather than refused. */
    private final boolean allowSigned;

    /** Where every diagnostic of this run goes. */
    private final DiagnosticListener listener;

    /** Every class written so far, as its binary name and its artefact, and never cleared. */
    private final List<String> modified = new ArrayList<>();

    /**
     * Prepares to weave dependencies with a weaver that already holds the plan.
     *
     * @param weaver      the weaver to put each dependency class through; must not be {@code null}
     * @param output      the directory woven copies are written below, which is created as needed;
     *                    must not be {@code null}
     * @param allowSigned whether to weave a signed artefact rather than refuse it
     * @param listener    where every diagnostic goes; must not be {@code null}
     * @throws NullPointerException if {@code weaver}, {@code output} or {@code listener} is
     *                              {@code null}
     */
    DependencyWeaver(@NotNull final Weaver weaver,
                     @NotNull final Path output,
                     final boolean allowSigned,
                     @NotNull final DiagnosticListener listener) {
        this.weaver = Objects.requireNonNull(weaver, "weaver");
        this.output = Objects.requireNonNull(output, "output");
        this.allowSigned = allowSigned;
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Weaves every artefact in turn and reports what was changed.
     *
     * <p>The list of modified classes is never cleared, so a second call on the same instance
     * reports the first call's classes over again. The goal builds one instance per run.
     *
     * @param artefacts the jars to weave, in the order they are to be read; must not be
     *                  {@code null}
     * @return the number of classes written, which is zero when every artefact was refused, was
     *         unreadable or held nothing the plan touches
     * @throws NullPointerException if {@code artefacts} is {@code null}
     * @throws UncheckedIOException if a woven class cannot be written to the output directory
     */
    int weave(@NotNull final List<Path> artefacts) {
        Objects.requireNonNull(artefacts, "artefacts");
        int written = 0;
        for (final Path artefact : artefacts) {
            written += weaveOne(new DependencyJar(artefact));
        }
        if (!this.modified.isEmpty()) {
            this.listener.report(Diagnostic.builder(DiagnosticCode.DEPENDENCY_CLASSES_MODIFIED)
                    .message(this.modified.size() + " class"
                            + (this.modified.size() == 1 ? "" : "es")
                            + " from dependencies were rewritten into " + this.output)
                    .details(this.modified)
                    .remedy("the original artefacts were not touched, so deleting this directory "
                            + "undoes the whole operation. Nothing is on the classpath yet: put "
                            + "the directory ahead of the dependency jars where your build "
                            + "assembles classpaths — surefire's additionalClasspathElements, or "
                            + "shade's include order")
                    .build());
        }
        return written;
    }

    /**
     * Weaves one artefact.
     *
     * @param jar the artefact to read
     * @return the number of its classes that were written, and zero when it is signed and not
     *         permitted or when it could not be read
     * @throws UncheckedIOException if a woven class cannot be written to the output directory
     */
    private int weaveOne(@NotNull final DependencyJar jar) {
        try {
            if (jar.isSigned() && !refuseSigned(jar)) {
                return 0;
            }
            final int[] written = {0};
            jar.forEachClass((internalName, bytes) -> {
                final byte[] woven = weaveOrReport(internalName, bytes);
                if (woven != null) {
                    write(internalName, woven);
                    this.modified.add(internalName.replace('/', '.') + "  (" + jar.name() + ')');
                    written[0]++;
                }
            });
            return written[0];
        } catch (final IOException unreadable) {
            this.listener.report(Diagnostic.builder(DiagnosticCode.INTERNAL_ERROR)
                    .message(jar.name() + " could not be read: " + unreadable.getMessage())
                    .build());
            return 0;
        }
    }

    /**
     * Decides what happens to a signed artefact, and says so either way.
     *
     * <p>Both outcomes are reported. An override that produced no output would be an override
     * nobody reviewing the build log could see was used, so permitting the artefact is as loud as
     * refusing it: the code, the severity, the message and the detail all differ between the two,
     * and only the refusal carries a remedy, since only it leaves the caller something to do about
     * it.
     *
     * @param jar the signed artefact
     * @return {@code true} when {@code allowSigned} was set, having reported {@code AW3020}, and
     *         {@code false} otherwise, having reported {@code AW3002}
     * @throws IOException if the archive has to be reopened to name its signer and cannot be
     */
    private boolean refuseSigned(@NotNull final DependencyJar jar) throws IOException {
        if (this.allowSigned) {
            // Reported even when permitted: an override that produces no output is an override
            // nobody reviewing the build log will ever notice was used.
            this.listener.report(Diagnostic.builder(DiagnosticCode.POLICY_OVERRIDE_ACTIVE)
                    .message("weaving " + jar.name() + ", which is signed by \"" + jar.signer()
                            + "\", because allowSigned is set")
                    .detail("the signature's integrity guarantee is void for the woven copies, "
                            + "while tooling continues to report the artefact as signed")
                    .build());
            return true;
        }
        this.listener.report(Diagnostic.builder(DiagnosticCode.POLICY_DENIED_SIGNED_ARTEFACT)
                .message("refusing to weave " + jar.name() + ": it comes from a JAR signed by \""
                        + jar.signer() + '"')
                .detail("weaving would void that signature's integrity guarantee while tooling "
                        + "continues to report the artefact as signed")
                .remedy("if this is intentional, set allowSigned and document the decision — the "
                        + "woven classes will record that the override was used")
                .build());
        return false;
    }

    /**
     * Weaves one class, turning a refusal into a diagnostic.
     *
     * @param internalName the class's internal name
     * @param bytes        the class as the archive holds it
     * @return the woven bytes, or {@code null} both when the plan leaves the class alone and when
     *         the weaver refused it, the latter having been reported as {@code AW4090}
     */
    private byte[] weaveOrReport(@NotNull final String internalName, final byte[] bytes) {
        try {
            return this.weaver.weave(internalName, bytes);
        } catch (final WeaveException refused) {
            this.listener.report(Diagnostic.builder(DiagnosticCode.INTERNAL_ERROR)
                    .message(internalName + " could not be woven: " + refused.getMessage())
                    .build());
            return null;
        }
    }

    /**
     * Writes one woven class below the output directory.
     *
     * @param internalName the class's internal name, which decides the path
     * @param woven        the bytes to write
     * @throws UncheckedIOException if the directories or the file cannot be created
     */
    private void write(@NotNull final String internalName, final byte[] woven) {
        final Path file = this.output.resolve(internalName + ".class");
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, woven);
        } catch (final IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    /**
     * Returns a description naming the output directory and how much has been written.
     *
     * @return the output directory and the number of classes modified so far
     */
    @Override
    @NotNull
    public String toString() {
        return "DependencyWeaver[output=" + this.output + ", modified=" + this.modified.size()
                + ']';
    }
}
