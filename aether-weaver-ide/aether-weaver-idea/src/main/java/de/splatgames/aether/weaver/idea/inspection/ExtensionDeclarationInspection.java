package de.splatgames.aether.weaver.idea.inspection;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiType;
import com.intellij.psi.impl.source.PsiExtensibleClass;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import de.splatgames.aether.weaver.idea.psi.ExtensionDeclarations;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reports an {@code @Extension} holder, or one of the methods it contributes, that the build would refuse.
 *
 * <p>Every class in the file is offered to the check and everything without {@code @Extension} is passed over. A
 * holder is then examined as a whole, and afterwards each of its own {@code public} methods in turn.
 *
 * <h2>What is reported about the holder</h2>
 *
 * <ul>
 *   <li>{@code AW1300} on the class name, when it is not {@code final}. Carries the fix that adds the keyword, and
 *       is the only holder-level report that does not stop the rest of the checks: a holder that is merely not
 *       final is still worth checking method by method.
 *   <li>{@code AW1306} on the class name, when the holder declares type parameters. Nothing further is examined.
 *   <li>{@code AW1307} on the class name, when the holder implements an interface or extends anything but
 *       {@link Object}. Nothing further is examined. Only the {@code extends} clause is treated leniently: a
 *       supertype named there that the module cannot resolve is not counted, so a file being edited is not reported
 *       against on that account alone. Every name in the {@code implements} list counts whether or not it resolves.
 * </ul>
 *
 * <h2>What is reported about a contributed method</h2>
 *
 * <p>In the order the checks run, each of which ends the examination of that method:
 *
 * <ul>
 *   <li>{@code AW1301} on the method name, when it is not {@code static}. Carries the fix that adds the keyword.
 *   <li>{@code AW1310} on the method name, when the method declares type parameters.
 *   <li>{@code AW1313} on the {@code @Receiver} annotation, when the method carries one and a parameter carries one
 *       as well.
 *   <li>{@code AW1302} on the method name, when nothing names a receiver — no {@code @Receiver} on the method, none
 *       on a parameter, and no type on the holder's own {@code @Extension}. Carries the fix that marks the first
 *       parameter, but only where that parameter's type is a class type; marking a primitive would trade this
 *       report for {@code AW1304}.
 *   <li>{@code AW1316} on the method name, when the holder's {@code @Extension} names a type, no {@code @Receiver}
 *       marks any parameter or the method itself, and the method's first parameter — or the absence of one — is not
 *       declared as exactly that type. The comparison is on the written type and nothing is inferred, so a subtype
 *       is reported too, and a method taking no parameters at all is reported the same way. A method that marks a
 *       parameter {@code @Receiver} with some other type is not checked against the holder's declared type here at
 *       all.
 *   <li>{@code AW1303} on the marked parameter, when {@code @Receiver} is on a parameter other than the first.
 *       Carries the fix that moves it.
 *   <li>{@code AW1304} on the receiver — the parameter, or the annotation for the static form — when what it names
 *       is not a class type. The static form's default, {@code void.class}, arrives here.
 *   <li>{@code AW1311} on the receiver, when the type it names carries type arguments.
 *   <li>{@code AW1308} on the method name, when the holder already contributes a call of the same name and the same
 *       parameter types <em>after the receiver</em> — two contributions whose receivers differ but whose remaining
 *       parameters agree are the same call here, however legal the two method signatures are in Java.
 *   <li>{@code AW1305} on the method name, when the receiver or one of its supertypes already declares a method of
 *       that name taking those parameter types.
 *   <li>{@code AW1312} on the receiver, when it is {@link Object}. This is the one report that is not followed by a
 *       {@code return}, being the last check there is.
 * </ul>
 *
 * <p>Every report is registered as {@link ProblemHighlightType#GENERIC_ERROR_OR_WARNING} whatever severity the
 * {@link DiagnosticCode} declares, and the code is carried at the head of the message.
 *
 * <h2>Declared members, never resolved ones</h2>
 *
 * <p>Two checks read a class's own declarations rather than everything it offers. The holder's methods are read
 * that way because {@link PsiClass#getMethods()} includes what it inherits, and {@link Object} declares nine
 * {@code public} methods that inherit into every holder — a correct holder would be reported nine times over for
 * being {@code static} on none of them, since none of {@code getClass}, {@code hashCode}, {@code equals},
 * {@code toString}, {@code notify}, {@code notifyAll} or the three overloads of {@code wait} is ever declared
 * {@code static}. The receiver's methods are read that way because resolving them runs this plugin's own
 * augmentation, which has just added the very method being checked for a collision.
 *
 * <p>A class that is not a {@link PsiExtensibleClass} therefore has no methods examined at all.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class ExtensionDeclarationInspection extends AbstractBaseJavaLocalInspectionTool {

    /** The receiver every expression in the program would answer to, reported rather than accepted silently. */
    private static final String OBJECT = "java.lang.Object";

    /**
     * Creates the inspection.
     *
     * <p>Held by the platform for the lifetime of the IDE and used from every inspection run, so it carries no state
     * of its own; the per-run state lives in the visitor and in the set of calls seen so far for one holder.
     */
    public ExtensionDeclarationInspection() {
        // Stateless.
    }

    /**
     * Returns a visitor that examines every class in the file.
     *
     * @param holder     the holder every report is registered on
     * @param isOnTheFly whether the run is an editor pass rather than a batch one; not used, because every check
     *                   here reads one holder and the types it names
     * @return a visitor whose {@code visitClass} performs the whole inspection
     */
    @Override
    @NotNull
    public PsiElementVisitor buildVisitor(@NotNull final ProblemsHolder holder,
                                          final boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitClass(@NotNull final PsiClass declared) {
                inspectHolder(declared, holder);
            }
        };
    }

    /**
     * Checks one class and, where the class itself is sound enough to carry them, its contributed methods.
     *
     * <p>The set of calls seen so far is created here and shared across the holder's methods, which is what makes
     * {@code AW1308} a statement about the holder rather than about one declaration.
     *
     * @param declared the class to examine, which need not be an extension holder
     * @param holder   the holder reports are registered on
     */
    private static void inspectHolder(@NotNull final PsiClass declared,
                                      @NotNull final ProblemsHolder holder) {
        if (!ExtensionDeclarations.isExtension(declared)) {
            return;
        }

        if (!declared.hasModifierProperty(PsiModifier.FINAL)) {
            report(holder, anchorOf(declared), DiagnosticCode.EXTENSION_NOT_FINAL,
                    "an extension class is never instantiated and never subclassed",
                    new MakeExtensionFinalFix(anchorOf(declared)));
        }
        if (declared.getTypeParameters().length > 0) {
            report(holder, anchorOf(declared), DiagnosticCode.EXTENSION_IS_GENERIC,
                    "contributed methods are looked up by descriptor, and a type parameter on the "
                            + "holder has nothing to bind to at the call site");
            return;
        }
        if (hasSupertype(declared)) {
            report(holder, anchorOf(declared), DiagnosticCode.EXTENSION_HAS_SUPERTYPE,
                    "nothing about the holder participates at the call site, so a supertype states "
                            + "a relationship the framework cannot honour");
            return;
        }

        // Own declarations, not getMethods(). getMethods() includes what the class inherits,
        // and Object's public methods inherit into every holder — so a class declaring one correct
        // extension would have been reported five times for toString, equals, hashCode, wait and
        // notify each "marking no @Receiver". Which they do not, because they are not its methods.
        final Set<String> seen = new LinkedHashSet<>();
        for (final PsiMethod method : ownMethodsOf(declared)) {
            if (method.isConstructor() || !method.hasModifierProperty(PsiModifier.PUBLIC)) {
                continue;
            }
            inspectMethod(method, seen, holder);
        }
    }

    /**
     * Checks one {@code public} method of a holder, and reports at most one thing about it.
     *
     * <p>The three forms a receiver can take are separated here: named on the method, marked on a parameter, or
     * named once on the holder's own {@code @Extension} and taken by position from every method.
     *
     * @param method the method to examine
     * @param seen   the calls this holder has contributed so far, added to as each method is accepted
     * @param holder the holder reports are registered on
     */
    private static void inspectMethod(@NotNull final PsiMethod method,
                                      @NotNull final Set<String> seen,
                                      @NotNull final ProblemsHolder holder) {
        final PsiElement anchor = anchorOf(method);

        if (!method.hasModifierProperty(PsiModifier.STATIC)) {
            report(holder, anchor, DiagnosticCode.EXTENSION_METHOD_NOT_STATIC,
                    "the receiver is passed as a parameter, so a contributed method has no "
                            + "instance of its own to be called on",
                    new MakeContributedMethodStaticFix(anchor));
            return;
        }
        if (method.getTypeParameters().length > 0) {
            report(holder, anchor, DiagnosticCode.EXTENSION_METHOD_IS_GENERIC,
                    "the stub the compiler resolves against would carry a type variable with "
                            + "nothing to bind it");
            return;
        }

        final PsiParameter[] parameters = method.getParameterList().getParameters();
        final int receiverAt = receiverIndex(parameters);
        final PsiAnnotation onMethod = ExtensionDeclarations.receiverAnnotationOf(method);

        if (onMethod != null && receiverAt >= 0) {
            report(holder, onMethod, DiagnosticCode.EXTENSION_RECEIVER_DECLARED_TWICE,
                    "the method form contributes a static method to a type and the parameter form "
                            + "contributes an instance method to its values; a declaration that "
                            + "asks for both says which of the two it is nowhere");
            return;
        }
        if (onMethod != null) {
            inspectStaticContribution(method, onMethod, seen, holder);
            return;
        }

        if (receiverAt < 0) {
            final PsiClass declaring = method.getContainingClass();
            final PsiType forTheClass = declaring == null
                    ? null
                    : ExtensionDeclarations.classReceiverOf(declaring);
            if (forTheClass == null) {
                report(holder, anchor, DiagnosticCode.EXTENSION_RECEIVER_MISSING,
                        "every public method of an extension class is contributed to a type, and "
                                + "@Receiver is what names that type — on the first parameter for an "
                                + "instance method, on the method for a static one",
                        markableReceiverOf(parameters));
                return;
            }
            // A class-level receiver: parameter zero is it by position, and must be declared as
            // that type. Nothing is inferred from the type — a method taking something else is
            // refused by the build rather than quietly left out.
            if (parameters.length == 0
                    || !forTheClass.equals(parameters[0].getType())) {
                report(holder, anchor, DiagnosticCode.EXTENSION_RECEIVER_NOT_THE_CLASSES,
                        "this class contributes to " + forTheClass.getPresentableText()
                                + ", so every contributed method must take that type first");
                return;
            }
        }
        if (receiverAt > 0) {
            report(holder, parameters[receiverAt], DiagnosticCode.EXTENSION_RECEIVER_NOT_FIRST,
                    "the rewrite passes the receiver straight through as argument zero, which is "
                            + "where the JVM has already put it for the virtual call",
                    new MoveReceiverParameterFirstFix(parameters[receiverAt]));
            return;
        }

        final PsiParameter receiver = parameters[0];
        final PsiType type = receiver.getType();
        if (!(type instanceof final PsiClassType declaredType)) {
            report(holder, receiver, DiagnosticCode.EXTENSION_RECEIVER_NOT_A_TYPE,
                    "a primitive, an array and a type variable have no class file for the compiler "
                            + "to resolve a contributed method against");
            return;
        }
        if (declaredType.getParameterCount() > 0) {
            report(holder, receiver, DiagnosticCode.EXTENSION_RECEIVER_IS_PARAMETERISED,
                    "erasure is all the call site has, so this would be contributed to every "
                            + declaredType.rawType().getPresentableText() + " in the program");
            return;
        }

        contributes(method, parameters, 1, declaredType, receiver, seen, holder);
    }

    /**
     * Checks a method whose {@code @Receiver} is on the method itself, contributing a static method to a type.
     *
     * <p>Every parameter is an argument of the contributed call here, which is why the offset handed on is zero
     * where the parameter form hands on one.
     *
     * @param method   the method to examine
     * @param onMethod the {@code @Receiver} written on it, which is also what the reports are anchored to
     * @param seen     the calls this holder has contributed so far
     * @param holder   the holder reports are registered on
     */
    private static void inspectStaticContribution(@NotNull final PsiMethod method,
                                                  @NotNull final PsiAnnotation onMethod,
                                                  @NotNull final Set<String> seen,
                                                  @NotNull final ProblemsHolder holder) {
        final PsiType named = ExtensionDeclarations.receiverTypeOf(onMethod);
        if (!(named instanceof final PsiClassType declaredType)) {
            report(holder, onMethod, DiagnosticCode.EXTENSION_RECEIVER_NOT_A_TYPE,
                    "a @Receiver on a method names the type that gains a static method, and its "
                            + "default — void — is not a type that can have one");
            return;
        }
        if (declaredType.getParameterCount() > 0) {
            report(holder, onMethod, DiagnosticCode.EXTENSION_RECEIVER_IS_PARAMETERISED,
                    "erasure is all the call site has, so this would be contributed to every "
                            + declaredType.rawType().getPresentableText() + " in the program");
            return;
        }

        contributes(method, method.getParameterList().getParameters(), 0, declaredType, onMethod,
                seen, holder);
    }

    /**
     * Checks what a method would contribute against what the holder has contributed already and what the receiver
     * already has.
     *
     * <p>The call is recorded whether or not the checks that follow report anything, so a second method of the same
     * shape is reported as a duplicate even where the first was reported as a collision.
     *
     * @param method     the method being contributed
     * @param parameters the method's parameters, receiver included
     * @param from       the index of the first parameter that is an argument of the contributed call: one for the
     *                   parameter form, zero for the static form
     * @param type       the receiver the call would be written on
     * @param anchor     where a report about the receiver is registered — the parameter, or the annotation
     * @param seen       the calls this holder has contributed so far
     * @param holder     the holder reports are registered on
     */
    private static void contributes(@NotNull final PsiMethod method,
                                    final PsiParameter @NotNull [] parameters,
                                    final int from,
                                    @NotNull final PsiClassType type,
                                    @NotNull final PsiElement anchor,
                                    @NotNull final Set<String> seen,
                                    @NotNull final ProblemsHolder holder) {
        if (!seen.add(callKeyOf(method, parameters, from))) {
            report(holder, anchorOf(method), DiagnosticCode.DUPLICATE_EXTENSION,
                    "this holder already contributes a call of that shape, and both would rewrite "
                            + "the same instruction");
            return;
        }

        final PsiClass target = type.resolve();
        if (target != null && declaresSignature(target, from, method, parameters)) {
            report(holder, anchorOf(method), DiagnosticCode.EXTENSION_COLLIDES_WITH_MEMBER,
                    "javac resolves the call to that method, so this extension would never be "
                            + "reached; use @Weave with @Inject or @Redirect to change what an "
                            + "existing method does");
            return;
        }

        if (target != null && OBJECT.equals(target.getQualifiedName())) {
            report(holder, anchor, DiagnosticCode.EXTENSION_RECEIVER_IS_OBJECT,
                    "every expression in every module that reads this extension will offer "
                            + method.getName() + ", including expressions whose type has nothing to "
                            + "do with what it means");
        }
    }

    /**
     * Builds the key that decides whether two methods contribute the same call.
     *
     * <p>Written from canonical type names rather than from erased ones, and only from the parameters at and after
     * {@code from} — the receiver itself never enters the key, so two overloads whose receiver types differ but
     * whose remaining parameters agree are one key here even though Java accepts both declarations. The build's own
     * duplicate check, {@code ExtensionChecks}, keys on the receiver as well as the name and the erased descriptor,
     * so the two disagree on exactly this case.
     *
     * @param method     the method being contributed
     * @param parameters the method's parameters, receiver included
     * @param from       the index of the first parameter that is an argument of the contributed call
     * @return the method's name followed by the argument types, each terminated by a comma
     */
    @NotNull
    private static String callKeyOf(@NotNull final PsiMethod method,
                                    final PsiParameter @NotNull [] parameters,
                                    final int from) {
        final StringBuilder key = new StringBuilder(method.getName()).append('(');
        for (int i = from; i < parameters.length; i++) {
            key.append(parameters[i].getType().getCanonicalText()).append(',');
        }
        return key.append(')').toString();
    }

    /**
     * Returns the methods a class declares itself.
     *
     * @param declared the class to read
     * @return its own methods, or an empty list when it is not a {@link PsiExtensibleClass} and its declarations
     *         cannot be told apart from what it inherits
     */
    @NotNull
    private static List<PsiMethod> ownMethodsOf(@NotNull final PsiClass declared) {
        return declared instanceof final PsiExtensibleClass extensible
                ? extensible.getOwnMethods()
                : List.of();
    }

    /**
     * Chooses whether {@code AW1302} can offer to mark a parameter.
     *
     * @param parameters the method's parameters
     * @return the fix that marks the first parameter, or no fix at all when there is no first parameter or its type
     *         is not a class type — marking a primitive would replace {@code AW1302} with {@code AW1304} and leave
     *         the author where they started
     */
    private static LocalQuickFix @NotNull [] markableReceiverOf(
            final PsiParameter @NotNull [] parameters) {
        if (parameters.length == 0 || !(parameters[0].getType() instanceof PsiClassType)) {
            return new LocalQuickFix[0];
        }
        return new LocalQuickFix[]{new MarkReceiverParameterFix(parameters[0].getParent()
                .getParent())};
    }

    /**
     * Finds the parameter carrying {@code @Receiver}.
     *
     * <p>Every parameter is searched, not only the first, which is what lets {@code AW1303} say that the annotation
     * is in the wrong place rather than that it is missing.
     *
     * @param parameters the method's parameters
     * @return the index of the first parameter annotated {@code @Receiver}, or {@code -1} when none is
     */
    private static int receiverIndex(final PsiParameter @NotNull [] parameters) {
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].getModifierList() != null
                    && parameters[i].getModifierList()
                    .findAnnotation(ExtensionDeclarations.RECEIVER) != null) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Reports whether the receiver already has a method the contributed call would resolve to instead.
     *
     * @param receiver    the class the call would be written on
     * @param from        the index of the first parameter that is an argument of the contributed call
     * @param contributed the method being contributed, read for its name
     * @param parameters  the contributed method's parameters, receiver included
     * @return whether the receiver or any of its supertypes declares a method of that name and those argument types
     */
    private static boolean declaresSignature(@NotNull final PsiClass receiver,
                                             final int from,
                                             @NotNull final PsiMethod contributed,
                                             final PsiParameter @NotNull [] parameters) {
        return declaresSignature(receiver, from, contributed, parameters, new HashSet<>());
    }

    /**
     * Searches one class and its supertypes for a declaration the contributed call would resolve to.
     *
     * @param type        the class to search
     * @param from        the index of the first parameter that is an argument of the contributed call
     * @param contributed the method being contributed, read for its name
     * @param parameters  the contributed method's parameters, receiver included
     * @param visited     the qualified names already searched, which stops an interface reached along two paths
     *                    from being searched twice
     * @return whether a declaration was found; a class whose qualified name has already been visited answers
     *         {@code false} without searching further
     */
    private static boolean declaresSignature(@NotNull final PsiClass type,
                                             final int from,
                                             @NotNull final PsiMethod contributed,
                                             final PsiParameter @NotNull [] parameters,
                                             @NotNull final Set<String> visited) {
        final String name = type.getQualifiedName();
        if (name != null && !visited.add(name)) {
            return false;
        }
        // Own declarations, never getMethods(). See this class's documentation: getMethods()
        // runs augmentation, and augmentation has just added the very method being checked.
        final List<PsiMethod> own = type instanceof final PsiExtensibleClass extensible
                ? extensible.getOwnMethods()
                : List.of();
        for (final PsiMethod candidate : own) {
            if (candidate.getName().equals(contributed.getName())
                    && matches(candidate.getParameterList().getParameters(), parameters,
                    from)) {
                return true;
            }
        }
        for (final PsiClassType supertype : type.getSuperTypes()) {
            final PsiClass resolved = supertype.resolve();
            if (resolved != null
                    && declaresSignature(resolved, from, contributed, parameters, visited)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Compares an existing method's parameters against the arguments of a contributed call.
     *
     * <p>Types are compared for equality and arity for an exact match, so neither a widening conversion nor a
     * subtype counts. This is what keeps an overload from being reported as a collision.
     *
     * @param existing    the parameters of the method the receiver already declares
     * @param contributed the contributed method's parameters, receiver included
     * @param from        the index of the first parameter that is an argument of the contributed call
     * @return whether the two describe the same call
     */
    private static boolean matches(final PsiParameter @NotNull [] existing,
                                   final PsiParameter @NotNull [] contributed,
                                   final int from) {
        if (existing.length != contributed.length - from) {
            return false;
        }
        for (int i = 0; i < existing.length; i++) {
            if (!existing[i].getType().equals(contributed[i + from].getType())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reports whether the holder states a relationship to another type.
     *
     * @param declared the holder to examine
     * @return whether it implements any interface, or extends a class that resolves to something other than
     *         {@link Object}; an {@code extends} clause that resolves to nothing answers {@code false}, because a
     *         file mid-edit and a fixture without a JDK look the same from here and the plugin may say less than
     *         the build but never something else
     */
    private static boolean hasSupertype(@NotNull final PsiClass declared) {
        if (declared.getImplementsListTypes().length > 0) {
            return true;
        }
        for (final PsiClassType extended : declared.getExtendsListTypes()) {
            final PsiClass resolved = extended.resolve();
            // An unresolved supertype is somebody mid-edit or a fixture without a JDK. Silence is
            // the answer either way: the plugin may say less than the build, never something else.
            if (resolved != null
                    && !OBJECT.equals(resolved.getQualifiedName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Chooses what a report about a class underlines.
     *
     * @param owner the class being reported on
     * @return its name identifier, or the class itself when it has none, so that the underline covers the name
     *         rather than the whole declaration
     */
    @NotNull
    private static PsiElement anchorOf(@NotNull final PsiClass owner) {
        final PsiElement identifier = owner.getNameIdentifier();
        return identifier == null ? owner : identifier;
    }

    /**
     * Chooses what a report about a method underlines.
     *
     * @param owner the method being reported on
     * @return its name identifier, or the method itself when it has none, so that the underline covers the name
     *         rather than the whole declaration
     */
    @NotNull
    private static PsiElement anchorOf(@NotNull final PsiMethod owner) {
        final PsiElement identifier = owner.getNameIdentifier();
        return identifier == null ? owner : identifier;
    }

    /**
     * Registers one report, with its code at the head of the message.
     *
     * <p>The message is the code, a colon and the explanation, which is the shape every test in this package reads
     * a code back out of. The highlight type is the same for every code, so a warning-severity code and an
     * error-severity one are rendered alike.
     *
     * @param holder the holder the report is registered on
     * @param anchor the element to underline
     * @param code   the diagnostic whose name the message opens with
     * @param why    the explanation, written to follow the code and a colon
     * @param fixes  the quick fixes to offer, of which there may be none
     */
    private static void report(@NotNull final ProblemsHolder holder,
                               @NotNull final PsiElement anchor,
                               @NotNull final DiagnosticCode code,
                               @NotNull final String why,
                               final LocalQuickFix @NotNull ... fixes) {
        holder.registerProblem(anchor, code.code() + ": " + why,
                ProblemHighlightType.GENERIC_ERROR_OR_WARNING, fixes);
    }
}
