package de.splatgames.aether.weaver.engine.select;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import de.splatgames.aether.weaver.api.select.MemberSelector;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Lists the members a selector could have meant, in lines shaped for the details of a diagnostic.
 *
 * <p>Ordering is by edit distance from the requested name, so a one-character typo puts the
 * intended member first, and the listing is capped at {@link #MAX_ENTRIES} with a final line
 * counting the rest.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class CandidateListing {

    /** The most candidates listed; the rest are counted in a final line. */
    public static final int MAX_ENTRIES = 10;

    /** Not instantiable. */
    private CandidateListing() {
    }

    /**
     * Describes the candidates, nearest name first.
     *
     * <p>The lines are ready to be used as diagnostic details. A candidate line carries its own
     * {@code available: } prefix, and a final line counts what was left out when there were more
     * than {@link #MAX_ENTRIES}. An empty candidate list produces one line saying so rather than
     * none, so a caller that appends the result always says something.
     *
     * @param requested  the selector that was written; must not be {@code null}
     * @param candidates the members that were searched; must not be {@code null}
     * @param form       the spelling to render each candidate in; must not be {@code null}
     * @return the lines to report, at most {@link #MAX_ENTRIES} plus one
     * @throws NullPointerException if any argument is {@code null}
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    public static List<String> describe(@NotNull final MemberSelector requested,
                                        @NotNull final List<MemberRef> candidates,
                                        @NotNull final MemberSelector.Form form) {
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(form, "form");

        if (candidates.isEmpty()) {
            return List.of("the target declares no members of this kind");
        }

        final String wanted = simpleName(requested);
        final List<MemberRef> ordered = candidates.stream()
                .sorted(Comparator
                        .comparingInt((MemberRef m) -> editDistance(wanted, m.name()))
                        .thenComparing(MemberRef::name))
                .toList();

        final List<String> lines = new java.util.ArrayList<>(MAX_ENTRIES + 1);
        ordered.stream()
                .limit(MAX_ENTRIES)
                .forEach(member -> lines.add("available: " + render(member, form)));
        if (ordered.size() > MAX_ENTRIES) {
            lines.add("... and " + (ordered.size() - MAX_ENTRIES) + " more");
        }
        return List.copyOf(lines);
    }

    /**
     * Renders one member in the spelling the selector was written in.
     *
     * <p>{@code DESCRIPTOR} hands off to {@link MemberRef#describe()}; the source form drops the
     * owner and renders each type by its source name, which is what a weave author wrote and can
     * compare their selector against.
     *
     * @param member the member to render; must not be {@code null}
     * @param form   the spelling; must not be {@code null}
     * @return the rendered member
     * @throws NullPointerException if either argument is {@code null}
     */
    @Contract(pure = true)
    @NotNull
    public static String render(@NotNull final MemberRef member, @NotNull final MemberSelector.Form form) {
        Objects.requireNonNull(member, "member");
        Objects.requireNonNull(form, "form");

        if (form == MemberSelector.Form.DESCRIPTOR) {
            return member.describe();
        }
        if (member.kind() == MemberRef.Kind.FIELD) {
            return member.name() + ':' + sourceName(member.fieldType());
        }
        final StringJoinerLite joiner = new StringJoinerLite();
        member.methodType().parameterList().forEach(p -> joiner.add(sourceName(p)));
        return member.name() + '(' + joiner + "):" + sourceName(member.methodType().returnType());
    }

    /**
     * Returns the name the ordering is measured against.
     *
     * <p>A constant selector has no member name, so its keyword stands in and the candidates end up
     * ordered by their distance from a word no member is called.
     *
     * @param selector the selector that was written; must not be {@code null}
     * @return the name, or the constant selector's keyword
     */
    private static String simpleName(@NotNull final MemberSelector selector) {
        return switch (selector) {
            case de.splatgames.aether.weaver.api.select.MethodSelector m -> m.name();
            case de.splatgames.aether.weaver.api.select.FieldSelector f -> f.name();
            case de.splatgames.aether.weaver.api.select.ConstantSelector c -> c.kind().keyword();
        };
    }

    /**
     * Renders a type the way a weave author writes it in a selector.
     *
     * <p>Arrays become {@code []} suffixes and a primitive keeps its keyword; a class is qualified,
     * so that two candidates whose simple names agree can be told apart.
     *
     * @param type the type to render; must not be {@code null}
     * @return the source spelling
     */
    private static String sourceName(@NotNull final java.lang.constant.ClassDesc type) {
        if (type.isArray()) {
            return sourceName(type.componentType()) + "[]";
        }
        if (type.isPrimitive()) {
            return type.displayName();
        }
        final String packageName = type.packageName();
        return packageName.isEmpty() ? type.displayName() : packageName + '.' + type.displayName();
    }

    /**
     * Returns the Levenshtein distance between two names, ignoring case.
     *
     * <p>Case is folded because a name differing only in case is a typo of exactly the kind this
     * ordering exists to surface. Only two rows of the matrix are kept, since the caller needs the
     * final number and not the edit script.
     *
     * @param a the name that was written; must not be {@code null}
     * @param b the candidate's name; must not be {@code null}
     * @return the number of single-character edits between the two, {@code 0} when they differ only
     *         in case
     */
    static int editDistance(@NotNull final String a, @NotNull final String b) {
        final String left = a.toLowerCase(java.util.Locale.ROOT);
        final String right = b.toLowerCase(java.util.Locale.ROOT);
        if (left.equals(right)) {
            return 0;
        }
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                final int substitution = previous[j - 1]
                        + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), substitution);
            }
            final int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    /**
     * Joins rendered parameter types with {@code , } into one buffer.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private static final class StringJoinerLite {

        /** The joined text so far. */
        private final StringBuilder sb = new StringBuilder(32);

        /**
         * Appends a value, preceded by a separator unless it is the first.
         *
         * @param value the value to append; must not be {@code null}
         */
        void add(@NotNull final String value) {
            if (!this.sb.isEmpty()) {
                this.sb.append(", ");
            }
            this.sb.append(value);
        }

        /**
         * Returns the joined text.
         *
         * @return the values joined by {@code , }, empty when none were added
         */
        @Override
        public String toString() {
            return this.sb.toString();
        }
    }
}
