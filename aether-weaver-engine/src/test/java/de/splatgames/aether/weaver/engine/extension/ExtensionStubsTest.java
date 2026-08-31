package de.splatgames.aether.weaver.engine.extension;

import de.splatgames.aether.weaver.api.manifest.WeaveManifest;
import de.splatgames.aether.weaver.api.spi.ClassSource;
import de.splatgames.aether.weaver.engine.extension.fixture.Greeting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.Annotation;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.MethodModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ExtensionStubsTest {

    private static final String HOLDER =
            "de.splatgames.aether.weaver.engine.extension.fixture.GreetingExtensions";

    private static final WeaveManifest.Extension LEGACY = new WeaveManifest.Extension(
            HOLDER,
            "de.splatgames.aether.weaver.engine.extension.fixture.Greeting",
            "legacy",
            "(Ljava/lang/String;)Ljava/lang/String;");

    private static final ClassSource CLASSPATH = internalName -> {
        try (InputStream in = ExtensionStubsTest.class.getResourceAsStream(
                '/' + internalName + ".class")) {
            return in == null ? Optional.empty() : Optional.of(in.readAllBytes());
        } catch (final IOException unreadable) {
            return Optional.empty();
        }
    };

    @Nested
    @DisplayName("what the stub carries across")
    class Carried {

        @Test
        @DisplayName("a deprecated extension is deprecated where it is called")
        void deprecationSurvives() {
            final MethodModel stub = stub();

            assertThat(stub.findAttribute(Attributes.deprecated()))
                    .as("the attribute is what makes javac warn at the call site")
                    .isPresent();
            assertThat(annotations(stub)).contains("Ljava/lang/Deprecated;");
        }

        @Test
        @DisplayName("a parameter annotation moves with its parameter")
        void parameterAnnotationsSurvive() {
            final List<List<String>> parameters =
                    stub().findAttribute(Attributes.runtimeInvisibleParameterAnnotations())
                            .map(attribute -> attribute.parameterAnnotations().stream()
                                    .map(ExtensionStubsTest::names)
                                    .toList())
                            .orElse(List.of());

            assertThat(parameters)
                    .as("the receiver is not a parameter of the contributed method, so what was "
                            + "parameter one is now parameter zero")
                    .hasSize(1);
            assertThat(parameters.getFirst()).contains("Lorg/jetbrains/annotations/Nullable;");
        }

        @Test
        @DisplayName("this framework's own annotations do not")
        void receiverIsNotCarried() {
            final MethodModel stub = stub();
            final List<String> all = new ArrayList<>(annotations(stub));
            stub.findAttribute(Attributes.runtimeVisibleParameterAnnotations())
                    .ifPresent(attribute -> attribute.parameterAnnotations()
                            .forEach(each -> all.addAll(names(each))));

            assertThat(all)
                    .as("@Receiver describes how the implementation was written; on a stub it would "
                            + "annotate a method that has no @Extension class around it")
                    .noneMatch(name -> name.startsWith("Lde/splatgames/aether/weaver/api/"));
        }
    }

    // --- reading the stub back ---------------------------------------------------------------

    private static MethodModel stub() {
        final String internal = Greeting.class.getName().replace('.', '/');
        final byte[] patched = ExtensionStubs.patch(CLASSPATH.find(internal).orElseThrow(),
                List.of(LEGACY), CLASSPATH);
        assertThat(patched).as("Greeting declares no legacy(String), so it must be added").isNotNull();

        for (final MethodModel method : ClassFile.of().parse(patched).methods()) {
            if (method.methodName().equalsString("legacy")) {
                return method;
            }
        }
        throw new AssertionError("the stub did not contain the method it was asked to add");
    }

    private static List<String> annotations(final MethodModel method) {
        return method.findAttribute(Attributes.runtimeVisibleAnnotations())
                .map(attribute -> names(attribute.annotations()))
                .orElse(List.of());
    }

    private static List<String> names(final List<Annotation> annotations) {
        return annotations.stream().map(each -> each.className().stringValue()).toList();
    }
}
