package de.splatgames.aether.weaver.processor;

import de.splatgames.aether.weaver.api.experimental.Nulls;
import de.splatgames.aether.weaver.api.Require;
import de.splatgames.aether.weaver.api.experimental.Scope;
import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import de.splatgames.aether.weaver.api.manifest.WeaveManifest;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Checks an {@code @Extension} holder and turns what it contributes into manifest entries.
 *
 * <p>An extension holder is an ordinary final class whose public members are contributed to another
 * type: a method to be called as though the receiver declared it, or a constant to be read off the
 * receiver as one of its own. Nothing about the holder survives at the call site, which is the
 * source of most of the rules here — a type parameter on the holder or on a method has nothing to
 * bind to, a supertype states a relationship that cannot be honoured, and a parameterised receiver
 * is indistinguishable from its raw form once erased.
 *
 * <p>Checking and collecting are one pass, not two. A contribution that reports an error is left
 * out of the returned list, and one that reports a warning is kept: {@code AW1300} and
 * {@code AW1312} do not stop a holder or a member from being contributed, and every other code here
 * does.
 *
 * <p>A member reaches these checks only if it is a candidate. A method that is not {@code public}
 * is the holder's own helper and is passed over in silence, which is what lets an extension class
 * factor out its own code; a field with no {@code @Receiver} is the holder's own state and is
 * likewise ignored, even when the holder names a class-level receiver.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
final class ExtensionChecks {

    /** The annotation that marks a class as an extension holder. */
    static final String EXTENSION = "de.splatgames.aether.weaver.api.experimental.Extension";

    /** The annotation that names the type a member is contributed to. */
    static final String RECEIVER = "de.splatgames.aether.weaver.api.experimental.Receiver";

    /**
     * Reads the receiver type a holder names once for the whole class.
     *
     * @param declaration the {@code @Extension} mirror, or {@code null}
     * @return the receiver's fully qualified name, or {@code null} when the holder names none —
     *         which includes the element's own default, {@code void}, that not being a declared
     *         type
     */
    @Contract(pure = true)
    @Nullable
    private static String receiverNamedBy(@Nullable final AnnotationMirror declaration) {
        final AnnotationValue value = Anchors.valueOf(declaration, "value");
        if (value == null || !(value.getValue() instanceof final TypeMirror named)
                || named.getKind() != TypeKind.DECLARED) {
            return null;
        }
        return nameOf(named).toString();
    }

    /**
     * Reads the {@code nulls} policy an annotation states.
     *
     * @param annotation the {@code @Receiver} mirror, or {@code null}
     * @return the enum constant's simple name, or {@code null} when the source did not write
     *         {@code nulls}; a caller wanting the effective policy substitutes
     *         {@code Nulls.UNCHECKED}
     */
    @Contract(pure = true)
    @Nullable
    private static String nullsOf(@Nullable final AnnotationMirror annotation) {
        return Anchors.enumOf(annotation, "nulls");
    }

    /**
     * Refuses a {@code nulls} policy on a declaration that has no receiver value to check.
     *
     * <p>{@code AW1315}. Reached from the two forms of {@code @Receiver} that name a type rather
     * than mark a parameter — the method form, which contributes a static method, and the field
     * form, which contributes a constant. Both are read off the type itself, so there is no
     * instance for a null check to look at. The remedy is to remove {@code nulls}, or to mark a
     * parameter {@code @Receiver} and contribute an instance method instead.
     *
     * <p>The policy on a parameter's {@code @Receiver} is the case this does not cover; there it is
     * honoured and reaches the manifest.
     *
     * @param named    the {@code @Receiver} mirror to examine
     * @param what     the member's qualified name, quoted in the message
     * @param anchor   where to report
     * @param reporter where to report
     * @return {@code true} when {@code AW1315} was reported and the caller should contribute
     *         nothing, {@code false} when no policy was written
     */
    private static boolean nullsWithoutAReceiver(@NotNull final AnnotationMirror named,
                                                 @NotNull final String what,
                                                 @NotNull final Anchor anchor,
                                                 @NotNull final MessagerReporter reporter) {
        if (nullsOf(named) == null) {
            return false;
        }
        reporter.report(Diagnostic.builder(DiagnosticCode.EXTENSION_NULLS_WITHOUT_RECEIVER)
                .message(what + " declares nulls, and names a type rather than a value")
                .detail("a static contribution and a constant are read off the type itself, so "
                        + "there is no receiver for a null check to look at")
                .remedy("remove nulls, or mark a parameter @Receiver to contribute an instance "
                        + "method instead")
                .build(), anchor);
        return true;
    }

    /** The receiver whose breadth is worth warning about, compared against the binary name. */
    private static final String OBJECT = "java.lang.Object";

    /**
     * Refuses instantiation; every entry point is static.
     *
     * @throws AssertionError always
     */
    private ExtensionChecks() {
        throw new AssertionError("no instances");
    }

