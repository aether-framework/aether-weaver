package de.splatgames.aether.weaver.engine.extension;

import de.splatgames.aether.weaver.api.manifest.WeaveManifest;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodTransform;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Weaves a null check on the receiver into the extensions that asked for one.
 *
 * <p>An instance extension is implemented as a static method whose first parameter is the receiver,
 * so a call on {@code null} does not throw where an ordinary method call would: it enters the
 * implementation, and fails later and somewhere else. A declaration whose receiver is
 * {@code CHECKED} gets the throw put back at the entry of the implementation, with a message naming
 * the extension and the holder.
 *
 * <p>The prologue is deliberately linear and branch-free, so a method's existing stack map frames
 * stay valid and none has to be recomputed.
 *
 * <p>Stateless, and every method is pure.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class ExtensionGuards {

    /** The class holding the check, so that the prologue needs no helper of this project's own. */
    private static final ClassDesc OBJECTS = ClassDesc.of("java.util.Objects");

    /** The two-argument overload, which is the one that carries a message. */
    private static final MethodTypeDesc REQUIRE_NON_NULL =
            MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_Object,
                    ConstantDescs.CD_String);

    /** The method name, also what {@code alreadyGuarded} looks for. */
    private static final String REQUIRE_NON_NULL_NAME = "requireNonNull";

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private ExtensionGuards() {
        throw new AssertionError("no instances");
    }

    /**
     * Adds the receiver check to every declaration in the list that asked for one.
     *
     * <p>The list is the holder's own declarations, and only those with {@code guarded()} set are
     * considered, which is the instance kind with a {@code CHECKED} receiver and nothing else. A
     * class with no such declaration is not rebuilt at all, which matters because every class an
     * agent loads reaches this method.
     *
     * <p>Methods are matched on name and {@code implementationDescriptor()}, the descriptor the
     * holder's own static method has, rather than on the call site's.
     *
     * @param holder     the holder class; must not be {@code null}
     * @param extensions the declarations of that holder; must not be {@code null}
     * @return the hardened class, or {@code null} when no check was added
     * @throws NullPointerException if either argument is {@code null}
     */
    @Contract(pure = true)
    public static byte @Nullable [] harden(final byte @NotNull [] holder,
                                           @NotNull final List<WeaveManifest.Extension> extensions) {
        Objects.requireNonNull(holder, "holder");
        Objects.requireNonNull(extensions, "extensions");

        final Map<String, WeaveManifest.Extension> guarded = new LinkedHashMap<>();
        for (final WeaveManifest.Extension extension : extensions) {
            if (extension.guarded()) {
                guarded.put(extension.name() + extension.implementationDescriptor(), extension);
            }
        }
        if (guarded.isEmpty()) {
            return null;
        }

        final ClassFile classFile = ClassFile.of();
        final ClassModel model = classFile.parse(holder);
        final int[] added = {0};

        final byte[] result = classFile.transformClass(model, (builder, element) -> {
            if (!(element instanceof final MethodModel method)) {
                builder.with(element);
                return;
            }
            final WeaveManifest.Extension extension = guarded.get(
                    method.methodName().stringValue() + method.methodType().stringValue());
            if (extension == null || alreadyGuarded(method)) {
                builder.with(element);
                return;
            }
            builder.transformMethod(method, MethodTransform.transformingCode(prologue(extension)));
            added[0]++;
        });
        return added[0] == 0 ? null : result;
    }

    /**
     * Builds the transform that prepends the check to one method's code.
     *
     * <p>Local {@code 0} is the receiver: the implementation is static and takes the receiver as
     * its first parameter, so the slot is the argument rather than {@code this}.
     *
     * <p>The message is built when the transform is created, so it reaches the class file as one
     * constant rather than as string handling at run time.
     *
     * @param extension the declaration whose receiver to check
     * @return the transform
     */
    @Contract(pure = true)
    @NotNull
    private static CodeTransform prologue(@NotNull final WeaveManifest.Extension extension) {
        final String message = extension.receiver() + '.' + extension.name()
                + " was called on null (the extension in " + extension.className()
                + " declares its receiver @Receiver(nulls = CHECKED))";
        return new CodeTransform() {
            /**
             * Emits the check before anything the author wrote.
             *
             * @param builder the code being built
             */
            @Override
            public void atStart(final java.lang.classfile.CodeBuilder builder) {
                // Linear and branch-free, so the stack map frames the method already carries stay
                // valid: two values pushed and both consumed before any instruction the author
                // wrote. requireNonNull returns its argument, which nothing here wants.
                builder.aload(0)
                        .ldc(message)
                        .invokestatic(OBJECTS, REQUIRE_NON_NULL_NAME, REQUIRE_NON_NULL)
                        .pop();
            }

            /**
             * Copies the method's own code through unchanged.
             *
             * @param builder the code being built
             * @param element the element to copy
             */
            @Override
            public void accept(final java.lang.classfile.CodeBuilder builder,
                               final CodeElement element) {
                builder.with(element);
            }
        };
    }

    /**
     * Reports whether a method already begins with a null check.
     *
     * <p>There is no stamp to consult, so the method's own first instructions are what stop a
     * second check being stacked behind the first when a class is hardened twice. The test is
     * coarse by construction: any method whose first invocation is
     * {@code java.util.Objects.requireNonNull} counts as guarded, whatever that call checks and
     * whichever overload it is.
     *
     * @param method the method to inspect
     * @return {@code true} when the method is treated as already guarded
     */
    @Contract(pure = true)
    private static boolean alreadyGuarded(@NotNull final MethodModel method) {
        return method.code()
                .map(code -> code.elementList().stream()
                        .filter(InvokeInstruction.class::isInstance)
                        .map(InvokeInstruction.class::cast)
                        .findFirst()
                        .filter(invoke -> invoke.owner().asInternalName().equals("java/util/Objects")
                                && invoke.name().equalsString(REQUIRE_NON_NULL_NAME))
                        .isPresent())
                .orElse(false);
    }

}
