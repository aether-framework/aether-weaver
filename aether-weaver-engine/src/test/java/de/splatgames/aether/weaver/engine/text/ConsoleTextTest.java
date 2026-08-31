package de.splatgames.aether.weaver.engine.text;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ConsoleText")
class ConsoleTextTest {

    private static final Charset CONSOLE = Charset.forName("cp850");

    @Nested
    @DisplayName("when the charset carries everything")
    class Unicode {

        @Test
        @DisplayName("UTF-8 keeps the typography exactly")
        void utf8IsUntouched() {
            final String report = "app.Greeting → app.Target — priority 0, T ≠ void …";

            assertThat(ConsoleText.forCharset(report, StandardCharsets.UTF_8))
                    .as("degrading where nothing is lost would make every terminal pay for the "
                            + "one that cannot cope")
                    .isSameAs(report);
        }
    }

    @Nested
    @DisplayName("when the charset cannot")
    class Degraded {

        @Test
        @DisplayName("each glyph becomes something that means the same")
        void glyphsDegradeToTheirMeaning() {
            assertThat(ConsoleText.forCharset("a → b", CONSOLE)).isEqualTo("a -> b");
            assertThat(ConsoleText.forCharset("a ← b", CONSOLE)).isEqualTo("a <- b");
            assertThat(ConsoleText.forCharset("  ↳ x", CONSOLE)).isEqualTo("  -> x");
            assertThat(ConsoleText.forCharset("T ≠ void", CONSOLE)).isEqualTo("T != void");
            assertThat(ConsoleText.forCharset("weave — target", CONSOLE)).isEqualTo("weave - target");
            assertThat(ConsoleText.forCharset("more…", CONSOLE)).isEqualTo("more...");
        }

        @Test
        @DisplayName("the counter-probe: without this, the stream writes a question mark")
        void theStreamItselfWouldLoseIt() throws Exception {
            final ByteArrayOutputStream raw = new ByteArrayOutputStream();
            try (PrintStream stream = new PrintStream(raw, true, CONSOLE)) {
                stream.print("a → b");
            }

            assertThat(raw.toString(CONSOLE))
                    .as("this is what the defect looks like, and it is why degrading has to happen "
                            + "before the write rather than being left to the stream")
                    .isEqualTo("a ? b");
        }

        @Test
        @DisplayName("what is written is then encodable, which is the whole point")
        void theResultSurvivesTheRoundTrip() throws Exception {
            final String degraded = ConsoleText.forCharset("app.A → app.B ≠ app.C …", CONSOLE);

            final ByteArrayOutputStream raw = new ByteArrayOutputStream();
            try (PrintStream stream = new PrintStream(raw, true, CONSOLE)) {
                stream.print(degraded);
            }

            assertThat(raw.toString(CONSOLE))
                    .as("nothing may be lost on the way out any more")
                    .isEqualTo(degraded)
                    .doesNotContain("?");
        }

        @Test
        @DisplayName("an unmapped glyph still degrades rather than throwing")
        void unmappedGlyphsBecomeAQuestionMark() {
            assertThat(ConsoleText.forCharset("plan 🔴 ok", CONSOLE))
                    .as("a supplementary character is one glyph, so it costs one replacement "
                            + "and not two")
                    .isEqualTo("plan ? ok");
        }

        @Test
        @DisplayName("ASCII around the damage is left alone")
        void asciiIsPreserved() {
            assertThat(ConsoleText.forCharset("INJECT onGreet() → greet() @HEAD", CONSOLE))
                    .isEqualTo("INJECT onGreet() -> greet() @HEAD");
        }
    }

    @Nested
    @DisplayName("the stream overload")
    class Streams {

        @Test
        @DisplayName("takes the charset from the stream it is going to")
        void usesTheStreamsCharset() {
            final PrintStream console = new PrintStream(new ByteArrayOutputStream(), true, CONSOLE);
            final PrintStream unicode =
                    new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);

            assertThat(ConsoleText.forStream("a → b", console)).isEqualTo("a -> b");
            assertThat(ConsoleText.forStream("a → b", unicode)).isEqualTo("a → b");
        }
    }
}
