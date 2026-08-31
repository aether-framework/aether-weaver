package de.splatgames.aether.weaver.api.manifest;

import de.splatgames.aether.weaver.api.experimental.Nulls;
import de.splatgames.aether.weaver.api.Require;
import de.splatgames.aether.weaver.api.experimental.Scope;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/**
 * Renders a {@link WeaveManifest} as the JSON document an artefact carries at
 * {@value WeaveManifest#RESOURCE}.
 *
 * <p>The output is a fixed shape rather than whatever a general JSON library would produce, and
 * {@link ManifestReader} parses exactly that shape back. The two are inverses for any manifest
 * whose version this release reads: writing such a manifest and reading the result yields an
 * equal manifest, including the contents of every nested entry. A manifest stating a version
 * above {@link WeaveManifest#VERSION} is written faithfully and then refused on reading.
 *
 * <h2>The shape of the output</h2>
 *
 * <ul>
 *   <li><b>Two spaces per level of indentation</b>, and one field per line. A field's value
 *       follows its key and a single space.
 *   <li><b>Fixed key order.</b> Every object writes its keys in the order given on
 *       {@link WeaveManifest}, which is the declaration order of the corresponding record's
 *       components.
 *   <li><b>An empty array is written as {@code []}</b> on the same line as its key. A non-empty
 *       array puts each entry on its own line.
 *   <li><b>{@code "tags"} and {@code "targets"} are written inline</b>, as one line holding the
 *       whole array with {@code ", "} between the strings, because their elements are single
 *       strings rather than objects.
 *   <li><b>The document ends with a newline</b> after the closing brace.
 *   <li><b>Numbers are decimal integers</b> and booleans are {@code true} or {@code false}.
 *       Nothing here emits a fraction, an exponent or a JSON {@code null}.
 * </ul>
 *
 * <p>Strings are escaped by the same rules everywhere: {@code "}, {@code \}, a newline, a
 * carriage return and a tab take their short escapes, any other character below {@code U+0020}
 * is written as a backslash, {@code u} and four lowercase hexadecimal digits, and everything else is
 * written as itself. The document is therefore UTF-8 rather than ASCII, and a manifest holding a
 * non-ASCII identifier stays readable to whoever opens it.
 *
 * <h2>What is left out</h2>
 *
 * <p>An extension writes {@code "kind"}, {@code "require"}, {@code "nulls"} and {@code "scope"}
 * only where the value differs from the default a reader assumes for a missing field —
 * {@link WeaveManifest.Extension.Kind#INSTANCE}, {@link Require#REQUIRED}, {@link Nulls#UNCHECKED}
 * and {@link Scope#PUBLIC}. Nothing else is elided: every other field is written even when it
 * holds its default, so the shape of a weave entry does not depend on its contents.
 *
 * <p>The test for {@code "kind"} is that the value is not {@link WeaveManifest.Extension.Kind#INSTANCE},
 * rather than that it is one of the kinds this version knows about. Writing only the kinds
 * enumerated today is how a kind added later would be written as nothing and read back as an
 * instance extension.
 *
 * <h2>Determinism</h2>
 *
 * <p>Rendering iterates lists in order and no hash-ordered collection, and consults nothing
 * outside the manifest that could change the bytes it produces — no clock and no environment.
 * Escaping a control character below {@code U+0020} formats its code unit through the default
 * locale, but that format has no locale-sensitive digits or grouping, so the bytes are identical
 * whichever locale is active. The same manifest therefore
 * renders to the same bytes on every run and on every machine, which is what allows a jar
 * carrying one to be reproducible.
 *
 * <h2>Thread safety</h2>
 *
 * <p>Stateless: {@link #write(WeaveManifest)} builds its own buffer and shares nothing, so it may
 * be called concurrently.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see ManifestReader
 */
public final class ManifestWriter {

    /** One level of indentation. Every nested level repeats it. */
    private static final String INDENT = "  ";

    /**
     * Refuses instantiation; every entry point is static.
     *
     * @throws AssertionError always
     */
    private ManifestWriter() {
        throw new AssertionError("no instances");
    }

    /**
     * Renders a manifest as the text of a {@value WeaveManifest#RESOURCE} resource.
     *
     * <p>The result ends with a newline and is meant to be stored as UTF-8. It always states a
     * {@code "version"} — the one the manifest carries, not necessarily
     * {@link WeaveManifest#VERSION} — and always writes both arrays, empty or not.
     *
     * @param manifest the manifest to render; must not be {@code null}
     * @return the document text, ending with a newline
     * @throws NullPointerException if {@code manifest} is {@code null}
     */
    @Contract(pure = true)
    @NotNull
    public static String write(@NotNull final WeaveManifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        final StringBuilder out = new StringBuilder(512);
        out.append("{\n");
        field(out, 1, "version", String.valueOf(manifest.version()), true);
        field(out, 1, "generator", Json.quote(manifest.generator()), true);
        out.append(INDENT).append("\"weaves\": ");
        array(out, 1, manifest.weaves(), (weave, depth) -> weave(out, depth, weave));
        out.append(",\n");
        out.append(INDENT).append("\"extensions\": ");
        array(out, 1, manifest.extensions(), (extension, depth) -> extension(out, depth, extension));
        out.append('\n').append("}\n");
        return out.toString();
    }

