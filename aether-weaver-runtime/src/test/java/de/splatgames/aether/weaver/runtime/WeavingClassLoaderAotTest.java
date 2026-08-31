package de.splatgames.aether.weaver.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class WeavingClassLoaderAotTest {

    private static final int TIMEOUT_SECONDS = 60;

    private static final String CACHE = "-XX:AOTCache=aether-weaver-test.aot";

    @Test
    @DisplayName("a JVM started with -XX:AOTCache gets AW2401")
    void theWarningIsReported() throws Exception {
        final String output = run(CACHE);

        assertThat(output).as("the probe must have run at all: %s", output).contains("probe: done");
        assertThat(output)
                .as("a weaving class loader under an AOT cache is the one driver combination the "
                        + "JVM's own documentation calls unsupported, and saying nothing about it "
                        + "leaves the operator to discover it from behaviour")
                .contains("AW2401");
    }

    @Test
    @DisplayName("counter-probe: an ordinary JVM stays quiet")
    void nothingIsReportedWithoutTheFlag() throws Exception {
        final String output = run();

        assertThat(output).contains("probe: done");
        assertThat(output)
                .as("without this the test above would pass on a detector that reported "
                        + "unconditionally, and the warning would be noise in every deployment")
                .doesNotContain("AW2401");
    }

    @Test
    @DisplayName("-XX:AOTMode=off vetoes it, because the JVM ignores the cache too")
    void theModeVetoIsRealEndToEnd() throws Exception {
        final String output = run("-XX:AOTMode=off", CACHE);

        assertThat(output).contains("probe: done");
        assertThat(output)
                .as("measured: that command line loads nothing from the cache, so a warning here "
                        + "would be one nobody could act on")
                .doesNotContain("AW2401");
    }

    private static String run(final String... flags) throws Exception {
        final List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(List.of(flags));
        command.addAll(List.of("-cp", System.getProperty("java.class.path"),
                AotProbe.class.getName()));

        final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        final String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertThat(process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("the child JVM did not finish").isTrue();
        assertThat(process.exitValue()).as("the probe failed: %s", output).isZero();
        return output;
    }
}
