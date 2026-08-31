package de.splatgames.aether.weaver.maven;

import de.splatgames.aether.weaver.api.manifest.ManifestWriter;
import de.splatgames.aether.weaver.api.Require;
import de.splatgames.aether.weaver.api.experimental.Scope;
import org.apache.maven.plugin.MojoExecutionException;
import de.splatgames.aether.weaver.api.manifest.WeaveManifest;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.classfile.ClassFile;
import java.lang.classfile.MethodModel;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StubsMojoTest {

    private static final WeaveManifest.Extension ON_STRING = new WeaveManifest.Extension(
            "com.acme.Strings", "java.lang.String", "shout", "(I)Ljava/lang/String;");

    private static final WeaveManifest.Extension ON_CLASSPATH = new WeaveManifest.Extension(
            "com.acme.Helpers", Manifests.class.getName(), "describe", "()Ljava/lang/String;");

    @Nested
    @DisplayName("where a stub goes")
    class Placement {

        @Test
        @DisplayName("a JDK receiver is stubbed into its module's patch directory")
        void jdkReceiverIsPatched(@TempDir final Path directory) throws Exception {
            final Path stubs = run(directory, ON_STRING);

            final Path stub = stubs.resolve("patch/java.base/java/lang/String.class");
            assertThat(stub)
                    .as("java.lang.String lives in java.base and cannot be shadowed by a classpath "
                            + "entry, so its stub is only reachable through --patch-module")
                    .isRegularFile();
            assertThat(methodsOf(stub)).contains("shout(I)Ljava/lang/String;");
        }

        @Test
        @DisplayName("the patched String is otherwise the real one")
        void theJdkReceiverKeepsItsOwnMembers(@TempDir final Path directory) throws Exception {
            // A stub is the receiver plus the extension, never a hand-built class with one method
            // on it. Compiling against the latter would make every other call on that type fail,
            // and the error would blame the caller.
            final Path stub = run(directory, ON_STRING)
                    .resolve("patch/java.base/java/lang/String.class");

            assertThat(methodsOf(stub))
                    .contains("length()I", "isEmpty()Z", "shout(I)Ljava/lang/String;");
        }

        @Test
        @DisplayName("a classpath receiver is stubbed into the classpath directory")
        void classpathReceiverIsShadowed(@TempDir final Path directory) throws Exception {
            final Path stubs = run(directory, ON_CLASSPATH);

            final Path stub = stubs.resolve("classpath")
                    .resolve(Manifests.class.getName().replace('.', '/') + ".class");
            assertThat(stub).isRegularFile();
            assertThat(methodsOf(stub)).contains("describe()Ljava/lang/String;");
        }

        @Test
        @DisplayName("the classpath directory exists even when nothing went into it")
        void theClasspathDirectoryAlwaysExists(@TempDir final Path directory) throws Exception {
            // A build names this directory in its compile classpath once and then forgets about
            // it. The day the last classpath extension is deleted, the directory must still be
            // there, or the build breaks for a reason that has nothing to do with the change.
            assertThat(run(directory, ON_STRING).resolve("classpath")).isDirectory();
        }
    }

    @Nested
    @DisplayName("an extension a dependency keeps to itself")
    class Scoped {

        private static final WeaveManifest.Extension SCOPED = new WeaveManifest.Extension(
                ON_CLASSPATH.className(), ON_CLASSPATH.receiver(), ON_CLASSPATH.name(),
                ON_CLASSPATH.descriptor(), ON_CLASSPATH.kind(), Require.REQUIRED,
                de.splatgames.aether.weaver.api.experimental.Nulls.UNCHECKED, Scope.MODULE);

        @Test
        @DisplayName("gets no stub here, so a call naming it does not compile")
        void aDependencysModuleScopedExtensionIsNotStubbed(@TempDir final Path directory)
                throws Exception {
            final Path stubs = run(directory, SCOPED);

            assertThat(stubs.resolve(StubsMojo.CLASSPATH)
                    .resolve(ON_CLASSPATH.receiverInternalName() + ".class"))
                    .as("a scope is a rule about who may write a call, and the moment a stub would "
                            + "make the call resolvable is the only moment it is still open")
                    .doesNotExist();
        }

        @Test
        @DisplayName("counter-probe: the same declaration public is stubbed")
        void thePublicOneIsStubbed(@TempDir final Path directory) throws Exception {
            final Path stubs = run(directory, ON_CLASSPATH);

            assertThat(stubs.resolve(StubsMojo.CLASSPATH)
                    .resolve(ON_CLASSPATH.receiverInternalName() + ".class"))
                    .as("without this the test above would pass for any reason at all")
                    .exists();
        }
    }

    @Nested
    @DisplayName("a receiver that is not on the compile classpath")
    class MissingReceivers {

        private static final WeaveManifest.Extension ON_MISSING = new WeaveManifest.Extension(
                "com.acme.Missings", "com.acme.absent.Gone", "gone", "()V",
                WeaveManifest.Extension.Kind.INSTANCE, Require.REQUIRED,
                de.splatgames.aether.weaver.api.experimental.Nulls.UNCHECKED,
                de.splatgames.aether.weaver.api.experimental.Scope.PUBLIC);

        @Test
        @DisplayName("REQUIRED stops the build here, where the missing type can be named")
        void requiredFails(@TempDir final Path directory) {
            assertThatThrownBy(() -> run(directory, ON_MISSING))
                    .as("javac would stop the build a moment later, at every call site, about a "
                            + "declaration in another module — one error naming the type beats "
                            + "twenty naming its consequences")
                    .isInstanceOf(MojoExecutionException.class)
                    .hasMessageContaining("com.acme.absent.Gone")
                    .hasMessageContaining("com.acme.Missings");
        }

        @Test
        @DisplayName("OPTIONAL skips it, which is what makes a soft dependency possible")
        void optionalIsSkipped(@TempDir final Path directory) throws Exception {
            final WeaveManifest.Extension optional = new WeaveManifest.Extension(
                    ON_MISSING.className(), ON_MISSING.receiver(), ON_MISSING.name(),
                    ON_MISSING.descriptor(), ON_MISSING.kind(), Require.OPTIONAL,
                    ON_MISSING.nulls(), ON_MISSING.scope());

            // No exception, and nothing written: a consumer without that dependency simply does
            // not get those methods, and for them nothing is wrong.
            final Path stubs = run(directory, optional);
            assertThat(stubs.resolve(StubsMojo.PATCH)).doesNotExist();
        }
    }

    @Nested
    @DisplayName("when there is nothing to do")
    class Quiet {

        @Test
        @DisplayName("a classpath with no extensions writes nothing at all")
        void noExtensionsWritesNothing(@TempDir final Path directory) throws Exception {
            final Path stubs = run(directory);

            assertThat(stubs)
                    .as("a module that uses no extension must not gain a directory it never asked "
                            + "for; an empty tree under target is a tree nobody trusts")
                    .doesNotExist();
        }
    }

    // --- the harness ---------------------------------------------------------------------------

    private static Path run(final Path directory,
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

        final Path stubs = directory.resolve("stubs");
        final StubsMojo mojo = new StubsMojo();
        mojo.setLog(new SystemStreamLog());
        set(mojo, "outputDirectory", stubs.toFile());
        set(mojo, "project", new MavenProject() {
            @Override
            public List<String> getCompileClasspathElements() {
                return classpath;
            }
        });
        mojo.execute();
        return stubs;
    }

    private static List<String> methodsOf(final Path file) throws Exception {
        final List<String> methods = new ArrayList<>();
        for (final MethodModel method : ClassFile.of().parse(Files.readAllBytes(file)).methods()) {
            methods.add(method.methodName().stringValue() + method.methodType().stringValue());
        }
        return methods;
    }

    private static void set(final Object mojo, final String name, final Object value)
            throws Exception {
        final Field field = mojo.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(mojo, value);
    }
}
