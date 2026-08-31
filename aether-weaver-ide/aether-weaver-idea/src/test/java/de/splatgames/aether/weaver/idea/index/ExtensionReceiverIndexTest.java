package de.splatgames.aether.weaver.idea.index;

import com.intellij.openapi.application.ReadAction;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.ArrayList;
import java.util.List;

public class ExtensionReceiverIndexTest extends BasePlatformTestCase {

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

            @Target(ElementType.PARAMETER)
            public @interface Receiver {
            }
            """;

    private static final String WEAVE = """
            package de.splatgames.aether.weaver.api;

            public @interface Weave {
                Class<?>[] value() default {};
                String[] targets() default {};
                Kind kind() default Kind.INSTANCE;

                enum Kind { INSTANCE, STATIC }
            }
            """;

    private static final String WEAVE_CLASS = """
            package fixture;

            import de.splatgames.aether.weaver.api.Weave;

            @Weave(Greeting.class)
            public final class GreetingWeave {
            }
            """;

    private static final String TARGET = """
            package fixture;

            public class Greeting {
                public String greet() { return "hello"; }
            }
            """;

    private static final String HOLDER = """
            package fixture;

            import de.splatgames.aether.weaver.api.experimental.Extension;
            import de.splatgames.aether.weaver.api.experimental.Receiver;

            @Extension
            public final class Strings {
                public static String shout(@Receiver Greeting self, int times) {
                    return self.greet();
                }
            }
            """;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/experimental/Extension.java", EXTENSION);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/experimental/Receiver.java", RECEIVER);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Weave.java", WEAVE);
        myFixture.addFileToProject("fixture/Greeting.java", TARGET);
        myFixture.addFileToProject("fixture/GreetingWeave.java", WEAVE_CLASS);
    }

    public void testTheIndexAnswersWithAnUnsavedDocumentOpen() {
        // configureByText leaves an in-memory document behind, which is exactly the state that
        // makes the platform run indexers inside a query. Adding the file with addFileToProject
        // instead would index it up front and prove nothing.
        myFixture.configureByText("Strings.java", HOLDER);

        final List<String> holders = ReadAction.compute(() -> {
            final List<String> found = new ArrayList<>();
            for (final PsiClass holder
                    : ExtensionReceiverIndex.contributingTo(receiver())) {
                found.add(holder.getName());
            }
            return found;
        });

        assertEquals("the extension is in the open document and nowhere else, so finding it is the "
                        + "same operation that used to re-enter the indexer", List.of("Strings"),
                holders);
    }

    public void testTheReceiverGainsTheMethodWithAnUnsavedDocumentOpen() {
        myFixture.configureByText("Strings.java", HOLDER);

        final List<String> methods = ReadAction.compute(() -> {
            final List<String> names = new ArrayList<>();
            for (final PsiMethod method : receiver().getMethods()) {
                names.add(method.getName());
            }
            return names;
        });

        assertTrue("this is the whole path in one call: getMethods() runs augmentation, which "
                        + "queries the index, which flushes the open document: " + methods,
                methods.contains("shout"));
    }

    public void testAReceiverNothingExtendsIsAnsweredEmpty() {
        assertEquals(List.of(),
                ReadAction.compute(() -> ExtensionReceiverIndex.contributingTo(receiver())));
    }

    private PsiClass receiver() {
        final PsiClass found = JavaPsiFacade.getInstance(getProject())
                .findClass("fixture.Greeting", GlobalSearchScope.allScope(getProject()));
        assertNotNull(found);
        return found;
    }
}
