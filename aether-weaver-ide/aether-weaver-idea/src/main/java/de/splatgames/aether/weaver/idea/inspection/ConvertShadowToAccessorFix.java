package de.splatgames.aether.weaver.idea.inspection;

import com.intellij.codeInspection.LocalQuickFixOnPsiElement;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import de.splatgames.aether.weaver.idea.psi.TargetMembers;
import de.splatgames.aether.weaver.idea.psi.HandlerSignature;
import de.splatgames.aether.weaver.idea.psi.WeaveDeclarations;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

/**
 * Replaces a {@code @Shadow} member with the generated members that reach the same state from outside the target.
 *
 * <p>Offered by {@link WeaveDeclarationInspection} beside {@code AW1090}, which it reports on a {@code @Shadow}
 * written in a weave declared {@code @Weave(kind = Kind.STATIC)}. A shadowed field becomes one or two
 * {@code @Accessor} methods and a shadowed method becomes a single {@code @Invoker}.
 *
 * <p>The generated names are chosen so that the framework's own inference finds the target member without an
 * explicit {@code value()}: {@code getBalance} and {@code setBalance} for a field called {@code balance}, and
 * {@code callFlush} for a method called {@code flush}. The round trip only holds where the first character of the
 * shadowed name is itself lower-case: the inference lower-cases the character after the prefix it strips, while
 * this fix only ever upper-cases the first character of the name it generates from, so a field named {@code URL}
 * becomes {@code getURL}, from which the inference derives {@code uRL} rather than {@code URL} back, and the
 * generated accessor is then reported {@code AW1030} for a field the target does not have under that name.
 *
 * <p>A setter is generated only where the target's own field is not {@code final}. Writing a final field through an
 * accessor is {@code AW1097}, so generating the pair unconditionally would hand the author the next diagnostic
 * along with the fix for this one.
 *
 * <p>The new declarations are inserted after the member they replace, in the order they are built, and the
 * {@code @Shadow} member is then deleted. Each is given a body that throws {@link AssertionError} rather than being
 * left abstract, which is what lets it be added to a weave class that is not abstract.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
final class ConvertShadowToAccessorFix extends LocalQuickFixOnPsiElement {

    /** The annotation written on a generated field accessor, spelled in full and shortened afterwards. */
    private static final String ACCESSOR = "de.splatgames.aether.weaver.api.Accessor";

    /** The annotation written on a generated method invoker, spelled in full and shortened afterwards. */
    private static final String INVOKER = "de.splatgames.aether.weaver.api.Invoker";

    /**
     * Anchors the fix to the shadowed member.
     *
     * @param member the field or method carrying {@code @Shadow}
     */
    ConvertShadowToAccessorFix(@NotNull final PsiMember member) {
        super(member);
    }

    /**
     * Returns the text of this action, naming what it would generate.
     *
     * @return {@code "Replace @Shadow with an @Accessor pair"} when the anchor is a field, and
     *         {@code "Replace @Shadow with an @Invoker"} for anything else
     */
    @Override
    @NotNull
    public String getText() {
        return getStartElement() instanceof PsiField
                ? "Replace @Shadow with an @Accessor pair"
                : "Replace @Shadow with an @Invoker";
    }

    /**
     * Returns the name this fix is grouped and looked up under.
     *
     * @return {@code "Replace @Shadow with a generated member"}, which covers both the field and the method form
     */
    @Override
    @NotNull
    public String getFamilyName() {
        return "Replace @Shadow with a generated member";
    }

    /**
     * Writes the generated declarations and removes the {@code @Shadow} member.
     *
     * <p>Nothing is written and nothing is deleted when the anchor is no longer a member, when it has no containing
     * class, or when no replacement can be built for it — a member whose type cannot be written back as source is
     * left exactly as it stands rather than being deleted in exchange for nothing.
     *
     * <p>Each generated declaration has its class references shortened, so the two annotations appear under their
     * simple names once the file imports them.
     *
     * @param project      the project the file belongs to
     * @param file         the file the member lives in
     * @param startElement the member the fix was created with
     * @param endElement   the end of the anchored range, unused
     */
    @Override
    public void invoke(@NotNull final Project project,
                       @NotNull final PsiFile file,
                       @NotNull final PsiElement startElement,
                       @NotNull final PsiElement endElement) {
        if (!(startElement instanceof final PsiMember member)) {
            return;
        }
        final PsiClass weave = member.getContainingClass();
        final List<String> replacements = replacementsFor(member);
        if (weave == null || replacements.isEmpty()) {
            return;
        }

        final PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
        final JavaCodeStyleManager styles = JavaCodeStyleManager.getInstance(project);
        PsiElement anchor = member;
        for (final String text : replacements) {
            anchor = weave.addAfter(factory.createMethodFromText(text, weave), anchor);
            styles.shortenClassReferences(anchor);
        }
        member.delete();
    }

    /**
     * Builds the source of every declaration that replaces the shadowed member.
     *
     * <p>A field yields the accessors; anything else is treated as a method and yields one invoker taking the same
     * parameters, by the same names, and returning the same type.
     *
     * @param member the shadowed member
     * @return the declarations to insert, or an empty list when the return type or one of the parameter types cannot
     *         be written back as source
     */
    @NotNull
    private static List<String> replacementsFor(@NotNull final PsiMember member) {
        if (member instanceof final PsiField field) {
            return accessorsFor(field);
        }
        final PsiMethod method = (PsiMethod) member;
        final String returned = method.getReturnType() == null
                ? null
                : HandlerSignature.writableTextOf(method.getReturnType());
        if (returned == null) {
            return List.of();
        }
        final StringJoiner parameters = new StringJoiner(", ", "(", ")");
        for (final PsiParameter parameter : method.getParameterList().getParameters()) {
            final String type = HandlerSignature.writableTextOf(parameter.getType());
            if (type == null) {
                return List.of();
            }
            parameters.add(type + ' ' + parameter.getName());
        }
        // "callFlush" is the framework's own convention: the target's name is inferred by stripping
        // a call or invoke prefix, so no explicit value() is needed.
        return List.of('@' + INVOKER + ' ' + returned + " call" + capitalise(method.getName())
                + parameters + " { throw new AssertionError(\"invoker\"); }");
    }

    /**
     * Builds the accessor declarations for a shadowed field.
     *
     * <p>The reader is always generated. The writer follows it only where the target declares the field without
     * {@code final}.
     *
     * @param field the shadowed field
     * @return the getter alone, the getter and the setter, or an empty list when the field's type cannot be written
     *         back as source
     */
    @NotNull
    private static List<String> accessorsFor(@NotNull final PsiField field) {
        final String type = HandlerSignature.writableTextOf(field.getType());
        if (type == null) {
            return List.of();
        }
        final String name = capitalise(field.getName());
        final List<String> declarations = new ArrayList<>(2);
        declarations.add('@' + ACCESSOR + ' ' + type + " get" + name
                + "() { throw new AssertionError(\"accessor\"); }");
        if (!isFinalOnTheTarget(field)) {
            declarations.add('@' + ACCESSOR + " void set" + name + '(' + type + ' '
                    + field.getName() + ") { throw new AssertionError(\"accessor\"); }");
        }
        return declarations;
    }

    /**
     * Reports whether the target declares the shadowed field {@code final}.
     *
     * <p>The weave's targets are searched in order and the first that declares a field of that name answers. Only
     * declared fields are considered, not inherited ones.
     *
     * @param shadow the shadowed field
     * @return whether the field should be treated as final; {@code true} whenever nothing can be established — the
     *         field has no containing class, the weave names no target, the field has no name, or no target declares
     *         a field of that name — because the reader alone is always legal and the writer is not
     */
    private static boolean isFinalOnTheTarget(@NotNull final PsiField shadow) {
        final PsiClass weave = shadow.getContainingClass();
        if (weave == null) {
            return true;
        }
        for (final PsiClass target : WeaveDeclarations.targetsOf(weave)) {
            final PsiField candidate = shadow.getName() == null
                    ? null
                    : TargetMembers.fieldNamed(target, shadow.getName());
            if (candidate != null) {
                return candidate.hasModifierProperty(PsiModifier.FINAL);
            }
        }
        // Unknown. The reader alone is always legal, so that is what gets generated.
        return true;
    }

    /**
     * Upper-cases the first character of a name, so that it can follow a {@code get}, {@code set} or {@code call}
     * prefix and still be recognised by the framework's inference.
     *
     * @param name the name to capitalise
     * @return the name with its first character upper-cased in {@link Locale#ROOT}, or the name unchanged when it is
     *         empty
     */
    @NotNull
    private static String capitalise(@NotNull final String name) {
        return name.isEmpty()
                ? name
                : name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
    }
}
