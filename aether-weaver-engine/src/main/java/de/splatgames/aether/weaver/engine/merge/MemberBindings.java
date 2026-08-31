package de.splatgames.aether.weaver.engine.merge;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import de.splatgames.aether.weaver.api.spi.Reporter;
import de.splatgames.aether.weaver.api.model.HandlerRef;
import de.splatgames.aether.weaver.api.model.InjectorSpec;
import de.splatgames.aether.weaver.engine.model.WeaveClass;
import de.splatgames.aether.weaver.engine.model.WeaveMember;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What each member of one weave resolves to on one target: the name it is emitted or called under,
 * and for a method the opcode a call to it must carry.
 *
 * <p>A binding is made for every shadowed and merged member the weave declares, and for every handler
 * it owns; a merged body may name any of them under the identity the weave gave it. An accessor or an
 * invoker needs none — each is generated on the target straight from its declaration, never copied out
 * of a body itself. A moved body may still call one, under the name and opcode it was compiled with,
 * since neither is bound. The two maps are keyed the way their members collide: a field by its name
 * alone, a method by name and descriptor.
 *
 * <p>Binding reports as it goes and never stops at the first failure, so one run tells a weave
 * everything that is wrong with it. {@link #isComplete()} is what a caller acts on.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
final class MemberBindings {

    /** How many hexadecimal characters of the digest a mangled name carries. */
    private static final int MANGLE_LENGTH = 8;

    /** What separates a mangled member's own name from the digest of the weave that declared it. */
    private static final String MANGLE_INFIX = "$aw$";

    /** The field bindings, keyed by the name the weave declares the field under. */
    private final Map<String, FieldRebind> fields;

    /** The method and handler bindings, keyed by the declared name and descriptor. */
    private final Map<String, MethodRebind> methods;

    /**
     * The target's fields that are to be rewritten without their final flag.
     *
     * <p>Named as the target names them, because that is what the rebuild matches its own fields
     * against, and a shadow may declare the member under another name.
     */
    private final Set<String> unfinalised;

    /** Whether every member bound; when false, at least one refusal has already been reported. */
    private final boolean complete;

    /**
     * Copies the resolved bindings into immutable maps.
     *
     * @param fields      the field bindings; must not be {@code null}
     * @param methods     the method bindings; must not be {@code null}
     * @param unfinalised the target fields to unfinalise; must not be {@code null}
     * @param complete    whether every member bound
     */
    private MemberBindings(@NotNull final Map<String, FieldRebind> fields,
                           @NotNull final Map<String, MethodRebind> methods,
                           @NotNull final Set<String> unfinalised,
                           final boolean complete) {
        this.fields = Map.copyOf(fields);
        this.methods = Map.copyOf(methods);
        this.unfinalised = Set.copyOf(unfinalised);
        this.complete = complete;
    }

    /**
     * Resolves every member and every own handler of one weave against one target.
     *
     * <p>The mangling suffix is derived once per weave, so two members of one weave that both have
     * to give way are renamed with the same digest.
     *
     * @param weave    the weave being dissolved; must not be {@code null}
     * @param target   the target's members; must not be {@code null}
     * @param reporter where refusals are reported; must not be {@code null}
     * @return the bindings, whether or not they are complete
     * @throws NullPointerException if any argument is {@code null}
     */
    @NotNull
    static MemberBindings of(@NotNull final WeaveClass weave,
                             @NotNull final TargetMembers target,
                             @NotNull final Reporter reporter) {
        Objects.requireNonNull(weave, "weave");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(reporter, "reporter");

        final Map<String, FieldRebind> fields = new LinkedHashMap<>();
        final Map<String, MethodRebind> methods = new LinkedHashMap<>();
        final Set<String> unfinalised = new LinkedHashSet<>();
        final String suffix = MANGLE_INFIX + digestOf(weave.binaryName());
        boolean complete = true;

        for (final WeaveMember member : weave.members()) {
            complete &= switch (member) {
                case WeaveMember.Shadowed shadowed ->
                        bindShadow(shadowed, target, fields, methods, unfinalised, weave, reporter);
                case WeaveMember.Merged merged ->
                        bindMerged(merged, target, fields, methods, suffix, weave, reporter);
                case WeaveMember.Accessor ignored -> true;
                case WeaveMember.Invoker ignored -> true;
            };
        }
        // A handler is not a WeaveMember — it is described by its InjectorSpec, so that a handler
        // is declared in exactly one place. It is still a method of the weave class, and an instance
        // weave dissolves it along with everything else, so it needs a binding like any other.
        for (final HandlerRef handler : handlersOf(weave)) {
            complete &= bindHandler(handler, target, methods, weave, reporter);
        }
        return new MemberBindings(fields, methods, unfinalised, complete);
    }

    /**
     * Returns the handlers the weave declares in its own class, each once.
     *
     * <p>Several injections may name one handler, and a handler that lives in a shared helper class
     * is not this weave's to move, so the list is filtered by owner and deduplicated by name and
     * descriptor.
     *
     * @param weave the weave; must not be {@code null}
     * @return the handlers, in the order their injections were declared
     */
    @NotNull
    static List<HandlerRef> handlersOf(@NotNull final WeaveClass weave) {
        final Map<String, HandlerRef> distinct = new LinkedHashMap<>();
        for (final InjectorSpec spec : weave.injectors()) {
            final HandlerRef handler = spec.handler();
            if (weave.weaveType().equals(handler.owner())) {
                distinct.putIfAbsent(key(handler.name(), handler.type()), handler);
            }
        }
        return List.copyOf(distinct.values());
    }

    /**
     * Binds one handler, which has to keep the name its injection sites will call.
     *
     * <p>A collision with a member the target already declares is {@code AW1080}.
     *
     * @param handler  the handler to bind; must not be {@code null}
     * @param target   the target's members; must not be {@code null}
     * @param methods  the method bindings being built; must not be {@code null}
     * @param weave    the weave that declared it; must not be {@code null}
     * @param reporter where a refusal is reported; must not be {@code null}
     * @return whether the handler bound
     */
    private static boolean bindHandler(@NotNull final HandlerRef handler,
                                       @NotNull final TargetMembers target,
                                       @NotNull final Map<String, MethodRebind> methods,
                                       @NotNull final WeaveClass weave,
                                       @NotNull final Reporter reporter) {
        if (target.declaresMethod(handler.name(), handler.type())) {
            reporter.report(Diagnostic.builder(DiagnosticCode.MERGED_MEMBER_COLLIDES)
                    .message(weave.binaryName() + " merges its handler '" + handler.name()
                            + handler.type().displayDescriptor() + "' into "
                            + target.type().displayName() + ", which already declares it")
                    .remedy("rename the handler. A handler cannot be @Unique — the injection sites "
                            + "call it by name, so a renamed one would be called under a name that "
                            + "no longer exists")
                    .build());
            return false;
        }
        methods.put(key(handler.name(), handler.type()),
                new MethodRebind(handler.name(),
                        handlerOpcode(handler, target.isInterface()), target.isInterface()));
        return true;
    }

    /**
     * Returns the opcode a call to the dissolved handler must use.
     *
     * <p>Read from the handler's declared flags, which are the flags it will carry on the target: a
     * merged method is emitted with the flags it had in the weave class.
     *
     * @param handler     the handler; must not be {@code null}
     * @param isInterface whether the target is an interface
     * @return the invocation opcode
     */
    @Contract(pure = true)
    @NotNull
    private static Opcode handlerOpcode(@NotNull final HandlerRef handler,
                                        final boolean isInterface) {
        if (handler.isStatic()) {
            return Opcode.INVOKESTATIC;
        }
        if (handler.isPrivate()) {
            return Opcode.INVOKESPECIAL;
        }
        return isInterface ? Opcode.INVOKEINTERFACE : Opcode.INVOKEVIRTUAL;
    }

    /**
     * Binds one shadow to the member of the target it promises is there.
     *
     * <p>The promise is checked rather than believed. A field the target does not declare is
     * {@code AW1030} and a method is {@code AW1020}; a field it declares at another type is
     * {@code AW1031}. A method's descriptor has to match exactly, and the diagnostic carries the
     * descriptors of every method of that name so that a mistaken overload can be seen.
     *
     * <p>{@code mutable} is honoured only where there is something to honour: the field joins the
     * unfinalise set and {@code AW1033} is reported when the target really declares it final, and a
     * field that was never final costs nothing and says nothing.
     *
     * @param shadowed    the shadow to bind; must not be {@code null}
     * @param target      the target's members; must not be {@code null}
     * @param fields      the field bindings being built; must not be {@code null}
     * @param methods     the method bindings being built; must not be {@code null}
     * @param unfinalised the target fields to unfinalise, added to here; must not be {@code null}
     * @param weave       the weave that declared it; must not be {@code null}
     * @param reporter    where a refusal is reported; must not be {@code null}
     * @return whether the shadow bound
     */
    private static boolean bindShadow(@NotNull final WeaveMember.Shadowed shadowed,
                                      @NotNull final TargetMembers target,
                                      @NotNull final Map<String, FieldRebind> fields,
                                      @NotNull final Map<String, MethodRebind> methods,
                                      @NotNull final Set<String> unfinalised,
                                      @NotNull final WeaveClass weave,
                                      @NotNull final Reporter reporter) {
        if (shadowed.isField()) {
            final Optional<FieldModel> found = target.field(shadowed.targetName());
            if (found.isEmpty()) {
                reporter.report(Diagnostic.builder(DiagnosticCode.FIELD_NOT_FOUND)
                        .message(weave.binaryName() + " shadows a field '" + shadowed.targetName()
                                + "' that " + target.type().displayName() + " does not declare")
                        .remedy("a @Shadow declaration is a promise that the target has this "
                                + "member; check the name, or the target's version")
                        .build());
                return false;
            }
            final ClassDesc declared = (ClassDesc) shadowed.type();
            final ClassDesc actual = found.get().fieldTypeSymbol();
            if (!declared.equals(actual)) {
                reporter.report(Diagnostic.builder(DiagnosticCode.SHADOW_TYPE_MISMATCH)
                        .message(weave.binaryName() + " shadows '" + shadowed.targetName()
                                + "' as " + declared.displayName() + ", but the target's is "
                                + actual.displayName())
                        .build());
                return false;
            }
            final Set<AccessFlag> targetFlags = found.get().flags().flags();
            if (shadowed.mutable() && targetFlags.contains(AccessFlag.FINAL)) {
                unfinalised.add(shadowed.targetName());
                reporter.report(Diagnostic.builder(DiagnosticCode.SHADOW_REMOVES_FINAL)
                        .message(weave.binaryName() + " declares @Shadow(mutable = true) for '"
                                + shadowed.targetName() + "', so " + target.type().displayName()
                                + " is rewritten with that field no longer final")
                        .detail(targetFlags.contains(AccessFlag.STATIC)
                                ? "the field is static final: javac inlines a compile-time constant "
                                + "at every call site, so already-compiled readers keep the old "
                                + "value no matter what is written here"
                                : "every other writer of the field, including the target's own "
                                + "constructor, is unaffected — the flag is all that changes")
                        .remedy("nothing needs doing; drop mutable = true if the weave only reads "
                                + "the field, so that the target keeps the guarantee it declared")
                        .build());
            }
            fields.put(shadowed.name(), new FieldRebind(shadowed.targetName(),
                    targetFlags.contains(AccessFlag.STATIC), actual));
            return true;
        }

        final MethodTypeDesc descriptor = (MethodTypeDesc) shadowed.type();
        final Optional<MethodModel> found = target.method(shadowed.targetName(), descriptor);
        if (found.isEmpty()) {
            reporter.report(Diagnostic.builder(DiagnosticCode.METHOD_NOT_FOUND)
                    .message(weave.binaryName() + " shadows a method '" + shadowed.targetName()
                            + descriptor.displayDescriptor() + "' that "
                            + target.type().displayName() + " does not declare")
                    .details(target.methodsNamed(shadowed.targetName()).stream()
                            .map(candidate -> candidate.methodName().stringValue()
                                    + candidate.methodTypeSymbol().displayDescriptor())
                            .toList())
                    .remedy("the descriptor must match exactly; an inherited member is not a "
                            + "declared one, and resolving the hierarchy would mean loading "
                            + "classes from inside class loading")
                    .build());
            return false;
        }
        // R6: the opcode comes from the resolved member's flags, never from the call shape.
        methods.put(key(shadowed.name(), descriptor),
                new MethodRebind(shadowed.targetName(), target.invokeOpcodeFor(found.get()),
                        target.isInterface()));
        return true;
    }

    /**
     * Binds one merged member to the name it is to be emitted under.
     *
     * <p>A collision is decided against what the target declares: a field on its name alone, a
     * method on name and descriptor. Without {@code @Unique} it is {@code AW1080} and the member
     * does not bind; with it, the member takes the weave's digest as a suffix and {@code AW1094}
     * says so unless the declaration asked for silence. A free name is kept either way — a digest in
     * every stack trace of the woven class buys nothing where nothing collided.
     *
     * <p>The opcode is derived from the flags the weave declared, which are the flags the emitted
     * member will carry — unlike a shadow, whose opcode comes from the target's own declaration.
     *
     * @param merged   the member to bind; must not be {@code null}
     * @param target   the target's members; must not be {@code null}
     * @param fields   the field bindings being built; must not be {@code null}
     * @param methods  the method bindings being built; must not be {@code null}
     * @param suffix   the weave's mangling suffix; must not be {@code null}
     * @param weave    the weave that declared it; must not be {@code null}
     * @param reporter where a refusal is reported; must not be {@code null}
     * @return whether the member bound
     */
    private static boolean bindMerged(@NotNull final WeaveMember.Merged merged,
                                      @NotNull final TargetMembers target,
                                      @NotNull final Map<String, FieldRebind> fields,
                                      @NotNull final Map<String, MethodRebind> methods,
                                      @NotNull final String suffix,
                                      @NotNull final WeaveClass weave,
                                      @NotNull final Reporter reporter) {
        if (merged.isField() && !checkFieldShape(merged, target, weave, reporter)) {
            return false;
        }

        final boolean collides = merged.isField()
                ? target.declaresField(merged.name())
                : target.declaresMethod(merged.name(), (MethodTypeDesc) merged.type());

        if (collides && !merged.unique()) {
            reporter.report(Diagnostic.builder(DiagnosticCode.MERGED_MEMBER_COLLIDES)
                    .message(weave.binaryName() + " merges '" + merged.name() + "' into "
                            + target.type().displayName() + ", which already declares it")
                    .remedy("declare the member @Unique to have it renamed instead, or rename it "
                            + "yourself. Overwriting the target's own member is not an option: it "
                            + "would replace working code with an uninitialised copy")
                    .build());
            return false;
        }

        final String name = collides ? merged.name() + suffix : merged.name();
        if (collides && !merged.silent()) {
            reporter.report(Diagnostic.builder(DiagnosticCode.UNIQUE_MEMBER_MANGLED)
                    .message(weave.binaryName() + " renamed its @Unique member '" + merged.name()
                            + "' to '" + name + "' — " + target.type().displayName()
                            + " already declares that name")
                    .remedy("nothing needs doing; declare @Unique(silent = true) to stop saying "
                            + "so. The name appears in stack traces and profiles of the woven "
                            + "class, which is why it is worth hearing once")
                    .build());
        }

        if (merged.isField()) {
            fields.put(merged.name(), new FieldRebind(name,
                    merged.flags().contains(AccessFlag.STATIC), (ClassDesc) merged.type()));
        } else {
            final MethodTypeDesc descriptor = (MethodTypeDesc) merged.type();
            methods.put(key(merged.name(), descriptor),
                    new MethodRebind(name, opcodeFor(merged.flags(), target.isInterface()),
                            target.isInterface()));
        }
        return true;
    }

    /**
     * Checks that an instance field may be merged into a target of this shape.
     *
     * <p>A static field is neither shape's business and passes at once. An instance field into a
     * record is refused as {@code AW1088}. Into an enum it is warned about as {@code AW1089} and then
     * allowed: the field is real, but nothing here writes anything into it beyond its default, since
     * the enum's constants are built in the target's own {@code <clinit>}. An {@code @Inject} at the
     * enum constructor's {@code HEAD} does run once per constant and can still write the field, which
     * is what the reported remedy points a caller to.
     *
     * @param merged   the member being bound; must not be {@code null}
     * @param target   the target's members; must not be {@code null}
     * @param weave    the weave that declared it; must not be {@code null}
     * @param reporter where the refusal or the warning is reported; must not be {@code null}
     * @return whether the field may be merged
     */
    private static boolean checkFieldShape(@NotNull final WeaveMember.Merged merged,
                                           @NotNull final TargetMembers target,
                                           @NotNull final WeaveClass weave,
                                           @NotNull final Reporter reporter) {
        if (merged.flags().contains(AccessFlag.STATIC)) {
            return true;
        }
        if (target.isRecord()) {
            reporter.report(Diagnostic.builder(DiagnosticCode.MERGE_FIELD_INTO_RECORD)
                    .message(weave.binaryName() + " merges the instance field '" + merged.name()
                            + "' into the record " + target.type().displayName())
                    .remedy("a record's equals, hashCode, toString and accessors are all derived "
                            + "from its components, so a merged field is state that every one of "
                            + "them ignores. Declare the field static, or keep the state outside "
                            + "the record")
                    .build());
            return false;
        }
        if (target.isEnum()) {
            reporter.report(Diagnostic.builder(DiagnosticCode.MERGE_FIELD_INTO_ENUM)
                    .message(weave.binaryName() + " merges the instance field '" + merged.name()
                            + "' into the enum " + target.type().displayName()
                            + ", whose constants are already constructed in <clinit>")
                    .remedy("nothing needs doing if the default value is what you want; otherwise "
                            + "write the field from an @Inject at the enum constructor's HEAD, "
                            + "which is the only code that runs per constant")
                    .build());
        }
        return true;
    }

    /**
     * Returns the opcode a call to a merged method must use.
     *
     * @param flags       the flags the weave declared the method with; must not be {@code null}
     * @param isInterface whether the target is an interface
     * @return the invocation opcode
     */
    @Contract(pure = true)
    @NotNull
    private static Opcode opcodeFor(@NotNull final Set<AccessFlag> flags,
                                    final boolean isInterface) {
        if (flags.contains(AccessFlag.STATIC)) {
            return Opcode.INVOKESTATIC;
        }
        if (flags.contains(AccessFlag.PRIVATE)) {
            return Opcode.INVOKESPECIAL;
        }
        return isInterface ? Opcode.INVOKEINTERFACE : Opcode.INVOKEVIRTUAL;
    }

    /**
     * Reports whether every member and handler bound.
     *
     * <p>Incomplete bindings are not partially usable: {@link StructuralWeaver} stops rebuilding this
     * target rather than writing part of a weave's members, and every reason it could not bind has
     * already been reported.
     *
     * @return whether the weave may be applied
     */
    @Contract(pure = true)
    boolean isComplete() {
        return this.complete;
    }

    /**
     * Returns the target's fields that are to be rewritten without their final flag.
     *
     * @return the field names as the target declares them, empty when no shadow asked for mutability
     *         or none of the fields was final
     */
    @Contract(pure = true)
    @NotNull
    @Unmodifiable
    Set<String> unfinalisedFields() {
        return this.unfinalised;
    }

    /**
     * Looks up the binding for a field the weave declares.
     *
     * @param name the name the weave declares it under; must not be {@code null}
     * @return the binding, or {@code null} when nothing bound that name
     * @throws NullPointerException if {@code name} is {@code null}
     */
    @Contract(pure = true)
    @Nullable
    FieldRebind field(@NotNull final String name) {
        return this.fields.get(Objects.requireNonNull(name, "name"));
    }

    /**
     * Looks up the binding for a method the weave declares.
     *
     * @param name       the name the weave declares it under; must not be {@code null}
     * @param descriptor the declared descriptor; must not be {@code null}
     * @return the binding, or {@code null} when nothing bound that name and descriptor
     * @throws NullPointerException if either argument is {@code null}
     */
    @Contract(pure = true)
    @Nullable
    MethodRebind method(@NotNull final String name, @NotNull final MethodTypeDesc descriptor) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(descriptor, "descriptor");
        return this.methods.get(key(name, descriptor));
    }

    /**
     * Returns the name a merged member is to be emitted under.
     *
     * @param member the merged member; must not be {@code null}
     * @return the bound name, mangled where the declaration had to give way, or the declared name
     *         when nothing bound it
     * @throws NullPointerException if {@code member} is {@code null}
     */
    @Contract(pure = true)
    @NotNull
    String nameOf(@NotNull final WeaveMember.Merged member) {
        Objects.requireNonNull(member, "member");
        if (member.isField()) {
            final FieldRebind rebind = this.fields.get(member.name());
            return rebind == null ? member.name() : rebind.targetName();
        }
        final MethodRebind rebind = this.methods.get(
                key(member.name(), (MethodTypeDesc) member.type()));
        return rebind == null ? member.name() : rebind.targetName();
    }

    /**
     * Builds the key a method binding is held under.
     *
     * @param name       the declared method name
     * @param descriptor the declared descriptor
     * @return the two concatenated
     */
    @Contract(pure = true)
    @NotNull
    private static String key(@NotNull final String name,
                              @NotNull final MethodTypeDesc descriptor) {
        return name + descriptor.descriptorString();
    }

    /**
     * Derives a weave's mangling suffix from its name.
     *
     * <p>The suffix depends on nothing but the weave's binary name, so the same inputs produce the
     * same bytes on every run. A counter would be shorter and would make the emitted name depend on
     * the order the weaves happened to be processed in.
     *
     * @param binaryName the weave's binary name; must not be {@code null}
     * @return four bytes of its SHA-256 digest, as eight lower-case hexadecimal characters
     * @throws IllegalStateException if the JVM has no SHA-256, which the platform requires of it
     */
    @Contract(pure = true)
    @NotNull
    private static String digestOf(@NotNull final String binaryName) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(binaryName.getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder(MANGLE_LENGTH);
            for (int i = 0; i < MANGLE_LENGTH / 2; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (final NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required of every JVM", impossible);
        }
    }

    /**
     * Returns the size of each map and whether binding was complete.
     *
     * @return a description for a debugger or a log line
     */
    @Override
    @NotNull
    public String toString() {
        return "MemberBindings[fields=" + this.fields.size() + ", methods=" + this.methods.size()
                + ", complete=" + this.complete + ']';
    }

    /**
     * What a field of the weave resolves to on the target.
     *
     * @param targetName the field's name on the target, which is the shadowed member's name or the
     *                   merged member's own, mangled where it had to give way
     * @param isStatic   whether the field is static there
     * @param type       the field's type there
     * @author Erik Pförtner
     * @since 0.1.0
     */
    record FieldRebind(@NotNull String targetName, boolean isStatic, @NotNull ClassDesc type) {

        /**
         * Checks the two references.
         *
         * @throws NullPointerException if {@code targetName} or {@code type} is {@code null}
         */
        FieldRebind {
            Objects.requireNonNull(targetName, "targetName");
            Objects.requireNonNull(type, "type");
        }
    }

    /**
     * What a method or handler of the weave resolves to on the target.
     *
     * @param targetName  the method's name on the target
     * @param opcode      the opcode a call to it must carry, resolved from the flags it has there
     * @param isInterface whether the target is an interface, which the emitted call repeats
     * @author Erik Pförtner
     * @since 0.1.0
     */
    record MethodRebind(@NotNull String targetName, @NotNull Opcode opcode, boolean isInterface) {

        /**
         * Checks the two references.
         *
         * @throws NullPointerException if {@code targetName} or {@code opcode} is {@code null}
         */
        MethodRebind {
            Objects.requireNonNull(targetName, "targetName");
            Objects.requireNonNull(opcode, "opcode");
        }
    }
}
