package de.splatgames.aether.weaver.maven;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.Require;
import de.splatgames.aether.weaver.api.experimental.Nulls;
import de.splatgames.aether.weaver.api.experimental.Scope;
import de.splatgames.aether.weaver.api.manifest.ManifestWriter;
import de.splatgames.aether.weaver.api.manifest.WeaveManifest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TransitiveWeaveTest {

    private final List<Diagnostic> reported = new ArrayList<>();

    @Test
    @DisplayName("AW3010 — a weave from an undeclared dependency is reported")
    void aTransitiveWeaveIsReported(@TempDir final Path root) throws IOException {
        final Path stranger = entryDeclaringAWeave(root.resolve("stranger"));

        final WeaveManifest merged = Manifests.of(List.of(stranger), Set.of(), this.reported::add);

        assertThat(merged.weaves())
                .as("the weave is still read and still applies; what changes is that it is named")
                .hasSize(1);
        assertThat(codes()).containsExactly("AW3010");
        assertThat(this.reported.getFirst().message())
                .as("the message has to name the artefact, because that is the only handle the "
                        + "reader has on something their own build file never mentions")
                .contains("stranger");
    }

    @Test
    @DisplayName("a weave from a declared dependency is silent")
    void aDirectWeaveIsSilent(@TempDir final Path root) throws IOException {
        final Path declared = entryDeclaringAWeave(root.resolve("declared"));

        Manifests.of(List.of(declared), Set.of(declared), this.reported::add);

        assertThat(codes())
                .as("without this the test above would pass against a check that reported every "
                        + "weave the classpath carries, which is every weave there is")
                .isEmpty();
    }

    @Test
    @DisplayName("a goal with no dependency graph checks nothing")
    void noGraphMeansNoCheck(@TempDir final Path root) throws IOException {
        final Path stranger = entryDeclaringAWeave(root.resolve("stranger"));

        Manifests.of(List.of(stranger), this.reported::add);

        assertThat(codes())
                .as("the two-argument form is what a goal without a project passes, and answering "
                        + "'transitive' there would be a guess rather than a finding")
                .isEmpty();
    }

    @Test
    @DisplayName("an extension from an undeclared dependency is not reported")
    void aTransitiveExtensionIsSilent(@TempDir final Path root) throws IOException {
        final Path stranger = entryDeclaringAnExtension(root.resolve("stranger"));

        Manifests.of(List.of(stranger), Set.of(), this.reported::add);

        assertThat(codes())
                .as("an extension rewrites a call the author wrote deliberately, so its arrival "
                        + "is already visible at the call site. A weave's is visible nowhere, and "
                        + "warning about both would bury the one that matters")
                .isEmpty();
    }

    // --- fixtures -------------------------------------------------------------------------

    private List<String> codes() {
        return this.reported.stream().map(diagnostic -> diagnostic.code().code()).toList();
    }

    private static Path entryDeclaringAWeave(final Path entry) throws IOException {
        return write(entry, new WeaveManifest(WeaveManifest.VERSION, "test",
                List.of(new WeaveManifest.Weave("com.acme.Tracing", "INSTANCE", 0,
                        "REQUIRED", "DEFAULT", List.of(), List.of("com.acme.Service"),
                        List.of(), List.of())),
                List.of()));
    }

    private static Path entryDeclaringAnExtension(final Path entry) throws IOException {
        return write(entry, new WeaveManifest(WeaveManifest.VERSION, "test", List.of(),
                List.of(new WeaveManifest.Extension("com.acme.Strings", "java.lang.String",
                        "shout", "(Ljava/lang/String;)Ljava/lang/String;",
                        WeaveManifest.Extension.Kind.INSTANCE,
                        Require.REQUIRED, Nulls.UNCHECKED, Scope.PUBLIC))));
    }

    private static Path write(final Path entry, final WeaveManifest manifest) throws IOException {
        final Path file = entry.resolve(WeaveManifest.RESOURCE);
        Files.createDirectories(file.getParent());
        Files.writeString(file, ManifestWriter.write(manifest), StandardCharsets.UTF_8);
        return entry;
    }
}
