package de.splatgames.aether.weaver.engine.plan;

import de.splatgames.aether.weaver.api.model.HandlerRef;
import de.splatgames.aether.weaver.api.Weave;
import de.splatgames.aether.weaver.api.model.InjectorSpec;
import de.splatgames.aether.weaver.api.spi.DiagnosticListener;
import de.splatgames.aether.weaver.engine.model.TargetRef;
import de.splatgames.aether.weaver.engine.model.WeaveClass;
import de.splatgames.aether.weaver.engine.plugin.PluginRegistry;
import org.jetbrains.annotations.NotNull;

import java.lang.constant.ClassDesc;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Turns the parsed weaves into the ordered, indexed plan the weaver runs from.
 *
 * <p>Nothing here is resolved against a target class. The planner flattens declarations into
 * {@link PlanEntry} values, orders them, hands them to {@link ConflictDetector}, which compares
 * declarations against one another and never reads a class file, and digests the result. Matching a
 * selector and looking an injector kind up happen later, once a target class is loading.
 *
 * <p>Conflicts do not abort planning. A plan is returned whether or not {@link ConflictDetector}
 * reported anything, so one run can report every conflict it found rather than one per rebuild;
 * whoever owns the listener decides what an error means.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class WeavePlanner {

    /** Where conflicts found during planning are reported. */
    private final DiagnosticListener listener;

    /** The conflict pass, run once per {@link #plan(List, PluginRegistry)} call. */
    private final ConflictDetector conflicts = new ConflictDetector();

    /**
     * Creates a planner reporting to the given listener.
     *
     * @param listener the sink for conflict diagnostics; must not be {@code null}
     * @throws NullPointerException if {@code listener} is {@code null}
     */
    public WeavePlanner(@NotNull final DiagnosticListener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Plans the given weaves.
     *
     * <p>Each weave contributes one entry per target per injector, so a weave naming three targets
     * and declaring two injectors becomes six. A weave that dissolves is additionally recorded
     * against each of its targets in the plan's structural index, which is what keeps a weave
     * declaring members and no injector at all from vanishing out of the plan's target list.
     *
     * <p>{@code plugins} is read at one place only, when the fingerprint is built: the registry's
     * plugin ids, contributed identifiers and metadata are part of what makes two builds the same
     * build. Nothing in planning consults it to resolve anything.
     *
     * @param weaves  the parsed weaves; must not be {@code null}, and no element may be
     *                {@code null}
     * @param plugins the loaded plugins, folded into the fingerprint; must not be {@code null}
     * @return the plan, whether or not conflicts were reported
     * @throws NullPointerException if either argument is {@code null}, or if {@code weaves} holds a
     *                              {@code null}
     */
    @NotNull
    public WeavePlan plan(@NotNull final List<WeaveClass> weaves,
                          @NotNull final PluginRegistry plugins) {
        Objects.requireNonNull(weaves, "weaves");
        Objects.requireNonNull(plugins, "plugins");

        final List<PlanEntry> entries = new ArrayList<>();
        final Map<String, List<WeaveClass>> structural = new LinkedHashMap<>();
        final List<WeaveClass> dissolving = new ArrayList<>();
        for (final WeaveClass weave : weaves) {
            Objects.requireNonNull(weave, "weave");
            // A weave dissolves when it is an instance weave that has something to dissolve: its
            // own members, or handlers it declares itself. A handler in a shared helper class is
            // not the weave's to move.
            final boolean dissolves = weave.kind() == Weave.Kind.INSTANCE
                    && (!weave.members().isEmpty() || declaresItsOwnHandler(weave));
            if (dissolves) {
                dissolving.add(weave);
            }
            for (final TargetRef target : weave.targets()) {
                for (final InjectorSpec spec : weave.injectors()) {
                    entries.add(new PlanEntry(target.type(), spec, weave.binaryName(),
                            weave.origin(), orderOf(weave, spec), dissolves));
                }
                if (dissolves) {
                    structural.computeIfAbsent(internalNameOf(target.type()),
                            key -> new ArrayList<>()).add(weave);
                }
            }
        }
        structural.replaceAll((key, value) -> List.copyOf(value));

        // The total order is established before anything else looks at the entries, so that every
        // later stage — conflict detection, the fingerprint, the report — sees the same sequence.
        entries.sort(PlanEntry::compareByOrder);

        this.conflicts.detect(weaves, entries, this.listener);

        return new WeavePlan(entries, structural, weaves,
                PlanFingerprint.of(entries, dissolving, plugins));
    }

    /**
     * Reports whether any of the weave's injectors names a handler the weave itself declares.
     *
     * @param weave the weave to inspect; must not be {@code null}
     * @return whether at least one handler is the weave's own
     */
    private static boolean declaresItsOwnHandler(@NotNull final WeaveClass weave) {
        return weave.injectors().stream()
                .anyMatch(spec -> weave.weaveType().equals(spec.handler().owner()));
    }

    /**
     * Strips a class descriptor down to the internal name the structural index is keyed by.
     *
     * @param type the class; must not be {@code null}
     * @return the internal name, as {@code com/acme/Ledger}
     */
    @NotNull
    private static String internalNameOf(@NotNull final ClassDesc type) {
        final String descriptor = type.descriptorString();
        return descriptor.substring(1, descriptor.length() - 1);
    }

    /**
     * Builds the order key of one declaration.
     *
     * <p>The key names the weave and the handler and nothing else, so every entry a weave produces
     * for one handler carries the same key however many targets the weave names.
     *
     * @param weave the declaring weave; must not be {@code null}
     * @param spec  the declaration; must not be {@code null}
     * @return the order key
     */
    @NotNull
    private static OrderKey orderOf(@NotNull final WeaveClass weave,
                                    @NotNull final InjectorSpec spec) {
        final HandlerRef handler = spec.handler();
        return new OrderKey(weave.priority(), weave.binaryName(), handler.name(),
                handler.type().descriptorString());
    }
}