    /**
     * Checks one extension holder and returns the entries it contributes.
     *
     * <p>The holder itself is checked first, and {@code AW1306} or {@code AW1307} ends the pass with
     * an empty list — a generic holder or one with a supertype contributes nothing at all.
     * {@code AW1300}, for a holder that is not {@code final}, is a warning and does not.
     *
     * <p>Methods are then examined in the order the compiler enumerates the holder's members, and
     * fields after all of them. Each contribution is keyed by receiver, name and descriptor, and a
     * key already taken is
     * refused: {@code AW1308} for a method, because two overloads erasing to one descriptor would
     * both rewrite the same call and erasure is all the call site has, with the remedy to rename
     * one. A field whose key is already taken is dropped without a diagnostic.
     *
     * <p>{@code require} and {@code scope} are read from the holder's {@code @Extension} once and
     * applied to every entry, so they are properties of the holder rather than of a member.
     *
     * @param holder   the {@code @Extension} class; must not be {@code null}
     * @param elements the element utilities used for binary names and inherited-member lookups;
     *                 must not be {@code null}
     * @param reporter where to report; must not be {@code null}
     * @return the entries to record, methods before fields; empty when the holder was refused or
     *         contributed nothing
     * @throws NullPointerException if any argument is {@code null}
     */
    @NotNull
    static List<WeaveManifest.Extension> of(@NotNull final TypeElement holder,
                                            @NotNull final Elements elements,
                                            @NotNull final MessagerReporter reporter) {
        Objects.requireNonNull(holder, "holder");
        Objects.requireNonNull(elements, "elements");
        Objects.requireNonNull(reporter, "reporter");

        if (!checkHolder(holder, reporter)) {
            return List.of();
        }

        final String binaryName = elements.getBinaryName(holder).toString();
        final AnnotationMirror declaration = Anchors.mirrorOf(holder, EXTENSION);
        final String require = Anchors.enumOf(declaration, "require");
        final String scope = Anchors.enumOf(declaration, "scope");
        final String declaredReceiver = receiverNamedBy(declaration);
        final List<WeaveManifest.Extension> contributed = new ArrayList<>();
        final Set<String> seen = new LinkedHashSet<>();

        for (final ExecutableElement method : ElementFilter.methodsIn(
                holder.getEnclosedElements())) {
            if (!method.getModifiers().contains(Modifier.PUBLIC)) {
                continue;
            }
            final WeaveManifest.Extension extension =
                    contribution(holder, binaryName, method, require, scope, declaredReceiver,
                            elements, reporter);
            if (extension == null) {
                continue;
            }
            if (!seen.add(extension.receiver() + '.' + extension.name() + extension.descriptor())) {
                // Two overloads that erase to the same descriptor. javac allows that no more than
                // this does, but a generic parameter can make two distinct signatures collide
                // after erasure, and erasure is all the call site has.
                reporter.report(Diagnostic.builder(DiagnosticCode.DUPLICATE_EXTENSION)
                        .message(extension.receiver() + '.' + extension.name()
                                + " is contributed twice by " + binaryName)
                        .detail("both would rewrite the same call, because they erase to the same "
                                + "descriptor " + extension.descriptor())
                        .remedy("rename one of them")
                        .build(), Anchor.at(method));
                continue;
            }
            contributed.add(extension);
        }

        for (final VariableElement field : ElementFilter.fieldsIn(holder.getEnclosedElements())) {
            final AnnotationMirror named = Anchors.mirrorOf(field, RECEIVER);
            if (named == null) {
                // A field with no @Receiver is the extension class's own state, which is ordinary
                // and none of this checker's business.
                continue;
            }
            final WeaveManifest.Extension constant =
                    constant(holder, binaryName, field, named, require, scope, elements, reporter);
            if (constant != null && seen.add(constant.receiver() + '.' + constant.name()
                    + constant.descriptor())) {
                contributed.add(constant);
            }
        }

        return contributed;
    }

