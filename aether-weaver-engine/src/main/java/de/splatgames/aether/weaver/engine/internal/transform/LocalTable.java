package de.splatgames.aether.weaver.engine.internal.transform;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.Label;
import java.lang.classfile.TypeKind;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LocalVariable;
import java.lang.constant.ClassDesc;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The {@code LocalVariable} debug entries of one body, indexed by element position rather than by
 * bytecode offset.
 *
 * <p>A scope in the class file is a pair of labels, and a caller asking "what is in slot 3 here"
 * holds an index into the element list. The two are reconciled once, when the table is built, so
 * every lookup afterwards is a comparison of two integers.
 *
 * <p>Debug information is optional, which is the difference between this and any structural view of
 * a body: a class compiled without {@code -g} has no entries at all, {@link #isAvailable()} is
 * {@code false}, and every lookup answers empty. That is the intended answer. Nothing here
 * reconstructs a name or a type from the instructions, because the wrong slot named confidently is
 * worse than no slot named.
 *
 * <p>Only the slot an entry names is recorded. A {@code long} or a {@code double} occupies two
 * slots, and javac — measured on JDK 25 — emits one entry for it, naming the lower of the two, so a
 * lookup for the upper half of a wide local finds nothing and one for the lower half finds the
 * whole variable.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class LocalTable {

    /** The answer for a body with no usable debug information; shared, since it holds nothing. */
    private static final LocalTable EMPTY = new LocalTable(List.of(), false);

    /** The entries, in the order the body declares them. */
    private final List<LocalSlot> slots;

    /** Whether at least one entry could be placed; {@code false} for {@link #EMPTY} alone. */
    private final boolean available;

    /**
     * Stores the entries.
     *
     * @param slots     the entries; must not be {@code null}
     * @param available whether the body carried usable debug information
     */
    private LocalTable(@NotNull final List<LocalSlot> slots, final boolean available) {
        this.slots = List.copyOf(slots);
        this.available = available;
    }

    /**
     * Builds the table for a parsed body.
     *
     * <p>A body with no placeable entry answers the shared instance {@link #empty()} also returns,
     * rather than a fresh one built for this call.
     *
     * @param code the body to read; must not be {@code null}
     * @return the table, empty and unavailable when the body carries no placeable entry
     * @throws NullPointerException if {@code code} is {@code null}
     */
    @Contract(value = "_ -> new", pure = true)
    @NotNull
    public static LocalTable of(@NotNull final CodeModel code) {
        return of(Objects.requireNonNull(code, "code").elementList());
    }

    /**
     * Builds the table from a body's elements.
     *
     * <p>Runs in two passes because the elements are not ordered the way the scopes are: measured
     * on JDK 25, the parsed element list puts every {@code LocalVariable} ahead of the first label,
     * so a single pass would meet each entry before the labels bounding it. The first pass records
     * where every label is bound and the second turns each entry's pair of labels into a pair of
     * indices.
     *
     * <p>An entry naming a label that is not bound in this list is dropped rather than given a
     * guessed range, which turns a lookup for it into an empty answer instead of a wrong slot.
     *
     * <p>When no entry could be placed, the shared instance {@link #empty()} also returns is
     * answered instead of a fresh one.
     *
     * @param elements the body's elements, in body order; must not be {@code null}
     * @return the table, empty and unavailable when no entry could be placed
     * @throws NullPointerException if {@code elements} is {@code null}
     */
    @Contract(value = "_ -> new", pure = true)
    @NotNull
    public static LocalTable of(@NotNull final List<CodeElement> elements) {
        Objects.requireNonNull(elements, "elements");

        // Two passes, and they cannot be merged: javac emits every LocalVariable pseudo-element at
        // the very front of the body, before the labels their scopes refer to have been seen.
        final Map<Label, Integer> positions = new IdentityHashMap<>();
        for (int i = 0; i < elements.size(); i++) {
            if (elements.get(i) instanceof LabelTarget target) {
                positions.put(target.label(), i);
            }
        }

        final List<LocalSlot> slots = new ArrayList<>();
        for (final CodeElement element : elements) {
            if (!(element instanceof LocalVariable variable)) {
                continue;
            }
            final Integer start = positions.get(variable.startScope());
            final Integer end = positions.get(variable.endScope());
            if (start == null || end == null) {
                // A scope this table cannot place is a scope it must not answer questions about.
                // Dropping the entry makes every lookup for it fail loudly; keeping it with a
                // guessed range is the silent-wrong-slot outcome.
                continue;
            }
            slots.add(new LocalSlot(
                    variable.slot(),
                    variable.name().stringValue(),
                    ClassDesc.ofDescriptor(variable.type().stringValue()),
                    start,
                    end));
        }
        return slots.isEmpty() ? EMPTY : new LocalTable(slots, true);
    }

    /**
     * Returns the table for a body whose debug information was not read at all.
     *
     * @return a table with no entries, whose {@link #isAvailable()} is {@code false}
     */
    @Contract(pure = true)
    @NotNull
    public static LocalTable empty() {
        return EMPTY;
    }

    /**
     * Reports whether the body carried debug information this table could place.
     *
     * <p>The distinction a caller needs before it reports a local by name: {@code false} means the
     * question cannot be answered here, not that the variable is absent.
     *
     * @return {@code true} when the table holds at least one entry
     */
    @Contract(pure = true)
    public boolean isAvailable() {
        return this.available;
    }

    /**
     * Returns every entry, in the order the body declares them.
     *
     * <p>Declaration order, not slot order — the two differ, and the queries that need slot order
     * sort for it.
     *
     * @return the entries
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    public List<LocalSlot> slots() {
        return this.slots;
    }

    /**
     * Returns the variable of that name live at that position.
     *
     * <p>A name may be declared more than once in one body, in scopes that do not overlap; the
     * position is what tells them apart, and the first entry live there wins.
     *
     * @param name  the variable name as the debug information spells it; must not be {@code null}
     * @param index the position in the element list to ask about
     * @return the entry, or empty when no variable of that name is live there
     * @throws NullPointerException if {@code name} is {@code null}
     */
    @Contract(pure = true)
    @NotNull
    public Optional<LocalSlot> byName(@NotNull final String name, final int index) {
        Objects.requireNonNull(name, "name");
        return this.slots.stream()
                .filter(slot -> slot.name().equals(name) && slot.isLiveAt(index))
                .findFirst();
    }

    /**
     * Returns the variable occupying that slot at that position.
     *
     * <p>Empty for a slot the debug information does not describe: one the compiler reused for a
     * variable whose scope has ended, the upper half of a wide pair, and every slot of a body
     * compiled without debug information. A caller that has a slot number from elsewhere therefore
     * cannot read an empty answer as "the slot does not exist".
     *
     * @param slot  the slot number as the body numbers it
     * @param index the position in the element list to ask about
     * @return the entry, or empty when no variable is recorded as live in that slot there
     */
    @Contract(pure = true)
    @NotNull
    public Optional<LocalSlot> bySlot(final int slot, final int index) {
        return this.slots.stream()
                .filter(candidate -> candidate.slot() == slot && candidate.isLiveAt(index))
                .findFirst();
    }

    /**
     * Returns every variable of exactly that type live at that position, in slot order.
     *
     * <p>The comparison is descriptor equality and nothing else: a subtype does not match its
     * supertype, and {@code java.lang.Object} matches only a local declared as {@code Object}. A
     * caller offering this as a way to find "the" local of a type gets a list precisely because the
     * answer is often not unique, and slot order is what makes the ambiguity reportable in a stable
     * order.
     *
     * @param type  the descriptor to match; must not be {@code null}
     * @param index the position in the element list to ask about
     * @return the matching entries, ordered by slot, or an empty list when none matches
     * @throws NullPointerException if {@code type} is {@code null}
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    public List<LocalSlot> byType(@NotNull final ClassDesc type, final int index) {
        Objects.requireNonNull(type, "type");
        return this.slots.stream()
                .filter(slot -> slot.type().equals(type) && slot.isLiveAt(index))
                .sorted(Comparator.comparingInt(LocalSlot::slot))
                .toList();
    }

    /**
     * Returns a rendering of everything live at that position, for a diagnostic to list.
     *
     * <p>Each entry reads {@code name:Type} with the type's display name rather than its
     * descriptor, and the list is in slot order so that two runs against the same body produce the
     * same listing.
     *
     * @param index the position in the element list to ask about
     * @return the renderings, ordered by slot, empty when nothing is live there or the table is
     *         unavailable
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    public List<String> namesLiveAt(final int index) {
        return this.slots.stream()
                .filter(slot -> slot.isLiveAt(index))
                .sorted(Comparator.comparingInt(LocalSlot::slot))
                .map(slot -> slot.name() + ':' + slot.type().displayName())
                .toList();
    }

    /**
     * Returns whether the table is available and how many entries it holds.
     *
     * @return a description naming both, without listing the entries
     */
    @Override
    public String toString() {
        return "LocalTable[available=" + this.available + ", slots=" + this.slots.size() + ']';
    }

    /**
     * One {@code LocalVariable} entry with its scope resolved to element indices.
     *
     * @param slot       the slot the variable occupies, as the body numbers it
     * @param name       the variable's name from the debug information
     * @param type       the variable's declared type, erased, as a descriptor
     * @param startIndex the first element index at which the variable is live
     * @param endIndex   the first element index at which it is no longer live
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public record LocalSlot(int slot, String name, ClassDesc type, int startIndex, int endIndex) {

        /**
         * Rejects a null name or type; the indices are not checked against any body.
         *
         * @throws NullPointerException if {@code name} or {@code type} is {@code null}
         */
        public LocalSlot {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
        }

        /**
         * Reports whether the variable is live at that position.
         *
         * <p>Half-open: live at {@link #startIndex()}, not live at {@link #endIndex()}. The end
         * index is where the scope's closing label is bound, and a variable is out of scope there.
         *
         * @param index the position in the element list to ask about
         * @return {@code true} when the position is inside the scope
         */
        @Contract(pure = true)
        public boolean isLiveAt(final int index) {
            return index >= this.startIndex && index < this.endIndex;
        }

        /**
         * Returns the kind that decides which load and store instruction reaches this slot.
         *
         * @return the type kind of {@link #type()}
         */
        @Contract(pure = true)
        @NotNull
        public TypeKind typeKind() {
            return TypeKind.from(this.type);
        }

        /**
         * Compares every component.
         *
         * @param o the object to compare with
         * @return {@code true} when {@code o} is a slot with the same number, name, type and scope
         */
        @Override
        public boolean equals(final @Nullable Object o) {
            return o instanceof LocalSlot other
                    && this.slot == other.slot
                    && this.startIndex == other.startIndex
                    && this.endIndex == other.endIndex
                    && this.name.equals(other.name)
                    && this.type.equals(other.type);
        }

        /**
         * Hashes the same components {@link #equals(Object)} compares.
         *
         * @return the hash of the slot, name, type and scope
         */
        @Override
        public int hashCode() {
            return Objects.hash(this.slot, this.name, this.type, this.startIndex, this.endIndex);
        }

        /**
         * Returns the slot, the variable and its scope, as a diagnostic lists a candidate.
         *
         * <p>The scope is written {@code [start..end)} because the end is exclusive.
         *
         * @return a description naming the slot, the name, the type's display name and the scope
         */
        @Override
        public String toString() {
            return "slot " + this.slot + ' ' + this.name + ':' + this.type.displayName()
                    + " [" + this.startIndex + ".." + this.endIndex + ')';
        }
    }
}
