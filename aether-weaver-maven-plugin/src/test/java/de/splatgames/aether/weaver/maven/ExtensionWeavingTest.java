package de.splatgames.aether.weaver.maven;

import de.splatgames.aether.weaver.api.manifest.ManifestWriter;
import de.splatgames.aether.weaver.api.manifest.WeaveManifest;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.classfile.ClassFile;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExtensionWeavingTest {

    private static final Class<?> RECEIVER = Manifests.class;

    private static final String HOLDER = "com.acme.Helpers";

    @Nested
    @DisplayName("a module that only calls extensions")
    class CallerOnly {

        @Test
        @DisplayName("its call sites are rewritten even though it declares no weave")
        void callSitesAreRewritten(@TempDir final Path directory) throws Exception {
            final Path classes = directory.resolve("classes");
            Files.createDirectories(classes.resolve("probe"));
            Files.write(classes.resolve("probe/Caller.class"), caller());

            run(directory, classes, extension());

            final List<InvokeInstruction> calls =
                    invocations(classes.resolve("probe/Caller.class"));
            assertThat(calls).singleElement().satisfies(invoke -> {
                assertThat(invoke.opcode()).isEqualTo(Opcode.INVOKESTATIC);
                assertThat(invoke.owner().asInternalName()).isEqualTo("com/acme/Helpers");
                assertThat(invoke.type().stringValue())
                        .as("the receiver becomes parameter zero")
                        .isEqualTo("(L" + RECEIVER.getName().replace('.', '/')
                                + ";)Ljava/lang/String;");
            });
        }

        @Test
        @DisplayName("running the goal twice changes nothing the second time")
        void theRewriteIsIdempotent(@TempDir final Path directory) throws Exception {
            final Path classes = directory.resolve("classes");
            Files.createDirectories(classes.resolve("probe"));
            Files.write(classes.resolve("probe/Caller.class"), caller());

            run(directory, classes, extension());
            final byte[] once = Files.readAllBytes(classes.resolve("probe/Caller.class"));

            run(directory, classes, extension());
            assertThat(Files.readAllBytes(classes.resolve("probe/Caller.class")))
                    .as("an invokestatic is never a candidate, so the transformation is idempotent "
                            + "by its own shape and needs no marker to record that it ran")
                    .isEqualTo(once);
        }

        @Test
        @DisplayName("a classpath declaring no extension leaves the class untouched")
        void nothingDeclaredChangesNothing(@TempDir final Path directory) throws Exception {
            final Path classes = directory.resolve("classes");
            Files.createDirectories(classes.resolve("probe"));
            final byte[] original = caller();
            Files.write(classes.resolve("probe/Caller.class"), original);

            run(directory, classes);

            assertThat(Files.readAllBytes(classes.resolve("probe/Caller.class")))
                    .isEqualTo(original);
        }
    }

    @Nested
    @DisplayName("a static extension, which travels through the same manifest")
    class StaticCalls {

        @Test
        @DisplayName("its kind survives being written to a manifest and read back")
        void staticCallSiteIsRewritten(@TempDir final Path directory) throws Exception {
            final Path classes = directory.resolve("classes");
            Files.createDirectories(classes.resolve("probe"));
            Files.write(classes.resolve("probe/Caller.class"), staticCaller());

            // The kind is the only thing distinguishing this from an instance extension, and it is
            // the one component that has to cross a file. Written as "instance" or lost, the weaver
            // would insert a receiver parameter that the call site never pushed.
            run(directory, classes, staticExtension());

            final InvokeInstruction rewritten =
                    invocations(classes.resolve("probe/Caller.class")).getFirst();
            assertThat(rewritten.opcode()).isEqualTo(Opcode.INVOKESTATIC);
            assertThat(rewritten.owner().asInternalName()).isEqualTo(HOLDER.replace('.', '/'));
            assertThat(rewritten.type().stringValue())
                    .as("a static extension passes exactly what the call site passed")
                    .isEqualTo("()Ljava/lang/String;");
        }

        @Test
        @DisplayName("and running the goal twice still changes nothing the second time")
        void theRewriteIsIdempotent(@TempDir final Path directory) throws Exception {
            final Path classes = directory.resolve("classes");
            Files.createDirectories(classes.resolve("probe"));
            Files.write(classes.resolve("probe/Caller.class"), staticCaller());

            // Worth its own test rather than sharing the instance one. An instance rewrite is
            // self-evidently not a candidate afterwards — its descriptor gained a parameter. A
            // static rewrite changes nothing but the owner, so what stops a second pass is that the
            // new owner really does declare the method, which is the same rule that stops any call
            // resolving to an extension when a real method answers first.
            run(directory, classes, staticExtension());
            final byte[] once = Files.readAllBytes(classes.resolve("probe/Caller.class"));

            run(directory, classes, staticExtension());
            assertThat(Files.readAllBytes(classes.resolve("probe/Caller.class")))
                    .isEqualTo(once);
        }
    }

    // --- the harness ---------------------------------------------------------------------------

    private static WeaveManifest.Extension extension() {
        return new WeaveManifest.Extension(HOLDER, RECEIVER.getName(), "describe",
                "()Ljava/lang/String;");
    }

    private static WeaveManifest.Extension staticExtension() {
        return new WeaveManifest.Extension(HOLDER, RECEIVER.getName(), "parse",
                "()Ljava/lang/String;", WeaveManifest.Extension.Kind.STATIC);
    }

    private static byte[] staticCaller() {
        final ClassDesc receiver = ClassDesc.of(RECEIVER.getName());
        return ClassFile.of().build(ClassDesc.of("probe.Caller"), builder -> builder
                .withMethodBody("call", MethodTypeDesc.of(ConstantDescs.CD_String),
                        ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                        code -> code
                                .invokestatic(receiver, "parse",
                                        MethodTypeDesc.of(ConstantDescs.CD_String))
                                .areturn()));
    }

    private static byte[] caller() {
        final ClassDesc receiver = ClassDesc.of(RECEIVER.getName());
        return ClassFile.of().build(ClassDesc.of("probe.Caller"), builder -> builder
                .withMethodBody("call", MethodTypeDesc.of(ConstantDescs.CD_String, receiver),
                        ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                        code -> code
                                .aload(0)
                                .invokevirtual(receiver, "describe",
                                        MethodTypeDesc.of(ConstantDescs.CD_String))
                                .areturn()));
    }

    private static void run(final Path directory,
                            final Path classes,
                            final WeaveManifest.Extension... extensions) throws Exception {
        final Path dependency = directory.resolve("dependency");
        final Path manifest = dependency.resolve(WeaveManifest.RESOURCE);
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, ManifestWriter.write(
                WeaveManifest.of("test", List.of(), List.of(extensions))));

        final List<String> classpath = new ArrayList<>();
        classpath.add(dependency.toString());
        classpath.addAll(List.of(System.getProperty("java.class.path")
                .split(File.pathSeparator)));

        final WeaveMojo mojo = new WeaveMojo();
        mojo.setLog(new SystemStreamLog());
        set(mojo, "classesDirectory", classes.toFile());
        set(mojo, "project", new MavenProject() {
            @Override
            public List<String> getCompileClasspathElements() {
                return classpath;
            }
        });
        mojo.execute();
    }

    private static List<InvokeInstruction> invocations(final Path file) throws Exception {
        final List<InvokeInstruction> found = new ArrayList<>();
        ClassFile.of().parse(Files.readAllBytes(file)).methods().forEach(method -> method.code()
                .ifPresent(code -> code.elementStream()
                        .filter(InvokeInstruction.class::isInstance)
                        .map(InvokeInstruction.class::cast)
                        .forEach(found::add)));
        return found;
    }

    private static void set(final Object mojo, final String name, final Object value)
            throws Exception {
        final Field field = mojo.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(mojo, value);
    }
}