    /**
     * Checks a field marked {@code @Receiver} and builds the constant it contributes.
     *
     * <p>The checks run in this order, each refusing outright.
     *
     * <ul>
     *   <li>{@code AW1315} for a {@code nulls} policy, there being no receiver value to check.
     *   <li>{@code AW1314} unless the field is {@code public static final}. A constant is read off
     *       the receiver as one of its own, and a non-final field would be shared writable state on
     *       a type whose author never gave it one; the remedy is to declare it
     *       {@code public static final}, or to drop the {@code @Receiver} and keep it as the
     *       holder's own field.
     *   <li>{@code AW1304} when {@code @Receiver} names no type or names one that is not declared.
     *       Its default is {@code void}, which cannot carry a constant; the remedy is to name a
     *       class or interface.
     *   <li>{@code AW1311} for a parameterised receiver: erasure is all the call site has, so the
     *       constant would land on every instantiation of that type. The remedy is to name the raw
     *       type.
     *   <li>{@code AW1305} when the receiver already has a field of that name. That includes an
     *       inherited one, because the resolution the constant would have to win is the same either
     *       way; the message names the type that declares it, and the remedy is to rename.
     * </ul>
     *
     * <p>{@code AW1312} follows if the receiver is {@link Object}, and is a warning: every type in
     * every module reading the extension would offer the constant. The entry is still contributed.
     *
     * <p>The recorded policy is always {@code Nulls.UNCHECKED}, a constant having no receiver value
     * for any other policy to describe.
     *
     * @param holder     the extension class
     * @param binaryName the holder's binary name, quoted in the messages
     * @param field      the annotated field
     * @param named      the {@code @Receiver} mirror on it
     * @param require    the holder's requirement policy as a constant name, or {@code null} for the
     *                   default
     * @param scope      the holder's scope as a constant name, or {@code null} for the default
     * @param elements   the element utilities used for binary names and the inherited-field lookup
     * @param reporter   where to report
     * @return the entry to contribute, or {@code null} when the field was refused
     */
    @Nullable
    private static WeaveManifest.Extension constant(@NotNull final TypeElement holder,
                                                    @NotNull final String binaryName,
                                                    @NotNull final VariableElement field,
                                                    @NotNull final AnnotationMirror named,
                                                    @Nullable final String require,
                                                    @Nullable final String scope,
                                                    @NotNull final Elements elements,
                                                    @NotNull final MessagerReporter reporter) {
        final Anchor anchor = Anchor.at(field, named);
        if (nullsWithoutAReceiver(named, binaryName + '.' + field.getSimpleName(), anchor,
                reporter)) {
            return null;
        }
        final Set<Modifier> modifiers = field.getModifiers();
        if (!modifiers.contains(Modifier.PUBLIC)
                || !modifiers.contains(Modifier.STATIC)
                || !modifiers.contains(Modifier.FINAL)) {
            reporter.report(Diagnostic.builder(DiagnosticCode.EXTENSION_CONSTANT_NOT_FINAL)
                    .message(binaryName + '.' + field.getSimpleName()
                            + " is not public static final")
                    .detail("a constant is read off the receiver as one of its own, and a field that "
                            + "is not final would be shared writable state on a type its own author "
                            + "never gave one")
                    .remedy("declare it public static final, or drop the @Receiver and keep it as "
                            + "the extension class's own field")
                    .build(), anchor);
            return null;
        }

        final TypeMirror receiver = receiverNamedOn(named);
        if (receiver == null || receiver.getKind() != TypeKind.DECLARED) {
            reporter.report(Diagnostic.builder(DiagnosticCode.EXTENSION_RECEIVER_NOT_A_TYPE)
                    .message("the @Receiver of " + binaryName + '.' + field.getSimpleName()
                            + " is " + (receiver == null ? "void" : receiver)
                            + ", which cannot carry a constant")
                    .detail("a @Receiver on a field names the type that gains the constant, and its "
                            + "default — void — is not a type that can have one")
                    .remedy("name a class or interface, as in @Receiver(BigDecimal.class)")
                    .build(), anchor);
            return null;
        }

        final DeclaredType declared = (DeclaredType) receiver;
        if (!declared.getTypeArguments().isEmpty()) {
            reporter.report(Diagnostic.builder(DiagnosticCode.EXTENSION_RECEIVER_IS_PARAMETERISED)
                    .message("the @Receiver of " + binaryName + '.' + field.getSimpleName()
                            + " is " + receiver)
                    .detail("erasure is all the call site has, so this would be contributed to "
                            + "every " + nameOf(declared) + " in the program, whatever its type "
                            + "argument")
                    .remedy("name the raw type")
                    .build(), anchor);
            return null;
        }

        final TypeElement receiverElement = (TypeElement) declared.asElement();
        final String receiverName = elements.getBinaryName(receiverElement).toString();
        final String descriptor = SourceMembers.typeDescriptorOf(field.asType());

        final VariableElement collides =
                fieldOf(receiverElement, field.getSimpleName().toString(), elements);
        if (collides != null) {
            reporter.report(Diagnostic.builder(DiagnosticCode.EXTENSION_COLLIDES_WITH_MEMBER)
                    .message(receiverName + '.' + field.getSimpleName()
                            + " already exists, declared by " + collides.getEnclosingElement())
                    .detail("javac resolves the read to that field, so this constant would never be "
                            + "reached")
                    .remedy("rename the constant")
                    .build(), anchor);
            return null;
        }

        if (OBJECT.contentEquals(receiverName)) {
            reporter.report(Diagnostic.builder(DiagnosticCode.EXTENSION_RECEIVER_IS_OBJECT)
                    .message(binaryName + '.' + field.getSimpleName()
                            + " is contributed to java.lang.Object")
                    .detail("every type in every module that reads this extension will offer "
                            + field.getSimpleName() + " as a constant of its own")
                    .remedy("name the narrowest type the constant is meaningful on")
                    .build(), anchor);
        }

        return new WeaveManifest.Extension(binaryName, receiverName,
                field.getSimpleName().toString(), descriptor,
                WeaveManifest.Extension.Kind.CONSTANT,
                require == null ? Require.REQUIRED : Require.valueOf(require), Nulls.UNCHECKED,
                scope == null ? Scope.PUBLIC : Scope.valueOf(scope));
    }

