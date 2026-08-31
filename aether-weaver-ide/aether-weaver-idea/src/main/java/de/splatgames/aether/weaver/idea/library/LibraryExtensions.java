package de.splatgames.aether.weaver.idea.library;

import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import de.splatgames.aether.weaver.api.manifest.WeaveManifest;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Lists the extensions the project's dependencies ship, and answers which holders extend a type.
 *
 * <p>An extension in a jar adds members to a type that the type's own class file knows nothing
 * about. This is where the augment provider and the tool window find them; everything here comes
 * out of the manifests {@link LibraryManifests} parsed, and no class file is opened to find a
 * holder.
 *
 * <p>A manifest states class names in the binary form, so a nested class arrives as
 * {@code com.acme.Outer$Inner}. Every name is converted to the source spelling before it is used
 * as a key or handed to {@link JavaPsiFacade}, which is what lets a nested receiver and a nested
 * holder be found at all.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class LibraryExtensions {

    /**
     * Prevents instantiation.
     *
     * @throws AssertionError always
     */
    private LibraryExtensions() {
        throw new AssertionError("no instances");
    }

    /**
     * Returns the extension holders that a library declares contributions to the given type from.
     *
     * <p>The holders are resolved in the whole project scope, libraries included, and one that does
     * not resolve is left out rather than reported: a manifest naming a class that is no longer on
     * the classpath costs that entry and nothing else.
     *
     * <p>Which members each holder contributes is not answered here. That is in the manifest
     * entries {@link #of(Project)} returns.
     *
     * @param receiver the type to find contributions for
     * @return the holders, empty when the type has no qualified name, while the project is
     *         indexing, when no library contributes to it, or when none of the named holders
     *         resolves
     * @throws NullPointerException if {@code receiver} is {@code null}
     */
    @Unmodifiable
    @NotNull
    public static List<PsiClass> contributingTo(@NotNull final PsiClass receiver) {
        Objects.requireNonNull(receiver, "receiver");
        final String qualified = receiver.getQualifiedName();
        final Project project = receiver.getProject();
        if (qualified == null || DumbService.getInstance(project).isDumb()) {
            return List.of();
        }

        final Set<String> holders = declared(project).get(qualified);
        if (holders == null || holders.isEmpty()) {
            return List.of();
        }

        final JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
        final GlobalSearchScope scope = GlobalSearchScope.allScope(project);
        final List<PsiClass> found = new ArrayList<>();
        for (final String holder : holders) {
            final PsiClass resolved = facade.findClass(qualifiedNameOf(holder), scope);
            if (resolved != null) {
                found.add(resolved);
            }
        }
        return List.copyOf(found);
    }

    /**
     * Returns every extension declared by a library manifest.
     *
     * <p>One entry per contributed member, not per holder, in the order the manifests and their
     * entries were read. Nothing is deduplicated: two libraries shipping the same extension are
     * both listed, which is what makes a duplicate visible instead of hiding one of the two.
     *
     * <p>Rebuilt on every call from the cached manifests.
     *
     * @param project the project whose dependencies are read
     * @return the extensions, empty when no library declares one
     * @throws NullPointerException if {@code project} is {@code null}
     */
    @Unmodifiable
    @NotNull
    public static List<Declared> of(@NotNull final Project project) {
        Objects.requireNonNull(project, "project");
        final List<Declared> found = new ArrayList<>();
        for (final LibraryManifests.Parsed parsed : LibraryManifests.of(project)) {
            for (final WeaveManifest.Extension extension : parsed.manifest().extensions()) {
                found.add(new Declared(extension, parsed.origin()));
            }
        }
        return List.copyOf(found);
    }

    /**
     * One extension from a library manifest, with the library it came from.
     *
     * @param declared the extension entry as the manifest states it
     * @param origin   the presentable path of the library root it was read from
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public record Declared(@NotNull WeaveManifest.Extension declared, @NotNull String origin) {

        /**
         * Rejects an entry that is missing either half.
         *
         * @throws NullPointerException if {@code declared} or {@code origin} is {@code null}
         */
        public Declared {
            Objects.requireNonNull(declared, "declared");
            Objects.requireNonNull(origin, "origin");
        }
    }

    /**
     * Returns the receiver-to-holders index, building it once per set of dependencies.
     *
     * <p>Cached on the project and invalidated by {@link ProjectRootManager}, which is the same
     * dependency {@link LibraryManifests} uses, so the index and the manifests under it are
     * discarded together.
     *
     * @param project the project whose dependencies are read
     * @return the holders' class names by receiver name; the key is the source spelling and the
     *         values are the manifest's own binary names
     */
    @NotNull
    private static Map<String, Set<String>> declared(@NotNull final Project project) {
        return CachedValuesManager.getManager(project).getCachedValue(project,
                () -> CachedValueProvider.Result.create(index(project),
                        ProjectRootManager.getInstance(project)));
    }

    /**
     * Builds the receiver-to-holders index from every library manifest.
     *
     * <p>Insertion order is kept for both the receivers and each receiver's holders, so the order
     * a holder is offered in follows the order of the dependencies rather than a hash. A holder
     * contributing several members to one receiver is recorded once.
     *
     * @param project the project whose dependencies are read
     * @return the holders' class names, as the manifest states them, keyed by the receiver's name
     *         in the source spelling
     */
    @NotNull
    private static Map<String, Set<String>> index(@NotNull final Project project) {
        final Map<String, Set<String>> byReceiver = new LinkedHashMap<>();
        for (final LibraryManifests.Parsed parsed : LibraryManifests.of(project)) {
            for (final WeaveManifest.Extension extension : parsed.manifest().extensions()) {
                byReceiver.computeIfAbsent(qualifiedNameOf(extension.receiver()),
                                key -> new LinkedHashSet<>())
                        .add(extension.className());
            }
        }
        return byReceiver;
    }

    /**
     * Converts a binary class name into the spelling PSI uses.
     *
     * <p>Every {@code $} becomes a {@code .}; nothing else is examined, so a name that holds a
     * {@code $} of its own is changed too.
     *
     * @param binaryName the name as the manifest states it
     * @return the qualified name
     */
    @NotNull
    private static String qualifiedNameOf(@NotNull final String binaryName) {
        return binaryName.replace('$', '.');
    }
}