    /**
     * Appends one extension object, omitting each policy that holds its default.
     *
     * <p>The four flags are computed before anything is written because each field has to know
     * whether a comma follows it, and that depends on whether any later field will be written at
     * all.
     *
     * @param out       the buffer to append to; must not be {@code null}
     * @param depth     the indentation level of the object's closing brace
     * @param extension the extension to render; must not be {@code null}
     */
    private static void extension(@NotNull final StringBuilder out,
                                  final int depth,
                                  @NotNull final WeaveManifest.Extension extension) {
        // Anything but the default, rather than "static" specifically. Writing only the kind this
        // version happens to know about is how a third kind ends up read back as the first.
        final boolean named = extension.kind() != WeaveManifest.Extension.Kind.INSTANCE;
        final boolean optional = extension.require() != Require.REQUIRED;
        final boolean nulls = extension.nulls() != Nulls.UNCHECKED;
        final boolean scoped = extension.scope() != Scope.PUBLIC;
        out.append("{\n");
        field(out, depth + 1, "class", Json.quote(extension.className()), true);
        field(out, depth + 1, "receiver", Json.quote(extension.receiver()), true);
        field(out, depth + 1, "name", Json.quote(extension.name()), true);
        field(out, depth + 1, "descriptor", Json.quote(extension.descriptor()),
                named || optional || nulls || scoped);
        if (named) {
            field(out, depth + 1, "kind", Json.quote(extension.kind().token()),
                    optional || nulls || scoped);
        }
        if (optional) {
            field(out, depth + 1, "require", Json.quote(extension.require().name()),
                    nulls || scoped);
        }
        if (nulls) {
            field(out, depth + 1, "nulls", Json.quote(extension.nulls().name()), scoped);
        }
        if (scoped) {
            field(out, depth + 1, "scope", Json.quote(extension.scope().name()), false);
        }
        out.append(INDENT.repeat(depth)).append('}');
    }

    /**
     * Appends one weave object, with its members and injectors nested inside it.
     *
     * @param out   the buffer to append to; must not be {@code null}
     * @param depth the indentation level of the object's closing brace
     * @param weave the weave to render; must not be {@code null}
     */
    private static void weave(@NotNull final StringBuilder out,
                              final int depth,
                              @NotNull final WeaveManifest.Weave weave) {
        out.append("{\n");
        field(out, depth + 1, "class", Json.quote(weave.className()), true);
        field(out, depth + 1, "kind", Json.quote(weave.kind()), true);
        field(out, depth + 1, "priority", String.valueOf(weave.priority()), true);
        field(out, depth + 1, "require", Json.quote(weave.require()), true);
        field(out, depth + 1, "phase", Json.quote(weave.phase()), true);
        field(out, depth + 1, "tags", strings(weave.tags()), true);
        field(out, depth + 1, "targets", strings(weave.targets()), true);

        out.append(INDENT.repeat(depth + 1)).append("\"members\": ");
        array(out, depth + 1, weave.members(), (member, at) -> member(out, at, member));
        out.append(",\n");

        out.append(INDENT.repeat(depth + 1)).append("\"injectors\": ");
        array(out, depth + 1, weave.injectors(), (injector, at) -> injector(out, at, injector));
        out.append('\n').append(INDENT.repeat(depth)).append('}');
    }

    /**
     * Appends one member object. Every field is written, including {@code "unique": false}.
     *
     * @param out    the buffer to append to; must not be {@code null}
     * @param depth  the indentation level of the object's closing brace
     * @param member the member to render; must not be {@code null}
     */
    private static void member(@NotNull final StringBuilder out,
                               final int depth,
                               @NotNull final WeaveManifest.Member member) {
        out.append("{\n");
        field(out, depth + 1, "disposition", Json.quote(member.disposition()), true);
        field(out, depth + 1, "kind", Json.quote(member.kind()), true);
        field(out, depth + 1, "name", Json.quote(member.name()), true);
        field(out, depth + 1, "descriptor", Json.quote(member.descriptor()), true);
        field(out, depth + 1, "targetName", Json.quote(member.targetName()), true);
        field(out, depth + 1, "unique", String.valueOf(member.unique()), false);
        out.append(INDENT.repeat(depth)).append('}');
    }

