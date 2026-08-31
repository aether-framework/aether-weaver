package de.splatgames.aether.weaver.engine.internal.transform;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.constant.ClassDesc;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds a {@link ClassFile} context that generates stack maps without loading a class to do it.
 *
 * <p>Generating stack maps rather than copying them makes the class-file API ask for the hierarchy
 * of types it merges, and how that question is answered is the whole content of this class.
 *
 * <p>Resolution goes through class <em>resources</em> first and falls back to
 * {@link ClassHierarchyResolver#defaultResolver()} only for what no resource yields. The default
 * resolver reflects over the system class loader and, as its own specification states, loads system
 * classes that are not yet loaded, which makes it unsuitable for instrumentation.
 * {@code FrameSupportTest} pins the ordering with a loader that records every call: it sees
 * resource reads and no {@code loadClass} for a woven type at all.
 *
 * <h2>Thread safety</h2>
 *
 * <p>One returned {@link ClassFile} is meant to be shared, so the resolver's cache is a
 * {@link ConcurrentHashMap}. {@link ClassHierarchyResolver#cached()} without an argument is
 * documented as not thread-safe, and the eight-thread case in {@code FrameSupportTest} carries a
 * timeout because the failure it guards against shows up as a hang rather than an exception.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class FrameSupport {

    /** Prevents instantiation. */
    private FrameSupport() {
    }

    /**
     * Returns the context an agent transforming classes as they load must use.
     *
     * <p>A {@code null} loader is what an agent is handed for a class defined by the bootstrap
     * loader, and there is no object there to ask for a resource, so the platform loader stands in.
     *
     * @param loader the loader whose resources describe the hierarchy, or {@code null} for the
     *               bootstrap loader
     * @return a class-file context that generates stack maps and resolves without loading classes
     */
    @NotNull
    public static ClassFile forLoadTime(final @Nullable ClassLoader loader) {
        final ClassLoader effective = loader != null ? loader : ClassLoader.getPlatformClassLoader();
        return ClassFile.of(
                ClassFile.StackMapsOption.GENERATE_STACK_MAPS,
                ClassFile.ClassHierarchyResolverOption.of(
                        ClassHierarchyResolver.ofResourceParsing(effective)
                                .orElse(ClassHierarchyResolver.defaultResolver())
                                .cached(ConcurrentHashMap::new)));
    }

    /**
     * Returns the context a build-time weave uses.
     *
     * <p>Identical to {@link #forLoadTime(ClassLoader)}, and identical on purpose: a class woven
     * during the build and the same class woven as it loads must not end up with different frames
     * because their hierarchies were resolved by different strategies. {@code FrameSupportTest}
     * compares the two outputs byte for byte.
     *
     * @param loader the loader whose resources describe the hierarchy, or {@code null} for the
     *               bootstrap loader
     * @return the same configuration {@link #forLoadTime(ClassLoader)} returns
     */
    @NotNull
    public static ClassFile forBuildTime(final @Nullable ClassLoader loader) {
        return forLoadTime(loader);
    }

    /**
     * Returns a context that resolves the hierarchy through a caller-supplied lookup.
     *
     * <p>The same configuration as {@link #forLoadTime(ClassLoader)} with the loader's resource
     * lookup replaced by an arbitrary one, for a set of classes that no loader can reach.
     *
     * @param resources the lookup from a type to a stream over its class file; must not be
     *                  {@code null}, and may itself return {@code null} for a type it does not
     *                  have, which sends that query on to the default resolver
     * @return a class-file context that generates stack maps and resolves through {@code resources}
     * @throws NullPointerException if {@code resources} is {@code null}
     */
    @NotNull
    public static ClassFile forResources(
            @NotNull final java.util.function.Function<ClassDesc, java.io.InputStream> resources) {
        Objects.requireNonNull(resources, "resources");
        return ClassFile.of(
                ClassFile.StackMapsOption.GENERATE_STACK_MAPS,
                ClassFile.ClassHierarchyResolverOption.of(
                        ClassHierarchyResolver.ofResourceParsing(resources)
                                .orElse(ClassHierarchyResolver.defaultResolver())
                                .cached(ConcurrentHashMap::new)));
    }
}
