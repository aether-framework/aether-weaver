package de.splatgames.aether.weaver.idea.intention;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

public class CreateExtensionMethodIntentionTest extends BasePlatformTestCase {

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

            public final class Greeting {
                public String greet() { return "hello"; }
            }
            """;

    private static final String HOLDER = """
            package fixture;

            import de.splatgames.aether.weaver.api.experimental.Extension;
            import de.splatgames.aether.weaver.api.experimental.Receiver;

            @Extension
            public final class Strings {
                public static String shout(@Receiver Greeting self) { return self.greet(); }
            }
            """;

    private static final String FAMILY = "Create extension method";

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/experimental/Extension.java", EXTENSION);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/experimental/Receiver.java", RECEIVER);
        myFixture.addFileToProject("fixture/Greeting.java", TARGET);
        myFixture.addFileToProject("fixture/Strings.java", HOLDER);
    }

    public void testItWritesTheMethodIntoTheHolder() {
        final IntentionAction offered = offeredFor("""
                static String call(Greeting greeting) {
                    return greeting.as<caret>Money("EUR");
                }
                """);
        assertNotNull("Java's own Create method offers to add it to Greeting; when Greeting is a "
                + "class file that cannot be done, and this is the offer that can", offered);
        assertTrue(offered.getText(), offered.getText().contains("'asMoney' in Strings"));

        myFixture.launchAction(offered);

        final String holder = holderText();
        assertTrue("the receiver goes in front, which is where the rewrite passes it: " + holder,
                generated("asMoney").contains("@Receiver Greeting self"));
        // The type is written qualified here and would be shortened in a real project: this
        // fixture has no JDK, so java.lang.String does not resolve and nothing can shorten a
        // reference it cannot resolve.
        assertTrue("the argument's type is known at the call site and must not be guessed: " + holder,
                generated("asMoney").contains("String "));
        assertTrue("a generated body that silently did nothing would be the worst of both: " + holder,
                holder.contains("UnsupportedOperationException"));
    }

    public void testACallOnTheTypeProducesAStaticContribution() {
        final IntentionAction offered = offeredFor("""
                static Greeting call() {
                    return Greeting.of<caret>("world");
                }
                """);
        assertNotNull(offered);
        myFixture.launchAction(offered);

        final String holder = holderText();
        assertTrue("the call site names the type, so the receiver is named on the method: " + holder,
                holder.contains("@Receiver(Greeting.class)"));
        // The generated declaration only, not the whole file: the fixture's own shout() already
        // has a @Receiver parameter, and a substring search over the file passes on that.
        assertFalse("a static contribution has no receiver among its parameters: "
                + generated("of"), generated("of").contains("@Receiver"));
    }

    public void testAStatementCallReturnsVoid() {
        final IntentionAction offered = offeredFor("""
                static void call(Greeting greeting) {
                    greeting.an<caret>nounce();
                }
                """);
        assertNotNull(offered);
        myFixture.launchAction(offered);

        assertTrue(holderText(), holderText().contains("public static void announce("));
    }

    public void testAResolvingCallIsNotOffered() {
        assertNull("Java would be offering to create a method that exists, and so would this",
                offeredFor("""
                        static String call(Greeting greeting) {
                            return greeting.gre<caret>et();
                        }
                        """));
    }

    public void testAReceiverWithoutAHolderIsNotOffered() {
        myFixture.addFileToProject("fixture/Other.java", """
                package fixture;

                public final class Other {
                }
                """);

        assertNull("where a new holder goes is a decision about a project's layout, and an "
                        + "intention answering it would be guessing under a suggestive name",
                offeredFor("""
                        static String call(Other other) {
                            return other.as<caret>Money("EUR");
                        }
                        """));
    }

    public void testTwoHoldersAreNotChosenBetween() {
        myFixture.addFileToProject("fixture/MoreStrings.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.experimental.Extension;
                import de.splatgames.aether.weaver.api.experimental.Receiver;

                @Extension
                public final class MoreStrings {
                    public static String whisper(@Receiver Greeting self) { return self.greet(); }
                }
                """);

        assertNull("picking one would put somebody's code in a file they did not choose",
                offeredFor("""
                        static String call(Greeting greeting) {
                            return greeting.as<caret>Money("EUR");
                        }
                        """));
    }

    private IntentionAction offeredFor(final String body) {
        myFixture.configureByText("Caller.java", """
                package fixture;

                public final class Caller {
                %s
                }
                """.formatted(body.indent(4)));

        final List<IntentionAction> available = myFixture.getAvailableIntentions();
        for (final IntentionAction action : available) {
            if (FAMILY.equals(action.getFamilyName())) {
                return action;
            }
        }
        return null;
    }

    private String generated(final String name) {
        final String holder = holderText();
        final int at = holder.indexOf(name + '(');
        assertTrue("the holder does not declare " + name + ": " + holder, at >= 0);
        final int start = holder.lastIndexOf("public", at);
        return holder.substring(start < 0 ? at : start, holder.indexOf('{', at));
    }

    private String holderText() {
        return myFixture.getPsiManager()
                .findFile(myFixture.findFileInTempDir("fixture/Strings.java"))
                .getText();
    }
}
