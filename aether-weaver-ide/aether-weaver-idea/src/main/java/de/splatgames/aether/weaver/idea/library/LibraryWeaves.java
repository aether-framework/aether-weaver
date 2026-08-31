package de.splatgames.aether.weaver.idea.library;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import de.splatgames.aether.weaver.api.manifest.WeaveManifest;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lists the weaves the project's dependencies ship, and answers which of them target a class.
 *
 * <p>A weave in a jar modifies this project without appearing in it, so nothing in the editor
 * would otherwise show it. This is where the tool window and the gutter markers get their entries
 * for one; everything here is read out of the manifests {@link LibraryManifests} parsed, and no
 * class file and no source is opened.
 *
 * <p>A weave is identified by its class name. Where two libraries ship the same weave class, the
 * one from the earlier root in dependency order is kept and the other is dropped, so a weave
 * appears once however many copies of it are on the classpath.
 *
 * <p>Nothing here consults an index, resolves a symbol or checks for dumb mode; a target is matched
 * as text.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class LibraryWeaves {

    /**
     * Prevents instantiation.
     *
     * @throws AssertionError always
     */
    private LibraryWeaves() {
        throw new AssertionError("no instances");
    }

    /**
     * One weave from a library manifest, with the library it came from.
     *
     * @param declared the weave entry as the manifest states it
     * @param origin   the presentable path of the library root it was read from
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public record Declared(@NotNull WeaveManifest.Weave declared, @NotNull String origin) {
    }

    /**
     * Returns every weave declared by a library manifest.
     *
     * <p>Cached on the project and invalidated by {@link ProjectRootManager}, so the list is
     * rebuilt when the dependencies change rather than per query.
     *
     * @param project the project whose dependencies are read
     * @return the weaves, one per class name, in the order the manifests were read
     */
    @Unmodifiable
    @NotNull
    public static List<Declared> of(@NotNull final Project project) {
        return CachedValuesManager.getManager(project).getCachedValue(project,
                () -> CachedValueProvider.Result.create(read(project),
                        ProjectRootManager.getInstance(project)));
    }

    /**
     * Returns the library weaves that name the given class among their targets.
     *
     * <p>The comparison is on the name and nothing else: no class is resolved, so a target naming a
     * class this project does not have still matches a query for that name. A manifest states its
     * targets as binary names, so a nested class arrives as {@code com.acme.Outer$Inner}; each
     * target has its {@code $} replaced by {@code .} before the comparison, which is what makes a
     * query in the spelling PSI uses find it.
     *
     * <p>Scans the cached list on every call.
     *
     * @param project    the project whose dependencies are read
     * @param targetName the target's qualified name, written the way PSI spells it
     * @return the weaves targeting that class, empty when none does
     */
    @Unmodifiable
    @NotNull
    public static List<Declared> targeting(@NotNull final Project project,
                                           @NotNull final String targetName) {
        final List<Declared> found = new ArrayList<>();
        for (final Declared candidate : of(project)) {
            // A manifest records targets as binary names, so a nested target reads
            // com.acme.Outer$Inner where PSI says com.acme.Outer.Inner. Comparing the two spellings
            // directly would silently answer "no weaves" for every nested class.
            for (final String target : candidate.declared().targets()) {
                if (targetName.equals(target.replace('$', '.'))) {
                    found.add(candidate);
                    break;
                }
            }
        }
        return List.copyOf(found);
    }

    /**
     * Collects the weaves of every library manifest, keeping the first of each class name.
     *
     * @param project the project whose dependencies are read
     * @return the weaves, in the order the manifests and their entries were read
     */
    @Unmodifiable
    @NotNull
    private static List<Declared> read(@NotNull final Project project) {
        final Map<String, Declared> weaves = new LinkedHashMap<>();
        for (final LibraryManifests.Parsed parsed : LibraryManifests.of(project)) {
            for (final WeaveManifest.Weave declared : parsed.manifest().weaves()) {
                weaves.putIfAbsent(declared.className(), new Declared(declared, parsed.origin()));
            }
        }
        return List.copyOf(weaves.values());
    }
}
