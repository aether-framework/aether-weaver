package de.splatgames.aether.weaver.idea.intention;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class WeaveIntentionsTest extends BasePlatformTestCase {

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
                public void settle(Money amount) { }
                public void settle(Gateway other) { }
                private void flush(boolean force) { }
            }
            """;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Weave.java", WEAVE);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Inject.java", INJECT);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Shadow.java", SHADOW);
        myFixture.addFileToProject("fixture/Money.java", MONEY);
        myFixture.addFileToProject("fixture/Gateway.java", TARGET);
    }

    public void testASelectorIsQualified() {
        weaveWith("charge(Money)");

        myFixture.launchAction(offered("Qualify the selector's parameter types"));

        assertTrue(myFixture.getFile().getText(),
                myFixture.getFile().getText().contains("\"charge(fixture.Money)\""));
    }

    public void testAQualifiedSelectorIsNotOffered() {
        weaveWith("charge(fixture.Money)");

        assertNull("offering a no-op trains the reader to ignore the intention list",
                myFixture.getAvailableIntention("Qualify the selector's parameter types"));
    }

    public void testABareNameIsNotQualified() {
        weaveWith("charge");

        assertNull("'charge' names every overload on purpose; turning it into a signature is a "
                        + "decision the author makes, not a formatting change",
                myFixture.getAvailableIntention("Qualify the selector's parameter types"));
    }

    public void testAnOverloadIsSeparatedByItsType() {
        weaveWith("settle(Money)");

        assertNotNull("this one does resolve — settle(Money) picks exactly one of the two overloads",
                myFixture.getAvailableIntention("Qualify the selector's parameter types"));
    }

    public void testAShadowFieldIsDeclared() {
        weave("", """
                @Inject(method = "charge(fixture.Money)")
                void onCharge() {
                    System.out.println(this.bal<caret>ance);
                }
                """);

        myFixture.launchAction(offered("Declare @Shadow for the target's member"));

        final String text = myFixture.getFile().getText();
        assertTrue("the type is copied from the target, not guessed: " + text,
                text.contains("@Shadow") && text.contains("Money balance"));
    }

    public void testAShadowDropsFinal() {
        weave("", """
                @Inject(method = "charge(fixture.Money)")
                void onCharge() {
                    System.out.println(this.open<caret>ing);
                }
                """);

        myFixture.launchAction(offered("Declare @Shadow for the target's member"));

        final String text = myFixture.getFile().getText();
        assertFalse("a final field with no initialiser in a class that may not declare a "
                        + "constructor (AW1081) is never definitely assigned: " + text,
                text.contains("final Money opening"));
        assertTrue(text, text.contains("Money opening"));
    }

    public void testAShadowMethodIsDeclared() {
        weave("", """
                @Inject(method = "charge(fixture.Money)")
                void onCharge() {
                    this.flu<caret>sh(true);
                }
                """);

        myFixture.launchAction(offered("Declare @Shadow for the target's member"));

        final String text = myFixture.getFile().getText();
        assertTrue("the parameter list must match the target's: " + text,
                text.contains("void flush(boolean force)"));
    }

    public void testAShadowIsNotOfferedForAMemberTheTargetLacks() {
        weave("", """
                @Inject(method = "charge(fixture.Money)")
                void onCharge() {
                    System.out.println(this.nowh<caret>ere);
                }
                """);

        assertNull("trading a compiler error the reader understands for a framework error they do "
                        + "not is not a fix",
                myFixture.getAvailableIntention("Declare @Shadow for the target's member"));
    }

    public void testAShadowIsNotOfferedInAStaticWeave() {
        weave("value = Gateway.class, kind = Weave.Kind.STATIC", """
                @Inject(method = "charge(fixture.Money)")
                static void onCharge() {
                    System.out.println(bal<caret>ance);
                }
                """);

        assertNull("that is AW1090, and the remedy there is @Accessor rather than @Shadow",
                myFixture.getAvailableIntention("Declare @Shadow for the target's member"));
    }

    public void testAWeaveIsCreatedForAClass() {
        myFixture.configureByText("Payment.java", """
                package fixture;

                public class Pay<caret>ment {
                    public void run() { }
                }
                """);

        myFixture.launchAction(offered("Create a weave for this class"));

        // Read from the directory rather than through JavaPsiFacade. A file created inside the
        // write action this intention runs in is on disk immediately but not necessarily in the
        // stub index yet, and asserting through findClass would be testing the fixture's indexing
        // schedule rather than whether the weave was written.
        final PsiFile created = myFixture.getFile().getContainingDirectory()
                .findFile("PaymentWeave.java");
        assertNotNull("the file is created in the target's own package, where a weave can reach "
                + "package-private members", created);
        final String text = created.getText();
        assertTrue("a weave must be final (AW1008): " + text, text.contains("final class"));
        assertTrue("the class literal is checked by the compiler and followed by Rename, which the "
                + "string form is not (AW1009): " + text, text.contains("Payment.class"));
    }

    public void testAWeaveIsNotOfferedForAWeave() {
        myFixture.configureByText("Audit.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.Weave;

                @Weave(Gateway.class)
                public final class Au<caret>dit { }
                """);

        assertNull("a weave targeting a weave is AW1087",
                myFixture.getAvailableIntention("Create a weave for this class"));
    }

    public void testAWeaveIsNotOfferedForAnInterface() {
        myFixture.configureByText("Sink.java", """
                package fixture;

                public interface Si<caret>nk {
                    void accept(Money amount);
                }
                """);

        assertNull(myFixture.getAvailableIntention("Create a weave for this class"));
    }

    private void weaveWith(final String selector) {
        weave("", """
                @Inject(method = "%s<caret>")
                void onCharge() { }
                """.formatted(selector));
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

    private IntentionAction offered(final String name) {
        final IntentionAction found = myFixture.getAvailableIntention(name);
        assertNotNull("'" + name + "' was not offered at the caret", found);
        return found;
    }
}
