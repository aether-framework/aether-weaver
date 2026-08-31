package de.splatgames.aether.weaver.engine.plugin;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import de.splatgames.aether.weaver.api.spi.Alias;
import de.splatgames.aether.weaver.api.spi.DiagnosticListener;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * An identifier-to-contribution map in which every identifier names its owner.
 *
 * <p>Two of these exist per run, one for injector kinds and one for injection points. An identifier
 * is either built-in and unqualified, as {@code HEAD}, or a plugin's and prefixed with its
 * namespace, as {@code acme:AFTER_LOGGING}. That rule is what lets a diagnostic attribute an
 * identifier to whoever contributed it, and it is enforced at registration rather than at use.
 *
 * <p>Retired identifiers live in a second map. A lookup through an {@link Alias} answers with the
 * replacement's contribution — the identical object, not a copy — so that which spelling a user
 * wrote cannot change what is woven, only what is warned about.
 *
 * <p>Both maps are sorted. {@link #ids()} is folded into the plan fingerprint, and registration
 * order would put the classpath into it.
 *
 * @param <T> the contribution type, an injector factory or an injection point factory
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class NamespacedRegistry<T> {

    /** The shape a plugin namespace must have. */
    private static final Pattern NAMESPACE = Pattern.compile("[a-z][a-z0-9-]*");

    /** The namespace no plugin may claim. */
    private static final String RESERVED_NAMESPACE = "aether";

    /** What this registry holds, as it appears inside diagnostic messages. */
    private final String what;

    /** The current identifiers and their contributions, sorted by identifier. */
    private final Map<String, T> entries;

    /** The retired identifiers, keyed by the deprecated spelling and sorted by it. */
    private final Map<String, Alias> aliases;

    /**
     * Takes copies of the two maps, sorted.
     *
     * @param what    what this registry holds, for diagnostics; must not be {@code null}
     * @param entries the current identifiers; must not be {@code null}
     * @param aliases the retired identifiers, already checked against {@code entries}; must not be
     *                {@code null}
     */
    private NamespacedRegistry(@NotNull final String what,
                               @NotNull final Map<String, T> entries,
                               @NotNull final Map<String, Alias> aliases) {
        this.what = what;
        this.entries = Collections.unmodifiableMap(new TreeMap<>(entries));
        this.aliases = Collections.unmodifiableMap(new TreeMap<>(aliases));
    }

    /**
     * Starts a registry.
     *
     * @param <T>  the contribution type
     * @param what what the registry will hold, as it should read inside a diagnostic message, such
     *             as {@code "injection point"}; must not be {@code null}
     * @return a new builder
     * @throws NullPointerException if {@code what} is {@code null}
     */
    @Contract(value = "_ -> new", pure = true)
    @NotNull
    public static <T> Builder<T> builder(@NotNull final String what) {
        return new Builder<>(Objects.requireNonNull(what, "what"));
    }

    /**
     * Resolves an identifier, warning if it was reached through a retired spelling.
     *
     * <p>A current identifier costs one map lookup and reports nothing. A retired one reports
     * {@code AW3120} against the given listener before answering, which is the only place a
     * deprecation is announced — nothing is warned about at registration, because an alias that
     * nobody writes is not a problem the user has.
     *
     * <p>The listener therefore decides whether the deprecation is seen at all: a caller passing
     * {@link DiagnosticListener#NOOP} resolves the alias silently.
     *
     * @param id       the identifier as the user wrote it; must not be {@code null}
     * @param listener where a deprecation warning goes; must not be {@code null}
     * @return the contribution, or empty when the identifier is neither registered nor aliased
     * @throws NullPointerException if either argument is {@code null}
     */
    @NotNull
    public Optional<T> lookup(@NotNull final String id,
                              @NotNull final DiagnosticListener listener) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(listener, "listener");

        final T direct = this.entries.get(id);
        if (direct != null) {
            return Optional.of(direct);
        }
        final Alias alias = this.aliases.get(id);
        if (alias == null) {
            return Optional.empty();
        }
        listener.report(Diagnostic.builder(DiagnosticCode.DEPRECATED_ALIAS_USED)
                .message(alias.describe())
                .remedy("replace '" + alias.deprecated() + "' with '" + alias.replacement()
                        + "'; the alias is removed no earlier than two minor versions after "
                        + alias.since())
                .build());
        return Optional.ofNullable(this.entries.get(alias.replacement()));
    }

    /**
     * Reports whether the identifier resolves, current or retired.
     *
     * <p>Silent where {@link #lookup} would warn, so that a "did you mean" search over candidate
     * spellings does not warn about a deprecation the user never wrote.
     *
     * @param id the identifier to probe; must not be {@code null}
     * @return whether it is registered or aliased
     * @throws NullPointerException if {@code id} is {@code null}
     */
    @Contract(pure = true)
    public boolean contains(@NotNull final String id) {
        Objects.requireNonNull(id, "id");
        return this.entries.containsKey(id) || this.aliases.containsKey(id);
    }

    /**
     * Returns the current identifiers, sorted.
     *
     * <p>Retired spellings are left out: this is what the plan fingerprint covers, and it should
     * not name an identifier that is being taken away.
     *
     * @return the identifiers
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    public List<String> ids() {
        return List.copyOf(this.entries.keySet());
    }

    /**
     * Returns the aliases that survived building, sorted by their deprecated spelling.
     *
     * @return the aliases
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    public List<Alias> aliases() {
        return List.copyOf(this.aliases.values());
    }

    /**
     * Returns how many current identifiers are registered, aliases excluded.
     *
     * @return the number of identifiers
     */
    @Contract(pure = true)
    public int size() {
        return this.entries.size();
    }

    /**
     * Returns what this registry holds, as it reads inside a diagnostic message.
     *
     * @return the description
     */
    @Contract(pure = true)
    @NotNull
    public String what() {
        return this.what;
    }

    /**
     * Returns what the registry holds and the two counts.
     *
     * @return the summary rendering
     */
    @Override
    @NotNull
    public String toString() {
        return "NamespacedRegistry[" + this.what + ", " + this.entries.size() + " ids, "
                + this.aliases.size() + " aliases]";
    }

    /**
     * Collects registrations, holding every problem back until {@link #build}.
     *
     * <p>Nothing is reported as it is registered. A registration that breaks a rule is refused, its
     * diagnostic is remembered, and the rest of the run carries on, so one build reports everything
     * that is wrong with the contributions instead of one problem per rebuild. Refusing rather than
     * overwriting is what keeps the first registration of an identifier the one that stands.
     *
     * @param <T> the contribution type
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public static final class Builder<T> {

        /** What the registry will hold, as it appears inside diagnostic messages. */
        private final String what;

        /** The accepted registrations, in registration order until the registry sorts them. */
        private final Map<String, T> entries = new LinkedHashMap<>();

        /** The namespace last seen registering each identifier. */
        private final Map<String, String> owners = new LinkedHashMap<>();

        /** The declared aliases, keyed by the deprecated spelling. */
        private final Map<String, Alias> aliases = new LinkedHashMap<>();

        /** The diagnostics accumulated so far, reported in this order by {@link #build}. */
        private final List<Diagnostic> problems = new ArrayList<>();

        /**
         * Creates a builder.
         *
         * @param what what the registry will hold, for diagnostics; must not be {@code null}
         */
        private Builder(@NotNull final String what) {
            this.what = what;
        }

        /**
         * Registers one identifier, or remembers why it could not be.
         *
         * <p>Three rules are checked in order and the first that fails ends the registration:
         *
         * <ul>
         *   <li>The namespace is well formed. {@code AW3101} for {@code aether}, which is reserved,
         *       and {@code AW3100} for anything else that does not match the pattern. The empty
         *       namespace is the framework's own and passes.
         *   <li>The identifier lies in that namespace. {@code AW3110} otherwise, which drops this
         *       one registration and leaves the contributor's others alone.
         *   <li>No one has claimed the identifier already. {@code AW3111} otherwise; the earlier
         *       registration keeps the identifier and this one is discarded, including when the same
         *       contributor registers the same identifier twice.
         * </ul>
         *
         * <p>{@code owners.put} records this attempt's namespace before the collision check below
         * runs, even when the attempt turns out to be refused.
         *
         * @param namespace the contributor's namespace, empty for the framework itself; must not be
         *                  {@code null}
         * @param id        the identifier to register; must not be {@code null}
         * @param value     the contribution; must not be {@code null}
         * @return this builder
         * @throws NullPointerException if any argument is {@code null}
         */
        @Contract("_, _, _ -> this")
        @NotNull
        public Builder<T> register(@NotNull final String namespace,
                                   @NotNull final String id,
                                   @NotNull final T value) {
            Objects.requireNonNull(namespace, "namespace");
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(value, "value");

            final String namespaceProblem = validateNamespace(namespace);
            if (namespaceProblem != null) {
                this.problems.add(Diagnostic.builder(RESERVED_NAMESPACE.equals(namespace)
                                ? DiagnosticCode.PLUGIN_NAMESPACE_RESERVED
                                : DiagnosticCode.PLUGIN_NAMESPACE_INVALID)
                        .message(namespaceProblem)
                        .build());
                return this;
            }
            if (!inNamespace(namespace, id)) {
                this.problems.add(Diagnostic.builder(
                                DiagnosticCode.PLUGIN_CONTRIBUTION_OUTSIDE_NAMESPACE)
                        .message("namespace '" + namespace + "' registered the " + this.what
                                + " '" + id + "', which is not in it")
                        .remedy(namespace.isEmpty()
                                ? "a built-in identifier must not contain a colon"
                                : "name it '" + namespace + ':' + stripNamespace(id) + '\'')
                        .build());
                return this;
            }
            final String previousOwner = this.owners.put(id, namespace);
            if (previousOwner != null) {
                this.problems.add(Diagnostic.builder(DiagnosticCode.PLUGIN_NAMESPACE_COLLISION)
                        .message("the " + this.what + " '" + id + "' was registered twice")
                        .detail("first by:  " + describeOwner(previousOwner))
                        .detail("then by:   " + describeOwner(namespace))
                        .remedy("two contributors cannot own one identifier; one of them must "
                                + "rename it, or the duplicate registration must be removed")
                        .build());
                return this;
            }
            this.entries.put(id, value);
            return this;
        }

        /**
         * Declares a retired spelling.
         *
         * <p>Declaring the same alias twice is not a problem; declaring two aliases of one
         * deprecated spelling that point at different replacements is, and is remembered as
         * {@code AW3111}. The later declaration is the one kept, so the registry built afterwards
         * follows the alias that was reported.
         *
         * <p>Whether the alias survives is decided in {@link #build}, which is the only place the
         * replacement can be checked.
         *
         * <p>The deprecated spelling is held to the same namespace rule as a registration. Without
         * that, retiring a spelling was a way into a namespace the contributor does not own: an
         * alias naming {@code aether:OLD} built clean and resolved, while registering
         * {@code aether:OLD} outright is {@code AW3100}. The replacement is not checked here — it
         * has to name something registered, which only {@link #build} can see.
         *
         * @param namespace the contributor's namespace, empty for the framework itself; must not
         *                  be {@code null}
         * @param alias     the alias to declare; must not be {@code null}
         * @return this builder
         * @throws NullPointerException if either argument is {@code null}
         */
        @Contract("_, _ -> this")
        @NotNull
        public Builder<T> alias(@NotNull final String namespace, @NotNull final Alias alias) {
            Objects.requireNonNull(namespace, "namespace");
            Objects.requireNonNull(alias, "alias");

            final String namespaceProblem = validateNamespace(namespace);
            if (namespaceProblem != null) {
                this.problems.add(Diagnostic.builder(RESERVED_NAMESPACE.equals(namespace)
                                ? DiagnosticCode.PLUGIN_NAMESPACE_RESERVED
                                : DiagnosticCode.PLUGIN_NAMESPACE_INVALID)
                        .message(namespaceProblem)
                        .build());
                return this;
            }
            if (!inNamespace(namespace, alias.deprecated())) {
                this.problems.add(Diagnostic.builder(
                                DiagnosticCode.PLUGIN_CONTRIBUTION_OUTSIDE_NAMESPACE)
                        .message("namespace '" + namespace + "' retired the " + this.what
                                + " '" + alias.deprecated() + "', which is not in it")
                        .remedy(namespace.isEmpty()
                                ? "a built-in identifier must not contain a colon"
                                : "name it '" + namespace + ':'
                                        + stripNamespace(alias.deprecated()) + '\'')
                        .build());
                return this;
            }

            final Alias previous = this.aliases.put(alias.deprecated(), alias);
            if (previous != null && !previous.equals(alias)) {
                this.problems.add(Diagnostic.builder(DiagnosticCode.PLUGIN_NAMESPACE_COLLISION)
                        .message("the alias '" + alias.deprecated()
                                + "' was declared twice, pointing at different replacements")
                        .detail("first: " + previous.replacement())
                        .detail("then:  " + alias.replacement())
                        .build());
            }
            return this;
        }

        /**
         * Checks the aliases against the registrations, reports everything found and builds the
         * registry.
         *
         * <p>An alias is dropped when its deprecated spelling is also registered, reported as
         * {@code AW3111}, or when its replacement is not registered, reported as {@code AW3121} with
         * the available identifiers listed. Both are checked here rather than in {@link #alias}
         * because an alias may be declared before the identifier it renames.
         *
         * <p>Everything accumulated is reported in the order it was found, and the registry is
         * returned regardless. A broken registration costs its own contribution and nothing else.
         *
         * @param listener where the accumulated diagnostics go; must not be {@code null}
         * @return the registry, holding what survived
         * @throws NullPointerException if {@code listener} is {@code null}
         */
        @NotNull
        public NamespacedRegistry<T> build(@NotNull final DiagnosticListener listener) {
            Objects.requireNonNull(listener, "listener");

            final Map<String, Alias> live = new LinkedHashMap<>();
            for (final Alias alias : this.aliases.values()) {
                if (this.entries.containsKey(alias.deprecated())) {
                    this.problems.add(Diagnostic.builder(DiagnosticCode.PLUGIN_NAMESPACE_COLLISION)
                            .message("'" + alias.deprecated() + "' is registered as a "
                                    + this.what + " and also declared as an alias")
                            .remedy("an identifier is either current or retired, not both")
                            .build());
                    continue;
                }
                if (!this.entries.containsKey(alias.replacement())) {
                    this.problems.add(Diagnostic.builder(DiagnosticCode.ALIAS_TARGET_UNKNOWN)
                            .message("the alias '" + alias.deprecated() + "' points at '"
                                    + alias.replacement() + "', which is not a registered "
                                    + this.what)
                            .details(this.entries.keySet().stream().sorted()
                                    .map(id -> "available: " + id).toList())
                            .remedy("register '" + alias.replacement()
                                    + "' or correct the alias — an alias cannot create an "
                                    + "identifier, only rename one")
                            .build());
                    continue;
                }
                live.put(alias.deprecated(), alias);
            }
            for (final Diagnostic problem : this.problems) {
                listener.report(problem);
            }
            return new NamespacedRegistry<>(this.what, this.entries, live);
        }

        /**
         * Checks a namespace.
         *
         * <p>The empty namespace is the framework's own and is accepted; {@code aether} is refused
         * although it looks well formed, so that the qualified spelling of a built-in identifier
         * cannot be taken by anyone.
         *
         * @param namespace the namespace to check; must not be {@code null}
         * @return the message describing what is wrong, or {@code null} when the namespace is
         *         acceptable
         */
        @Contract(pure = true)
        private static @Nullable String validateNamespace(@NotNull final String namespace) {
            if (namespace.isEmpty()) {
                return null;
            }
            if (RESERVED_NAMESPACE.equals(namespace)) {
                return "the namespace '" + RESERVED_NAMESPACE + "' is reserved for Aether Weaver";
            }
            if (!NAMESPACE.matcher(namespace).matches()) {
                return "namespace '" + namespace + "' must match " + NAMESPACE.pattern();
            }
            return null;
        }

        /**
         * Reports whether an identifier belongs to a namespace.
         *
         * <p>A built-in identifier carries no colon at all, and a plugin's carries its namespace and
         * at least one character after the colon, so neither {@code acme:} nor a bare {@code :}
         * qualifies.
         *
         * @param namespace the owning namespace, empty for the framework; must not be {@code null}
         * @param id        the identifier; must not be {@code null}
         * @return whether the identifier lies in the namespace
         */
        @Contract(pure = true)
        private static boolean inNamespace(@NotNull final String namespace,
                                           @NotNull final String id) {
            if (namespace.isEmpty()) {
                return id.indexOf(':') < 0 && !id.isEmpty();
            }
            return id.startsWith(namespace + ':') && id.length() > namespace.length() + 1;
        }

        /**
         * Returns an identifier without its namespace, for suggesting the spelling it should have
         * had.
         *
         * @param id the identifier; must not be {@code null}
         * @return the part after the first colon, or the whole identifier when there is none
         */
        @Contract(pure = true)
        @NotNull
        private static String stripNamespace(@NotNull final String id) {
            final int colon = id.indexOf(':');
            return colon < 0 ? id : id.substring(colon + 1);
        }

        /**
         * Names a contributor in a diagnostic.
         *
         * <p>The empty namespace has no name a user would recognise, so it is spelled out rather
         * than shown as an empty quotation.
         *
         * @param namespace the contributor's namespace; must not be {@code null}
         * @return the rendering
         */
        @Contract(pure = true)
        @NotNull
        private static String describeOwner(@NotNull final String namespace) {
            return namespace.isEmpty() ? "Aether Weaver itself" : "plugin '" + namespace + '\'';
        }
    }
}
