package de.splatgames.aether.weaver.testkit;

import de.splatgames.aether.weaver.engine.dump.Disassembly;
import de.splatgames.aether.weaver.engine.dump.TextDiff;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Compares woven bytes against a committed class file and explains a difference as a
 * {@code javap} diff.
 *
 * <p>A fixture called {@code name} occupies up to four files in one directory:
 *
 * <ul>
 *   <li>{@code name.class} — the accepted bytes. This is what the comparison is against.
 *   <li>{@code name.txt} — the {@link Disassembly} of those bytes. Nothing reads it; it exists so
 *       that a change to the fixture arrives in a review as readable bytecode rather than as a
 *       binary blob.
 *   <li>{@code name.actual.class} and {@code name.actual.txt} — the bytes that did not match an
 *       existing fixture, written only for that mismatch so both sides can be read side by side.
 *       A comparison that fails because the fixture was missing writes {@code name.class} and
 *       {@code name.txt} instead, and no {@code .actual} pair.
 * </ul>
 *
 * <h2>The first run always fails</h2>
 *
 * <p>Comparing against a fixture that does not exist writes it and then throws anyway. A fixture
 * that passed on the run that created it would have recorded whatever the code did that day as
 * correct, and nobody would have looked at it.
 *
 * <h2>Accepting a change</h2>
 *
 * <p>Setting the system property named by {@link #UPDATE_PROPERTY} rewrites every fixture a run
 * touches and asserts nothing at all. A build with it set proves nothing, so it belongs on a
 * deliberate local run and not in CI; {@link #updating()} is the question to ask if a test wants
 * to refuse to run under it.
 *
 * <h2>What moves a fixture without anything being wrong</h2>
 *
 * <p>A fixture records the output of weaving a class file that {@code javac} produced, so it
 * depends on the compiler as well as on the weaver, and a JDK upgrade can move it. The failure
 * message says so.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * GoldenFiles golden = GoldenFiles.in(Path.of("src/test/resources/golden"));
 *
 * golden.verify("ledger-audited", weaving.weave(Ledger.class));
 * }</pre>
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see WovenAssert
 */
public final class GoldenFiles {

    /**
     * The system property that turns every comparison into a rewrite.
     *
     * <p>Read through {@link Boolean#getBoolean(String)}, so only the value {@code true},
     * ignoring case, counts; anything else, including the property being absent, leaves the
     * comparison in force.
     */
    public static final String UPDATE_PROPERTY = "golden.update";

    /** Where the fixtures live. Every name is resolved against this and may not escape it. */
    private final Path directory;

    /**
     * Binds this instance to a directory.
     *
     * <p>The directory is not required to exist; {@link #verify(String, byte[])} creates it when
     * it writes.
     *
     * @param directory the directory fixtures live in; must not be {@code null}
     */
    private GoldenFiles(@NotNull final Path directory) {
        this.directory = directory;
    }

    /**
     * Returns a golden-file comparison rooted at a directory.
     *
     * <p>The path is kept as given and only made absolute and normalised at the moment a name is
     * resolved against it, rather than at the moment this method is called. What that buys is not
     * a choice of working directory — the JVM fixes that at startup, before this method can ever
     * run — but that a relative directory is re-absolutised and re-normalised on every
     * {@link #verify(String, byte[])} call rather than once here, and that {@link #toString()}
     * still prints the directory exactly as given rather than resolved.
     *
     * @param directory the directory the fixtures live in; must not be {@code null}
     * @return a comparison rooted there
     * @throws NullPointerException if {@code directory} is {@code null}
     */
    @Contract(value = "_ -> new", pure = true)
    @NotNull
    public static GoldenFiles in(@NotNull final Path directory) {
        return new GoldenFiles(Objects.requireNonNull(directory, "directory"));
    }

    /**
     * Reports whether this run rewrites fixtures instead of comparing against them.
     *
     * <p>Read from the system property each time, so a test that sets or clears the property sees
     * the change immediately.
     *
     * @return {@code true} when {@link #UPDATE_PROPERTY} is set to {@code true}
     */
    @Contract(pure = true)
    public static boolean updating() {
        return Boolean.getBoolean(UPDATE_PROPERTY);
    }

    /**
     * Compares a weave result against the fixture of the given name.
     *
     * <p>Compares {@link WeaveResult#effective()}, so a class no weave named is recorded as its
     * own original bytes rather than skipped. A fixture that captured an untouched class stays
     * green until something starts weaving that class, at which point it fails.
     *
     * @param name   the fixture name, without extension; must not be {@code null} or blank
     * @param result the result to compare; must not be {@code null}
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalArgumentException if {@code name} is blank or would resolve outside the
     *                                  directory
     * @throws AssertionError           if the fixture did not exist, or if the bytes differ from
     *                                  it
     */
    public void verify(@NotNull final String name, @NotNull final WeaveResult result) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(result, "result");
        verify(name, result.effective());
    }

    /**
     * Compares bytes against the fixture of the given name.
     *
     * <p>Four outcomes, in the order they are decided:
     *
     * <ol>
     *   <li>{@link #updating()} is true. Both {@code name.class} and {@code name.txt} are
     *       rewritten and nothing is asserted, whatever the fixture held before.
     *   <li>{@code name.class} does not exist. It and its rendering are written, and an
     *       {@link AssertionError} naming the path it was written to is thrown anyway, so that the
     *       new fixture is read before it is committed.
     *   <li>The bytes match. Nothing is written and the method returns.
     *   <li>The bytes differ. {@code name.actual.class} and {@code name.actual.txt} are written,
     *       and an {@link AssertionError} is thrown carrying a {@code javap} diff of the two, the
     *       property to re-run with, and the note that the compiler that built the target
     *       influences the fixture too.
     * </ol>
     *
     * <p>{@code name} is a file name without an extension. It may contain a path separator and
     * name a subdirectory, which will be created; it may not resolve outside the directory this
     * instance was bound to, which is what rejects {@code ../escaped}.
     *
     * @param name  the fixture name, without extension; must not be {@code null} or blank
     * @param bytes the bytes to compare; must not be {@code null}
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalArgumentException if {@code name} is blank or would resolve outside the
     *                                  directory
     * @throws UncheckedIOException     if a file could not be read or written
     * @throws AssertionError           if the fixture did not exist, or if the bytes differ from
     *                                  it
     */
    public void verify(@NotNull final String name, final byte @NotNull [] bytes) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(bytes, "bytes");
        if (name.isBlank()) {
            throw new IllegalArgumentException("a golden fixture needs a name");
        }

        final Path classFile = resolve(name + ".class");
        final Path rendering = resolve(name + ".txt");

        if (updating()) {
            write(classFile, rendering, bytes);
            return;
        }
        if (!Files.exists(classFile)) {
            write(classFile, rendering, bytes);
            throw new AssertionError("the golden file for '" + name + "' did not exist and has "
                    + "been written to " + classFile + ". Read the .txt rendering next to it before "
                    + "committing: a new fixture that passed on its first run would record whatever "
                    + "the code did that day as correct.");
        }

        final byte[] golden = read(classFile);
        if (Arrays.equals(golden, bytes)) {
            return;
        }
        write(resolve(name + ".actual.class"), resolve(name + ".actual.txt"), bytes);
        throw new AssertionError("the woven bytes for '" + name + "' differ from the golden file."
                + System.lineSeparator() + describe(classFile, resolve(name + ".actual.class"))
                + System.lineSeparator()
                + "If the change is intended, re-run with -D" + UPDATE_PROPERTY + "=true and read "
                + "the .txt diff before committing." + System.lineSeparator()
                + "Golden files also depend on the javac that compiled the target, so a JDK upgrade "
                + "can move them without anything being wrong.");
    }

    /**
     * Renders the difference between two class files as indented diff lines.
     *
     * <p>Three answers, and each is a sentence the failure message can carry on its own:
     *
     * <ul>
     *   <li>{@code javap} is not in this runtime — it lives in the {@code jdk.javap} module, which
     *       a trimmed image may omit. Names both paths so the reader can compare them from a full
     *       JDK.
     *   <li>The disassemblies agree under {@link Disassembly#key(String)}, or {@code javap} exited
     *       non-zero on both files, so both come back empty and no comparison happens at all. In
     *       the first case the bytes still differ because the difference is in the constant pool
     *       or in an attribute {@code javap} does not print; in the second, nothing about the
     *       class files can be read from this diff. Either way, a failing comparison with no
     *       visible diff is this case.
     *   <li>Otherwise, what {@link TextDiff#unified(java.util.List, java.util.List,
     *       java.util.function.Function)} returns, each line indented by four spaces: ordinarily
     *       the hunks, but when the two disassemblies differ too widely to diff line by line, a
     *       single explanatory line in their place. Comparing by {@link Disassembly#key(String)} is
     *       what keeps the diff short in the ordinary case: weaving shifts every offset and
     *       renumbers the pool, and comparing the raw output would report the whole method as
     *       changed.
     * </ul>
     *
     * <p>A file that {@code javap} cannot read at all disassembles to an empty list, which makes
     * the other side appear wholly added or wholly removed rather than raising anything.
     *
     * @param golden the accepted class file; must not be {@code null}
     * @param actual the class file that did not match; must not be {@code null}
     * @return the diff, or the sentence that stands in for it
     */
    @NotNull
    private static String describe(@NotNull final Path golden, @NotNull final Path actual) {
        if (!Disassembly.available()) {
            return "    javap is not in this runtime, so there is no readable diff. Compare "
                    + golden + " with " + actual + " from a full JDK.";
        }
        final List<String> before = Disassembly.of(golden).orElse(List.of());
        final List<String> after = Disassembly.of(actual).orElse(List.of());
        final List<String> diff = TextDiff.unified(before, after, Disassembly::key);
        if (diff.isEmpty()) {
            return "    The bytes differ but the disassembly does not: the difference is in the "
                    + "constant pool or in an attribute javap does not print.";
        }
        return diff.stream()
                .map(line -> "    " + line)
                .reduce("", (a, b) -> a.isEmpty() ? b : a + System.lineSeparator() + b);
    }

    /**
     * Writes a fixture and its rendering, creating the directory if it is missing.
     *
     * <p>The rendering is disassembled from the file just written rather than from the bytes in
     * hand, because {@link Disassembly} takes a path. {@link Disassembly#of(Path)} comes back
     * empty both when {@code javap} is not in this runtime and when it exits non-zero on the file
     * just written, and either way the rendering is a two-line note saying there is none and
     * telling the reader to delete and regenerate it, so that an unreadable placeholder is never
     * mistaken for a disassembly of nothing. The rendering is written as UTF-8 with
     * {@link System#lineSeparator()} between lines.
     *
     * @param classFile where the bytes go; must not be {@code null}
     * @param rendering where the disassembly goes; must not be {@code null}
     * @param bytes     the bytes to write; must not be {@code null}
     * @throws UncheckedIOException if the directory or either file could not be written
     */
    private static void write(@NotNull final Path classFile,
                              @NotNull final Path rendering,
                              final byte @NotNull [] bytes) {
        try {
            Files.createDirectories(classFile.getParent());
            Files.write(classFile, bytes);
            final List<String> lines = Disassembly.of(classFile).orElse(List.of(
                    "javap was not available when this file was written, so there is no rendering.",
                    "Delete this file and regenerate from a full JDK."));
            Files.writeString(rendering, String.join(System.lineSeparator(), lines),
                    StandardCharsets.UTF_8);
        } catch (final IOException unwritable) {
            throw new UncheckedIOException("could not write " + classFile, unwritable);
        }
    }

    /**
     * Reads a fixture back.
     *
     * <p>Called only after the file has been found to exist, so a failure here is a permission or
     * a read problem rather than a missing fixture, and is raised rather than turned into a
     * mismatch.
     *
     * @param classFile the fixture to read; must not be {@code null}
     * @return the fixture's bytes
     * @throws UncheckedIOException if the file could not be read
     */
    private static byte @NotNull [] read(@NotNull final Path classFile) {
        try {
            return Files.readAllBytes(classFile);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException("could not read " + classFile, unreadable);
        }
    }

    /**
     * Resolves a file name against the directory and refuses one that would leave it.
     *
     * <p>The directory is made absolute and normalised first, then the name is resolved against it
     * and normalised in turn, and the result must still start with the root. Normalising before
     * the check is what makes it hold: {@code ../escaped} only stops starting with the root once
     * the {@code ..} has been collapsed. A fixture name is chosen in test source and not by a
     * user, so this guards against a mistake rather than an attack, and the name is quoted back in
     * the refusal.
     *
     * @param fileName the name to resolve, extension included; must not be {@code null}
     * @return the absolute, normalised path
     * @throws IllegalArgumentException if the name resolves outside the directory
     */
    @Contract(pure = true)
    @NotNull
    private Path resolve(@NotNull final String fileName) {
        final Path root = this.directory.toAbsolutePath().normalize();
        final Path resolved = root.resolve(fileName).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException(
                    "a golden fixture name must stay inside the directory: " + fileName);
        }
        return resolved;
    }

    /**
     * Returns the directory and whether this run rewrites rather than compares.
     *
     * <p>The directory is printed as it was given, not as it is resolved. {@code , updating} is
     * appended only while {@link #UPDATE_PROPERTY} is set, which is the fact most worth seeing in
     * a message from a run where every comparison passed.
     *
     * @return {@code GoldenFiles[} the directory, optionally {@code , updating}, and {@code ]}
     */
    @Override
    @NotNull
    public String toString() {
        return "GoldenFiles[" + this.directory + (updating() ? ", updating" : "") + ']';
    }
}
