package de.splatgames.aether.weaver.idea.toolwindow;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.ArrayList;
import java.util.List;

public class WeavesModelTest extends BasePlatformTestCase {

    private static final String WEAVE = """
            package de.splatgames.aether.weaver.api;

            public @interface Weave {
                Class<?>[] value() default {};
                String[] targets() default {};
                int priority() default 0;
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

    private static final String TARGET = """
            package fixture;

            public class Gateway {
                public void close() { }
                public void settle() { }
            }
            """;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Weave.java", WEAVE);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Inject.java", INJECT);
        myFixture.addFileToProject("fixture/Gateway.java", TARGET);
    }

    public void testAWeaveIsListedWithWhatItDeclares() {
        weave("Audit", "Gateway.class", """
                @Inject(method = "close()")
                void onClose() { }
                """);

        final List<WeavesModel.Weave> weaves = WeavesModel.of(getProject());

        assertEquals(1, weaves.size());
        final WeavesModel.Weave weave = weaves.getFirst();
        assertEquals("fixture.Audit", weave.name());
        assertTrue("an instance weave's code moves into the target", weave.merged());
        assertEquals(List.of("fixture.Gateway"), weave.targets());
        assertEquals(1, weave.handlers().size());
        assertEquals("onClose", weave.handlers().getFirst().name());
    }

    public void testBindingIsReportedPerHandler() {
        weave("Audit", "Gateway.class", """
                @Inject(method = "close()")
                void onClose() { }

                @Inject(method = "nothingHere()")
                void onNothing() { }
                """);

        final List<WeavesModel.Handler> handlers = WeavesModel.of(getProject())
                .getFirst().handlers();
        final List<String> reported = new ArrayList<>();
        for (final WeavesModel.Handler handler : handlers) {
            reported.add(handler.name() + '=' + handler.binding());
        }

        assertTrue("" + reported, reported.contains("onClose=" + WeavesModel.Binding.BOUND));
        assertTrue("" + reported, reported.contains("onNothing=" + WeavesModel.Binding.UNBOUND));
    }

    public void testHandlersAreListedInExecutionOrder() {
        weave("Audit", "Gateway.class", """
                @Inject(method = "close()")
                void onZulu() { }

                @Inject(method = "settle()")
                void onAlpha() { }
                """);

        final List<String> listed = new ArrayList<>();
        for (final WeavesModel.Handler handler : WeavesModel.of(getProject())
                .getFirst().handlers()) {
            listed.add(handler.name());
        }

        assertEquals("the order two handlers run in is not the order they are written in, and this "
                        + "window is where a reader finds that out",
                List.of("onAlpha", "onZulu"), listed);
    }

    public void testThePriorityIsCarried() {
        weave("Audit", "value = Gateway.class, priority = 100", """
                @Inject(method = "close()")
                void onClose() { }
                """);

        final WeavesModel.Weave weave = WeavesModel.of(getProject()).getFirst();

        assertEquals(100, weave.priority());
        assertEquals("a handler carries its weave's priority, since that is what decides which of "
                        + "two weaves on one method goes first",
                100, weave.handlers().getFirst().priority());
    }

    public void testAStaticWeaveIsListedAsStatic() {
        weave("Audit", "value = Gateway.class, kind = Weave.Kind.STATIC", """
                @Inject(method = "close()")
                static void onClose() { }
                """);

        assertFalse("its code never moves into the target, and the window has to say so",
                WeavesModel.of(getProject()).getFirst().merged());
    }

    public void testAnOrdinaryClassIsNotListed() {
        myFixture.addFileToProject("fixture/Plain.java", """
                package fixture;

                public final class Plain {
                    void run() { }
                }
                """);

        assertEquals("a window listing every class would be a project view with extra steps",
                List.of(), WeavesModel.of(getProject()));
    }

    private void weave(final String name, final String declaration, final String body) {
        myFixture.addFileToProject("fixture/" + name + ".java", """
                package fixture;

                import de.splatgames.aether.weaver.api.Inject;
                import de.splatgames.aether.weaver.api.Weave;

                @Weave(%s)
                public final class %s {
                %s
                }
                """.formatted(declaration, name, body.indent(4)));
    }

}
