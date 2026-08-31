package de.splatgames.aether.weaver.idea.intention;

import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import de.splatgames.aether.weaver.idea.psi.TargetMembers;
import com.intellij.psi.util.PsiTreeUtil;
import de.splatgames.aether.weaver.idea.psi.HandlerSignature;
import de.splatgames.aether.weaver.idea.psi.WeaveDeclarations;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * Declares the {@code @Shadow} that makes a red reference to the target's own member compile.
 *
 * <p>Inside a merged weave, {@code this.balance} means the target's field, but javac has no way to
 * know that: the weave class does not declare it, so the reference is an error until the weave
 * declares a {@code @Shadow} standing in for it. This intention writes that declaration, copying
 * the shape from the member the target actually has rather than inferring it from the use.
 *
 * <p>Offered on an unresolved reference inside a class annotated {@code @Weave}, when exactly one
 * of that weave's targets declares a matching member. A call is matched against the targets' own
 * methods by name and argument count, and any other reference against their own fields by name.
 * Inherited members are not searched. Several candidates with the same arity cannot be told apart
 * from a call whose arguments do not resolve either, so nothing is offered rather than binding the
 * weave to a method nobody named.
 *
 * <p>Not offered in a {@code @Weave(kind = Kind.STATIC)} weave. A static weave is never merged, so
 * a {@code @Shadow} in one is reported as {@code AW1090}; the member is reached with an
 * {@code @Accessor} or an {@code @Invoker} instead.
 *
 * <p>The declaration is added to the weave class. For a field it is the type and the name; for a
 * method it is the signature and a body that throws, which is dead weight the weave discards and
 * only has to compile. The access modifier and {@code static} are copied from the target's member;
 * {@code final} is not, because a weave may not declare a constructor ({@code AW1081}) and a
 * {@code final} field written without an initialiser would never be definitely assigned.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class AddShadowIntention extends PsiElementBaseIntentionAction {

    /**
     * The annotation the generated declaration carries, written in full and shortened afterwards.
     */
    private static final String SHADOW = "de.splatgames.aether.weaver.api.Shadow";

    /** The body given to a shadowed method, which stands in for the target's own and never runs. */
    private static final String BODY = "{ throw new AssertionError(\"shadow\"); }";

    /** Creates the intention, which holds no state between invocations. */
    public AddShadowIntention() {
        // Stateless.
    }

    /**
     * Returns the text of the intention entry.
     *
     * @return the entry's text
     */
    @Override
    @NotNull
    public String getText() {
        return "Declare @Shadow for the target's member";
    }

    /**
     * Returns the family the intention is configured under.
     *
     * @return the family name
     */
    @Override
    @NotNull
    public String getFamilyName() {
        return "Declare @Shadow";
    }

    /**
     * Reports whether the intention is offered at the given element.
     *
     * @param project the project the file belongs to
     * @param editor  the editor, or {@code null} when there is none
     * @param element the element under the caret
     * @return {@code true} when exactly one member of the weave's targets answers the reference
     *         under the caret
     */
    @Override
    public boolean isAvailable(@NotNull final Project project,
                               @Nullable final Editor editor,
                               @NotNull final PsiElement element) {
        return memberFor(element) != null;
    }

    /**
     * Writes the declaration into the weave class.
     *
     * <p>The member and the weave are looked up again rather than carried over from
     * {@link #isAvailable(Project, Editor, PsiElement)}, and nothing happens when either no longer
     * answers or when the member's types cannot be written out. The declaration is created from
     * text with the weave as its context, added to the weave, and then shortened, which is what
     * turns the fully qualified {@code @Shadow} into an import and a simple name.
     *
     * @param project the project the file belongs to
     * @param editor  the editor, or {@code null} when there is none; not used
     * @param element the element under the caret
     */
    @Override
    public void invoke(@NotNull final Project project,
                       @Nullable final Editor editor,
                       @NotNull final PsiElement element) {
        final PsiMember member = memberFor(element);
        final PsiClass weave = WeaveDeclarations.enclosingWeave(element);
        if (member == null || weave == null) {
            return;
        }
        final String text = declarationOf(member);
        if (text == null) {
            return;
        }
        final PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
        final PsiElement declaration = member instanceof PsiField
                ? factory.createFieldFromText(text, weave)
                : factory.createMethodFromText(text, weave);
        JavaCodeStyleManager.getInstance(project)
                .shortenClassReferences(weave.add(declaration));
    }

    /**
     * Returns the target member the reference under the caret was meant to name.
     *
     * <p>The reference has to be unresolved: one that already resolves needs no shadow. Its
     * enclosing class has to be an instance weave that does not already declare the name, since a
     * second declaration of it would not compile either. What is searched then depends on the
     * reference's parent: a call is matched against the targets' own methods by name and by the
     * number of arguments written, and anything else against their own fields by name.
     *
     * @param element the element under the caret
     * @return the one member found, or {@code null} when there is no unresolved reference, no
     *         enclosing instance weave, a name the weave already declares, or a number of
     *         candidates other than one
     */
    @Nullable
    private static PsiMember memberFor(@NotNull final PsiElement element) {
        final PsiReferenceExpression reference =
                PsiTreeUtil.getParentOfType(element, PsiReferenceExpression.class, false);
        final String name = reference == null ? null : reference.getReferenceName();
        if (name == null || reference.resolve() != null) {
            return null;
        }
        final PsiClass weave = WeaveDeclarations.enclosingWeave(reference);
        if (weave == null || WeaveDeclarations.isStaticWeave(weave)
                || declares(weave, name)) {
            return null;
        }

        final boolean call = reference.getParent() instanceof PsiMethodCallExpression;
        final int arguments = call
                ? ((PsiMethodCallExpression) reference.getParent())
                        .getArgumentList().getExpressionCount()
                : 0;

        final List<PsiMember> found = new ArrayList<>(2);
        for (final PsiClass target : WeaveDeclarations.targetsOf(weave)) {
            if (call) {
                for (final PsiMethod candidate : TargetMembers.ownMethodsOf(target)) {
                    if (!candidate.isConstructor() && candidate.getName().equals(name)
                            && candidate.getParameterList().getParametersCount() == arguments) {
                        found.add(candidate);
                    }
                }
            } else {
                for (final PsiField candidate : TargetMembers.ownFieldsOf(target)) {
                    if (candidate.getName().equals(name)) {
                        found.add(candidate);
                    }
                }
            }
        }
        // Several overloads with the same arity cannot be told apart from a call whose arguments do
        // not resolve either. Declaring the wrong one binds the weave to a method nobody named.
        return found.size() == 1 ? found.getFirst() : null;
    }

    /**
     * Reports whether the weave already declares the name itself.
     *
     * <p>Fields and methods are both searched, and the name alone decides: a weave that declares
     * the name in either shape is one where adding a declaration answers nothing.
     *
     * @param weave the weave class
     * @param name  the name written at the reference
     * @return {@code true} when the weave declares a field or a method of that name
     */
    private static boolean declares(@NotNull final PsiClass weave, @NotNull final String name) {
        for (final PsiField field : TargetMembers.ownFieldsOf(weave)) {
            if (field.getName().equals(name)) {
                return true;
            }
        }
        for (final PsiMethod method : TargetMembers.ownMethodsOf(weave)) {
            if (method.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Renders the declaration to write for the given target member.
     *
     * <p>Types are written by {@link HandlerSignature#writableTextOf}, which erases a type
     * mentioning a type variable and answers {@code null} for one that does not resolve. A method
     * keeps its parameter names as the target wrote them and is given {@link #BODY}.
     *
     * @param member the target's member
     * @return the declaration text, or {@code null} when the field's type, the method's return type
     *         or any of its parameter types cannot be written out
     */
    @Nullable
    private static String declarationOf(@NotNull final PsiMember member) {
        final String modifiers = modifiersOf(member);
        if (member instanceof final PsiField field) {
            final String type = HandlerSignature.writableTextOf(field.getType());
            return type == null ? null : '@' + SHADOW + ' ' + modifiers + type + ' '
                    + field.getName() + ';';
        }

        final PsiMethod method = (PsiMethod) member;
        final String returned = method.getReturnType() == null
                ? null
                : HandlerSignature.writableTextOf(method.getReturnType());
        if (returned == null) {
            return null;
        }
        final StringJoiner parameters = new StringJoiner(", ", "(", ")");
        for (final PsiParameter parameter : method.getParameterList().getParameters()) {
            final String type = HandlerSignature.writableTextOf(parameter.getType());
            if (type == null) {
                return null;
            }
            parameters.add(type + ' ' + parameter.getName());
        }
        return '@' + SHADOW + ' ' + modifiers + returned + ' ' + method.getName() + parameters
                + ' ' + BODY;
    }

    /**
     * Returns the modifiers to copy onto the declaration, each followed by a space.
     *
     * <p>Exactly four are considered, in the order {@code private}, {@code protected},
     * {@code public}, {@code static}. Everything else the target wrote is dropped: a
     * package-private member stays package-private because no access modifier is found, and a
     * {@code final} field becomes a plain one.
     *
     * @param member the target's member
     * @return the modifiers, empty for a package-private instance member
     */
    @NotNull
    private static String modifiersOf(@NotNull final PsiMember member) {
        final StringBuilder text = new StringBuilder();
        for (final String modifier
                : List.of(PsiModifier.PRIVATE, PsiModifier.PROTECTED, PsiModifier.PUBLIC,
                        PsiModifier.STATIC)) {
            if (member.hasModifierProperty(modifier)) {
                text.append(modifier).append(' ');
            }
        }
        return text.toString();
    }

    /**
     * Reports that the platform is to open a write action around
     * {@link #invoke(Project, Editor, PsiElement)}.
     *
     * @return {@code true}, since the weave class is edited in place
     */
    @Override
    public boolean startInWriteAction() {
        return true;
    }
}
