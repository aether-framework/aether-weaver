package de.splatgames.aether.weaver.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.assertj.core.api.Assertions.assertThat;

class RetransformAnnotationSpikeTest {

    private static final int TIMEOUT_SECONDS = 60;

    @Test
    @DisplayName("spike 11 — the JVM accepts it, and reflection sees it")
    void aClassLevelAnnotationCanBeAddedByRetransformation(@TempDir final Path work)
            throws Exception {
        final String output = runProbe(work);

        assertThat(output)
                .as("the probe must have run to completion: %s", output)
                .contains("SPIKE-DONE");

        assertThat(output)
                .as("the warm subject's annotations really were read before the retransform, "
                        + "so the mirror held a cached (empty) answer — without this the last "
                        + "assertion would prove nothing about cache invalidation")
                .contains("preWarm=false annotations=0");

        assertThat(output)
                .as("(a) JVMTI must accept an attribute-only redefinition — attributes are not "
                        + "members, but 'expected' is the word this spike exists to eliminate")
                .contains("accepted=true");

        assertThat(output)
                .as("(c) the retransformed bytes really carry the annotation, so a negative "
                        + "reflective answer would be the mirror's doing and not the weaver's")
                .contains("inBytes=true");

        assertThat(output)
                .as("(b) reflection reports it on a class whose annotations were NEVER read "
                        + "before the retransform")
                .contains("reflectedCold=true");

        assertThat(output)
                .as("(d) and on one whose annotations WERE read first. This is the case that "
                        + "would be silently wrong: the JVM accepting the new bytes while the "
                        + "mirror keeps answering from a stale cache looks exactly like success")
                .contains("reflectedWarm=true");
    }

    // -------------------------------------------------------------------------------------

    private static String runProbe(final Path work) throws Exception {
        final Path classes = Files.createDirectories(work.resolve("probe"));
        compile(classes, SUBJECT_COLD, SUBJECT_WARM, PROBE);

        final Path agentJar = agentJar(work);
        final Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-javaagent:" + agentJar,
                "-cp", classes + File.pathSeparator + System.getProperty("java.class.path"),
                "spike.Probe")
                .redirectErrorStream(true)
                .start();

        final String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertThat(process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("the probe JVM did not finish").isTrue();
        return output;
    }

    private static Path agentJar(final Path work) throws IOException {
        final Manifest manifest = new Manifest();
        final Attributes main = manifest.getMainAttributes();
        main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        main.putValue("Premain-Class", SpikeAgent.class.getName());
        // Without this the JVM refuses retransformClasses outright, and the spike would measure
        // the manifest rather than the JVM.
        main.putValue("Can-Retransform-Classes", "true");

        final Path jar = work.resolve("spike-agent.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            out.flush();
        }
        return jar;
    }

    private static void compile(final Path output, final String... sources) {
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, null)) {
            files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(output));
            final List<JavaFileObject> units = new ArrayList<>();
            for (final String source : sources) {
                units.add(new Source("spike/" + nameOf(source), source));
            }
            assertThat(compiler.getTask(null, files, null,
                    List.of("-classpath", System.getProperty("java.class.path"), "-proc:none"),
                    null, units).call())
                    .as("the probe must compile").isTrue();
        } catch (final IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    private static String nameOf(final String source) {
        final java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?m)^public (?:final )?class (\\w+)").matcher(source);
        assertThat(matcher.find()).as("every source declares one public class").isTrue();
        return matcher.group(1);
    }

    private static final String SUBJECT_COLD = """
            package spike;

            public class SubjectCold {
                public String value() { return "cold"; }
            }
            """;

    private static final String SUBJECT_WARM = """
            package spike;

            public class SubjectWarm {
                public String value() { return "warm"; }
            }
            """;

    private static final String PROBE = """
            package spike;

            import de.splatgames.aether.weaver.agent.SpikeAgent;
            import de.splatgames.aether.weaver.api.Woven;

            import java.lang.classfile.Annotation;
            import java.lang.classfile.AnnotationElement;
            import java.lang.classfile.AnnotationValue;
            import java.lang.classfile.ClassFile;
            import java.lang.classfile.ClassTransform;
            import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
            import java.lang.constant.ClassDesc;
            import java.lang.instrument.ClassFileTransformer;
            import java.lang.instrument.Instrumentation;
            import java.security.ProtectionDomain;

            public class Probe {

                public static void main(String[] args) throws Exception {
                    Instrumentation inst = SpikeAgent.instrumentation();

                    // Load both subjects. The warm one has its annotations read NOW, before any
                    // retransformation, which is the only way a stale mirror cache becomes visible.
                    Class<?> cold = SubjectCold.class;
                    Class<?> warm = SubjectWarm.class;
                    System.out.println("preCold=" + (cold.getAnnotation(Woven.class) != null));
                    System.out.println("preWarm=" + (warm.getAnnotation(Woven.class) != null)
                            + " annotations=" + warm.getAnnotations().length);

                    byte[][] captured = new byte[1][];
                    ClassFileTransformer adder = new ClassFileTransformer() {
                        @Override
                        public byte[] transform(Module module, ClassLoader loader, String name,
                                                Class<?> beingRedefined, ProtectionDomain pd,
                                                byte[] buffer) {
                            if (name == null || !name.startsWith("spike/Subject")) {
                                return null;
                            }
                            byte[] woven = annotate(buffer);
                            captured[0] = woven;
                            return woven;
                        }
                    };

                    boolean accepted;
                    try {
                        inst.addTransformer(adder, true);
                        inst.retransformClasses(cold, warm);
                        accepted = true;
                    } catch (Throwable refused) {
                        accepted = false;
                        System.out.println("refusal=" + refused);
                    } finally {
                        inst.removeTransformer(adder);
                    }
                    System.out.println("accepted=" + accepted);

                    boolean inBytes = captured[0] != null
                            && ClassFile.of().parse(captured[0])
                                    .findAttribute(java.lang.classfile.Attributes
                                            .runtimeVisibleAnnotations())
                                    .isPresent();
                    System.out.println("inBytes=" + inBytes);

                    System.out.println("reflectedCold="
                            + (cold.getAnnotation(Woven.class) != null));
                    System.out.println("reflectedWarm="
                            + (warm.getAnnotation(Woven.class) != null));
                    System.out.println("SPIKE-DONE");
                }

                static byte[] annotate(byte[] original) {
                    ClassFile cf = ClassFile.of();
                    Annotation woven = Annotation.of(
                            ClassDesc.of("de.splatgames.aether.weaver.api.Woven"),
                            AnnotationElement.of("weaver", AnnotationValue.ofString("0.1.0")),
                            AnnotationElement.of("fingerprint",
                                    AnnotationValue.ofString("spike11")));
                    return cf.transformClass(cf.parse(original),
                            ClassTransform.endHandler(builder ->
                                    builder.with(RuntimeVisibleAnnotationsAttribute.of(woven))));
                }
            }
            """;

    private static final class Source extends SimpleJavaFileObject {

        private final String code;

        Source(final String path, final String code) {
            super(URI.create("string:///" + path + ".java"), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(final boolean ignoreEncodingErrors) {
            return this.code;
        }
    }
}
