package de.splatgames.aether.weaver.testkit;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.Severity;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Objects;

/**
 * What one call to {@link Weaving#weave(Class)} produced: the class before, the class after, the
 * repeat pass, and what was reported along the way.
 *
 * <p>Both {@link #woven()} and {@link #secondPass()} are {@code null} when no weave named the
 * class. That is the ordinary outcome for an untargeted class rather than a failure, and
 * {@link #effective()} exists so that an assertion about the class's shape has bytes to read
 * either way.
 *
 * <h2>Every array is copied</h2>
 *
 * <p>The constructor copies each array it is given and every accessor copies again, so nothing a
 * caller holds can change what the result reports and no two calls return the same array.
 *
 * <p>That also makes the record's generated {@link #equals(Object)} of no use: the components are
 * arrays, which compare by reference, and each instance holds copies of its own, so no two
 * distinct instances are ever equal — not even two built from the same arrays. Compare
 * {@link java.util.Arrays#equals(byte[], byte[])} on the accessors instead.
 *
 * @param internalName the woven class's internal name, such as {@code com/acme/Ledger}
 * @param original     the class file as it stood before weaving
 * @param woven        the class file after weaving, or {@code null} when no weave applied
 * @param secondPass   the class file a second weave of {@code original} produced, or {@code null}
 *                     when that pass applied nothing
 * @param diagnostics  what was reported during the call that produced this result, in the order
 *                     reported
 * @author Erik Pförtner
 * @since 0.1.0
 * @see WovenAssert
 * @see Weaving
 */
public record WeaveResult(@NotNull String internalName,
                          byte @NotNull [] original,
                          byte @Nullable [] woven,
                          byte @Nullable [] secondPass,
                          @NotNull @Unmodifiable List<Diagnostic> diagnostics) {

    /**
     * Checks the required components and copies everything that could still be mutated.
     *
     * <p>{@code original}, {@code woven} and {@code secondPass} are cloned; {@code diagnostics}
     * becomes an unmodifiable copy, which is also what rejects a {@code null} element in it.
     *
     * @throws NullPointerException if {@code internalName}, {@code original} or
     *                              {@code diagnostics} is {@code null}, or if {@code diagnostics}
     *                              contains {@code null}
     */
    public WeaveResult {
        Objects.requireNonNull(internalName, "internalName");
        original = Objects.requireNonNull(original, "original").clone();
        woven = woven == null ? null : woven.clone();
        secondPass = secondPass == null ? null : secondPass.clone();
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }

    /**
     * Returns the class's binary name.
     *
     * <p>{@link #internalName()} with every {@code /} replaced by {@code .}. This is the form
     * every assertion failure names the class by, and the form
     * {@link ClassLoader#defineClass(String, byte[], int, int)} expects.
     *
     * @return the binary name, such as {@code com.acme.Ledger}
     */
    @Contract(pure = true)
    @NotNull
    public String binaryName() {
        return this.internalName.replace('/', '.');
    }

    /**
     * Reports whether anything applied to the class.
     *
     * <p>Reads only whether {@link #woven()} is present. Bytes identical to the original still
     * count as woven, because the weaver produced them.
     *
     * @return {@code true} when the weaver produced bytes for this class
     */
    @Contract(pure = true)
    public boolean wasWoven() {
        return this.woven != null;
    }

    /**
     * Returns the class as it stands after weaving.
     *
     * <p>The woven bytes when there are any, the original bytes otherwise. It is what
     * {@link WovenAssert} parses, verifies and defines, so an assertion applied to a class no
     * weave named describes the original rather than failing on a {@code null}.
     * {@link WovenAssert#isDeterministic()} is the exception: comparing the two passes needs the
     * raw {@link #woven()} and {@link #secondPass()}, since their absence is itself part of what
     * it checks.
     *
     * @return a fresh copy of the woven bytes, or of the original when nothing applied
     */
    @Contract(pure = true)
    public byte @NotNull [] effective() {
        return this.woven == null ? this.original.clone() : this.woven.clone();
    }

    /**
     * Returns the class file as it stood before weaving.
     *
     * @return a fresh copy of the original bytes
     */
    @Override
    public byte @NotNull [] original() {
        return this.original.clone();
    }

    /**
     * Returns the class file the weaver produced.
     *
     * @return a fresh copy of the woven bytes, or {@code null} when no weave named the class
     */
    @Override
    public byte @Nullable [] woven() {
        return this.woven == null ? null : this.woven.clone();
    }

    /**
     * Returns the class file a second weave of the same original produced.
     *
     * <p>Compared against {@link #woven()} by {@link WovenAssert#isDeterministic()}. A weaver that
     * depends on nothing outside its inputs produces the same bytes both times.
     *
     * @return a fresh copy of the second pass's bytes, or {@code null} when that pass applied
     *         nothing
     */
    @Override
    public byte @Nullable [] secondPass() {
        return this.secondPass == null ? null : this.secondPass.clone();
    }

    /**
     * Returns the codes of the diagnostics at or above a severity.
     *
     * <p>Filtered by {@link Severity#isAtLeast(Severity)} and kept in the order reported.
     * Duplicates are kept: one code reported three times appears three times, which is how a test
     * can tell one occurrence from several. Pass {@link Severity#DEBUG} to keep everything.
     *
     * @param atLeast the lowest severity to include; must not be {@code null}
     * @return the codes, in the order reported, with repeats
     * @throws NullPointerException if {@code atLeast} is {@code null}
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    public List<String> codes(@NotNull final Severity atLeast) {
        Objects.requireNonNull(atLeast, "atLeast");
        return this.diagnostics.stream()
                .filter(diagnostic -> diagnostic.severity().isAtLeast(atLeast))
                .map(diagnostic -> diagnostic.code().code())
                .toList();
    }

    /**
     * Returns a one-line summary rather than the record's generated form.
     *
     * <p>The generated one would print three byte arrays as identity hashes and tell the reader
     * nothing. This prints the binary name, {@code woven} or {@code untouched}, and the number of
     * diagnostics with the noun agreed to it. The bytes themselves do not appear, so this is safe
     * to put in an assertion message.
     *
     * @return the summary, for example {@code WeaveResult[com.acme.Ledger woven, 1 diagnostic]}
     */
    @Override
    @NotNull
    public String toString() {
        return "WeaveResult[" + binaryName() + (wasWoven() ? " woven" : " untouched")
                + ", " + this.diagnostics.size() + " diagnostic"
                + (this.diagnostics.size() == 1 ? "" : "s") + ']';
    }
}
