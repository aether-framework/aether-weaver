package de.splatgames.aether.weaver.testkit;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.model.Origin;
import de.splatgames.aether.weaver.api.spi.ClassSource;
import de.splatgames.aether.weaver.engine.Weaver;
import de.splatgames.aether.weaver.engine.model.WeaveClass;
import de.splatgames.aether.weaver.engine.parse.WeaveClassParser;
import de.splatgames.aether.weaver.engine.verify.VerificationPolicy;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Weaves classes in memory, from their own class files, without an agent or a build step.
 *
 * <p>A {@code Weaving} owns one built {@link Weaver} and the list of diagnostics reported since
 * {@link #of(Class[])} began, which starts collecting before the weaver itself exists and keeps
 * appending as {@link #weave(Class)} is called. Weave classes and targets are named as
 * {@link Class} objects, and their bytes are read back out of the class loader that already loaded
 * them, so a test asserts about the same class files the rest of the build produced.
 *
 * <p>Nothing is loaded or redefined here. {@link #weave(Class)} returns bytes; loading them is
 * what {@link WovenAssert#isAcceptedByTheJvm()} and
 * {@link WovenAssert#loadsAndRuns(WovenAssert.ThrowingConsumer)} do, into a throwaway class loader
 * of their own. The target class stays exactly as the JVM already has it.
 *
 * <h2>How the weaver is configured</h2>
 *
 * <p>{@link #of(Class[])} fixes three things that a test would otherwise have to get right itself:
 * the class source is a map holding the weave classes' own bytes and nothing else; verification is
 * {@link VerificationPolicy#STRICT}, so output the class file verifier refuses is thrown out of
 * {@link #weave(Class)} rather than handed back; and every diagnostic, from parsing through
 * planning to weaving, is collected into one list.
 *
 * <h2>Every class is woven twice</h2>
 *
 * <p>{@link #weave(Class)} runs the weaver twice over the same original bytes and keeps both
 * results, so that {@link WovenAssert#isDeterministic()} has a second pass to compare against
 * without the test asking for one. Whatever the second pass reports is discarded without being
 * compared against the first; only the first pass's diagnostics are in the result. A diagnostic
 * the second pass alone raises is lost silently, even though it would be the loudest evidence of
 * the non-determinism {@link WovenAssert#isDeterministic()} exists to catch.
 *
 * <h2>Thread safety</h2>
 *
 * <p>Not safe for concurrent use. {@link #weave(Class)} appends to and truncates a plain
 * {@link ArrayList} that {@link #diagnostics()} also reads.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * Weaving weaving = Weaving.of(AuditWeave.class);
 * WeaveResult result = weaving.weave(Ledger.class);
 *
 * assertThatWoven(result)
 *         .wasWoven()
 *         .satisfiesEveryInvariant()
 *         .preservesUntargetedMethods("charge");
 * }</pre>
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see WeaverExtension
 * @see WeaveResult
 */
public final class Weaving {

    /** The weaver built from the weave classes given to {@link #of(Class[])}. */
    private final Weaver weaver;

    /**
     * Every diagnostic reported since {@link #of(Class[])} began, in the order reported.
     *
     * <p>Mutable and shared: the parser, the planner and the weaver all append to it through the
     * same listener, and {@link #weave(Class)} truncates it to drop whatever the second pass
     * reported, without comparing it to the first.
     */
    private final List<Diagnostic> diagnostics;

    /**
     * Wraps a built weaver and the list its diagnostics arrive in.
     *
     * @param weaver      the built weaver; must not be {@code null}
     * @param diagnostics the live list the weaver reports into, retained rather than copied; must
     *                    not be {@code null}
     */
    private Weaving(@NotNull final Weaver weaver, @NotNull final List<Diagnostic> diagnostics) {
        this.weaver = weaver;
        this.diagnostics = diagnostics;
    }

    /**
     * Plans a weaver over the given weave classes.
     *
     * <p>Each class's own class file is read from its class loader, parsed by
     * {@link WeaveClassParser} under an {@link Origin} of {@code "testkit"} carrying the class's
     * binary name, and handed to {@link Weaver#builder()}. The same bytes are also registered as
     * the weaver's {@link ClassSource}, keyed by internal name, which is where the bodies of an
     * instance weave's members are read from when it is dissolved into a target. Only the classes
     * named here are in that map.
     *
     * <p>The classes are read in the order given, so the diagnostics of an earlier one arrive
     * before those of a later one. What the planner then does with that order is its own affair.
     *
     * <p>A class that carries no {@code @Weave}, names no usable target, or draws an error from
     * the parser is refused rather than skipped. The refusal names the class and appends every
     * diagnostic collected so far in this call, rendered with
     * {@link Diagnostic#format()} — which includes the diagnostics of the classes already parsed
     * before it, not only that class's own.
     *
     * @param weaves the weave classes; must not be {@code null} and must not be empty
     * @return a weaver planned over those classes, with nothing woven yet
     * @throws NullPointerException     if {@code weaves} is {@code null}
     * @throws IllegalArgumentException if {@code weaves} is empty, or if one of the classes is not
     *                                  a usable weave class
     * @throws IllegalStateException    if a class has no class file its class loader can supply
     * @throws UncheckedIOException     if a class file could not be read
     */
    @Contract(value = "_ -> new")
    @NotNull
    public static Weaving of(final Class<?> @NotNull ... weaves) {
        Objects.requireNonNull(weaves, "weaves");
        if (weaves.length == 0) {
            throw new IllegalArgumentException("give at least one weave class");
        }

        final List<Diagnostic> diagnostics = new ArrayList<>();
        final WeaveClassParser parser = new WeaveClassParser(diagnostics::add);
        final List<WeaveClass> parsed = new ArrayList<>(weaves.length);
        final Map<String, byte[]> bytes = new LinkedHashMap<>();

        for (final Class<?> weave : weaves) {
            final byte[] classFile = bytesOf(weave);
            bytes.put(internalNameOf(weave), classFile);
            parsed.add(parser.parse(ClassFile.of().parse(classFile),
                            Origin.of("testkit", weave.getName()))
                    .orElseThrow(() -> new IllegalArgumentException(weave.getName()
                            + " is not a usable weave class: " + describe(diagnostics))));
        }

        return new Weaving(Weaver.builder()
                .weaves(parsed)
                .classSource(ClassSource.ofMap(bytes))
                .verification(VerificationPolicy.STRICT)
                .diagnostics(diagnostics::add)
                .build(), diagnostics);
    }

    /**
     * Weaves one class and reports what came out.
     *
     * <p>The target's class file is read from its own class loader, never from the JVM's loaded
     * form of it, so a class already loaded and a class merely on the classpath weave the same
     * way. The loaded class is not touched.
     *
     * <p>A target no weave names is not an error: the weaver returns nothing, the result's
     * {@link WeaveResult#woven()} is {@code null} and {@link WeaveResult#wasWoven()} is
     * {@code false}. {@link WeaveResult#effective()} then gives the original bytes back, so an
     * assertion about the class's shape still has something to read.
     *
     * <p>The weaver is run a second time over the same original bytes, and that pass becomes
     * {@link WeaveResult#secondPass()}. Feeding the original rather than the first pass's output
     * is what makes it a repetition of the same work rather than a test of re-weaving already
     * woven bytes. Diagnostics the second pass reports are dropped before the result is built.
     *
     * <p>The result carries the diagnostics reported between entering and leaving this method.
     * Anything reported earlier — while {@link #of(Class[])} parsed and planned the weave classes,
     * or by an earlier call to this method — is not in it. {@link #diagnostics()} holds all of
     * them.
     *
     * @param target the class to weave; must not be {@code null}
     * @return the original bytes, the woven bytes or {@code null}, the second pass, and this
     *         call's diagnostics
     * @throws NullPointerException  if {@code target} is {@code null}
     * @throws IllegalStateException if the class has no class file its class loader can supply,
     *                               which is the case for an array, a hidden class and a class
     *                               generated at run time
     * @throws UncheckedIOException  if the class file could not be read
     * @throws de.splatgames.aether.weaver.api.diagnostic.WeaveException if the woven bytes fail
     *                               verification, which {@link VerificationPolicy#STRICT} refuses
     *                               rather than reports
     */
    @NotNull
    public WeaveResult weave(@NotNull final Class<?> target) {
        Objects.requireNonNull(target, "target");

        final String internalName = internalNameOf(target);
        final byte[] original = bytesOf(target);
        final int before = this.diagnostics.size();
        final byte[] woven = this.weaver.weave(internalName, original);
        final int afterFirst = this.diagnostics.size();

        // Every class is woven twice, always. Determinism is the invariant a bytecode framework
        // is least likely to notice losing — nothing else fails, the output is simply different
        // between two builds — so it is measured here rather than left to a test that remembers to
        // ask. Feeding the ORIGINAL bytes again is what makes it a second weave rather than the
        // idempotence gate: the gate reads the stamp, and the original carries none.
        final byte[] second = this.weaver.weave(internalName, original);
        while (this.diagnostics.size() > afterFirst) {
            // The second pass reports the same things as the first. Keeping them would double every
            // diagnostic a test asserts on, for a pass the test never asked for.
            this.diagnostics.remove(this.diagnostics.size() - 1);
        }

        // Everything reported so far, not only what this call produced: a conflict found while
        // planning belongs to the first result, or a test that looks only at results never sees it.
        final List<Diagnostic> reported = before == 0
                ? List.copyOf(this.diagnostics)
                : List.copyOf(this.diagnostics.subList(before, this.diagnostics.size()));
        return new WeaveResult(internalName, original, woven, second, reported);
    }

    /**
     * Returns the weaver itself, for assertions this class does not wrap.
     *
     * <p>{@link Weaver#plan()} returns the plan fixed when the weaver was built, which does not
     * change afterward. {@link Weaver#statistics()} returns a snapshot too: each call reads the
     * counters as they stand at that moment, and the value returned does not itself move
     * afterward. The count of classes seen advances twice for every {@link #weave(Class)} call,
     * since each call weaves the target twice; the count of classes woven advances only when
     * something actually applied.
     *
     * @return the built weaver, never {@code null}
     */
    @Contract(pure = true)
    @NotNull
    public Weaver weaver() {
        return this.weaver;
    }

    /**
     * Returns every diagnostic reported since {@link #of(Class[])} began collecting them.
     *
     * <p>Parsing, planning and every {@link #weave(Class)} call, in the order reported, less
     * whatever each second pass reported, which is discarded without being compared to the
     * first. This is the only place a diagnostic raised while {@link #of(Class[])} was planning
     * is visible; {@link WeaveResult#diagnostics()} carries only what its own call reported.
     *
     * @return a snapshot, unaffected by later weaving
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    public List<Diagnostic> diagnostics() {
        return List.copyOf(this.diagnostics);
    }

    /**
     * Reads a class's own class file back out of its class loader.
     *
     * <p>The resource name is the internal name with {@code .class} appended, looked up on the
     * class's defining loader, or on the system class loader when that is {@code null} because the
     * class is a bootstrap one. A class the loader cannot produce a resource for — an array type,
     * a primitive, a hidden class, anything defined at run time — is refused with a message naming
     * the class, because there is no file to weave.
     *
     * @param type the class to read; must not be {@code null}
     * @return the class file bytes
     * @throws IllegalStateException if the loader has no resource for the class
     * @throws UncheckedIOException  if reading the resource failed
     */
    static byte @NotNull [] bytesOf(@NotNull final Class<?> type) {
        final String resource = internalNameOf(type) + ".class";
        try (InputStream stream = loaderOf(type).getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("no class file for " + type.getName()
                        + "; a hidden, generated or array class cannot be woven from a test");
            }
            return stream.readAllBytes();
        } catch (final IOException unreadable) {
            throw new UncheckedIOException("could not read " + resource, unreadable);
        }
    }

    /**
     * Returns the loader to look a class's resource up on.
     *
     * <p>{@link Class#getClassLoader()} is {@code null} for a bootstrap class, such as
     * {@code Object} or {@code String}, which is not a loader that can be asked for a resource;
     * the system class loader stands in. A platform class, such as {@code java.sql.Connection},
     * already has a non-{@code null} loader of its own and is asked directly.
     *
     * @param type the class whose loader is wanted; must not be {@code null}
     * @return the class's own loader, or the system class loader when it has none
     */
    @NotNull
    private static ClassLoader loaderOf(@NotNull final Class<?> type) {
        final ClassLoader loader = type.getClassLoader();
        return loader == null ? ClassLoader.getSystemClassLoader() : loader;
    }

    /**
     * Returns a class's internal name.
     *
     * <p>The binary name with every {@code .} replaced by {@code /}. A nested class keeps its
     * {@code $}, since the binary name already carries it. An array type comes back as its
     * descriptor with the same substitution applied, such as {@code [I}, which is not the name of
     * any class file and is why an array is refused by {@link #bytesOf(Class)} rather than here.
     *
     * @param type the class to name; must not be {@code null}
     * @return the internal name, such as {@code com/acme/Ledger}
     */
    @Contract(pure = true)
    @NotNull
    static String internalNameOf(@NotNull final Class<?> type) {
        return type.getName().replace('.', '/');
    }

    /**
     * Renders the collected diagnostics for the message of a refused weave class.
     *
     * <p>Each is rendered with {@link Diagnostic#format()} and joined with {@code "\n"}, starting
     * with one, so the block reads as a list under the message it is appended to. An empty list
     * produces a sentence pointing at a missing {@code @Weave} instead: a class carrying none is
     * not a weave and not a mistake, so {@link WeaveClassParser} returns empty and reports
     * nothing, and the refusal here would otherwise have no explanation attached.
     *
     * @param diagnostics everything reported so far; must not be {@code null}
     * @return the rendered block, or the sentence used when nothing was reported
     */
    @Contract(pure = true)
    @NotNull
    private static String describe(@NotNull final List<Diagnostic> diagnostics) {
        return diagnostics.isEmpty()
                ? "the parser reported nothing, so it is probably missing @Weave"
                : diagnostics.stream().map(Diagnostic::format).reduce("", (a, b) -> a + "\n" + b);
    }

    /**
     * Returns the plan this weaving holds, wrapped in the class name.
     *
     * <p>Describes what was planned, not what has been woven; the value does not change over the
     * life of the object.
     *
     * @return {@code Weaving[} followed by the weaver's plan and {@code ]}
     */
    @Override
    @NotNull
    public String toString() {
        return "Weaving[" + this.weaver.plan() + ']';
    }
}
