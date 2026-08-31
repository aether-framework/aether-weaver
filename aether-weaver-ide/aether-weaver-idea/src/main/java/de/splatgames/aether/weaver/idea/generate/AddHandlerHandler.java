package de.splatgames.aether.weaver.idea.generate;

import com.intellij.codeInsight.generation.ClassMember;
import com.intellij.codeInsight.generation.GenerateMembersHandlerBase;
import com.intellij.codeInsight.generation.GenerationInfo;
import com.intellij.codeInsight.generation.PsiGenerationInfo;
import com.intellij.codeInsight.generation.MemberChooserObject;
import com.intellij.codeInsight.generation.PsiMethodMember;
import com.intellij.openapi.editor.Editor;
import com.intellij.ui.SimpleColoredComponent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiNameHelper;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiTypes;
import com.intellij.psi.util.ClassUtil;
import de.splatgames.aether.weaver.idea.bytecode.TargetLocals;
import de.splatgames.aether.weaver.idea.bytecode.TargetOperations;
import de.splatgames.aether.weaver.api.select.MemberSelector;
import de.splatgames.aether.weaver.idea.psi.HandlerSignature;
import de.splatgames.aether.weaver.idea.psi.SelectorTargets;
import de.splatgames.aether.weaver.idea.psi.TargetMembers;
import de.splatgames.aether.weaver.idea.psi.WeaveDeclarations;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import javax.swing.JTree;

