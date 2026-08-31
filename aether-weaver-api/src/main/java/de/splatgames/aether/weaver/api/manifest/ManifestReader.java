package de.splatgames.aether.weaver.api.manifest;

import de.splatgames.aether.weaver.api.experimental.Nulls;
import de.splatgames.aether.weaver.api.Require;
import de.splatgames.aether.weaver.api.experimental.Scope;
import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import de.splatgames.aether.weaver.api.spi.Reporter;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Parses the JSON document an artefact carries at {@value WeaveManifest#RESOURCE} into a
 * {@link WeaveManifest}.
 *
 * <p>A manifest is read from whatever happens to be on the classpath, long after it was written
 * and possibly by an older release than the one that wrote it. Reading is therefore tolerant by
 * default and refusing by exception: an unknown field costs nothing, an unusable entry costs that
 * entry, and only a document that cannot be understood as a whole costs the whole document.
 * Nothing here throws for a document that merely disagrees with expectations — the two ways of
 * failing are a reported diagnostic and a {@code null} return.
 *
 * <h2>What is tolerated</h2>
 *
 * <ul>
 *   <li><b>An unknown key is ignored</b> at every level: root, weave, member, injector, point and
 *       extension. A manifest written by a newer processor that records extra metadata is read by
 *       an older runtime without a diagnostic.
 *   <li><b>A key of the wrong JSON type is treated as absent.</b> Each field is read by type —
 *       a string field takes the value only if it is a string, a number field only if it is a
 *       number, a boolean field only if it is a boolean, an array field only if it is an array —
 *       and falls back to its default otherwise. A JSON {@code null} is therefore also read as
 *       absent.
 *   <li><b>An array entry that is not a JSON object is dropped silently.</b> This applies to
 *       {@code "weaves"}, {@code "extensions"}, {@code "members"}, {@code "injectors"} and
 *       {@code "points"}; in {@code "tags"} and {@code "targets"}, an element that is not a
 *       string is dropped the same way. No diagnostic is reported for either.
 *   <li><b>An omitted field takes its default</b>, and the defaults are chosen so that writing
 *       a default and omitting it produce equal entries.
 * </ul>
 *
 * <h2>Defaults for an omitted field</h2>
 *
 * <p>Root: {@code "version"} the current {@link WeaveManifest#VERSION}, {@code "generator"} the
 * text {@code unknown}, both arrays empty.
 *
 * <p>Weave: {@code "kind"} {@code INSTANCE}, {@code "priority"} {@code 0}, {@code "require"}
 * {@code REQUIRED}, {@code "phase"} {@code DEFAULT}, and every array empty. {@code "class"} has
 * no default; an entry without one is dropped.
 *
 * <p>Member: {@code "disposition"} {@code MERGE}, {@code "kind"} {@code FIELD},
 * {@code "descriptor"} empty, {@code "unique"} {@code false}, and {@code "targetName"} the value
 * of {@code "name"} — a member that is not renamed need not say so twice.
 *
 * <p>Injector: {@code "kind"} {@code INJECT}, {@code "id"} the text {@code unnamed},
 * {@code "require"} and {@code "allow"} {@code 0}, {@code "group"} empty, {@code "points"} empty.
 * {@code "handler"} and {@code "method"} have no defaults; an entry missing either is dropped.
 *
 * <p>Point: {@code "point"} {@code HEAD}, {@code "target"} empty, {@code "ordinal"} {@code -1}
 * for every match, {@code "shift"} {@code NONE}, {@code "by"} {@code 0}, {@code "access"}
 * {@code ANY}, {@code "slice"} empty.
 *
 * <p>Extension: {@code "kind"} {@code instance}, {@code "require"} {@link Require#REQUIRED},
 * {@code "nulls"} {@link Nulls#UNCHECKED}, {@code "scope"} {@link Scope#PUBLIC}. The other four
 * fields have no defaults; an entry missing any of them is dropped.
 *
 * <h2>What is refused, and what it costs</h2>
 *
 * <ul>
 *   <li><b>{@code AW2300} — the document is not JSON, or not a JSON object.</b> The whole
 *       document is refused and {@code null} returned. The message carries the offset the parser
 *       stopped at. A manifest is generated, so this means it was hand-edited or truncated in
 *       transit, and the remedy is to rebuild the artefact.
 *   <li><b>{@code AW2300} — a weave entry names no class</b>, or names one that is blank. That
 *       entry is dropped and every other entry of the document is kept.
 *   <li><b>{@code AW2300} — an injector names no handler or no target method.</b> That injector
 *       is dropped; its weave and the weave's other injectors survive.
 *   <li><b>{@code AW2300} — an extension entry is missing its class, receiver, name or
 *       descriptor.</b> That entry is dropped.
 *   <li><b>{@code AW2300} — an extension names a {@code "kind"} this version does not know.</b>
 *       That entry is dropped, because the kind decides what shape of call is rewritten and
 *       guessing it would rewrite the call wrongly rather than not at all. Update the toolchain,
 *       or rebuild the artefact with this version.
 *   <li><b>{@code AW2300} — an extension names a {@code "require"}, {@code "nulls"} or
 *       {@code "scope"} this version does not know.</b> Unlike an unknown kind, the entry is
 *       kept and the policy is read as its default, since a policy this version cannot enforce
 *       is one it can only decline to apply. Update the toolchain if the policy is meant to be
 *       enforced here.
 *   <li><b>{@code AW2301} — the document states a {@code "version"} greater than
 *       {@link WeaveManifest#VERSION}.</b> The whole document is refused and {@code null}
 *       returned, without looking at any entry: a newer schema may give a familiar field a new
 *       meaning, so reading it would be guessing. Upgrade aether-weaver, or rebuild the artefact
 *       against this version. The comparison narrows the stated value to {@code int} before
 *       comparing, so a version too large to fit is not compared as written: one that wraps to a
 *       value at or below {@link WeaveManifest#VERSION} is read rather than refused here, and one
 *       that wraps to a negative value passes this check and then fails
 *       {@link WeaveManifest}'s own constructor instead, as an {@link IllegalArgumentException}
 *       rather than a reported diagnostic.
 * </ul>
 *
 * <p>Every diagnostic names the {@code origin} it was given, so a caller that passes the path of
 * the jar or directory being read makes the failing artefact identifiable.
 *
 * <h2>Where reading throws instead</h2>
 *
 * <p>Three documents parse as JSON and then fail a record's own invariant, and the resulting
 * {@link IllegalArgumentException} propagates to the caller rather than being reported: a
 * {@code "version"} that narrows to a negative value — whether written negative or large enough
 * to overflow {@code int} into a negative one — a member whose {@code "name"} is absent, not a
 * string, or blank, and a point whose {@code "point"} is present and blank. A caller reading
 * manifests off a classpath it does not control should be prepared for that as well as for a
 * {@code null} return.
 *
 * <h2>Thread safety</h2>
 *
 * <p>Stateless. {@link #read(String, String, Reporter)} keeps nothing between calls and may be
 * called concurrently, provided the {@link Reporter} it is given tolerates it.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see ManifestWriter
 * @see WeaveManifest
 */
public final class ManifestReader {

    /**
     * Refuses instantiation; every entry point is static.
     *
     * @throws AssertionError always
     */
    private ManifestReader() {
        throw new AssertionError("no instances");
    }

    /**
     * Parses one manifest document.
     *
     * <p>The version is checked before any entry is examined, so a document from a newer schema
     * produces exactly one diagnostic and no partial reading. Everything after that point is
     * entry-by-entry: a broken entry costs itself, and the manifest returned holds the entries
     * that survived, in document order.
     *
     * <p>An omitted {@code "version"} is read as {@value WeaveManifest#VERSION}, the current
     * schema. This release keeps no version below that either: a stated {@code 0} is remapped to
     * {@value WeaveManifest#VERSION}, a stated value that resolves above it is refused as
     * {@code AW2301}, and a stated value that resolves to a negative one fails the manifest's own
     * constructor with an {@link IllegalArgumentException}; with {@value WeaveManifest#VERSION}
     * being {@code 1} today, every manifest this method returns states version {@code 1}. The
     * field is not collapsed into that constant because a later release, whose own
     * {@link WeaveManifest#VERSION} has moved past {@code 1}, can still read an older document at
     * the version it actually states.
     *
     * @param text     the document text; must not be {@code null}
     * @param origin   what to name in a diagnostic as the source of the text, such as the
     *                 path of the jar it came from; must not be {@code null}
     * @param reporter the reporter to hand every diagnostic to; must not be {@code null}
     * @return the parsed manifest, or {@code null} when the document could not be parsed at all
     *         ({@code AW2300}) or states a schema version this release does not read
     *         ({@code AW2301})
     * @throws NullPointerException     if any argument is {@code null}
     * @throws IllegalArgumentException if the document parses but states a version that narrows to
     *                                  a negative value — including one written as negative and
     *                                  one large enough to overflow {@code int} into a negative
     *                                  value — or holds a member whose {@code "name"} is absent,
     *                                  not a string, or blank, or a point whose {@code "point"} is
     *                                  present and blank
     */
    @Nullable
    public static WeaveManifest read(@NotNull final String text,
                                     @NotNull final String origin,
                                     @NotNull final Reporter reporter) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(reporter, "reporter");

        final Map<String, Object> root;
        try {
            root = Json.readObject(text);
        } catch (final IllegalArgumentException malformed) {
            reporter.report(Diagnostic.builder(DiagnosticCode.MANIFEST_MALFORMED)
                    .message("the manifest in " + origin + " could not be read: "
                            + malformed.getMessage())
                    .detail("a manifest is generated, so a malformed one means it was edited by "
                            + "hand or truncated in transit")
                    .remedy("rebuild the artefact that contains it")
                    .build());
            return null;
        }

        final int version = (int) number(root, "version", 0);
        if (version > WeaveManifest.VERSION) {
            reporter.report(Diagnostic.builder(DiagnosticCode.MANIFEST_VERSION_TOO_NEW)
                    .message("the manifest in " + origin + " is version " + version
                            + ", and this build reads version " + WeaveManifest.VERSION)
                    .detail("an unknown schema version may give a familiar field a new meaning, "
                            + "so reading it would be guessing")
                    .remedy("upgrade aether-weaver, or rebuild " + origin + " against this version")
                    .build());
            return null;
        }

        final List<WeaveManifest.Weave> weaves = new ArrayList<>();
        for (final Object entry : list(root, "weaves")) {
            final WeaveManifest.Weave weave = weave(entry, origin, reporter);
            if (weave != null) {
                weaves.add(weave);
            }
        }

        final List<WeaveManifest.Extension> extensions = new ArrayList<>();
        for (final Object entry : list(root, "extensions")) {
            final WeaveManifest.Extension extension = extension(entry, origin, reporter);
            if (extension != null) {
                extensions.add(extension);
            }
        }

        return new WeaveManifest(version == 0 ? WeaveManifest.VERSION : version,
                string(root, "generator", "unknown"), weaves, extensions);
    }

    /**
     * Reads one entry of the {@code "extensions"} array.
     *
     * <p>The two failures are graded differently on purpose. A missing part of the identity, or a
     * kind this version cannot map to a shape of call, drops the entry; an unknown policy leaves
     * the entry in place with the policy at its default, because a policy this release does not
     * know is one it can only decline to apply, whereas an unknown kind would decide how a call
     * site is rewritten.
     *
     * @param entry    the parsed array element; may be {@code null} or any parsed JSON value
     * @param origin   what to name in a diagnostic as the source of the document
     * @param reporter the reporter to hand every diagnostic to
     * @return the extension, or {@code null} when the entry is not an object, is missing part of
     *         its identity, or names an unknown kind
     */
    @Nullable
    private static WeaveManifest.Extension extension(@Nullable final Object entry,
                                                     @NotNull final String origin,
                                                     @NotNull final Reporter reporter) {
        final Map<String, Object> object = asObject(entry);
        if (object == null) {
            return null;
        }
        final String className = string(object, "class", "");
        final String receiver = string(object, "receiver", "");
        final String name = string(object, "name", "");
        final String descriptor = string(object, "descriptor", "");
        if (className.isBlank() || receiver.isBlank() || name.isBlank() || descriptor.isBlank()) {
            reporter.report(Diagnostic.builder(DiagnosticCode.MANIFEST_MALFORMED)
                    .message("an extension entry in " + origin
                            + " is incomplete and was skipped")
                    .detail("an extension needs a holder class, a receiver type, a method name and "
                            + "a descriptor; this entry named "
                            + (className.isBlank() ? "no holder" : className))
                    .remedy("rebuild the artefact that contains it")
                    .build());
            return null;
        }

        final String token = string(object, "kind", "instance");
        final WeaveManifest.Extension.Kind kind = WeaveManifest.Extension.Kind.of(token);
        if (kind == null) {
            reporter.report(Diagnostic.builder(DiagnosticCode.MANIFEST_MALFORMED)
                    .message("the extension " + receiver + '.' + name + " in " + origin
                            + " is of kind \"" + token + "\", which this version does not know")
                    .detail("it was written by a newer toolchain, and guessing what shape of call "
                            + "it replaces would rewrite that call wrongly rather than not at all")
                    .remedy("update aether-weaver, or rebuild the artefact with this version")
                    .build());
            return null;
        }
        return new WeaveManifest.Extension(className, receiver, name, descriptor, kind,
                policy(object, "require", Require.values(), Require.REQUIRED, origin, reporter),
                policy(object, "nulls", Nulls.values(), Nulls.UNCHECKED, origin, reporter),
                policy(object, "scope", Scope.values(), Scope.PUBLIC, origin, reporter));
    }

    /**
     * Reads a field naming an enumeration constant, falling back where the name is not known.
     *
     * <p>Matching is on the constant's name, exactly, which is what {@link ManifestWriter} writes.
     * An absent or blank field takes the fallback without a diagnostic; a field naming something
     * else takes the fallback and reports {@code AW2300}.
     *
     * @param <P>      the enumeration type
     * @param object   the entry to read from; must not be {@code null}
     * @param field    the key to read; must not be {@code null}
     * @param values   every constant of the enumeration; must not be {@code null}
     * @param fallback the constant to use when the field is absent or unknown; must not be
     *                 {@code null}
     * @param origin   what to name in a diagnostic as the source of the document
     * @param reporter the reporter to hand every diagnostic to
     * @return the named constant, or {@code fallback}
     */
    @NotNull
    private static <P extends Enum<P>> P policy(@NotNull final Map<String, Object> object,
                                                @NotNull final String field,
                                                final P @NotNull [] values,
                                                @NotNull final P fallback,
                                                @NotNull final String origin,
                                                @NotNull final Reporter reporter) {
        final String written = string(object, field, "");
        if (written.isBlank()) {
            return fallback;
        }
        for (final P value : values) {
            if (value.name().equals(written)) {
                return value;
            }
        }
        reporter.report(Diagnostic.builder(DiagnosticCode.MANIFEST_MALFORMED)
                .message("an extension entry in " + origin + " asks for " + field + " \"" + written
                        + "\", which this version does not know")
                .detail("it was written by a newer toolchain; the entry is kept and treated as "
                        + fallback)
                .remedy("update aether-weaver if that policy is meant to be enforced here")
                .build());
        return fallback;
    }

    /**
     * Reads one entry of the {@code "weaves"} array, with its members and injectors.
     *
     * <p>A member is built inline rather than in a method of its own. An entry that is not an
     * object is dropped, the same as elsewhere; an entry that is an object but whose
     * {@code "name"} is absent, not a string, or blank is not dropped — {@code string(member,
     * "name", "")} falls back to {@code ""} in each of those cases, and
     * {@link WeaveManifest.Member}'s compact constructor throws {@link IllegalArgumentException}
     * for a blank name — so that exception propagates out of {@link #weave} instead.
     *
     * @param entry    the parsed array element; may be {@code null} or any parsed JSON value
     * @param origin   what to name in a diagnostic as the source of the document
     * @param reporter the reporter to hand every diagnostic to
     * @return the weave, or {@code null} when the entry is not an object or names no class
     */
    @Nullable
    private static WeaveManifest.Weave weave(@Nullable final Object entry,
                                             @NotNull final String origin,
                                             @NotNull final Reporter reporter) {
        final Map<String, Object> object = asObject(entry);
        if (object == null) {
            return null;
        }
        final String className = string(object, "class", "");
        if (className.isBlank()) {
            reporter.report(Diagnostic.builder(DiagnosticCode.MANIFEST_MALFORMED)
                    .message("a weave entry in " + origin + " names no class and was skipped")
                    .build());
            return null;
        }

        final List<WeaveManifest.Member> members = new ArrayList<>();
        for (final Object each : list(object, "members")) {
            final Map<String, Object> member = asObject(each);
            if (member != null) {
                members.add(new WeaveManifest.Member(
                        string(member, "disposition", "MERGE"),
                        string(member, "kind", "FIELD"),
                        string(member, "name", ""),
                        string(member, "descriptor", ""),
                        string(member, "targetName", string(member, "name", "")),
                        bool(member, "unique")));
            }
        }

        final List<WeaveManifest.Injector> injectors = new ArrayList<>();
        for (final Object each : list(object, "injectors")) {
            final WeaveManifest.Injector injector = injector(each, className, origin, reporter);
            if (injector != null) {
                injectors.add(injector);
            }
        }

        return new WeaveManifest.Weave(className,
                string(object, "kind", "INSTANCE"),
                (int) number(object, "priority", 0),
                string(object, "require", "REQUIRED"),
                string(object, "phase", "DEFAULT"),
                strings(object, "tags"),
                strings(object, "targets"),
                members,
                injectors);
    }

    /**
     * Reads one entry of a weave's {@code "injectors"} array, with its points.
     *
     * @param entry     the parsed array element; may be {@code null} or any parsed JSON value
     * @param className the weave the injector belongs to, named in the diagnostic so that a
     *                  dropped injector can be traced to a class rather than to a file
     * @param origin    what to name in a diagnostic as the source of the document
     * @param reporter  the reporter to hand every diagnostic to
     * @return the injector, or {@code null} when the entry is not an object or names no handler
     *         or no target method
     */
    @Nullable
    private static WeaveManifest.Injector injector(@Nullable final Object entry,
                                                   @NotNull final String className,
                                                   @NotNull final String origin,
                                                   @NotNull final Reporter reporter) {
        final Map<String, Object> object = asObject(entry);
        if (object == null) {
            return null;
        }
        final String handler = string(object, "handler", "");
        final String method = string(object, "method", "");
        if (handler.isBlank() || method.isBlank()) {
            reporter.report(Diagnostic.builder(DiagnosticCode.MANIFEST_MALFORMED)
                    .message("an injector of " + className + " in " + origin
                            + " names no handler or no target method, and was skipped")
                    .build());
            return null;
        }

        final List<WeaveManifest.Point> points = new ArrayList<>();
        for (final Object each : list(object, "points")) {
            final Map<String, Object> point = asObject(each);
            if (point != null) {
                points.add(new WeaveManifest.Point(
                        string(point, "point", "HEAD"),
                        string(point, "target", ""),
                        (int) number(point, "ordinal", -1),
                        string(point, "shift", "NONE"),
                        (int) number(point, "by", 0),
                        string(point, "access", "ANY"),
                        string(point, "slice", "")));
            }
        }

        return new WeaveManifest.Injector(
                string(object, "kind", "INJECT"),
                string(object, "id", "unnamed"),
                handler,
                method,
                points,
                (int) number(object, "require", 0),
                (int) number(object, "allow", 0),
                string(object, "group", ""));
    }

    // --- reading one value, tolerantly ----------------------------------------------------

    /**
     * Views a parsed value as a JSON object.
     *
     * @param value the parsed value; may be {@code null}
     * @return the value as a map, or {@code null} when it is not a JSON object
     */
    @Contract(pure = true)
    @Nullable
    private static Map<String, Object> asObject(@Nullable final Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        @SuppressWarnings("unchecked")
        final Map<String, Object> object = (Map<String, Object>) map;
        return object;
    }

    /**
     * Reads a string field.
     *
     * @param object   the entry to read from; must not be {@code null}
     * @param key      the key to read; must not be {@code null}
     * @param fallback the value to use when the key is absent or does not hold a string; must not
     *                 be {@code null}
     * @return the string, or {@code fallback}
     */
    @Contract(pure = true)
    @NotNull
    private static String string(@NotNull final Map<String, Object> object,
                                 @NotNull final String key,
                                 @NotNull final String fallback) {
        return object.get(key) instanceof String text ? text : fallback;
    }

    /**
     * Reads an integer field.
     *
     * <p>The result is widened to {@code long} and narrowed by the caller: the manifest holds
     * priorities, ordinals and counts, and a value outside {@code int} range is truncated rather
     * than reported.
     *
     * @param object   the entry to read from; must not be {@code null}
     * @param key      the key to read; must not be {@code null}
     * @param fallback the value to use when the key is absent or does not hold a number
     * @return the number, or {@code fallback}
     */
    @Contract(pure = true)
    private static long number(@NotNull final Map<String, Object> object,
                               @NotNull final String key,
                               final long fallback) {
        return object.get(key) instanceof Number value ? value.longValue() : fallback;
    }

    /**
     * Reads a boolean field, defaulting to {@code false}.
     *
     * @param object the entry to read from; must not be {@code null}
     * @param key    the key to read; must not be {@code null}
     * @return {@code true} only when the key holds the JSON value {@code true}
     */
    @Contract(pure = true)
    private static boolean bool(@NotNull final Map<String, Object> object,
                                @NotNull final String key) {
        return object.get(key) instanceof Boolean value && value;
    }

    /**
     * Reads an array field.
     *
     * @param object the entry to read from; must not be {@code null}
     * @param key    the key to read; must not be {@code null}
     * @return the entries in document order, or an empty list when the key is absent or does not
     *         hold an array
     */
    @Contract(pure = true)
    @NotNull
    private static List<Object> list(@NotNull final Map<String, Object> object,
                                     @NotNull final String key) {
        if (!(object.get(key) instanceof List<?> entries)) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        final List<Object> values = (List<Object>) entries;
        return values;
    }

    /**
     * Reads an array field of strings, dropping every element that is not one.
     *
     * @param object the entry to read from; must not be {@code null}
     * @param key    the key to read; must not be {@code null}
     * @return the string elements in document order, or an empty list when the key is absent,
     *         does not hold an array, or holds an array with no string in it
     */
    @Contract(pure = true)
    @NotNull
    private static List<String> strings(@NotNull final Map<String, Object> object,
                                        @NotNull final String key) {
        final List<String> values = new ArrayList<>();
        for (final Object entry : list(object, key)) {
            if (entry instanceof String text) {
                values.add(text);
            }
        }
        return values;
    }
}
