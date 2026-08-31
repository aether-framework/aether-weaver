package de.splatgames.aether.weaver.engine.inject.point;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import de.splatgames.aether.weaver.api.model.InjectorKind;
import de.splatgames.aether.weaver.api.model.InjectorSpec;
import de.splatgames.aether.weaver.api.model.PointSpec;
import de.splatgames.aether.weaver.api.spi.MethodView;
import de.splatgames.aether.weaver.api.spi.Reporter;
import de.splatgames.aether.weaver.api.spi.Site;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LookupSwitchInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.classfile.instruction.TableSwitchInstruction;
import java.lang.classfile.instruction.ThrowInstruction;
import java.lang.constant.ConstantDescs;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Drops the resolved positions that nothing may be injected at.
 *
 * <p>Locating a position and being able to emit at it are different questions, and the four steps
 * that find a site answer only the first. The three checks here answer the second, and each turns a
 * failure with no useful moment of discovery into a diagnostic. Two of them describe a class that
 * would be written successfully and refused afterwards: a constructor that calls an instance method
 * on {@code this} before the superclass constructor defines and resolves without error — measured on
 * Temurin 25 as {@code define: OK}, {@code resolveClass: OK} — and only fails, with a
 * {@link VerifyError}, at first active use ({@code new: java.lang.VerifyError}). HotSpot's message at
 * that point reads {@code Bad type on operand stack ... uninitializedThis is not assignable}; running
 * {@code ClassFile.verify} on the same bytes instead, independent of class loading, reports a
 * different message with no {@code uninitializedThis} in it at all: {@code Bad type on operand stack
 * in Bad1::<init>() @1 (Bad1 is not assignable from uninit@65535)}. The third describes a handler that
 * is woven, never runs, and is reported by nothing else at all.
 *
 * <p>A refused site is dropped from the list rather than turned into a failure of the whole
 * resolution. A declaration matching four calls of which one sits in an unreachable branch is
 * therefore woven three times and reports once, and the accounting a declaration's {@code require}
 * and {@code allow} is checked against sees the three.
 *
 * <h2>Which checks run</h2>
 *
 * <p>Only for {@code @Inject}. {@code @Redirect} and {@code @Wrap} take a different route entirely,
 * because the positions they can use are constrained differently: they stand in for an operation
 * and therefore need one, so a site on the far side of an instruction is refused as
 * {@code AW1061}. Any other injector kind passes through untouched.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
final class SiteSafety {

    /** The kinds that stand in for an operation, and so accept only a site on the operation. */
    private static final Set<InjectorKind> REPLACE_AN_OPERATION =
            Set.of(InjectorKind.REDIRECT, InjectorKind.WRAP);

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private SiteSafety() {
        throw new AssertionError("no instances");
    }

