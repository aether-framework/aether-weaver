package de.splatgames.aether.weaver.engine.merge;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import de.splatgames.aether.weaver.api.spi.Reporter;
import de.splatgames.aether.weaver.engine.model.WeaveClass;
import de.splatgames.aether.weaver.engine.model.WeaveMember;
import org.jetbrains.annotations.NotNull;

import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Writes the accessor and invoker methods a weave declares straight onto the target.
 *
 * <p>Nothing is copied here. Both kinds are generated from the declaration's shape alone, so a weave
 * of only accessors and invokers needs no class file of its own — unlike a merged member or a
 * handler, whose body can come from nowhere else.
 *
 * <p>Both entry points check first, refusing what would not work: a field or method the target does
 * not declare, a descriptor or shape that does not match it, a setter that would write a final field,
 * and a name and descriptor the target already has. They run while the target is being rebuilt, so a
 * refusal costs only the one member it names; the refusals that stop the rebuild before it writes
 * happen earlier, and what becomes of the class after that is for the caller to decide.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
final class GeneratedMembers {

    /**
     * The flags every generated member carries: public, and nothing besides.
     *
     * <p>The declaration's own flags are not carried over. The usual spelling of an accessor or an
     * invoker is an abstract method, and emitting that flag would produce an abstract method with a
     * body. Nor is {@code ACC_STATIC} ever set, so slot zero of a generated body is the receiver.
     */
    private static final int GENERATED_FLAGS = ClassFile.ACC_PUBLIC;

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private GeneratedMembers() {
        throw new AssertionError("no instances");
    }

    /**
     * Generates a getter or a setter for a field of the target.
     *
     * <p>Four checks, in this order, each refusing the member outright: the target declares the
     * field ({@code AW1030} otherwise); the descriptor describes a read or a write of that field's
     * type ({@code AW1031}); a setter's field is not final ({@code AW1097}); and the name and
     * descriptor are still free on the target ({@code AW1095}).
     *
     * <p>The emitted body is the receiver where the access needs one, then the field access with the
     * opcode the field's own flags call for. A getter follows with a return of the field's kind; a
     * setter instead loads what sits in slot one for an instance field, or in slot zero for a static
     * one, and returns void. The generated method is an instance method in both cases.
     *
     * @param builder  the target being rebuilt; must not be {@code null}
     * @param accessor the declaration to generate from; must not be {@code null}
     * @param target   the target's members, for resolving the field; must not be {@code null}
     * @param weave    the weave that declared it, named in every diagnostic; must not be {@code null}
     * @param reporter where a refusal is reported; must not be {@code null}
     * @return whether the method was emitted
     * @throws NullPointerException if any argument is {@code null}
     */
    static boolean accessor(@NotNull final ClassBuilder builder,
                            @NotNull final WeaveMember.Accessor accessor,
                            @NotNull final TargetMembers target,
                            @NotNull final WeaveClass weave,
                            @NotNull final Reporter reporter) {
        Objects.requireNonNull(builder, "builder");
        Objects.requireNonNull(accessor, "accessor");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(weave, "weave");
        Objects.requireNonNull(reporter, "reporter");

        final Optional<FieldModel> found = target.field(accessor.targetField());
        if (found.isEmpty()) {
            reporter.report(Diagnostic.builder(DiagnosticCode.FIELD_NOT_FOUND)
                    .message(weave.binaryName() + '#' + accessor.name() + " exposes a field '"
                            + accessor.targetField() + "' that " + target.type().displayName()
                            + " does not declare")
                    .remedy("name the field explicitly with @Accessor(\"…\") if the inference from "
                            + "the method name picked the wrong one")
                    .build());
            return false;
        }
        final FieldModel field = found.get();
        final ClassDesc fieldType = field.fieldTypeSymbol();
        final boolean isStatic = field.flags().flags().contains(AccessFlag.STATIC);

        if (!shapeFits(accessor, fieldType, weave, reporter)) {
            return false;
        }
        if (!accessor.isGetter() && field.flags().flags().contains(AccessFlag.FINAL)) {
            // The verifier does not catch this. A putfield to a final field from anything but the
            // declaring constructor is an IllegalAccessError thrown when the setter is FIRST CALLED
            // — so a class that passed every check the engine runs would fail in production, at a
            // point with nothing to connect it back to the weave that caused it.
            reporter.report(Diagnostic.builder(DiagnosticCode.ACCESSOR_WRITES_FINAL_FIELD)
                    .message(weave.binaryName() + '#' + accessor.name() + " would write '"
                            + accessor.targetField() + "', which " + target.type().displayName()
                            + " declares final")
                    .detail("the class would verify and throw IllegalAccessError the first time "
                            + "the setter was called")
                    .remedy("a final field is written once, by the constructor. Use "
                            + "@Shadow(mutable = true), which removes the flag deliberately and "
                            + "says so — an accessor has no way to express that intent")
                    .build());
            return false;
        }
        if (!isFree(builder, target, accessor.name(), accessor.type(), weave, reporter)) {
            return false;
        }

        builder.withMethodBody(accessor.name(), accessor.type(), GENERATED_FLAGS, code -> {
            if (accessor.isGetter()) {
                if (!isStatic) {
                    code.aload(0);
                }
                code.fieldAccess(target.fieldOpcodeFor(field, false), target.type(),
                        accessor.targetField(), fieldType);
                code.return_(TypeKind.from(fieldType));
                return;
            }
            if (!isStatic) {
                code.aload(0);
            }
            // Slot zero is `this`, so the value the setter was handed starts at one.
            code.loadLocal(TypeKind.from(fieldType), isStatic ? 0 : 1);
            code.fieldAccess(target.fieldOpcodeFor(field, true), target.type(),
                    accessor.targetField(), fieldType);
            code.return_();
        });
        return true;
    }

