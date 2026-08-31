package com.acme.payments;

import de.splatgames.aether.weaver.api.At;
import de.splatgames.aether.weaver.api.Inject;
import de.splatgames.aether.weaver.api.Point;
import de.splatgames.aether.weaver.api.Weave;

/**
 * Weave on {@link Ledger} carrying one handler per non-call point, all of them inside
 * {@link Ledger#record(java.math.BigDecimal)}.
 *
 * <p>Together they name three kinds of position that are not method calls: a field write, a field
 * access of either direction, an allocation and a throw. {@link Point} declares others that are not
 * calls either — {@link Point#HEAD}, {@link Point#RETURN}, {@link Point#TAIL} and
 * {@link Point#CONSTANT} — and none of them appears here. The two field declarations differ in
 * {@link At#access()} and in {@link At#target()}: one names the instance field {@code entries} and
 * the other the static field {@code posted}, so what {@link At#access()} changes is visible on one
 * target method but not on one target field.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
@Weave(Ledger.class)
public final class LedgerWeave {

    /**
     * Runs before the write to {@code entries}.
     *
     * <p>{@link At.Access#PUT} names a write of an instance field, so the read that
     * {@code entries + 1} performs on the same line is not matched.
     */
    @Inject(method = "record", at = @At(value = Point.FIELD, target = "entries",
            access = At.Access.PUT))
    void onEntriesWritten() {
        System.out.println("entries is about to change");
    }

    /**
     * Runs before every access to {@code posted}, which {@code posted + 1} makes two positions: the
     * read and the write.
     *
     * <p>{@link At#access()} is left at {@link At.Access#ANY}, the only setting that reaches this
     * field short of the static forms — {@code posted} is {@code static}, and
     * {@link At.Access#GET} and {@link At.Access#PUT} name the instance forms alone.
     */
    @Inject(method = "record", at = @At(value = Point.FIELD, target = "posted"))
    void onPostedTouched() {
        System.out.println("posted is read or written here");
    }

    /**
     * Runs before the allocation of the {@link Receipt}, not before its constructor call.
     *
     * <p>The target of a {@link Point#NEW} is compared as text against the created type's binary
     * name, so the simple name {@code Receipt} would name the same class as the qualified form
     * written here.
     */
    @Inject(method = "record", at = @At(value = Point.NEW, target = "com.acme.payments.Receipt"))
    void onReceiptCreated() {
        System.out.println("a receipt is being made");
    }

    /**
     * Runs before the exception leaves {@link Ledger#record(java.math.BigDecimal)}.
     *
     * <p>{@link Point#THROW} matches every throw in the search window and consults no target, so
     * this declaration is written without one; the method holds exactly one.
     */
    @Inject(method = "record", at = @At(Point.THROW))
    void onThrow() {
        System.out.println("something is about to be thrown");
    }
}
