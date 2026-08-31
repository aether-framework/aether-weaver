package de.splatgames.aether.weaver.processor;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import de.splatgames.aether.weaver.api.select.MemberKind;
import de.splatgames.aether.weaver.api.model.InjectorSpec;
import de.splatgames.aether.weaver.api.select.MemberSelector;

import java.lang.classfile.ClassModel;

import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Checks weave and extension declarations while the compiler is still running, and records what
 * they declare.
 *
 * <p>Registered as a service, so a project that has this module on its annotation processor path
 * gets it without configuring anything. It claims nothing: {@link #process} always answers
 * {@code false}, leaving the annotations visible to every other processor in the round.
 *
 * <h2>What it does</h2>
 *
 * <p>Two things, and they are independent of each other. It reports the mistakes in a weave that
 * can be found without running the weaver — a target that does not resolve, a handler whose
 * signature cannot be called, a member that will not merge, an injection point that matches no
 * instruction — so that a build fails at the declaration rather than when the weaver runs. And it
 * writes the manifest the runtime discovers weaves through, which is the only channel from a
 * compilation to a run.
 *
 * <p>The two are deliberately not conditional on each other: a declaration that was reported as an
 * error is still recorded, because the entry describes what the source said rather than whether the
 * source was right.
 *
 * <h2>The order the checks run in</h2>
 *
 * <p>Everything that is true of a weave whatever it is applied to runs first and once — the target
 * declaration itself, the class's shape, its members, and each handler's own signature. Only then
 * are the targets resolved and each of them checked. That is what keeps a weave with three targets
 * from being told three times that it declares a constructor, while still being told separately for
 * each target which of them is missing a method.
 *
 * <p>An injection whose selector does not parse is dropped after that first pass: it is never
 * checked against a target and never reaches the manifest, so one unparseable selector costs its
 * own declaration and nothing else.
 *
 * <h2>What it reports itself</h2>
 *
 * <p>Most diagnostics come from the check classes this one drives. Reported here directly are
 * {@code AW1001} and {@code AW1002} for how the targets were declared, {@code AW1006},
 * {@code AW1007}, {@code AW1008} and {@code AW1084} for the weave class's shape, {@code AW1081} for
 * a constructor, and {@code AW1005} for an instance handler in a static weave.
 *
 * <h2>Thread safety</h2>
 *
 * <p>Not safe to share, and not meant to be: {@link #init} is {@code synchronized} because
 * {@link AbstractProcessor} declares it so, but the three fields it sets are then read without
 * synchronisation, and both the manifest collector and the class-file cache accumulate state across
 * rounds. One processor instance belongs to one compilation task, which the host compiler drives
 * from a single thread.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
@SupportedAnnotationTypes({WeaveProcessor.WEAVE, WeaveProcessor.EXTENSION})
public final class WeaveProcessor extends AbstractProcessor {

    /** The qualified name of {@code @Weave}, one of the two annotations this is registered for. */
    static final String WEAVE = "de.splatgames.aether.weaver.api.Weave";

    /** The qualified name of {@code @Extension}, the other. */
    static final String EXTENSION = "de.splatgames.aether.weaver.api.experimental.Extension";

    /** The qualified name of {@code @Inject}. */
    static final String INJECT = "de.splatgames.aether.weaver.api.Inject";

    /**
     * The qualified name of the container {@code javac} rewrites a repeated {@code @Inject} into.
     *
     * <p>Written with a dot before the nested part, because that is the canonical name
     * {@link Anchors#nameOf(AnnotationMirror)} produces and this is compared against it.
     */
    static final String INJECT_CONTAINER = INJECT + ".Container";

    /** The qualified name of {@code @Redirect}. */
    static final String REDIRECT = "de.splatgames.aether.weaver.api.Redirect";

    /** The qualified name of {@code @Wrap}. */
    static final String WRAP = "de.splatgames.aether.weaver.api.Wrap";

    /** The qualified name of {@code @Shadow}. */
    static final String SHADOW = "de.splatgames.aether.weaver.api.Shadow";

    /** The qualified name of {@code @Unique}. */
    static final String UNIQUE = "de.splatgames.aether.weaver.api.Unique";

    /** The qualified name of {@code @Accessor}. */
    static final String ACCESSOR = "de.splatgames.aether.weaver.api.Accessor";

    /** The qualified name of {@code @Invoker}. */
    static final String INVOKER = "de.splatgames.aether.weaver.api.Invoker";

    /**
     * The simple name of the {@code Weave.Kind} constant that means the weave is never merged.
     *
     * <p>Compared as a string because resolving the constant would need the enum on the processor's
     * own classpath.
     */
    private static final String STATIC_KIND = "STATIC";

    /** Where every check in this compilation reports; created in {@link #init}. */
    private MessagerReporter reporter;

    /** The class-file reader, whose cache spans the compilation; created in {@link #init}. */
    private TargetBytes bytes;

    /** The manifest collector, written out in the final round; created in {@link #init}. */
    private ManifestEmitter manifest;


    /**
     * Creates a processor with nothing configured.
     *
     * <p>Public and taking no arguments because a {@code ServiceLoader} instantiates it. Everything
     * it needs comes from the processing environment, which is not available until {@link #init},
     * so an instance between construction and initialisation cannot process anything.
     */
    public WeaveProcessor() {
        // The processing environment is not available until init; nothing to do here.
    }

    /**
     * Takes the reporter, the class-file reader and the manifest collector from the environment.
     *
     * <p>All three live as long as the processor does, which is what makes the class-file cache and
     * the manifest span every round of one compilation rather than one round of it.
     *
     * @param environment the processing environment; must not be {@code null}
     * @throws NullPointerException if {@code environment} is {@code null}
     */
    @Override
    public synchronized void init(@NotNull final ProcessingEnvironment environment) {
        super.init(Objects.requireNonNull(environment, "environment"));
        this.reporter = new MessagerReporter(environment.getMessager());
        this.bytes = new TargetBytes(environment.getFiler(), environment.getElementUtils());
        this.manifest = new ManifestEmitter(environment.getFiler());
    }

    /**
     * Claims support for whatever source version the host compiler is.
     *
     * <p>Nothing here is tied to a language level: the checks read annotation mirrors and element
     * kinds, and a construct this processor has never heard of is skipped rather than misread.
     * Naming a fixed version instead would make every newer compiler print a warning about it.
     *
     * @return the latest source version the running compiler supports
     */
    @Override
    @NotNull
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    /**
     * Checks every weave and extension the round offers, and writes the manifest when there are no
     * rounds left.
     *
     * <p>The final round does nothing but write. The manifest is written there and nowhere else
     * because a {@link javax.annotation.processing.Filer} refuses to reopen a resource it created:
     * writing once per round works on a single-round compilation and throws the moment another
     * processor generates a source file and causes a second round.
     *
     * <p>In an ordinary round the annotations are filtered by name again, so anything else the
     * compiler hands over is ignored, and an annotated element that is not a type is skipped. Both
     * annotations declare {@code @Target(ElementType.TYPE)}, so neither filter is expected to
     * reject anything; they keep a malformed or rewritten element from becoming a cast failure
     * inside the compiler.
     *
     * @param annotations the annotations to process; must not be {@code null}
     * @param round       the round environment; must not be {@code null}
     * @return {@code false} always, so that the annotations remain unclaimed and every other
     *         processor still sees them
     * @throws NullPointerException if either argument is {@code null}
     */
    @Override
    public boolean process(@NotNull final Set<? extends TypeElement> annotations,
                           @NotNull final RoundEnvironment round) {
        Objects.requireNonNull(annotations, "annotations");
        Objects.requireNonNull(round, "round");
        if (round.processingOver()) {
            // The manifest is written here and nowhere else. Filer refuses to reopen a
            // resource it created, so writing per round works on a single-round compilation and
            // throws the moment another processor generates a source file.
            this.manifest.write(this.reporter);
            return false;
        }
        for (final TypeElement annotation : annotations) {
            final String name = annotation.getQualifiedName().toString();
            if (!WEAVE.equals(name) && !EXTENSION.equals(name)) {
                continue;
            }
            for (final Element element : round.getElementsAnnotatedWith(annotation)) {
                if (!(element instanceof final TypeElement type)) {
                    continue;
                }
                if (WEAVE.equals(name)) {
                    check(type);
                } else {
                    checkExtension(type);
                }
            }
        }
        return false;
    }

    /**
     * Checks one {@code @Extension} holder and records what it contributes.
     *
     * <p>The holder is registered even when it contributes nothing, so a compilation whose only
     * extension was refused still writes a manifest.
     *
     * @param type the extension holder; must not be {@code null}
     */
    private void checkExtension(@NotNull final TypeElement type) {
        final Elements elements = this.processingEnv.getElementUtils();
        this.manifest.addExtensions(elements.getBinaryName(type).toString(),
                ExtensionChecks.of(type, elements, this.reporter));
    }

    /**
     * Runs every check for one weave class and records it.
     *
     * <p>The pass is in two halves. Everything true of the weave whatever it targets happens first
     * and once: how the targets were declared, the class's own shape, its members, the
     * merge-related annotations a static weave cannot honour, and each handler's signature and
     * selector. Then the targets are resolved and, for each, the members and handlers are checked
     * against that target and its injection points resolved against its bytes.
     *
     * <p>Returns without doing anything when the element carries no {@code @Weave} mirror. That can
     * only happen where another processor has rewritten the element between the round environment
     * answering and this being called, and the alternative is a {@link NullPointerException} raised
     * inside the compiler with nothing to point the user at.
     *
     * <p>The manifest entry is written last and unconditionally, so a weave that was refused is
     * still recorded.
     *
     * @param type the weave class; must not be {@code null}
     */
    private void check(@NotNull final TypeElement type) {
        final AnnotationMirror weave = Anchors.mirrorOf(type, WEAVE);
        if (weave == null) {
            // Only reachable when a second processor has rewritten the element, but a null here
            // would otherwise become a NullPointerException inside javac with no user-visible cause.
            return;
        }
        checkTargets(type, weave);
        checkShape(type);
        checkMembers(type, weave);

        MemberChecks.declaration(type, STATIC_KIND.equals(Anchors.enumOf(weave, "kind")),
                this.reporter);
        final List<Injection> injections = readInjections(type);

        // The targets last: everything above is true of the weave whatever it is applied to, and
        // running it per target would say each of those things once per target.
        final Types types = this.processingEnv.getTypeUtils();
        final Elements elements = this.processingEnv.getElementUtils();
        final List<String> targetNames = new ArrayList<>();
        for (final SourceTargets.Resolved target : SourceTargets.of(
                type, weave, elements, this.reporter)) {
            targetNames.add(elements.getBinaryName(target.element()).toString());
            MemberChecks.againstTarget(type, target, types, this.reporter);
            for (final Injection injection : injections) {
                HandlerChecks.againstTarget(injection.handler(), injection.mirror(),
                        injection.selector(), target, types, elements, this.reporter);
            }
            checkPoints(injections, target);
        }

        record(type, weave, targetNames, injections, elements);
    }

    /**
     * Builds the weave's manifest entry and hands it to the collector.
     *
     * <p>Each of {@code kind}, {@code require} and {@code phase} is read as the enum constant's
     * simple name and defaulted here, because an annotation mirror holds only what the source
     * wrote while the manifest needs a value either way. The defaults written are {@code INSTANCE},
     * {@code REQUIRED} and {@code DEFAULT}.
     *
     * <p>An injection that yields no specification — one whose {@code at} array was empty — is left
     * out of the entry with nothing said about it.
     *
     * @param type       the weave class; must not be {@code null}
     * @param weave      its {@code @Weave} mirror; must not be {@code null}
     * @param targets    the resolved targets as binary names; must not be {@code null}
     * @param injections the declarations whose selectors parsed; must not be {@code null}
     * @param elements   the element utilities, used for binary names; must not be {@code null}
     */
    private void record(@NotNull final TypeElement type,
                        @NotNull final AnnotationMirror weave,
                        @NotNull final List<String> targets,
                        @NotNull final List<Injection> injections,
                        @NotNull final Elements elements) {
        final List<InjectorSpec> specs = new ArrayList<>(injections.size());
        for (final Injection injection : injections) {
            final InjectorSpec spec = SourceSpecs.of(injection.handler(), injection.mirror(),
                    injection.selector(), elements);
            if (spec != null) {
                specs.add(spec);
            }
        }
        final String kind = Anchors.enumOf(weave, "kind");
        final String require = Anchors.enumOf(weave, "require");
        final String phase = Anchors.enumOf(weave, "phase");
        this.manifest.add(ManifestEmitter.entry(type,
                elements.getBinaryName(type).toString(),
                kind == null ? "INSTANCE" : kind,
                intOf(weave),
                require == null ? "REQUIRED" : require,
                phase == null ? "DEFAULT" : phase,
                Anchors.stringsOf(weave, "tags"),
                targets,
                SourceMembers.of(type),
                specs));
    }

    /**
     * Reads the {@code priority} the weave declared.
     *
     * @param weave the {@code @Weave} mirror; must not be {@code null}
     * @return the declared priority, or {@code 0} when the source omitted it
     */
    @Contract(pure = true)
    private static int intOf(@NotNull final AnnotationMirror weave) {
        final AnnotationValue value = Anchors.valueOf(weave, "priority");
        return value != null && value.getValue() instanceof Integer priority ? priority : 0;
    }

    /**
     * Resolves every declaration's injection points against one target's compiled bytes.
     *
     * <p>Skipped entirely for a weave with no injections, which is what keeps a purely structural
     * weave from being told {@code AW1200} about a check it never wanted, and for a target whose
     * class file could not be named. Where the class file simply is not there, {@code AW1200} is
     * reported once for the target and this returns.
     *
     * @param injections the declarations whose selectors parsed; must not be {@code null}
     * @param target     the target and the anchor that named it; must not be {@code null}
     */
    private void checkPoints(@NotNull final List<Injection> injections,
                             @NotNull final SourceTargets.Resolved target) {
        if (injections.isEmpty() || !TargetBytes.isNameable(target.element())) {
            return;
        }
        final ClassModel compiled = this.bytes.of(target.element(), target.anchor(), this.reporter);
        if (compiled == null) {
            return;
        }
        final String name = target.element().getQualifiedName().toString();
        for (final Injection injection : injections) {
            final InjectorSpec spec = SourceSpecs.of(injection.handler(), injection.mirror(),
                    injection.selector(), this.processingEnv.getElementUtils());
            if (spec != null) {
                PointChecks.run(injection.handler(), injection.mirror(), spec, compiled, name,
                        this.reporter);
            }
        }
    }

    /**
     * Checks each handler declaration on its own and returns the ones worth carrying further.
     *
     * <p>Every injection annotation on every method of the weave is checked here, whatever the
     * weave's targets turn out to be, and its {@code method} selector parsed. A declaration whose
     * selector did not parse has already been reported and is left out of the result, so it is
     * neither checked against a target nor recorded in the manifest.
     *
     * @param type the weave class; must not be {@code null}
     * @return one entry per injection annotation whose selector parsed, in declaration order
     */
    @NotNull
    private List<Injection> readInjections(@NotNull final TypeElement type) {
        final List<Injection> injections = new ArrayList<>();
        for (final Element member : type.getEnclosedElements()) {
            if (!(member instanceof ExecutableElement method)) {
                continue;
            }
            for (final AnnotationMirror mirror : injectionsOn(method)) {
                HandlerChecks.declaration(method, mirror, this.reporter);
                final MemberSelector selector = SelectorChecks.parse(
                        Anchors.stringOf(mirror, "method", ""), MemberKind.METHOD, method, mirror,
                        "method", this.reporter);
                if (selector != null) {
                    injections.add(new Injection(method, mirror, selector));
                }
            }
        }
        return injections;
    }

    /**
     * One injection declaration that has passed its own checks, ready to be run against a target.
     *
     * <p>The three parts travel together because every later stage needs all of them: the mirror
     * carries the elements and the position, the handler carries the signature, and the selector is
     * the parse nothing re-does.
     *
     * @param handler  the method the annotation is written on
     * @param mirror   the {@code @Inject}, {@code @Redirect} or {@code @Wrap} mirror, one
     *                 occurrence of it where the annotation was repeated
     * @param selector the parsed {@code method} selector
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private record Injection(@NotNull ExecutableElement handler,
                             @NotNull AnnotationMirror mirror,
                             @NotNull MemberSelector selector) {
    }


    /**
     * Lists the injection annotations on a method, with a repeated one flattened back out.
     *
     * <p>{@code javac} rewrites two {@code @Inject} annotations on one method into a single
     * {@code @Inject.Container}, so code reading the mirrors directly sees neither of the originals
     * unless it looks inside the container. Each occurrence is returned as its own mirror, which is
     * what makes a handler with one annotation and a handler with several the same thing to every
     * later stage.
     *
     * @param method the method to search; must not be {@code null}
     * @return the mirrors in source order, containers replaced by their contents
     */
    @NotNull
    private static List<AnnotationMirror> injectionsOn(@NotNull final ExecutableElement method) {
        final List<AnnotationMirror> found = new ArrayList<>();
        for (final AnnotationMirror mirror : method.getAnnotationMirrors()) {
            final String annotation = Anchors.nameOf(mirror);
            if (annotation.equals(INJECT) || annotation.equals(REDIRECT)
                    || annotation.equals(WRAP)) {
                found.add(mirror);
            } else if (annotation.equals(INJECT_CONTAINER)) {
                for (final AnnotationValue nested : Anchors.arrayOf(mirror, "value")) {
                    if (nested.getValue() instanceof AnnotationMirror repeated) {
                        found.add(repeated);
                    }
                }
            }
        }
        return found;
    }

    /**
     * Checks that the weave declared its targets exactly once, in one of the two forms.
     *
     * <p>Both forms written is {@code AW1002}: which of the two is authoritative would be a guess.
     * Keep the class literals and delete {@code targets = }, or the other way round. The caret goes
     * on the {@code targets} literal, the class literals being the form to keep. Reporting it ends
     * this check, so {@code AW1001} is never added alongside it — but it does not stop the pass, and
     * both forms are still resolved, checked and recorded afterwards.
     *
     * <p>Neither form written is {@code AW1001}: give the annotation a class literal,
     * {@code @Weave(Session.class)}, or a name where the target is not on the compile classpath,
     * {@code @Weave(targets = "com.acme.Session")}. There is no literal to underline, so the caret
     * falls back to the annotation itself.
     *
     * @param type  the weave class; must not be {@code null}
     * @param weave its {@code @Weave} mirror; must not be {@code null}
     */
    private void checkTargets(@NotNull final TypeElement type,
                              @NotNull final AnnotationMirror weave) {
        final List<AnnotationValue> literals = Anchors.arrayOf(weave, "value");
        final List<String> names = Anchors.stringsOf(weave, "targets");

        if (!literals.isEmpty() && !names.isEmpty()) {
            this.reporter.report(Diagnostic.builder(
                            DiagnosticCode.WEAVE_DUPLICATE_TARGET_DECLARATION)
                    .message("weave " + name(type) + " declares targets both as class literals and "
                            + "as names; which of the two is authoritative would be a guess")
                    .remedy("keep the class literals and delete targets=, or the other way round")
                    .build(),
                    // The `targets` literal, not the annotation: the class literals are the form
                    // to keep, so the position points at what should go.
                    Anchor.at(type, weave, Anchors.valueOf(weave, "targets")));
            return;
        }
        if (literals.isEmpty() && names.isEmpty()) {
            this.reporter.report(Diagnostic.builder(DiagnosticCode.WEAVE_NO_TARGETS)
                    .message("weave " + name(type) + " declares no target")
                    .remedy("give @Weave a class literal — @Weave(Session.class) — or a name, "
                            + "@Weave(targets = \"com.acme.Session\") when the target is not on "
                            + "the compile classpath")
                    .build(),
                    // There is no literal to point at: the fault is what the annotation does not say.
                    Anchor.at(type, weave));
        }
    }

    /**
     * Checks the weave class's own declaration, independently of what it targets.
     *
     * <p>Four conditions, none of which stops the others.
     *
     * <ul>
     *   <li>A superclass other than {@link Object} is {@code AW1006}. A weave's members are copied
     *       into its target and the target already has a superclass of its own, so declare the
     *       weave to extend {@link Object} and reach the superclass's members through
     *       {@code @Shadow}. An interface has no superclass in the element model and so is never
     *       reported.
     *   <li>Any implemented interface is {@code AW1084}, the message naming the first of them.
     *       Adding an interface to a target is not a 0.1.0 capability.
     *   <li>Any type parameter is {@code AW1007}, anchored on the first parameter itself so that
     *       the caret lands on what has to be deleted. A weave's members are copied verbatim into
     *       the target, where a type variable has nothing to bind to.
     *   <li>A class that is neither {@code abstract} nor {@code final} is {@code AW1008}, a
     *       warning; declare it final. An {@code abstract} class is exempt because it cannot be
     *       final and because abstract members are the spelling {@code @Accessor} and
     *       {@code @Invoker} use, and anything that is not a class — an interface, an enum, a
     *       record — is exempt because the test is on the element's kind.
     * </ul>
     *
     * @param type the weave class; must not be {@code null}
     */
    private void checkShape(@NotNull final TypeElement type) {
        final TypeMirror superclass = type.getSuperclass();
        if (superclass.getKind() != TypeKind.NONE && !isObject(superclass)) {
            this.reporter.report(Diagnostic.builder(DiagnosticCode.WEAVE_HAS_SUPERCLASS)
                    .message("weave " + name(type) + " extends " + superclass
                            + "; a weave's members are copied into its target, and the target "
                            + "already has a superclass of its own")
                    .remedy("declare the weave to extend Object, and reach the superclass's "
                            + "members through @Shadow instead")
                    .build(), Anchor.at(type));
        }
        if (!type.getInterfaces().isEmpty()) {
            this.reporter.report(Diagnostic.builder(DiagnosticCode.WEAVE_IMPLEMENTS_INTERFACE)
                    .message("weave " + name(type) + " implements "
                            + type.getInterfaces().getFirst()
                            + "; adding an interface to a target is not a 0.1.0 capability")
                    .build(), Anchor.at(type));
        }
        if (!type.getTypeParameters().isEmpty()) {
            this.reporter.report(Diagnostic.builder(DiagnosticCode.WEAVE_IS_GENERIC)
                    .message("weave " + name(type) + " is generic; its members are copied verbatim "
                            + "into the target, where a type variable has nothing to bind to")
                    .build(),
                    // The type parameter itself is an element, so the caret lands on <T> rather
                    // than on the class name.
                    Anchor.at(type.getTypeParameters().getFirst()));
        }
        // An abstract weave declares @Accessor or @Invoker in their abstract spelling, which is a
        // legitimate shape — and an abstract class cannot be final, so the advice would be
        // impossible to follow.
        final Set<Modifier> modifiers = type.getModifiers();
        if (!modifiers.contains(Modifier.ABSTRACT) && !modifiers.contains(Modifier.FINAL)
                && type.getKind() == ElementKind.CLASS) {
            this.reporter.report(Diagnostic.builder(DiagnosticCode.WEAVE_NOT_FINAL)
                    .message("weave " + name(type) + " is not final; a weave class is never "
                            + "subclassed and never instantiated")
                    .remedy("declare it final")
                    .build(), Anchor.at(type));
        }
    }

    /**
     * Checks the weave's methods for the two faults that need no target.
     *
     * <p>A constructor the source wrote is {@code AW1081}: it cannot be merged, because the target
     * already has its own. Initialise merged state from an {@code @Inject} at the target
     * constructor's {@code Point.HEAD} instead. A constructor the compiler supplied is not
     * reported, which is what the origin test is for. Each written constructor is reported
     * separately, so a weave declaring two is told twice.
     *
     * <p>A handler that is not {@code static} in a weave declaring {@code kind = Kind.STATIC} is
     * {@code AW1005}: such a weave is never merged into its target, so there is no instance to call
     * the handler on. The remedy printed is to declare it static and take the target as the first
     * parameter. The caret goes on the handler, its modifier list being the part that is wrong.
     *
     * <p>{@code AW1005} is also reported by {@code HandlerChecks} for any non-static {@code @Wrap}
     * handler, in any weave, with a different explanation and a different position. A non-static
     * {@code @Wrap} handler in a static weave produces both, and they do not duplicate each other:
     * one is about the weave's kind and one about what a wrap's inner level can reach.
     *
     * @param type  the weave class; must not be {@code null}
     * @param weave its {@code @Weave} mirror; must not be {@code null}
     */
    private void checkMembers(@NotNull final TypeElement type,
                              @NotNull final AnnotationMirror weave) {
        final boolean isStatic = STATIC_KIND.equals(enumOf(weave, "kind"));

        for (final Element member : type.getEnclosedElements()) {
            if (!(member instanceof ExecutableElement method)) {
                continue;
            }
            if (method.getKind() == ElementKind.CONSTRUCTOR) {
                if (!isExplicit(method)) {
                    continue;
                }
                this.reporter.report(Diagnostic.builder(DiagnosticCode.WEAVE_DECLARES_CONSTRUCTOR)
                        .message("weave " + name(type) + " declares a constructor; it cannot be "
                                + "merged, because the target already has its own")
                        .remedy("initialise merged state from an @Inject at the target "
                                + "constructor's HEAD")
                        .build(), Anchor.at(method));
                continue;
            }
            if (isStatic && isHandler(method) && !method.getModifiers().contains(Modifier.STATIC)) {
                this.reporter.report(Diagnostic.builder(
                                DiagnosticCode.STATIC_WEAVE_INSTANCE_HANDLER)
                        .message("handler " + name(type) + '#' + method.getSimpleName()
                                + " is not static, but a static weave is never merged into its "
                                + "target, so there is no instance to call it on")
                        .remedy("declare it static and take the target as the first parameter")
                        .build(), Anchor.at(method));
            }
        }
    }

    /**
     * Reports whether a constructor was written in the source.
     *
     * <p>{@link javax.lang.model.util.Elements.Origin#EXPLICIT} is the only origin that counts: a
     * default constructor the compiler supplies is {@code MANDATED}, and one another processor
     * generated is {@code SYNTHETIC}. Neither is something the weave's author can delete.
     *
     * @param constructor the constructor to test; must not be {@code null}
     * @return {@code true} when the source declared it
     */
    @Contract(pure = true)
    private boolean isExplicit(@NotNull final ExecutableElement constructor) {
        return this.processingEnv.getElementUtils().getOrigin(constructor)
                == javax.lang.model.util.Elements.Origin.EXPLICIT;
    }

    /**
     * Reports whether a method carries an injection annotation.
     *
     * <p>{@code @Inject}, its container, {@code @Redirect} and {@code @Wrap} count, and nothing
     * else does. A handler is what an injector entry in the manifest describes, so anything
     * answering {@code true} here is deliberately not also recorded as a member.
     *
     * @param method the method to test; must not be {@code null}
     * @return {@code true} when the method is a handler
     */
    @Contract(pure = true)
    static boolean isHandler(@NotNull final ExecutableElement method) {
        for (final AnnotationMirror mirror : method.getAnnotationMirrors()) {
            final String name = Anchors.nameOf(mirror);
            if (name.equals(INJECT) || name.equals(INJECT_CONTAINER)
                    || name.equals(REDIRECT) || name.equals(WRAP)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads an annotation element declared as an enum type, as the constant's simple name.
     *
     * <p>Behaves exactly as {@link Anchors#enumOf(AnnotationMirror, String)}, which the rest of
     * this class uses for the same job.
     *
     * @param mirror the annotation to read; must not be {@code null}
     * @param name   the element's name; must not be {@code null}
     * @return the constant's simple name, or {@code null} when the source did not write that
     *         element or wrote something that is not an enum constant
     */
    @Contract(pure = true)
    @Nullable
    private static String enumOf(@NotNull final AnnotationMirror mirror,
                                 @NotNull final String name) {
        final AnnotationValue value = Anchors.valueOf(mirror, name);
        if (value == null || !(value.getValue() instanceof Element constant)) {
            return null;
        }
        return constant.getSimpleName().toString();
    }

    /**
     * Reports whether a type mirror is {@link Object}.
     *
     * <p>Compared as text rather than through {@link javax.lang.model.util.Types}, which the check
     * that calls this does not otherwise need.
     *
     * @param type the type to test; must not be {@code null}
     * @return {@code true} when it renders as {@code java.lang.Object}
     */
    @Contract(pure = true)
    private static boolean isObject(@NotNull final TypeMirror type) {
        return "java.lang.Object".equals(type.toString());
    }

    /**
     * Names a type as diagnostics quote it.
     *
     * <p>The qualified name, with a dot before a nested part, rather than the binary name the
     * manifest records: a message is read against the source, where {@code Outer.Inner} is how the
     * class is written.
     *
     * @param type the type to name; must not be {@code null}
     * @return its qualified name
     */
    @Contract(pure = true)
    @NotNull
    private static String name(@NotNull final TypeElement type) {
        return type.getQualifiedName().toString();
    }
}
