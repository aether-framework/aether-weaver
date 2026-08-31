package de.splatgames.aether.weaver.idea.inspection;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class WeaveQuickFixTest extends BasePlatformTestCase {

    private static final String WEAVE = """
            package de.splatgames.aether.weaver.api;

            public @interface Weave {
                Class<?>[] value() default {};
                String[] targets() default {};
                Kind kind() default Kind.INSTANCE;

                enum Kind { INSTANCE, STATIC }
            }
            """;

    private static final String INJECT = """
            package de.splatgames.aether.weaver.api;

            public @interface Inject {
                String method();
            }
            """;

    private static final String SHADOW = """
            package de.splatgames.aether.weaver.api;

            public @interface Shadow {
                String value() default "";
                boolean mutable() default false;
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

    private static final String MONEY = """
            package fixture;

            public class Money { }
            """;

    private static final String TARGET = """
            package fixture;

            public class Gateway {
                private Money balance;
                private final Money opening = null;
                public void charge(Money amount) { }
                private void flush(boolean force) { }
            }
            """;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Weave.java", WEAVE);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Inject.java", INJECT);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Shadow.java", SHADOW);
        // Without these two on the fixture's classpath the generated annotations cannot be
        // shortened or imported, and the assertions below would be checking a fully qualified
        // spelling no real project ever sees.
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Accessor.java", ACCESSOR);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Invoker.java", INVOKER);
        myFixture.addFileToProject("fixture/Money.java", MONEY);
        myFixture.addFileToProject("fixture/Gateway.java", TARGET);
    }

    public void testAPastedDescriptorGainsItsPrefix() {
        myFixture.enableInspections(new SelectorInspection());
        weave("", """
                @Inject(method = "charge(Lfixture/Money;)V")
                void onCharge() { }
                """);

        applyFix("Apply the selector parser's suggestion");

        assertTrue("the suggestion comes from SelectorSyntaxException, not from this plugin "
                        + "recognising the shape a second time: " + myFixture.getFile().getText(),
                myFixture.getFile().getText().contains("\"desc:charge(Lfixture/Money;)V\""));
    }

    public void testAShadowedFieldBecomesAnAccessorPair() {
        myFixture.enableInspections(new WeaveDeclarationInspection());
        weave("value = Gateway.class, kind = Weave.Kind.STATIC", """
                @Shadow
                private Money balance;
                """);

        applyFix("Replace @Shadow with a generated member");

        final String text = myFixture.getFile().getText();
        assertTrue("the reader is the half that is always legal: " + text,
                text.contains("@Accessor") && text.contains("getBalance()"));
        assertTrue("the field is not final on the target, so a writer is legal too: " + text,
                text.contains("setBalance("));
        assertFalse("the @Shadow it replaces has to go, or AW1090 stands: " + text,
                text.contains("@Shadow"));
    }

    public void testAFinalFieldGetsNoSetter() {
        myFixture.enableInspections(new WeaveDeclarationInspection());
        weave("value = Gateway.class, kind = Weave.Kind.STATIC", """
                @Shadow
                private Money opening;
                """);

        applyFix("Replace @Shadow with a generated member");

        final String text = myFixture.getFile().getText();
        assertTrue(text, text.contains("getOpening()"));
        assertFalse("writing a final field through an accessor is AW1097 — handing over the next "
                        + "diagnostic along with the fix for this one: " + text,
                text.contains("setOpening("));
    }

    public void testAShadowedMethodBecomesAnInvoker() {
        myFixture.enableInspections(new WeaveDeclarationInspection());
        weave("value = Gateway.class, kind = Weave.Kind.STATIC", """
                @Shadow
                private void flush(boolean force) { throw new AssertionError("shadow"); }
                """);

        applyFix("Replace @Shadow with a generated member");

        final String text = myFixture.getFile().getText();
        assertTrue("the target's name is inferred by stripping a call or invoke prefix, so no "
                        + "explicit value() is needed: " + text,
                text.contains("@Invoker") && text.contains("callFlush(boolean force)"));
    }

    private void weave(final String declaration, final String body) {
        final String targets = declaration.isEmpty() ? "Gateway.class" : declaration;
        myFixture.configureByText("Audit.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.Inject;
                import de.splatgames.aether.weaver.api.Shadow;
                import de.splatgames.aether.weaver.api.Weave;

                @Weave(%s)
                public final class Audit {
                %s
                }
                """.formatted(targets, body.indent(4)));
    }

    private void applyFix(final String family) {
        for (final IntentionAction fix : myFixture.getAllQuickFixes()) {
            if (family.equals(fix.getFamilyName())) {
                myFixture.launchAction(fix);
                return;
            }
        }
        fail("no fix named '" + family + "' was offered");
    }
}
