package de.splatgames.aether.weaver.engine.inject.point;

import de.splatgames.aether.weaver.api.At;
import de.splatgames.aether.weaver.api.Point;
import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.model.HandlerRef;
import de.splatgames.aether.weaver.api.model.InjectorKind;
import de.splatgames.aether.weaver.api.model.InjectorSpec;
import de.splatgames.aether.weaver.api.model.PointSpec;
import de.splatgames.aether.weaver.api.select.MemberKind;
import de.splatgames.aether.weaver.api.select.MemberSelector;
import de.splatgames.aether.weaver.engine.parse.PointTargets;
import de.splatgames.aether.weaver.api.model.SliceSpec;
import de.splatgames.aether.weaver.api.spi.CodeView;
import de.splatgames.aether.weaver.api.spi.MethodView;
import de.splatgames.aether.weaver.api.spi.InjectionPoint;
import de.splatgames.aether.weaver.api.spi.Reporter;
import de.splatgames.aether.weaver.api.spi.Site;
import de.splatgames.aether.weaver.api.spi.TargetView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PointResolverTest {

    private static final TargetView TARGET = compileFixture();

    private final List<Diagnostic> reported = new ArrayList<>();

    private final Reporter reporter = this.reported::add;

    private final PointResolver resolver = new PointResolver(BuiltInPoints.all()::get);

    @Nested
    @DisplayName("HEAD")
    class Head {

        @Test
        @DisplayName("an ordinary method yields exactly one site at its first instruction")
        void ordinaryMethod() {
            final List<Site> sites = resolve("work", at(Point.HEAD));

            assertThat(sites).singleElement().satisfies(site -> {
                assertThat(site.kind()).isEqualTo(Site.Kind.METHOD_ENTRY);
                assertThat(body("work").elements().get(site.index()))
                        .as("elementList() carries pseudo-elements — labels, line numbers, local "
                                + "variable declarations — so the entry is the first real "
                                + "INSTRUCTION, which is not index 0")
                        .isInstanceOf(java.lang.classfile.Instruction.class);
                assertThat(body("work").elements().subList(0, site.index()))
                        .noneMatch(java.lang.classfile.Instruction.class::isInstance);
            });
        }

        @Test
        @DisplayName("in a constructor the site is AFTER the initialiser call")
        void constructorHeadIsAfterSuper() {
            final CodeView body = body("<init>");
            final List<Site> sites = resolve("<init>", at(Point.HEAD));

            assertThat(sites).singleElement().satisfies(site -> {
                assertThat(site.index())
                        .as("before the superclass initialiser has run, `this` is uninitialised "
                                + "and the verifier rejects almost every use of it")
                        .isGreaterThan(0);
                final int superCall = indexOfInitialiserCall(body);
                assertThat(site.index()).isEqualTo(superCall + 1);
            });
        }

        @Test
        @DisplayName("a target is refused")
        void targetIsForbidden() {
            assertThat(resolve("work", PointSpec.builtIn(Point.HEAD).target("#anything").build()))
                    .isEmpty();
            assertThat(codes()).containsExactly("AW1043");
        }

        @Test
        @DisplayName("a shift is refused")
        void shiftIsRefused() {
            assertThat(resolve("work",
                    PointSpec.builtIn(Point.HEAD).shift(At.Shift.AFTER).build())).isEmpty();
            assertThat(codes()).containsExactly("AW1102");
        }
    }

    @Nested
    @DisplayName("RETURN and TAIL")
    class Returns {

        @Test
        @DisplayName("RETURN finds every return, TAIL only the last")
        void returnsAndTail() {
            final List<Site> all = resolve("branching", at(Point.RETURN));
            final List<Site> tail = resolve("branching", at(Point.TAIL));

            assertThat(all)
                    .as("the fixture returns from two places")
                    .hasSizeGreaterThanOrEqualTo(2);
            assertThat(tail).singleElement()
                    .satisfies(site -> assertThat(site.index()).isEqualTo(all.getLast().index()));
        }

        @Test
        @DisplayName("TAIL is not 'every exit' — an early return does not fire it")
        void tailIsNotEveryExit() {
            final List<Site> all = resolve("branching", at(Point.RETURN));
            final List<Site> tail = resolve("branching", at(Point.TAIL));

            assertThat(tail.getFirst().index())
                    .as("a method with an early return still exits through it without firing TAIL; "
                            + "this is the misunderstanding that has cost Mixin users the most time")
                    .isNotEqualTo(all.getFirst().index());
        }
    }

    @Nested
    @DisplayName("INVOKE")
    class Invoke {

        @Test
        @DisplayName("a name-only selector matches regardless of owner")
        void nameOnlyMatches() {
            assertThat(resolve("work", at(Point.INVOKE, "#helper"))).isNotEmpty();
        }

        @Test
        @DisplayName("an ordinal selects one of several matches")
        void ordinalSelects() {
            final List<Site> all = resolve("work", at(Point.INVOKE, "#helper"));
            final List<Site> second = resolve("work",
                    PointSpec.builtIn(Point.INVOKE).target("#helper").ordinal(1).build());

            assertThat(all).hasSizeGreaterThanOrEqualTo(2);
            assertThat(second).singleElement()
                    .satisfies(site -> assertThat(site.index()).isEqualTo(all.get(1).index()));
        }

        @Test
        @DisplayName("a descriptor-form target matches the call it names")
        void descriptorFormMatches() {
            // It did not, for as long as the parsed path existed: a desc: owner arrives as a
            // TypePattern.Exact, whose toString is `Lpointfixture/Target;`, and that was compared
            // against the instruction's binary name. No input could have satisfied it, so the one
            // form the API documents as naming exactly one member matched nothing at all.
            assertThat(resolve("work", parsed(Point.INVOKE, "desc:pointfixture/Target.helper"
                    + "(Ljava/lang/String;)Ljava/lang/String;")))
                    .hasSize(3);
        }

        @Test
        @DisplayName("a descriptor-form owner is exact, and does not match by simple name")
        void descriptorFormOwnerIsExact() {
            assertThat(resolve("work",
                    parsed(Point.INVOKE, "desc:Target.helper(Ljava/lang/String;)Ljava/lang/String;")))
                    .as("the descriptor form promises to name exactly one member; suffix-matching "
                            + "its owner would take the one unambiguous form and make it ambiguous")
                    .isEmpty();
        }

        @Test
        @DisplayName("a parameter list narrows the match")
        void signatureNarrows() {
            // Rule three of this framework's own documentation — "Gateway.send(Payment) matches
            // exactly one shape" — was not implemented on the path production takes. A selector
            // written *because* the target has overloads was the one that ignored them, and the
            // injection landed on a call the author had not named, with no diagnostic anywhere.
            assertThat(resolve("work",
                    parsed(Point.INVOKE, "pointfixture.Target.helper(java.lang.String)")))
                    .hasSize(3);
            assertThat(resolve("work", parsed(Point.INVOKE, "pointfixture.Target.helper(int)")))
                    .as("there is no helper(int); matching one anyway is how a weave binds to the "
                            + "wrong instruction silently")
                    .isEmpty();
        }

        @Test
        @DisplayName("a signature that matches nothing says why")
        void signatureMismatchExplainsItself() {
            resolve("work", parsed(Point.INVOKE, "pointfixture.Target.helper(int)"));

            assertThat(reported.getFirst().details())
                    .as("the listing prints every candidate's descriptor, but only a reader who "
                            + "already knows that a parameter list narrows can use that")
                    .anySatisfy(detail -> assertThat(detail).contains("names a signature"));
        }

        @Test
        @DisplayName("an omitted parameter list still matches any signature")
        void anOmittedParameterListStillMatchesAnything() {
            assertThat(resolve("work", parsed(Point.INVOKE, "pointfixture.Target.helper")))
                    .as("narrowing on a list that was written must not turn into narrowing on one "
                            + "that was not; the owner-and-name form is the middle rule and it has "
                            + "to keep matching regardless of signature")
                    .hasSize(3);
        }

        @Test
        @DisplayName("a simple-name owner still matches by suffix")
        void simpleOwnerMatchesBySuffix() {
            assertThat(resolve("work", parsed(Point.INVOKE, "Target.helper(java.lang.String)")))
                    .hasSize(3);
        }

        @Test
        @DisplayName("a wildcard name matches every member of the owner")
        void wildcardNameMatchesEveryMember() {
            assertThat(resolve("work", parsed(Point.INVOKE, "pointfixture.Target.*")))
                    .as("helper three times, begin and commit")
                    .hasSize(5);
        }

        @Test
        @DisplayName("an out-of-range ordinal says how many were found")
        void ordinalOutOfRange() {
            assertThat(resolve("work",
                    PointSpec.builtIn(Point.INVOKE).target("#helper").ordinal(99).build()))
                    .isEmpty();

            assertThat(codes()).containsExactly("AW1110");
            assertThat(reported.getFirst().message())
                    .as("the count is what turns a 20-minute investigation into a 5-second fix")
                    .contains("ordinal 99")
                    .contains("found");
        }

        @Test
        @DisplayName("a non-match lists the invocations that ARE there")
        void nonMatchListsCandidates() {
            assertThat(resolve("work", at(Point.INVOKE, "#nothingIsCalledThis"))).isEmpty();

            assertThat(codes()).containsExactly("AW1043");
            assertThat(reported.getFirst().details())
                    .as("this listing is the highest-value diagnostic in the system")
                    .anySatisfy(d -> assertThat(d).contains("helper"));
        }

        @Test
        @DisplayName("invokedynamic is skipped, and the message says so")
        void invokeDynamicIsExplained() {
            resolve("concatenating", at(Point.INVOKE, "#makeConcatWithConstants"));

            assertThat(reported.getFirst().details())
                    .as("'why doesn't my selector match string concatenation' is otherwise a "
                            + "mystifying afternoon")
                    .anySatisfy(d -> assertThat(d).contains("invokedynamic"));
        }

        @Test
        @DisplayName("INVOKE_AFTER matches the same instruction and emits on the other side")
        void invokeAfterIsTheOtherSide() {
            final List<Site> before = resolve("work", at(Point.INVOKE, "#helper"));
            final List<Site> after = resolve("work", at(Point.INVOKE_AFTER, "#helper"));

            assertThat(after).hasSameSizeAs(before);
            assertThat(after.getFirst().kind()).isEqualTo(Site.Kind.AFTER_ELEMENT);
            assertThat(before.getFirst().kind()).isEqualTo(Site.Kind.BEFORE_ELEMENT);
            assertThat(after.getFirst().index())
                    .as("""
                            This assertion used to read isEqualTo, and that is how the defect \
                            survived: the two sites carried different Kinds and the same index, \
                            and every injector emits BEFORE the index it is handed. Nothing read \
                            the Kind, so INVOKE_AFTER emitted exactly where INVOKE does — before \
                            the call, with the call's result not yet on the stack.

                            The test's own name said "the other side" while its assertion said \
                            "the same place", which is why it passed.""")
                    .isEqualTo(before.getFirst().index() + 1);
        }

        @Test
        @DisplayName("and the way back names the call again, for a caller walking instructions")
        void theMatchedInstructionIsRecoverable() {
            final List<Site> before = resolve("work", at(Point.INVOKE, "#helper"));
            final List<Site> after = resolve("work", at(Point.INVOKE_AFTER, "#helper"));

            assertThat(PointResolver.matchedIndexOf(after.getFirst()))
                    .as("""
                            The other half of the translation above, and it is not decoration. \
                            A caller that walks the instruction stream asking "did this \
                            declaration select THAT instruction" compares in the point's \
                            coordinate, while a resolved site is in the emitter's. The IDE \
                            plugin's operation enumeration does exactly that and silently offered \
                            nothing at all for INVOKE_AFTER — every candidate compared against a \
                            site one further on, and no dialog ever showed the after-side of a \
                            call again.

                            It failed silently because "no operations" is also what a method with \
                            no calls answers.""")
                    .isEqualTo(before.getFirst().index());
        }

        @Test
        @DisplayName("the way back leaves every other kind alone")
        void onlyTheAfterSideMoves() {
            for (final Site site : resolve("work", at(Point.INVOKE, "#helper"))) {
                assertThat(PointResolver.matchedIndexOf(site))
                        .as("a before-site is already the instruction it matched, and subtracting "
                                + "from it would name whatever precedes the call")
                        .isEqualTo(site.index());
            }
        }

        @Test
        @DisplayName("a missing target is refused rather than matching everything")
        void targetIsRequired() {
            assertThat(resolve("work", PointSpec.builtIn(Point.INVOKE).build())).isEmpty();
            assertThat(codes()).containsExactly("AW1043");
        }
    }

    @Nested
    @DisplayName("what an invokedynamic hides")
    class HiddenCalls {

        @Test
        @DisplayName("AW1103 — a method reference the selector names is reported, and the ordinary call is still woven")
        void aMethodReferenceIsReported() {
            final List<Site> sites = resolve("referencing", at(Point.INVOKE, "#describe"));

            assertThat(sites)
                    .as("the ordinary call is a perfectly good INVOKE site and must still resolve")
                    .hasSize(1);
            assertThat(codes())
                    .as("this is the case AW1043's listing cannot cover: something DID match, so "
                            + "the injection succeeds and its accounting is satisfied, while one of "
                            + "the places the author meant is quietly not woven")
                    .containsExactly("AW1103");
        }

        @Test
        @DisplayName("the report names the method behind the reference, not the interface's")
        void theReportNamesTheRealMethod() {
            resolve("referencing", at(Point.INVOKE, "#describe"));

            assertThat(reported.getFirst().details())
                    .as("the instruction's own name is the functional interface's — `get` here — "
                            + "while the method the author wrote travels as a MethodHandle among "
                            + "the bootstrap arguments. Reporting the former would name something "
                            + "nobody wrote")
                    .anySatisfy(detail -> assertThat(detail).contains("describe"));
        }

        @Test
        @DisplayName("a selector that names nothing in the lambda is silent")
        void anUnrelatedSelectorIsSilent() {
            resolve("referencing", at(Point.INVOKE, "#helper"));

            assertThat(codes())
                    .as("without this the tests above would pass against a check that fired "
                            + "whenever a method contained any invokedynamic at all, which is most "
                            + "modern methods")
                    .doesNotContain("AW1103");
        }
    }

    @Nested
    @DisplayName("FIELD, NEW, CONSTANT and THROW")
    class OtherPoints {

        @Test
        @DisplayName("FIELD filters by access kind")
        void fieldFiltersByAccess() {
            final List<Site> any = resolve("work",
                    PointSpec.builtIn(Point.FIELD).target("#counter").build());
            final List<Site> writes = resolve("work", PointSpec.builtIn(Point.FIELD)
                    .target("#counter").access(At.Access.PUT).build());

            assertThat(any).isNotEmpty();
            assertThat(writes).hasSizeLessThanOrEqualTo(any.size());
            assertThat(writes).isNotEmpty();
        }

        @Test
        @DisplayName("FIELD narrows on the field's type, in either form")
        void fieldNarrowsOnType() {
            // The same rule as a method's parameter list, and it has to be the same rule: a user
            // who moves a selector from INVOKE to FIELD must not find that it means something else
            // there. `counter` is an int, so the long is a field this target does not have.
            assertThat(resolve("work", parsed(Point.FIELD, "pointfixture.Target.counter:int")))
                    .isNotEmpty();
            assertThat(resolve("work", parsed(Point.FIELD, "pointfixture.Target.counter:long")))
                    .isEmpty();
            assertThat(resolve("work", parsed(Point.FIELD, "desc:pointfixture/Target.counter:I")))
                    .isNotEmpty();
        }

        @Test
        @DisplayName("NEW matches a type, not a member")
        void newMatchesAType() {
            assertThat(resolve("allocating", at(Point.NEW, "java.lang.StringBuilder")))
                    .as("Point.NEW names a class; forcing it through the member grammar would "
                            + "either fail or succeed with the wrong meaning")
                    .isNotEmpty();
            assertThat(resolve("allocating", at(Point.NEW, "StringBuilder")))
                    .as("the simple name works too")
                    .isNotEmpty();
        }

        @Test
        @DisplayName("CONSTANT matches a small integer, which is not an ldc")
        void constantMatchesIntrinsics() {
            assertThat(resolve("constants", at(Point.CONSTANT, "int:7")))
                    .as("iconst_* and bipush carry their value without an ldc; matching only ldc "
                            + "silently misses every small integer, which is most of them")
                    .isNotEmpty();
        }

        @Test
        @DisplayName("CONSTANT matches a string too")
        void constantMatchesStrings() {
            assertThat(resolve("constants", at(Point.CONSTANT, "string:retry"))).isNotEmpty();
        }

        @Test
        @DisplayName("CONSTANT matches the canonical spelling the API itself renders")
        void constantMatchesItsOwnRendering() {
            // Every one of these is what ConstantSelector.render(SOURCE) produces, and every one
            // of them used to match nothing. The point compared the target's text, minus everything
            // up to its first colon, against String.valueOf(value): so string:"retry" was compared
            // as "retry" with quotes against retry without them, and class:java.lang.Void against
            // ClassOrInterfaceDesc[Void]. Three of the grammar's seven kinds could never match.
            assertThat(resolve("constants", parsed(Point.CONSTANT, "string:\"retry\"")))
                    .as("a string constant is the one people reach for first")
                    .isNotEmpty();
            assertThat(resolve("constants", parsed(Point.CONSTANT, "class:java.lang.Void")))
                    .isNotEmpty();
            assertThat(resolve("constants", parsed(Point.CONSTANT, "int:7")))
                    .as("the numeric kinds worked before and must keep working")
                    .isNotEmpty();
        }

        @Test
        @DisplayName("CONSTANT still matches a target that is not a constant selector")
        void constantStillMatchesRawText() {
            // string:retry is a *field* selector by the grammar's own disambiguation rule — string
            // is not a keyword, so an unquoted value is a field named retry on a type named string.
            // It reached the constant by text comparison before and must keep doing so.
            assertThat(resolve("constants", at(Point.CONSTANT, "string:retry"))).isNotEmpty();
        }

        @Test
        @DisplayName("Silence: a constant the target does not load matches nothing")
        void constantDoesNotMatchTheWrongValue() {
            assertThat(resolve("constants", parsed(Point.CONSTANT, "string:\"other\"")))
                    .as("comparing values rather than renderings must not turn into matching "
                            + "everything")
                    .isEmpty();
            assertThat(resolve("constants", parsed(Point.CONSTANT, "int:8"))).isEmpty();
        }

        @Test
        @DisplayName("THROW finds an athrow")
        void throwFindsAthrow() {
            assertThat(resolve("throwing", at(Point.THROW))).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("slices")
    class Slices {

        @Test
        @DisplayName("a slice narrows where the search runs")
        void sliceNarrowsTheSearch() {
            final List<Site> whole = resolve("work", at(Point.INVOKE, "#helper"));
            final List<Site> sliced = resolveSliced("work",
                    PointSpec.builtIn(Point.INVOKE).target("#helper").slice("tx").build(),
                    slice("tx", "#begin", "#commit"));

            assertThat(sliced).hasSizeLessThan(whole.size());
            assertThat(sliced).isNotEmpty();
        }

        @Test
        @DisplayName("ordinals are counted within the slice")
        void ordinalsRenumberInsideASlice() {
            final List<Site> firstInSlice = resolveSliced("work",
                    PointSpec.builtIn(Point.INVOKE).target("#helper").ordinal(0).slice("tx").build(),
                    slice("tx", "#begin", "#commit"));
            final List<Site> firstOverall = resolve("work",
                    PointSpec.builtIn(Point.INVOKE).target("#helper").ordinal(0).build());

            assertThat(firstInSlice).singleElement().satisfies(site -> assertThat(site.index())
                    .as("a slice is a statement about where to look, so 'the first match' means "
                            + "the first match in the place you said to look")
                    .isNotEqualTo(firstOverall.getFirst().index()));
        }

        @Test
        @DisplayName("an unresolvable bound is refused rather than silently widening")
        void unresolvableBoundIsRefused() {
            assertThat(resolveSliced("work",
                    PointSpec.builtIn(Point.INVOKE).target("#helper").slice("tx").build(),
                    slice("tx", "#doesNotExist", "#commit")))
                    .isEmpty();

            assertThat(codes()).containsExactly("AW1120");
        }

        @Test
        @DisplayName("AW1121 — and the `to` bound, which had the same code and no test")
        void unresolvableUpperBoundIsRefused() {
            assertThat(resolveSliced("work",
                    PointSpec.builtIn(Point.INVOKE).target("#helper").slice("tx").build(),
                    slice("tx", "#begin", "#doesNotExist")))
                    .isEmpty();

            assertThat(codes())
                    .as("""
                            The two bounds go through one `boundOf` and differ only in the code \
                            they are handed, which is exactly the arrangement where a copied \
                            argument survives: AW1120 was asserted and AW1121 was reported by \
                            nobody's test. It turned out correct. That is a fact about this \
                            resolver and was not a fact about the suite.""")
                    .containsExactly("AW1121");
        }

        @Test
        @DisplayName("a bound is a parsed selector, the way the parser builds one")
        void boundsAreParsedSelectors() {
            // Every other test in this class bounds the slice with raw text, which is the shape
            // WeaveClassParser never produces: readSlice goes through readPoint, so a bound arrives
            // at the resolver parsed exactly like any other @At. Raw text is compared by splitting
            // it at its last dot, a parsed selector is not, and a qualified owner is where the two
            // come apart — so the form the framework actually uses needs a test of its own.
            assertThat(resolveSliced("work",
                    PointSpec.builtIn(Point.INVOKE).target("#helper").ordinal(0).slice("tx").build(),
                    parsedSlice("tx", "pointfixture.Target.begin()",
                            "pointfixture.Target.commit()")))
                    .singleElement()
                    .satisfies(site -> assertThat(site.index()).isEqualTo(
                            resolveSliced("work",
                                    PointSpec.builtIn(Point.INVOKE).target("#helper").ordinal(0)
                                            .slice("tx").build(),
                                    slice("tx", "#begin", "#commit"))
                                    .getFirst().index()));
        }

        @Test
        @DisplayName("a bound in descriptor form selects the same position as in source form")
        void descriptorBoundsSelectTheSamePosition() {
            // The question a generator has to answer before it may offer the descriptor form for a
            // slice: a bound is an @At like any other, so nothing here is slice-specific — but
            // "nothing is slice-specific" is a claim about a code path, and this is the measurement
            // rather than the claim.
            final PointSpec query =
                    PointSpec.builtIn(Point.INVOKE).target("#helper").ordinal(0).slice("tx").build();

            assertThat(resolveSliced("work", query,
                    parsedSlice("tx", "desc:pointfixture/Target.begin()V",
                            "desc:pointfixture/Target.commit()V")))
                    .singleElement()
                    .satisfies(site -> assertThat(site.index()).isEqualTo(
                            resolveSliced("work", query,
                                    parsedSlice("tx", "pointfixture.Target.begin()",
                                            "pointfixture.Target.commit()"))
                                    .getFirst().index()));
            assertThat(codes()).isEmpty();
        }
    }

    @Nested
    @DisplayName("shift")
    class Shift {

        @Test
        @DisplayName("AFTER moves the site one element on")
        void afterMovesForward() {
            final List<Site> plain = resolve("work", at(Point.INVOKE, "#helper"));
            final List<Site> shifted = resolve("work", PointSpec.builtIn(Point.INVOKE)
                    .target("#helper").ordinal(0).shift(At.Shift.AFTER).build());

            assertThat(shifted).singleElement().satisfies(site ->
                    assertThat(site.index()).isEqualTo(plain.getFirst().index() + 1));
        }

        @Test
        @DisplayName("a large BY offset warns but still resolves")
        void largeOffsetWarns() {
            final List<Site> shifted = resolve("work", PointSpec.builtIn(Point.INVOKE)
                    .target("#helper").ordinal(0).shift(At.Shift.BY).by(5).build());

            assertThat(shifted)
                    .as("occasionally legitimate; a framework that forbids the escape hatch gets "
                            + "forked")
                    .isNotEmpty();
            assertThat(codes()).contains("AW1112");
        }

        @Test
        @DisplayName("shifting out of the slice is refused")
        void shiftOutOfRangeIsRefused() {
            assertThat(resolve("work", PointSpec.builtIn(Point.INVOKE)
                    .target("#helper").ordinal(0).shift(At.Shift.BY).by(-99999).build()))
                    .isEmpty();
            assertThat(codes()).contains("AW1111");
        }
    }

    @Nested
    @DisplayName("the registry")
    class Registry {

        @Test
        @DisplayName("a point's target requirement can be asked without implementing one")
        void requirementIsQueryable() {
            // InjectionPoint.targetRequirement() is @ApiStatus.OverrideOnly — a question the
            // framework asks an implementation, not one a caller asks the framework. A tool that
            // wants the same answer needs a query, and the IntelliJ Plugin Verifier says so out
            // loud: calling the SPI method from the plugin failed verification, which is how this
            // method came to exist rather than a second list of which points take targets.
            assertThat(BuiltInPoints.requirementOf(Point.INVOKE.name()))
                    .isEqualTo(InjectionPoint.TargetRequirement.REQUIRED);
            assertThat(BuiltInPoints.requirementOf(Point.HEAD.name()))
                    .isEqualTo(InjectionPoint.TargetRequirement.FORBIDDEN);
        }

        @Test
        @DisplayName("a point nobody registered has no answer, rather than a default one")
        void unknownPointHasNoRequirement() {
            assertThat(BuiltInPoints.requirementOf("acme:NOT_INSTALLED"))
                    .as("a contributed point defines its own rules, and inventing OPTIONAL for it "
                            + "would let a tool claim something about a point it has never seen")
                    .isNull();
        }
    }

    @Nested
    @DisplayName("unknown points")
    class Unknown {

        @Test
        @DisplayName("an unregistered identifier is refused with a usable message")
        void unknownPointIsRefused() {
            assertThat(resolve("work", PointSpec.named("acme:NOT_INSTALLED").build())).isEmpty();
            assertThat(reported.getFirst().remedy())
                    .hasValueSatisfying(r -> assertThat(r).contains("namespace"));
            assertThat(codes())
                    .as("""
                            This test checked the remedy and never the code, and that is how the \
                            two drifted apart. AW1101 was named INJECTION_POINT_RESERVED_NAMESPACE \
                            and summarised as "custom injection point uses a reserved namespace", \
                            while the only thing that reports it is this lookup failing. A reader \
                            who looked the code up got a description of a different mistake.""")
                    .containsExactly("AW1101");
        }

        @Test
        @DisplayName("an unqualified custom identifier lands here too, because nothing can register it")
        void anUnqualifiedCustomIdentifierIsUnknown() {
            assertThat(resolve("work", PointSpec.named("AFTER_LOGGING").build())).isEmpty();

            assertThat(codes())
                    .as("""
                            @At.custom promised AW1101 for "an identifier without a namespace" and \
                            nothing asserted that it arrives. It does — not by a check of its own, \
                            but because the unqualified namespace is not open to factories, so no \
                            such point can be registered. The javadoc now says that rather than \
                            implying a separate rule.""")
                    .containsExactly("AW1101");
        }
    }

    // --- fixtures -------------------------------------------------------------------------

    private List<Site> resolve(final String method, final PointSpec spec) {
        return this.resolver.resolve(method(method), body(method),
                injector(spec, List.of()), spec, this.reporter);
    }

    private List<Site> resolveSliced(final String method, final PointSpec spec,
                                     final SliceSpec slice) {
        return this.resolver.resolve(method(method), body(method),
                injector(spec, List.of(slice)), spec, this.reporter);
    }

    private List<String> codes() {
        return this.reported.stream().map(d -> d.code().code()).toList();
    }

    private static PointSpec at(final Point point) {
        return PointSpec.builtIn(point).build();
    }

    private static PointSpec at(final Point point, final String target) {
        return PointSpec.builtIn(point).target(target).build();
    }

    private static PointSpec parsed(final Point point, final String target) {
        final MemberKind expected = PointTargets.selectorKindFor(point.name());
        return PointSpec.builtIn(point)
                .target(target, MemberSelector.parse(target, expected))
                .build();
    }

    private static SliceSpec slice(final String id, final String from, final String to) {
        return new SliceSpec(id,
                PointSpec.builtIn(Point.INVOKE).target(from).ordinal(0).build(),
                PointSpec.builtIn(Point.INVOKE).target(to).ordinal(0).build());
    }

    private static SliceSpec parsedSlice(final String id, final String from, final String to) {
        return new SliceSpec(id,
                PointSpec.builtIn(Point.INVOKE)
                        .target(from, MemberSelector.parse(from, MemberKind.METHOD))
                        .ordinal(0).build(),
                PointSpec.builtIn(Point.INVOKE)
                        .target(to, MemberSelector.parse(to, MemberKind.METHOD))
                        .ordinal(0).build());
    }

    private static InjectorSpec injector(final PointSpec spec, final List<SliceSpec> slices) {
        return new InjectorSpec(InjectorKind.INJECT,
                new HandlerRef(ClassDesc.of("com.acme.W"), "handler",
                        MethodTypeDesc.of(ConstantDescs.CD_void), Set.of()),
                "work()", MemberSelector.parse("work()"),
                List.of(spec), slices, "handler", 1, 0, "", List.of());
    }

    private static MethodView method(final String name) {
        return TARGET.methods().stream()
                .filter(m -> name.equals(m.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the fixture must declare " + name));
    }

    private static CodeView body(final String name) {
        return method(name).code().orElseThrow();
    }

    private static int indexOfInitialiserCall(final CodeView code) {
        final List<java.lang.classfile.CodeElement> elements = code.elements();
        for (int i = 0; i < elements.size(); i++) {
            if (elements.get(i) instanceof java.lang.classfile.instruction.InvokeInstruction invoke
                    && "<init>".equals(invoke.name().stringValue())) {
                return i;
            }
        }
        throw new AssertionError("the fixture constructor must call an initialiser");
    }

    private static TargetView compileFixture() {
        try {
            final Path output = Files.createTempDirectory("aether-weaver-points");
            final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            try (StandardJavaFileManager files =
                         compiler.getStandardFileManager(null, null, null)) {
                files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(output));
                final boolean ok = compiler.getTask(null, files, null, List.of(), null,
                        List.of(new Source())).call();
                if (!ok) {
                    throw new AssertionError("the point fixture must compile");
                }
            }
            final byte[] bytes = Files.readAllBytes(output.resolve("pointfixture/Target.class"));
            return ModelViews.of(ClassFile.of().parse(bytes));
        } catch (final Exception failed) {
            throw new AssertionError("could not build the point fixture", failed);
        }
    }

    private static final class Source extends SimpleJavaFileObject {

        private static final String CODE = """
                package pointfixture;

                public class Target {

                    private int counter;
                    private final String name;

                    public Target(String name) {
                        // A `new` inside the argument list, so the depth counter in HeadPoint has
                        // something to get wrong.
                        this.name = new StringBuilder(name).toString();
                        this.counter = 0;
                    }

                    public String work(int times) {
                        // 'a' sits OUTSIDE the tx slice on purpose: if the slice started before the
                        // first match, ordinals inside it would coincide with ordinals overall and
                        // the renumbering test would pass without testing anything.
                        helper("a");
                        begin();
                        helper("b");
                        commit();
                        helper("c");
                        counter++;
                        int read = counter;
                        if (times == 0) {
                            return "empty";
                        }
                        return name + read;
                    }

                    public String branching(int times) {
                        if (times == 0) {
                            return "early";
                        }
                        return "late";
                    }

                    public String concatenating(String other) {
                        return name + other;
                    }

                    public StringBuilder allocating() {
                        return new StringBuilder();
                    }

                    public int constants() {
                        int small = 7;
                        String text = "retry";
                        Class<?> type = Void.class;
                        Object nothing = null;
                        return small + text.length() + type.hashCode()
                                + (nothing == null ? 0 : 1);
                    }

                    public void throwing() {
                        throw new IllegalStateException("no");
                    }

                    // describe() is called BOTH ways here: once as an ordinary call, which
                    // INVOKE matches, and once as a method reference, which compiles to an
                    // invokedynamic and does not. One selector, two sites, one of them silent.
                    public java.util.function.Supplier<String> referencing() {
                        describe();
                        return this::describe;
                    }

                    private String describe() { return this.name; }

                    private void begin() { }
                    private void commit() { }
                    private String helper(String s) { return s; }
                }
                """;

        Source() {
            super(URI.create("string:///pointfixture/Target.java"), Kind.SOURCE);
        }

        @Override
        public CharSequence getCharContent(final boolean ignoreEncodingErrors) {
            return CODE;
        }
    }
}
