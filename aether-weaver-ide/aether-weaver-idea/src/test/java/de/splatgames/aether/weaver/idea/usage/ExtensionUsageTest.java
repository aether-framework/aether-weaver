package de.splatgames.aether.weaver.idea.usage;

import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.usageView.UsageInfo;
import de.splatgames.aether.weaver.idea.psi.ExtensionDeclarations;

import java.util.Collection;

public class ExtensionUsageTest extends BasePlatformTestCase {

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

                @Receiver(Greeting.class)
                public static Greeting of(String name) { return new Greeting(); }

                private static String helper(String text) { return text; }
            }
            """;

    private static final String CALLER = """
            package fixture;

            public final class Caller {
                public static String call() { return Greeting.of("world").shout(); }
            }
            """;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/experimental/Extension.java", EXTENSION);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/experimental/Receiver.java", RECEIVER);
        myFixture.addFileToProject("fixture/Greeting.java", TARGET);
        myFixture.addFileToProject("fixture/Strings.java", HOLDER);
        myFixture.addFileToProject("fixture/Caller.java", CALLER);
    }

    public void testTheCallSiteIsAUsageOfTheImplementation() {
        final Collection<UsageInfo> usages = myFixture.findUsages(method("shout"));

        assertEquals("greeting.shout() is a call to this method, however little it looks like one; "
                        + "an IDE that reports none is telling somebody it is safe to delete: "
                        + usages, 1, usages.size());
    }

    public void testAStaticCallSiteIsAUsageToo() {
        assertEquals(1, myFixture.findUsages(method("of")).size());
    }

    public void testAContributedMethodIsAnImplicitUsage() {
        final ExtensionImplicitUsageProvider provider = new ExtensionImplicitUsageProvider();

        assertTrue("an extension published in a library has its call sites in other people's "
                        + "projects, and there is nothing in this one to find",
                provider.isImplicitUsage(method("shout")));
        assertTrue(provider.isImplicitUsage(method("of")));
        assertTrue("nothing names an extension class either",
                provider.isImplicitUsage(holder()));
    }

    public void testAPrivateHelperIsNotClaimed() {
        final PsiMethod helper = method("helper");
        assertFalse("silencing this would cost the user the only warning they get about genuinely "
                        + "dead code, which an extension class accumulates like any other",
                new ExtensionImplicitUsageProvider().isImplicitUsage(helper));
        assertFalse("and it contributes nothing, which is the same question asked once",
                ExtensionDeclarations.contributes(helper));
    }

    private PsiMethod method(final String name) {
        final PsiMethod[] found = holder().findMethodsByName(name, false);
        assertEquals("the fixture declares exactly one " + name, 1, found.length);
        return found[0];
    }

    private PsiClass holder() {
        final PsiClass found = JavaPsiFacade.getInstance(getProject())
                .findClass("fixture.Strings", GlobalSearchScope.allScope(getProject()));
        assertNotNull(found);
        return found;
    }
}
