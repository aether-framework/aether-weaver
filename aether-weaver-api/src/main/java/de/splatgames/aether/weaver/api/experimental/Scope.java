package de.splatgames.aether.weaver.api.experimental;

import org.jetbrains.annotations.ApiStatus;

/**
 * Who is offered the members an {@link Extension} class contributes.
 *
 * <p>Declared with {@link Extension#scope()} and applying to every contribution of that class.
 * There is no per-method override.
 *
 * <p>The scope is enforced where the compiler stubs are generated, and there only. A call site can
 * name a contributed member only if a stub for it was produced, so withholding the stub is what
 * withholds the member; nothing at weave time consults this value, which means a call site that
 * did compile is rewritten whatever the scope says. This is a rule about what other code may be
 * written against, not a rule about what runs.
 *
 * <h2>Stability</h2>
 *
 * <p>Marked {@link ApiStatus.Experimental}, as is every other type in this package. That annotation
 * is the whole of the promise the source makes: no compatibility guarantee is stated for this
 * declaration, and nothing here names a release in which its shape is fixed. A scope other than
 * {@link #PUBLIC} is written into the generated manifest by the constant's name; {@link #PUBLIC}
 * itself is omitted rather than written out. A reader that does not know a constant it does find
 * there reports {@code AW2300} and treats the entry as {@link #PUBLIC} rather than dropping it —
 * which offers a contribution more widely than it asked to be offered, and is worth knowing before a
 * narrower constant is relied on.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see Extension#scope()
 */
@ApiStatus.Experimental
public enum Scope {

    /**
     * Every module that reads the manifest is offered the contributions.
     *
     * <p>The default of {@link Extension#scope()}, and the value a manifest that names no scope
     * reads as. A stub is generated for the receiver wherever the declaring artefact is on the
     * compile classpath, so a consumer of the artefact can call the contributed member.
     */
    PUBLIC,

    /**
     * Only the module that declares the contributions is offered them.
     *
     * <p>A stub is generated when the manifest entry comes from the project's own compilation
     * output; the same entry arriving from a dependency is withheld, so a consumer's call site
     * naming the member does not compile. The count of withheld entries is reported at debug level
     * rather than as a diagnostic, since withholding is what was asked for.
     *
     * <p>Ownership is decided by the compilation output directory being on the classpath. A build
     * that has no output directory to compare against owns nothing, so every entry with this scope
     * is then treated as a dependency's and withheld.
     */
    MODULE
}
