package de.splatgames.aether.weaver.runtime;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Objects;

/**
 * Reports the flag that names an AOT cache, or the configuration used to train one, among the
 * arguments this JVM was started with.
 *
 * <p>What is read is the arguments the JVM was started with, not the state of any cache. A run
 * naming a cache file that turns out to be unusable is still reported, because those arguments are
 * what a reader can compare against their deployment. {@code -XX:AOTConfiguration=} is among the
 * flags reported although it names a training configuration rather than a cache file: the two are
 * distinct artefacts, but the caller that consults this class treats naming either the same way.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
final class AotCache {

    /** The flag that suppresses a cache named alongside it. */
    private static final String OFF = "-XX:AOTMode=off";

    /**
     * The flag prefixes that name an AOT file, each carrying its {@code =} so that a flag which
     * names nothing cannot match. Classic CDS, {@code -XX:SharedArchiveFile}, is not among them:
     * it is common enough in packaged runtimes that reporting it would teach operators to ignore
     * the report.
     */
    private static final List<String> CACHE_FLAGS =
            List.of("-XX:AOTCache=", "-XX:AOTCacheOutput=", "-XX:AOTConfiguration=");

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private AotCache() {
        throw new AssertionError("no instances");
    }

    /**
     * Reports the AOT flag this JVM was started with.
     *
     * <p>Asking is safe whatever the surrounding runtime offers: {@code java.management} may be
     * absent from a jlinked image, and its absence costs the report rather than the caller.
     *
     * @return the flag as written among the arguments the JVM was started with, or {@code null}
     *         when none was given, when {@code -XX:AOTMode=off} accompanies one, or when the
     *         arguments cannot be read
     */
    @Nullable
    static String active() {
        try {
            return detect(ManagementFactory.getRuntimeMXBean().getInputArguments());
        } catch (final LinkageError | RuntimeException unavailable) {
            // A jlinked runtime need not contain java.management, and a detector that took the
            // class loader down with it would be worse than one that misses. Missing here costs a
            // warning; throwing here costs the application.
            return null;
        }
    }

    /**
     * Reports the AOT flag among the given arguments.
     *
     * <p>{@code -XX:AOTMode=off} vetoes the others wherever it stands among them: measured on
     * Temurin 25.0.3, a run carrying it loads nothing from a cache named beside it, so reporting
     * that cache would name something the JVM has already been told to ignore.
     * {@code -XX:AOTMode} in any other form names no file, since the JVM refuses a value that is
     * not one of its modes, and is not treated as a cache.
     *
     * @param arguments the JVM arguments to search; must not be {@code null}
     * @return the first AOT flag found, in the order the arguments were given, or {@code null}
     *         when none is present
     * @throws NullPointerException if {@code arguments} is {@code null}
     */
    @Contract(pure = true)
    @Nullable
    static String detect(@NotNull final List<String> arguments) {
        Objects.requireNonNull(arguments, "arguments");

        if (arguments.contains(OFF)) {
            // Measured: `-XX:AOTMode=off -XX:AOTCache=app.aot` loads nothing from the cache.
            return null;
        }
        for (final String argument : arguments) {
            for (final String flag : CACHE_FLAGS) {
                if (argument.startsWith(flag)) {
                    return argument;
                }
            }
        }
        return null;
    }
}
