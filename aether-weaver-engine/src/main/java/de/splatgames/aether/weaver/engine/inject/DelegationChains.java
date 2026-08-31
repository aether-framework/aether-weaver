package de.splatgames.aether.weaver.engine.inject;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import de.splatgames.aether.weaver.api.spi.CodeView;
import de.splatgames.aether.weaver.api.spi.MethodView;
import de.splatgames.aether.weaver.api.spi.Reporter;
import de.splatgames.aether.weaver.api.spi.TargetView;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.classfile.CodeElement;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Warns a weave that attached to two constructors of one class where one of them calls the other.
 *
 * <p>A single {@code new} runs every constructor in a {@code this(...)} chain, so a handler attached
 * to two links of one chain is called twice for one object. Nothing about that is visible from
 * either declaration, and nothing about it is wrong for a handler that observes, which is why the
 * result is {@code AW1027} at warning severity rather than a refusal.
 *
 * <p>Detection is per weave class rather than per declaration. Two weaves that each attach to one
 * link are two separate handlers, each called once per object, and reporting that would be noise.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
final class DelegationChains {

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private DelegationChains() {
        throw new AssertionError("no instances");
    }

    /**
     * Reports every weave whose attached constructors form a chain.
     *
     * <p>Called once every declaration has been resolved against the target and before anything is
     * emitted, because the two declarations that make up a chain never see each other: each is
     * resolved and bound on its own, and only the accumulated map of weave class to attached
     * constructors shows the overlap.
     *
     * <p>Attachment is compared by constructor descriptor, so two declarations of one weave on the
     * same constructor collapse to one and are not a chain.
     *
     * @param target   the class being woven; must not be {@code null}
     * @param byWeave  the constructors each weave class attached to, keyed by weave class name;
     *                 must not be {@code null}
     * @param reporter where to report; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    static void report(@NotNull final TargetView target,
                       @NotNull final Map<String, List<MethodView>> byWeave,
                       @NotNull final Reporter reporter) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(byWeave, "byWeave");
        Objects.requireNonNull(reporter, "reporter");

        final Map<MethodTypeDesc, MethodTypeDesc> delegation = delegationOf(target);
        if (delegation.isEmpty()) {
            return;
        }
        for (final Map.Entry<String, List<MethodView>> weave : byWeave.entrySet()) {
            final Set<MethodTypeDesc> attached = new LinkedHashSet<>();
            for (final MethodView constructor : weave.getValue()) {
                attached.add(constructor.type());
            }
            if (attached.size() < 2) {
                // An early-out, not a guard: one constructor cannot reach itself by delegation, so
                // the walk below would answer "no chain" anyway. Said here because a reader — or a
                // counter-probe — will otherwise expect removing it to change an answer.
                continue;
            }
            final List<String> chained = chainedWithin(attached, delegation);
            if (!chained.isEmpty()) {
                reporter.report(Diagnostic.builder(DiagnosticCode.CONSTRUCTOR_DELEGATION_CHAIN)
                        .message(weave.getKey() + " attaches to " + attached.size()
                                + " constructors of " + target.binaryName()
                                + ", and one of them calls another")
                        .details(chained)
                        .detail("a single `new` runs every constructor in the chain, so the "
                                + "handler is called once for each of them")
                        .remedy("this is right for a handler that observes and wrong for one that "
                                + "counts, allocates or validates. Attach to the constructor the "
                                + "chain ends at — the one that calls super() rather than this() — "
                                + "if the handler should run once per object")
                        .build());
            }
        }
    }

    /**
     * Builds the target's {@code this(...)} graph, one edge per constructor.
     *
     * <p>Every constructor of the class is walked, not only the attached ones, because a chain may
     * pass through a constructor no weave named on its way from one attached constructor to another.
     *
     * @param target the class being woven; must not be {@code null}
     * @return the constructor descriptors that delegate, each mapped to what it delegates to
     */
    @Contract(pure = true)
    @NotNull
    private static Map<MethodTypeDesc, MethodTypeDesc> delegationOf(
            @NotNull final TargetView target) {
        final Map<MethodTypeDesc, MethodTypeDesc> delegation = new LinkedHashMap<>();
        for (final MethodView method : target.methods()) {
            if (!ConstantDescs.INIT_NAME.equals(method.name())) {
                continue;
            }
            final MethodTypeDesc delegate = delegateOf(method, target.internalName());
            if (delegate != null) {
                delegation.put(method.type(), delegate);
            }
        }
        return delegation;
    }

