package de.splatgames.aether.weaver.api.diagnostic;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

/**
 * The identity of a reportable condition: a stable code, a default severity, a category and a
 * one-line summary.
 *
 * <p>This is what a {@link Diagnostic} is built from, and the only thing a reader can match on
 * without parsing prose. Two implementations exist and they occupy disjoint code spaces:
 * {@link DiagnosticCode}, the framework's own catalogue, whose {@link #code()} is always
 * {@code AW} followed by four digits; and {@link PluginDiagnosticId}, whose {@link #code()} is
 * always {@code namespace:IDENTIFIER}. A colon appears in exactly one of the two forms, so no
 * plugin code can ever be mistaken for a built-in one and {@link DiagnosticCode#of(String)} returns
 * empty for every plugin code.
 *
 * <h2>Implementing it</h2>
 *
 * <p>A plugin that needs its own conditions should use {@link PluginDiagnosticId} rather than
 * writing a fresh implementation: it enforces the namespace and identifier shapes that keep the two
 * code spaces disjoint, and it refuses the {@code aether} namespace. An implementation written by
 * hand must satisfy the same rules, and in addition:
 *
 * <ul>
 *   <li>{@link #code()}, {@link #defaultSeverity()}, {@link #category()}, {@link #summary()} and
 *       {@link #namespace()} must be pure and must return the same value on every call. A
 *       {@link Diagnostic} reads them once, at build time, and a value that changed afterwards
 *       would make {@link Diagnostic#equals(Object)} inconsistent.
 *   <li>None of them may return {@code null}.
 *   <li>{@link Object#equals(Object)} and {@link Object#hashCode()} must compare by value.
 *       {@link Diagnostic#equals(Object)} compares codes with {@link Object#equals(Object)} rather
 *       than with {@code ==}, so two identities describing the same condition have to be equal for
 *       two reports of it to be equal. An enum satisfies this by identity; a record satisfies it
 *       componentwise.
 *   <li>{@link #summary()} must not be blank. It is the text a {@link Diagnostic} falls back to
 *       when no message is given, and it is what reference tables and tooltips show.
 * </ul>
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * // A plugin's own condition, reported through the Reporter it was handed.
 * static final DiagnosticId WRAP_NOT_REENTRANT = new PluginDiagnosticId(
 *         "acme", "WRAP_NOT_REENTRANT", Severity.ERROR, DiagnosticCode.Category.DECLARATION,
 *         "a wrapped operation was invoked twice in one handler");
 *
 * reporter.report(Diagnostic.builder(WRAP_NOT_REENTRANT)
 *         .message("acme.WrapHandler#around invokes operation.call() twice")
 *         .remedy("call the operation exactly once")
 *         .build());
 * // formats as: acme:WRAP_NOT_REENTRANT ...
 * }</pre>
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see DiagnosticCode
 * @see PluginDiagnosticId
 */
public interface DiagnosticId {

    /**
     * Returns the wire form of this identity: the string a user sees in build output and searches
     * for.
     *
     * <p>{@code AW} followed by four digits for a built-in condition, and
     * {@code namespace:IDENTIFIER} for a contributed one. It is the first thing
     * {@link Diagnostic#format()} writes and the key {@link DiagnosticCode#of(String)} looks up.
     *
     * @return the code, never blank
     */
    @Contract(pure = true)
    @NotNull
    String code();

    /**
     * Returns the severity a {@link Diagnostic} carries when its builder was not told otherwise.
     *
     * <p>This is a property of the condition, not of one report of it. A single site may raise the
     * severity for a particular report through {@link Diagnostic.Builder#severity(Severity)}. The
     * default {@link #isSuppressible()} is decided by the value returned here, though an
     * implementation may override {@link #isSuppressible()} to answer inconsistently with it.
     *
     * @return the default severity
     */
    @Contract(pure = true)
    @NotNull
    Severity defaultSeverity();

    /**
     * Returns the part of the system this condition belongs to.
     *
     * <p>The categories are declared by {@link DiagnosticCode.Category} and are shared by both
     * implementations, so a plugin's condition is grouped with the framework's own conditions of
     * the same kind rather than into a category of its own.
     *
     * @return the category
     */
    @Contract(pure = true)
    @NotNull
    DiagnosticCode.Category category();

    /**
     * Returns a one-line description of the condition, independent of any particular occurrence.
     *
     * <p>Used in two places: as the message of a {@link Diagnostic} built without one, and as the
     * text shown in reference tables and tooltips. It describes the condition in general and must
     * therefore not name a class, a member or a position.
     *
     * @return the summary, never blank
     */
    @Contract(pure = true)
    @NotNull
    String summary();

    /**
     * Returns the namespace that owns this condition, or an empty string for a built-in one.
     *
     * <p>The default is the empty string, which is what {@link DiagnosticCode} reports and what
     * makes {@link #isBuiltIn()} true. {@link PluginDiagnosticId} overrides it with the namespace
     * component of its {@link #code()}.
     *
     * @return the owning namespace, or an empty string when the condition is built in
     */
    @Contract(pure = true)
    @NotNull
    default String namespace() {
        return "";
    }

    /**
     * Reports whether this condition belongs to the framework rather than to a plugin.
     *
     * <p>Derived from {@link #namespace()} being empty, which is exactly the difference between the
     * two wire forms: a built-in code carries no colon and therefore no namespace.
     *
     * @return {@code true} when {@link #namespace()} is empty
     */
    @Contract(pure = true)
    default boolean isBuiltIn() {
        return namespace().isEmpty();
    }

    /**
     * Reports whether a report of this condition may be silenced.
     *
     * <p>The default implementation derives this from {@link #defaultSeverity()} through
     * {@link Severity#isSuppressible()}. It is a default method on an unsealed interface, so a
     * hand-written implementation may override it to return a value inconsistent with the severity
     * it declares — nothing enforces the two agreeing. {@link Diagnostic#isSuppressible()} does not
     * consult this method in any case: it asks the severity the diagnostic actually carries.
     *
     * @return {@code true} unless {@link #defaultSeverity()} is {@link Severity#ERROR}
     */
    @Contract(pure = true)
    default boolean isSuppressible() {
        return defaultSeverity().isSuppressible();
    }
}
