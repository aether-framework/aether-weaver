/**
 * Weaves classes in memory inside a test and asserts about the bytes that come back.
 *
 * <p>The unit of work is one weave of one class. {@link de.splatgames.aether.weaver.testkit.Weaving} plans a weaver
 * over weave classes named as {@link java.lang.Class} objects and weaves a target from that target's own class file;
 * {@link de.splatgames.aether.weaver.testkit.WeaveResult} carries what the call produced;
 * {@link de.splatgames.aether.weaver.testkit.WovenAssert} asserts about it; and
 * {@link de.splatgames.aether.weaver.testkit.GoldenFiles} compares it against a class file committed alongside the
 * test. {@link de.splatgames.aether.weaver.testkit.WeaverExtension} and
 * {@link de.splatgames.aether.weaver.testkit.Weaves} exist only to hand a
 * {@link de.splatgames.aether.weaver.testkit.Weaving} to a parameter of type {@code Weaving}
 * wherever JUnit offers parameter resolution, not only in a test method.
 *
 * <p>No agent, no build step and no class redefinition are involved. Nothing in this package reads a weave manifest,
 * discovers weaves from the classpath, or consults the configuration a driver would: the weave classes are the ones
 * named in source, and the only configuration property this package reads is
 * {@link de.splatgames.aether.weaver.testkit.GoldenFiles#UPDATE_PROPERTY}.
 *
 * <h2>The shape of a test</h2>
 *
 * <p>Under JUnit, a {@link de.splatgames.aether.weaver.testkit.Weaving} arrives as a parameter and the weave classes
 * are named by {@link de.splatgames.aether.weaver.testkit.Weaves} on the method, the class or an enclosing class.
 *
 * <pre>{@code
 * import static de.splatgames.aether.weaver.testkit.WovenAssert.assertThatWoven;
 *
 * @ExtendWith(WeaverExtension.class)
 * @Weaves(AuditWeave.class)
 * class LedgerWeavingTest {
 *
 *     @Test
 *     void auditApplies(Weaving weaving) {
 *         WeaveResult result = weaving.weave(Ledger.class);
 *
 *         assertThatWoven(result)
 *                 .wasWoven()
 *                 .satisfiesEveryInvariant()
 *                 .preservesUntargetedMethods("charge")
 *                 .loadsAndRuns(type -> {
 *                     Object ledger = type.getDeclaredConstructor().newInstance();
 *                     type.getMethod("charge", int.class).invoke(ledger, 5);
 *                 });
 *     }
 * }
 * }</pre>
 *
 * <p>The names given to {@code preservesUntargetedMethods} exempt the methods a weave was expected to change, so
 * the example asserts that no other method's instructions moved. The exemptions are method names rather than
 * selectors, so naming {@code charge} exempts every overload of it.
 *
 * <p>The search for a {@link de.splatgames.aether.weaver.testkit.Weaves} declaration goes outwards from the context
 * the parameter belongs to — the test method, then its class, then each enclosing class — and stops at the first
 * element carrying one, so a declaration on a test method replaces the one on its class rather than adding to it.
 * Because the annotation is {@link java.lang.annotation.Inherited}, a test class also carries its superclass's
 * declaration. Reaching the top without finding a declaration, and finding one that names no class, are each a
 * {@link org.junit.jupiter.api.extension.ParameterResolutionException}: a weaver with nothing planned would weave
 * nothing, and every assertion that nothing was applied would pass.
 *
 * <p>{@link de.splatgames.aether.weaver.testkit.WeaverExtension} is the only type here that refers to JUnit in code,
 * and the Jupiter dependency is declared {@code provided}, so a consumer supplies its own version.
 * {@link de.splatgames.aether.weaver.testkit.Weaving#of(Class[])} is the same entry point without the extension:
 *
 * <pre>{@code
 * Weaving weaving = Weaving.of(AuditWeave.class);
 * WeaveResult result = weaving.weave(Ledger.class);
 * }</pre>
 *
 * <p>Every assertion failure raised in this package is a plain {@link java.lang.AssertionError} rather than a
 * framework type. {@link de.splatgames.aether.weaver.testkit.WeaveResult} is a record with a public canonical
 * constructor, and {@link de.splatgames.aether.weaver.testkit.GoldenFiles#verify(java.lang.String, byte[])} takes
 * bytes directly, so both the assertions and the golden-file comparison can be pointed at a class file that this
 * package did not produce.
 *
 * <h2>How the weaver is configured, and what it is not</h2>
 *
 * <p>{@link de.splatgames.aether.weaver.testkit.Weaving#of(Class[])} settles the whole configuration of the
 * weaver, and nothing in this package offers a way to change any of it.
 *
 * <ul>
 *   <li><b>The class source holds the weave classes and nothing else.</b> Their own bytes are registered under their
 *       internal names, which is where the bodies of an instance weave's members are read from when it is dissolved
 *       into a target. A class not named in that call is not in the map.
 *   <li><b>Verification is {@link de.splatgames.aether.weaver.engine.verify.VerificationPolicy#STRICT}.</b> Output
 *       the verifier refuses is thrown out of
 *       {@link de.splatgames.aether.weaver.testkit.Weaving#weave(java.lang.Class)} as a
 *       {@link de.splatgames.aether.weaver.api.diagnostic.WeaveException} rather than handed back for an assertion
 *       to find, and a fatal policy does not also report, so the diagnostic travels inside that exception rather
 *       than reaching the collected list.
 *   <li><b>Plugins are not discovered.</b> No discovery loader is set, so a
 *       {@link de.splatgames.aether.weaver.api.spi.WeaverPlugin} published as a service on the test classpath is not
 *       loaded and only the engine's built-in plugin is installed.
 *   <li><b>The weaver runs as {@link de.splatgames.aether.weaver.engine.Weaver.Driver#BUILD},</b> which is the
 *       builder's default. The driver is consulted only when a class already carries a different plan's weave
 *       record; a class already woven by this same plan is skipped identically under either driver.
 *   <li><b>Every diagnostic is collected,</b> from parsing the weave classes through planning to weaving.
 * </ul>
 *
 * <p>The weave classes are read in the order given. A class that carries no {@code @Weave}, names no usable target
 * or draws an error from the parser is refused with an {@link java.lang.IllegalArgumentException} rather than
 * skipped, and under the extension that refusal reaches the test as a
 * {@link org.junit.jupiter.api.extension.ParameterResolutionException} carrying it as the cause.
 *
 * <h2>Every target is woven twice</h2>
 *
 * <p>{@link de.splatgames.aether.weaver.testkit.Weaving#weave(java.lang.Class)} runs the weaver twice over the same
 * original bytes and keeps both results, so that
 * {@link de.splatgames.aether.weaver.testkit.WovenAssert#isDeterministic()} has a second pass to compare against
 * whether or not the test asked for one. Two consequences are visible to a caller: the count of classes seen by
 * {@link de.splatgames.aether.weaver.engine.Weaver#statistics()} advances twice per call, and whatever the second
 * pass reported is discarded without being compared against the first, so a diagnostic only the second pass raises
 * is lost.
 *
 * <p>The second pass is fed the original bytes rather than the first pass's output. Woven output carries this plan's
 * weave record, and the engine's idempotence gate skips a class that already carries it, so feeding the output back
 * would measure nothing.
 *
 * <h2>What is read, and what is never touched</h2>
 *
 * <p>Both the weave classes and the target are read as {@code .class} resources from their own class loader, or from
 * the system class loader when the class has none. A class the loader cannot produce a resource for — an array type,
 * a hidden class, a class generated at run time — is refused with an {@link java.lang.IllegalStateException}, and an
 * unreadable resource with an {@link java.io.UncheckedIOException}.
 *
 * <p>The JVM's own copy of the target is left exactly as it was.
 * {@link de.splatgames.aether.weaver.testkit.Weaving#weave(java.lang.Class)} returns bytes and loads nothing. The
 * only assertions that define a class are
 * {@link de.splatgames.aether.weaver.testkit.WovenAssert#isAcceptedByTheJvm()},
 * {@link de.splatgames.aether.weaver.testkit.WovenAssert#satisfiesEveryInvariant()} (which calls it), and
 * {@link de.splatgames.aether.weaver.testkit.WovenAssert#loadsAndRuns(
 * de.splatgames.aether.weaver.testkit.WovenAssert.ThrowingConsumer)}, each in a throwaway loader created for that
 * one call, whose parent defaults to the loader of
 * {@link de.splatgames.aether.weaver.testkit.WovenAssert} and is changed by
 * {@link de.splatgames.aether.weaver.testkit.WovenAssert#withParent(java.lang.ClassLoader)}. The class a callback
 * receives is that woven copy, not the copy the JVM already holds under the same name; a class the throwaway loader
 * did not define is reached through the parent, which is how a mark written by a woven handler is visible to the
 * test afterwards.
 *
 * <h2>Assertions</h2>
 *
 * <p>A chain is entered through
 * {@link de.splatgames.aether.weaver.testkit.WovenAssert#assertThatWoven(
 * de.splatgames.aether.weaver.testkit.WeaveResult)} and every assertion returns the same object or throws. Nothing
 * is collected: the first failure ends the chain. Every failure raised here is an
 * {@link java.lang.AssertionError} whose message begins with the class's binary name in square brackets and goes on
 * to name what was actually found. The one failure that carries no such prefix is an
 * {@link java.lang.AssertionError} thrown by the callback of {@code loadsAndRuns}, which propagates unchanged.
 *
 * <p>Everything that parses, verifies or defines the class reads
 * {@link de.splatgames.aether.weaver.testkit.WeaveResult#effective()}, which falls back to the original bytes
 * whenever the call produced no woven bytes at all — not only when no weave named the class, but also when the
 * shape gate or a {@code Deny} policy rejected the plan, when the class already carried this plan's weave record,
 * when an {@code AW2201} refusal applied, or when a resolved pipeline weaves the class into unchanged bytes. A
 * chain that does not begin with {@link de.splatgames.aether.weaver.testkit.WovenAssert#wasWoven()} therefore
 * describes the original class in every one of those cases, and the assertions that hold of any well-formed class
 * pass on it.
 *
 * <h2>Golden files</h2>
 *
 * <p>{@link de.splatgames.aether.weaver.testkit.GoldenFiles} compares bytes against a committed {@code name.class}
 * in a directory the caller chooses, and writes a {@code name.txt} disassembly beside it so that a change to the
 * fixture arrives in a review as readable bytecode. A mismatch writes {@code name.actual.class} and
 * {@code name.actual.txt} and fails; ordinarily the failure carries a diff of the two disassemblies, but when they
 * agree even though the bytes differ, or when {@code javap} exited non-zero on both files, the failure names the
 * bytes as different without a diff to show for it.
 *
 * <pre>{@code
 * GoldenFiles golden = GoldenFiles.in(Path.of("src/test/resources/golden"));
 *
 * golden.verify("ledger-audited", weaving.weave(Ledger.class));
 * }</pre>
 *
 * <p>Two properties of that comparison decide how it is used. Outside update mode, a fixture that does not exist is
 * written and the comparison fails anyway, so the first run of a new fixture never passes there. Setting
 * {@link de.splatgames.aether.weaver.testkit.GoldenFiles#UPDATE_PROPERTY} to {@code true} rewrites every fixture
 * the run touches and asserts nothing at all — including a fixture that did not exist yet, whose first run then
 * passes — which is what
 * {@link de.splatgames.aether.weaver.testkit.GoldenFiles#updating()} answers so that a test can refuse to run under
 * it.
 *
 * <p>The rendering and the diff come from {@link de.splatgames.aether.weaver.engine.dump.Disassembly}, which runs
 * {@code javap} in-process through {@link java.util.spi.ToolProvider}. That tool lives in the {@code jdk.javap}
 * module, which a trimmed runtime image may leave out. Without it the {@code .txt} is a two-line note saying there
 * is no rendering, and a mismatch fails with a sentence naming both files instead of a diff. The comparison against
 * {@code name.class} is over raw bytes and does not depend on the tool.
 *
 * <h2>Diagnostics</h2>
 *
 * <p>Nothing in this package raises a diagnostic of its own; every diagnostic a result carries came from the engine.
 * {@link de.splatgames.aether.weaver.testkit.WovenAssert#reports(java.lang.String)} compares the code as a string
 * and checks nothing about whether the project declares it, so a misspelled code simply fails to match.
 *
 * <p>Two scopes, and they are not the same. A
 * {@link de.splatgames.aether.weaver.testkit.WeaveResult} carries only what its own call to
 * {@link de.splatgames.aether.weaver.testkit.Weaving#weave(java.lang.Class)} reported, so a diagnostic raised while
 * the weave classes were being parsed and planned is in
 * {@link de.splatgames.aether.weaver.testkit.Weaving#diagnostics()} and nowhere else.
 *
 * <p>Because the weaver runs as {@link de.splatgames.aether.weaver.engine.Weaver.Driver#BUILD}, a target whose
 * class file already carries a different plan's weave record is reported as {@code AW2201}, an error, and comes
 * back unwoven; the chain then fails at {@link de.splatgames.aether.weaver.testkit.WovenAssert#wasWoven()} rather
 * than at an assertion about what was applied. A target already carrying this same plan's record is skipped
 * silently, with nothing reported at all. A class file that has not already been through a weaver meets neither
 * case.
 *
 * <h2>Sharing and mutation</h2>
 *
 * <p>{@link de.splatgames.aether.weaver.testkit.WeaverExtension} builds one
 * {@link de.splatgames.aether.weaver.testkit.Weaving} per {@link org.junit.jupiter.api.extension.ExtensionContext}
 * that first asks for one and keeps it in that context's store. Because a store lookup consults ancestor stores,
 * a context nested inside the one that first built the instance is handed that same instance rather than building
 * its own; for a test method's parameter, that means every test method gets a weaver of its own, with its own plan,
 * statistics and diagnostics, unless an enclosing context already built one first. A
 * {@link de.splatgames.aether.weaver.testkit.Weaving} is not safe for concurrent use: it appends to and truncates a
 * plain list that its own accessor reads.
 *
 * <p>{@link de.splatgames.aether.weaver.testkit.WeaveResult} copies every array it is given and copies again on
 * every accessor, so no two calls return the same array and nothing a caller holds can change what a result reports.
 * That also leaves the record's generated {@code equals} of no use: the components are arrays and each instance
 * holds copies of its own, so two distinct results are never equal.
 * {@link de.splatgames.aether.weaver.testkit.WovenAssert} holds a result and a parent loader and nothing else.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
package de.splatgames.aether.weaver.testkit;
