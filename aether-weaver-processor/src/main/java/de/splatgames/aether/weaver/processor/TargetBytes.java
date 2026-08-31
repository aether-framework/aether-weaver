package de.splatgames.aether.weaver.processor;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.annotation.processing.Filer;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Finds and parses a target's compiled class file during annotation processing.
 *
 * <p>Injection points cannot be checked against source elements — a point names an instruction, and
 * the compiler's model has no instructions — so the one check that resolves points reads the
 * target's bytes instead. They are only there when the target was compiled before this round: a
 * target compiled from source alongside the weave has no class file yet, because annotation
 * processing runs ahead of code generation.
 *
 * <p>Every way of not finding the bytes ends in the same place, {@code null} and one
 * {@code AW1200}. That is deliberate: none of the reasons is a fault in the user's declaration, and
 * the points are validated again at weave time where the class file always exists. The practical
 * consequence for the reader is that the point diagnostics in the {@code AW11xx} range arrive from
 * the weaver rather than from the compiler for that target.
 *
 * <h2>Caching</h2>
 *
 * <p>Results are cached by binary name, and a failure is cached as readily as a success. One
 * instance belongs to one processor instance and so spans the whole compilation, which is what
 * keeps {@code AW1200} to one notice per target however many weaves name it and however many
 * injections each of them declares.
 *
 * <h2>Thread safety</h2>
 *
 * <p>Not safe to share. The cache is a plain {@link HashMap} written on every miss, and one
 * instance belongs to one processor, which the host compiler drives from a single thread.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
final class TargetBytes {

    /** The compiler's resource reader, which is how a processor reaches the compile classpath. */
    private final Filer filer;

    /** The element utilities, used for the binary name a class file is looked up under. */
    private final Elements elements;

    /**
     * Parsed targets by binary name, holding {@code null} for a target that could not be read so
     * that a second lookup neither reads again nor reports again.
     */
    private final Map<String, ClassModel> cache = new HashMap<>();

    /**
     * Creates a reader over one processing environment.
     *
     * @param filer    the environment's filer; must not be {@code null}
     * @param elements the environment's element utilities; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    TargetBytes(@NotNull final Filer filer, @NotNull final Elements elements) {
        this.filer = Objects.requireNonNull(filer, "filer");
        this.elements = Objects.requireNonNull(elements, "elements");
    }

    /**
     * Returns the target's parsed class file, reporting once when there is none.
     *
     * <p>{@code AW1200} is reported on the first lookup that fails and never again for that target,
     * the failure being cached along with the successes. It is informational and nothing needs
     * doing about it; the message names the class and the detail says the points are validated at
     * weave time.
     *
     * @param target   the target whose bytes are wanted; must not be {@code null}
     * @param anchor   where to report the notice, normally the literal that named the target; must
     *                 not be {@code null}
     * @param reporter where to report; must not be {@code null}
     * @return the parsed class, or {@code null} when the class file is absent, unreadable or
     *         unparseable
     * @throws NullPointerException if any argument is {@code null}
     */
    @Nullable
    ClassModel of(@NotNull final TypeElement target,
                  @NotNull final Anchor anchor,
                  @NotNull final MessagerReporter reporter) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(reporter, "reporter");

        final String binary = this.elements.getBinaryName(target).toString();
        if (this.cache.containsKey(binary)) {
            return this.cache.get(binary);
        }

