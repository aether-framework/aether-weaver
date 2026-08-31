package de.splatgames.aether.weaver.idea.completion;

import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementPresentation;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.ArrayList;
import java.util.List;

public class ContributedMethodCompletionTest extends BasePlatformTestCase {

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

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/experimental/Extension.java", EXTENSION);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/experimental/Receiver.java", RECEIVER);
        myFixture.addFileToProject("fixture/Greeting.java", TARGET);
        myFixture.addFileToProject("fixture/Strings.java", HOLDER);
    }

    public void testAContributedMethodSaysWhereItComesFrom() {
        final String shout = renderingOf("shout");

        assertNotNull("the method must still be offered; marking it is the smaller half of this",
                shout);
        assertTrue("a row indistinguishable from BigDecimal's own members hides that this one is "
                        + "declared elsewhere and needs the weaver in the build: " + shout,
                shout.contains("extension in Strings"));
    }

    public void testTheReceiversOwnMembersAreUntouched() {
        final String greet = renderingOf("greet");

        assertNotNull("this contributor stands in front of every Java completion in the IDE; if it "
                + "dropped results, this is what would notice", greet);
        assertFalse("greet is declared by the class in front of it and there is nothing to say "
                + "about it: " + greet, greet.contains("extension in"));
    }

    private String renderingOf(final String name) {
        myFixture.configureByText("Caller.java", """
                package fixture;

                public final class Caller {
                    static void call(Greeting greeting) {
                        greeting.<caret>
                    }
                }
                """);
        myFixture.completeBasic();

        final LookupElement[] offered = myFixture.getLookupElements();
        assertNotNull("nothing was offered at all", offered);
        final List<String> rows = new ArrayList<>();
        for (final LookupElement element : offered) {
            final LookupElementPresentation presentation =
                    LookupElementPresentation.renderElement(element);
            if (name.equals(presentation.getItemText())) {
                rows.add(presentation.getItemText() + presentation.getTailText());
            }
        }
        return rows.isEmpty() ? null : rows.getFirst();
    }
}
