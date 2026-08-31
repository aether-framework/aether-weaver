package com.acme.payments;

import de.splatgames.aether.weaver.api.At;
import de.splatgames.aether.weaver.api.Inject;
import de.splatgames.aether.weaver.api.Point;
import de.splatgames.aether.weaver.api.Shadow;
import de.splatgames.aether.weaver.api.Unique;
import de.splatgames.aether.weaver.api.Weave;
import de.splatgames.aether.weaver.api.callback.ReturnableCallback;

import java.math.BigDecimal;

/*
 * Every weave in this file is wrong, and the IDE must say so before the build does.
 *
 * ALL OF IT COMPILES. That is the whole reason these inspections exist: none of these mistakes is a
 * Java mistake. javac is perfectly happy with a weave that names no target, a selector that is a
 * misspelled string, and a @Shadow in a weave whose code never moves. Without the plugin the first
 * anyone hears of them is the weaving step — or, for the misspelled selector, never: the injection
 * simply does not happen.
 *
 * Open this file and expect THIRTEEN underlines. If one is missing, an inspection has stopped firing.
 * If a fourteenth appears anywhere else in the project, one has started firing on correct code — which is
 * the more expensive direction, because the remedy a user reaches for is switching the inspection
 * off, and the true reports go with it.
 *
 * Silent.java is the opposite discipline: declarations that are legal, and draw nothing.
 */
/**
 * Weave that names no target at all.
 *
 * <p>{@code @Weave} carries neither a class literal nor a {@code targets} name, which is
 * {@code AW1001}. A weave with no target is inert, and a build that accepted it would report success
 * for code that never runs, so this is an error rather than a warning. Give the annotation
 * {@code Gateway.class}, or {@code targets = "com.acme.payments.Gateway"} where the class is not on
 * the compile classpath.
 *
 * <p>Reported on the annotation by the plugin's weave-declaration inspection and by the annotation
 * processor. The sample's build runs neither the processor nor the weaver, so in this project the
 * editor is the only thing that reports it, and the same is true of every other report in this file.
 * Every other check stays silent on this class for reasons that are not all the same one: the
 * return-type check runs whether or not a selector resolves and is silent here only because the
 * handler is {@code void}; {@code @At(Point.HEAD)} forbids a target outright, so the point itself
 * never asks for one; and the parameter and callback checks do need a selector naming exactly one
 * method, which this weave has none to offer since it names no target for one to resolve against.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
@Weave
final class NamesNoTarget {

    /**
     * Handler that can never run, on a weave that names nothing to inject it into.
     *
     * <p>Carries no report of its own. The selector is only ever compared against the classes the
     * enclosing {@code @Weave} names, and there are none, so nothing here is checked — including the
     * fact that a bare {@code "charge"} would be ambiguous over {@link Gateway}'s three overloads.
     */
    @Inject(method = "charge", at = @At(Point.HEAD))
    void onCharge() {
        System.out.println("this handler can never run");
    }
}

/**
 * Weave that names its target twice, once as a class literal and once as a name.
 *
 * <p>{@code AW1002}. Which of the two is authoritative would be a guess, so neither is used: the
 * weave ends up with no targets at all, and {@code AW1001} is not additionally reported for it.
 * Keep {@code value = Gateway.class} and delete {@code targets}, or the other way round. The class
 * literal is the form to keep wherever the target is on the compile classpath, because the compiler
 * checks it, it follows a rename, and it survives the class moving to another package.
 *
 * <p>Reported on the annotation by the plugin's weave-declaration inspection and by the annotation
 * processor. {@code TargetsByName} shows the {@code targets} form used on its own, which is correct
 * and draws nothing.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
@Weave(value = Gateway.class, targets = "com.acme.payments.Gateway")
final class NamesTheTargetTwice {

    /**
     * Correct handler on a declaration that is not.
     *
     * <p>{@link Gateway#settle()} is not overloaded, so the bare name resolves, and the plugin reads
     * both spellings of the target when it resolves a selector — which is why the report above is the
     * only one in this class. The weave itself ends up with no targets at all, so there is nothing
     * for this handler to be attached to.
     */
    @Inject(method = "settle", at = @At(Point.HEAD))
    void onSettle() {
        System.out.println("settling");
    }
}

