package de.splatgames.aether.weaver.idea.preview;

import com.intellij.lang.java.JavaLanguage;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaTokenType;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiArrayInitializerMemberValue;
import com.intellij.psi.PsiClass;
import de.splatgames.aether.weaver.api.select.FieldSelector;
import com.intellij.psi.PsiUnaryExpression;
import com.intellij.psi.PsiThrowStatement;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiAssignmentExpression;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaToken;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiReturnStatement;
import com.intellij.psi.PsiSwitchLabelStatementBase;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.search.searches.ReferencesSearch;
import de.splatgames.aether.weaver.idea.bytecode.TargetOperations;
import de.splatgames.aether.weaver.idea.bytecode.CompiledLines;
import de.splatgames.aether.weaver.idea.bytecode.EditorLines;
import de.splatgames.aether.weaver.idea.bytecode.CompiledClasses;
import de.splatgames.aether.weaver.engine.parse.PointTargets;
import de.splatgames.aether.weaver.api.spi.TargetView;
import de.splatgames.aether.weaver.api.spi.MethodView;
import de.splatgames.aether.weaver.api.model.SliceSpec;
import de.splatgames.aether.weaver.api.model.PointSpec;
import com.intellij.psi.util.ClassUtil;
import de.splatgames.aether.weaver.idea.index.WeaveTargetIndex;
import de.splatgames.aether.weaver.idea.psi.CaretAnchors;
import de.splatgames.aether.weaver.idea.psi.HandlerOrder;
import de.splatgames.aether.weaver.idea.psi.SelectorTargets;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import de.splatgames.aether.weaver.api.select.MemberKind;
import de.splatgames.aether.weaver.api.select.MemberSelector;
import de.splatgames.aether.weaver.api.select.MethodSelector;
import de.splatgames.aether.weaver.api.select.TypePattern;
import de.splatgames.aether.weaver.idea.selector.SelectorReference;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Works out where a weave's handlers land in the file being woven, and what to draw there.
 *
 * <p>Two entry points, one machinery. {@link #of(PsiFile)} produces the {@link WeaveBlock}s that
 * {@link WeaveInlayPass} draws above the target's own code, and {@link #targetLinesOf(PsiElement)}
 * answers the narrower question {@link InjectionSiteHints} asks from the weave's side: which lines
 * of the target one injection reaches. Both rest on the same step, which turns a handler and its
 * {@code @At} into document offsets in the target.
 *
 * <h2>Source or class file</h2>
 *
 * <p>A position is found either by matching PSI in the target's body or by resolving the point
 * against the compiled class, and the choice is made per handler.
 *
 * <p>The class file is used when the declaration pins an {@code ordinal}, names a {@code shift} or
 * carries a {@code slice}, and also whenever the target method has no PSI body to walk. All three
 * of ordinal, shift and slice count instructions rather than source constructs, and the two do not
 * correspond: string concatenation, boxing and an enhanced {@code for} each put calls into the
 * bytecode that appear nowhere in the text. Those positions are therefore asked of the engine's own
 * resolver through {@link TargetOperations}, mapped to class file lines by {@link CompiledLines}
 * and into lines of the file on screen by {@link EditorLines}. Where {@link CompiledClasses} cannot
 * supply the compiled form, nothing is drawn.
 *
 * <p>Otherwise the target's source is matched, point by point:
 *
 * <ul>
 *   <li>{@code HEAD} is the body's first statement, or, in a body with no statements, the first
 *       thing inside the braces.
 *   <li>{@code RETURN} is every {@code return} statement in the body.
 *   <li>{@code TAIL} is the single exit and nothing else: a body with no {@code return} at all
 *       resolves to its closing brace, and a body with exactly one {@code return} resolves to it
 *       only when it is also the body's last statement. Any other body yields nothing, because the
 *       last exit in bytecode order is not necessarily the last one in the text.
 *   <li>{@code INVOKE} and {@code INVOKE_AFTER} are every call whose resolved method the selector
 *       names. Both are anchored on the call itself; the difference between them is carried by the
 *       section's header and its explanation rather than by the position.
 *   <li>{@code FIELD} is every reference resolving to a field the selector names, filtered by the
 *       declaration's {@code access}.
 *   <li>{@code CONSTANT} is every literal whose value renders to the selector's own text and that
 *       is not folded away by the compiler.
 *   <li>{@code NEW} is every {@code new} whose class reference resolves to a class of that
 *       qualified or simple name.
 *   <li>{@code THROW} is every {@code throw}, or, when a target is named, those whose exception
 *       type matches it.
 * </ul>
 *
 * <p>Except for {@code HEAD}, {@code RETURN}, {@code TAIL} and {@code THROW}, a source match needs
 * a target to have been written; a declaration with an empty one draws nothing.
 *
 * <h2>One line, one block</h2>
 *
 * <p>Every offset found is normalised to the start of its line before it is grouped, when the file
 * has a document to normalise against; without one, two matches on the same line stay two distinct
 * offsets and produce two blocks. Within a block there is a section per point and kind, in
 * the order of {@code POINTS} and with {@link WeaveBlock.Kind#INJECT} before
 * {@link WeaveBlock.Kind#REDIRECT}; within a section, handlers are listed in
 * {@link HandlerOrder#EXECUTION_ORDER} and a handler that applies more than once on the line is
 * counted rather than repeated.
 *
 * <p>A {@code @Redirect} is the only annotation that produces a {@link WeaveBlock.Kind#REDIRECT}
 * section. A {@code @Wrap} is drawn as an injection.
 *
 * <h2>Silence</h2>
 *
 * <p>Nothing here reports to the user, and every failure is an empty list: a selector that resolves
 * to nothing or to several methods, a point this release cannot parse, a half-typed annotation, a
 * target that has not been compiled where the class file is needed. Reporting on what the user
 * wrote belongs to the inspections; a block drawn on a guess would be a claim about where code
 * runs.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class WeaveBlocks {

    /** The point that runs on entry to the target. */
    static final String HEAD = "HEAD";

    /** The point that runs before each of the target's returns. */
    static final String RETURN = "RETURN";

    /** The point that runs before a call the declaration names. */
    static final String INVOKE = "INVOKE";

    /** The point that runs after a call the declaration names. */
    static final String INVOKE_AFTER = "INVOKE_AFTER";

    /**
     * The point that runs once on the way out, keeping only the last return in body order.
     *
     * <p>This class's source match for the point ({@code tailOf(PsiCodeBlock)}) further requires that
     * last return to be the target's single exit; a method with several returns matches nothing here,
     * though {@code Point} itself does not impose that restriction.
     */
    static final String TAIL = "TAIL";

    /** The point that runs at an access to a field the declaration names. */
    static final String FIELD = "FIELD";

    /** The point that runs where an instance of the named class is created. */
    static final String NEW = "NEW";

    /** The point that runs before a {@code throw}. */
    static final String THROW = "THROW";

    /** The point that runs at a load of the constant the declaration names. */
    private static final String CONSTANT = "CONSTANT";

    /**
     * The points that can become a section, in the order their sections stack within a block.
     *
     * <p>{@code HEAD} first and {@code TAIL} last. A point outside this list never becomes a section,
     * whatever was collected under its name.
     */
    private static final List<String> POINTS =
            List.of(HEAD, FIELD, NEW, INVOKE, INVOKE_AFTER, CONSTANT, THROW, RETURN, TAIL);

    /** The attribute of an injection annotation holding its {@code @At}. */
    private static final String AT_ATTRIBUTE = "at";

    /** The attribute of an injection annotation holding its {@code @Slice}. */
    private static final String SLICE_ATTRIBUTE = "slice";

    /** The attribute of an {@code @At} naming what the point matches. */
    private static final String TARGET_ATTRIBUTE = "target";

    /** The attribute of an {@code @At} pinning which match is meant. */
    private static final String ORDINAL_ATTRIBUTE = "ordinal";

    /** The attribute of an {@code @At} moving the position off the match. */
    private static final String SHIFT_ATTRIBUTE = "shift";

    /** The attribute of an {@code @At} saying how far a {@code BY} shift moves. */
    private static final String BY_ATTRIBUTE = "by";

    /** The attribute of a {@code @Slice} holding its lower bound. */
    private static final String FROM_ATTRIBUTE = "from";

    /** The attribute of a {@code @Slice} holding its upper bound. */
    private static final String TO_ATTRIBUTE = "to";

    /** The attribute of an {@code @At} narrowing a {@code FIELD} point to reads or to writes. */
    private static final String ACCESS_ATTRIBUTE = "access";

    /** The {@code access} that narrows nothing, and what an unwritten one is read as. */
    private static final String ANY_ACCESS = "ANY";

    /**
     * The ordinal that pins nothing, so that every match is a position.
     *
     * <p>Also what an {@code ordinal} attribute that is absent or is not an integer literal is read as,
     * which keeps an unreadable ordinal out of the class file path.
     */
    private static final int EVERY_MATCH = -1;

    /** The qualified name of the one annotation drawn as a replacement rather than an addition. */
    private static final String REDIRECT_ANNOTATION =
            "de.splatgames.aether.weaver.api.Redirect";

    /** The {@code shift} that moves nothing, and what an unwritten one is read as. */
    private static final String NO_SHIFT = "NONE";

    /** The most lines of handler code a section shows before the rest are counted instead. */
    private static final int MAX_LINES = 12;

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private WeaveBlocks() {
        throw new AssertionError("no instances");
    }

    /**
     * Returns the blocks to draw over the given file.
     *
     * <p>Every method the file declares is a candidate target. For each, the handlers that name it are
     * found, each handler is placed, and every offset it lands on is normalised to the start of its
     * line when the file has a document; without one, offsets are left as found. Offsets are collected
     * in a sorted map, so blocks come back in ascending offset order, with one per line when
     * normalisation applied and possibly more than one per line when it did not.
     *
     * <p>Within a line, handlers are grouped by point and kind, and a group becomes a section only if
     * its point is one of the nine this class knows. The block's identity is derived from the target's
     * signature and the section headers, which is what lets a collapsed block stay collapsed while the
     * text above it is edited.
     *
     * @param file the file being shown; must not be {@code null}
     * @return the blocks, ordered by offset; empty when the file declares no woven method and when
     *         no handler of one can be placed
     */
    @Unmodifiable
    @NotNull
    public static List<WeaveBlock> of(@NotNull final PsiFile file) {
        // Keyed by offset, then by point, because two points legitimately share one offset: a
        // one-line method is its own first statement and its own return.
        final Map<Integer, Anchor> byOffset = new TreeMap<>();
        final Document document =
                PsiDocumentManager.getInstance(file.getProject()).getDocument(file);

        for (final PsiMethod target : PsiTreeUtil.findChildrenOfType(file, PsiMethod.class)) {
            // May be null, and that is no longer a reason to skip. An abstract or interface
            // method has no code to inject into, but neither has a decompiled one — a Cls method
            // never carries a body whatever the editor renders — and that one is injectable and is
            // the ordinary case for a weaver. Which of the two this is gets decided per handler by
            // whether the point can be placed without reading the source at all.
            final PsiCodeBlock body = target.getBody();
            for (final Handler handler : handlersFor(target)) {
                for (final int offset : offsetsOf(target, handler, body)) {
                    // Normalised to the start of the line. A block inlay is drawn above a *line*,
                    // so two offsets on one line produce two inlays stacked above it — which is what
                    // `format(a) + format(a)` did: correct, one block per call site, and to the
                    // reader an unexplained duplicate. One line is one block; how often a handler
                    // applies on it is said in words instead.
                    byOffset.computeIfAbsent(startOfLine(document, offset),
                                    key -> new Anchor(signatureOf(target)))
                            .byPoint()
                            .computeIfAbsent(handler.at().point() + "\u0000" + handler.kind(),
                                    key -> new ArrayList<>())
                            .add(handler);
                }
            }
        }

        final List<WeaveBlock> blocks = new ArrayList<>();
        byOffset.forEach((offset, anchor) -> {
            final List<WeaveBlock.Section> sections = new ArrayList<>();
            // In execution order, and inside one block. Two inlays on one offset would leave
            // their order to the platform, and the order is a statement about when the code runs —
            // not something to borrow from undocumented behaviour and hope for.
            for (final String point : POINTS) {
                for (final WeaveBlock.Kind kind : WeaveBlock.Kind.values()) {
                    final List<Handler> here = anchor.byPoint().get(point + "\u0000" + kind);
                    if (here != null) {
                        sections.add(sectionOf(here, point, kind, file.getProject()));
                    }
                }
            }
            blocks.add(new WeaveBlock(offset, identify(anchor.target(), sections),
                    List.copyOf(sections)));
        });
        return List.copyOf(blocks);
    }

    /**
     * Returns the lines of the target that one injection reaches.
     *
     * <p>The element is the {@code method} selector of an injection annotation. Its enclosing
     * annotation supplies the point and the kind, its enclosing method is the handler, and the target
     * is the single method the element's {@code SelectorReference} resolves to. The lines are those of
     * the target's own file, one-based, sorted and without duplicates: two matches on one line are
     * reported once.
     *
     * @param literal the element carrying the selector; must not be {@code null}
     * @return the one-based target lines in ascending order; empty when the element carries no
     *         readable {@code @At}, is not inside a method, resolves to other than exactly one
     *         method, or when the target's file has no document or the point matches nothing in it
     */
    @NotNull
    public static List<Integer> targetLinesOf(@NotNull final PsiElement literal) {
        final At at = atOf(literal);
        final PsiMethod handlerMethod = PsiTreeUtil.getParentOfType(literal, PsiMethod.class);
        if (at == null || handlerMethod == null) {
            return List.of();
        }
        final PsiMethod target = targetOf(literal);
        if (target == null) {
            return List.of();
        }
        final PsiFile file = target.getContainingFile();
        final Document document = file == null
                ? null
                : PsiDocumentManager.getInstance(target.getProject()).getDocument(file);
        if (document == null) {
            return List.of();
        }
        final Handler handler = new Handler(handlerMethod, at, kindOf(literal));
        final java.util.SortedSet<Integer> lines = new java.util.TreeSet<>();
        for (final int offset : offsetsOf(target, handler, target.getBody())) {
            if (offset >= 0 && offset <= document.getTextLength()) {
                lines.add(document.getLineNumber(offset) + 1);
            }
        }
        return List.copyOf(lines);
    }

    /**
     * Returns the one method the element's selector names.
     *
     * <p>Only a {@code SelectorReference} is consulted, and only when it resolves to exactly one
     * element and that element is a method. A selector naming no signature over an overloaded target
     * resolves to several, and reporting the sites of all of them as one injection's would be a claim
     * about where code runs that the selector does not support.
     *
     * @param literal the element carrying the selector; must not be {@code null}
     * @return the target method, or {@code null} when no selector reference resolves to exactly one
     */
    @Nullable
    private static PsiMethod targetOf(@NotNull final PsiElement literal) {
        for (final PsiReference reference : literal.getReferences()) {
            if (!(reference instanceof SelectorReference)) {
                continue;
            }
            // Exactly one. A selector naming no signature over an overloaded target resolves to
            // several, and "it lands on line 41 and line 58" would be two different injections'
            // worth of sites shown as one — the ambiguity is the inspection's to report, not this
            // hint's to average over.
            final var resolved = ((SelectorReference) reference).multiResolve(false);
            if (resolved.length == 1 && resolved[0].getElement() instanceof final PsiMethod method) {
                return method;
            }
        }
        return null;
    }

    /**
     * Handlers in the order the engine runs them.
     *
     * <p>Compares the handler methods with {@link HandlerOrder#EXECUTION_ORDER}: highest
     * {@code @Weave(priority)} first, then the weave's qualified class name, the handler's own name and
     * its parameter types, all ascending. The reference search that finds the handlers answers in
     * whatever order the index produces, and the list in a section is a claim about which handler runs
     * first, so it is sorted before it is shown.
     */
    private static final java.util.Comparator<Handler> EXECUTION_ORDER =
            java.util.Comparator.comparing(Handler::method, HandlerOrder.EXECUTION_ORDER);

    /**
     * Moves an offset back to the start of the line it sits on.
     *
     * @param document the document the offset belongs to, or {@code null} when there is none
     * @param offset   the offset to normalise
     * @return the start offset of the line, or {@code offset} unchanged when there is no document or
     *         the offset lies outside it
     */
    private static int startOfLine(@Nullable final Document document, final int offset) {
        if (document == null || offset < 0 || offset > document.getTextLength()) {
            return offset;
        }
        return document.getLineStartOffset(document.getLineNumber(offset));
    }

    /**
     * Renders a method as the text that stands for it in a block's identity.
     *
     * <p>The name, then each parameter's presentable type followed by a comma, in brackets, so that
     * {@code charge(String one, int two)} renders as {@code charge(String,int,)}. The trailing comma is
     * of no consequence: this text is only ever compared with another rendering of the same shape.
     *
     * @param method the method to render; must not be {@code null}
     * @return the rendered signature
     */
    @NotNull
    private static String signatureOf(@NotNull final PsiMethod method) {
        final StringBuilder rendered = new StringBuilder(method.getName()).append('(');
        for (final PsiParameter parameter : method.getParameterList().getParameters()) {
            rendered.append(parameter.getType().getPresentableText()).append(',');
        }
        return rendered.append(')').toString();
    }

    /**
     * Builds the identity a block is remembered by.
     *
     * <p>The target's signature, then each section header preceded by a bar. Deliberately free of the
     * offset, so that an edit above the block does not lose the reader's decision to collapse it. Two
     * blocks in one method whose sections carry the same headers therefore share an identity and
     * collapse together.
     *
     * @param target   the rendered signature of the method the block sits in; must not be {@code null}
     * @param sections the block's sections, in the order they will be drawn; must not be {@code null}
     * @return the identity
     */
    @NotNull
    private static String identify(@NotNull final String target,
                                   @NotNull final List<WeaveBlock.Section> sections) {
        final StringBuilder id = new StringBuilder(target);
        for (final WeaveBlock.Section section : sections) {
            id.append('|').append(section.header());
        }
        return id.toString();
    }

    /**
     * The handlers gathered on one line, grouped by the point and kind they belong to.
     *
     * <p>The map key is the point's name, a NUL character and the kind, which keeps two points that
     * legitimately share an offset apart: a one-line method is its own first statement and its own
     * return.
     *
     * @param target  the rendered signature of the method the line belongs to
     * @param byPoint the handlers, keyed by point and kind, in the order the keys were first seen
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private record Anchor(@NotNull String target,
                          @NotNull Map<String, List<Handler>> byPoint) {

        /**
         * Creates an anchor for a target with nothing gathered yet.
         *
         * @param target the rendered signature of the method the line belongs to; must not be {@code null}
         */
        Anchor(@NotNull final String target) {
            this(target, new LinkedHashMap<>());
        }
    }

    /**
     * Returns the offsets in the target at which one handler applies.
     *
     * <p>Routes to the class file whenever the declaration counts instructions — an ordinal, a shift or
     * a slice — and whenever there is no body to walk. Otherwise the point decides which of the source
     * matchers is asked; a point none of them names produces no offsets.
     *
     * @param target  the method being woven; must not be {@code null}
     * @param handler the handler and the {@code @At} that places it; must not be {@code null}
     * @param body    the target's body, or {@code null} when its PSI has none
     * @return the offsets, in the order the matcher found them; empty when the point matches nothing
     */
    @NotNull
    private static List<Integer> offsetsOf(@NotNull final PsiMethod target,
                                           @NotNull final Handler handler,
                                           @Nullable final PsiCodeBlock body) {
        // No body means no source to read, not nothing to find. A decompiled method holds code
        // the reader can see and the engine can resolve; only the PSI has no body to walk. So the
        // class file answers for every point there, not merely for the four that always need it.
        if (handler.at().needsCompiled() || body == null) {
            return compiledOffsetsOf(target, handler.at());
        }
        return switch (handler.at().point()) {
            case HEAD -> List.of(entryOf(body));
            case RETURN -> PsiTreeUtil.findChildrenOfType(body, PsiReturnStatement.class).stream()
                    .map(PsiStatement::getTextOffset)
                    .toList();
            case TAIL -> tailOf(body);
            case INVOKE, INVOKE_AFTER -> callSitesOf(handler.at(), body);
            case FIELD -> fieldSitesOf(handler.at(), body);
            case CONSTANT -> constantSitesOf(handler.at(), body);
            case NEW -> newSitesOf(handler.at(), body);
            case THROW -> throwSitesOf(handler.at(), body);
            default -> List.of();
        };
    }

    /**
     * Returns the position of the target's single exit, read from source.
     *
     * <p>Answered only where the source makes it unambiguous. A body with no {@code return} runs off
     * its end, so the closing brace is the only exit. A body with exactly one {@code return} qualifies
     * only if that statement is also the body's last, since otherwise a void method falls off the end
     * as well and there are two exits after all. More than one {@code return} yields nothing: the tail
     * is the last exit in bytecode order, and that is not necessarily the last one in the text.
     *
     * @param body the target's body; must not be {@code null}
     * @return the single exit's offset, or empty when the source does not settle which exit is last
     */
    @NotNull
    private static List<Integer> tailOf(@NotNull final PsiCodeBlock body) {
        final List<PsiReturnStatement> returns =
                new ArrayList<>(PsiTreeUtil.findChildrenOfType(body, PsiReturnStatement.class));
        if (returns.isEmpty()) {
            // A void method that simply runs off the end: the implicit return is the only exit.
            return List.of(endOf(body));
        }
        if (returns.size() > 1) {
            return List.of();
        }
        final PsiStatement[] statements = body.getStatements();
        final PsiReturnStatement only = returns.getFirst();
        // The one return must also be the body's last statement, or a void method falls off the end
        // as well and there are two exits after all.
        return statements.length > 0 && statements[statements.length - 1] == only
                ? List.of(only.getTextOffset())
                : List.of();
    }

    /**
     * Returns the offset of a body's closing brace.
     *
     * @param body the body to measure; must not be {@code null}
     * @return the closing brace's offset, or the body's end offset when it has no closing brace
     */
    private static int endOf(@NotNull final PsiCodeBlock body) {
        final PsiJavaToken brace = body.getRBrace();
        return brace == null ? body.getTextRange().getEndOffset() : brace.getTextOffset();
    }

    /**
     * Returns the literals a {@code CONSTANT} point matches, read from source.
     *
     * <p>Each literal is rendered by {@link CaretAnchors#constantTextOf(PsiLiteralExpression)}, which
     * uses the API's own {@code ConstantSelector} — the same routine the handler generator writes a
     * target with — and the rendering is compared with the declaration's target. A second spelling
     * would compare unequal to the annotation the reader is looking at.
     *
     * <p>Folded literals are skipped; see {@link #isFolded(PsiLiteralExpression)} for what counts as
     * folded and where that reasoning does not hold.
     *
     * @param at   the point declaration; must not be {@code null}
     * @param body the target's body; must not be {@code null}
     * @return the offsets of the matching literals; empty when no target was written
     */
    @NotNull
    private static List<Integer> constantSitesOf(@NotNull final At at,
                                                 @NotNull final PsiCodeBlock body) {
        if (!at.exact()) {
            return List.of();
        }
        final List<Integer> offsets = new ArrayList<>();
        for (final PsiLiteralExpression literal
                : PsiTreeUtil.findChildrenOfType(body, PsiLiteralExpression.class)) {
            // Spelled by CaretAnchors, which renders it with the API's own
            // ConstantSelector — the same routine the generator writes the target with. A
            // second spelling would compare unequal to the annotation in front of the reader.
            if (isFolded(literal)
                    || !at.target().equals(CaretAnchors.constantTextOf(literal))) {
                continue;
            }
            offsets.add(literal.getTextOffset());
        }
        return offsets;
    }

    /**
     * Reports whether a literal is one the compiler never loads on its own.
     *
     * <p>Two cases. A literal in a {@code case} label is treated as folded regardless of the switch's
     * selector type, and the search for the enclosing label stops at a code block so that a literal in
     * the label's body does not count. This is accurate for a label on an integral or {@code enum}
     * switch, where the label becomes part of the switch instruction's table rather than a load; a
     * {@code String} switch is desugared by the compiler into a {@code hashCode} switch plus
     * {@code equals} comparisons, and each label constant there is loaded like any other literal, so
     * this method under-reports loads for that case. A literal whose enclosing expression is itself a
     * compile-time constant is folded into it, and the constant that is loaded is the result.
     *
     * @param literal the literal to judge; must not be {@code null}
     * @return {@code true} when no load corresponds to this literal
     */
    private static boolean isFolded(@NotNull final PsiLiteralExpression literal) {
        // A case label is compiled into the switch instruction's table, not into a load before it.
        if (PsiTreeUtil.getParentOfType(literal, PsiSwitchLabelStatementBase.class, true,
                PsiCodeBlock.class) != null) {
            return true;
        }
        // Folded into whatever encloses it: the enclosing expression is itself a compile-time
        // constant, so the compiler computes it and loads the result instead.
        final PsiElement parent = literal.getParent();
        return parent instanceof final PsiExpression enclosing
                && PsiUtil.isConstantExpression(enclosing);
    }

    /**
     * Returns the field accesses a {@code FIELD} point matches, read from source.
     *
     * <p>The selector is parsed as a field, not left to the parser to guess: a bare {@code state} could
     * name a field or a method of any signature, and {@link MemberSelector#parse(String, MemberKind)}
     * takes the expected kind precisely because the text alone cannot decide. A target that does not
     * parse as a field selector yields nothing.
     *
     * <p>A reference qualifies when it resolves to a field of that name, when the selector's owner —
     * if it wrote one — names the field's class, and when the declaration's {@code access} matches the
     * way the field is being used here.
     *
     * @param at   the point declaration; must not be {@code null}
     * @param body the target's body; must not be {@code null}
     * @return the offsets of the matching references; empty when no target was written or it is not a
     *         field selector
     */
    @NotNull
    private static List<Integer> fieldSitesOf(@NotNull final At at,
                                              @NotNull final PsiCodeBlock body) {
        if (!at.exact()) {
            return List.of();
        }
        // The kind must be told. A bare `state` could name a field or a method of any
        // signature, and the parser says so — it takes the caller's context precisely because the
        // text alone cannot decide. Parsed without it, every unqualified field selector came back a
        // method selector and every FIELD point rendered nothing.
        if (!(selector(at.target(), MemberKind.FIELD) instanceof final FieldSelector wanted)) {
            return List.of();
        }

        final List<Integer> offsets = new ArrayList<>();
        for (final PsiReferenceExpression reference
                : PsiTreeUtil.findChildrenOfType(body, PsiReferenceExpression.class)) {
            if (!(reference.resolve() instanceof final PsiField field)
                    || !wanted.name().equals(field.getName())) {
                continue;
            }
            final Optional<TypePattern> owner = wanted.owner();
            if (owner.isPresent() && !ownerMatches(owner.get(), field.getContainingClass())) {
                continue;
            }
            if (accessMatches(at.access(), reference, field)) {
                offsets.add(reference.getTextOffset());
            }
        }
        return offsets;
    }

    /**
     * Reports whether a reference is the kind of access the declaration asked for.
     *
     * <p>{@code ANY} matches everything. Otherwise the {@code STATIC_} prefix must agree with the
     * field's own staticness, and then the reference is judged by what encloses it: the left side of a
     * plain {@code =} is a write only, the left side of a compound assignment such as {@code +=} is
     * both a read and a write, any operand of a unary expression is treated as both — which is
     * accurate for {@code ++} and {@code --} but also matches operands of {@code !}, unary
     * {@code -} and {@code ~}, none of which write the field — and anything else is a read.
     *
     * @param access    the declaration's access, as written; must not be {@code null}
     * @param reference the reference to the field; must not be {@code null}
     * @param field     the field it resolves to; must not be {@code null}
     * @return {@code true} when this access is one the declaration selects
     */
    private static boolean accessMatches(@NotNull final String access,
                                         @NotNull final PsiReferenceExpression reference,
                                         @NotNull final PsiField field) {
        if (ANY_ACCESS.equals(access)) {
            return true;
        }
        final boolean isStatic = field.hasModifierProperty(PsiModifier.STATIC);
        if (access.startsWith("STATIC_") != isStatic) {
            return false;
        }
        final boolean wantsWrite = access.endsWith("PUT");

        final PsiElement parent = reference.getParent();
        if (parent instanceof final PsiAssignmentExpression assignment
                && assignment.getLExpression() == reference) {
            // A plain `=` only writes; `+=` and friends read first.
            return wantsWrite
                    || !JavaTokenType.EQ.equals(assignment.getOperationTokenType());
        }
        if (parent instanceof PsiUnaryExpression) {
            // ++ and -- are a read and a write both.
            return true;
        }
        return !wantsWrite;
    }

    /**
     * Returns the instantiations a {@code NEW} point matches, read from source.
     *
     * <p>The target names a class rather than a member, so it is not parsed as a selector; slashes are
     * turned into dots and the result is compared with the resolved class's qualified name and with its
     * simple name, which lets a declaration name either.
     *
     * @param at   the point declaration; must not be {@code null}
     * @param body the target's body; must not be {@code null}
     * @return the offsets of the matching {@code new} expressions; empty when no target was written
     */
    @NotNull
    private static List<Integer> newSitesOf(@NotNull final At at,
                                            @NotNull final PsiCodeBlock body) {
        if (!at.exact()) {
            return List.of();
        }
        final String wanted = at.target().replace('/', '.');
        final List<Integer> offsets = new ArrayList<>();
        for (final PsiNewExpression created
                : PsiTreeUtil.findChildrenOfType(body, PsiNewExpression.class)) {
            final PsiJavaCodeReferenceElement reference = created.getClassReference();
            final PsiElement resolved = reference == null ? null : reference.resolve();
            if (resolved instanceof final PsiClass instantiated
                    && (wanted.equals(instantiated.getQualifiedName())
                    || wanted.equals(instantiated.getName()))) {
                offsets.add(created.getTextOffset());
            }
        }
        return offsets;
    }

    /**
     * Returns the throws a {@code THROW} point matches, read from source.
     *
     * <p>The only source matcher that accepts an empty target: a {@code THROW} naming no type matches
     * every {@code throw} in the body. Where a type is named, the thrown expression's canonical type or
     * its simple name must equal it, and a {@code throw} whose expression has no resolvable type is
     * skipped.
     *
     * @param at   the point declaration; must not be {@code null}
     * @param body the target's body; must not be {@code null}
     * @return the offsets of the matching {@code throw} statements; empty when the declaration pins an
     *         ordinal, shifts, or carries a slice
     */
    @NotNull
    private static List<Integer> throwSitesOf(@NotNull final At at,
                                              @NotNull final PsiCodeBlock body) {
        if (at.ordinal() != EVERY_MATCH || at.shifted() || at.sliced()) {
            return List.of();
        }
        final String wanted = at.target().replace('/', '.');
        final List<Integer> offsets = new ArrayList<>();
        for (final PsiThrowStatement thrown
                : PsiTreeUtil.findChildrenOfType(body, PsiThrowStatement.class)) {
            if (wanted.isBlank()) {
                offsets.add(thrown.getTextOffset());
                continue;
            }
            final PsiExpression exception = thrown.getException();
            final String type = exception == null || exception.getType() == null
                    ? null
                    : exception.getType().getCanonicalText();
            if (type != null && (wanted.equals(type) || wanted.equals(simpleName(type)))) {
                offsets.add(thrown.getTextOffset());
            }
        }
        return offsets;
    }

    /**
     * Parses a selector, treating any refusal as no answer.
     *
     * <p>The text comes out of an annotation the user is still typing, so a parse failure is the
     * ordinary case rather than an error to report.
     *
     * @param target   the selector text; must not be {@code null}
     * @param expected the kind of member the point's target names; must not be {@code null}
     * @return the parsed selector, or {@code null} when the text does not parse
     */
    @Nullable
    private static MemberSelector selector(@NotNull final String target,
                                           @NotNull final MemberKind expected) {
        try {
            return MemberSelector.parse(target, expected);
        } catch (final RuntimeException malformed) {
            return null;
        }
    }

    /**
     * Returns the calls an {@code INVOKE} or {@code INVOKE_AFTER} point matches, read from source.
     *
     * <p>Anchored on the call itself for both points. A call whose method cannot be resolved is
     * skipped.
     *
     * @param at   the point declaration; must not be {@code null}
     * @param body the target's body; must not be {@code null}
     * @return the offsets of the matching calls; empty when no target was written or it is not a method
     *         selector
     */
    @NotNull
    private static List<Integer> callSitesOf(@NotNull final At at,
                                             @NotNull final PsiCodeBlock body) {
        if (!at.exact()) {
            return List.of();
        }
        final MethodSelector selector = methodSelector(at.target());
        if (selector == null) {
            return List.of();
        }

        final List<Integer> offsets = new ArrayList<>();
        for (final PsiMethodCallExpression call
                : PsiTreeUtil.findChildrenOfType(body, PsiMethodCallExpression.class)) {
            final PsiMethod resolved = call.resolveMethod();
            if (resolved != null && matches(selector, resolved)) {
                offsets.add(call.getTextOffset());
            }
        }
        return offsets;
    }

    /**
     * Parses a target as the method selector a call site is matched against.
     *
     * @param target the selector text; must not be {@code null}
     * @return the selector, or {@code null} when the text does not parse as one or names an initialiser
     *         rather than a call
     */
    @Nullable
    private static MethodSelector methodSelector(@NotNull final String target) {
        final MemberSelector parsed = selector(target, MemberKind.METHOD);
        return parsed instanceof final MethodSelector method && !method.isInitialiser()
                ? method
                : null;
    }

    /**
     * Reports whether a resolved method is the one a selector names.
     *
     * <p>The name must be equal, and the owner — where one was written — must name the method's class.
     * A selector with no parameter list names every overload and matches each of them; one with a
     * parameter list must agree in arity and in every parameter type.
     *
     * @param selector the parsed selector; must not be {@code null}
     * @param resolved the method the call resolved to; must not be {@code null}
     * @return {@code true} when the selector names this method
     */
    private static boolean matches(@NotNull final MethodSelector selector,
                                   @NotNull final PsiMethod resolved) {
        if (!selector.name().equals(resolved.getName())) {
            return false;
        }
        final Optional<TypePattern> owner = selector.owner();
        if (owner.isPresent() && !ownerMatches(owner.get(), resolved.getContainingClass())) {
            return false;
        }
        final Optional<List<TypePattern>> parameters = selector.parameters();
        if (parameters.isEmpty()) {
            // No signature: the selector names every overload, and every overload is a real match.
            return true;
        }

        final PsiParameter[] actual = resolved.getParameterList().getParameters();
        final List<TypePattern> wanted = parameters.get();
        if (actual.length != wanted.size()) {
            return false;
        }
        for (int index = 0; index < actual.length; index++) {
            if (!typeMatches(wanted.get(index), actual[index].getType().getPresentableText())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reports whether a type pattern names the given class.
     *
     * <p>Asked of {@link SelectorTargets#namesClass(TypePattern, PsiClass)} rather than answered here,
     * so that the binary name a nested owner has to be written with is accepted in one place instead of
     * in each caller.
     *
     * @param pattern the owner the selector wrote; must not be {@code null}
     * @param owner   the class to test, or {@code null} when the member has none
     * @return {@code true} when the pattern names that class
     */
    private static boolean ownerMatches(@NotNull final TypePattern pattern,
                                        @Nullable final PsiClass owner) {
        // Asked of SelectorTargets rather than answered here. This comparison existed twice,
        // and both copies were missing the binary name a nested owner is written with — which is
        // the spelling the engine requires, so every injection naming one drew no block at all.
        // Two copies of a rule is two chances to be the one that is wrong.
        return SelectorTargets.namesClass(pattern, owner);
    }

    /**
     * Reports whether a parameter type pattern describes a parameter of the resolved method.
     *
     * <p>{@link TypePattern.Any} matches everything. Otherwise both sides are reduced to a simple name
     * before they are compared, because the selector may be qualified where the source is not and the
     * reverse, and generic arguments are erased away, because the engine matches on the erased
     * signature.
     *
     * @param pattern the type the selector wrote; must not be {@code null}
     * @param actual  the parameter's presentable type; must not be {@code null}
     * @return {@code true} when the pattern describes that type
     */
    private static boolean typeMatches(@NotNull final TypePattern pattern,
                                       @NotNull final String actual) {
        if (pattern instanceof TypePattern.Any) {
            return true;
        }
        final String named = pattern.renderSource().replace('/', '.');
        // Compared on the simple name at both ends: the selector may be qualified where the source
        // is not, and the reverse. Generic arguments are not part of the erased signature the engine
        // matches on, so they are dropped rather than compared.
        return simpleName(named).equals(simpleName(erase(actual)));
    }

    /**
     * Drops the generic arguments from a written type.
     *
     * <p>Cut at the first {@code <} and resumed after the last {@code >}, so a nested argument list
     * goes with it and an array of a generic type keeps its brackets.
     *
     * @param type the type as written; must not be {@code null}
     * @return the type without its generic arguments
     */
    @NotNull
    private static String erase(@NotNull final String type) {
        final int angle = type.indexOf('<');
        return angle < 0
                ? type
                : type.substring(0, angle) + type.substring(type.lastIndexOf('>') + 1);
    }

    /**
     * Reduces a dotted name to its last segment.
     *
     * @param name the name to reduce; must not be {@code null}
     * @return the text after the last dot, or {@code name} when it has none
     */
    @NotNull
    private static String simpleName(@NotNull final String name) {
        final int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    /**
     * Builds the section for one point and kind out of the handlers gathered there.
     *
     * <p>The handlers are sorted into execution order and then counted rather than repeated: one
     * handler can apply several times on one line — two calls to the same method, say — and drawing its
     * body once per application would read as a rendering fault instead of as information. Its name
     * carries a multiplication sign and the count instead, so two applications of
     * {@code Audit.onCall()} render as {@code Audit.onCall() ×2}.
     *
     * <p>The header ends with two spaces and the point's tag, and carries nothing else. The tag is what
     * the reader wrote in the weave; that a redirect replaces rather than adds is said by the section's
     * colour and by its explanation.
     *
     * @param handlers the handlers gathered at this point and kind, in no particular order; must not be
     *                 {@code null}
     * @param point    the point's name; must not be {@code null}
     * @param kind     whether these handlers add or replace; must not be {@code null}
     * @param project  the project whose Java highlighter colours the handler bodies; must not be
     *                 {@code null}
     * @return the section
     */
    @NotNull
    private static WeaveBlock.Section sectionOf(@NotNull final List<Handler> handlers,
                                                @NotNull final String point,
                                                @NotNull final WeaveBlock.Kind kind,
                                                @NotNull final Project project) {
        // The engine's order, mirrored. ReferencesSearch answers in whatever order the index
        // happens to produce, which changes with what was edited last — so without this the two
        // handlers on one point swapped places as the reader clicked around. Worse than untidy: the
        // list is a claim about which handler runs first.
        final List<Handler> ordered = new ArrayList<>(handlers);
        ordered.sort(EXECUTION_ORDER);

        // Counted, not repeated. One handler can apply several times on one line — two calls to
        // the same method, say — and listing it once per application says "this ran twice" by
        // showing the same code twice, which reads as a rendering fault rather than as information.
        final Map<String, Integer> times = new LinkedHashMap<>();
        final List<Handler> distinct = new ArrayList<>();
        for (final Handler handler : ordered) {
            final String named = handler.describe();
            if (times.merge(named, 1, Integer::sum) == 1) {
                distinct.add(handler);
            }
        }

        final StringBuilder header = new StringBuilder();
        final List<List<WeaveBlock.Fragment>> lines = new ArrayList<>();
        for (final Handler handler : distinct) {
            if (!header.isEmpty()) {
                header.append(", ");
            }
            header.append(handler.describe());
            final int applied = times.get(handler.describe());
            if (applied > 1) {
                header.append(" \u00d7").append(applied);
            }
            lines.addAll(highlight(handler.body(), project));
        }
        // The tag alone, and nothing else. `@HEAD` is what the reader wrote in the weave and what
        // they scan for; a sentence in its place is longer, softer and says the same thing to
        // somebody who already knows it. That a redirect replaces rather than adds is carried by the
        // section's colour and spelled out on the hover — deliberately not in the header, which
        // stays as short as the annotation it mirrors.
        header.append("  @").append(point);
        return new WeaveBlock.Section(kind, header.toString(),
                explain(kind, point, distinct), List.copyOf(lines));
    }

    /**
     * Renders the sentence shown on the block's gutter tooltip.
     *
     * <p>A redirect is described as replacing, whatever its point. An injection is described by its
     * point, and a point that names something quotes the first handler's target: an
     * {@code INVOKE} on {@code helper} reads {@code runs before the call to helper}. A
     * {@code THROW} with no target, and a redirect with none, fall back to describing the position
     * itself, and a point this release does not know is described by its own name.
     *
     * @param kind     whether the handlers add or replace; must not be {@code null}
     * @param point    the point's name; must not be {@code null}
     * @param handlers the section's handlers, whose first supplies the target; must not be {@code null}
     * @return the sentence
     */
    @NotNull
    private static String explain(@NotNull final WeaveBlock.Kind kind,
                                  @NotNull final String point,
                                  @NotNull final List<Handler> handlers) {
        final String target = handlers.isEmpty() ? "" : handlers.getFirst().at().target();
        if (kind == WeaveBlock.Kind.REDIRECT) {
            return target.isBlank()
                    ? "replaces the operation here"
                    : "replaces the call to " + target;
        }
        return switch (point) {
            case HEAD -> "runs on entry";
            case RETURN -> "runs before returning";
            case TAIL -> "runs once, on the way out";
            case INVOKE -> "runs before the call to " + target;
            case INVOKE_AFTER -> "runs after the call to " + target;
            case FIELD -> "runs at the access to " + target;
            case NEW -> "runs where " + target + " is created";
            case THROW -> target.isBlank()
                    ? "runs before this throw"
                    : "runs before " + target + " is thrown";
            default -> "runs at " + point;
        };
    }

    /**
     * Returns the offsets of a point by resolving it against the target's class file.
     *
     * <p>Four things have to be true before anything is read: the target has an owning class and a
     * containing file, {@link EditorLines#canPlace(PsiFile)} accepts that file, and
     * {@link CompiledClasses} produces a view of the compiled class. The method is then matched by name
     * and descriptor, the declaration is rebuilt as a {@code PointSpec}, and the positions come from
     * {@link TargetOperations}.
     *
     * <p>The lines that come back are the class file's, not the editor's; they are translated by
     * {@link EditorLines} and a line outside the document is dropped rather than clamped.
     *
     * @param target the method being woven; must not be {@code null}
     * @param at     the point declaration; must not be {@code null}
     * @return the offsets of the starts of the matching lines; empty when any of the steps above cannot
     *         be taken
     */
    @NotNull
    private static List<Integer> compiledOffsetsOf(@NotNull final PsiMethod target,
                                                   @NotNull final At at) {
        final PsiClass owner = target.getContainingClass();
        final PsiFile file = target.getContainingFile();
        if (owner == null || file == null || !EditorLines.canPlace(file)) {
            return List.of();
        }
        final CompiledClasses.Lookup lookup = CompiledClasses.of(owner);
        if (!lookup.isAvailable()) {
            return List.of();
        }
        final MethodView compiled = compiledMethodOf(lookup.view(), target);
        final PointSpec spec = specOf(at);
        if (compiled == null || spec == null) {
            return List.of();
        }
        final Document document =
                PsiDocumentManager.getInstance(target.getProject()).getDocument(file);
        if (document == null) {
            return List.of();
        }

        final List<Integer> offsets = new ArrayList<>();
        for (final int compiledLine : CompiledLines.of(compiled,
                TargetOperations.sitesOf(compiled, spec, slicesOf(at)))) {
            // Two coordinate systems. CompiledLines answers in the class file's, which is the
            // editor's only when the file on screen is the one that was compiled.
            final int line = EditorLines.of(file, compiledLine);
            if (line >= 1 && line <= document.getLineCount()) {
                offsets.add(document.getLineStartOffset(line - 1));
            }
        }
        return offsets;
    }

    /**
     * Rebuilds a point declaration as the {@code PointSpec} the engine resolves.
     *
     * <p>The shift and access names are turned back into their API constants, and a target that was
     * written is parsed in the grammar {@link PointTargets#selectorKindFor(String)} names for the
     * point. A point that method names no grammar for keeps its target as text; {@code NEW}, whose
     * target is a class rather than a member, is one of those. Anything the API refuses on the way,
     * a constant name no enum declares included, comes back as no specification at all.
     *
     * @param at the point declaration; must not be {@code null}
     * @return the specification, or {@code null} when the declaration is half-typed or names something
     *         this release does not know
     */
    @Nullable
    private static PointSpec specOf(@NotNull final At at) {
        try {
            final PointSpec.Builder builder = PointSpec.named(at.point())
                    .ordinal(at.ordinal())
                    .shift(de.splatgames.aether.weaver.api.At.Shift.valueOf(at.shift()))
                    .by(at.by())
                    .access(de.splatgames.aether.weaver.api.At.Access.valueOf(at.access()));
            if (at.target().isBlank()) {
                return builder.build();
            }
            final MemberKind expected = PointTargets.selectorKindFor(at.point());
            return expected == null
                    ? builder.target(at.target()).build()
                    : builder.target(at.target(), MemberSelector.parse(at.target(), expected))
                            .build();
        } catch (final RuntimeException malformed) {
            // Half-typed, or a point this release does not know. Either way there is nothing to
            // draw and nothing to say — the inspections report on what the user wrote.
            return null;
        }
    }

    /**
     * Rebuilds a declaration's slice as the region the engine bounds a search with.
     *
     * <p>A slice bound has to resolve to exactly one position, so a bound whose ordinal was not
     * pinned is refused here rather than left to {@code SliceSpec}, which rejects one. A bound that
     * does not specify at all is refused the same way.
     *
     * @param at the point declaration; must not be {@code null}
     * @return a list holding the one unnamed slice, or empty when there is no slice or either bound is
     *         unusable
     */
    @NotNull
    private static List<SliceSpec> slicesOf(@NotNull final At at) {
        final Slice slice = at.slice();
        if (slice == null) {
            return List.of();
        }
        final PointSpec from = specOf(slice.from());
        final PointSpec to = specOf(slice.to());
        if (from == null || to == null || from.matchesAll() || to.matchesAll()) {
            // A bound without an ordinal resolves to several positions and bounds nothing.
            // SliceSpec refuses it; this refuses first rather than letting the refusal throw.
            return List.of();
        }
        return List.of(new SliceSpec("", from, to));
    }

    /**
     * Finds the compiled form of a method in a parsed class.
     *
     * <p>Matched on name and descriptor together, since two overloads hold entirely different
     * instructions.
     *
     * @param view   the parsed class; must not be {@code null}
     * @param target the method to find; must not be {@code null}
     * @return the compiled method, or {@code null} when the class declares no method of that name and
     *         descriptor
     */
    @Nullable
    private static MethodView compiledMethodOf(@NotNull final TargetView view,
                                               @NotNull final PsiMethod target) {
        // Name and descriptor together: two overloads hold entirely different instructions.
        final String descriptor = ClassUtil.getAsmMethodSignature(target);
        for (final MethodView candidate : view.methods()) {
            if (candidate.name().equals(target.getName())
                    && candidate.type().descriptorString().equals(descriptor)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Returns the offset a {@code HEAD} point is drawn above.
     *
     * <p>The first statement, where there is one. A body with no statements still has an inside, and
     * the block belongs in it: the body's own offset is its opening brace, which sits on the signature
     * line, and a block drawn there reads as running before the method is entered. So the first thing
     * after the brace is taken instead — a comment, or the closing brace, either of which is inside.
     *
     * @param body the target's body; must not be {@code null}
     * @return the offset to anchor on
     */
    private static int entryOf(@NotNull final PsiCodeBlock body) {
        final PsiStatement[] statements = body.getStatements();
        if (statements.length > 0) {
            return statements[0].getTextOffset();
        }

        // A body with no statements still has an inside, and the block belongs in it. Anchoring
        // on the body itself anchors on its opening brace — which sits on the signature line, so the
        // block was drawn above the declaration and read as running before the method was entered.
        // The first thing after the brace is a comment, or the closing brace; either is inside.
        for (PsiElement child = body.getFirstChild(); child != null;
             child = child.getNextSibling()) {
            if (child instanceof PsiWhiteSpace) {
                continue;
            }
            if (child instanceof final PsiJavaToken token
                    && token.getTokenType() == JavaTokenType.LBRACE) {
                continue;
            }
            return child.getTextOffset();
        }
        return body.getTextRange().getEndOffset();
    }

    /**
     * Returns the handlers that name the given method.
     *
     * <p>References to the method are searched over the scope
     * {@link WeaveTargetIndex#weavesTargeting(PsiClass)} returns, and only a {@code SelectorReference}
     * counts: an ordinary Java call site is not an injection. Each such reference contributes a handler
     * when it is inside a method and its annotation yields a readable {@code @At}.
     *
     * @param target the method being woven; must not be {@code null}
     * @return the handlers, in whatever order the search produced them
     */
    @NotNull
    private static List<Handler> handlersFor(@NotNull final PsiMethod target) {
        final List<Handler> handlers = new ArrayList<>();
        // The access scope is deliberately ignored; see the marker provider for the whole of it.
        // A weave edits a class file rather than calling into it, so whether Java would let the
        // weave's package reference the member has nothing to do with whether it weaves it.
        for (final PsiReference reference : ReferencesSearch
                .search(new ReferencesSearch.SearchParameters(target,
                        WeaveTargetIndex.weavesTargeting(target.getContainingClass()), true))
                .findAll()) {
            if (!(reference instanceof SelectorReference)) {
                // Ordinary Java call sites are not injections.
                continue;
            }
            final PsiElement literal = reference.getElement();
            final PsiMethod handler = PsiTreeUtil.getParentOfType(literal, PsiMethod.class);
            final At at = atOf(literal);
            if (handler != null && at != null) {
                handlers.add(new Handler(handler, at, kindOf(literal)));
            }
        }
        return handlers;
    }

    /**
     * Returns the kind of section an element's annotation produces.
     *
     * <p>Only {@code @Redirect} replaces. Everything else, {@code @Wrap} included, is drawn as an
     * injection.
     *
     * @param literal the element inside the annotation; must not be {@code null}
     * @return {@link WeaveBlock.Kind#REDIRECT} for a {@code @Redirect}, {@link WeaveBlock.Kind#INJECT}
     *         otherwise
     */
    @NotNull
    private static WeaveBlock.Kind kindOf(@NotNull final PsiElement literal) {
        final PsiAnnotation annotation = PsiTreeUtil.getParentOfType(literal, PsiAnnotation.class);
        return annotation != null && REDIRECT_ANNOTATION.equals(annotation.getQualifiedName())
                ? WeaveBlock.Kind.REDIRECT
                : WeaveBlock.Kind.INJECT;
    }

    /**
     * Reads the point declaration around an element, together with the slice that narrows it.
     *
     * <p>The {@code at} attribute may be written as a single {@code @At} or as an array. An array of
     * exactly one is unwrapped; an array of any other size yields nothing, because which of several
     * points a given site belongs to cannot be decided from the array alone.
     *
     * @param literal the element inside the injection annotation; must not be {@code null}
     * @return the declaration, or {@code null} when there is no enclosing annotation, no single
     *         {@code @At} in it, or the {@code @At} names no point
     */
    @Nullable
    private static At atOf(@NotNull final PsiElement literal) {
        final PsiAnnotation inject = PsiTreeUtil.getParentOfType(literal, PsiAnnotation.class);
        if (inject == null) {
            return null;
        }
        PsiAnnotationMemberValue at = inject.findAttributeValue(AT_ATTRIBUTE);
        if (at instanceof final PsiArrayInitializerMemberValue array) {
            final PsiAnnotationMemberValue[] initialisers = array.getInitializers();
            if (initialisers.length != 1) {
                // Several points on one injection: which site is which cannot be decided from the
                // array alone, so nothing is claimed.
                return null;
            }
            at = initialisers[0];
        }
        if (!(at instanceof final PsiAnnotation point)) {
            return null;
        }
        final String named =
                named(point.findAttributeValue(PsiAnnotation.DEFAULT_REFERENCED_METHOD_NAME));
        if (named == null) {
            return null;
        }
        return atFrom(point, sliceOf(inject));
    }

    /**
     * Reads one {@code @At} into the form the matchers use.
     *
     * <p>Every attribute has a stand-in for the case where it was not written or cannot be read as a
     * constant of the expected shape: no target is an empty string, no ordinal is
     * {@link #EVERY_MATCH}, no shift is {@code NONE}, no {@code by} is {@code 0} and no access is
     * {@code ANY}.
     *
     * @param point the {@code @At} to read; must not be {@code null}
     * @param slice the slice narrowing it, or {@code null} when the declaration carries none
     * @return the declaration, or {@code null} when the annotation names no point
     */
    @Nullable
    private static At atFrom(@NotNull final PsiAnnotation point, @Nullable final Slice slice) {
        final String named =
                named(point.findAttributeValue(PsiAnnotation.DEFAULT_REFERENCED_METHOD_NAME));
        if (named == null) {
            return null;
        }
        final String shift = named(point.findAttributeValue(SHIFT_ATTRIBUTE));
        return new At(named,
                text(point.findAttributeValue(TARGET_ATTRIBUTE)),
                number(point.findAttributeValue(ORDINAL_ATTRIBUTE)),
                shift == null ? NO_SHIFT : shift,
                number(point.findAttributeValue(BY_ATTRIBUTE), 0),
                slice,
                accessOf(point));
    }

    /**
     * Reads an injection's slice.
     *
     * <p>As with {@code at}, an array of exactly one slice is unwrapped and any other size yields
     * nothing.
     *
     * @param inject the injection annotation; must not be {@code null}
     * @return the slice, or {@code null} when there is none, when several were written, or when either
     *         bound cannot be read
     */
    @Nullable
    private static Slice sliceOf(@NotNull final PsiAnnotation inject) {
        PsiAnnotationMemberValue value = inject.findAttributeValue(SLICE_ATTRIBUTE);
        if (value instanceof final PsiArrayInitializerMemberValue array) {
            if (array.getInitializers().length != 1) {
                return null;
            }
            value = array.getInitializers()[0];
        }
        if (!(value instanceof final PsiAnnotation slice)) {
            return null;
        }
        final At from = boundOf(slice, FROM_ATTRIBUTE);
        final At to = boundOf(slice, TO_ATTRIBUTE);
        return from == null || to == null ? null : new Slice(from, to);
    }

    /**
     * Reads one bound of a slice.
     *
     * <p>A bound carries no slice of its own, so the recursion stops here.
     *
     * @param slice     the {@code @Slice} to read from; must not be {@code null}
     * @param attribute the bound's attribute name; must not be {@code null}
     * @return the bound, or {@code null} when that attribute holds no {@code @At}
     */
    @Nullable
    private static At boundOf(@NotNull final PsiAnnotation slice, @NotNull final String attribute) {
        return slice.findAttributeValue(attribute) instanceof final PsiAnnotation bound
                ? atFrom(bound, null)
                : null;
    }

    /**
     * Reads an {@code @At}'s access.
     *
     * @param point the {@code @At} to read; must not be {@code null}
     * @return the constant's name, or {@code ANY} when none was written
     */
    @NotNull
    private static String accessOf(@NotNull final PsiAnnotation point) {
        final String named = named(point.findAttributeValue(ACCESS_ATTRIBUTE));
        return named == null ? ANY_ACCESS : named;
    }

    /**
     * Reports whether an injection annotation carries a slice at all.
     *
     * <p>Answers from the annotation, where {@code At.sliced()} answers from a declaration that has
     * already been read; a non-empty array counts here, an empty one does not, where reading one
     * requires exactly one.
     *
     * @param inject the injection annotation; must not be {@code null}
     * @return {@code true} when a slice is written
     */
    private static boolean sliced(@NotNull final PsiAnnotation inject) {
        final PsiAnnotationMemberValue slices = inject.findAttributeValue(SLICE_ATTRIBUTE);
        if (slices instanceof final PsiArrayInitializerMemberValue array) {
            return array.getInitializers().length > 0;
        }
        return slices instanceof PsiAnnotation;
    }

    /**
     * Reads an attribute written as a constant's name.
     *
     * @param value the attribute value, or {@code null} when the attribute is absent
     * @return the reference's own name, without a qualifier, or {@code null} when the value is not a
     *         reference
     */
    @Nullable
    private static String named(@Nullable final PsiAnnotationMemberValue value) {
        return value instanceof final PsiReferenceExpression reference
                ? reference.getReferenceName()
                : null;
    }

    /**
     * Reads an attribute written as a string literal.
     *
     * @param value the attribute value, or {@code null} when the attribute is absent
     * @return the string, or an empty string when the value is not a string literal
     */
    @NotNull
    private static String text(@Nullable final PsiAnnotationMemberValue value) {
        return value instanceof final PsiLiteralExpression literal
                && literal.getValue() instanceof final String string
                ? string
                : "";
    }

    /**
     * Reads an attribute written as an integer literal, defaulting to every match.
     *
     * @param value the attribute value, or {@code null} when the attribute is absent
     * @return the integer, or {@link #EVERY_MATCH} when the value is not an integer literal
     */
    private static int number(@Nullable final PsiAnnotationMemberValue value) {
        return number(value, EVERY_MATCH);
    }

    /**
     * Reads an attribute written as an integer literal.
     *
     * @param value    the attribute value, or {@code null} when the attribute is absent
     * @param fallback what to answer when it is not an integer literal
     * @return the integer, or {@code fallback}
     */
    private static int number(@Nullable final PsiAnnotationMemberValue value, final int fallback) {
        return value instanceof final PsiLiteralExpression literal
                && literal.getValue() instanceof final Integer integer
                ? integer
                : fallback;
    }

    /**
     * Splits handler source into the coloured fragments a block draws.
     *
     * <p>Run through the project's Java highlighting lexer. Each token contributes its text and the
     * last of the keys {@link SyntaxHighlighter#getTokenHighlights} offers for it; a token the
     * highlighter has no key for contributes a fragment with none. Tokens are split on newlines, so a
     * fragment never spans a line, and an empty piece contributes no fragment at all.
     *
     * @param source  the handler body to colour; must not be {@code null}
     * @param project the project whose highlighter is used; must not be {@code null}
     * @return the lines, trimmed and capped
     */
    @NotNull
    private static List<List<WeaveBlock.Fragment>> highlight(@NotNull final String source,
                                                             @NotNull final Project project) {
        final SyntaxHighlighter highlighter = SyntaxHighlighterFactory
                .getSyntaxHighlighter(JavaLanguage.INSTANCE, project, null);
        final List<List<WeaveBlock.Fragment>> lines = new ArrayList<>();
        List<WeaveBlock.Fragment> current = new ArrayList<>();

        final Lexer lexer = highlighter.getHighlightingLexer();
        lexer.start(source);
        while (lexer.getTokenType() != null) {
            final TextAttributesKey[] keys = highlighter.getTokenHighlights(lexer.getTokenType());
            // The last key is the most specific one the highlighter offers.
            final TextAttributesKey key = keys.length == 0 ? null : keys[keys.length - 1];
            final String text = source.substring(lexer.getTokenStart(), lexer.getTokenEnd());

            final String[] parts = text.split("\n", -1);
            for (int index = 0; index < parts.length; index++) {
                if (index > 0) {
                    lines.add(List.copyOf(current));
                    current = new ArrayList<>();
                }
                if (!parts[index].isEmpty()) {
                    current.add(new WeaveBlock.Fragment(parts[index], key));
                }
            }
            lexer.advance();
        }
        lines.add(List.copyOf(current));
        return trim(lines);
    }

    /**
     * Removes the blank lines around a handler body and caps its length.
     *
     * <p>Leading and trailing blank lines go; blank lines in the middle stay, since they are the
     * author's own spacing. A body longer than {@link #MAX_LINES} keeps its first
     * {@code MAX_LINES - 1} lines and ends with a line counting the rest, so that a handler long
     * enough to swallow the file does not bury the target it is describing.
     *
     * @param lines the coloured lines to trim; must not be {@code null}
     * @return the lines to draw, never longer than {@link #MAX_LINES}
     */
    @NotNull
    private static List<List<WeaveBlock.Fragment>> trim(
            @NotNull final List<List<WeaveBlock.Fragment>> lines) {
        final List<List<WeaveBlock.Fragment>> kept = new ArrayList<>();
        for (final List<WeaveBlock.Fragment> line : lines) {
            final boolean blank = line.stream().allMatch(fragment -> fragment.text().isBlank());
            if (!blank || !kept.isEmpty()) {
                kept.add(line);
            }
        }
        while (!kept.isEmpty()
                && kept.getLast().stream().allMatch(fragment -> fragment.text().isBlank())) {
            kept.removeLast();
        }
        if (kept.size() <= MAX_LINES) {
            return List.copyOf(kept);
        }
        // A handler long enough to swallow the file is summarised rather than shown whole: the
        // point of this view is to keep the target readable.
        final List<List<WeaveBlock.Fragment>> capped =
                new ArrayList<>(kept.subList(0, MAX_LINES - 1));
        capped.add(List.of(new WeaveBlock.Fragment(
                "… " + (kept.size() - MAX_LINES + 1) + " more lines", null)));
        return List.copyOf(capped);
    }

    /**
     * The two bounds of a declaration's slice, as read from source.
     *
     * @param from the lower bound
     * @param to   the upper bound
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private record Slice(@NotNull At from, @NotNull At to) {
    }

    /**
     * One {@code @At} as read from source, with a stand-in wherever an attribute could not be read.
     *
     * <p>Everything is text: the point and the two enum-valued attributes are kept as the names that
     * were written rather than as constants, so a name this release does not declare travels this far
     * and is refused where it is turned back into API types.
     *
     * @param point   the point's name, as written
     * @param target  the {@code target} attribute, or an empty string when none was written
     * @param ordinal the {@code ordinal} attribute, or {@code EVERY_MATCH}
     * @param shift   the {@code shift} constant's name, or {@code NONE}
     * @param by      the {@code by} attribute, or {@code 0}
     * @param slice   the enclosing declaration's single slice, or {@code null}
     * @param access  the {@code access} constant's name, or {@code ANY}
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private record At(@NotNull String point,
                      @NotNull String target,
                      int ordinal,
                      @NotNull String shift,
                      int by,
                      @Nullable Slice slice,
                      @NotNull String access) {

        /**
         * Reports whether the declaration moves the position off the match.
         *
         * @return {@code true} when a shift other than {@code NONE} was written
         */
        boolean shifted() {
            return !NO_SHIFT.equals(this.shift);
        }

        /**
         * Reports whether the declaration narrows the search to a region.
         *
         * @return {@code true} when a slice was read
         */
        boolean sliced() {
            return this.slice != null;
        }

        /**
         * Reports whether this declaration can only be placed against the class file.
         *
         * @return {@code true} when an ordinal is pinned, a shift is named or a slice is written, each of
         *         which counts instructions rather than source constructs
         */
        boolean needsCompiled() {
            return this.ordinal != EVERY_MATCH || shifted() || sliced();
        }

        /**
         * Reports whether the source matchers can answer for this declaration.
         *
         * @return {@code true} when a target was written and nothing about the declaration counts
         *         instructions
         */
        boolean exact() {
            return !this.target.isBlank()
                    && this.ordinal == EVERY_MATCH
                    && !shifted()
                    && !sliced();
        }
    }

    /**
     * One handler method, and the declaration that places it in the target.
     *
     * @param method the handler
     * @param at     the point declaration that places it
     * @param kind   whether it adds to the target or replaces an operation in it
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private record Handler(@NotNull PsiMethod method,
                           @NotNull At at,
                           @NotNull WeaveBlock.Kind kind) {

        /**
         * Renders the handler as it is named in a section header.
         *
         * <p>The weave's simple class name, a dot, the handler's name and empty brackets, as in
         * {@code Audit.onCharge()}. A handler with no enclosing class renders as the name alone.
         *
         * @return the rendered name
         */
        @NotNull
        String describe() {
            final PsiClass weave = PsiTreeUtil.getParentOfType(this.method, PsiClass.class);
            return (weave == null ? "" : weave.getName() + ".") + this.method.getName() + "()";
        }

        /**
         * Returns the handler's body as the text to draw, without its braces.
         *
         * <p>The handler is indented for its own class and is shown against the target's code, so the
         * indentation its non-blank lines have in common is removed from every line. Blank lines are
         * emptied rather than shortened.
         *
         * @return the body's text, or an empty string when the handler has no body
         */
        @NotNull
        String body() {
            final PsiCodeBlock body = this.method.getBody();
            if (body == null) {
                return "";
            }
            final String text = body.getText();
            final String inner = text.length() < 2 ? "" : text.substring(1, text.length() - 1);

            // The handler is indented for its own class; shown here it must line up with the
            // target's code instead, so the common indentation is removed rather than kept.
            final List<String> raw = inner.lines().filter(line -> !line.isBlank()).toList();
            final int common = raw.stream().mapToInt(Handler::indentOf).min().orElse(0);
            final List<String> moved = new ArrayList<>();
            for (final String line : inner.lines().toList()) {
                moved.add(line.isBlank() ? "" : line.substring(Math.min(common, indentOf(line))));
            }
            return String.join("\n", moved);
        }

        /**
         * Counts the leading whitespace characters of a line.
         *
         * <p>Characters, not columns: this figure is only ever used to cut the same number of characters
         * off the front of a line that was measured the same way.
         *
         * @param line the line to measure; must not be {@code null}
         * @return the number of leading whitespace characters
         */
        private static int indentOf(@NotNull final String line) {
            int indent = 0;
            while (indent < line.length() && Character.isWhitespace(line.charAt(indent))) {
                indent++;
            }
            return indent;
        }
    }
}
