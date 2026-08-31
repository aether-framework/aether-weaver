package de.splatgames.aether.weaver.engine.extension;

import de.splatgames.aether.weaver.api.manifest.WeaveManifest;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.Opcode;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDesc;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.DynamicCallSiteDesc;
import java.lang.constant.MethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.Objects;

/**
 * Repoints the calls a class makes to a contributed extension at the class that implements it.
 *
 * <p>An extension is compiled against a stub on the receiver, so the call site {@code javac}
 * produced names the receiver and not the holder. Rewriting it here is what makes the call reach
 * the implementation at run time: an {@code invokevirtual} or {@code invokeinterface} becomes an
 * {@code invokestatic} on the holder with the receiver as its first argument, a {@code getstatic}
 * of a contributed constant is repointed at the holder's field, and a method handle among an
 * {@code invokedynamic}'s bootstrap arguments is rebound the same way, which is what carries a
 * method reference to an extension.
 *
 * <p>Every class an agent loads passes through here, so the cheap questions come first: an empty
 * index returns before the class is parsed, and a constant pool holding no name and descriptor the
 * index knows returns before any transform is built.
 *
 * <p>Stateless, and every method is pure.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class ExtensionCalls {

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private ExtensionCalls() {
        throw new AssertionError("no instances");
    }

    /**
     * Rewrites every extension call in a class.
     *
     * <p>Returns {@code null} to mean that nothing was rewritten, which a caller must not confuse
     * with a failure. Rewrites are counted rather than inferred from the constant pool that led
     * here, because a pool entry can outlive the instruction that needed it: returning a rebuilt
     * class that is identical in meaning would report a change to the statistics and to the
     * idempotence gate that never happened.
     *
     * @param original the class to rewrite; must not be {@code null}
     * @param index    the extensions in force; must not be {@code null}
     * @return the rewritten class, or {@code null} when no instruction changed
     * @throws NullPointerException if either argument is {@code null}
     */
    @Contract(pure = true)
    public static byte @Nullable [] rewrite(final byte @NotNull [] original,
                                            @NotNull final ExtensionIndex index) {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(index, "index");
        if (index.isEmpty()) {
            return null;
        }

        final ClassFile classFile = ClassFile.of();
        final ClassModel model = classFile.parse(original);
        if (!callsAnExtension(model, index)) {
            return null;
        }

        // Counted rather than assumed. A pool entry can outlive the instruction that needed it —
        // a method removed by a previous transform leaves its Methodref behind — and returning a
        // rebuilt class that is byte-identical in meaning would report a rewrite that never
        // happened, which is what the driver's statistics and the idempotence gate both read.
        final int[] rewritten = {0};
        final byte[] result = classFile.transformClass(model,
                ClassTransform.transformingMethodBodies((builder, element) -> {
                    if (element instanceof final InvokeInstruction invoke) {
                        final WeaveManifest.Extension extension = candidate(invoke, index);
                        if (extension != null) {
                            builder.invokestatic(ClassDesc.of(extension.className()),
                                    extension.name(),
                                    MethodTypeDesc.ofDescriptor(
                                            extension.implementationDescriptor()));
                            rewritten[0]++;
                            return;
                        }
                    } else if (element instanceof final FieldInstruction read) {
                        final WeaveManifest.Extension constant = constant(read, index);
                        if (constant != null) {
                            builder.getstatic(ClassDesc.of(constant.className()), constant.name(),
                                    ClassDesc.ofDescriptor(constant.descriptor()));
                            rewritten[0]++;
                            return;
                        }
                    } else if (element instanceof final InvokeDynamicInstruction indy) {
                        final DynamicCallSiteDesc rebound = rebound(indy, index);
                        if (rebound != null) {
                            builder.invokedynamic(rebound);
                            rewritten[0]++;
                            return;
                        }
                    }
                    builder.with(element);
                }));
        return rewritten[0] == 0 ? null : result;
    }

    /**
     * Matches a field access against the contributed constants.
     *
     * <p>Only {@code GETSTATIC}: the access is rewritten to a {@code getstatic} on the holder, so
     * an instance field access or a write cannot be what a contributed constant was compiled to.
     *
     * @param read  the field instruction
     * @param index the extensions in force
     * @return the constant this access reads, or {@code null}
     */
    @Contract(pure = true)
    @Nullable
    private static WeaveManifest.Extension constant(@NotNull final FieldInstruction read,
                                                    @NotNull final ExtensionIndex index) {
        if (read.opcode() != Opcode.GETSTATIC) {
            return null;
        }
        return index.find(read.owner().asInternalName(),
                read.name().stringValue(),
                read.type().stringValue(),
                WeaveManifest.Extension.Kind.CONSTANT);
    }

    /**
     * Rebinds any bootstrap argument of an invokedynamic that is a handle to an extension.
     *
     * <p>The argument array is cloned lazily, so a call site with nothing to rebind allocates
     * nothing and is answered with {@code null}. Every argument is examined, since a bootstrap may
     * carry several handles.
     *
     * @param indy  the instruction
     * @param index the extensions in force
     * @return the rebound call site, or {@code null} when no argument named an extension
     */
    @Contract(pure = true)
    @Nullable
    private static DynamicCallSiteDesc rebound(@NotNull final InvokeDynamicInstruction indy,
                                               @NotNull final ExtensionIndex index) {
        final DynamicCallSiteDesc site = indy.invokedynamic().asSymbol();
        final ConstantDesc[] arguments = site.bootstrapArgs();

        ConstantDesc[] rebound = null;
        for (int i = 0; i < arguments.length; i++) {
            if (!(arguments[i] instanceof final DirectMethodHandleDesc handle)) {
                continue;
            }
            final MethodHandleDesc repointed = repointed(handle, index);
            if (repointed == null) {
                continue;
            }
            if (rebound == null) {
                rebound = arguments.clone();
            }
            rebound[i] = repointed;
        }
        if (rebound == null) {
            return null;
        }
        // withArgs, so that the bootstrap method, the name and the invocation type are carried
        // over rather than restated. They are all still correct, and restating them is how one of
        // them would eventually stop being.
        return site.withArgs(rebound);
    }

    /**
     * Repoints one method handle at the holder that implements the extension it names.
     *
     * <p>The result is always a {@code STATIC} handle, whatever the original kind was, because an
     * instance extension is implemented by a static method taking the receiver first.
     *
     * @param handle the handle to examine
     * @param index  the extensions in force
     * @return the repointed handle, or {@code null} when the handle names no extension
     */
    @Contract(pure = true)
    @Nullable
    private static MethodHandleDesc repointed(@NotNull final DirectMethodHandleDesc handle,
                                              @NotNull final ExtensionIndex index) {
        final WeaveManifest.Extension.Kind kind = kindOf(handle.kind());
        if (kind == null) {
            return null;
        }
        // lookupDescriptor(), not invocationType(): for REF_invokeVirtual the latter has the
        // receiver prepended, and the index is keyed by what the call site writes.
        final WeaveManifest.Extension extension = index.find(internalNameOf(handle.owner()),
                handle.methodName(),
                handle.lookupDescriptor(),
                kind);
        if (extension == null) {
            return null;
        }
        return MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.STATIC,
                ClassDesc.of(extension.className()), extension.name(),
                MethodTypeDesc.ofDescriptor(extension.implementationDescriptor()));
    }

    /**
     * Returns the internal name of a class descriptor, which is how the index is keyed.
     *
     * @param type the descriptor
     * @return the internal name, with slashes
     */
    @Contract(pure = true)
    @NotNull
    private static String internalNameOf(@NotNull final ClassDesc type) {
        final String descriptor = type.descriptorString();
        return descriptor.substring(1, descriptor.length() - 1);
    }

    /**
     * Maps a method handle kind onto the extension kind it could stand for.
     *
     * <p>A getter, a setter, a constructor or a special invocation cannot name an extension, and
     * answering {@code null} for them is what keeps them out of the lookup.
     *
     * @param kind the handle's kind
     * @return the extension kind, or {@code null} when no extension can have that shape
     */
    @Contract(pure = true)
    @Nullable
    private static WeaveManifest.Extension.Kind kindOf(
            @NotNull final DirectMethodHandleDesc.Kind kind) {
        return switch (kind) {
            case VIRTUAL, INTERFACE_VIRTUAL -> WeaveManifest.Extension.Kind.INSTANCE;
            case STATIC, INTERFACE_STATIC -> WeaveManifest.Extension.Kind.STATIC;
            default -> null;
        };
    }

    /**
     * Matches an invocation against the index.
     *
     * <p>The owner written at the call site is only the starting point; the index walks the
     * hierarchy from there, so an extension declared on a supertype answers a call on a subtype.
     *
     * @param invoke the instruction
     * @param index  the extensions in force
     * @return the extension this call should reach, or {@code null}
     */
    @Contract(pure = true)
    @Nullable
    private static WeaveManifest.Extension candidate(@NotNull final InvokeInstruction invoke,
                                                     @NotNull final ExtensionIndex index) {
        final WeaveManifest.Extension.Kind kind = kindOf(invoke.opcode());
        if (kind == null) {
            return null;
        }
        return index.find(invoke.owner().asInternalName(),
                invoke.name().stringValue(),
                invoke.type().stringValue(),
                kind);
    }

    /**
     * Maps an invocation opcode onto the extension kind it could stand for.
     *
     * <p>{@code INVOKESPECIAL} is absent on purpose: it is how a constructor, a private method and
     * a {@code super} call are written, none of which resolves to an extension.
     *
     * @param opcode the opcode
     * @return the extension kind, or {@code null} when no extension can have that shape
     */
    @Contract(pure = true)
    @Nullable
    private static WeaveManifest.Extension.Kind kindOf(@NotNull final Opcode opcode) {
        return switch (opcode) {
            case INVOKEVIRTUAL, INVOKEINTERFACE -> WeaveManifest.Extension.Kind.INSTANCE;
            case INVOKESTATIC -> WeaveManifest.Extension.Kind.STATIC;
            default -> null;
        };
    }

    /**
     * Reports whether the constant pool holds any name and descriptor the index knows.
     *
     * <p>A pre-filter, not a decision: it ignores the owner, so it can answer {@code true} for a
     * class that turns out to have nothing rewritten. It exists to keep a class that mentions no
     * extension at all from being transformed.
     *
     * @param model the class to inspect
     * @param index the extensions in force
     * @return {@code true} when a rewrite is worth attempting
     */
    @Contract(pure = true)
    private static boolean callsAnExtension(@NotNull final ClassModel model,
                                            @NotNull final ExtensionIndex index) {
        for (final PoolEntry entry : model.constantPool()) {
            // Fields as well as methods: a contributed constant is read through a Fieldref. The two
            // cannot be confused by the lookup, because a method's descriptor begins with '(' and a
            // field's never does.
            if (!(entry instanceof final MemberRefEntry reference)) {
                continue;
            }
            if (index.mentions(reference.name().stringValue(),
                    reference.type().stringValue())) {
                return true;
            }
        }
        return false;
    }
}
