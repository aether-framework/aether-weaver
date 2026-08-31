package de.splatgames.aether.weaver.api.model;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Names what a declaration does to the position it matched, and thereby which injector is asked to
 * emit it.
 *
 * <p>A kind is an identifier, not an enumeration. The built-in kinds are constants of this record
 * rather than enum constants so that a plugin can contribute a kind this release never heard of and
 * still be a first-class participant: the engine looks an injector up by
 * {@linkplain #id() identifier} alone and has no list of permitted values to extend.
 *
 * <h2>Identifier grammar</h2>
 *
 * <p>An identifier is either unqualified, such as {@code inject}, or namespaced as
 * {@code namespace:name}. The rules are checked by the canonical constructor:
 *
 * <ul>
 *   <li>It must contain a non-whitespace character.
 *   <li>It may contain at most one colon.
 *   <li>A colon may be neither the first nor the last character, so both halves are non-empty.
 * </ul>
 *
 * <p>Anything else throws {@link IllegalArgumentException} from the constructor. No case folding,
 * trimming or normalisation is applied, and two kinds are equal exactly when their identifiers are
 * equal as strings.
 *
 * <h2>The unqualified namespace is reserved</h2>
 *
 * <p>An identifier with no colon belongs to the built-in namespace, which is why
 * {@link de.splatgames.aether.weaver.api.Point#HEAD} spells as {@code HEAD} and not
 * {@code aether:HEAD}. {@link #of(String)} therefore refuses an unqualified identifier outright and
 * exists for exactly that reason: it is the factory a plugin author calls, and the failure is a
 * thrown {@link IllegalArgumentException} whose message spells the namespaced form to write
 * instead. The canonical constructor does not refuse it, because the constants below are built with
 * it.
 *
 * <p>The same rule is enforced again at registration. A plugin's
 * {@link de.splatgames.aether.weaver.api.spi.InjectorFactory} declaring a namespace that is not the
 * plugin's own, or offering a kind whose identifier is not prefixed with the plugin's namespace and
 * a colon, is reported as {@code AW3110} and the whole factory is dropped rather than partly
 * registered. Prefix each kind with your namespace: an identifier that does not name its owner
 * cannot be attributed in a diagnostic and cannot be switched off as a set.
 *
 * <h2>The built-in constants</h2>
 *
 * <p>{@link #INJECT}, {@link #REDIRECT} and {@link #WRAP} are the kinds the weave class parser
 * produces, one per handler annotation, and the three the built-in plugin registers an injector
 * for. {@link #MERGE}, {@link #ACCESSOR} and {@link #INVOKER} name the identifiers that would
 * describe the structural dispositions; no injector is registered for them and no declaration is
 * parsed into one, because a merged member, an accessor and an invoker are not emitted at a matched
 * position but folded into the target as members.
 *
 * <p>A declaration whose kind has no registered injector is reported as {@code AW4090} and skipped:
 * a namespaced kind whose plugin is not on the classpath lands here, as does an
 * {@link InjectorSpec} built directly with a kind nothing offers.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * public final class AcmeInjectors implements InjectorFactory {
 *
 *     static final InjectorKind TRACE = InjectorKind.of("acme:trace");
 *
 *     @Override
 *     public String namespace() {
 *         return "acme";                       // must match the plugin's own namespace
 *     }
 *
 *     @Override
 *     public Set<InjectorKind> kinds() {
 *         return Set.of(TRACE);                // every id here must start with "acme:"
 *     }
 *
 *     @Override
 *     public Injector create(InjectorKind kind) {
 *         return new TraceInjector();
 *     }
 * }
 *
 * InjectorKind.of("trace");        // IllegalArgumentException: the unqualified namespace is reserved
 * TRACE.isBuiltIn();               // false
 * TRACE.namespace();               // acme
 * InjectorKind.INJECT.namespace(); // "" — the built-in namespace
 * }</pre>
 *
 * @param id the identifier, unqualified for a built-in kind and {@code namespace:name} otherwise
 * @author Erik Pförtner
 * @since 0.1.0
 * @see de.splatgames.aether.weaver.api.spi.InjectorFactory
 */
public record InjectorKind(String id) {

    /**
     * The kind of a {@link de.splatgames.aether.weaver.api.Inject} declaration, which calls a
     * {@code void} handler beside the matched position and leaves the position itself alone.
     */
    public static final InjectorKind INJECT = new InjectorKind("inject");

    /**
     * The kind of a {@link de.splatgames.aether.weaver.api.Redirect} declaration, which replaces
     * the matched operation with a call to the handler.
     */
    public static final InjectorKind REDIRECT = new InjectorKind("redirect");

    /**
     * The kind of a {@link de.splatgames.aether.weaver.api.Wrap} declaration, which hands the
     * matched operation to the handler as something it may perform, repeat or skip.
     */
    public static final InjectorKind WRAP = new InjectorKind("wrap");

    /**
     * The identifier {@code merge}, naming the disposition of a weave member copied into its
     * target. No injector is registered for it: a merged member is folded into the target as a
     * member rather than emitted at a matched position.
     */
    public static final InjectorKind MERGE = new InjectorKind("merge");

    /**
     * The identifier {@code accessor}, naming the disposition of an
     * {@link de.splatgames.aether.weaver.api.Accessor} declaration. No injector is registered for
     * it; an accessor becomes a member of the target.
     */
    public static final InjectorKind ACCESSOR = new InjectorKind("accessor");

    /**
     * The identifier {@code invoker}, naming the disposition of an
     * {@link de.splatgames.aether.weaver.api.Invoker} declaration. No injector is registered for
     * it; an invoker becomes a member of the target.
     */
    public static final InjectorKind INVOKER = new InjectorKind("invoker");

    /**
     * Checks the identifier's grammar.
     *
     * <p>Accepts an unqualified identifier, which {@link #of(String)} does not; the built-in
     * constants of this record are the reason it has to.
     *
     * @throws NullPointerException     if {@code id} is {@code null}
     * @throws IllegalArgumentException if {@code id} is blank, contains more than one colon, or
     *                                  begins or ends with one
     */
    public InjectorKind {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("an injector kind must not be blank");
        }
        final int colon = id.indexOf(':');
        if (colon >= 0) {
            if (colon == 0 || colon == id.length() - 1) {
                throw new IllegalArgumentException(
                        "a namespaced injector kind needs text on both sides of the colon, but was "
                                + '"' + id + '"');
            }
            if (id.indexOf(':', colon + 1) >= 0) {
                throw new IllegalArgumentException(
                        "an injector kind has at most one colon, but was \"" + id + '"');
            }
        }
    }

    /**
     * Returns a contributed kind, refusing any identifier that would claim the built-in namespace.
     *
     * <p>This is the factory a plugin uses. Everything the canonical constructor rejects is
     * rejected here as well, and an unqualified identifier is rejected on top of that.
     *
     * @param id the identifier, which must be of the form {@code namespace:name}
     * @return the kind
     * @throws NullPointerException     if {@code id} is {@code null}
     * @throws IllegalArgumentException if {@code id} is blank, is malformed, or carries no
     *                                  namespace
     */
    @Contract(value = "_ -> new", pure = true)
    @NotNull
    public static InjectorKind of(@NotNull final String id) {
        final InjectorKind kind = new InjectorKind(id);
        if (kind.isBuiltIn()) {
            throw new IllegalArgumentException(
                    "the unqualified namespace is reserved for built-in injectors; name this one "
                            + "\"yournamespace:" + id + '"');
        }
        return kind;
    }

    /**
     * Reports whether this kind belongs to the built-in namespace.
     *
     * <p>Decided by the spelling alone. A kind with no colon answers {@code true} whether or not
     * this release declares a constant for it.
     *
     * @return {@code true} when {@link #id()} contains no colon
     */
    @Contract(pure = true)
    public boolean isBuiltIn() {
        return this.id.indexOf(':') < 0;
    }

    /**
     * Returns the part of the identifier before the colon.
     *
     * @return the namespace, or the empty string for a built-in kind
     */
    @Contract(pure = true)
    @NotNull
    public String namespace() {
        final int colon = this.id.indexOf(':');
        return colon < 0 ? "" : this.id.substring(0, colon);
    }

    /**
     * Returns the identifier unchanged.
     *
     * <p>Overridden away from the record's generated form: this reads as {@code acme:trace} rather
     * than as {@code InjectorKind[id=acme:trace]}.
     *
     * @return {@link #id()}
     */
    @Override
    @Contract(pure = true)
    @NotNull
    public String toString() {
        return this.id;
    }
}
