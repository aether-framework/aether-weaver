package de.splatgames.aether.weaver.api;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads the {@link Woven} record a class carries, as ordinary values rather than as annotation
 * elements.
 *
 * <p>The supported way to ask a class at run time what was done to it. Every accessor here reads
 * the annotation and nothing else — no class file is parsed, no weaver is consulted — so the
 * answers are exactly what the weaver wrote at the moment it stamped the class, and they cost
 * nothing beyond the reflective lookup that produced the instance.
 *
 * <p>What this adds over reading {@link Woven} directly is the decoding: arrays become unmodifiable
 * lists, {@link Woven#extra()} becomes a map, the bits of {@link Woven#flags()} become named
 * questions, and {@link Woven#entries()} becomes readable lines. A caller that needs the raw
 * elements can still take the annotation itself; a caller that wants to print what happened wants
 * this.
 *
 * <h2>Absence is not the same as unwoven</h2>
 *
 * <p>{@link #of(Class)} answers empty for a class with no {@link Woven} annotation, and there are
 * two reasons a woven class can be in that state: the weaver was configured with
 * {@link Woven.Detail#NONE}, or the class was woven by something that does not stamp. The
 * annotation is a reader-facing record and not the weaver's own bookkeeping, which lives in a class
 * file attribute this type does not read.
 *
 * <h2>Thread safety</h2>
 *
 * <p>Immutable and safe to share. The instance holds the annotation and nothing else; every
 * accessor is pure, and the collections handed back are unmodifiable copies rather than views.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * WovenInfo.of(Ledger.class).ifPresent(info -> {
 *     System.out.println(info);                        // woven by 0.1.0 plan 9f1c... via ...
 *     for (String applied : info.entries()) {          // empty unless Detail.FULL
 *         System.out.println("  " + applied);
 *     }
 *     if (info.entriesTruncated()) {
 *         System.out.println("  ... and more");
 *     }
 * });
 * }</pre>
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see Woven
 */
public final class WovenInfo {

    /** The record this instance reads; never {@code null}. */
    private final Woven woven;

    /**
     * Wraps a record that has already been found on a class.
     *
     * <p>Private because the only supported entry point is {@link #of(Class)}, which is what
     * guarantees the record came off a class rather than from a synthesised annotation instance.
     *
     * @param woven the record to read; must not be {@code null}
     */
    private WovenInfo(@NotNull final Woven woven) {
        this.woven = woven;
    }

    /**
     * Reads the record a class carries, if it carries one.
     *
     * <p>Only the class's own annotation counts. {@link Woven} is not {@code @Inherited}, and the
     * lookup asks for the declared annotation explicitly, so a subclass of a woven class does not
     * report itself as woven.
     *
     * <p>An empty result means the class carries no record. It does not mean the class was never
     * woven: a weaver configured with {@link Woven.Detail#NONE} writes none.
     *
     * @param type the class to inspect; must not be {@code null}
     * @return the record, or an empty {@link Optional} when the class carries none
     * @throws NullPointerException if {@code type} is {@code null}
     */
    @Contract(pure = true)
    @NotNull
    public static Optional<WovenInfo> of(@NotNull final Class<?> type) {
        Objects.requireNonNull(type, "type");
        // getDeclaredAnnotation rather than getAnnotation: @Woven is not @Inherited, so the two
        // agree today. Asking for the declared one states the intent, and keeps this correct if
        // anyone ever adds @Inherited by mistake.
        return Optional.ofNullable(type.getDeclaredAnnotation(Woven.class)).map(WovenInfo::new);
    }

    /**
     * Returns the version of the weaver that produced the class.
     *
     * @return the weaver version, never blank
     */
    @Contract(pure = true)
    @NotNull
    public String weaver() {
        return this.woven.weaver();
    }

    /**
     * Returns the identity of the plan that was applied.
     *
     * <p>64 lowercase hexadecimal characters, covering the whole plan rather than this class alone:
     * every class woven in one run reports the same value.
     *
     * @return the plan fingerprint
     */
    @Contract(pure = true)
    @NotNull
    public String fingerprint() {
        return this.woven.fingerprint();
    }

    /**
     * Returns how much the weaver was configured to record.
     *
     * <p>{@link Woven.Detail#NONE} is never observed here, because a class stamped at that level
     * carries no annotation for {@link #of(Class)} to find.
     *
     * @return the detail level the record was written at
     */
    @Contract(pure = true)
    @NotNull
    public Woven.Detail detail() {
        return this.woven.detail();
    }

    /**
     * Returns the binary names of the weave classes that contributed to the class.
     *
     * <p>Sorted and free of duplicates, one name per weave class however many of its declarations
     * were planned against the class. A weave class is listed once any of its declarations produces
     * a plan entry for this target, whether or not that declaration went on to resolve a site or
     * pass injector validation — a declaration that matched nothing or was refused can still leave
     * its weave class here. An instance weave that only merges members onto the target and declares
     * no handler of its own produces no plan entry and is not listed, even though it changed the
     * class.
     *
     * @return the weave classes planned against the class
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    public List<String> weaves() {
        return List.of(this.woven.weaves());
    }

    /**
     * Returns the plugins the weaver was registered with, as {@code namespace:version}.
     *
     * <p>Sorted. A plugin is listed whether or not it contributed anything to this class.
     *
     * @return the registered plugin coordinates
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    public List<String> plugins() {
        return List.of(this.woven.plugins());
    }

    /**
     * Returns the plugin metadata, decoded from {@link Woven#extra()}.
     *
     * <p>Each {@code key=value} string is split at its <em>first</em> {@code =}, so a value may
     * contain further ones. A string with no {@code =}, or one beginning with {@code =}, names no
     * key and is dropped rather than reported. Where two strings decode to the same key the later
     * one wins.
     *
     * <p>The result is an unmodifiable map with no iteration order of its own; the sorting the
     * weaver applied when it wrote {@link Woven#extra()} does not survive the copy. A caller that
     * needs the keys in order should sort them.
     *
     * <p>An unrecognised key belongs to a plugin the reader does not know and should be ignored.
     *
     * @return the metadata, empty when the record carries none
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    public Map<String, String> metadata() {
        final Map<String, String> decoded = new LinkedHashMap<>();
        for (final String entry : this.woven.extra()) {
            final int equals = entry.indexOf('=');
            if (equals > 0) {
                decoded.put(entry.substring(0, equals), entry.substring(equals + 1));
            }
        }
        return Map.copyOf(decoded);
    }

    /**
     * Returns one line per modification the weaver applied, formatted for reading.
     *
     * <p>Each line is {@code weave kind handler -> target}, built from the four components of a
     * {@link Woven.Entry}. The format is meant for a log or a console; a caller that needs the
     * components separately should read {@link Woven#entries()} directly.
     *
     * <p>Empty unless {@link #detail()} is {@link Woven.Detail#FULL}, and at most 32 lines even
     * then — {@link #entriesTruncated()} says whether there were more. A line is written for every
     * declaration the plan carried for this class, whether or not it went on to resolve a site or
     * pass injector validation, so a declaration that matched nothing or was refused still appears.
     *
     * @return the planned declarations, in plan order
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    public List<String> entries() {
        final List<String> lines = new ArrayList<>(this.woven.entries().length);
        for (final Woven.Entry entry : this.woven.entries()) {
            lines.add(entry.weave() + ' ' + entry.kind() + ' ' + entry.handler()
                    + " -> " + entry.target());
        }
        return List.copyOf(lines);
    }

    /**
     * Reports whether a policy override was active when the class was woven.
     *
     * <p>Decodes the {@code 0x0001} bit of {@link Woven#flags()}. The weaver's stamping path does
     * not set that bit, so this answers {@code false} for every class this version writes.
     *
     * @return whether the record has the policy-override bit set
     */
    @Contract(pure = true)
    public boolean usedPolicyOverride() {
        return (this.woven.flags() & FLAG_POLICY_OVERRIDE) != 0;
    }

    /**
     * Reports whether the class was changed structurally.
     *
     * <p>Decodes the {@code 0x0002} bit of {@link Woven#flags()}. The weaver's stamping path does
     * not set that bit, so this answers {@code false} for every class this version writes; a class
     * that did gain members is recognised by its members rather than by this.
     *
     * @return whether the record has the structural bit set
     */
    @Contract(pure = true)
    public boolean isStructural() {
        return (this.woven.flags() & FLAG_STRUCTURAL) != 0;
    }

    /**
     * Reports whether {@link #entries()} lists everything that was applied.
     *
     * <p>Decodes the {@code 0x0004} bit of {@link Woven#flags()}, which the weaver sets when a
     * {@link Woven.Detail#FULL} record had more than 32 modifications to list. A {@code true} here
     * means the first 32 are present and the rest were dropped; it does not say how many.
     *
     * @return whether the listed modifications are only the first of them
     */
    @Contract(pure = true)
    public boolean entriesTruncated() {
        return (this.woven.flags() & FLAG_TRUNCATED) != 0;
    }

    /**
     * Returns a one-line summary naming the weaver, the plan and the contributing weaves.
     *
     * <p>Of the form {@code woven by 0.1.0 plan 9f1c... via com.acme.LedgerAudit}, with the weaves
     * comma-separated. The fingerprint is written in full. Intended for a log line; the accessors
     * are what a caller should read to make a decision.
     *
     * @return the summary
     */
    @Override
    @NotNull
    public String toString() {
        return "woven by " + weaver() + " plan " + fingerprint()
                + " via " + String.join(", ", weaves());
    }

    /** The {@link Woven#flags()} bit saying a policy override was active. */
    private static final int FLAG_POLICY_OVERRIDE = 0x0001;

    /** The {@link Woven#flags()} bit saying the class was changed structurally. */
    private static final int FLAG_STRUCTURAL = 0x0002;

    /** The {@link Woven#flags()} bit saying {@link Woven#entries()} was cut short at 32. */
    private static final int FLAG_TRUNCATED = 0x0004;
}
