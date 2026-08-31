package de.splatgames.aether.weaver.idea.psi;

import com.intellij.openapi.editor.Document;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiIfStatement;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiLoopStatement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiReturnStatement;
import com.intellij.psi.PsiSwitchStatement;
import com.intellij.psi.PsiSynchronizedStatement;
import com.intellij.psi.PsiTryStatement;
import com.intellij.psi.PsiType;
import com.intellij.psi.util.ClassUtil;
import com.intellij.psi.util.PsiTreeUtil;
import de.splatgames.aether.weaver.api.select.ConstantSelector;
import de.splatgames.aether.weaver.api.select.MemberSelector;
import de.splatgames.aether.weaver.idea.bytecode.SourceAnchor;
import de.splatgames.aether.weaver.idea.bytecode.SpotFinder;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.constant.ConstantDesc;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads what the editor knows about the expression a caret stands in, in the spelling a class file uses.
 *
 * <p>The source half of the search {@link de.splatgames.aether.weaver.idea.bytecode.SpotFinder} performs. Everything
 * produced here is written the way the bytecode side writes it — owners as internal names with {@code /} separators,
 * method descriptors, constants as rendered {@code ConstantSelector} text — so that an anchor and an instruction can
 * be compared without either side re-rendering the other's form.
 *
 * <p>Nothing here resolves more than the editor already has. A reference the editor cannot resolve yields an anchor
 * with the unresolved components left {@code null} rather than no anchor at all, because an absent component must not
 * exclude a candidate instruction; the components that could be read still narrow the search.
 *
 * <h2>Line numbers</h2>
 *
 * <p>Every line this class reports is one-based, converted from the document's zero-based numbering, because the
 * numbers it is compared against come from a class file's line number table. An offset outside the document is
 * reported as line {@code 0}, which no line table entry carries.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class CaretAnchors {

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private CaretAnchors() {
        throw new AssertionError("no instances");
    }

    /**
     * Reads the caret's surroundings, from the expression it stands on outwards to the target method.
     *
     * <p>The walk stops at {@code target} and at the file, so a caret in a lambda or an anonymous class inside the
     * target still contributes the expressions between it and the method. Only elements one of the five anchor kinds
     * can be read from produce an anchor, and each one that does takes the next depth, so depth {@code 0} is the
     * innermost anchor found rather than the caret element itself — a caret on a name inside an argument list is
     * answered with the call around it at depth {@code 0}.
     *
     * <p>The region is the nearest enclosing loop, {@code try}, {@code switch}, {@code synchronized} or {@code if}
     * below the target. The method body is deliberately not one: a slice spanning the whole method bounds nothing.
     *
     * @param element  the element the caret is on; must not be {@code null}
     * @param target   the method the caret is in, which bounds the walk outwards; must not be {@code null}
     * @param document the document the element belongs to, which supplies the line numbers; must not be {@code null}
     * @return the reading, with its anchors innermost first and both region lines {@code 0} when the caret stands in
     *         no such block
     */
    @NotNull
    public static SpotFinder.Reading at(@NotNull final PsiElement element,
                                        @NotNull final PsiMethod target,
                                        @NotNull final Document document) {
        final List<SourceAnchor> anchors = new ArrayList<>();
        int depth = 0;
        for (PsiElement current = element;
             current != null && current != target && !(current instanceof com.intellij.psi.PsiFile);
             current = current.getParent()) {
            final SourceAnchor anchor = anchorOf(current, document, depth);
            if (anchor != null) {
                anchors.add(anchor);
                depth++;
            }
        }
        final PsiElement region = regionOf(element, target);
        return new SpotFinder.Reading(List.copyOf(anchors), firstLineOf(document, element),
                region == null ? 0 : firstLineOf(document, region),
                region == null ? 0 : lastLineOf(document, region));
    }

    /**
     * Describes one element on its own, without a caret to place it against.
     *
     * <p>The entry point for enumerating a whole method body rather than one position: every anchor it produces
     * carries depth {@code 0}, since there is no walk outwards to count.
     *
     * @param element  the element to describe; must not be {@code null}
     * @param document the document the element belongs to; must not be {@code null}
     * @return the anchor, or {@code null} when the element is not one of the five kinds an anchor can be read from,
     *         or is one of them but names nothing the search could use
     */
    @Nullable
    public static SourceAnchor describe(@NotNull final PsiElement element,
                                        @NotNull final Document document) {
        return anchorOf(element, document, 0);
    }

    /**
     * Describes one element, if it is a kind an injection point can name.
     *
     * <p>A reference expression counts only when it resolves to a field: a reference to a local, a parameter or a
     * class compiles to no instruction the {@code FIELD} point could match, and offering one would offer a target
     * that matches nothing.
     *
     * @param element  the element to describe; must not be {@code null}
     * @param document the document supplying the line numbers; must not be {@code null}
     * @param depth    the depth to record on the anchor
     * @return the anchor, or {@code null} when the element is not one of the five kinds an anchor can be read from,
     *         or is one of them but names nothing the search could use
     */
    @Nullable
    private static SourceAnchor anchorOf(@NotNull final PsiElement element,
                                         @NotNull final Document document,
                                         final int depth) {
        if (element instanceof final PsiMethodCallExpression call) {
            return callAnchor(call, document, depth);
        }
        if (element instanceof final PsiNewExpression created) {
            return newAnchor(created, document, depth);
        }
        if (element instanceof final PsiReferenceExpression reference
                && reference.resolve() instanceof final PsiField field) {
            return fieldAnchor(reference, field, document, depth);
        }
        if (element instanceof final PsiLiteralExpression literal) {
            return constantAnchor(literal, document, depth);
        }
        if (element instanceof PsiReturnStatement) {
            return new SourceAnchor(SourceAnchor.Kind.RETURN, null, null, null, null,
                    firstLineOf(document, element), lastLineOf(document, element), 0, depth);
        }
        return null;
    }

    /**
     * Describes a call.
     *
     * <p>An unresolved call still yields an anchor, named from the text at the call site and carrying no descriptor,
     * because a half-typed file is the ordinary state of a file being edited and the name alone already excludes
     * most instructions. A resolved constructor is named {@code <init>}, which is what the instruction carries.
     *
     * @param call     the call; must not be {@code null}
     * @param document the document supplying the line numbers; must not be {@code null}
     * @param depth    the depth to record on the anchor
     * @return the anchor, or {@code null} when the call site carries no name at all
     */
    @Nullable
    private static SourceAnchor callAnchor(@NotNull final PsiMethodCallExpression call,
                                           @NotNull final Document document,
                                           final int depth) {
        final PsiMethod resolved = call.resolveMethod();
        final String name = resolved != null
                ? resolved.isConstructor() ? "<init>" : resolved.getName()
                : call.getMethodExpression().getReferenceName();
        if (name == null) {
            return null;
        }
        return new SourceAnchor(SourceAnchor.Kind.CALL, ownerOfCall(call, resolved), name,
                descriptorOf(resolved), null, firstLineOf(document, call),
                lastLineOf(document, call),
                occurrenceOf(call, name, PsiMethodCallExpression.class, document), depth);
    }

    /**
     * Describes an object creation.
     *
     * <p>A primitive array creation such as {@code new int[3]} is refused: its element type has no class reference,
     * so the owner comes out {@code null} and no anchor is produced. A class-typed array creation such as
     * {@code new Foo[3]} keeps its class reference and is described the same as a plain {@code new Foo()}, since the
     * created expression itself, not the reference, is what carries the array dimensions.
     *
     * @param created  the creation expression; must not be {@code null}
     * @param document the document supplying the line numbers; must not be {@code null}
     * @param depth    the depth to record on the anchor
     * @return the anchor, or {@code null} when the created type could not be resolved to a class
     */
    @Nullable
    private static SourceAnchor newAnchor(@NotNull final PsiNewExpression created,
                                          @NotNull final Document document,
                                          final int depth) {
        final String owner = created.getClassReference() == null
                ? null
                : internalNameOf(created.getClassReference().resolve());
        return owner == null
                ? null
                : new SourceAnchor(SourceAnchor.Kind.INSTANTIATION, owner, null, null, null,
                        firstLineOf(document, created), lastLineOf(document, created), 0, depth);
    }

    /**
     * Describes a field read or write.
     *
     * <p>The owner is the class that declares the field rather than the type the reference was qualified with.
     *
     * @param reference the reference expression; must not be {@code null}
     * @param field     the field it resolved to; must not be {@code null}
     * @param document  the document supplying the line numbers; must not be {@code null}
     * @param depth     the depth to record on the anchor
     * @return the anchor, whose owner is {@code null} when the declaring class has no JVM name
     */
    @Nullable
    private static SourceAnchor fieldAnchor(@NotNull final PsiReferenceExpression reference,
                                            @NotNull final PsiField field,
                                            @NotNull final Document document,
                                            final int depth) {
        final String name = field.getName();
        // No descriptor. A field's type is written in the class file as a descriptor and in the
        // editor as a PsiType, and rendering one from the other is a second authority on descriptors
        // this plugin has no reason to become — a field name is unique within its class, so the name
        // and the owner already identify it.
        return new SourceAnchor(SourceAnchor.Kind.FIELD_ACCESS,
                internalNameOf(field.getContainingClass()), name, null, null,
                firstLineOf(document, reference), lastLineOf(document, reference),
                occurrenceOf(reference, name, PsiReferenceExpression.class, document), depth);
    }

    /**
     * Describes a literal.
     *
     * <p>A literal is named by its value alone, so the anchor carries no owner, name or descriptor and the search
     * matches it on the rendered constant.
     *
     * @param literal  the literal; must not be {@code null}
     * @param document the document supplying the line numbers; must not be {@code null}
     * @param depth    the depth to record on the anchor
     * @return the anchor, or {@code null} when the literal has no spelling in the constant grammar
     */
    @Nullable
    private static SourceAnchor constantAnchor(@NotNull final PsiLiteralExpression literal,
                                               @NotNull final Document document,
                                               final int depth) {
        final String text = constantTextOf(literal);
        return text == null
                ? null
                : new SourceAnchor(SourceAnchor.Kind.CONSTANT, null, null, null, text,
                        firstLineOf(document, literal), lastLineOf(document, literal), 0, depth);
    }

    /**
     * Renders a literal as the framework's own constant selector.
     *
     * <p>Rendered by {@link de.splatgames.aether.weaver.api.select.ConstantSelector} rather than from the literal's
     * text, because the text is compared with what an annotation carries and a second spelling of the same value
     * would compare unequal: {@code "retry"} in the editor becomes {@code string:"retry"}, which is what the engine
     * parses back.
     *
     * @param literal the literal to render; must not be {@code null}
     * @return the rendered selector, or {@code null} for the {@code null} literal and for any value the constant
     *         grammar cannot name
     */
    @Contract(pure = true)
    @Nullable
    public static String constantTextOf(@NotNull final PsiLiteralExpression literal) {
        final ConstantDesc value = describedValueOf(literal.getValue());
        if (value == null) {
            return null;
        }
        final ConstantSelector selector = ConstantSelector.of(value);
        return selector == null ? null : selector.render(MemberSelector.Form.SOURCE);
    }

    /**
     * Converts a literal's value to the constant a class file would load for it.
     *
     * <p>The selector grammar knows seven spellings and none of them is {@code char}, {@code boolean}, {@code byte}
     * or {@code short}, so those four are widened to {@code int} here — a character to its code point and a boolean
     * to {@code 1} or {@code 0} — which is the constant the class file loads for them.
     *
     * @param value the literal's value, or {@code null} when the editor computed none
     * @return the constant, or {@code null} when there is no value or its type has no spelling in the grammar
     */
    @Contract(pure = true)
    @Nullable
    private static ConstantDesc describedValueOf(@Nullable final Object value) {
        return switch (value) {
            case final Integer number -> number;
            case final Long number -> number;
            case final Float number -> number;
            case final Double number -> number;
            case final String text -> text;
            case final Character character -> (int) character.charValue();
            case final Boolean flag -> flag ? 1 : 0;
            case final Byte number -> number.intValue();
            case final Short number -> number.intValue();
            case null, default -> null;
        };
    }

    /**
     * Counts how many identically named expressions of the same kind complete before this one.
     *
     * <p>What tells two calls to the same method apart when both were compiled to the same line. The count is by
     * completion rather than by position in the text, so an argument is numbered before the call it is an argument
     * to — which is the order the instructions are emitted in, and the opposite of the order they are read in.
     *
     * @param <T>        the expression kind being counted
     * @param expression the expression to number; must not be {@code null}
     * @param name       the name the siblings have to share; must not be {@code null}
     * @param kind       the interface to search for; must not be {@code null}
     * @param document   the document supplying the line numbers; must not be {@code null}
     * @return the zero-based position among the identically named expressions completing before this one within its
     *         own lines, and {@code 0} when the expression is in no code block
     */
    private static <T extends PsiExpression> int occurrenceOf(@NotNull final T expression,
                                                              @NotNull final String name,
                                                              @NotNull final Class<T> kind,
                                                              @NotNull final Document document) {
        // The enclosing block, not the enclosing statement. Two calls on one line are two
        // statements, so a statement-wide scope holds exactly one of them and every expression came
        // back numbered zero — which is the number that says "the first instruction on this line",
        // for both of them. That is the bug this whole ordinal exists to prevent, reintroduced one
        // level down.
        final PsiElement scope = PsiTreeUtil.getParentOfType(expression, PsiCodeBlock.class, false);
        if (scope == null) {
            return 0;
        }
        final int ends = expression.getTextRange().getEndOffset();
        final int first = firstLineOf(document, expression);
        final int last = lastLineOf(document, expression);
        int before = 0;
        // The interface, never expression.getClass(). The runtime class is a platform
        // implementation type, and asking for that would quietly stop matching the day the platform
        // returns a different one — a search that finds nothing and reports no ordinal at all.
        for (final T sibling : PsiTreeUtil.findChildrenOfType(scope, kind)) {
            if (sibling == expression || !name.equals(nameOf(sibling))) {
                continue;
            }
            final int completes = sibling.getTextRange().getEndOffset();
            // Confined to the anchor's own lines, because that is the window the instruction search
            // is confined to. Counting a call three statements further down would produce an
            // ordinal for a candidate list it was never counted against.
            if (completes < ends && lastLineOf(document, sibling) >= first
                    && firstLineOf(document, sibling) <= last) {
                before++;
            }
        }
        return before;
    }

    /**
     * Returns the name a counted expression carries.
     *
     * <p>Read from the reference text and not from a resolved member, because the siblings are being compared with
     * one another and an unresolved one has to be comparable too.
     *
     * @param expression the expression to name; must not be {@code null}
     * @return the name written at the site, or {@code null} for an expression that is neither a call nor a reference
     */
    @Nullable
    private static String nameOf(@NotNull final PsiExpression expression) {
        if (expression instanceof final PsiMethodCallExpression call) {
            return call.getMethodExpression().getReferenceName();
        }
        return expression instanceof final PsiReferenceExpression reference
                ? reference.getReferenceName()
                : null;
    }

    /**
     * Returns the class a call is made on.
     *
     * <p>A qualifier whose type resolves to a class with a JVM name decides the owner, because that is the class the
     * instruction names. A qualifier that is itself a reference to a class, as a static call's qualifier is, decides
     * it as well and does so unconditionally: that class's internal name is returned even when it is {@code null},
     * without ever trying the resolved method. Every other case falls back to the resolved method's declaring
     * class — not only a call with no qualifier at all, but also one qualified with something that names no class,
     * such as an array-typed qualifier ({@code array.clone()}) or a qualifier that does not resolve.
     *
     * @param call     the call; must not be {@code null}
     * @param resolved the method it resolved to, or {@code null} when it resolved to none
     * @return the internal name of the owner, or {@code null} when the qualifier resolves directly to a class with
     *         no JVM name, or when neither the qualifier nor the resolved method yields a class with one
     */
    @Nullable
    private static String ownerOfCall(@NotNull final PsiMethodCallExpression call,
                                      @Nullable final PsiMethod resolved) {
        final PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
        if (qualifier != null) {
            final PsiType type = qualifier.getType();
            if (type instanceof final PsiClassType classType) {
                final String internal = internalNameOf(classType.resolve());
                if (internal != null) {
                    return internal;
                }
            }
            // A static call qualifies with the class itself, which has no type of its own.
            if (qualifier instanceof final PsiReferenceExpression named
                    && named.resolve() instanceof final PsiClass owner) {
                return internalNameOf(owner);
            }
        }
        return resolved == null ? null : internalNameOf(resolved.getContainingClass());
    }

    /**
     * Returns a resolved method's descriptor.
     *
     * <p>Encoded by the platform's own {@link com.intellij.psi.util.ClassUtil} rather than rendered here, so nothing
     * in this class has to spell a type descriptor of its own.
     *
     * @param method the resolved method, or {@code null} when the call resolved to none
     * @return the descriptor, or {@code null} when there is no method to encode
     */
    @Nullable
    private static String descriptorOf(@Nullable final PsiMethod method) {
        return method == null ? null : ClassUtil.getAsmMethodSignature(method);
    }

    /**
     * Returns a class's internal name.
     *
     * <p>The JVM binary name with its separators replaced, so a nested class comes out as
     * {@code com/acme/Outer$Inner} — the spelling the instruction carries.
     *
     * @param owner the element to name, which need not be a class
     * @return the internal name, or {@code null} when the element is not a class or the platform reports no JVM name
     *         for it
     */
    @Nullable
    private static String internalNameOf(@Nullable final PsiElement owner) {
        if (!(owner instanceof final PsiClass declared)) {
            return null;
        }
        final String binary = ClassUtil.getJVMClassName(declared);
        return binary == null ? null : binary.replace('.', '/');
    }

    /**
     * Returns the innermost block the caret stands in that a slice could be built from.
     *
     * <p>Only the five statements whose body is a region an author can point at qualify. The method itself never
     * does: the walk stops at {@code target} without considering it, so a slice covering the whole body — which
     * would bound nothing — cannot be proposed.
     *
     * @param element the element the caret is on; must not be {@code null}
     * @param target  the method to stop at; must not be {@code null}
     * @return the enclosing statement, or {@code null} when the caret stands in straight-line code
     */
    @Nullable
    private static PsiElement regionOf(@NotNull final PsiElement element,
                                       @NotNull final PsiMethod target) {
        for (PsiElement current = element; current != null && current != target;
             current = current.getParent()) {
            if (current instanceof PsiLoopStatement
                    || current instanceof PsiTryStatement
                    || current instanceof PsiSwitchStatement
                    || current instanceof PsiSynchronizedStatement
                    || current instanceof PsiIfStatement) {
                return current;
            }
        }
        return null;
    }

    /**
     * Returns the one-based line an element starts on.
     *
     * @param document the document the element belongs to; must not be {@code null}
     * @param element  the element; must not be {@code null}
     * @return the line of its first character
     */
    private static int firstLineOf(@NotNull final Document document,
                                   @NotNull final PsiElement element) {
        return lineAt(document, element.getTextRange().getStartOffset());
    }

    /**
     * Returns the one-based line an element ends on.
     *
     * <p>Measured at the last character rather than at the end offset, which is the offset after it and lands on the
     * following line for an element ending at a line break. An empty range falls back to the start offset.
     *
     * @param document the document the element belongs to; must not be {@code null}
     * @param element  the element; must not be {@code null}
     * @return the line of its last character
     */
    private static int lastLineOf(@NotNull final Document document,
                                  @NotNull final PsiElement element) {
        return lineAt(document, Math.max(element.getTextRange().getEndOffset() - 1,
                element.getTextRange().getStartOffset()));
    }

    /**
     * Converts a document offset to the line number a class file would carry.
     *
     * @param document the document; must not be {@code null}
     * @param offset   the offset to convert
     * @return the one-based line, or {@code 0} for an offset outside the document, which no line table entry carries
     */
    private static int lineAt(@NotNull final Document document, final int offset) {
        // The document's lines are zero-based and a class file's are one-based.
        return offset < 0 || offset > document.getTextLength()
                ? 0
                : document.getLineNumber(offset) + 1;
    }

}
