package de.splatgames.aether.weaver.api.spi;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.classfile.CodeElement;
import java.util.Objects;

/**
 * One position in a method body that an {@link InjectionPoint} matched.
 *
 * <p>A site is what {@link InjectionPoint#find} answers with and what the engine narrows, shifts,
 * checks and finally emits at. It is three things: where the position is, what the position means
 * relative to the element that was matched, and — where there was one — the element itself.
 *
 * <h2>What the index counts</h2>
 *
 * <p>A position in {@link CodeView#elements()} of the view the point was handed. Not a bytecode
 * offset, not an ordinal among instructions: labels, line numbers, local-variable declarations and
 * exception handlers are elements too, and they are counted.
 *
 * <p>Where the declaration named a {@link de.splatgames.aether.weaver.api.Slice}, the view handed
 * to the point is the slice alone and the index is relative to it. The engine adds the slice's
 * offset back afterwards, so a point never sees, and never has to compensate for, the rest of the
 * method.
 *
 * <h2>What happens to a site after it is returned</h2>
 *
 * <p>In this order, for the sites one {@code @At} produced:
 *
 * <ol>
 *   <li><b>Translation.</b> The slice offset is added, and one further element is added for a site
 *       of kind {@link Kind#AFTER_ELEMENT}. From here on every index is a position in the whole
 *       body, and it is the position code is emitted <em>before</em> — which is what makes
 *       {@link Kind#AFTER_ELEMENT} mean "past this instruction" without every injector having to
 *       know about it.
 *   <li><b>The ordinal.</b> Applied as a position in the list the point returned, so it counts
 *       within the slice. Past the end is {@code AW1110}.
 *   <li><b>The shift.</b> {@link de.splatgames.aether.weaver.api.At.Shift#BEFORE} subtracts one
 *       element and {@link de.splatgames.aether.weaver.api.At.Shift#AFTER} adds one; a shift that
 *       leaves the range the site was found in is {@code AW1111}, and a
 *       {@link de.splatgames.aether.weaver.api.At.Shift#BY} of more than four elements draws the
 *       warning {@code AW1112}. The kind and the element travel with the moved site unchanged, so
 *       after a shift the element is no longer at the index.
 *   <li><b>The safety checks</b>, each of which drops the position it reports on. For an
 *       {@code @Inject}: a position before a constructor's own {@code super()} call is
 *       {@code AW1026} for a non-static handler, a position inside the window between a
 *       {@code new} and its constructor call is {@code AW1105}, and unreachable code is
 *       {@code AW1130} — a warning that still costs the position, since a handler there would
 *       never run and nothing else would say so. For {@code @Redirect} and {@code @Wrap}: a site of
 *       kind {@link Kind#AFTER_ELEMENT} is {@code AW1061}, because there is no operation at the
 *       position after one to stand in for. Any other injector kind keeps every position it found.
 * </ol>
 *
 * <p>Only the index survives into emission. The kind and the element decide what happens during
 * those four steps and are not carried further.
 *
 * <h2>Which distinctions the engine actually makes</h2>
 *
 * <p>{@link Kind#AFTER_ELEMENT} is the only kind the engine treats specially, in translation and in
 * the check that refuses it for an operation-replacing injector. {@link Kind#BEFORE_ELEMENT},
 * {@link Kind#METHOD_ENTRY} and {@link Kind#METHOD_EXIT} are handled identically to one another,
 * so choosing between those three describes the site rather than changing what is done with it.
 * A point that means "past this instruction" must nevertheless say {@link Kind#AFTER_ELEMENT}: the
 * translation is the only thing that moves the index, and a point that adds one itself and reports
 * {@link Kind#BEFORE_ELEMENT} lands one element further on than it meant to.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @Override
 * public List<Site> find(MethodView method, CodeView code, PointSpec spec, Reporter reporter) {
 *     List<Site> sites = new ArrayList<>();
 *     List<CodeElement> elements = code.elements();
 *     for (int at = 0; at < elements.size(); at++) {
 *         if (elements.get(at) instanceof InvokeInstruction invoke
 *                 && "close".equals(invoke.name().stringValue())) {
 *             // The position after the call: the engine moves the index on, not this code.
 *             sites.add(new Site(at, Site.Kind.AFTER_ELEMENT, invoke));
 *         }
 *     }
 *     return List.copyOf(sites);
 * }
 * }</pre>
 *
 * @param index   the position in {@link CodeView#elements()} of the view that was searched
 * @param kind    what the index means relative to {@code element}
 * @param element the element that was matched, or {@code null} where the point named a position
 *                rather than an element
 * @author Erik Pförtner
 * @since 0.1.0
 * @see InjectionPoint
 * @see CodeView
 */
public record Site(int index,
                   @NotNull Kind kind,
                   @Nullable CodeElement element) {

    /**
     * Checks that the position is expressible and that its meaning is stated.
     *
     * <p>Only the lower bound is checked. An index is meaningful against one particular body, which
     * a site does not carry, so whether the position exists is settled by the stages that use it
     * rather than here.
     *
     * @throws NullPointerException     if {@code kind} is {@code null}
     * @throws IllegalArgumentException if {@code index} is negative
     */
    public Site {
        Objects.requireNonNull(kind, "kind");
        if (index < 0) {
            throw new IllegalArgumentException("index must not be negative, got: " + index);
        }
    }

    /**
     * Returns whether this site names the element it was found at.
     *
     * @return {@code true} when {@link #element()} is present
     */
    @Contract(pure = true)
    public boolean hasElement() {
        return this.element != null;
    }

    /**
     * What a site's index means relative to the element that was matched.
     *
     * <p>The kind is set by the point that found the site and is preserved through translation and
     * shifting. It is read where the index is translated, to decide whether the position moves on
     * by one element, and again where a site is checked for being able to stand in for an
     * operation.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public enum Kind {

        /**
         * The index is the matched element, and code goes in front of it.
         *
         * <p>What the built-in {@code INVOKE}, {@code FIELD}, {@code NEW}, {@code CONSTANT} and
         * {@code THROW} points return, and the only kind {@code @Redirect} and {@code @Wrap}
         * accept — an operation can only be stood in for where the operation still is.
         */
        BEFORE_ELEMENT,

        /**
         * The index is the matched element, and code goes after it.
         *
         * <p>The engine adds one element to the index when it translates the site, so a point
         * returns the index of the element it matched and not the one after it. What the built-in
         * {@code INVOKE_AFTER} point returns.
         *
         * <p>Refused for {@code @Redirect} and {@code @Wrap} with {@code AW1061}: the position
         * after an operation holds no operation to replace. Use {@code @Inject} to add code there.
         */
        AFTER_ELEMENT,

        /**
         * The index is where the method's own code begins, and no element was matched.
         *
         * <p>What the built-in {@code HEAD} point returns, with a {@code null} element. For an
         * ordinary method that is the first instruction; for a constructor it is the element after
         * the constructor's own {@code super()} or {@code this()} call, which is why an instance
         * handler at {@code HEAD} has a {@code this} to be called on.
         */
        METHOD_ENTRY,

        /**
         * The index is a return instruction, and code goes in front of it.
         *
         * <p>What the built-in {@code RETURN} and {@code TAIL} points return, carrying the return
         * instruction as the element. Code goes before that instruction, so in a method that
         * returns a value the value is already on the stack there.
         */
        METHOD_EXIT
    }
}
