package de.splatgames.aether.weaver.api.callback;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Carries a reference local variable of a target method into a handler and the handler's writes back out.
 *
 * <p>A Java parameter is passed by value, so a handler that assigns to an ordinary parameter changes its own
 * copy and leaves the target holding the reference it had before. A parameter declared as this type is handed a
 * carrier instead: the engine constructs one around the variable's current value, passes it to the handler, and
 * reads {@link #get()} back into the target's own slot as soon as the handler returns.
 *
 * <p>This is the carrier for every reference type. The eight primitives have one each — {@link LocalIntRef},
 * {@link LocalLongRef}, {@link LocalFloatRef}, {@link LocalDoubleRef}, {@link LocalBooleanRef},
 * {@link LocalByteRef}, {@link LocalShortRef} and {@link LocalCharRef} — and a primitive variable is matched by
 * its own carrier rather than by this one.
 *
 * <p>Only a parameter annotated {@link de.splatgames.aether.weaver.api.Local} with {@code mutable = true} may be
 * declared as a carrier. A carrier without that flag is reported as {@code AW1054} — a carrier the handler may
 * not write to is a handle nobody should be holding — and {@code mutable = true} on a parameter that is not a
 * carrier is reported as {@code AW1053}, because assigning to such a parameter could only be a no-op. Both codes
 * are reported by the annotation processor at compile time and again by the engine.
 *
 * <h2>Which variable it matches, and what the type argument is worth</h2>
 *
 * <p>A carrier is matched by what it holds rather than by its own type, and this one erases to {@code Object}.
 * That has one consequence worth planning around: the two strategies that draw their candidates from the
 * variables whose declared type equals the parameter's — {@link de.splatgames.aether.weaver.api.Local#ordinal()}
 * and resolution by type alone — look for a variable declared exactly {@code Object}, and report {@code AW1050}
 * when the target has none live at the matched position. A reference variable of any other type is captured by
 * {@link de.splatgames.aether.weaver.api.Local#name()} or by
 * {@link de.splatgames.aether.weaver.api.Local#index()}, both of which accept any reference for a reference
 * parameter.
 *
 * <p>The type argument is not checked against the variable. It is erased before the class file is written, the
 * engine constructs the carrier through the erased {@code (Object)} constructor, and the match is made on the
 * variable's own type, so a {@code LocalRef<String>} declared for a variable of another type compiles and weaves
 * and fails at run time — as a {@link ClassCastException} from the cast the compiler inserted after
 * {@link #get()}, or at the write-back described below.
 *
 * <h2>What is written back, and when</h2>
 *
 * <p>The write-back is emitted immediately after the handler's call returns, and it reads the carrier once. A
 * handler that stores the carrier somewhere and calls {@link #set(Object)} later changes nothing in the target:
 * the target's slot has already been written and the carrier is no longer consulted.
 *
 * <p>Because the carrier erases, the value read back out is cast to the variable's own type before it is stored
 * — when that type is known. It is known whenever the target carries a usable {@code LocalVariableTable} entry
 * for the captured slot, which is every capture strategy except {@link de.splatgames.aether.weaver.api.Local}
 * captured {@code by index} on a target with no such entry; there, nothing narrower than {@code Object} is
 * available, and the emitted cast is {@code checkcast Object}, which never fails. Where the type is known, a
 * handler that stores a value the variable's type does not accept fails with a {@link ClassCastException} at the
 * write-back, naming that type, rather than somewhere later in the target's own code. A {@code null} passes the
 * cast and is stored, so a handler may clear a variable.
 *
 * <p>On the {@link de.splatgames.aether.weaver.api.Inject} path the write-back precedes the check of the
 * handler's callback, so a handler that both writes the variable and cancels through a {@link Callback} gets
 * both — the value is in the slot even though the target returns without reaching the code that would read it.
 *
 * <p>A {@link de.splatgames.aether.weaver.api.Redirect} handler is handed its captures and no write-back is
 * emitted for them, so what it stores into a carrier is discarded. Writing a local from a handler is what the
 * {@link de.splatgames.aether.weaver.api.Inject} path does.
 *
 * <h2>Thread safety</h2>
 *
 * <p>A carrier is constructed at each execution of the injected call and reaches only the handler that call
 * invokes, so an ordinary handler never shares one. The class itself synchronizes nothing and its field is not
 * volatile, so a carrier a handler publishes to another thread carries no visibility guarantee.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @Weave(Ledger.class)
 * public final class LedgerAudit {
 *
 *     @Inject(method = "charge(java.math.BigDecimal)",
 *             at = @At(value = Point.INVOKE, target = "#post"),
 *             require = 1)
 *     private void beforePost(@Local(name = "note", mutable = true) LocalRef<String> note) {
 *         if (note.get() == null) {
 *             note.set("unattributed");   // charge() goes on with the replacement
 *         }
 *     }
 * }
 * }</pre>
 *
 * @param <T> the type of the captured variable, erased before the weave and not checked against it
 * @author Erik Pförtner
 * @since 0.1.0
 * @see de.splatgames.aether.weaver.api.Local
 * @see LocalIntRef
 */
public final class LocalRef<T> {

    /** The value handed to the handler, and the value read back into the target's slot. */
    private @Nullable T value;

    /**
     * Creates a carrier holding the given value.
     *
     * <p>The engine emits this constructor at the injected call with the target variable's current value, so a
     * carrier a handler receives always starts at what the target holds rather than at {@code null}.
     *
     * @param value the value to carry, which may be {@code null}
     */
    public LocalRef(@Nullable final T value) {
        this.value = value;
    }

    /**
     * Returns the value currently carried.
     *
     * <p>Before the handler writes anything this is the value the target variable held at the injected call.
     * This method is also what the emitted write-back calls to obtain the value it stores into the target's
     * slot; because the carrier erases, it returns {@code Object} in the class file and the caller's own cast
     * decides what the handler sees.
     *
     * @return the value currently carried, or {@code null} when the variable holds none
     */
    @Contract(pure = true)
    public @Nullable T get() {
        return this.value;
    }

    /**
     * Replaces the value carried.
     *
     * <p>The target's own variable is not changed here; it is changed by the write-back, which reads the carrier
     * once after the handler returns. The last value stored before that point is the one the target goes on
     * with, and a value stored afterwards is never read. Nothing checks the value against the variable's type
     * here — that cast happens at the write-back.
     *
     * @param value the value the target variable is to hold, which may be {@code null}
     */
    public void set(@Nullable final T value) {
        this.value = value;
    }

    /**
     * Returns a description of this carrier and the value it holds, in the form {@code LocalRef[note]}.
     *
     * <p>The value is rendered with {@link String#valueOf(Object)}, so a carrier holding {@code null} renders as
     * {@code LocalRef[null]} and any other value is rendered by its own {@code toString}.
     *
     * @return a description of this carrier and the value it holds
     */
    @Override
    @NotNull
    public String toString() {
        return "LocalRef[" + this.value + ']';
    }
}
