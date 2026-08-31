package de.splatgames.aether.weaver.engine;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import de.splatgames.aether.weaver.api.Woven;
import de.splatgames.aether.weaver.api.model.GroupSpec;
import de.splatgames.aether.weaver.api.model.InjectorKind;
import de.splatgames.aether.weaver.api.spi.Reporter;
import de.splatgames.aether.weaver.engine.extension.ExtensionCalls;
import de.splatgames.aether.weaver.engine.extension.ExtensionGuards;
import de.splatgames.aether.weaver.engine.extension.ExtensionIndex;
import de.splatgames.aether.weaver.engine.explain.ExplainReport;
import de.splatgames.aether.weaver.engine.explain.SiteObserver;
import de.splatgames.aether.weaver.engine.inject.WeavingPipeline;
import de.splatgames.aether.weaver.engine.observe.Statistics;
import de.splatgames.aether.weaver.engine.observe.WeaveEvents;
import de.splatgames.aether.weaver.engine.merge.StructuralWeaver;
import de.splatgames.aether.weaver.engine.merge.WeaveBytes;
import de.splatgames.aether.weaver.engine.model.WeaveClass;
import de.splatgames.aether.weaver.engine.internal.transform.WeaveAttribute;
import de.splatgames.aether.weaver.engine.stamp.WeaveAttributeWriter;
import de.splatgames.aether.weaver.engine.stamp.WeaveRecord;
import de.splatgames.aether.weaver.engine.stamp.WovenAnnotationWriter;
import de.splatgames.aether.weaver.api.spi.DiagnosticListener;
import de.splatgames.aether.weaver.api.spi.PlanEntryView;
import de.splatgames.aether.weaver.api.spi.PluginEvent;
import de.splatgames.aether.weaver.api.spi.StatisticsView;
import de.splatgames.aether.weaver.api.spi.WeavePolicy;
import de.splatgames.aether.weaver.api.spi.WeaveTarget;
import de.splatgames.aether.weaver.engine.plan.WeavePlan;
import de.splatgames.aether.weaver.engine.stamp.Provenance;
import de.splatgames.aether.weaver.engine.plugin.PluginRegistry;
import de.splatgames.aether.weaver.engine.verify.Verifier;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies a plan to one class at a time, and is where a driver hands a class to the engine.
 *
 * <p>Most of what a driver offers is not woven at all, and that is what decides the shape of the
 * entry points: two map lookups answer for a class the plan names nowhere, before any allocation
 * and, for the supplier overload, before the bytes are so much as fetched — unless an extension is
 * in force, in which case such a class is still parsed and examined.
 *
 * <p>{@code null} means that the class is to be used unchanged. It does not separate "nothing to
 * do" from "refused along the way"; the listener has already heard about the second. Where
 * verification refuses a class under {@code REPORT} the return is not {@code null} but the original
 * bytes, so a driver that writes whatever it gets back still writes a class that loads. Each private
 * method below carries the reasoning for the step it implements.
 *
 * <p>Every offered class is counted as seen. Only a class that came all the way through
 * {@link #apply} is counted as woven, which is why the count is taken after verification rather than
 * from the return value. A class changed only by the extension pass is counted as seen and as
 * nothing else, and contributes no time.
 *
 * <p>Immutable after construction and its counters are concurrent, so one instance serves every
 * thread a parallel-capable class loader weaves on.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class Weaver {

    /** What is to be woven where, already sorted into the order the injections run in. */
    private final WeavePlan plan;

    /**
     * What may be woven at all, asked at most once per class that the plan names — not asked for
     * one {@link #weavableShape} already refused.
     */
    private final WeavePolicy policy;

    /** Checks every class this weaver changed, including one changed only by an extension. */
    private final Verifier verifier;

    /** The injectors, points and resolvers in force, and the audience for plugin events. */
    private final PluginRegistry plugins;

    /** Which side this weaver runs on, which decides how a foreign weave record is treated. */
    private final Driver driver;

    /** Where diagnostics go; already the counting listener when a report was asked for. */
    private final DiagnosticListener listener;

    /** The injection half of gate 4, built once with resolvers that read the registry per call. */
    private final WeavingPipeline pipeline;

    /** The merge half of gate 4, which copies members out of the weave class itself. */
    private final StructuralWeaver structural;

    /** Every group any weave declared, flattened into one list before it reaches the pipeline. */
    private final List<GroupSpec> groups;

    /** How much the {@code @Woven} annotation says; the attribute is written regardless. */
    private final Woven.Detail detail;

    /** The report, or {@code null} when none was asked for, in which case no sites are observed. */
    private final @Nullable ExplainReport report;

    /** Counters, concurrent, and the source of every {@link StatisticsView} this weaver hands out. */
    private final Statistics statistics;

    /** The JFR bridge, or the no-op one on a runtime without JFR. */
    private final WeaveEvents events;

    /**
     * The extensions whose calls are rewritten.
     *
     * <p>When this is not empty, a class the plan names nowhere still has to be examined, which is
     * the one thing that takes the fast path away.
     */
    private final ExtensionIndex extensions;

    /**
     * Which side of the build a weaver runs on.
     *
     * <p>The only behaviour this changes is the treatment of a class that already carries a weave
     * record from a different plan, where the same record means different things: at build time an
     * input that had been woven before it arrived, at load time a class file the build that
     * produced it may well have woven.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public enum Driver {

        /**
         * Weaving class files ahead of time.
         *
         * <p>A class carrying a foreign plan's record is refused as {@code AW2201}.
         */
        BUILD,

        /**
         * Weaving classes as they are loaded.
         *
         * <p>A class carrying a foreign plan's record is warned about as {@code AW2202} and woven
         * anyway.
         */
        LOAD
    }

    /**
     * Assembles a weaver from what the builder decided.
     *
     * <p>The two lookups handed to the pipeline consult the registry on every use rather than being
     * resolved once here, so a plugin's factory produces a fresh instance each time one is needed.
     * They run once per point and per injection of every class woven, so an identifier reached
     * through a deprecated alias would report {@code AW3120} that many times. Both therefore pass
     * a listener that forwards the first report for an identifier and drops the rest, rather than
     * the {@code DiagnosticListener.NOOP} they once passed: dropping every one of them left a
     * retired spelling warning nobody, which is the whole purpose of {@code AW3120}.
     *
     * @param plan       what is to be woven
     * @param policy     what may be woven
     * @param verifier   what checks the result
     * @param plugins    the loaded plugins
     * @param groups     every group any weave declared
     * @param detail     how much the {@code @Woven} annotation says
     * @param weaveBytes where a weave class's own bytes come from
     * @param extensions the extensions whose calls are rewritten
     * @param listener   where diagnostics go
     * @param driver     which side this weaver runs on
     * @param report     the report to fill, or {@code null} for none
     * @throws NullPointerException if any argument but {@code report} is {@code null}
     */
    Weaver(@NotNull final WeavePlan plan,
           @NotNull final WeavePolicy policy,
           @NotNull final Verifier verifier,
           @NotNull final PluginRegistry plugins,
           @NotNull final List<GroupSpec> groups,
           @NotNull final Woven.Detail detail,
           @NotNull final WeaveBytes weaveBytes,
           @NotNull final ExtensionIndex extensions,
           @NotNull final DiagnosticListener listener,
           @NotNull final Driver driver,
           @Nullable final ExplainReport report) {
        this.extensions = Objects.requireNonNull(extensions, "extensions");
        this.structural = new StructuralWeaver(Objects.requireNonNull(weaveBytes, "weaveBytes"));
        this.plan = Objects.requireNonNull(plan, "plan");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.plugins = Objects.requireNonNull(plugins, "plugins");
        this.groups = List.copyOf(Objects.requireNonNull(groups, "groups"));
        this.detail = Objects.requireNonNull(detail, "detail");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.driver = Objects.requireNonNull(driver, "driver");
        this.report = report;
        this.statistics = new Statistics(plan.targets().size());
        this.events = WeaveEvents.discover();
        this.pipeline = new WeavingPipeline(
                id -> plugins.points().lookup(id, onceFor("point:" + id, listener))
                        .map(factory -> factory.create(id))
                        .orElse(null),
                kind -> plugins.injectors().lookup(kind, onceFor("injector:" + kind, listener))
                        .map(factory -> factory.create(kindOf(kind)))
                        .orElse(null),
                report == null ? SiteObserver.NONE : report);
    }

    /**
     * The identifiers a deprecation warning has already been reported for.
     *
     * <p>Keyed by registry and identifier, so a point and an injector sharing a spelling are
     * counted apart. Weaving may run on several threads, and the set is read and written from each
     * of them.
     */
    private final Set<String> warnedFor = ConcurrentHashMap.newKeySet();

    /**
     * Wraps a listener so that one identifier warns once.
     *
     * @param key      what to count the report against
     * @param listener where the first report goes
     * @return a listener that forwards nothing after the first report for {@code key}
     */
    @Contract(value = "_, _ -> new", pure = true)
    @NotNull
    private DiagnosticListener onceFor(@NotNull final String key,
                                       @NotNull final DiagnosticListener listener) {
        return diagnostic -> {
            if (this.warnedFor.add(key)) {
                listener.report(diagnostic);
            }
        };
    }

    /** The weaver version, written into every weave record and printed by the explain report. */
    static final String VERSION = "0.1.0";

    /**
     * Every kind this release declares, in one place.
     *
     * <p>The list is the only thing standing between {@link #kindOf(String)} and
     * {@link InjectorKind#of(String)}, which refuses an unqualified identifier. A constant left out
     * of it therefore does not fall back to something weaker: every declaration naming that kind
     * throws out of the pipeline. {@code WeaverKindsTest} asserts the list against the constants
     * {@link InjectorKind} declares, because that is the drift a second list cannot survive.
     */
    static final List<InjectorKind> BUILT_IN_KINDS = List.of(
            InjectorKind.INJECT, InjectorKind.REDIRECT, InjectorKind.WRAP,
            InjectorKind.MERGE, InjectorKind.ACCESSOR, InjectorKind.INVOKER);

    /**
     * Turns an injector identifier back into a kind.
     *
     * <p>{@link InjectorKind#of(String)} refuses an unqualified identifier, since the unqualified
     * namespace belongs to the built-in kinds, so the identifier is first compared against
     * {@link #BUILT_IN_KINDS}. Anything else, namespaced or not, is handed to the factory as it
     * stands.
     *
     * @param id the identifier a plan entry carries
     * @return the kind
     * @throws IllegalArgumentException if the identifier names no built-in kind and
     *                                  {@link InjectorKind#of(String)} refuses it
     */
    @Contract(pure = true)
    @NotNull
    private static InjectorKind kindOf(@NotNull final String id) {
        for (final InjectorKind builtIn : BUILT_IN_KINDS) {
            if (builtIn.id().equals(id)) {
                return builtIn;
            }
        }
        return InjectorKind.of(id);
    }

    /**
     * Returns a builder.
     *
     * @return a new builder with every default in place
     */
    @Contract(value = " -> new", pure = true)
    @NotNull
    public static WeaverBuilder builder() {
        return new WeaverBuilder();
    }

    /**
     * Weaves a class whose bytes are already in hand.
     *
     * <p>Identical to the supplier overload except that there is nothing left to defer.
     *
     * @param internalName the class's internal name, such as {@code com/acme/Ledger}
     * @param original     the class as it stands; not modified
     * @return the woven class, or {@code null} when it is to be used unchanged
     * @throws NullPointerException if either argument is {@code null}
     */
    public byte @Nullable [] weave(@NotNull final String internalName,
                                   final byte @NotNull [] original) {
        Objects.requireNonNull(internalName, "internalName");
        Objects.requireNonNull(original, "original");
        this.statistics.seen();

        final List<PlanEntryView> entries = this.plan.entriesFor(internalName);
        final List<WeaveClass> dissolving = this.plan.structuralFor(internalName);
        if (entries.isEmpty() && dissolving.isEmpty()) {
            return extensionsOnly(internalName, original);
        }
        return timed(internalName, () -> original, entries, dissolving);
    }

    /**
     * Weaves a class whose bytes are fetched only if they are needed.
     *
     * <p>For a caller that would have to read a file or open a jar entry to produce them: when the
     * plan names nothing for this class and no extension is in force, the supplier is never called.
     *
     * @param internalName the class's internal name, such as {@code com/acme/Ledger}
     * @param original     supplies the class as it stands; called at most once
     * @return the woven class, or {@code null} when it is to be used unchanged
     * @throws NullPointerException if either argument is {@code null}
     */
    public byte @Nullable [] weave(@NotNull final String internalName,
                                   @NotNull final ByteSupplier original) {
        Objects.requireNonNull(internalName, "internalName");
        Objects.requireNonNull(original, "original");
        this.statistics.seen();

        // Gate 1: the fast path. Two map lookups, no allocation, no bytes fetched. Both are
        // consulted because a weave that only merges members declares no injection at all, and
        // asking about entries alone would silently never apply it.
        final List<PlanEntryView> entries = this.plan.entriesFor(internalName);
        final List<WeaveClass> dissolving = this.plan.structuralFor(internalName);
        if (entries.isEmpty() && dissolving.isEmpty()) {
            return this.extensions.isEmpty() ? null : extensionsOnly(internalName, original.get());
        }

        return timed(internalName, original, entries, dissolving);
    }

    /**
     * Applies the extension pass to a class the plan names nowhere.
     *
     * <p>Two rewrites, either of which may decline: calls this class makes to an extension, and the
     * receiver guards it owes if it is itself a holder. The second is handed the result of the
     * first when there was one, so a holder that also calls an extension gets both.
     *
     * <p>This path never counts a class as woven and never records time against it. It is not a
     * weave; the plan had nothing to say about the class.
     *
     * @param internalName the class's internal name
     * @param original     the class as it stands
     * @return the rewritten class, or {@code null} when neither rewrite applied; also the original
     *         bytes, unrewritten, when a rewrite applied but the verifier refused the result under
     *         {@code REPORT}
     */
    private byte @Nullable [] extensionsOnly(@NotNull final String internalName,
                                             final byte @NotNull [] original) {
        if (this.extensions.isEmpty()) {
            return null;
        }
        final byte[] rewritten = ExtensionCalls.rewrite(original, this.extensions);
        final byte[] guarded = ExtensionGuards.harden(
                rewritten == null ? original : rewritten,
                this.extensions.declaredBy(internalName));
        if (rewritten == null && guarded == null) {
            return null;
        }
        // Verified like anything else this engine hands back. Under REPORT the verifier returns
        // the original, and a caller that wrote the returned bytes unconditionally still gets a
        // class that loads.
        return this.verifier.check(internalName, original,
                guarded == null ? rewritten : guarded);
    }

    /**
     * Times {@link #apply} and records the elapsed time whatever it does.
     *
     * <p>In a {@code finally}, so a class refused at a gate and a class that threw both still
     * account for the time they cost.
     *
     * @param internalName the class's internal name
     * @param original     supplies the class as it stands
     * @param entries      the injections planned for it
     * @param dissolving   the weaves to merge into it
     * @return whatever {@link #apply} returned
     */
    private byte @Nullable [] timed(@NotNull final String internalName,
                                    @NotNull final ByteSupplier original,
                                    @NotNull final List<PlanEntryView> entries,
                                    @NotNull final List<WeaveClass> dissolving) {
        final long started = System.nanoTime();
        try {
            return apply(internalName, original, entries, dissolving, started);
        } finally {
            this.statistics.spent(System.nanoTime() - started);
        }
    }

    /**
     * Runs gates two to six over a class the plan does name.
     *
     * <p>The idempotence gate is handed the raw bytes rather than the {@code ClassModel} parsed
     * above. That model came from a plain {@code ClassFile.of()}, which sees {@code AetherWeave} as
     * an unknown attribute and would answer "not woven" for every class, including one this weaver
     * stamped a moment ago; the byte overload parses with the attribute mapper.
     *
     * <p>Injections run before structural merges. A refusal from the pipeline abandons the class
     * outright, and it can do so safely because the pipeline builds into a fresh class rather than
     * mutating this one, so there is never a half-woven target to hand back. The structural weaver
     * answers {@code null} for a refusal and for having nothing to emit alike, so what follows can
     * only ask whether anything changed at all: an injected class stands even when the merge that
     * was to follow it produced nothing.
     *
     * <p>The extension pass runs here rather than in a second sweep, because the class is already in
     * hand and a sweep that visited only the classes the plan missed would leave every woven target
     * unrewritten.
     *
     * <p>The {@code WeaveTarget} shown to the policy reports {@code false} for both
     * {@code signed} and {@code declaredWeaveClass}: neither is knowable from the bytes, so a
     * driver that does know decides it before offering the class at all.
     *
     * @param internalName the class's internal name
     * @param original     supplies the class as it stands
     * @param entries      the injections planned for it
     * @param dissolving   the weaves to merge into it
     * @param started      when timing began, for the JFR event
     * @return the woven class, {@code null} when it is to be used unchanged, or the original bytes
     *         when a verifier refusal under {@code REPORT} left {@code checked != woven}
     */
    private byte @Nullable [] apply(@NotNull final String internalName,
                                    @NotNull final ByteSupplier original,
                                    @NotNull final List<PlanEntryView> entries,
                                    @NotNull final List<WeaveClass> dissolving,
                                    final long started) {
        final byte[] bytes = original.get();
        final ClassModel model = ClassFile.of().parse(bytes);

        if (!weavableShape(internalName, model)) {
            return null;
        }

        // Gate 2: may this class be woven at all?
        final WeaveTarget target = new WeaveTarget(internalName, model.majorVersion(),
                false, false);
        final WeavePolicy.Decision decision = this.policy.decide(target);
        if (decision instanceof WeavePolicy.Decision.Deny denied) {
            this.listener.report(Diagnostic.builder(denied.code())
                    .message(denied.reason())
                    .detail(entries.size() + " modification"
                            + (entries.size() == 1 ? "" : "s") + " were planned for it")
                    .build());
            return null;
        }

        // Gate 3: has this exact plan already been applied? Build-time weaving followed by a
        // load-time driver is the common case, and re-applying would double every injection.
        //
        // The byte[] overload, never the ClassModel one. `model` above was parsed by a plain
        // ClassFile.of(), which sees AetherWeave as an unknown attribute and therefore answers
        // "not woven" for every class — including one this weaver stamped a moment ago. This gate
        // read that model until a build-plugin test ran the goal twice over one directory and found
        // the handler call emitted twice. Provenance documents the hazard; the fix is to hand it
        // the bytes and let it parse with the attribute mapper.
        if (Provenance.wovenBy(bytes, this.plan.fingerprint())) {
            return null;
        }
        if (!alreadyWovenElsewhere(internalName, bytes)) {
            return null;
        }

        // Gate 4: apply. Injections first, structure second — and the whole class is abandoned if
        // either refuses. A target that gained a weave's fields but not the code that initialises
        // them is worse than one that was left alone.
        final Reporter reporter = this.listener::report;
        byte[] current = bytes;
        if (!entries.isEmpty()) {
            final byte[] woven = this.pipeline.weave(model, entries, this.groups, reporter);
            if (woven == null) {
                // Nothing resolved, or accounting refused it. The pipeline has already said why,
                // and the original class stands — never a half-woven one, because the pipeline
                // builds into a fresh class rather than mutating this one.
                return null;
            }
            current = woven;
        }
        if (!dissolving.isEmpty()) {
            final byte[] merged = this.structural.apply(
                    ClassFile.of().parse(current), dissolving, reporter);
            if (merged == null && current == bytes) {
                return null;
            }
            if (merged != null) {
                current = merged;
            }
        }
        // A target may itself call an extension, and it is rewritten here rather than in a second
        // pass — the class is already in hand, and a pass that ran only over classes the plan
        // missed would leave every woven target unrewritten.
        if (!this.extensions.isEmpty()) {
            final byte[] rewritten = ExtensionCalls.rewrite(current, this.extensions);
            if (rewritten != null) {
                current = rewritten;
            }
            // And, if this class is itself an extension holder, the receiver guards its
            // declarations asked for. A holder is an ordinary class that may also be a target.
            final byte[] guarded =
                    ExtensionGuards.harden(current, this.extensions.declaredBy(internalName));
            if (guarded != null) {
                current = guarded;
            }
        }

        if (current == bytes) {
            return null;
        }

        // Gate 5: verify. Under REPORT this hands back the ORIGINAL, never the broken class.
        final byte[] woven = current;
        final byte[] checked = this.verifier.check(internalName, bytes, woven);
        if (checked != woven) {
            // Counted, not returned as a success. The class the caller gets back is the original
            // one, so a run that reported "42 classes woven" while a verifier had quietly handed
            // back 3 of them unchanged would be a statistic nobody could trust.
            this.statistics.failed();
            return checked;
        }

        // Gate 6: stamp, so the next driver can tell this plan was already applied.
        final byte[] stamped = stamp(checked, entries);
        // Counted here rather than at the caller, because gate 5 also returns non-null — it
        // hands back the ORIGINAL when verification refused the class. A caller that counted "not
        // null" as woven would report classes as changed that were handed straight back.
        this.statistics.woven(entries.size());
        if (this.events.enabled()) {
            this.events.classWoven(internalName, entries.size(), this.plan.fingerprint(),
                    System.nanoTime() - started);
        }
        this.plugins.publish(new PluginEvent.ClassWoven(internalName, entries.size()),
                this.listener);
        return stamped;
    }

    /**
     * Writes the {@code AetherWeave} attribute, and the {@code @Woven} annotation the detail level
     * asks for.
     *
     * <p>Parsed and rebuilt with the attribute mapper installed, so an attribute already on the
     * class survives being read and written rather than being dropped as unknown.
     *
     * <p>The record names the injections that were applied; a structural merge applied in the same
     * pass is not among them, and the policy-override and structural flags are both written as
     * {@code false}.
     *
     * @param woven   the woven class
     * @param entries the injections applied to it
     * @return the stamped class
     */
    private byte @NotNull [] stamp(final byte @NotNull [] woven,
                                   @NotNull final List<PlanEntryView> entries) {
        final WeaveRecord record = WeaveRecord.of(VERSION, this.plan.fingerprint(),
                entries, this.plugins.plugins(), this.plugins.metadata(), false, false);

        return WeaveAttribute.classFileWithMapper().transformClass(
                WeaveAttribute.classFileWithMapper().parse(woven),
                ClassTransform.endHandler(builder -> {
                    WeaveAttributeWriter.stamp(builder, record);
                    WovenAnnotationWriter.annotation(record, this.detail)
                            .ifPresent(annotation -> builder.with(
                                    RuntimeVisibleAnnotationsAttribute.of(annotation)));
                }));
    }

    /**
     * Returns the plan this weaver applies.
     *
     * @return the plan
     */
    @Contract(pure = true)
    @NotNull
    public WeavePlan plan() {
        return this.plan;
    }

    /**
     * Returns the plan's fingerprint, which is what a stamped class is compared against.
     *
     * @return the fingerprint
     */
    @Contract(pure = true)
    @NotNull
    public String fingerprint() {
        return this.plan.fingerprint();
    }

    /**
     * Returns the loaded plugins.
     *
     * @return the registry
     */
    @Contract(pure = true)
    @NotNull
    public PluginRegistry plugins() {
        return this.plugins;
    }

    /**
     * Returns an account of this run, followed by the plugins that took part.
     *
     * <p>The body is the {@link ExplainReport} when one was asked for and the plan's own terse
     * listing otherwise, so this is always answerable and is simply less informative without
     * {@code explain(true)}. The plugin block is appended either way.
     *
     * @return the explanation, as several lines
     */
    @Contract(pure = true)
    @NotNull
    public String explain() {
        final StringBuilder sb = new StringBuilder(
                this.report == null ? this.plan.explain() : this.report.render());
        if (this.plugins.isEmpty()) {
            return sb.append(System.lineSeparator()).append("plugins: none").toString();
        }
        sb.append(System.lineSeparator()).append("plugins:");
        this.plugins.plugins().forEach(id -> sb.append(System.lineSeparator())
                .append("  ").append(id.describe()));
        if (!this.plugins.metadata().isEmpty()) {
            sb.append(System.lineSeparator()).append("plugin metadata:");
            this.plugins.metadata().forEach((key, value) -> sb.append(System.lineSeparator())
                    .append("  ").append(key).append('=').append(value));
        }
        return sb.toString();
    }

    /**
     * Returns the report, when one was asked for.
     *
     * <p>The same instance the pipeline reports resolutions to, so it goes on filling as classes
     * are woven.
     *
     * @return the report, or empty when {@code explain(true)} was not set
     */
    @Contract(pure = true)
    @NotNull
    public java.util.Optional<ExplainReport> report() {
        return java.util.Optional.ofNullable(this.report);
    }

    /**
     * Returns the counters as they stand.
     *
     * <p>A snapshot, and taken without locking: on a weaving thread the individual counts may be
     * read at slightly different moments.
     *
     * @return the statistics
     */
    @Contract(value = " -> new", pure = true)
    @NotNull
    public StatisticsView statistics() {
        return this.statistics.snapshot();
    }

    /**
     * Reports whether flight-recorder events are being emitted.
     *
     * @return {@code true} when a recording is enabled for this weaver's event; {@code false} on a
     *         runtime with no JFR and also on one with JFR but no recording enabling it
     */
    @Contract(pure = true)
    public boolean recording() {
        return this.events.enabled();
    }

    /**
     * Tells the plugins that weaving is over, with this weaver's own statistics.
     */
    public void finish() {
        finish(statistics());
    }

    /**
     * Tells the plugins that weaving is over, with statistics a caller supplies.
     *
     * <p>The event carries the statistics given rather than this weaver's own. Nothing here stops
     * the weaver being used again afterwards: the event is a notification, not a close, and calling
     * it twice publishes it twice.
     *
     * @param statistics the statistics to report; must not be {@code null}
     * @throws NullPointerException if {@code statistics} is {@code null}
     */
    public void finish(@NotNull final StatisticsView statistics) {
        Objects.requireNonNull(statistics, "statistics");
        this.plugins.publish(new PluginEvent.WeavingFinished(statistics), this.listener);
    }

    /**
     * Reports whether a class file's own shape allows weaving, and says what is wrong when it does
     * not.
     *
     * <p>{@code AW2004} refuses a preview class file built for another release: such a file is
     * accepted only by the exact JVM version that produced it, so nothing could load what weaving
     * it produced.
     *
     * <p>{@code AW1092} only warns, and the class is woven. An anonymous or local class is named by
     * a number the compiler assigned in source order, so adding an unrelated lambda earlier in the
     * file silently moves this weave onto a different class.
     *
     * @param internalName the class's internal name
     * @param model        the parsed class
     * @return {@code true} when weaving may proceed
     */
    private boolean weavableShape(@NotNull final String internalName,
                                  @NotNull final ClassModel model) {
        if (model.minorVersion() == ClassFile.PREVIEW_MINOR_VERSION
                && model.majorVersion() != ClassFile.latestMajorVersion()) {
            this.listener.report(Diagnostic.builder(DiagnosticCode.PREVIEW_CLASS_FILE_MISMATCH)
                    .message(internalName + " was compiled with preview features of another "
                            + "release")
                    .detail("class file major version " + model.majorVersion()
                            + ", and this JVM loads preview classes of version "
                            + ClassFile.latestMajorVersion() + " only")
                    .remedy("a preview class file is accepted by the exact JVM version that "
                            + "produced it and by no other, so nothing could load what weaving it "
                            + "produced. Recompile the target against the JVM that will run it, or "
                            + "run the weaver on the JVM the target was compiled with")
                    .build());
            return false;
        }
        if (model.findAttribute(Attributes.enclosingMethod()).isPresent()) {
            this.listener.report(Diagnostic.builder(DiagnosticCode.TARGET_IS_ANONYMOUS_OR_LOCAL)
                    .message(internalName + " is " + (namedInSource(model) ? "a local" : "an "
                            + "anonymous") + " class, whose name the compiler invented")
                    .detail("the trailing number counts the anonymous and local classes of its "
                            + "enclosing class in source order")
                    .remedy("adding an unrelated lambda or anonymous class earlier in that file "
                            + "renumbers every one after it, and this weave would then modify a "
                            + "different class without saying so. Target the enclosing class and "
                            + "narrow with a selector, or give the class a name")
                    .build());
        }
        return true;
    }

    /**
     * Distinguishes a local class from an anonymous one, for the wording of {@code AW1092}.
     *
     * <p>Both carry {@code EnclosingMethod}; only a local class has an inner name in the
     * {@code InnerClasses} entry describing itself.
     *
     * @param model the parsed class
     * @return {@code true} for a local class, {@code false} for an anonymous one
     */
    @Contract(pure = true)
    private static boolean namedInSource(@NotNull final ClassModel model) {
        return model.findAttribute(Attributes.innerClasses())
                .map(attribute -> attribute.classes().stream()
                        .filter(inner -> inner.innerClass().equals(model.thisClass()))
                        .anyMatch(inner -> inner.innerName().isPresent()))
                .orElse(false);
    }

    /**
     * Decides what to do about a class that already carries somebody else's weave record.
     *
     * <p>Reached only after the same plan's fingerprint has been ruled out, so a record found here
     * is always a different plan's; a class carrying none at all is passed on in silence. The two
     * drivers then answer differently and both report. {@code AW2201} at build time is an error and
     * stops the class. {@code AW2202} at load time is a warning and lets it through, so both plans
     * apply and any weave they have in common runs twice — which the diagnostic says, because
     * nothing here can tell whether that was intended.
     *
     * @param internalName the class's internal name
     * @param bytes        the class as it stands
     * @return {@code true} to go on weaving, {@code false} to leave the class alone
     */
    private boolean alreadyWovenElsewhere(@NotNull final String internalName,
                                          final byte @NotNull [] bytes) {
        final Optional<WeaveRecord> existing = Provenance.recordOf(bytes);
        if (existing.isEmpty()) {
            return true;
        }
        final WeaveRecord record = existing.get();
        if (this.driver == Driver.LOAD) {
            this.listener.report(Diagnostic.builder(DiagnosticCode.LOAD_TIME_OVER_BUILD_TIME_WEAVE)
                    .message(internalName + " was already woven before it was loaded, and this "
                            + "weaver's plan is a different one")
                    .detail("woven by plan " + record.fingerprint() + " with "
                            + record.weaves().size() + " weave"
                            + (record.weaves().size() == 1 ? "" : "s"))
                    .detail("this plan is " + this.plan.fingerprint())
                    .remedy("both plans will apply, so any weave they have in common runs twice. "
                            + "Configure the agent and the build plugin with different weaves, or "
                            + "drop one of them — weaving at build time and again at load time is "
                            + "rarely meant")
                    .build());
            return true;
        }
        this.listener.report(Diagnostic.builder(DiagnosticCode.ALREADY_WOVEN_DIFFERENT_PLAN)
                .message(internalName + " already carries a weave record from a different plan")
                .detail("woven by plan " + record.fingerprint() + " with "
                        + record.weaves().size() + " weave"
                        + (record.weaves().size() == 1 ? "" : "s"))
                .detail("this plan is " + this.plan.fingerprint())
                .remedy("applying a second plan on top would run both, and every weave they have "
                        + "in common would fire twice. The usual cause is an output directory "
                        + "woven once already and not rebuilt since a weave changed — a clean "
                        + "build settles it. Otherwise the input is an artefact that has been "
                        + "through this before, such as a shaded jar, and the original classes "
                        + "are what should be woven")
                .build());
        return false;
    }

    /**
     * Returns the plan and the verifier, which are what distinguish two weavers in a log.
     *
     * @return a description of this weaver
     */
    @Override
    @NotNull
    public String toString() {
        return "Weaver[" + this.plan + ", " + this.verifier + ']';
    }

    /**
     * Supplies a class's bytes on demand.
     *
     * <p>Exists so that the cost of producing them can be skipped for a class nothing wants to
     * weave. An implementation is called at most once per weave and is free to be expensive.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    @FunctionalInterface
    public interface ByteSupplier {

        /**
         * Returns the class file.
         *
         * @return the bytes; the weaver does not modify them
         */
        byte @NotNull [] get();
    }
}
