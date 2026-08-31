package de.splatgames.aether.weaver.api.experimental;

import org.jetbrains.annotations.ApiStatus;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Names the type that gains the member an {@link Extension} class contributes.
 *
 * <p>Where this annotation sits decides what shape of member is contributed, and the three
 * positions mean three different things rather than being spellings of one.
 *
 * <table border="1">
 *   <caption>Position, shape and where the receiver type comes from</caption>
 *   <tr><th>Position</th><th>Contributes</th><th>Receiver type</th><th>Call site</th></tr>
 *   <tr><td>the first parameter</td><td>an instance method</td><td>the parameter's own type;
 *       {@link #value()} is not read</td><td>{@code value.name(...)}</td></tr>
 *   <tr><td>the method</td><td>a {@code static} method</td><td>{@link #value()}, which must be
 *       set</td><td>{@code Type.name(...)}</td></tr>
 *   <tr><td>a field</td><td>a constant</td><td>{@link #value()}, which must be set</td>
 *       <td>{@code Type.NAME}</td></tr>
 * </table>
 *
 * <p>An {@link Extension} class may instead name one receiver for all of its methods with
 * {@link Extension#value()}. A method carrying this annotation is judged by it alone and ignores
 * the class-level receiver.
 *
 * <h2>Stability</h2>
 *
 * <p>Marked {@link ApiStatus.Experimental}, as is every other type in this package. That annotation
 * is the whole of the promise the source makes: no compatibility guarantee is stated for this
 * declaration, and nothing here names a release in which its shape is fixed. A {@link #nulls()} of
 * anything but {@link Nulls#UNCHECKED} is written into the generated manifest by the name of the
 * constant; {@link Nulls#UNCHECKED} itself is omitted rather than written out. A reader that does
 * not know a constant it does find there reports {@code AW2300} and treats the entry as
 * {@link Nulls#UNCHECKED} rather than dropping it.
 *
 * <h2>What is refused</h2>
 *
 * <ul>
 *   <li><b>On a parameter other than the first</b>: {@code AW1303}. The rewrite passes the receiver
 *       straight through as argument zero, which is where the JVM has already put it for the
 *       virtual call being replaced.
 *   <li><b>Both on the method and on a parameter</b>: {@code AW1313}. The two forms mean different
 *       things, so a declaration asking for both says nowhere which of them it is.
 *   <li><b>A type that cannot carry a member</b>: {@code AW1304} for a primitive, an array, a type
 *       variable, or a {@link #value()} left at its default of {@code void.class} on a method or a
 *       field.
 *   <li><b>A parameterised type</b>: {@code AW1311}. Erasure is all the call site has, so the
 *       member would reach every instance of the raw type whatever its type argument.
 *   <li><b>{@link Object}</b>: {@code AW1312}, a warning. The member is contributed, and every
 *       expression in every module that reads the manifest then offers it.
 *   <li><b>On a field that is not {@code public static final}</b>: {@code AW1314}. A contributed
 *       constant is read off the receiver as one of its own, and a non-final field would be shared
 *       writable state on a type whose author never gave it one.
 *   <li><b>{@link #nulls()} where there is no receiver value</b>: {@code AW1315}, for this
 *       annotation on a method or on a field.
 * </ul>
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @Extension
 * public final class Amounts {
 *
 *     @Receiver(BigDecimal.class)                       // BigDecimal.CENT
 *     public static final BigDecimal CENT = new BigDecimal("0.01");
 *
 *     @Receiver(BigDecimal.class)                       // BigDecimal.parse("1,234.50")
 *     public static BigDecimal parse(String text) {
 *         return new BigDecimal(text.replace(",", ""));
 *     }
 *
 *     // amount.asMoney("EUR"), with the receiver checked before the body runs
 *     public static String asMoney(@Receiver(nulls = Nulls.CHECKED) BigDecimal self, String symbol) {
 *         return symbol + self.setScale(2, RoundingMode.HALF_UP);
 *     }
 *
 *     // amount.orZero(), which is written to accept a null receiver
 *     public static BigDecimal orZero(@Receiver(nulls = Nulls.NULLABLE) BigDecimal self) {
 *         return self == null ? BigDecimal.ZERO : self;
 *     }
 * }
 * }</pre>
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see Extension
 * @see Nulls
 */
@ApiStatus.Experimental
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.METHOD, ElementType.FIELD})
public @interface Receiver {

    /**
     * The type that gains the member.
     *
     * <p>Read only when this annotation is on a method or on a field. On a parameter the receiver
     * type is the parameter's own type and this element is never consulted, so setting it there
     * has no effect and is not reported.
     *
     * <p>The default is a sentinel rather than a type. On a method or a field, leaving it at
     * {@code void.class} is reported as {@code AW1304}, because {@code void} is not a type that can
     * have a member.
     *
     * @return the receiver type, or {@code void.class} to name none
     */
    Class<?> value() default void.class;

    /**
     * What the contributed member promises about a {@code null} receiver.
     *
     * <p>Meaningful only on a parameter, which is the one position that carries a receiver value.
     * On a method or on a field it is reported as {@code AW1315} and the whole contribution is
     * refused.
     *
     * <p>{@link Nulls#CHECKED} is the only value that changes the emitted code: the holder's method
     * gains a prologue rejecting a {@code null} receiver. The other two describe intent and emit
     * nothing.
     *
     * @return the null policy for the receiver
     */
    Nulls nulls() default Nulls.UNCHECKED;
}
