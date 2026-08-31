package de.splatgames.aether.weaver.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AotCacheTest {

    @Nested
    @DisplayName("a cache is in play")
    class Detected {

        @Test
        @DisplayName("-XX:AOTCache names one to read")
        void consumingACache() {
            assertThat(AotCache.detect(List.of("-XX:AOTCache=app.aot")))
                    .isEqualTo("-XX:AOTCache=app.aot");
        }

        @Test
        @DisplayName("-XX:AOTCacheOutput names one to write, which is a training run")
        void producingACache() {
            assertThat(AotCache.detect(List.of("-Xmx1g", "-XX:AOTCacheOutput=app.aot")))
                    .as("a training run whose classes are loaded by a weaving class loader trains "
                            + "on the woven forms, which is the same coupling seen from the other "
                            + "end")
                    .isEqualTo("-XX:AOTCacheOutput=app.aot");
        }

        @Test
        @DisplayName("-XX:AOTConfiguration records one")
        void recordingAConfiguration() {
            assertThat(AotCache.detect(List.of("-XX:AOTConfiguration=app.aotconf")))
                    .isEqualTo("-XX:AOTConfiguration=app.aotconf");
        }

        @Test
        @DisplayName("the mode may be spelled out alongside it")
        void modeOnWithACache() {
            assertThat(AotCache.detect(List.of("-XX:AOTMode=on", "-XX:AOTCache=app.aot")))
                    .isEqualTo("-XX:AOTCache=app.aot");
        }

        @Test
        @DisplayName("an argument the JVM was given through JAVA_TOOL_OPTIONS counts too")
        void flagsFromTheEnvironment() {
            // Measured: getInputArguments() reports what JAVA_TOOL_OPTIONS injected, so a cache
            // configured by an environment variable in a container is seen here.
            assertThat(AotCache.detect(List.of("-XX:AOTCache=/opt/app.aot")))
                    .isEqualTo("-XX:AOTCache=/opt/app.aot");
        }
    }

    @Nested
    @DisplayName("no cache is in play")
    class NotDetected {

        @Test
        @DisplayName("an ordinary command line")
        void nothingAtAll() {
            assertThat(AotCache.detect(List.of("-Xmx2g", "-XX:+UseZGC"))).isNull();
        }

        @Test
        @DisplayName("-XX:AOTMode=off vetoes a cache named alongside it")
        void offBeatsACacheFile() {
            assertThat(AotCache.detect(List.of("-XX:AOTMode=off", "-XX:AOTCache=app.aot")))
                    .as("measured: that command line loads nothing from the cache, so warning "
                            + "about it would be a warning nobody could act on")
                    .isNull();
        }

        @Test
        @DisplayName("a mode with no cache file loads nothing")
        void modeAloneIsInert() {
            assertThat(AotCache.detect(List.of("-XX:AOTMode=auto")))
                    .as("measured: -XX:AOTMode=auto with no cache file loaded nothing")
                    .isNull();
        }

        @Test
        @DisplayName("classic CDS is deliberately not treated as an AOT cache")
        void sharedArchivesAreNotWarnedAbout() {
            assertThat(AotCache.detect(List.of("-XX:SharedArchiveFile=app.jsa")))
                    .as("the flag is common enough in packaged runtimes that warning on it would "
                            + "teach people to ignore the warning, which costs more than it saves")
                    .isNull();
        }

        @Test
        @DisplayName("a flag that merely starts similarly is not one of ours")
        void prefixesAreNotConfused() {
            assertThat(AotCache.detect(List.of("-XX:+AOTClassLinking", "-XX:AOTCacheSomething")))
                    .as("the flags that matter all carry a value, and matching without the '=' "
                            + "would fire on flags that name no cache at all")
                    .isNull();
        }
    }

    @Nested
    @DisplayName("reading this JVM's own command line")
    class ThisJvm {

        @Test
        @DisplayName("it answers without throwing, whatever the surrounding runtime offers")
        void neverThrows() {
            // The test JVM has no AOT cache, so this is null here. What is being asserted is that
            // asking is safe: a jlinked runtime without java.management would otherwise take the
            // class loader down with a NoClassDefFoundError at construction.
            assertThat(AotCache.active()).isNull();
        }
    }
}
