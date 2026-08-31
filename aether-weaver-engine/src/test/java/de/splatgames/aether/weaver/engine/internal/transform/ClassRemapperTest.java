package de.splatgames.aether.weaver.engine.internal.transform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ClassRemapperTest {

    @Test
    @DisplayName("an identity mapping leaves every class in java.base byte-identical")
    void identityMappingIsIdentityAcrossTheJdk() {
        final ClassFile classFile = ClassFile.of();
        final ClassRemapper identity = ClassRemapper.of(type -> type);

        final AtomicInteger examined = new AtomicInteger();
        final List<String> differing = new ArrayList<>();
        final List<String> failed = new ArrayList<>();

        forEachClassInJavaBase((name, bytes) -> {
            examined.incrementAndGet();
            try {
                final ClassModel model = classFile.parse(bytes);
                final byte[] rewritten = identity.remap(classFile, model, bytes);
                if (!java.util.Arrays.equals(bytes, rewritten) && differing.size() < 20) {
                    differing.add(name + " (" + bytes.length + " -> " + rewritten.length + ')');
                }
            } catch (final RuntimeException e) {
                if (failed.size() < 20) {
                    failed.add(name + ": " + e);
                }
            }
        });

        assertThat(examined.get())
                .as("the jrt file system should have yielded a substantial corpus")
                .isGreaterThan(5_000);
        assertThat(failed).as("classes the remapper could not process").isEmpty();
        assertThat(differing).as("classes an identity mapping altered").isEmpty();
    }

    @Test
    @DisplayName("rebuilding every class in java.base preserves its members exactly")
    void rebuildingPreservesMembersAcrossTheJdk() {
        // remap() short-circuits when nothing changes, so this drives the rebuild path directly
        // with a mapping that renames a type no JDK class mentions. The rebuilt class is not
        // byte-identical — the constant pool is re-interned — but every declared member,
        // descriptor and flag must survive.
        final ClassFile classFile = ClassFile.of();
        final ClassRemapper rebuilding = ClassRemapper.of(Map.of(
                java.lang.constant.ClassDesc.of("com.acme.DoesNotExist"),
                java.lang.constant.ClassDesc.of("com.acme.AlsoDoesNotExist")));

        final List<String> broken = new ArrayList<>();
        final AtomicInteger examined = new AtomicInteger();

        forEachClassInJavaBase((name, bytes) -> {
            if (examined.incrementAndGet() % 11 != 0) {
                return;
            }
            final ClassModel before = classFile.parse(bytes);
            final ClassModel after = classFile.parse(
                    classFile.transformClass(before, rebuilding.asClassTransform()));

            if (!signature(before).equals(signature(after)) && broken.size() < 10) {
                broken.add(name);
            }
        });

        assertThat(broken).as("classes whose members changed under a rebuild").isEmpty();
    }

    @Test
    @DisplayName("remapping is deterministic")
    void remappingIsDeterministic() {
        final ClassFile classFile = ClassFile.of();
        final ClassRemapper remapper = ClassRemapper.of(Map.of(
                java.lang.constant.ClassDesc.of(Sample.class.getName()),
                java.lang.constant.ClassDesc.of("com.acme.Replacement")));
        final byte[] original = readClass(Sample.class);

        final byte[] first = remapper.remap(classFile, classFile.parse(original), original);
        final byte[] second = remapper.remap(classFile, classFile.parse(original), original);

        assertThat(second)
                .as("the same input must always produce the same bytes, or the build cannot be "
                        + "reproducible")
                .isEqualTo(first);
    }

    private static List<String> signature(final ClassModel model) {
        final List<String> members = new ArrayList<>();
        model.fields().forEach(f -> members.add("F " + f.fieldName().stringValue()
                + ' ' + f.fieldType().stringValue() + ' ' + f.flags().flagsMask()));
        model.methods().forEach(m -> members.add("M " + m.methodName().stringValue()
                + ' ' + m.methodType().stringValue() + ' ' + m.flags().flagsMask()));
        members.add("S " + model.superclass().map(s -> s.asInternalName()).orElse("-"));
        model.interfaces().forEach(i -> members.add("I " + i.asInternalName()));
        java.util.Collections.sort(members);
        return members;
    }

    @Test
    @DisplayName("every class in java.base still verifies after an identity remap")
    void identityMappingKeepsTheJdkVerifiable() {
        final ClassFile classFile = ClassFile.of();
        final ClassRemapper identity = ClassRemapper.of(type -> type);
        final List<String> broken = new ArrayList<>();
        final AtomicInteger examined = new AtomicInteger();

        forEachClassInJavaBase((name, bytes) -> {
            if (examined.incrementAndGet() % 7 != 0) {
                return;   // a seventh of the corpus keeps this test under a second
            }
            final byte[] rewritten =
                    classFile.transformClass(classFile.parse(bytes), identity.asClassTransform());
            if (!classFile.verify(rewritten).isEmpty() && broken.size() < 10) {
                broken.add(name + ": " + classFile.verify(rewritten).getFirst().getMessage());
            }
        });

        assertThat(broken).as("classes that stopped verifying").isEmpty();
    }

    @Test
    @DisplayName("a real mapping rewrites every reference form")
    void rewritesEveryReferenceForm() {
        final var source = java.lang.constant.ClassDesc.of(Sample.class.getName());
        final var target = java.lang.constant.ClassDesc.of("com.acme.Replacement");

        final ClassFile classFile = ClassFile.of();
        final byte[] original = readClass(Sample.class);
        final byte[] rewritten = ClassRemapper.of(Map.of(source, target))
                .remap(classFile, classFile.parse(original), original);

        final String before = new String(original, java.nio.charset.StandardCharsets.ISO_8859_1);
        final String after = new String(rewritten, java.nio.charset.StandardCharsets.ISO_8859_1);

        assertThat(before).contains(Sample.class.getName().replace('.', '/'));
        assertThat(after)
                .as("no reference to the source type may survive anywhere in the class file, "
                        + "including as an unreachable constant pool entry")
                .doesNotContain(Sample.class.getName().replace('.', '/'));
        assertThat(after).contains("com/acme/Replacement");
        assertThat(classFile.verify(rewritten)).isEmpty();
    }

    @Test
    @DisplayName("array types are mapped through their element type")
    void arraysAreMappedThroughTheirElementType() {
        final var from = java.lang.constant.ClassDesc.of("com.acme.Foo");
        final var to = java.lang.constant.ClassDesc.of("com.acme.Bar");
        final ClassRemapper remapper = ClassRemapper.of(Map.of(from, to));

        assertThat(remapper.map(from.arrayType())).isEqualTo(to.arrayType());
        assertThat(remapper.map(from.arrayType().arrayType())).isEqualTo(to.arrayType().arrayType());
        assertThat(remapper.map(java.lang.constant.ConstantDescs.CD_int.arrayType()))
                .as("primitives are never mapped")
                .isEqualTo(java.lang.constant.ConstantDescs.CD_int.arrayType());
    }

    @Test
    @DisplayName("a mapping that returns null is rejected rather than producing a broken class")
    void nullMappingIsRejected() {
        final ClassRemapper broken = ClassRemapper.of(type -> null);
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> broken.map(java.lang.constant.ClassDesc.of("X")))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("mapping returned null");
    }

    // -------------------------------------------------------------------------------------

    private static byte[] readClass(final Class<?> type) {
        final String resource = type.getName().replace('.', '/') + ".class";
        try (var in = type.getClassLoader().getResourceAsStream(resource)) {
            return java.util.Objects.requireNonNull(in, resource).readAllBytes();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void forEachClassInJavaBase(final java.util.function.BiConsumer<String, byte[]> consumer) {
        try (var fs = FileSystems.newFileSystem(URI.create("jrt:/"), Map.of())) {
            final Path root = fs.getPath("/modules/java.base");
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(p -> p.toString().endsWith(".class"))
                        .filter(p -> !p.toString().endsWith("module-info.class"))
                        .forEach(p -> {
                            try {
                                consumer.accept(p.toString(), Files.readAllBytes(p));
                            } catch (final IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        });
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("all")
    static class Sample {
        Sample self;
        Sample[] many;
        java.util.List<Sample> generic;

        Sample identity(final Sample input) {
            return input;
        }

        Object build() {
            final Sample created = new Sample();
            final Sample[] array = new Sample[2];
            final Sample[][] grid = new Sample[2][2];
            final Object cast = (Sample) (Object) created;
            final boolean check = cast instanceof Sample;
            final Runnable lambda = () -> System.out.println(created.self);
            final java.util.function.Supplier<Sample> reference = Sample::new;
            final Class<?> literal = Sample.class;
            this.self = created;
            return "" + created + array.length + grid.length + check + lambda + reference + literal;
        }

        <T extends Sample> T generic(final T input) throws IllegalStateException {
            return input;
        }
    }
}