/**
 * Static weave declaring the two annotations that mean something only for a merged weave.
 *
 * <p>A {@code @Weave(kind = Kind.STATIC)} stays a class of its own and is never merged into its
 * target, so each of the two fields below asks for something that cannot happen, and each is a
 * report. The handler is {@code static}, which is why there is no third.
 *
 * <p>The one way out for either field is the same: declare the weave
 * {@code @Weave(kind = Kind.INSTANCE)} so that its members are merged into the target and
 * {@code @Shadow} and {@code @Unique} have something to bind to. An {@code @Accessor} or an
 * {@code @Invoker} is not a substitute in a static weave — a weave declared
 * {@code @Weave(kind = Kind.STATIC)} is never merged into its target, and neither annotation
 * emits a member for one either.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
@Weave(value = Ledger.class, kind = Weave.Kind.STATIC)
final class StaticWeaveReachingTooFar {

    /**
     * {@code @Shadow} in a static weave: {@code AW1090}.
     *
     * <p>There is nothing for the declaration to bind to. The field stays an ordinary field of this
     * class, reading and writing the weave rather than {@link Ledger}, which is the failure mode the
     * report exists to prevent — the code compiles, runs and silently touches the wrong object.
     *
     * <p>The name and the erased type are the target's, so the type comparison behind {@code AW1031}
     * would pass; what is wrong here is the annotation, not the field it describes.
     *
     * <p>Reported on the annotation by the plugin's weave-declaration inspection, with a fix replacing
     * it with a generated member, and by the annotation processor.
     */
    @Shadow
    private int entries;

    /**
     * {@code @Unique} in a static weave: {@code AW1091}.
     *
     * <p>{@code @Unique} asks for a member to be renamed on its way into the target, and a static
     * weave's members never go there, so there is no effect to ask for. Delete the annotation, or
     * declare the weave {@code @Weave(kind = Kind.INSTANCE)}.
     *
     * <p>Reported on the annotation by the plugin's weave-declaration inspection and by the annotation
     * processor. No fix is offered: which of the two remedies is meant is a decision about the weave,
     * not about this field.
     */
    @Unique
    private BigDecimal total;

    /**
     * The one member of this weave that is right, and the reason there are two reports here and not
     * three.
     *
     * <p>A static weave's handler is called from the woven target across a class boundary, so it must
     * be {@code static}; a non-static one is {@code AW1005}, which
     * {@code InstanceHandlerInAStaticWeave} carries instead. It is package-private and {@link Ledger}
     * is in the same package, so the injected call can reach it and {@code AW1042} does not apply
     * either.
     */
    @Inject(method = "record", at = @At(Point.HEAD))
    static void onRecord() {
        System.out.println("recording");
    }
}

/**
 * Weave whose selector is a misspelling.
 *
 * <p>{@code charg} is not a method of {@link Gateway}, and nothing about the declaration is a Java
 * mistake: a selector is a string, and a string with a letter missing compiles. The parameter list
 * parses, so the fault is the name rather than the grammar — a text that does not parse at all is
 * {@code AW1015}, and one written as a JVM descriptor without its prefix is {@code AW1017}, which
 * {@code PastedDescriptor} carries.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
@Weave(Gateway.class)
final class MisspelledSelector {

    /**
     * Handler whose selector names no method of the target: {@code AW1020}.
     *
     * <p>Reported on the name inside the string by the plugin's selector inspection, by the
     * annotation processor and by the engine. The fix offers the nearest name on the target by edit
     * distance, and is offered at all only where a name within its budget was found; {@code charg} is
     * one edit from {@code charge}.
     *
     * <p>The return type is still compared unconditionally, {@code void} against {@code void}, and
     * says nothing; the parameter and callback checks are the ones that need a selector resolving to
     * exactly one method, and stay silent because {@code charg} resolves to none.
     */
    @Inject(method = "charg(BigDecimal)", at = @At(Point.HEAD))
    void onCharge() {
        System.out.println("charging");
    }
}

