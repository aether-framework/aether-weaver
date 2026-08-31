package de.splatgames.aether.weaver.testkit;

import de.splatgames.aether.weaver.api.diagnostic.Severity;
import fixture.AuditWeave;
import fixture.Target;
import fixture.TotalWeave;
import fixture.Trace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.List;

import static de.splatgames.aether.weaver.testkit.WovenAssert.assertThatWoven;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WeaverExtension.class)
@de.splatgames.aether.weaver.testkit.Weaves(AuditWeave.class)
class TestkitTest {

    @BeforeEach
    void forgetPreviousMarks() {
        Trace.clear();
    }

    @Nested
    @DisplayName("in-memory weaving")
    class InMemory {

        @Test
        @DisplayName("the woven class satisfies every invariant and runs the handler")
        void theWholeChain(final Weaving weaving) {
            final WeaveResult result = weaving.weave(Target.class);

            assertThatWoven(result)
                    .wasWoven()
                    .satisfiesEveryInvariant()
                    .preservesUntargetedMethods("charge")
                    .loadsAndRuns(type -> {
                        final Object instance = type.getDeclaredConstructor().newInstance();
                        type.getMethod("charge", int.class).invoke(instance, 5);
                    });

            assertEquals(List.of("charge"), Trace.RECORD,
                    "the handler must have run inside the woven copy, and the mark must reach the "
                            + "test through a class the throwaway loader did not redefine");
        }

        @Test
        @DisplayName("a class no weave names comes back untouched")
        void untargetedClassesAreNotWoven(final Weaving weaving) {
            assertThatWoven(weaving.weave(Trace.class)).wasNotWoven();
        }

        @Test
        @DisplayName("the extension gives each test its own weaver")
        void oneFacadePerTest(final Weaving weaving) {
            assertEquals(0, weaving.weaver().statistics().classesSeen(),
                    "a facade shared across a class would carry one test's counters into the next");
        }

        @Test
        @DisplayName("a method-level @Weaves replaces the class's set")
        @de.splatgames.aether.weaver.testkit.Weaves(TotalWeave.class)
        void methodLevelWeavesWins(final Weaving weaving) {
            assertThatWoven(weaving.weave(Target.class))
                    .satisfiesEveryInvariant()
                    .preservesUntargetedMethods("total");

            assertEquals(1, weaving.weaver().plan().size(),
                    "replacing rather than adding is what lets a test say 'only this weave'");
        }

        @Test
        @DisplayName("a weaver over no weaves is refused rather than planning nothing")
        void emptyWeaveSetsAreRefused() {
            assertThrows(IllegalArgumentException.class, Weaving::of);
        }

        @Test
        @DisplayName("a class with no class file cannot be woven, and says so")
        void generatedClassesAreRefused(final Weaving weaving) {
            assertThrows(IllegalStateException.class, () -> weaving.weave(int[].class));
        }
    }

    @Nested
    @DisplayName("the invariants, in both directions")
    class Invariants {

        @Test
        @DisplayName("I1 verifies: a broken class file is refused")
        void verificationCatchesBrokenBytes() {
            final AssertionError error = assertThrows(AssertionError.class,
                    () -> assertThatWoven(resultOf(broken())).verifies());

            assertTrue(error.getMessage().contains("does not verify"), error.getMessage());
        }

        @Test
        @DisplayName("I8 the JVM refuses what verification passed")
        void definitionCatchesWhatVerifyDoesNot() {
            final byte[] bytes = simple("fx.Real");
            assertTrue(ClassFile.of().verify(bytes).isEmpty(),
                    "the fixture must verify cleanly, or this test proves nothing about the gap "
                            + "between verification and definition");

            // The class file says fx.Real; the result claims it is fx.Other. Verification has no
            // opinion about that, and the JVM refuses it at definition — which is the whole reason
            // isAcceptedByTheJvm exists next to verifies.
            final AssertionError error = assertThrows(AssertionError.class,
                    () -> assertThatWoven(new WeaveResult("fx/Other", bytes, bytes, bytes,
                            List.of())).isAcceptedByTheJvm());

            assertTrue(error.getMessage().contains("refused"), error.getMessage());
        }

        @Test
        @DisplayName("I4 isDeterministic: two passes that disagree are caught")
        void determinismCatchesDrift() {
            final AssertionError error = assertThrows(AssertionError.class,
                    () -> assertThatWoven(new WeaveResult("fx/Real", simple("fx.Real"),
                            simple("fx.Real"), broken(), List.of())).isDeterministic());

            assertTrue(error.getMessage().contains("different bytes"), error.getMessage());
        }

