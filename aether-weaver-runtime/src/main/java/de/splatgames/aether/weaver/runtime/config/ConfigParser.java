package de.splatgames.aether.weaver.runtime.config;

import de.splatgames.aether.weaver.api.Phase;
import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import de.splatgames.aether.weaver.api.spi.Reporter;
import de.splatgames.aether.weaver.engine.verify.VerificationPolicy;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * Turns one configuration source into one {@link ConfigLayer}.
 *
 * <p>Every key is flat and carries the {@link #PREFIX}, so the same setting is written the same way
 * wherever it is given: as {@code aether.weaver.verification=report} in a properties file or on the
 * command line as {@code -Daether.weaver.verification=report}, and as {@code verification=report}
 * in an agent argument string, where the prefix is implied.
 *
 * <h2>The keys</h2>
 *
 * <p>Written here without the prefix. A value is trimmed before it is read, and a key that is not
 * one of these is reported as {@code AW2310} and ignored.
 *
 * <ul>
 *   <li>{@code enabled} - {@code true} or {@code false}, without regard to case.
 *   <li>{@code verification} - the name of a
 *       {@link de.splatgames.aether.weaver.engine.verify.VerificationPolicy} constant, without
 *       regard to case.
 *   <li>{@code onError} - the name of an {@link ErrorPolicy} constant, without regard to case.
 *   <li>{@code phase} - the name of a {@link de.splatgames.aether.weaver.api.Phase} constant,
 *       without regard to case.
 *   <li>{@code dump} - the directory to write the original and woven bytes to. The value
 *       {@code off}, and an empty value, record nothing at all rather than recording an "off": the
 *       setting is then indistinguishable from one this source never mentioned, so a directory
 *       named by a lower-precedence layer still takes effect.
 *   <li>{@code explain} - {@code true} or {@code false}, without regard to case.
 *   <li>{@code tags.include}, {@code tags.exclude} - comma-separated tag lists. Both describe one
 *       {@link TagFilter} and a source setting both ends up with both; a source setting either,
 *       however, replaces whatever filter the layers below it built, because
 *       {@link ConfigLayer#merge(ConfigLayer)} treats the filter as one value.
 *   <li>{@code policy.allowSigned} - {@code true} or {@code false}, without regard to case.
 *   <li>{@code policy.allowPackage} - a comma-separated list of package names.
 *   <li>{@code weave[<binary name>].enabled} - {@code true} or {@code false} for one weave class.
 *   <li>{@code weave[<binary name>].priority} - an integer for one weave class.
 *   <li>{@code injector[<name>].enabled} - {@code true} or {@code false} for one injection.
 * </ul>
 *
 * <p>The name inside the brackets is taken verbatim and never checked against anything that exists,
 * so a misspelt weave or injection produces no diagnostic and simply never applies. An unrecognised
 * setting after the brackets is reported as {@code AW2310}, as is an unrecognised family in front
 * of them.
 *
 * <h2>What a value that cannot be read does</h2>
 *
 * <p>Most failures are reported as {@code AW2310}: an unknown key, an agent argument with no
 * {@code =}, a boolean that is neither {@code true} nor {@code false}, a priority that is not an
 * integer, and an enumerated setting given a value it does not take. In each of these cases the
 * setting is left unset rather than guessed at, so a lower-precedence layer goes on deciding it and
 * the default applies if none did. Nothing here coerces: {@code enabled=ture} does not read as
 * {@code false}.
 *
 * <p>A {@code dump} value is the exception: it is handed to {@link Path#of(String, String...)}
 * uncaught, so a value that names no valid path throws {@link java.nio.file.InvalidPathException}
 * out of the parser instead of being reported as {@code AW2310}. The throw abandons the rest of
 * the source, so entries after the offending {@code dump} key are never read and their own
 * diagnostics are never reported.
 *
 * <p>An unknown key is answered with the nearest of the non-indexed keys where one is within
 * {@code SUGGESTION_DISTANCE} edits, and with nothing where none is.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class ConfigParser {

    /**
     * The prefix every key carries, in a properties file and in a system property alike.
     *
     * <p>An agent argument is written without it and has it prepended before parsing, so the key
     * names quoted in a diagnostic are prefixed whichever source they came from.
     */
    public static final String PREFIX = "aether.weaver.";

    /**
     * The keys a suggestion may name.
     *
     * <p>Consulted only by {@link #suggest(String)}; the keys that are actually recognised are the
     * cases of the switch in {@link #parse(Map, String, Reporter)} and the families
     * {@link #applyIndexed(ConfigLayer.Builder, String, String, String, Reporter)} knows. A key
     * handled there but absent here is parsed normally and is simply never offered as a
     * correction.
     */
    private static final Set<String> SCALAR_KEYS = Set.of(
            "enabled", "verification", "onError", "dump", "explain", "phase",
            "tags.include", "tags.exclude",
            "policy.allowSigned", "policy.allowPackage");

    /**
     * The largest edit distance at which a mistyped key is answered with a suggestion.
     *
     * <p>A key further than this from every known key draws no suggestion at all, which keeps a
     * key resembling nothing from being answered with an unrelated one.
     */
    private static final int SUGGESTION_DISTANCE = 3;

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private ConfigParser() {
        throw new AssertionError("no instances");
    }

    /**
     * Reads a properties object in which every key carries the {@link #PREFIX}.
     *
     * <p>Unlike {@link #ofSystemProperties(Properties, Reporter)}, a key without the prefix is
     * reported as {@code AW2310} here rather than passed over. Keys are read in sorted order rather
     * than in {@link Properties}' own, which is that of a hash table, so two runs over the same
     * file report their problems in the same order.
     *
     * <p>Diagnostics name the source as {@code weaver.properties}.
     *
     * @param properties the properties to read; must not be {@code null}
     * @param reporter   where problems go; must not be {@code null}
     * @return the layer these properties describe, saying nothing where nothing could be read
     * @throws NullPointerException if either argument is {@code null}
     * @throws java.nio.file.InvalidPathException if a {@code dump} value names no valid path; the
     *                                             entries after it in this source are then never
     *                                             read
     */
    @NotNull
    public static ConfigLayer ofProperties(@NotNull final Properties properties,
                                           @NotNull final Reporter reporter) {
        Objects.requireNonNull(properties, "properties");
        final Map<String, String> entries = new LinkedHashMap<>();
        // Sorted, so that two runs over the same file report their problems in the same order —
        // Properties is a hash table and its iteration order is not a promise.
        for (final String key : new TreeSet<>(properties.stringPropertyNames())) {
            entries.put(key, properties.getProperty(key));
        }
        return parse(entries, "weaver.properties", reporter);
    }

    /**
     * Reads this framework's keys out of a system properties object and ignores everything else.
     *
     * <p>A key without the {@link #PREFIX} is passed over in silence. The system properties hold
     * everything the JVM and the application ever set, and complaining about them would bury this
     * framework's own diagnostics.
     *
     * <p>Diagnostics name the source as {@code system properties}.
     *
     * @param properties the properties to read, ordinarily {@link System#getProperties()}; must not
     *                   be {@code null}
     * @param reporter   where problems go; must not be {@code null}
     * @return the layer the prefixed keys describe
     * @throws NullPointerException if either argument is {@code null}
     * @throws java.nio.file.InvalidPathException if a {@code dump} value names no valid path; the
     *                                             entries after it in this source are then never
     *                                             read
     */
    @NotNull
    public static ConfigLayer ofSystemProperties(@NotNull final Properties properties,
                                                 @NotNull final Reporter reporter) {
        Objects.requireNonNull(properties, "properties");
        final Map<String, String> entries = new LinkedHashMap<>();
        for (final String key : new TreeSet<>(properties.stringPropertyNames())) {
            // Only prefixed keys, and unprefixed ones are not reported. The system properties
            // contain everything the JVM and the application ever set; complaining about them would
            // bury this framework's own diagnostics under hundreds of lines about java.home.
            if (key.startsWith(PREFIX)) {
                entries.put(key, properties.getProperty(key));
            }
        }
        return parse(entries, "system properties", reporter);
    }

    /**
     * Reads an agent argument string: comma-separated {@code key=value} pairs, with the
     * {@link #PREFIX} implied.
     *
     * <p>The comma is the pair separator and nothing escapes it, so a value cannot contain one. A
     * multi-valued key such as {@code tags.include=audit,metrics} therefore parses as
     * {@code tags.include=audit} followed by a fragment {@code metrics} that has no {@code =} and
     * is reported as {@code AW2310}. Repeating the key instead does not accumulate either: the pairs
     * are collected into a map, so the last value given for a key is the one parsed.
     *
     * <p>Everything up to the first {@code =} is the key and the remainder is the value, so a value
     * may contain further {@code =} signs. Both are trimmed, and an empty pair between two commas
     * is skipped without complaint.
     *
     * <p>Diagnostics name the source as {@code agent arguments}.
     *
     * @param arguments the argument string the JVM handed the agent, or {@code null}
     * @param reporter  where problems go; must not be {@code null}
     * @return the layer these arguments describe, or {@link ConfigLayer#EMPTY} when the string is
     *         {@code null} or blank
     * @throws NullPointerException if {@code reporter} is {@code null}
     * @throws java.nio.file.InvalidPathException if a {@code dump} value names no valid path; the
     *                                             entries after it in this source are then never
     *                                             read
     */
    @NotNull
    public static ConfigLayer ofAgentArguments(@Nullable final String arguments,
                                               @NotNull final Reporter reporter) {
        Objects.requireNonNull(reporter, "reporter");
        if (arguments == null || arguments.isBlank()) {
            return ConfigLayer.EMPTY;
        }
        final Map<String, String> entries = new LinkedHashMap<>();
        for (final String pair : arguments.split(",")) {
            final String trimmed = pair.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            final int equals = trimmed.indexOf('=');
            if (equals < 0) {
                reporter.report(Diagnostic.builder(DiagnosticCode.UNKNOWN_CONFIGURATION_KEY)
                        .message("the agent argument '" + trimmed + "' has no value")
                        .remedy("agent arguments are comma-separated key=value pairs, as in "
                                + "verification=report,dump=/tmp/woven")
                        .build());
                continue;
            }
            entries.put(PREFIX + trimmed.substring(0, equals).trim(),
                    trimmed.substring(equals + 1).trim());
        }
        return parse(entries, "agent arguments", reporter);
    }

    /**
     * Reads already-collected entries into a layer.
     *
     * <p>Entries are read in the map's iteration order, which is the order diagnostics come out in.
     * The policy is assembled separately from the rest and set on the builder only if something was
     * relaxed, so an explicit {@code policy.allowSigned=false} leaves the layer at
     * {@link PolicyConfig#STRICT} and says nothing rather than contradicting a layer below it.
     *
     * @param entries  the entries, keyed by full key including the prefix
     * @param origin   what to call this source in a diagnostic
     * @param reporter where problems go; must not be {@code null}
     * @return the layer these entries describe
     * @throws NullPointerException if {@code reporter} is {@code null}
     * @throws java.nio.file.InvalidPathException if a {@code dump} value names no valid path; the
     *                                             remaining entries are then never read
     */
    @NotNull
    private static ConfigLayer parse(@NotNull final Map<String, String> entries,
                                     @NotNull final String origin,
                                     @NotNull final Reporter reporter) {
        Objects.requireNonNull(reporter, "reporter");
        final ConfigLayer.Builder layer = ConfigLayer.builder();
        boolean allowSigned = false;
        final Set<String> allowPackages = new LinkedHashSet<>();

        for (final Map.Entry<String, String> entry : entries.entrySet()) {
            final String key = entry.getKey();
            if (!key.startsWith(PREFIX)) {
                report(reporter, key, origin);
                continue;
            }
            final String name = key.substring(PREFIX.length());
            final String value = entry.getValue() == null ? "" : entry.getValue().trim();

            if (applyIndexed(layer, name, value, origin, reporter)) {
                continue;
            }
            switch (name) {
                case "enabled" -> readBoolean(value, key, origin, reporter).ifPresent(
                        layer::enabled);
                case "verification" -> readEnum(VerificationPolicy.class, value, key, origin,
                        reporter).ifPresent(layer::verification);
                case "onError" -> readEnum(ErrorPolicy.class, value, key, origin, reporter)
                        .ifPresent(layer::onError);
                case "phase" -> readEnum(Phase.class, value, key, origin, reporter)
                        .ifPresent(layer::phase);
                case "dump" -> {
                    // "off" is a value rather than an absence: it lets a higher layer switch off
                    // dumping that a lower one turned on, which "leave it unset" cannot express.
                    if (!"off".equalsIgnoreCase(value) && !value.isEmpty()) {
                        layer.dumpDirectory(Path.of(value));
                    }
                }
                case "explain" -> readBoolean(value, key, origin, reporter).ifPresent(
                        layer::explain);
                case "tags.include" -> layer.tags(mergeTags(layer, split(value), Set.of()));
                case "tags.exclude" -> layer.tags(mergeTags(layer, Set.of(), split(value)));
                case "policy.allowSigned" -> allowSigned = readBoolean(value, key, origin, reporter)
                        .orElse(false);
                case "policy.allowPackage" -> allowPackages.addAll(split(value));
                default -> report(reporter, key, origin);
            }
        }

        if (allowSigned || !allowPackages.isEmpty()) {
            layer.policy(new PolicyConfig(allowSigned, allowPackages));
        }
        return layer.build();
    }

    /**
     * Reads a key of the form {@code family[subject].setting}, if it is one.
     *
     * @param layer    the builder to add to
     * @param name     the key without its prefix
     * @param value    the trimmed value
     * @param origin   what to call this source in a diagnostic
     * @param reporter where problems go
     * @return {@code true} when the key named a family this understands, whether or not the setting
     *         after the brackets was one it understands; {@code false} when the key is not of this
     *         shape or names another family, leaving the caller to go on treating it as a plain key
     */
    private static boolean applyIndexed(@NotNull final ConfigLayer.Builder layer,
                                        @NotNull final String name,
                                        @NotNull final String value,
                                        @NotNull final String origin,
                                        @NotNull final Reporter reporter) {
        final int open = name.indexOf('[');
        final int close = name.indexOf(']', open + 1);
        if (open < 0 || close < 0 || close + 2 > name.length() || name.charAt(close + 1) != '.') {
            return false;
        }
        final String family = name.substring(0, open);
        final String subject = name.substring(open + 1, close);
        final String setting = name.substring(close + 2);

        switch (family) {
            case "weave" -> {
                switch (setting) {
                    case "enabled" -> readBoolean(value, PREFIX + name, origin, reporter)
                            .ifPresent(enabled ->
                                    layer.weave(subject, new WeaveOverride(enabled, null)));
                    case "priority" -> readInt(value, PREFIX + name, origin, reporter)
                            .ifPresent(priority ->
                                    layer.weave(subject, new WeaveOverride(null, priority)));
                    default -> report(reporter, PREFIX + name, origin);
                }
                return true;
            }
            case "injector" -> {
                if ("enabled".equals(setting)) {
                    readBoolean(value, PREFIX + name, origin, reporter).ifPresent(enabled ->
                            layer.injector(subject, new InjectorOverride(enabled)));
                } else {
                    report(reporter, PREFIX + name, origin);
                }
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * Adds tags to the filter the builder holds so far.
     *
     * <p>Reading the filter back out of the builder is what lets the {@code tags.include} and
     * {@code tags.exclude} keys of one source combine, whichever order they arrive in.
     *
     * @param layer    the builder whose filter is being extended
     * @param included the tags to include as well
     * @param excluded the tags to exclude as well
     * @return the combined filter
     */
    @NotNull
    private static TagFilter mergeTags(@NotNull final ConfigLayer.Builder layer,
                                       @NotNull final Set<String> included,
                                       @NotNull final Set<String> excluded) {
        final TagFilter existing = layer.build().tags();
        final Set<String> in = new LinkedHashSet<>(
                existing == null ? Set.of() : existing.included());
        final Set<String> out = new LinkedHashSet<>(
                existing == null ? Set.of() : existing.excluded());
        in.addAll(included);
        out.addAll(excluded);
        return new TagFilter(in, out);
    }

    /**
     * Splits a comma-separated value.
     *
     * @param value the raw value
     * @return the trimmed, non-empty entries in the order given, without duplicates
     */
    @Contract(pure = true)
    @NotNull
    private static Set<String> split(@NotNull final String value) {
        final Set<String> entries = new LinkedHashSet<>();
        for (final String entry : value.split(",")) {
            final String trimmed = entry.trim();
            if (!trimmed.isEmpty()) {
                entries.add(trimmed);
            }
        }
        return entries;
    }

    /**
     * Reads {@code true} or {@code false}, without regard to case.
     *
     * @param value    the raw value
     * @param key      the full key, quoted in a diagnostic
     * @param origin   what to call this source in a diagnostic
     * @param reporter where problems go
     * @return the value, or empty after reporting {@code AW2310} for anything else, so that the
     *         setting stays unset instead of becoming {@code false}
     */
    @NotNull
    private static java.util.Optional<Boolean> readBoolean(@NotNull final String value,
                                                           @NotNull final String key,
                                                           @NotNull final String origin,
                                                           @NotNull final Reporter reporter) {
        if ("true".equalsIgnoreCase(value)) {
            return java.util.Optional.of(true);
        }
        if ("false".equalsIgnoreCase(value)) {
            return java.util.Optional.of(false);
        }
        // Never Boolean.parseBoolean, which answers false for everything it does not recognise.
        // "enabled=ture" would then read as a deliberate "off", and the weaves would be silently
        // gone — which is the exact failure this whole check exists to prevent.
        reporter.report(Diagnostic.builder(DiagnosticCode.UNKNOWN_CONFIGURATION_KEY)
                .message(key + " in " + origin + " is '" + value + "', which is not true or false")
                .detail("the setting is left for a lower-precedence layer to decide, rather than "
                        + "guessed at")
                .build());
        return java.util.Optional.empty();
    }

    /**
     * Reads a decimal integer.
     *
     * @param value    the raw value
     * @param key      the full key, quoted in a diagnostic
     * @param origin   what to call this source in a diagnostic
     * @param reporter where problems go
     * @return the value, or empty after reporting {@code AW2310} when it does not parse
     */
    @NotNull
    private static java.util.Optional<Integer> readInt(@NotNull final String value,
                                                       @NotNull final String key,
                                                       @NotNull final String origin,
                                                       @NotNull final Reporter reporter) {
        try {
            return java.util.Optional.of(Integer.valueOf(value));
        } catch (final NumberFormatException notANumber) {
            reporter.report(Diagnostic.builder(DiagnosticCode.UNKNOWN_CONFIGURATION_KEY)
                    .message(key + " in " + origin + " is '" + value + "', which is not a number")
                    .build());
            return java.util.Optional.empty();
        }
    }

    /**
     * Reads an enum constant by name, without regard to case.
     *
     * @param <E>      the enumeration
     * @param type     the enumeration to match against
     * @param value    the raw value
     * @param key      the full key, quoted in a diagnostic
     * @param origin   what to call this source in a diagnostic
     * @param reporter where problems go
     * @return the constant, or empty after reporting {@code AW2310} listing every constant of
     *         {@code type} in lower case
     */
    @NotNull
    private static <E extends Enum<E>> java.util.Optional<E> readEnum(
            @NotNull final Class<E> type,
            @NotNull final String value,
            @NotNull final String key,
            @NotNull final String origin,
            @NotNull final Reporter reporter) {
        for (final E constant : type.getEnumConstants()) {
            if (constant.name().equalsIgnoreCase(value)) {
                return java.util.Optional.of(constant);
            }
        }
        final List<String> allowed = new ArrayList<>();
        for (final E constant : type.getEnumConstants()) {
            allowed.add("allowed: " + constant.name().toLowerCase(Locale.ROOT));
        }
        reporter.report(Diagnostic.builder(DiagnosticCode.UNKNOWN_CONFIGURATION_KEY)
                .message(key + " in " + origin + " is '" + value + "', which is not a value it takes")
                .details(allowed)
                .build());
        return java.util.Optional.empty();
    }

    /**
     * Reports a key that changed nothing.
     *
     * @param reporter where the diagnostic goes
     * @param key      the full key as written
     * @param origin   what to call this source in the diagnostic
     */
    private static void report(@NotNull final Reporter reporter,
                               @NotNull final String key,
                               @NotNull final String origin) {
        final Diagnostic.Builder diagnostic =
                Diagnostic.builder(DiagnosticCode.UNKNOWN_CONFIGURATION_KEY)
                        .message("unknown configuration key '" + key + "' in " + origin)
                        .detail("it was ignored, so whatever it was meant to change did not change");
        suggest(key).ifPresent(nearest -> diagnostic.remedy("did you mean '" + nearest + "'?"));
        reporter.report(diagnostic.build());
    }

    /**
     * Finds the known key a mistyped one most resembles.
     *
     * <p>The prefix is stripped before comparing and put back on the answer. Where several keys are
     * equally close the first in alphabetical order is offered, so the same typo always draws the
     * same suggestion.
     *
     * @param key the key as written, with or without the prefix
     * @return the nearest of {@code SCALAR_KEYS} with the prefix restored, or empty when the
     *         nearest is further than {@code SUGGESTION_DISTANCE} edits away
     */
    @Contract(pure = true)
    @NotNull
    private static java.util.Optional<String> suggest(@NotNull final String key) {
        final String name = key.startsWith(PREFIX) ? key.substring(PREFIX.length()) : key;
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (final String known : new TreeSet<>(SCALAR_KEYS)) {
            final int distance = distance(name, known);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = known;
            }
        }
        return bestDistance <= SUGGESTION_DISTANCE
                ? java.util.Optional.of(PREFIX + best)
                : java.util.Optional.empty();
    }

    /**
     * Returns the Levenshtein distance between two strings.
     *
     * <p>Two rows rather than a full matrix, and the rows are swapped rather than reallocated: the
     * distance is computed once per known key for every unrecognised key in a source.
     *
     * @param first  one string
     * @param second the other
     * @return the number of single-character insertions, deletions and substitutions between them
     */
    @Contract(pure = true)
    private static int distance(@NotNull final String first, @NotNull final String second) {
        int[] previous = new int[second.length() + 1];
        int[] current = new int[second.length() + 1];
        for (int j = 0; j <= second.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= first.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= second.length(); j++) {
                final int substitution = previous[j - 1]
                        + (first.charAt(i - 1) == second.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            final int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[second.length()];
    }
}
