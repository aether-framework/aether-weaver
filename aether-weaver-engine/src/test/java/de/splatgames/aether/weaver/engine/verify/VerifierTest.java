package de.splatgames.aether.weaver.engine.verify;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.WeaveException;
import de.splatgames.aether.weaver.api.spi.DiagnosticListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class VerifierTest {

    private static final byte[] VALID = valid();

    private static final byte[] BROKEN = broken();

    private final List<Diagnostic> reported = new ArrayList<>();

    private final DiagnosticListener listener = this.reported::add;

    @Nested
    @DisplayName("the fixtures are what the tests claim")
    class Fixtures {

        @Test
        @DisplayName("one verifies and the other does not")
        void fixturesAreValidAndInvalid() {
            assertThat(ClassFile.of().verify(VALID))
                    .as("if this were non-empty the other tests would prove nothing")
                    .isEmpty();
            assertThat(ClassFile.of().verify(BROKEN))
                    .as("a mock would let the verifier pass a test it should fail")
                    .isNotEmpty();
        }
    }

    @Nested
    @DisplayName("STRICT")
    class Strict {

        @Test
        @DisplayName("a valid class passes through unchanged")
        void validPassesThrough() {
            final byte[] result = verifier(VerificationPolicy.STRICT)
                    .check("com/acme/Fixture", VALID, VALID);

            assertThat(result).isSameAs(VALID);
            assertThat(reported).isEmpty();
        }

        @Test
        @DisplayName("an invalid class throws, listing what the verifier said")
        void invalidThrows() {
            assertThatExceptionOfType(WeaveException.class)
                    .isThrownBy(() -> verifier(VerificationPolicy.STRICT)
                            .check("com/acme/Fixture", VALID, BROKEN))
                    .satisfies(thrown -> {
                        assertThat(thrown.diagnostics()).singleElement().satisfies(d -> {
                            assertThat(d.code().code()).isEqualTo("AW4001");
                            assertThat(d.details()).isNotEmpty();
                        });
                    });
        }

        @Test
        @DisplayName("it is the default")
        void strictIsTheDefault() {
            assertThat(VerificationPolicy.STRICT.isFatal()).isTrue();
            assertThat(VerificationPolicy.STRICT.verifies()).isTrue();
        }
    }

    @Nested
    @DisplayName("REPORT")
    class Report {

        @Test
        @DisplayName("an invalid class yields the ORIGINAL, never the broken one")
        void reportReturnsTheOriginal() {
            final byte[] result = verifier(VerificationPolicy.REPORT)
                    .check("com/acme/Fixture", VALID, BROKEN);

            assertThat(result)
                    .as("an invalid class does not fail where it was woven — it fails as a "
                            + "VerifyError at whatever unrelated point the JVM first links it, "
                            + "usually with no sign that weaving was involved")
                    .isSameAs(VALID)
                    .isNotSameAs(BROKEN);
            assertThat(ClassFile.of().verify(result)).isEmpty();
        }

        @Test
        @DisplayName("the failure is reported rather than thrown")
        void reportReports() {
            verifier(VerificationPolicy.REPORT).check("com/acme/Fixture", VALID, BROKEN);

            assertThat(reported).singleElement().satisfies(d -> {
                assertThat(d.code().code()).isEqualTo("AW4001");
                assertThat(d.message()).contains("com/acme/Fixture");
                assertThat(d.remedy()).isPresent();
            });
        }

        @Test
        @DisplayName("a valid class is still returned as the woven one")
        void validIsStillWoven() {
            assertThat(verifier(VerificationPolicy.REPORT).check("com/acme/F", VALID, VALID))
                    .isSameAs(VALID);
            assertThat(reported).isEmpty();
        }
    }

    @Nested
    @DisplayName("OFF")
    class Off {

        @Test
        @DisplayName("nothing is checked and the woven class is returned as-is")
        void offSkipsVerification() {
            final byte[] result = verifier(VerificationPolicy.OFF)
                    .check("com/acme/Fixture", VALID, BROKEN);

            assertThat(result)
                    .as("OFF exists so throughput measurements can separate weaving from "
                            + "verifying, and is documented as unsupported in production")
                    .isSameAs(BROKEN);
            assertThat(reported).isEmpty();
        }

        @Test
        @DisplayName("it is neither the default nor fatal")
        void offIsNotTheDefault() {
            assertThat(VerificationPolicy.OFF.verifies()).isFalse();
            assertThat(VerificationPolicy.OFF.isFatal()).isFalse();
        }
    }

    private Verifier verifier(final VerificationPolicy policy) {
        return new Verifier(policy, this.listener);
    }

    private static byte[] valid() {
        return ClassFile.of().build(ClassDesc.of("com.acme.Fixture"), builder -> builder
                .withMethodBody("work", MethodTypeDesc.of(ConstantDescs.CD_void),
                        ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                        code -> code.return_()));
    }

    private static byte[] broken() {
        return ClassFile.of(ClassFile.StackMapsOption.DROP_STACK_MAPS)
                .build(ClassDesc.of("com.acme.Fixture"), builder -> builder
                        .withMethodBody("work", MethodTypeDesc.of(ConstantDescs.CD_void),
                                ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                                code -> {
                                    final java.lang.classfile.Label target = code.newLabel();
                                    code.goto_(target).labelBinding(target).return_();
                                }));
    }
}
