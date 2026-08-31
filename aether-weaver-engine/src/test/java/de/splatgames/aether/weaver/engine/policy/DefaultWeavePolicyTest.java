package de.splatgames.aether.weaver.engine.policy;

import de.splatgames.aether.weaver.api.spi.WeavePolicy;
import de.splatgames.aether.weaver.api.spi.WeaveTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class DefaultWeavePolicyTest {

    private static final int MODERN = 69;

    @Nested
    @DisplayName("denials that cannot be overridden")
    class Absolute {

        @Test
        @DisplayName("java.* is refused even with every override set")
        void javaIsAlwaysRefused() {
            final WeavePolicy permissive = DefaultWeavePolicy.builder().allowSigned().build();

            assertThat(deny(permissive, "java/lang/String"))
                    .as("bootstrap ordering makes this unsupportable rather than merely risky: the "
                            + "classes load before any transformer can be installed")
                    .isNotNull()
                    .satisfies(d -> assertThat(d.code().code()).isEqualTo("AW3001"));
        }

        @Test
        @DisplayName("java.* cannot even be named as an override")
        void javaCannotBeReopened() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> DefaultWeavePolicy.builder().allowPackage("java.lang"))
                    .withMessageContaining("cannot be reopened");
        }

        @Test
        @DisplayName("Aether Weaver refuses to weave itself")
        void noSelfWeaving() {
            assertThat(deny(DefaultWeavePolicy.standard(),
                    "de/splatgames/aether/weaver/engine/Weaver"))
                    .as("a framework that can modify its own policy gate has no guarantees left, "
                            + "including the guarantee that it refuses to weave itself")
                    .isNotNull()
                    .satisfies(d -> assertThat(d.code().code()).isEqualTo("AW3003"));
        }

        @Test
        @DisplayName("a declared weave class is refused")
        void weaveClassesAreRefused() {
            final WeaveTarget weave = new WeaveTarget("com/acme/Audit", MODERN, false, true);

            assertThat(DefaultWeavePolicy.standard().decide(weave))
                    .isInstanceOfSatisfying(WeavePolicy.Decision.Deny.class,
                            d -> assertThat(d.code().code()).isEqualTo("AW1087"));
        }

        @Test
        @DisplayName("a class file older than 50 is refused")
        void ancientClassFilesAreRefused() {
            final WeaveTarget ancient = new WeaveTarget("com/acme/Legacy", 49, false, false);

            assertThat(DefaultWeavePolicy.standard().decide(ancient))
                    .isInstanceOfSatisfying(WeavePolicy.Decision.Deny.class,
                            d -> assertThat(d.code().code()).isEqualTo("AW2003"));
        }
    }

    @Nested
    @DisplayName("denials an override can reopen")
    class Overridable {

        @Test
        @DisplayName("javax, jdk, sun and com.sun are refused by default")
        void jdkPackagesAreRefused() {
            final WeavePolicy standard = DefaultWeavePolicy.standard();

            assertThat(deny(standard, "javax/sql/DataSource")).isNotNull();
            assertThat(deny(standard, "jdk/internal/misc/Unsafe")).isNotNull();
            assertThat(deny(standard, "sun/nio/ch/IOUtil")).isNotNull();
            assertThat(deny(standard, "com/sun/crypto/provider/AESCipher")).isNotNull();
        }

        @Test
        @DisplayName("an explicit package override reopens exactly that package")
        void overrideReopensOnePackage() {
            final WeavePolicy policy = DefaultWeavePolicy.builder()
                    .allowPackage("com.sun.crypto.provider")
                    .build();

            assertThat(policy.decide(target("com/sun/crypto/provider/AESCipher")).isAllowed())
                    .isTrue();
            assertThat(deny(policy, "com/sun/jndi/ldap/LdapCtx"))
                    .as("an override names one package, never a subtree — 'this package' and "
                            + "'everything under it' are different intentions and only one was "
                            + "stated")
                    .isNotNull();
        }

        @Test
        @DisplayName("a wildcard is refused at construction")
        void wildcardsAreRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> DefaultWeavePolicy.builder().allowPackage("com.sun.*"))
                    .withMessageContaining("never a wildcard");
        }

        @Test
        @DisplayName("signed code is refused by default and reopened globally")
        void signedCode() {
            final WeaveTarget signed = new WeaveTarget("com/acme/Signed", MODERN, true, false);

            assertThat(DefaultWeavePolicy.standard().decide(signed))
                    .isInstanceOfSatisfying(WeavePolicy.Decision.Deny.class,
                            d -> assertThat(d.code().code()).isEqualTo("AW3002"));
            assertThat(DefaultWeavePolicy.builder().allowSigned().build().decide(signed).isAllowed())
                    .isTrue();
        }

        @Test
        @DisplayName("overrides are visible, so an artefact can record them")
        void overridesAreEnumerable() {
            final DefaultWeavePolicy policy = DefaultWeavePolicy.builder()
                    .allowPackage("com.sun.crypto.provider")
                    .allowSigned()
                    .build();

            assertThat(policy.hasOverrides()).isTrue();
            assertThat(policy.allowedPackages()).containsExactly("com.sun.crypto.provider");
            assertThat(DefaultWeavePolicy.standard().hasOverrides()).isFalse();
        }
    }

    @Nested
    @DisplayName("ordinary classes and composition")
    class Composition {

        @Test
        @DisplayName("an ordinary application class is allowed")
        void applicationClassIsAllowed() {
            assertThat(DefaultWeavePolicy.standard().decide(target("com/acme/Session")).isAllowed())
                    .isTrue();
        }

        @Test
        @DisplayName("composition can only narrow")
        void compositionNarrows() {
            final WeavePolicy permissive = t -> WeavePolicy.Decision.allow();
            final WeavePolicy composed = DefaultWeavePolicy.standard().and(permissive);

            assertThat(deny(composed, "java/lang/String"))
                    .as("a plugin that could widen the denylist could disable the guard that stops "
                            + "it disabling guards")
                    .isNotNull();
        }

        @Test
        @DisplayName("a custom policy can add a denial the built-in one does not have")
        void compositionAddsDenials() {
            final WeavePolicy noVendor = t -> t.packageName().startsWith("com.vendor")
                    ? new WeavePolicy.Decision.Deny(
                            de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode
                                    .POLICY_DENIED_JDK_PACKAGE, "vendor code is off limits")
                    : WeavePolicy.Decision.allow();

            final WeavePolicy composed = DefaultWeavePolicy.standard().and(noVendor);

            assertThat(deny(composed, "com/vendor/Thing")).isNotNull();
            assertThat(composed.decide(target("com/acme/Thing")).isAllowed()).isTrue();
        }

        @Test
        @DisplayName("the built-in reason wins when both refuse")
        void builtInReasonWins() {
            final WeavePolicy alsoDenies = t -> new WeavePolicy.Decision.Deny(
                    de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode.POLICY_OVERRIDE_ACTIVE,
                    "some other reason");

            assertThat(deny(DefaultWeavePolicy.standard().and(alsoDenies), "java/lang/String"))
                    .as("the built-in reason is the one a user can act on")
                    .satisfies(d -> assertThat(d.code().code()).isEqualTo("AW3001"));
        }

        @Test
        @DisplayName("a denial must carry a reason")
        void denialsNeedReasons() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new WeavePolicy.Decision.Deny(
                            de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode
                                    .POLICY_DENIED_JDK_PACKAGE, "  "))
                    .withMessageContaining("reason");
        }
    }

    private static WeavePolicy.Decision.Deny deny(final WeavePolicy policy,
                                                  final String internalName) {
        final WeavePolicy.Decision decision = policy.decide(target(internalName));
        return decision instanceof WeavePolicy.Decision.Deny denied ? denied : null;
    }

    private static WeaveTarget target(final String internalName) {
        return new WeaveTarget(internalName, MODERN, false, false);
    }
}