        @Test
        @DisplayName("I5 preservesUntargetedMethods: a change nobody named is caught")
        void untargetedChangesAreCaught(final Weaving weaving) {
            final WeaveResult result = weaving.weave(Target.class);

            final AssertionError error = assertThrows(AssertionError.class,
                    () -> assertThatWoven(result).preservesUntargetedMethods());

            assertTrue(error.getMessage().contains("charge"), error.getMessage());
            assertTrue(error.getMessage().contains("no weave named it"), error.getMessage());
        }

        @Test
        @DisplayName("I6 preservesClassVersion: a raised version is caught")
        void versionChangesAreCaught() {
            final byte[] original = simple("fx.Real");
            final byte[] raised = ClassFile.of().transformClass(
                    ClassFile.of().parse(original), java.lang.classfile.ClassTransform.ACCEPT_ALL);
            final byte[] bumped = raised.clone();
            // The major version lives at bytes 6 and 7 of every class file. Lowered rather than
            // raised: the ClassFile API refuses to parse a version newer than the JDK it runs on,
            // so raising it would fail before the assertion under test could.
            bumped[7] = (byte) (bumped[7] - 1);

            final AssertionError error = assertThrows(AssertionError.class,
                    () -> assertThatWoven(new WeaveResult("fx/Real", original, bumped, bumped,
                            List.of())).preservesClassVersion());

            assertTrue(error.getMessage().contains("class file version changed"),
                    error.getMessage());
        }

        @Test
        @DisplayName("I7 preservesDebugInfo: a stripped LineNumberTable is caught")
        void debugInfoLossIsCaught(final Weaving weaving) {
            final byte[] original = weaving.weave(Target.class).original();
            // Two things had to be right here, and both were measured rather than assumed.
            // The options belong to the PARSER, not to the writer — a dropping writer over a
            // normally-parsed model changes nothing. And the transform has to touch the bodies:
            // ClassTransform.ACCEPT_ALL takes the API's unchanged-method fast path, which copies
            // the original bytes verbatim and keeps every line number.
            final byte[] stripped = ClassFile.of().transformClass(
                    ClassFile.of(ClassFile.DebugElementsOption.DROP_DEBUG,
                                    ClassFile.LineNumbersOption.DROP_LINE_NUMBERS)
                            .parse(original),
                    java.lang.classfile.ClassTransform.transformingMethodBodies(
                            java.lang.classfile.CodeTransform.ACCEPT_ALL));

            final AssertionError error = assertThrows(AssertionError.class,
                    () -> assertThatWoven(new WeaveResult("x", original, stripped, stripped,
                            List.of())).preservesDebugInfo());

            assertTrue(error.getMessage().contains("lost its"), error.getMessage());
        }

        @Test
        @DisplayName("wasWoven and wasNotWoven each catch the other's case")
        void bothDirectionsOfWasWoven(final Weaving weaving) {
            final WeaveResult woven = weaving.weave(Target.class);
            final WeaveResult untouched = weaving.weave(Trace.class);

            assertThrows(AssertionError.class, () -> assertThatWoven(woven).wasNotWoven());
            assertThrows(AssertionError.class, () -> assertThatWoven(untouched).wasWoven());
        }

        @Test
        @DisplayName("hasMethod and hasField name what is actually there when they fail")
        void memberAssertions(final Weaving weaving) {
            final WeaveResult result = weaving.weave(Target.class);

            assertThatWoven(result).hasMethod("charge", "(I)I").hasField("balance", "I");

            final AssertionError error = assertThrows(AssertionError.class,
                    () -> assertThatWoven(result).hasMethod("absent", "()V"));
            assertTrue(error.getMessage().contains("charge(I)I"),
                    "a failure that did not list what is there sends the reader to javap");
        }

        @Test
        @DisplayName("reportsNothing and reports read the diagnostics the result carries")
        void diagnosticAssertions(final Weaving weaving) {
            final WeaveResult result = weaving.weave(Target.class);

            assertThatWoven(result).reportsNothing(Severity.WARNING);
            assertThrows(AssertionError.class, () -> assertThatWoven(result).reports("AW1043"));
            assertTrue(result.codes(Severity.DEBUG).isEmpty());
        }
    }

