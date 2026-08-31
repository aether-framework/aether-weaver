package de.splatgames.aether.weaver.engine.internal.transform;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import java.lang.classfile.AttributeMapper;
import java.lang.classfile.AttributedElement;
import java.lang.classfile.BufWriter;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassReader;
import java.lang.classfile.CustomAttribute;
import java.lang.classfile.constantpool.Utf8Entry;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The class-file attribute a woven class carries, recording which weaver produced it and what was
 * applied.
 *
 * <p>An attribute rather than an annotation. An annotation would join the class's observable
 * annotation set and change what every framework that enumerates annotations sees; an attribute is
 * invisible to reflection, which {@code WeaveAttributeTest} checks by loading a stamped class and
 * asserting it declares none.
 *
 * <h2>Surviving other tools</h2>
 *
 * <p>The payload does carry constant-pool indices — the layout below writes two in the header and
 * four per entry — so it is not free of references to its surroundings. What lets a class-file API
 * instance that has never heard of this attribute copy it through a transform unchanged is not the
 * mapper's declared {@link java.lang.classfile.AttributeMapper.AttributeStability#STATELESS
 * stability}, which such a tool never consults: without this class's mapper installed, the attribute
 * parses as an unknown one, and the class-file API's default
 * {@link java.lang.classfile.ClassFile.AttributesProcessingOption#PASS_ALL_ATTRIBUTES} is what carries
 * it across a transform unread. {@code WeaveAttributeTest} retransforms a stamped class with a plain
 * {@code ClassFile.of()} and reads the attribute back; measured under
 * {@code ClassFile.of(AttributesProcessingOption.DROP_UNKNOWN_ATTRIBUTES)}, the same retransform
 * drops it instead.
 *
 * <p>That default alone is not enough: passthrough carries the payload's bytes, indices included,
 * so it only survives while those indices still point into the pool they were written against.
 * {@link java.lang.classfile.ClassFile.ConstantPoolSharingOption#NEW_POOL} rebuilds the pool and
 * still copies an unknown attribute through unread, so the surviving bytes end up pointing into a
 * pool that no longer has the same entries at those slots — measured as a
 * {@link java.lang.classfile.constantpool.ConstantPoolException} on the next read. {@link
 * ClassRemapper}, which transforms under {@code NEW_POOL} and reports that requirement through
 * {@link ClassRemapper#requiredPoolOption()}, cannot rely on this passthrough and instead decodes
 * and rewrites the payload itself.
 *
 * <h2>Layout</h2>
 *
 * <p>After the standard name index and {@code u4} length, the payload is
 *
 * <pre>
 * u2 schema_version
 * u2 weaver_version_index    -&gt; CONSTANT_Utf8
 * u2 fingerprint_index       -&gt; CONSTANT_Utf8
 * u2 flags
 * u2 entry_count
 * { u2 weave_class_index     -&gt; CONSTANT_Utf8
 *   u2 kind_index            -&gt; CONSTANT_Utf8
 *   u2 handler_index         -&gt; CONSTANT_Utf8
 *   u2 target_index          -&gt; CONSTANT_Utf8 } * entry_count
 * </pre>
 *
 * <p>{@link #SCHEMA_VERSION} is read first, and a payload whose schema does not match this class's
 * own is refused with an {@link IllegalArgumentException} rather than decoded, whichever direction
 * the mismatch runs; see {@link Mapper#readAttribute} for why.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class WeaveAttribute extends CustomAttribute<WeaveAttribute> {

    /** The attribute name as it appears in the constant pool. */
    public static final String NAME = "AetherWeave";

    /** The payload layout this class writes, and the only one it reads. */
    public static final int SCHEMA_VERSION = 1;

    /** Set in {@link #flags()} when the run overrode the weaving policy. */
    public static final int FLAG_POLICY_OVERRIDE = 0x0001;

    /** The reader and writer for {@link #NAME}; needed by anything that wants to see it. */
    public static final AttributeMapper<WeaveAttribute> MAPPER = new Mapper();

    /** The version of the weaver that produced the class. */
    private final String weaverVersion;

    /** The fingerprint of the plan the class was woven from. */
    private final String fingerprint;

    /** The flag bits, of which {@link #FLAG_POLICY_OVERRIDE} is the only one defined. */
    private final int flags;

    /** What was woven, one entry per applied declaration. */
    private final List<Entry> entries;

    /**
     * Builds the attribute to be written into a class.
     *
     * @param weaverVersion the version of the weaver producing the class; must not be {@code null}
     * @param fingerprint   the fingerprint of the plan; must not be {@code null}
     * @param flags         the flag bits, {@code 0} for none
     * @param entries       what was woven; must not be {@code null}, and is copied
     * @throws NullPointerException if any reference argument is {@code null}
     */
    public WeaveAttribute(@NotNull final String weaverVersion, @NotNull final String fingerprint,
                          final int flags, @NotNull final List<Entry> entries) {
        super(MAPPER);
        this.weaverVersion = Objects.requireNonNull(weaverVersion, "weaverVersion");
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        this.flags = flags;
        this.entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }

    /**
     * Returns a class-file context that understands this attribute.
     *
     * <p>Without the mapper the attribute still parses, as an unknown attribute, and
     * {@code findAttribute(MAPPER)} then answers empty — so anything meaning to read or write it
     * must go through a context built here. The given options are kept and the mapper option is
     * appended; the last option of a kind is the one that takes effect, so a caller's own
     * {@code AttributeMapperOption} is overridden rather than merged.
     *
     * @param options the options to combine with the mapper option
     * @return a class-file context with {@link #MAPPER} installed for {@link #NAME}
     */
    @NotNull
    public static ClassFile classFileWithMapper(@NotNull final ClassFile.Option... options) {
        final ClassFile.Option[] combined = new ClassFile.Option[options.length + 1];
        System.arraycopy(options, 0, combined, 0, options.length);
        combined[options.length] = ClassFile.AttributeMapperOption.of(
                name -> NAME.equals(name.stringValue()) ? MAPPER : null);
        return ClassFile.of(combined);
    }

    /**
     * Reads the attribute out of class-file bytes.
     *
     * <p>Parses with the mapper installed, which is what distinguishes this from
     * {@link #readFrom(ClassModel)}: a model the caller parsed for itself only yields the attribute
     * if that parse had the mapper.
     *
     * @param bytes the class file; must not be {@code null}
     * @return the attribute, or empty when the class carries none
     * @throws NullPointerException     if {@code bytes} is {@code null}
     * @throws IllegalArgumentException if the attribute is present but its schema does not match
     *                                  {@link #SCHEMA_VERSION}
     */
    @NotNull
    public static Optional<WeaveAttribute> readFrom(@NotNull final byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        return readFrom(classFileWithMapper().parse(bytes));
    }

    /**
     * Reads the attribute out of an already parsed class.
     *
     * <p>Answers empty for a model parsed without {@link #MAPPER}, whatever the class actually
     * carries, because the attribute is then held as an unknown one.
     *
     * @param model the parsed class; must not be {@code null}
     * @return the attribute, or empty when the class carries none or the model cannot see it
     * @throws NullPointerException if {@code model} is {@code null}
     */
    @Contract(pure = true)
    @NotNull
    public static Optional<WeaveAttribute> readFrom(@NotNull final ClassModel model) {
        Objects.requireNonNull(model, "model");
        return model.findAttribute(MAPPER);
    }

    /**
     * Returns the version of the weaver that wrote the attribute.
     *
     * @return the weaver version as it was recorded
     */
    @Contract(pure = true)
    @NotNull
    public String weaverVersion() {
        return this.weaverVersion;
    }

    /**
     * Returns the fingerprint of the plan the class was woven from.
     *
     * @return the fingerprint as it was recorded
     */
    @Contract(pure = true)
    @NotNull
    public String fingerprint() {
        return this.fingerprint;
    }

    /**
     * Returns the flag word.
     *
     * <p>The whole word, including bits this version gives no meaning to; the reader and the writer
     * both treat it as opaque, so unknown bits survive a read followed by a write.
     *
     * @return the flag bits
     */
    @Contract(pure = true)
    public int flags() {
        return this.flags;
    }

    /**
     * Reports whether the run that wrote this attribute overrode the weaving policy.
     *
     * @return {@code true} when {@link #FLAG_POLICY_OVERRIDE} is set in {@link #flags()}
     */
    @Contract(pure = true)
    public boolean usedPolicyOverride() {
        return (this.flags & FLAG_POLICY_OVERRIDE) != 0;
    }

    /**
     * Returns what was woven into the class.
     *
     * @return the entries, in the order they were written
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    public List<Entry> entries() {
        return this.entries;
    }

    /**
     * Returns the header fields and the number of entries.
     *
     * @return a description naming the version, fingerprint, flags and entry count, without
     *         listing the entries themselves
     */
    @Override
    public String toString() {
        return NAME + "[version=" + this.weaverVersion + ", fingerprint=" + this.fingerprint
                + ", flags=" + this.flags + ", entries=" + this.entries.size() + ']';
    }

    /**
     * One applied declaration, as four strings.
     *
     * <p>Four constant-pool strings and nothing resolved: an entry is written to be read back and
     * printed, so none of the four is parsed on the way in or on the way out.
     *
     * @param weaveClass the binary name of the weave class that contributed the declaration
     * @param kind       the injector kind's identifier
     * @param handler    the handler as name and descriptor
     * @param target     the target selector as the author wrote it
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public record Entry(String weaveClass, String kind, String handler, String target) {

        /**
         * Rejects a null component; nothing else about the strings is checked.
         *
         * @throws NullPointerException if any component is {@code null}
         */
        public Entry {
            Objects.requireNonNull(weaveClass, "weaveClass");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(handler, "handler");
            Objects.requireNonNull(target, "target");
        }

        /**
         * Returns the entry as one line.
         *
         * @return the weave class, kind and handler, then an arrow and the target
         */
        @Override
        public String toString() {
            return this.weaveClass + ' ' + this.kind + ' ' + this.handler + " -> " + this.target;
        }
    }

    /**
     * Reads and writes the payload described by {@link WeaveAttribute}.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private static final class Mapper implements AttributeMapper<WeaveAttribute> {

        /**
         * Returns the attribute name this mapper claims.
         *
         * @return {@link WeaveAttribute#NAME}
         */
        @Override
        public String name() {
            return NAME;
        }

        /**
         * Decodes one payload.
         *
         * <p>{@code pos} is the start of the payload, past the name index and the length, so the
         * cursor begins at the schema version rather than six bytes before it.
         *
         * <p>The schema version is checked before any other field is touched: a later layout
         * decoded with these offsets would yield entries built from whatever the indices happened
         * to hit.
         *
         * @param enclosing the element carrying the attribute
         * @param reader    the reader over the class file
         * @param pos       the offset of the first payload byte
         * @return the decoded attribute
         * @throws IllegalArgumentException if the payload's schema version is not
         *                                  {@link WeaveAttribute#SCHEMA_VERSION}
         */
        @Override
        public WeaveAttribute readAttribute(@NotNull final AttributedElement enclosing,
                                            @NotNull final ClassReader reader,
                                            final int pos) {
            // `pos` is the payload start, not the attribute header. Assuming otherwise reads
            // the length as a constant pool index and fails with "Bad CP index: 0".
            int cursor = pos;
            final int schema = reader.readU2(cursor);
            cursor += 2;
            if (schema != SCHEMA_VERSION) {
                throw new IllegalArgumentException(
                        NAME + " schema version " + schema + " is not supported by this weaver "
                                + "(expected " + SCHEMA_VERSION + ')');
            }
            final String version = reader.readEntry(cursor, Utf8Entry.class).stringValue();
            cursor += 2;
            final String fingerprint = reader.readEntry(cursor, Utf8Entry.class).stringValue();
            cursor += 2;
            final int flags = reader.readU2(cursor);
            cursor += 2;
            final int count = reader.readU2(cursor);
            cursor += 2;

            final List<Entry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                final String weaveClass = reader.readEntry(cursor, Utf8Entry.class).stringValue();
                cursor += 2;
                final String kind = reader.readEntry(cursor, Utf8Entry.class).stringValue();
                cursor += 2;
                final String handler = reader.readEntry(cursor, Utf8Entry.class).stringValue();
                cursor += 2;
                final String target = reader.readEntry(cursor, Utf8Entry.class).stringValue();
                cursor += 2;
                entries.add(new Entry(weaveClass, kind, handler, target));
            }
            return new WeaveAttribute(version, fingerprint, flags, entries);
        }

        /**
         * Encodes one payload, including the attribute header.
         *
         * <p>The length is not known until the payload has been written, so a zero is written in
         * its place, the payload follows, and the four bytes are patched with the distance from the
         * end of the placeholder to the current position.
         *
         * <p>The schema version written is the constant, not a value carried on the attribute:
         * there is one layout this class writes, and it is the one its reader accepts.
         *
         * @param writer    the writer, positioned where the attribute begins
         * @param attribute the attribute to encode
         */
        @Override
        public void writeAttribute(@NotNull final BufWriter writer, @NotNull final WeaveAttribute attribute) {
            writer.writeIndex(writer.constantPool().utf8Entry(NAME));
            final int lengthPosition = writer.size();
            writer.writeInt(0);
            final int payloadStart = writer.size();

            writer.writeU2(SCHEMA_VERSION);
            writer.writeIndex(writer.constantPool().utf8Entry(attribute.weaverVersion));
            writer.writeIndex(writer.constantPool().utf8Entry(attribute.fingerprint));
            writer.writeU2(attribute.flags);
            writer.writeU2(attribute.entries.size());
            for (final Entry entry : attribute.entries) {
                writer.writeIndex(writer.constantPool().utf8Entry(entry.weaveClass()));
                writer.writeIndex(writer.constantPool().utf8Entry(entry.kind()));
                writer.writeIndex(writer.constantPool().utf8Entry(entry.handler()));
                writer.writeIndex(writer.constantPool().utf8Entry(entry.target()));
            }
            writer.patchInt(lengthPosition, 4, writer.size() - payloadStart);
        }

        /**
         * Returns how much of the payload survives a transform of the class around it.
         *
         * <p>See {@link WeaveAttribute the class documentation} for what actually decides whether an
         * unrelated tool copies the attribute through or drops it — this value is not it.
         *
         * @return {@code STATELESS}
         */
        @Override
        public AttributeStability stability() {
            // No bytecode offsets and no pool indices in the payload, so an unrelated transform
            // cannot invalidate it — which is what lets the attribute survive other tools.
            return AttributeStability.STATELESS;
        }

        /**
         * Reports whether a class may carry more than one of these.
         *
         * @return {@code false}; a class has one provenance, and two attributes would leave a
         *         reader to choose between them
         */
        @Override
        public boolean allowMultiple() {
            return false;
        }
    }
}
