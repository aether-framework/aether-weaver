package de.splatgames.aether.weaver.idea.generate;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.psi.PsiModifier;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Everything the two handler dialogs ask for, and everything the generator reads.
 *
 * <p>One value carries the whole answer from a dialog to {@code AddHandlerHandler.handlerFor(PsiClass,
 * PsiMethod, TargetOperations.Operation, List, TargetOperations.Bounds, HandlerOptions)}, which is
 * what lets the preview and the insertion run the generator on exactly the same input.
 *
 * <p>All of it except the group survives the dialog: {@link #save()} writes the choices into the
 * IDE's own {@link PropertiesComponent} and {@link #load()} reads them back for the next
 * invocation. A group is deliberately not remembered, because a group name is only meaningful
 * against the weave that declared it.
 *
 * @param kind       whether the handler runs alongside the matched operation or stands in for it
 * @param point      where inside the target the handler attaches
 * @param match      how many positions the injection accepts, and whether it pins one
 * @param selector   the form the generated selectors are written in
 * @param visibility the visibility of the generated method
 * @param prefix     what the handler's name begins with, before the capitalised subject; empty to
 *                   use the subject alone
 * @param group      the group the injection is accounted against, or an empty string for none
 * @param callback   whether the handler takes a callback parameter; ignored by a redirect
 * @param locals     whether the locals live at the site are captured as {@code @Local}
 *                   parameters; ignored by a redirect
 * @param javadoc    whether a documentation comment is generated above the handler
 * @param todo       whether the body is marked with a {@code TODO} comment
 * @author Erik Pförtner
 * @since 0.1.0
 */
public record HandlerOptions(@NotNull Kind kind,
                             @NotNull Point point,
                             @NotNull Match match,
                             @NotNull Selector selector,
                             @NotNull Visibility visibility,
                             @NotNull String prefix,
                             @NotNull String group,
                             boolean callback,
                             boolean locals,
                             boolean javadoc,
                             boolean todo) {

    /** The prefix a handler's name begins with unless the user replaces it. */
    public static final String DEFAULT_PREFIX = "on";

    /** The stored key of {@link #kind()}. */
    private static final String KIND_KEY = "aether.weaver.generate.kind";

    /** The stored key of {@link #point()}. */
    private static final String POINT_KEY = "aether.weaver.generate.point";

    /** The stored key of {@link #match()}. */
    private static final String MATCH_KEY = "aether.weaver.generate.match";

    /** The stored key of {@link #selector()}. */
    private static final String SELECTOR_KEY = "aether.weaver.generate.selector";

    /** The stored key of {@link #visibility()}. */
    private static final String VISIBILITY_KEY = "aether.weaver.generate.visibility";

    /** The stored key of {@link #prefix()}. */
    private static final String PREFIX_KEY = "aether.weaver.generate.prefix";

    /** The stored key of {@link #callback()}. */
    private static final String CALLBACK_KEY = "aether.weaver.generate.callback";

    /** The stored key of {@link #locals()}. */
    private static final String LOCALS_KEY = "aether.weaver.generate.locals";

    /** The stored key of {@link #javadoc()}. */
    private static final String JAVADOC_KEY = "aether.weaver.generate.javadoc";

    /** The stored key of {@link #todo()}. */
    private static final String TODO_KEY = "aether.weaver.generate.todo";

    /**
     * Which annotation the generated handler carries.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public enum Kind {

        /** {@code @Inject}: the handler runs at the point, and the target's own code still runs. */
        INJECT("Inject — run alongside the target's own code"),

        /**
         * {@code @Redirect}: the handler stands in for the matched operation, which is therefore not
         * performed.
         */
        REDIRECT("Redirect — replace the operation entirely");

        /** What the combo box shows for this constant. */
        private final String label;

        /**
         * Creates a constant with the text the dialog shows for it.
         *
         * @param label what the combo box shows for this constant
         */
        Kind(@NotNull final String label) {
            this.label = label;
        }

        /**
         * Reports whether this kind can be generated at the given point.
         *
         * <p>An inject applies everywhere. A redirect stands in for an operation, so it applies only
         * where there is one to stand in for: {@link Point#INVOKE}, {@link Point#FIELD} and
         * {@link Point#NEW}. {@link Point#INVOKE_AFTER} is excluded with the positional points, since
         * standing in for a call is standing in for the whole of it.
         *
         * @param point the point the handler would attach at
         * @return {@code true} when this kind can be written at that point
         */
        @Contract(pure = true)
        public boolean appliesTo(@NotNull final Point point) {
            return this == INJECT
                    || point == Point.INVOKE || point == Point.FIELD || point == Point.NEW;
        }

        /**
         * Returns the label rather than the constant's name.
         *
         * <p>The combo boxes render their items with it, so this is what the user reads.
         *
         * @return the label
         */
        @Override
        @NotNull
        public String toString() {
            return this.label;
        }
    }

    /**
     * The injection points this generator offers, each paired with the API constant it writes.
     *
     * <p>A separate enum rather than the API's own, because the dialog needs a label per constant
     * and the two questions the generator asks of a point - does it need an operation, can it match
     * more than once - are about this plugin rather than about the annotation.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public enum Point {

        /** Before the target's own code. */
        HEAD("Head — before the target's own code", de.splatgames.aether.weaver.api.Point.HEAD),

        /** At every return of the target, which is the one point that can match several times. */
        RETURN("Return — at every return", de.splatgames.aether.weaver.api.Point.RETURN),

        /** At the last return of the target. */
        TAIL("Tail — at the last return", de.splatgames.aether.weaver.api.Point.TAIL),

        /** Before a call the target makes. */
        INVOKE("Invoke — before a call the target makes",
                de.splatgames.aether.weaver.api.Point.INVOKE),

        /** After a call the target makes has returned. */
        INVOKE_AFTER("Invoke after — after a call the target makes",
                de.splatgames.aether.weaver.api.Point.INVOKE_AFTER),

        /** At a field the target reads or writes. */
        FIELD("Field — at a field the target reads or writes",
                de.splatgames.aether.weaver.api.Point.FIELD),

        /** At something the target instantiates. */
        NEW("New — at something the target instantiates",
                de.splatgames.aether.weaver.api.Point.NEW),

        /** At a constant the target loads. */
        CONSTANT("Constant — at a constant the target loads",
                de.splatgames.aether.weaver.api.Point.CONSTANT);

        /** What the combo box shows for this constant. */
        private final String label;

        /** The API constant this one is written as. */
        private final de.splatgames.aether.weaver.api.Point api;

        /**
         * Creates a constant with the text the dialog shows for it and the point it writes.
         *
         * @param label what the combo box shows for this constant
         * @param api   the API constant the generated annotation names
         */
        Point(@NotNull final String label,
              @NotNull final de.splatgames.aether.weaver.api.Point api) {
            this.label = label;
            this.api = api;
        }

        /**
         * Returns the API constant this point is written as.
         *
         * @return the point the generated {@code @At} names
         */
        @Contract(pure = true)
        @NotNull
        public de.splatgames.aether.weaver.api.Point api() {
            return this.api;
        }

        /**
         * Returns the option standing for the given API point.
         *
         * <p>Every constant of {@link de.splatgames.aether.weaver.api.Point} has one here, so the
         * fallback is reached only if a point is added there and not here.
         *
         * @param api the API point to translate
         * @return the matching option, or {@link #HEAD} when there is none
         */
        @Contract(pure = true)
        @NotNull
        public static Point of(@NotNull final de.splatgames.aether.weaver.api.Point api) {
            for (final Point candidate : values()) {
                if (candidate.api == api) {
                    return candidate;
                }
            }
            return HEAD;
        }

        /**
         * Reports whether attaching here requires an operation to have been chosen.
         *
         * <p>True of everything except {@link #HEAD}, {@link #RETURN} and {@link #TAIL}: the other
         * points are defined by an instruction, and an {@code @At} naming one of them with no target is
         * an error the user did not write. The generator refuses rather than guessing, and the dialog
         * shows the list of operations instead of the list of methods.
         *
         * @return {@code true} when no handler can be generated for this point without an operation
         */
        @Contract(pure = true)
        public boolean needsOperation() {
            return this != HEAD && this != RETURN && this != TAIL;
        }

        /**
         * Reports whether one target can hold several of this point.
         *
         * <p>True only of {@link #RETURN}. It is what makes pinning an ordinal meaningful for a
         * positional point: there is one head and one tail, so an ordinal on either is at best noise
         * and at worst {@code AW1110}.
         *
         * @return {@code true} when the point can occur more than once in one method
         */
        @Contract(pure = true)
        public boolean isRepeatable() {
            return this == RETURN;
        }

        /**
         * Returns the label rather than the constant's name.
         *
         * <p>The combo boxes render their items with it, so this is what the user reads.
         *
         * @return the label
         */
        @Override
        @NotNull
        public String toString() {
            return this.label;
        }
    }

    /**
     * How many positions the generated injection accepts, and whether it pins one.
     *
     * <p>Each constant is the pair of {@code require} and {@code allow} it writes, plus the ordinal
     * it pins. A value of zero for either bound is not written at all, so the default rule adds no
     * attributes to the annotation.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public enum Match {

        /** Every position, with no complaint if there is none: no ordinal, no bounds. */
        EVERY("Every matching position", -1, 0, 0),

        /** Every position, and at least one: {@code require = 1}. */
        EVERY_REQUIRED("Every matching position — fail the build if there is none", -1, 1, 0),

        /** Exactly one position: {@code require = 1, allow = 1}. */
        EXACTLY_ONE("Exactly one position — fail the build otherwise", -1, 1, 1),

        /** The first position only, written as {@code ordinal = 0} inside the {@code @At}. */
        FIRST("The first matching position only", 0, 0, 0);

        /** What the combo box shows for this constant. */
        private final String label;

        /** The ordinal written into the {@code @At}, or {@code -1} to write none. */
        private final int ordinal;

        /** The {@code require} written into the annotation; {@code 0} writes nothing. */
        private final int require;

        /** The {@code allow} written into the annotation; {@code 0} writes nothing. */
        private final int allow;

        /**
         * Creates a constant with the text the dialog shows for it and the attributes it writes.
         *
         * @param label   what the combo box shows for this constant
         * @param ordinal the ordinal to pin, or {@code -1} for none
         * @param require the lower bound to write, or {@code 0} for none
         * @param allow   the upper bound to write, or {@code 0} for none
         */
        Match(@NotNull final String label, final int ordinal, final int require, final int allow) {
            this.label = label;
            this.ordinal = ordinal;
            this.require = require;
            this.allow = allow;
        }

        /**
         * Returns the ordinal this rule pins.
         *
         * <p>Read only when no operation was chosen; an operation carries the ordinal of the row the
         * user picked, and that one wins.
         *
         * @return the ordinal to write, or {@code -1} to write none
         */
        @Contract(pure = true)
        public int pinnedOrdinal() {
            return this.ordinal;
        }

        /**
         * Returns the fewest matches the injection accepts.
         *
         * @return the {@code require} to write, or {@code 0} to write none
         */
        @Contract(pure = true)
        public int require() {
            return this.require;
        }

        /**
         * Returns the most matches the injection accepts.
         *
         * @return the {@code allow} to write, or {@code 0} to write none
         */
        @Contract(pure = true)
        public int allow() {
            return this.allow;
        }

        /**
         * Reports whether this rule is offered for the given point.
         *
         * <p>Everything applies everywhere except {@link #FIRST}, which is offered only at
         * {@link Point#RETURN}: it is the one point that both repeats and carries no operation of its
         * own to be counted from.
         *
         * @param point the point the handler would attach at
         * @return {@code true} when the rule can be chosen for that point
         */
        @Contract(pure = true)
        public boolean appliesTo(@NotNull final Point point) {
            // An operation point already carries the ordinal of the row the user picked, so pinning
            // one here would either restate it or contradict it.
            return point.isRepeatable() && !point.needsOperation() || this != FIRST;
        }

        /**
         * Returns the label rather than the constant's name.
         *
         * <p>The combo boxes render their items with it, so this is what the user reads.
         *
         * @return the label
         */
        @Override
        @NotNull
        public String toString() {
            return this.label;
        }
    }

    /**
     * The form every selector in the generated annotation is written in.
     *
     * <p>Not presentation. A simple name matches an owner by suffix, so it can select a wider set of
     * instructions than a qualified one and land the same instruction on a different ordinal, which
     * is why changing this re-enumerates the operations rather than relabelling them.
     *
     * <p>A form that turns out not to name the target exactly is not written: the generator falls
     * back to the fully qualified source form.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public enum Selector {

        /** Source form, with every parameter type written out in full. */
        QUALIFIED("Source form, fully qualified parameter types"),

        /** Source form, with parameter types written as their simple names. */
        SIMPLE("Source form, simple parameter type names"),

        /** Descriptor form, prefixed with {@code desc:}. */
        DESCRIPTOR("Descriptor form (desc:)");

        /** What the combo box shows for this constant. */
        private final String label;

        /**
         * Creates a constant with the text the dialog shows for it.
         *
         * @param label what the combo box shows for this constant
         */
        Selector(@NotNull final String label) {
            this.label = label;
        }

        /**
         * Returns the label rather than the constant's name.
         *
         * <p>The combo boxes render their items with it, so this is what the user reads.
         *
         * @return the label
         */
        @Override
        @NotNull
        public String toString() {
            return this.label;
        }
    }

    /**
     * The visibility the generated handler is declared with.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public enum Visibility {

        /** Whatever the weave kind requires: {@code public} for a static weave, {@code private} otherwise. */
        AUTOMATIC("Automatic — what the weave kind requires", null),

        /** Declared {@code public}. */
        PUBLIC("public", PsiModifier.PUBLIC),

        /** Declared {@code protected}. */
        PROTECTED("protected", PsiModifier.PROTECTED),

        /** Declared with no visibility modifier at all. */
        PACKAGE_PRIVATE("package-private", PsiModifier.PACKAGE_LOCAL),

        /** Declared {@code private}. */
        PRIVATE("private", PsiModifier.PRIVATE);

        /** What the combo box shows for this constant. */
        private final String label;

        /** The PSI modifier to write, or {@code null} to let the weave kind decide. */
        private final String modifier;

        /**
         * Creates a constant with the text the dialog shows for it and the modifier it writes.
         *
         * @param label    what the combo box shows for this constant
         * @param modifier the PSI modifier to write, or {@code null} for the weave kind's own choice
         */
        Visibility(@NotNull final String label, @Nullable final String modifier) {
            this.label = label;
            this.modifier = modifier;
        }

        /**
         * Returns the PSI modifier this choice writes.
         *
         * <p>{@link PsiModifier#PACKAGE_LOCAL} is not a Java keyword and is written as nothing.
         *
         * @return the modifier constant, or {@code null} when the weave kind decides
         */
        @Contract(pure = true)
        @Nullable
        public String modifier() {
            return this.modifier;
        }

        /**
         * Reports whether this visibility is reachable from a static weave's target.
         *
         * <p>A static weave is never merged into its target, so the injected call is an ordinary
         * cross-class call, reachable only when the handler is not {@code private} and either the
         * handler is {@code public} in a {@code public} weave class or the weave and the target share a
         * package. {@link #AUTOMATIC} resolves to {@code public} for a static weave and {@link #PUBLIC}
         * is always public, so both are reachable regardless of package; the other visibilities depend
         * on whether the weave and the target happen to share one, which this method cannot know, so
         * the dialogs offer no others once the weave is known to be static.
         *
         * @return {@code true} when a static weave's handler may be declared this way
         */
        @Contract(pure = true)
        public boolean survivesAStaticWeave() {
            return this == AUTOMATIC || this == PUBLIC;
        }

        /**
         * Returns the label rather than the constant's name.
         *
         * <p>The combo boxes render their items with it, so this is what the user reads.
         *
         * @return the label
         */
        @Override
        @NotNull
        public String toString() {
            return this.label;
        }
    }

    /**
     * Returns the options a first invocation starts from.
     *
     * <p>An inject at the head of the target, every matching position, fully qualified selectors,
     * the visibility the weave kind requires, the {@link #DEFAULT_PREFIX} and a callback parameter;
     * no group, no captured locals, no comment and no marker.
     *
     * @return the built-in defaults
     */
    @Contract(pure = true)
    @NotNull
    public static HandlerOptions defaults() {
        return new HandlerOptions(Kind.INJECT, Point.HEAD, Match.EVERY, Selector.QUALIFIED,
                Visibility.AUTOMATIC,
                DEFAULT_PREFIX, "", true, false, false, false);
    }

    /**
     * Reads the choices back from the IDE's stored properties.
     *
     * <p>A value that was never stored, or that no longer names a constant of its enum, falls back
     * to the one {@link #defaults()} gives. The group is never restored: it is meaningful only
     * against the weave that declared it, and the next invocation may be aimed at another.
     *
     * @return the remembered options, with defaults for whatever is missing
     */
    @NotNull
    public static HandlerOptions load() {
        final PropertiesComponent stored = PropertiesComponent.getInstance();
        final HandlerOptions defaults = defaults();
        return new HandlerOptions(
                read(stored.getValue(KIND_KEY), Kind.values(), defaults.kind()),
                read(stored.getValue(POINT_KEY), Point.values(), defaults.point()),
                read(stored.getValue(MATCH_KEY), Match.values(), defaults.match()),
                read(stored.getValue(SELECTOR_KEY), Selector.values(), defaults.selector()),
                read(stored.getValue(VISIBILITY_KEY), Visibility.values(), defaults.visibility()),
                stored.getValue(PREFIX_KEY, defaults.prefix()),
                "",
                stored.getBoolean(CALLBACK_KEY, defaults.callback()),
                stored.getBoolean(LOCALS_KEY, defaults.locals()),
                stored.getBoolean(JAVADOC_KEY, defaults.javadoc()),
                stored.getBoolean(TODO_KEY, defaults.todo()));
    }

    /**
     * Stores these choices for the next invocation.
     *
     * <p>Everything except the group is passed to {@link PropertiesComponent}, each under a key of its
     * own. The prefix and the four booleans are passed through the three-argument overload, alongside
     * {@link #defaults()}'s value for that same key as the default to compare against.
     */
    public void save() {
        final PropertiesComponent stored = PropertiesComponent.getInstance();
        final HandlerOptions defaults = defaults();
        stored.setValue(KIND_KEY, this.kind.name());
        stored.setValue(POINT_KEY, this.point.name());
        stored.setValue(MATCH_KEY, this.match.name());
        stored.setValue(SELECTOR_KEY, this.selector.name());
        stored.setValue(VISIBILITY_KEY, this.visibility.name());
        stored.setValue(PREFIX_KEY, this.prefix, defaults.prefix());
        stored.setValue(CALLBACK_KEY, this.callback, defaults.callback());
        stored.setValue(LOCALS_KEY, this.locals, defaults.locals());
        stored.setValue(JAVADOC_KEY, this.javadoc, defaults.javadoc());
        stored.setValue(TODO_KEY, this.todo, defaults.todo());
    }

    /**
     * Resolves a stored constant name against the constants that exist now.
     *
     * @param <T>      the enum type being read
     * @param stored   the stored name, possibly {@code null} or written by an older build
     * @param values   the constants to match against
     * @param fallback what to return when nothing matches
     * @return the constant whose name equals the stored text, or {@code fallback}
     */
    @Contract(pure = true)
    @NotNull
    private static <T extends Enum<T>> T read(final String stored,
                                              final T @NotNull [] values,
                                              @NotNull final T fallback) {
        for (final T candidate : values) {
            if (candidate.name().equals(stored)) {
                return candidate;
            }
        }
        return fallback;
    }
}
