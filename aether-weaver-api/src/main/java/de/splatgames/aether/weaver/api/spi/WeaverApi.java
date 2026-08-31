package de.splatgames.aether.weaver.api.spi;

/**
 * The generation of this service provider interface, held as a compile-time constant.
 *
 * <p>Every plugin answers {@link WeaverPlugin#apiLevel()} with {@link #LEVEL}, and the engine
 * compares that answer against the {@link #LEVEL} it was itself built with. The comparison is the
 * whole compatibility gate: a plugin built against a newer generation of {@code api.spi} than the
 * engine provides is refused before it can run, rather than being allowed to fail later with a
 * {@link LinkageError} raised from inside class loading, where nothing can attribute it to the jar
 * that caused it.
 *
 * <h2>Why the constant works</h2>
 *
 * <p>{@link #LEVEL} is a {@code static final int} initialised by a literal, so it is a constant
 * variable in the sense of the Java Language Specification and {@code javac} folds its value into
 * every class file that reads it. A plugin compiled against generation 1 therefore carries the
 * number 1 in its own instruction stream and keeps reporting 1 when it is later placed beside an
 * engine of generation 2 — which is exactly what the gate needs, because the question being asked
 * is what the plugin was compiled against, not what is on the classpath now.
 *
 * <p>The consequence is that {@code return WeaverApi.LEVEL;} in a plugin is not a delegation. It is
 * a way of writing down the number the plugin was compiled against without having to remember what
 * it is. Writing a literal instead compiles and passes the gate today, and stops tracking the API
 * the moment the plugin is rebuilt against a newer one; the diagnostic the engine raises when a
 * plugin throws a {@link LinkageError} names this as the first thing to check.
 *
 * <h2>What the engine does with the number</h2>
 *
 * <p>The check runs once per plugin, before {@link WeaverPlugin#contribute(PluginContext)} is
 * called, so a plugin that fails it contributes nothing at all.
 *
 * <ul>
 *   <li>A level above the engine's own is reported as {@code AW3112}, naming both numbers. The
 *       remedy is to upgrade Aether Weaver or to use a build of the plugin made for this
 *       generation.
 *   <li>A level below the oldest generation the engine still supports is reported as
 *       {@code AW3113}. The oldest supported generation is a property of the engine rather than of
 *       this class; in this release it is {@code 1}, so no positive level is too old, and {@code 0}
 *       is the one non-negative level that is.
 *   <li>A level equal to or below the engine's own, and not below its minimum, loads.
 * </ul>
 *
 * <p>The built-in plugin is checked in exactly the same way, so an engine assembled from mismatched
 * jars is refused rather than half-loaded.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * public final class AcmePlugin implements WeaverPlugin {
 *
 *     @Override
 *     public PluginId id() {
 *         return new PluginId("acme", "Acme Weaves", "1.4.0");
 *     }
 *
 *     @Override
 *     public int apiLevel() {
 *         return WeaverApi.LEVEL;   // folded in at compile time; never write the number
 *     }
 *
 *     @Override
 *     public void contribute(PluginContext ctx) {
 *         ctx.points(new AcmePoints());
 *     }
 * }
 * }</pre>
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see WeaverPlugin#apiLevel()
 */
public final class WeaverApi {

    /**
     * The generation of {@code de.splatgames.aether.weaver.api.spi} this build declares.
     *
     * <p>A constant variable, and therefore folded into the class file of every plugin that reads
     * it. The number is raised only when the SPI gains a shape a plugin built against the previous
     * generation cannot be run against; a purely additive change leaves it alone.
     */
    public static final int LEVEL = 1;

    /**
     * Refuses instantiation of a constant holder.
     *
     * @throws AssertionError always
     */
    private WeaverApi() {
        throw new AssertionError("no instances");
    }
}