    /**
     * Reads the type a {@code @Receiver} names.
     *
     * <p>The mirror form, not the {@code Class} object: an annotation element of class type cannot
     * be read reflectively during processing, and the type it names need not be loadable by the
     * processor at all.
     *
     * @param named the {@code @Receiver} mirror
     * @return the named type, or {@code null} when the source wrote no {@code value} — leaving it at
     *         {@code void.class}, which callers report as a receiver that cannot carry anything
     */
    @Contract(pure = true)
    @Nullable
    private static TypeMirror receiverNamedOn(@NotNull final AnnotationMirror named) {
        final AnnotationValue value = Anchors.valueOf(named, "value");
        return value != null && value.getValue() instanceof final TypeMirror written
                ? written
                : null;
    }

    /**
     * Finds a field of that name anywhere in the receiver's hierarchy.
     *
     * <p>Inherited members are included, unlike the lookups a weave's members are checked against.
     * The question here is what a read of {@code Receiver.NAME} would resolve to at a call site, and
     * that resolution does not care where the field was declared.
     *
     * @param receiver the receiver type
     * @param name     the field name to match exactly
     * @param elements the element utilities supplying the full member list
     * @return the field, or {@code null} when nothing of that name is visible on the receiver
     */
    @Contract(pure = true)
    @Nullable
    private static VariableElement fieldOf(@NotNull final TypeElement receiver,
                                           @NotNull final String name,
                                           @NotNull final Elements elements) {
        for (final VariableElement candidate : ElementFilter.fieldsIn(
                elements.getAllMembers(receiver))) {
            if (candidate.getSimpleName().contentEquals(name)) {
                return candidate;
            }
        }
        return null;
    }


    /**
     * Checks the extension class itself.
     *
     * <p>{@code AW1300} when it is not {@code final}: an extension class is never instantiated and
     * never subclassed, so a non-final one invites a use it does not have. A warning, and the
     * holder goes on to contribute.
     *
     * <p>{@code AW1306} when it declares type parameters, which have nothing to bind to at a call
     * site that resolves contributions by descriptor. {@code AW1307} when it has a superclass other
     * than {@link Object} or implements anything, nothing about the holder participating at the
     * call site. Either ends the pass, and the generic test runs first, so a generic holder with a
     * supertype reports only {@code AW1306}.
     *
     * @param holder   the extension class
     * @param reporter where to report
     * @return {@code true} when the holder's members may be examined, {@code false} when it was
     *         refused outright
     */
    private static boolean checkHolder(@NotNull final TypeElement holder,
                                       @NotNull final MessagerReporter reporter) {
        if (!holder.getModifiers().contains(Modifier.FINAL)) {
            reporter.report(Diagnostic.builder(DiagnosticCode.EXTENSION_NOT_FINAL)
                    .message(holder.getQualifiedName() + " is not final")
                    .detail("an extension class is never instantiated and never subclassed, so a "
                            + "non-final one invites a use it does not have")
                    .remedy("declare it final")
                    .build(), Anchor.at(holder));
        }

        if (!holder.getTypeParameters().isEmpty()) {
            reporter.report(Diagnostic.builder(DiagnosticCode.EXTENSION_IS_GENERIC)
                    .message(holder.getQualifiedName() + " declares type parameters")
                    .detail("contributed methods are looked up by descriptor, and a type parameter "
                            + "on the holder has nothing to bind to at the call site")
                    .remedy("remove the type parameters")
                    .build(), Anchor.at(holder));
            return false;
        }

        final TypeMirror superclass = holder.getSuperclass();
        final boolean extendsObject = superclass.getKind() == TypeKind.NONE
                || "java.lang.Object".contentEquals(nameOf(superclass));
        if (!extendsObject || !holder.getInterfaces().isEmpty()) {
            reporter.report(Diagnostic.builder(DiagnosticCode.EXTENSION_HAS_SUPERTYPE)
                    .message(holder.getQualifiedName() + " has a superclass or an interface")
                    .detail("nothing about the holder participates at the call site, so a "
                            + "supertype states a relationship the framework cannot honour")
                    .remedy("make it extend Object and implement nothing")
                    .build(), Anchor.at(holder));
            return false;
        }
        return true;
    }

