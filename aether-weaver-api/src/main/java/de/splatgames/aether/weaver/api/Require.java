package de.splatgames.aether.weaver.api;

/**
 * Whether a declaration insists that the type it names is present at compile time.
 *
 * <p>Two declarations carry one of these constants: {@link Weave#require()}, which speaks about the
 * classes a weave targets, and
 * {@link de.splatgames.aether.weaver.api.experimental.Extension#require()}, which speaks about the
 * receiver an extension contributes to. In both cases the constant answers one question and no
 * other: when the named type is not on the compile classpath, is that a build failure or an
 * accepted absence.
 *
 * <h2>What it does not decide</h2>
 *
 * <p>This is a compile-time statement. It is recorded on the parsed weave and written to a
 * generated manifest, and no stage of planning, conflict detection or injection consults it. A
 * weave whose target class is simply never loaded at weave time is not woven and reports nothing,
 * whichever constant it declares; a weave that declares {@link #OPTIONAL} still fails on every
 * other declaration error the engine finds.
 *
 * <p>It also has nothing to do with {@link Inject#require()}, {@link Redirect#require()} and
 * {@link Wrap#require()}, which count matched positions inside a target method and are an
 * {@code int} rather than one of these constants.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * // com.acme.optional.Ledger ships in one build of the target library and not in the other,
 * // so its absence must not fail the compilation of this weave.
 * @Weave(targets = "com.acme.optional.Ledger", require = Require.OPTIONAL)
 * public final class LedgerAudit {
 *
 *     @Inject(method = "charge(java.math.BigDecimal)", at = @At(Point.HEAD))
 *     private void onCharge(java.math.BigDecimal amount) {
 *     }
 * }
 * }</pre>
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see Weave#require()
 */
public enum Require {

    /**
     * The named type must be on the compile classpath.
     *
     * <p>This is the default of both {@link Weave#require()} and
     * {@link de.splatgames.aether.weaver.api.experimental.Extension#require()}, and it is what a
     * declaration that says nothing means.
     *
     * <p>For a weave, a {@link Weave#targets()} name that the annotation processor cannot resolve
     * is reported as {@code AW1004}, an error. A {@link Weave#value()} class literal is resolved by
     * the compiler before the processor ever sees it, so this constant makes no difference to a
     * weave whose targets are written as literals.
     *
     * <p>For an extension, a receiver that is not on the compile classpath makes the build fail
     * when stubs are generated: no stub can be produced for a type that is absent, and every call
     * naming the contributed method would fail to compile with an error that pointed somewhere
     * else.
     */
    REQUIRED,

    /**
     * The named type may be absent at compile time.
     *
     * <p>For a weave, an unresolvable {@link Weave#targets()} name is then accepted silently:
     * {@code AW1004} is not reported and the target is left to be found, or not found, when the
     * weaver runs. Only the string form is affected, because only the string form can name a class
     * the compiler cannot see.
     *
     * <p>For an extension, an absent receiver means the extension is skipped during stub
     * generation rather than failing the build.
     *
     * <p>Nothing else is relaxed. A name that is not a usable binary class name at all is still
     * {@code AW1004} from the engine, and the remaining declaration checks run unchanged.
     */
    OPTIONAL
}