    /**
     * Returns the subset of the given sites that can be injected at, reporting each one dropped.
     *
     * <p>For {@code @Inject}, three checks run in this order and the first to refuse a site reports
     * it and moves on.
     *
     * <ul>
     *   <li><b>{@code AW1026}</b> — the site is at or before the constructor's own {@code super()}
     *       or {@code this()} call and the handler is not static. The comparison includes the
     *       initialiser's own index, since a site's index is the position code is emitted
     *       <em>before</em>. Only checked in a constructor, and only for a handler that needs a
     *       receiver.
     *   <li><b>{@code AW1105}</b> — the site is between a {@code new} and the constructor call that
     *       completes it, where the reference on the stack is to an object that does not exist yet.
     *   <li><b>{@code AW1130}</b> — nothing can reach the site. A warning by severity, but the site
     *       is dropped all the same: a handler that cannot run is not a handler that was woven.
     * </ul>
     *
     * @param sites    the resolved sites, in body order; must not be {@code null}
     * @param method   the target method; must not be {@code null}
     * @param elements the whole body, which the indices refer to; must not be {@code null}
     * @param injector the declaration being resolved; must not be {@code null}
     * @param spec     the point that produced the sites, named in the diagnostics; must not be
     *                 {@code null}
     * @param reporter where the diagnostics go; must not be {@code null}
     * @return the sites that survive, in the order they were given
     * @throws NullPointerException if any argument is {@code null}
     */
    @NotNull
    @Unmodifiable
    static List<Site> usable(@NotNull final List<Site> sites,
                             @NotNull final MethodView method,
                             @NotNull final List<CodeElement> elements,
                             @NotNull final InjectorSpec injector,
                             @NotNull final PointSpec spec,
                             @NotNull final Reporter reporter) {
        Objects.requireNonNull(sites, "sites");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(elements, "elements");
        Objects.requireNonNull(injector, "injector");
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(reporter, "reporter");

        if (REPLACE_AN_OPERATION.contains(injector.kind())) {
            return operationsOnly(sites, injector, spec, reporter);
        }
        if (!InjectorKind.INJECT.equals(injector.kind())) {
            return List.copyOf(sites);
        }
        final int initialiser = ConstantDescs.INIT_NAME.equals(method.name())
                ? initialiserCall(elements)
                : -1;
        final List<Site> usable = new ArrayList<>(sites.size());
        for (final Site site : sites) {
            // `<=`, not `<`. A site's index is the position code is emitted BEFORE, so a site at
            // the initialiser's own index puts the handler immediately before the super() call —
            // `this` has still not been initialised there. INVOKE_AFTER on the last call inside the
            // constructor's own argument list resolves to exactly that index and was let through.
            if (initialiser >= 0 && site.index() <= initialiser
                    && !injector.handler().isStatic()) {
                reporter.report(Diagnostic.builder(DiagnosticCode.THIS_UNAVAILABLE_BEFORE_SUPER_CALL)
                        .message(injector.handler().describe() + " needs `this`, and " + spec.point()
                                + " resolved to a position before the constructor's own super() "
                                + "call")
                        .detail("element " + site.index() + ": " + elements.get(site.index()))
                        .remedy("an instance handler is dissolved into the target and called on "
                                + "`this`, which does not exist until the superclass constructor "
                                + "has run — the JVM refuses to load a constructor that uses it "
                                + "earlier. Declare the handler static, or move the point after "
                                + "the super() call, which is where Point.HEAD already puts it")
                        .build());
                continue;
            }
            if (isUninitialised(elements, site.index())) {
                reporter.report(Diagnostic.builder(DiagnosticCode.SITE_IN_UNINITIALISED_WINDOW)
                        .message(injector.handler().describe() + " resolved " + spec.point()
                                + " to a position between a `new` and its constructor call")
                        .detail("element " + site.index() + ": " + elements.get(site.index()))
                        .remedy("the stack there holds a reference to an object that does not "
                                + "exist yet, and the JVM refuses code that touches it. Move the "
                                + "point after the constructor call — an ordinal, a slice, or "
                                + "INVOKE_AFTER on the constructor itself — or use @Redirect or "
                                + "@Wrap, which take the whole instantiation over")
                        .build());
                continue;
            }
            if (isDead(elements, site.index())) {
                reporter.report(Diagnostic.builder(DiagnosticCode.SITE_IN_DEAD_CODE)
                        .message(injector.handler().describe() + " resolved " + spec.point()
                                + " to an instruction nothing can reach")
                        .detail("element " + site.index() + ": " + elements.get(site.index()))
                        .remedy("a handler injected there would never run, and nothing else would "
                                + "say so. This is usually a selector matching a compiler-generated "
                                + "leftover rather than the code that was meant — narrow it with a "
                                + "slice or an ordinal")
                        .build());
                continue;
            }
            usable.add(site);
        }
        return List.copyOf(usable);
    }

    /**
     * Returns the sites that name an operation, reporting the rest as {@code AW1061}.
     *
     * <p>The test is the site's kind, not its position: {@code AFTER_ELEMENT} names the gap after
     * an instruction, and there is nothing in a gap to stand in for. Every other kind is kept here,
     * so a {@code @Redirect} at a position that names no operation at all is refused elsewhere
     * rather than by this method.
     *
     * @param sites    the resolved sites; must not be {@code null}
     * @param injector the declaration being resolved, named in the diagnostic; must not be
     *                 {@code null}
     * @param spec     the point that produced the sites; must not be {@code null}
     * @param reporter where the diagnostics go; must not be {@code null}
     * @return the sites whose kind is not {@code AFTER_ELEMENT}
     */
    @NotNull
    @Unmodifiable
    private static List<Site> operationsOnly(@NotNull final List<Site> sites,
                                             @NotNull final InjectorSpec injector,
                                             @NotNull final PointSpec spec,
                                             @NotNull final Reporter reporter) {
        final List<Site> usable = new ArrayList<>(sites.size());
        for (final Site site : sites) {
            if (site.kind() == Site.Kind.AFTER_ELEMENT) {
                reporter.report(Diagnostic.builder(DiagnosticCode.OPERATION_TARGET_UNSUPPORTED)
                        .message(injector.handler().describe() + " stands in for an operation, and "
                                + spec.point() + " resolved to the position after one rather than "
                                + "to the operation itself")
                        .detail("site at index " + site.index() + ", after "
                                + (site.hasElement() ? site.element() : "an unnamed element"))
                        .remedy("there is nothing at a position after an operation to stand in "
                                + "for — @Inject is what adds code there, and it is what "
                                + "INVOKE_AFTER exists for. A point that is meant to be "
                                + "redirected or wrapped returns its site as BEFORE_ELEMENT on "
                                + "the operation itself")
                        .build());
                continue;
            }
            usable.add(site);
        }
        return List.copyOf(usable);
    }

