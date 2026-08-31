package de.splatgames.aether.weaver.engine.stamp;

import de.splatgames.aether.weaver.api.spi.PlanEntryView;
import de.splatgames.aether.weaver.api.spi.PluginId;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * What was done to one class, in the form both carriers of the stamp are written from.
 *
 * <p>The {@code AetherWeave} attribute and the {@code @Woven} annotation are built from the same
 * record, so the version, the fingerprint and the entries cannot disagree between them; each writer
 * only chooses what to leave out. The record holds more than the attribute can carry — plugin
 * coordinates and plugin metadata reach a woven class through the annotation alone.
 *
 * <p>Everything that has no natural order is put into one: {@link #of} sorts the weave names and
 * the plugin coordinates, and the metadata is held sorted by key. The entries keep the order the
 * plan held them in, so the same inputs produce the same record.
 *
 * @param weaverVersion  the version of the weaver that wrote the stamp; never blank
 * @param fingerprint    the plan's fingerprint, which is what a later run compares against; never
 *                       blank
 * @param weaves         the binary names of the weave classes that contributed
 * @param plugins        the coordinates of the plugins that were loaded
 * @param metadata       what the plugins had to say, keyed and sorted by key
 * @param entries        one entry per declaration the plan held for the class
 * @param policyOverride whether a policy override was active
 * @param structural     whether the class was changed structurally
 * @author Erik Pförtner
 * @since 0.1.0
 */
public record WeaveRecord(@NotNull String weaverVersion,
                          @NotNull String fingerprint,
                          @NotNull @Unmodifiable List<String> weaves,
                          @NotNull @Unmodifiable List<String> plugins,
                          @NotNull @Unmodifiable Map<String, String> metadata,
                          @NotNull @Unmodifiable List<Entry> entries,
                          boolean policyOverride,
                          boolean structural) {

    /** The {@link #flags(boolean)} bit saying a policy override was active. */
    public static final int FLAG_POLICY_OVERRIDE = 0x0001;

    /** The {@link #flags(boolean)} bit saying the class was changed structurally. */
    public static final int FLAG_STRUCTURAL = 0x0002;

    /**
     * The {@link #flags(boolean)} bit saying the annotation's entry listing was cut short.
     *
     * <p>Never set in the attribute, which holds every entry; a reader that finds it set in the
     * annotation has to read the attribute to see the rest.
     */
    public static final int FLAG_TRUNCATED = 0x0004;

    /** The most entries the annotation lists; the attribute is not capped. */
    public static final int MAX_ANNOTATION_ENTRIES = 32;

    /**
     * Copies every collection and checks that the record identifies its weaver and its plan.
     *
     * <p>{@code metadata} becomes a {@link TreeMap}, so its iteration order is by key and does not
     * depend on how the plugins were loaded.
     *
     * @throws NullPointerException     if any reference component is {@code null}
     * @throws IllegalArgumentException if {@code weaverVersion} or {@code fingerprint} is blank
     */
    public WeaveRecord {
        Objects.requireNonNull(weaverVersion, "weaverVersion");
        Objects.requireNonNull(fingerprint, "fingerprint");
        weaves = List.copyOf(Objects.requireNonNull(weaves, "weaves"));
        plugins = List.copyOf(Objects.requireNonNull(plugins, "plugins"));
        metadata = Collections.unmodifiableMap(
                new TreeMap<>(Objects.requireNonNull(metadata, "metadata")));
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        if (weaverVersion.isBlank() || fingerprint.isBlank()) {
            throw new IllegalArgumentException(
                    "a weave record must name its weaver and its plan");
        }
    }

    /**
     * Builds a record from what the plan held against one class.
     *
     * <p>Each entry names the weave class, the injector kind's identifier, the handler as name and
     * descriptor, and the target selector as the author wrote it. The weave names are the distinct
     * owners of those entries, sorted, and the plugin coordinates are sorted as well.
     *
     * @param weaverVersion  the version to stamp; must not be blank
     * @param fingerprint    the plan's fingerprint; must not be blank
     * @param applied        the declarations the plan held for the class, in plan order; must not
     *                       be {@code null} or contain {@code null}
     * @param pluginIds      the plugins that were loaded; must not be {@code null}
     * @param pluginMetadata what the plugins contributed; must not be {@code null}
     * @param policyOverride whether a policy override was active
     * @param structural     whether the class was changed structurally
     * @return the record
     * @throws NullPointerException     if any reference argument is {@code null}, or {@code applied}
     *                                  holds a {@code null}
     * @throws IllegalArgumentException if {@code weaverVersion} or {@code fingerprint} is blank, or
     *                                  an entry would have a blank component
     */
    @Contract(pure = true)
    @NotNull
    public static WeaveRecord of(@NotNull final String weaverVersion,
                                 @NotNull final String fingerprint,
                                 @NotNull final List<? extends PlanEntryView> applied,
                                 @NotNull final List<PluginId> pluginIds,
                                 @NotNull final Map<String, String> pluginMetadata,
                                 final boolean policyOverride,
                                 final boolean structural) {
        Objects.requireNonNull(applied, "applied");
        Objects.requireNonNull(pluginIds, "pluginIds");

        final List<Entry> entries = new ArrayList<>(applied.size());
        final List<String> weaves = new ArrayList<>();
        for (final PlanEntryView entry : applied) {
            Objects.requireNonNull(entry, "entry");
            entries.add(new Entry(entry.weaveClassName(),
                    entry.spec().kind().id(),
                    entry.handler().name() + entry.handler().type().descriptorString(),
                    entry.spec().rawMethod()));
            if (!weaves.contains(entry.weaveClassName())) {
                weaves.add(entry.weaveClassName());
            }
        }
        Collections.sort(weaves);

        final List<String> plugins = new ArrayList<>(pluginIds.size());
        pluginIds.forEach(id -> plugins.add(id.coordinate()));
        Collections.sort(plugins);

        return new WeaveRecord(weaverVersion, fingerprint, weaves, plugins,
                pluginMetadata, entries, policyOverride, structural);
    }

    /**
     * Returns the flag word for a carrier that truncates its entry listing or does not.
     *
     * <p>Derived on each call rather than stored, so the word cannot disagree with the components.
     * Truncation is the caller's to state because it is a property of the carrier being written and
     * not of the record: the annotation caps its listing and the attribute does not.
     *
     * @param truncated whether the carrier being written cut the entry listing short
     * @return the flag word, made of {@link #FLAG_POLICY_OVERRIDE}, {@link #FLAG_STRUCTURAL} and
     *         {@link #FLAG_TRUNCATED}
     */
    @Contract(pure = true)
    public int flags(final boolean truncated) {
        int flags = 0;
        if (this.policyOverride) {
            flags |= FLAG_POLICY_OVERRIDE;
        }
        if (this.structural) {
            flags |= FLAG_STRUCTURAL;
        }
        if (truncated) {
            flags |= FLAG_TRUNCATED;
        }
        return flags;
    }

    /**
     * Flattens the metadata into the {@code key=value} strings the annotation carries.
     *
     * <p>An annotation element cannot be a map, so the pairs travel as an array of strings. The
     * first {@code =} separates the two, which is why a key containing one cannot be read back.
     *
     * @return the pairs, in key order
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    public List<String> flatMetadata() {
        final List<String> flat = new ArrayList<>(this.metadata.size());
        this.metadata.forEach((key, value) -> flat.add(key + '=' + value));
        return List.copyOf(flat);
    }

    /**
     * Returns whether the annotation would have to cut the entry listing short.
     *
     * @return {@code true} when there are more than {@link #MAX_ANNOTATION_ENTRIES} entries
     */
    @Contract(pure = true)
    public boolean exceedsAnnotationCap() {
        return this.entries.size() > MAX_ANNOTATION_ENTRIES;
    }

    /**
     * One declaration the plan held against the class, in the four strings both carriers write.
     *
     * <p>All four are text rather than references: a reader of a woven class file has no plan to
     * look anything up in, so the entry has to identify the declaration on its own.
     *
     * @param weave   the binary name of the weave class that declared it
     * @param kind    the injector kind's identifier
     * @param handler the handler's name followed by its descriptor
     * @param target  the target selector as the weave author wrote it
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public record Entry(@NotNull String weave,
                        @NotNull String kind,
                        @NotNull String handler,
                        @NotNull String target) {

        /**
         * Checks that every component says something.
         *
         * @throws NullPointerException     if any component is {@code null}
         * @throws IllegalArgumentException if any component is blank
         */
        public Entry {
            Objects.requireNonNull(weave, "weave");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(handler, "handler");
            Objects.requireNonNull(target, "target");
            if (weave.isBlank() || kind.isBlank() || handler.isBlank() || target.isBlank()) {
                throw new IllegalArgumentException("no component of a weave entry may be blank");
            }
        }
    }
}
