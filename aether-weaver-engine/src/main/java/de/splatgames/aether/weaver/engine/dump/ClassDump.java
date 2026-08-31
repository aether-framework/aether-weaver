package de.splatgames.aether.weaver.engine.dump;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import de.splatgames.aether.weaver.api.spi.DiagnosticListener;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Writes the before and after of one woven class, plus a readable diff of the two.
 *
 * <p>A dump is a debugging aid, and it runs inside class loading. Nothing here may cost the
 * application a class: an unwritable directory or a class name that would escape it is reported as
 * a diagnostic rather than thrown, and the caller still receives the bytes it was going to define.
 * A class file {@code javap} refuses is not a diagnostic; it is written into the diff file itself as
 * the finding.
 *
 * <p>Each of the three files is written under the class's package, so a dump directory has the
 * shape of a class hierarchy and two classes of the same simple name do not collide.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class ClassDump {

    /** The dump root, absolute and normalised so that {@code resolve} can test for escape. */
    private final Path directory;

    /**
     * Creates a dump rooted at a directory.
     *
     * <p>The directory is not created or examined here. A run that never weaves a class therefore
     * leaves nothing behind, and a directory that cannot be created is reported at the first dump
     * rather than at construction.
     *
     * @param directory where to write; must not be {@code null}
     * @throws NullPointerException if {@code directory} is {@code null}
     */
    public ClassDump(@NotNull final Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath()
                .normalize();
    }

    /**
     * Writes {@code .original.class}, {@code .woven.class} and {@code .diff.txt} for one class.
     *
     * <p>Every failure is reported as {@code AW4090} and none is thrown, including the refusal of a
     * class name that would resolve outside the dump directory: the {@link IllegalArgumentException}
     * that refusal raises is thrown inside the same {@code try} that catches it. Partial output is
     * possible, since the three files are written one after another and the diff is written last.
     *
     * @param internalName the class's internal name, which also decides the path
     * @param original     the bytes as they arrived
     * @param woven        the bytes as they are being handed back
     * @param listener     where a failure to dump is reported
     * @throws NullPointerException if any argument is {@code null}
     */
    public void write(@NotNull final String internalName,
                      final byte @NotNull [] original,
                      final byte @NotNull [] woven,
                      @NotNull final DiagnosticListener listener) {
        Objects.requireNonNull(internalName, "internalName");
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(woven, "woven");
        Objects.requireNonNull(listener, "listener");

        try {
            final Path base = resolve(internalName);
            Files.createDirectories(base.getParent());

            final Path before = sibling(base, ".original.class");
            final Path after = sibling(base, ".woven.class");
            Files.write(before, original);
            Files.write(after, woven);
            Files.writeString(sibling(base, ".diff.txt"),
                    String.join(System.lineSeparator(), diff(internalName, before, after)),
                    StandardCharsets.UTF_8);
        } catch (final IOException | RuntimeException unwritable) {
            // Reported, never thrown. This runs inside class loading; an unwritable directory
            // must cost a debugging aid, not a class.
            listener.report(Diagnostic.builder(DiagnosticCode.INTERNAL_ERROR)
                    .message("could not dump " + internalName.replace('/', '.') + ": "
                            + unwritable.getMessage())
                    .detail(unwritable.getClass().getName())
                    .remedy("check that " + this.directory + " exists and is writable, or unset "
                            + "aether.weaver.dump")
                    .build());
        }
    }

    /**
     * Builds the contents of the diff file, headers and all.
     *
     * <p>Each of the three ways this can produce no hunks says which one it was, because they call
     * for different things from a reader: a runtime without {@code javap} needs the command to run
     * elsewhere, a class file {@code javap} refuses is itself the finding, and a class that really
     * did not change instruction for instruction still changed in ways the comparison ignores.
     *
     * @param internalName the class's internal name, used in the header lines
     * @param before       the file the original bytes were written to
     * @param after        the file the woven bytes were written to
     * @return the lines of the diff file
     */
    @NotNull
    private static List<String> diff(@NotNull final String internalName,
                                     @NotNull final Path before,
                                     @NotNull final Path after) {
        final List<String> lines = new ArrayList<>();
        lines.add("--- " + internalName + "  (original)");
        lines.add("+++ " + internalName + "  (woven)");
        lines.add("");

        if (!Disassembly.available()) {
            lines.add("javap is not available in this runtime, so there is no disassembly to");
            lines.add("compare. It lives in the jdk.javap module, which a trimmed runtime image");
            lines.add("may leave out. Both class files were written next to this one; run");
            lines.add("  javap -c -p <file>");
            lines.add("on each from a full JDK to compare them.");
            return lines;
        }

        final Optional<List<String>> original = Disassembly.of(before);
        final Optional<List<String>> woven = Disassembly.of(after);
        if (original.isEmpty() || woven.isEmpty()) {
            lines.add("javap refused one of the two class files, so there is no diff. Both were");
            lines.add("written next to this one. A woven class that javap will not read is worth");
            lines.add("reporting: the weaver verifies every class before returning it.");
            return lines;
        }

        final List<String> hunks = TextDiff.unified(original.get(), woven.get(), Disassembly::key);
        if (hunks.isEmpty()) {
            lines.add("no difference, once bytecode offsets and constant-pool indices are set");
            lines.add("aside. The class was rewritten — the AetherWeave attribute and the pool");
            lines.add("entries the weaver added are both real — but no instruction changed.");
            return lines;
        }
        lines.add("offsets and constant-pool indices are ignored when comparing, because weaving");
        lines.add("shifts all of them; the lines below are the real javap output.");
        lines.add("");
        lines.addAll(hunks);
        return lines;
    }

    /**
     * Resolves the base path for a class, refusing one that would leave the dump directory.
     *
     * <p>The name is read out of a class file, and a class file can hold any string at all,
     * including one with {@code ..} in it. The check is on the normalised result rather than on the
     * name, so an escape assembled out of several segments is caught too.
     *
     * @param internalName the class's internal name
     * @return the base path, without a suffix
     * @throws IllegalArgumentException if the name resolves outside the dump directory
     */
    @Contract(pure = true)
    @NotNull
    private Path resolve(@NotNull final String internalName) {
        final Path resolved = this.directory.resolve(internalName).normalize();
        if (!resolved.startsWith(this.directory)) {
            // A class name comes from a class file, and a class file can say anything.
            throw new IllegalArgumentException(
                    "the class name would write outside the dump directory: " + internalName);
        }
        return resolved;
    }

    /**
     * Appends a suffix to a path's file name.
     *
     * <p>Not {@code resolve}, which would treat the suffix as a child name.
     *
     * @param base   the base path
     * @param suffix the suffix, including its leading dot
     * @return the sibling path
     */
    @Contract(pure = true)
    @NotNull
    private static Path sibling(@NotNull final Path base, @NotNull final String suffix) {
        return base.resolveSibling(base.getFileName() + suffix);
    }

    /**
     * Returns the dump directory and whether a disassembler was found.
     *
     * @return a description of this dump
     */
    @Override
    @NotNull
    public String toString() {
        return "ClassDump[" + this.directory + ", javap="
                + (Disassembly.available() ? "available" : "absent") + ']';
    }
}
