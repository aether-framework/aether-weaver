package de.splatgames.aether.weaver.idea.intention;

import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.ClassUtil;
import de.splatgames.aether.weaver.api.select.MemberSelector;
import de.splatgames.aether.weaver.idea.psi.PointDeclarations;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Converts the selector written in {@code @At(target = "...")} between its two spellings.
 *
 * <p>Backs {@link ConvertPointTargetToSourceFormIntention} and
 * {@link ConvertPointTargetToDescriptorFormIntention}, which do nothing but ask for a converted
 * text and write it back. Both directions answer {@code null} rather than a guess, and both
 * verify their own output before returning it: the source form is parsed again and re-rendered,
 * and the descriptor form is resolved again against the same point. A conversion that changes
 * which member the declaration names is a working weave replaced by one that silently stops
 * matching, and neither the compiler nor the author sees it happen.
 *
 * <p>Only a method converts to the descriptor form. A point whose target names a field reaches
 * {@code sole} with something other than a {@link PsiMethod} and is refused there; a target
 * naming a constant never reaches {@code sole} with anything at all, since a constant names a
 * value rather than a member and resolves to an empty list.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
final class PointTargetLiterals {

    /**
     * Prevents instantiation.
     *
     * @throws AssertionError always
     */
    private PointTargetLiterals() {
        throw new AssertionError("no instances");
    }

    /**
     * Returns the point target literal at the given element.
     *
     * <p>The element itself is taken when it is already a literal, and otherwise its parent is,
     * which is the token the caret sits on inside the quotes. The literal has to be the value of
     * the {@value PointDeclarations#TARGET_ATTRIBUTE} attribute of an {@code @At}, which
     * {@link PointDeclarations#atOf(PsiLiteralExpression)} decides; unlike a selector literal,
     * nothing here requires an enclosing {@code @Weave}.
     *
     * @param element the element under the caret
     * @return the literal, or {@code null} when it is not an {@code @At} target
     */
    @Nullable
    static PsiLiteralExpression at(@NotNull final PsiElement element) {
        final PsiElement literal = element instanceof PsiLiteralExpression
                ? element
                : element.getParent();
        return literal instanceof final PsiLiteralExpression target
                && PointDeclarations.atOf(target) != null
                ? target
                : null;
    }

    /**
     * Renders the literal's selector in the source form.
     *
     * <p>Offered only for a selector that was written in the descriptor form; one already in the
     * source form is refused rather than converted.
     * {@link MemberSelector#render(MemberSelector.Form)} is documented to always answer for
     * {@link MemberSelector.Form#SOURCE}, but not to always reproduce the selector it started
     * from: a member named after one of the seven unsafe keywords and written without an owner
     * renders differently from how it was written. The rendered text is therefore parsed again
     * and re-rendered, and it is returned only when the two agree. A selector that does not
     * parse, or whose rendering does not survive the round trip, converts to nothing.
     *
     * @param literal the literal to convert, or {@code null}
     * @return the source form, or {@code null} when the literal holds no non-blank string, was not
     *         written in the descriptor form, or does not survive being read back
     */
    @Nullable
    static String sourceFormOf(@Nullable final PsiLiteralExpression literal) {
        final String text = textOf(literal);
        if (text == null) {
            return null;
        }
        try {
            final MemberSelector parsed = MemberSelector.parse(text);
            if (parsed.form() != MemberSelector.Form.DESCRIPTOR) {
                return null;
            }
            final String source = parsed.render(MemberSelector.Form.SOURCE);
            // The conversion has to survive being read back. Rendering is documented to produce
            // the best available approximation rather than failing, and an approximation written
            // into somebody's source is a working selector replaced by a plausible one.
            return source.equals(MemberSelector.parse(source).render(MemberSelector.Form.SOURCE))
                    ? source
                    : null;
        } catch (final RuntimeException unusable) {
            return null;
        }
    }

