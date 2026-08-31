package de.splatgames.aether.weaver.idea.generate;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.generation.ClassMember;
import com.intellij.codeInsight.generation.PsiGenerationInfo;
import com.intellij.codeInsight.generation.PsiMethodMember;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import de.splatgames.aether.weaver.idea.inspection.HandlerSignatureInspection;
import de.splatgames.aether.weaver.idea.psi.SelectorTargets;

import java.util.ArrayList;
import java.util.List;

public class AddHandlerTest extends BasePlatformTestCase {

    private int generated;

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
                At[] at() default {};
                int require() default 0;
                int allow() default 0;
                String group() default "";
            }
            """;

    private static final String AT = """
            package de.splatgames.aether.weaver.api;

            public @interface At {
                Point value() default Point.HEAD;
                int ordinal() default -1;
            }
            """;

    private static final String GROUP = """
            package de.splatgames.aether.weaver.api;

            public @interface Group {
                String name();
                int min() default 1;
                int max() default 0;
            }
            """;

    private static final String POINT = """
            package de.splatgames.aether.weaver.api;

            public enum Point { HEAD, RETURN, TAIL, INVOKE }
            """;

    private static final String CALLBACK = """
            package de.splatgames.aether.weaver.api.callback;

            public interface Callback {
                void cancel();
            }
            """;

    private static final String RETURNABLE_CALLBACK = """
            package de.splatgames.aether.weaver.api.callback;

            public interface ReturnableCallback<T> extends Callback {
                T value();
            }
            """;

    private static final String MONEY = """
            package fixture;

            public class Money { }
            """;

    private static final String RECEIPT = """
            package fixture;

            public class Receipt { }
            """;

    private static final String TARGET = """
            package fixture;

            public class Gateway {
                // Two different parameter types, deliberately. With two of the same type a
                // generator that emitted them in the wrong order would still satisfy every
                // assertion here, and a counter-probe reversing them found exactly that.
                public Receipt charge(Money amount, Receipt token) { return null; }
                public void close() { }
                public static void reset() { }
                public native void handshake();
                public Gateway() { }
            }
            """;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Weave.java", WEAVE);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Inject.java", INJECT);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/At.java", AT);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Point.java", POINT);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Group.java", GROUP);
        myFixture.addFileToProject(
                "de/splatgames/aether/weaver/api/callback/Callback.java", CALLBACK);
        myFixture.addFileToProject(
                "de/splatgames/aether/weaver/api/callback/ReturnableCallback.java",
                RETURNABLE_CALLBACK);
        myFixture.addFileToProject("fixture/Money.java", MONEY);
        myFixture.addFileToProject("fixture/Receipt.java", RECEIPT);
        myFixture.addFileToProject("fixture/Gateway.java", TARGET);
    }

    public void testAHandlerIsGeneratedForATargetMethod() {
        final String written = generate("", "charge");

        assertTrue("the selector names the signature, not the bare name: " + written,
                written.contains("\"charge(fixture.Money, fixture.Receipt)\""));
        assertTrue("the parameters are the target's, in order: " + written,
                written.contains("fixture.Money amount, fixture.Receipt token"));
        assertTrue("a non-void target gets the callback that lets the handler change the outcome: "
                        + written,
                written.contains("ReturnableCallback<fixture.Receipt> callback"));
        assertTrue("HEAD is written out, so the reader can see what to change: " + written,
                written.contains("Point.HEAD"));
    }

    public void testTheSelectorIsAlwaysTheFullSignature() {
        final String written = generate("", "close");

        assertTrue("'close' alone resolves right up to the day somebody overloads it, at which "
                        + "point a file nobody touched becomes AW1021: " + written,
                written.contains("\"close()\""));
    }

    public void testAVoidTargetGetsThePlainCallback() {
        final String written = generate("", "close");

        assertFalse("nothing to carry back, so not the returnable one: " + written,
                written.contains("ReturnableCallback"));
        assertTrue("" + written, written.contains("Callback callback"));
    }

    public void testAnInstanceHandlerIsPrivate() {
        final String written = generate("", "close");

        assertTrue("" + written, written.contains("private void onClose"));
    }

    public void testAStaticWeaveHandlerTakesTheReceiver() {
        final String written = generate("value = Gateway.class, kind = Weave.Kind.STATIC", "close");

        assertTrue("a static weave is never merged, so the emitted call is an ordinary cross-class "
                        + "invocation — private would be AW1042, discovered at the first execution "
                        + "of the injected call rather than at build time: " + written,
                written.contains("public static void onClose"));
        assertTrue("it has no 'this', so the target arrives as a parameter: " + written,
                written.contains("fixture.Gateway gateway"));
    }

    public void testAStaticTargetGetsNoReceiver() {
        final String written = generate("value = Gateway.class, kind = Weave.Kind.STATIC", "reset");

        assertFalse("there is no instance for a static method, so a receiver parameter would be a "
                        + "parameter the framework cannot fill: " + written,
                written.contains("Gateway gateway"));
    }

    public void testASecondHandlerForOneMethodIsNumbered() {
        myFixture.addFileToProject("fixture/Audit.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.Weave;

                @Weave(Gateway.class)
                public final class Audit {
                    void onClose() { }
                }
                """);

        final PsiClass weave = classNamed("fixture.Audit");
        final PsiMethod handler = AddHandlerHandler.handlerFor(weave, methodNamed("close"), HandlerOptions.defaults());

        assertNotNull(handler);
        assertTrue("two handlers on one method are entirely legitimate — a different point, a "
                        + "different concern: " + handler.getText(),
                handler.getText().contains("onClose2"));
    }

    public void testOnlyInjectableMethodsAreOffered() {
        myFixture.addFileToProject("fixture/Audit.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.Weave;

                @Weave(Gateway.class)
                public final class Audit { }
                """);

        final List<String> offered = new ArrayList<>();
        for (final ClassMember member
                : new AddHandlerHandler().getAllOriginalMembers(classNamed("fixture.Audit"))) {
            offered.add(((PsiMethodMember) member).getElement().getName());
        }

        assertTrue("the ordinary methods are what this exists for: " + offered,
                offered.contains("charge") && offered.contains("close"));
        assertFalse("a native method has no class file to inject into — offering it is offering a "
                        + "build failure (AW1025): " + offered,
                offered.contains("handshake"));
        assertFalse("a handler at the head of a constructor runs before super() and is AW1026, "
                        + "which deserves more than a footnote: " + offered,
                offered.contains("Gateway"));
    }

    public void testTheActionOnlyAppliesToAWeave() {
        myFixture.addFileToProject("fixture/Plain.java", """
                package fixture;

                public final class Plain { }
                """);
        myFixture.addFileToProject("fixture/Untargeted.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.Weave;

                @Weave
                public final class Untargeted { }
                """);
        myFixture.addFileToProject("fixture/Audit.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.Weave;

                @Weave(Gateway.class)
                public final class Audit { }
                """);

        final AddHandlerAction action = new AddHandlerAction();

        assertTrue("a weave with a target is the whole point",
                action.isValidForClass(classNamed("fixture.Audit")));
        assertFalse("an entry in every Java class is noise in a menu people open constantly",
                action.isValidForClass(classNamed("fixture.Plain")));
        assertFalse("a weave whose target does not resolve would open a chooser with nothing in it",
                action.isValidForClass(classNamed("fixture.Untargeted")));
    }

    public void testEveryGeneratedHandlerSatisfiesTheInspection() {
        myFixture.configureByText("Audit.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.Weave;

                @Weave(Gateway.class)
                public final class Audit { }
                """);
        final PsiClass weave = classNamed("fixture.Audit");
        final AddHandlerHandler handler = new AddHandlerHandler();

        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            for (final ClassMember member : handler.getAllOriginalMembers(weave)) {
                final PsiMethod generated =
                        AddHandlerHandler.handlerFor(
                                weave, ((PsiMethodMember) member).getElement(), HandlerOptions.defaults());
                assertNotNull("nothing here should defeat the generator", generated);
                new PsiGenerationInfo<>(generated).insert(weave, null, false);
            }
        });

        myFixture.enableInspections(new HandlerSignatureInspection());
        final List<String> problems = new ArrayList<>();
        for (final HighlightInfo info : myFixture.doHighlighting()) {
            if (info.getDescription() != null && info.getDescription().startsWith("AW")) {
                problems.add(info.getDescription());
            }
        }

        assertEquals("a generator whose output its own inspection underlines is worse than no "
                        + "generator: " + problems + "\n" + myFixture.getFile().getText(),
                List.of(), problems);
    }

    public void testTheDescriptorFormIsGenerated() {
        final String written = generate("", "charge",
                options(HandlerOptions.Point.HEAD, HandlerOptions.Selector.DESCRIPTOR, true));

        assertTrue("the descriptor comes from the platform's own encoder, against a method that is "
                        + "already resolved — this is encoding a known member, not interpreting a "
                        + "string: " + written,
                written.contains("\"desc:charge(Lfixture/Money;Lfixture/Receipt;)Lfixture/Receipt;\""));
    }

    public void testEverySelectorSpellingNamesTheSameMethod() {
        myFixture.addFileToProject("fixture/Audit.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.Weave;

                @Weave(Gateway.class)
                public final class Audit { }
                """);
        final PsiClass weave = classNamed("fixture.Audit");

        for (final HandlerOptions.Selector form : HandlerOptions.Selector.values()) {
            final PsiMethod handler = AddHandlerHandler.handlerFor(weave, methodNamed("charge"),
                    options(HandlerOptions.Point.HEAD, form, true));
            assertNotNull("" + form, handler);
            final String written = handler.getText();
            final int start = written.indexOf('"') + 1;
            final String selector = written.substring(start, written.indexOf('"', start));

            assertEquals("the spelling changed but the method it names must not: " + form
                            + " produced " + selector,
                    methodNamed("charge"), SelectorTargets.exact(weave, selector));
        }
    }

    public void testTheSimpleNameFormIsGenerated() {
        final String written = generate("", "charge",
                options(HandlerOptions.Point.HEAD, HandlerOptions.Selector.SIMPLE, true));

        assertTrue("" + written, written.contains("\"charge(Money, Receipt)\""));
    }

    public void testTheChosenPointIsGenerated() {
        final String written = generate("", "close",
                options(HandlerOptions.Point.RETURN, HandlerOptions.Selector.QUALIFIED, true));

        assertTrue("a handler at RETURN is as ordinary as one at HEAD: " + written,
                written.contains("Point.RETURN"));
    }

    public void testAnOperationPointIsNotWrittenWithoutAnOperation() {
        for (final HandlerOptions.Point point : HandlerOptions.Point.values()) {
            final HandlerOptions options = new HandlerOptions(HandlerOptions.Kind.INJECT, point,
                    HandlerOptions.Match.EVERY, HandlerOptions.Selector.QUALIFIED,
                    HandlerOptions.Visibility.AUTOMATIC, HandlerOptions.DEFAULT_PREFIX, "",
                    true, false, false, false);
            myFixture.addFileToProject("fixture/Point" + point.name() + ".java", """
                    package fixture;

                    import de.splatgames.aether.weaver.api.Weave;

                    @Weave(Gateway.class)
                    public final class Point%s { }
                    """.formatted(point.name()));
            final PsiMethod handler = AddHandlerHandler.handlerFor(
                    classNamed("fixture.Point" + point.name()), methodNamed("close"), options);

            if (point.needsOperation()) {
                assertNull("@At(Point." + point + ") with no target is an error the user did not "
                        + "type", handler);
            } else {
                assertNotNull("" + point, handler);
            }
        }
    }

    public void testARedirectIsRefusedAtAPosition() {
        assertFalse("a redirect stands in for an operation, and at a position there is none — the "
                        + "engine calls that AW1061",
                HandlerOptions.Kind.REDIRECT.appliesTo(HandlerOptions.Point.HEAD));
        assertFalse("standing in for a call is standing in for the whole call, so 'after' is a "
                        + "position within it",
                HandlerOptions.Kind.REDIRECT.appliesTo(HandlerOptions.Point.INVOKE_AFTER));
        assertTrue("INVOKE, FIELD and NEW are what the engine lists as redirectable",
                HandlerOptions.Kind.REDIRECT.appliesTo(HandlerOptions.Point.INVOKE)
                        && HandlerOptions.Kind.REDIRECT.appliesTo(HandlerOptions.Point.FIELD)
                        && HandlerOptions.Kind.REDIRECT.appliesTo(HandlerOptions.Point.NEW));
    }

    public void testTheCallbackCanBeDeclined() {
        final String written = generate("", "charge",
                options(HandlerOptions.Point.HEAD, HandlerOptions.Selector.QUALIFIED, false));

        assertFalse("a handler that only observes has no use for it: " + written,
                written.contains("Callback"));
    }

    public void testAVoidTargetCanTakeAPlainCallback() {
        final String written = generate("", "close",
                options(HandlerOptions.Point.HEAD, HandlerOptions.Selector.QUALIFIED, true));

        assertTrue("cancelling is the reason to take one, and a void target can still be "
                        + "cancelled: " + written,
                written.contains("callback.Callback callback"));
    }

    public void testTheDefaultMatchRuleAddsNoAttributes() {
        final String written = generate("", "close");

        assertFalse("an annotation restating four defaults reads as though somebody weighed them: "
                        + written,
                written.contains("ordinal") || written.contains("require")
                        || written.contains("allow") || written.contains("group"));
    }

    public void testTheFirstPositionRuleWritesAnOrdinal() {
        final String written = generate("", "close", new HandlerOptions(HandlerOptions.Kind.INJECT,
                HandlerOptions.Point.RETURN, HandlerOptions.Match.FIRST,
                HandlerOptions.Selector.QUALIFIED, HandlerOptions.Visibility.AUTOMATIC,
                HandlerOptions.DEFAULT_PREFIX, "", true, false, false, false));

        assertTrue("the ordinal has to move inside @At, where the point is: " + written,
                written.contains("value = de.splatgames.aether.weaver.api.Point.RETURN")
                        && written.contains("ordinal = 0"));
        assertFalse("pinning a position says nothing about how many there must be: " + written,
                written.contains("require"));
    }

    public void testTheExactlyOneRuleWritesBothBounds() {
        final String written = generate("", "close", new HandlerOptions(HandlerOptions.Kind.INJECT,
                HandlerOptions.Point.RETURN, HandlerOptions.Match.EXACTLY_ONE,
                HandlerOptions.Selector.QUALIFIED, HandlerOptions.Visibility.AUTOMATIC,
                HandlerOptions.DEFAULT_PREFIX, "", true, false, false, false));

        assertTrue("an injection that matches nothing is otherwise a warning-free no-op: " + written,
                written.contains("require = 1") && written.contains("allow = 1"));
        assertFalse("bounding the count is not the same as choosing one of them: " + written,
                written.contains("ordinal"));
    }

    public void testTheFirstPositionRuleIsNotOfferedForHeadOrTail() {
        assertFalse("there is one head, so pinning the ordinal to zero is noise — and AW1110 as "
                        + "soon as somebody edits it upward",
                HandlerOptions.Match.FIRST.appliesTo(HandlerOptions.Point.HEAD));
        assertFalse("" + HandlerOptions.Point.TAIL,
                HandlerOptions.Match.FIRST.appliesTo(HandlerOptions.Point.TAIL));
        assertTrue("a method can return from several places, which is the whole case for the rule",
                HandlerOptions.Match.FIRST.appliesTo(HandlerOptions.Point.RETURN));
        for (final HandlerOptions.Match rule : HandlerOptions.Match.values()) {
            assertTrue("a rule about how many positions there must be applies to every point: "
                            + rule,
                    rule == HandlerOptions.Match.FIRST
                            || rule.appliesTo(HandlerOptions.Point.HEAD));
        }
    }

    public void testAChosenVisibilityIsWritten() {
        final String written = generate("", "close", new HandlerOptions(HandlerOptions.Kind.INJECT,
                HandlerOptions.Point.HEAD, HandlerOptions.Match.EVERY,
                HandlerOptions.Selector.QUALIFIED, HandlerOptions.Visibility.PROTECTED,
                HandlerOptions.DEFAULT_PREFIX, "", true, false, false, false));

        assertTrue("" + written, written.contains("protected void onClose"));
    }

    public void testPackagePrivateIsWrittenAsNothing() {
        final String written = generate("", "close", new HandlerOptions(HandlerOptions.Kind.INJECT,
                HandlerOptions.Point.HEAD, HandlerOptions.Match.EVERY,
                HandlerOptions.Selector.QUALIFIED, HandlerOptions.Visibility.PACKAGE_PRIVATE,
                HandlerOptions.DEFAULT_PREFIX, "", true, false, false, false));

        assertFalse("'packageLocal' is what PSI calls it and not a Java keyword: " + written,
                written.contains("packageLocal"));
        assertTrue("" + written, written.contains("void onClose"));
    }

    public void testAStaticWeaveIsOnlyOfferedReachableVisibilities() {
        assertTrue("" + HandlerOptions.Visibility.PUBLIC,
                HandlerOptions.Visibility.PUBLIC.survivesAStaticWeave());
        assertTrue("" + HandlerOptions.Visibility.AUTOMATIC,
                HandlerOptions.Visibility.AUTOMATIC.survivesAStaticWeave());
        assertFalse("a static weave's handler is called across classes, so private is AW1042 — "
                        + "found when the injected call first runs, not when the build does",
                HandlerOptions.Visibility.PRIVATE.survivesAStaticWeave());
        assertFalse("the target's package is not the weave's to choose and survives no move "
                        + "refactoring",
                HandlerOptions.Visibility.PACKAGE_PRIVATE.survivesAStaticWeave());
        assertFalse("" + HandlerOptions.Visibility.PROTECTED,
                HandlerOptions.Visibility.PROTECTED.survivesAStaticWeave());
    }

    public void testTheNamePrefixIsUsed() {
        assertTrue(generate("", "close", prefixed("intercept")).contains("void interceptClose"));
        assertTrue("an empty prefix is a legitimate choice, not a reason to fall back",
                generate("", "close", prefixed("")).contains("void close("));
    }

    public void testAHandlerThatWouldCollideWithTheTargetIsNumbered() {
        myFixture.addFileToProject("fixture/Collide.java", """
                package fixture;

                public class Collide {
                    public void run() { }
                    private void onRun(de.splatgames.aether.weaver.api.callback.Callback callback) { }
                }
                """);
        myFixture.addFileToProject("fixture/CollideWeave.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.Weave;

                @Weave(Collide.class)
                public final class CollideWeave { }
                """);

        final PsiClass weave = classNamed("fixture.CollideWeave");
        final PsiMethod handler = AddHandlerHandler.handlerFor(
                weave, methodOf("fixture.Collide", "run"), HandlerOptions.defaults());

        assertNotNull(handler);
        assertTrue("an instance weave's handler is merged into the target, so a matching name and "
                        + "parameter list is AW1080: " + handler.getText(),
                handler.getText().contains("onRun2"));
    }

    public void testADifferentSignatureOnTheTargetIsNotACollision() {
        myFixture.addFileToProject("fixture/Near.java", """
                package fixture;

                public class Near {
                    public void run() { }
                    private void onRun(int unrelated) { }
                }
                """);
        myFixture.addFileToProject("fixture/NearWeave.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.Weave;

                @Weave(Near.class)
                public final class NearWeave { }
                """);

        final PsiMethod handler = AddHandlerHandler.handlerFor(classNamed("fixture.NearWeave"),
                methodOf("fixture.Near", "run"), HandlerOptions.defaults());

        assertNotNull(handler);
        assertTrue("the engine collides on name and descriptor together, so numbering on the name "
                        + "alone would rename handlers that were never in danger: "
                        + handler.getText(),
                handler.getText().contains("void onRun("));
    }

    public void testOnlyDeclaredGroupsAreOffered() {
        myFixture.addFileToProject("fixture/Grouped.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.Group;
                import de.splatgames.aether.weaver.api.Weave;

                @Weave(Gateway.class)
                @Group(name = "audit")
                public final class Grouped { }
                """);
        myFixture.addFileToProject("fixture/Ungrouped.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.Weave;

                @Weave(Gateway.class)
                public final class Ungrouped { }
                """);

        assertEquals("a grouped injection is exempt from its own require, so a group name nobody "
                        + "declared leaves the injection with no match check at all",
                List.of("audit"),
                de.splatgames.aether.weaver.idea.psi.WeaveDeclarations
                        .groupsOf(classNamed("fixture.Grouped")));
        assertEquals(List.of(),
                de.splatgames.aether.weaver.idea.psi.WeaveDeclarations
                        .groupsOf(classNamed("fixture.Ungrouped")));
    }

    public void testAChosenGroupIsWritten() {
        final String written = generate("", "close", new HandlerOptions(HandlerOptions.Kind.INJECT,
                HandlerOptions.Point.HEAD, HandlerOptions.Match.EVERY,
                HandlerOptions.Selector.QUALIFIED, HandlerOptions.Visibility.AUTOMATIC,
                HandlerOptions.DEFAULT_PREFIX, "audit", true, false, false, false));

        assertTrue("" + written, written.contains("group = \"audit\""));
    }

    public void testTheCommentAndTheMarkerAreOptional() {
        final String bare = generate("", "charge");
        assertFalse("" + bare, bare.contains("/**") || bare.contains("TODO"));

        final String full = generate("", "charge", new HandlerOptions(HandlerOptions.Kind.INJECT,
                HandlerOptions.Point.HEAD, HandlerOptions.Match.EVERY,
                HandlerOptions.Selector.QUALIFIED, HandlerOptions.Visibility.AUTOMATIC,
                HandlerOptions.DEFAULT_PREFIX, "", true, false, true, true));

        assertTrue("" + full, full.contains("/**") && full.contains("TODO"));
        assertTrue("every parameter gets a line, including the callback: " + full,
                full.contains("@param amount") && full.contains("@param token")
                        && full.contains("@param callback"));
    }

    public void testEveryOptionCombinationStillSatisfiesTheInspection() {
        myFixture.configureByText("Audit.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.Weave;

                @Weave(Gateway.class)
                public final class Audit { }
                """);
        final PsiClass weave = classNamed("fixture.Audit");
        final AddHandlerHandler generator = new AddHandlerHandler();

        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            for (final HandlerOptions.Point point : HandlerOptions.Point.values()) {
                // An operation point needs an operation, and one cannot be read here: the fixture
                // has no compiler output. What it writes for one is OperationHandlerTest's subject.
                if (point.needsOperation()) {
                    continue;
                }
                for (final HandlerOptions.Match match : HandlerOptions.Match.values()) {
                    if (!match.appliesTo(point)) {
                        continue;
                    }
                    // Visibility is deliberately not part of the product: it cannot reach any of
                    // the rules this inspection reads, and every extra dimension is another
                    // hundred methods in one class for nothing.
                    for (final boolean callback : new boolean[]{true, false}) {
                        for (final ClassMember member : generator.getAllOriginalMembers(weave)) {
                            final PsiMethod generated = AddHandlerHandler.handlerFor(weave,
                                    ((PsiMethodMember) member).getElement(),
                                    new HandlerOptions(HandlerOptions.Kind.INJECT, point, match,
                                            HandlerOptions.Selector.QUALIFIED,
                                            HandlerOptions.Visibility.AUTOMATIC,
                                            HandlerOptions.DEFAULT_PREFIX, "", callback,
                                            false, true, true));
                            assertNotNull("nothing here should defeat the generator", generated);
                            new PsiGenerationInfo<>(generated).insert(weave, null, false);
                        }
                    }
                }
            }
        });

        myFixture.enableInspections(new HandlerSignatureInspection());
        final List<String> problems = new ArrayList<>();
        for (final HighlightInfo info : myFixture.doHighlighting()) {
            if (info.getDescription() != null && info.getDescription().startsWith("AW")) {
                problems.add(info.getDescription());
            }
        }

        assertEquals("a generator whose output its own inspection underlines is worse than no "
                        + "generator: " + problems, List.of(), problems);
    }

    private static HandlerOptions prefixed(final String prefix) {
        final HandlerOptions defaults = HandlerOptions.defaults();
        return new HandlerOptions(defaults.kind(), defaults.point(), defaults.match(), defaults.selector(),
                defaults.visibility(), prefix, "", defaults.callback(), defaults.locals(),
                defaults.javadoc(), defaults.todo());
    }

    private static HandlerOptions options(final HandlerOptions.Point point,
                                          final HandlerOptions.Selector selector,
                                          final boolean callback) {
        final HandlerOptions defaults = HandlerOptions.defaults();
        return new HandlerOptions(defaults.kind(), point, defaults.match(), selector, defaults.visibility(),
                defaults.prefix(), "", callback, defaults.locals(), defaults.javadoc(), defaults.todo());
    }

    private PsiMethod methodOf(final String owner, final String name) {
        for (final PsiMethod candidate : classNamed(owner).getMethods()) {
            if (candidate.getName().equals(name)) {
                return candidate;
            }
        }
        throw new AssertionError("the fixture must declare " + owner + '.' + name);
    }

    private String generate(final String declaration, final String target) {
        return generate(declaration, target, HandlerOptions.defaults());
    }

    private String generate(final String declaration,
                            final String target,
                            final HandlerOptions options) {
        final String targets = declaration.isEmpty() ? "Gateway.class" : declaration;
        // A fresh weave per call. Two calls in one test used to write the same file twice, which
        // the fixture answers with an IOException — a failure that says nothing about the generator.
        final String name = "Audit" + this.generated++;
        myFixture.addFileToProject("fixture/" + name + ".java", """
                package fixture;

                import de.splatgames.aether.weaver.api.Weave;

                @Weave(%s)
                public final class %s { }
                """.formatted(targets, name));

        final PsiMethod handler = AddHandlerHandler.handlerFor(
                classNamed("fixture." + name), methodNamed(target), options);
        assertNotNull("the generator declined to write anything for " + target, handler);
        return handler.getText();
    }

    private PsiMethod methodNamed(final String name) {
        for (final PsiMethod candidate : classNamed("fixture.Gateway").getMethods()) {
            if (candidate.getName().equals(name)) {
                return candidate;
            }
        }
        throw new AssertionError("the fixture must declare " + name);
    }

    private PsiClass classNamed(final String qualifiedName) {
        final PsiClass found = JavaPsiFacade.getInstance(getProject())
                .findClass(qualifiedName, GlobalSearchScope.allScope(getProject()));
        assertNotNull("the fixture must declare " + qualifiedName, found);
        return found;
    }
}
