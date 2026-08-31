package de.splatgames.aether.weaver.idea.inspection;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.ArrayList;
import java.util.List;

public class ExtensionDeclarationInspectionTest extends BasePlatformTestCase {

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
                public String repeat(String separator) { return separator; }
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

    public void testACorrectExtensionIsSilent() {
        assertEquals("a rule that fired on every extension class would pass every other test in "
                        + "this file; this is the one that would not",
                List.of(), codesOf("""
                        @Extension
                        public final class Strings {
                            public static String shout(@Receiver Greeting self, int times) {
                                return self.greet();
                            }

                            private static String helper(String text) {
                                return text;
                            }
                        }
                        """));
    }

    public void testInheritedObjectMethodsAreNotReported() {
        // toString, equals, hashCode, wait and notify are public and mark no @Receiver. Reading
        // the holder's methods rather than its own declarations reports all of them.
        assertEquals("Object's methods inherit into every holder; reporting them would put five "
                        + "errors on a class whose source is entirely correct",
                List.of(), codesOf("""
                        @Extension
                        public final class Strings {
                            public static String shout(@Receiver Greeting self) {
                                return self.greet();
                            }
                        }
                        """));
    }

    public void testAPlainClassIsSilent() {
        assertEquals(List.of(), codesOf("""
                public final class Strings {
                    public static String shout(Greeting self) { return self.greet(); }
                }
                """));
    }

    public void testANonFinalHolderIsReported() {
        assertEquals(List.of("AW1300"), codesOf("""
                @Extension
                public class Strings {
                    public static String shout(@Receiver Greeting self) { return self.greet(); }
                }
                """));
    }

    public void testANonStaticMethodIsReported() {
        assertEquals(List.of("AW1301"), codesOf("""
                @Extension
                public final class Strings {
                    public String shout(@Receiver Greeting self) { return self.greet(); }
                }
                """));
    }

    public void testAMissingReceiverIsReported() {
        assertEquals(List.of("AW1302"), codesOf("""
                @Extension
                public final class Strings {
                    public static String shout(Greeting self) { return self.greet(); }
                }
                """));
    }

    public void testAReceiverThatIsNotFirstIsReported() {
        assertEquals(List.of("AW1303"), codesOf("""
                @Extension
                public final class Strings {
                    public static String shout(int times, @Receiver Greeting self) {
                        return self.greet();
                    }
                }
                """));
    }

    public void testAPrimitiveReceiverIsReported() {
        assertEquals(List.of("AW1304"), codesOf("""
                @Extension
                public final class Strings {
                    public static int twice(@Receiver int self) { return self * 2; }
                }
                """));
    }

    public void testACollisionIsReported() {
        assertEquals("Greeting.greet() is real, so javac resolves to it and this extension is "
                        + "dead code",
                List.of("AW1305"), codesOf("""
                        @Extension
                        public final class Strings {
                            public static String greet(@Receiver Greeting self) { return ""; }
                        }
                        """));
    }

    public void testAnOverloadIsNotACollision() {
        // Greeting.repeat(String) exists; repeat(int) does not. AW1305 is a check on name AND
        // descriptor, so comparing names alone would put an error on a declaration the build
        // accepts — which is the expensive direction for this inspection to be wrong in.
        assertEquals(List.of(), codesOf("""
                @Extension
                public final class Strings {
                    public static String repeat(@Receiver Greeting self, int times) {
                        return self.greet();
                    }
                }
                """));
    }

    public void testAGenericHolderIsReported() {
        assertEquals(List.of("AW1306"), codesOf("""
                @Extension
                public final class Strings<T> {
                }
                """));
    }

    public void testAHolderWithASupertypeIsReported() {
        assertEquals(List.of("AW1307"), codesOf("""
                @Extension
                public final class Strings implements Runnable {
                    public void run() { }
                }
                """));
    }

    public void testOverloadsInOneHolderAreNotDuplicates() {
        // This asserted AW1308 until the cross-check against the processor was run: the plugin
        // keyed duplicates on arity, the processor on the descriptor, and shout(int) and
        // shout(long) are two different calls. Reporting them put an error on a declaration the
        // build accepts — and no test in this file would ever have noticed, because it was written
        // from the same misunderstanding as the code.
        //
        // AW1308 stays implemented and mirrors the processor's own check. Neither can fire from a
        // single holder in valid Java: two methods with one erased signature do not compile. It
        // fires where it was meant to, in the engine's index, when two *holders* contribute the
        // same call.
        assertEquals(List.of(), codesOf("""
                @Extension
                public final class Strings {
                    public static String shout(@Receiver Greeting self, int times) {
                        return self.greet();
                    }

                    public static String shout(@Receiver Greeting self, long times) {
                        return self.greet();
                    }
                }
                """));
    }

    public void testAGenericMethodIsReported() {
        assertEquals(List.of("AW1310"), codesOf("""
                @Extension
                public final class Strings {
                    public static <T> T pick(@Receiver Greeting self, T value) { return value; }
                }
                """));
    }

    public void testAParameterisedReceiverIsReported() {
        assertEquals(List.of("AW1311"), codesOf("""
                @Extension
                public final class Strings {
                    public static String first(@Receiver java.util.List<String> self) {
                        return null;
                    }
                }
                """));
    }

    public void testAStaticContributionIsAccepted() {
        assertEquals("a receiver named on the method is a declaration the build accepts, and an "
                        + "inspection that reported it would be wrong about correct code",
                List.of(), codesOf("""
                @Extension
                public final class Strings {
                    @Receiver(String.class)
                    public static String twice(int n) { return String.valueOf(n * 2); }
                }
                """));
    }

    public void testAW1313ReceiverNamedTwice() {
        assertEquals(List.of("AW1313"), codesOf("""
                @Extension
                public final class Strings {
                    @Receiver(Integer.class)
                    public static String shout(@Receiver String self) { return self; }
                }
                """));
    }

    public void testAW1304ReceiverOnMethodNamesNothing() {
        assertEquals(List.of("AW1304"), codesOf("""
                @Extension
                public final class Strings {
                    @Receiver
                    public static String shout(String text) { return text; }
                }
                """));
    }

    // --- the harness ---------------------------------------------------------------------------

    private List<String> codesOf(final String body) {
        myFixture.configureByText("Strings.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.experimental.Extension;
                import de.splatgames.aether.weaver.api.experimental.Receiver;

                %s
                """.formatted(body));

        final List<String> codes = new ArrayList<>();
        for (final HighlightInfo info : myFixture.doHighlighting()) {
            final String description = info.getDescription();
            if (description != null && description.startsWith("AW")) {
                codes.add(description.substring(0, description.indexOf(':')));
            }
        }
        return codes;
    }
}
