package de.splatgames.aether.weaver.api.spi;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Everything a {@link WeaveSource} is given when it is asked which weave classes exist.
 *
 * <p>Two things, because a source needs exactly two: somewhere to look, and somewhere to complain.
 * The context is created by the driver, handed to
 * {@link WeaveSource#candidates(DiscoveryContext)}, and not retained afterwards.
 *
 * <h2>The loader</h2>
 *
 * <p>{@link #loader()} is what a source searches — with
 * {@link ClassLoader#getResources(String)} for a resource that may appear in several artefacts at
 * once, which is how the built-in manifest source finds one manifest per artefact on the
 * classpath. It is not there to load classes with. Loading a weave class in order to inspect it
 * would run its static initialiser and define the type before the weaver had a chance to read it,
 * so a source reads bytes through a {@link ClassSource} instead and leaves the loader for
 * resources.
 *
 * <p>Which loader arrives is the driver's decision, and it is not necessarily the loader the woven
 * program will run in. The load-time driver hands over a loader opened solely for discovery,
 * covering the same roots as the weaving loader but defining nothing, precisely so that reading a
 * manifest cannot define a class before the weaver exists.
 *
 * <h2>The reporter</h2>
 *
 * <p>{@link #reporter()} is where a source says what went wrong while it looked. Discovery is
 * expected to survive a broken artefact rather than abort: the built-in manifest source reports
 * {@code AW2302} when no manifest is on the classpath at all, {@code AW2300} for a manifest that
 * cannot be read or parsed, and {@code AW2303} when two artefacts declare the same weave class. A
 * manifest it cannot use costs that artefact its weaves and nothing more: one stale library must
 * not be able to switch off every weave in the application. A source that throws instead takes the
 * whole run with it: the built-in discovery path does not contain an exception from a source, so it
 * propagates out of the driver's discovery call, before any class has been woven.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * public final class ResourceWeaveSource implements WeaveSource {
 *
 *     @Override
 *     public String name() {
 *         return "acme resource list";
 *     }
 *
 *     @Override
 *     public Stream<WeaveCandidate> candidates(DiscoveryContext context) {
 *         URL listing = context.loader().getResource("META-INF/acme-weaves.txt");
 *         if (listing == null) {
 *             context.reporter().report(DiagnosticCode.MANIFEST_NOT_FOUND,
 *                     "no acme weave listing on the classpath");
 *             return Stream.of();
 *         }
 *         return read(listing, ClassSource.ofClassLoader(context.loader()));
 *     }
 * }
 * }</pre>
 *
 * @param loader   the loader whose resources a source searches
 * @param reporter where a source reports what it could not read
 * @author Erik Pförtner
 * @since 0.1.0
 * @see WeaveSource
 */
public record DiscoveryContext(@NotNull ClassLoader loader, @NotNull Reporter reporter) {

    /**
     * Checks that both components are present.
     *
     * @throws NullPointerException if either component is {@code null}
     */
    public DiscoveryContext {
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(reporter, "reporter");
    }

    /**
     * Returns a context over the given loader that discards what a source reports.
     *
     * <p>{@link Reporter#NOOP} stands in for the reporter, so a source's account of what it could
     * not read is lost. Suited to a caller that only wants the candidates — a tool listing what is
     * on a classpath — and not to a build, where the artefact that was skipped is the useful part.
     *
     * @param loader the loader to search; must not be {@code null}
     * @return a new context reporting nowhere
     * @throws NullPointerException if {@code loader} is {@code null}
     */
    @Contract(value = "_ -> new", pure = true)
    @NotNull
    public static DiscoveryContext of(@NotNull final ClassLoader loader) {
        return new DiscoveryContext(loader, Reporter.NOOP);
    }
}
