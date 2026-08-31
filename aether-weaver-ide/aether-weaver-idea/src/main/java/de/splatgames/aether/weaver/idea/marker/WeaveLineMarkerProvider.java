package de.splatgames.aether.weaver.idea.marker;

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo;
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider;
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import de.splatgames.aether.weaver.idea.index.WeaveTargetIndex;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.search.GlobalSearchScope;
import de.splatgames.aether.weaver.api.manifest.WeaveManifest;
import de.splatgames.aether.weaver.idea.library.LibraryWeaves;
import de.splatgames.aether.weaver.idea.psi.HandlerOrder;
import de.splatgames.aether.weaver.idea.psi.SelectorTargets;
import de.splatgames.aether.weaver.idea.selector.SelectorReference;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Marks a method that a weave injects into, and navigates to the handlers doing it.
 *
 * <p>Registered for Java in {@code plugin.xml} as a {@code codeInsight.lineMarkerProvider}. This is
 * the direction from the woven member back to the weave; {@link HandlerLineMarkerProvider} marks the
 * handler side of the same relation, but not the same set of handlers: this provider lists every
 * handler whose selector names the member, including one declared with {@code @Wrap}, while
 * {@link HandlerLineMarkerProvider} marks only a handler carrying {@code @Inject} or
 * {@code @Redirect}.
 *
 * <h2>Where the handlers come from</h2>
 *
 * <p>Two sources are consulted for every marked method, and a marker is produced when either
 * yields something.
 *
 * <ul>
 *   <li><b>The project.</b> References to the method are searched over the scope
 *       {@link WeaveTargetIndex#weavesTargeting(PsiClass)} returns, and only a
 *       {@link SelectorReference} counts: an ordinary Java call site is the platform's business,
 *       not this marker's. The enclosing method of each such reference is the handler.
 *   <li><b>The dependencies.</b> The weave manifests of the project's libraries are read through
 *       {@link LibraryWeaves#targeting(Project, String)}. Of the weaves naming the method's class,
 *       each injector whose selector names the method — as decided by
 *       {@link SelectorTargets#namesMethod(String, PsiMethod)} — contributes a handler.
 * </ul>
 *
 * <p>The reference search is not confined to the member's own access scope: a package-private
 * method is found from a weave in another package as readily as from one beside it.
 *
 * <h2>What the tooltip says</h2>
 *
 * <p>The tooltip opens with {@code Woven by one handler, in execution order:} or
 * {@code Woven by 3 handlers, in execution order:}, counting both sources together, and gives each
 * handler an indented line of its own. A project handler's line names its weave class, the
 * handler's name and the priority {@link HandlerOrder#priorityOf(PsiMethod)} reads from the weave.
 * A handler from a library is rendered from the manifest instead: the name is
 * {@link WeaveManifest.Injector#handler()} as written, a method name immediately followed by its JVM
 * descriptor, so the line reads, for example,
 * {@code AuditWeave.onCharge(Ljava/math/BigDecimal;)V() — priority 50, from a dependency} rather than
 * a clean method name; the priority is the weave manifest's own {@code priority}, the same figure a
 * project handler's line shows, not a value read back from the handler method itself; and the class
 * name is the resolved {@link PsiClass}'s simple name, not text taken from the manifest. The popup
 * title is {@code Weave Handlers}.
 *
 * <p>Only the project's handlers are sorted, by {@link HandlerOrder#EXECUTION_ORDER}, which totally
 * orders them by priority and, for a tie, by the weave's PSI-resolved qualified name, the handler's
 * name and its parameters' presentable text. The engine's own order, {@code OrderKey.ORDER}, ties on
 * the weave's binary name and JVM descriptor instead, so two handlers this comparator distinguishes
 * only by a difference {@code OrderKey.ORDER} does not see — a parameter list rendered identically by
 * {@code getPresentableText()}, or a nested class named with a dot here and a {@code $} there — can
 * be listed in an order the engine does not run them in. The dependencies' handlers follow the
 * project's in the order the manifests were read.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see HandlerLineMarkerProvider
 */
public final class WeaveLineMarkerProvider extends RelatedItemLineMarkerProvider {

    /** Creates the provider; it holds no state. */
    public WeaveLineMarkerProvider() {
        // Stateless.
    }

    /**
     * Returns the name this provider is known by.
     *
     * @return {@code "Woven by Aether Weaver"}
     */
    @Override
    @NotNull
    public String getName() {
        return "Woven by Aether Weaver";
    }

    /**
     * Returns the icon this provider's markers carry.
     *
     * @return {@link AllIcons.Gutter#ImplementedMethod}, the same icon
     *         {@link #collectNavigationMarkers} builds every marker with
     */
    @Override
    @Nullable
    public Icon getIcon() {
        return AllIcons.Gutter.ImplementedMethod;
    }

    /**
     * Adds a marker to the handlers weaving into the method the given element names.
     *
     * <p>Only a {@link PsiIdentifier} whose parent is a {@link PsiMethod} is considered, and the
     * marker is built on that identifier rather than on the method, so it is anchored to the leaf
     * naming the method rather than to the method itself.
     *
     * <p>The navigation targets are the project's handlers in execution order followed by the
     * libraries'. A handler shipped by a library whose method cannot be found in the weave class
     * navigates to that class instead.
     *
     * @param element the element being examined
     * @param result  the collection each marker is added to
     */
    @Override
    protected void collectNavigationMarkers(
            @NotNull final PsiElement element,
            @NotNull final Collection<? super RelatedItemLineMarkerInfo<?>> result) {
        // The identifier, not the method: see the type documentation.
        if (!(element instanceof PsiIdentifier)
                || !(element.getParent() instanceof final PsiMethod method)) {
            return;
        }

        final List<PsiMethod> handlers = handlersFor(method);
        final List<Shipped> shipped = shippedHandlersFor(method);
        if (handlers.isEmpty() && shipped.isEmpty()) {
            return;
        }
        final List<PsiElement> targets = new ArrayList<>(handlers);
        for (final Shipped entry : shipped) {
            targets.add(entry.element());
        }
        result.add(NavigationGutterIconBuilder.create(AllIcons.Gutter.ImplementedMethod)
                .setTargets(targets)
                .setPopupTitle("Weave Handlers")
                .setTooltipText(tooltipFor(handlers, shipped))
                .createLineMarkerInfo(element));
    }

    /**
     * Renders the tooltip listing the handlers.
     *
     * <p>The count covers both lists: {@code Woven by one handler} against
     * {@code Woven by 2 handlers}. Every handler then gets a line of its own, broken with
     * {@code <br>} and indented with non-breaking spaces. A project handler is named as its weave
     * class, a dot and its own name; one whose containing class is unavailable is named without the
     * prefix rather than dropped.
     *
     * @param handlers the project's handlers, in the order they are listed
     * @param shipped  the handlers shipped by dependencies, listed after the project's
     * @return the tooltip text, as HTML
     */
    @NotNull
    private static String tooltipFor(@NotNull final List<PsiMethod> handlers,
                                     @NotNull final List<Shipped> shipped) {
        final int total = handlers.size() + shipped.size();
        final StringBuilder tooltip = new StringBuilder(total == 1
                ? "Woven by one handler"
                : "Woven by " + total + " handlers");
        tooltip.append(", in execution order:");
        for (final PsiMethod handler : handlers) {
            final PsiClass weave = handler.getContainingClass();
            tooltip.append("<br>&nbsp;&nbsp;")
                    .append(weave == null ? "" : weave.getName() + '.')
                    .append(handler.getName())
                    .append("() — priority ")
                    .append(HandlerOrder.priorityOf(handler));
        }
        for (final Shipped entry : shipped) {
            // Named as coming from a dependency. A reader who sees an unfamiliar handler and
            // cannot find it in the project has to be told it is not theirs, or they go looking.
            tooltip.append("<br>&nbsp;&nbsp;")
                    .append(entry.weaveName())
                    .append('.')
                    .append(entry.handler())
                    .append("() — priority ")
                    .append(entry.priority())
                    .append(", from a dependency");
        }
        return tooltip.toString();
    }

    /**
     * One handler read out of a library's weave manifest.
     *
     * <p>{@link #shippedHandlersFor(PsiMethod)} skips a weave whose class does not resolve, so an
     * instance is built only once that class is on the classpath; {@link #weaveName()} and
     * {@link #element()} are then read from the resolved {@link PsiClass}, and only
     * {@link #handler()} is still carried as the manifest wrote it.
     *
     * @param weaveName the resolved weave class's simple name, or the manifest's class name with
     *                  every {@code $} rewritten to a {@code .} when the class has none
     * @param handler   the handler as the manifest records it, which
     *                  {@link WeaveManifest.Injector#handler()} defines as a method name immediately
     *                  followed by its JVM descriptor
     * @param priority  the priority the manifest states for the weave, not for this injector
     * @param element   what the marker navigates to: the handler method of the weave class, or the
     *                  weave class itself when no method of that name is declared there
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private record Shipped(@NotNull String weaveName,
                           @NotNull String handler,
                           int priority,
                           @NotNull PsiElement element) {
    }

    /**
     * Returns the handlers that dependencies of this project weave into the given method.
     *
     * <p>A manifest states its targets and selectors as text, so the work here is turning that text
     * back into something to navigate to. A weave whose class is not on the classpath is skipped
     * entirely, since there would be nothing to open. Otherwise the injector's selector is compared
     * against the method by {@link SelectorTargets#namesMethod(String, PsiMethod)}, and the handler
     * is looked for among the weave class's own methods by the name the manifest records, which
     * carries a JVM descriptor after it; a lookup that finds nothing falls back to the weave class,
     * so the marker still leads somewhere.
     *
     * @param method the method being marked
     * @return the shipped handlers naming it, empty when none does and when the method's class has
     *         no qualified name
     */
    @NotNull
    private static List<Shipped> shippedHandlersFor(@NotNull final PsiMethod method) {
        final PsiClass target = method.getContainingClass();
        final String targetName = target == null ? null : target.getQualifiedName();
        if (targetName == null) {
            return List.of();
        }

        final Project project = method.getProject();
        final JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
        final List<Shipped> shipped = new ArrayList<>();
        for (final LibraryWeaves.Declared declared
                : LibraryWeaves.targeting(project, targetName)) {
            final String className = declared.declared().className().replace('$', '.');
            final PsiClass weave = facade.findClass(className, GlobalSearchScope.allScope(project));
            if (weave == null) {
                continue;
            }
            for (final WeaveManifest.Injector injector : declared.declared().injectors()) {
                if (!SelectorTargets.namesMethod(injector.method(), method)) {
                    continue;
                }
                final PsiMethod[] found = weave.findMethodsByName(injector.handler(), false);
                shipped.add(new Shipped(weave.getName() == null ? className : weave.getName(),
                        injector.handler(), declared.declared().priority(),
                        found.length == 0 ? weave : found[0]));
            }
        }
        return List.copyOf(shipped);
    }

    /**
     * Builds the reference search for the weaves that may name the given member.
     *
     * <p>Two things are settled here. The scope is the one
     * {@link WeaveTargetIndex#weavesTargeting(PsiClass)} returns, which narrows the search to the
     * weaves the index knows target the member's class — except while the project is still indexing,
     * when that method falls back to the whole project rather than to an empty result; a member with
     * no containing class gets the empty scope and therefore no handlers. The search is also not
     * limited to the member's own access scope: a package-private member is found from a weave in
     * another package.
     *
     * @param member the member whose references are wanted
     * @return the parameters to search with
     */
    @NotNull
    private static ReferencesSearch.SearchParameters namingAnywhere(@NotNull final PsiMember member) {
        return new ReferencesSearch.SearchParameters(member,
                WeaveTargetIndex.weavesTargeting(member.getContainingClass()), true);
    }

    /**
     * Returns the project's handlers weaving into the given method, in execution order.
     *
     * <p>A reference that is not a {@link SelectorReference} is skipped, and so is one with no
     * enclosing method, which leaves nothing to navigate to. Nothing is de-duplicated, so a handler
     * is listed once per selector reference that names the method.
     *
     * <p>The sort is {@link HandlerOrder#EXECUTION_ORDER}: highest priority first, then the weave's
     * qualified name, the handler's name and its rendered parameter types.
     *
     * @param method the method being marked
     * @return the handlers, empty when no weave in the project names the method
     */
    @NotNull
    private static List<PsiMethod> handlersFor(@NotNull final PsiMethod method) {
        final List<PsiMethod> handlers = new ArrayList<>();
        for (final PsiReference reference : ReferencesSearch
                .search(namingAnywhere(method))
                .findAll()) {
            if (!(reference instanceof SelectorReference)) {
                // Ordinary Java call sites are the platform's business, not this marker's.
                continue;
            }
            final PsiMethod handler =
                    PsiTreeUtil.getParentOfType(reference.getElement(), PsiMethod.class);
            if (handler != null) {
                handlers.add(handler);
            }
        }
        handlers.sort(HandlerOrder.EXECUTION_ORDER);
        return handlers;
    }
}
