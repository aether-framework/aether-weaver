package de.splatgames.aether.weaver.idea.inspection;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.ArrayList;
import java.util.List;

public class HandlerSignatureInspectionTest extends BasePlatformTestCase {

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

    private static final String REDIRECT = """
            package de.splatgames.aether.weaver.api;

            public @interface Redirect {
                String method();
            }
            """;

    private static final String LOCAL = """
            package de.splatgames.aether.weaver.api;

            public @interface Local {
                String value() default "";
            }
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

    private static final String TYPES = """
            package fixture;

            public class Money { }
            """;

    private static final String OTHER_TYPE = """
            package fixture;

            public class Receipt { }
            """;

    private static final String TARGET = """
            package fixture;

            public class Gateway {
                public Receipt charge(Money amount, Money fee) { return null; }
                public void close() { }
                public void log(Money... parts) { }
                public void settle(Money amount) { }
                public void settle(Receipt receipt) { }
            }
            """;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Weave.java", WEAVE);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Inject.java", INJECT);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Redirect.java", REDIRECT);
        myFixture.addFileToProject("de/splatgames/aether/weaver/api/Local.java", LOCAL);
        myFixture.addFileToProject(
                "de/splatgames/aether/weaver/api/callback/Callback.java", CALLBACK);
        myFixture.addFileToProject(
                "de/splatgames/aether/weaver/api/callback/ReturnableCallback.java",
                RETURNABLE_CALLBACK);
        myFixture.addFileToProject("fixture/Money.java", TYPES);
        myFixture.addFileToProject("fixture/Receipt.java", OTHER_TYPE);
        myFixture.addFileToProject("fixture/Gateway.java", TARGET);
        myFixture.enableInspections(new HandlerSignatureInspection());
    }

    public void testAWrongParameterTypeIsReported() {
        weave("", """
                @Inject(method = "charge(fixture.Money, fixture.Money)")
                void onCharge(Receipt amount) { }
                """);

        assertTrue("this is the whole point of the inspection: " + problems(),
                describes("AW1040") && describes("fixture.Receipt"));
    }

    public void testTooManyParametersAreReported() {
        weave("", """
                @Inject(method = "close()")
                void onClose(Money amount) { }
                """);

        assertTrue("" + problems(), describes("AW1040"));
    }

    public void testANonVoidHandlerIsReported() {
        weave("", """
                @Inject(method = "nothing.the.target.has()")
                int onCharge() { return 0; }
                """);

        assertTrue("'an @Inject handler returns void' is true of every handler ever written, so it "
                        + "can be said while the selector is still being typed: " + problems(),
                describes("AW1041"));
    }

    public void testAWrongCallbackTypeIsReported() {
        weave("", """
                @Inject(method = "charge(fixture.Money, fixture.Money)")
                void onCharge(ReturnableCallback<Money> callback) { }
                """);

        assertTrue("" + problems(), describes("AW1071") && describes("fixture.Receipt"));
    }

    public void testAShorterPrefixIsAccepted() {
        weave("", """
                @Inject(method = "charge(fixture.Money, fixture.Money)")
                void onCharge(Money amount) { }
                """);

        assertEquals("'a handler may take the first n of them and nothing else' — one of two is a "
                        + "prefix, and reporting it would make the documented feature unusable",
                List.of(), problems());
    }

    public void testALocalCaptureIsNotCounted() {
        weave("", """
                @Inject(method = "close()")
                void onClose(@Local("attempts") Money captured) { }
                """);

        assertEquals("counting it makes every handler that captures a local look like it takes an "
                        + "argument too many — a false positive on the shape @Local exists for",
                List.of(), problems());
    }

    public void testAStaticWeaveReceiverIsNotCounted() {
        weave("value = Gateway.class, kind = Weave.Kind.STATIC", """
                @Inject(method = "close()")
                static void onClose(Gateway gateway) { }
                """);

        assertEquals("the framework's own remedy for AW1005 is 'declare it static and take the "
                        + "target as the first parameter'; counting that parameter would report "
                        + "every handler written the way the framework asks for",
                List.of(), problems());
    }

    public void testVarargsMatchTheArrayForm() {
        weave("", """
                @Inject(method = "log(fixture.Money[])")
                void onLog(Money[] parts) { }
                """);

        assertEquals("Money... IS Money[] once compiled; comparing the two spellings literally "
                        + "reports a handler that is in fact exactly right",
                List.of(), problems());
    }

    public void testAWrongVarargsElementTypeIsReported() {
        weave("", """
                @Inject(method = "log(fixture.Money[])")
                void onLog(Receipt[] parts) { }
                """);

        assertTrue("if this is silent, the selector never resolved and the silence next door means "
                        + "nothing: " + problems(),
                describes("AW1040"));
    }

    public void testAnOverloadIsPickedByItsTypes() {
        weave("", """
                @Inject(method = "settle(fixture.Receipt)")
                void onSettle(Money amount) { }
                """);

        assertTrue("silence here means the two settle overloads were separated by arity alone, "
                        + "which cannot separate them at all: " + problems(),
                describes("AW1040"));
    }

    public void testAnAmbiguousSelectorIsLeftAlone() {
        weave("", """
                @Inject(method = "settle")
                void onSettle(Receipt wrong) { }
                """);

        assertEquals("naming an overload set is AW1021 and belongs to whoever owns that code; "
                        + "guessing which one was meant is how a plugin reports correct code",
                List.of(), problems());
    }

    public void testAnUnresolvedTypeIsNotReported() {
        weave("", """
                @Inject(method = "charge(fixture.Money, fixture.Money)")
                void onCharge(Nonexistent amount) { }
                """);

        assertEquals("a type the IDE cannot see is the ordinary state of a file being written; "
                        + "reporting a prefix violation against one would underline correct code",
                List.of(), problems());
    }

    public void testARedirectIsNotChecked() {
        weave("", """
                @Redirect(method = "charge(fixture.Money, fixture.Money)")
                Receipt onCharge(Receipt other) { return other; }
                """);

        assertEquals("a redirect replaces an operation instead of being called alongside the "
                        + "target, so neither the prefix rule nor the void rule applies to it",
                List.of(), problems());
    }

    public void testAMethodOutsideAWeaveIsNotChecked() {
        myFixture.configureByText("NotAWeave.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.Inject;

                public final class NotAWeave {
                    @Inject(method = "charge(fixture.Money, fixture.Money)")
                    int onCharge(Receipt wrong) { return 0; }
                }
                """);

        assertEquals("this plugin does not claim annotations it did not define", List.of(),
                problems());
    }

    public void testTheFixCorrectsTypesAndKeepsTheRest() {
        weave("", """
                @Inject(method = "charge(fixture.Money, fixture.Money)")
                void onCharge(Receipt amount, ReturnableCallback<Receipt> callback) { }
                """);

        applyTheFix();

        final String text = myFixture.getFile().getText();
        assertTrue("the argument's type becomes the target's: " + text,
                text.contains("Money amount"));
        assertTrue("the name the author chose survives, because the body already uses it: " + text,
                text.contains("amount"));
        assertTrue("the callback is not an argument and is none of this fix's business: " + text,
                text.contains("ReturnableCallback<Receipt> callback"));
    }

    public void testTheFixDoesNotWidenWhatTheAuthorNarrowed() {
        weave("", """
                @Inject(method = "close()")
                void onClose(Money first, Money second) { }
                """);

        applyTheFix();

        final String text = myFixture.getFile().getText();
        assertTrue("the target takes nothing, so the handler may take nothing: " + text,
                text.contains("onClose()"));
    }

    public void testWhatTheFixWritesIsAccepted() {
        weave("", """
                @Inject(method = "charge(fixture.Money, fixture.Money)")
                void onCharge(Receipt amount) { }
                """);

        applyTheFix();

        assertEquals("the fix and the inspection share one model of the rule precisely so that this "
                        + "cannot drift apart: " + problems(),
                List.of(), problems());
    }

    public void testAValuelessCancelIsReported() {
        weave("", """
                @Inject(method = "charge(fixture.Money, fixture.Money)")
                void onCharge(Callback callback) {
                    callback.cancel();
                }
                """);

        assertTrue("charge returns a Receipt, so cancelling has to say what it returns instead: "
                        + problems(),
                describes("AW1070"));
    }

    public void testCancellingAVoidTargetIsAccepted() {
        weave("", """
                @Inject(method = "close()")
                void onClose(Callback callback) {
                    callback.cancel();
                }
                """);

        assertEquals("close() returns nothing, so cancel() is exactly right", List.of(), problems());
    }

    public void testACancelThroughAHelperIsNotReported() {
        weave("", """
                @Inject(method = "charge(fixture.Money, fixture.Money)")
                void onCharge(Callback callback) {
                    stop(callback);
                }

                private void stop(Callback other) {
                    other.cancel();
                }
                """);

        assertEquals("the call is written on a parameter of stop, not on the handler's own callback",
                List.of(), problems());
    }

    private void weave(final String declaration, final String body) {
        final String targets = declaration.isEmpty() ? "Gateway.class" : declaration;
        myFixture.configureByText("Audit.java", """
                package fixture;

                import de.splatgames.aether.weaver.api.Inject;
                import de.splatgames.aether.weaver.api.Local;
                import de.splatgames.aether.weaver.api.Redirect;
                import de.splatgames.aether.weaver.api.Weave;
                import de.splatgames.aether.weaver.api.callback.Callback;
                import de.splatgames.aether.weaver.api.callback.ReturnableCallback;

                @Weave(%s)
                public final class Audit {
                %s
                }
                """.formatted(targets, body.indent(4)));
    }

    private void applyTheFix() {
        for (final IntentionAction fix : myFixture.getAllQuickFixes()) {
            if (fix.getFamilyName().equals("Adjust handler parameters to the target")) {
                myFixture.launchAction(fix);
                return;
            }
        }
        fail("the inspection reported nothing to fix, or offered no fix for it: " + problems());
    }

    private List<String> problems() {
        final List<String> found = new ArrayList<>();
        for (final HighlightInfo info : myFixture.doHighlighting()) {
            if (info.getDescription() != null && info.getDescription().startsWith("AW")) {
                found.add(info.getDescription());
            }
        }
        return found;
    }

    private boolean describes(final String fragment) {
        for (final String problem : problems()) {
            if (problem.contains(fragment)) {
                return true;
            }
        }
        return false;
    }
}
