package de.splatgames.aether.weaver.idea.toolwindow;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import de.splatgames.aether.weaver.api.manifest.WeaveManifest;
import de.splatgames.aether.weaver.idea.index.WeaveTargetIndex;
import de.splatgames.aether.weaver.idea.library.LibraryWeaves;
import de.splatgames.aether.weaver.idea.psi.HandlerOrder;
import de.splatgames.aether.weaver.idea.psi.SelectorTargets;
import de.splatgames.aether.weaver.idea.psi.TargetMembers;
import de.splatgames.aether.weaver.idea.psi.WeaveDeclarations;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Collects the weaves of the project and of its dependencies, for the Weaves tool window.
 *
 * <p>What this can state and what it cannot follows from where each half comes from. A weave
 * declared in the project is read from PSI, so its handlers can be checked against the targets and
 * each row carries the declaration to navigate to. A weave read out of a dependency's manifest is
 * recorded, not resolved: its handlers are listed as {@link Binding#FROM_MANIFEST} and carry no
 * element.
 *
 * <p>Nothing here is the result of weaving. Which positions a handler matched is decided by the
 * build against compiled bytes, and the window says so in its footer; a handler reported as
 * {@link Binding#BOUND} has a target method of that name, not a confirmed injection site.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see WeavesToolWindowFactory
 */
public final class WeavesModel {

    /** The {@code @Weave} element carrying the priority, read as a literal. */
    private static final String PRIORITY_ATTRIBUTE = "priority";

    /** The {@code kind} a manifest records for a weave whose code is not merged into its target. */
    private static final String STATIC_KIND = "STATIC";

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private WeavesModel() {
        throw new AssertionError("no instances");
    }

    /**
     * What could be established about a handler's selector without building anything.
     *
     * <p>Three of the four are answers about a weave in the project; the fourth says the question
     * was not asked. The renderer greys {@link #BOUND} and marks everything else, so the distinction
     * between a selector that names nothing and one that could not be checked is the difference
     * between a mistake and a limitation.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public enum Binding {

        /**
         * The selector resolves to exactly one method.
         *
         * <p>For an unqualified selector that method is declared by one of the weave's own targets;
         * for a selector naming a qualified owner it is whatever class {@link SelectorTargets}
         * resolves project-wide, which need not be one of the weave's targets at all.
         */
        BOUND("bound"),

        /**
         * {@link SelectorTargets#exact} found no single method.
         *
         * <p>Covers more than a name the targets do not declare: a name matched by more than one
         * overload lands here as well, and so does a selector that does not parse, that names a
         * field, a constant or an initialiser, or whose owner is written as a simple class name.
         */
        UNBOUND("not bound here"),

        /** Nothing was checked: the weave resolves no target, or the handler wrote no selector. */
        UNKNOWN("target not resolved"),

        /** Read from a dependency's manifest, which records the handler rather than resolving it. */
        FROM_MANIFEST("from manifest");

        /** The text the window shows for this binding. */
        private final String label;

        /**
         * Creates a binding with the text the window shows for it.
         *
         * @param label the label
         */
        Binding(@NotNull final String label) {
            this.label = label;
        }

        /**
         * Returns the label, so that the renderer can append the binding directly.
         *
         * @return the label
         */
        @Override
        @NotNull
        public String toString() {
            return this.label;
        }
    }

    /**
     * One handler of a weave.
     *
     * <p>The priority is the weave's own — it is declared once, on the class — and is repeated here
     * because it is what decides which of two weaves on one method goes first, and the reader is
     * looking at the handler.
     *
     * @param name     the handler method's name
     * @param selector the {@code method} selector it declares, empty where none could be read
     * @param priority the priority of the declaring weave, {@code 0} where none is written
     * @param binding  what could be established about the selector
     * @param element  the handler method to navigate to, or {@code null} for a weave read from a
     *                 library manifest
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public record Handler(@NotNull String name,
                          @NotNull String selector,
                          int priority,
                          @NotNull Binding binding,
                          @Nullable PsiElement element) {
    }

    /**
     * One weave and what it declares.
     *
     * <p>{@code module} carries two different things depending on where the weave came from: the
     * module for a weave in the project, and the library for one read from a manifest. It is the
     * sort key either way, which keeps the two groups apart in the window.
     *
     * @param name     the qualified name of the weave class, with nested classes separated by dots
     *                 even where the manifest wrote a {@code $}
     * @param module   the declaring module, or the library a manifest weave came from; empty for a
     *                 class the platform cannot place in a module
     * @param merged   whether the weave's code is moved into its target, which is the case for
     *                 every weave that is not declared static
     * @param priority the declared priority, {@code 0} where none is written
     * @param targets  the qualified names of the classes it weaves into, empty when none resolves
     * @param handlers its handlers, in the order they run
     * @param element  the weave class to navigate to, or {@code null} for a weave read from a
     *                 library manifest
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public record Weave(@NotNull String name,
                        @NotNull String module,
                        boolean merged,
                        int priority,
                        @Unmodifiable @NotNull List<String> targets,
                        @Unmodifiable @NotNull List<Handler> handlers,
                        @Nullable PsiElement element) {
    }

    /**
     * Collects every weave the project declares and every one its dependencies publish.
     *
     * <p>Reads a file-based index and resolves PSI, so it needs a read action and answers only
     * outside indexing; the tool window supplies both.
     *
     * <p>Ordered by module and then by name, so that the weaves of one module stand together and the
     * order does not change between two refreshes of an unchanged project.
     *
     * @param project the project to read
     * @return the weaves, empty when neither the project nor its dependencies declare one
     */
    @NotNull
    public static List<Weave> of(@NotNull final Project project) {
        final List<Weave> weaves = new ArrayList<>();
        for (final PsiClass declared : WeaveTargetIndex.allWeaves(project)) {
            final Weave weave = weaveOf(declared);
            if (weave != null) {
                weaves.add(weave);
            }
        }
        for (final LibraryWeaves.Declared declared : LibraryWeaves.of(project)) {
            weaves.add(fromManifest(declared));
        }
        weaves.sort(Comparator.comparing(Weave::module).thenComparing(Weave::name));
        return List.copyOf(weaves);
    }

    /**
     * Builds the row for a weave recorded in a dependency's manifest.
     *
     * <p>Nothing is resolved: the manifest states what the dependency's build produced, so every
     * handler is listed as {@link Binding#FROM_MANIFEST} and the library takes the place of the
     * module. Binary names are rewritten with dots so that a nested class reads the way it was
     * written in source.
     *
     * @param declared the manifest entry and the library it was read from
     * @return the row
     */
    @NotNull
    private static Weave fromManifest(@NotNull final LibraryWeaves.Declared declared) {
        final WeaveManifest.Weave weave = declared.declared();
        final List<String> targets = new ArrayList<>(weave.targets().size());
        for (final String target : weave.targets()) {
            targets.add(target.replace('$', '.'));
        }
        final List<Handler> handlers = new ArrayList<>(weave.injectors().size());
        for (final WeaveManifest.Injector injector : weave.injectors()) {
            handlers.add(new Handler(injector.handler(), injector.method(), weave.priority(),
                    Binding.FROM_MANIFEST, null));
        }
        return new Weave(weave.className().replace('$', '.'),
                declared.origin(),
                !STATIC_KIND.equalsIgnoreCase(weave.kind()),
                weave.priority(),
                List.copyOf(targets),
                List.copyOf(handlers),
                null);
    }

    /**
     * Builds the row for a weave declared in the project.
     *
     * <p>A target that resolves to a class without a qualified name is listed under its simple name
     * rather than dropped, because a row with one target missing would say the weave has fewer than
     * it does.
     *
     * @param declared the weave class
     * @return the row, or {@code null} for a class with no qualified name
     */
    @Nullable
    private static Weave weaveOf(@NotNull final PsiClass declared) {
        final String name = declared.getQualifiedName();
        if (name == null) {
            return null;
        }
        final List<PsiClass> targets = WeaveDeclarations.targetsOf(declared);
        final List<String> targetNames = new ArrayList<>(targets.size());
        for (final PsiClass target : targets) {
            targetNames.add(target.getQualifiedName() == null
                    ? target.getName()
                    : target.getQualifiedName());
        }

        final Module module = ModuleUtilCore.findModuleForPsiElement(declared);
        final int priority = priorityOf(declared);
        return new Weave(name,
                module == null ? "" : module.getName(),
                !WeaveDeclarations.isStaticWeave(declared),
                priority,
                List.copyOf(targetNames),
                handlersOf(declared, targets, priority),
                declared);
    }

    /**
     * Builds the handler rows of one weave, in the order they run.
     *
     * <p>Sorted by {@code HandlerOrder.EXECUTION_ORDER} — priority first, then a name-based
     * tie-break — rather than by declaration order, because the order two handlers on one method run
     * in is not the order they are written in and this window is where a reader finds that out.
     *
     * <p>Only {@code @Inject} and {@code @Redirect} methods are listed; {@link #selectorOf} looks for
     * neither an {@code @Accessor}, an {@code @Invoker} nor a {@code @Wrap}. An {@code @Accessor} or
     * an {@code @Invoker} carries no {@code method} selector and has nothing to fill a list whose
     * columns are a selector and a binding, but a {@code @Wrap} does carry one and is left out of this
     * window regardless.
     *
     * @param weave    the weave class
     * @param targets  the classes it weaves into, used to decide each handler's binding
     * @param priority the weave's priority, carried into every handler
     * @return the handler rows
     */
    @Unmodifiable
    @NotNull
    private static List<Handler> handlersOf(@NotNull final PsiClass weave,
                                            @NotNull final List<PsiClass> targets,
                                            final int priority) {
        final List<PsiMethod> declared = new ArrayList<>();
        for (final PsiMethod method : TargetMembers.ownMethodsOf(weave)) {
            if (selectorOf(method) != null) {
                declared.add(method);
            }
        }
        declared.sort(HandlerOrder.EXECUTION_ORDER);

        final List<Handler> handlers = new ArrayList<>(declared.size());
        for (final PsiMethod method : declared) {
            final String selector = selectorOf(method);
            handlers.add(new Handler(method.getName(),
                    selector == null ? "" : selector,
                    priority,
                    bindingOf(weave, targets, selector),
                    method));
        }
        return List.copyOf(handlers);
    }

    /**
     * Decides what can be said about one handler's selector.
     *
     * <p>{@link Binding#UNKNOWN} rather than {@link Binding#UNBOUND} where there is nothing to check
     * against: a weave whose targets do not resolve would otherwise report every one of its handlers
     * as a mistake, in a project that may simply not have the target on its classpath yet.
     *
     * @param weave    the weave the handler belongs to
     * @param targets  the classes it weaves into
     * @param selector the handler's selector, or {@code null} when none could be read
     * @return the binding to show
     */
    @NotNull
    private static Binding bindingOf(@NotNull final PsiClass weave,
                                     @NotNull final List<PsiClass> targets,
                                     final String selector) {
        if (targets.isEmpty() || selector == null) {
            return Binding.UNKNOWN;
        }
        return SelectorTargets.exact(weave, selector) == null ? Binding.UNBOUND : Binding.BOUND;
    }

    /**
     * Returns the selector a handler declares.
     *
     * <p>{@code @Inject} is looked for first, so a method carrying both annotations is listed once,
     * under the selector of its {@code @Inject}.
     *
     * @param method the method to read
     * @return the selector text, or {@code null} when the method carries neither annotation or its
     *         {@code method} element is not a constant string
     */
    @Nullable
    private static String selectorOf(@NotNull final PsiMethod method) {
        for (final String annotation
                : List.of(WeaveDeclarations.INJECT, WeaveDeclarations.REDIRECT)) {
            final PsiAnnotation declared = WeaveDeclarations.annotation(method, annotation);
            final PsiElement value = declared == null
                    ? null
                    : declared.findAttributeValue(WeaveDeclarations.METHOD_ATTRIBUTE);
            if (value instanceof final PsiLiteralExpression literal
                    && literal.getValue() instanceof final String text) {
                return text;
            }
        }
        return null;
    }

    /**
     * Returns the priority a weave declares.
     *
     * <p>Read as a literal, so a priority written as a reference to a constant reads as {@code 0}
     * here even though the build sees its value. The window would otherwise have to resolve
     * arbitrary constant expressions to fill one column.
     *
     * @param weave the weave class
     * @return the declared priority, or {@code 0} when none is written or it is not an integer
     *         literal
     */
    private static int priorityOf(@NotNull final PsiClass weave) {
        final PsiAnnotation declared =
                WeaveDeclarations.annotation(weave, WeaveDeclarations.WEAVE);
        final PsiElement value = declared == null
                ? null
                : declared.findAttributeValue(PRIORITY_ATTRIBUTE);
        if (value instanceof final PsiLiteralExpression literal
                && literal.getValue() instanceof final Integer priority) {
            return priority;
        }
        return 0;
    }
}
