package de.splatgames.aether.weaver.engine.verify;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import de.splatgames.aether.weaver.api.diagnostic.WeaveException;
import de.splatgames.aether.weaver.api.spi.DiagnosticListener;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.lang.classfile.ClassFile;
import java.util.List;
import java.util.Objects;

/**
 * Gate 5 of the weave path, and the last gate on the extensions-only path, through which woven
 * bytes pass before the engine trusts them.
 *
 * <p>On the weave path a stamp gate follows this one and rewrites the bytes it approved, so when
 * this gate approves a class the bytes it hands back there are not the bytes the driver finally
 * receives; when a non-fatal refusal returns the class unchanged instead, the stamp gate is never
 * reached and this gate's result is what the driver gets. On the extensions-only path there is no
 * stamp gate, and this one's result goes to the driver unchanged regardless.
 *
 * <p>Two checks stand behind it, and their order is what decides the diagnostic:
 * {@link StructuralCheck} runs first and reports {@code AW4004}, then
 * {@link ClassFile#verify(byte[])} reports {@code AW4001}. Neither remedy asks for a change to the
 * target: {@code AW4004} names the engine, {@code AW4001} names a weave or the engine, and both
 * ask for a class dump, since these are bytes no source file states.
 *
 * <p>Holds nothing but a {@link VerificationPolicy} and the listener a non-fatal refusal goes to,
 * so one instance serves a whole run and is as safe to share between threads as that listener is.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class Verifier {

    /** How many entries a diagnostic lists before the remainder becomes a count. */
    private static final int MAX_REPORTED_ERRORS = 10;

    /** What a refusal does, and whether anything is checked at all. */
    private final VerificationPolicy policy;

    /** Where a non-fatal refusal is reported. */
    private final DiagnosticListener listener;

    /**
     * Binds a policy to the listener that hears about a refusal.
     *
     * <p>Both are required even under {@link VerificationPolicy#OFF}, whose only effect on
     * {@link #check(String, byte[], byte[])} is to decide that nothing is checked, and under
     * {@link VerificationPolicy#STRICT}, which never calls the listener.
     *
     * @param policy   what a refusal does; must not be {@code null}
     * @param listener where a non-fatal refusal is reported; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public Verifier(@NotNull final VerificationPolicy policy,
                    @NotNull final DiagnosticListener listener) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Checks a woven class and decides which bytes the caller gets back.
     *
     * <p>A class that is both structurally malformed and unverifiable is reported as
     * {@code AW4004} alone, so {@code AW4001} covers only classes whose exception tables pass the
     * structural check and still do not verify.
     *
     * <p>The {@code byte[]} return carries no signal of its own for a non-fatal refusal; only the
     * listener given to the constructor sees the diagnostic. Under {@link VerificationPolicy#REPORT}
     * the array handed back is {@code original} itself, so where the caller's {@code original} and
     * {@code woven} arguments are distinct arrays, a caller reading only the return value tells
     * refusal from success by comparing the result against the {@code woven} array it passed in;
     * this method does not require or check that they differ. A caller that writes the result
     * without comparing anything still writes a class that loads.
     *
     * @param internalName the class's internal name, which the message names; must not be
     *                     {@code null}
     * @param original     the class as it arrived, handed back when a refusal is not fatal; must
     *                     not be {@code null}
     * @param woven        the class as weaving left it; must not be {@code null}
     * @return {@code woven} when the policy checks nothing or nothing refuses it, and
     *         {@code original} when a refusal is not fatal
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalArgumentException if {@link StructuralCheck#of(byte[])} propagates one, which
     *                                  is not caught here and so does not become a diagnostic
     * @throws WeaveException if the policy is fatal and either check refuses the class
     */
    @NotNull
    public byte[] check(@NotNull final String internalName,
                        final byte @NotNull [] original,
                        final byte @NotNull [] woven) {
        Objects.requireNonNull(internalName, "internalName");
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(woven, "woven");

        if (!this.policy.verifies()) {
            return woven;
        }
        // Before the bytecode verifier, not after. A class whose structure the JVM refuses never
        // reaches dataflow verification at all, so asking ClassFile.verify first would report a
        // clean result for bytes that cannot be loaded.
        final List<StructuralCheck.Problem> structural = StructuralCheck.of(woven);
        if (!structural.isEmpty()) {
            return refuse(structuralDiagnostic(internalName, structural), original);
        }
        final List<VerifyError> errors = ClassFile.of().verify(woven);
        if (errors.isEmpty()) {
            return woven;
        }

        return refuse(describe(internalName, errors), original);
    }

    /**
     * Turns a diagnostic into whatever the policy says a refusal is.
     *
     * <p>A fatal policy does not also report: the diagnostic travels inside the
     * {@link WeaveException}, so a driver that both listens and catches sees it once rather than
     * twice.
     *
     * @param diagnostic what was found; must not be {@code null}
     * @param original   the bytes to fall back to; must not be {@code null}
     * @return {@code original}
     * @throws WeaveException if the policy is fatal
     */
    private byte @NotNull [] refuse(@NotNull final Diagnostic diagnostic,
                                    final byte @NotNull [] original) {
        if (this.policy.isFatal()) {
            throw new WeaveException(diagnostic.message(), List.of(diagnostic));
        }
        this.listener.report(diagnostic);
        return original;
    }

    /**
     * Builds the {@code AW4004} diagnostic from what {@link StructuralCheck} found.
     *
     * <p>At most {@value #MAX_REPORTED_ERRORS} problems become detail lines and the rest are
     * summarised as a count, so a rewrite that went wrong in every method of a large class still
     * leaves the surrounding build log readable.
     *
     * @param internalName the class the problems were found in; must not be {@code null}
     * @param problems     what was found; must not be {@code null}
     * @return the diagnostic
     */
    @Contract(pure = true)
    @NotNull
    private static Diagnostic structuralDiagnostic(
            @NotNull final String internalName,
            @NotNull final List<StructuralCheck.Problem> problems) {
        final Diagnostic.Builder builder =
                Diagnostic.builder(DiagnosticCode.STRUCTURAL_SELF_CHECK_FAILED)
                        .message(internalName + " is structurally malformed after weaving");
        problems.stream().limit(MAX_REPORTED_ERRORS)
                .forEach(problem -> builder.detail(problem.method() + ": " + problem.describe()));
        if (problems.size() > MAX_REPORTED_ERRORS) {
            builder.detail("... and " + (problems.size() - MAX_REPORTED_ERRORS) + " more");
        }
        return builder
                .remedy("this is a defect in the engine rather than in the weave: these are "
                        + "shapes the JVM refuses to load at all, and ClassFile.verify does not "
                        + "report them. Re-run with class dumps enabled and report the dump")
                .build();
    }

    /**
     * Reports the policy this verifier was constructed with, unchanged since then.
     *
     * @return the policy
     */
    @Contract(pure = true)
    @NotNull
    public VerificationPolicy policy() {
        return this.policy;
    }

    /**
     * Returns the policy, which is all that distinguishes one verifier from another in a log.
     *
     * @return a description of this verifier
     */
    @Override
    @NotNull
    public String toString() {
        return "Verifier[" + this.policy + ']';
    }

    /**
     * Builds the {@code AW4001} diagnostic from what {@link ClassFile#verify(byte[])} said.
     *
     * <p>Each {@link VerifyError}'s message becomes a detail line, capped at
     * {@value #MAX_REPORTED_ERRORS} as in {@link #structuralDiagnostic(String, List)}. The count in
     * the message is the full one rather than the capped one, so a reader can tell a class with a
     * single fault from one whose every method is broken.
     *
     * @param internalName the class that did not verify; must not be {@code null}
     * @param errors       what the verifier reported, in the order it reported them; must not be
     *                     {@code null}
     * @return the diagnostic
     */
    @Contract(pure = true)
    @NotNull
    private static Diagnostic describe(@NotNull final String internalName,
                                       @NotNull final List<VerifyError> errors) {
        final Diagnostic.Builder builder = Diagnostic.builder(DiagnosticCode.VERIFICATION_FAILED)
                .message(internalName + " does not verify after weaving (" + errors.size()
                        + (errors.size() == 1 ? " error)" : " errors)"));
        errors.stream().limit(MAX_REPORTED_ERRORS)
                .forEach(error -> builder.detail(error.getMessage()));
        if (errors.size() > MAX_REPORTED_ERRORS) {
            builder.detail("... and " + (errors.size() - MAX_REPORTED_ERRORS) + " more");
        }
        return builder
                .remedy("this is a defect in a weave or in the engine, not in the target. Re-run "
                        + "with class dumps enabled and compare the javap output before and after; "
                        + "the first error's method is where to look")
                .build();
    }
}
