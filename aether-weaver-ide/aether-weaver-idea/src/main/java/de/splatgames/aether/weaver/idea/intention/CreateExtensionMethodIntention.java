package de.splatgames.aether.weaver.idea.intention;

import com.intellij.codeInsight.ExpectedTypeInfo;
import com.intellij.codeInsight.ExpectedTypesProvider;
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiExpressionStatement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypes;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.codeStyle.VariableKind;
import com.intellij.psi.util.PsiTreeUtil;
import de.splatgames.aether.weaver.idea.index.ExtensionReceiverIndex;
import de.splatgames.aether.weaver.idea.psi.ExtensionDeclarations;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Declares a missing extension method in the holder that already extends the receiver.
 *
 * <p>Java's own "Create method" offers to add the method to the class it was called on, which is
 * impossible when that class is a class file from a dependency. This offers the other place it can
 * go: the {@code @Extension} class that already contributes to that type.
 *
 * <p>Offered on a qualified call that does not resolve, when exactly one writable extension holder
 * contributes to the receiver. Where two do, nothing is offered, since choosing one would put
 * somebody's code in a file they did not name; where none does, nothing is offered either, because
 * creating a holder is a decision about a project's layout.
 *
 * <h2>The declaration that is written</h2>
 *
 * <p>Two shapes, decided by what the call is qualified with. A call on an instance takes the
 * receiver as an annotated first parameter; a call on the type names the receiver on the method
 * instead and takes no receiver parameter:
 *
 * <pre>{@code
 * greeting.asMoney(currency)
 *     ->  public static R asMoney(@Receiver Greeting self, Currency N)
 *
 * Greeting.of(name)
 *     ->  @Receiver(Greeting.class)
 *         public static R of(String N)
 * }</pre>
 *
 * <p>{@code R} above stands for whatever the call site expects, and {@code N} for the parameter's
 * name: the platform's own suggestion for the argument's type, not the text that was written at
 * the call site.
 *
 * <p>Three rules of the extension model are built into that text. The declaration is
 * {@code static}, which {@code AW1301} requires of a {@code public} method of an extension class.
 * A {@code @Receiver} parameter is the first one, which is {@code AW1303}. And the two spellings
 * of the receiver are never combined, which is {@code AW1313}. The body throws
 * {@link UnsupportedOperationException}, so the method compiles and fails loudly rather than
 * silently doing nothing.
 *
 * <p>The return type is taken from the call site: {@code void} where the call is a statement, the
 * first expected type where the compiler has one, and {@link Object} where it has none. Parameter
 * types are the arguments' own, with {@link Object} standing in for an argument whose type cannot
 * be worked out or that is the literal {@code null}, and parameter names come from the platform's
 * own suggestions, numbered apart from each other and from {@code self}.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class CreateExtensionMethodIntention extends PsiElementBaseIntentionAction {

    /** The body of the generated method, which compiles and refuses to be called. */
    private static final String NOT_IMPLEMENTED =
            "throw new UnsupportedOperationException(\"not implemented\");";

    /**
     * The entry's text, replaced by {@link #isAvailable(Project, Editor, PsiElement)} with one
     * naming the method and the holder it would be written into.
     *
     * <p>Never reset: a failed availability check leaves the last successful text in place, so
     * {@link #getText()} can name a holder that has nothing to do with the current caret until the
     * next check succeeds.
     */
    private volatile String text = "Create extension method";

    /** Creates the intention; the destination is worked out per invocation. */
    public CreateExtensionMethodIntention() {
        // The destination is worked out per invocation.
    }

    /**
     * Returns the text of the intention entry.
     *
     * @return the text last computed by {@link #isAvailable(Project, Editor, PsiElement)}, and
     *         {@code Create extension method} until that has found a holder
     */
    @Override
    @NotNull
    public String getText() {
        return this.text;
    }

    /**
     * Returns the family the intention is configured under.
     *
     * @return the family name
     */
    @Override
    @NotNull
    public String getFamilyName() {
        return "Create extension method";
    }

    /**
     * Reports whether the intention is offered at the given element, and names the destination.
     *
     * <p>On success the entry's text is replaced with one naming the called method and the holder
     * it would be written into, so that the list says where the code is about to go.
     *
     * @param project the project the file belongs to
     * @param editor  the editor, or {@code null} when there is none
     * @param element the element under the caret
     * @return {@code true} when the caret is on an unresolved qualified call whose receiver has
     *         exactly one writable extension holder
     */
    @Override
    public boolean isAvailable(@NotNull final Project project,
                               @Nullable final Editor editor,
                               @NotNull final PsiElement element) {
        final PsiMethodCallExpression call = callAt(element);
        final PsiClass receiver = receiverOf(call);
        if (call == null || receiver == null) {
            return false;
        }
        final PsiClass holder = soleHolderFor(receiver);
        if (holder == null) {
            return false;
        }
        this.text = "Create extension method '" + call.getMethodExpression().getReferenceName()
                + "' in " + holder.getName();
        return true;
    }

    /**
     * Writes the declaration into the holder and opens it.
     *
     * <p>The call, the receiver and the holder are worked out again rather than carried over from
     * {@link #isAvailable(Project, Editor, PsiElement)}, and nothing happens when any of them no
     * longer answers. The added method is shortened and reformatted, and then opened, which
     * usually means a second file: the call site and the holder are rarely the same file.
     *
     * @param project the project the file belongs to
     * @param editor  the editor, or {@code null} when there is none, in which case the method is
     *                still written but not opened
     * @param element the element under the caret
     */
    @Override
    public void invoke(@NotNull final Project project,
                       @Nullable final Editor editor,
                       @NotNull final PsiElement element) {
        final PsiMethodCallExpression call = callAt(element);
        final PsiClass receiver = receiverOf(call);
        if (call == null || receiver == null) {
            return;
        }
        final PsiClass holder = soleHolderFor(receiver);
        if (holder == null) {
            return;
        }

        final PsiMethod written = PsiElementFactory.getInstance(project)
                .createMethodFromText(sourceOf(call, receiver), holder);
        final PsiElement added = holder.add(written);
        JavaCodeStyleManager.getInstance(project).shortenClassReferences(added);
        CodeStyleManager.getInstance(project).reformat(added);

        // Opened only when the action came from an editor. A method the author cannot see is one
        // they have to go looking for; a navigation request with no editor to serve it is not.
        if (editor != null && added instanceof final PsiMethod created) {
            created.navigate(true);
        }
    }

    /**
     * Renders the declaration for the given call.
     *
     * <p>Every type is written by its canonical name and shortened by the caller. The receiver is
     * named on the method for a call on the type and taken as the first parameter otherwise; the
     * call's own arguments follow in the order they were written.
     *
     * @param call     the unresolved call the method is created for
     * @param receiver the type the call was made on
     * @return the method's source, ending in a body that throws
     */
    @Contract(pure = true)
    @NotNull
    private static String sourceOf(@NotNull final PsiMethodCallExpression call,
                                   @NotNull final PsiClass receiver) {
        final boolean onTheType = isCalledOnTheType(call);
        final StringBuilder source = new StringBuilder();
        if (onTheType) {
            source.append('@').append(ExtensionDeclarations.RECEIVER)
                    .append('(').append(receiver.getQualifiedName()).append(".class)\n");
        }
        source.append("public static ").append(returnTypeOf(call)).append(' ')
                .append(call.getMethodExpression().getReferenceName()).append('(');
        if (!onTheType) {
            source.append('@').append(ExtensionDeclarations.RECEIVER).append(' ')
                    .append(receiver.getQualifiedName()).append(" self");
        }
        for (final String parameter : parametersOf(call)) {
            if (source.charAt(source.length() - 1) != '(') {
                source.append(", ");
            }
            source.append(parameter);
        }
        return source.append(") {\n").append(NOT_IMPLEMENTED).append("\n}").toString();
    }

    /**
     * Renders one declaration per argument of the call.
     *
     * <p>The name is the platform's first suggestion for the argument, and a name already taken is
     * followed by {@code 2}, {@code 3} and so on. {@code self} counts as taken whether or not the
     * receiver is written as a parameter, so a generated signature reads the same either way. The
     * type is the argument's own canonical name, except that {@link Object} stands in for an
     * argument whose type cannot be worked out or that is the literal {@code null}.
     *
     * @param call the unresolved call
     * @return the parameters as {@code type name} texts, in the order the arguments were written
     */
    @NotNull
    private static List<String> parametersOf(@NotNull final PsiMethodCallExpression call) {
        final JavaCodeStyleManager names = JavaCodeStyleManager.getInstance(call.getProject());
        final List<String> parameters = new ArrayList<>();
        final List<String> taken = new ArrayList<>();
        taken.add("self");

        for (final PsiExpression argument : call.getArgumentList().getExpressions()) {
            final PsiType type = argument.getType();
            // An argument whose type cannot be worked out is usually one that is itself red. Object
            // keeps the generated method compiling, which is what lets the author fix the argument
            // and then the signature rather than fighting both at once.
            final String declared = type == null || PsiTypes.nullType().equals(type)
                    ? "java.lang.Object"
                    : type.getCanonicalText();
            final String suggested = names.suggestVariableName(VariableKind.PARAMETER, null,
                    argument, type).names[0];

            String name = suggested;
            for (int i = 2; taken.contains(name); i++) {
                name = suggested + i;
            }
            taken.add(name);
            parameters.add(declared + ' ' + name);
        }
        return parameters;
    }

    /**
     * Returns the type the generated method declares as its result.
     *
     * <p>Only the first expected type is used where the compiler offers several.
     *
     * @param call the unresolved call
     * @return {@code void} for a call written as a statement, the canonical name of the first
     *         expected type where there is one, and {@code java.lang.Object} otherwise
     */
    @NotNull
    private static String returnTypeOf(@NotNull final PsiMethodCallExpression call) {
        if (call.getParent() instanceof PsiExpressionStatement) {
            // Written as a statement: nothing can be done with a value, so promising one would put
            // an unused expression where the author wrote an action.
            return "void";
        }
        final ExpectedTypeInfo[] expected = ExpectedTypesProvider.getExpectedTypes(call, false);
        if (expected.length == 0) {
            return "java.lang.Object";
        }
        final PsiType type = expected[0].getType();
        return type.getCanonicalText();
    }

    /**
     * Returns the call this intention would create a method for.
     *
     * <p>The innermost enclosing call is taken, and the element itself counts as one.
     *
     * @param element the element under the caret
     * @return the call, or {@code null} when there is none, when it is unqualified, when it names
     *         nothing, or when it already resolves
     */
    @Nullable
    private static PsiMethodCallExpression callAt(@NotNull final PsiElement element) {
        final PsiMethodCallExpression call =
                PsiTreeUtil.getParentOfType(element, PsiMethodCallExpression.class, false);
        if (call == null) {
            return null;
        }
        final PsiReferenceExpression reference = call.getMethodExpression();
        if (reference.getQualifierExpression() == null || reference.getReferenceName() == null) {
            // An unqualified call names something in scope; there is no receiver to extend.
            return null;
        }
        // Already resolves: Java would be offering to create a method that exists, and so would
        // this. The red call is the whole occasion for the offer.
        return call.resolveMethod() == null ? call : null;
    }

    /**
     * Returns the type the call was made on.
     *
     * <p>A call on the type answers with the class the qualifier resolves to; a call on an
     * expression answers with what its type resolves to.
     *
     * @param call the unresolved call, or {@code null}
     * @return the receiver, or {@code null} when there is no call, no qualifier, or a qualifier
     *         whose type is not a class type or does not resolve
     */
    @Nullable
    private static PsiClass receiverOf(@Nullable final PsiMethodCallExpression call) {
        if (call == null) {
            return null;
        }
        final PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
        if (qualifier == null) {
            return null;
        }
        if (isCalledOnTheType(call)) {
            return (PsiClass) ((PsiReferenceExpression) qualifier).resolve();
        }
        return qualifier.getType() instanceof final PsiClassType type ? type.resolve() : null;
    }

    /**
     * Reports whether the call names its receiver's type rather than an instance of it.
     *
     * @param call the call to examine
     * @return {@code true} when the qualifier is a reference that resolves to a class
     */
    @Contract(pure = true)
    private static boolean isCalledOnTheType(@NotNull final PsiMethodCallExpression call) {
        return call.getMethodExpression().getQualifierExpression()
                instanceof final PsiReferenceExpression qualifier
                && qualifier.resolve() instanceof PsiClass;
    }

    /**
     * Returns the one holder a method for this receiver can be written into.
     *
     * <p>The candidates come from {@link ExtensionReceiverIndex#contributingTo(PsiClass)}, which
     * answers per simple name and is empty while the project is indexing. Each is checked twice:
     * it has to be writable, and it has to declare a contribution whose receiver is this very type
     * rather than another type of the same simple name.
     *
     * @param receiver the type the call was made on
     * @return the sole holder, or {@code null} when none or more than one survives that
     */
    @Nullable
    private static PsiClass soleHolderFor(@NotNull final PsiClass receiver) {
        PsiClass only = null;
        for (final PsiClass holder : ExtensionReceiverIndex.contributingTo(receiver)) {
            // A holder in a dependency contributes to this receiver and cannot be added to. Offering
            // it would produce an intention that fails, which is worse than one that never appears.
            if (!holder.isWritable() || !contributesTo(holder, receiver)) {
                continue;
            }
            if (only != null) {
                return null;
            }
            only = holder;
        }
        return only;
    }

    /**
     * Reports whether the holder already contributes something to the given receiver.
     *
     * <p>The receiver of each contributed method is resolved and compared as an element, so two
     * types of the same simple name are told apart.
     *
     * @param holder   the extension holder to examine
     * @param receiver the type the call was made on
     * @return {@code true} when at least one contributed method names that receiver
     */
    private static boolean contributesTo(@NotNull final PsiClass holder,
                                         @NotNull final PsiClass receiver) {
        for (final PsiMethod method : ExtensionDeclarations.contributedBy(holder)) {
            if (holder.getManager().areElementsEquivalent(
                    ExtensionDeclarations.receiverOf(method), receiver)) {
                return true;
            }
        }
        return false;
    }
}