    @Nested
    @DisplayName("golden files")
    class Golden {

        @Test
        @DisplayName("a missing golden file is written and the run still fails")
        void firstRunFails(@org.junit.jupiter.api.io.TempDir final java.nio.file.Path work,
                           final Weaving weaving) {
            final GoldenFiles golden = GoldenFiles.in(work);
            final WeaveResult result = weaving.weave(Target.class);

            final AssertionError error = assertThrows(AssertionError.class,
                    () -> golden.verify("target", result));

            assertTrue(error.getMessage().contains("did not exist"), error.getMessage());
            assertTrue(java.nio.file.Files.exists(work.resolve("target.class")));
            assertTrue(java.nio.file.Files.exists(work.resolve("target.txt")),
                    "the .txt rendering is the point: it is what makes the next change to this "
                            + "fixture reviewable instead of a binary blob in a pull request");
        }

        @Test
        @DisplayName("the second run passes against what the first wrote")
        void secondRunPasses(@org.junit.jupiter.api.io.TempDir final java.nio.file.Path work,
                             final Weaving weaving) {
            final GoldenFiles golden = GoldenFiles.in(work);
            final WeaveResult result = weaving.weave(Target.class);
            assertThrows(AssertionError.class, () -> golden.verify("target", result));

            golden.verify("target", result);
        }

        @Test
        @DisplayName("a mismatch fails with a readable javap diff, not a byte count")
        void mismatchesAreReadable(@org.junit.jupiter.api.io.TempDir final java.nio.file.Path work,
                                   final Weaving weaving) throws Exception {
            final GoldenFiles golden = GoldenFiles.in(work);
            assertThrows(AssertionError.class,
                    () -> golden.verify("target", weaving.weave(Target.class).original()));

            final AssertionError error = assertThrows(AssertionError.class,
                    () -> golden.verify("target", weaving.weave(Target.class)));

            assertTrue(error.getMessage().contains("invokevirtual")
                            || error.getMessage().contains("onCharge"),
                    "the failure must show the bytecode that changed: " + error.getMessage());
            assertTrue(error.getMessage().contains("golden.update"),
                    "and it must say how to accept the change deliberately");
            assertTrue(java.nio.file.Files.exists(work.resolve("target.actual.txt")),
                    "the rejected rendering is written too, so a reviewer can read both");
        }

        @Test
        @DisplayName("the rendering is the same disassembly a class dump writes")
        void oneDisassembler(@org.junit.jupiter.api.io.TempDir final java.nio.file.Path work,
                             final Weaving weaving) throws Exception {
            final GoldenFiles golden = GoldenFiles.in(work);
            assertThrows(AssertionError.class,
                    () -> golden.verify("target", weaving.weave(Target.class)));

            final String rendering = java.nio.file.Files.readString(work.resolve("target.txt"));
            assertTrue(rendering.contains("public int charge(int)"), rendering);
        }

        @Test
        @DisplayName("a fixture name that would leave the directory is refused")
        void traversalIsRefused(@org.junit.jupiter.api.io.TempDir final java.nio.file.Path work) {
            assertThrows(IllegalArgumentException.class,
                    () -> GoldenFiles.in(work).verify("../escaped", new byte[]{1}));
        }

        @Test
        @DisplayName("this run is not an updating one, or every assertion above would pass")
        void notUpdating() {
            assertFalse(GoldenFiles.updating(),
                    "-Dgolden.update=true makes every golden assertion pass, so a build that had "
                            + "it set would prove nothing");
        }
    }

    // -------------------------------------------------------------------------------------

    private static WeaveResult resultOf(final byte[] bytes) {
        return new WeaveResult("fx/Real", bytes, bytes, bytes, List.of());
    }

    private static byte[] simple(final String binaryName) {
        return ClassFile.of().build(ClassDesc.of(binaryName), builder -> builder
                .withMethodBody("work", MethodTypeDesc.of(ConstantDescs.CD_void),
                        ClassFile.ACC_PUBLIC, code -> code.return_()));
    }

    private static byte[] broken() {
        final byte[] bytes = ClassFile.of().build(ClassDesc.of("fx.Real"), builder -> builder
                .withMethodBody("work", MethodTypeDesc.of(ConstantDescs.CD_void),
                        ClassFile.ACC_PUBLIC, code -> code.aconst_null().areturn()));
        assertNotNull(bytes);
        return bytes;
    }
}
