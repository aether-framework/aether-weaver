package de.splatgames.aether.weaver.engine.merge;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The target class as the merge stage needs to see it: what it declares, and what shape it is.
 *
 * <p>Built once per class being woven and then asked repeatedly — every binding, every generated
 * member and every refusal message resolves a name through it — so the two maps are built up front
 * rather than by walking {@link ClassModel#fields()} again for each question.
 *
 * <p>Only declarations are indexed. One of the refusals built from it says why: a shadowed method the
 * target does not declare is told, in its remedy, that resolving an inherited member would mean
 * walking the hierarchy and loading classes from inside class loading. The other not-found refusals
 * built from this index — a shadowed field, an accessor's field, an invoker's method — do not repeat
 * that rationale; each speaks only to its own case.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
final class TargetMembers {

    /** The superclass every record has, and one of the two signals that the target is one. */
    private static final ClassDesc RECORD = ClassDesc.of("java.lang.Record");

    /** The target's own type, which every rebound and generated instruction names as its owner. */
    private final ClassDesc type;

    /** Whether the target is an interface, which decides between invokeinterface and invokevirtual. */
    private final boolean isInterface;

    /** Whether the target is a record, over-detected on purpose; see the constructor. */
    private final boolean isRecord;

    /** Whether the target is an enum, which limits what merging an instance field can achieve. */
    private final boolean isEnum;

    /**
     * The declared fields, keyed by name alone.
     *
     * <p>A shadow and an accessor both name a field without a descriptor, so the name is the whole
     * key; where a class file declares one name twice, the later declaration is the one kept.
     * Iteration order is the copy's and not the class file's.
     */
    private final Map<String, FieldModel> fields;

    /** The declared methods, keyed by name and descriptor, since an overload is a different member. */
    private final Map<String, MethodModel> methods;

    /**
     * Indexes a target class.
     *
     * @param model the class being woven; must not be {@code null}
     * @throws NullPointerException if {@code model} is {@code null}
     */
    TargetMembers(@NotNull final ClassModel model) {
        Objects.requireNonNull(model, "model");
        this.type = model.thisClass().asSymbol();
        this.isInterface = model.flags().flags().contains(AccessFlag.INTERFACE);
        // Two independent signals, because either one alone can be absent: a record compiled by a
        // tool that omits the attribute still extends java.lang.Record, and a class file could carry
        // the attribute without the superclass. Merging a field into either shape is refused, so the
        // cheap over-detection is the safe direction.
        this.isRecord = model.findAttribute(Attributes.record()).isPresent()
                || model.superclass().map(entry -> RECORD.equals(entry.asSymbol())).orElse(false);
        this.isEnum = model.flags().flags().contains(AccessFlag.ENUM);

        final Map<String, FieldModel> byFieldName = new LinkedHashMap<>();
        model.fields().forEach(field -> byFieldName.put(field.fieldName().stringValue(), field));
        this.fields = Map.copyOf(byFieldName);

        final Map<String, MethodModel> byKey = new LinkedHashMap<>();
        model.methods().forEach(method -> byKey.put(
                key(method.methodName().stringValue(), method.methodTypeSymbol()), method));
        this.methods = Map.copyOf(byKey);
    }

    /**
     * Returns the target's type.
     *
     * @return the type, as named by the class file's own {@code this_class}
     */
    @Contract(pure = true)
    @NotNull
    ClassDesc type() {
        return this.type;
    }

    /**
     * Looks up a declared field.
     *
     * @param name the field's name; must not be {@code null}
     * @return the field, or empty when the target declares none by that name
     * @throws NullPointerException if {@code name} is {@code null}
     */
    @Contract(pure = true)
    @NotNull
    Optional<FieldModel> field(@NotNull final String name) {
        return Optional.ofNullable(this.fields.get(Objects.requireNonNull(name, "name")));
    }

    /**
     * Looks up a declared method by name and descriptor.
     *
     * <p>The descriptor has to match exactly; {@link #methodsNamed(String)} is what a diagnostic uses
     * to show the reader what the target does declare under that name.
     *
     * @param name       the method's name; must not be {@code null}
     * @param descriptor the method's descriptor; must not be {@code null}
     * @return the method, or empty when the target declares no such member
     * @throws NullPointerException if either argument is {@code null}
     */
    @Contract(pure = true)
    @NotNull
    Optional<MethodModel> method(@NotNull final String name,
                                 @NotNull final MethodTypeDesc descriptor) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(descriptor, "descriptor");
        return Optional.ofNullable(this.methods.get(key(name, descriptor)));
    }

    /**
     * Returns every declared method of that name, whatever its descriptor.
     *
     * <p>The candidate list a not-found diagnostic prints: a weave that got an overload wrong reads
     * the descriptors it could have meant instead of being told only that its own is unknown. The
     * index is an immutable map copy, so the order is the map's rather than the class file's.
     *
     * @param name the method name; must not be {@code null}
     * @return the matching methods, empty when there are none
     * @throws NullPointerException if {@code name} is {@code null}
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    List<MethodModel> methodsNamed(@NotNull final String name) {
        Objects.requireNonNull(name, "name");
        final List<MethodModel> found = new ArrayList<>();
        this.methods.values().forEach(method -> {
            if (method.methodName().equalsString(name)) {
                found.add(method);
            }
        });
        return List.copyOf(found);
    }

    /**
     * Reports whether the target declares a field of that name.
     *
     * <p>A merged field collides on the name alone; unlike a method, no descriptor takes part.
     *
     * @param name the field name; must not be {@code null}
     * @return whether the target declares it
     * @throws NullPointerException if {@code name} is {@code null}
     */
    @Contract(pure = true)
    boolean declaresField(@NotNull final String name) {
        return this.fields.containsKey(Objects.requireNonNull(name, "name"));
    }

    /**
     * Reports whether the target declares a method of that name and descriptor.
     *
     * <p>Both parts count, so a merged or generated method may share a name with one of the target's
     * own as long as the descriptors differ.
     *
     * @param name       the method name; must not be {@code null}
     * @param descriptor the method descriptor; must not be {@code null}
     * @return whether the target declares it
     * @throws NullPointerException if either argument is {@code null}
     */
    @Contract(pure = true)
    boolean declaresMethod(@NotNull final String name,
                           @NotNull final MethodTypeDesc descriptor) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(descriptor, "descriptor");
        return this.methods.containsKey(key(name, descriptor));
    }

    /**
     * Returns the opcode a call to the given member of the target must use.
     *
     * <p>The answer comes from the resolved member's own flags, never from how the weave wrote the
     * call: the weave compiled against its own declaration of the member, which need not agree.
     *
     * @param method the target's method, as resolved; must not be {@code null}
     * @return the invocation opcode
     * @throws NullPointerException if {@code method} is {@code null}
     */
    @Contract(pure = true)
    @NotNull
    Opcode invokeOpcodeFor(@NotNull final MethodModel method) {
        final java.util.Set<AccessFlag> flags =
                Objects.requireNonNull(method, "method").flags().flags();
        if (flags.contains(AccessFlag.STATIC)) {
            return Opcode.INVOKESTATIC;
        }
        if (flags.contains(AccessFlag.PRIVATE)) {
            // Not invokevirtual. It happens to work inside a nestmate and is wrong the moment the
            // target is not one, and wrong in a way that dispatches to an override.
            return Opcode.INVOKESPECIAL;
        }
        return this.isInterface ? Opcode.INVOKEINTERFACE : Opcode.INVOKEVIRTUAL;
    }

    /**
     * Returns the opcode an access to the given field of the target must use.
     *
     * @param field the target's field, as resolved; must not be {@code null}
     * @param write whether the access writes rather than reads
     * @return the field access opcode
     * @throws NullPointerException if {@code field} is {@code null}
     */
    @Contract(pure = true)
    @NotNull
    Opcode fieldOpcodeFor(@NotNull final FieldModel field, final boolean write) {
        final boolean isStatic =
                Objects.requireNonNull(field, "field").flags().flags().contains(AccessFlag.STATIC);
        if (isStatic) {
            return write ? Opcode.PUTSTATIC : Opcode.GETSTATIC;
        }
        return write ? Opcode.PUTFIELD : Opcode.GETFIELD;
    }

    /**
     * Reports whether the target is an interface.
     *
     * @return whether the class file carries {@code ACC_INTERFACE}
     */
    @Contract(pure = true)
    boolean isInterface() {
        return this.isInterface;
    }

    /**
     * Reports whether the target is a record.
     *
     * @return whether either of the two signals the constructor reads is present
     */
    @Contract(pure = true)
    boolean isRecord() {
        return this.isRecord;
    }

    /**
     * Reports whether the target is an enum.
     *
     * @return whether the class file carries {@code ACC_ENUM}
     */
    @Contract(pure = true)
    boolean isEnum() {
        return this.isEnum;
    }

    /**
     * Builds the key a method is indexed under.
     *
     * @param name       the method name
     * @param descriptor the method descriptor
     * @return the two concatenated
     */
    @Contract(pure = true)
    @NotNull
    private static String key(@NotNull final String name,
                              @NotNull final MethodTypeDesc descriptor) {
        return name + descriptor.descriptorString();
    }

    /**
     * Returns the target's name and how much of it was indexed.
     *
     * @return a description for a debugger or a log line
     */
    @Override
    @NotNull
    public String toString() {
        return "TargetMembers[" + this.type.displayName() + ", fields=" + this.fields.size()
                + ", methods=" + this.methods.size() + ']';
    }
}
