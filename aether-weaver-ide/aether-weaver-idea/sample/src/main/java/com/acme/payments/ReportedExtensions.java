package com.acme.payments;

import de.splatgames.aether.weaver.api.experimental.Extension;
import de.splatgames.aether.weaver.api.experimental.Receiver;

import java.math.BigDecimal;
import java.util.List;

/*
 * Every extension in this file is wrong, and the IDE must say so before the build does.
 *
 * ALL OF IT COMPILES. Not one of these is a Java mistake: javac is perfectly happy with a class
 * that is not final, a public method that forgot an annotation, and a receiver in second place.
 *
 * And here the timing matters more than it does in Reported.java. A weave that is wrong is found
 * when the weaver runs, and the weave is the only thing that has to change. An extension is
 * validated ONCE — by the time anything is woven, javac has already compiled every caller against a
 * stub built from whatever was accepted. A declaration that turns out to be wrong is wrong in code
 * that already exists. The editor is not saving anyone a few seconds here; it is the difference
 * between an edit and a migration.
 *
 * Open this file and expect THIRTEEN underlines. Four of them carry a quick fix — Alt+Enter on the
 * first four classes. The rest deliberately do not, and the reason is at the bottom of this comment.
 *
 * Amounts.java is the opposite discipline: a declaration that is correct, and draws nothing.
 */

/**
 * Extension holder that is not {@code final}: {@code AW1300}.
 *
 * <p>A holder is never instantiated and never subclassed, so a non-final one offers a use it does
 * not have. A warning rather than an error, because the extension is still contributed — and the
 * only holder-level report that does not stop the remaining checks, which is why the contribution
 * below is examined as well and found correct.
 *
 * <p>Reported on the class name by the plugin's extension inspection, with the fix that adds the
 * keyword, and by the annotation processor. The sample's build runs no processor, so in this
 * project the editor is the only thing that reports it; the same is true of every report in this
 * file.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
@Extension
class NotFinal {

    /**
     * Correct contribution on a holder that is not.
     *
     * <p>{@code static}, receiver marked on the first parameter, and named after nothing
     * {@link BigDecimal} already declares, so the only report in this class is the one on the class
     * name.
     *
     * @param self the receiver
     * @return the receiver added to itself
     */
    public static BigDecimal doubled(@Receiver final BigDecimal self) {
        return self.add(self);
    }
}

/**
 * Extension holder whose contribution is not {@code static}: {@code AW1301}.
 *
 * <p>Every {@code public} method of a holder is contributed and the receiver is passed as a
 * parameter, so a contributed method has no instance of its own to be called on. Declare it
 * {@code static}, or make it {@code private} where it is a helper — a non-public method is not
 * contributed and is not checked at all.
 *
 * <p>Reported on the method name by the plugin's extension inspection, with the fix that adds the
 * keyword, and by the annotation processor. The check ends there, so nothing else about the method
 * is examined.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
@Extension
final class InstanceContribution {

    /**
     * Instance method of a holder, reported as {@code AW1301}.
     *
     * <p>The declaration is otherwise well formed: the receiver is marked and it is the first
     * parameter. The modifier is the only thing wrong, so the fix that adds it leaves a correct
     * declaration behind.
     *
     * @param self the receiver
     * @return the receiver halved
     */
    public BigDecimal halved(@Receiver final BigDecimal self) {
        return self.divide(BigDecimal.TWO);
    }
}

/**
 * Extension holder whose contribution names no receiver: {@code AW1302}.
 *
 * <p>Nothing here says what the method is contributed to. There is no {@code @Receiver} on the
 * method, none on a parameter, and the class's own {@code @Extension} names no type. Annotate the
 * first parameter, name one receiver for the whole class with {@code @Extension(Type.class)}, or
 * make the method {@code private}.
 *
 * <p>Reported on the method name by the plugin's extension inspection and by the annotation
 * processor. The fix that marks the first parameter is offered here because that parameter's type
 * is a class type; marking a primitive would trade this report for {@code AW1304}.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
@Extension
final class NoReceiver {

    /**
     * Contribution with no receiver named anywhere, reported as {@code AW1302}.
     *
     * <p>The parameter is called {@code self} and is of a type a receiver could be, neither of which
     * counts: the annotation is what names a receiver, and a naming convention is not one.
     *
     * @param self the parameter the fix would mark
     * @return the parameter negated
     */
    public static BigDecimal negated(final BigDecimal self) {
        return self.negate();
    }
}

