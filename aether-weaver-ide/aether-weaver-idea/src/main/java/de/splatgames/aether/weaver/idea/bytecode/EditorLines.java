package de.splatgames.aether.weaver.idea.bytecode;

import com.intellij.execution.filters.LineNumbersMapping;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiCompiledElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Translates a line of a class file into the line of the file the editor is showing.
 *
 * <p>The two coincide for a file the user can edit: its text is what the compiler read, so a line
 * from {@link CompiledLines} already names a line of the document. They do not coincide for a
 * compiled file opened from a library, whose text was produced by the decompiler and bears no
 * relation to the original numbering. The platform publishes what the decompiler recorded as a
 * {@link LineNumbersMapping} on the {@link VirtualFile}, and that mapping is the only thing here
 * that can bridge the two.
 *
 * <p>Nothing is placed on a guess. Where no mapping exists, or where the mapping declines a line,
 * the answer is {@code 0} and the caller shows nothing rather than a marker on an arbitrary line.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class EditorLines {

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private EditorLines() {
        throw new AssertionError("no instances");
    }

    /**
     * Translates a class file line into a line of the given file as it is shown.
     *
     * <p>A file that is not a {@link PsiCompiledElement} is its own source and the line is returned
     * unchanged. A compiled one is translated through the mapping the decompiler left on its
     * {@link VirtualFile}, and answers {@code 0} when there is no virtual file or no mapping.
     *
     * @param shown the file on screen; must not be {@code null}
     * @param line  the one-based line in the class file
     * @return the one-based line in {@code shown}, or {@code 0} when {@code line} is below
     *         {@code 1} or the file's text cannot be related to the class file
     */
    public static int of(@NotNull final PsiFile shown, final int line) {
        if (line < 1) {
            return 0;
        }
        if (!(shown instanceof PsiCompiledElement)) {
            return line;
        }

        final VirtualFile file = shown.getVirtualFile();
        return translate(file == null
                ? null
                : file.getUserData(LineNumbersMapping.LINE_NUMBERS_MAPPING_KEY), line);
    }

    /**
     * Applies a decompiler mapping to a class file line.
     *
     * <p>Anything {@link LineNumbersMapping#bytecodeToSource(int)} answers below the first line is
     * turned into {@code 0} here so that a single "no line" answer travels outwards; a caller that
     * passed a negative or zero result to a document would ask for a line before the first one.
     *
     * @param mapping the mapping the decompiler published, or {@code null} when there is none
     * @param line    the one-based line in the class file
     * @return the mapped one-based line, or {@code 0} when there is no mapping, the mapping refuses
     *         the line, or {@code line} is below {@code 1}
     */
    static int translate(@Nullable final LineNumbersMapping mapping, final int line) {
        if (mapping == null || line < 1) {
            // Nothing decompiled this file, or nothing published a mapping for it. Its text is then
            // not something whose lines can be reasoned about at all.
            return 0;
        }
        final int mapped = mapping.bytecodeToSource(line);
        return mapped < 1 ? 0 : mapped;
    }

    /**
     * Reports whether lines can be placed in the given file at all.
     *
     * <p>Answered before any class file is read, so that a caller can abandon the work rather than
     * compute a set of lines that {@link #of(PsiFile, int)} would then refuse one by one.
     *
     * @param shown the file on screen, or {@code null} when there is none
     * @return {@code true} for a source file, and for a compiled file whose decompiled text carries
     *         a {@link LineNumbersMapping}
     */
    public static boolean canPlace(@Nullable final PsiFile shown) {
        if (shown == null) {
            return false;
        }
        if (!(shown instanceof PsiCompiledElement)) {
            return true;
        }
        final VirtualFile file = shown.getVirtualFile();
        return file != null
                && file.getUserData(LineNumbersMapping.LINE_NUMBERS_MAPPING_KEY) != null;
    }
}