    /**
     * Checks one public method of the holder and builds the entry it contributes.
     *
     * <p>Two checks apply to any shape of contribution. {@code AW1301} refuses a method that is not
     * {@code static}, the receiver being passed as a parameter and the method having no instance of
     * its own; the remedy offers making it {@code private} if it is a helper. {@code AW1310} refuses
     * a method with type parameters of its own, because the stub the compiler resolves against would
     * carry a type variable with nothing to bind it and inference at the call site would then differ
     * from what the declaration says.
     *
     * <p>Which of the three shapes the method is comes next, decided by where {@code @Receiver} is
     * written.
     *
     * <ul>
     *   <li>On the method <em>and</em> on a parameter: {@code AW1313}. The two forms mean different
     *       things — the method form contributes a static method to a type, the parameter form an
     *       instance method to its values — so a declaration asking for both says which of the two
     *       it is nowhere. Keep one.
     *   <li>On the method alone: a static contribution, checked by
     *       {@link #staticContribution(String, ExecutableElement, AnnotationMirror, String, String,
     *       Elements, MessagerReporter)}.
     *   <li>On a parameter, or on none while the holder names a class-level receiver: an instance
     *       contribution, and the receiver is parameter zero.
     * </ul>
     *
     * <p>{@code AW1302} refuses a method that marks no {@code @Receiver} where the holder names none
     * either; the remedy lists all three ways out, including making the method {@code private}.
     * {@code AW1316} refuses one that relies on a class-level receiver but does not take that type
     * first, a class-level receiver making parameter zero the receiver by position; a method taking
     * no parameters at all is refused the same way. Nothing is inferred from the type, so a method
     * taking something else is refused rather than quietly left out — left out being
     * indistinguishable from spelled wrong at the call site that then fails to compile.
     *
     * <p>{@code AW1303} refuses {@code @Receiver} on a later parameter, the rewrite passing the
     * receiver straight through as argument zero where the JVM has already put it. {@code AW1304}
     * refuses a receiver that is not a declared type, a primitive, an array and a type variable
     * having no class file to resolve a contributed method against. {@code AW1311} refuses a
     * parameterised one.
     *
     * <p>Only an instance contribution honours {@code nulls}, read from the parameter's own
     * {@code @Receiver} where it has one. A method reaching this path through a class-level receiver
     * has no annotation to read one from and takes the default.
     *
     * @param holder           the extension class
     * @param binaryName       the holder's binary name, quoted in the messages
     * @param method           the public method to check
     * @param require          the holder's requirement policy as a constant name, or {@code null}
     * @param scope            the holder's scope as a constant name, or {@code null}
     * @param declaredReceiver the receiver the holder names for the whole class, or {@code null}
     * @param elements         the element utilities used for binary names and member lookups
     * @param reporter         where to report
     * @return the entry to contribute, or {@code null} when the method was refused
     */
    @Nullable
    private static WeaveManifest.Extension contribution(@NotNull final TypeElement holder,
                                                        @NotNull final String binaryName,
                                                        @NotNull final ExecutableElement method,
                                                        @Nullable final String require,
                                                        @Nullable final String scope,
                                                        @Nullable final String declaredReceiver,
                                                        @NotNull final Elements elements,
                                                        @NotNull final MessagerReporter reporter) {
        if (!method.getModifiers().contains(Modifier.STATIC)) {
            reporter.report(Diagnostic.builder(DiagnosticCode.EXTENSION_METHOD_NOT_STATIC)
                    .message(binaryName + '.' + method.getSimpleName() + " is not static")
                    .detail("the receiver is passed as a parameter, so a contributed method has no "
                            + "instance of its own to be called on")
                    .remedy("declare it static, or make it private if it is a helper")
                    .build(), Anchor.at(method));
            return null;
        }

        if (!method.getTypeParameters().isEmpty()) {
            reporter.report(Diagnostic.builder(DiagnosticCode.EXTENSION_METHOD_IS_GENERIC)
                    .message(binaryName + '.' + method.getSimpleName()
                            + " declares its own type parameters")
                    .detail("the stub the compiler resolves against would carry a type variable "
                            + "with nothing to bind it, so inference at the call site would differ "
                            + "from what this declaration says")
                    .remedy("use the erased type, or move the method to an ordinary utility class")
                    .build(), Anchor.at(method));
            return null;
        }

        final List<? extends VariableElement> parameters = method.getParameters();
        final int receiverAt = receiverIndex(parameters);
        final AnnotationMirror onMethod = Anchors.mirrorOf(method, RECEIVER);

        if (onMethod != null && receiverAt >= 0) {
            reporter.report(Diagnostic.builder(DiagnosticCode.EXTENSION_RECEIVER_DECLARED_TWICE)
                    .message(binaryName + '.' + method.getSimpleName()
                            + " names a receiver on the method and on parameter " + receiverAt)
                    .detail("the two forms mean different things — the method form contributes a "
                            + "static method to a type, the parameter form contributes an instance "
                            + "method to its values — and a declaration that asks for both says "
                            + "which of the two it is nowhere")
                    .remedy("keep @Receiver on the method for a static extension, or on the first "
                            + "parameter for an instance one")
                    .build(), Anchor.at(method));
            return null;
        }
        if (onMethod != null) {
            return staticContribution(binaryName, method, onMethod, require, scope, elements,
                    reporter);
        }

        if (receiverAt < 0) {
            if (declaredReceiver == null) {
                reporter.report(Diagnostic.builder(DiagnosticCode.EXTENSION_RECEIVER_MISSING)
                        .message(binaryName + '.' + method.getSimpleName() + " marks no @Receiver")
                        .detail("every public method of an extension class is contributed to a type, "
                                + "and @Receiver is what names that type — on the first parameter for an "
                                + "instance method, on the method itself for a static one")
                        .remedy("annotate the first parameter @Receiver, name one for the whole class "
                                + "with @Extension(Type.class), or make the method private")
                        .build(), Anchor.at(method));
                return null;
            }
            // A class-level receiver: parameter zero is the receiver by position, and must be
            // declared as the named type. Nothing is inferred from the type — a method that takes
            // something else is refused rather than quietly left out, because "left out" is
            // indistinguishable from "spelled wrong" at the call site that then fails to compile.
            if (parameters.isEmpty()
                    || !declaredReceiver.contentEquals(nameOf(parameters.get(0).asType()))) {
                reporter.report(Diagnostic.builder(DiagnosticCode.EXTENSION_RECEIVER_NOT_THE_CLASSES)
                        .message(binaryName + '.' + method.getSimpleName() + " takes "
                                + (parameters.isEmpty()
                                        ? "no parameters"
                                        : parameters.get(0).asType() + " first")
                                + ", and this class contributes to " + declaredReceiver)
                        .detail("a class-level receiver makes parameter zero the receiver by "
                                + "position, so every contributed method must take that type first")
                        .remedy("take " + declaredReceiver + " as the first parameter, make the "
                                + "method private, or name its own receiver")
                        .build(), Anchor.at(method));
                return null;
            }
        }
        if (receiverAt > 0) {
            reporter.report(Diagnostic.builder(DiagnosticCode.EXTENSION_RECEIVER_NOT_FIRST)
                    .message(binaryName + '.' + method.getSimpleName()
                            + " marks parameter " + receiverAt + " as the @Receiver")
                    .detail("the rewrite passes the receiver straight through as argument zero, "
                            + "which is where the JVM has already put it for the virtual call")
                    .remedy("move the @Receiver parameter to the front")
                    .build(), Anchor.at(parameters.get(receiverAt)));
            return null;
        }

        final VariableElement receiver = parameters.get(0);
        final TypeMirror type = receiver.asType();
        if (type.getKind() != TypeKind.DECLARED) {
            reporter.report(Diagnostic.builder(DiagnosticCode.EXTENSION_RECEIVER_NOT_A_TYPE)
                    .message("the @Receiver of " + binaryName + '.' + method.getSimpleName()
                            + " is " + type + ", which cannot carry a method")
                    .detail("a primitive, an array and a type variable have no class file for the "
                            + "compiler to resolve a contributed method against")
                    .remedy("use a class or interface type, boxing the value if that is what is "
                            + "wanted")
                    .build(), Anchor.at(receiver));
            return null;
        }

        final DeclaredType declared = (DeclaredType) type;
        if (!declared.getTypeArguments().isEmpty()) {
            reporter.report(Diagnostic.builder(DiagnosticCode.EXTENSION_RECEIVER_IS_PARAMETERISED)
                    .message("the @Receiver of " + binaryName + '.' + method.getSimpleName()
                            + " is " + type)
                    .detail("erasure is all the call site has, so this would be contributed to "
                            + "every " + nameOf(declared) + " in the program, whatever its type "
                            + "argument")
                    .remedy("use the raw type and check inside the method, or narrow the receiver "
                            + "to a type that is not parameterised")
                    .build(), Anchor.at(receiver));
            return null;
        }

        return contributed(binaryName, method, (TypeElement) declared.asElement(),
                callSiteDescriptorOf(method, 1), WeaveManifest.Extension.Kind.INSTANCE,
                require, scope, nullsOf(Anchors.mirrorOf(receiver, RECEIVER)),
                Anchor.at(receiver), elements, reporter);
    }

