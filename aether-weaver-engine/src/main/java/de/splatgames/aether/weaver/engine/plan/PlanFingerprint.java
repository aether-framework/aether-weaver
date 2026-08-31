package de.splatgames.aether.weaver.engine.plan;

import de.splatgames.aether.weaver.engine.model.WeaveClass;
import de.splatgames.aether.weaver.engine.model.WeaveMember;
import de.splatgames.aether.weaver.engine.plugin.PluginRegistry;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The identity of a plan: a SHA-256 over everything that decides what the weaving will do.
 *
 * <p>This is not a cache key that only costs a slow rebuild when it is wrong. The weaver stamps it
 * into every class it writes and compares it against the stamp before weaving a class again, so two
 * plans that agree here treat a class the stamp already names as already woven and silently skip
 * it, while two plans that disagree here — even about a class they touch the same way — both weave
 * over it and apply every injection they have in common twice, but only under a {@code LOAD}
 * driver; under {@code BUILD}, the class is refused instead and the run reports an error, so it is
 * woven once rather than twice. Anything that changes the outcome
 * has to reach the digest, and anything that does not must stay out of it — a weave found in
 * another directory is the same modification, and putting the path in would make two machines
 * disagree over an identical build.
 *
 * <p>The digest input is text: a layout tag, then one record per plan entry, one record per
 * structural weave plus one further record per member it declares, a header record naming how many
 * plugins follow, and then one record per plugin contribution. Records are always separated by
 * {@code U+001E}, and nothing is escaped. Within the structural and plugin records, fields are
 * separated by {@code U+001F}; a plan entry's own record is instead the pipe-separated text a
 * {@link PlanEntry} renders itself, which folds in a further pipe-separated {@link OrderKey}.
 *
 * @param value the digest as 64 lowercase hex characters
 * @author Erik Pförtner
 * @since 0.1.0
 */
public record PlanFingerprint(@NotNull String value) {

    /** How much of the digest {@link #abbreviated()} keeps for a report line. */
    private static final int ABBREVIATED_LENGTH = 16;

    /** The digest algorithm; every conforming JVM has it. */
    private static final String ALGORITHM = "SHA-256";

    /** The separator between fields of one record, {@code U+001F}. */
    private static final char FIELD = '\u001f';

    /** The separator between records, {@code U+001E}. */
    private static final char RECORD = '\u001e';

    /**
     * Checks the spelling, since the value is compared as text against a stamp read out of a class
     * file.
     *
     * @throws NullPointerException     if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is not 64 lowercase hex characters
     */
    public PlanFingerprint {
        Objects.requireNonNull(value, "value");
        if (value.length() != 64 || !value.chars().allMatch(PlanFingerprint::isLowerHex)) {
            throw new IllegalArgumentException(
                    "a fingerprint is 64 lowercase hex characters, got: " + value);
        }
    }

    /**
     * Digests a plan that has no structural weaves.
     *
     * @param entries the plan entries, in the order the plan holds them; must not be {@code null}
     * @param plugins the loaded plugins; must not be {@code null}
     * @return the fingerprint
     * @throws NullPointerException if either argument is {@code null}, or if {@code entries} holds a
     *                              {@code null}
     */
    @Contract(value = "_, _ -> new", pure = true)
    @NotNull
    public static PlanFingerprint of(@NotNull final List<PlanEntry> entries,
                                     @NotNull final PluginRegistry plugins) {
        return of(entries, List.of(), plugins);
    }

    /**
     * Digests a plan.
     *
     * <p>Order is part of the input rather than something this method imposes. {@code entries} is
     * appended as given, but {@link WeavePlanner} sorts it beforehand, so its order is fixed by the
     * targets a weave declares rather than by discovery order. {@code structural} is also appended
     * as given, and nothing sorts it first: it is the weaves that dissolve, in the order the caller
     * supplied them, so a build with two or more dissolving weaves can produce a different digest
     * depending on the order in which they were discovered.
     *
     * <p>The plugins contribute their namespaces and versions in the order the loader accepted them,
     * which is itself deterministic because the loader sorts the candidates before accepting any of
     * them, and the identifiers they registered for injector kinds and injection points and their
     * metadata, which the registry keeps sorted regardless of acceptance order — so a plugin that
     * changes which identifiers it offers changes every fingerprint.
     *
     * @param entries    the plan entries, in the order the plan holds them; must not be
     *                   {@code null}
     * @param structural the weaves that dissolve into a target, whose declared members are part of
     *                   the outcome although they produce no entry; must not be {@code null}
     * @param plugins    the loaded plugins; must not be {@code null}
     * @return the fingerprint
     * @throws NullPointerException if any argument is {@code null}, or if either list holds a
     *                              {@code null}
     */
    @Contract(value = "_, _, _ -> new", pure = true)
    @NotNull
    public static PlanFingerprint of(@NotNull final List<PlanEntry> entries,
                                     @NotNull final List<WeaveClass> structural,
                                     @NotNull final PluginRegistry plugins) {
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(structural, "structural");
        Objects.requireNonNull(plugins, "plugins");

        final StringBuilder sb = new StringBuilder(512);
        sb.append("aether-weaver/1").append(RECORD);

        for (final PlanEntry entry : entries) {
            sb.append(Objects.requireNonNull(entry, "entry").canonical()).append(RECORD);
        }
        for (final WeaveClass weave : structural) {
            appendStructure(sb, Objects.requireNonNull(weave, "weave"));
        }

        sb.append("plugins").append(FIELD).append(plugins.plugins().size()).append(RECORD);
        plugins.plugins().forEach(id -> sb
                .append(id.namespace()).append(FIELD)
                .append(id.version()).append(RECORD));
        plugins.injectors().ids().forEach(id -> sb.append("kind").append(FIELD)
                .append(id).append(RECORD));
        plugins.points().ids().forEach(id -> sb.append("point").append(FIELD)
                .append(id).append(RECORD));
        for (final Map.Entry<String, String> metadata : plugins.metadata().entrySet()) {
            sb.append("meta").append(FIELD).append(metadata.getKey())
                    .append(FIELD).append(metadata.getValue()).append(RECORD);
        }

        return new PlanFingerprint(digest(sb.toString()));
    }

