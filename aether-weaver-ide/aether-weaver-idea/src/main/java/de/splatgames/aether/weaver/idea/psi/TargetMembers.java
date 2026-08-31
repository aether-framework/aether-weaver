package de.splatgames.aether.weaver.idea.psi;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.impl.source.PsiExtensibleClass;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.StringJoiner;

/**
 * Reads the members a class declares itself.
 *
 * <p>Every question this plugin asks about a target — does it already have this field, does a {@code @Shadow} name a
 * method it really declares, which methods can a handler be generated for — has to be answered about the class as
 * its author wrote it. {@code getMethods()} and {@code getFields()} run augmentation, and this plugin's own
 * {@code WeaveAugmentProvider} merges a weave's members into its target, so asking them would answer that a target
 * already declares the member a weave is about to give it. {@code PsiExtensibleClass} is the platform's way to read
 * past that, and the plain accessors are used only for a class that does not implement it.
 *
 * <p>Nothing here searches supertypes. An inherited member is not something the target declares, and a weave that
 * names one is a different question from the one these methods answer.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class TargetMembers {

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private TargetMembers() {
        throw new AssertionError("no instances");
    }

    /**
     * Returns the methods a class declares.
     *
     * @param owner the class to read; must not be {@code null}
     * @return the declared methods in declaration order, constructors included
     */
    @NotNull
    public static List<PsiMethod> ownMethodsOf(@NotNull final PsiClass owner) {
        return owner instanceof final PsiExtensibleClass extensible
                ? extensible.getOwnMethods()
                : List.of(owner.getMethods());
    }

    /**
     * Returns the fields a class declares.
     *
     * @param owner the class to read; must not be {@code null}
     * @return the declared fields in declaration order
     */
    @NotNull
    public static List<PsiField> ownFieldsOf(@NotNull final PsiClass owner) {
        return owner instanceof final PsiExtensibleClass extensible
                ? extensible.getOwnFields()
                : List.of(owner.getFields());
    }

    /**
     * Returns a declared field by name.
     *
     * <p>The name alone identifies it, since a class cannot declare two fields with one name.
     *
     * @param owner the class to search; must not be {@code null}
     * @param name  the field name; must not be {@code null}
     * @return the field, or {@code null} when the class declares none with that name — an inherited field is not
     *         found
     */
    @Nullable
    public static PsiField fieldNamed(@NotNull final PsiClass owner, @NotNull final String name) {
        for (final PsiField candidate : ownFieldsOf(owner)) {
            if (name.equals(candidate.getName())) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Returns a declared method by the signature {@link #signatureOf(PsiMethod)} renders.
     *
     * <p>What answers whether a {@code @Shadow} names a member the target really has. A method whose own signature
     * cannot be rendered is skipped rather than matched loosely, so an unresolved parameter type on either side
     * yields no answer instead of the wrong one.
     *
     * @param owner     the class to search; must not be {@code null}
     * @param signature the signature to look for, in this class's rendering; must not be {@code null}
     * @return the method, or {@code null} when the class declares none with that signature
     */
    @Nullable
    public static PsiMethod methodWithSignature(@NotNull final PsiClass owner,
                                                @NotNull final String signature) {
        for (final PsiMethod candidate : ownMethodsOf(owner)) {
            if (signature.equals(signatureOf(candidate))) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Renders a method as a name and its erased parameter types.
     *
     * <p>The key two methods are compared by, written {@code charge(java.math.BigDecimal,int)}. Erased, because the
     * comparison is between what the editor resolved and what the class file will hold; the return type is not part
     * of it, since a Java class cannot declare two methods differing only in it.
     *
     * @param method the method to render; must not be {@code null}
     * @return the signature, or {@code null} when a parameter type does not resolve, which makes the method
     *         incomparable rather than different
     */
    @Contract(pure = true)
    @Nullable
    public static String signatureOf(@NotNull final PsiMethod method) {
        final StringJoiner parameters = new StringJoiner(",", "(", ")");
        for (final PsiParameter parameter : method.getParameterList().getParameters()) {
            final String type = HandlerSignature.erasedNameOf(parameter.getType());
            if (type == null) {
                return null;
            }
            parameters.add(type);
        }
        return method.getName() + parameters;
    }
}
