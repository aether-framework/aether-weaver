package de.splatgames.aether.weaver.runtime.config;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Which weaves a run keeps, judged by the tags each weave declares.
 *
 * <p>Built from the {@code aether.weaver.tags.include} and {@code aether.weaver.tags.exclude}
 * configuration keys, each a comma-separated list. Both keys describe this one filter, and a source
 * that sets both ends up with both; a layer that sets either, however, replaces the whole filter of
 * the layers below it rather than adding to it, because {@link ConfigLayer#merge(ConfigLayer)}
 * treats the filter as a single value.
 *
 * <p>{@link #accepts(Set)} states the rule the two sets combine into. Tags are matched by exact
 * string equality; there is no pattern, no case folding and no diagnostic for a tag that matches
 * nothing.
 *
 * @param included the tags that qualify a weave, or empty to qualify every weave that is not
 *                 excluded
 * @param excluded the tags that disqualify a weave, whatever else it carries
 * @author Erik Pförtner
 * @since 0.1.0
 */
public record TagFilter(@NotNull @Unmodifiable Set<String> included,
                        @NotNull @Unmodifiable Set<String> excluded) {

    /** The filter that accepts every weave. This is what a configuration resolves to when unset. */
    public static final TagFilter ALL = new TagFilter(Set.of(), Set.of());

    /**
     * Takes defensive, unmodifiable copies of both sets.
     *
     * @throws NullPointerException if either set is {@code null} or holds a {@code null}
     */
    public TagFilter {
        included = Set.copyOf(Objects.requireNonNull(included, "included"));
        excluded = Set.copyOf(Objects.requireNonNull(excluded, "excluded"));
    }

    /**
     * Returns a filter accepting only weaves carrying one of the given tags.
     *
     * @param tags the tags to include; must not be {@code null}, hold a {@code null} or repeat a tag
     * @return a filter that includes those tags and excludes none
     * @throws NullPointerException     if {@code tags} is {@code null} or holds a {@code null}
     * @throws IllegalArgumentException if a tag is given twice
     */
    @Contract(value = "_ -> new", pure = true)
    @NotNull
    public static TagFilter include(@NotNull final String... tags) {
        return new TagFilter(Set.of(tags), Set.of());
    }

    /**
     * Returns a filter accepting every weave that carries none of the given tags.
     *
     * @param tags the tags to exclude; must not be {@code null}, hold a {@code null} or repeat a tag
     * @return a filter that excludes those tags and includes everything else
     * @throws NullPointerException     if {@code tags} is {@code null} or holds a {@code null}
     * @throws IllegalArgumentException if a tag is given twice
     */
    @Contract(value = "_ -> new", pure = true)
    @NotNull
    public static TagFilter exclude(@NotNull final String... tags) {
        return new TagFilter(Set.of(), Set.of(tags));
    }

    /**
     * Returns this filter with further tags excluded.
     *
     * <p>The included set is carried over unchanged, and a tag already excluded is accepted without
     * complaint, unlike {@link #exclude(String...)}.
     *
     * @param tags the tags to exclude as well; must not be {@code null} or hold a {@code null}
     * @return a new filter, this one being unchanged
     * @throws NullPointerException if {@code tags} is {@code null} or holds a {@code null}
     */
    @Contract(value = "_ -> new", pure = true)
    @NotNull
    public TagFilter excluding(@NotNull final String... tags) {
        final Set<String> combined = new LinkedHashSet<>(this.excluded);
        for (final String tag : Objects.requireNonNull(tags, "tags")) {
            combined.add(Objects.requireNonNull(tag, "tag"));
        }
        return new TagFilter(this.included, combined);
    }

    /**
     * Decides whether a weave carrying the given tags is kept.
     *
     * <p>An excluded tag settles it: one match in {@link #excluded()} rejects the weave whatever
     * else it carries, and that test runs first. Failing that, an empty {@link #included()} accepts
     * everything, and a non-empty one accepts only a weave sharing at least one tag with it — which
     * makes a weave declaring no tags at all rejected as soon as anything is included by name.
     *
     * @param tags the tags the weave declares, possibly empty; must not be {@code null}
     * @return {@code true} when the weave is kept
     * @throws NullPointerException if {@code tags} is {@code null}
     */
    @Contract(pure = true)
    public boolean accepts(@NotNull final Set<String> tags) {
        Objects.requireNonNull(tags, "tags");
        for (final String tag : tags) {
            if (this.excluded.contains(tag)) {
                return false;
            }
        }
        if (this.included.isEmpty()) {
            return true;
        }
        for (final String tag : tags) {
            if (this.included.contains(tag)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether this filter judges nothing.
     *
     * @return {@code true} when neither set holds a tag, so that {@link #accepts(Set)} is always
     *         {@code true}
     */
    @Contract(pure = true)
    public boolean isUnrestricted() {
        return this.included.isEmpty() && this.excluded.isEmpty();
    }

    /**
     * Returns the two sets, or {@code TagFilter[all]} when nothing is filtered.
     *
     * <p>This is what {@link WeaverConfig#summary()} and the explain report show for the
     * {@code tags} setting, so the unrestricted case is spelled out rather than shown as two empty
     * brackets.
     *
     * @return a description of this filter
     */
    @Override
    @NotNull
    public String toString() {
        if (isUnrestricted()) {
            return "TagFilter[all]";
        }
        return "TagFilter[include=" + this.included + ", exclude=" + this.excluded + ']';
    }
}
