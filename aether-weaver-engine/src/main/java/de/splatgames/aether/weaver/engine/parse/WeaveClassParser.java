package de.splatgames.aether.weaver.engine.parse;

import de.splatgames.aether.weaver.api.Accessor;
import de.splatgames.aether.weaver.api.At;
import de.splatgames.aether.weaver.api.Group;
import de.splatgames.aether.weaver.api.Inject;
import de.splatgames.aether.weaver.api.Invoker;
import de.splatgames.aether.weaver.api.Local;
import de.splatgames.aether.weaver.api.Phase;
import de.splatgames.aether.weaver.api.Point;
import de.splatgames.aether.weaver.api.Redirect;
import de.splatgames.aether.weaver.api.Wrap;
import de.splatgames.aether.weaver.api.Require;
import de.splatgames.aether.weaver.api.Result;
import de.splatgames.aether.weaver.api.Shadow;
import de.splatgames.aether.weaver.api.Unique;
import de.splatgames.aether.weaver.api.Weave;
import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import de.splatgames.aether.weaver.api.diagnostic.Location;
import de.splatgames.aether.weaver.api.diagnostic.Severity;
import de.splatgames.aether.weaver.api.select.MemberKind;
import de.splatgames.aether.weaver.api.select.MemberSelector;
import de.splatgames.aether.weaver.api.select.SelectorSyntaxException;
import de.splatgames.aether.weaver.api.spi.DiagnosticListener;
import de.splatgames.aether.weaver.api.model.GroupSpec;
import de.splatgames.aether.weaver.api.model.HandlerRef;
import de.splatgames.aether.weaver.api.model.InjectorKind;
import de.splatgames.aether.weaver.api.model.InjectorSpec;
import de.splatgames.aether.weaver.api.model.LocalSpec;
import de.splatgames.aether.weaver.api.model.Origin;
import de.splatgames.aether.weaver.api.model.PointSpec;
import de.splatgames.aether.weaver.api.model.SliceSpec;
import de.splatgames.aether.weaver.engine.model.TargetRef;
import de.splatgames.aether.weaver.engine.model.WeaveClass;
import de.splatgames.aether.weaver.engine.model.WeaveMember;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.classfile.Annotation;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.RuntimeVisibleParameterAnnotationsAttribute;
import java.lang.classfile.attribute.SignatureAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Builds the engine's model of a weave out of the weave's class file.
 *
 * <p>Every annotation is read from the class file's attributes through {@code Annotations}, so a
 * weave that is not on the parser's own classpath is modelled in full, and neither the weave nor
 * its target is ever loaded.
 *
 * <p>A class file records only the annotation elements the source wrote, so every default the
 * annotations declare is written out a second time here. The two have to agree: a default
 * changed on an annotation and not here is a value the compiler never records and the parser
 * never substitutes.
 *
 * <p>Complaints have one channel, the {@link DiagnosticListener} given to the constructor, and
 * an error changes nothing about how far the parse goes. The whole class is read either way and
 * the result is discarded at the end, so one run reports every mistake in a weave rather than
 * the first.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class WeaveClassParser {

    /**
     * The points at which the target has already computed the value a returnable callback reports.
     *
     * <p>Names rather than {@link Point} constants, because a {@link PointSpec} carries its point as
     * a string so that a custom point can be one. The membership test compares that string, so a
     * custom point spelled exactly {@code RETURN} or {@code TAIL} is value-bearing all the same.
     */
    private static final Set<String> VALUE_BEARING =
            Set.of(Point.RETURN.name(), Point.TAIL.name());

    /** The internal name matched against the owner of a {@code value()} call in a handler's code. */
    private static final String RETURNABLE_CALLBACK =
            "de/splatgames/aether/weaver/api/callback/ReturnableCallback";

    /** The prefixes stripped from an {@code @Invoker} declaration to infer the target method's name. */
    private static final List<String> INVOKER_PREFIXES = List.of("call", "invoke");

    /** The prefixes stripped from an {@code @Accessor} declaration to infer the target field's name. */
    private static final List<String> ACCESSOR_PREFIXES = List.of("get", "set", "is");

    /**
     * The name-and-descriptor pairs whose merge is warned about as {@code AW1083}.
     *
     * <p>Matched whole, so an overload sharing only the name is left alone. {@code main} is in the
     * set although {@link Object} does not declare it: what these have in common is that something
     * outside the target's own code calls them.
     */
    private static final Set<String> OBJECT_METHODS = Set.of(
            "toString()Ljava/lang/String;",
            "equals(Ljava/lang/Object;)Z",
            "hashCode()I",
            "main([Ljava/lang/String;)V");

    /** The audience for every diagnostic this parser produces. */
    private final DiagnosticListener listener;

    /**
     * Creates a parser reporting to the given listener.
     *
     * @param listener where every diagnostic goes; must not be {@code null}
     * @throws NullPointerException if {@code listener} is {@code null}
     */
    public WeaveClassParser(@NotNull final DiagnosticListener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Models one class, if it is a weave.
     *
     * <p>A class carrying no {@code @Weave} is not a weave and not a mistake: nothing is reported
     * and the result is empty, because most classes a discovery run is offered are ordinary ones.
     *
     * <p>Fields are read before methods and both in declaration order, so the members, the injectors
     * and the diagnostics all come out in the order the source declared them.
     *
     * <p>A declaration the parser judges wrong is reported; a value the model's own invariants
     * refuse is not. An injector whose {@code allow} is below its {@code require} and not {@code 0}
     * reaches a record constructor and comes back out as an {@link IllegalArgumentException} as soon
     * as the method is read, before any guard can intervene. A {@code @Group} whose maximum is below
     * its minimum and not {@code 0} reaches the same constructor only if the rest of the class still
     * parses to a usable weave: groups are read last, after the early return for a failed report or
     * an empty target list, so a {@code @Group} that would otherwise throw is never constructed when
     * the class already failed or named no target. An {@code @At} with an ordinal below {@code -1} is
     * refused instead by {@link PointSpec.Builder#ordinal(int)}.
     *
     * @param model  the class to read; must not be {@code null}
     * @param origin where the class was found, named in a detail line of every diagnostic reported
     *               for it; must not be {@code null}
     * @return the modelled weave, or empty when the class is not a weave, when it named no usable
     *         target, or when any diagnostic reported for it was an error
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalArgumentException if a declaration carries a value the model refuses outright
     */
    @Contract(pure = true)
    @NotNull
    public Optional<WeaveClass> parse(@NotNull final ClassModel model,
                                      @NotNull final Origin origin) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(origin, "origin");

        final List<Annotation> onClass = Annotations.on(model);
        final Annotation weave = Annotations.find(onClass, Weave.class);
        if (weave == null) {
            return Optional.empty();
        }

        final ClassDesc weaveType = model.thisClass().asSymbol();
        final Report report = new Report(this.listener, binaryNameOf(weaveType), origin);

        checkClassShape(model, report);

        final Weave.Kind kind = Annotations.enumOr(weave, "kind", Weave.Kind.class,
                Weave.Kind.INSTANCE);
        final List<TargetRef> targets = readTargets(weave, report);

        final List<WeaveMember> members = new ArrayList<>();
        final List<InjectorSpec> injectors = new ArrayList<>();
        for (final FieldModel field : model.fields()) {
            readField(field, kind, members, report);
        }
        for (final MethodModel method : model.methods()) {
            readMethod(method, weaveType, kind, members, injectors, report);
        }

        if (report.failed || targets.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new WeaveClass(
                weaveType,
                targets,
                kind,
                Annotations.intOr(weave, "priority", 0),
                Annotations.enumOr(weave, "require", Require.class, Require.REQUIRED),
                Annotations.enumOr(weave, "phase", Phase.class, Phase.DEFAULT),
                new LinkedHashSet<>(Annotations.strings(weave, "tags")),
                readGroups(onClass),
                members,
                injectors,
                origin));
    }

    // ---------------------------------------------------------------------------------------
    // Class level
    // ---------------------------------------------------------------------------------------

    /**
     * Reports the shapes a weave class may not have.
     *
     * <p>{@code AW1006} for a superclass, {@code AW1084} for an interface and {@code AW1007} for a
     * type parameter are errors, so a class with any of them is discarded. Only the first interface
     * is named.
     *
     * <p>A weave that is not final is {@code AW1008}, a warning, and is looked for only where the
     * modifier could have been written.
     *
     * @param model  the weave class
     * @param report where the diagnostics go
     */
    private static void checkClassShape(final ClassModel model, final Report report) {
        final ClassDesc superclass = model.superclass()
                .map(entry -> entry.asSymbol())
                .orElse(ConstantDescs.CD_Object);
        if (!superclass.equals(ConstantDescs.CD_Object)) {
            report.error(DiagnosticCode.WEAVE_HAS_SUPERCLASS,
                    "weave " + report.weaveClass + " extends " + binaryNameOf(superclass)
                            + "; a weave's members are copied into its target, and the target "
                            + "already has a superclass of its own",
                    "declare the weave to extend Object, and reach the superclass's members "
                            + "through @Shadow instead");
        }
        if (!model.interfaces().isEmpty()) {
            report.error(DiagnosticCode.WEAVE_IMPLEMENTS_INTERFACE,
                    "weave " + report.weaveClass + " implements "
                            + binaryNameOf(model.interfaces().getFirst().asSymbol())
                            + "; adding an interface to a target is not a 0.1.0 capability",
                    null);
        }
        final boolean generic = model.findAttribute(Attributes.signature())
                .map(SignatureAttribute::asClassSignature)
                .map(signature -> !signature.typeParameters().isEmpty())
                .orElse(false);
        if (generic) {
            report.error(DiagnosticCode.WEAVE_IS_GENERIC,
                    "weave " + report.weaveClass + " is generic; its members are copied verbatim "
                            + "into the target, where a type variable has nothing to bind to",
                    null);
        }
        // An abstract class cannot be final, so warning about it would be advice that cannot be
        // followed. A weave declares abstract members when it uses @Accessor or @Invoker in their
        // abstract spelling, and that is a legitimate shape.
        final boolean canBeFinal = !model.flags().has(AccessFlag.ABSTRACT)
                && !model.flags().has(AccessFlag.INTERFACE);
        if (canBeFinal && !model.flags().has(AccessFlag.FINAL)) {
            report.warn(DiagnosticCode.WEAVE_NOT_FINAL,
                    "weave " + report.weaveClass + " is not final; a weave class is never "
                            + "subclassed and never instantiated",
                    "declare it final");
        }
    }

    /**
     * Reads the targets from {@code @Weave}, in whichever of the two spellings was used.
     *
     * <p>The spellings are exclusive rather than additive: both is {@code AW1002} and neither is
     * {@code AW1001}, and either leaves the weave with no targets, which is itself enough to discard
     * it.
     *
     * <p>A name that is not a usable binary class name is {@code AW1004} and is skipped rather than
     * fatal to the loop, so a weave naming three unusable targets is told about all three.
     *
     * @param weave  the {@code @Weave} annotation
     * @param report where the diagnostics go
     * @return the targets, class literals before names, or an empty list when the declaration was
     *         refused
     */
    private static List<TargetRef> readTargets(final Annotation weave, final Report report) {
        final List<ClassDesc> literals = Annotations.classes(weave, "value");
        final List<String> names = Annotations.strings(weave, "targets");

        if (!literals.isEmpty() && !names.isEmpty()) {
            report.error(DiagnosticCode.WEAVE_DUPLICATE_TARGET_DECLARATION,
                    "weave " + report.weaveClass + " declares targets both as class literals and "
                            + "as names; which of the two is authoritative would be a guess",
                    "keep the class literals and delete targets=, or the other way round");
            return List.of();
        }
        if (literals.isEmpty() && names.isEmpty()) {
            report.error(DiagnosticCode.WEAVE_NO_TARGETS,
                    "weave " + report.weaveClass + " declares no target, so it can never apply",
                    "name the class it modifies: @Weave(TheTarget.class)");
            return List.of();
        }

        final List<TargetRef> targets = new ArrayList<>(literals.size() + names.size());
        for (final ClassDesc literal : literals) {
            targets.add(TargetRef.ofClassLiteral(literal));
        }
        for (final String name : names) {
            final ClassDesc type;
            try {
                type = ClassDesc.of(name);
            } catch (final IllegalArgumentException e) {
                report.error(DiagnosticCode.WEAVE_TARGET_UNRESOLVABLE,
                        "weave " + report.weaveClass + " names the target \"" + name
                                + "\", which is not a usable binary class name",
                        "a nested class is written with a dollar sign, as in "
                                + "\"com.acme.Outer$Inner\"");
                continue;
            }
            targets.add(TargetRef.ofName(type));
        }
        return targets;
    }

    /**
     * Reads the {@code @Group} declarations, written once or repeated.
     *
     * <p>Nothing is checked here beyond what {@link GroupSpec} checks of itself, because a group is
     * about a total and no total exists until the injectors have been matched against a target. A
     * pair of bounds {@link GroupSpec} refuses throws rather than being reported.
     *
     * @param onClass the weave class's annotations
     * @return the groups in declaration order
     */
    private static List<GroupSpec> readGroups(final List<Annotation> onClass) {
        final List<GroupSpec> groups = new ArrayList<>();
        for (final Annotation group : Annotations.findRepeated(onClass, Group.class,
                Group.Container.class)) {
            groups.add(new GroupSpec(
                    Annotations.stringOr(group, "name", ""),
                    Annotations.intOr(group, "min", 1),
                    Annotations.intOr(group, "max", 0)));
        }
        return groups;
    }

    // ---------------------------------------------------------------------------------------
    // Members
    // ---------------------------------------------------------------------------------------

    /**
     * Classifies one field of the weave and models it, unless the declaration is refused.
     *
     * <p>{@code @Shadow} is looked for first and wins outright: a field carrying both it and
     * {@code @Unique} is a shadow, and the {@code @Unique} is never read. Everything else is merged,
     * with or without an annotation of its own.
     *
     * <p>An initialiser is recognised as a {@code ConstantValue} attribute and by nothing else — no
     * access flag is examined — and is {@code AW1032} on a shadow or {@code AW1093} on a merged
     * field. Neither stops the field from being modelled.
     *
     * @param field   the field being read
     * @param kind    the weave's kind, which decides whether a merge-only annotation means anything
     * @param members collects the member this field becomes
     * @param report  where the diagnostics go
     */
    private static void readField(final FieldModel field,
                                  final Weave.Kind kind,
                                  final List<WeaveMember> members,
                                  final Report report) {
        final List<Annotation> annotations = Annotations.on(field);
        final String name = field.fieldName().stringValue();
        final ClassDesc type = field.fieldTypeSymbol();
        final Set<AccessFlag> flags = field.flags().flags();
        // An initialiser survives into the class file only as a ConstantValue, and only for a
        // static final field of constant type. Every other initialiser javac compiles into <init>
        // or <clinit>, which a weave may not declare — so AW1081/AW1082 catch those, and this
        // catches the one shape that reaches us silently.
        final boolean initialised = field.findAttribute(Attributes.constantValue()).isPresent();

        final Annotation shadow = Annotations.find(annotations, Shadow.class);
        if (shadow != null) {
            if (reportStaticWeaveMember(DiagnosticCode.SHADOW_IN_STATIC_WEAVE, "@Shadow",
                    name, kind, report)) {
                return;
            }
            if (initialised) {
                report.warn(DiagnosticCode.SHADOW_FIELD_INITIALISER_IGNORED,
                        "@Shadow field '" + name + "' in " + report.weaveClass + " has an "
                                + "initialiser; a shadow declares what the target already has, so "
                                + "the value is never written anywhere",
                        "delete the initialiser — the target's own value is what the weave reads");
            }
            members.add(new WeaveMember.Shadowed(name, type, flags,
                    orElse(Annotations.stringOr(shadow, "value", ""), name),
                    Annotations.booleanOr(shadow, "mutable", false)));
            return;
        }
        final Annotation unique = Annotations.find(annotations, Unique.class);
        if (unique != null && reportStaticWeaveMember(DiagnosticCode.UNIQUE_IN_STATIC_WEAVE,
                "@Unique", name, kind, report)) {
            return;
        }
        if (initialised) {
            report.info(DiagnosticCode.MERGED_FIELD_INITIALISER_IGNORED,
                    "merged field '" + name + "' in " + report.weaveClass + " has an initialiser; "
                            + "the field is copied into the target with the JVM's default value "
                            + "instead, because the initialiser belongs to a constructor and a "
                            + "weave has none to merge",
                    "write the value from an @Inject at the target constructor's HEAD, which is the "
                            + "only place that runs once per instance");
        }
        members.add(new WeaveMember.Merged(name, type, flags, unique != null,
                unique != null && Annotations.booleanOr(unique, "silent", false)));
    }

    /**
     * Refuses a merge-only annotation on a member of a static weave.
     *
     * <p>One method for {@code AW1090} and {@code AW1091} and for both the field and the method
     * path, because all four cases fail for the same reason and want the same advice.
     *
     * @param code   {@code AW1090} for {@code @Shadow}, {@code AW1091} for {@code @Unique}
     * @param what   the annotation as the message should spell it
     * @param name   the member's name
     * @param kind   the weave's kind
     * @param report where the diagnostics go
     * @return whether the member was refused, in which case the caller models nothing for it
     */
    private static boolean reportStaticWeaveMember(final DiagnosticCode code,
                                                   final String what,
                                                   final String name,
                                                   final Weave.Kind kind,
                                                   final Report report) {
        if (kind != Weave.Kind.STATIC) {
            return false;
        }
        report.error(code,
                what + " member '" + name + "' in " + report.weaveClass + " belongs to a static "
                        + "weave, which is never merged into its target, so there is nothing for "
                        + "the declaration to bind to",
                "declare the weave @Weave(kind = Kind.INSTANCE) if it is meant to be merged, or "
                        + "reach the target's state through the handler's parameters instead");
        return true;
    }

    /**
     * Classifies one method of the weave: a handler, a generated member, or a merge.
     *
     * <p>{@code <init>} and {@code <clinit>} are answered first and reach none of the rest. A static
     * initialiser is {@code AW1082} outright; a constructor is {@code AW1081} only when
     * {@link #isImplicitConstructor(MethodModel)} judges it written, since every class file has one.
     *
     * <p>One method can produce several {@link InjectorSpec}s — {@code @Inject} is repeatable, and a
     * {@code @Redirect} or a {@code @Wrap} may sit beside it — and they share the one
     * {@link HandlerRef}, the one list of captures and the one {@code @Result} flag read here,
     * because all three describe the method rather than any of its declarations. {@code AW1005} is
     * reported once per method for the same reason, before any declaration becomes a spec.
     *
     * <p>A method carrying any of the three is a handler and is therefore not also modelled as a
     * member, whether or not its declarations survived. Anything else falls through a fixed ladder:
     * {@code @Accessor}, {@code @Invoker}, {@code @Shadow}, {@code @Unique}, merged. The
     * {@code @Shadow} branch reads {@code mutable} for a method exactly as the field branch does.
     *
     * @param method    the method being read
     * @param weaveType the weave's own type, which the handler reference names
     * @param kind      the weave's kind
     * @param members   collects the member this method becomes, if it becomes one
     * @param injectors collects the injectors this method declares
     * @param report    where the diagnostics go
     */
    private static void readMethod(final MethodModel method,
                                   final ClassDesc weaveType,
                                   final Weave.Kind kind,
                                   final List<WeaveMember> members,
                                   final List<InjectorSpec> injectors,
                                   final Report report) {
        final String name = method.methodName().stringValue();
        if (ConstantDescs.INIT_NAME.equals(name)) {
            if (!isImplicitConstructor(method)) {
                report.error(DiagnosticCode.WEAVE_DECLARES_CONSTRUCTOR,
                        "weave " + report.weaveClass + " declares a constructor; it cannot be "
                                + "merged, because the target already has its own",
                        "initialise merged state from an @Inject at the target constructor's HEAD");
            }
            return;
        }
        if (ConstantDescs.CLASS_INIT_NAME.equals(name)) {
            report.error(DiagnosticCode.WEAVE_DECLARES_STATIC_INITIALISER,
                    "weave " + report.weaveClass + " declares a static initialiser; merging one "
                            + "into a target that already has its own is not a 0.1.0 capability",
                    null);
            return;
        }

        final List<Annotation> annotations = Annotations.on(method);
        final MethodTypeDesc type = method.methodTypeSymbol();
        final Set<AccessFlag> flags = method.flags().flags();
        final HandlerRef handler = new HandlerRef(weaveType, name, type, flags);

        final int before = injectors.size();
        final List<Annotation> injects = Annotations.findRepeated(annotations, Inject.class,
                Inject.Container.class);
        final Annotation redirect = Annotations.find(annotations, Redirect.class);
        final Annotation wrap = Annotations.find(annotations, Wrap.class);
        final boolean isHandler = !injects.isEmpty() || redirect != null || wrap != null;

        if (isHandler && kind == Weave.Kind.STATIC && !flags.contains(AccessFlag.STATIC)) {
            report.error(DiagnosticCode.STATIC_WEAVE_INSTANCE_HANDLER,
                    "handler " + handler.describe() + " is not static, but a static weave is "
                            + "never merged into its target, so there is no instance to call it on",
                    "declare it static and take the target as the first parameter");
        }

        final List<LocalSpec> locals = readLocals(method);
        final boolean capturesResult = capturesResult(method);
        for (final Annotation inject : injects) {
            final InjectorSpec spec = readInjector(InjectorKind.INJECT, inject, handler, locals,
                    capturesResult, report);
            if (spec != null) {
                injectors.add(spec);
            }
        }
        if (redirect != null) {
            final InjectorSpec spec = readInjector(InjectorKind.REDIRECT, redirect, handler, locals,
                    capturesResult, report);
            if (spec != null) {
                injectors.add(spec);
            }
        }
        if (wrap != null) {
            final InjectorSpec spec = readInjector(InjectorKind.WRAP, wrap, handler, locals,
                    capturesResult, report);
            if (spec != null) {
                injectors.add(spec);
            }
        }
        if (isHandler) {
            checkCallbackValue(method, handler, injectors.subList(before, injectors.size()), report);
        }
        if (isHandler) {
            return;
        }

        final Annotation accessor = Annotations.find(annotations, Accessor.class);
        if (accessor != null) {
            members.add(new WeaveMember.Accessor(name, type, flags,
                    orElse(Annotations.stringOr(accessor, "value", ""),
                            inferName(name, ACCESSOR_PREFIXES))));
            return;
        }
        final Annotation invoker = Annotations.find(annotations, Invoker.class);
        if (invoker != null) {
            members.add(new WeaveMember.Invoker(name, type, flags,
                    orElse(Annotations.stringOr(invoker, "value", ""),
                            inferName(name, INVOKER_PREFIXES))));
            return;
        }
        final Annotation shadow = Annotations.find(annotations, Shadow.class);
        if (shadow != null) {
            if (reportStaticWeaveMember(DiagnosticCode.SHADOW_IN_STATIC_WEAVE, "@Shadow",
                    name, kind, report)) {
                return;
            }
            members.add(new WeaveMember.Shadowed(name, type, flags,
                    orElse(Annotations.stringOr(shadow, "value", ""), name),
                    Annotations.booleanOr(shadow, "mutable", false)));
            return;
        }
        final Annotation unique = Annotations.find(annotations, Unique.class);
        if (unique != null && reportStaticWeaveMember(DiagnosticCode.UNIQUE_IN_STATIC_WEAVE,
                "@Unique", name, kind, report)) {
            return;
        }
        reportObjectMethod(name, type, report);
        members.add(new WeaveMember.Merged(name, type, flags, unique != null,
                unique != null && Annotations.booleanOr(unique, "silent", false)));
    }

    /**
     * Warns as {@code AW1083} about merging a method something outside the target calls.
     *
     * @param name   the method's name
     * @param type   its descriptor, which is part of the match
     * @param report where the diagnostics go
     */
    private static void reportObjectMethod(final String name,
                                           final MethodTypeDesc type,
                                           final Report report) {
        final String signature = name + type.descriptorString();
        if (!OBJECT_METHODS.contains(signature)) {
            return;
        }
        report.warn(DiagnosticCode.MERGED_OBJECT_METHOD,
                report.weaveClass + " merges '" + name + type.displayDescriptor() + "' into its "
                        + "target, replacing behaviour the platform itself calls",
                "make sure this is meant: collections, debuggers and logging all call these without "
                        + "the target's author being able to see it happen");
    }

    /**
     * Reads the {@code @Local} captures from the handler's parameter annotations.
     *
     * <p>A {@link LocalSpec} records the position in
     * {@code RuntimeVisibleParameterAnnotations}, which is the handler's own parameter list. An
     * unannotated parameter contributes nothing, so a spec's position in the returned list is not
     * its parameter number.
     *
     * @param method the handler
     * @return the captures in parameter order, or an empty list when the attribute is absent
     */
    private static List<LocalSpec> readLocals(final MethodModel method) {
        final List<List<Annotation>> perParameter = method
                .findAttribute(Attributes.runtimeVisibleParameterAnnotations())
                .map(RuntimeVisibleParameterAnnotationsAttribute::parameterAnnotations)
                .orElseGet(List::of);

        final List<LocalSpec> locals = new ArrayList<>();
        for (int parameter = 0; parameter < perParameter.size(); parameter++) {
            final Annotation local = Annotations.find(perParameter.get(parameter), Local.class);
            if (local != null) {
                locals.add(new LocalSpec(parameter,
                        Annotations.stringOr(local, "name", ""),
                        Annotations.intOr(local, "index", -1),
                        Annotations.intOr(local, "ordinal", -1),
                        Annotations.booleanOr(local, "mutable", false)));
            }
        }
        return locals;
    }

    /**
     * Reports whether the handler takes the target's result as its first parameter.
     *
     * <p>Parameter zero and no other: a {@code @Result} on a later parameter is not looked for here
     * and leaves this {@code false}.
     *
     * @param method the handler
     * @return whether parameter zero carries {@code @Result}
     */
    private static boolean capturesResult(final MethodModel method) {
        final List<List<Annotation>> perParameter = method
                .findAttribute(Attributes.runtimeVisibleParameterAnnotations())
                .map(RuntimeVisibleParameterAnnotationsAttribute::parameterAnnotations)
                .orElseGet(List::of);
        return !perParameter.isEmpty()
                && Annotations.find(perParameter.getFirst(), Result.class) != null;
    }

    /**
     * Refuses a handler that reads the callback's value where the target has computed none.
     *
     * <p>Reported as {@code AW1072}, once per point rather than once per handler, so a handler whose
     * four points are all wrong is told about all four.
     *
     * <p>The check is on the handler's instructions rather than on its signature, which is why it is
     * here and not in the annotation processor: taking a {@code ReturnableCallback} is legitimate at
     * every point, and only calling {@code value()} on it is not, and a call is a statement that
     * {@code javax.lang.model} does not model.
     *
     * @param method   the handler, whose code decides whether the check applies at all
     * @param handler  the handler reference the message names
     * @param declared the specs this one method contributed, and only those
     * @param report   where the diagnostics go
     */
    private static void checkCallbackValue(final MethodModel method,
                                           final HandlerRef handler,
                                           final List<InjectorSpec> declared,
                                           final Report report) {
        if (declared.isEmpty() || !readsCallbackValue(method)) {
            return;
        }
        for (final InjectorSpec spec : declared) {
            for (final PointSpec point : spec.points()) {
                if (VALUE_BEARING.contains(point.point())) {
                    continue;
                }
                report.error(DiagnosticCode.CALLBACK_VALUE_UNAVAILABLE,
                        "handler " + handler.describe() + " reads ReturnableCallback.value() at "
                                + point.point() + ", where the target has not computed one yet",
                        "value() is the value the target is about to return, so it exists at "
                                + String.join(" and ", VALUE_BEARING) + " only. Move the injection "
                                + "there, or drop the call — reading it here would hand the "
                                + "handler a null it cannot tell from a real one");
            }
        }
    }

    /**
     * Reports whether the handler's own code calls {@code ReturnableCallback.value()}.
     *
     * <p>Matched on the invocation's owner as the descriptor records it. {@code ReturnableCallback}
     * is {@code non-sealed}, so a call whose compile-time receiver is some other type is not seen,
     * and neither is one made by a method the handler delegates to. A method with no {@code Code}
     * attribute reads nothing.
     *
     * @param method the handler
     * @return whether the call appears among its instructions
     */
    private static boolean readsCallbackValue(final MethodModel method) {
        return method.code()
                .map(code -> code.elementList().stream()
                        .anyMatch(element -> element instanceof final InvokeInstruction invoke
                                && "value".equals(invoke.name().stringValue())
                                && RETURNABLE_CALLBACK.equals(invoke.owner().asInternalName())))
                .orElse(false);
    }

    // ---------------------------------------------------------------------------------------
    // Injectors
    // ---------------------------------------------------------------------------------------

    /**
     * Builds one {@link InjectorSpec} from an {@code @Inject}, a {@code @Redirect} or a
     * {@code @Wrap}.
     *
     * <p>One path for all three, because they declare the same elements.
     *
     * <p>All or nothing: a refused method selector, slice or point abandons the whole declaration,
     * so a partly-read one is never modelled. A declaration that resolves to no point at all is
     * {@code AW1043}.
     *
     * @param kind           which of the three annotations this is
     * @param annotation     the annotation being read
     * @param handler        the method carrying it
     * @param locals         the captures read from that method's parameters
     * @param capturesResult whether that method's first parameter carries {@code @Result}
     * @param report         where the diagnostics go
     * @return the spec, or {@code null} when any part of the declaration was refused
     */
    private static @Nullable InjectorSpec readInjector(final InjectorKind kind,
                                                       final Annotation annotation,
                                                       final HandlerRef handler,
                                                       final List<LocalSpec> locals,
                                                       final boolean capturesResult,
                                                       final Report report) {
        final String rawMethod = Annotations.stringOr(annotation, "method", "");
        final MemberSelector method = parseSelector(rawMethod, MemberKind.METHOD, handler, report);
        if (method == null) {
            return null;
        }

        final List<SliceSpec> slices = new ArrayList<>();
        for (final Annotation slice : Annotations.nested(annotation, "slice")) {
            final SliceSpec spec = readSlice(slice, handler, report);
            if (spec == null) {
                return null;
            }
            slices.add(spec);
        }

        final List<PointSpec> points = new ArrayList<>();
        // @Redirect declares a single @At, @Inject an array of them.
        final Annotation single = Annotations.nestedOne(annotation, "at");
        final List<Annotation> declared = single != null
                ? List.of(single)
                : Annotations.nested(annotation, "at");
        for (final Annotation at : declared) {
            final PointSpec spec = readPoint(at, -1, handler, report);
            if (spec == null) {
                return null;
            }
            points.add(spec);
        }
        if (points.isEmpty()) {
            report.error(DiagnosticCode.NO_INJECTION_POINT_MATCHED,
                    "handler " + handler.describe() + " declares no injection point",
                    "add at = @At(Point.HEAD), or whichever point it should attach to");
            return null;
        }

        // 0 is the annotations' "the injector decides" sentinel. Because a class file records only
        // the elements that were written, an explicit require = 0 is distinguishable from an
        // omitted one, and only the omitted one becomes the default of 1.
        final int require = Annotations.has(annotation, "require")
                ? Annotations.intOr(annotation, "require", 0)
                : 1;

        return new InjectorSpec(kind, handler, rawMethod, method, points, slices,
                orElse(Annotations.stringOr(annotation, "id", ""), derivedId(handler, kind)),
                require,
                Annotations.intOr(annotation, "allow", 0),
                Annotations.stringOr(annotation, "group", ""),
                locals,
                capturesResult);
    }

    /**
     * Builds one {@link SliceSpec} from a {@code @Slice}.
     *
     * <p>{@code @Slice} declares a default for each bound and a class file records neither, so an
     * omitted {@code from} becomes {@link Point#HEAD} and an omitted {@code to} becomes
     * {@link Point#TAIL} here instead.
     *
     * @param annotation the {@code @Slice}
     * @param handler    the handler carrying it, which any diagnostic names
     * @param report     where the diagnostics go
     * @return the slice, or {@code null} when either bound was refused
     */
    private static @Nullable SliceSpec readSlice(final Annotation annotation,
                                                 final HandlerRef handler,
                                                 final Report report) {
        final Annotation from = Annotations.nestedOne(annotation, "from");
        final Annotation to = Annotations.nestedOne(annotation, "to");

        // A range boundary must resolve to exactly one position, so both bounds default to the
        // first match rather than to @At's usual "keep every match".
        final PointSpec start = from == null
                ? PointSpec.builtIn(Point.HEAD).ordinal(0).build()
                : readPoint(from, 0, handler, report);
        final PointSpec end = to == null
                ? PointSpec.builtIn(Point.TAIL).ordinal(0).build()
                : readPoint(to, 0, handler, report);
        if (start == null || end == null) {
            return null;
        }
        return new SliceSpec(Annotations.stringOr(annotation, "id", ""), start, end);
    }

    /**
     * Builds one {@link PointSpec} from an {@code @At}.
     *
     * <p>A non-empty {@code custom} replaces the {@link Point} constant entirely, so a custom point
     * spelled exactly like a built-in one travels as that built-in one's name and gets its answer
     * everywhere the point is later compared by name.
     *
     * <p>Whether {@code target} is parsed as a selector is decided by the point and not by the text.
     * A point that names no member kind keeps its target as text, which is what keeps
     * {@link Point#NEW}'s class name out of the member grammar; a target that should have parsed and
     * did not abandons the point.
     *
     * @param annotation     the {@code @At}
     * @param defaultOrdinal the ordinal to use when none was written: {@code -1} keeps every match,
     *                       and a slice bound passes {@code 0}
     * @param handler        the handler carrying it, which any diagnostic names
     * @param report         where the diagnostics go
     * @return the point, or {@code null} when its target was refused
     */
    private static @Nullable PointSpec readPoint(final Annotation annotation,
                                                 final int defaultOrdinal,
                                                 final HandlerRef handler,
                                                 final Report report) {
        final String custom = Annotations.stringOr(annotation, "custom", "");
        final String point = custom.isEmpty()
                ? Annotations.enumOr(annotation, "value", Point.class, Point.HEAD).name()
                : custom;

        final PointSpec.Builder builder = PointSpec.named(point)
                .ordinal(Annotations.intOr(annotation, "ordinal", defaultOrdinal))
                .shift(Annotations.enumOr(annotation, "shift", At.Shift.class, At.Shift.NONE))
                .by(Annotations.intOr(annotation, "by", 0))
                .access(Annotations.enumOr(annotation, "access", At.Access.class, At.Access.ANY))
                .slice(Annotations.stringOr(annotation, "slice", ""));

        final String target = Annotations.stringOr(annotation, "target", "");
        if (target.isEmpty()) {
            return builder.build();
        }
        final MemberKind expected = selectorKindFor(point);
        if (expected == null) {
            return builder.target(target).build();
        }
        final MemberSelector selector = parseSelector(target, expected, handler, report);
        return selector == null ? null : builder.target(target, selector).build();
    }

    /**
     * Delegates to {@link PointTargets#selectorKindFor(String)}.
     *
     * @param point the point's name
     * @return the grammar the point's target is written in, or {@code null} when it names no member
     */
    private static @Nullable MemberKind selectorKindFor(final String point) {
        return PointTargets.selectorKindFor(point);
    }

    /**
     * Parses a selector, turning a syntax error into a diagnostic.
     *
     * <p>A blank selector is {@code AW1015} without being handed to the grammar at all. A
     * {@link SelectorSyntaxException} carries its own code and its own suggestion, and both are
     * passed through untouched so that the grammar keeps the wording of its own errors; only the
     * handler's name is prepended, without which a build log names a selector and not the
     * declaration that wrote it.
     *
     * @param text     the selector as written
     * @param expected the grammar it has to parse as
     * @param handler  the handler carrying it
     * @param report   where the diagnostics go
     * @return the selector, or {@code null} when it did not parse
     */
    private static @Nullable MemberSelector parseSelector(final String text,
                                                          final MemberKind expected,
                                                          final HandlerRef handler,
                                                          final Report report) {
        if (text.isBlank()) {
            report.error(DiagnosticCode.SELECTOR_SYNTAX_ERROR,
                    "handler " + handler.describe() + " declares an empty selector",
                    null);
            return null;
        }
        try {
            return MemberSelector.parse(text, expected);
        } catch (final SelectorSyntaxException e) {
            report.error(e.code(),
                    "handler " + handler.describe() + ": " + e.getMessage(),
                    e.suggestion().orElse(null));
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------
    // Small shared pieces
    // ---------------------------------------------------------------------------------------

    /**
     * Judges whether a constructor was written or is the one the compiler supplies.
     *
     * <p>A heuristic, and it can be nothing else: a constructor written as {@code Weave() {}}
     * compiles to exactly the bytes that would have been generated for it. It answers yes for a
     * constructor with no parameters whose body is at most three instructions — the {@code aload_0},
     * {@code invokespecial} and {@code return} of the supplied one — and yes for a constructor with
     * no {@code Code} attribute. Only {@code Instruction} elements are counted, because a code
     * model's element list also carries labels and, for a class compiled with debug information,
     * line numbers.
     *
     * @param method the constructor
     * @return whether it may be the implicit one, in which case {@code AW1081} is not reported
     */
    private static boolean isImplicitConstructor(final MethodModel method) {
        if (method.methodTypeSymbol().parameterCount() != 0) {
            return false;
        }
        return method.code()
                .map(code -> code.elementList().stream()
                        .filter(element -> element instanceof Instruction)
                        .count() <= 3)
                .orElse(true);
    }

    /**
     * Strips a known prefix from a declaration's name to arrive at the target's.
     *
     * <p>A prefix counts only when what follows starts with an upper-case letter, so {@code isolate}
     * keeps its name rather than becoming {@code olate}. The first matching prefix wins, and a name
     * matching none is used as it stands.
     *
     * @param declared the name written in the weave
     * @param prefixes the prefixes to try, in order
     * @return the inferred name
     */
    private static String inferName(final String declared, final List<String> prefixes) {
        for (final String prefix : prefixes) {
            if (declared.length() > prefix.length() && declared.startsWith(prefix)
                    && Character.isUpperCase(declared.charAt(prefix.length()))) {
                final String rest = declared.substring(prefix.length());
                return Character.toLowerCase(rest.charAt(0)) + rest.substring(1);
            }
        }
        return declared;
    }

    /**
     * Builds the identifier a declaration that named none is given.
     *
     * <p>Derived from the method and the kind, so a method carrying both an {@code @Inject} and a
     * {@code @Redirect} gets two distinct identifiers — but two {@code @Inject}s on one method
     * derive the same one, and are told apart only by what they wrote in {@code id}.
     *
     * @param handler the handler
     * @param kind    the injector kind
     * @return the derived identifier
     */
    private static String derivedId(final HandlerRef handler, final InjectorKind kind) {
        return handler.describe() + '#' + kind.id();
    }

    /**
     * Substitutes for an element that was written empty or not written at all, which a class file
     * records the same way.
     *
     * @param value    the value as read
     * @param fallback what an empty value means
     * @return {@code value}, or {@code fallback} when it is empty
     */
    private static String orElse(final String value, final String fallback) {
        return value.isEmpty() ? fallback : value;
    }

    /**
     * Renders a class descriptor as a binary name, for a message a person reads.
     *
     * <p>Strips the leading {@code L} and the trailing semicolon and swaps the separator, so it is
     * correct for a class descriptor and for nothing else; every caller holds one.
     *
     * @param type the class's descriptor
     * @return the binary name
     */
    private static String binaryNameOf(final ClassDesc type) {
        final String descriptor = type.descriptorString();
        return descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
    }

    /**
     * The parse's diagnostic channel, and its memory of whether anything was fatal.
     *
     * <p>Carried through the static methods instead of the listener itself, because the weave's name
     * and its origin belong on every diagnostic and would otherwise be threaded through as two more
     * arguments each. Only {@link #error(DiagnosticCode, String, String)} records a failure; a
     * warning and an info leave the weave usable.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private static final class Report {

        /** Where the finished diagnostics go. */
        private final DiagnosticListener listener;

        /** The weave's binary name, which is the location of every diagnostic reported here. */
        private final String weaveClass;

        /** Where the weave was found, which becomes a detail line on every diagnostic. */
        private final Origin origin;

        /**
         * Whether an error has been reported.
         *
         * <p>Set once and never cleared. {@link WeaveClassParser#parse(ClassModel, Origin)} reads
         * it only after the whole class has been read, which is what lets one run report every
         * mistake in a weave instead of stopping at the first.
         */
        private boolean failed;

        /**
         * Creates a report for one weave class.
         *
         * @param listener   where the diagnostics go
         * @param weaveClass the weave's binary name
         * @param origin     where the weave was found
         */
        private Report(final DiagnosticListener listener,
                       final String weaveClass,
                       final Origin origin) {
            this.listener = listener;
            this.weaveClass = weaveClass;
            this.origin = origin;
        }

        /**
         * Reports a diagnostic that makes the weave unusable.
         *
         * @param code    the diagnostic's code
         * @param message what is wrong
         * @param remedy  what to do about it, or {@code null} when there is nothing to suggest
         */
        private void error(final DiagnosticCode code,
                           final String message,
                           final @Nullable String remedy) {
            this.failed = true;
            emit(code, Severity.ERROR, message, remedy);
        }

        /**
         * Reports a diagnostic the weave survives.
         *
         * @param code    the diagnostic's code
         * @param message what is surprising
         * @param remedy  what to do about it, or {@code null} when there is nothing to suggest
         */
        private void warn(final DiagnosticCode code,
                          final String message,
                          final @Nullable String remedy) {
            emit(code, Severity.WARNING, message,
                    remedy);
        }

        /**
         * Reports something worth knowing that is not a fault.
         *
         * @param code    the diagnostic's code
         * @param message what happened
         * @param remedy  what to do instead, or {@code null} when there is nothing to suggest
         */
        private void info(final DiagnosticCode code,
                          final String message,
                          final @Nullable String remedy) {
            emit(code, Severity.INFO, message, remedy);
        }

        /**
         * Builds one diagnostic and hands it to the listener.
         *
         * <p>The location names the weave class and never a handler, even for a diagnostic about
         * one; the handler is named in the message text instead. The origin goes in as a detail
         * rather than as part of the message, so that a weave found in a dependency can be told
         * from one compiled in this build without every message having to say so.
         *
         * @param code     the diagnostic's code
         * @param severity how bad it is
         * @param message  what the diagnostic says
         * @param remedy   what to do about it, or {@code null} to add none
         */
        private void emit(final DiagnosticCode code,
                          final Severity severity,
                          final String message,
                          final @Nullable String remedy) {
            final Diagnostic.Builder builder = Diagnostic.builder(code)
                    .severity(severity)
                    .message(message)
                    .location(Location.builder().weave(this.weaveClass, null).build())
                    .detail("discovered via " + this.origin.describe());
            if (remedy != null) {
                builder.remedy(remedy);
            }
            this.listener.report(builder.build());
        }
    }
}