    /**
     * Appends one dissolving weave and each member it declares.
     *
     * <p>A member's kind enters the digest as the record class's simple name, so renaming a
     * {@link WeaveMember} subtype changes the fingerprint of every build that has one. The access
     * flags are sorted by name, because a {@link java.util.Set} would otherwise let its iteration
     * order into the digest.
     *
     * @param sb    the digest input being built; must not be {@code null}
     * @param weave the weave to append; must not be {@code null}
     */
    private static void appendStructure(@NotNull final StringBuilder sb,
                                        @NotNull final WeaveClass weave) {
        sb.append("weave").append(FIELD).append(weave.binaryName())
                .append(FIELD).append(weave.kind()).append(RECORD);
        for (final WeaveMember member : weave.members()) {
            sb.append(member.getClass().getSimpleName()).append(FIELD)
                    .append(member.name()).append(FIELD)
                    .append(descriptorOf(member)).append(FIELD)
                    .append(member.flags().stream().map(Enum::name).sorted().toList())
                    .append(FIELD).append(detailOf(member)).append(RECORD);
        }
    }

    /**
     * Returns a member's descriptor, whether it is a field or a method.
     *
     * @param member the member; must not be {@code null}
     * @return the descriptor string
     * @throws ClassCastException if the member's type is neither a {@code ClassDesc} nor a
     *                            {@code MethodTypeDesc}
     */
    @Contract(pure = true)
    @NotNull
    private static String descriptorOf(@NotNull final WeaveMember member) {
        return member.type() instanceof java.lang.constant.ClassDesc field
                ? field.descriptorString()
                : ((java.lang.constant.MethodTypeDesc) member.type()).descriptorString();
    }

    /**
     * Returns what distinguishes one member of a kind from another of the same name and descriptor.
     *
     * <p>Each arm carries the part of the declaration that changes what weaving does with the
     * member: whether a merged member is mangled, what a shadow resolves against and whether it is
     * mutable, and which target an accessor or invoker reaches.
     *
     * @param member the member; must not be {@code null}
     * @return the distinguishing text
     */
    @Contract(pure = true)
    @NotNull
    private static String detailOf(@NotNull final WeaveMember member) {
        return switch (member) {
            case WeaveMember.Merged merged -> "unique=" + merged.unique();
            case WeaveMember.Shadowed shadowed ->
                    shadowed.targetName() + ",mutable=" + shadowed.mutable();
            case WeaveMember.Accessor accessor -> accessor.targetField();
            case WeaveMember.Invoker invoker -> invoker.targetMethod();
        };
    }

    /**
     * Returns the first 16 characters, for a report line or a log message.
     *
     * @return the abbreviated digest
     */
    @Contract(pure = true)
    @NotNull
    public String abbreviated() {
        return this.value.substring(0, ABBREVIATED_LENGTH);
    }

    /**
     * Returns the full digest, so that a fingerprint interpolated into text reads as the stamp does.
     *
     * @return the 64-character digest
     */
    @Override
    @NotNull
    public String toString() {
        return this.value;
    }

    /**
     * Hashes the canonical text as UTF-8 and renders it as lowercase hex.
     *
     * @param canonical the digest input; must not be {@code null}
     * @return the digest as 64 lowercase hex characters
     * @throws IllegalStateException if this JVM has no SHA-256
     */
    @Contract(pure = true)
    @NotNull
    private static String digest(@NotNull final String canonical) {
        try {
            final MessageDigest sha = MessageDigest.getInstance(ALGORITHM);
            return HexFormat.of().formatHex(sha.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException impossible) {
            // SHA-256 is required of every conforming JVM; if it is genuinely absent, nothing this
            // framework does can be trusted anyway.
            throw new IllegalStateException(ALGORITHM + " is required but unavailable", impossible);
        }
    }

    /**
     * Reports whether a code point is a digit or a lowercase hex letter.
     *
     * @param codePoint the code point to test
     * @return whether it may appear in a fingerprint
     */
    @Contract(pure = true)
    private static boolean isLowerHex(final int codePoint) {
        return (codePoint >= '0' && codePoint <= '9') || (codePoint >= 'a' && codePoint <= 'f');
    }
}