    /**
     * Finds the constructor a constructor delegates to, if it delegates at all.
     *
     * <p>A constructor body opens with either {@code this(...)} or {@code super(...)}, both of which
     * are an {@code invokespecial} of {@code <init>}, and the two are told apart by the owner alone.
     * Finding the right one is not simply finding the first: an argument to that call may itself be
     * a {@code new}, whose own {@code <init>} is invoked earlier in the instruction stream. The depth
     * counter is what discounts those — every {@code new} raises it and the next {@code <init>}
     * lowers it again, so the first invocation reached at depth zero is the delegation.
     *
     * @param constructor  the constructor to examine; must not be {@code null}
     * @param internalName the internal name of the class being woven, which distinguishes
     *                     {@code this(...)} from {@code super(...)}; must not be {@code null}
     * @return the descriptor of the constructor it delegates to, or {@code null} when it calls a
     *         superclass constructor, carries no body, or reaches no such call at all
     */
    @Contract(pure = true)
    @Nullable
    private static MethodTypeDesc delegateOf(@NotNull final MethodView constructor,
                                             @NotNull final String internalName) {
        final CodeView body = constructor.code().orElse(null);
        if (body == null) {
            return null;
        }
        final List<CodeElement> elements = body.elements();
        int depth = 0;
        for (final CodeElement element : elements) {
            if (element instanceof NewObjectInstruction) {
                depth++;
            } else if (element instanceof final InvokeInstruction invoke
                    && invoke.opcode() == Opcode.INVOKESPECIAL
                    && ConstantDescs.INIT_NAME.equals(invoke.name().stringValue())) {
                if (depth > 0) {
                    depth--;
                    continue;
                }
                return internalName.equals(invoke.owner().asInternalName())
                        ? invoke.typeSymbol()
                        : null;
            }
        }
        return null;
    }

    /**
     * Describes each attached constructor that reaches another attached one.
     *
     * <p>The walk follows the graph rather than a single edge, so a chain that passes through a
     * constructor the weave did not attach to is still found, and the description says whether the
     * two are joined directly or through the chain. It stops at the first attached constructor
     * reached from a given start: the point is that the handler runs more than once, and listing the
     * rest of the chain would not add to it.
     *
     * <p>The set of walked descriptors is a termination guard, not bookkeeping. Java source cannot
     * express a cycle of {@code this(...)} calls, but the input here is a class file that need not
     * have come from a compiler, and a cycle would otherwise loop forever.
     *
     * @param attached   the constructor descriptors one weave attached to; must not be {@code null}
     * @param delegation the target's delegation graph; must not be {@code null}
     * @return one detail line per attached constructor that reaches another, empty when none does
     */
    @Contract(pure = true)
    @NotNull
    private static List<String> chainedWithin(
            @NotNull final Set<MethodTypeDesc> attached,
            @NotNull final Map<MethodTypeDesc, MethodTypeDesc> delegation) {
        final List<String> chained = new ArrayList<>();
        for (final MethodTypeDesc from : attached) {
            final Set<MethodTypeDesc> walked = new LinkedHashSet<>();
            MethodTypeDesc next = delegation.get(from);
            while (next != null && walked.add(next)) {
                if (attached.contains(next)) {
                    chained.add("  <init>" + from.displayDescriptor()
                            + " reaches <init>" + next.displayDescriptor()
                            + (delegation.get(from) == next ? " directly" : " through the chain"));
                    break;
                }
                next = delegation.get(next);
            }
        }
        return List.copyOf(chained);
    }
}