    /**
     * Checks a method whose {@code @Receiver} is on the method itself and builds its entry.
     *
     * <p>This is the form that contributes a static method to a type, so no parameter is the
     * receiver and none is dropped from the descriptor. {@code AW1315} refuses a {@code nulls}
     * policy, {@code AW1304} a {@code @Receiver} that names no type or one that is not declared —
     * its default, {@code void}, being a type that cannot carry a method — and {@code AW1311} a
     * parameterised one.
     *
     * @param binaryName the holder's binary name, quoted in the messages
     * @param method     the annotated method
     * @param onMethod   the {@code @Receiver} mirror on it
     * @param require    the holder's requirement policy as a constant name, or {@code null}
     * @param scope      the holder's scope as a constant name, or {@code null}
     * @param elements   the element utilities used for binary names and the inherited-method lookup
     * @param reporter   where to report
     * @return the entry to contribute, or {@code null} when the method was refused
     */
    @Nullable
    private static WeaveManifest.Extension staticContribution(
            @NotNull final String binaryName,
            @NotNull final ExecutableElement method,
            @NotNull final AnnotationMirror onMethod,
            @Nullable final String require,
            @Nullable final String scope,
            @NotNull final Elements elements,
            @NotNull final MessagerReporter reporter) {
        final Anchor anchor = Anchor.at(method, onMethod);
        if (nullsWithoutAReceiver(onMethod, binaryName + '.' + method.getSimpleName(), anchor,
                reporter)) {
            return null;
        }
        final AnnotationValue value = Anchors.valueOf(onMethod, "value");
        final TypeMirror type = value == null || !(value.getValue() instanceof TypeMirror written)
                ? null
                : written;

        if (type == null || type.getKind() != TypeKind.DECLARED) {
            reporter.report(Diagnostic.builder(DiagnosticCode.EXTENSION_RECEIVER_NOT_A_TYPE)
                    .message("the @Receiver of " + binaryName + '.' + method.getSimpleName()
                            + " is " + (type == null ? "void" : type)
                            + ", which cannot carry a method")
                    .detail("a @Receiver on a method names the type that gains a static method, and "
                            + "its default — void — is not a type that can have one")
                    .remedy("name a class or interface, as in @Receiver(BigDecimal.class)")
                    .build(), anchor);
            return null;
        }

        final DeclaredType declared = (DeclaredType) type;
        if (!declared.getTypeArguments().isEmpty()) {
            reporter.report(Diagnostic.builder(DiagnosticCode.EXTENSION_RECEIVER_IS_PARAMETERISED)
                    .message("the @Receiver of " + binaryName + '.' + method.getSimpleName()
                            + " is " + type)
                    .detail("erasure is all the call site has, so this would be contributed to "
                            + "every " + nameOf(declared) + " in the program, whatever its type "
                            + "argument")
                    .remedy("name the raw type")
                    .build(), anchor);
            return null;
        }

        return contributed(binaryName, method, (TypeElement) declared.asElement(),
                callSiteDescriptorOf(method, 0), WeaveManifest.Extension.Kind.STATIC, require, scope,
                null, anchor, elements, reporter);
    }