/**
 * Instance weave declaring two fields the target already has, each wrong in a different way.
 *
 * <p>An instance weave's members are merged into its target, which is what turns a name the target
 * already uses into a collision rather than a coincidence. The two fields separate the two reports:
 * the first is an ordinary field that would land on one {@link Ledger} declares, the second is a
 * {@code @Shadow}, which is allowed to name an existing field and names the wrong type for it.
 *
 * <p>Only a member the target declares itself counts for either check. A weave field named like a
 * field of the target's superclass is not a collision, and a {@code @Shadow} of an inherited field
 * is not type-checked.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
@Weave(Ledger.class)
final class ConflictsWithTheTarget {

    /**
     * Ordinary field merged onto a field {@link Ledger} already declares: {@code AW1080}.
     *
     * <p>Overwriting the target's own field is not an option, because it would replace working state
     * with an uninitialised copy. Rename the field, or declare it {@code @Unique} to have it renamed on
     * the way in, or declare it {@code @Shadow} if what was wanted was the target's field rather than
     * one of this weave's own.
     *
     * <p>Reported on the field name by the plugin's weave-member inspection and by the annotation
     * processor. A field is matched by its name alone; a method would be matched by name and erased
     * parameter types.
     */
    private int entries;

    /**
     * {@code @Shadow} of a field {@link Ledger} declares as {@code int}: {@code AW1031}.
     *
     * <p>A shadow is a promise about a member that already exists, so its type has to be the one the
     * target gave it. Types are compared as erased names, which is the comparison the weaver makes, so
     * a disagreeing type argument is not a mismatch and a different erased type is.
     *
     * <p>Reported on the type element by the plugin's weave-member inspection and by the annotation
     * processor. A {@code @Shadow} naming a field the target does not declare at all is a different
     * code, {@code AW1030}, which the build raises and no inspection in the plugin does.
     */
    @Shadow
    private String posted;

    /**
     * Handler reading both fields, and carrying no report of its own.
     *
     * <p>It is here so that neither field is dead in the ordinary sense. The two reports above are
     * about the declarations; a field nothing reads would collect the platform's unused-declaration
     * warning on top of them, and only a {@code @Shadow} field is claimed as implicitly read.
     */
    @Inject(method = "record", at = @At(Point.HEAD))
    void onRecord() {
        System.out.println("recording " + this.entries + this.posted);
    }
}

/**
 * Weave whose bare selector names three methods at once.
 *
 * <p>{@link Gateway} declares three {@code charge} overloads, so {@code "charge"} is
 * {@code AW1021}: the selector is ambiguous and is reported rather than resolved arbitrarily. Write
 * a signature that picks one out, as {@code "charge(BigDecimal)"}, or the descriptor form.
 *
 * <p>Reported on the name inside the string by the plugin's selector inspection and by the
 * annotation processor. The return-type check still runs and is silent because the handler
 * returns {@code void}; the parameter and callback checks are the ones that stay silent because
 * they need a selector naming exactly one method, and guessing which overload was meant is how a
 * plugin ends up underlining correct code.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
@Weave(Gateway.class)
final class NamesEveryOverload {

    /**
     * Handler whose selector matches every {@code charge} overload: {@code AW1021}.
     *
     * <p>The point is {@link Point#RETURN} rather than {@link Point#HEAD}, which the selector check
     * never reads: it examines the {@code method} attribute alone, so the ambiguity is the same at
     * every point.
     */
    @Inject(method = "charge", at = @At(Point.RETURN))
    void onCharge() {
        System.out.println("charged");
    }
}

/**
 * Static weave whose handler is not {@code static}.
 *
 * <p>{@code AW1005}. A static weave is never merged into its target, so the injected call is made
 * from {@link Router} to this class, and an instance method has no receiver for that call to use.
 * Emitting it anyway would mean an {@code invokestatic} to an instance method, which fails at link
 * time long after the declaration that caused it.
 *
 * <p>Declare the handler {@code static}, which the plugin offers as a fix, or declare the weave
 * {@code @Weave(kind = Kind.INSTANCE)} so that it is dissolved into its target and the handler
 * becomes one of that class's own methods. State the handler needs beyond its parameters belongs in
 * a static field of the weave.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
@Weave(value = Router.class, kind = Weave.Kind.STATIC)
final class InstanceHandlerInAStaticWeave {

    /**
     * Non-static handler of a static weave: {@code AW1005}.
     *
     * <p>Reported on the handler's name by the plugin's weave-declaration inspection, with a fix adding
     * the modifier, and by the annotation processor. The report is raised only because the method
     * carries {@code @Inject}; an ordinary instance method of a static weave is that weave's own
     * business and is left alone.
     */
    @Inject(method = "route", at = @At(Point.HEAD))
    void onRoute() {
        System.out.println("routing");
    }
}

