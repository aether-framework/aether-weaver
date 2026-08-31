package de.splatgames.aether.weaver.idea.augment;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.testFramework.DumbModeTestUtils;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.ArrayList;
import java.util.List;

public class WeaveAugmentTest extends BasePlatformTestCase {

    private static final String WEAVE = """
            package de.splatgames.aether.weaver.api;

            public @interface Weave {
                Class<?>[] value() default {};
                String[] targets() default {};
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

    private static final String SHADOW = """
            package de.splatgames.aether.weaver.api;

            public @interface Shadow {
                String value() default "";
            }
            """;

    private static final String UNIQUE = """
            package de.splatgames.aether.weaver.api;

            public @interface Unique {
            }
            """;

    private static final String ACCESSOR = """
            package de.splatgames.aether.weaver.api;

            public @interface Accessor {
                String value() default "";
            }
            """;

    private static final String TARGET = """
            package fixture;

            public class Session {
                private int ledger;
                public void close() { }
            }
            """;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Weave.java", WEAVE);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Inject.java", INJECT);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Shadow.java", SHADOW);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Unique.java", UNIQUE);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Accessor.java", ACCESSOR);
        myFixture.addFileToProject("fixture/Session.java", TARGET);
    }

    public void testAUniqueMethodAppearsOnTheTarget() {
        weave("@Weave(Session.class)", """
                @Unique
                public long elapsed() { return 0; }
                """);

        assertTrue("without this, calling session.elapsed() in ordinary code is red — correct code "
                        + "reported as an error: " + methodsOfTarget(),
                methodsOfTarget().contains("elapsed"));
    }

    public void testAUniqueFieldAppearsOnTheTarget() {
        weave("@Weave(Session.class)", """
                @Unique
                public long startedAt;
                """);

        assertTrue("" + fieldsOfTarget(), fieldsOfTarget().contains("startedAt"));
    }

    public void testAnAccessorAppearsOnTheTarget() {
        weave("@Weave(Session.class)", """
                @Accessor
                int getLedger() { throw new IllegalStateException("accessor"); }
                """);

        assertTrue("an accessor exists so that code outside the weave can reach the field; if the "
                        + "generated method is invisible it has achieved nothing: " + methodsOfTarget(),
                methodsOfTarget().contains("getLedger"));
    }

    public void testAHandlerAppearsOnTheTarget() {
        weave("@Weave(Session.class)", """
                @Inject(method = "close")
                void onClose() { }
                """);

        assertTrue("" + methodsOfTarget(), methodsOfTarget().contains("onClose"));
    }

    public void testAMergedHandlerIsPrivate() {
        weave("@Weave(Session.class)", """
                @Inject(method = "close")
                void onClose() { }
                """);

        final PsiMethod handler = augmentedMethod("onClose");
        assertNotNull(handler);
        assertTrue("a handler is a real method of the woven class and also an implementation "
                        + "detail nobody should call; private is how both stay true",
                handler.hasModifierProperty(com.intellij.psi.PsiModifier.PRIVATE));
    }

    public void testAShadowIsNotAdded() {
        weave("@Weave(Session.class)", """
                @Shadow
                private int ledger;
                """);

        assertEquals("adding it would put a duplicate-member error on a target whose own source is "
                        + "perfectly fine: " + fieldsOfTarget(),
                1, countOf(fieldsOfTarget(), "ledger"));
    }

    public void testAStaticWeaveMergesNothing() {
        weave("@Weave(value = Session.class, kind = Weave.Kind.STATIC)", """
                @Unique
                public long elapsed() { return 0; }
                """);

        assertFalse("a static weave's code stays where it is written; the target gains no members "
                        + "at all: " + methodsOfTarget(),
                methodsOfTarget().contains("elapsed"));
    }

    public void testAStaticWeaveStillContributesItsAccessor() {
        weave("@Weave(value = Session.class, kind = Weave.Kind.STATIC)", """
                @Accessor
                int getLedger() { throw new IllegalStateException("accessor"); }
                """);

        assertTrue("a static weave reaches the target's state through @Accessor precisely because "
                        + "it cannot merge; refusing to show it would invert the rule: "
                        + methodsOfTarget(),
                methodsOfTarget().contains("getLedger"));
    }

    public void testACollidingMemberIsNotAdded() {
        weave("@Weave(Session.class)", """
                @Unique
                private int ledger;
                """);

        assertEquals("the engine renames this to ledger$aw$<digest>; offering the plain name would "
                        + "claim a member the compiled class does not have under that name: "
                        + fieldsOfTarget(),
                1, countOf(fieldsOfTarget(), "ledger"));
    }

    public void testAnUnwovenClassGainsNothing() {
        myFixture.addFileToProject("fixture/Untouched.java", """
                package fixture;

