package de.splatgames.aether.weaver.api.model;

import de.splatgames.aether.weaver.api.At;
import de.splatgames.aether.weaver.api.Point;
import de.splatgames.aether.weaver.api.select.MemberSelector;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;

/**
 * Where inside a target method a declaration attaches, in the form the search runs against.
 *
 * <p>This is the parsed form of {@link At}, and it is also the argument an
 * {@link de.splatgames.aether.weaver.api.spi.InjectionPoint} receives when it is asked to find its
 * positions. A point specification is inert: it describes a search, and the
 * {@link de.splatgames.aether.weaver.api.spi.InjectionPoint} registered under {@link #point()}
 * decides what it means.
 *
 * <h2>The order the components are applied in</h2>
 *
 * <p>Resolution is a pipeline, and knowing the order is the only way to predict what a declaration
 * matches:
 *
 * <ol>
 *   <li><b>The point is looked up</b> by {@link #point()}. An identifier no plugin registered is
 *       reported as {@code AW1101}; a contributed point is always {@code namespace:NAME} and needs
 *       its plugin on the classpath.
 *   <li><b>The target requirement is checked.</b> A point declaring
 *       {@code TargetRequirement.REQUIRED} with no {@link #rawTarget()}, and one declaring
 *       {@code FORBIDDEN} with a target, are both reported as {@code AW1043}.
 *   <li><b>The shift is checked</b> against
 *       {@link de.splatgames.aether.weaver.api.spi.InjectionPoint#supportsShift(At.Shift)}. A shift
 *       the point refuses is reported as {@code AW1102}, because it would land somewhere the
 *       verifier rejects.
 *   <li><b>The slice is resolved</b>, narrowing the body to a region. See {@link SliceSpec}.
 *   <li><b>The point searches</b> that region and returns its matches.
 *   <li><b>The ordinal selects</b> one of them, counting within the region.
 *   <li><b>The shift moves</b> the selection.
 *   <li><b>Positions nothing may be woven at are refused</b>, which the first steps happily find.
 *       Which refusals apply depends on the kind of declaration. A
 *       {@link de.splatgames.aether.weaver.api.Redirect} or
 *       {@link de.splatgames.aether.weaver.api.Wrap} stands in for an operation, so a position
 *       <em>after</em> one is reported as {@code AW1061}; there is nothing there to stand in for,
 *       and {@link de.splatgames.aether.weaver.api.Inject} is what adds code at such a position.
 *       An {@link de.splatgames.aether.weaver.api.Inject} is additionally refused before a
 *       constructor's own {@code super(...)} call when its handler needs {@code this}
 *       ({@code AW1026}), between a {@code new} and the constructor call that completes it
 *       ({@code AW1105}), and warned about at an instruction nothing can reach ({@code AW1130}),
 *       which drops that position and keeps the rest.
 * </ol>
 *
 * <h2>The target, and why it is carried twice</h2>
 *
 * <p>{@link #rawTarget()} is the text as written; {@link #target()} is that text parsed into a
 * {@link MemberSelector}. Both may be {@code null}, and a parsed selector without the text it came
 * from is refused on construction, so there are exactly three states:
 *
 * <ul>
 *   <li><b>Neither.</b> {@link #hasTarget()} is {@code false} and the point matches on position
 *       alone, as {@link Point#HEAD} and {@link Point#TAIL} do.
 *   <li><b>Text only.</b> {@link #hasTarget()} is {@code true} and {@link #hasSelector()} is
 *       {@code false}. This is what a point whose target is not a member produces:
 *       {@link Point#NEW} names a class, and forcing a class name through the member grammar would
 *       either fail or succeed with the wrong meaning. Such a target is compared as text.
 *   <li><b>Both.</b> The target was parsed, and the parsed form is what the point matches with.
 *       {@link Point#FIELD} is parsed with the field grammar, and {@link Point#INVOKE},
 *       {@link Point#INVOKE_AFTER} and {@link Point#CONSTANT} with the method grammar. A target
 *       that does not parse is reported as {@code AW1015}, as {@code AW1017} when the text looks
 *       like a descriptor written without the required {@code desc:} prefix, or as
 *       {@code AW1018} or {@code AW1019} where the mistake is inside a {@code desc:} form, and the
 *       declaration is dropped.
 * </ul>
 *
 * <p>A contributed point that declares a target always lands in the text-only state, because the
 * grammar to parse that target with is not known to the reader of the weave class.
 *
 * <h2>The ordinal</h2>
 *
 * <p>{@code -1} keeps every match, which is what lets a single declaration weave several positions;
 * {@code 0} and above select exactly one, zero-based, counted <em>within the slice</em>. The
 * sentinel is not a value here, and defaulting it to {@code 0} would silently weave only the first
 * match of a declaration meant for all of them. An ordinal past the end of the matches is reported
 * as {@code AW1110}.
 *
 * <p>A point used as a {@link SliceSpec} bound is the one place where the default is {@code 0}
 * instead, since a bound must resolve to exactly one position; {@link SliceSpec} refuses a bound
 * whose {@link #matchesAll()} is true.
 *
 * <h2>Shift</h2>
 *
 * <p>{@link #shift()} moves the selected position by whole instructions after the ordinal has
 * chosen it: {@link At.Shift#NONE} by nothing, {@link At.Shift#BEFORE} by {@code -1},
 * {@link At.Shift#AFTER} by {@code +1}, and {@link At.Shift#BY} by {@link #by()}, which may be
 * negative. {@link #by()} determines the moved position only when {@link #shift()} is
 * {@link At.Shift#BY}; a {@link At.Shift#BY} of {@code 0} moves nothing.
 *
 * <p>An offset of more than four instructions in either direction is reported as {@code AW1112}, a
 * warning: a large offset almost always means a slice or a different point would express the intent
 * better, and it breaks on any recompilation of the target. A shift that moves a site out of the
 * region it was found in is reported as {@code AW1111}, and this point then matches nothing rather
 * than being clamped.
 *
 * <h2>Access</h2>
 *
 * <p>{@link #access()} narrows a {@link Point#FIELD} search to one kind of field access, and no
 * other point's search consults it.
 *
 * <h2>Arguments</h2>
 *
 * <p>{@link #arguments()} is configuration a contributed point defines for itself. Nothing that
 * reads an {@link At} populates it, so a specification parsed from an annotation always has it
 * empty; it is filled in by a caller building a specification through {@link Builder}. It exists as
 * a seam because adding a component to this record after release would be a breaking change, so a
 * contributed point that needs configuration has somewhere to put it without a new {@link At}
 * element.
 *
 * <h2>Identifiers</h2>
 *
 * <p>{@link #point()} is the built-in {@link Point} constant's own {@link Enum#name()} for a
 * built-in point — {@code "HEAD"}, {@code "INVOKE_AFTER"} — and {@code namespace:NAME} for a
 * contributed one. It is compared literally and is never case-folded.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * // Parsed from an annotation:
 * @Inject(method = "process(java.util.List)",
 *         at = @At(value = Point.INVOKE, target = "com.acme.Store.persist(Entry)", ordinal = 1),
 *         require = 1)
 * private static void onSecondPersist(Callback cb) { ... }
 *
 * // The same position, built directly for a contributed injection point:
 * PointSpec spec = PointSpec.named("acme:AFTER_LOGGING")
 *         .arguments(Map.of("level", "WARN"))
 *         .shift(At.Shift.AFTER)
 *         .build();
 * }</pre>
 *
 * @param point     the injection point identifier; never blank
 * @param rawTarget the target as written, or {@code null} when none was given
 * @param target    the target parsed as a member selector, or {@code null} when it was not parsed
 * @param ordinal   the zero-based match to select, or {@code -1} to keep every match
 * @param shift     how far to move the selected position
 * @param by        the offset used by {@link At.Shift#BY}, ignored for every other shift
 * @param access    the field access kind to narrow to, consulted by {@link Point#FIELD}'s matching
 *                  only
 * @param slice     the identifier of the slice to search, or the empty string for the unnamed slice
 * @param arguments configuration for a contributed point, held as an unmodifiable copy
 * @author Erik Pförtner
 * @since 0.1.0
 * @see At
 * @see de.splatgames.aether.weaver.api.spi.InjectionPoint
 */
