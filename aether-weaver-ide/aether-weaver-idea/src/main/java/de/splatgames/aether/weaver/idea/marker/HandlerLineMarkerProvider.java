package de.splatgames.aether.weaver.idea.marker;

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo;
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider;
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiNameValuePair;
import com.intellij.psi.PsiReference;
import com.intellij.psi.ResolveResult;
import de.splatgames.aether.weaver.idea.psi.WeaveDeclarations;
import de.splatgames.aether.weaver.idea.selector.SelectorReference;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Marks a handler of a weave class and navigates to the members its selector binds to.
 *
 * <p>Registered for Java in {@code plugin.xml} as a {@code codeInsight.lineMarkerProvider}. This is
 * the direction from the weave outwards; {@link WeaveLineMarkerProvider} marks the woven member and
 * lists the handlers reaching it, which is not confined to what this provider marks: a handler
 * carrying only {@code @Wrap} is still listed there, through {@code method} attributes that
 * {@link de.splatgames.aether.weaver.idea.selector.SelectorReferenceContributor} attaches a reference
 * to regardless of which weave annotation declares them.
 *
 * <p>A method is marked when its containing class carries {@code @Weave}, the method itself carries
 * {@code @Inject} or {@code @Redirect}, that annotation's {@code method} attribute is a string
 * literal, and at least one target resolves from it. {@code @Inject} is looked for first and
 * {@code @Redirect} only if there is none, so a method carrying both is marked from its
 * {@code @Inject} selector alone. Those two annotations are the only ones looked for: a handler
 * carrying nothing but {@code @Wrap} gets no marker here, even though it is still reachable from the
 * woven member's own marker.
 *
 * <p>Resolution is {@link SelectorReference}'s, which is what keeps the gutter and the rest of the
 * plugin agreeing about what a selector names. A selector written as a bare name matches every
 * method of that name the weave's targets have, inherited ones included, so the marker lists every
 * overload rather than picking one; giving the selector a parameter list narrows it. The tooltip is
 * {@code Weaves into one member} for a single target and {@code Weaves into 3 members} for three,
 * under the popup title {@code Woven Members}.
 *
 * <p>A selector that resolves to nothing produces no marker at all. Reporting that is the selector
 * inspection's business, and a gutter icon leading nowhere would be a worse report of the same
 * problem.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see WeaveLineMarkerProvider
 */
public final class HandlerLineMarkerProvider extends RelatedItemLineMarkerProvider {

    /** Creates the provider; it holds no state. */
    public HandlerLineMarkerProvider() {
        // Stateless.
    }

    /**
     * Returns the name this provider is known by.
     *
     * @return {@code "Weaves into"}
     */
    @Override
    @NotNull
    public String getName() {
        return "Weaves into";
    }

    /**
     * Returns the icon this provider's markers carry.
     *
     * @return {@link AllIcons.Gutter#ImplementingMethod}, the same icon
     *         {@link #collectNavigationMarkers} builds every marker with
     */
    @Override
    @Nullable
    public Icon getIcon() {
        return AllIcons.Gutter.ImplementingMethod;
    }

    /**
     * Adds a marker to the woven members when the given element names a handler.
     *
     * <p>Only a {@link PsiIdentifier} whose parent is a {@link PsiMethod} is considered, and the
     * marker is built on that identifier rather than on the method, so it is anchored to the leaf
     * naming the method rather than to the method itself.
     *
     * @param element the element being examined
     * @param result  the collection each marker is added to
     */
    @Override
    protected void collectNavigationMarkers(
            @NotNull final PsiElement element,
            @NotNull final Collection<? super RelatedItemLineMarkerInfo<?>> result) {
        // The identifier, not the method: the platform runs marker collection in two passes and a
        // marker on a non-leaf flickers between them. The rule is enforced by the tests, which
        // rethrow logged platform errors.
        if (!(element instanceof PsiIdentifier)
                || !(element.getParent() instanceof final PsiMethod handler)) {
            return;
        }

        final PsiLiteralExpression selector = selectorOf(handler);
        if (selector == null) {
            return;
        }
        final List<PsiElement> targets = targetsOf(selector);
        if (targets.isEmpty()) {
            // An unresolvable selector is an inspection's business, not a gutter's. A marker that
            // led nowhere would be a worse report of the same problem.
            return;
        }
        result.add(NavigationGutterIconBuilder.create(AllIcons.Gutter.ImplementingMethod)
                .setTargets(targets)
                .setPopupTitle("Woven Members")
                .setTooltipText(targets.size() == 1
                        ? "Weaves into one member"
                        : "Weaves into " + targets.size() + " members")
                .createLineMarkerInfo(element));
    }

    /**
     * Returns the literal holding the target selector of an injection declared on the given method.
     *
     * <p>Three conditions have to hold, and each of them alone yields {@code null}: the containing
     * class carries {@code @Weave}, the method carries {@code @Inject} or {@code @Redirect}, and
     * that annotation names its {@code method} attribute with a string literal. The attribute list
     * is read as written, so a {@code method} given as a constant reference rather than as a literal
     * yields {@code null} too.
     *
     * @param handler the method examined
     * @return the literal expression given for {@code method}, or {@code null} when the method
     *         declares no readable injection
     */
    @Nullable
    private static PsiLiteralExpression selectorOf(@NotNull final PsiMethod handler) {
        final PsiClass weave = handler.getContainingClass();
        if (weave == null || WeaveDeclarations.annotation(weave, WeaveDeclarations.WEAVE) == null) {
            return null;
        }
        PsiAnnotation injection = WeaveDeclarations.annotation(handler, WeaveDeclarations.INJECT);
        if (injection == null) {
            injection = WeaveDeclarations.annotation(handler, WeaveDeclarations.REDIRECT);
        }
        if (injection == null) {
            return null;
        }
        for (final PsiNameValuePair attribute : injection.getParameterList().getAttributes()) {
            if (WeaveDeclarations.METHOD_ATTRIBUTE.equals(attribute.getName())
                    && attribute.getValue() instanceof final PsiLiteralExpression literal) {
                return literal;
            }
        }
        return null;
    }

    /**
     * Returns the members the given selector literal binds to.
     *
     * <p>Only a {@link SelectorReference} on the literal is followed; any other reference sitting on
     * it is skipped, and a resolution that supplies no element is dropped. The result is in the
     * order the references and their resolutions arrive, and is not de-duplicated.
     *
     * @param selector the literal carrying the selector
     * @return the resolved members, empty when the selector binds to none
     */
    @NotNull
    private static List<PsiElement> targetsOf(@NotNull final PsiLiteralExpression selector) {
        final List<PsiElement> targets = new ArrayList<>();
        for (final PsiReference reference : selector.getReferences()) {
            if (!(reference instanceof final SelectorReference selectorReference)) {
                continue;
            }
            for (final ResolveResult resolved : selectorReference.multiResolve(false)) {
                final PsiElement member = resolved.getElement();
                if (member != null) {
                    targets.add(member);
                }
            }
        }
        return targets;
    }
}
