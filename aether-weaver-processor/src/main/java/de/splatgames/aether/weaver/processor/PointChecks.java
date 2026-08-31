package de.splatgames.aether.weaver.processor;

import de.splatgames.aether.weaver.api.model.InjectorSpec;
import de.splatgames.aether.weaver.api.model.PointSpec;
import de.splatgames.aether.weaver.api.spi.CodeView;
import de.splatgames.aether.weaver.api.spi.MethodView;
import de.splatgames.aether.weaver.api.spi.Reporter;
import de.splatgames.aether.weaver.api.spi.TargetView;
import de.splatgames.aether.weaver.engine.inject.MatchAccounting;
import de.splatgames.aether.weaver.engine.inject.point.BuiltInPoints;
import de.splatgames.aether.weaver.engine.inject.point.ModelViews;
import de.splatgames.aether.weaver.api.spi.Site;
import de.splatgames.aether.weaver.engine.inject.point.PointResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.ExecutableElement;
import java.lang.classfile.ClassModel;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Resolves a declaration's injection points against the target's compiled bytecode, at compile
 * time.
 *
 * <p>The only check in this package that reads a class file rather than the compiler's source
 * model, and the only one that can answer whether a well-formed declaration naming things that all
 * exist still matches no instruction. It runs once per target and once per injection, after the
 * handler has been checked against that target, and only where the target's class file could be
 * read: a target compiled in the same round is reported as {@code AW1200} and skipped.
 *
 * <p>Nothing is reported from here. Every diagnostic a user sees is raised by
 * {@link de.splatgames.aether.weaver.engine.inject.point.PointResolver}, by the point it dispatched
 * to, or by {@link de.splatgames.aether.weaver.engine.inject.MatchAccounting}, and is handed to the
 * {@link MessagerReporter} through a {@link Reporter} that adds a position. A refusal at compile
 * time is therefore ordinarily the refusal the weaver would have raised, worded the same way and
 * under the same code, rather than a second opinion about it — {@link #run} documents where a
 * slice combined with an ordinal, and an omitted {@code require}, break that equivalence.
 *
 * <p>A {@code @Wrap} declaration breaks the equivalence a different way: {@link SourceSpecs}
 * builds its specification with {@code InjectorKind.INJECT}, while
 * {@link de.splatgames.aether.weaver.engine.parse.WeaveClassParser} builds the same annotation
 * with {@code InjectorKind.WRAP}, and the resolver's own safety check branches on that kind. A
 * site this class refuses under {@code AW1105}, {@code AW1026} or {@code AW1130} — the checks
 * {@code INJECT} is subject to — is one the weaver never asks that question about, because at
 * weave time a {@code @Wrap} is routed to the check that treats it as standing in for the
 * operation it wraps.
 *
 * <p>The registry handed to the resolver is {@link BuiltInPoints#all()} and nothing else. A
 * {@code @At(custom = "namespace:NAME")} naming a contributed point is therefore unknown at compile
 * time and is refused as {@code AW1101}, which is an error and fails the build, whether or not the
 * plugin that registers that point is on the runtime classpath.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
final class PointChecks {

    /**
     * Refuses instantiation; the single entry point is static.
     *
     * @throws AssertionError always
     */
    private PointChecks() {
        throw new AssertionError("no instances");
    }

    /**
     * Resolves every point of one declaration against one target's bytes and accounts the matches.
     *
     * <p>Returns having reported nothing when the target's methods do not yield exactly one method
     * of the selector's name — see {@code methodOf} — or when that method has no body. In the
     * second case the source model has, in every constructible instance, already reported some
     * diagnostic of its own about the same handler — typically {@code AW1023} or {@code AW1025}
     * for a method the source model resolved but found to have no code, though a handler whose own
     * resolution failed for a different reason can already carry a different code instead — so
     * repeating one from the bytecode would only restate a fault that was already reported. In the
     * first case, whether {@code methodOf} answers {@code null} and whether the source model
     * reported {@code AW1020} are independent of each other: the two compare the selector against
     * the target differently, so one can happen without the other, and this declaration's points
     * go unchecked here either way, with nothing further said about them.
     *
     * <p>The points of the declaration are resolved one at a time and their results pooled by
     * instruction index, so two points landing on the same instruction count as one match rather
     * than two. What the resolver can report at this stage is what it reports at weave time:
     * {@code AW1101} for an unregistered point identifier, {@code AW1043} for a point that requires
     * a target and was given none or forbids one and was given it, {@code AW1102} for a shift the
     * point refuses, {@code AW1110} for an ordinal past the last match, {@code AW1111} for a shift
     * that leaves the range it was found in, {@code AW1112} for a large {@code shift = BY}, and the
     * point's own {@code AW1043} for a target that matched nothing or {@code AW1103} for a selector
     * that also matched something reached through an {@code invokedynamic}. Sites that resolve but
     * may not be injected at are dropped individually with {@code AW1026}, {@code AW1061},
     * {@code AW1105} or {@code AW1130}.
     *
     * <p>The slice diagnostics {@code AW1120}, {@code AW1121} and {@code AW1122} cannot arise here,
     * and no slice narrows anything: the specification handed in carries no slices whatever the
     * annotation declared, so every point searches the whole method and an ordinal is counted over
     * the whole method. At weave time that ordinal is counted within the slice, so a declaration
     * combining a slice with an ordinal is resolved here against a position the weaver need not
     * agree with.
     *
     * <p>The pooled count is then offered to
     * {@link de.splatgames.aether.weaver.engine.inject.MatchAccounting#check} with no groups
     * declared, which has two consequences. A declaration naming a {@code group} is not accounted
     * at all here — neither {@code AW1043} nor {@code AW1044} can come from it, because the group's
     * total is a fact about the whole weave and this call sees one declaration. And the
     * {@code require} it is checked against is the number the source wrote, which is {@code 0} when
     * the element was omitted, so an omitted {@code require} never produces {@code AW1043} at
     * compile time even though the weaver's own reading of the same annotation turns it into one.
     * {@code AW1044} is reported as usual for a non-zero {@code allow} that was exceeded; narrow
     * the declaration with an ordinal or a slice.
     *
     * <p>Every one of those is anchored on the {@code method} selector literal, whatever element
     * the fault is really in. With two {@code @At} points on one handler the position has to say
     * which injection failed, and a diagnostic naming the point in its text is easier to act on
     * than a caret on a shared annotation.
     *
     * @param handler    the handler the declaration is written on; must not be {@code null}
     * @param injection  the injection annotation's mirror, used to position the report inside it;
     *                   must not be {@code null}
     * @param spec       the declaration, already built from that mirror; must not be {@code null}
     * @param target     the target's parsed class file; must not be {@code null}
     * @param targetName the target's qualified name, checked for {@code null} and not read
     * @param reporter   where to report; must not be {@code null}
     * @throws NullPointerException if {@code handler}, {@code spec}, {@code target},
     *                              {@code targetName} or {@code reporter} is {@code null}
     */
    static void run(@NotNull final ExecutableElement handler,
                    @NotNull final AnnotationMirror injection,
                    @NotNull final InjectorSpec spec,
                    @NotNull final ClassModel target,
                    @NotNull final String targetName,
                    @NotNull final MessagerReporter reporter) {
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(targetName, "targetName");
        Objects.requireNonNull(reporter, "reporter");

        // Anchored on the selector literal, not on the handler: with two @At points on one
        // handler the caret has to say which injection failed, and the selector is the thing the
        // author would change.
        final Anchor anchor = Anchor.at(handler, injection, Anchors.valueOf(injection, "method"));
        final Reporter bridge = diagnostic -> reporter.report(diagnostic, anchor);

        final MethodView method = methodOf(target, spec);
        if (method == null) {
            // The source model already refused this under AW1020, at a better position and with
            // the target's real methods listed. Saying it again from the bytecode would be the
            // same fact twice, differently worded.
            return;
        }
        final CodeView code = method.code().orElse(null);
        if (code == null) {
            // Likewise AW1023 or AW1025, already reported against the declaration.
            return;
        }

        final Set<Integer> sites = new LinkedHashSet<>();
        for (final PointSpec point : spec.points()) {
            for (final Site site : new PointResolver(BuiltInPoints.all()::get)
                    .resolve(method, code, spec, point, bridge)) {
                sites.add(site.index());
            }
        }

        // No second "nothing matched" is added here. The resolver already reports AW1043 when a
        // point selects nothing, and it does so far better than this class could — it lists what it
        // DID find in the method, which is the difference between a refusal and a fix. A summary
        // alongside it would be the same fault stated twice, the second time with less to go on.
        final Map<InjectorSpec, Integer> counts = new LinkedHashMap<>();
        counts.put(spec, sites.size());
        MatchAccounting.check(counts, java.util.List.of(), bridge);
    }

    /**
     * Finds the one method of the target whose name equals the selector's raw text, up to that
     * text's first {@code (}.
     *
     * <p>The match is on the name alone. The raw text is cut at its first {@code (} and compared
     * literally, character for character, against every declared method's name, so the parameter
     * list plays no part in choosing between overloads. This literal text is exactly what the
     * annotation wrote, never what a parsed selector reduces it to, so anything the source model
     * strips or resolves away before comparing names — an owner prefix, a {@code desc:} or
     * {@code src:} prefix, a wildcard marker — survives into this comparison and keeps it from
     * matching a method the source model matched without complaint: {@code fixture.Target.run()}
     * yields the name {@code fixture.Target.run}, which no method has, and {@code desc:run()V} or
     * {@code src:run()} yield {@code desc:run} and {@code src:run}, which no method has either. A
     * name the target declares more than once answers {@code null} for a different reason, once
     * the literal text does match: the comparison finds two candidates and refuses to choose.
     * Whether this method answers {@code null} for either reason and whether the source model has
     * already reported {@code AW1020} about the same handler are independent of each other: the
     * source model does not compare names the way this method does — a wildcard marker, for
     * instance, is a name no method has literally, so it matches nothing here, while the source
     * model's own comparison treats it as matching every declared method instead of comparing it
     * as text at all — so a literal text matching nothing declared here can correspond to a source
     * model report under {@code AW1020}, under a different code entirely, or under none at all,
     * depending on how the same selector resolves there.
     *
     * <p>{@link ModelViews#of(ClassModel)} reads the whole class eagerly, including a copy of every
     * method's element list, and this runs once per injection rather than once per target.
     *
     * @param target the target's parsed class file; must not be {@code null}
     * @param spec   the declaration whose raw selector names the method; must not be {@code null}
     * @return the single method of that name, or {@code null} when the target declares none or
     *         several
     */
    @Nullable
    private static MethodView methodOf(@NotNull final ClassModel target,
                                       @NotNull final InjectorSpec spec) {
        final String raw = spec.rawMethod();
        final String name = raw.contains("(") ? raw.substring(0, raw.indexOf('(')) : raw;

        final TargetView view = ModelViews.of(target);
        MethodView found = null;
        for (final MethodView candidate : view.methods()) {
            if (!candidate.name().equals(name)) {
                continue;
            }
            if (found != null) {
                return null;
            }
            found = candidate;
        }
        return found;
    }
}