                public class Untouched {
                    public void run() { }
                }
                """);
        weave("@Weave(Session.class)", """
                @Unique
                public long elapsed() { return 0; }
                """);

        assertFalse("if this were false for every class, every assertion above would pass for the "
                        + "wrong reason",
                namesOf(classNamed("fixture.Untouched").getMethods()).contains("elapsed"));
    }

    public void testTheWeaveIsNotAugmentedWithItsOwnMembers() {
        weave("@Weave(Session.class)", """
                @Unique
                public long elapsed() { return 0; }
                """);

        assertEquals("the weave declares them; adding them again would report every one as a "
                        + "duplicate",
                1, countOf(namesOf(classNamed("fixture.Audit").getMethods()), "elapsed"));
    }

    public void testACallToAMergedMemberIsNotAnError() {
        weave("@Weave(Session.class)", """
                @Unique
                public long elapsed() { return 0; }
                """);
        myFixture.configureByText("Caller.java", """
                package fixture;

                public class Caller {
                    long ask(Session session) {
                        return session.elapsed();
                    }
                }
                """);

        final List<String> errors = new ArrayList<>();
        for (final HighlightInfo info : myFixture.doHighlighting()) {
            if (info.getSeverity() == HighlightSeverity.ERROR && info.getDescription() != null) {
                errors.add(info.getDescription());
            }
        }

        assertEquals("correct code reported as an error is the strongest possible signal that the "
                        + "tooling does not understand the framework: " + errors,
                List.of(), errors);
    }

    private void weave(final String declaration, final String body) {
        myFixture.addFileToProject("fixture/Audit.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.Accessor;
                import de.splatgames.aether.weaver.api.Inject;
                import de.splatgames.aether.weaver.api.Shadow;
                import de.splatgames.aether.weaver.api.Unique;
                import de.splatgames.aether.weaver.api.Weave;

                %s
                public final class Audit {
                %s
                }
                """.formatted(declaration, body.indent(4)));
    }

    private List<String> methodsOfTarget() {
        return namesOf(classNamed("fixture.Session").getMethods());
    }

    private List<String> fieldsOfTarget() {
        final List<String> names = new ArrayList<>();
        for (final PsiField field : classNamed("fixture.Session").getFields()) {
            names.add(field.getName());
        }
        return names;
    }

    private PsiMethod augmentedMethod(final String name) {
        for (final PsiMethod method : classNamed("fixture.Session").getMethods()) {
            if (name.equals(method.getName())) {
                return method;
            }
        }
        return null;
    }

    private static List<String> namesOf(final PsiMethod[] methods) {
        final List<String> names = new ArrayList<>();
        for (final PsiMethod method : methods) {
            names.add(method.getName());
        }
        return names;
    }

    private static int countOf(final List<String> names, final String name) {
        int found = 0;
        for (final String candidate : names) {
            if (name.equals(candidate)) {
                found++;
            }
        }
        return found;
    }

    private PsiClass classNamed(final String qualifiedName) {
        final PsiClass found = JavaPsiFacade.getInstance(getProject())
                .findClass(qualifiedName, GlobalSearchScope.allScope(getProject()));
        assertNotNull("the fixture must declare " + qualifiedName, found);
        return found;
    }

    public void testItAnswersNothingWhileIndexing() {
        myFixture.addFileToProject("fixture/Audit.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.Weave;

                @Weave(Target.class)
                public final class Audit {
                }
                """);
        myFixture.addFileToProject("fixture/Marked.java", """
                package fixture;

                public @interface Marked {
                }
                """);
        // Annotated, and through an import. findAnnotation only resolves when there is an
        // annotation to ask the qualified name of, and it is resolving the import that names it
        // which reaches the stub index. A bare class touches nothing and passes either way — this
        // fixture was one, and the test it was in went green against the bug it was written for.
        myFixture.addFileToProject("fixture/Target.java", """
                package fixture;

                import fixture.Marked;

                @Marked
                public class Target {
                    public void charge() { }
                }
                """);
        final PsiClass target = JavaPsiFacade.getInstance(getProject())
                .findClass("fixture.Target", GlobalSearchScope.allScope(getProject()));
        assertNotNull("the fixture must declare the target", target);

        // The resolve cache is warm from the setup above, and a warm cache answers without the
        // index — which is the difference between reproducing this and not.
        com.intellij.psi.PsiManager.getInstance(getProject()).dropResolveCaches();
        final Object[] fields = DumbModeTestUtils.computeInDumbModeSynchronously(getProject(),
                target::getFields);

        assertNotNull("walking a class during indexing has to answer, not throw — this used to be "
                + "an IndexNotReadyException over the navigation bar on project open", fields);
    }
}
