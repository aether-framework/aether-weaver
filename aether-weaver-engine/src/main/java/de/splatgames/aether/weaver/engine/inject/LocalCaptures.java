package de.splatgames.aether.weaver.engine.inject;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import de.splatgames.aether.weaver.api.model.HandlerRef;
import de.splatgames.aether.weaver.api.model.InjectorSpec;
import de.splatgames.aether.weaver.api.model.LocalSpec;
import de.splatgames.aether.weaver.api.spi.HandlerBinding;
import de.splatgames.aether.weaver.api.spi.MethodView;
import de.splatgames.aether.weaver.api.spi.Reporter;
import de.splatgames.aether.weaver.engine.internal.transform.LocalTable;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.classfile.TypeKind;
import java.lang.constant.ClassDesc;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Turns a declaration's {@code @Local} captures into the loads that push them, against one injection
 * site.
 *
 * <p>Per site, not per declaration. A slot's occupant depends on where in the body the injection
 * lands, because a compiler reuses a slot once a scope ends, so the same declaration can resolve to
 * different slots at two of its own sites and both are right. That is why the result is attached
 * through {@code HandlerBinding.withCaptures} rather than computed inside
 * {@code HandlerBinding.bind}, which sees the method and not the position.
 *
 * <p>The engine refuses rather than guesses. Every strategy other than an explicit slot needs the
 * target's {@code LocalVariableTable}, and where it is absent the declaration is reported rather
 * than resolved from the method's shape: a wrong slot reads a different value of a compatible type
 * instead of failing, which is a defect that surfaces far from its cause.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
final class LocalCaptures {

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private LocalCaptures() {
        throw new AssertionError("no instances");
    }

    /**
     * Resolves every capture of one declaration at one site.
     *
     * <p>The captures are sorted by handler parameter index, because the loads are pushed in the
     * order they are returned and that order has to be the handler's parameter order.
     * Nothing about {@code InjectorSpec.locals()} promises that order, so the sort here is what
     * makes the emission correct rather than incidental.
     *
     * <p>A failure does not stop the loop. The answer is all-or-nothing — one capture that does not
     * resolve discards the rest — but every failure is reported before returning, so a handler
     * capturing three locals, none of which resolve, produces three messages rather than one and a
     * mystery.
     *
     * @param spec     the declaration whose captures are being resolved; must not be {@code null}
     * @param method   the target method, named in every diagnostic; must not be {@code null}
     * @param locals   the target's local variable table, possibly unavailable; must not be
     *                 {@code null}
     * @param site     the element index the injection lands at
     * @param reporter where to report an unresolvable capture; must not be {@code null}
     * @return the loads in handler parameter order, an empty list when the declaration captures
     *         nothing, or {@code null} when at least one capture was reported as unresolvable
     * @throws NullPointerException if any argument is {@code null}
     */
    @Nullable
    static List<HandlerBinding.Load> resolve(@NotNull final InjectorSpec spec,
                                             @NotNull final MethodView method,
                                             @NotNull final LocalTable locals,
                                             final int site,
                                             @NotNull final Reporter reporter) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(locals, "locals");
        Objects.requireNonNull(reporter, "reporter");

        if (spec.locals().isEmpty()) {
            return List.of();
        }

        final HandlerRef handler = spec.handler();
        final List<LocalSpec> ordered = spec.locals().stream()
                .sorted(Comparator.comparingInt(LocalSpec::parameter))
                .toList();

