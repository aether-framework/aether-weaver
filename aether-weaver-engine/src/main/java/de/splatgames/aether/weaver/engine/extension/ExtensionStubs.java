package de.splatgames.aether.weaver.engine.extension;

import de.splatgames.aether.weaver.api.manifest.WeaveManifest;
import de.splatgames.aether.weaver.api.spi.ClassSource;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.classfile.Annotation;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.MethodBuilder;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodSignature;
import java.lang.classfile.Signature;
import java.lang.classfile.attribute.ConstantValueAttribute;
import java.lang.classfile.attribute.DeprecatedAttribute;
import java.lang.classfile.attribute.ExceptionsAttribute;
import java.lang.classfile.attribute.RuntimeInvisibleAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeInvisibleParameterAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleParameterAnnotationsAttribute;
import java.lang.classfile.attribute.SignatureAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Adds a declaration for each contributed extension to the class it extends, so that {@code javac}
 * will compile a call to it.
 *
 * <p>A patched receiver is a compile-time artefact and nothing else. The bodies throw
 * {@link UnsupportedOperationException} with a message saying so, because a stub that reached a
 * runtime classpath would shadow the real class and every extension call would fail there instead
 * of being rewritten.
 *
 * <p>The declaration is made to look like the implementation from the caller's side and no further.
 * The signature, the checked exceptions, the deprecation and the annotations are carried across, and
 * so is the {@code varargs} modifier, since it is the one flag that changes what a caller may write;
 * the rest of the implementation's modifiers, its receiver parameter and this framework's own
 * annotations are not.
 *
 * <p>Nothing is added for a member the receiver already declares, so patching an already patched
 * class returns {@code null} rather than declaring it twice.
 *
 * <p>Stateless: nothing here holds state across calls, though {@link #constant} and
 * {@link #declaration} write into the {@link ClassBuilder} and {@link MethodBuilder} their caller
 * passes to them.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class ExtensionStubs {

    /** What a stub body throws, naming the holder that should have been called instead. */
    private static final ClassDesc UNSUPPORTED =
            ClassDesc.of("java.lang.UnsupportedOperationException");

    /** The constructor that carries a message. */
    private static final MethodTypeDesc UNSUPPORTED_INIT =
            MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String);

    /**
     * The descriptor prefix of this framework's own annotations, which a stub does not carry.
     *
     * <p>They describe the declaration in the holder, not the member the receiver appears to gain,
     * and copying them would make the stub look like a second contribution of the same extension.
     */
    private static final String OWN_ANNOTATIONS = "Lde/splatgames/aether/weaver/api/";

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private ExtensionStubs() {
        throw new AssertionError("no instances");
    }

    /**
     * Adds a stub to a receiver class for each of the given extensions it does not already declare.
     *
     * <p>A method stub throws; a constant stub is a {@code public static final} field carrying the
     * implementation's {@code ConstantValue} when one can be read, which is what lets it be used
     * where a constant is required. Where the holder cannot be found on {@code holders} the stub is
     * still written, with the name and descriptor the manifest gives and nothing else — the call
     * site compiles, and only the extras are lost.
     *
     * @param receiver   the class to patch; must not be {@code null}
     * @param extensions the extensions contributed to it; must not be {@code null}
     * @param holders    where the implementing classes are read from; must not be {@code null}
     * @return the patched class, or {@code null} when {@code extensions} is empty or the receiver
     *         already declares all of them
     * @throws NullPointerException if any argument is {@code null}
     */
    @Contract(pure = true)
    public static byte @Nullable [] patch(final byte @NotNull [] receiver,
                                          @NotNull final List<WeaveManifest.Extension> extensions,
                                          @NotNull final ClassSource holders) {
        Objects.requireNonNull(receiver, "receiver");
        Objects.requireNonNull(extensions, "extensions");
        Objects.requireNonNull(holders, "holders");
        if (extensions.isEmpty()) {
            return null;
        }

        final ClassFile classFile = ClassFile.of();
        final ClassModel model = classFile.parse(receiver);

        final List<WeaveManifest.Extension> missing = new ArrayList<>();
        for (final WeaveManifest.Extension extension : extensions) {
            if (!alreadyDeclared(model, extension)) {
                missing.add(extension);
            }
        }
        if (missing.isEmpty()) {
            return null;
        }

        return classFile.transformClass(model, ClassTransform.endHandler(builder -> {
            for (final WeaveManifest.Extension extension : missing) {
                if (extension.kind() == WeaveManifest.Extension.Kind.CONSTANT) {
                    constant(builder, extension, holders);
                    continue;
                }
                final MethodTypeDesc descriptor =
                        MethodTypeDesc.ofDescriptor(extension.descriptor());
                final String note = extension.receiver() + '.' + extension.name()
                        + " is an aether-weaver extension implemented by " + extension.className()
                        + "; this stub is a compile-time artefact and must not be on a runtime "
                        + "classpath";
                final MethodModel implementation = implementationOf(extension, holders);
                builder.withMethod(extension.name(), descriptor, flagsFor(extension, implementation),
                        method -> {
                            declaration(method, extension, implementation);
                            method.withCode(code -> code
                                    .new_(UNSUPPORTED)
                                    .dup()
                                    .ldc(note)
                                    .invokespecial(UNSUPPORTED, ConstantDescs.INIT_NAME,
                                            UNSUPPORTED_INIT)
                                    .athrow());
                        });
            }
        }));
    }

    /**
     * Adds one contributed constant as a field of the receiver.
     *
     * <p>The flags are fixed rather than copied: a contributed constant is public, static and final
     * on the receiver whatever the holder declared. Of the annotations only the runtime-visible
     * ones are carried, where a method stub is given both kinds.
     *
     * @param builder   the class being built
     * @param extension the constant to add
     * @param holders   where the implementing class is read from
     */
    private static void constant(@NotNull final ClassBuilder builder,
                                 @NotNull final WeaveManifest.Extension extension,
                                 @NotNull final ClassSource holders) {
        final FieldModel implementation = constantOf(extension, holders);
        builder.withField(extension.name(), ClassDesc.ofDescriptor(extension.descriptor()),
                field -> {
                    field.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC
                            | ClassFile.ACC_FINAL);
                    if (implementation == null) {
                        return;
                    }
                    implementation.findAttribute(Attributes.constantValue())
                            .ifPresent(value -> field.with(
                                    ConstantValueAttribute.of(value.constant().constantValue())));
                    implementation.findAttribute(Attributes.signature())
                            .ifPresent(signature -> field.with(SignatureAttribute.of(
                                    signature.asTypeSignature())));
                    implementation.findAttribute(Attributes.deprecated())
                            .ifPresent(deprecated -> field.with(DeprecatedAttribute.of()));
                    final List<Annotation> visible = declaredOn(
                            implementation.findAttribute(Attributes.runtimeVisibleAnnotations())
                                    .map(RuntimeVisibleAnnotationsAttribute::annotations)
                                    .orElse(List.of()));
                    if (!visible.isEmpty()) {
                        field.with(RuntimeVisibleAnnotationsAttribute.of(visible));
                    }
                });
    }

    /**
     * Finds the field in the holder that a contributed constant is declared by.
     *
     * @param extension the constant
     * @param holders   where the implementing class is read from
     * @return the field, or {@code null} when the holder or the field cannot be found
     */
    @Contract(pure = true)
    @Nullable
    private static FieldModel constantOf(@NotNull final WeaveManifest.Extension extension,
                                         @NotNull final ClassSource holders) {
        final ClassModel holder = holderOf(extension, holders);
        if (holder == null) {
            return null;
        }
        for (final FieldModel field : holder.fields()) {
            if (field.fieldName().equalsString(extension.name())
                    && field.fieldType().equalsString(extension.descriptor())) {
                return field;
            }
        }
        return null;
    }

    /**
     * Decides the stub's access flags.
     *
     * <p>Public always, static for a static extension, and varargs when the implementation is.
     * Varargs is the only flag of the implementation's that changes what a caller may write; the
     * rest describe a body the stub does not have.
     *
     * @param extension      the extension
     * @param implementation the holder's method, or {@code null} when it could not be read
     * @return the flags
     */
    @Contract(pure = true)
    private static int flagsFor(@NotNull final WeaveManifest.Extension extension,
                                @Nullable final MethodModel implementation) {
        int flags = ClassFile.ACC_PUBLIC;
        if (extension.kind() == WeaveManifest.Extension.Kind.STATIC) {
            flags |= ClassFile.ACC_STATIC;
        }
        if (implementation == null) {
            return flags;
        }
        // The only flag that changes what a caller may write. ACC_FINAL, ACC_SYNCHRONIZED and the
        // rest describe the implementation, and a stub is not the implementation. Deprecation is
        // not here because a class file does not express it as a flag — it is an attribute and an
        // annotation, and both are copied by declaration().
        return flags | (implementation.flags().flagsMask() & ClassFile.ACC_VARARGS);
    }

    /**
     * Copies from the implementation everything a caller of the stub can observe.
     *
     * <p>The generic signature, the {@code throws} clause, the deprecation and both annotation
     * attributes, with the receiver's own parameter dropped for an instance extension so that the
     * annotations still line up with the parameters the stub declares.
     *
     * <p>Nothing is copied when the holder could not be read, which leaves the stub with the raw
     * name and descriptor. It compiles; it just says less.
     *
     * @param method         the method being built
     * @param extension      the extension
     * @param implementation the holder's method, or {@code null} when it could not be read
     */
    private static void declaration(@NotNull final MethodBuilder method,
                                    @NotNull final WeaveManifest.Extension extension,
                                    @Nullable final MethodModel implementation) {
        if (implementation == null) {
            return;
        }
        signatureOf(extension, implementation).ifPresent(
                signature -> method.with(SignatureAttribute.of(signature)));

        implementation.findAttribute(Attributes.exceptions())
                .ifPresent(exceptions -> method.with(ExceptionsAttribute.ofSymbols(
                        exceptions.exceptions().stream()
                                .map(entry -> entry.asSymbol())
                                .toList())));
        implementation.findAttribute(Attributes.deprecated())
                .ifPresent(deprecated -> method.with(DeprecatedAttribute.of()));

        final List<Annotation> visible =
                declaredOn(implementation.findAttribute(Attributes.runtimeVisibleAnnotations())
                        .map(RuntimeVisibleAnnotationsAttribute::annotations).orElse(List.of()));
        if (!visible.isEmpty()) {
            method.with(RuntimeVisibleAnnotationsAttribute.of(visible));
        }
        final List<Annotation> invisible =
                declaredOn(implementation.findAttribute(Attributes.runtimeInvisibleAnnotations())
                        .map(RuntimeInvisibleAnnotationsAttribute::annotations).orElse(List.of()));
        if (!invisible.isEmpty()) {
            method.with(RuntimeInvisibleAnnotationsAttribute.of(invisible));
        }

        final boolean dropReceiver = extension.kind() == WeaveManifest.Extension.Kind.INSTANCE;
        implementation.findAttribute(Attributes.runtimeVisibleParameterAnnotations())
                .map(RuntimeVisibleParameterAnnotationsAttribute::parameterAnnotations)
                .map(all -> parameters(all, dropReceiver))
                .filter(all -> !all.isEmpty())
                .ifPresent(all -> method.with(
                        RuntimeVisibleParameterAnnotationsAttribute.of(all)));
        implementation.findAttribute(Attributes.runtimeInvisibleParameterAnnotations())
                .map(RuntimeInvisibleParameterAnnotationsAttribute::parameterAnnotations)
                .map(all -> parameters(all, dropReceiver))
                .filter(all -> !all.isEmpty())
                .ifPresent(all -> method.with(
                        RuntimeInvisibleParameterAnnotationsAttribute.of(all)));
    }

    /**
     * Drops this framework's own annotations from a list.
     *
     * <p>Matched on the descriptor prefix, so an annotation of a subpackage of the API goes too.
     *
     * @param annotations the annotations found on the implementation
     * @return the annotations worth carrying, in their original order
     */
    @Contract(pure = true)
    @NotNull
    private static List<Annotation> declaredOn(@NotNull final List<Annotation> annotations) {
        final List<Annotation> kept = new ArrayList<>(annotations.size());
        for (final Annotation annotation : annotations) {
            if (!annotation.className().stringValue().startsWith(OWN_ANNOTATIONS)) {
                kept.add(annotation);
            }
        }
        return kept;
    }

    /**
     * Realigns parameter annotations onto the parameters the stub declares.
     *
     * <p>An empty result is returned when nothing survives the filtering, so that a stub is not
     * given a parameter-annotations attribute consisting entirely of empty lists.
     *
     * @param all          the implementation's parameter annotations, one list per parameter
     * @param dropReceiver whether the first parameter is the receiver and must be skipped
     * @return the annotations per remaining parameter, or an empty list when none is left
     */
    @Contract(pure = true)
    @NotNull
    private static List<List<Annotation>> parameters(@NotNull final List<List<Annotation>> all,
                                                     final boolean dropReceiver) {
        final List<List<Annotation>> kept = new ArrayList<>();
        boolean any = false;
        for (int i = dropReceiver ? 1 : 0; i < all.size(); i++) {
            final List<Annotation> annotations = declaredOn(all.get(i));
            any |= !annotations.isEmpty();
            kept.add(annotations);
        }
        return any ? kept : List.of();
    }

    /**
     * Reports whether the receiver already declares this member.
     *
     * <p>Compared against the call-site descriptor, which is what the stub would be given. A
     * constant is looked for among the fields and everything else among the methods.
     *
     * @param model     the receiver
     * @param extension the extension
     * @return {@code true} when no stub is needed
     */
    @Contract(pure = true)
    private static boolean alreadyDeclared(@NotNull final ClassModel model,
                                           @NotNull final WeaveManifest.Extension extension) {
        if (extension.kind() == WeaveManifest.Extension.Kind.CONSTANT) {
            for (final FieldModel field : model.fields()) {
                if (field.fieldName().equalsString(extension.name())
                        && field.fieldType().equalsString(extension.descriptor())) {
                    return true;
                }
            }
            return false;
        }
        for (final MethodModel method : model.methods()) {
            if (method.methodName().equalsString(extension.name())
                    && method.methodType().equalsString(extension.descriptor())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds the holder's method that implements an extension.
     *
     * <p>Matched on {@code implementationDescriptor()}, which for an instance extension carries the
     * receiver as its first parameter.
     *
     * @param extension the extension
     * @param holders   where the implementing class is read from
     * @return the method, or {@code null} when the holder or the method cannot be found
     */
    @Contract(pure = true)
    @Nullable
    private static MethodModel implementationOf(@NotNull final WeaveManifest.Extension extension,
                                                @NotNull final ClassSource holders) {
        final ClassModel holder = holderOf(extension, holders);
        if (holder == null) {
            return null;
        }

        final String descriptor = extension.implementationDescriptor();
        for (final MethodModel method : holder.methods()) {
            if (method.methodName().equalsString(extension.name())
                    && method.methodType().equalsString(descriptor)) {
                return method;
            }
        }
        return null;
    }

    /**
     * Reads the class that implements an extension.
     *
     * <p>Absent and unreadable are the same answer: a stub is generated either way, without the
     * detail the holder would have supplied.
     *
     * @param extension the extension
     * @param holders   where the implementing class is read from
     * @return the holder, or {@code null}
     */
    @Contract(pure = true)
    @Nullable
    private static ClassModel holderOf(@NotNull final WeaveManifest.Extension extension,
                                       @NotNull final ClassSource holders) {
        final Optional<byte[]> bytes = holders.find(extension.classInternalName());
        if (bytes.isEmpty()) {
            return null;
        }
        try {
            return ClassFile.of().parse(bytes.get());
        } catch (final IllegalArgumentException unreadable) {
            return null;
        }
    }

    /**
     * Returns the generic signature the stub should carry, if the implementation has one.
     *
     * <p>An implementation with no {@code Signature} attribute needs none on the stub either: the
     * descriptor already says everything about a method that uses no type variable.
     *
     * @param extension      the extension
     * @param implementation the holder's method
     * @return the signature, or empty when there is none to carry
     */
    @Contract(pure = true)
    @NotNull
    private static Optional<MethodSignature> signatureOf(
            @NotNull final WeaveManifest.Extension extension,
            @NotNull final MethodModel implementation) {
        final Optional<SignatureAttribute> attribute =
                implementation.findAttribute(Attributes.signature());
        if (attribute.isEmpty()) {
            return Optional.empty();
        }
        final MethodSignature signature = attribute.get().asMethodSignature();
        if (extension.kind() == WeaveManifest.Extension.Kind.STATIC) {
            return Optional.of(signature);
        }
        return dropReceiver(signature);
    }

    /**
     * Removes the receiver from an implementation's generic signature.
     *
     * <p>The type parameters, the result and the {@code throws} signatures are kept as they are.
     *
     * <p>A signature with no argument at all cannot be an instance implementation's, and yields
     * empty rather than an adjusted signature.
     *
     * @param implementation the implementation's signature
     * @return the signature without its first argument, or empty when there was none
     */
    @Contract(pure = true)
    @NotNull
    private static Optional<MethodSignature> dropReceiver(
            @NotNull final MethodSignature implementation) {
        final List<Signature> arguments = implementation.arguments();
        if (arguments.isEmpty()) {
            return Optional.empty();
        }
        final List<Signature> remaining = arguments.subList(1, arguments.size());
        return Optional.of(MethodSignature.of(implementation.typeParameters(),
                implementation.throwableSignatures(),
                implementation.result(),
                remaining.toArray(new Signature[0])));
    }
}
