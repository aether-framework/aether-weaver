package de.splatgames.aether.weaver.idea.bytecode;

import de.splatgames.aether.weaver.api.spi.CodeView;
import de.splatgames.aether.weaver.api.spi.MethodView;
import de.splatgames.aether.weaver.engine.internal.transform.LocalTable;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.lang.constant.ClassDesc;
import java.util.ArrayList;
import java.util.List;

/**
 * Reports the local variables a handler could capture at a set of injection sites.
 *
 * <p>What this offers is what {@code @Local} can bind to by name, so the answer has to hold at
 * every site a declaration matches rather than at one of them: a name offered for a declaration
 * that matches three positions and is live at two of them would generate an annotation that fails
 * where the author cannot see it. Everything here is therefore an intersection over the sites.
 *
 * <p>Names come from the local variable table, which a class compiled without {@code -g} does not
 * carry. {@link #isAvailable(MethodView)} distinguishes that case from a method that genuinely has
 * nothing to capture, because the two need different things said to the author.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class TargetLocals {

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private TargetLocals() {
        throw new AssertionError("no instances");
    }

    /**
     * One local variable that can be captured, as it would be written into a handler.
     *
     * @param name the variable's name as the local variable table records it, which is what
     *             {@code @Local} binds by
     * @param type the variable's declared type
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public record Capture(@NotNull String name, @NotNull ClassDesc type) {
    }

    /**
     * Reports whether the method carries a local variable table at all.
     *
     * @param method the compiled method; must not be {@code null}
     * @return {@code true} when names can be read from the method, {@code false} for a method with
     *         no code and for one compiled without {@code -g}
     * @throws NullPointerException if {@code method} is {@code null}
     */
    @Contract(pure = true)
    public static boolean isAvailable(@NotNull final MethodView method) {
        return tableOf(method).isAvailable();
    }

    /**
     * Reports the variables capturable at every one of the given sites.
     *
     * <p>The receiver and the parameters are excluded: they occupy the slots below the first the
     * body can declare into, and a handler reaches them through its own parameters rather than
     * through a capture. What remains is offered only when it is live at every site and when the
     * name resolves back to that same slot at every site, which drops a name reused by two
     * variables in disjoint scopes rather than binding it to whichever one the first site saw.
     *
     * @param method the compiled method; must not be {@code null}
     * @param sites  the element indices a declaration matches; must not be {@code null}
     * @return the variables live at all of them, in the order the table records them; empty when
     *         the method has no local variable table, when {@code sites} is empty, or when nothing
     *         is live at all of them
     * @throws NullPointerException if {@code method} is {@code null}
     */
    @Unmodifiable
    @NotNull
    public static List<Capture> at(@NotNull final MethodView method,
                                   @NotNull final List<Integer> sites) {
        final LocalTable table = tableOf(method);
        if (!table.isAvailable() || sites.isEmpty()) {
            return List.of();
        }

        final int reserved = reservedSlots(method);
        final List<Capture> captures = new ArrayList<>();
        for (final LocalTable.LocalSlot slot : table.slots()) {
            if (slot.slot() < reserved || !isLiveAtAll(table, slot, sites)) {
                continue;
            }
            captures.add(new Capture(slot.name(), slot.type()));
        }
        return List.copyOf(captures);
    }

    /**
     * Reports whether one slot is the one its name resolves to at every site.
     *
     * <p>Liveness alone is not enough. {@code @Local} binds by name, so the check is that
     * {@link LocalTable#byName(String, int)} answers this very slot at each site; a second variable
     * of the same name, live in a scope this one is not, would otherwise be captured under a name
     * that names it just as well.
     *
     * @param table the method's local variable table; must not be {@code null}
     * @param slot  the slot to test; must not be {@code null}
     * @param sites the element indices a declaration matches; must not be {@code null}
     * @return {@code true} when the slot is live and unambiguously named at every site
     */
    private static boolean isLiveAtAll(@NotNull final LocalTable table,
                                       @NotNull final LocalTable.LocalSlot slot,
                                       @NotNull final List<Integer> sites) {
        for (final int site : sites) {
            if (!slot.isLiveAt(site)
                    || !table.byName(slot.name(), site).filter(slot::equals).isPresent()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reports the first slot the method's body can declare a variable into.
     *
     * <p>Counted from the signature rather than read from the table, because the receiver and the
     * parameters are recorded there like any other variable and nothing in an entry says which it
     * is. A {@code long} or a {@code double} parameter takes two slots, which is why this is a sum
     * over the parameter types rather than a count of them.
     *
     * @param method the compiled method; must not be {@code null}
     * @return the number of slots occupied by the receiver and the parameters
     */
    private static int reservedSlots(@NotNull final MethodView method) {
        int slots = method.isStatic() ? 0 : 1;
        for (int index = 0; index < method.type().parameterCount(); index++) {
            final ClassDesc parameter = method.type().parameterType(index);
            slots += parameter.descriptorString().equals("J")
                    || parameter.descriptorString().equals("D") ? 2 : 1;
        }
        return slots;
    }

    /**
     * Reads the method's local variable table.
     *
     * @param method the compiled method; must not be {@code null}
     * @return the table, or {@link LocalTable#empty()} when the method has no code, which is
     *         indistinguishable here from a body compiled without {@code -g}
     */
    @NotNull
    private static LocalTable tableOf(@NotNull final MethodView method) {
        final CodeView code = method.code().orElse(null);
        return code == null ? LocalTable.empty() : LocalTable.of(code.elements());
    }
}