        final List<HandlerBinding.Load> loads = new ArrayList<>(ordered.size());
        boolean resolved = true;
        for (final LocalSpec local : ordered) {
            final ClassDesc parameter = handler.type().parameterType(local.parameter());
            final ClassDesc carrier = LocalRefs.carrierOf(parameter);
            if (!LocalRefs.agree(local, carrier, handler, method, reporter)) {
                resolved = false;
                continue;
            }
            // A mutable capture's parameter names the CARRIER, not the variable. Matching the
            // target's local against LocalIntRef would refuse every correct declaration, so what is
            // matched is what the carrier holds — and the carrier is remembered for the emission.
            final ClassDesc declared = carrier == null
                    ? parameter
                    : LocalRefs.heldBy(carrier);
            final HandlerBinding.Load resolvedLoad =
                    resolveOne(local, declared, handler, method, locals, site, reporter);
            final HandlerBinding.Load load = resolvedLoad == null || carrier == null
                    ? resolvedLoad
                    : new HandlerBinding.Load(resolvedLoad.slot(), resolvedLoad.kind(), carrier,
                            // The generic carrier erases, so what has to be cast back to is the
                            // variable's own type — which the table knows and the parameter does
                            // not. Without a table there is nothing better than what was declared.
                            locals.bySlot(resolvedLoad.slot(), site)
                                    .map(LocalTable.LocalSlot::type)
                                    .orElse(declared));
            if (load == null) {
                // Keep going: a handler capturing three locals none of which resolve should produce
                // three messages, not one and a mystery.
                resolved = false;
                continue;
            }
            loads.add(load);
        }
        return resolved ? List.copyOf(loads) : null;
    }

    /**
     * Dispatches one capture to the strategy its declaration selected.
     *
     * <p>{@link LocalSpec.Strategy#BY_SLOT} is answered before the availability check below, and it
     * is the only strategy that survives a target compiled without {@code -g}: it names the slot
     * outright, so nothing has to be looked up to select it, even though {@link #bySlot} still
     * consults the table when the table can describe the slot at this site. Every other strategy
     * requires the table to answer, which is why they share one {@code AW1052} for a missing table
     * rather than each reporting a failure of its own that would not say what the real problem is.
     *
     * @param local    the capture declaration; must not be {@code null}
     * @param declared the descriptor to match the target's variable against; must not be
     *                 {@code null}
     * @param handler  the handler, for the diagnostic; must not be {@code null}
     * @param method   the target method, for the diagnostic; must not be {@code null}
     * @param locals   the target's local variable table; must not be {@code null}
     * @param site     the element index the injection lands at
     * @param reporter where to report; must not be {@code null}
     * @return the load, or {@code null} after reporting
     */
    @Nullable
    private static HandlerBinding.Load resolveOne(@NotNull final LocalSpec local,
                                                  @NotNull final ClassDesc declared,
                                                  @NotNull final HandlerRef handler,
                                                  @NotNull final MethodView method,
                                                  @NotNull final LocalTable locals,
                                                  final int site,
                                                  @NotNull final Reporter reporter) {
        if (local.strategy() == LocalSpec.Strategy.BY_SLOT) {
            return bySlot(local, declared, handler, method, locals, site, reporter);
        }
        if (!locals.isAvailable()) {
            reporter.report(Diagnostic.builder(DiagnosticCode.LOCAL_VARIABLE_TABLE_MISSING)
                    .message(where(local, handler, method) + " resolves "
                            + local.strategy().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ')
                            + ", but the target carries no LocalVariableTable")
                    .remedy("recompile the target with -g, or capture by index = <slot> having read "
                            + "its bytecode — the engine will not infer a slot from the method's "
                            + "shape, because a wrong slot reads a different value rather than "
                            + "failing")
                    .build());
            return null;
        }
        return switch (local.strategy()) {
            case BY_NAME -> byName(local, declared, handler, method, locals, site, reporter);
            case BY_ORDINAL -> byOrdinal(local, declared, handler, method, locals, site, reporter);
            case BY_TYPE -> byType(local, declared, handler, method, locals, site, reporter);
            case BY_SLOT -> throw new AssertionError("handled above");
        };
    }

    /**
     * Resolves a capture that names its slot outright.
     *
     * <p>The escape hatch, and the only path that produces a load without the table having answered
     * anything. Where the table does describe the slot at this site, the occupant's type is checked
     * and a mismatch is {@code AW1050}; where it does not, nothing is checked and the declared type
     * decides the opcode. Both halves matter: an author who read the target's bytecode gets the slot
     * they asked for on a stripped target, and an author who has debug information still gets told
     * when the slot holds something else at the position they picked.
     *
     * <p>The kind is taken from the occupant where there is one and derived from the declared type
     * otherwise. The two cannot disagree once the check above has passed: {@code Assignability}
     * requires primitives to be identical and never pairs a primitive with a reference, so a slot it
     * accepts always loads with the same opcode as the declaration.
     *
     * @param local    the capture declaration; must not be {@code null}
     * @param declared the descriptor the handler wrote; must not be {@code null}
     * @param handler  the handler, for the diagnostic; must not be {@code null}
     * @param method   the target method, for the diagnostic; must not be {@code null}
     * @param locals   the target's local variable table; must not be {@code null}
     * @param site     the element index the injection lands at
     * @param reporter where to report; must not be {@code null}
     * @return the load, or {@code null} after reporting {@code AW1050}
     */
    @Nullable
    private static HandlerBinding.Load bySlot(@NotNull final LocalSpec local,
                                              @NotNull final ClassDesc declared,
                                              @NotNull final HandlerRef handler,
                                              @NotNull final MethodView method,
                                              @NotNull final LocalTable locals,
                                              final int site,
                                              @NotNull final Reporter reporter) {
        final Optional<LocalTable.LocalSlot> occupant = locals.bySlot(local.index(), site);
        if (occupant.isPresent() && !Assignability.allows(occupant.get().type(), declared)) {
            reporter.report(Diagnostic.builder(DiagnosticCode.LOCAL_NOT_RESOLVABLE)
                    .message(where(local, handler, method) + " asks for slot " + local.index()
                            + ", which holds " + occupant.get().type().displayName()
                            + " there, not " + declared.displayName())
                    .details(liveAt(locals, site))
                    .remedy("slots are assigned by the compiler and are reused once a scope ends; "
                            + "capture by name instead, or correct the slot")
                    .build());
            return null;
        }
        return new HandlerBinding.Load(local.index(),
                occupant.map(LocalTable.LocalSlot::typeKind).orElseGet(() -> TypeKind.from(declared)));
    }

    /**
     * Resolves a capture that names its variable.
     *
     * <p>Liveness at the site is part of the lookup, not a check applied after it, so a name that
     * exists in the table but whose scope has ended — or has not begun — is {@code AW1050} rather
     * than a slot. The diagnostic carries the names that are live instead, because the author's next
     * question is which one to write.
     *
     * @param local    the capture declaration; must not be {@code null}
     * @param declared the descriptor the handler wrote; must not be {@code null}
     * @param handler  the handler, for the diagnostic; must not be {@code null}
     * @param method   the target method, for the diagnostic; must not be {@code null}
     * @param locals   the target's local variable table; must not be {@code null}
     * @param site     the element index the injection lands at
     * @param reporter where to report; must not be {@code null}
     * @return the load, or {@code null} after reporting {@code AW1050}
     */
    @Nullable
    private static HandlerBinding.Load byName(@NotNull final LocalSpec local,
                                              @NotNull final ClassDesc declared,
                                              @NotNull final HandlerRef handler,
                                              @NotNull final MethodView method,
                                              @NotNull final LocalTable locals,
                                              final int site,
                                              @NotNull final Reporter reporter) {
        final Optional<LocalTable.LocalSlot> found = locals.byName(local.name(), site);
        if (found.isEmpty()) {
            reporter.report(Diagnostic.builder(DiagnosticCode.LOCAL_NOT_RESOLVABLE)
                    .message(where(local, handler, method) + " captures a local named '"
                            + local.name() + "', which is not live at this injection point")
                    .details(liveAt(locals, site))
                    .remedy("a variable declared later in the method, or one whose scope has "
                            + "already ended, does not match even though it exists somewhere in "
                            + "the table — pick one from the listing above, or inject where the "
                            + "variable is live")
                    .build());
            return null;
        }
        return checked(found.get(), local, declared, handler, method, locals, site, reporter);
    }

    /**
     * Resolves a capture that counts variables of its type.
     *
     * <p>The candidates are the live variables whose recorded type equals {@code declared}, in slot
     * order, so an ordinal is positional within the type and within the site rather than within the
     * method. Two consequences follow and neither is visible from the annotation: moving the
     * injection point can change which variable an unchanged ordinal picks, and a mutable reference
     * capture counts variables the table records as {@link Object}, since that is what the carrier
     * reduces to.
     *
     * <p>No type check follows, because the candidate list was built from the type.
     *
     * @param local    the capture declaration; must not be {@code null}
     * @param declared the descriptor the handler wrote; must not be {@code null}
     * @param handler  the handler, for the diagnostic; must not be {@code null}
     * @param method   the target method, for the diagnostic; must not be {@code null}
     * @param locals   the target's local variable table; must not be {@code null}
     * @param site     the element index the injection lands at
     * @param reporter where to report; must not be {@code null}
     * @return the load, or {@code null} after reporting {@code AW1050}
     */
    @Nullable
    private static HandlerBinding.Load byOrdinal(@NotNull final LocalSpec local,
                                                 @NotNull final ClassDesc declared,
                                                 @NotNull final HandlerRef handler,
                                                 @NotNull final MethodView method,
                                                 @NotNull final LocalTable locals,
                                                 final int site,
                                                 @NotNull final Reporter reporter) {
        final List<LocalTable.LocalSlot> candidates = locals.byType(declared, site);
        if (local.ordinal() >= candidates.size()) {
            reporter.report(Diagnostic.builder(DiagnosticCode.LOCAL_NOT_RESOLVABLE)
                    .message(where(local, handler, method) + " asks for "
                            + declared.displayName() + " number " + local.ordinal()
                            + ", but only " + candidates.size()
                            + " of that type are live at this injection point")
                    .details(liveAt(locals, site))
                    .remedy("ordinals are counted in slot order over the locals of the "
                            + "parameter's type that are live here, from zero")
                    .build());
            return null;
        }
        return new HandlerBinding.Load(candidates.get(local.ordinal()).slot(),
                candidates.get(local.ordinal()).typeKind());
    }

    /**
     * Resolves a capture that names nothing but its type.
     *
     * <p>Exactly one candidate resolves; none is {@code AW1050} and more than one is
     * {@code AW1051}. The second is a separate code because it is a separate mistake: the variable
     * the author wants is there, and what is missing is the means of saying which. Picking the first
     * would weave, run, and read whichever variable the compiler happened to allocate first.
     *
     * <p>The ambiguity diagnostic lists the candidates rather than the live variables, since the
     * live ones are not the choice being made.
     *
     * @param local    the capture declaration; must not be {@code null}
     * @param declared the descriptor the handler wrote; must not be {@code null}
     * @param handler  the handler, for the diagnostic; must not be {@code null}
     * @param method   the target method, for the diagnostic; must not be {@code null}
     * @param locals   the target's local variable table; must not be {@code null}
     * @param site     the element index the injection lands at
     * @param reporter where to report; must not be {@code null}
     * @return the load, or {@code null} after reporting {@code AW1050} or {@code AW1051}
     */
    @Nullable
    private static HandlerBinding.Load byType(@NotNull final LocalSpec local,
                                              @NotNull final ClassDesc declared,
                                              @NotNull final HandlerRef handler,
                                              @NotNull final MethodView method,
                                              @NotNull final LocalTable locals,
                                              final int site,
                                              @NotNull final Reporter reporter) {
        final List<LocalTable.LocalSlot> candidates = locals.byType(declared, site);
        if (candidates.isEmpty()) {
            reporter.report(Diagnostic.builder(DiagnosticCode.LOCAL_NOT_RESOLVABLE)
                    .message(where(local, handler, method) + " captures a "
                            + declared.displayName()
                            + ", and none is live at this injection point")
                    .details(liveAt(locals, site))
                    .remedy("name the variable with @Local(name = \"…\"), or inject where one of "
                            + "that type is live")
                    .build());
            return null;
        }
        if (candidates.size() > 1) {
            reporter.report(Diagnostic.builder(DiagnosticCode.LOCAL_AMBIGUOUS)
                    .message(where(local, handler, method) + " captures a "
                            + declared.displayName() + ", and " + candidates.size()
                            + " are live at this injection point")
                    .details(candidates.stream().map(LocalTable.LocalSlot::toString).toList())
                    .remedy("say which: @Local(name = \"…\") is the readable form, "
                            + "@Local(ordinal = n) the positional one. Two candidates and a coin "
                            + "flip is not resolution")
                    .build());
            return null;
        }
        return new HandlerBinding.Load(candidates.getFirst().slot(),
                candidates.getFirst().typeKind());
    }

    /**
     * Builds the load for a variable found by name, after checking its type.
     *
     * <p>Only the name-based path needs this. A type-based lookup selected its candidates by type
     * already, and a slot-based one checks the occupant itself, so this is where a declaration that
     * names the right variable with the wrong type is caught.
     *
     * @param found    the variable the name resolved to; must not be {@code null}
     * @param local    the capture declaration; must not be {@code null}
     * @param declared the descriptor the handler wrote; must not be {@code null}
     * @param handler  the handler, for the diagnostic; must not be {@code null}
     * @param method   the target method, for the diagnostic; must not be {@code null}
     * @param locals   the target's local variable table; must not be {@code null}
     * @param site     the element index the injection lands at
     * @param reporter where to report; must not be {@code null}
     * @return the load, or {@code null} after reporting {@code AW1050}
     */
    @Nullable
    private static HandlerBinding.Load checked(@NotNull final LocalTable.LocalSlot found,
                                               @NotNull final LocalSpec local,
                                               @NotNull final ClassDesc declared,
                                               @NotNull final HandlerRef handler,
                                               @NotNull final MethodView method,
                                               @NotNull final LocalTable locals,
                                               final int site,
                                               @NotNull final Reporter reporter) {
        if (!Assignability.allows(found.type(), declared)) {
            reporter.report(Diagnostic.builder(DiagnosticCode.LOCAL_NOT_RESOLVABLE)
                    .message(where(local, handler, method) + " is declared "
                            + declared.displayName() + ", but '" + found.name() + "' is a "
                            + found.type().displayName())
                    .details(liveAt(locals, site))
                    .remedy("declare the parameter with the variable's own type")
                    .build());
            return null;
        }
        return new HandlerBinding.Load(found.slot(), found.typeKind());
    }

    /**
     * Opens every message in this class with the same three facts.
     *
     * <p>Nothing downstream attributes a diagnostic to the declaration that caused it, so the
     * handler, the parameter and the target have to be in the message itself. A capture failing at
     * several sites of one injection produces several messages that differ only in what was live,
     * which is why the position is left to the details rather than repeated here.
     *
     * @param local   the capture declaration; must not be {@code null}
     * @param handler the handler; must not be {@code null}
     * @param method  the target method; must not be {@code null}
     * @return the message prefix
     */
    @Contract(pure = true)
    @NotNull
    private static String where(@NotNull final LocalSpec local,
                                @NotNull final HandlerRef handler,
                                @NotNull final MethodView method) {
        return handler.describe() + " parameter " + local.parameter() + " (@Local in "
                + method.describe() + ')';
    }

    /**
     * Lists what the author could have captured instead, as diagnostic details.
     *
     * <p>Always one line rather than one per variable, so a method with thirty live locals does not
     * bury the message. The empty case gets a sentence of its own: an empty detail list would read
     * as though the listing had been omitted, when the absence of live variables is itself the
     * explanation.
     *
     * @param locals the target's local variable table; must not be {@code null}
     * @param site   the element index the injection lands at
     * @return one detail line, either the listing or a statement that there is none
     */
    @Contract(pure = true)
    @NotNull
    private static List<String> liveAt(@NotNull final LocalTable locals, final int site) {
        final List<String> live = locals.namesLiveAt(site);
        return live.isEmpty()
                ? List.of("no locals are live at this injection point")
                : List.of("live here: " + String.join(", ", live));
    }
}
