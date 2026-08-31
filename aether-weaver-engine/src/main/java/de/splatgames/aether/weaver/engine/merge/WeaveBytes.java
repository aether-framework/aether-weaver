package de.splatgames.aether.weaver.engine.merge;

import de.splatgames.aether.weaver.api.spi.ClassSource;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.constant.ClassDesc;
import java.util.Objects;

/**
 * Supplies the class file of a weave class to the one stage that needs it.
 *
 * <p>A parsed weave carries declarations and no bodies, so dissolving an instance weave into its
 * target means reading the weave class a second time. That stage is the only reader, so a run
 * that merely injects never has to supply anything.
 *
 * <p>The key is a {@link ClassDesc} rather than an internal name, which is the form a parsed weave
 * names itself in; {@link #from(ClassSource)} adapts a source keyed the other way.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
@FunctionalInterface
public interface WeaveBytes {

    /**
     * Supplies no bytes at all.
     *
     * <p>The default of
     * {@link de.splatgames.aether.weaver.engine.WeaverBuilder#weaveBytes(WeaveBytes)}. A weave that
     * merely injects is unaffected by it; one that must be dissolved and needs a body reaches
     * {@code AW1096} instead.
     */
    WeaveBytes NONE = weaveType -> null;

    /**
     * Returns the class file of the given weave class.
     *
     * <p>Asked once per dissolving weave each time one of its targets is woven, and the answer is
     * parsed by the caller rather than kept, so an implementation that reads a file or a jar entry
     * is the one place worth caching.
     *
     * @param weaveType the weave class's own type; must not be {@code null}
     * @return the class file, or {@code null} when this supplier does not have it
     */
    byte @Nullable [] bytesOf(@NotNull ClassDesc weaveType);

    /**
     * Adapts a {@link ClassSource}, which is keyed by internal name.
     *
     * <p>An absent class becomes {@code null}, which is the answer the merge stage expects. An
     * {@link java.io.UncheckedIOException} raised by the source passes through untouched: a
     * classpath entry that cannot be read is a different answer from a class the source lacks.
     *
     * @param source the source to read from; must not be {@code null}
     * @return a supplier reading from that source
     * @throws NullPointerException if {@code source} is {@code null}
     */
    @Contract(value = "_ -> new", pure = true)
    @NotNull
    static WeaveBytes from(@NotNull final ClassSource source) {
        Objects.requireNonNull(source, "source");
        return weaveType -> {
            // packageName() is empty for the default package, and prefixing an empty package with a
            // separator would ask for "/Session" — a resource nothing has.
            final String packageName = weaveType.packageName();
            final String internalName = packageName.isEmpty()
                    ? weaveType.displayName()
                    : packageName.replace('.', '/') + '/' + weaveType.displayName();
            return source.find(internalName).orElse(null);
        };
    }
}