    /**
     * Renders the literal's selector in the descriptor form.
     *
     * <p>The text is not parsed as a selector here. What it names is resolved through
     * {@link PointDeclarations}, and the descriptor is then built from the one method that came
     * back: {@value MemberSelector#DESCRIPTOR_PREFIX}, the owner's internal name, a {@code .}, the
     * method's name, and the JVM signature {@code ClassUtil} produces for it. The result is
     * resolved again at the same point and returned only when it lands on that same method, which
     * is what keeps a descriptor this plugin spells differently from the way the framework reads
     * it out of somebody's source.
     *
     * @param literal the literal to convert, or {@code null}
     * @return the descriptor form, or {@code null} when the literal holds no non-blank string, the
     *         text already begins with {@value MemberSelector#DESCRIPTOR_PREFIX}, the point names
     *         anything other than exactly one method, the owner has no binary name, the method has
     *         no signature, the conversion changes nothing, or the converted text no longer names
     *         the same method
     */
    @Nullable
    static String descriptorFormOf(@Nullable final PsiLiteralExpression literal) {
        final String text = textOf(literal);
        if (text == null || text.startsWith(MemberSelector.DESCRIPTOR_PREFIX)) {
            return null;
        }
        final PsiMethod named = soleMethodNamedBy(literal);
        final PsiClass owner = named == null ? null : named.getContainingClass();
        final String binary = owner == null ? null : ClassUtil.getJVMClassName(owner);
        final String descriptor = named == null ? null : ClassUtil.getAsmMethodSignature(named);
        if (binary == null || descriptor == null || descriptor.isBlank()) {
            return null;
        }

        final String converted = MemberSelector.DESCRIPTOR_PREFIX + binary.replace('.', '/')
                + '.' + named.getName() + descriptor;
        if (converted.equals(text)) {
            return null;
        }
        // Resolved again, and it has to land on the same one method. A descriptor this plugin
        // encoded differently from the way the framework reads it would otherwise reach somebody's
        // source, and the only symptom would be a weave that stops matching.
        return named.equals(soleMethodNamedBy(literal, converted)) ? converted : null;
    }

    /**
     * Returns the one method the literal's own text names.
     *
     * @param literal the point target literal
     * @return the method, or {@code null} when the text names no member, several, or something
     *         that is not a method
     */
    @Nullable
    private static PsiMethod soleMethodNamedBy(@NotNull final PsiLiteralExpression literal) {
        return sole(PointDeclarations.membersNamedBy(literal));
    }

    /**
     * Returns the one method the given text names at the literal's point.
     *
     * <p>The point comes from the {@code @At} the literal belongs to and the literal serves as the
     * resolution context, so replacement text is resolved exactly as the written text was.
     *
     * @param literal the point target literal, used for its {@code @At} and as the context
     * @param text    the text to resolve instead of the literal's own
     * @return the method, or {@code null} when the literal is not an {@code @At} target, or the
     *         text names no member, several, or something that is not a method
     */
    @Nullable
    private static PsiMethod soleMethodNamedBy(@NotNull final PsiLiteralExpression literal,
                                               @NotNull final String text) {
        final PsiAnnotation at = PointDeclarations.atOf(literal);
        return at == null
                ? null
                : sole(PointDeclarations.membersNamedBy(
                        PointDeclarations.pointOf(at), text, literal));
    }

    /**
     * Returns the single method in the given members.
     *
     * @param members the members a target text named
     * @return the one member when there is exactly one and it is a method, and {@code null}
     *         otherwise
     */
    @Nullable
    private static PsiMethod sole(@NotNull final List<PsiElement> members) {
        return members.size() == 1 && members.getFirst() instanceof final PsiMethod method
                ? method
                : null;
    }

    /**
     * Returns the literal's value as a usable target text.
     *
     * @param literal the literal to read, or {@code null}
     * @return the text, or {@code null} when the literal is {@code null}, holds something other
     *         than a string, or holds a blank one
     */
    @Nullable
    private static String textOf(@Nullable final PsiLiteralExpression literal) {
        return literal != null && literal.getValue() instanceof final String text && !text.isBlank()
                ? text
                : null;
    }
}