public record PointSpec(String point,
                        @Nullable String rawTarget,
                        @Nullable MemberSelector target,
                        int ordinal,
                        At.Shift shift,
                        int by,
                        At.Access access,
                        String slice,
                        Map<String, String> arguments) {

    /**
     * Checks the identifier and the ordinal, and takes an unmodifiable copy of the arguments.
     *
     * <p>A parsed selector with no {@link #rawTarget()} is refused because every diagnostic about a
     * target quotes the text the author wrote, and a selector rendered back into text is not
     * guaranteed to be that text.
     *
     * @throws NullPointerException     if {@code point}, {@code shift}, {@code access},
     *                                  {@code slice} or {@code arguments} is {@code null}, or if
     *                                  {@code arguments} holds a {@code null} key or value
     * @throws IllegalArgumentException if {@code point} is blank, if {@code ordinal} is below
     *                                  {@code -1}, or if {@code target} is given without
     *                                  {@code rawTarget}
     */
    public PointSpec {
        Objects.requireNonNull(point, "point");
        Objects.requireNonNull(shift, "shift");
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(slice, "slice");
        Objects.requireNonNull(arguments, "arguments");
        arguments = Map.copyOf(arguments);
        if (point.isBlank()) {
            throw new IllegalArgumentException("a point identifier must not be blank");
        }
        if (ordinal < -1) {
            throw new IllegalArgumentException(
                    "ordinal is -1 for all matches or a zero-based index, but was " + ordinal);
        }
        if (target != null && rawTarget == null) {
            throw new IllegalArgumentException(
                    "a parsed selector cannot exist without the text it was parsed from");
        }
    }

    /**
     * Starts a specification for one of the built-in points.
     *
     * <p>The identifier is the constant's {@link Enum#name()}, which is the spelling every built-in
     * point registers itself under.
     *
     * @param point the built-in point; must not be {@code null}
     * @return a builder with every other component at its default
     * @throws NullPointerException if {@code point} is {@code null}
     */
    @Contract(value = "_ -> new", pure = true)
    @NotNull
    public static Builder builtIn(@NotNull final Point point) {
        return new Builder(Objects.requireNonNull(point, "point").name());
    }

    /**
     * Starts a specification for a point named by identifier.
     *
     * <p>The route for a contributed point, whose identifier is {@code namespace:NAME}. Passing a
     * built-in constant's name here is equivalent to {@link #builtIn(Point)}; nothing checks that
     * the identifier is registered, and an unregistered one is reported as {@code AW1101} when the
     * declaration is resolved.
     *
     * @param point the point identifier; must not be {@code null} or blank
     * @return a builder with every other component at its default
     * @throws NullPointerException     if {@code point} is {@code null}
     * @throws IllegalArgumentException if {@code point} is empty or contains only whitespace
     */
    @Contract(value = "_ -> new", pure = true)
    @NotNull
    public static Builder named(@NotNull final String point) {
        return new Builder(point);
    }

    /**
     * Reports whether a target was written.
     *
     * <p>Answers on {@link #rawTarget()}, not on {@link #target()}, so a target that was kept as
     * text counts. This is what the required-and-forbidden check consults.
     *
     * @return {@code true} when {@link #rawTarget()} is not {@code null}
     */
    @Contract(pure = true)
    public boolean hasTarget() {
        return this.rawTarget != null;
    }

    /**
     * Reports whether the target was parsed into a selector.
     *
     * <p>{@code false} both when there is no target and when there is one that was deliberately
     * left as text. A point that finds this {@code false} and {@link #hasTarget()} {@code true}
     * matches the raw text instead.
     *
     * @return {@code true} when {@link #target()} is not {@code null}
     */
    @Contract(pure = true)
    public boolean hasSelector() {
        return this.target != null;
    }

    /**
     * Reports whether every match is kept rather than one being selected.
     *
     * @return {@code true} when {@link #ordinal()} is negative
     */
    @Contract(pure = true)
    public boolean matchesAll() {
        return this.ordinal < 0;
    }

    /**
     * Reports whether this point names a slice by identifier.
     *
     * <p>{@code false} for the empty identifier, which still selects a slice — the unnamed one, if
     * the declaration has it.
     *
     * @return {@code true} when {@link #slice()} is not the empty string
     */
    @Contract(pure = true)
    public boolean isSliced() {
        return !this.slice.isEmpty();
    }

    /**
     * Assembles a {@link PointSpec} component by component.
     *
     * <p>The route for a caller building a specification directly rather than reading one from an
     * {@link At}: a plugin's tests, a tool, or a driver that has its own declaration format. Every
     * component starts at the same default the annotation declares, so a builder left untouched
     * produces the specification an {@code @At} with nothing written would.
     *
     * <p>Not thread-safe and not reusable in the sense of being rewound: {@link #build()} may be
     * called repeatedly, and each call reads the builder's state as it stands.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public static final class Builder {

        /** The point identifier, fixed when the builder is created. */
        private final String point;

        /** The target as written, or {@code null} while none has been given. */
        private @Nullable String rawTarget;

        /** The parsed target, or {@code null} while none has been given. */
        private @Nullable MemberSelector target;

        /** The match to select; {@code -1} keeps every match. */
        private int ordinal = -1;

        /** How far to move the selected position. */
        private At.Shift shift = At.Shift.NONE;

        /** The offset for {@link At.Shift#BY}. */
        private int by;

        /** The field access kind to narrow to. */
        private At.Access access = At.Access.ANY;

        /** The slice identifier; the empty string selects the unnamed slice. */
        private String slice = "";

        /** Configuration for a contributed point. */
        private Map<String, String> arguments = Map.of();

        /**
         * Creates a builder for the given point identifier.
         *
         * @param point the point identifier; must not be {@code null} or blank
         * @throws NullPointerException     if {@code point} is {@code null}
         * @throws IllegalArgumentException if {@code point} is empty or contains only whitespace
         */
        private Builder(@NotNull final String point) {
            Objects.requireNonNull(point, "point");
            if (point.isBlank()) {
                throw new IllegalArgumentException("a point identifier must not be blank");
            }
            this.point = point;
        }

        /**
         * Sets the target as text, leaving it unparsed.
         *
         * <p>What a point whose target is not a member wants, and the only form available when the
         * grammar to parse the target with is unknown. Any parsed selector set earlier is left in
         * place, so call {@link #target(String, MemberSelector)} to change both at once.
         *
         * @param target the target as written; must not be {@code null}
         * @return this builder
         * @throws NullPointerException if {@code target} is {@code null}
         */
        @Contract(value = "_ -> this")
        @NotNull
        public Builder target(@NotNull final String target) {
            this.rawTarget = Objects.requireNonNull(target, "target");
            return this;
        }

        /**
         * Sets the target as text together with the selector it parsed to.
         *
         * <p>The two are set together because a parsed selector without its source text is refused
         * on construction.
         *
         * @param target the target as written; must not be {@code null}
         * @param parsed the selector that text parsed to; must not be {@code null}
         * @return this builder
         * @throws NullPointerException if either argument is {@code null}
         */
        @Contract(value = "_, _ -> this")
        @NotNull
        public Builder target(@NotNull final String target, @NotNull final MemberSelector parsed) {
            this.rawTarget = Objects.requireNonNull(target, "target");
            this.target = Objects.requireNonNull(parsed, "parsed");
            return this;
        }

        /**
         * Sets which match to select.
         *
         * @param ordinal the zero-based match, or {@code -1} to keep every match
         * @return this builder
         * @throws IllegalArgumentException if {@code ordinal} is below {@code -1}
         */
        @Contract(value = "_ -> this")
        @NotNull
        public Builder ordinal(final int ordinal) {
            if (ordinal < -1) {
                throw new IllegalArgumentException(
                        "ordinal is -1 for all matches or a zero-based index, but was " + ordinal);
            }
            this.ordinal = ordinal;
            return this;
        }

        /**
         * Sets how far to move the selected position.
         *
         * @param shift the shift; must not be {@code null}
         * @return this builder
         * @throws NullPointerException if {@code shift} is {@code null}
         */
        @Contract(value = "_ -> this")
        @NotNull
        public Builder shift(@NotNull final At.Shift shift) {
            this.shift = Objects.requireNonNull(shift, "shift");
            return this;
        }

        /**
         * Sets the offset {@link At.Shift#BY} uses.
         *
         * <p>Not validated and not bounded here. A magnitude above four is reported as
         * {@code AW1112} when the declaration is resolved, and the offset is ignored entirely
         * unless {@link #shift(At.Shift)} is {@link At.Shift#BY}.
         *
         * @param by the offset in instructions, which may be negative
         * @return this builder
         */
        @Contract(value = "_ -> this")
        @NotNull
        public Builder by(final int by) {
            this.by = by;
            return this;
        }

        /**
         * Sets the field access kind to narrow to.
         *
         * <p>Consulted by {@link Point#FIELD}'s matching only; every other point carries the value
         * without matching against it.
         *
         * @param access the access kind; must not be {@code null}
         * @return this builder
         * @throws NullPointerException if {@code access} is {@code null}
         */
        @Contract(value = "_ -> this")
        @NotNull
        public Builder access(@NotNull final At.Access access) {
            this.access = Objects.requireNonNull(access, "access");
            return this;
        }

        /**
         * Sets the slice this point searches.
         *
         * <p>Compared literally against {@link SliceSpec#id()}. An identifier no slice of the
         * declaration declares is not an error: the point searches the whole method instead.
         *
         * @param slice the slice identifier, or the empty string for the unnamed slice; must not be
         *              {@code null}
         * @return this builder
         * @throws NullPointerException if {@code slice} is {@code null}
         */
        @Contract(value = "_ -> this")
        @NotNull
        public Builder slice(@NotNull final String slice) {
            this.slice = Objects.requireNonNull(slice, "slice");
            return this;
        }

        /**
         * Sets the configuration a contributed point reads for itself.
         *
         * <p>Copied immediately, so the caller may keep and change the map it passed. Replaces any
         * arguments set earlier rather than merging with them.
         *
         * @param arguments the arguments; must not be {@code null} and must hold no {@code null}
         *                  key or value
         * @return this builder
         * @throws NullPointerException if {@code arguments} is {@code null} or holds a {@code null}
         *                              key or value
         */
        @Contract("_ -> this")
        @NotNull
        public Builder arguments(@NotNull final Map<String, String> arguments) {
            this.arguments = Map.copyOf(Objects.requireNonNull(arguments, "arguments"));
            return this;
        }

        /**
         * Returns the specification described so far.
         *
         * <p>A fresh instance on each call, and the builder remains usable afterwards.
         *
         * @return the point specification
         * @throws IllegalArgumentException if the components do not satisfy the record's own
         *                                  invariants
         */
        @Contract(value = "-> new", pure = true)
        @NotNull
        public PointSpec build() {
            return new PointSpec(this.point, this.rawTarget, this.target, this.ordinal,
                    this.shift, this.by, this.access, this.slice, this.arguments);
        }
    }
}