/**
 * Extension holder whose receiver is not the first parameter: {@code AW1303}.
 *
 * <p>The rewrite passes the receiver straight through as argument zero, which is where the JVM has
 * already put it for the virtual call being replaced. Move the marked parameter to the front, which
 * is what the fix does.
 *
 * <p>Reported on the marked parameter by the plugin's extension inspection and by the annotation
 * processor. Every parameter is searched rather than only the first, which is what lets the report
 * be about where the annotation is instead of about its being absent.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
@Extension
final class ReceiverLate {

    /**
     * Contribution whose {@code @Receiver} is in second place, reported as {@code AW1303}.
     *
     * <p>A call site writes the receiver before the dot whatever the declaration looks like, so the
     * position of the marked parameter is the whole of the fault and moving it is the whole of the
     * remedy.
     *
     * @param by   the multiplier
     * @param self the receiver, in the position that is reported
     * @return the receiver multiplied
     */
    public static BigDecimal scaled(final int by, @Receiver final BigDecimal self) {
        return self.multiply(BigDecimal.valueOf(by));
    }
}

/**
 * Extension holder whose receiver is a primitive: {@code AW1304}.
 *
 * <p>A primitive, an array and a type variable each have no class file for the compiler to resolve
 * a contributed member against. Name a class or an interface, boxing the value where a primitive
 * was meant — {@code Integer} here.
 *
 * <p>Reported on the marked parameter by the plugin's extension inspection and by the annotation
 * processor. No fix is offered.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
@Extension
final class PrimitiveReceiver {

    /**
     * Contribution on {@code int}, reported as {@code AW1304}.
     *
     * <p>The same annotation on the same position as a correct declaration; only the type is wrong,
     * which is what makes this distinct from {@code NoReceiver} above.
     *
     * @param self the primitive receiver
     * @return twice the receiver
     */
    public static int twice(@Receiver final int self) {
        return self * 2;
    }
}

/**
 * Extension holder contributing a member {@link BigDecimal} already declares: {@code AW1305}.
 *
 * <p>javac resolves the call to the member that genuinely exists, so the extension would never be
 * reached; it is refused rather than left as code that compiles and runs nowhere. Rename the
 * extension, or use {@code @Weave} with {@code @Inject} or {@code @Redirect} to change what the
 * existing member does.
 *
 * <p>Reported on the method name by the plugin's extension inspection and by the annotation
 * processor. The lookup walks the receiver's supertypes as well, so a member inherited by
 * {@link BigDecimal} would collide in the same way; here the collision is with a method
 * {@link BigDecimal} declares itself. The same collision found across artefacts at weave time is
 * {@code AW1309}.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
@Extension
final class CollidesWithBigDecimal {

    /**
     * Contribution named {@code abs}, which {@link BigDecimal} already has: {@code AW1305}.
     *
     * <p>The parameters after the receiver are what a collision is compared on, and there are none
     * on either side, so this matches {@link BigDecimal#abs()} exactly. The body does what the
     * existing method does, so nothing about the contribution is wrong except that it is
     * unreachable.
     *
     * @param self the receiver
     * @return the receiver's magnitude
     */
    public static BigDecimal abs(@Receiver final BigDecimal self) {
        return self.signum() < 0 ? self.negate() : self;
    }
}

