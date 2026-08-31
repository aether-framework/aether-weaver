package de.splatgames.aether.weaver.testkit;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.Severity;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Assertions about a {@link WeaveResult}: that it applied, that it verifies, that the JVM takes it,
 * that it is reproducible, and that it left alone everything no weave named.
 *
 * <p>Entered through {@link #assertThatWoven(WeaveResult)} and chained; every assertion returns
 * this same object, or throws. Nothing is collected: the first failure ends the chain, so an
 * assertion after a failing one is not evaluated.
 *
 * <p>Every assertion failure raised by this class is an {@link AssertionError} whose message
 * begins with the class's binary name in square brackets, so a failure from a parameterised test
 * says which class it was about without the test having to add that itself. A failure the chain
 * merely propagates — an {@link AssertionError} thrown by the callback given to
 * {@link #loadsAndRuns(ThrowingConsumer)} — carries no such prefix. Beyond the prefix, each
 * message is written to name what was actually found — the methods a class does have, the
 * diagnostics it did report, the instruction that changed — because the alternative is a reader
 * reaching for {@code javap}.
 *
 * <p>Depends on the result and on a parent class loader, and holds no other state; an instance may
 * be reused for as many assertions as the chain has.
 *
 * <h2>An assertion reads the effective bytes</h2>
 *
 * <p>Everything that parses, verifies or defines the class reads {@link WeaveResult#effective()},
 * which falls back to the original when nothing applied. An assertion chain that does not begin
 * with {@link #wasWoven()} therefore describes the original class when no weave named it. This is
 * harmless for the assertions that hold of any well-formed class — {@link #verifies()},
 * {@link #isAcceptedByTheJvm()}, {@link #isDeterministic()}, {@link #preservesClassVersion()},
 * {@link #preservesDebugInfo()}, {@link #preservesUntargetedMethods(String...)} and
 * {@link #satisfiesEveryInvariant()}, which composes five of them — all pass on the
 * original — but an assertion that expects something weaving added, such as
 * {@link #hasMethod(String, String)} or {@link #hasField(String, String)}, still fails on it.
 * {@link #wasWoven()} exists to close the gap for every assertion after it, and says so when it
 * fails.
 *
 * <h2>Verification and definition are different questions</h2>
 *
 * <p>{@link #verifies()} runs the class file verifier over the bytes; {@link #isAcceptedByTheJvm()}
 * hands them to a class loader. Neither subsumes the other: a class file whose name disagrees with
 * the name it is defined under passes verification and is refused at definition. The failure
 * message from {@link #isAcceptedByTheJvm()} names a second such case, an exception range whose
 * start is past its end.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * assertThatWoven(weaving.weave(Ledger.class))
 *         .wasWoven()
 *         .satisfiesEveryInvariant()
 *         .preservesUntargetedMethods("charge")
 *         .hasMethod("charge", "(I)I")
 *         .reportsNothing(Severity.WARNING)
 *         .loadsAndRuns(type -> {
 *             Object ledger = type.getDeclaredConstructor().newInstance();
 *             type.getMethod("charge", int.class).invoke(ledger, 5);
 *         });
 * }</pre>
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see WeaveResult
 * @see Weaving
 */
public final class WovenAssert {

    /** The result under assertion. */
    private final WeaveResult result;

    /** The parent of the throwaway loader the class is defined in. */
    private final ClassLoader parent;

    /**
     * Binds an assertion to a result and a parent loader.
     *
     * @param result the result to assert about; must not be {@code null}
     * @param parent the parent of the loader the class will be defined in; must not be
     *               {@code null}
     */
    private WovenAssert(@NotNull final WeaveResult result, @NotNull final ClassLoader parent) {
        this.result = result;
        this.parent = parent;
    }

    /**
     * Begins an assertion chain about a weave result.
     *
     * <p>The parent of the loader used by
     * {@link #isAcceptedByTheJvm()} and {@link #loadsAndRuns(ThrowingConsumer)} defaults to the
     * loader that loaded this class; where that loader also sees the test classpath, a woven class
     * defined under it resolves the same types the test does.
     * {@link #withParent(ClassLoader)} changes it.
     *
     * @param result the result to assert about; must not be {@code null}
     * @return a fresh assertion over that result
     * @throws NullPointerException if {@code result} is {@code null}
     */
    @Contract(value = "_ -> new", pure = true)
    @NotNull
    public static WovenAssert assertThatWoven(@NotNull final WeaveResult result) {
        return new WovenAssert(Objects.requireNonNull(result, "result"),
                WovenAssert.class.getClassLoader());
    }

    /**
     * Returns an assertion over the same result that defines the class under a different parent.
     *
     * <p>Affects only {@link #isAcceptedByTheJvm()} and {@link #loadsAndRuns(ThrowingConsumer)}.
     * Every other assertion parses bytes and never loads anything, so the parent makes no
     * difference to them. This instance is unchanged; chaining continues from the returned one.
     *
     * @param loader the parent for the throwaway loader; must not be {@code null}
     * @return a new assertion over the same result
     * @throws NullPointerException if {@code loader} is {@code null}
     */
    @Contract(value = "_ -> new", pure = true)
    @NotNull
    public WovenAssert withParent(@NotNull final ClassLoader loader) {
        return new WovenAssert(this.result, Objects.requireNonNull(loader, "loader"));
    }

    /**
     * Asserts that a weave applied to the class.
     *
     * <p>Reads {@link WeaveResult#wasWoven()} and nothing else; it does not look at whether the
     * bytes changed. Worth putting first in a chain: without it, several of the assertions after
     * it — those that hold of any well-formed class — fall back to the original class when
     * nothing applied and pass on it silently, which is the situation the failure message names.
     *
     * @return this assertion
     * @throws AssertionError if the weaver produced no bytes for the class
     */
    @NotNull
    public WovenAssert wasWoven() {
        if (!this.result.wasWoven()) {
            throw failure("nothing was woven into " + this.result.binaryName()
                    + ", so every assertion after this one would be about the original class");
        }
        return this;
    }

    /**
     * Asserts that nothing applied to the class.
     *
     * <p>The assertion a test makes about a class its weaves are not supposed to reach. The
     * failure quotes the length of the bytes the weaver produced, which is the only fact about
     * them worth stating when the expectation was that there would be none.
     *
     * @return this assertion
     * @throws AssertionError if the weaver produced bytes for the class
     */
    @NotNull
    public WovenAssert wasNotWoven() {
        if (this.result.wasWoven()) {
            throw failure("expected nothing to apply to " + this.result.binaryName()
                    + ", but the weaver produced " + this.result.woven().length + " bytes");
        }
        return this;
    }

    /**
     * Asserts that the class file verifier accepts the effective bytes.
     *
     * <p>Runs {@link ClassFile#verify(byte[])}. Every {@link VerifyError} it returns appears in
     * the failure, one per line indented by four spaces, carrying that error's own message and
     * not its type or stack trace. Nothing is loaded.
     *
     * @return this assertion
     * @throws AssertionError if the verifier reported anything
     */
    @NotNull
    public WovenAssert verifies() {
        final List<VerifyError> errors = ClassFile.of().verify(this.result.effective());
        if (!errors.isEmpty()) {
            throw failure("the woven class does not verify:"
                    + errors.stream().map(error -> System.lineSeparator() + "    " + error.getMessage())
                    .reduce("", String::concat));
        }
        return this;
    }

    /**
     * Asserts that a class loader will define the effective bytes.
     *
     * <p>Defines them in a throwaway loader whose parent is the one this assertion carries, under
     * {@link WeaveResult#binaryName()}. The defined class is discarded; nothing in it is run, and
     * no static initialiser fires, because definition does not initialise.
     *
     * <p>The loader is new for each call and nothing is loaded through it beforehand, so a name
     * already defined elsewhere in the JVM is not a conflict. Two calls on one chain define the
     * class twice, in two loaders.
     *
     * <p>The failure carries the {@link LinkageError} that was raised, both in the message and as
     * the cause, and adds the note that verification is necessary rather than sufficient.
     *
     * @return this assertion
     * @throws AssertionError if the loader refused the bytes
     */
    @NotNull
    public WovenAssert isAcceptedByTheJvm() {
        define();
        return this;
    }

    /**
     * Defines the effective bytes and hands the resulting class to a callback.
     *
     * <p>The class the callback receives is the woven copy, defined in a throwaway loader, and not
     * the class the JVM already has under that name. Reflection on it therefore observes the woven
     * members. Anything the woven code writes to a class the throwaway loader did not redefine —
     * one reached through the parent — is visible to the test afterwards, which is how a handler
     * that records a mark can be observed at all.
     *
     * <p>The callback may throw anything. An {@link AssertionError} propagates as it is, on the
     * grounds that it already explains itself; anything else becomes an {@link AssertionError}
     * quoting the throwable's {@code toString} and carrying it as the cause. A checked exception
     * from reflection therefore surfaces as an assertion failure, not as a compile error at the
     * call site.
     *
     * @param assertion what to do with the defined class; must not be {@code null}
     * @return this assertion
     * @throws NullPointerException if {@code assertion} is {@code null}
     * @throws AssertionError       if the loader refused the bytes, or if the callback threw
     */
    @NotNull
    public WovenAssert loadsAndRuns(@NotNull final ThrowingConsumer<Class<?>> assertion) {
        Objects.requireNonNull(assertion, "assertion");
        final Class<?> defined = define();
        try {
            assertion.accept(defined);
        } catch (final AssertionError alreadyExplained) {
            throw alreadyExplained;
        } catch (final Throwable failed) {
            final AssertionError error = failure("the woven class loaded but did not behave: "
                    + failed);
            error.initCause(failed);
            throw error;
        }
        return this;
    }

    /**
     * Asserts that weaving the same class twice produced the same bytes.
     *
     * <p>Compares {@link WeaveResult#woven()} with {@link WeaveResult#secondPass()}, which
     * {@link Weaving#weave(Class)} produced by running the weaver over the original bytes a second
     * time. Three outcomes:
     *
     * <ul>
     *   <li>Both absent. Passes. Nothing applied on either pass, which is as reproducible as
     *       applying the same thing twice.
     *   <li>One absent. Fails, naming a plan that depends on something outside its inputs.
     *   <li>Both present and unequal. Fails, quoting both lengths — which are equal when the
     *       difference is in the content rather than the size.
     * </ul>
     *
     * <p>The comparison is over raw bytes, so a difference in constant-pool order that changes
     * nothing about behaviour still fails. That is the point: two builds of the same source that
     * do not agree byte for byte break every downstream comparison, and nothing else in a build
     * reports it.
     *
     * @return this assertion
     * @throws AssertionError if the two passes disagree about whether or what to weave
     */
    @NotNull
    public WovenAssert isDeterministic() {
        final byte[] first = this.result.woven();
        final byte[] second = this.result.secondPass();
        if (first == null && second == null) {
            return this;
        }
        if (first == null || second == null) {
            throw failure("weaving " + this.result.binaryName() + " applied on one pass and not "
                    + "the other, which is a plan that depends on something outside its inputs");
        }
        if (!Arrays.equals(first, second)) {
            throw failure("weaving " + this.result.binaryName() + " twice produced different "
                    + "bytes (" + first.length + " and " + second.length + "); two builds of the "
                    + "same source would not agree, and nothing else would report it");
        }
        return this;
    }

    /**
     * Asserts that every method except the named ones has the instruction sequence it started
     * with.
     *
     * <p>Each method of the original is looked up in the effective bytes by name and descriptor
     * and its instructions compared one by one. Pseudo-instructions are excluded from the
     * comparison, so a moved line number or a widened local-variable range is not a change;
     * {@link #preservesDebugInfo()} is what watches those.
     *
     * <p>The exemptions are method <em>names</em>, not selectors and not descriptors, so naming
     * {@code "charge"} exempts every overload of {@code charge}. A name that matches nothing is
     * accepted silently. {@code <init>} and {@code <clinit>} are spelled out in full if they are
     * to be exempted.
     *
     * <p>Only methods present in the original and carrying a body are examined; an abstract or
     * native method has no instructions and is not compared. A method weaving <em>added</em> is
     * not reported by this assertion; {@link #hasMethod(String, String)} is what asserts about
     * one.
     * A method that disappeared is reported, as is one whose body changed, and both messages say
     * {@code and no weave named it}, but only the changed-body message goes on: it is followed by
     * the first instruction index at which the two disagree, with the old line marked {@code -}
     * and the new one {@code +}, or, when one sequence is merely a prefix of the other, by the two
     * lengths instead.
     *
     * @param changed the names of the methods a weave was expected to alter; must not be
     *                {@code null}, and may be empty to require that nothing changed at all
     * @return this assertion
     * @throws NullPointerException if {@code changed} is {@code null} or contains {@code null}
     * @throws AssertionError       if a method not named is missing or has a different body
     */
    @NotNull
    public WovenAssert preservesUntargetedMethods(final String @NotNull ... changed) {
        Objects.requireNonNull(changed, "changed");
        final Set<String> expected = new LinkedHashSet<>(List.of(changed));

        final Map<String, List<String>> before = MethodBodies.of(this.result.original());
        final Map<String, List<String>> after = MethodBodies.of(this.result.effective());

        for (final Map.Entry<String, List<String>> method : before.entrySet()) {
            final String name = method.getKey().substring(0, method.getKey().indexOf('('));
            if (expected.contains(name)) {
                continue;
            }
            final List<String> now = after.get(method.getKey());
            if (now == null) {
                throw failure(method.getKey() + " is gone from " + this.result.binaryName()
                        + ", and no weave named it");
            }
            if (!now.equals(method.getValue())) {
                throw failure(method.getKey() + " changed in " + this.result.binaryName()
                        + " and no weave named it" + difference(method.getValue(), now));
            }
        }
        return this;
    }

    /**
     * Asserts that weaving did not move the class file version.
     *
     * <p>Compares both the major and the minor version of the original against the effective
     * bytes. A raised version is the failure worth catching: the class still loads on the JDK that
     * built it and fails on the older JVM it is deployed to, and the failure message says so.
     *
     * @return this assertion
     * @throws AssertionError if either version number differs
     */
    @NotNull
    public WovenAssert preservesClassVersion() {
        final ClassModel before = ClassFile.of().parse(this.result.original());
        final ClassModel after = ClassFile.of().parse(this.result.effective());
        if (before.majorVersion() != after.majorVersion()
                || before.minorVersion() != after.minorVersion()) {
            throw failure("the class file version changed from " + before.majorVersion() + '.'
                    + before.minorVersion() + " to " + after.majorVersion() + '.'
                    + after.minorVersion() + "; a raised version fails on the deployment JVM and "
                    + "not on the one that built it");
        }
        return this;
    }

    /**
     * Asserts that no method lost the debug attributes it had.
     *
     * <p>Three kinds are watched, and only these three: {@code LineNumberTable},
     * {@code LocalVariableTable} and {@code LocalVariableTypeTable}. Presence is all that is
     * compared — a method that kept its {@code LineNumberTable} passes even if every number in it
     * moved.
     *
     * <p>The check runs in one direction. A method that had none of the three cannot fail it, and
     * an attribute that weaving <em>added</em> is not a failure either. A method that vanished
     * from the class altogether counts as having lost every attribute it had, and is reported as
     * such rather than as a missing method.
     *
     * @return this assertion
     * @throws AssertionError if a method that had one of the three attributes no longer has it
     */
    @NotNull
    public WovenAssert preservesDebugInfo() {
        final Map<String, Set<String>> before = MethodBodies.debugInfo(this.result.original());
        final Map<String, Set<String>> after = MethodBodies.debugInfo(this.result.effective());

        for (final Map.Entry<String, Set<String>> method : before.entrySet()) {
            final Set<String> now = after.getOrDefault(method.getKey(), Set.of());
            for (final String attribute : method.getValue()) {
                if (!now.contains(attribute)) {
                    throw failure(method.getKey() + " lost its " + attribute + "; every stack "
                            + "trace through that method becomes a puzzle, and nothing fails "
                            + "until somebody has to read one");
                }
            }
        }
        return this;
    }

    /**
     * Asserts that nothing was reported at or above a severity.
     *
     * <p>Reads the diagnostics the result carries, which are those of the call that produced it
     * and not everything the weaver has ever reported;
     * {@link Weaving#diagnostics()} is the wider view. The failure's first line names the
     * threshold that was asked for; every offending diagnostic follows, rendered with
     * {@link Diagnostic#format()} and started on a new line indented by four spaces. Only that
     * first line is indented here — the detail and remedy lines are part of what
     * {@link Diagnostic#format()} produced and carry its own indentation, and none of them carries
     * a severity, which that rendering omits.
     *
     * @param atLeast the lowest severity that counts as a failure; must not be {@code null}
     * @return this assertion
     * @throws NullPointerException if {@code atLeast} is {@code null}
     * @throws AssertionError       if any diagnostic is at or above that severity
     */
    @NotNull
    public WovenAssert reportsNothing(@NotNull final Severity atLeast) {
        Objects.requireNonNull(atLeast, "atLeast");
        final List<Diagnostic> found = this.result.diagnostics().stream()
                .filter(diagnostic -> diagnostic.severity().isAtLeast(atLeast))
                .toList();
        if (!found.isEmpty()) {
            throw failure("expected nothing at " + atLeast + " or worse, but got:"
                    + found.stream()
                    .map(diagnostic -> System.lineSeparator() + "    " + diagnostic.format())
                    .reduce("", String::concat));
        }
        return this;
    }

    /**
     * Asserts that a diagnostic code appears among what was reported.
     *
     * <p>The code is compared as a string against {@link WeaveResult#codes(Severity)} taken at
     * {@link Severity#DEBUG}, so a code of any severity counts and nothing has to be said about
     * which. Written exactly as it appears in a build log, such as {@code AW1043}. A misspelled
     * or non-existent code simply fails to match; nothing here checks that the string names a code
     * the project declares.
     *
     * <p>Only presence is asserted. A code reported more than once satisfies this once, and the
     * failure lists every code that was reported so the reader can see what arrived instead.
     *
     * @param code the diagnostic code expected; must not be {@code null}
     * @return this assertion
     * @throws NullPointerException if {@code code} is {@code null}
     * @throws AssertionError       if no diagnostic carries that code
     */
    @NotNull
    public WovenAssert reports(@NotNull final String code) {
        Objects.requireNonNull(code, "code");
        if (!this.result.codes(Severity.DEBUG).contains(code)) {
            throw failure("expected " + code + ", but " + this.result.binaryName()
                    + " reported " + this.result.codes(Severity.DEBUG));
        }
        return this;
    }

    /**
     * Asserts that the effective bytes declare a method.
     *
     * <p>Name and descriptor must both match exactly; the descriptor is the class file form, such
     * as {@code "(I)I"}, not a Java signature. Only the class's own methods are looked at, so an
     * inherited method is not found. The failure lists every method the class does declare, each
     * as its name immediately followed by its descriptor.
     *
     * @param name       the method name; must not be {@code null}
     * @param descriptor the method descriptor in class file form; must not be {@code null}
     * @return this assertion
     * @throws NullPointerException if either argument is {@code null}
     * @throws AssertionError       if the class declares no such method
     */
    @NotNull
    public WovenAssert hasMethod(@NotNull final String name, @NotNull final String descriptor) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(descriptor, "descriptor");

        final ClassModel model = ClassFile.of().parse(this.result.effective());
        final boolean found = model.methods().stream()
                .anyMatch(method -> method.methodName().equalsString(name)
                        && method.methodType().equalsString(descriptor));
        if (!found) {
            throw failure(this.result.binaryName() + " has no " + name + descriptor + "; it has "
                    + model.methods().stream()
                    .map(method -> method.methodName().stringValue()
                            + method.methodType().stringValue())
                    .toList());
        }
        return this;
    }

    /**
     * Asserts that the effective bytes declare a field.
     *
     * <p>Name and descriptor must both match exactly; the descriptor is the class file form, such
     * as {@code "I"} or {@code "Ljava/lang/String;"}. Only the class's own fields are looked at.
     * The failure lists every field the class does declare, each as {@code name:descriptor}, which
     * is also how the expectation is spelled in the message.
     *
     * @param name       the field name; must not be {@code null}
     * @param descriptor the field descriptor in class file form; must not be {@code null}
     * @return this assertion
     * @throws NullPointerException if either argument is {@code null}
     * @throws AssertionError       if the class declares no such field
     */
    @NotNull
    public WovenAssert hasField(@NotNull final String name, @NotNull final String descriptor) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(descriptor, "descriptor");

        final ClassModel model = ClassFile.of().parse(this.result.effective());
        final boolean found = model.fields().stream()
                .anyMatch(field -> field.fieldName().equalsString(name)
                        && field.fieldType().equalsString(descriptor));
        if (!found) {
            throw failure(this.result.binaryName() + " has no field " + name + ':' + descriptor
                    + "; it has " + model.fields().stream()
                    .map(field -> field.fieldName().stringValue() + ':'
                            + field.fieldType().stringValue())
                    .toList());
        }
        return this;
    }

    /**
     * Runs the five invariants that need no argument, in order.
     *
     * <p>{@link #verifies()}, {@link #isAcceptedByTheJvm()}, {@link #isDeterministic()},
     * {@link #preservesClassVersion()} and {@link #preservesDebugInfo()}. The order matters only
     * in that the first to fail ends the chain, and it puts the two structural checks first, so a
     * class that is not a valid class file is reported as such rather than as a version or a
     * debug-info difference.
     *
     * <p>It covers only these five. In particular it does not call
     * {@link #preservesUntargetedMethods(String...)}, which needs the list of methods a weave was
     * meant to touch and only the test knows that, and it does not call {@link #wasWoven()}: when
     * nothing applied, the effective bytes are the original, and all five then say something about
     * the original class rather than about weaving.
     *
     * @return this assertion
     * @throws AssertionError if any of the five fails
     */
    @NotNull
    public WovenAssert satisfiesEveryInvariant() {
        return verifies()
                .isAcceptedByTheJvm()
                .isDeterministic()
                .preservesClassVersion()
                .preservesDebugInfo();
    }

    /**
     * Defines the effective bytes in a throwaway loader.
     *
     * <p>A fresh {@link Definer} per call, so nothing is ever redefined and the defined class is
     * unreachable once the caller drops it. A {@link LinkageError} becomes an
     * {@link AssertionError} so that the refusal reports as a failing assertion; the error is
     * quoted in the message and kept as the cause.
     *
     * @return the defined class
     * @throws AssertionError if the loader refused the bytes
     */
    @NotNull
    private Class<?> define() {
        final byte[] bytes = this.result.effective();
        try {
            return new Definer(this.parent).define(this.result.binaryName(), bytes);
        } catch (final LinkageError refused) {
            final AssertionError error = failure("the JVM refused the woven class: " + refused
                    + System.lineSeparator()
                    + "    ClassFile.verify is necessary and not sufficient — an exception range "
                    + "with start > end passes it and fails here");
            error.initCause(refused);
            throw error;
        }
    }

    /**
     * Describes where two instruction sequences first disagree.
     *
     * <p>Scans to the first differing index within the overlap and renders that one pair, the old
     * line prefixed {@code -} and the new one {@code +}, each on its own indented line under a
     * header naming the index. One instruction is enough: a reader who has the index can find the
     * rest.
     *
     * <p>When neither sequence differs within the overlap, one is a prefix of the other and there
     * is no differing instruction to point at; the two lengths are reported instead.
     *
     * @param before the original instructions; must not be {@code null}
     * @param after  the instructions now; must not be {@code null}
     * @return the rendered difference, always beginning with a line break
     */
    @Contract(pure = true)
    @NotNull
    private static String difference(@NotNull final List<String> before,
                                     @NotNull final List<String> after) {
        for (int i = 0; i < Math.min(before.size(), after.size()); i++) {
            if (!before.get(i).equals(after.get(i))) {
                return System.lineSeparator() + "    at instruction " + i + ':'
                        + System.lineSeparator() + "    - " + before.get(i)
                        + System.lineSeparator() + "    + " + after.get(i);
            }
        }
        return System.lineSeparator() + "    " + before.size() + " instructions became "
                + after.size();
    }

    /**
     * Builds the {@link AssertionError} every assertion in this class throws.
     *
     * <p>Prefixes the message with the class's binary name in square brackets. Every failure from
     * this class carries that prefix and nothing else in common, so a message can be written as a
     * sentence about the class without repeating its name.
     *
     * @param message what went wrong; must not be {@code null}
     * @return the error, not thrown
     */
    @Contract(value = "_ -> new", pure = true)
    @NotNull
    private AssertionError failure(@NotNull final String message) {
        return new AssertionError("[" + this.result.binaryName() + "] " + message);
    }

    /**
     * A loader that exists to define one class and then be discarded.
     *
     * <p>{@link ClassLoader#defineClass(String, byte[], int, int)} is protected, so reaching it at
     * all requires a subclass. Nothing else is overridden: a name this loader has not defined is
     * delegated to the parent in the usual way, which is what lets a woven class reach the test's
     * own types.
     *
     * <p>Named {@code aether-testkit}, so a stack trace or a
     * {@link ClassLoader#getName()} identifies where a class of unexpected provenance came from.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private static final class Definer extends ClassLoader {

        /**
         * Creates the loader.
         *
         * @param parent what to delegate everything else to; must not be {@code null}
         */
        Definer(@NotNull final ClassLoader parent) {
            super("aether-testkit", parent);
        }

        /**
         * Defines a class from the whole of the given array.
         *
         * <p>Defining only; the class is not initialised, and no protection domain is supplied.
         * Calling this twice with the same name on one instance raises a
         * {@link LinkageError}, which is why the caller creates a loader per call.
         *
         * @param binaryName the name to define the class under, which must be the name the bytes
         *                   themselves declare; must not be {@code null}
         * @param bytes      the class file; must not be {@code null}
         * @return the defined class
         * @throws LinkageError if the bytes are malformed or disagree with {@code binaryName}
         */
        @NotNull
        Class<?> define(@NotNull final String binaryName, final byte @NotNull [] bytes) {
            return defineClass(binaryName, bytes, 0, bytes.length);
        }
    }

    /**
     * A consumer that may throw anything.
     *
     * <p>Exists so that the body of {@link WovenAssert#loadsAndRuns(ThrowingConsumer)} can call
     * reflection directly. {@link java.util.function.Consumer} would force each call site to wrap
     * a {@link ReflectiveOperationException} in a try-catch that says nothing a test wants to say.
     *
     * @param <T> what is consumed
     * @author Erik Pförtner
     * @since 0.1.0
     */
    @FunctionalInterface
    public interface ThrowingConsumer<T> {

        /**
         * Consumes the value.
         *
         * <p>What is thrown decides how the failure reads: an {@link AssertionError} reaches the
         * test unchanged, and anything else is wrapped in one by
         * {@link WovenAssert#loadsAndRuns(ThrowingConsumer)}.
         *
         * @param value what to consume
         * @throws Throwable anything the body raises
         */
        void accept(T value) throws Throwable;
    }

    /**
     * Returns the result under assertion, wrapped in the class name.
     *
     * <p>Delegates to {@link WeaveResult#toString()}, so it names the class, whether anything
     * applied and how many diagnostics there are. The parent class loader does not appear.
     *
     * @return {@code WovenAssert[} followed by the result's own summary and {@code ]}
     */
    @Override
    @NotNull
    public String toString() {
        return "WovenAssert[" + this.result + ']';
    }

    /**
     * Reduces a class file to the two things the preservation assertions compare.
     *
     * <p>Both methods key by name immediately followed by descriptor, so an overload is its own
     * entry, and both iterate in class file order and preserve it. A method with no {@code Code}
     * attribute — abstract, native — produces no entry at all, which is why an absent key and an
     * empty value mean different things to the callers.
     *
     * @author Erik Pförtner
     * @since 0.1.0
     */
    private static final class MethodBodies {

        /**
         * Refuses instantiation.
         *
         * @throws AssertionError always
         */
        private MethodBodies() {
            throw new AssertionError("no instances");
        }

        /**
         * Returns each method's instructions, rendered as text.
         *
         * <p>Only elements that are {@link java.lang.classfile.Instruction} are kept; everything
         * else the code element stream carries is dropped. Those are position markers — labels,
         * line numbers, local variable ranges — and comparing them would report a method as
         * changed for a difference that is not in what it executes.
         *
         * <p>Each instruction is compared by its {@code toString}. That is what makes the
         * comparison independent of the object identities two parses of the same bytes produce,
         * and it is also its limit: two instructions whose renderings coincide compare equal.
         *
         * @param classFile the class to read; must not be {@code null}
         * @return the instructions per method, in class file order
         */
        @NotNull
        static Map<String, List<String>> of(final byte @NotNull [] classFile) {
            final Map<String, List<String>> bodies = new java.util.LinkedHashMap<>();
            for (final MethodModel method : ClassFile.of().parse(classFile).methods()) {
                method.code().ifPresent(code -> bodies.put(
                        method.methodName().stringValue() + method.methodType().stringValue(),
                        code.elementStream()
                                .filter(element -> element instanceof java.lang.classfile.Instruction)
                                // Pseudo-instructions are position markers: labels, line numbers and
                                // local variable ranges. They differ between two parses of the same
                                // bytes, so comparing them would report every method as changed.
                                .map(Object::toString)
                                .toList()));
            }
            return bodies;
        }

        /**
         * Returns which debug attributes each method's code carries.
         *
         * <p>Presence only, and only three kinds, named by the class file attribute they end up
         * in: a {@link java.lang.classfile.instruction.LineNumber} element implies a
         * {@code LineNumberTable}, a {@link java.lang.classfile.instruction.LocalVariable} implies
         * a {@code LocalVariableTable}, and a
         * {@link java.lang.classfile.instruction.LocalVariableType} implies a
         * {@code LocalVariableTypeTable}. How many of each there are, and what they say, is not
         * recorded; a method that kept one entry of a table counts as still having it.
         *
         * <p>A method whose code carries none of the three maps to an empty set rather than being
         * left out, so the caller can tell a method with no debug information from a method that
         * is not there.
         *
         * @param classFile the class to read; must not be {@code null}
         * @return the attribute names present per method, in class file order
         */
        @NotNull
        static Map<String, Set<String>> debugInfo(final byte @NotNull [] classFile) {
            final Map<String, Set<String>> found = new java.util.LinkedHashMap<>();
            for (final MethodModel method : ClassFile.of().parse(classFile).methods()) {
                method.code().ifPresent(code -> {
                    final Set<String> kinds = new LinkedHashSet<>();
                    code.elementStream().forEach(element -> {
                        if (element instanceof java.lang.classfile.instruction.LineNumber) {
                            kinds.add("LineNumberTable");
                        } else if (element
                                instanceof java.lang.classfile.instruction.LocalVariable) {
                            kinds.add("LocalVariableTable");
                        } else if (element
                                instanceof java.lang.classfile.instruction.LocalVariableType) {
                            kinds.add("LocalVariableTypeTable");
                        }
                    });
                    found.put(method.methodName().stringValue()
                            + method.methodType().stringValue(), kinds);
                });
            }
            return found;
        }
    }
}
