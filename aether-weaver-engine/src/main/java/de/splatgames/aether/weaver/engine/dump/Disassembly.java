package de.splatgames.aether.weaver.engine.dump;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.spi.ToolProvider;

/**
 * Runs {@code javap} in-process and reduces its output to something two versions of a class can be
 * compared by.
 *
 * <p>Weaving shifts every bytecode offset and renumbers the constant pool, so a line-by-line
 * comparison of raw {@code javap} output reports the whole method as changed for a single inserted
 * instruction. {@link #key(String)} removes exactly the two things weaving always disturbs and
 * nothing else, which is what leaves a diff short enough to read.
 *
 * <p>The tool is reached through {@link ToolProvider}, not by starting a process, so there is no
 * JDK to locate and no temporary file. It lives in the {@code jdk.javap} module, which a trimmed
 * runtime image may leave out; {@link #available()} is the question to ask before relying on it.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class Disassembly {

    /** The tool, looked up once: the answer cannot change while the JVM runs. */
    private static final Optional<ToolProvider> JAVAP = ToolProvider.findFirst("javap");

    /**
     * The instruction's own offset, at the start of a line.
     *
     * <p>Anchored, so a number appearing anywhere else survives. That is deliberate: a branch
     * target is written as a bare number in the operand, and masking it as well would hide a jump
     * that really was retargeted.
     */
    private static final Pattern OFFSET = Pattern.compile("^\\s*\\d+:");

    /**
     * A constant-pool index, which weaving renumbers wholesale.
     *
     * <p>Replaced by a bare {@code #} rather than removed, so the comment {@code javap} prints
     * after the index still carries what the entry actually referred to.
     */
    private static final Pattern POOL = Pattern.compile("#\\d+");

    /** Runs of whitespace, collapsed because the offset column {@code javap} aligns to is gone. */
    private static final Pattern RUNS = Pattern.compile("\\s+");

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private Disassembly() {
        throw new AssertionError("no instances");
    }

    /**
     * Reports whether this runtime carries {@code javap}.
     *
     * @return {@code true} when the tool was found
     */
    @Contract(pure = true)
    public static boolean available() {
        return JAVAP.isPresent();
    }

    /**
     * Disassembles a class file with {@code -c -p}, so that private members appear too.
     *
     * <p>Anything the tool writes to its error stream is discarded: the exit status is the only
     * thing consulted, and the caller has no use for a message about a file it is about to describe
     * as unreadable anyway.
     *
     * @param classFile the file to disassemble; must not be {@code null}
     * @return the output as lines, or empty when the tool is absent or refused the file
     * @throws NullPointerException if {@code classFile} is {@code null}
     */
    @Unmodifiable
    @NotNull
    public static Optional<List<String>> of(@NotNull final Path classFile) {
        Objects.requireNonNull(classFile, "classFile");
        if (JAVAP.isEmpty()) {
            return Optional.empty();
        }

        final StringWriter out = new StringWriter();
        final StringWriter errors = new StringWriter();
        final int status;
        try (PrintWriter output = new PrintWriter(out); PrintWriter problems =
                new PrintWriter(errors)) {
            status = JAVAP.get().run(output, problems, "-c", "-p", classFile.toString());
        }
        if (status != 0) {
            return Optional.empty();
        }
        return Optional.of(out.toString().lines().toList());
    }

    /**
     * Reduces one line of {@code javap} output to what a comparison should be sensitive to.
     *
     * <p>The leading offset goes, every constant-pool index becomes {@code #}, and the remaining
     * whitespace is collapsed and trimmed. Two lines that differ only in those respects produce the
     * same key and compare equal; everything else, including a branch target, still separates them.
     *
     * <p>Keys are for comparing only. A diff built on them still prints the original lines, so a
     * reader sees the real offsets and indices.
     *
     * @param line the line to reduce; must not be {@code null}
     * @return the key
     * @throws NullPointerException if {@code line} is {@code null}
     */
    @Contract(pure = true)
    @NotNull
    public static String key(@NotNull final String line) {
        Objects.requireNonNull(line, "line");
        final String withoutOffset = OFFSET.matcher(line).replaceFirst("");
        final String withoutPool = POOL.matcher(withoutOffset).replaceAll("#");
        return RUNS.matcher(withoutPool.strip()).replaceAll(" ");
    }
}