/**
 * Extension holder that declares type parameters: {@code AW1306}.
 *
 * <p>Contributed methods are looked up by descriptor, and a type parameter on the holder has
 * nothing to bind to at the call site. Remove the type parameters.
 *
 * <p>Reported on the class name by the plugin's extension inspection and by the annotation
 * processor. Nothing else in the class is examined once this is reported, which is why the
 * contribution below — correct in itself — carries no report and is not proof of anything.
 *
 * @param <T> a type parameter nothing binds; its presence is the fault
 * @author Erik Pförtner
 * @since 0.1.0
 */
@Extension
final class GenericHolder<T> {

    /**
     * Correct contribution that is never examined, because the holder's own report stops the checks.
     *
     * @param self the receiver
     * @return the receiver
     */
    public static BigDecimal identity(@Receiver final BigDecimal self) {
        return self;
    }
}

/**
 * Extension holder with a supertype: {@code AW1307}.
 *
 * <p>Nothing about a holder participates at the call site, so a supertype states a relationship the
 * framework cannot honour. Make it extend {@link Object} and implement nothing.
 *
 * <p>Reported on the class name by the plugin's extension inspection and by the annotation
 * processor. The {@code extends} clause is treated leniently by the inspection — a supertype it
 * cannot resolve is not counted, so a file being edited is not reported against on that account
 * alone — while every name in an {@code implements} list counts whether it resolves or not. Here
 * {@link Receipt} resolves, which is why {@link Receipt} is not {@code final} and has an accessible
 * constructor.
 *
 * <p>Nothing else in the class is examined once this is reported.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
@Extension
final class HasASupertype extends Receipt {

    /**
     * Passes an amount to the superclass, since {@link Receipt} declares no other constructor.
     *
     * <p>Not examined for two reasons over: a constructor is skipped whatever its modifiers, a
     * non-public method is skipped as well, and the class-level report has already ended the
     * examination of this holder.
     */
    HasASupertype() {
        super(BigDecimal.ZERO);
    }

    /**
     * Correct contribution that is never examined, because the holder's own report stops the checks.
     *
     * @param self the receiver
     * @return the receiver
     */
    public static BigDecimal identity(@Receiver final BigDecimal self) {
        return self;
    }
}

/**
 * Extension holder whose contribution declares its own type parameters: {@code AW1310}.
 *
 * <p>The stub the compiler resolves against would carry a type variable with nothing to bind it, so
 * inference at the call site would differ from what the declaration says. Use the erased type, or
 * move the method to an ordinary utility class.
 *
 * <p>Reported on the method name by the plugin's extension inspection and by the annotation
 * processor. The holder itself is fine — {@code AW1306} is the same fault one level up, on
 * {@code GenericHolder}.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
@Extension
final class GenericContribution {

    /**
     * Generic contribution, reported as {@code AW1310}.
     *
     * <p>The receiver is not the generic part: it is {@link BigDecimal} and correctly marked. What the
     * report is about is {@code <T>}, which the call site would have to infer against a stub that never
     * carried the argument.
     *
     * @param <T>   the type parameter that cannot be bound
     * @param self  the receiver
     * @param value the value returned unchanged
     * @return the value
     */
    public static <T> T pick(@Receiver final BigDecimal self, final T value) {
        return value;
    }
}

/**
 * Extension holder whose receiver carries type arguments: {@code AW1311}.
 *
 * <p>Erasure is all the call site has, so the member would be contributed to every {@link List} in
 * the program whatever its element type — including lists that have nothing to do with amounts.
 * Name the raw type and check inside the method, or narrow the receiver to a type that is not
 * parameterised, so that the declaration says what will actually happen.
 *
 * <p>Reported on the marked parameter by the plugin's extension inspection and by the annotation
 * processor.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
@Extension
final class ParameterisedReceiver {

    /**
     * Contribution on {@code List<BigDecimal>}, reported as {@code AW1311}.
     *
     * <p>The declaration is correct in every other respect, and the body genuinely needs the element
     * type it names: {@code reduce} is called with {@link BigDecimal#ZERO} and {@code BigDecimal::add}.
     * The report is not that the source is wrong but that the class file cannot carry what it says.
     *
     * @param self the receiver
     * @return the sum of the receiver's elements
     */
    public static BigDecimal total(@Receiver final List<BigDecimal> self) {
        return self.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}