        final ClassModel model = read(binary);
        this.cache.put(binary, model);
        if (model == null) {
            reporter.report(Diagnostic.builder(DiagnosticCode.INJECTION_POINTS_NOT_VALIDATED)
                    .message("the class file for " + binary + " was not readable, so its "
                            + "injection points could not be checked here")
                    .detail("they are validated at weave time, where the class file always exists")
                    .remedy("nothing needs doing; this is expected when the target is compiled "
                            + "from source in the same round as the weave")
                    .build(), anchor);
        }
        return model;
    }

    /**
     * Reads and parses one class file off the compile classpath.
     *
     * <p>The binary name is split at its last dot into a package and a file name, so a nested class
     * is looked up under the {@code Outer$Inner.class} its binary name already spells. Only
     * {@link StandardLocation#CLASS_PATH} is searched: a class file this compilation is about to
     * produce is not on it, which is the case {@code AW1200} exists for.
     *
     * <p>An empty file is treated as absent rather than as a parse failure, and a file the
     * class-file parser refuses is treated the same way. A class file that cannot be parsed here is
     * one the weaver could not have woven either, so refusing it at this position would state a
     * fault the weaver states better.
     *
     * @param binary the target's binary name; must not be {@code null}
     * @return the parsed class, or {@code null} when nothing was found, the file was empty, or it
     *         did not parse
     */
    @Nullable
    private ClassModel read(@NotNull final String binary) {
        final int lastDot = binary.lastIndexOf('.');
        final String packageName = lastDot < 0 ? "" : binary.substring(0, lastDot);
        final String fileName = binary.substring(lastDot + 1) + ".class";

        final byte[] bytes = readFrom(StandardLocation.CLASS_PATH, packageName, fileName);
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            return ClassFile.of().parse(bytes);
        } catch (final IllegalArgumentException malformed) {
            // A class file this parser cannot read is one the weaver could not have woven either,
            // and saying so here would duplicate AW2001 at a worse position.
            return null;
        }
    }

    /**
     * Reads one resource whole, answering {@code null} for every way that can fail.
     *
     * <p>The checked and unchecked failures a {@link Filer} lookup produces all mean the same thing
     * to a caller that only wants bytes if they are there, and none of them means the weave is
     * wrong. {@code FilerException} in particular is thrown for a resource opened twice in one
     * round, which is a fact about the filer's bookkeeping rather than about the user's code, and
     * is why the caller caches its misses. Any other {@link RuntimeException} a file manager throws
     * is caught as well, because a processor that propagates one ends the compilation with a stack
     * trace in place of the user's real errors.
     *
     * @param location    the location to search; must not be {@code null}
     * @param packageName the package the resource sits in, empty for the unnamed package; must not
     *                    be {@code null}
     * @param fileName    the resource's file name; must not be {@code null}
     * @return the bytes, or {@code null} when the resource could not be opened or read
     */
    private byte @Nullable [] readFrom(@NotNull final StandardLocation location,
                                       @NotNull final String packageName,
                                       @NotNull final String fileName) {
        try {
            final FileObject file = this.filer.getResource(location, packageName, fileName);
            try (InputStream stream = file.openInputStream()) {
                return stream.readAllBytes();
            }
        } catch (final IOException | IllegalArgumentException | IllegalStateException absent) {
            // Every one of these means "not there", and none of them means "the weave is
            // wrong". FilerException in particular is thrown for a resource opened twice in one
            // round, which is a fact about the Filer's bookkeeping and not about the user's code.
            return null;
        } catch (final RuntimeException unexpected) {
            // A file manager is allowed to be surprising; a processor is not allowed to abort the
            // compilation because one was.
            return null;
        }
    }

    /**
     * Reports whether a type has a class file this reader could name.
     *
     * <p>A top-level or member type is one a class literal or a qualified name can name, which is
     * the only way a target reaches this class at all. A local or anonymous type answers
     * {@code false}.
     *
     * <p>Its one caller uses it to skip the class-file lookup entirely, so a target that is not
     * nameable produces no {@code AW1200} either.
     *
     * @param target the type to test; must not be {@code null}
     * @return {@code true} for a top-level or member type
     * @throws NullPointerException if {@code target} is {@code null}
     */
    static boolean isNameable(@NotNull final TypeElement target) {
        final NestingKind nesting = Objects.requireNonNull(target, "target").getNestingKind();
        return nesting == NestingKind.TOP_LEVEL || nesting == NestingKind.MEMBER;
    }
}
