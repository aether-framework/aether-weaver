package de.splatgames.aether.weaver.engine.merge;

import de.splatgames.aether.weaver.api.Weave;
import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import de.splatgames.aether.weaver.api.model.HandlerRef;
import de.splatgames.aether.weaver.api.spi.Reporter;
import de.splatgames.aether.weaver.engine.internal.transform.ClassRemapper;
import de.splatgames.aether.weaver.engine.model.WeaveClass;
import de.splatgames.aether.weaver.engine.model.WeaveMember;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.classfile.AccessFlags;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassElement;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeModel;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.FieldElement;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodElement;
import java.lang.classfile.MethodModel;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Dissolves instance weaves into one target: copies the members they declare, generates the
 * accessors and invokers they ask for, and rebinds every reference the moved code makes to the
 * weave it came from.
 *
 * <p>The target is rebuilt rather than edited: its own elements are copied into a fresh class and
 * the weaves' contributions follow them.
 *
 * <p>Binding is all or nothing within one rebuild. Every weave is resolved against the target before
 * a byte is written, and one weave that cannot be bound stops the rebuild before it starts writing,
 * rather than producing a target that gained some of a weave's members and not the rest; what happens
 * to the class after that is for the caller to decide. The checks an accessor or an invoker needs run
 * later, while the class is being written, and cost only their own member.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class StructuralWeaver {

    /** Where a weave's own class file comes from, which is the only place a merged body exists. */
    private final WeaveBytes bytes;

    /**
     * Creates a weaver reading weave classes from the given source.
     *
     * @param bytes where a weave class's own bytes come from; must not be {@code null}
     * @throws NullPointerException if {@code bytes} is {@code null}
     */
    public StructuralWeaver(@NotNull final WeaveBytes bytes) {
        this.bytes = Objects.requireNonNull(bytes, "bytes");
    }

    /**
     * Applies every weave that dissolves into the given class.
     *
     * <p>{@code null} answers a refusal and having nothing to emit alike, and the two cannot be told
     * apart from the return value; a refusal has reported at least one diagnostic first. Nothing to
     * emit is an ordinary outcome rather than a degenerate one: a weave admitted only because it asks
     * for a mutable shadow adds no member, and has nothing left to change once the target's field
     * turns out not to be final.
     *
     * @param model    the class to weave, as it stands; must not be {@code null}
     * @param weaves   the weaves that dissolve into it; must not be {@code null}
     * @param reporter where refusals are reported; must not be {@code null}
     * @return the rebuilt class, or {@code null} when a weave was refused or there was nothing to
     *         emit
     * @throws NullPointerException if any argument is {@code null}
     */
    public byte @Nullable [] apply(@NotNull final ClassModel model,
                                   @NotNull final List<WeaveClass> weaves,
                                   @NotNull final Reporter reporter) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(weaves, "weaves");
        Objects.requireNonNull(reporter, "reporter");

        final TargetMembers target = new TargetMembers(model);
        final List<Contribution> contributions = prepare(weaves, target, reporter);
        if (contributions == null || contributions.isEmpty()) {
            return null;
        }

        final Set<String> unfinalise = contributions.stream()
                .flatMap(contribution -> contribution.bindings().unfinalisedFields().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        // A weave admitted only because it asks for a mutable shadow changes nothing when the target
        // field was never final. Rebuilding the class for that would spend a full parse-and-emit to
        // produce the bytes it started with.
        if (unfinalise.isEmpty() && contributions.stream().noneMatch(
                contribution -> emitsMembers(contribution.weave()))) {
            return null;
        }

        return ClassFile.of().build(model.thisClass().asSymbol(), builder -> {
            // Everything the target had, first and unchanged — except a field whose final flag a
            // @Shadow(mutable = true) asked to drop. A merged member that collided was already
            // refused, so nothing here can be overwritten by what follows.
            for (final ClassElement element : model) {
                if (element instanceof FieldModel field
                        && unfinalise.contains(field.fieldName().stringValue())) {
                    unfinalise(builder, field);
                } else {
                    builder.with(element);
                }
            }
            for (final Contribution contribution : contributions) {
                emit(builder, contribution, target, reporter);
            }
        });
    }

    /**
     * Writes a field of the target into the rebuilt class without its final flag.
     *
     * <p>The flags are set first and every other element of the field copied after, skipping the
     * {@link AccessFlags}: it reaches the builder as an element like any other, and copying it would
     * put back exactly the flag being removed.
     *
     * @param builder the class being rebuilt; must not be {@code null}
     * @param field   the target's own field; must not be {@code null}
     */
    private static void unfinalise(@NotNull final ClassBuilder builder,
                                   @NotNull final FieldModel field) {
        final int flags = field.flags().flagsMask() & ~ClassFile.ACC_FINAL;
        builder.withField(field.fieldName().stringValue(), field.fieldTypeSymbol(),
                fieldBuilder -> {
                    fieldBuilder.withFlags(flags);
                    for (final FieldElement element : field) {
                        if (!(element instanceof AccessFlags)) {
                            fieldBuilder.with(element);
                        }
                    }
                });
    }

    /**
     * Resolves every weave against the target, or refuses them all.
     *
     * <p>A weave that is not an instance weave, or that would change nothing structurally, is passed
     * over without prejudice — it may still have injections, which are applied elsewhere. A weave that
     * has a body to move and no class file to move it from is {@code AW1096}; a weave whose members do
     * not bind has already said why through {@code MemberBindings}.
     *
     * <p>The loop runs to the end after the first failure, so one run reports every weave that is
     * wrong rather than the first.
     *
     * @param weaves   the weaves planned for this target; must not be {@code null}
     * @param target   the target's members; must not be {@code null}
     * @param reporter where refusals are reported; must not be {@code null}
     * @return the contributions, in the order the weaves were given, or {@code null} when any of them
     *         was refused
     */
    @Nullable
    private List<Contribution> prepare(@NotNull final List<WeaveClass> weaves,
                                       @NotNull final TargetMembers target,
                                       @NotNull final Reporter reporter) {
        final List<Contribution> contributions = new java.util.ArrayList<>(weaves.size());
        boolean usable = true;

        for (final WeaveClass weave : weaves) {
            if (weave.kind() != Weave.Kind.INSTANCE || !hasStructuralEffect(weave)) {
                continue;
            }
            final byte[] classFile = this.bytes.bytesOf(weave.weaveType());
            if (classFile == null && needsBodies(weave)) {
                reporter.report(Diagnostic.builder(DiagnosticCode.WEAVE_BYTES_UNAVAILABLE)
                        .message(weave.binaryName() + " merges members into "
                                + target.type().displayName()
                                + ", but its class file was not supplied")
                        .remedy("give the weaver a byte source with WeaverBuilder.weaveBytes(…): a "
                                + "method's body exists only in the class file, and the parsed "
                                + "model deliberately does not carry one")
                        .build());
                usable = false;
                continue;
            }
            final MemberBindings bindings = MemberBindings.of(weave, target, reporter);
            if (!bindings.isComplete()) {
                usable = false;
                continue;
            }
            contributions.add(new Contribution(weave, bindings,
                    classFile == null ? null : ClassFile.of().parse(classFile)));
        }
        return usable ? List.copyOf(contributions) : null;
    }

    /**
     * Reports whether this weave would change the target at all.
     *
     * <p>Either it emits a member, or it asks for mutability on a field shadow, which changes the
     * target without adding to it. Whether that field is final at all is settled later, so a weave
     * admitted here can still turn out to have nothing to do.
     *
     * @param weave the weave; must not be {@code null}
     * @return whether applying it would produce different bytes
     */
    private static boolean hasStructuralEffect(@NotNull final WeaveClass weave) {
        return emitsMembers(weave)
                || weave.members().stream().anyMatch(member ->
                member instanceof WeaveMember.Shadowed shadowed
                        && shadowed.mutable() && shadowed.isField());
    }

    /**
     * Reports whether this weave puts a new member on the target.
     *
     * <p>Any declared member other than a shadow, or a handler the weave declares itself. A handler
     * declared in another class is not the weave's to move.
     *
     * @param weave the weave; must not be {@code null}
     * @return whether the target gains a member
     */
    private static boolean emitsMembers(@NotNull final WeaveClass weave) {
        return weave.members().stream().anyMatch(member -> !(member instanceof WeaveMember.Shadowed))
                || !MemberBindings.handlersOf(weave).isEmpty();
    }

    /**
     * Reports whether anything of this weave has to be copied out of its class file.
     *
     * <p>Only a merged member and a handler have a body. An accessor and an invoker are generated from
     * their declaration, so a weave of nothing but those needs no class file of its own — which keeps
     * a host that only wants accessors from having to supply a byte source.
     *
     * @param weave the weave; must not be {@code null}
     * @return whether the weave's class file is required
     */
    private static boolean needsBodies(@NotNull final WeaveClass weave) {
        return weave.members().stream().anyMatch(member -> member instanceof WeaveMember.Merged)
                || !MemberBindings.handlersOf(weave).isEmpty();
    }

    /**
     * Writes one weave's contributions into the class being rebuilt.
     *
     * <p>Declared members first, in the order the weave declared them, then its handlers.
     *
     * @param builder      the class being rebuilt; must not be {@code null}
     * @param contribution the weave, its bindings and its parsed class file; must not be {@code null}
     * @param target       the target's members; must not be {@code null}
     * @param reporter     where a generated member's refusal is reported; must not be {@code null}
     */
    private static void emit(@NotNull final ClassBuilder builder,
                             @NotNull final Contribution contribution,
                             @NotNull final TargetMembers target,
                             @NotNull final Reporter reporter) {
        final WeaveClass weave = contribution.weave();
        for (final WeaveMember member : weave.members()) {
            switch (member) {
                case WeaveMember.Merged merged -> copy(builder, contribution, merged, target);
                case WeaveMember.Accessor accessor ->
                        GeneratedMembers.accessor(builder, accessor, target, weave, reporter);
                case WeaveMember.Invoker invoker ->
                        GeneratedMembers.invoker(builder, invoker, target, weave, reporter);
                case WeaveMember.Shadowed ignored -> {
                    // Never copied: the target already has it, and copying would overwrite a
                    // working member with an uninitialised one.
                }
            }
        }
        // The handlers last, and by the same route: a merged handler is an ordinary method of the
        // target that happens to be called from an injection site.
        for (final HandlerRef handler : MemberBindings.handlersOf(weave)) {
            copyMerged(builder, contribution, handler.name(), handler.type(), target);
        }
    }

    /**
     * Copies one merged member out of the weave's class file.
     *
     * <p>A contribution without a class file declares no merged member, since {@code prepare} refuses
     * that pair as {@code AW1096}. A member the class file turns out not to declare is passed over
     * in silence.
     *
     * @param builder      the class being rebuilt; must not be {@code null}
     * @param contribution the weave, its bindings and its parsed class file; must not be {@code null}
     * @param merged       the member to copy; must not be {@code null}
     * @param target       the target's members; must not be {@code null}
     */
    private static void copy(@NotNull final ClassBuilder builder,
                             @NotNull final Contribution contribution,
                             @NotNull final WeaveMember.Merged merged,
                             @NotNull final TargetMembers target) {
        final ClassModel source = contribution.source();
        if (source == null) {
            return;
        }
        final String name = contribution.bindings().nameOf(merged);
        if (merged.isField()) {
            source.fields().stream()
                    .filter(field -> field.fieldName().equalsString(merged.name()))
                    .findFirst()
                    .ifPresent(field -> copyField(builder, field, name, merged));
            return;
        }

        copyMerged(builder, contribution, merged.name(), (MethodTypeDesc) merged.type(), target);
    }

    /**
     * Copies one method of the weave class into the target, rebinding what it says on the way.
     *
     * <p>The two transforms are composed in this order and not the other: {@link MergedBodyTransform}
     * recognises the weave's own members by their owner still being the weave type, and only then
     * does the remapper rewrite that type into the target — in a cast, a {@code new}, a descriptor,
     * a class constant and a local variable's debug type alike, so that what lands on the target no
     * longer mentions the weave.
     *
     * <p>Every method reaching this call already has a binding: it is either a
     * {@link WeaveMember.Merged} member or one of the weave's own handlers, and both are bound by
     * {@link MemberBindings#of} before {@code prepare} admits the contribution — a failure in either
     * makes {@code prepare} return {@code null} first. The declared name is used only when the
     * bindings hold no entry for it, a case neither caller of this method produces.
     *
     * @param builder      the class being rebuilt; must not be {@code null}
     * @param contribution the weave, its bindings and its parsed class file; must not be {@code null}
     * @param declaredName the method's name in the weave class; must not be {@code null}
     * @param descriptor   the method's descriptor; must not be {@code null}
     * @param target       the target's members; must not be {@code null}
     */
    private static void copyMerged(@NotNull final ClassBuilder builder,
                                   @NotNull final Contribution contribution,
                                   @NotNull final String declaredName,
                                   @NotNull final MethodTypeDesc descriptor,
                                   @NotNull final TargetMembers target) {
        final ClassModel source = contribution.source();
        if (source == null) {
            return;
        }
        final MemberBindings.MethodRebind rebind =
                contribution.bindings().method(declaredName, descriptor);
        final String name = rebind == null ? declaredName : rebind.targetName();
        final ClassDesc weaveType = contribution.weave().weaveType();
        final CodeTransform rebindBody = MergedBodyTransform
                .of(weaveType, target.type(), contribution.bindings())
                .andThen(ClassRemapper.of(Map.of(weaveType, target.type())).asCodeTransform());

        source.methods().stream()
                .filter(method -> method.methodName().equalsString(declaredName)
                        && descriptor.equals(method.methodTypeSymbol()))
                .findFirst()
                .ifPresent(method -> copyMethod(builder, method, name, descriptor, rebindBody));
    }

    /**
     * Emits a merged field on the target.
     *
     * <p>The type comes from the parsed declaration and the flags from the weave's own class file,
     * and nothing else of that field is carried over.
     *
     * @param builder the class being rebuilt; must not be {@code null}
     * @param field   the field as the weave class declares it; must not be {@code null}
     * @param name    the name to emit it under, mangled when {@code @Unique} had to give way; must not
     *                be {@code null}
     * @param merged  the parsed declaration; must not be {@code null}
     */
    private static void copyField(@NotNull final ClassBuilder builder,
                                  @NotNull final FieldModel field,
                                  @NotNull final String name,
                                  @NotNull final WeaveMember.Merged merged) {
        builder.withField(name, (ClassDesc) merged.type(), fieldBuilder -> {
            // A ConstantValue is not carried over: it would make a merged field behave unlike every
            // other field the weave author wrote, initialising before any constructor runs. The
            // parser reports AW1093 when it sees one, so the drop has already been announced.
            fieldBuilder.withFlags(field.flags().flagsMask());
        });
    }

    /**
     * Emits a merged method on the target, element by element.
     *
     * <p>Only the code goes through the transform; every other element of the method — its checked
     * exceptions, its signature, its annotations — is copied across untouched.
     *
     * @param builder    the class being rebuilt; must not be {@code null}
     * @param method     the method as the weave class declares it; must not be {@code null}
     * @param name       the name to emit it under; must not be {@code null}
     * @param descriptor the descriptor to emit it under; must not be {@code null}
     * @param rebind     the transform applied to its code; must not be {@code null}
     */
    private static void copyMethod(@NotNull final ClassBuilder builder,
                                   @NotNull final MethodModel method,
                                   @NotNull final String name,
                                   @NotNull final MethodTypeDesc descriptor,
                                   @NotNull final CodeTransform rebind) {
        builder.withMethod(name, descriptor, method.flags().flagsMask(), methodBuilder -> {
            for (final MethodElement element : method) {
                if (element instanceof CodeModel code) {
                    methodBuilder.transformCode(code, rebind);
                } else {
                    methodBuilder.with(element);
                }
            }
        });
    }

    /**
     * Returns a fixed description of this weaver.
     *
     * @return the constant {@code StructuralWeaver}
     */
    @Override
    @NotNull
    public String toString() {
        return "StructuralWeaver";
    }

    /**
     * One weave that has been resolved against the target and is ready to be written.
     *
     * @param weave    the weave being dissolved
     * @param bindings what each of its members resolved to on the target
     * @param source   its parsed class file, or {@code null} when it has no body to move
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private record Contribution(@NotNull WeaveClass weave,
                                @NotNull MemberBindings bindings,
                                @Nullable ClassModel source) {
    }
}
