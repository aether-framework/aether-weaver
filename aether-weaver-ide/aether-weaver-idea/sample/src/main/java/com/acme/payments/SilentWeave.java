package com.acme.payments;

import de.splatgames.aether.weaver.api.At;
import de.splatgames.aether.weaver.api.Inject;
import de.splatgames.aether.weaver.api.Point;
import de.splatgames.aether.weaver.api.Weave;

/**
 * Weave on {@link Silent} whose four declarations differ in what the plugin has to read to place
 * them.
 *
 * <p>Every one of them is legal, and no inspection in the plugin reports anything here. What
 * separates them is the injection preview. The plugin reads the compiled class rather than the
 * target's source whenever the declaration pins an {@link At#ordinal()}, names an
 * {@link At#shift()} or carries a slice — all three count instructions, and instructions do not
 * correspond to source constructs — and, independently of that, whenever there is no source body
 * to read at all, which this file never triggers since the target is part of the same compile. It
 * draws nothing when the class file it reads does not exist yet. Of the four below,
 * {@link #onTail()} is resolved from source and finds nothing, {@link #onConstant()} is resolved
 * from source and finds the literal, and the last two send the plugin to the class file.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
@Weave(Silent.class)
public final class SilentWeave {

    /**
     * Runs at the last point {@link Silent#pick(boolean)} returns from.
     *
     * <p>{@link Silent#pick(boolean)} has two {@code return} statements, which is neither of the two
     * bodies a tail is resolved on in source, so the preview draws nothing for this declaration —
     * on a compiled project as much as on an unopened one, since nothing here sends the plugin to
     * the class file. The declaration is correct and the engine weaves it at the last return in
     * body order. {@code RouterWeave} carries the same point on a body that does resolve.
     */
    @Inject(method = "pick", at = @At(Point.TAIL))
    void onTail() {
        System.out.println("never drawn");
    }

    /**
     * Runs immediately before {@link Silent#answer()} loads its literal.
     *
     * <p>The target is written in the constant grammar's own spelling, {@code int:42}. The plugin
     * renders each literal of the target with the API's {@code ConstantSelector} and compares the
     * rendering with what is written here, so a second spelling of the same value would compare
     * unequal and match nothing.
     *
     * <p>{@link Point#CONSTANT} takes an optional {@link At#target()}: an absent one is not
     * reported as {@code AW1043} the way it is for a call or a field point.
     */
    @Inject(method = "answer", at = @At(value = Point.CONSTANT, target = "int:42"))
    void onConstant() {
        System.out.println("drawn once compiled");
    }

    /**
     * Runs before the first of the two calls in {@link Silent#twice()}.
     *
     * <p>The ordinal is what distinguishes the two, and it is also what routes the plugin to the
     * compiled class: an ordinal counts matching instructions, and the default of {@code -1} is the
     * one value that pins nothing. Until the sample has been compiled there is no class file to
     * count in and the preview draws nothing.
     */
    @Inject(method = "twice", at = @At(value = Point.INVOKE, target = "help", ordinal = 0))
    void onFirstCallOnly() {
        System.out.println("drawn once compiled");
    }

    /**
     * Runs after each of the two calls in {@link Silent#twice()}, reached by shifting rather than by
     * naming {@link Point#INVOKE_AFTER}.
     *
     * <p>A shift moves the matched position by instructions, so this declaration is resolved against
     * the compiled class for the same reason the ordinal above is, and draws nothing until the
     * sample has been compiled. No ordinal is written, so both calls are matched.
     */
    @Inject(method = "twice", at = @At(value = Point.INVOKE, target = "help",
            shift = At.Shift.AFTER))
    void onShifted() {
        System.out.println("drawn once compiled");
    }
}
