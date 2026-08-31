package de.splatgames.aether.weaver.engine.internal.transform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeaveAttributeTest {

    private static final List<WeaveAttribute.Entry> ENTRIES = List.of(
            new WeaveAttribute.Entry("com.acme.audit.PaymentAudit", "INJECT",
                    "onCharge(Ljava/math/BigDecimal;)V", "charge(Ljava/math/BigDecimal;)V"),
            new WeaveAttribute.Entry("com.acme.trace.SessionTracing", "REDIRECT",
                    "wrapSend()V", "run()V"));

    private static byte[] stamp(final WeaveAttribute attribute) {
        final ClassFile classFile = WeaveAttribute.classFileWithMapper();
        final byte[] original = readClass(Sample.class);
        return classFile.transformClass(classFile.parse(original),
                ClassTransform.endHandler(cb -> cb.with(attribute)));
    }

    @Test
    @DisplayName("the attribute round-trips through a write and a read")
    void roundTrips() {
        final byte[] stamped = stamp(new WeaveAttribute("0.1.0", "a3f9c2e1d4b60718", 0, ENTRIES));

        final WeaveAttribute read = WeaveAttribute.readFrom(stamped).orElseThrow();
        assertThat(read.weaverVersion()).isEqualTo("0.1.0");
        assertThat(read.fingerprint()).isEqualTo("a3f9c2e1d4b60718");
        assertThat(read.flags()).isZero();
        assertThat(read.usedPolicyOverride()).isFalse();
        assertThat(read.entries()).isEqualTo(ENTRIES);
    }

    @Test
    @DisplayName("a stamped class still verifies and still loads")
    void stampedClassRemainsUsable() throws Exception {
        final byte[] stamped = stamp(new WeaveAttribute("0.1.0", "abc", 0, ENTRIES));

        assertThat(ClassFile.of().verify(stamped)).isEmpty();

        final ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> findClass(final String name) {
                return defineClass(name, stamped, 0, stamped.length);
            }
        };
        assertThat(loader.loadClass(Sample.class.getName()).getDeclaredConstructor().newInstance())
                .isNotNull();
    }

    @Test
    @DisplayName("the attribute survives transformation by a tool that does not know it")
    void survivesForeignTransformation() {
        // This is what makes the attribute trustworthy as provenance: an unrelated bytecode tool
        // in the pipeline must not silently drop it.
        final byte[] stamped = stamp(new WeaveAttribute("0.1.0", "survives", 0, ENTRIES));

        final ClassFile unaware = ClassFile.of();
        final byte[] retransformed =
                unaware.transformClass(unaware.parse(stamped), ClassTransform.ACCEPT_ALL);

        final WeaveAttribute read = WeaveAttribute.readFrom(retransformed).orElseThrow();
        assertThat(read.fingerprint()).isEqualTo("survives");
        assertThat(read.entries()).isEqualTo(ENTRIES);
    }

    @Test
    @DisplayName("the policy-override flag is recorded")
    void policyOverrideFlag() {
        final byte[] stamped = stamp(new WeaveAttribute(
                "0.1.0", "x", WeaveAttribute.FLAG_POLICY_OVERRIDE, List.of()));
        assertThat(WeaveAttribute.readFrom(stamped).orElseThrow().usedPolicyOverride()).isTrue();
    }

    @Test
    @DisplayName("an unwoven class reports no attribute")
    void unwovenClassHasNoAttribute() {
        assertThat(WeaveAttribute.readFrom(readClass(Sample.class))).isEmpty();
    }

    @Test
    @DisplayName("an empty entry list round-trips")
    void emptyEntries() {
        final byte[] stamped = stamp(new WeaveAttribute("0.1.0", "empty", 0, List.of()));
        assertThat(WeaveAttribute.readFrom(stamped).orElseThrow().entries()).isEmpty();
    }

    @Test
    @DisplayName("a future schema version is rejected rather than misread")
    void futureSchemaIsRejected() {
        // Reading a newer payload layout with this version's field offsets would produce
        // plausible nonsense, so the version is checked before anything else is decoded.
        // The bad attribute is written by a mapper that emits a higher version, which is exactly
        // what a future weaver would do.
        final byte[] stamped = stampWithSchemaVersion(WeaveAttribute.SCHEMA_VERSION + 1);

        assertThatThrownBy(() -> WeaveAttribute.readFrom(stamped))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema version");
    }

    private static byte[] stampWithSchemaVersion(final int schemaVersion) {
        final java.lang.classfile.AttributeMapper<FutureAttribute> mapper =
                new java.lang.classfile.AttributeMapper<>() {
                    @Override
                    public String name() {
                        return WeaveAttribute.NAME;
                    }

                    @Override
                    public FutureAttribute readAttribute(
                            final java.lang.classfile.AttributedElement enclosing,
                            final java.lang.classfile.ClassReader reader, final int pos) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public void writeAttribute(final java.lang.classfile.BufWriter writer,
                                               final FutureAttribute attribute) {
                        writer.writeIndex(writer.constantPool().utf8Entry(WeaveAttribute.NAME));
                        final int lengthPosition = writer.size();
                        writer.writeInt(0);
                        final int payloadStart = writer.size();
                        writer.writeU2(schemaVersion);
                        writer.writeU2(0);
                        writer.patchInt(lengthPosition, 4, writer.size() - payloadStart);
                    }

                    @Override
                    public AttributeStability stability() {
                        return AttributeStability.STATELESS;
                    }
                };

        final ClassFile classFile = ClassFile.of();
        final byte[] original = readClass(Sample.class);
        return classFile.transformClass(classFile.parse(original),
                ClassTransform.endHandler(cb -> cb.with(new FutureAttribute(mapper))));
    }

    private static final class FutureAttribute
            extends java.lang.classfile.CustomAttribute<FutureAttribute> {
        private FutureAttribute(final java.lang.classfile.AttributeMapper<FutureAttribute> mapper) {
            super(mapper);
        }
    }

    @Test
    @DisplayName("the attribute is invisible to reflection")
    void invisibleToReflection() throws Exception {
        // An annotation would change the class's observable annotation set and break frameworks
        // that enumerate annotations. An attribute does not.
        final byte[] stamped = stamp(new WeaveAttribute("0.1.0", "x", 0, ENTRIES));
        final ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> findClass(final String name) {
                return defineClass(name, stamped, 0, stamped.length);
            }
        };
        final Class<?> loaded = loader.loadClass(Sample.class.getName());
        assertThat(loaded.getAnnotations()).isEmpty();
        assertThat(loaded.getDeclaredFields()).hasSize(1);
    }

    private static byte[] readClass(final Class<?> type) {
        final String resource = type.getName().replace('.', '/') + ".class";
        try (var in = type.getClassLoader().getResourceAsStream(resource)) {
            return Objects.requireNonNull(in, resource).readAllBytes();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static class Sample {
        int value;
    }
}
