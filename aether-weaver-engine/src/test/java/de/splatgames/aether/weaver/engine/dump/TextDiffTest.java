package de.splatgames.aether.weaver.engine.dump;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class TextDiffTest {

    @Nested
    @DisplayName("what it reports")
    class Reports {

        @Test
        @DisplayName("two identical texts produce nothing at all")
        void noDifference() {
            assertThat(diff(List.of("a", "b", "c"), List.of("a", "b", "c"))).isEmpty();
        }

        @Test
        @DisplayName("an insertion in the middle shows as one added line")
        void insertion() {
            assertThat(changed(diff(List.of("a", "b", "c"), List.of("a", "x", "b", "c"))))
                    .containsExactly("+ x");
        }

        @Test
        @DisplayName("a deletion shows as one removed line")
        void deletion() {
            assertThat(changed(diff(List.of("a", "b", "c"), List.of("a", "c"))))
                    .containsExactly("- b");
        }

        @Test
        @DisplayName("a replacement shows as both")
        void replacement() {
            assertThat(changed(diff(List.of("a", "b", "c"), List.of("a", "x", "c"))))
                    .containsExactlyInAnyOrder("- b", "+ x");
        }

        @Test
        @DisplayName("a change at the very first line is not lost to the prefix trim")
        void changeAtTheStart() {
            assertThat(changed(diff(List.of("a", "b"), List.of("x", "b"))))
                    .containsExactlyInAnyOrder("- a", "+ x");
        }

        @Test
        @DisplayName("a change at the very last line is not lost to the suffix trim")
        void changeAtTheEnd() {
            assertThat(changed(diff(List.of("a", "b"), List.of("a", "x"))))
                    .containsExactlyInAnyOrder("- b", "+ x");
        }

        @Test
        @DisplayName("an empty original is all additions")
        void emptyBefore() {
            assertThat(changed(diff(List.of(), List.of("a", "b"))))
                    .containsExactly("+ a", "+ b");
        }

        @Test
        @DisplayName("an empty result is all removals")
        void emptyAfter() {
            assertThat(changed(diff(List.of("a", "b"), List.of())))
                    .containsExactly("- a", "- b");
        }
    }

    @Nested
    @DisplayName("what a reader sees around a change")
    class Context {

        @Test
        @DisplayName("unchanged lines surround each hunk, so a change has a place")
        void contextIsIncluded() {
            final List<String> before = lines("a", "b", "c", "d", "e", "f", "g", "h", "i");
            final List<String> after = new ArrayList<>(before);
            after.set(4, "CHANGED");

            final List<String> diff = diff(before, after);

            assertThat(diff).anyMatch(line -> line.startsWith("@@ "));
            assertThat(diff).contains("  b", "  c", "  d", "- e", "+ CHANGED", "  f", "  g", "  h");
            assertThat(diff)
                    .as("three lines either side is enough to place a change and short enough to "
                            + "read; the far ends of the file are not context, they are noise")
                    .doesNotContain("  a", "  i");
        }

        @Test
        @DisplayName("the hunk header counts from the whole file, not from the trimmed region")
        void hunkHeadersAreAbsolute() {
            final List<String> before = lines("a", "b", "c", "d", "e", "f", "g", "h");
            final List<String> after = new ArrayList<>(before);
            after.set(6, "CHANGED");

            assertThat(diff(before, after))
                    .filteredOn(line -> line.startsWith("@@ "))
                    .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                    .as("a header that named lines nobody can find in either file would be worse "
                            + "than no header")
                    .startsWith("@@ -4,");
        }
    }

    @Nested
    @DisplayName("comparing by a key rather than by the line")
    class Normalised {

        @Test
        @DisplayName("lines that differ only in what the key ignores compare equal")
        void theKeyDecides() {
            assertThat(diff(List.of("0: iload_1"), List.of("2: iload_1"),
                    line -> line.replaceAll("^\\d+: ", "")))
                    .as("this is the whole reason the differ takes a key: without it, inserting "
                            + "anything renames every line after it")
                    .isEmpty();
        }

        @Test
        @DisplayName("and the output still shows the real lines, not the keys")
        void theOutputIsUnnormalised() {
            assertThat(changed(TextDiff.unified(List.of("0: iload_1"), List.of("2: iload_2"),
                    line -> line.replaceAll("^\\d+: ", ""))))
                    .as("a diff that printed its own comparison keys would show a reader something "
                            + "that is in neither file")
                    .containsExactlyInAnyOrder("- 0: iload_1", "+ 2: iload_2");
        }
    }

    @Nested
    @DisplayName("the refusal")
    class TooLarge {

        @Test
        @DisplayName("two texts with nothing in common are refused rather than allocated for")
        void enormousPairsAreRefused() {
            final List<String> before = new ArrayList<>();
            final List<String> after = new ArrayList<>();
            for (int i = 0; i < 2100; i++) {
                before.add("before " + i);
                after.add("after " + i);
            }

            assertThat(diff(before, after))
                    .as("2100 by 2100 is over four million cells, which is sixteen megabytes for "
                            + "a diff nobody would read; a debugging aid must not be the thing "
                            + "that runs a build out of memory")
                    .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                    .contains("differ too widely")
                    .contains("both class files were written");
        }

        @Test
        @DisplayName("but two large texts that mostly agree are diffed normally")
        void theTrimSavesTheCommonCase() {
            final List<String> before = new ArrayList<>();
            for (int i = 0; i < 5000; i++) {
                before.add("line " + i);
            }
            final List<String> after = new ArrayList<>(before);
            after.add(2500, "INSERTED");

            assertThat(changed(diff(before, after)))
                    .as("prefix and suffix trimming is what keeps the quadratic table small, and "
                            + "a woven class is exactly this shape: enormous and locally changed")
                    .containsExactly("+ INSERTED");
        }
    }

    // -------------------------------------------------------------------------------------

    private static List<String> diff(final List<String> before, final List<String> after) {
        return diff(before, after, Function.identity());
    }

    private static List<String> diff(final List<String> before, final List<String> after,
                                     final Function<String, String> normalise) {
        return TextDiff.unified(before, after, normalise);
    }

    private static List<String> changed(final List<String> diff) {
        return diff.stream()
                .filter(line -> line.startsWith("+ ") || line.startsWith("- "))
                .toList();
    }

    private static List<String> lines(final String... values) {
        return new ArrayList<>(List.of(values));
    }
}
