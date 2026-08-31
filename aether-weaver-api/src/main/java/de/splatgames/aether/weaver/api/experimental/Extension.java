package de.splatgames.aether.weaver.api.experimental;

import org.jetbrains.annotations.ApiStatus;

import de.splatgames.aether.weaver.api.Require;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a holder of members contributed to types it does not own.
 *
 * <p>Every {@code public} method of an annotated class is contributed to some receiver type, and
 * every field carrying {@link Receiver} is contributed to one as a constant. The annotation
 * processor records each contribution in the generated weave manifest, a build step produces
 * compiler stubs so that call sites naming the contributed member compile, and the engine rewrites
 * those call sites to the holder's own static method. The holder class is never instantiated.
 *
 * <h2>Stability</h2>
 *
 * <p>Marked {@link ApiStatus.Experimental}, as is every other type in this package. That annotation
 * is the whole of the promise the source makes: no compatibility guarantee is stated for this
 * declaration, and nothing here names a release in which its shape is fixed. A caller should expect
 * to revisit any code that names {@code @Extension}, {@link Receiver}, {@link Nulls} or
 * {@link Scope} when moving between versions, and should keep such code in as few places as
 * possible.
 *
 * <p>One consequence is visible in the manifest and is worth planning for. A {@link #require()} or
 * {@link #scope()} that holds its default is omitted from the generated document; only a value that
 * differs from the default is written, by the name of the enum constant. A reader that does not
 * know a constant it does find there reports {@code AW2300} and treats the entry as the default
 * rather than dropping it. Gaining a constant is therefore readable by an older toolchain, at the
 * cost of a diagnostic and of the policy not being enforced there.
 *
 * <h2>What the holder class must be</h2>
 *
 * <p>Each of these is checked by the annotation processor.
 *
 * <ul>
 *   <li><b>It should be {@code final}.</b> Reported as {@code AW1300}, a warning, and the
 *       contributions are made anyway. A holder is never instantiated and never subclassed.
 *   <li><b>It declares no type parameters.</b> Reported as {@code AW1306}. Contributed methods are
 *       looked up by descriptor, and a type parameter on the holder has nothing to bind to at the
 *       call site. Nothing further in the class is checked once this is reported.
 *   <li><b>It extends {@link Object} and implements nothing.</b> Reported as {@code AW1307}, and
 *       likewise stops the class from being examined further. Nothing about the holder participates
 *       at the call site, so a supertype states a relationship the framework cannot honour.
 * </ul>
 *
 * <h2>What a contributed method must be</h2>
 *
 * <p>Only {@code public} methods are contributed. A method that is {@code private},
 * package-private or {@code protected} is an ordinary helper and is not examined at all, which is
 * how a holder keeps internal methods.
 *
 * <ul>
 *   <li><b>{@code static}.</b> Reported as {@code AW1301}. The receiver is passed as a parameter,
 *       so a contributed method has no instance of its own to be called on.
 *   <li><b>No type parameters of its own.</b> Reported as {@code AW1310}. The stub the compiler
 *       resolves against would carry a type variable with nothing to bind it, so inference at the
 *       call site would differ from what the declaration says.
 *   <li><b>Exactly one receiver, declared in exactly one of the three ways below.</b> None at all
 *       is {@code AW1302}; both a method-level and a parameter-level {@link Receiver} is
 *       {@code AW1313}.
 *   <li><b>A receiver type that can carry a member.</b> A primitive, an array or a type variable is
 *       {@code AW1304}; a parameterised type is {@code AW1311}, because erasure is all the call
 *       site has; {@link Object} is {@code AW1312}, a warning, and the member is contributed to
 *       every expression in every module that reads the manifest.
 *   <li><b>A name and descriptor the receiver does not already declare.</b> Reported as
 *       {@code AW1305} at compile time and as {@code AW1309} at weave time. javac resolves such a
 *       call to the member that genuinely exists, so the contribution would never be reached.
 *   <li><b>A name and descriptor no other contribution of this class already uses.</b> Reported as
 *       {@code AW1308}, whose usual cause is two overloads that erase to the same descriptor. The
 *       same code covers two artefacts contributing the same call.
 * </ul>
 *
 * <h2>The three ways to name a receiver</h2>
 *
 * <ol>
 *   <li><b>{@link Receiver} on the first parameter</b> contributes an instance member: the call
 *       site {@code value.name(...)} is rewritten to a call of the holder's method with
 *       {@code value} as argument zero. The receiver type is the parameter's own type, and
 *       {@link Receiver#value()} is not consulted. {@link Receiver} on a later parameter is
 *       {@code AW1303}.
 *   <li><b>{@link Receiver} on the method</b> contributes a {@code static} member:
 *       {@code Type.name(...)}. {@link Receiver#value()} names the type and must be set, since its
 *       default of {@code void.class} is {@code AW1304}. The call site passes exactly what the
 *       implementation takes.
 *   <li><b>{@link #value()} on this annotation</b> names one receiver for every method of the class
 *       that carries no {@link Receiver} of its own. Parameter zero is then the receiver by
 *       position and must be declared as exactly that type; a method that takes something else, or
 *       nothing, is {@code AW1316} rather than being left out, because being left out is
 *       indistinguishable from being spelled wrong at the call site that then fails to compile.
 * </ol>
 *
 * <p>The three do not interact beyond that. A method carrying its own {@link Receiver} is judged by
 * that alone, so a class-level receiver and a method that names a different one are both honoured.
 *
 * <h2>Constants</h2>
 *
 * <p>A field is contributed only when it carries {@link Receiver}; a field without one is the
 * holder's own state and is not examined. A contributed field must be {@code public static final},
 * reported as {@code AW1314} otherwise, and {@link Receiver#value()} must name the receiver type.
 * {@link Receiver#nulls()} on a field is {@code AW1315}, since a constant is read off the type and
 * there is no receiver value to check.
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
 * @see Receiver
 * @see Scope
 * @see Nulls
 */
@ApiStatus.Experimental
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Extension {

    /**
     * One receiver type for every method of this class that names none of its own.
     *
     * <p>Setting it makes parameter zero the receiver by position: a contributed method must
     * declare exactly that type first, and one that does not is reported as {@code AW1316}. A
     * method carrying its own {@link Receiver}, on the method or on its first parameter, is judged
     * by that instead and is unaffected by this element.
     *
     * <p>The default is a sentinel rather than a type. Left at {@code void.class} the class names
     * no receiver, and every contributed method must then name one itself or be reported as
     * {@code AW1302}.
     *
     * @return the receiver every unmarked method contributes to, or {@code void.class} to name none
     */
    Class<?> value() default void.class;

    /**
     * Whether the receiver must be on the compile classpath when stubs are generated.
     *
     * <p>Applies to every contribution of this class; there is no per-method override. With
     * {@link Require#REQUIRED}, a receiver that is absent fails stub generation, because no stub can
     * be produced for a type that is not there and every call naming the contributed member would
     * fail to compile with an error pointing somewhere else. With {@link Require#OPTIONAL} the
     * contribution is skipped instead and the build continues.
     *
     * <p>A value other than the default is written into the generated manifest by the constant's
     * name; {@link Require#REQUIRED} itself is omitted rather than written out. The value is not
     * consulted at weave time.
     *
     * @return whether an absent receiver is a build failure
     */
    Require require() default Require.REQUIRED;

    /**
     * Who is offered the contributions of this class.
     *
     * <p>{@link Scope#PUBLIC} offers them to every module that reads the manifest;
     * {@link Scope#MODULE} offers them only to the module that declares them. Applies to every
     * contribution of this class. A value other than the default is written into the generated
     * manifest by the constant's name; {@link Scope#PUBLIC} itself is omitted rather than written
     * out.
     *
     * @return the visibility of the contributions
     */
    Scope scope() default Scope.PUBLIC;
}
