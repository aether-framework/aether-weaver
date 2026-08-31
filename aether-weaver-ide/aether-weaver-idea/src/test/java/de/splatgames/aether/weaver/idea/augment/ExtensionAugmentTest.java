package de.splatgames.aether.weaver.idea.augment;

import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.ArrayList;
import java.util.List;

public class ExtensionAugmentTest extends BasePlatformTestCase {

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

    private static final String SUPERTYPE = """
            package fixture;

            public interface Named {
                String name();
            }
            """;

    private static final String TARGET = """
            package fixture;

            public final class Greeting implements Named {
                public String name() { return "world"; }
                public String greet() { return "hello"; }
                public String repeat(String separator) { return separator; }
            }
            """;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/experimental/Extension.java", EXTENSION);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/experimental/Receiver.java", RECEIVER);
        myFixture.addFileToProject("fixture/Named.java", SUPERTYPE);
        myFixture.addFileToProject("fixture/Greeting.java", TARGET);
    }

    public void testAContributedMethodAppearsOnTheReceiver() {
        extension("""
                public static String shout(@Receiver Greeting self, int times) {
                    return self.greet();
                }
                """);

        assertTrue("without this, writing greeting.shout(3) is red — code the build compiles and "
                        + "runs, reported as an error: " + methodsOfReceiver(),
                methodsOfReceiver().contains("shout"));
    }

    public void testTheReceiverParameterIsNotOfferedAsAnArgument() {
        extension("""
                public static String shout(@Receiver Greeting self, int times) {
                    return self.greet();
                }
                """);

        final PsiMethod shout = augmentedMethod("shout");
        assertNotNull(shout);
        assertEquals("greeting.shout(3) passes one argument; offering two would make every correct "
                        + "call look wrong", 1,
                shout.getParameterList().getParametersCount());
        assertEquals("int", shout.getParameterList().getParameters()[0].getType()
                .getPresentableText());
    }

    public void testTheContributedMethodIsNotStatic() {
        extension("""
                public static String shout(@Receiver Greeting self) { return self.greet(); }
                """);

        final PsiMethod shout = augmentedMethod("shout");
        assertNotNull(shout);
        assertFalse("marking it static would make greeting.shout() report a static call on an "
                        + "instance — the very warning this exists to remove",
                shout.hasModifierProperty(PsiModifier.STATIC));
    }

    public void testAStaticContributionAppearsOnTheType() {
        extension("""
                @Receiver(Greeting.class)
                public static Greeting of(String name) { return new Greeting(); }
                """);

        final PsiMethod of = augmentedMethod("of");
        assertNotNull("Greeting.of(\"x\") is written on the type, and without this it is red", of);
        assertTrue("the call site is `Greeting.of(...)`; an instance method there would be reported "
                        + "as referenced from a static context",
                of.hasModifierProperty(PsiModifier.STATIC));
        assertEquals("no parameter is the receiver, so none is dropped", 1,
                of.getParameterList().getParametersCount());
    }

    public void testAReceiverNamedTwiceContributesNothing() {
        extension("""
                @Receiver(Greeting.class)
                public static String both(@Receiver Greeting self) { return self.greet(); }
                """);

        assertFalse("AW1313 refuses this declaration, so offering it would put a method on a class "
                        + "that the build is about to refuse to produce: " + methodsOfReceiver(),
                methodsOfReceiver().contains("both"));
    }

    public void testASupertypeContributionIsVisibleOnTheSubtype() {
        extension("""
                public static String initial(@Receiver Named self) {
                    return self.name().substring(0, 1);
                }
                """);

        final PsiClass receiver = findClass("fixture.Greeting");
        assertNotNull(receiver);
        assertTrue("the weaver resolves greeting.initial() through Greeting's hierarchy now, so an "
                        + "editor that did not would report an error on code that compiles and runs",
                receiver.findMethodsByName("initial", true).length > 0);
        assertEquals("and it stays off Greeting's own members, because that is where it is not",
                0, receiver.findMethodsByName("initial", false).length);
    }

    public void testItCarriesTheImplementationsDocumentation() {
        extension("""
                /**
                 * Shouts a greeting.
                 *
                 * @param self the receiver; never null
                 * @return the shouted greeting
                 */
                public static String shout(@Receiver Greeting self) { return self.greet(); }
                """);

        final PsiMethod shout = augmentedMethod("shout");
        assertNotNull(shout);
        assertNotNull("hovering a contributed method must not show a bare signature when its author "
                + "wrote a paragraph one file away", shout.getDocComment());
        assertTrue(shout.getDocComment().getText(), shout.getDocComment().getText()
                .contains("Shouts a greeting"));
    }

    public void testDeprecationTravelsWithIt() {
        extension("""
                /**
                 * Shouts a greeting.
                 *
                 * @deprecated use greet() instead
                 */
                public static String shout(@Receiver Greeting self) { return self.greet(); }
                """);

        final PsiMethod shout = augmentedMethod("shout");
        assertNotNull(shout);
        assertTrue("the stub carries the deprecation to javac, so an editor that did not show it "
                + "would disagree with the build about a warning", shout.isDeprecated());
    }

    public void testAContributedConstantAppearsOnTheType() {
        extension("""
                @Receiver(Greeting.class)
                public static final String CENT = "0.01";
                """);

        final PsiClass receiver = findClass("fixture.Greeting");
        assertNotNull(receiver);
        final PsiField cent = receiver.findFieldByName("CENT", false);
        assertNotNull("Greeting.CENT is what the build compiles and runs; without this it is red",
                cent);
        assertTrue("a constant is read off the type, so an instance field there would be reported "
                + "as referenced from a static context", cent.hasModifierProperty(PsiModifier.STATIC));
        assertTrue("and it is final, which is the only shape the processor accepts",
                cent.hasModifierProperty(PsiModifier.FINAL));
    }

    public void testANonFinalFieldContributesNothing() {
        extension("""
                @Receiver(Greeting.class)
                public static String NAME = "x";
                """);

        final PsiClass receiver = findClass("fixture.Greeting");
        assertNotNull(receiver);
        assertNull("AW1314 refuses this declaration, so offering it would put a constant on a class "
                + "the build is about to refuse to produce", receiver.findFieldByName("NAME", false));
    }

    public void testAClassLevelReceiverContributesEveryMethod() {
        myFixture.addFileToProject("fixture/Strings.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.experimental.Extension;

                @Extension(Greeting.class)
                public final class Strings {
                    public static String shout(Greeting self, int times) { return self.greet(); }
                    public static String quiet(Greeting self) { return self.greet(); }
                    private static String helper(int n) { return ""; }
                }
                """);

        final List<String> names = methodsOfReceiver();
        assertTrue("the whole point of naming the receiver once is that the methods below it need "
                + "no annotation: " + names, names.contains("shout") && names.contains("quiet"));
        assertFalse("a private helper is still a helper: " + names, names.contains("helper"));
    }

    public void testItNavigatesToTheImplementation() {
        extension("""
                public static String shout(@Receiver Greeting self) { return self.greet(); }
                """);

        final PsiMethod shout = augmentedMethod("shout");
        assertNotNull(shout);
        assertTrue("a method nobody can navigate to is a method nobody can read",
                shout.getNavigationElement() instanceof PsiMethod navigated
                        && "Strings".equals(navigated.getContainingClass().getName()));
    }

    public void testAPrivateHelperContributesNothing() {
        extension("""
                private static String helper(Greeting self) { return self.greet(); }
                """);

        assertFalse("" + methodsOfReceiver(), methodsOfReceiver().contains("helper"));
    }

    public void testAMethodWithoutAReceiverContributesNothing() {
        extension("""
                public static String loose(Greeting self) { return self.greet(); }
                """);

        assertFalse("" + methodsOfReceiver(), methodsOfReceiver().contains("loose"));
    }

    public void testAReceiverThatIsNotFirstContributesNothing() {
        extension("""
                public static String late(int times, @Receiver Greeting self) {
                    return self.greet();
                }
                """);

        assertFalse("the build refuses this declaration, so offering it would show a method that "
                        + "will never exist: " + methodsOfReceiver(),
                methodsOfReceiver().contains("late"));
    }

    public void testACollidingSignatureContributesNothing() {
        extension("""
                public static String greet(@Receiver Greeting self) { return "shadowed"; }
                """);

        assertEquals("Greeting.greet() is real, so javac resolves to it and this extension is dead "
                        + "code; offering it would put a duplicate-member error on correct source",
                1, occurrencesOf("greet"));
    }

    public void testAnOverloadWithADifferentParameterIsStillContributed() {
        extension("""
                public static String repeat(@Receiver Greeting self, int times) {
                    return self.greet();
                }
                """);

        // Greeting.repeat(String) exists; repeat(int) does not. The processor's AW1305 compares
        // name AND descriptor, so these coexist — and a collision check that compared names alone
        // would silently hide a perfectly valid extension.
        assertEquals("comparing names instead of signatures would have suppressed this",
                2, occurrencesOf("repeat"));
    }

    public void testTheHolderIsNotAugmentedWithItsOwnContributions() {
        extension("""
                public static String shout(@Receiver Greeting self) { return self.greet(); }
                """);

        final PsiClass holder = findClass("fixture.Strings");
        assertNotNull(holder);
        int shouts = 0;
        for (final PsiMethod method : holder.getMethods()) {
            if ("shout".equals(method.getName())) {
                shouts++;
            }
        }
        assertEquals("it declares the method already; adding it back would report a duplicate",
                1, shouts);
    }

    // --- the harness ---------------------------------------------------------------------------

    private void extension(final String members) {
        myFixture.addFileToProject("fixture/Strings.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.experimental.Extension;
                import de.splatgames.aether.weaver.api.experimental.Receiver;

                @Extension
                public final class Strings {
                %s
                }
                """.formatted(members.indent(4)));
    }

    private List<String> methodsOfReceiver() {
        final PsiClass receiver = findClass("fixture.Greeting");
        assertNotNull(receiver);
        final List<String> names = new ArrayList<>();
        for (final PsiMethod method : receiver.getMethods()) {
            names.add(method.getName());
        }
        return names;
    }

    private int occurrencesOf(final String name) {
        int found = 0;
        for (final String each : methodsOfReceiver()) {
            if (name.equals(each)) {
                found++;
            }
        }
        return found;
    }

    private PsiMethod augmentedMethod(final String name) {
        final PsiClass receiver = findClass("fixture.Greeting");
        assertNotNull(receiver);
        for (final PsiMethod method : receiver.getMethods()) {
            if (name.equals(method.getName())) {
                return method;
            }
        }
        return null;
    }

    private PsiClass findClass(final String qualified) {
        return JavaPsiFacade.getInstance(getProject())
                .findClass(qualified, GlobalSearchScope.allScope(getProject()));
    }
}