/**
 * Weave whose selector is a JVM descriptor written without the prefix that says so.
 *
 * <p>{@code AW1017}. The text is a valid method descriptor and not a valid source-form selector, so
 * the parser refuses it and only then — never as a first guess — recognises the shape and offers the
 * same text with {@code desc:} in front of it. The plugin turns that suggestion into a fix.
 *
 * <p>Recognising the shape first would be worse than not recognising it at all, because a text
 * legal in both readings would be silently taken as the other one. Only a text that cannot be a
 * source selector is offered the suggestion.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
@Weave(Gateway.class)
final class PastedDescriptor {

    /**
     * Handler whose selector is a descriptor missing its {@code desc:} prefix: {@code AW1017},
     * carrying the corrected spelling.
     *
     * <p>Reported on the whole string, since the failure is the text and not a position in it, by the
     * plugin's selector inspection and by the annotation processor. The descriptor names the
     * {@code charge} overload that takes a {@link java.math.BigDecimal} and returns a {@link String},
     * which is a method {@link Gateway} really has; what is wrong is the spelling.
     * {@code DescriptorWeave} writes the same form correctly, as {@code desc:settle()V}.
     */
    @Inject(method = "charge(Ljava/math/BigDecimal;)Ljava/lang/String;", at = @At(Point.HEAD))
    void onCharge() {
        System.out.println("charging");
    }
}

/**
 * Weave whose three handlers each disagree with the target in a different way.
 *
 * <p>Every selector here resolves, which is what makes two of the three reports possible: the
 * parameter and callback checks need a selector naming exactly one method, and each of the other
 * wrong selectors in this file leaves them silent. The third, the return-type check, needs no
 * resolved selector at all and would fire on a misspelled one just as it does here. Two of the
 * three name
 * {@link Gateway#charge(java.math.BigDecimal)}, where {@code AuditWeave} and {@code PriorityWeave}
 * also inject, so that one position carries four handlers from three weaves and the target-side
 * gutter has an order to show.
 *
 * <p>Each report is anchored on the part of the signature that is wrong — the return type, the
 * first parameter that disagrees, the callback — rather than on the method as a whole.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
@Weave(Gateway.class)
final class HandlersThatCannotBeCalled {

    /**
     * Handler whose parameter is not the target's: {@code AW1040}.
     *
     * <p>A handler's parameters are a prefix of the target's, in declaration order, so the first has to
     * be the {@link java.math.BigDecimal} that {@code charge} takes and not a {@link String}. Taking
     * none at all would be legal, the empty prefix being a prefix, so the fault is the type rather than
     * the count.
     *
     * <p>Reported on the parameter, with a fix that rewrites the whole list, by the plugin's
     * handler-signature inspection, and by the annotation processor.
     *
     * @param amount the parameter that disagrees with the target's first
     */
    @Inject(method = "charge(BigDecimal)", at = @At(Point.HEAD))
    void takesTheWrongType(final String amount) {
        System.out.println("charging " + amount);
    }

    /**
     * Handler that returns a value: {@code AW1041}.
     *
     * <p>An injected call is a statement in the middle of the target's own code, so a returned value
     * would have nowhere to go. To change what the target returns, take a {@link ReturnableCallback}
     * and cancel with a value.
     *
     * <p>The only check in the set that needs no target: it is reported on the return type element even
     * while the selector is still half-typed. Here the selector does resolve — {@link Gateway#settle()}
     * is not overloaded — and the target returns {@code void}, which changes nothing.
     *
     * @return a value the woven call has nowhere to put
     */
    @Inject(method = "settle", at = @At(Point.HEAD))
    int returnsSomething() {
        return 1;
    }

    /**
     * Handler whose callback names the wrong value type: {@code AW1071}.
     *
     * <p>{@code Gateway.charge(BigDecimal)} returns a {@link String}, so the callback able to cancel it
     * is a {@code ReturnableCallback<String>}; the type argument written here is the target's parameter
     * type instead. The message quotes the argument that would be right. Reported on the callback
     * parameter by the plugin's handler-signature inspection and by the annotation processor.
     *
     * <p>The parameter list is otherwise correct. A handler may take the callback alone, because the
     * target's own arguments in front of it are a prefix and an empty prefix is one, so {@code AW1040}
     * does not apply.
     *
     * <p>The {@code cancel} call in the body is not a second report either. {@code AW1070} is about a
     * no-argument {@code cancel()} on a target that returns something, and this one passes a value — a
     * value of the wrong type, which is the Java error the compiler would raise here if the callback's
     * type argument were the right one.
     *
     * @param callback the callback whose type argument disagrees with the target's return type
     */
    @Inject(method = "charge(BigDecimal)", at = @At(Point.HEAD))
    void promisesTheWrongCallback(final ReturnableCallback<BigDecimal> callback) {
        callback.cancel(BigDecimal.ZERO);
    }
}
