package de.splatgames.aether.weaver.idea.bytecode;

import de.splatgames.aether.weaver.api.Point;
import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.model.HandlerRef;
import de.splatgames.aether.weaver.api.model.InjectorKind;
import de.splatgames.aether.weaver.api.model.InjectorSpec;
import de.splatgames.aether.weaver.api.model.PointSpec;
import de.splatgames.aether.weaver.api.model.SliceSpec;
import de.splatgames.aether.weaver.api.select.ConstantSelector;
import de.splatgames.aether.weaver.api.select.MemberKind;
import de.splatgames.aether.weaver.api.select.MemberSelector;
import de.splatgames.aether.weaver.api.spi.CodeView;
import de.splatgames.aether.weaver.api.spi.MethodView;
import de.splatgames.aether.weaver.api.spi.Reporter;
import de.splatgames.aether.weaver.api.spi.Site;
import de.splatgames.aether.weaver.engine.inject.RedirectShapes;
import de.splatgames.aether.weaver.engine.inject.point.BuiltInPoints;
import de.splatgames.aether.weaver.engine.inject.point.PointResolver;
import de.splatgames.aether.weaver.engine.parse.PointTargets;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.lang.classfile.CodeElement;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Enumerates the operations of a compiled method as selectors that would resolve back to them.
 *
 * <p>Every proposal reached by enumerating a method's operations ({@link #of}) is verified
 * against the engine's own resolver before it is offered; {@link #selectorFor}, the fallback for a
 * target that has not been compiled, cannot verify its own answer and is the one exception. A
 * selector and an ordinal are written into an annotation and read back by an {@code @Inject}, so
 * nothing may be offered that an {@code @Inject} would resolve to a different instruction or to
 * none: each candidate is resolved once without an ordinal to learn its position among the matches,
 * and again with that ordinal in place to confirm that the pair names exactly one instruction and
 * that it is this one. A candidate that fails the second resolve is dropped rather than offered
 * with a caveat.
 *
 * <p>The resolver is driven through a synthetic {@code InjectorSpec} that names a handler no class
 * declares, and every diagnostic it produces is discarded. Nothing here reports to the user: a
 * proposal that cannot be verified is simply not made, and an unverifiable form falls back to the
 * qualified source form before it is abandoned.
 *
 * <p>Selectors are written in the API's own vocabulary throughout. A constant is rendered by
 * {@link ConstantSelector} rather than formatted here, because a second opinion about quoting,
 * escaping or how a class constant is spelled would produce a selector that parses and names
 * something else.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class TargetOperations {

    /**
     * The engine's resolver, over the built-in points only.
     *
     * <p>A custom point contributed by a build is not visible to the IDE, so a lookup that finds
     * nothing here yields no proposals rather than a wrong one.
     */
    private static final PointResolver RESOLVER = new PointResolver(BuiltInPoints.all()::get);

    /**
     * The name given to every part of the synthetic declaration the resolver is driven with.
     *
     * <p>It reaches nothing the user sees, but it is not inert: the probe's {@link HandlerRef}
     * carries no flags, so {@link HandlerRef#isStatic()} is {@code false}, and an instance handler
     * on an {@code <init>} target has every site at or before the constructor's {@code super()} call
     * dropped by the engine's own safety check. A declaration cannot be built without a handler
     * reference and a method selector, both of which the declaration record requires, but the
     * handler's shape does affect which sites come back for a constructor.
     */
    private static final String PROBE = "probe";

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private TargetOperations() {
        throw new AssertionError("no instances");
    }

    /**
     * How an operation's selector is written.
     *
     * <p>A spelling changes how an operation is named and never which operations exist: the same
     * instructions are found under all three. Where a spelling cannot name an instruction the
     * resolver agrees with, the qualified form is tried before the instruction is dropped, so a
     * request for one spelling can yield a selector in another.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public enum Spelling {

        /** Binary class names with their packages, and parameter types written out in full. */
        QUALIFIED,

        /** Class names without their packages, in both the owner and the parameter list. */
        SIMPLE,

        /**
         * The descriptor form, prefixed with {@link MemberSelector#DESCRIPTOR_PREFIX}, which names
         * an overload exactly and carries no parameter list.
         *
         * <p>Reaches a call and a field access when the operation is enumerated from the class file
         * by {@link #of}. Written from the source by {@link #selectorFor(SourceAnchor, Spelling)}, a
         * field access is rewritten to {@link #QUALIFIED} instead; only a call is rendered in this
         * form there. A creation names a type and a constant names a value, and neither has a
         * descriptor form to be written in.
         */
        DESCRIPTOR
    }

    /**
     * One operation of a compiled method, together with the selector that resolves to it.
     *
     * <p>{@link #target()} and {@link #ordinal()} are what an annotation would carry, and they were
     * verified together: resolving that pair against this method selects this instruction and no
     * other. The ordinal is counted within whatever region the operation was enumerated in, so an
     * operation found under a slice carries an ordinal that is only correct beside that slice.
     *
     * @param point     the point the operation was found for
     * @param target    the selector naming it, in the spelling that was asked for or in the
     *                  qualified form when that spelling could not be verified
     * @param ordinal   the zero-based position among the operations the selector matches
     * @param index     the index of the matched element in the method's code
     * @param label     the operation as it reads in a list the author chooses from
     * @param redirects the descriptor a redirect handler for this operation must begin with, or
     *                  {@code null} when the operation is not one a redirect can replace
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public record Operation(@NotNull Point point,
                            @NotNull String target,
                            int ordinal,
                            int index,
                            @NotNull String label,
                            @Nullable MethodTypeDesc redirects) {

        /**
         * Reports whether a redirect could replace this operation.
         *
         * @return {@code true} when {@link #redirects()} is present
         */
        @Contract(pure = true)
        public boolean isRedirectable() {
            return this.redirects != null;
        }
    }

    /**
     * What one instruction says about itself, in the spelling an anchor read from the source uses.
     *
     * <p>The comparison half of the search: every component is a class file spelling, so an anchor
     * can be tested against an instruction without either side rendering the other's form. Absent
     * components are empty strings rather than {@code null}, because they are compared with
     * {@code equals} and never rendered.
     *
     * @param kind       what the instruction is
     * @param owner      the internal name of the class it names, empty for a constant
     * @param name       the member's name, empty for a creation and a constant
     * @param descriptor the method descriptor of a call or the type descriptor of a field, empty
     *                   for a creation and a constant
     * @param constant   the constant selector the loaded value renders as, empty for every other
     *                   kind and for a constant the selector grammar cannot name
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public record Described(@NotNull SourceAnchor.Kind kind,
                            @NotNull String owner,
                            @NotNull String name,
                            @NotNull String descriptor,
                            @NotNull String constant) {
    }

    /**
     * Describes the instruction at one position in a method's code.
     *
     * <p>An anchor read from the source is matched against this rather than against the operation's
     * selector, because a selector has three spellings and an instruction has one description.
     *
     * @param method the compiled method; must not be {@code null}
     * @param index  the element index to describe
     * @return the description, or {@code null} when the method has no code, the index is outside
     *         it, or the element is not one of the four kinds an anchor can name
     * @throws NullPointerException if {@code method} is {@code null}
     */
    @Contract(pure = true)
    @Nullable
    public static Described describe(@NotNull final MethodView method, final int index) {
        final CodeView code = method.code().orElse(null);
        if (code == null) {
            return null;
        }
        final List<CodeElement> elements = code.elements();
        if (index < 0 || index >= elements.size()) {
            return null;
        }
        return switch (elements.get(index)) {
            case final InvokeInstruction invoke -> new Described(SourceAnchor.Kind.CALL,
                    invoke.owner().asInternalName(), invoke.name().stringValue(),
                    invoke.typeSymbol().descriptorString(), "");
            case final FieldInstruction field -> new Described(SourceAnchor.Kind.FIELD_ACCESS,
                    field.owner().asInternalName(), field.name().stringValue(),
                    field.typeSymbol().descriptorString(), "");
            case final NewObjectInstruction created -> new Described(SourceAnchor.Kind.INSTANTIATION,
                    created.className().asInternalName(), "", "", "");
            // Rendered through the API's own selector, the same way the target is. A second
            // spelling of the same constant here would compare unequal to the one written into the
            // annotation, and the mismatch would show up as "the caret found nothing" on a literal
            // the user is looking straight at.
            case final ConstantInstruction loaded -> new Described(SourceAnchor.Kind.CONSTANT,
                    "", "", "", textOf(ConstantSelector.of(loaded.constantValue())));
            default -> null;
        };
    }

    /**
     * Renders a constant selector for a {@link Described}, where absence is an empty string.
     *
     * @param selector the selector, or {@code null} for a value the grammar cannot name
     * @return the source rendering, or an empty string
     */
    @NotNull
    private static String textOf(@Nullable final ConstantSelector selector) {
        final String rendered = renderingOf(selector);
        return rendered == null ? "" : rendered;
    }

    /**
     * A region of a method, named by the two operations that bound it.
     *
     * <p>Turned into a {@code @Slice} by {@link #sliceOf(Bounds)}. The bounds are ordinary
     * operations and are named by their own selectors, so a slice narrows the search in exactly the
     * terms the annotation will carry.
     *
     * @param from the operation the region starts at
     * @param to   the operation the region ends at
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public record Bounds(@NotNull Operation from, @NotNull Operation to) {
    }

    /**
     * Enumerates the operations of the whole method for one point.
     *
     * @param method   the compiled method; must not be {@code null}
     * @param point    the point to enumerate for; must not be {@code null}
     * @param spelling how each selector is written; must not be {@code null}
     * @return every operation the point names and the resolver confirms, in code order
     * @throws NullPointerException if {@code method} is {@code null}
     */
    @Unmodifiable
    @NotNull
    public static List<Operation> of(@NotNull final MethodView method,
                                     @NotNull final Point point,
                                     @NotNull final Spelling spelling) {
        return of(method, point, spelling, null);
    }

    /**
     * Enumerates the operations of a method, or of one region of it, for one point.
     *
     * <p>The body is walked once and every element the point could name is turned into a selector,
     * which is then verified: the operation is offered only when resolving that selector with the
     * ordinal it was found at selects exactly this instruction. A selector the requested spelling
     * produced that fails this test is retried in the qualified form, and the operation carries
     * whichever form passed — so a caller asking for {@link Spelling#DESCRIPTOR} can receive a
     * source-form selector, which is the signal that the descriptor form does not work here.
     *
     * <p>Resolution is memoised per distinct selector within a call. Every resolve scans the whole
     * body, and a method calling one member twenty times would otherwise pay for twenty identical
     * scans to learn the same twenty positions.
     *
     * <p>With bounds, ordinals are counted inside the region rather than across the method, which
     * is how the engine counts them beside a slice. The result is therefore not a sublist of the
     * unbounded one: the same instructions carry different ordinals.
     *
     * <p>A point that names a position rather than an operation, such as
     * {@link Point#HEAD}, yields nothing: there is nothing in a body to enumerate for it.
     *
     * @param method   the compiled method; must not be {@code null}
     * @param point    the point to enumerate for; must not be {@code null}
     * @param spelling how each selector is written; must not be {@code null}
     * @param bounds   the region to count and search within, or {@code null} for the whole method
     * @return every operation the point names and the resolver confirms, in code order; empty when
     *         the method has no code or the bounds cannot be turned into a slice
     * @throws NullPointerException if {@code method} is {@code null}
     */
    @Unmodifiable
    @NotNull
    public static List<Operation> of(@NotNull final MethodView method,
                                     @NotNull final Point point,
                                     @NotNull final Spelling spelling,
                                     @Nullable final Bounds bounds) {
        final CodeView code = method.code().orElse(null);
        if (code == null) {
            return List.of();
        }

        // One resolve per distinct target, not per instruction. Every resolve scans the whole
        // body, and a method that calls one member twenty times used to pay for twenty identical
        // scans to learn the same twenty positions. On a large method — a JDK class, say — that is
        // the difference between a dialog that answers and one that stalls.
        final Matches matches = new Matches(method, code, point, bounds);

        final List<Operation> found = new ArrayList<>();
        final List<CodeElement> elements = code.elements();
        for (int index = 0; index < elements.size(); index++) {
            final CodeElement element = elements.get(index);
            final String wanted = targetOf(element, point, spelling);
            if (wanted == null) {
                continue;
            }
            final Operation operation = verified(matches, wanted, index, element);
            if (operation != null) {
                found.add(operation);
                continue;
            }
            final String qualified = targetOf(element, point, Spelling.QUALIFIED);
            if (qualified != null && !qualified.equals(wanted)) {
                final Operation fallback = verified(matches, qualified, index, element);
                if (fallback != null) {
                    found.add(fallback);
                }
            }
        }
        return List.copyOf(found);
    }

    /**
     * Reports the positions a declaration naming this operation would inject at.
     *
     * @param method    the compiled method; must not be {@code null}
     * @param point     the point the declaration names; must not be {@code null}
     * @param operation the operation the declaration targets, or {@code null} for a point that
     *                  takes no target
     * @return the element indices the resolver selected, empty when nothing was selected
     * @throws NullPointerException if {@code method} is {@code null}
     */
    @Unmodifiable
    @NotNull
    public static List<Integer> sitesOf(@NotNull final MethodView method,
                                        @NotNull final Point point,
                                        @Nullable final Operation operation) {
        return sitesOf(method, point, operation, null);
    }

    /**
     * Reports the positions a declaration naming this operation and slice would inject at.
     *
     * <p>These are injection positions, not matched instructions: a site for
     * {@link Point#INVOKE_AFTER} sits one element past the call it matched, which is what makes it
     * the right coordinate for asking which locals are live where the handler would run.
     *
     * <p>Without an operation the point is resolved with no target and no ordinal, which selects
     * every position it names — the shape of a positional declaration such as
     * {@link Point#RETURN}.
     *
     * @param method    the compiled method; must not be {@code null}
     * @param point     the point the declaration names; must not be {@code null}
     * @param operation the operation the declaration targets, or {@code null} for a point that
     *                  takes no target
     * @param bounds    the region the declaration's slice would name, or {@code null} for none
     * @return the element indices the resolver selected; empty when the method has no code, the
     *         selector cannot be parsed for this point, or nothing was selected
     * @throws NullPointerException if {@code method} is {@code null}
     */
    @Unmodifiable
    @NotNull
    public static List<Integer> sitesOf(@NotNull final MethodView method,
                                        @NotNull final Point point,
                                        @Nullable final Operation operation,
                                        @Nullable final Bounds bounds) {
        final CodeView code = method.code().orElse(null);
        if (code == null) {
            return List.of();
        }
        final PointSpec spec = operation == null
                ? PointSpec.builtIn(point).build()
                : specFor(point, operation.target(), operation.ordinal());
        if (spec == null) {
            return List.of();
        }
        final List<Integer> sites = new ArrayList<>();
        for (final Site site : resolve(method, code, spec, bounds)) {
            sites.add(site.index());
        }
        return List.copyOf(sites);
    }

    /**
     * The resolver results for one enumeration pass, with the unpinned ones memoised.
     *
     * <p>Exists for the cost of resolving. Every resolve scans the whole body, and the walk that
     * produces the proposals asks about one selector per instruction, so a method that calls the
     * same member repeatedly asks the same question repeatedly. The unpinned answer is the same
     * every time and is cached; the pinned one differs per ordinal and is not.
     *
     * <p>Scoped to a single call of {@code of}, so nothing here has to be safe to share or to
     * survive an edit to the class file.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private static final class Matches {

        /** The method every resolve is made against. */
        private final MethodView method;

        /** The body every resolve scans. */
        private final CodeView code;

        /** The point every resolve is made for. */
        private final Point point;

        /** The region ordinals are counted in, or {@code null} for the whole method. */
        private final Bounds bounds;

        /** Matched element indices per selector, resolved without an ordinal. */
        private final Map<String, List<Integer>> unpinned = new HashMap<>();

        /**
         * Holds what every resolve in this pass has in common.
         *
         * @param method the compiled method; must not be {@code null}
         * @param code   its body; must not be {@code null}
         * @param point  the point being enumerated; must not be {@code null}
         * @param bounds the region to count within, or {@code null} for the whole method
         */
        Matches(@NotNull final MethodView method,
                @NotNull final CodeView code,
                @NotNull final Point point,
                @Nullable final Bounds bounds) {
            this.method = method;
            this.code = code;
            this.point = point;
            this.bounds = bounds;
        }

        /**
         * Reports every instruction the selector matches, in the point's own coordinate.
         *
         * <p>Translated back with {@link PointResolver#matchedIndexOf(Site)}, because comparing a
         * resolved index with an instruction index finds nothing for
         * {@link Point#INVOKE_AFTER} and finds nothing silently.
         *
         * @param target the selector to resolve; must not be {@code null}
         * @return the matched element indices in match order, so that a position in the list is the
         *         ordinal that names it; empty when the selector cannot be parsed for this point
         */
        @NotNull
        List<Integer> of(@NotNull final String target) {
            return this.unpinned.computeIfAbsent(target, candidate -> {
                final PointSpec spec = specFor(this.point, candidate, -1);
                if (spec == null) {
                    return List.of();
                }
                final List<Integer> indices = new ArrayList<>();
                for (final Site site : resolve(this.method, this.code, spec, this.bounds)) {
                    indices.add(PointResolver.matchedIndexOf(site));
                }
                return List.copyOf(indices);
            });
        }

        /**
         * Resolves the selector with an ordinal written into it, exactly as an annotation would.
         *
         * <p>Not memoised: this is asked once per candidate, with a different ordinal each time.
         *
         * @param target  the selector to resolve; must not be {@code null}
         * @param ordinal the zero-based match to pin to
         * @return the sites selected, which the caller expects to be exactly one; empty when the
         *         selector cannot be parsed for this point
         */
        @NotNull
        List<Site> pinned(@NotNull final String target, final int ordinal) {
            final PointSpec spec = specFor(this.point, target, ordinal);
            return spec == null ? List.of() : resolve(this.method, this.code, spec, this.bounds);
        }

        /**
         * Returns the body every resolve in this pass scans.
         *
         * @return the body
         */
        @NotNull
        CodeView code() {
            return this.code;
        }

        /**
         * Returns the point every resolve in this pass is made for.
         *
         * @return the point
         */
        @NotNull
        Point point() {
            return this.point;
        }
    }

    /**
     * Turns one candidate instruction into an operation, or refuses it.
     *
     * <p>Two resolves stand behind every proposal. The first says where this instruction sits among
     * the selector's matches; the second says that writing that ordinal down selects it and nothing
     * else, which is the statement the generated annotation makes. A candidate that survives both
     * is also asked whether a redirect could replace it, so that the answer travels with it.
     *
     * @param matches the resolver results for this pass; must not be {@code null}
     * @param target  the selector proposed for the instruction; must not be {@code null}
     * @param index   the instruction's element index
     * @param element the instruction itself, used only for the label; must not be {@code null}
     * @return the operation, or {@code null} when the selector does not match this instruction or
     *         the selector and ordinal together select something other than it alone
     */
    @Nullable
    private static Operation verified(@NotNull final Matches matches,
                                      @NotNull final String target,
                                      final int index,
                                      @NotNull final CodeElement element) {
        final int ordinal = matches.of(target).indexOf(index);
        if (ordinal < 0) {
            return null;
        }
        // Resolved again with the ordinal in place. The first pass only says where this
        // instruction sits among the matches; this says that writing that down selects it and
        // nothing else, which is the statement the generated annotation actually makes.
        final List<Site> pinned = matches.pinned(target, ordinal);
        if (pinned.size() != 1 || PointResolver.matchedIndexOf(pinned.getFirst()) != index) {
            return null;
        }
        final RedirectShapes.Shape shape = RedirectShapes.at(matches.code().elements(), index);
        return new Operation(matches.point(), target, ordinal, index, labelOf(element, target),
                shape == null ? null : shape.handler());
    }

    /**
     * Reports the positions a declaration already written in the source would inject at.
     *
     * <p>The entry point for a caller holding a parsed {@code @At} rather than an operation this
     * class proposed: the point, its target, its ordinal and its shift are all taken from the spec
     * as the author wrote them.
     *
     * @param method the compiled method; must not be {@code null}
     * @param spec   the point declaration to resolve; must not be {@code null}
     * @param slices the slices narrowing it, empty for none; must not be {@code null}
     * @return the element indices the resolver selected, empty when the method has no code or
     *         nothing was selected
     * @throws NullPointerException if {@code method} is {@code null}
     */
    @Unmodifiable
    @NotNull
    public static List<Integer> sitesOf(@NotNull final MethodView method,
                                        @NotNull final PointSpec spec,
                                        @NotNull final List<SliceSpec> slices) {
        final CodeView code = method.code().orElse(null);
        if (code == null) {
            return List.of();
        }
        final List<Integer> sites = new ArrayList<>();
        for (final Site site : RESOLVER.resolve(method, code, probe(spec, slices), spec,
                new Ignored())) {
            sites.add(site.index());
        }
        return List.copyOf(sites);
    }

    /**
     * Builds the point declaration a proposal would compile to.
     *
     * <p>The selector is parsed through the kind-aware overload, the one the parser uses, so that a
     * method selector offered where a field is expected is rejected here exactly as it would be
     * rejected in a build. A point with no expected kind takes the selector unparsed.
     *
     * @param point   the point to declare; must not be {@code null}
     * @param target  the selector to name; must not be {@code null}
     * @param ordinal the zero-based match to pin to, or {@code -1} to keep every match
     * @return the declaration, or {@code null} when the selector is malformed for this point
     */
    @Nullable
    private static PointSpec specFor(@NotNull final Point point,
                                     @NotNull final String target,
                                     final int ordinal) {
        final PointSpec.Builder builder = PointSpec.builtIn(point).ordinal(ordinal);
        final MemberKind expected = PointTargets.selectorKindFor(point.name());
        if (expected == null) {
            return builder.target(target).build();
        }
        try {
            // The kind-aware overload, which is the one the parser uses: a method selector where a
            // field is expected is a syntax error there and a rejected proposal here.
            return builder.target(target, MemberSelector.parse(target, expected)).build();
        } catch (final RuntimeException malformed) {
            return null;
        }
    }

    /**
     * Runs the engine's resolver over one declaration, discarding its diagnostics.
     *
     * @param method the compiled method; must not be {@code null}
     * @param code   its body; must not be {@code null}
     * @param spec   the point declaration to resolve; must not be {@code null}
     * @param bounds the region to narrow to, or {@code null} for the whole method
     * @return the sites selected, empty when the bounds cannot be turned into a slice
     */
    @NotNull
    private static List<Site> resolve(@NotNull final MethodView method,
                                      @NotNull final CodeView code,
                                      @NotNull final PointSpec spec,
                                      @Nullable final Bounds bounds) {
        final InjectorSpec injector = probe(spec, bounds);
        return injector == null
                ? List.of()
                : RESOLVER.resolve(method, code, injector, spec, new Ignored());
    }

    /**
     * Builds the synthetic declaration for a point, optionally narrowed to a region.
     *
     * @param spec   the point declaration; must not be {@code null}
     * @param bounds the region to narrow to, or {@code null} for the whole method
     * @return the declaration, or {@code null} when the bounds name a selector that cannot be
     *         parsed and so describe no region at all
     */
    @Nullable
    private static InjectorSpec probe(@NotNull final PointSpec spec,
                                      @Nullable final Bounds bounds) {
        final List<SliceSpec> slices;
        if (bounds == null) {
            slices = List.of();
        } else {
            final SliceSpec slice = sliceOf(bounds);
            if (slice == null) {
                return null;
            }
            slices = List.of(slice);
        }
        return probe(spec, slices);
    }

    /**
     * Builds the synthetic declaration the resolver is driven with.
     *
     * <p>The resolver answers for a declaration rather than for a point, so one has to exist. Its
     * kind, handler, method selector and identifier are placeholders naming nothing in any project,
     * and the diagnostics that mention them are thrown away — but the handler is not otherwise
     * inert: it carries no flags, so {@link HandlerRef#isStatic()} is {@code false} for it, and on
     * an {@code <init>} target the engine's safety check drops every site at or before the
     * constructor's {@code super()} call as a result. The point and the slices are what this method
     * is meant to vary; the handler's shape is a fixed, and consequential, side effect of it.
     *
     * @param spec   the point declaration; must not be {@code null}
     * @param slices the slices to narrow with, empty for none; must not be {@code null}
     * @return a declaration carrying that point and those slices
     */
    @NotNull
    private static InjectorSpec probe(@NotNull final PointSpec spec,
                                      @NotNull final List<SliceSpec> slices) {
        return new InjectorSpec(InjectorKind.INJECT,
                new HandlerRef(ClassDesc.of(PROBE), PROBE,
                        MethodTypeDesc.of(ConstantDescs.CD_void), Set.of()),
                PROBE + "()", MemberSelector.parse(PROBE + "()"),
                List.of(spec), slices, PROBE, 0, 0, "", List.of());
    }

    /**
     * Turns a pair of bounding operations into the slice an annotation would carry.
     *
     * <p>Each bound keeps its own point and ordinal, so a region bounded by the second of three
     * identical calls is described as such rather than as the first one the selector matches.
     *
     * @param bounds the operations bounding the region; must not be {@code null}
     * @return the slice, or {@code null} when either bound's selector is malformed for its point
     * @throws NullPointerException if {@code bounds} is {@code null}
     */
    @Nullable
    public static SliceSpec sliceOf(@NotNull final Bounds bounds) {
        final PointSpec from =
                specFor(bounds.from().point(), bounds.from().target(), bounds.from().ordinal());
        final PointSpec to =
                specFor(bounds.to().point(), bounds.to().target(), bounds.to().ordinal());
        return from == null || to == null ? null : new SliceSpec("", from, to);
    }

    /**
     * Writes a selector for what the editor read, with no class file consulted.
     *
     * <p>The fallback for a target that has not been compiled, and the one thing here that cannot
     * verify its own answer: nothing resolves the selector, so no ordinal is available and the
     * proposal is worth less than one made from the bytes.
     *
     * <p>{@link Spelling#DESCRIPTOR} is honoured only for a call. A field access and a creation
     * name a type, which has no descriptor form, and asking for one falls back to
     * {@link Spelling#QUALIFIED} rather than to the simple name.
     *
     * @param anchor   what the editor read; must not be {@code null}
     * @param spelling how the selector is written; must not be {@code null}
     * @return the selector, or {@code null} when the anchor names a position rather than an
     *         operation, when the editor could not resolve enough of it, or when its descriptor is
     *         one the constant pool grammar rejects
     * @throws NullPointerException if {@code anchor} is {@code null}
     */
    @Contract(pure = true)
    @Nullable
    public static String selectorFor(@NotNull final SourceAnchor anchor,
                                     @NotNull final Spelling spelling) {
        return switch (anchor.kind()) {
            case CALL -> callSelectorFor(anchor, spelling);
            case FIELD_ACCESS -> anchor.owner() == null || anchor.name() == null
                    ? null
                    : typeOf(anchor.owner(), spelling == Spelling.DESCRIPTOR
                            ? Spelling.QUALIFIED
                            : spelling) + '.' + anchor.name();
            case INSTANTIATION -> anchor.owner() == null
                    ? null
                    : typeOf(anchor.owner(), spelling == Spelling.DESCRIPTOR
                            ? Spelling.QUALIFIED
                            : spelling);
            case CONSTANT -> anchor.constant();
            case RETURN, HEAD -> null;
        };
    }

    /**
     * Writes a selector for a call the editor read.
     *
     * <p>The parameter list is rendered from the anchor's descriptor rather than from the resolved
     * method's own types, so that the selector and the descriptor cannot disagree about an
     * overload.
     *
     * @param anchor   the call anchor; must not be {@code null}
     * @param spelling how the selector is written; must not be {@code null}
     * @return the selector, or {@code null} when the owner, the name or the descriptor is absent,
     *         or the descriptor does not parse
     */
    @Contract(pure = true)
    @Nullable
    private static String callSelectorFor(@NotNull final SourceAnchor anchor,
                                          @NotNull final Spelling spelling) {
        if (anchor.owner() == null || anchor.name() == null || anchor.descriptor() == null) {
            return null;
        }
        try {
            return memberOf(anchor.owner(), anchor.name(), anchor.descriptor(), spelling,
                    parametersOf(MethodTypeDesc.ofDescriptor(anchor.descriptor()), spelling));
        } catch (final IllegalArgumentException malformed) {
            // A descriptor the editor produced that the constant-pool grammar rejects. Nothing here
            // can name that call, and inventing a selector for it would name a different one.
            return null;
        }
    }

    /**
     * Writes the selector a point would use for one instruction.
     *
     * <p>The point decides which instruction is interesting, so an element of the wrong sort is not
     * an error but simply not this point's business.
     *
     * @param element  the instruction to name; must not be {@code null}
     * @param point    the point being enumerated; must not be {@code null}
     * @param spelling how the selector is written; must not be {@code null}
     * @return the selector, or {@code null} when the element is not what the point names, the point
     *         names no operation, or the loaded constant has no spelling in the selector grammar
     */
    @Nullable
    private static String targetOf(@NotNull final CodeElement element,
                                   @NotNull final Point point,
                                   @NotNull final Spelling spelling) {
        return switch (point) {
            case INVOKE, INVOKE_AFTER -> element instanceof final InvokeInstruction invoke
                    ? memberOf(invoke.owner().asInternalName(), invoke.name().stringValue(),
                            invoke.typeSymbol().descriptorString(), spelling,
                            parametersOf(invoke.typeSymbol(), spelling))
                    : null;
            case FIELD -> element instanceof final FieldInstruction field
                    ? memberOf(field.owner().asInternalName(), field.name().stringValue(),
                            field.typeSymbol().descriptorString(), spelling, null)
                    : null;
            case NEW -> element instanceof final NewObjectInstruction created
                    ? typeOf(created.className().asInternalName(), spelling)
                    : null;
            // Rendered by the API's own ConstantSelector, never formatted here. A string is
            // quoted and escaped, a class is written by name rather than by descriptor and null
            // carries no value at all; a second opinion about any of that would produce a selector
            // that parses and names something else.
            case CONSTANT -> element instanceof final ConstantInstruction loaded
                    ? renderingOf(ConstantSelector.of(loaded.constantValue()))
                    : null;
            default -> null;
        };
    }

    /**
     * Renders a constant selector in its source form.
     *
     * @param selector the selector, or {@code null} for a value the grammar cannot name, such as a
     *                 method handle
     * @return the rendering, or {@code null} when there is no selector
     */
    @Nullable
    private static String renderingOf(@Nullable final ConstantSelector selector) {
        return selector == null ? null : selector.render(MemberSelector.Form.SOURCE);
    }

    /**
     * Writes a member selector in the requested spelling.
     *
     * @param ownerInternal the declaring class's internal name; must not be {@code null}
     * @param name          the member's name; must not be {@code null}
     * @param descriptor    the member's descriptor, used by the descriptor spelling only; must not
     *                      be {@code null}
     * @param spelling      how the selector is written; must not be {@code null}
     * @param parameters    the rendered parameter list to append, or {@code null} for a member that
     *                      takes none, which is every field, or for a call rendered in the
     *                      {@link Spelling#DESCRIPTOR} form, where it is unused
     * @return the selector
     */
    @NotNull
    private static String memberOf(@NotNull final String ownerInternal,
                                   @NotNull final String name,
                                   @NotNull final String descriptor,
                                   @NotNull final Spelling spelling,
                                   @Nullable final String parameters) {
        final String selector = spelling == Spelling.DESCRIPTOR
                ? MemberSelector.DESCRIPTOR_PREFIX + ownerInternal + '.' + name + descriptor
                : typeOf(ownerInternal, spelling) + '.' + name
                        + (parameters == null ? "" : parameters);
        return selector;
    }

    /**
     * Renders a method's parameter list for a source-form selector.
     *
     * <p>Types only, separated by {@code ", "}, and no return type: that is the shape the selector
     * grammar accepts.
     *
     * @param type     the method's descriptor; must not be {@code null}
     * @param spelling how the types are written; must not be {@code null}
     * @return the list in parentheses, or {@code null} for the descriptor spelling, which carries
     *         the descriptor itself and needs no list
     */
    @Nullable
    private static String parametersOf(@NotNull final MethodTypeDesc type,
                                       @NotNull final Spelling spelling) {
        if (spelling == Spelling.DESCRIPTOR) {
            return null;
        }
        final StringBuilder parameters = new StringBuilder("(");
        for (int index = 0; index < type.parameterCount(); index++) {
            parameters.append(index == 0 ? "" : ", ").append(nameOf(type.parameterType(index),
                    spelling));
        }
        return parameters.append(')').toString();
    }

    /**
     * Writes a class's internal name as a selector names it.
     *
     * <p>A nested class keeps the {@code $} its binary name carries; only the package is dropped
     * for the simple spelling.
     *
     * @param ownerInternal the internal name, with {@code /} separators; must not be {@code null}
     * @param spelling      how the name is written; the descriptor spelling is treated as the
     *                      qualified one, since a type has no descriptor form here
     * @return the binary name, or its last segment for the simple spelling
     */
    @NotNull
    private static String typeOf(@NotNull final String ownerInternal,
                                 @NotNull final Spelling spelling) {
        final String binary = ownerInternal.replace('/', '.');
        if (spelling != Spelling.SIMPLE) {
            return binary;
        }
        final int dot = binary.lastIndexOf('.');
        return dot < 0 ? binary : binary.substring(dot + 1);
    }

    /**
     * Writes one parameter type as a selector names it.
     *
     * <p>An array is written as its component type followed by {@code []}, recursively, and a
     * primitive by its own name in every spelling. A class in the unnamed package is written by its
     * display name, since prefixing an empty package would produce a leading dot.
     *
     * @param type     the type to name; must not be {@code null}
     * @param spelling how the name is written; must not be {@code null}
     * @return the rendered type
     */
    @NotNull
    private static String nameOf(@NotNull final ClassDesc type,
                                 @NotNull final Spelling spelling) {
        if (type.isArray()) {
            return nameOf(type.componentType(), spelling) + "[]";
        }
        if (type.isPrimitive()) {
            return type.displayName();
        }
        return spelling == Spelling.SIMPLE
                ? type.displayName()
                : type.packageName().isEmpty()
                        ? type.displayName()
                        : type.packageName() + '.' + type.displayName();
    }

    /**
     * Writes the operation as it reads in a list the author chooses from.
     *
     * <p>The selector alone is ambiguous for a field, which is named the same whether it is read or
     * written, so the direction is spelled out from the opcode. A creation and a constant are
     * prefixed with the word for what they are.
     *
     * @param element the instruction; must not be {@code null}
     * @param target  the selector naming it; must not be {@code null}
     * @return the label, which is the selector itself for a call
     */
    @NotNull
    private static String labelOf(@NotNull final CodeElement element, @NotNull final String target) {
        if (element instanceof final FieldInstruction field) {
            return target + (field.opcode().name().contains("PUT") ? " (write)" : " (read)");
        }
        if (element instanceof NewObjectInstruction) {
            return "new " + target;
        }
        if (element instanceof ConstantInstruction) {
            return "constant " + target;
        }
        return target;
    }

    /**
     * The reporter the resolver is given, which discards everything.
     *
     * <p>Nothing here is a build. A selector that resolves to nothing is a proposal not made, and
     * the diagnostics the resolver would raise about the synthetic declaration describe a handler
     * that does not exist.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private static final class Ignored implements Reporter {

        /** Holds no state, so instances are interchangeable. */
        Ignored() {
            // Stateless.
        }

        /**
         * Discards the diagnostic.
         *
         * @param diagnostic the diagnostic the resolver raised
         */
        @Override
        public void report(@NotNull final Diagnostic diagnostic) {
            // Deliberately nothing.
        }
    }
}
