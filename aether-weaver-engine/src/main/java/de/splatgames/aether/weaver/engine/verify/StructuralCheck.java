package de.splatgames.aether.weaver.engine.verify;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.instruction.ExceptionCatch;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Looks for malformations in a woven class's exception tables, at least one of which
 * {@link ClassFile#verify(byte[])} does not report.
 *
 * <p>Well-formedness and verification are separate stages, and {@link ClassFile#verify(byte[])}
 * models only the second. An exception range whose start equals its end draws no error from it,
 * while defining the same bytes fails with {@code ClassFormatError: Illegal exception table range}
 * — both measured on HotSpot 25. A class in that state passes verification and still fails at the
 * user's class load, which is what this catches and why {@link Verifier} asks it first.
 *
 * <p>What is examined is narrow: the exception table of every method that carries a code
 * attribute, and nothing else. Bytes that {@link ClassFile#parse(byte[])} refuses outright become
 * a single {@link Problem} rather than an exception.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class StructuralCheck {

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private StructuralCheck() {
        throw new AssertionError("no instances");
    }

    /**
     * One malformation, and the method it was found in.
     *
     * <p>{@link Verifier} joins the two as {@code method + ": " + describe} into one detail line of
     * a diagnostic, so {@link #describe()} is phrased as a clause and repeats no method name of its
     * own.
     *
     * @param method   the method as its name followed by a display descriptor, such as
     *                 {@code run()void}, or {@code <class>} for a failure that belongs to no method
     * @param describe what is wrong, phrased to follow the method name
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public record Problem(@NotNull String method, @NotNull String describe) {

        /**
         * Checks that both components are present.
         *
         * @throws NullPointerException if either component is {@code null}
         */
        public Problem {
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(describe, "describe");
        }
    }

    /**
     * Returns everything structurally wrong with a woven class.
     *
     * <p>A method with no code is skipped, and so is one whose code is a {@code CodeModel} that is
     * not a {@link CodeAttribute}: the range check needs the label-to-offset mapping that only
     * {@link CodeAttribute} provides.
     *
     * @param woven the class as it would be handed back; must not be {@code null}
     * @return the problems found, in method order and empty when there are none
     * @throws NullPointerException if {@code woven} is {@code null}
     * @throws IllegalArgumentException if a method's exception table names a bytecode offset
     *                                  greater than its code's length — an offset equal to the
     *                                  length does not throw; parsing such a class succeeds and
     *                                  the failure surfaces later, when the table is read, which
     *                                  is past the {@code catch} here
     */
    @Contract(pure = true)
    @NotNull
    @Unmodifiable
    public static List<Problem> of(final byte @NotNull [] woven) {
        Objects.requireNonNull(woven, "woven");
        final List<Problem> problems = new ArrayList<>();
        final ClassModel model;
        try {
            model = ClassFile.of().parse(woven);
        } catch (final IllegalArgumentException malformed) {
            // Unparseable output is the loudest structural failure there is, and it has no method
            // to name.
            return List.of(new Problem("<class>",
                    "the woven class cannot be parsed back: " + malformed.getMessage()));
        }

        for (final MethodModel method : model.methods()) {
            final CodeAttribute code = method.code()
                    .filter(CodeAttribute.class::isInstance)
                    .map(CodeAttribute.class::cast)
                    .orElse(null);
            if (code == null) {
                continue;
            }
            final String named = method.methodName().stringValue()
                    + method.methodTypeSymbol().displayDescriptor();
            checkExceptionRanges(code, named, problems);
        }
        return List.copyOf(problems);
    }

    /**
     * Adds a problem for every way one method's exception table is malformed.
     *
     * <p>An entry naming an offset that does not resolve is reported and then skipped, because the
     * remaining two comparisons would be reading positions that mean nothing. Those two are not
     * exclusive of each other: a range that protects nothing and also reaches past the end of the
     * method yields two problems for the one handler.
     *
     * @param code     the method's code; must not be {@code null}
     * @param named    the method as a problem should name it; must not be {@code null}
     * @param problems collects what is found; must not be {@code null}
     */
    private static void checkExceptionRanges(@NotNull final CodeAttribute code,
                                             @NotNull final String named,
                                             @NotNull final List<Problem> problems) {
        final int length = code.codeLength();
        for (final ExceptionCatch handler : code.exceptionHandlers()) {
            final int start = code.labelToBci(handler.tryStart());
            final int end = code.labelToBci(handler.tryEnd());
            final int target = code.labelToBci(handler.handler());
            if (start < 0 || end < 0 || target < 0) {
                // A label the code never bound. The builder resolves these, so reaching here means
                // something emitted a jump to a position that does not exist.
                problems.add(new Problem(named,
                        "an exception handler names a position the method does not have "
                                + "(start=" + start + ", end=" + end + ", handler=" + target + ')'));
                continue;
            }
            if (start >= end) {
                problems.add(new Problem(named,
                        "an exception range starts at " + start + " and ends at " + end
                                + ", which protects nothing"));
            }
            if (end > length || target >= length) {
                problems.add(new Problem(named,
                        "an exception handler reaches past the end of the method (end=" + end
                                + ", handler=" + target + ", length=" + length + ')'));
            }
        }
    }
}
