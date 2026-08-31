package de.splatgames.aether.weaver.engine.verify;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class StructuralCheckTest {

    private static final ClassDesc SUBJECT = ClassDesc.of("structural.Subject");

    private final List<Diagnostic> reported = new ArrayList<>();

    @Test
    @DisplayName("a well-formed class is reported as nothing")
    void aCleanClassIsClean() {
        assertThat(StructuralCheck.of(clean())).isEmpty();
    }

    @Test
    @DisplayName("an exception range that protects nothing — which ClassFile.verify accepts")
    void anEmptyProtectedRangeIsCaught() {
        final byte[] woven = emptyProtectedRange();

        assertThat(ClassFile.of().verify(woven))
            .as("the premise of this whole class: if this ever starts reporting, the "
                    + "structural check has become redundant and should be reconsidered rather "
                    + "than kept out of habit")
            .isEmpty();
        assertThat(StructuralCheck.of(woven))
            .singleElement()
            .satisfies(problem -> {
                assertThat(problem.describe()).contains("protects nothing");
            });
    }

    @Test
    @DisplayName("AW4004 — the verifier refuses the class and hands back the original")
    void theVerifierReportsAndKeepsTheOriginal() {
        final Verifier verifier = new Verifier(VerificationPolicy.REPORT, this.reported::add);
        final byte[] original = clean();
        final byte[] woven = emptyProtectedRange();

        final byte[] result = verifier.check("structural/Subject", original, woven);

        assertThat(codes()).containsExactly("AW4004");
        assertThat(result)
            .as("a class the JVM would refuse must not be written; the target as it arrived still "
                    + "works, which a ClassFormatError at the user's class loading would not")
            .isEqualTo(original);
    }

    // --- fixtures -------------------------------------------------------------------------

    private List<String> codes() {
        return this.reported.stream().map(diagnostic -> diagnostic.code().code()).toList();
    }

    private static byte[] clean() {
        return ClassFile.of().build(SUBJECT, builder -> builder
                .withFlags(ClassFile.ACC_PUBLIC)
                .withMethodBody("run", MethodTypeDesc.of(ConstantDescs.CD_void),
                        ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, CodeBuilder::return_));
    }

    private static byte[] emptyProtectedRange() {
        return ClassFile.of().build(SUBJECT, builder -> builder
                .withFlags(ClassFile.ACC_PUBLIC)
                .withMethodBody("run", MethodTypeDesc.of(ConstantDescs.CD_void),
                        ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> {
                            final Label here = code.newBoundLabel();
                            final Label handler = code.newLabel();
                            code.exceptionCatch(here, here, handler,
                                    Optional.of(code.constantPool()
                                            .classEntry(ClassDesc.of("java.lang.Throwable"))))
                                .return_()
                                .labelBinding(handler)
                                .pop()
                                .return_();
                        }));
    }

}
