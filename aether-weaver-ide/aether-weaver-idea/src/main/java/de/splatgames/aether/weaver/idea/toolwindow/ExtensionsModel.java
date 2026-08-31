package de.splatgames.aether.weaver.idea.toolwindow;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import de.splatgames.aether.weaver.api.manifest.WeaveManifest;
import de.splatgames.aether.weaver.idea.index.ExtensionReceiverIndex;
import de.splatgames.aether.weaver.idea.library.LibraryExtensions;
import de.splatgames.aether.weaver.idea.psi.ExtensionDeclarations;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Collects what the project and its dependencies contribute through extensions, for the Weaves tool
 * window.
 *
 * <p>Two sources, and they are not symmetrical. A holder declared in the project is read from PSI,
 * so its contributions carry the declaration to navigate to and the module it belongs to. A holder
 * read out of a dependency's manifest carries the library it came from and no element: there is no
 * source to open. {@link Holder#fromLibrary()} is that distinction, and the window uses it to decide
 * what a row can offer.
 *
 * <p>{@link #of(Project)} keeps nothing between calls and rebuilds its whole list every time; the
 * rows it returns are immutable, so the window holds a snapshot rather than a live view. The
 * manifest index it reads to build that list is itself cached, by
 * {@link de.splatgames.aether.weaver.idea.library.LibraryExtensions} on the project and invalidated
 * by {@link com.intellij.openapi.roots.ProjectRootManager}, so this class rebuilding its own answer
 * does not force a rebuild of that index.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see WeavesToolWindowFactory
 */
public final class ExtensionsModel {

    /**
     * The value both {@code module} and {@code origin} take where the other one applies.
     *
     * <p>A project holder has a module and no origin; a library holder has an origin and no module.
     * Empty rather than {@code null} because the sort key is the module, and a comparator that has
     * to allow for {@code null} sorts by an accident of which half is missing.
     */
    private static final String NO_MODULE = "";

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private ExtensionsModel() {
        throw new AssertionError("no instances");
    }

    /**
     * One member a holder contributes to a receiver.
     *
     * <p>The signature is written as the call site reads, not as the declaration does: the receiver
     * of an instance contribution stands before the dot rather than in the parameter list, and a
     * constant is named alone, because {@code CENT} is what a user types and
     * {@code CENTLjava/math/BigDecimal;} is not.
     *
     * @param receiver  the qualified name of the type the member is contributed to, or {@code "?"}
     *                  where the declaration names a receiver that does not resolve
     * @param signature the member as it is called, with the receiver already removed for an instance
     *                  contribution and with no parameter list at all for a constant
     * @param kind      how the member is reached
     * @param element   the declaration to navigate to, or {@code null} for a contribution read from
     *                  a library manifest
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public record Contribution(@NotNull String receiver,
                               @NotNull String signature,
                               @NotNull WeaveManifest.Extension.Kind kind,
                               @Nullable PsiElement element) {

        /**
         * Rejects a row that is missing text the window would have to render.
         *
         * @throws NullPointerException if {@code receiver}, {@code signature} or {@code kind} is
         *                              {@code null}
         */
        public Contribution {
            Objects.requireNonNull(receiver, "receiver");
            Objects.requireNonNull(signature, "signature");
            Objects.requireNonNull(kind, "kind");
        }
    }

    /**
     * One extension class and everything it contributes.
     *
     * <p>A holder declared in the project carries a module and no origin; one read from a manifest
     * carries an origin, no module and no element; with an empty module it sorts in front of the
     * holders of any named module.
     *
     * @param name          the qualified name of the holder class, with nested classes separated by
     *                      dots even where the manifest wrote a {@code $}
     * @param module        the module declaring it, empty for a holder from a library and for a
     *                      class the platform cannot place in one
     * @param origin        the library it was read from, empty for a holder declared in the project
     * @param contributions its members, copied on construction
     * @param element       the class to navigate to, or {@code null} for a holder read from a
     *                      library manifest
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public record Holder(@NotNull String name,
                         @NotNull String module,
                         @NotNull String origin,
                         @Unmodifiable @NotNull List<Contribution> contributions,
                         @Nullable PsiElement element) {

        /**
         * Rejects a row that is missing text the window would have to render, and takes a copy of
         * the contributions so that a later edit to the caller's list cannot change a row on screen.
         *
         * @throws NullPointerException if {@code name}, {@code module}, {@code origin} or
         *                              {@code contributions} is {@code null}, or the list holds a
         *                              {@code null}
         */
        public Holder {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(module, "module");
            Objects.requireNonNull(origin, "origin");
            contributions = List.copyOf(Objects.requireNonNull(contributions, "contributions"));
        }

        /**
         * Reports whether this holder came from a dependency rather than from the project.
         *
         * <p>Equivalent to having no element, which is the property the window actually needs: a
         * row with nothing to navigate to.
         *
         * @return {@code true} when the holder was read from a library manifest
         */
        @Contract(pure = true)
        public boolean fromLibrary() {
            return this.element == null;
        }
    }

    /**
     * Collects every extension holder the project declares and every one its dependencies publish.
     *
     * <p>Reads a file-based index and resolves PSI, so it needs a read action and answers only
     * outside indexing; the tool window supplies both.
     *
     * <p>Ordered by module and then by name, which puts the holders from libraries — whose module is
     * empty — in front of those of any named module.
     *
     * @param project the project to read; must not be {@code null}
     * @return the holders, empty when neither the project nor its dependencies declare one
     * @throws NullPointerException if {@code project} is {@code null}
     */
    @Unmodifiable
    @NotNull
    public static List<Holder> of(@NotNull final Project project) {
        Objects.requireNonNull(project, "project");
        final List<Holder> holders = new ArrayList<>();

        for (final PsiClass declared : ExtensionReceiverIndex.allHolders(project)) {
            final Holder holder = holderOf(declared);
            if (holder != null) {
                holders.add(holder);
            }
        }
        holders.addAll(fromManifests(project));

        holders.sort(Comparator.comparing(Holder::module).thenComparing(Holder::name));
        return List.copyOf(holders);
    }

    /**
     * Builds the row for one holder declared in the project.
     *
     * <p>Contributed methods and contributed constants are listed together, in that order, because
     * the window shows them as one list of call sites.
     *
     * @param declared the holder class
     * @return the row, or {@code null} for a class with no qualified name — nothing names the
     *         holder itself, only the type its members are contributed to, but {@link Holder#name()}
     *         is what the window and its sort key need
     */
    @Nullable
    private static Holder holderOf(@NotNull final PsiClass declared) {
        final String name = declared.getQualifiedName();
        if (name == null) {
            return null;
        }

        final List<Contribution> contributions = new ArrayList<>();
        for (final PsiMethod method : ExtensionDeclarations.contributedBy(declared)) {
            // A receiver that fails to resolve is not necessarily AW1304 — that code covers only a
            // receiver that is not a class type in the first place. Either way there is nothing to
            // name it with, so nameOf falls back to a placeholder.
            contributions.add(new Contribution(
                    nameOf(ExtensionDeclarations.receiverOf(method)),
                    signatureOf(method),
                    ExtensionDeclarations.isStaticContribution(method)
                            ? WeaveManifest.Extension.Kind.STATIC
                            : WeaveManifest.Extension.Kind.INSTANCE,
                    method));
        }
        for (final PsiField constant : ExtensionDeclarations.constantsOf(declared)) {
            // The name alone: a constant's call site is `BigDecimal.CENT` and the row renders as
            // `→ java.math.BigDecimal.CENT`, which is exactly what a reader will type. Appending a
            // type would make it the one row in the window that is not a call site.
            contributions.add(new Contribution(
                    nameOf(ExtensionDeclarations.receiverOf(constant)),
                    constant.getName(),
                    WeaveManifest.Extension.Kind.CONSTANT,
                    constant));
        }

        final Module module = ModuleUtilCore.findModuleForPsiElement(declared);
        return new Holder(name, module == null ? NO_MODULE : module.getName(), NO_MODULE,
                contributions, declared);
    }

    /**
     * Builds the rows for the extensions the project's dependencies publish.
     *
     * <p>A manifest records one entry per member, so entries are folded back into one holder per
     * class name. Two libraries publishing a holder of the same name therefore end up as one row,
     * carrying the origin of whichever was read first.
     *
     * @param project the project whose dependencies are read
     * @return the rows, one per distinct holder class name, in the order the manifests were read
     */
    @NotNull
    private static List<Holder> fromManifests(@NotNull final Project project) {
        final List<Holder> holders = new ArrayList<>();
        for (final LibraryExtensions.Declared declared : LibraryExtensions.of(project)) {
            final WeaveManifest.Extension extension = declared.declared();
            final Contribution contribution = new Contribution(
                    extension.receiver().replace('$', '.'),
                    // A constant's descriptor is its type rather than a parameter list, and
                    // `CENTLjava/math/BigDecimal;` is not something anybody wants to read.
                    extension.kind() == WeaveManifest.Extension.Kind.CONSTANT
                            ? extension.name()
                            : extension.name() + extension.descriptor(),
                    extension.kind(),
                    null);

            final String name = extension.className().replace('$', '.');
            final Holder existing = lastNamed(holders, name);
            if (existing == null) {
                final List<Contribution> only = new ArrayList<>();
                only.add(contribution);
                holders.add(new Holder(name, NO_MODULE, declared.origin(), only, null));
                continue;
            }
            final List<Contribution> all = new ArrayList<>(existing.contributions());
            all.add(contribution);
            holders.set(holders.lastIndexOf(existing),
                    new Holder(name, NO_MODULE, existing.origin(), all, null));
        }
        return holders;
    }

    /**
     * Finds the row already built for a holder class name.
     *
     * <p>Searched from the end, because entries of one holder arrive together and the row being
     * filled is almost always the last one.
     *
     * @param holders the rows built so far
     * @param name    the qualified holder name to look for
     * @return the last row with that name, or {@code null} when there is none
     */
    @Contract(pure = true)
    @Nullable
    private static Holder lastNamed(@NotNull final List<Holder> holders,
                                    @NotNull final String name) {
        for (int i = holders.size() - 1; i >= 0; i--) {
            if (holders.get(i).name().equals(name)) {
                return holders.get(i);
            }
        }
        return null;
    }

    /**
     * Names the receiver a contribution is written on.
     *
     * <p>{@code AW1304} covers only a receiver that is not a {@link com.intellij.psi.PsiClassType} at
     * all — a primitive, an array, a type variable, or the unset {@code void} default — so a
     * receiver written as a class type that fails to resolve reaches this placeholder without any
     * diagnostic naming it, as does one that resolves to a class with no qualified name. This is
     * also called for a contributed constant, which
     * {@link de.splatgames.aether.weaver.idea.inspection.ExtensionDeclarationInspection} never
     * examines at all.
     *
     * @param receiver the resolved receiver, or {@code null} when the declaration names none that
     *                 resolves
     * @return the qualified name, or {@code "?"} when there is none or it has none
     */
    @Contract(pure = true)
    @NotNull
    private static String nameOf(@Nullable final PsiClass receiver) {
        return receiver == null || receiver.getQualifiedName() == null
                ? "?"
                : receiver.getQualifiedName();
    }

    /**
     * Renders a contributed method as its call site reads.
     *
     * <p>The first parameter of an instance contribution is the receiver and is dropped: it is
     * written before the dot at the call site, so a row listing it as an argument would describe a
     * call nobody can write. A static contribution keeps every parameter.
     *
     * @param method the contributed method
     * @return the name and the parameter list, types written in their presentable form
     */
    @Contract(pure = true)
    @NotNull
    private static String signatureOf(@NotNull final PsiMethod method) {
        final PsiParameter[] parameters = method.getParameterList().getParameters();
        final StringBuilder signature = new StringBuilder(method.getName()).append('(');
        for (int i = ExtensionDeclarations.isStaticContribution(method) ? 0 : 1;
                i < parameters.length; i++) {
            if (signature.charAt(signature.length() - 1) != '(') {
                signature.append(", ");
            }
            signature.append(parameters[i].getType().getPresentableText());
        }
        return signature.append(')').toString();
    }
}
