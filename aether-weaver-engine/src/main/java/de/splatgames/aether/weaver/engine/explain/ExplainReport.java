package de.splatgames.aether.weaver.engine.explain;

import de.splatgames.aether.weaver.api.diagnostic.Severity;
import de.splatgames.aether.weaver.api.model.InjectorSpec;
import de.splatgames.aether.weaver.api.model.PointSpec;
import de.splatgames.aether.weaver.engine.model.WeaveClass;
import de.splatgames.aether.weaver.api.spi.PlanEntryView;
import de.splatgames.aether.weaver.engine.model.WeaveMember;
import de.splatgames.aether.weaver.engine.plan.WeavePlan;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds the human-readable account of a run that {@code --explain} asks for.
 *
 * <p>Two halves, filled at different times. The plan half is known as soon as planning finishes and
 * is read straight out of the {@link WeavePlan} at render time. The other half can only come from
 * weaving, and arrives through {@link SiteObserver#resolved(Resolution)} as each target is rewritten
 * — which is why a report rendered before any class was offered says {@code not woven yet} against
 * every declaration instead of claiming it matched nothing.
 *
 * <p>Rendering is therefore not a snapshot of a finished run: {@link #render()} may be called at any
 * point and describes what is known then.
 *
 * <h2>Thread safety</h2>
 *
 * <p>Weaving runs on whatever thread a driver loads classes on, so the resolutions map and the two
 * counters are concurrent and the configuration pair is volatile. {@link #render()} reads all of
 * them without locking, and can interleave with a resolution arriving.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class ExplainReport implements SiteObserver {

    /** Column width for the injector kind, wide enough for {@code REDIRECT} and a space. */
    private static final int KIND_WIDTH = 9;

    /** Column width for a handler or member name with its type. */
    private static final int NAME_WIDTH = 34;

    /** Column width for the target method and point, before the resolution result. */
    private static final int SITE_WIDTH = 40;

    /** The weaver version printed in the header. */
    private final String version;

    /** The plan every part of the report except the resolutions is read from. */
    private final WeavePlan plan;

    /**
     * What weaving matched, keyed by {@link Resolution#key()}.
     *
     * <p>A later resolution replaces an earlier one under the same key, so a class offered twice
     * leaves the report describing the second attempt.
     */
    private final Map<String, Resolution> resolutions = new ConcurrentHashMap<>();

    /** Warnings seen through {@link #note(Severity)}, for the footer. */
    private final AtomicInteger warnings = new AtomicInteger();

    /** Errors seen through {@link #note(Severity)}, for the footer. */
    private final AtomicInteger errors = new AtomicInteger();

    /** The one-line configuration summary, or {@code null} while no driver has supplied one. */
    private volatile @Nullable String summary;

    /** The settings block, empty until a driver supplies it. */
    private volatile List<Setting> settings = List.of();

    /**
     * Creates a report over a finished plan.
     *
     * @param version the weaver version to print in the header; must not be {@code null}
     * @param plan    the plan to describe; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public ExplainReport(@NotNull final String version, @NotNull final WeavePlan plan) {
        this.version = Objects.requireNonNull(version, "version");
        this.plan = Objects.requireNonNull(plan, "plan");
    }

    /**
     * Adds the configuration block, which only a driver knows.
     *
     * <p>Nothing in the engine can say which layer decided a setting, so the block is absent from a
     * report nobody hands one to rather than being printed empty.
     *
     * @param configuration a one-line summary of the effective configuration; must not be
     *                      {@code null}
     * @param decided       one entry per setting worth attributing; must not be {@code null}
     * @return this report
     * @throws NullPointerException if either argument is {@code null}
     */
    @NotNull
    public ExplainReport configuration(@NotNull final String configuration,
                                       @NotNull final List<Setting> decided) {
        this.summary = Objects.requireNonNull(configuration, "configuration");
        this.settings = List.copyOf(Objects.requireNonNull(decided, "decided"));
        return this;
    }

    /**
     * Counts one diagnostic towards the footer.
     *
     * <p>Only the severity is kept; the diagnostic itself has already gone to whatever listener
     * reported it.
     *
     * @param severity the severity to count; must not be {@code null}
     * @throws NullPointerException if {@code severity} is {@code null}
     */
    public void note(@NotNull final Severity severity) {
        switch (Objects.requireNonNull(severity, "severity")) {
            case ERROR -> this.errors.incrementAndGet();
            case WARNING -> this.warnings.incrementAndGet();
            default -> {
                // INFO and DEBUG are not counted: a footer that grew with every informational line
                // would stop being a summary of what went wrong.
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Stores the resolution under its key, replacing any earlier one.
     */
    @Override
    public void resolved(@NotNull final Resolution resolution) {
        Objects.requireNonNull(resolution, "resolution");
        this.resolutions.put(resolution.key(), resolution);
    }

    /**
     * Renders the whole report: header, weaves, execution order, footer.
     *
     * <p>Every line is stripped of trailing spaces, so the padded columns leave no ragged edge in a
     * file or a terminal.
     *
     * @return the report as it stands now
     */
    @Contract(pure = true)
    @NotNull
    public String render() {
        final StringBuilder text = new StringBuilder(1024);
        header(text);
        weaves(text);
        executionOrder(text);
        footer(text);
        return text.toString();
    }

    /**
     * Writes the version line and, when a driver supplied one, the configuration block.
     *
     * <p>The arrow column is aligned to the longest setting name, so the block is laid out only
     * after all of the settings are known.
     *
     * @param text the buffer to append to
     */
    private void header(@NotNull final StringBuilder text) {
        line(text, "Aether Weaver " + this.version + " — plan "
                + this.plan.planFingerprint().abbreviated());
        if (this.summary != null) {
            line(text, "Configuration: " + this.summary);
        }
        final int width = this.settings.stream().mapToInt(s -> s.name().length()).max().orElse(0);
        for (final Setting setting : this.settings) {
            line(text, "  " + pad(setting.name(), width + 1) + "← " + setting.source());
        }
    }

    /**
     * Writes one block per weave, and under each the targets it names.
     *
     * <p>An empty plan says so in words. A count of zero above an empty list would leave a reader
     * unable to tell a plan that found nothing from a report that failed to list what it found.
     *
     * @param text the buffer to append to
     */
    private void weaves(@NotNull final StringBuilder text) {
        final List<WeaveClass> all = this.plan.weaves();
        line(text, "");
        line(text, "Weaves (" + all.size() + "):");
        if (all.isEmpty()) {
            line(text, "");
            line(text, "  none — nothing on the classpath declared a weave this run accepted");
            return;
        }
        for (final WeaveClass weave : all) {
            line(text, "");
            line(text, "  " + weave.binaryName() + "  [" + weave.kind() + ", priority "
                    + weave.priority() + ", origin: " + weave.origin().describe() + ']');
            for (final var target : weave.targets()) {
                line(text, "    → " + binaryNameOf(target.type()));
                members(text, weave, internalNameOf(target.type()));
                injectors(text, weave, internalNameOf(target.type()));
            }
        }
    }

    /**
     * Writes the weave's members under one target, when the planner dissolves it into that target.
     *
     * @param text   the buffer to append to
     * @param weave  the weave whose members to list
     * @param target the target's internal name
     */
    private void members(@NotNull final StringBuilder text,
                         @NotNull final WeaveClass weave,
                         @NotNull final String target) {
        // Asked of the plan rather than of the weave. A weave declares members; whether they are
        // dissolved into a given target is the planner's decision, and a report that repeated the
        // declaration would show merges for a static weave that merges nothing.
        if (!this.plan.structuralFor(target).contains(weave)) {
            return;
        }
        for (final WeaveMember member : weave.members()) {
            switch (member) {
                case WeaveMember.Merged merged -> line(text, member(merged.name(), merged.type(),
                        "MERGE", merged.unique() ? "(unique)" : ""));
                case WeaveMember.Shadowed shadowed -> line(text, member(shadowed.name(),
                        shadowed.type(), "SHADOW", "→ " + (shadowed.isField() ? "field " : "method ")
                                + shadowed.targetName()));
                case WeaveMember.Accessor accessor -> line(text, member(accessor.name(),
                        accessor.type(), "ACCESSOR", "→ field " + accessor.targetField()));
                case WeaveMember.Invoker invoker -> line(text, member(invoker.name(),
                        invoker.type(), "INVOKER", "→ method " + invoker.targetMethod()));
            }
        }
    }

    /**
     * Writes one line per point of every injector the weave declares, with what it matched.
     *
     * <p>The lookup key is rebuilt here from the same four parts weaving filed the resolution
     * under, so the two spellings must stay in step; a key that no longer matches shows every
     * declaration as {@code not woven yet} rather than failing.
     *
     * <p>An injector is declared by the weave and not by one of its targets, so every declaration
     * is listed under every target the weave names, each with its own resolution.
     *
     * @param text   the buffer to append to
     * @param weave  the weave whose injectors to list
     * @param target the target's internal name, which is part of the key
     */
    private void injectors(@NotNull final StringBuilder text,
                           @NotNull final WeaveClass weave,
                           @NotNull final String target) {
        for (final InjectorSpec spec : weave.injectors()) {
            for (final PointSpec point : spec.points()) {
                final String where = Resolution.pointOf(point);
                final String left = "        " + pad(spec.kind().id().toUpperCase(Locale.ROOT),
                        KIND_WIDTH) + pad(handlerOf(spec), NAME_WIDTH)
                        + "→ " + spec.rawMethod() + ' ' + where;
                final Resolution found = this.resolutions.get(
                        Resolution.key(target, weave.binaryName(), spec.id(), where));
                line(text, pad(left, SITE_WIDTH + KIND_WIDTH + NAME_WIDTH + 8)
                        + (found == null ? "not woven yet" : found.describe()));
            }
        }
    }

    /**
     * Writes, for each place two or more declarations can meet, the order they run in.
     *
     * <p>Grouped by target, method and point, and listed in the plan's own order: the plan is
     * already sorted by the rule that decides precedence, so numbering the entries as they come is
     * the order rather than a re-derivation of it. A place with a single declaration is listed too.
     *
     * @param text the buffer to append to
     */
    private void executionOrder(@NotNull final StringBuilder text) {
        final Map<String, List<PlanEntryView>> groups = new LinkedHashMap<>();
        for (final PlanEntryView entry : this.plan.entries()) {
            for (final PointSpec point : entry.spec().points()) {
                groups.computeIfAbsent(binaryNameOf(entry.target()) + '.'
                                + entry.spec().rawMethod() + ' ' + Resolution.pointOf(point),
                        key -> new ArrayList<>()).add(entry);
            }
        }
        for (final Map.Entry<String, List<PlanEntryView>> group : groups.entrySet()) {
            line(text, "");
            line(text, "Execution order at " + group.getKey() + ':');
            int position = 1;
            for (final PlanEntryView entry : group.getValue()) {
                line(text, "  " + position++ + ". " + pad(entry.weaveClassName() + '#'
                        + entry.handler().name(), NAME_WIDTH)
                        + "(priority " + entry.priority() + ')');
            }
        }
    }

    /**
     * Writes the totals line.
     *
     * <p>Merges are counted per target rather than per declaration: one weave dissolved into two
     * targets contributes its merged members twice, because that is how many members the run adds.
     * The counts come from the plan, so they say what was planned, not what a driver has since
     * offered.
     *
     * @param text the buffer to append to
     */
    private void footer(@NotNull final StringBuilder text) {
        int merges = 0;
        for (final WeaveClass weave : this.plan.weaves()) {
            for (final var target : weave.targets()) {
                if (!this.plan.structuralFor(internalNameOf(target.type())).contains(weave)) {
                    continue;
                }
                merges += (int) weave.members().stream()
                        .filter(member -> member instanceof WeaveMember.Merged)
                        .count();
            }
        }
        line(text, "");
        line(text, "Targets: " + this.plan.targets().size()
                + "   Injections: " + this.plan.entries().size()
                + "   Merges: " + merges
                + "   Warnings: " + this.warnings.get()
                + "   Errors: " + this.errors.get());
    }

    /**
     * Formats one member line.
     *
     * @param name  the member's name
     * @param type  the member's type, a {@code ClassDesc} or a {@code MethodTypeDesc}
     * @param kind  the word in the kind column
     * @param extra what follows the name column, already formatted
     * @return the line, without a line separator
     */
    @Contract(pure = true)
    @NotNull
    private static String member(@NotNull final String name,
                                 @NotNull final Object type,
                                 @NotNull final String kind,
                                 @NotNull final String extra) {
        return "        " + pad(kind, KIND_WIDTH) + pad(name + typeOf(type), NAME_WIDTH) + extra;
    }

    /**
     * Renders a member's type, which is a field type or a method descriptor.
     *
     * <p>{@code WeaveMember} admits only {@code ClassDesc} and {@code MethodTypeDesc} and checks it
     * on construction, so the cast in the second branch cannot fail for a member that exists.
     *
     * @param type the member's type
     * @return {@code :Type} for a field, or the display form of the descriptor for a method
     * @throws ClassCastException if {@code type} is neither
     */
    @Contract(pure = true)
    @NotNull
    private static String typeOf(@NotNull final Object type) {
        if (type instanceof final ClassDesc field) {
            return ":" + field.displayName();
        }
        return ((MethodTypeDesc) type).displayDescriptor();
    }

    /**
     * Renders a handler as {@code name(Type, Type)}.
     *
     * <p>Parameter types only, with no return type: the column is there to tell two overloads
     * apart, and the return type does not do that in Java source.
     *
     * @param spec the injector whose handler to render
     * @return the handler's signature
     */
    @Contract(pure = true)
    @NotNull
    private static String handlerOf(@NotNull final InjectorSpec spec) {
        final StringBuilder text = new StringBuilder(24).append(spec.handler().name()).append('(');
        final List<ClassDesc> parameters = spec.handler().type().parameterList();
        for (int i = 0; i < parameters.size(); i++) {
            text.append(i == 0 ? "" : ", ").append(parameters.get(i).displayName());
        }
        return text.append(')').toString();
    }

    /**
     * Returns the binary name of a class descriptor.
     *
     * <p>Strips the leading {@code L} and the trailing {@code ;} rather than asking the descriptor,
     * so it is correct only for a class type; the plan holds nothing else.
     *
     * @param type the descriptor
     * @return the binary name, with dots
     */
    @Contract(pure = true)
    @NotNull
    private static String binaryNameOf(@NotNull final ClassDesc type) {
        final String descriptor = type.descriptorString();
        return descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
    }

    /**
     * Returns the internal name of a class descriptor, which is what the plan is keyed by.
     *
     * @param type the descriptor
     * @return the internal name, with slashes
     */
    @Contract(pure = true)
    @NotNull
    private static String internalNameOf(@NotNull final ClassDesc type) {
        final String descriptor = type.descriptorString();
        return descriptor.substring(1, descriptor.length() - 1);
    }

    /**
     * Pads a value out to a column width.
     *
     * <p>At least one space is always appended, so a value wider than its column pushes the next one
     * along instead of running into it.
     *
     * @param value the value to pad
     * @param width the column width
     * @return the padded value
     */
    @Contract(pure = true)
    @NotNull
    private static String pad(@NotNull final String value, final int width) {
        return value + " ".repeat(Math.max(1, width - value.length()));
    }

    /**
     * Appends one line, separating it from what is already there.
     *
     * <p>The separator goes before the line rather than after it, which is what keeps a report from
     * ending in a blank line, and an empty first line from opening one.
     *
     * @param text the buffer to append to
     * @param line the line, whose trailing spaces are dropped
     */
    private static void line(@NotNull final StringBuilder text, @NotNull final String line) {
        if (!text.isEmpty()) {
            text.append(System.lineSeparator());
        }
        text.append(line.stripTrailing());
    }

    /**
     * Returns how much of each half the report holds.
     *
     * @return the number of weaves planned and the number of resolutions heard
     */
    @Override
    @NotNull
    public String toString() {
        return "ExplainReport[" + this.plan.weaves().size() + " weaves, "
                + this.resolutions.size() + " resolved]";
    }

    /**
     * One configuration setting, with the layer that decided it.
     *
     * <p>{@link #value()} is carried but not rendered by {@link ExplainReport#render()}; the block
     * shows the name and the source, because the effective values are already in the summary line.
     *
     * @param name   the setting's name
     * @param value  the value in force
     * @param source the layer that decided it, in whatever words the driver uses
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public record Setting(@NotNull String name,
                          @NotNull String value,
                          @NotNull String source) {

        /**
         * Checks that every component is present.
         *
         * @throws NullPointerException if any component is {@code null}
         */
        public Setting {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(source, "source");
        }

        /**
         * Returns the setting as {@code name=value} and its source.
         *
         * @return a description of this setting
         */
        @Override
        @NotNull
        public String toString() {
            return this.name + '=' + this.value + " ← " + this.source;
        }
    }

    /**
     * Returns the settings a driver supplied.
     *
     * @return the settings, empty when {@link #configuration(String, List)} was never called
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    public List<Setting> settings() {
        return this.settings;
    }
}