    /**
     * Checks that the declared descriptor is a read or a write of the field it names.
     *
     * <p>A getter returns the field's type and takes nothing; a setter takes it and returns void.
     * Anything else is reported as {@code AW1031}, the same code a shadow whose type disagrees with
     * the target's gets, with the declared descriptor as the detail.
     *
     * @param accessor  the declaration; must not be {@code null}
     * @param fieldType the type of the field it names; must not be {@code null}
     * @param weave     the weave that declared it; must not be {@code null}
     * @param reporter  where a refusal is reported; must not be {@code null}
     * @return whether the descriptor fits
     */
    private static boolean shapeFits(@NotNull final WeaveMember.Accessor accessor,
                                     @NotNull final ClassDesc fieldType,
                                     @NotNull final WeaveClass weave,
                                     @NotNull final Reporter reporter) {
        final MethodTypeDesc type = accessor.type();
        final boolean getter = accessor.isGetter()
                && fieldType.equals(type.returnType());
        final boolean setter = type.parameterCount() == 1
                && ConstantDescs.CD_void.equals(type.returnType())
                && fieldType.equals(type.parameterType(0));
        if (getter || setter) {
            return true;
        }
        reporter.report(Diagnostic.builder(DiagnosticCode.SHADOW_TYPE_MISMATCH)
                .message(weave.binaryName() + '#' + accessor.name()
                        + " does not describe a read or a write of '" + accessor.targetField()
                        + "', which is a " + fieldType.displayName())
                .detail("declared: " + type.displayDescriptor())
                .remedy("a getter takes nothing and returns the field's type; a setter takes the "
                        + "field's type and returns void")
                .build());
        return false;
    }