    /**
     * Returns the index of the constructor's own {@code super()} or {@code this()} call.
     *
     * <p>The first {@code invokespecial} of {@code <init>} is not necessarily it: an argument to
     * the call may itself construct an object, and that object's constructor call comes first. The
     * depth counter matches each {@code <init>} against a preceding {@code new}, so the one at
     * depth zero — the only one with no {@code new} of its own — is the delegation.
     *
     * @param elements the constructor's body; must not be {@code null}
     * @return the index of the delegating call, or {@code -1} when the body has none
     */
    @Contract(pure = true)
    private static int initialiserCall(@NotNull final List<CodeElement> elements) {
        int depth = 0;
        for (int index = 0; index < elements.size(); index++) {
            final CodeElement element = elements.get(index);
            if (element instanceof NewObjectInstruction) {
                depth++;
            } else if (element instanceof final InvokeInstruction invoke
                    && invoke.opcode() == Opcode.INVOKESPECIAL
                    && ConstantDescs.INIT_NAME.equals(invoke.name().stringValue())) {
                if (depth == 0) {
                    return index;
                }
                depth--;
            }
        }
        return -1;
    }

    /**
     * Reports whether a position lies inside an open {@code new}/{@code <init>} window.
     *
     * <p>Counts forward from the start of the body rather than looking at the neighbourhood,
     * because the window is a property of everything that came before: nested allocations open
     * several at once, and only a count says whether any is still open here.
     *
     * <p>The constructor call that closes a window counts as the window's edge rather than its
     * inside: a site on that instruction itself is not refused, though every position between the
     * {@code new} and it is.
     *
     * @param elements the body; must not be {@code null}
     * @param site     the position to judge
     * @return {@code true} when an allocation is still uncompleted at that position
     */
    @Contract(pure = true)
    private static boolean isUninitialised(@NotNull final List<CodeElement> elements,
                                           final int site) {
        int depth = 0;
        for (int index = 0; index < site && index < elements.size(); index++) {
            final CodeElement element = elements.get(index);
            if (element instanceof NewObjectInstruction) {
                depth++;
            } else if (element instanceof final InvokeInstruction invoke
                    && invoke.opcode() == Opcode.INVOKESPECIAL
                    && ConstantDescs.INIT_NAME.equals(invoke.name().stringValue())
                    && depth > 0) {
                depth--;
            }
        }
        if (depth == 0) {
            return false;
        }
        // The constructor call that closes the window is the window's edge, not its inside.
        return !(elements.get(site) instanceof final InvokeInstruction invoke
                && invoke.opcode() == Opcode.INVOKESPECIAL
                && ConstantDescs.INIT_NAME.equals(invoke.name().stringValue()));
    }

    /**
     * Reports whether nothing can reach a position.
     *
     * <p>Proves unreachability rather than estimating it, and gives up as soon as it cannot: the
     * scan walks back to the nearest instruction and answers {@code true} only if that instruction
     * transfers control away unconditionally. A label bound between the two ends the scan with
     * {@code false}, because something can aim there and this method has no way to know whether
     * anything does. The start of the body is reachable by definition.
     *
     * <p>The consequence is that a run of dead instructions is reported at its first instruction
     * and not afterwards, since a later one is preceded by an ordinary instruction. Refusing more
     * than that would need a reachability pass over the whole body, and being wrong in that
     * direction refuses code that works.
     *
     * @param elements the body; must not be {@code null}
     * @param site     the position to judge
     * @return {@code true} when the position is provably unreachable
     */
    @Contract(pure = true)
    private static boolean isDead(@NotNull final List<CodeElement> elements, final int site) {
        for (int index = site - 1; index >= 0; index--) {
            final CodeElement element = elements.get(index);
            if (element instanceof LabelTarget) {
                // Something can aim here, so this is as far back as the question can be answered.
                return false;
            }
            if (element instanceof final Instruction instruction) {
                return transfersUnconditionally(instruction);
            }
        }
        // The start of the method is always reached.
        return false;
    }

    /**
     * Reports whether an instruction never falls through to the next one.
     *
     * <p>A return, a throw and either switch always transfer; a branch does only when it is a
     * {@code goto} or {@code goto_w}, since every other branch opcode is conditional and its
     * fall-through side is reachable.
     *
     * @param instruction the instruction to judge; must not be {@code null}
     * @return {@code true} when control cannot continue past it
     */
    @Contract(pure = true)
    private static boolean transfersUnconditionally(@NotNull final Instruction instruction) {
        if (instruction instanceof ReturnInstruction
                || instruction instanceof ThrowInstruction
                || instruction instanceof TableSwitchInstruction
                || instruction instanceof LookupSwitchInstruction) {
            return true;
        }
        return instruction instanceof final BranchInstruction branch
                && (branch.opcode() == Opcode.GOTO || branch.opcode() == Opcode.GOTO_W);
    }

}
