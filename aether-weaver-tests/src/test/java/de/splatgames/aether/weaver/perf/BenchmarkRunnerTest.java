package de.splatgames.aether.weaver.perf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;

class BenchmarkRunnerTest {

    @Test
    @EnabledIfSystemProperty(named = "jmh", matches = "true",
            disabledReason = "benchmarks run on demand: -Djmh=true")
    @DisplayName("the performance suite runs and reports")
    void runTheSuite() throws Exception {
        final Collection<RunResult> results = new Runner(new OptionsBuilder()
                .include(WeavingBenchmark.class.getSimpleName())
                .shouldFailOnError(true)
                .build())
                .run();

        assertThat(results)
                .as("every benchmark in the suite must produce a result; a silent zero would mean "
                        + "JMH found no generated runners, which happens when its annotation "
                        + "processor did not run")
                .hasSize(3);

        for (final RunResult result : results) {
            System.out.printf("%-24s %10.3f %s%n",
                    result.getParams().getBenchmark()
                            .substring(result.getParams().getBenchmark().lastIndexOf('.') + 1),
                    result.getPrimaryResult().getScore(),
                    result.getPrimaryResult().getScoreUnit());
        }
    }
}