    /**
     * Generates a method on the target that calls one of its own, whatever its access.
     *
     * <p>The target must declare that name with exactly that descriptor: an invoker is the same call
     * made from inside the class, so nothing is adapted. A miss is {@code AW1020}, carrying every
     * method of that name as details so that a mistaken overload can be seen; a name and descriptor
     * the target already declares is {@code AW1095}.
     *
     * <p>The body loads the receiver unless the resolved method is static, then each parameter in
     * turn by its own width, and calls with the opcode
     * {@link TargetMembers#invokeOpcodeFor(MethodModel)} resolves. Slots are counted from one for an
     * instance method and from zero for a static one, and the generated method is an instance method
     * in both cases.
     *
     * @param builder  the target being rebuilt; must not be {@code null}
     * @param invoker  the declaration to generate from; must not be {@code null}
     * @param target   the target's members, for resolving the method; must not be {@code null}
     * @param weave    the weave that declared it, named in every diagnostic; must not be {@code null}
     * @param reporter where a refusal is reported; must not be {@code null}
     * @return whether the method was emitted
     * @throws NullPointerException if any argument is {@code null}
     */
    static boolean invoker(@NotNull final ClassBuilder builder,
                           @NotNull final WeaveMember.Invoker invoker,
                           @NotNull final TargetMembers target,
                           @NotNull final WeaveClass weave,
                           @NotNull final Reporter reporter) {
        Objects.requireNonNull(builder, "builder");
        Objects.requireNonNull(invoker, "invoker");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(weave, "weave");
        Objects.requireNonNull(reporter, "reporter");

        final Optional<MethodModel> found =
                target.method(invoker.targetMethod(), invoker.type());
        if (found.isEmpty()) {
            reporter.report(Diagnostic.builder(DiagnosticCode.METHOD_NOT_FOUND)
                    .message(weave.binaryName() + '#' + invoker.name() + " exposes a method '"
                            + invoker.targetMethod() + invoker.type().displayDescriptor()
                            + "' that " + target.type().displayName() + " does not declare")
                    .details(target.methodsNamed(invoker.targetMethod()).stream()
                            .map(candidate -> candidate.methodName().stringValue()
                                    + candidate.methodTypeSymbol().displayDescriptor())
                            .toList())
                    .remedy("an invoker's signature must match the target method's exactly — it is "
                            + "the same call, made from inside the class")
                    .build());
            return false;
        }
        if (!isFree(builder, target, invoker.name(), invoker.type(), weave, reporter)) {
            return false;
        }

        final MethodModel method = found.get();
        final Opcode opcode = target.invokeOpcodeFor(method);
        final boolean isStatic = opcode == Opcode.INVOKESTATIC;

        builder.withMethodBody(invoker.name(), invoker.type(), GENERATED_FLAGS, code -> {
            int slot = isStatic ? 0 : 1;
            if (!isStatic) {
                code.aload(0);
            }
            final List<ClassDesc> parameters = invoker.type().parameterList();
            for (final ClassDesc parameter : parameters) {
                final TypeKind kind = TypeKind.from(parameter);
                code.loadLocal(kind, slot);
                // A long or a double occupies two slots, so the next one does not start at +1.
                slot += kind.slotSize();
            }
            code.invoke(opcode, target.type(), invoker.targetMethod(), invoker.type(),
                    target.isInterface());
            code.return_(TypeKind.from(invoker.type().returnType()));
        });
        return true;
    }

    /**
     * Checks that the target does not already declare the member about to be generated.
     *
     * <p>Only the target's own declarations are consulted, and a collision is {@code AW1095}. A
     * generated member has no {@code @Unique} spelling to be renamed under, so the declaration in
     * the weave is what has to change.
     *
     * @param builder    the class being rebuilt; not consulted, the target's own members answer the
     *                   question
     * @param target     the target's members; must not be {@code null}
     * @param name       the name the member would be generated under; must not be {@code null}
     * @param descriptor the descriptor it would carry; must not be {@code null}
     * @param weave      the weave that declared it; must not be {@code null}
     * @param reporter   where a refusal is reported; must not be {@code null}
     * @return whether the name and descriptor are free
     */
    private static boolean isFree(@NotNull final ClassBuilder builder,
                                  @NotNull final TargetMembers target,
                                  @NotNull final String name,
                                  @NotNull final MethodTypeDesc descriptor,
                                  @NotNull final WeaveClass weave,
                                  @NotNull final Reporter reporter) {
        if (!target.declaresMethod(name, descriptor)) {
            return true;
        }
        reporter.report(Diagnostic.builder(DiagnosticCode.GENERATED_MEMBER_COLLIDES)
                .message(weave.binaryName() + " generates '" + name
                        + descriptor.displayDescriptor() + "' on " + target.type().displayName()
                        + ", which already declares it")
                .remedy("rename the accessor or invoker; generating over the target's own method "
                        + "would replace working code")
                .build());
        return false;
    }
}
