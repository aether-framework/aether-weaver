package de.splatgames.aether.weaver.engine.internal.transform;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.Label;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.CharacterRange;
import java.lang.classfile.instruction.ExceptionCatch;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LocalVariable;
import java.lang.classfile.instruction.LocalVariableType;
import java.lang.classfile.instruction.LookupSwitchInstruction;
import java.lang.classfile.instruction.SwitchCase;
import java.lang.classfile.instruction.TableSwitchInstruction;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Rewrites a body so that every label in it belongs to the builder receiving it.
 *
 * <p>A {@link Label} belongs to the code that created it. Copying a body's elements into another
 * method carries the original's labels along, and offering a label to the wrong builder fails with
 * {@code IllegalStateException: Unexpected label context} — measured on Temurin 25 by driving two
 * method bodies through one relabeler. Copying one body into two methods is the case that makes
 * this unavoidable: both copies would otherwise share a single set of labels.
 *
 * <p>The mapping is therefore per body, and every label-bearing element is rebuilt around a fresh
 * label rather than forwarded. New labels are handed out lazily, so a branch met before its target
 * creates the label the eventual {@code LabelTarget} then binds.
 *
 * <h2>Thread safety</h2>
 *
 * <p>Not safe to share. One instance holds one body's mapping in a plain {@link HashMap}, and
 * {@link #transform()} exists so that a caller can hold a reusable transform without holding an
 * instance.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class CodeRelabeler implements CodeTransform {

    /** The original label to its replacement in {@link #builder}, filled in as labels are met. */
    private final Map<Label, Label> mapping = new HashMap<>();

    /** The builder whose labels the replacements belong to. */
    private final CodeBuilder builder;

    /**
     * Binds this relabeler to one builder.
     *
     * @param builder the builder every new label comes from; must not be {@code null}
     * @throws NullPointerException if {@code builder} is {@code null}
     */
    private CodeRelabeler(@NotNull final CodeBuilder builder) {
        this.builder = Objects.requireNonNull(builder, "builder");
    }

    /**
     * Returns a relabeler for one body.
     *
     * <p>Valid for exactly the body {@code builder} is building; a second body needs a second
     * relabeler.
     *
     * @param builder the builder every new label comes from; must not be {@code null}
     * @return a relabeler with an empty mapping
     * @throws NullPointerException if {@code builder} is {@code null}
     */
    @Contract(value = "_ -> new", pure = true)
    @NotNull
    public static CodeRelabeler of(@NotNull final CodeBuilder builder) {
        return new CodeRelabeler(builder);
    }

    /**
     * Returns a transform that relabels each body it is applied to independently.
     *
     * <p>Built on {@link CodeTransform#ofStateful(java.util.function.Supplier)}, whose supplier is
     * invoked once per traversal, so the returned transform may be held and applied to any number
     * of bodies: each gets its own relabeler and its own mapping.
     *
     * @return a reusable transform
     */
    @Contract(value = " -> new", pure = true)
    @NotNull
    public static CodeTransform transform() {
        return CodeTransform.ofStateful(() -> new CodeTransform() {

            /** The relabeler for the body being traversed; created in {@link #atStart}. */
            private CodeRelabeler delegate;

            /**
             * Creates the relabeler for this body.
             *
             * <p>The builder is not known until the traversal begins, and a relabeler cannot be
             * created without one, which is why the delegate is not a field of the supplier's
             * result at construction.
             *
             * @param builder the builder for the body about to be traversed
             */
            @Override
            public void atStart(@NotNull final CodeBuilder builder) {
                this.delegate = CodeRelabeler.of(builder);
            }

            /**
             * Passes the element to this body's relabeler.
             *
             * @param builder the builder receiving the rewritten element
             * @param element the element to rewrite
             */
            @Override
            public void accept(@NotNull final CodeBuilder builder, @NotNull final CodeElement element) {
                this.delegate.accept(builder, element);
            }
        });
    }

    /**
     * Returns this body's replacement for one original label, creating it on first sight.
     *
     * <p>Idempotent: the same original always yields the same replacement, which is what makes a
     * branch and the {@code LabelTarget} it aims at agree without either knowing about the other.
     *
     * @param original the label from the body being copied; must not be {@code null}
     * @return the corresponding label in this relabeler's builder
     * @throws NullPointerException if {@code original} is {@code null}
     */
    @NotNull
    public Label map(@NotNull final Label original) {
        Objects.requireNonNull(original, "original");
        return this.mapping.computeIfAbsent(original, ignored -> this.builder.newLabel());
    }

    /**
     * Rewrites one element, replacing every label it names.
     *
     * <p>The cases are every element kind that carries a label, including the ones that carry no
     * instruction: an exception handler's three labels, the two debug scopes and a character range.
     * A kind left out forwards a label belonging to the body being copied from, into a body that
     * does not own it.
     *
     * <p>The replacement labels come from the builder this relabeler was created with, while the
     * elements go to {@code cb}. The two are the same builder for the body the relabeler belongs
     * to, and that is the whole of why one relabeler cannot serve two.
     *
     * @param cb      the builder receiving the rewritten element; must not be {@code null}
     * @param element the element to rewrite; must not be {@code null}
     */
    @Override
    public void accept(@NotNull final CodeBuilder cb, @NotNull final CodeElement element) {
        switch (element) {
            case LabelTarget target -> cb.labelBinding(map(target.label()));

            case BranchInstruction branch ->
                    cb.branch(branch.opcode(), map(branch.target()));

            case LookupSwitchInstruction lookup -> cb.lookupswitch(
                    map(lookup.defaultTarget()), mapCases(lookup.cases()));

            case TableSwitchInstruction table -> cb.tableswitch(
                    table.lowValue(), table.highValue(),
                    map(table.defaultTarget()), mapCases(table.cases()));

            case ExceptionCatch handler -> cb.exceptionCatch(
                    map(handler.tryStart()), map(handler.tryEnd()),
                    map(handler.handler()), handler.catchType());

            case LocalVariable variable -> cb.localVariable(
                    variable.slot(), variable.name(), variable.type(),
                    map(variable.startScope()), map(variable.endScope()));

            case LocalVariableType variable -> cb.localVariableType(
                    variable.slot(), variable.name(), variable.signature(),
                    map(variable.startScope()), map(variable.endScope()));

            case CharacterRange range -> cb.characterRange(
                    map(range.startScope()), map(range.endScope()),
                    range.characterRangeStart(), range.characterRangeEnd(), range.flags());

            // Everything else carries no label. A default that forwards rather than throws keeps
            // this forward-compatible with instruction categories a later JDK may add.
            default -> cb.with(element);
        }
    }

    /**
     * Returns the switch cases with their targets replaced.
     *
     * <p>Rebuilt rather than mutated: a {@link SwitchCase} pairs a value with a label and has no
     * setter, so the case value is carried over and a new pair is made around the mapped label.
     *
     * @param cases the original cases; must not be {@code null}
     * @return cases with the same values and this body's labels
     */
    private java.util.List<SwitchCase> mapCases(@NotNull final java.util.List<SwitchCase> cases) {
        return cases.stream()
                .map(switchCase -> SwitchCase.of(switchCase.caseValue(), map(switchCase.target())))
                .toList();
    }
}
