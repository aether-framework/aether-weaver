package de.splatgames.aether.weaver.idea.preview;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

public class InjectionSiteHintTest extends BasePlatformTestCase {


    private static final String WEAVE = """
            package de.splatgames.aether.weaver.api;

            public @interface Weave {
                Class<?>[] value() default {};
                String[] targets() default {};
                int priority() default 0;
            }
            """;

    private static final String INJECT = """
            package de.splatgames.aether.weaver.api;

            public @interface Inject {
                String id() default "";
                String method();
                At[] at() default {};
            }
            """;

    private static final String AT = """
            package de.splatgames.aether.weaver.api;

            public @interface At {
                Point value() default Point.HEAD;
                String target() default "";
                int ordinal() default -1;
            }
            """;

    private static final String POINT = """
            package de.splatgames.aether.weaver.api;

            public enum Point {
                HEAD, RETURN, TAIL, INVOKE, INVOKE_AFTER, FIELD, NEW, CONSTANT, THROW
            }
            """;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Weave.java", WEAVE);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Inject.java", INJECT);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/At.java", AT);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Point.java", POINT);
    }

    public void testASingleSiteIsNamedByItsLine() {
        target("""
                package fixture;

                public final class Service {
                    void charge() {
                        audit();
                    }

                    void audit() {
                    }
                }
                """);

        assertEquals("→ line 5", List.copyOf(hintsIn("""
                package fixture;

                import de.splatgames.aether.weaver.api.*;

                @Weave(Service.class)
                public final class Tracing {

                    @Inject(method = "charge()", at = @At(value = Point.INVOKE, target = "#audit"))
                    void onAudit() {
                    }
                }
                """)).getFirst());
    }

    public void testSeveralSitesAreCountedAndListed() {
        target("""
                package fixture;

                public final class Service {
                    void charge() {
                        audit();
                        audit();
                        audit();
                    }

                    void audit() {
                    }
                }
                """);

        assertEquals("→ 3 sites: 5, 6, 7", List.copyOf(hintsIn("""
                package fixture;

                import de.splatgames.aether.weaver.api.*;

                @Weave(Service.class)
                public final class Tracing {

                    @Inject(method = "charge()", at = @At(value = Point.INVOKE, target = "#audit"))
                    void onAudit() {
                    }
                }
                """)).getFirst());
    }

    public void testAPointThatMatchesNothingIsSilent() {
        target("""
                package fixture;

                public final class Service {
                    void charge() {
                    }

                    void audit() {
                    }
                }
                """);

        assertEmpty("an unbuilt project, an unresolvable selector and a genuinely empty match "
                        + "all arrive here as an empty list. Saying 'no match' would be right for "
                        + "one of the three and wrong for the other two, and the plugin may be "
                        + "silent but may never be wrong",
                hintsIn("""
                        package fixture;

                        import de.splatgames.aether.weaver.api.*;

                        @Weave(Service.class)
                        public final class Tracing {

                            @Inject(method = "charge()",
                                    at = @At(value = Point.INVOKE, target = "#absent"))
                            void onAbsent() {
                            }
                        }
                        """));
    }

    public void testAnUnresolvableTargetIsSilent() {
        target("""
                package fixture;

                public final class Service {
                    void charge() {
                    }
                }
                """);

        assertEmpty(hintsIn("""
                package fixture;

                import de.splatgames.aether.weaver.api.*;

                @Weave(Service.class)
                public final class Tracing {

                    @Inject(method = "absent()", at = @At(Point.HEAD))
                    void onAbsent() {
                    }
                }
                """));
    }

    public void testTheSelectorIsFoundByName() {
        target("""
                package fixture;

                public final class Service {
                    void charge() {
                        audit();
                    }

                    void audit() {
                    }
                }
                """);

        assertEquals("`id` is a string too, and it is written first here. Taking the first "
                        + "literal would read the injection's own name as the method it attaches "
                        + "to, and answer confidently about a method that does not exist",
                "→ line 5", List.copyOf(hintsIn("""
                        package fixture;

                        import de.splatgames.aether.weaver.api.*;

                        @Weave(Service.class)
                        public final class Tracing {

                            @Inject(id = "audit-trace", method = "charge()",
                                    at = @At(value = Point.INVOKE, target = "#audit"))
                            void onAudit() {
                            }
                        }
                        """)).getFirst());
    }

    public void testANonInjectionAnnotationIsIgnored() {
        target("""
                package fixture;

                public final class Service {
                    void charge() {
                        audit();
                    }

                    void audit() {
                    }
                }
                """);
        myFixture.addFileToProject("fixture/Custom.java", """
                package fixture;

                public @interface Custom {
                    String method();
                }
                """);

        assertEmpty("the attribute is called `method` and its value resolves, so everything "
                        + "downstream would happily answer for it. Only the annotation's own name "
                        + "says this is not an injection — an earlier fixture used "
                        + "@SuppressWarnings, which fails the attribute check first and so proved "
                        + "nothing about the annotation check at all",
                hintsIn("""
                        package fixture;

                        import de.splatgames.aether.weaver.api.*;

                        @Weave(Service.class)
                        public final class Tracing {

                            @Custom(method = "charge()")
                            void notAnInjection() {
                            }
                        }
                        """));
    }

    // --- the harness -----------------------------------------------------------------------

    private void target(final String source) {
        myFixture.addFileToProject("fixture/Service.java", source);
    }

    private List<String> hintsIn(final String source) {
        myFixture.configureByText("Tracing.java", source);
        return InjectionSiteHints.of(myFixture.getFile()).stream()
                .map(InjectionSiteHints.Hint::text)
                .toList();
    }
}
