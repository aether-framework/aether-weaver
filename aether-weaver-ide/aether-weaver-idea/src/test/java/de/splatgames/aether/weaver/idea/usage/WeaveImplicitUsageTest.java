package de.splatgames.aether.weaver.idea.usage;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInspection.deadCode.UnusedDeclarationInspectionBase;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.ArrayList;
import java.util.List;

public class WeaveImplicitUsageTest extends BasePlatformTestCase {

    private static final String WEAVE = """
            package de.splatgames.aether.weaver.api;

            public @interface Weave {
                Class<?>[] value() default {};
            }
            """;

    private static final String INJECT = """
            package de.splatgames.aether.weaver.api;

            public @interface Inject {
                String method();
            }
            """;

    private static final String REDIRECT = """
            package de.splatgames.aether.weaver.api;

            public @interface Redirect {
                String method();
            }
            """;

    private static final String SHADOW = """
            package de.splatgames.aether.weaver.api;

            public @interface Shadow {
                String value() default "";
            }
            """;

    private static final String ACCESSOR = """
            package de.splatgames.aether.weaver.api;

            public @interface Accessor {
                String value() default "";
            }
            """;

    private static final String INVOKER = """
            package de.splatgames.aether.weaver.api;

            public @interface Invoker {
                String value() default "";
            }
            """;

    private static final String LEDGER = """
            package fixture;

            public class Ledger {
                public int balance() { return 0; }
            }
            """;

    private static final String TARGET = """
            package fixture;

            public class Target {
                private Ledger ledger = new Ledger();
                public void close() { }
            }
            """;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Weave.java", WEAVE);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Inject.java", INJECT);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Redirect.java", REDIRECT);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Shadow.java", SHADOW);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Accessor.java", ACCESSOR);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Invoker.java", INVOKER);
        myFixture.addFileToProject("fixture/Ledger.java", LEDGER);
        myFixture.addFileToProject("fixture/Target.java", TARGET);
        myFixture.enableInspections(new UnusedDeclarationInspectionBase(true));
    }

    public void testTheWeaveClassIsNotDead() {
        assertNoWarningAbout("Audit", weave("""
                @Inject(method = "close")
                void onClose() { }
                """));
    }

    public void testAnInjectHandlerIsNotDead() {
        assertNoWarningAbout("onClose", weave("""
                @Inject(method = "close")
                void onClose() { }
                """));
    }

    public void testARedirectHandlerIsNotDead() {
        assertNoWarningAbout("onClose", weave("""
                @Redirect(method = "close")
                void onClose() { }
                """));
    }

    public void testAnAccessorIsNotDead() {
        assertNoWarningAbout("getLedger", weave("""
                @Accessor
                Ledger getLedger() { throw new IllegalStateException("accessor"); }
                """));
    }

    public void testAnInvokerIsNotDead() {
        assertNoWarningAbout("callClose", weave("""
                @Invoker
                void callClose() { throw new IllegalStateException("invoker"); }
                """));
    }

    public void testAHandlerParameterIsNotDead() {
        assertNoWarningAbout("amount", weave("""
                @Inject(method = "close")
                void onClose(Ledger amount) { }
                """));
    }

    public void testAShadowFieldIsNotUnassigned() {
        assertNoWarningAbout("ledger", weave("""
                @Shadow private Ledger ledger;

                @Inject(method = "close")
                void onClose() { int balance = this.ledger.balance(); }
                """));
    }

    public void testAShadowFieldWrittenAndNeverReadIsNotDead() {
        assertNoWarningAbout("ledger", weave("""
                @Shadow private Ledger ledger;

                @Inject(method = "close")
                void onClose() { this.ledger = new Ledger(); }
                """));
    }

    public void testAnOrdinaryUnusedMethodIsStillReported() {
        assertWarningAbout("reallyUnused", weave("""
                @Inject(method = "close")
                void onClose() { }

                private void reallyUnused() { }
                """));
    }

    public void testAnUncalledShadowMethodIsStillReported() {
        assertWarningAbout("flush", weave("""
                @Shadow private void flush() { throw new IllegalStateException("shadow"); }

                @Inject(method = "close")
                void onClose() { }
                """));
    }

    public void testAnEntirelyUnreferencedShadowFieldIsStillReported() {
        assertWarningAbout("ledger", weave("""
                @Shadow private Ledger ledger;

                @Inject(method = "close")
                void onClose() { }
                """));
    }

    public void testTheSameAnnotationsOutsideAWeaveAreNotClaimed() {
        myFixture.configureByText("NotAWeave.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.Inject;

                public final class NotAWeave {
                    @Inject(method = "close")
                    private void onClose() { }
                }
                """);

        assertWarningAbout("onClose", warnings());
    }

    private List<String> weave(final String body) {
        myFixture.configureByText("Audit.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.Accessor;
                import de.splatgames.aether.weaver.api.Inject;
                import de.splatgames.aether.weaver.api.Invoker;
                import de.splatgames.aether.weaver.api.Redirect;
                import de.splatgames.aether.weaver.api.Shadow;
                import de.splatgames.aether.weaver.api.Weave;

                @Weave(Target.class)
                public final class Audit {
                %s
                }
                """.formatted(body.indent(4)));
        return warnings();
    }

    private List<String> warnings() {
        final List<String> found = new ArrayList<>();
        for (final HighlightInfo info : myFixture.doHighlighting()) {
            if (info.getSeverity() == HighlightSeverity.WARNING && info.getDescription() != null) {
                found.add(info.getDescription());
            }
        }
        return found;
    }

    private static void assertNoWarningAbout(final String name, final List<String> warnings) {
        for (final String warning : warnings) {
            assertFalse("'" + name + "' is used by the framework, and grey text is how an IDE says "
                            + "delete this: " + warnings,
                    warning.contains("'" + name + "'") || warning.contains("'" + name + "("));
        }
    }

    private static void assertWarningAbout(final String name, final List<String> warnings) {
        for (final String warning : warnings) {
            if (warning.contains("'" + name + "'") || warning.contains("'" + name + "(")) {
                return;
            }
        }
        fail("nothing was reported about '" + name + "', so a real unused declaration went "
                + "unmentioned — which the user cannot notice: " + warnings);
    }
}