    /**
     * Checks a resolved contribution against the receiver and builds its manifest entry.
     *
     * <p>The last two checks both shapes of method share. {@code AW1305} refuses a contribution the
     * receiver already answers to under that name and descriptor: javac resolves the call to the
     * real method, so the extension would never be reached. Inherited members count, not only
     * declared ones — {@link String} declares no {@code getClass()} of its own, yet an extension
     * contributed under that name and descriptor to {@link String} is exactly as dead as one named
     * {@code length}, because {@link Elements#getAllMembers(TypeElement)} is what this check reads
     * — and the remedy is to rename the extension, or to use {@code @Weave} with {@code @Inject} or
     * {@code @Redirect} to change what the existing method does.
     *
     * <p>{@code AW1312} warns about a receiver of {@link Object}, and does not refuse: every
     * expression in every module reading the extension would offer the method, including
     * expressions whose type has nothing to do with what it means. Contributing to {@link Object}
     * is occasionally meant, and nothing here can tell that from somebody having written the widest
     * type that happened to compile, so the entry is contributed either way.
     *
     * <p>The three policies are resolved to their defaults here, an element the source did not write
     * being invisible in the mirror: {@code Require.REQUIRED}, {@code Nulls.UNCHECKED} and
     * {@code Scope.PUBLIC}.
     *
     * @param binaryName the holder's binary name, quoted in the messages
     * @param method     the contributed method
     * @param receiver   the type that gains it
     * @param descriptor the call-site descriptor, the receiver already dropped where it was a
     *                   parameter
     * @param kind       whether the contribution is static or an instance method
     * @param require    the holder's requirement policy as a constant name, or {@code null}
     * @param scope      the holder's scope as a constant name, or {@code null}
     * @param nulls      the receiver's null policy as a constant name, or {@code null}
     * @param anchor     where to report {@code AW1312}; a collision is reported on the method
     *                   itself whatever this says
     * @param elements   the element utilities used for binary names and the inherited-method lookup
     * @param reporter   where to report
     * @return the entry to contribute, or {@code null} when the method collided
     */
    @Nullable
    private static WeaveManifest.Extension contributed(
            @NotNull final String binaryName,
            @NotNull final ExecutableElement method,
            @NotNull final TypeElement receiver,
            @NotNull final String descriptor,
            @NotNull final WeaveManifest.Extension.Kind kind,
            @Nullable final String require,
            @Nullable final String scope,
            @Nullable final String nulls,
            @NotNull final Anchor anchor,
            @NotNull final Elements elements,
            @NotNull final MessagerReporter reporter) {
        final String receiverName = elements.getBinaryName(receiver).toString();

        final ExecutableElement collides =
                memberOf(receiver, method.getSimpleName().toString(), descriptor, elements);
        if (collides != null) {
            reporter.report(Diagnostic.builder(DiagnosticCode.EXTENSION_COLLIDES_WITH_MEMBER)
                    .message(receiverName + '.' + method.getSimpleName() + descriptor
                            + " already exists, declared by "
                            + collides.getEnclosingElement())
                    .detail("javac resolves the call to that method, so this extension would never "
                            + "be reached")
                    .remedy("rename the extension, or use @Weave with @Inject or @Redirect to "
                            + "change what the existing method does")
                    .build(), Anchor.at(method));
            return null;
        }

        if (OBJECT.contentEquals(receiverName)) {
            // Not refused. Contributing to Object is occasionally what somebody means, and the
            // framework has no way to tell that from the far more common case of somebody having
            // written the widest type that happened to compile.
            reporter.report(Diagnostic.builder(DiagnosticCode.EXTENSION_RECEIVER_IS_OBJECT)
                    .message(binaryName + '.' + method.getSimpleName()
                            + " is contributed to java.lang.Object")
                    .detail("every expression in every module that reads this extension will offer "
                            + method.getSimpleName() + ", including expressions whose type has "
                            + "nothing to do with what it means")
                    .remedy("name the narrowest type the method is meaningful on")
                    .build(), anchor);
        }

        return new WeaveManifest.Extension(binaryName, receiverName,
                method.getSimpleName().toString(), descriptor, kind,
                require == null ? Require.REQUIRED : Require.valueOf(require),
                nulls == null ? Nulls.UNCHECKED : Nulls.valueOf(nulls),
                scope == null ? Scope.PUBLIC : Scope.valueOf(scope));
    }

