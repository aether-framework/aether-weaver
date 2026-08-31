package de.splatgames.aether.weaver.idea.bytecode;

import de.splatgames.aether.weaver.api.spi.CodeView;
import de.splatgames.aether.weaver.api.spi.MethodView;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.lang.classfile.CodeElement;
import java.lang.classfile.instruction.LineNumber;
import java.util.ArrayList;
import java.util.List;

/**
 * Reports the source lines the {@code LineNumber} entries of a compiled method give its
 * instructions.
 *
 * <p>The answers are in the class file's coordinate system, which is the editor's only for a file
 * the editor is showing in the form it was compiled from. A caller placing something in a document
 * passes the result through {@link EditorLines} first.
 *
 * <p>A method compiled without {@code -g} carries no line entries, and everything here then answers
 * "no line" rather than guessing one.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class CompiledLines {

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private CompiledLines() {
        throw new AssertionError("no instances");
    }

    /**
     * Reports the lines the given instruction positions were compiled from.
     *
     * <p>Order is preserved and duplicates are kept, so a caller may pass one position twice and
     * receive the same line twice. The result is not the same length as {@code sites} when a
     * position has no line entry before it: such a position is dropped rather than reported as
     * zero, which makes the result usable as a list of lines but stops it from being indexed
     * alongside {@code sites}.
     *
     * @param method the compiled method the positions index into; must not be {@code null}
     * @param sites  the element indices to map, in the order the answers are wanted; must not be
     *               {@code null}
     * @return the lines found, in the order of {@code sites} and possibly shorter than it; empty
     *         when the method has no code or {@code sites} is empty
     * @throws NullPointerException if {@code method} is {@code null}
     */
    @Unmodifiable
    @NotNull
    public static List<Integer> of(@NotNull final MethodView method,
                                   @NotNull final List<Integer> sites) {
        final CodeView code = method.code().orElse(null);
        if (code == null || sites.isEmpty()) {
            return List.of();
        }

        final List<CodeElement> elements = code.elements();
        final List<Integer> lines = new ArrayList<>(sites.size());
        for (final int site : sites) {
            final int line = lineAt(elements, site);
            if (line > 0) {
                lines.add(line);
            }
        }
        return List.copyOf(lines);
    }

    /**
     * Reports the line the nearest preceding {@link LineNumber} gives a position.
     *
     * <p>The search starts at the position itself and walks backwards, because a
     * {@link LineNumber} marks the start of a run of instructions rather than each one of them. A
     * position past the end of the body is clamped to the last element, so it takes the method's
     * final line rather than none.
     *
     * @param elements the method's code elements; must not be {@code null}
     * @param site     the element index to explain
     * @return the line, or {@code 0} when no {@link LineNumber} precedes the position, which is the
     *         answer for a negative index and for a body with no line entries at all
     * @throws NullPointerException if {@code elements} is {@code null}
     */
    @Contract(pure = true)
    private static int lineAt(@NotNull final List<CodeElement> elements, final int site) {
        for (int index = Math.min(site, elements.size() - 1); index >= 0; index--) {
            if (elements.get(index) instanceof final LineNumber line) {
                return line.line();
            }
        }
        return 0;
    }
}
