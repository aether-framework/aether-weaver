/**
 * Extension members: methods and constants a holder class contributes to types it does not own.
 *
 * <h2>Stability</h2>
 *
 * <p>Every type in this package carries {@code @ApiStatus.Experimental}, and that annotation is the
 * whole of the promise the source makes. No compatibility guarantee is stated for any of these
 * declarations, and nothing here names a release in which their shape is fixed. Code that names
 * {@link Extension}, {@link Receiver}, {@link Nulls} or {@link Scope} should be expected to need
 * rewriting between versions and should be kept in as few places as possible. Nothing else in
 * {@code aether-weaver-api} is marked this way.
 *
 * <h2>What an extension is</h2>
 *
 * <p>A class annotated {@link Extension} is a holder. Every {@code public} method it declares is
 * contributed to some receiver type, and every field carrying {@link Receiver} is contributed to one
 * as a constant. A call written {@code value.name(...)}, {@code Type.name(...)} or {@code Type.NAME}
 * against the receiver is then served by the holder's own {@code static} member. The holder is never
 * instantiated, and a method that is {@code private}, package-private or {@code protected} is an
 * ordinary helper that is not examined at all.
 *
 * <p>Three steps make that work, and they happen in three different places:
 *
 * <ol>
 *   <li>The annotation processor checks each contribution and records it in the generated weave
 *       manifest, as a
 *       {@link de.splatgames.aether.weaver.api.manifest.WeaveManifest.Extension} entry.
 *   <li>A build step reads the manifests on the compile classpath and produces compiler stubs, so that
 *       a call site naming a contributed member compiles.
 *   <li>The engine rewrites those call sites to the holder's static method.
 * </ol>
 *
 * <p>The middle step is where {@link Scope} is enforced, and the only place it is: a call site can name
 * a contributed member only if a stub for it was produced, so withholding the stub is what withholds
 * the member. Nothing at weave time consults the scope, which means a call site that did compile is
 * rewritten whatever the scope says. {@link Scope#MODULE} decides ownership by the compilation output
 * directory being on the classpath, so a build with no output directory to compare against owns
 * nothing and withholds every entry with that scope.
 *
 * <h2>Naming the receiver</h2>
 *
 * <p>Three positions, and they mean three different things rather than being spellings of one.
 *
 * <table border="1">
 *   <caption>Where {@link Receiver} sits, and what it contributes</caption>
 *   <tr><th>Position</th><th>Contributes</th><th>Receiver type</th><th>Call site</th></tr>
 *   <tr><td>the first parameter</td><td>an instance method</td>
 *       <td>the parameter's own type; {@link Receiver#value()} is not read</td>
 *       <td>{@code value.name(...)}</td></tr>
 *   <tr><td>the method</td><td>a {@code static} method</td>
 *       <td>{@link Receiver#value()}, which must be set</td><td>{@code Type.name(...)}</td></tr>
 *   <tr><td>a field</td><td>a constant</td><td>{@link Receiver#value()}, which must be set</td>
 *       <td>{@code Type.NAME}</td></tr>
 * </table>
 *
 * <p>{@link Extension#value()} is the fourth way: it names one receiver for every method of the class
 * that carries no {@link Receiver} of its own, and parameter zero is then the receiver by position. A
 * method carrying its own {@link Receiver} is judged by that alone and ignores the class-level one, so
 * a class receiver and a method naming a different one are both honoured.
 *
 * <h2>What is refused</h2>
 *
 * <p>Every code in the {@code AW13xx} block belongs to this feature. All of them are reported by the
 * annotation processor except {@code AW1309}, which only the engine raises, while {@code AW1308} is
 * raised by both.
 *
 * <ul>
 *   <li><b>The holder.</b> Not {@code final} is {@code AW1300}, a warning, and the contributions are
 *       made anyway. Type parameters are {@code AW1306} and a supertype other than {@link Object} is
 *       {@code AW1307}; either stops the class from being examined further.
 *   <li><b>The method.</b> Not {@code static} is {@code AW1301}; its own type parameters are
 *       {@code AW1310}; no receiver at all is {@code AW1302}; both a method-level and a
 *       parameter-level {@link Receiver} is {@code AW1313}; {@link Receiver} on a parameter other than
 *       the first is {@code AW1303}; and a method that ignores a class-level {@link Extension#value()}
 *       by not declaring that type first is {@code AW1316}.
 *   <li><b>The receiver type.</b> A primitive, an array, a type variable or a {@link Receiver#value()}
 *       left at its default of {@code void.class} is {@code AW1304}. A parameterised type is
 *       {@code AW1311}, because erasure is all the call site has. {@link Object} is {@code AW1312}, a
 *       warning, and the member is then offered on every expression in every module that reads the
 *       manifest.
 *   <li><b>Collisions.</b> A name and descriptor the receiver already declares is {@code AW1305} at
 *       compile time and {@code AW1309} at weave time — javac resolves such a call to the member that
 *       genuinely exists, so the contribution would never be reached. A name and descriptor another
 *       contribution already uses is {@code AW1308}, whose usual cause is two overloads that erase to
 *       the same descriptor; the same code covers two artefacts contributing the same call.
 *   <li><b>Constants.</b> A contributed field must be {@code public static final}, or {@code AW1314}.
 *       {@link Receiver#nulls()} on a method or a field is {@code AW1315}: a static contribution and a
 *       constant are read off the type itself, so there is no receiver value for a policy to speak
 *       about.
 * </ul>
 *
 * <h2>The null receiver</h2>
 *
 * <p>An extension is called through a rewritten call site rather than through a virtual dispatch, so
 * the {@link NullPointerException} a reader expects from {@code value.name()} on a {@code null} value
 * does not happen by itself. {@link Nulls} is what closes that gap, and declaring one is a decision
 * rather than a formality. {@link Nulls#CHECKED} is the only constant that changes the emitted code:
 * the holder's method gains a prologue rejecting a {@code null} receiver. {@link Nulls#NULLABLE} says
 * the member answers for a {@code null} itself and {@link Nulls#UNCHECKED}, the default, promises
 * nothing; neither emits anything.
 *
 * <h2>What an older toolchain does with a newer manifest</h2>
 *
 * <p>{@link Extension#require()}, {@link Extension#scope()} and {@link Receiver#nulls()} are written
 * into the generated manifest by the name of the enumeration constant, and only where the value
 * differs from the default — {@link de.splatgames.aether.weaver.api.Require#REQUIRED},
 * {@link Scope#PUBLIC} and {@link Nulls#UNCHECKED} are omitted rather than written out, which is why
 * an entry that names none of them reads back as all three.
 *
 * <p>A reader that meets a constant it does not know reports {@code AW2300} and keeps the entry with
 * the default. Gaining a constant is therefore readable by an older toolchain, at the cost of a
 * diagnostic and of the policy not being enforced there — and the direction that costs matters is
 * {@link Scope}, where the fallback to {@link Scope#PUBLIC} offers a contribution more widely than it
 * asked to be offered. An unknown extension {@code kind} is graded differently and costs the entry, on
 * the ground that guessing what shape of call to rewrite is worse than not rewriting it.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @Extension(BigDecimal.class)          // the class receiver: parameter zero, by position
 * public final class Amounts {
 *
 *     @Receiver(BigDecimal.class)       // a constant: BigDecimal.CENT
 *     public static final BigDecimal CENT = new BigDecimal("0.01");
 *
 *     private Amounts() {
 *         throw new AssertionError("no instances");
 *     }
 *
 *     // amount.asMoney("EUR") — the receiver is checked for null before the body runs
 *     public static String asMoney(@Receiver(nulls = Nulls.CHECKED) BigDecimal self, String symbol) {
 *         return symbol + self.setScale(2, RoundingMode.HALF_UP);
 *     }
 *
 *     // amount.split(3) — no @Receiver, so the class receiver applies and must come first
 *     public static List<BigDecimal> split(BigDecimal self, int parts) {
 *         ...
 *     }
 *
 *     // BigDecimal.parse("1,234.50") — @Receiver on the method makes it static
 *     @Receiver(BigDecimal.class)
 *     public static BigDecimal parse(String text) {
 *         return new BigDecimal(text.replace(",", ""));
 *     }
 * }
 * }</pre>
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
package de.splatgames.aether.weaver.api.experimental;