import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Writes weave handlers, and is the only place in this plugin that writes one.
 *
 * <p>Two roles in one class. As a {@link GenerateMembersHandlerBase} it drives the Generate menu
 * entry: the members it offers are the injectable methods of the weave's targets, the choice is
 * made in {@code AddHandlerDialog} rather than in the platform's own chooser, and the prototype it
 * returns is what the framework inserts. The static {@code handlerFor} overloads are the generator
 * proper, and both dialogs render their preview through them, so a preview shows the text that
 * will be written rather than an approximation of it.
 *
 * <p>The generated method is assembled as source text and parsed back into PSI. Every type in it
 * is written fully qualified; shortening those to imports belongs to whoever adds the method to a
 * file.
 *
 * <p>Nothing is written on speculation. Where a selector cannot be parsed, a type does not resolve,
 * a name would not be an identifier, or the chosen point needs an operation that was not given, the
 * generator returns {@code null} and the caller reports it. That is deliberate: a handler that
 * looks right and carries an annotation the engine rejects costs the reader a diagnostic on code
 * they did not type.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class AddHandlerHandler extends GenerateMembersHandlerBase {

    /** The annotation an inject handler carries, written out in full. */
    private static final String INJECT = "de.splatgames.aether.weaver.api.Inject";

    /** The annotation a redirect handler carries, written out in full. */
    private static final String REDIRECT = "de.splatgames.aether.weaver.api.Redirect";

    /** The annotation a narrowed search is written with. */
    private static final String SLICE = "de.splatgames.aether.weaver.api.Slice";

    /** The annotation a captured local's parameter carries. */
    private static final String LOCAL = "de.splatgames.aether.weaver.api.Local";

    /** The annotation that carries the point, the target and the ordinal. */
    private static final String AT = "de.splatgames.aether.weaver.api.At";

    /** The prefix a point constant is written with; the constant's own name follows it. */
    private static final String POINT = "de.splatgames.aether.weaver.api.Point.";

    /** The title of the platform's own member chooser, which is reached only when nothing is offered. */
    private static final String CHOOSER_TITLE = "Choose Target Methods to Handle";

    /**
     * The most characters an identifier derived from foreign text keeps.
     *
     * <p>The text is a member name out of somebody else's class file, and a handler named after all of
     * it reads as a stack trace.
     */
    private static final int MAX_NAME_PART = 24;

    /** The parameter name used when nothing derived from the type or the local is an identifier. */
    private static final String FALLBACK_PARAMETER = "value";

    /** The name of the callback parameter, numbered if a target parameter already has it. */
    private static final String CALLBACK_NAME = "callback";

    /**
     * Bounds the loop in {@link #nameFor}, which tries the unsuffixed name and then {@code base2}
     * through {@code base(NAME_ATTEMPTS - 2)} - {@code NAME_ATTEMPTS - 2} candidates in all - before
     * giving up and returning {@code base(NAME_ATTEMPTS - 1)} without testing it.
     */
    private static final int NAME_ATTEMPTS = 200;

    /**
     * The choices this invocation generates with.
     *
     * <p>Seeded from what the last invocation stored and replaced wholesale by the dialog.
     */
    private HandlerOptions options = HandlerOptions.load();

    /**
     * The dialog's answer to "which locals does this member capture", or {@code null} before it has
     * been asked.
     *
     * <p>Held as a function rather than as a list because the framework asks for one prototype at a
     * time and the answer depends on the member.
     */
    private java.util.function.BiFunction<PsiMethod, TargetOperations.Operation,
            List<TargetLocals.Capture>> captures;

    /**
     * The dialog's answer to "which slice does this member use", or {@code null} before it has been
     * asked.
     */
    private java.util.function.Function<PsiMethod, TargetOperations.Bounds> bounds;

    /**
     * Creates the handler with the platform chooser's title and the stored options.
     */
    public AddHandlerHandler() {
        super(CHOOSER_TITLE);
    }

    /**
     * Asks what to generate, through this plugin's own dialog rather than the platform's chooser.
     *
     * <p>With nothing to offer the base class is left to say so. Otherwise the dialog collects the
     * whole of {@link HandlerOptions} alongside the chosen member, stores it for the next invocation,
     * and leaves behind the two per-member answers - the captured locals and the slice - that
     * {@link #generateMemberPrototypes(PsiClass, ClassMember)} asks for again as it builds each
     * prototype.
     *
     * @param weave   the weave the handler is generated into
     * @param project the project the dialog belongs to
     * @param editor  the editor the action was invoked from
     * @return the chosen members, or {@code null} when the dialog was cancelled
     */
    @Override
    @Nullable
    protected ClassMember[] chooseOriginalMembers(@NotNull final PsiClass weave,
                                                  @NotNull final Project project,
                                                  @NotNull final Editor editor) {
        final ClassMember[] offered = getAllOriginalMembers(weave);
        if (offered.length == 0) {
            return super.chooseOriginalMembers(weave, project, editor);
        }

        final List<PsiMethod> targets = new ArrayList<>(offered.length);
        for (final ClassMember member : offered) {
            targets.add(((PsiMethodMember) member).getElement());
        }

        final AddHandlerDialog dialog = new AddHandlerDialog(project, weave, targets, this.options);
        if (!dialog.showAndGet()) {
            return null;
        }
        this.options = dialog.options();
        this.options.save();
        this.captures = dialog::capturesFor;
        this.bounds = dialog::boundsFor;

        return dialog.chosen().toArray(ClassMember.EMPTY_ARRAY);
    }

    /**
     * One operation inside one target method, offered as a member the chooser can hold.
     *
     * <p>The platform's model is a member per row, and an operation is not a member. Wrapping it lets
     * the same selection machinery carry either answer, with the enclosing method as the parent node
     * so that a row still reads as belonging to something.
     *
     * @param method    the target method the operation was found in
     * @param operation the operation the handler will attach to
     * @author Erik Pförtner
     * @since 0.1.0
     */
    record OperationMember(@NotNull PsiMethod method,
                           @NotNull TargetOperations.Operation operation) implements ClassMember {

        /**
         * Renders the row as the operation's label.
         *
         * @param component the component to append to
         * @param tree      the tree the row belongs to
         */
        @Override
        public void renderTreeNode(final SimpleColoredComponent component, final JTree tree) {
            component.append(getText());
        }

        /**
         * Returns the operation's label.
         *
         * @return the label the operation carries
         */
        @Override
        @NotNull
        public String getText() {
            return this.operation.label();
        }

        /**
         * Returns the enclosing target method, so that rows group under the method they were found in.
         *
         * @return the target method as a chooser object
         */
        @Override
        @NotNull
        public MemberChooserObject getParentNodeDelegate() {
            return new PsiMethodMember(this.method);
        }
    }

    /**
     * Returns every method of the weave's targets a handler can be generated for.
     *
     * <p>Own methods only, and only those that can be attached to - a constructor, an abstract
     * method and a native method are not - so an inherited method is offered on the class that
     * declares it or not at all.
     *
     * @param weave the weave whose targets are read
     * @return the offered methods, empty when no target resolves or none of them has an injectable
     *         method
     */
    @Override
    protected ClassMember @NotNull [] getAllOriginalMembers(@NotNull final PsiClass weave) {
        final List<ClassMember> members = new ArrayList<>();
        for (final PsiClass target : WeaveDeclarations.targetsOf(weave)) {
            for (final PsiMethod candidate : TargetMembers.ownMethodsOf(target)) {
                if (isInjectable(candidate)) {
                    members.add(new PsiMethodMember(candidate));
                }
            }
        }
        return members.toArray(ClassMember.EMPTY_ARRAY);
    }

    /**
     * Builds the method the framework will insert for one chosen member.
     *
     * <p>An {@code OperationMember} generates a handler attached to its operation and a
     * {@link PsiMethodMember} one attached to a position in the method; anything else generates
     * nothing.
     *
     * @param weave  the weave the handler is generated into
     * @param member the chosen member
     * @return one prototype, or an empty array when the generator declined to write anything
     */
    @Override
    protected GenerationInfo @NotNull [] generateMemberPrototypes(@NotNull final PsiClass weave,
                                                                  @NotNull final ClassMember member) {
        final PsiMethod handler = switch (member) {
            case OperationMember chosen -> handlerFor(weave, chosen.method(), chosen.operation(),
                    capturesFor(chosen.method(), chosen.operation()),
                    boundsFor(chosen.method()), this.options);
            case PsiMethodMember chosen -> handlerFor(weave, chosen.getElement(), null,
                    capturesFor(chosen.getElement(), null),
                    boundsFor(chosen.getElement()), this.options);
            default -> null;
        };
        return handler == null
                ? GenerationInfo.EMPTY_ARRAY
                : new GenerationInfo[]{new PsiGenerationInfo<>(handler)};
    }

    /**
     * Returns the locals the dialog chose to capture for this member.
     *
     * @param target    the target method
     * @param operation the operation the handler attaches to, or {@code null} for a positional point
     * @return the captures, empty when no dialog has run
     */
    @NotNull
    private List<TargetLocals.Capture> capturesFor(@NotNull final PsiMethod target,
                                                   @Nullable final TargetOperations.Operation operation) {
        return this.captures == null ? List.of() : this.captures.apply(target, operation);
    }

    /**
     * Returns the slice the dialog chose for this member.
     *
     * @param target the target method
     * @return the bounds, or {@code null} when no dialog has run or no slice was chosen
     */
    @Nullable
    private TargetOperations.Bounds boundsFor(@NotNull final PsiMethod target) {
        return this.bounds == null ? null : this.bounds.apply(target);
    }

    /**
     * Generates a handler at a position, with no captures and no slice.
     *
     * @param weave   the weave the handler is written for
     * @param target  the method to attach to
     * @param options the choices to generate with
     * @return the generated method, or {@code null} when it cannot be written
     */
    @Nullable
    static PsiMethod handlerFor(@NotNull final PsiClass weave,
                                @NotNull final PsiMethod target,
                                @NotNull final HandlerOptions options) {
        return handlerFor(weave, target, null, options);
    }

    /**
     * Generates a handler at an operation, with no captures and no slice.
     *
     * @param weave     the weave the handler is written for
     * @param target    the method to attach to
     * @param operation the operation to attach to, or {@code null} for a positional point
     * @param options   the choices to generate with
     * @return the generated method, or {@code null} when it cannot be written
     */
    @Nullable
    static PsiMethod handlerFor(@NotNull final PsiClass weave,
                                @NotNull final PsiMethod target,
                                @Nullable final TargetOperations.Operation operation,
                                @NotNull final HandlerOptions options) {
        return handlerFor(weave, target, operation, List.of(), options);
    }

    /**
     * Generates a handler with captures but no slice.
     *
     * @param weave     the weave the handler is written for
     * @param target    the method to attach to
     * @param operation the operation to attach to, or {@code null} for a positional point
     * @param captures  the locals to capture
     * @param options   the choices to generate with
     * @return the generated method, or {@code null} when it cannot be written
     */
    @Nullable
    static PsiMethod handlerFor(@NotNull final PsiClass weave,
                                @NotNull final PsiMethod target,
                                @Nullable final TargetOperations.Operation operation,
                                @NotNull final List<TargetLocals.Capture> captures,
                                @NotNull final HandlerOptions options) {
        return handlerFor(weave, target, operation, captures, null, options);
    }

    /**
     * Generates the handler itself.
     *
     * <p>The text is assembled in the order it is read - an optional documentation comment, the
     * {@code @Inject} or {@code @Redirect} annotation, the modifiers, the return type, the name, the
     * parameters and a body - and parsed against the weave, so the result carries the project's own
     * language level.
     *
     * <p>Nothing is written where anything would have to be guessed. The result is {@code null} when
     * the target cannot be named by a selector, when the point needs an operation and none was given,
     * when a redirect was asked for at something that cannot be stood in for, when a type in the
     * signature does not resolve, or when the name or any parameter name would not be an identifier.
     *
     * @param weave     the weave the handler is written for, which decides the modifiers, the resolve
     *                  scope and whether a receiver parameter is needed
     * @param target    the method to attach to
     * @param operation the operation to attach to, or {@code null} for a positional point
     * @param captures  the locals to capture; ignored for a redirect
     * @param bounds    the slice to narrow the search to, or {@code null} for the whole method
     * @param options   the choices to generate with
     * @return the generated method, or {@code null} when it cannot be written
     */
    @Nullable
    static PsiMethod handlerFor(@NotNull final PsiClass weave,
                                @NotNull final PsiMethod target,
                                @Nullable final TargetOperations.Operation operation,
                                @NotNull final List<TargetLocals.Capture> captures,
                                @Nullable final TargetOperations.Bounds bounds,
                                @NotNull final HandlerOptions options) {
        final String selector = selectorFor(weave, target, options.selector());
        if (selector == null || options.point().needsOperation() && operation == null) {
            return null;
        }
        final boolean redirecting = options.kind() == HandlerOptions.Kind.REDIRECT;
        if (redirecting && (operation == null || !operation.isRedirectable())) {
            return null;
        }

        final Parameters parameters = redirecting
                ? redirectParametersFor(weave, operation.redirects())
                : parametersFor(weave, target, captures, options.callback());
        if (parameters == null) {
            return null;
        }

        final String returns = redirecting
                ? sourceNameOf(weave, operation.redirects().returnType())
                : "void";
        if (returns == null) {
            return null;
        }
        final String name = nameFor(weave, target, operation, parameters, options);
        if (!declarable(weave, name, parameters)) {
            return null;
        }
        final String text = (options.javadoc() ? javadocFor(target, parameters, options) : "")
                + annotationFor(selector, operation, bounds, options) + '\n'
                + modifiersFor(weave, options) + returns + ' ' + name
                + '(' + parameters.text() + ") {\n"
                + bodyFor(returns, name, options)
                + "}\n";
        return JavaPsiFacade.getElementFactory(weave.getProject()).createMethodFromText(text, weave);
    }

    /**
     * Reports whether the name and every parameter name are identifiers in this project.
     *
     * <p>Asked before the text is parsed. The names are derived from a member name, a type name or a
     * local variable name read out of a class file, and none of those has to be spellable here.
     *
     * @param weave      the weave whose project decides what an identifier is
     * @param name       the handler's name
     * @param parameters the parameters, whose documented names are the written ones
     * @return {@code true} when all of them can be declared
     */
    private static boolean declarable(@NotNull final PsiClass weave,
                                      @NotNull final String name,
                                      @NotNull final Parameters parameters) {
        final PsiNameHelper names = PsiNameHelper.getInstance(weave.getProject());
        if (!usable(names, name)) {
            return false;
        }
        for (final Documented parameter : parameters.documented()) {
            if (!usable(names, parameter.name())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the body of the generated method.
     *
     * <p>A non-void handler gets the marker whether or not one was asked for, and a {@code return}
     * of the type's default value, which is what makes the body compile.
     *
     * @param returns the return type as source text
     * @param name    the handler's name, which the marker names
     * @param options the choices to generate with
     * @return the body, without its braces
     */
    @NotNull
    private static String bodyFor(@NotNull final String returns,
                                  @NotNull final String name,
                                  @NotNull final HandlerOptions options) {
        final String marker = options.todo() || !"void".equals(returns)
                ? "    // TODO: " + name + '\n'
                : "";
        return marker + ("void".equals(returns) ? "" : "    return " + defaultOf(returns) + ";\n");
    }

    /**
     * Returns the value a generated {@code return} carries.
     *
     * <p>Matched against the type as written, so anything that is not one of the eight primitive
     * spellings is a reference type and gets {@code null}.
     *
     * @param type the return type as source text
     * @return the literal to return
     */
    @NotNull
    private static String defaultOf(@NotNull final String type) {
        return switch (type) {
            case "boolean" -> "false";
            case "char" -> "'\\0'";
            case "byte", "short", "int" -> "0";
            case "long" -> "0L";
            case "float" -> "0.0F";
            case "double" -> "0.0D";
            default -> "null";
        };
    }

    /**
     * Builds the parameter list of a redirect from the operation's own shape.
     *
     * <p>A redirect stands in for the operation, so its parameters are exactly what the operation
     * consumed - for an instance call that includes the receiver, which the shape carries first.
     * Names are derived from the types, an array becoming a plural, and are numbered where the same
     * type occurs twice.
     *
     * @param weave the weave whose resolve scope names the types
     * @param shape the operation's descriptor
     * @return the parameters, or {@code null} when one of the types cannot be written
     */
    @Nullable
    private static Parameters redirectParametersFor(@NotNull final PsiClass weave,
                                                    @NotNull final MethodTypeDesc shape) {
        final StringJoiner written = new StringJoiner(", ");
        final StringJoiner erased = new StringJoiner(",", "(", ")");
        final List<Documented> documented = new ArrayList<>();
        final Set<String> used = new LinkedHashSet<>();

        for (int index = 0; index < shape.parameterCount(); index++) {
            final String type = sourceNameOf(weave, shape.parameterType(index));
            if (type == null) {
                return null;
            }
            final String base = parameterNameOf(weave, simpleNameOf(type).replace("[]", "s"));
            String name = base;
            for (int suffix = 2; used.contains(name); suffix++) {
                name = base + suffix;
            }
            used.add(name);
            documented.add(new Documented(name, index == 0 && shape.parameterCount() > 0
                    ? "what the operation was performed on or with"
                    : "an input the operation would have received"));
            written.add(type + ' ' + name);
            erased.add(type);
        }
        return new Parameters(written.toString(), List.copyOf(documented), erased.toString());
    }

    /**
     * Writes a descriptor type as source text.
     *
     * <p>Resolution is an improvement rather than a requirement: a type that resolves is written as
     * the qualified name PSI reports, and one that does not is written as its binary name with the
     * nested separator replaced, which is still what the reader would have typed.
     *
     * @param weave the weave whose resolve scope is searched
     * @param type  the type to write
     * @return the type as source text; never {@code null}, since neither the primitive branch nor the
     *         reference branch can produce one for the array branch to propagate
     */
    @Nullable
    private static String sourceNameOf(@NotNull final PsiClass weave, @NotNull final ClassDesc type) {
        if (type.isArray()) {
            final String component = sourceNameOf(weave, type.componentType());
            return component == null ? null : component + "[]";
        }
        if (type.isPrimitive()) {
            return type.displayName();
        }
        final String binary = type.packageName().isEmpty()
                ? type.displayName()
                : type.packageName() + '.' + type.displayName();
        final PsiClass resolved = JavaPsiFacade.getInstance(weave.getProject())
                .findClass(binary.replace('$', '.'), weave.getResolveScope());
        return resolved != null && resolved.getQualifiedName() != null
                ? resolved.getQualifiedName()
                : binary.replace('$', '.');
    }

    /**
     * Writes the {@code @Inject} or {@code @Redirect} annotation.
     *
     * <p>Where an operation was chosen its ordinal wins over the one the match rule pins: the
     * operation's is the ordinal of the row the user picked, and the rule's is about narrowing a set
     * of positions. The long form, naming {@code value}, is written whenever an operation was chosen
     * or the rule pins an ordinal; the short {@code @At(Point.X)} form is written otherwise. An
     * operation read from the source rather than a class file carries no ordinal of its own - its
     * ordinal is {@code -1} - so a chosen operation does not by itself guarantee the long form; it is
     * the {@code operation == null} test, not the ordinal, that decides between the two.
     *
     * <p>The slice is written unnamed, and the {@code @At} refers to no slice by id, because a point
     * that names no slice carries the empty id that the engine compares against.
     *
     * <p>A bound of zero is not written at all, so the default match rule adds no attributes.
     *
     * @param selector  the target method selector
     * @param operation the operation the handler attaches to, or {@code null} for a positional point
     * @param bounds    the slice, or {@code null} for the whole method
     * @param options   the choices to generate with
     * @return the annotation as source text
     */
    @NotNull
    private static String annotationFor(@NotNull final String selector,
                                        @Nullable final TargetOperations.Operation operation,
                                        @Nullable final TargetOperations.Bounds bounds,
                                        @NotNull final HandlerOptions options) {
        final HandlerOptions.Match match = options.match();
        // An operation carries its own ordinal, and it is the ordinal of the row the user picked.
        // The match rule's ordinal is about narrowing a set of positions and has nothing to say here.
        final int ordinal = operation != null ? operation.ordinal() : match.pinnedOrdinal();

        final StringBuilder at = new StringBuilder(48).append('@').append(AT);
        if (ordinal < 0 && operation == null) {
            at.append('(').append(POINT).append(options.point().name()).append(')');
        } else {
            at.append("(value = ").append(POINT).append(options.point().name());
            if (operation != null) {
                at.append(", target = ").append(quoted(operation.target()));
            }
            if (ordinal >= 0) {
                at.append(", ordinal = ").append(ordinal);
            }
            at.append(')');
        }

        final StringBuilder annotation = new StringBuilder(64)
                .append('@')
                .append(options.kind() == HandlerOptions.Kind.REDIRECT ? REDIRECT : INJECT)
                .append("(method = ").append(quoted(selector)).append(", at = ").append(at);
        if (bounds != null) {
            // Unnamed, and the @At carries no slice reference: a point that names no slice
            // carries "", and SliceSpec.matches compares exactly that against the slice's id. An id
            // would have to be written twice to say what an empty one already says once.
            annotation.append(", slice = @").append(SLICE)
                    .append("(from = ").append(atFor(bounds.from()))
                    .append(", to = ").append(atFor(bounds.to())).append(')');
        }
        if (match.require() > 0) {
            annotation.append(", require = ").append(match.require());
        }
        if (match.allow() > 0) {
            annotation.append(", allow = ").append(match.allow());
        }
        if (!options.group().isEmpty()) {
            annotation.append(", group = ").append(quoted(options.group()));
        }
        return annotation.append(')').toString();
    }

    /**
     * Writes one slice bound as a fully specified {@code @At}.
     *
     * <p>Both the target and the ordinal are always written: a bound that resolved to several
     * positions would bound nothing.
     *
     * @param operation the instruction the bound names
     * @return the annotation as source text
     */
    @NotNull
    private static String atFor(@NotNull final TargetOperations.Operation operation) {
        return '@' + AT + "(value = " + POINT + operation.point().name()
                + ", target = " + quoted(operation.target())
                + ", ordinal = " + operation.ordinal() + ')';
    }

    /**
     * Returns the text as a Java string literal.
     *
     * @param text the text to quote, escaped as the platform escapes it
     * @return the literal, quotes included
     */
    @NotNull
    private static String quoted(@NotNull final String text) {
        return '"' + StringUtil.escapeStringCharacters(text) + '"';
    }

    /**
     * Writes the handler's modifiers.
     *
     * <p>A static weave's handler is {@code static}, and its automatic visibility is {@code public}
     * because the injected call crosses classes; an instance weave's is merged into the target, where
     * {@code private} is reachable. Package-private is written as nothing at all, since what PSI calls
     * it is not a Java keyword.
     *
     * @param weave   the weave, whose kind decides the automatic answers
     * @param options the choices to generate with
     * @return the modifiers, with a trailing space, or an empty string when there are none
     */
    @NotNull
    private static String modifiersFor(@NotNull final PsiClass weave,
                                       @NotNull final HandlerOptions options) {
        final boolean isStatic = WeaveDeclarations.isStaticWeave(weave);
        String visibility = options.visibility().modifier();
        if (visibility == null) {
            visibility = isStatic ? PsiModifier.PUBLIC : PsiModifier.PRIVATE;
        } else if (PsiModifier.PACKAGE_LOCAL.equals(visibility)) {
            visibility = "";
        }

        final StringJoiner modifiers = new StringJoiner(" ", "", " ");
        modifiers.setEmptyValue("");
        if (!visibility.isEmpty()) {
            modifiers.add(visibility);
        }
        if (isStatic) {
            modifiers.add(PsiModifier.STATIC);
        }
        return modifiers.toString();
    }

    /**
     * Renders the documentation comment that goes above the handler.
     *
     * <p>A summary naming the point and the target method, then one parameter line per parameter,
     * including the receiver, the captured locals and the callback. The point is named in lower case
     * as its constant is spelled.
     *
     * @param target     the method being attached to
     * @param parameters the parameters to describe
     * @param options    the choices to generate with
     * @return the comment, ending in a newline
     */
    @NotNull
    private static String javadocFor(@NotNull final PsiMethod target,
                                     @NotNull final Parameters parameters,
                                     @NotNull final HandlerOptions options) {
        final StringBuilder comment = new StringBuilder(128)
                .append("/**\n * Runs at the ")
                .append(options.point().name().toLowerCase(Locale.ROOT))
                .append(" of {@code ").append(target.getName()).append("}.\n");
        if (!parameters.documented().isEmpty()) {
            comment.append(" *\n");
            for (final Documented parameter : parameters.documented()) {
                comment.append(" * @param ").append(parameter.name()).append(' ')
                        .append(parameter.description()).append('\n');
            }
        }
        return comment.append(" */\n").toString();
    }

    /**
     * Writes the selector naming the target method.
     *
     * <p>The requested form is used only when it names that method and nothing else from the weave's
     * point of view; otherwise the fully qualified source form is written instead. A spelling is a
     * preference about how to write a target, and silently naming a different method to honour it
     * would be the worst answer available.
     *
     * @param weave  the weave the selector is resolved from
     * @param target the method to name
     * @param form   the requested form
     * @return the selector, or {@code null} when the target cannot be named at all
     */
    @Nullable
    private static String selectorFor(@NotNull final PsiClass weave,
                                      @NotNull final PsiMethod target,
                                      @NotNull final HandlerOptions.Selector form) {
        final String qualified = sourceFormOf(target, false);
        if (qualified == null) {
            return null;
        }
        final String wanted = switch (form) {
            case QUALIFIED -> qualified;
            case SIMPLE -> sourceFormOf(target, true);
            case DESCRIPTOR -> descriptorFormOf(target);
        };
        return wanted != null && namesExactly(weave, wanted, target) ? wanted : qualified;
    }

    /**
     * Writes the source form of a method selector.
     *
     * <p>The framework's own parser is asked whether the result is a selector, and a rejection is a
     * refusal to write it: text this plugin invented that the parser rejects becomes {@code AW1015}
     * on code the user did not type.
     *
     * @param target the method to name
     * @param simple whether parameter types are written as simple names
     * @return the selector, or {@code null} when a parameter type does not resolve or the result does
     *         not parse
     */
    @Nullable
    private static String sourceFormOf(@NotNull final PsiMethod target, final boolean simple) {
        final StringJoiner parameters = new StringJoiner(", ", "(", ")");
        for (final PsiParameter parameter : target.getParameterList().getParameters()) {
            final String type = HandlerSignature.erasedNameOf(parameter.getType());
            if (type == null) {
                return null;
            }
            parameters.add(simple ? simpleNameOf(type) : type);
        }
        final String selector = target.getName() + parameters;
        try {
            MemberSelector.parse(selector);
        } catch (final RuntimeException malformed) {
            // The framework's own parser is the authority on what it accepts. Writing something it
            // rejects would put the user in front of AW1015 on code they did not type.
            return null;
        }
        return selector;
    }

    /**
     * Writes the descriptor form of a method selector.
     *
     * <p>The descriptor comes from the platform's own encoder rather than from anything here, and the
     * result is put through {@link MemberSelector} for the same reason the source form is.
     *
     * @param target the method to name
     * @return the selector, prefixed as a descriptor, or {@code null} when the platform cannot encode
     *         the method or the result does not parse
     */
    @Nullable
    private static String descriptorFormOf(@NotNull final PsiMethod target) {
        final String descriptor = ClassUtil.getAsmMethodSignature(target);
        if (descriptor == null || descriptor.isBlank()) {
            return null;
        }
        final String selector = MemberSelector.DESCRIPTOR_PREFIX + target.getName() + descriptor;
        try {
            MemberSelector.parse(selector);
        } catch (final RuntimeException malformed) {
            return null;
        }
        return selector;
    }

    /**
     * Reports whether the selector resolves to exactly the given method.
     *
     * @param weave    the weave the selector is resolved from
     * @param selector the selector to test
     * @param target   the method it has to name
     * @return {@code true} when it resolves to that method and no other
     */
    private static boolean namesExactly(@NotNull final PsiClass weave,
                                        @NotNull final String selector,
                                        @NotNull final PsiMethod target) {
        return target.equals(SelectorTargets.exact(weave, selector));
    }

    /**
     * A parameter as the generated comment describes it.
     *
     * <p>Carries the name that was actually written, which is not always the name it was derived
     * from: a local's recorded name goes into the {@code @Local} attribute untouched while the
     * parameter beside it may have had to be renamed to be an identifier.
     *
     * @param name        the parameter name as written
     * @param description the phrase the parameter line carries
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private record Documented(@NotNull String name, @NotNull String description) {
    }

    /**
     * A generated parameter list, in the three shapes the generator needs it in.
     *
     * @param text       the list as written into the declaration, annotations included
     * @param documented one entry per parameter, in order, for the comment and the identifier check
     * @param erased     the erased types in parentheses, used to compare against a target method's
     *                   signature when choosing a name
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private record Parameters(@NotNull String text,
                              @Unmodifiable @NotNull List<Documented> documented,
                              @NotNull String erased) {
    }

    /**
     * Builds the parameter list of an inject handler.
     *
     * <p>In order: the receiver, present only when a static weave attaches to an instance method,
     * since such a handler has no {@code this}; the target's own parameters under their own names;
     * the captured locals; and the callback last. A capture's parameter may be renamed to be an
     * identifier or to avoid a collision, and is numbered where one occurs, but the name in its
     * {@code @Local} attribute is always the one the local variable table recorded.
     *
     * @param weave         the weave the handler is written for
     * @param target        the method being attached to
     * @param captures      the locals to capture
     * @param wantsCallback whether a callback parameter was asked for; a target whose return type has
     *                      no callback shape gets none regardless
     * @return the parameters, or {@code null} when a type involved cannot be written
     */
    @Nullable
    private static Parameters parametersFor(@NotNull final PsiClass weave,
                                            @NotNull final PsiMethod target,
                                            @NotNull final List<TargetLocals.Capture> captures,
                                            final boolean wantsCallback) {
        final StringJoiner written = new StringJoiner(", ");
        final StringJoiner erased = new StringJoiner(",", "(", ")");
        final List<Documented> documented = new ArrayList<>();
        final Set<String> used = new LinkedHashSet<>();

        if (WeaveDeclarations.isStaticWeave(weave) && !target.hasModifierProperty(PsiModifier.STATIC)) {
            final PsiClass owner = target.getContainingClass();
            final String qualified = owner == null ? null : owner.getQualifiedName();
            if (qualified == null) {
                return null;
            }
            final String name = parameterNameOf(weave, simpleNameOf(qualified));
            used.add(name);
            documented.add(new Documented(name,
                    "the instance the injected call was reached through"));
            written.add(qualified + ' ' + name);
            erased.add(qualified);
        }
        for (final PsiParameter parameter : target.getParameterList().getParameters()) {
            final String type = HandlerSignature.writableTextOf(parameter.getType());
            final String erasedType = HandlerSignature.erasedNameOf(parameter.getType());
            final String name = parameter.getName();
            if (type == null || erasedType == null) {
                return null;
            }
            used.add(name);
            documented.add(new Documented(name,
                    "the target's {@code " + name + "} argument, as it stands at the injection"));
            written.add(type + ' ' + name);
            erased.add(erasedType);
        }

        // Before the callback and after the target's own arguments. Position is free — both the
        // processor and this plugin skip a @Local parameter when checking the prefix rule — so the
        // one that reads best wins, and a trailing callback is what every other handler has.
        for (final TargetLocals.Capture capture : captures) {
            final String type = sourceNameOf(weave, capture.type());
            if (type == null) {
                return null;
            }
            // The parameter's name is the generator's to choose; the @Local attribute's is not.
            // A capture names the variable the table recorded, and that text goes into the
            // annotation untouched even when it cannot be a parameter name here.
            final String base = parameterNameOf(weave, capture.name());
            String name = base;
            for (int suffix = 2; used.contains(name); suffix++) {
                name = base + suffix;
            }
            used.add(name);
            documented.add(new Documented(name,
                    "the target's local {@code " + capture.name() + "}, as it stands at the site"));
            written.add('@' + LOCAL + "(name = " + quoted(capture.name()) + ") " + type + ' ' + name);
            erased.add(type);
        }

        final String callback = wantsCallback ? HandlerSignature.callbackTypeFor(target) : null;
        if (callback != null) {
            String name = CALLBACK_NAME;
            for (int suffix = 2; used.contains(name); suffix++) {
                name = CALLBACK_NAME + suffix;
            }
            documented.add(new Documented(name, PsiTypes.voidType().equals(target.getReturnType())
                    ? "the handle this handler can cancel the target through"
                    : "the handle this handler can cancel the target through, carrying the "
                            + "value it would return"));
            written.add(callback + ' ' + name);
            erased.add(rawNameOf(callback));
        }
        return new Parameters(written.toString(), List.copyOf(documented), erased.toString());
    }

    /**
     * Chooses the handler's name.
     *
     * <p>A handler attached to an operation is named after the operation rather than after the method
     * it was found in, so that three handlers on three calls in one method do not differ only by a
     * number.
     *
     * <p>A taken name is numbered rather than refused: two handlers on one target method, at different
     * points or for different concerns, are ordinary. Two sets of names are avoided - the weave's own
     * methods, and, for an instance weave, the target's methods by name and erased parameter types
     * together, because such a weave is merged into its target and the engine collides on both.
     *
     * @param weave      the weave the handler is written into
     * @param target     the method being attached to
     * @param operation  the operation the handler attaches to, or {@code null} for a positional point
     * @param parameters the parameters, whose erased form decides what counts as a collision
     * @param options    the choices to generate with
     * @return an unused name, or {@code base(NAME_ATTEMPTS - 1)}, untested, once every candidate up to
     *         it has been rejected
     */
    @NotNull
    private static String nameFor(@NotNull final PsiClass weave,
                                  @NotNull final PsiMethod target,
                                  @Nullable final TargetOperations.Operation operation,
                                  @NotNull final Parameters parameters,
                                  @NotNull final HandlerOptions options) {
        // A handler attached to an operation is named after the operation, not after the method it
        // happens to sit in — three handlers on three calls inside one method would otherwise differ
        // only by a number.
        final String subject = operation == null ? target.getName() : memberOf(operation);
        final String prefix = options.prefix();
        final String base = prefix.isEmpty() ? subject : prefix + capitalise(subject);

        final Set<String> taken = new LinkedHashSet<>();
        for (final PsiMethod declared : TargetMembers.ownMethodsOf(weave)) {
            taken.add(declared.getName());
        }

        final PsiClass owner = target.getContainingClass();
        final Set<String> merged = new LinkedHashSet<>();
        if (owner != null && !WeaveDeclarations.isStaticWeave(weave)) {
            for (final PsiMethod declared : TargetMembers.ownMethodsOf(owner)) {
                final String signature = TargetMembers.signatureOf(declared);
                if (signature != null) {
                    merged.add(signature);
                }
            }
        }

        // Two handlers on one target method are entirely legitimate — a different point, a different
        // concern — so a taken name is a reason to number, not to refuse.
        String name = base;
        for (int suffix = 2; suffix < NAME_ATTEMPTS; suffix++) {
            if (!taken.contains(name) && !merged.contains(name + parameters.erased())) {
                return name;
            }
            name = base + suffix;
        }
        return name;
    }

    /**
     * Derives a name from what the operation names.
     *
     * <p>The arguments and the owner are dropped, leaving the member. A constructor's target ends in
     * {@code <init>}, which is already its own last dotted segment, so prefixing it with {@code new}
     * yields {@code new<init>} rather than a name built from the constructed type; {@link #identifierOf}
     * then reduces that to {@code newInit} by dropping the angle brackets and capitalising the letter
     * that follows the one it drops. Where {@link #identifierOf} reduces the member to nothing at all -
     * text with no Java identifier character in it - the point's own name is used instead.
     *
     * @param operation the operation to name
     * @return a subject for the handler's name, never empty
     */
    @NotNull
    private static String memberOf(@NotNull final TargetOperations.Operation operation) {
        String target = operation.target();
        final int arguments = target.indexOf('(');
        if (arguments >= 0) {
            target = target.substring(0, arguments);
        }
        final int dot = target.lastIndexOf('.');
        final String member = dot < 0 ? target : target.substring(dot + 1);
        // A constructor's name is not one a Java method can carry, and "new Thing" reads better as
        // a handler called onNewThing than as onInit.
        final String named = "<init>".equals(member)
                ? "new" + capitalise(simpleNameOf(target))
                : member;
        final String identifier = identifierOf(named);
        return identifier.isEmpty()
                ? capitalise(operation.point().name().toLowerCase(Locale.ROOT))
                : identifier;
    }

    /**
     * Turns a suggestion into a parameter name.
     *
     * <p>Three attempts: the suggestion reduced to an identifier and uncapitalised, that with
     * {@code Value} appended - which is what saves a suggestion that is a keyword, since a keyword is
     * not an identifier - and finally {@link #FALLBACK_PARAMETER}.
     *
     * @param weave      the weave whose project decides what an identifier is
     * @param suggestion the text to derive from, typically a type or local variable name
     * @return a name that can be declared
     */
    @NotNull
    private static String parameterNameOf(@NotNull final PsiClass weave,
                                          @NotNull final String suggestion) {
        final PsiNameHelper names = PsiNameHelper.getInstance(weave.getProject());
        final String sanitised = uncapitalise(identifierOf(suggestion));
        if (usable(names, sanitised)) {
            return sanitised;
        }
        final String suffixed = sanitised + "Value";
        return usable(names, suffixed) ? suffixed : FALLBACK_PARAMETER;
    }

    /**
     * Reports whether the text can be declared as a name.
     *
     * @param names the platform's own answer for this project's language level
     * @param name  the text to test
     * @return {@code true} when it is a non-empty identifier
     */
    private static boolean usable(@NotNull final PsiNameHelper names, @NotNull final String name) {
        return !name.isEmpty() && names.isIdentifier(name);
    }

    /**
     * Reduces arbitrary text to an identifier.
     *
     * <p>Characters that cannot appear are dropped, and the next character that can is capitalised, so
     * that a separator becomes a word boundary rather than a run-together. The result is truncated to
     * {@link #MAX_NAME_PART} characters and may be empty, which the callers treat as failure.
     *
     * @param text the text to reduce
     * @return the identifier, possibly empty
     */
    @NotNull
    private static String identifierOf(@NotNull final String text) {
        final StringBuilder identifier = new StringBuilder(text.length());
        boolean capitalise = false;
        for (int index = 0; index < text.length(); index++) {
            final char character = text.charAt(index);
            final boolean usable = identifier.isEmpty()
                    ? Character.isJavaIdentifierStart(character)
                    : Character.isJavaIdentifierPart(character);
            if (!usable) {
                capitalise = !identifier.isEmpty();
                continue;
            }
            identifier.append(capitalise ? Character.toUpperCase(character) : character);
            capitalise = false;
        }
        return identifier.length() > MAX_NAME_PART
                ? identifier.substring(0, MAX_NAME_PART)
                : identifier.toString();
    }

    /**
     * Reports whether a handler can be offered for the method at all.
     *
     * <p>A native or abstract method has no body to inject into. A constructor is excluded as well,
     * regardless of point.
     *
     * @param candidate the method to test
     * @return {@code true} when the method can be attached to
     */
    private static boolean isInjectable(@NotNull final PsiMethod candidate) {
        return !candidate.isConstructor()
                && !candidate.hasModifierProperty(PsiModifier.ABSTRACT)
                && !candidate.hasModifierProperty(PsiModifier.NATIVE);
    }

    /**
     * Strips the type arguments from a written type.
     *
     * @param type the type as source text
     * @return the type up to its first type argument
     */
    @NotNull
    private static String rawNameOf(@NotNull final String type) {
        final int arguments = type.indexOf('<');
        return arguments < 0 ? type : type.substring(0, arguments);
    }

    /**
     * Returns the text after the last dot.
     *
     * @param qualified the text to cut
     * @return the last dotted segment, or the whole text when there is no dot
     */
    @NotNull
    private static String simpleNameOf(@NotNull final String qualified) {
        final int dot = qualified.lastIndexOf('.');
        return dot < 0 ? qualified : qualified.substring(dot + 1);
    }

    /**
     * Upper-cases the first character.
     *
     * @param name the text to capitalise
     * @return the text, unchanged when it is empty
     */
    @NotNull
    private static String capitalise(@NotNull final String name) {
        return name.isEmpty()
                ? name
                : name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
    }

    /**
     * Lower-cases the first character.
     *
     * @param name the text to uncapitalise
     * @return the text, unchanged when it is empty
     */
    @NotNull
    private static String uncapitalise(@NotNull final String name) {
        return name.isEmpty()
                ? name
                : name.substring(0, 1).toLowerCase(Locale.ROOT) + name.substring(1);
    }
}
