package de.splatgames.aether.weaver.idea.inspection;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class ExtensionQuickFixTest extends BasePlatformTestCase {

    private static final String EXTENSION = """
            package de.splatgames.aether.weaver.api.experimental;

            public @interface Extension {
                Class<?> value() default void.class;
            }
            """;

    private static final String RECEIVER = """
            package de.splatgames.aether.weaver.api.experimental;

            import java.lang.annotation.ElementType;
            import java.lang.annotation.Target;

            @Target({ElementType.PARAMETER, ElementType.METHOD, ElementType.FIELD})
            public @interface Receiver {
                Class<?> value() default void.class;
            }
            """;

    private static final String TARGET = """
            package fixture;

            public class Greeting {
                public String greet() { return "hello"; }
            }
            """;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/experimental/Extension.java", EXTENSION);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/experimental/Receiver.java", RECEIVER);
        myFixture.addFileToProject("fixture/Greeting.java", TARGET);
        myFixture.enableInspections(new ExtensionDeclarationInspection());
    }

    public void testANonFinalHolderIsMadeFinal() {
        extension("""
                @Extension
                public class Strings {
                    public static String shout(@Receiver Greeting self) { return self.greet(); }
                }
                """);

        applyFix("Declare the extension class final");

        assertTrue(text(), text().contains("public final class Strings"));
        assertSilent();
    }

    public void testANonStaticMethodIsMadeStatic() {
        extension("""
                @Extension
                public final class Strings {
                    public String shout(@Receiver Greeting self) { return self.greet(); }
                }
                """);

        applyFix("Declare the contributed method static");

        assertTrue(text(), text().contains("public static String shout"));
        assertSilent();
    }

    public void testAMissingReceiverIsMarked() {
        extension("""
                @Extension
                public final class Strings {
                    public static String shout(Greeting self) { return self.greet(); }
                }
                """);

        applyFix("Mark the first parameter @Receiver");

        assertTrue(text(), text().contains("@Receiver Greeting self"));
        assertSilent();
    }

    public void testAPrimitiveFirstParameterIsOfferedNoFix() {
        extension("""
                @Extension
                public final class Strings {
                    public static int twice(int self) { return self * 2; }
                }
                """);

        assertFalse("annotating an int trades AW1302 for AW1304 and leaves the author exactly "
                        + "where they were, one fix poorer",
                offers("Mark the first parameter @Receiver"));
    }

    public void testAMisplacedReceiverIsMovedFirst() {
        extension("""
                @Extension
                public final class Strings {
                    public static String shout(int times, @Receiver Greeting self) {
                        return self.greet();
                    }
                }
                """);

        applyFix("Move the @Receiver parameter first");

        assertTrue("the annotation has to travel with the parameter; leaving it behind would turn "
                        + "AW1303 into AW1302: " + text(),
                text().contains("(@Receiver Greeting self, int times)"));
        assertSilent();
    }

    // --- the harness ---------------------------------------------------------------------------

    private void extension(final String body) {
        myFixture.configureByText("Strings.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.experimental.Extension;
                import de.splatgames.aether.weaver.api.experimental.Receiver;

                %s
                """.formatted(body));
    }

    private void applyFix(final String family) {
        for (final IntentionAction fix : myFixture.getAllQuickFixes()) {
            if (family.equals(fix.getFamilyName())) {
                myFixture.launchAction(fix);
                return;
            }
        }
        fail("no fix named '" + family + "' was offered; an inspection whose remedy is only a "
                + "sentence in a message is a remedy nobody applies");
    }

    private boolean offers(final String family) {
        for (final IntentionAction fix : myFixture.getAllQuickFixes()) {
            if (family.equals(fix.getFamilyName())) {
                return true;
            }
        }
        return false;
    }

    private void assertSilent() {
        for (final com.intellij.codeInsight.daemon.impl.HighlightInfo info
                : myFixture.doHighlighting()) {
            final String description = info.getDescription();
            if (description != null && description.startsWith("AW")) {
                fail("the fix left " + description + " standing:\n" + text());
            }
        }
    }

    private String text() {
        return myFixture.getFile().getText();
    }
}
