package de.splatgames.aether.weaver.api.callback;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * The implementation of {@link ReturnableCallback} that injected code constructs.
 *
 * <p>Not part of the supported surface. It is {@code public} because the code that instantiates it
 * is emitted into the target class, which is in another package, so the {@code new} and the
 * constructor call reach this class from outside. A handler declares {@link Callback} or
 * {@link ReturnableCallback} as its parameter type and never names this one; nothing promises that
 * the class, its constructors or its {@link #toString()} form keep their shape.
 *
 * <p>One instance serves one handler call. The injected code creates it, hands it over, and reads
 * {@link #isCancelled()} unconditionally once the call returns; {@link #value()} is read only then,
 * on the cancelled path, and not at all where the target's return type is {@code void}. The
 * injected code holds its own reference, in the local slot it allocated for the callback, until
 * the target method returns; a handler that keeps a reference beyond that point keeps the object
 * alive after it, though it stays inert. The fields are plain and unsynchronised, which is sound
 * because the instance is neither shared between injected positions nor published by the engine.
 *
 * <p>The two constructors are the two shapes the emission uses. At a position where the target has
 * computed no return value the one-argument form is called and {@link #value()} answers
 * {@code null}; at a return instruction of a value-returning method the two-argument form is
 * called with the value the target was about to return, boxed, so that a handler reading
 * {@link #value()} there sees the real one rather than a default it could not tell apart from it.
 * The emitted call site uses the erased descriptor, passing {@code Object}.
 *
 * @param <T> the target method's return type, boxed
 * @author Erik Pförtner
 * @since 0.1.0
 */
@ApiStatus.Internal
public final class CallbackSupport<T> implements ReturnableCallback<T> {

    /** The identifier of the injection declaration this callback was created for. */
    private final String id;

    /** Whether a handler has asked for the target method to end here. */
    private boolean cancelled;

    /** The value the target returns when cancelled; {@code null} until one is supplied. */
    private @Nullable T value;

    /**
     * Creates a callback carrying no value.
     *
     * <p>Used at every position other than a return of a value-returning method. A handler that
     * cancels through {@link #cancel()} rather than {@link #cancel(Object)} on an instance created
     * this way leaves {@link #value()} at {@code null}, which the injected code returns as it
     * stands for a reference return type and fails to unbox for a primitive one.
     *
     * @param id the identifier of the injection declaration; must not be {@code null}
     * @throws NullPointerException if {@code id} is {@code null}
     */
    public CallbackSupport(@NotNull final String id) {
        this.id = Objects.requireNonNull(id, "id");
    }

    /**
     * Creates a callback carrying the value the target is about to return.
     *
     * <p>Used where the injection matched a return instruction of a method that returns something.
     * The value is not a cancellation: {@link #isCancelled()} is still {@code false}, and the
     * target keeps its own value unless a handler cancels.
     *
     * @param id    the identifier of the injection declaration; must not be {@code null}
     * @param value the value the target computed, boxed, or {@code null}
     * @throws NullPointerException if {@code id} is {@code null}
     */
    public CallbackSupport(@NotNull final String id, @Nullable final T value) {
        this.id = Objects.requireNonNull(id, "id");
        this.value = value;
    }

    /**
     * Returns the identifier of the injection declaration this callback was created for.
     *
     * @return the identifier passed to the constructor, never {@code null}
     */
    @Contract(pure = true)
    @Override
    @NotNull
    public String id() {
        return this.id;
    }

    /**
     * Marks the target method as finished without changing the value on record.
     *
     * <p>Whatever {@link #value()} already holds is what the target returns — the value it
     * computed itself where this callback was created with one, and {@code null} otherwise. A
     * later {@link #cancel(Object)} still replaces it; this method never clears it.
     */
    @Override
    public void cancel() {
        this.cancelled = true;
    }

    /**
     * Marks the target method as finished and records the value it returns instead.
     *
     * <p>The value replaces whatever was on record, including the one the target computed. Its
     * type is not checked here: the injected code casts it to the target's own return type at the
     * cancelled return, so a value of the wrong type fails there with a
     * {@link ClassCastException}.
     *
     * @param value the value the target returns, or {@code null}
     */
    @Override
    public void cancel(@Nullable final T value) {
        this.cancelled = true;
        this.value = value;
    }

    /**
     * Reports whether a handler has cancelled.
     *
     * @return {@code true} once either {@code cancel} method has been called
     */
    @Contract(pure = true)
    @Override
    public boolean isCancelled() {
        return this.cancelled;
    }

    /**
     * Returns the value on record.
     *
     * @return the value the target computed where this callback was created with one and no
     *         handler has replaced it, the value the last {@link #cancel(Object)} supplied, or
     *         {@code null}
     */
    @Contract(pure = true)
    @Override
    public @Nullable T value() {
        return this.value;
    }

    /**
     * Returns a description naming the injection and, when cancelled, the value on record.
     *
     * <p>{@code Callback[audit-charge]} while nothing has cancelled, and
     * {@code Callback[audit-charge, cancelled=Receipt[rejected]]} once something has. The value
     * appears in the cancelled form only, because it is only then that it decides anything.
     *
     * @return the description, never {@code null}
     */
    @Override
    @NotNull
    public String toString() {
        return "Callback[" + this.id + (this.cancelled ? ", cancelled=" + this.value : "") + ']';
    }
}
