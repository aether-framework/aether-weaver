package de.splatgames.aether.weaver.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectStructureTest {

    private static final List<String> PUBLISHED_MODULES = List.of(
            "aether-weaver-api",
            "aether-weaver-engine",
            "aether-weaver-runtime",
            "aether-weaver-agent",
            "aether-weaver-processor",
            "aether-weaver-maven-plugin",
            "aether-weaver-testkit");

    private static Path repositoryRoot() {
        Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("pom.xml"))
                    && Files.isDirectory(candidate.resolve("aether-weaver-api"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("could not locate the reactor root from "
                + System.getProperty("user.dir"));
    }

    private static final String TARGET_SEGMENT = File.separator + "target" + File.separator;

    private static List<Path> javaSources(final Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains(TARGET_SEGMENT))
                    .toList();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(final Path path) {
        try {
            return Files.readString(path);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("no module-info.java exists anywhere in the project")
    void noModuleDescriptors() {
        final List<String> found = javaSources(repositoryRoot()).stream()
                .filter(p -> p.getFileName().toString().equals("module-info.java"))
                .map(p -> repositoryRoot().relativize(p).toString())
                .toList();

        assertThat(found)
                .as("Aether Weaver is a classpath library and ships no module descriptors. "
                        + "Encapsulation uses the 'internal' package convention instead.")
                .isEmpty();
    }

    @Test
    @DisplayName("the api module references no other Aether Weaver module")
    void apiHasNoInternalDependencies() {
        final Path api = repositoryRoot().resolve("aether-weaver-api");
        final List<String> violations = new ArrayList<>();

        for (final Path source : javaSources(api)) {
            read(source).lines()
                    .filter(l -> l.startsWith("import "))
                    .map(l -> l.substring("import ".length()).replace("static ", "").trim())
                    .filter(fqcn -> fqcn.startsWith("de.splatgames.aether.weaver."))
                    .filter(fqcn -> !fqcn.startsWith("de.splatgames.aether.weaver.api."))
                    .forEach(fqcn -> violations.add(
                            repositoryRoot().relativize(source) + " -> " + fqcn));
        }

        assertThat(violations)
                .as("the api module must not reference any other Aether Weaver module")
                .isEmpty();
    }

    @Test
    @DisplayName("the engine module does not reference any driver")
    void engineDoesNotReferenceDrivers() {
        final Path engine = repositoryRoot().resolve("aether-weaver-engine");
        final List<String> drivers =
                List.of("runtime", "agent", "processor", "maven", "testkit");
        final List<String> violations = new ArrayList<>();

        for (final Path source : javaSources(engine)) {
            read(source).lines()
                    .filter(l -> l.startsWith("import "))
                    .forEach(line -> drivers.stream()
                            .filter(d -> line.contains("de.splatgames.aether.weaver." + d + '.'))
                            .forEach(d -> violations.add(
                                    repositoryRoot().relativize(source) + " -> " + d)));
        }

        assertThat(violations)
                .as("the engine must not depend on a driver; drivers supply I/O and lifecycle only")
                .isEmpty();
    }

    @Test
    @DisplayName("no driver references another driver")
    void driversAreIndependent() {
        final List<String> drivers =
                List.of("runtime", "agent", "processor", "maven", "testkit");
        final List<String> violations = new ArrayList<>();

        for (final String driver : drivers) {
            final Path moduleRoot = repositoryRoot().resolve(
                    "maven".equals(driver) ? "aether-weaver-maven-plugin" : "aether-weaver-" + driver);
            if (!Files.isDirectory(moduleRoot)) {
                continue;
            }
            for (final Path source : javaSources(moduleRoot)) {
                read(source).lines()
                        .filter(l -> l.startsWith("import "))
                        .forEach(line -> drivers.stream()
                                .filter(other -> !other.equals(driver))
                                // the agent is layered on the runtime by design
                                .filter(other -> !("agent".equals(driver) && "runtime".equals(other)))
                                .filter(other -> line.contains(
                                        "de.splatgames.aether.weaver." + other + '.'))
                                .forEach(other -> violations.add(
                                        repositoryRoot().relativize(source) + ": "
                                                + driver + " -> " + other)));
            }
        }

        assertThat(violations).as("drivers must not depend on each other").isEmpty();
    }

    @Test
    @DisplayName("no module but the engine imports an engine internal, and none is exposed")
    void internalPackagesStayInternal() {
        final List<String> violations = new ArrayList<>();

        // The engine is exempt from the import rule and only from that rule. Read literally,
        // "nothing outside internal references it" would forbid the engine from using its own
        // ClassRemapper, LocalsShifter and CodeRelabeler — which exist for the engine to use and
        // would otherwise be dead code. What the convention actually protects is stated below and
        // in module layout §6: no OTHER module reaches in, and no published signature leaks a type
        // that carries no compatibility promise.
        for (final String module : PUBLISHED_MODULES) {
            if ("aether-weaver-engine".equals(module)) {
                continue;
            }
            final Path moduleRoot = repositoryRoot().resolve(module);
            if (!Files.isDirectory(moduleRoot)) {
                continue;
            }
            for (final Path source : javaSources(moduleRoot)) {
                final String content = read(source);
                final boolean sourceIsInternal = content.lines()
                        .filter(l -> l.startsWith("package "))
                        .anyMatch(l -> l.contains(".internal."));
                if (sourceIsInternal) {
                    continue;
                }
                content.lines()
                        .filter(l -> l.startsWith("import "))
                        .filter(l -> l.contains("de.splatgames.aether.weaver.")
                                && l.contains(".internal."))
                        .forEach(l -> violations.add(
                                repositoryRoot().relativize(source) + " -> " + l.trim()));
            }
        }

        assertThat(violations)
                .as("a package path containing '.internal.' carries no compatibility promise "
                        + "and must not be referenced from another module")
                .isEmpty();

        assertThat(engineTypesExposingInternals())
                .as("an engine type outside an internal package may USE an internal type, but must "
                        + "never expose one in a public signature — that would make a type with no "
                        + "compatibility promise part of the API by accident")
                .isEmpty();
    }

    private List<String> engineTypesExposingInternals() {
        final List<String> leaks = new ArrayList<>();
        final Path moduleRoot = repositoryRoot().resolve("aether-weaver-engine");
        if (!Files.isDirectory(moduleRoot)) {
            return leaks;
        }
        for (final Path source : javaSources(moduleRoot)) {
            final String content = read(source);
            if (content.lines().filter(l -> l.startsWith("package "))
                    .anyMatch(l -> l.contains(".internal."))) {
                continue;
            }
            final List<String> internalTypes = content.lines()
                    .filter(l -> l.startsWith("import ") && l.contains(".internal."))
                    .map(l -> l.substring(l.lastIndexOf('.') + 1).replace(";", "").trim())
                    .toList();
            if (internalTypes.isEmpty()) {
                continue;
            }
            content.lines()
                    .map(String::strip)
                    .filter(l -> l.startsWith("public ") || l.startsWith("protected "))
                    .forEach(line -> internalTypes.stream()
                            .filter(type -> line.matches(".*\\b" + type + "\\b.*"))
                            .forEach(type -> leaks.add(
                                    repositoryRoot().relativize(source) + ": " + line)));
        }
        return leaks;
    }

    @Test
    @DisplayName("the IDE directory exists and is deliberately not a Maven module")
    void ideIsNotInTheReactor() {
        final Path ide = repositoryRoot().resolve("aether-weaver-ide");
        assertThat(ide)
                .as("the editor integrations live here; if the directory moved, this test is the "
                        + "one place that says where it went")
                .isDirectory();
        assertThat(ide.resolve("aether-weaver-idea/build.gradle.kts"))
                .as("the IntelliJ plugin is a Gradle build")
                .exists();
        assertThat(ide.resolve("pom.xml"))
                .as("it must have no POM at all")
                .doesNotExist();

        assertThat(read(repositoryRoot().resolve("pom.xml")))
                .as("and the reactor must never list it. Building the plugin downloads an "
                        + "IntelliJ Platform distribution — more than a gigabyte — and `mvn "
                        + "install` must not depend on that. A directory that is deliberately not "
                        + "a module is exactly the kind of thing somebody adds to <modules> "
                        + "helpfully, six months from now, wondering why it was missing")
                .doesNotContain("<module>aether-weaver-ide");
    }

    @Test
    @DisplayName("every published module declares an Automatic-Module-Name")
    void everyPublishedModuleDeclaresAnAutomaticModuleName() {
        final List<String> missing = new ArrayList<>();

        for (final String module : PUBLISHED_MODULES) {
            final Path pom = repositoryRoot().resolve(module).resolve("pom.xml");
            if (!Files.isRegularFile(pom)) {
                missing.add(module + " (no pom.xml)");
                continue;
            }
            if (!read(pom).contains("<automatic.module.name>")) {
                missing.add(module);
            }
        }

        assertThat(missing)
                .as("a published jar without a stable Automatic-Module-Name breaks JPMS "
                        + "consumers on every version bump")
                .isEmpty();
    }

    @Test
    @DisplayName("no source file is excluded by .gitignore")
    void noSourceFileIsGitIgnored() throws Exception {
        final List<Path> sources = new ArrayList<>();
        try (Stream<Path> tree = Files.walk(repositoryRoot())) {
            tree.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.toString().contains(File.separator + "target"
                            + File.separator))
                    .filter(path -> !path.toString().contains(File.separator + "build"
                            + File.separator))
                    .filter(path -> !path.toString().contains(File.separator + ".git"
                            + File.separator))
                    .forEach(sources::add);
        }
        assertThat(sources).as("the walk itself must find something, or this test proves nothing")
                .isNotEmpty();

        final List<String> ignored = gitIgnored(sources);

        assertThat(ignored)
                .as("a source file matched by .gitignore is invisible: it compiles "
                        + "and its tests pass locally, and it is simply absent from the commit. "
                        + "This was not hypothetical — an unanchored `preview/` rule left over "
                        + "from an unrelated project template swallowed an entire Java package, "
                        + "and nothing but a human noticing said so")
                .isEmpty();
    }

    private static List<String> gitIgnored(final List<Path> files) throws Exception {
        final ProcessBuilder builder =
                new ProcessBuilder("git", "check-ignore", "--stdin", "--no-index")
                        .directory(repositoryRoot().toFile())
                        .redirectErrorStream(false);
        final Process git = builder.start();
        try (var out = git.getOutputStream()) {
            for (final Path file : files) {
                out.write((file.toAbsolutePath() + System.lineSeparator())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        final String matched = new String(git.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        git.waitFor();

        return matched.lines().filter(line -> !line.isBlank()).toList();
    }
}
