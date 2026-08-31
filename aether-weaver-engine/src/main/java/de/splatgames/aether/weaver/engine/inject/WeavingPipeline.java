package de.splatgames.aether.weaver.engine.inject;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import de.splatgames.aether.weaver.api.diagnostic.Severity;
import de.splatgames.aether.weaver.api.model.GroupSpec;
import de.splatgames.aether.weaver.api.model.InjectorKind;
import de.splatgames.aether.weaver.api.model.InjectorSpec;
import de.splatgames.aether.weaver.api.model.PointSpec;
import de.splatgames.aether.weaver.api.model.SliceSpec;
import de.splatgames.aether.weaver.api.select.MethodSelector;
import de.splatgames.aether.weaver.api.spi.CodeView;
import de.splatgames.aether.weaver.api.spi.InjectionContext;
import de.splatgames.aether.weaver.api.spi.InjectionPoint;
import de.splatgames.aether.weaver.api.spi.Injector;
import de.splatgames.aether.weaver.api.spi.HandlerBinding;
import de.splatgames.aether.weaver.api.spi.MethodView;
import de.splatgames.aether.weaver.api.spi.PlanEntryView;
import de.splatgames.aether.weaver.api.spi.Reporter;
import de.splatgames.aether.weaver.api.spi.Site;
import de.splatgames.aether.weaver.api.spi.TargetView;
import de.splatgames.aether.weaver.engine.explain.Resolution;
import de.splatgames.aether.weaver.engine.explain.SiteObserver;
import de.splatgames.aether.weaver.engine.plugin.PluginIsolation;
import de.splatgames.aether.weaver.engine.inject.point.ModelViews;
import de.splatgames.aether.weaver.engine.inject.point.PointResolver;
import de.splatgames.aether.weaver.engine.inject.point.Targets;
import de.splatgames.aether.weaver.engine.internal.transform.LocalTable;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.constant.ConstantDescs;
import java.lang.classfile.instruction.ExceptionCatch;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Turns a plan for one class into woven bytes.
 *
 * <p>The work is in two phases, and the split is forced by what an element index means. An
 * injection point answers with indices into the body as it was read, and those indices are all an
 * emitter has to recognise its own positions by — so resolution, validation and argument binding
 * have to finish before the first instruction is written. Once the rewrite begins, an index
 * identifies nothing that can be looked up again.
 *
 * <p>The outcome is all or nothing. A class is written with every surviving declaration applied, or
 * nothing is returned at all and the caller keeps the bytes it had; there is no partially woven
 * result, because the rewrite builds into a fresh class and abandoning it costs only the work.
 *
 * <p>An instance holds the two registries and the observer it was built with, and keeps nothing
 * from one call to the next.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class WeavingPipeline {

    /** Resolves a declaration's points against a body, wrapping the registry it was given. */
    private final PointResolver points;

    /** Looks an injector up by kind identifier, answering {@code null} where none is registered. */
    private final Function<String, Injector> injectors;

    /** Told what every point matched while the body it matched in still exists. */
    private final SiteObserver observer;

    /**
     * Creates a pipeline that reports to nobody.
     *
     * @param points    the injection point registry, by identifier; must not be {@code null}
     * @param injectors the injector registry, by kind identifier; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public WeavingPipeline(@NotNull final Function<String, InjectionPoint> points,
                           @NotNull final Function<String, Injector> injectors) {
        this(points, injectors, SiteObserver.NONE);
    }

    /**
     * Creates a pipeline that tells an observer what each point matched.
     *
     * @param points    the injection point registry, by identifier; must not be {@code null}
     * @param injectors the injector registry, by kind identifier; must not be {@code null}
     * @param observer  where to report resolved positions; {@link SiteObserver#NONE} to report
     *                  nowhere; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public WeavingPipeline(@NotNull final Function<String, InjectionPoint> points,
                           @NotNull final Function<String, Injector> injectors,
                           @NotNull final SiteObserver observer) {
        this.points = new PointResolver(Objects.requireNonNull(points, "points"));
        this.injectors = Objects.requireNonNull(injectors, "injectors");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    /**
     * Weaves one class.
     *
     * <p>Each entry is taken as far as it can go on its own: its target method is found, its points
     * are resolved, its injector is asked to validate it, and its handler is bound at every position
     * it matched. An entry that fails any of those is dropped, and the class is still woven from
     * whatever survives. What is not local to one entry is settled afterwards — the delegation
     * warning, which needs every entry's constructors at once, and the match accounting, which
     * refuses the whole class rather than one declaration when a bound is not met.
     *
     * <p>One position matched by two points of a declaration counts once, because the positions are
     * collected into a set before they are counted or bound. An entry whose method was not found, or
     * whose method has nothing to inject into, is still recorded with a count of zero, so that it is
     * accounted rather than quietly absent.
     *
     * <p>Nothing is returned when the accounting refuses the class, when no entry survived, when the
     * rewrite was refused, or when a class carrying a contributed kind had an injector throw.
     *
     * @param model    the class to weave; must not be {@code null}
     * @param entries  the declarations to apply, in the order they should be offered each element;
     *                 must not be {@code null}
     * @param groups   the groups the entries are accounted against; must not be {@code null}
     * @param reporter where to report; must not be {@code null}
     * @return the woven bytes, or {@code null} when the class was not woven
     * @throws NullPointerException if any argument is {@code null}
     */
    public byte @Nullable [] weave(@NotNull final ClassModel model,
                                   @NotNull final List<PlanEntryView> entries,
                                   @NotNull final List<GroupSpec> groups,
                                   @NotNull final Reporter reporter) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(groups, "groups");
        Objects.requireNonNull(reporter, "reporter");

        final TargetView target = ModelViews.of(model);
        final String internalName = model.thisClass().asInternalName();
        final Map<String, List<Resolved>> byMethod = new LinkedHashMap<>();
        final Map<InjectorSpec, Integer> counts = new LinkedHashMap<>();
        // Which constructors each weave attached to, so a delegation chain can be spotted across
        // injections that never see each other.
        final Map<String, List<MethodView>> constructors = new LinkedHashMap<>();

        for (final PlanEntryView entry : entries) {
            final InjectorSpec spec = entry.spec();
            final MethodView method = methodFor(spec, target, reporter);
            if (method == null || !injectable(method, reporter)) {
                counts.merge(spec, 0, Integer::sum);
                // The observer hears about this too. A report that stayed silent here would show
                // "not woven yet" for a class that WAS woven — which is the one thing it must never
                // say, because it sends the reader looking for a driver that never offered the
                // class instead of at the selector that named a method the target does not have.
                nothing(internalName, entry);
                continue;
            }
            if (ConstantDescs.INIT_NAME.equals(method.name())) {
                constructors.computeIfAbsent(entry.weaveClassName(), key -> new ArrayList<>())
                        .add(method);
            }
            final CodeView body = method.code().orElseThrow();

            final Set<Integer> sites = new LinkedHashSet<>();
            for (final PointSpec point : spec.points()) {
                final List<Integer> here = new ArrayList<>();
                final List<Site> resolved =
                        resolveIsolated(method, body, spec, point, reporter);
                if (resolved == null) {
                    continue;
                }
                for (final Site site : resolved) {
                    here.add(site.index());
                    sites.add(site.index());
                }
                // Told now, not remembered for later. An index is meaningful only against the
                // body it came from, and that body is about to be rebuilt — so a report either
                // hears this here or never learns it.
                this.observer.resolved(new Resolution(internalName, entry.weaveClassName(),
                        spec.id(), Resolution.pointOf(point), spec.rawMethod(), here));
            }
            counts.merge(spec, sites.size(), Integer::sum);
            if (sites.isEmpty()) {
                continue;
            }

            final Injector injector = injectorFor(spec.kind().id(), reporter);
            if (injector == null) {
                reporter.report(Diagnostic.builder(DiagnosticCode.INTERNAL_ERROR)
                        .message("no injector is registered for kind '" + spec.kind().id() + '\'')
                        .build());
                continue;
            }
            if (!validates(spec.kind().id(), injector, entry, target, reporter)) {
                // Emitting after validation has already refused the declaration produces a class
                // that is wrong in a second, worse way: a non-static handler reported as AW1005 and
                // then called with `invokestatic` fails at link time, long after the diagnostic that
                // explained it. Found by building the second injector, which reports a shape error
                // that the argument binding then reported again in less useful words.
                continue;
            }

            final Map<Integer, HandlerBinding> bindings =
                    bindPerSite(injector, spec, method, body, sites, reporter);
            if (bindings == null) {
                continue;
            }
            byMethod.computeIfAbsent(method.name(), key -> new ArrayList<>())
                    .add(new Resolved(injector, entry, target, method, List.copyOf(sites),
                            bindings));
        }

        DelegationChains.report(target, constructors, reporter);

        if (!MatchAccounting.check(counts, groups, reporter)) {
            return null;
        }
        if (byMethod.isEmpty()) {
            return null;
        }

        final String contributed = contributedKinds(byMethod);
        if (contributed.isEmpty()) {
            return emit(model, internalName, byMethod, reporter);
        }
        return PluginIsolation.call(contributed, PluginIsolation.Phase.APPLY, reporter,
                () -> emit(model, internalName, byMethod, reporter)).orElse(null);
    }

    /**
     * Rewrites the class once, with every surviving declaration applied.
     *
     * <p>One body transform per target method name, composed into a single class transform: the
     * predicate handed to
     * {@link ClassTransform#transformingMethodBodies(java.util.function.Predicate, CodeTransform)}
     * selects by method name rather than descriptor, so a class with overloads sharing that name has
     * the transform applied to each of them. Each transform counts elements as it walks, and that
     * count belongs to one method, which is why it is built per method rather than shared.
     *
     * @param model        the class to rewrite; must not be {@code null}
     * @param internalName its internal name, for a diagnostic; must not be {@code null}
     * @param byMethod     the declarations to apply, grouped by target method name; must not be
     *                     {@code null}
     * @param reporter     where to report; must not be {@code null}
     * @return the woven bytes, or {@code null} when the writer refused the result
     */
    private byte @Nullable [] emit(@NotNull final ClassModel model,
                                   @NotNull final String internalName,
                                   @NotNull final Map<String, List<Resolved>> byMethod,
                                   @NotNull final Reporter reporter) {
        ClassTransform transform = ClassTransform.ACCEPT_ALL;
        for (final Map.Entry<String, List<Resolved>> group : byMethod.entrySet()) {
            final String methodName = group.getKey();
            final List<Emission> emissions = emissionsFor(group.getValue(), reporter);
            final ProtectedRanges ranges = rangesFor(group.getValue(), internalName, reporter);
            transform = transform.andThen(ClassTransform.transformingMethodBodies(
                    m -> methodName.equals(m.methodName().stringValue()),
                    // ofStateful, always: each transform counts elements, and that count belongs
                    // to one method.
                    CodeTransform.ofStateful(() -> compose(emissions, ranges))));
        }
        try {
            return ClassFile.of().transformClass(model, transform);
        } catch (final IllegalArgumentException refused) {
            // The Class-File API validates while it writes, so the one limit an injection can
            // realistically cross — 65535 bytes of code in one method — surfaces here as an
            // exception rather than as bytes anything could inspect afterwards. Letting it escape
            // would abort the whole build with a stack trace naming an internal writer.
            reporter.report(oversize(internalName, refused));
            return null;
        }
    }

    /**
     * Names the contributed kinds taking part in this class, for the isolation that wraps emission.
     *
     * <p>An empty answer is the signal to emit without isolation at all, which keeps a class woven
     * only by built-in kinds out of a containment that has nothing to contain.
     *
     * @param byMethod the declarations to apply, grouped by target method name; must not be
     *                 {@code null}
     * @return the namespaced kind identifiers, comma-separated, or an empty string where every kind
     *         is built in
     */
    @Contract(pure = true)
    @NotNull
    private static String contributedKinds(@NotNull final Map<String, List<Resolved>> byMethod) {
        final Set<String> kinds = new LinkedHashSet<>();
        for (final List<Resolved> group : byMethod.values()) {
            for (final Resolved resolved : group) {
                final String kind = resolved.entry().spec().kind().id();
                if (kind.indexOf(':') >= 0) {
                    kinds.add(kind);
                }
            }
        }
        return String.join(", ", kinds);
    }

    /**
     * Resolves one point, containing a contributed one that throws.
     *
     * <p>A built-in point is called directly, so a defect in the engine surfaces as itself rather
     * than as {@code AW3116} against a plugin that does not exist.
     *
     * @param method   the target method; must not be {@code null}
     * @param body     its body; must not be {@code null}
     * @param spec     the declaration being resolved; must not be {@code null}
     * @param point    the point to resolve; must not be {@code null}
     * @param reporter where to report; must not be {@code null}
     * @return the positions matched, empty where the point matched nothing, or {@code null} where a
     *         contributed point threw and was reported as {@code AW3116}
     */
    @Nullable
    private List<Site> resolveIsolated(@NotNull final MethodView method,
                                       @NotNull final CodeView body,
                                       @NotNull final InjectorSpec spec,
                                       @NotNull final PointSpec point,
                                       @NotNull final Reporter reporter) {
        final String contributed = contributedIn(spec, point);
        if (contributed == null) {
            return this.points.resolve(method, body, spec, point, reporter);
        }
        return PluginIsolation.call(contributed, PluginIsolation.Phase.PLANNING, reporter,
                () -> this.points.resolve(method, body, spec, point, reporter)).orElse(null);
    }

    /**
     * Names the contributed point this resolution will reach, if any.
     *
     * <p>Resolving a point resolves its slice first, and a slice bound is a point of its own that
     * the same call reaches. Choosing the guard on the declaration's own {@code @At} alone
     * therefore left one crossing uncovered: a built-in {@code @At} took the direct branch and
     * then called a contributed point as its bound, so a throw from that point left
     * {@link de.splatgames.aether.weaver.engine.Weaver#weave} with no {@code AW3116} and no
     * diagnostic of any kind.
     *
     * @param spec  the declaration being resolved; must not be {@code null}
     * @param point the point to resolve; must not be {@code null}
     * @return the identifier to attribute a failure to, or {@code null} when nothing contributed
     *         is reached
     */
    @Contract(pure = true)
    @Nullable
    private static String contributedIn(@NotNull final InjectorSpec spec,
                                        @NotNull final PointSpec point) {
        if (point.point().indexOf(':') >= 0) {
            return point.point();
        }
        for (final SliceSpec slice : spec.slices()) {
            if (!slice.id().equals(point.slice())) {
                continue;
            }
            for (final PointSpec bound : List.of(slice.from(), slice.to())) {
                if (bound.point().indexOf(':') >= 0) {
                    return bound.point();
                }
            }
        }
        return null;
    }

    /**
     * Turns the writer's refusal into a diagnostic, telling the one cause a weave can produce from
     * the ones it cannot.
     *
     * <p>The message is all there is to go on: the Class-File API validates while it writes, so what
     * arrives is an exception rather than bytes anything could inspect. A message naming a code
     * length is the 65535-byte limit on one method's code and is the author's to act on, reported as
     * {@code AW4003}; anything else is reported as {@code AW4004}, which asks for the class file
     * rather than for a change to the weave.
     *
     * @param internalName the class being woven; must not be {@code null}
     * @param refused      what the writer threw; must not be {@code null}
     * @return the diagnostic
     */
    @Contract(pure = true)
    @NotNull
    private static Diagnostic oversize(@NotNull final String internalName,
                                       @NotNull final IllegalArgumentException refused) {
        final String said = String.valueOf(refused.getMessage());
        if (!said.contains("Code length")) {
            return Diagnostic.builder(DiagnosticCode.STRUCTURAL_SELF_CHECK_FAILED)
                    .message(internalName + " could not be written after weaving")
                    .detail(said)
                    .remedy("a contributed injector writes through the same builder, so this "
                            + "is a defect in the engine or in a plugin rather than in the "
                            + "weave. Re-run with class dumps enabled and report the dump "
                            + "together with this message; if a plugin is loaded, try the run "
                            + "without it")
                    .build();
        }
        return Diagnostic.builder(DiagnosticCode.METHOD_TOO_LARGE)
                .message(internalName + " has a method that no longer fits after weaving")
                .detail(said)
                .remedy("a method's code is capped at 65535 bytes by the class file format, and "
                        + "the target was already close enough that the injection crossed it. The "
                        + "handler's own body costs nothing here — only the call does — so the "
                        + "usual fix is fewer injection points in that method, not a smaller "
                        + "handler")
                .build();
    }

    /**
     * Tells the observer that a declaration matched nothing, once for each of its points.
     *
     * <p>Reported for an entry that never reached resolution at all, because an observer that hears
     * nothing about such a class cannot tell it apart from a class that was never offered for
     * weaving — and those two send a reader looking in opposite directions.
     *
     * @param internalName the class being woven; must not be {@code null}
     * @param entry        the declaration that matched nothing; must not be {@code null}
     */
    private void nothing(@NotNull final String internalName,
                         @NotNull final PlanEntryView entry) {
        for (final PointSpec point : entry.spec().points()) {
            this.observer.resolved(new Resolution(internalName, entry.weaveClassName(),
                    entry.spec().id(), Resolution.pointOf(point), entry.spec().rawMethod(),
                    List.of()));
        }
    }

    /**
     * Asks an injector to check a declaration, and reports whether it may proceed.
     *
     * <p>Every diagnostic reaches the reporter whatever its severity, so an injector that finds
     * several faults costs one build rather than several; only an error withdraws the declaration.
     * Emitting one that has already been refused is what this exists to prevent: a handler reported
     * as unusable and then called anyway fails at link time, far from the diagnostic that explained
     * it.
     *
     * @param injector the injector to ask; must not be {@code null}
     * @param entry    the declaration being checked; must not be {@code null}
     * @param target   the class being woven; must not be {@code null}
     * @param reporter where the injector's diagnostics are forwarded; must not be {@code null}
     * @return {@code true} when nothing of error severity was reported
     */
    private static boolean validates(@NotNull final String kind,
                                     @NotNull final Injector injector,
                                     @NotNull final PlanEntryView entry,
                                     @NotNull final TargetView target,
                                     @NotNull final Reporter reporter) {
        final boolean[] refused = {false};
        final Reporter collecting = diagnostic -> {
            refused[0] |= diagnostic.severity() == Severity.ERROR;
            reporter.report(diagnostic);
        };
        if (!isContributed(kind)) {
            injector.validate(entry, target, collecting);
            return !refused[0];
        }
        // A contributed injector's validate() is its code, and a throw from it used to leave
        // Weaver.weave with no diagnostic. Refusing the declaration is what the engine does when
        // validate reports an error, so it is also what it does when validate cannot answer.
        return PluginIsolation.call(kind, PluginIsolation.Phase.APPLY, reporter, () -> {
            injector.validate(entry, target, collecting);
            return !refused[0];
        }).orElse(false);
    }

    /**
     * Asks an injector how many operands a site supplies, containing a contributed one that
     * throws.
     *
     * @param injector the injector to ask; must not be {@code null}
     * @param spec     the declaration; must not be {@code null}
     * @param method   the target method; must not be {@code null}
     * @param body     its body; must not be {@code null}
     * @param site     the position; must not be {@code null}
     * @param reporter where to report; must not be {@code null}
     * @return the count, or {@code null} where a contributed injector threw
     */
    @Nullable
    private static Integer operandsAt(@NotNull final Injector injector,
                                      @NotNull final InjectorSpec spec,
                                      @NotNull final MethodView method,
                                      @NotNull final CodeView body,
                                      final int site,
                                      @NotNull final Reporter reporter) {
        if (!isContributed(spec.kind().id())) {
            return injector.stackOperandsAt(spec, method, body, site);
        }
        return PluginIsolation.call(spec.kind().id(), PluginIsolation.Phase.APPLY, reporter,
                () -> injector.stackOperandsAt(spec, method, body, site)).orElse(null);
    }

    /**
     * Resolves an injector, containing a contributed factory that throws.
     *
     * @param kind     the identifier the declaration carries; must not be {@code null}
     * @param reporter where to report; must not be {@code null}
     * @return the injector, or {@code null} where none is registered or the factory threw
     */
    @Nullable
    private Injector injectorFor(@NotNull final String kind, @NotNull final Reporter reporter) {
        if (!isContributed(kind)) {
            return this.injectors.apply(kind);
        }
        return PluginIsolation.call(kind, PluginIsolation.Phase.APPLY, reporter,
                () -> this.injectors.apply(kind)).orElse(null);
    }

    /**
     * Reports whether an identifier names something a plugin contributed.
     *
     * <p>The built-in kinds hold the unqualified namespace, so a colon is what separates a
     * plugin's code from the engine's. Built-ins are called directly on purpose: a defect in the
     * engine has to surface as itself rather than as {@code AW3117} against a plugin that does
     * not exist.
     *
     * @param id the identifier; must not be {@code null}
     * @return whether the identifier carries a namespace
     */
    @Contract(pure = true)
    private static boolean isContributed(@NotNull final String id) {
        return id.indexOf(':') >= 0;
    }

    /**
     * Binds the handler at every position the declaration matched.
     *
     * <p>The binding depends on the position only through the number of operands the site supplies,
     * so it is computed once per distinct count and shared. A count whose binding was refused is
     * remembered, which is what keeps one unusable handler from reporting the same fault once per
     * position.
     *
     * <p>Captured locals are the exception: they depend on what is live at the position, so they are
     * resolved per position and attached to the shared binding. A failure there does not stop the
     * loop, so a declaration failing at three of its positions says so three times, each naming what
     * was live at that one.
     *
     * <p>The local variable table is read only where the declaration captures something, since a
     * declaration that captures nothing has no use for it.
     *
     * @param injector the injector, asked how many operands each position supplies; must not be
     *                 {@code null}
     * @param spec     the declaration being bound; must not be {@code null}
     * @param method   the target method; must not be {@code null}
     * @param body     its body; must not be {@code null}
     * @param sites    the positions it matched; must not be {@code null}
     * @param reporter where to report a handler that does not fit; must not be {@code null}
     * @return the binding at each position, or {@code null} when any position could not be bound,
     *         which withdraws the whole declaration
     */
    @Nullable
    private static Map<Integer, HandlerBinding> bindPerSite(@NotNull final Injector injector,
                                                            @NotNull final InjectorSpec spec,
                                                            @NotNull final MethodView method,
                                                            @NotNull final CodeView body,
                                                            @NotNull final Set<Integer> sites,
                                                            @NotNull final Reporter reporter) {
        final LocalTable locals = spec.locals().isEmpty()
                ? LocalTable.empty()
                : LocalTable.of(body.elements());
        final Map<Integer, HandlerBinding> byOperandCount = new LinkedHashMap<>();
        final Set<Integer> refusedCounts = new LinkedHashSet<>();

        final Map<Integer, HandlerBinding> bindings = new LinkedHashMap<>();
        boolean resolved = true;
        for (final int site : sites) {
            final Integer answered = operandsAt(injector, spec, method, body, site, reporter);
            if (answered == null) {
                // A contributed injector threw where it was asked how deep the stack is. It has
                // been reported as AW3117; withdrawing the declaration is what the engine does
                // for every other refusal at this point.
                return null;
            }
            final int operands = answered;
            if (refusedCounts.contains(operands)) {
                resolved = false;
                continue;
            }
            HandlerBinding binding = byOperandCount.get(operands);
            if (binding == null) {
                binding = HandlerBinding.bind(spec.handler(), method, spec.locals(), operands,
                        reporter);
                if (binding == null) {
                    refusedCounts.add(operands);
                    resolved = false;
                    continue;
                }
                byOperandCount.put(operands, binding);
            }
            final List<HandlerBinding.Load> captures =
                    LocalCaptures.resolve(spec, method, locals, site, reporter);
            if (captures == null) {
                // Keep going so that an injection failing at three of its sites says so three
                // times, each naming what WAS live there.
                resolved = false;
                continue;
            }
            bindings.put(site, binding.withCaptures(captures));
        }
        return resolved ? Map.copyOf(bindings) : null;
    }

    /**
     * Builds the transform that rewrites one method with every declaration on it.
     *
     * <p>Each injector is asked for its emitter through the SPI rather than through whatever wider
     * method the built-in implementation happens to offer, so a built-in kind and a contributed one
     * travel the same path and a gap in the SPI is visible from inside the engine.
     *
     * <p>The transform is stateful: it counts the elements it is offered, and that count is the
     * coordinate every emitter was given.
     *
     * @param emissions the declarations on this method, each with its context; must not be
     *                  {@code null}
     * @param ranges    how the method's protected ranges have to be cut; must not be {@code null}
     * @return the transform
     */
    @NotNull
    private static CodeTransform compose(@NotNull final List<Emission> emissions,
                                         @NotNull final ProtectedRanges ranges) {
        final List<Injector.Emitter> emitters = emissions.stream()
                // Through the SPI, exactly as a contributed injector is reached. Calling the
                // built-in through a wider concrete method is how the SPI's missing pieces stayed
                // invisible.
                .map(emission -> emission.injector().emitter(emission.context()))
                .toList();

        return new CodeTransform() {

            /** How many elements have been offered, which is the coordinate the emitters use. */
            private int index;

            /**
             * The exception-table entries held back while ranges are being cut, in the order the
             * body holds them — the order {@code ProtectedRanges} indexes its pieces by.
             */
            private final List<ExceptionCatch> deferred = new ArrayList<>();

            /** Where each cut range stops, bound before the injected code at that position. */
            private final Map<Integer, java.lang.classfile.Label> pause = new LinkedHashMap<>();

            /** Where each cut range starts again, bound after the injected code. */
            private final Map<Integer, java.lang.classfile.Label> resume = new LinkedHashMap<>();

            /**
             * Offers one element to every emitter and binds the labels a cut range needs.
             *
             * <p>The index is taken before anything else, so an exception-table entry withheld here
             * still consumes its position and the indices the emitters were given remain the ones
             * they matched against.
             *
             * <p>A single {@code REPLACE} suppresses the element for every emitter, which is what
             * lets a redirect and an injection share a position: both write, and the operation is
             * gone. Where that happens at a position a range was to be cut around, no resume label
             * is bound and the pause label is dropped again, so neither bound of the pieces meeting
             * at that position exists and {@code atEnd} emits neither of them.
             *
             * @param builder where instructions are appended
             * @param element the element being offered
             */
            @Override
            public void accept(final java.lang.classfile.CodeBuilder builder,
                               final CodeElement element) {
                final int at = this.index++;
                if (ranges.splits() && element instanceof final ExceptionCatch handler) {
                    this.deferred.add(handler);
                    return;
                }
                final boolean splitting = ranges.splitAt().contains(at);
                if (splitting) {
                    this.pause.put(at, builder.newBoundLabel());
                }
                boolean keep = true;
                for (final Injector.Emitter emitter : emitters) {
                    if (emitter.emitAt(builder, element, at) == Injector.Disposition.REPLACE) {
                        // Any REPLACE wins. A redirect replaces an operation; an injection that also
                        // matched that position still emits, and the operation is still gone —
                        // which is what both of them asked for.
                        keep = false;
                    }
                }
                if (splitting && keep) {
                    this.resume.put(at, builder.newBoundLabel());
                } else if (splitting) {
                    // Something replaced the element here, so this position is not purely added
                    // code: whatever stands in for the operation belongs inside the target's range
                    // exactly as the operation did. Forgetting the pause leaves the range whole.
                    this.pause.remove(at);
                }
                if (keep) {
                    builder.accept(element);
                }
            }

            /**
             * Writes the exception table back, cut where it had to be.
             *
             * <p>A handler with no pieces is re-emitted exactly as it arrived, which covers both a
             * handler no injection touched and one whose bounds this body does not resolve. A piece
             * is written only where both of its labels were bound.
             *
             * @param builder where the entries are appended
             */
            @Override
            public void atEnd(final java.lang.classfile.CodeBuilder builder) {
                for (int ordinal = 0; ordinal < this.deferred.size(); ordinal++) {
                    final ExceptionCatch handler = this.deferred.get(ordinal);
                    final List<ProtectedRanges.Piece> pieces = ranges.piecesOf(ordinal);
                    if (pieces.isEmpty()) {
                        builder.exceptionCatch(handler.tryStart(), handler.tryEnd(),
                                handler.handler(), handler.catchType());
                        continue;
                    }
                    for (final ProtectedRanges.Piece piece : pieces) {
                        final java.lang.classfile.Label from = piece.from() == null
                                ? handler.tryStart()
                                : this.resume.get(piece.from());
                        final java.lang.classfile.Label to = piece.to() == null
                                ? handler.tryEnd()
                                : this.pause.get(piece.to());
                        if (from != null && to != null) {
                            builder.exceptionCatch(from, to, handler.handler(),
                                    handler.catchType());
                        }
                    }
                }
            }
        };
    }

    /**
     * Reports whether a method can be injected into, and says why not when it cannot.
     *
     * <p>Three refusals, each with its own code because the remedies differ: {@code AW1025} for a
     * native method, whose implementation is not a class file at all; {@code AW1023} for one with no
     * body; {@code AW1024} for a compiler-generated method, which has a body and would weave, but
     * whose shape is not the author's to rely on. A native method is tested first because it is
     * bodyless as well, and the remedy {@code AW1023} carries — name an implementing method — is of
     * no use to someone whose method is implemented outside the class file.
     *
     * @param method   the target method; must not be {@code null}
     * @param reporter where to report; must not be {@code null}
     * @return {@code true} when the method has a body the engine will rewrite
     */
    private static boolean injectable(@NotNull final MethodView method,
                                      @NotNull final Reporter reporter) {
        if (method.isNative()) {
            reporter.report(Diagnostic.builder(DiagnosticCode.TARGET_METHOD_NATIVE)
                    .message(method.describe() + " is native; its implementation is not a class "
                            + "file, so there is nothing here to inject into")
                    .remedy("inject into the Java method that calls it, or use @Redirect at the "
                            + "call site to intercept the transition")
                    .build());
            return false;
        }
        if (method.code().isEmpty()) {
            reporter.report(Diagnostic.builder(DiagnosticCode.TARGET_METHOD_ABSTRACT)
                    .message(method.describe() + " has no body to inject into")
                    .remedy("name an implementing method instead; an abstract declaration says "
                            + "what happens, not how")
                    .build());
            return false;
        }
        if (method.isSynthetic()) {
            reporter.report(Diagnostic.builder(DiagnosticCode.TARGET_METHOD_SYNTHETIC)
                    .message(method.describe() + " is compiler-generated (synthetic or a bridge)")
                    .detail("it has a body and the injection would work; what it would not do is "
                            + "survive a recompilation that changes the generated shape")
                    .remedy("name the method the author wrote — for a bridge, the one with the "
                            + "specific parameter types; for a lambda body, the method containing "
                            + "the lambda")
                    .build());
            return false;
        }
        return true;
    }

    /**
     * Finds the one method a declaration names.
     *
     * <p>Matching goes through the parsed selector wherever the declaration carries one, so an
     * overload is told apart by the parameter types the author wrote. A selector that is not a
     * method selector falls back to the name alone, taken from the raw text up to its first bracket.
     *
     * <p>Reports {@code AW1020} with every method of the target listed when nothing matches, and
     * {@code AW1021} with the candidates listed when several do.
     *
     * @param spec     the declaration naming the method; must not be {@code null}
     * @param target   the class being woven; must not be {@code null}
     * @param reporter where to report; must not be {@code null}
     * @return the method, or {@code null} when the selector matched no method or several
     */
    @Nullable
    private static MethodView methodFor(@NotNull final InjectorSpec spec,
                                        @NotNull final TargetView target,
                                        @NotNull final Reporter reporter) {
        final String raw = spec.rawMethod();
        final String name = raw.contains("(") ? raw.substring(0, raw.indexOf('(')) : raw;

        // The PARSED selector, not the raw text chopped at its first bracket. Matching on the
        // name alone cannot tell two overloads apart, so an overloaded target resolved to "several
        // methods" whatever the author wrote — and the remedy told them to add parameter types that
        // were being discarded one line earlier.
        final List<MethodView> matches = target.methods().stream()
                .filter(method -> spec.method() instanceof final MethodSelector selector
                        ? Targets.selects(selector, method.name(), target.internalName(),
                                method.type())
                        : method.name().equals(name))
                .toList();
        if (matches.size() == 1) {
            return matches.getFirst();
        }
        if (matches.isEmpty()) {
            reporter.report(Diagnostic.builder(DiagnosticCode.METHOD_NOT_FOUND)
                    .message("no method '" + name + "' on " + target.binaryName())
                    .details(target.methods().stream().map(MethodView::describe).sorted().toList())
                    .remedy("check the selector against the listing above")
                    .build());
            return null;
        }
        reporter.report(Diagnostic.builder(DiagnosticCode.SELECTOR_AMBIGUOUS)
                .message("'" + raw + "' matches " + matches.size() + " methods on "
                        + target.binaryName())
                .details(matches.stream().map(MethodView::describe).sorted().toList())
                .remedy("add the parameter types, or use the desc: form to pin one exactly")
                .build());
        return null;
    }

    /**
     * Works out how the protected ranges of one target-method-name group have to be cut, and
     * reports it when they are.
     *
     * <p>{@code resolved} is grouped by target method name, not by descriptor (see {@link #emit}):
     * {@link #methodFor} can resolve declarations sharing that name to different overloads, and all
     * of them land in the same group. The cut is nonetheless computed against a single body —
     * {@code resolved.getFirst().method()} — so any declaration in the group whose own
     * {@code method()} is not identical to that one is resolved against the wrong body.
     *
     * <p>Only positions of the {@code inject} kind count as insertions. A kind that replaces an
     * operation needs no cut, since whatever stands in for the operation belongs inside the target's
     * range exactly as the operation did; a contributed kind that does add code inside a protected
     * range is not recognised here and leaves the range whole.
     *
     * <p>A cut is reported as {@code AW1131} rather than refused: the weave is correct, and what
     * changes is which exceptions the target observes, which the author has to know to decide
     * whether the weave should catch its own failures.
     *
     * @param resolved     the declarations grouped under one target method name; must not be empty
     *                     when any of them injects, and must not be {@code null}
     * @param internalName the class being woven, for the message; must not be {@code null}
     * @param reporter     where to report; must not be {@code null}
     * @return the cut, holding nothing when no injection falls inside a protected range
     */
    @NotNull
    private static ProtectedRanges rangesFor(@NotNull final List<Resolved> resolved,
                                             @NotNull final String internalName,
                                             @NotNull final Reporter reporter) {
        final Set<Integer> insertions = new LinkedHashSet<>();
        for (final Resolved one : resolved) {
            if (InjectorKind.INJECT.equals(one.entry().spec().kind())) {
                insertions.addAll(one.sites());
            }
        }
        if (insertions.isEmpty()) {
            return ProtectedRanges.of(List.of(), Set.of());
        }
        final MethodView method = resolved.getFirst().method();
        final ProtectedRanges ranges =
                ProtectedRanges.of(method.code().orElseThrow().elements(), insertions);
        if (ranges.splits()) {
            reporter.report(Diagnostic.builder(DiagnosticCode.PROTECTED_RANGE_SPLIT)
                    .message(internalName + '.' + method.name() + " has "
                            + ranges.splitHandlers()
                            + (ranges.splitHandlers() == 1
                                    ? " protected range split around injected code"
                                    : " protected ranges split around injected code"))
                    .detail("the target's own catch blocks no longer cover the handler calls, so a "
                            + "handler that throws is not silently caught by code that was written "
                            + "for the target's own failures")
                    .remedy("nothing needs doing; this is reported because it changes which "
                            + "exceptions the target observes, and a weave that meant to be caught "
                            + "by the target has to catch its own")
                    .build());
        }
        return ranges;
    }

    /**
     * Builds one emission per declaration on this method, each knowing what else matched its
     * positions.
     *
     * <p>The sharing map is built once and given to every context, so each declaration sees the same
     * account of who else is at a position and in the same order. That order is the order the
     * declarations arrived in, which is what decides the outermost of several wraps at one position.
     *
     * @param resolved the declarations on this method; must not be {@code null}
     * @param reporter where an injector reports a problem found while emitting; must not be
     *                 {@code null}
     * @return the emissions, in the order the declarations arrived
     */
    @NotNull
    private static List<Emission> emissionsFor(@NotNull final List<Resolved> resolved,
                                               @NotNull final Reporter reporter) {
        final Map<Integer, List<PlanEntryView>> sharing = new LinkedHashMap<>();
        for (final Resolved one : resolved) {
            for (final int site : one.sites()) {
                sharing.computeIfAbsent(site, key -> new ArrayList<>()).add(one.entry());
            }
        }
        final Map<Integer, List<PlanEntryView>> frozen = new LinkedHashMap<>();
        sharing.forEach((site, entries) -> frozen.put(site, List.copyOf(entries)));

        final List<Emission> emissions = new ArrayList<>(resolved.size());
        for (final Resolved one : resolved) {
            emissions.add(new Emission(one.injector(),
                    new Context(one.entry(), one.target(), one.method(), one.sites(),
                            one.bindings(), Map.copyOf(frozen), reporter)));
        }
        return List.copyOf(emissions);
    }

    /**
     * One declaration that survived the first phase, with everything emission needs.
     *
     * @param injector the injector that will emit for it
     * @param entry    the declaration
     * @param target   the class being woven
     * @param method   the target method it resolved to
     * @param sites    the element indices it matched, each once
     * @param bindings the handler binding at each of those indices
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private record Resolved(@NotNull Injector injector,
                            @NotNull PlanEntryView entry,
                            @NotNull TargetView target,
                            @NotNull MethodView method,
                            @NotNull List<Integer> sites,
                            @NotNull Map<Integer, HandlerBinding> bindings) {
    }

    /**
     * An injector paired with the context it is to be asked for an emitter with.
     *
     * @param injector the injector
     * @param context  what it is told about the method it is emitting into
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private record Emission(@NotNull Injector injector, @NotNull InjectionContext context) {
    }

    /**
     * What one declaration is told while it emits into one method.
     *
     * <p>Everything here is settled before the rewrite begins and is shared unchanged across the
     * overloads the transform is applied to, so a context describes the method the declaration
     * resolved to rather than the method currently being walked.
     *
     * @param entry       the declaration being emitted
     * @param target      the class being woven
     * @param method      the method the declaration resolved to
     * @param sites       the element indices it matched
     * @param bindings    the handler binding at each of those indices
     * @param sharing     every declaration at each position of this method, in arrival order
     * @param diagnostics where a problem found while emitting is reported
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private record Context(@NotNull PlanEntryView entry,
                           @NotNull TargetView target,
                           @NotNull MethodView method,
                           @NotNull List<Integer> sites,
                           @NotNull Map<Integer, HandlerBinding> bindings,
                           @NotNull Map<Integer, List<PlanEntryView>> sharing,
                           @NotNull Reporter diagnostics) implements InjectionContext {

        /**
         * Returns every declaration that matched one element of this method, including this one.
         *
         * @param site the element index to ask about
         * @return the declarations there, in arrival order, or an empty list for an element no
         *         declaration matched
         */
        @Override
        @NotNull
        public List<PlanEntryView> entriesAt(final int site) {
            return this.sharing.getOrDefault(site, List.of());
        }

        /**
         * Returns the binding computed for one of this declaration's positions.
         *
         * @param site one of this declaration's own element indices
         * @return the binding there
         * @throws IllegalArgumentException if the index is not one of this declaration's positions,
         *                                  which is a defect in the injector that asked
         */
        @Override
        @NotNull
        public HandlerBinding argumentsAt(final int site) {
            final HandlerBinding binding = this.bindings.get(site);
            if (binding == null) {
                throw new IllegalArgumentException(
                        "element " + site + " is not a site of this injection: " + this.sites);
            }
            return binding;
        }

        /**
         * Returns the target method's return type.
         *
         * @return the return type of the method this declaration resolved to
         */
        @Override
        @NotNull
        public java.lang.constant.ClassDesc returnType() {
            return this.method.type().returnType();
        }
    }
}