/**
 * Extension holder whose receiver is {@link Object}: {@code AW1312}.
 *
 * <p>The extension is contributed. Every expression in every module that reads the manifest will
 * then offer the member, including expressions whose type has nothing to do with what it means. A
 * warning rather than an error, because contributing to {@link Object} is occasionally what
 * somebody means and neither the framework nor the plugin can tell that from somebody having
 * written the widest type that happened to compile. Name the narrowest type the member is
 * meaningful on.
 *
 * <p>Reported on the receiver by the plugin's extension inspection and by the annotation processor.
 * The one report in the inspection's method checks that is not followed by a return, being the last
 * check there is.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
@Extension
final class ObjectReceiver {

    /**
     * Contribution on every expression in the program, reported as {@code AW1312}.
     *
     * <p>Reached only after the collision check has passed, which it does here because {@link Object}
     * declares no {@code describe}.
     *
     * @param self the receiver, which may be {@code null}
     * @return the receiver rendered as text
     */
    public static String describe(@Receiver final Object self) {
        return String.valueOf(self);
    }
}

/**
 * Extension holder whose contribution names a receiver twice: {@code AW1313}.
 *
 * <p>The two forms mean different things. {@code @Receiver} on the method contributes a
 * {@code static} member to a type, reached as {@code Type.member(...)}; on the first parameter it
 * contributes an instance member to that type's values, reached as {@code value.member(...)}. A
 * declaration asking for both says nowhere which of the two it is. Keep one.
 *
 * <p>Reported on the method's own {@code @Receiver} by the plugin's extension inspection and by the
 * annotation processor, and checked before anything about the receiver's type, so the two
 * annotations naming the same type changes nothing.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
@Extension
final class ReceiverNamedTwice {

    /**
     * Contribution marked on the method and on the parameter, reported as {@code AW1313}.
     *
     * <p>Both forms end up naming {@link BigDecimal} — the method's {@code @Receiver} writes it out,
     * the parameter's takes it from the parameter's own declared type — which is what makes the
     * declaration look harmless. The disagreement is not about the type but about whether the member
     * is contributed to the type or to its values.
     *
     * @param self the receiver, marked a second time
     * @return the receiver added to itself
     */
    @Receiver(BigDecimal.class)
    public static BigDecimal doubled(@Receiver final BigDecimal self) {
        return self.add(self);
    }
}

/**
 * Extension holder whose contribution does not take the class's declared receiver: {@code AW1316}.
 *
 * <p>{@code @Extension(BigDecimal.class)} makes parameter zero the receiver by position, so every
 * contributed method must take exactly that type first; a method taking something else, or taking
 * nothing at all, is refused. Nothing is inferred from the type, so a subtype is reported as well.
 * Take the declared type first, make the method {@code private}, or name the method's own receiver
 * with {@code @Receiver} — a method that marks a parameter is not compared against the class's type
 * here at all.
 *
 * <p>Reported on the method name by the plugin's extension inspection and by the annotation
 * processor. Being refused rather than quietly left out is the point: left out is
 * indistinguishable, at the call site that then fails to compile, from being spelled wrong.
 *
 * <p>{@code Amounts} is the same class-level form used correctly.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
@Extension(BigDecimal.class)
final class ClassLevelMismatch {

    /**
     * Contribution whose first parameter is an {@code int}, reported as {@code AW1316}.
     *
     * <p>{@code AW1302} does not apply, because the class-level {@code @Extension} does name a
     * receiver; what is missing is a parameter of that type in front.
     *
     * @param by the multiplier occupying the receiver's position
     * @return the multiplier as an amount
     */
    public static BigDecimal scaled(final int by) {
        return BigDecimal.valueOf(by);
    }
}
