package de.splatgames.aether.weaver.processor;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.Elements;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Resolves what a {@code @Weave} names into the target types every later check runs against.
 *
 * <p>Targets reach the annotation two ways, as class literals in {@code value} and as strings in
 * {@code targets}, and both are read here. A literal has already been resolved by the compiler; a
 * string has not, which is what makes {@code AW1009} this class's business alone — only the
 * processor knows what the compile classpath holds. {@code AW1004} is reported here as well, for a
 * name not on that classpath, but it is not exclusive to this class: the engine reports the same
 * code again at weave time, from
 * {@link de.splatgames.aether.weaver.engine.parse.WeaveClassParser}, for a name that is not even a
 * usable binary class name, a narrower fault this class never checks for.
 *
 * <p>Each resolved target is paired with the anchor of the literal that named it, so a diagnostic
 * about the second of three targets underlines the second of three literals rather than the whole
 * annotation. Two checks read that anchor: {@link #check} in this class, for {@code AW1087}, and
 * {@code WeaveProcessor.checkPoints}, for {@code AW1200}. The member and handler checks that also
 * run per target anchor their own reports on their own selector or member instead, and never read
 * this one.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
final class SourceTargets {

    /**
     * Refuses instantiation; the single entry point is static.
     *
     * @throws AssertionError always
     */
    private SourceTargets() {
        throw new AssertionError("no instances");
    }

    /**
     * Resolves the weave's targets, reporting what cannot be resolved and what should not be
     * targeted.
     *
     * <p>Class literals come first, in source order, then names, so the order of the result is not
     * the order the two forms were written in. A literal whose value is not a declared type is
     * dropped without a diagnostic, the compiler having already refused it.
     *
     * <p>A name that resolves is accepted and reported as {@code AW1009}: the class is on the
     * compile classpath, so a class literal would be checked by the compiler, follow a rename and
     * survive a move between packages. That notice is informational and is reported whatever
     * {@code require} says, including for a weave declaring {@code require = Require.OPTIONAL}.
     *
     * <p>A name that does not resolve is reported as {@code AW1004} and dropped — check the
     * spelling, or declare {@code require = Require.OPTIONAL} where the target is deliberately
     * absent at compile time. {@code Require.OPTIONAL} suppresses only that one diagnostic: a
     * named target that still does not resolve is dropped regardless, its members and handlers
     * never checked at compile time, but a target declared {@code require = Require.OPTIONAL}
     * that does resolve is added like any other and is checked by every later pass exactly as a
     * required target would be.
     *
     * <p>This is called after {@code WeaveProcessor} has already reported {@code AW1002} for a
     * weave that wrote both forms, and resolves both forms regardless. A class named as a literal
     * and again as a string therefore appears twice in the result, is checked twice, and is
     * recorded twice in the manifest's target list — unlike the engine's own reading, which gives
     * such a weave no targets at all.
     *
     * @param weave    the weave class; must not be {@code null}
     * @param mirror   its {@code @Weave} mirror; must not be {@code null}
     * @param elements the element utilities, used to resolve a named target; must not be
     *                 {@code null}
     * @param reporter where to report; must not be {@code null}
     * @return the usable targets with their anchors, as an unmodifiable list, empty when nothing
     *         the weave named resolved to a target that may be woven
     * @throws NullPointerException if any argument is {@code null}
     */
    @NotNull
    @Unmodifiable
    static List<Resolved> of(@NotNull final TypeElement weave,
                             @NotNull final AnnotationMirror mirror,
                             @NotNull final Elements elements,
                             @NotNull final MessagerReporter reporter) {
        Objects.requireNonNull(weave, "weave");
        Objects.requireNonNull(mirror, "mirror");
        Objects.requireNonNull(elements, "elements");
        Objects.requireNonNull(reporter, "reporter");

        final List<Resolved> resolved = new ArrayList<>();
        for (final AnnotationValue literal : Anchors.arrayOf(mirror, "value")) {
            final TypeElement target = elementOf(literal);
            if (target != null) {
                resolved.add(new Resolved(target, Anchor.at(weave, mirror, literal)));
            }
        }

        final boolean optional = "OPTIONAL".equals(Anchors.enumOf(mirror, "require"));
        for (final AnnotationValue named : Anchors.arrayOf(mirror, "targets")) {
            if (!(named.getValue() instanceof String name)) {
                continue;
            }
            final Anchor anchor = Anchor.at(weave, mirror, named);
            final TypeElement target = elements.getTypeElement(name);
            if (target == null) {
                if (!optional) {
                    reporter.report(Diagnostic.builder(DiagnosticCode.WEAVE_TARGET_UNRESOLVABLE)
                            .message("weave " + weave.getQualifiedName() + " targets '" + name
                                    + "', which is not on the compile classpath")
                            .remedy("check the spelling, or declare require = Require.OPTIONAL "
                                    + "when the target is deliberately absent at compile time and "
                                    + "present at run time")
                            .build(), anchor);
                }
                continue;
            }
            reporter.report(Diagnostic.builder(DiagnosticCode.WEAVE_TARGET_PREFER_CLASS_LITERAL)
                    .message("weave " + weave.getQualifiedName() + " names '" + name
                            + "' as a string, but the class is on the compile classpath")
                    .remedy("write @Weave(" + target.getSimpleName() + ".class): a class literal is "
                            + "checked by the compiler, follows a rename, and survives the class "
                            + "being moved to another package")
                    .build(), anchor);
            resolved.add(new Resolved(target, anchor));
        }

        return List.copyOf(check(weave, resolved, reporter));
    }

    /**
     * Drops the targets that are themselves weave classes.
     *
     * <p>Reported as {@code AW1087}, once per offending target and anchored on the literal that
     * named it. A weave class is dissolved into its own target and never loaded as itself, so by
     * the time anything could be woven into it there is nothing there; target the class the other
     * weave targets and order the two with {@code priority}.
     *
     * <p>Only a {@code @Weave} written directly on the target is seen, and only where the target is
     * a source or classpath element the processor can read the annotation off.
     *
     * @param weave    the weave doing the targeting, named in the message; must not be {@code null}
     * @param targets  the resolved targets to filter; must not be {@code null}
     * @param reporter where to report; must not be {@code null}
     * @return the targets that may be woven, in the order given
     */
    @NotNull
    private static List<Resolved> check(@NotNull final TypeElement weave,
                                        @NotNull final List<Resolved> targets,
                                        @NotNull final MessagerReporter reporter) {
        final List<Resolved> usable = new ArrayList<>(targets.size());
        for (final Resolved target : targets) {
            if (Anchors.mirrorOf(target.element(), WeaveProcessor.WEAVE) != null) {
                reporter.report(Diagnostic.builder(DiagnosticCode.WEAVE_TARGETS_WEAVE)
                        .message("weave " + weave.getQualifiedName() + " targets "
                                + target.element().getQualifiedName() + ", which is itself a weave")
                        .detail("a weave class is dissolved into its own target, so by the time "
                                + "anything could be woven into it, it is no longer there")
                        .remedy("target the class the other weave targets, and order the two with "
                                + "priority = …")
                        .build(), target.anchor());
                continue;
            }
            usable.add(target);
        }
        return usable;
    }

    /**
     * Unwraps a class literal into the type it names.
     *
     * <p>A class literal's mirror value is a {@link javax.lang.model.type.TypeMirror}, not a
     * {@code Class}, precisely so that the processor never has to load the named type.
     *
     * @param value the annotation value to unwrap; must not be {@code null}
     * @return the type, or {@code null} when the value is not a class literal naming a declared
     *         type — which is what an erroneous literal the compiler has already refused looks like
     */
    @Contract(pure = true)
    @Nullable
    private static TypeElement elementOf(@NotNull final AnnotationValue value) {
        if (value.getValue() instanceof DeclaredType declared
                && declared.asElement() instanceof TypeElement type) {
            return type;
        }
        return null;
    }

    /**
     * A target the weave named, together with the place in the source that named it.
     *
     * <p>The pair travels together because two checks report against the literal that brought a
     * target in — {@link #check} in this class, for {@code AW1087}, and {@code checkPoints}, for
     * {@code AW1200} — and need it alongside the element, which alone cannot say which of several
     * literals that was. The other per-target checks carry the anchor without reading it,
     * anchoring their own reports on the handler, the selector or the member instead.
     *
     * @param element the target type
     * @param anchor  the literal, or the annotation, the diagnostic about this target is printed
     *                against
     * @author Erik Pförtner
     * @since 0.1.0
     */
    record Resolved(@NotNull TypeElement element, @NotNull Anchor anchor) {

        /**
         * Rejects a half-built pair.
         *
         * @throws NullPointerException if either component is {@code null}
         */
        Resolved {
            Objects.requireNonNull(element, "element");
            Objects.requireNonNull(anchor, "anchor");
        }
    }
}