    /**
     * Finds the parameter marked {@code @Receiver}.
     *
     * @param parameters the method's parameters
     * @return the index of the first parameter carrying the annotation, or {@code -1} when none
     *         does; a positive index is what {@code AW1303} refuses
     */
    @Contract(pure = true)
    private static int receiverIndex(@NotNull final List<? extends VariableElement> parameters) {
        for (int i = 0; i < parameters.size(); i++) {
            if (Anchors.mirrorOf(parameters.get(i), RECEIVER) != null) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Renders the descriptor a call site would carry, rather than the implementation's own.
     *
     * <p>The whole rewrite is a lookup by owner, name and descriptor against what the class file
     * holds, so this is the form the manifest has to record. For an instance contribution the
     * receiver is skipped, because at the call site it is the receiver of the invocation and not an
     * argument; for a static contribution nothing is skipped.
     *
     * @param method the contributed method
     * @param from   the first parameter index to include: {@code 1} for an instance contribution,
     *               {@code 0} for a static one
     * @return the method descriptor of the parameters from {@code from} onwards and the return type
     */
    @Contract(pure = true)
    @NotNull
    private static String callSiteDescriptorOf(@NotNull final ExecutableElement method,
                                               final int from) {
        final StringBuilder out = new StringBuilder("(");
        final List<? extends VariableElement> parameters = method.getParameters();
        for (int i = from; i < parameters.size(); i++) {
            out.append(SourceMembers.typeDescriptorOf(parameters.get(i).asType()));
        }
        return out.append(')')
                .append(SourceMembers.typeDescriptorOf(method.getReturnType()))
                .toString();
    }

    /**
     * Finds a method of that name and descriptor anywhere in the receiver's hierarchy.
     *
     * <p>Inherited methods are included: a collision with {@code Object.hashCode} makes an
     * extension exactly as unreachable as a collision with a method the receiver declares itself.
     * The descriptor compared is the receiver method's own, which is the form the call site
     * resolves against.
     *
     * @param receiver   the receiver type
     * @param name       the method name to match exactly
     * @param descriptor the call-site descriptor to match exactly
     * @param elements   the element utilities supplying the full member list
     * @return the colliding method, or {@code null} when nothing on the receiver answers to that
     *         name and descriptor
     */
    @Contract(pure = true)
    @Nullable
    private static ExecutableElement memberOf(@NotNull final TypeElement receiver,
                                              @NotNull final String name,
                                              @NotNull final String descriptor,
                                              @NotNull final Elements elements) {
        for (final ExecutableElement candidate : ElementFilter.methodsIn(
                elements.getAllMembers(receiver))) {
            if (candidate.getSimpleName().contentEquals(name)
                    && SourceMembers.descriptorOf(candidate).equals(descriptor)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Names a type for comparison and for quoting in a message.
     *
     * <p>The canonical name for a declared type, dropping any type arguments; whatever the mirror
     * renders for anything else. A {@link CharSequence} rather than a {@link String} because a
     * declared type's name arrives as a {@link javax.lang.model.element.Name}, which callers
     * comparing with {@code contentEquals} need not convert.
     *
     * @param type the type to name
     * @return the fully qualified name of a declared type, or the mirror's own rendering
     */
    @Contract(pure = true)
    @NotNull
    private static CharSequence nameOf(@NotNull final TypeMirror type) {
        if (type instanceof final DeclaredType declared
                && declared.asElement() instanceof final TypeElement element) {
            return element.getQualifiedName();
        }
        return type.toString();
    }
}
