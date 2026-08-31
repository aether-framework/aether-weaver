package de.splatgames.aether.weaver.engine.stamp;

import de.splatgames.aether.weaver.engine.internal.transform.WeaveAttribute;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.lang.classfile.ClassBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Writes the {@code AetherWeave} attribute onto a class being built.
 *
 * <p>The attribute is the machine-readable half of the stamp and is written whatever detail level
 * the {@code @Woven} annotation is asked for. It carries the version, the fingerprint, the flag word
 * and every entry, and nothing else the record holds: the plugin coordinates, the plugin metadata
 * and the weave names have no place in it. The weave names can be recovered from the entries, as
 * {@link Provenance#recordOf(byte[])} does. The other two are carried only by the annotation, and
 * under {@code Woven.Detail.NONE} no annotation is written at all, so at that level nothing on the
 * class carries them.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class WeaveAttributeWriter {

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private WeaveAttributeWriter() {
        throw new AssertionError("no instances");
    }

    /**
     * Adds the attribute to the class being built.
     *
     * @param builder the class under construction; must not be {@code null}
     * @param record  what was done to the class; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public static void stamp(@NotNull final ClassBuilder builder,
                             @NotNull final WeaveRecord record) {
        Objects.requireNonNull(builder, "builder").with(attribute(record));
    }

    /**
     * Builds the attribute from the record.
     *
     * <p>Every entry is copied across, and the flag word is taken with truncation {@code false},
     * because the attribute has no cap to exceed.
     *
     * @param record what was done to the class; must not be {@code null}
     * @return the attribute to write
     * @throws NullPointerException if {@code record} is {@code null}
     */
    @Contract(pure = true)
    @NotNull
    static WeaveAttribute attribute(@NotNull final WeaveRecord record) {
        Objects.requireNonNull(record, "record");

        final List<WeaveAttribute.Entry> entries = new ArrayList<>(record.entries().size());
        for (final WeaveRecord.Entry entry : record.entries()) {
            entries.add(new WeaveAttribute.Entry(entry.weave(), entry.kind(),
                    entry.handler(), entry.target()));
        }
        return new WeaveAttribute(record.weaverVersion(), record.fingerprint(),
                record.flags(false), entries);
    }
}
