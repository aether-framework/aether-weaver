package de.splatgames.aether.weaver.idea.toolwindow;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import de.splatgames.aether.weaver.api.manifest.WeaveManifest;

import java.util.ArrayList;
import java.util.List;

public class ExtensionsModelTest extends BasePlatformTestCase {

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

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/experimental/Extension.java", EXTENSION);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/experimental/Receiver.java", RECEIVER);
        myFixture.addFileToProject("fixture/Greeting.java", TARGET);
    }

    public void testAHolderIsListedWithItsContributions() {
        myFixture.addFileToProject("fixture/Strings.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.experimental.Extension;
                import de.splatgames.aether.weaver.api.experimental.Receiver;

                @Extension
                public final class Strings {
                    public static String shout(@Receiver Greeting self, int times) {
                        return self.greet();
                    }
                }
                """);

        final List<ExtensionsModel.Holder> holders = ExtensionsModel.of(getProject());
        assertEquals("" + names(holders), 1, holders.size());
        assertEquals("fixture.Strings", holders.getFirst().name());

        final List<ExtensionsModel.Contribution> contributions =
                holders.getFirst().contributions();
        assertEquals("" + contributions, 1, contributions.size());
        assertEquals("fixture.Greeting", contributions.getFirst().receiver());
        assertEquals("the receiver is written before the dot, so a row that listed it as an "
                        + "argument would describe a call nobody can write",
                "shout(int)", contributions.getFirst().signature());
        assertEquals(WeaveManifest.Extension.Kind.INSTANCE, contributions.getFirst().kind());
    }

    public void testAStaticContributionKeepsItsParameters() {
        myFixture.addFileToProject("fixture/Strings.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.experimental.Extension;
                import de.splatgames.aether.weaver.api.experimental.Receiver;

                @Extension
                public final class Strings {
                    @Receiver(Greeting.class)
                    public static Greeting of(String name) { return null; }
                }
                """);

        final ExtensionsModel.Contribution contribution =
                ExtensionsModel.of(getProject()).getFirst().contributions().getFirst();

        assertEquals("of(String)", contribution.signature());
        assertEquals("fixture.Greeting", contribution.receiver());
        assertEquals(WeaveManifest.Extension.Kind.STATIC, contribution.kind());
    }

    public void testAConstantIsListedByName() {
        myFixture.addFileToProject("fixture/Strings.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.experimental.Extension;
                import de.splatgames.aether.weaver.api.experimental.Receiver;

                @Extension
                public final class Strings {
                    @Receiver(Greeting.class)
                    public static final String CENT = "0.01";
                }
                """);

        final ExtensionsModel.Contribution contribution =
                ExtensionsModel.of(getProject()).getFirst().contributions().getFirst();

        assertEquals("a row reading CENTLjava/lang/String; is not something anybody wants to read",
                "CENT", contribution.signature());
        assertEquals("fixture.Greeting", contribution.receiver());
        assertEquals(WeaveManifest.Extension.Kind.CONSTANT, contribution.kind());
    }

    public void testAHelperIsNotListed() {
        myFixture.addFileToProject("fixture/Strings.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.experimental.Extension;
                import de.splatgames.aether.weaver.api.experimental.Receiver;

                @Extension
                public final class Strings {
                    public static String shout(@Receiver Greeting self) { return self.greet(); }

                    private static String helper(String text) { return text; }
                }
                """);

        assertEquals("a window listing everything a holder declares would say the project "
                        + "contributes a method it does not", 1,
                ExtensionsModel.of(getProject()).getFirst().contributions().size());
    }

    public void testAPlainClassIsNotListed() {
        myFixture.addFileToProject("fixture/Strings.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.experimental.Receiver;

                public final class Strings {
                    public static String shout(@Receiver Greeting self) { return self.greet(); }
                }
                """);

        assertEquals("if this were non-empty every assertion above would prove nothing",
                List.of(), ExtensionsModel.of(getProject()));
    }

    public void testAProjectWithoutExtensionsIsEmpty() {
        assertEquals(List.of(), ExtensionsModel.of(getProject()));
    }

    private static List<String> names(final List<ExtensionsModel.Holder> holders) {
        final List<String> names = new ArrayList<>();
        for (final ExtensionsModel.Holder holder : holders) {
            names.add(holder.name());
        }
        return names;
    }
}
