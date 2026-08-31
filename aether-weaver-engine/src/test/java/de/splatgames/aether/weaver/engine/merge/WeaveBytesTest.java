package de.splatgames.aether.weaver.engine.merge;

import de.splatgames.aether.weaver.api.spi.ClassSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.constant.ClassDesc;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class WeaveBytesTest {

    @Nested
    @DisplayName("the name the source is asked for")
    class Naming {

        @Test
        @DisplayName("a package becomes slashes")
        void packagesUseSlashes() {
            assertThat(asked(ClassDesc.of("com.acme.audit.PaymentAudit")))
                    .containsExactly("com/acme/audit/PaymentAudit");
        }

        @Test
        @DisplayName("a class in the default package gets no leading slash")
        void theDefaultPackageHasNoSeparator() {
            assertThat(asked(ClassDesc.of("Session")))
                    .as("prefixing an empty package with a separator asks for \"/Session\", "
                            + "which no source has — and the merge stage would then report the "
                            + "weave's class file as missing while it sat right there")
                    .containsExactly("Session");
        }

        @Test
        @DisplayName("a nested class keeps its dollar sign")
        void nestedClassesKeepTheirBinaryName() {
            assertThat(asked(ClassDesc.of("com.acme.Outer$Inner")))
                    .as("a class file is named after the binary name, dollar and all")
                    .containsExactly("com/acme/Outer$Inner");
        }
    }

    @Nested
    @DisplayName("what comes back")
    class Answers {

        @Test
        @DisplayName("the bytes are handed through unchanged")
        void bytesArePassedThrough() {
            final byte[] bytes = "class-file".getBytes(StandardCharsets.UTF_8);
            final WeaveBytes adapter = WeaveBytes.from(
                    ClassSource.ofMap(Map.of("com/acme/Audit", bytes)));

            assertThat(adapter.bytesOf(ClassDesc.of("com.acme.Audit"))).isEqualTo(bytes);
        }

        @Test
        @DisplayName("a miss becomes null, which is what the merge stage expects")
        void missesBecomeNull() {
            assertThat(WeaveBytes.from(ClassSource.NONE).bytesOf(ClassDesc.of("com.acme.Audit")))
                    .as("absence is an answer here: a host that only weaves statically never "
                            + "needs a weave class's bytes")
                    .isNull();
        }
    }

    // -------------------------------------------------------------------------------------

    private static List<String> asked(final ClassDesc type) {
        final List<String> names = new ArrayList<>();
        WeaveBytes.from(internalName -> {
            names.add(internalName);
            return Optional.empty();
        }).bytesOf(type);
        return names;
    }
}
