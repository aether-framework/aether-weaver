package de.splatgames.aether.weaver.api.diagnostic;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A diagnostic identity owned by a plugin rather than by the framework.
 *
 * <p>The wire form is {@code namespace:IDENTIFIER}, produced by {@link #code()}. That colon is what
 * keeps the two code spaces apart: a {@link DiagnosticCode} is always {@code AW} followed by four
 * digits and never contains a colon, so {@link DiagnosticCode#of(String)} returns empty for every
 * value of this record and no plugin condition can be read as a built-in one.
 *
 * <h2>What the constructor refuses</h2>
 *
 * <p>The canonical constructor validates every component and throws rather than accepting an
 * identity that could not be attributed. All of these are {@link IllegalArgumentException} except
 * the {@code null} checks, which are {@link NullPointerException}.
 *
 * <ul>
 *   <li>Any {@code null} component.
 *   <li>A {@code namespace} that does not match {@code [a-z][a-z0-9-]*}: it must begin with a
 *       lower-case letter and may then contain lower-case letters, digits and hyphens. An empty
 *       namespace is refused, because the empty namespace is what {@link DiagnosticId#isBuiltIn()}
 *       means. A dot is refused, so a namespace can never look like a package name that a reader
 *       would try to resolve.
 *   <li>The namespace {@code aether}, which is reserved for Aether Weaver.
 *   <li>An {@code id} that does not match {@code [A-Z][A-Z0-9_]*}: it must begin with an upper-case
 *       letter and may then contain upper-case letters, digits and underscores. The case difference
 *       from the namespace is what makes the two halves of a code readable apart at a glance.
 *   <li>A blank {@code summary}. It is what a {@link Diagnostic} built without a message falls back
 *       to, and what reference tables and tooltips show.
 * </ul>
 *
 * <p>The engine reports a plugin that claims the reserved namespace for its identifiers as
 * {@code AW3101}, and a namespace that is malformed as {@code AW3100}; giving the plugin a
 * namespace of its own resolves both. A contribution registered under a namespace other than the
 * plugin's own is {@code AW3110}.
 *
 * <h2>Severity is not negotiable</h2>
 *
 * <p>{@link DiagnosticId#isSuppressible()} is derived from {@link #defaultSeverity()} through
 * {@link Severity#isSuppressible()}, and this record does not override it, so a plugin that
 * declares {@link Severity#ERROR} answers {@code false} from that method and one that declares
 * {@link Severity#WARNING} answers {@code true}. Nothing under {@code src/main} of any module
 * calls {@link DiagnosticId#isSuppressible()}, so neither answer currently changes what a build
 * does with the diagnostic.
 *
 * <h2>Equality</h2>
 *
 * <p>Record equality, componentwise. This matters beyond the record itself:
 * {@link Diagnostic#equals(Object)} compares codes with {@link Object#equals(Object)} rather than
 * with {@code ==}, so two separately constructed identities with the same five components produce
 * equal diagnostics. Two identities that differ only in {@link #summary()} are not equal and will
 * not compare as the same condition.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * public final class AcmePlugin implements de.splatgames.aether.weaver.api.spi.WeaverPlugin {
 *
 *     static final DiagnosticId WRAP_NOT_REENTRANT = new PluginDiagnosticId(
 *             "acme",                                   // the plugin's namespace
 *             "WRAP_NOT_REENTRANT",                     // upper case, underscores allowed
 *             Severity.ERROR,                           // not suppressible
 *             DiagnosticCode.Category.DECLARATION,
 *             "a wrapped operation was invoked twice in one handler");
 *
 *     // WRAP_NOT_REENTRANT.code() is "acme:WRAP_NOT_REENTRANT"
 * }
 * }</pre>
 *
 * @param namespace       the owning namespace, matching {@code [a-z][a-z0-9-]*} and not
 *                        {@code aether}
 * @param id              the identifier within that namespace, matching {@code [A-Z][A-Z0-9_]*}
 * @param defaultSeverity the severity a {@link Diagnostic} of this condition carries unless
 *                        overridden
 * @param category        the part of the system the condition belongs to
 * @param summary         a one-line description of the condition, not blank
 * @author Erik Pförtner
 * @since 0.1.0
 * @see DiagnosticCode
 */
public record PluginDiagnosticId(@NotNull String namespace,
                                 @NotNull String id,
                                 @NotNull Severity defaultSeverity,
                                 @NotNull DiagnosticCode.Category category,
                                 @NotNull String summary) implements DiagnosticId {

    /** The namespace Aether Weaver keeps for itself; no plugin may claim it. */
    private static final String RESERVED_NAMESPACE = "aether";

    /** The shape a namespace must have: lower case, starting with a letter, hyphens allowed. */
    private static final Pattern NAMESPACE = Pattern.compile("[a-z][a-z0-9-]*");

    /** The shape an identifier must have: upper case, starting with a letter, underscores allowed. */
    private static final Pattern ID = Pattern.compile("[A-Z][A-Z0-9_]*");

    /**
     * Rejects an identity that could not be attributed or could be confused with a built-in code.
     *
     * @throws NullPointerException     if any component is {@code null}
     * @throws IllegalArgumentException if {@code namespace} does not match
     *                                  {@code [a-z][a-z0-9-]*}, if it is {@code aether}, if
     *                                  {@code id} does not match {@code [A-Z][A-Z0-9_]*}, or if
     *                                  {@code summary} is blank
     */
    public PluginDiagnosticId {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(defaultSeverity, "defaultSeverity");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(summary, "summary");
        if (!NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException(
                    "namespace must match " + NAMESPACE.pattern() + ", got: " + namespace);
        }
        if (RESERVED_NAMESPACE.equals(namespace)) {
            throw new IllegalArgumentException(
                    "the namespace '" + RESERVED_NAMESPACE + "' is reserved for Aether Weaver");
        }
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    "id must match " + ID.pattern() + ", got: " + id);
        }
        if (summary.isBlank()) {
            throw new IllegalArgumentException("summary must not be blank");
        }
    }

    /**
     * Returns the wire form, {@code namespace:id}.
     *
     * <p>The colon is the only one in the string, since neither component may contain one, and it
     * is what distinguishes this from every {@link DiagnosticCode}.
     *
     * @return the namespace, a colon, and the identifier
     */
    @Contract(pure = true)
    @Override
    @NotNull
    public String code() {
        return this.namespace + ':' + this.id;
    }

    /**
     * Returns {@link #code()}, so that logging a code produces a string a user can search for.
     *
     * @return the wire form
     */
    @Override
    @NotNull
    public String toString() {
        return code();
    }
}
