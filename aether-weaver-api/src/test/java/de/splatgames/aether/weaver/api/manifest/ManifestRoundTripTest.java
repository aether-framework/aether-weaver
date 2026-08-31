package de.splatgames.aether.weaver.api.manifest;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.spi.Reporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the manifest format as a pair: what {@link ManifestWriter} emits, {@link ManifestReader} reads
 * back unchanged.
 *
 * <p>A manifest is written into a jar by one build and read by another, possibly a different release of
 * Aether Weaver, so the two halves have to agree about more than syntax. The cases fix four things: a
 * value written is a value read, a rendering is stable enough for a reproducible build, a document from
 * a newer schema is either tolerated or refused rather than guessed at, and a document that cannot be
 * used costs the reader as little of the rest as possible.
 *
 * <h2>The harness</h2>
 *
 * <p>Diagnostics go into {@link #reported} through {@link #reporter}, both instance fields, so each
 * case sees only what it caused: JUnit builds a fresh instance of the enclosing class for every test
 * method, nested ones included. An {@code isEmpty()} on {@link #reported} therefore says that reading
 * reported nothing at all, not merely nothing of a chosen kind.
 *
 * <p>{@link ManifestReader#read(String, String, Reporter)} returns {@code null} for a document it
 * refuses, and a non-null manifest for one it could partly use. Both halves are asserted wherever they
 * differ, because a refusal and a salvage are not distinguishable from the diagnostics alone.
 *
 * <p>The nested {@code Parser} cases call {@link Json} directly. They are the exception to the above:
 * no reader runs, no diagnostic is produced, and what they assert is the exception the parser throws.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
class ManifestRoundTripTest {

    /** Every diagnostic the reader produced during one test method, in report order. */
    private final List<Diagnostic> reported = new ArrayList<>();

    /** Collects into {@link #reported} and filters nothing, so an unexpected report fails a case. */
    private final Reporter reporter = this.reported::add;

    /**
     * Fixes that writing and reading are inverses.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    @Nested
    @DisplayName("write, read, and get the same thing back")
    class RoundTrip {

        /**
         * Asserts that a manifest holding two weaves, three members, one injector and two points survives a
         * write and a read with every field equal, and reports nothing.
         *
         * <p>The comparison is record equality on the whole tree, so a field the writer forgets or the reader
         * drops fails here rather than in whatever later reads that field. The fixture exercises both
         * spellings of most things that have two: a weave with tags and one without, a member whose
         * {@code targetName} differs from its name and one where they agree, and a point with a target,
         * ordinal, shift and slice beside one that carries none of them.
         *
         * <p>{@link #populated()} declares no extension, so nothing here covers the {@code extensions} array
         * or the entries in it.
         */
        @Test
        @DisplayName("a fully populated manifest survives unchanged")
        void everythingSurvives() {
            final WeaveManifest original = populated();

            final WeaveManifest parsed = ManifestReader.read(
                    ManifestWriter.write(original), "test", reporter);

            assertThat(reported).isEmpty();
            assertThat(parsed)
                    .as("equality on the record compares every field of every nested entry, "
                            + "which is what makes a dropped one impossible to miss")
                    .isEqualTo(original);
        }

        /**
         * Asserts that a manifest with no weaves round-trips.
         *
         * <p>The empty case is where a writer that emits a trailing separator, or a reader that treats an
         * empty array as an absent one, first goes wrong. It is also the common case: a module with no weave
         * classes still gets a manifest.
         */
        @Test
        @DisplayName("an empty manifest survives too")
        void nothingAlsoSurvives() {
            final WeaveManifest original = WeaveManifest.of("test/0.1.0", List.of());

            assertThat(ManifestReader.read(ManifestWriter.write(original), "test", reporter))
                    .isEqualTo(original);
        }

        /**
         * Asserts that two renderings of two separately built, equal manifests are the same text.
         *
         * <p>Byte-identical output is what lets a jar containing a manifest be compared between builds. The
         * assertion catches any ordering the writer takes from something other than the manifest itself --
         * hash iteration order being the usual source -- because the two manifests are equal in value and
         * distinct in identity.
         */
        @Test
        @DisplayName("the same manifest renders byte-identically every time")
        void renderingIsStable() {
            assertThat(ManifestWriter.write(populated()))
                    .as("a manifest that varied between builds would end the reproducible-build "
                            + "guarantee at the first jar containing one")
                    .isEqualTo(ManifestWriter.write(populated()));
        }

        /**
         * Asserts that a tag list holding a quotation mark, a backslash, a newline and the empty string
         * survives the round trip.
         *
         * <p>Escaping is the one part of the format where the writer and the reader must implement the same
         * table, and where getting it wrong produces a document that parses into different text rather than
         * one that fails to parse. The empty string is in the fixture for a different reason: nothing in the
         * reader drops a blank tag, and the assertion pins that.
         *
         * <p>Three escape forms are covered. A carriage return, a tab, and a control character low enough
         * to be written as a six-digit unicode escape are not in the fixture.
         */
        @Test
        @DisplayName("characters that need escaping come back as themselves")
        void escapesSurvive() {
            final WeaveManifest original = WeaveManifest.of("test", List.of(
                    new WeaveManifest.Weave("com.acme.Odd", "STATIC", 0, "REQUIRED", "DEFAULT",
                            List.of("a\"b", "c\\d", "e\nf", ""), List.of("com.acme.T"),
                            List.of(), List.of())));

            assertThat(ManifestReader.read(ManifestWriter.write(original), "test", reporter))
                    .isEqualTo(original);
        }
    }

    /**
     * Fixes what a reader does with a document written by a different version of the toolchain.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    @Nested
    @DisplayName("schema evolution")
    class Evolution {

        /**
         * Asserts that unknown fields at the top level, on a weave, on an injector and on a point are all
         * ignored without a diagnostic, and that the known fields around them still read.
         *
         * <p>This is the compatibility direction that costs nothing to keep and is easy to lose: a newer
         * processor that adds optional metadata must leave an older runtime able to read the document. The
         * assertion on {@link #reported} is what pins silence -- tolerating the field while warning about it
         * would fill a build log with noise about a manifest the user did not write.
         */
        @Test
        @DisplayName("unknown fields are ignored, at every level")
        void unknownFieldsAreIgnored() {
            final WeaveManifest parsed = ManifestReader.read("""
                    {
                      "version": 1,
                      "generator": "future/9.9",
                      "futureTopLevel": {"anything": [1, 2, 3]},
                      "weaves": [
                        {
                          "class": "com.acme.Audit",
                          "futureWeaveField": true,
                          "targets": ["com.acme.Service"],
                          "injectors": [
                            {
                              "handler": "onRun()V",
                              "method": "com.acme.Service.run()",
                              "futureInjectorField": "ignored",
                              "points": [{"point": "HEAD", "futurePointField": 7}]
                            }
                          ]
                        }
                      ]
                    }
                    """, "future.jar", reporter);

            assertThat(reported)
                    .as("a newer processor adding optional metadata must not stop an older "
                            + "runtime from reading the manifest at all")
                    .isEmpty();
            assertThat(parsed).isNotNull();
            assertThat(parsed.weaves()).singleElement().satisfies(weave -> {
                assertThat(weave.className()).isEqualTo("com.acme.Audit");
                assertThat(weave.injectors()).singleElement().satisfies(injector ->
                        assertThat(injector.points()).singleElement().satisfies(point ->
                                assertThat(point.point()).isEqualTo("HEAD")));
            });
        }

        /**
         * Asserts that a weave entry carrying nothing but its class name reads as the same weave as one
         * whose defaults are all written out.
         *
         * <p>A weave entry's fields are always written, so this is not the writer eliding a default: it is
         * the entry above being hand-written sparse. The reader is expected to supply the same defaults for
         * a missing weave field as the writer would have written explicitly, so the two statements have to
         * mean the same thing. If they drift, a manifest becomes sensitive to which version wrote it: the
         * same weave would be {@code INSTANCE} in one jar and something else in another.
         */
        @Test
        @DisplayName("omitted optional fields take the same values as written defaults")
        void defaultsMatchWrittenValues() {
            final WeaveManifest sparse = ManifestReader.read("""
                    {"version": 1, "generator": "g", "weaves": [
                      {"class": "com.acme.Audit"}
                    ]}
                    """, "test", reporter);

            assertThat(sparse).isNotNull();
            assertThat(sparse.weaves().getFirst())
                    .as("writing the default and omitting it are the same statement, so they "
                            + "must not produce different weaves")
                    .isEqualTo(new WeaveManifest.Weave("com.acme.Audit", "INSTANCE", 0,
                            "REQUIRED", "DEFAULT", List.of(), List.of(), List.of(), List.of()));
        }

        /**
         * Asserts that a manifest declaring a schema version above the one this release reads is refused with
         * {@code AW2301} and nothing else.
         *
         * <p>Refusal rather than best effort, because a later schema is free to give an existing field a new
         * meaning: reading it would produce a plan that looks valid and weaves the wrong thing.
         * {@code containsExactly} pins that the refusal is reported once and that no {@code AW2300} is
         * reported alongside it, so the message a user gets names the version rather than the syntax.
         *
         * <p>The remedy the diagnostic carries is to upgrade Aether Weaver or rebuild the artefact against
         * this release.
         */
        @Test
        @DisplayName("AW2301 — a newer schema version is refused, not guessed at")
        void newerVersionsAreRefused() {
            assertThat(ManifestReader.read(
                    "{\"version\": 2, \"generator\": \"g\", \"weaves\": []}", "future.jar",
                    reporter))
                    .as("an unknown schema may give a familiar field a new meaning; reading it "
                            + "hopefully is the opposite of the safety this project sells")
                    .isNull();
            assertThat(codes()).containsExactly("AW2301");
        }

        /**
         * Asserts that the version this release writes is a version it reads, reporting nothing.
         *
         * <p>The boundary case of the refusal above: the comparison is strictly greater than, so the current
         * version passes. Without it, tightening the check to a range would refuse every manifest the
         * toolchain itself produces.
         */
        @Test
        @DisplayName("the current version is accepted")
        void theCurrentVersionIsAccepted() {
            assertThat(ManifestReader.read(
                    "{\"version\": 1, \"generator\": \"g\", \"weaves\": []}", "test", reporter))
                    .isNotNull();
            assertThat(reported).isEmpty();
        }
    }

    /**
     * Fixes how much a reader gives up when part of a document is unusable.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    @Nested
    @DisplayName("refusing what cannot be used")
    class Refusals {

        /**
         * Asserts that a truncated document is reported as {@code AW2300} and yields no manifest.
         *
         * <p>The parser throws and the reader turns that into a diagnostic naming the artefact it came from,
         * so a broken manifest fails the artefact rather than the process. {@code containsExactly} pins that
         * one report is produced, not one per unreadable construct.
         */
        @Test
        @DisplayName("AW2300 — malformed JSON takes the manifest, not the process")
        void malformedJsonIsReported() {
            assertThat(ManifestReader.read("{\"version\": 1, ", "broken.jar", reporter)).isNull();
            assertThat(codes()).containsExactly("AW2300");
        }

        /**
         * Asserts that a weave entry naming no class is dropped with one {@code AW2300} while the valid entry
         * beside it is kept.
         *
         * <p>Entry-level recovery is the point: a manifest is generated, so one unusable entry indicates a
         * toolchain problem rather than a user error, and refusing the whole document would disable every
         * weave in the artefact instead of one. The surviving list is asserted exactly, so a reader that kept
         * a placeholder for the dropped entry fails here too.
         */
        @Test
        @DisplayName("a weave entry with no class is dropped, and the rest survive")
        void unusableEntriesAreDroppedIndividually() {
            final WeaveManifest parsed = ManifestReader.read("""
                    {"version": 1, "generator": "g", "weaves": [
                      {"kind": "STATIC"},
                      {"class": "com.acme.Good"}
                    ]}
                    """, "test", reporter);

            assertThat(codes()).containsExactly("AW2300");
            assertThat(parsed).isNotNull();
            assertThat(parsed.weaves())
                    .as("one broken entry must not cost the reader every good one beside it")
                    .extracting(WeaveManifest.Weave::className)
                    .containsExactly("com.acme.Good");
        }

        /**
         * Asserts that an injector naming no handler is dropped with one {@code AW2300} while its weave and
         * the injector beside it survive.
         *
         * <p>The same recovery one level down. An injector needs both a handler and a target method to name
         * any work at all, so an entry missing either is unusable on its own terms and says nothing about the
         * weave that declares it.
         */
        @Test
        @DisplayName("an injector with no handler is dropped, and its weave survives")
        void unusableInjectorsAreDroppedIndividually() {
            final WeaveManifest parsed = ManifestReader.read("""
                    {"version": 1, "generator": "g", "weaves": [
                      {"class": "com.acme.Audit", "injectors": [
                        {"method": "com.acme.Service.run()"},
                        {"handler": "onRun()V", "method": "com.acme.Service.run()"}
                      ]}
                    ]}
                    """, "test", reporter);

            assertThat(codes()).containsExactly("AW2300");
            assertThat(parsed).isNotNull();
            assertThat(parsed.weaves().getFirst().injectors()).hasSize(1);
        }

        /**
         * Asserts that a well-formed JSON document that is not an object is refused as {@code AW2300}.
         *
         * <p>The refusal comes from the shape check rather than from a syntax error, which is why it is worth
         * a case of its own: the text parses, and it is still not a manifest.
         */
        @Test
        @DisplayName("a document that is not an object is refused")
        void arraysAreNotManifests() {
            assertThat(ManifestReader.read("[1, 2, 3]", "broken.jar", reporter)).isNull();
            assertThat(codes()).containsExactly("AW2300");
        }
    }

    /**
     * Fixes the limits of the {@link Json} parser, which reads whatever manifest is on the classpath.
     *
     * <p>These cases call the parser directly, so they assert exceptions rather than diagnostics; the
     * enclosing class's {@code reported} list stays empty because no reader runs.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    @Nested
    @DisplayName("the restricted parser")
    class Parser {

        /**
         * Asserts that a fractional number and trailing content after the document are both refused.
         *
         * <p>The parser reads integers only, which is everything the writer emits: priorities, ordinals,
         * counts and the schema version. Accepting a syntax the writer never produces would add a path
         * through the parser that no manifest in the project exercises, in code whose input comes off the
         * classpath.
         *
         * <p>Both refusals are {@link IllegalArgumentException}, which is what
         * {@link ManifestReader#read(String, String, Reporter)} catches to report {@code AW2300}.
         */
        @Test
        @DisplayName("it refuses what it does not emit, rather than half-supporting it")
        void unsupportedSyntaxIsRefused() {
            assertThatThrownBy(() -> Json.readObject("{\"a\": 1.5}"))
                    .as("the manifest holds priorities, ordinals and counts — accepting a format "
                            + "the writer never emits leaves an untested path in a parser that "
                            + "reads files off the classpath")
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> Json.readObject("{\"a\": 1} trailing"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        /**
         * Asserts that a document nested two hundred levels deep is refused with a message naming the depth.
         *
         * <p>The parser descends recursively, so without a limit the nesting in an untrusted document decides
         * how deep the stack goes. The limit is checked against the depth carried down the recursion rather
         * than measured afterwards, so the refusal happens before the frames are used up.
         */
        @Test
        @DisplayName("it refuses a document nested past its limit")
        void deepNestingIsRefused() {
            final String deep = "{\"a\": ".repeat(200) + "1" + "}".repeat(200);

            assertThatThrownBy(() -> Json.readObject(deep))
                    .as("a parser that reads whatever is on the classpath must not be made to "
                            + "recurse until the stack ends")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("deep");
        }

        /**
         * Asserts that a syntax error names the offset it was found at.
         *
         * <p>A manifest is one line per field and hundreds of lines long, so an exception saying only that
         * the document is malformed leaves the reader to find the spot. The offset travels into the
         * {@code AW2300} message, which is where a user meets it.
         */
        @Test
        @DisplayName("the offset of a syntax error is named")
        void failuresSayWhere() {
            assertThatThrownBy(() -> Json.readObject("{\"a\": tru}"))
                    .hasMessageContaining("offset");
        }
    }

    // -------------------------------------------------------------------------------------

    /**
     * Returns the wire form of every diagnostic reported so far, in report order.
     *
     * <p>Reduces a report to its code so that a case can assert on the conditions raised without pinning
     * message text, which names the artefact and is written per reporting site.
     *
     * @return the codes, in the order they were reported
     */
    private List<String> codes() {
        return this.reported.stream().map(diagnostic -> diagnostic.code().code()).toList();
    }

    /**
     * Builds a manifest exercising every field of {@link WeaveManifest.Weave},
     * {@link WeaveManifest.Member}, {@link WeaveManifest.Injector} and {@link WeaveManifest.Point}.
     *
     * <p>Built twice where a case needs two equal manifests, so that equality and rendering can be
     * compared across distinct instances.
     *
     * <p>The two-argument {@link WeaveManifest#of(String, List)} factory leaves the extension list empty,
     * so no {@link WeaveManifest.Extension} is covered by anything built from here.
     *
     * @return a manifest of the current schema version
     */
    private static WeaveManifest populated() {
        return WeaveManifest.of("aether-weaver-processor/0.1.0", List.of(
                new WeaveManifest.Weave(
                        "com.acme.audit.PaymentAudit", "INSTANCE", 100, "OPTIONAL", "EARLY",
                        List.of("audit", "metrics"),
                        List.of("com.acme.PaymentService", "com.acme.RefundService"),
                        List.of(
                                new WeaveManifest.Member("SHADOW", "FIELD", "ledger",
                                        "Lcom/acme/Ledger;", "ledger", false),
                                new WeaveManifest.Member("MERGE", "FIELD", "startedAt", "J",
                                        "startedAt", true),
                                new WeaveManifest.Member("ACCESSOR", "METHOD", "getName",
                                        "()Ljava/lang/String;", "name", false)),
                        List.of(new WeaveManifest.Injector("INJECT", "onCharge",
                                "onCharge(Ljava/math/BigDecimal;)V",
                                "com.acme.PaymentService.charge(java.math.BigDecimal)",
                                List.of(
                                        new WeaveManifest.Point("HEAD", "", -1, "NONE", 0,
                                                "ANY", ""),
                                        new WeaveManifest.Point("INVOKE",
                                                "com.acme.Gateway.send(com.acme.Payment)", 2,
                                                "BY", 3, "GET", "body")),
                                1, 4, "compat"))),
                new WeaveManifest.Weave("com.acme.audit.Tracing", "STATIC", 0, "REQUIRED",
                        "DEFAULT", List.of(), List.of("com.acme.PaymentService"),
                        List.of(), List.of())));
    }
}
