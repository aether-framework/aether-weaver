package de.splatgames.aether.weaver.idea.marker;

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo;
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider;
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiMethod;
import de.splatgames.aether.weaver.idea.psi.ExtensionDeclarations;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;

/**
 * Marks a declaration of an {@code @Extension} class that contributes a member to another type, and
 * navigates to the type receiving it.
 *
 * <p>Registered for Java in {@code plugin.xml} as a {@code codeInsight.lineMarkerProvider}. Two
 * shapes of declaration are marked, and both must sit directly in a class carrying
 * {@code @Extension}:
 *
 * <ul>
 *   <li>a method accepted by {@link ExtensionDeclarations#contributes(PsiMethod)}, whose receiver is
 *       read by {@link ExtensionDeclarations#receiverOf(PsiMethod)};
 *   <li>a field accepted by {@link ExtensionDeclarations#contributesConstant(PsiField)}, whose
 *       receiver is read by {@link ExtensionDeclarations#receiverOf(PsiField)}.
 * </ul>
 *
 * <p>The marker's single navigation target is the receiver class, under the popup title
 * {@code Extended Type}, and its tooltip is {@code Contributed to} followed by that class's
 * qualified name. A contribution is read as static when {@code @Receiver} sits on the method and
 * parameter zero does not also carry it, per
 * {@link ExtensionDeclarations#isStaticContribution(PsiMethod)}, and
 * such a contribution adds {@code as a static method}; a constant adds {@code as a constant}, so a
 * contributed constant reads {@code Contributed to com.acme.Ledger as a constant}. The qualified
 * name is the point of the tooltip: an extension names its receiver in an annotation or in a
 * parameter position, and neither is where a reader looks first.
 *
 * <p>No marker is produced where the receiver does not resolve to a class, or where that class has
 * no qualified name. A field carrying {@code @Receiver} that is not {@code public static final} is
 * one case where the build refuses the declaration and the gutter agrees: it is reported by the
 * annotation processor as {@code AW1314}, and {@link ExtensionDeclarations#contributesConstant(PsiField)}
 * requires the same three modifiers, so no marker is produced for it either. The two checks are not
 * the same check in general: {@link ExtensionDeclarations#contributes(PsiMethod)} accepts a public,
 * static, non-constructor method with a receiver at parameter zero without inspecting the rest of the
 * signature, so a marker can still be produced for a method the build refuses on other grounds, among
 * them a generic method, {@code @Receiver} placed on the method and on a parameter other than the
 * first, or a class-level receiver whose first parameter has the wrong type.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class ExtensionLineMarkerProvider extends RelatedItemLineMarkerProvider {

    /** Creates the provider; it holds no state. */
    public ExtensionLineMarkerProvider() {
        // Stateless.
    }

    /**
     * Adds a marker for the receiver when the given element names a contributed method or constant.
     *
     * <p>Only a {@link PsiIdentifier} is considered, and the marker is built on that identifier
     * rather than on the method or field it names, so it is anchored to the leaf naming the
     * declaration rather than to the declaration itself. Everything else about the
     * declaration is read through its parent, so an identifier whose parent is neither a
     * {@link PsiMethod} nor a {@link PsiField} is ignored.
     *
     * @param element the element being examined
     * @param result  the collection each marker is added to
     */
    @Override
    protected void collectNavigationMarkers(
            @NotNull final PsiElement element,
            @NotNull final Collection<? super RelatedItemLineMarkerInfo<?>> result) {
        // The identifier, not the declaration: the platform runs marker collection in two passes and
        // a marker on a non-leaf flickers between them.
        if (!(element instanceof PsiIdentifier)) {
            return;
        }

        final PsiElement declaration = element.getParent();
        final PsiClass receiver;
        final String shape;
        if (declaration instanceof final PsiMethod method) {
            if (!contributedFrom(method.getContainingClass())
                    || !ExtensionDeclarations.contributes(method)) {
                return;
            }
            receiver = ExtensionDeclarations.receiverOf(method);
            shape = ExtensionDeclarations.isStaticContribution(method) ? " as a static method" : "";
        } else if (declaration instanceof final PsiField field) {
            if (!contributedFrom(field.getContainingClass())
                    || !ExtensionDeclarations.contributesConstant(field)) {
                return;
            }
            receiver = ExtensionDeclarations.receiverOf(field);
            shape = " as a constant";
        } else {
            return;
        }

        if (receiver == null || receiver.getQualifiedName() == null) {
            return;
        }
        result.add(NavigationGutterIconBuilder.create(AllIcons.Gutter.OverridenMethod)
                .setTarget(receiver)
                .setPopupTitle("Extended Type")
                .setTooltipText("Contributed to " + receiver.getQualifiedName() + shape)
                .createLineMarkerInfo(element));
    }

    /**
     * Reports whether the given class is an extension holder.
     *
     * @param holder the class the declaration sits in, or {@code null} when it has none
     * @return {@code true} when the class carries {@code @Extension}, and {@code false} when it does
     *         not or is {@code null}
     */
    private static boolean contributedFrom(final PsiClass holder) {
        return holder != null && ExtensionDeclarations.isExtension(holder);
    }
}
