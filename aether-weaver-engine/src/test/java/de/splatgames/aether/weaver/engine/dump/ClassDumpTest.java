package de.splatgames.aether.weaver.engine.dump;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class ClassDumpTest {

    private final List<Diagnostic> reported = new ArrayList<>();

    @Nested
    @DisplayName("the three files")
    class Files3 {

        @Test
        @DisplayName("original, woven and diff are all written, under the class's package")
        void allThreeAreWritten(@TempDir final Path work) throws Exception {
            new ClassDump(work).write("fx/Target", original(), woven(), reported::add);

            assertThat(work.resolve("fx/Target.original.class")).exists();
            assertThat(work.resolve("fx/Target.woven.class")).exists();
            assertThat(work.resolve("fx/Target.diff.txt")).exists();
            assertThat(reported).isEmpty();
        }

        @Test
        @DisplayName("the class files are the bytes, unchanged")
        void theBytesAreTheBytes(@TempDir final Path work) throws Exception {
            new ClassDump(work).write("fx/Target", original(), woven(), reported::add);

            assertThat(Files.readAllBytes(work.resolve("fx/Target.original.class")))
                    .isEqualTo(original());
            assertThat(Files.readAllBytes(work.resolve("fx/Target.woven.class")))
                    .isEqualTo(woven());
        }

        @Test
        @DisplayName("the diff names both sides at the top")
        void theDiffIsLabelled(@TempDir final Path work) throws Exception {
            new ClassDump(work).write("fx/Target", original(), woven(), reported::add);

            assertThat(diffOf(work))
                    .startsWith("--- fx/Target  (original)", "+++ fx/Target  (woven)");
        }
    }

    @Nested
    @DisplayName("the diff is readable, which is the whole point")
    class Readable {

        @Test
        @DisplayName("two inserted instructions produce two added lines and nothing removed")
        void onlyTheInsertionShows(@TempDir final Path work) throws Exception {
            new ClassDump(work).write("fx/Target", original(), woven(), reported::add);

            final List<String> hunks = diffOf(work).stream()
                    .filter(line -> line.startsWith("+ ") || line.startsWith("- "))
                    .toList();

            assertThat(hunks)
                    .as("the insertion shifts every later offset; a diff that reported those "
                            + "as changes would report the whole method and be read by nobody")
                    .hasSize(2);
            assertThat(hunks).allSatisfy(line -> assertThat(line).startsWith("+ "));
            assertThat(hunks.get(0)).contains("iconst_0");
            assertThat(hunks.get(1)).contains("pop");
        }

        @Test
        @DisplayName("counter-probe: without normalisation the same change is unreadable")
        void normalisationIsWhatMakesItShort() {
            final List<String> before = Disassembly.of(write(original())).orElseThrow();
            final List<String> after = Disassembly.of(write(woven())).orElseThrow();

            final int normalised = changedLines(
                    TextDiff.unified(before, after, Disassembly::key));
            final int raw = changedLines(TextDiff.unified(before, after, Function.identity()));

            assertThat(normalised).isEqualTo(2);
            assertThat(raw)
                    .as("every instruction's offset moved by two, and the constant-pool indices "
                            + "moved too; this is the diff the feature exists to avoid")
                    .isGreaterThan(normalised);
        }

        @Test
        @DisplayName("the diff says what it ignored, so nobody wonders where the offsets went")
        void theDiffExplainsItself(@TempDir final Path work) throws Exception {
            new ClassDump(work).write("fx/Target", original(), woven(), reported::add);

            assertThat(String.join("\n", diffOf(work)))
                    .contains("offsets and constant-pool indices are ignored when comparing");
        }

        @Test
        @DisplayName("two identical classes produce a diff that says so")
        void nothingChanged(@TempDir final Path work) throws Exception {
            new ClassDump(work).write("fx/Target", original(), original(), reported::add);

            assertThat(String.join("\n", diffOf(work)))
                    .as("a stamped-but-otherwise-untouched class is a real outcome, and saying "
                            + "\"no difference\" is more useful than an empty file")
                    .contains("no difference");
        }
    }

    @Nested
    @DisplayName("a dump never costs the application anything")
    class Harmless {

        @Test
        @DisplayName("a class name that would escape the directory is refused, not written")
        void traversalIsRefused(@TempDir final Path work) throws Exception {
            new ClassDump(work.resolve("inside")).write("../../escaped", original(), woven(),
                    reported::add);

            assertThat(work.resolve("../../escaped.woven.class").normalize()).doesNotExist();
            assertThat(reported)
                    .as("a class name comes from a class file, and a class file can say anything")
                    .hasSize(1);
            assertThat(reported.getFirst().format()).contains("outside the dump directory");
        }

        @Test
        @DisplayName("an unwritable directory is reported rather than thrown")
        void failuresAreReported(@TempDir final Path work) throws Exception {
            // A regular file where the directory should be: creating the tree underneath it fails.
            final Path blocked = work.resolve("blocked");
            Files.writeString(blocked, "not a directory");

            new ClassDump(blocked).write("fx/Target", original(), woven(), reported::add);

            assertThat(reported)
                    .as("this runs inside class loading in two of the three drivers; a dump that "
                            + "could take an application down would be a worse bug than any it "
                            + "helps find")
                    .hasSize(1);
            assertThat(reported.getFirst().format()).contains("could not dump fx.Target");
        }
    }

    @Nested
    @DisplayName("normalising one line")
    class Normalisation {

        @Test
        @DisplayName("the offset column goes, and so does the alignment it was padding")
        void offsetsAreDropped() {
            assertThat(Disassembly.key("        12: iload_1")).isEqualTo("iload_1");
            assertThat(Disassembly.key("         0: iload_1"))
                    .as("two instructions at different offsets must compare equal, or an "
                            + "insertion renames every line after it")
                    .isEqualTo(Disassembly.key("       120: iload_1"));
        }

        @Test
        @DisplayName("constant-pool indices go, and the comment that explains them stays")
        void poolIndicesAreDropped() {
            assertThat(Disassembly.key("  4: invokestatic  #21   // Method fx/Trace.say:()V"))
                    .as("the comment carries the meaning the index stands for, so nothing is lost")
                    .contains("// Method fx/Trace.say:()V")
                    .doesNotContain("#21");
        }

        @Test
        @DisplayName("branch targets stay, because masking them could hide a real change")
        void branchTargetsSurvive() {
            assertThat(Disassembly.key("       7: ifeq          19"))
                    .as("ifeq 12 becoming ifeq 20 after an insertion is noise, but ifeq becoming "
                            + "ifne, or a branch retargeted around a block, is exactly what a "
                            + "reader is looking for — and no rule masks one without risking the "
                            + "other")
                    .contains("19");
        }
    }

    // -------------------------------------------------------------------------------------

    private static List<String> diffOf(final Path work) throws Exception {
        return Files.readAllLines(work.resolve("fx/Target.diff.txt"));
    }

    private static int changedLines(final List<String> diff) {
        return (int) diff.stream()
                .filter(line -> line.startsWith("+ ") || line.startsWith("- "))
                .count();
    }

    private static Path write(final byte[] bytes) {
        try {
            final Path file = java.nio.file.Files.createTempFile("aether-dump-test", ".class");
            file.toFile().deleteOnExit();
            Files.write(file, bytes);
            return file;
        } catch (final Exception failed) {
            throw new IllegalStateException(failed);
        }
    }

    private static byte[] original() {
        return build(code -> code
                .bipush(7).istore(1)
                .bipush(11).istore(2)
                .iload(1).iload(2).iadd().ireturn());
    }

    private static byte[] woven() {
        return build(code -> code
                .iconst_0().pop()
                .bipush(7).istore(1)
                .bipush(11).istore(2)
                .iload(1).iload(2).iadd().ireturn());
    }

    private static byte[] build(final Consumer<CodeBuilder> body) {
        return ClassFile.of().build(ClassDesc.of("fx.Target"), builder -> builder
                .withMethodBody("work", MethodTypeDesc.of(ConstantDescs.CD_int),
                        ClassFile.ACC_PUBLIC, body::accept));
    }
}