    /**
     * Appends one injector object, with its points nested between {@code "method"} and
     * {@code "require"}.
     *
     * @param out      the buffer to append to; must not be {@code null}
     * @param depth    the indentation level of the object's closing brace
     * @param injector the injector to render; must not be {@code null}
     */
    private static void injector(@NotNull final StringBuilder out,
                                 final int depth,
                                 @NotNull final WeaveManifest.Injector injector) {
        out.append("{\n");
        field(out, depth + 1, "kind", Json.quote(injector.kind()), true);
        field(out, depth + 1, "id", Json.quote(injector.id()), true);
        field(out, depth + 1, "handler", Json.quote(injector.handler()), true);
        field(out, depth + 1, "method", Json.quote(injector.method()), true);

        out.append(INDENT.repeat(depth + 1)).append("\"points\": ");
        array(out, depth + 1, injector.points(), (point, at) -> point(out, at, point));
        out.append(",\n");

        field(out, depth + 1, "require", String.valueOf(injector.require()), true);
        field(out, depth + 1, "allow", String.valueOf(injector.allow()), true);
        field(out, depth + 1, "group", Json.quote(injector.group()), false);
        out.append(INDENT.repeat(depth)).append('}');
    }

    /**
     * Appends one point object.
     *
     * @param out   the buffer to append to; must not be {@code null}
     * @param depth the indentation level of the object's closing brace
     * @param point the point to render; must not be {@code null}
     */
    private static void point(@NotNull final StringBuilder out,
                              final int depth,
                              @NotNull final WeaveManifest.Point point) {
        out.append("{\n");
        field(out, depth + 1, "point", Json.quote(point.point()), true);
        field(out, depth + 1, "target", Json.quote(point.target()), true);
        field(out, depth + 1, "ordinal", String.valueOf(point.ordinal()), true);
        field(out, depth + 1, "shift", Json.quote(point.shift()), true);
        field(out, depth + 1, "by", String.valueOf(point.by()), true);
        field(out, depth + 1, "access", Json.quote(point.access()), true);
        field(out, depth + 1, "slice", Json.quote(point.slice()), false);
        out.append(INDENT.repeat(depth)).append('}');
    }

    /**
     * Appends one indented {@code "key": value} line.
     *
     * <p>The value is appended as given, so a caller that means a JSON string has to quote it
     * with {@link Json#quote(String)} first; a caller passing a number or a nested array passes
     * its text unquoted.
     *
     * @param out   the buffer to append to; must not be {@code null}
     * @param depth the indentation level of the line
     * @param key   the field name, written without escaping because every key here is a literal
     * @param value the rendered value, already quoted where it is a string
     * @param more  whether another field follows, which decides between a trailing comma and a
     *              bare newline
     */
    private static void field(@NotNull final StringBuilder out,
                              final int depth,
                              @NotNull final String key,
                              @NotNull final String value,
                              final boolean more) {
        out.append(INDENT.repeat(depth)).append('"').append(key).append("\": ").append(value);
        out.append(more ? ",\n" : "\n");
    }

    /**
     * Appends an array of objects, one entry per line, and closes it at the given level.
     *
     * <p>An empty list is written as {@code []} with no line break, so an object holding nothing
     * costs one line rather than three.
     *
     * @param <T>     the entry type
     * @param out     the buffer to append to; must not be {@code null}
     * @param depth   the indentation level of the closing bracket
     * @param entries the entries to render, in order; must not be {@code null}
     * @param each    appends one entry at the indentation level it is given; must not be
     *                {@code null}
     */
    private static <T> void array(@NotNull final StringBuilder out,
                                  final int depth,
                                  @NotNull final List<T> entries,
                                  @NotNull final EntryWriter<T> each) {
        if (entries.isEmpty()) {
            out.append("[]");
            return;
        }
        out.append("[\n");
        for (int i = 0; i < entries.size(); i++) {
            out.append(INDENT.repeat(depth + 1));
            each.write(entries.get(i), depth + 1);
            out.append(i + 1 < entries.size() ? ",\n" : "\n");
        }
        out.append(INDENT.repeat(depth)).append(']');
    }

    /**
     * Renders a list of strings as a single-line JSON array.
     *
     * @param values the strings to render, in order; must not be {@code null}
     * @return the array text, {@code []} for an empty list, with each element quoted and escaped
     */
    @Contract(pure = true)
    @NotNull
    private static String strings(@NotNull final List<String> values) {
        final StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(Json.quote(values.get(i)));
        }
        return out.append(']').toString();
    }

    /**
     * Appends one entry of an array.
     *
     * <p>The buffer is not a parameter: every implementation is a lambda that closes over the one
     * buffer {@link #write(WeaveManifest)} created, which keeps a single {@link StringBuilder} for
     * the whole document.
     *
     * @param <T> the entry type
     * @author Erik Pförtner
     * @since 0.1.0
     */
    @FunctionalInterface
    private interface EntryWriter<T> {

        /**
         * Appends the given entry.
         *
         * @param entry the entry to render; must not be {@code null}
         * @param depth the indentation level of the entry's own closing brace
         */
        void write(@NotNull T entry, int depth);
    }
}
