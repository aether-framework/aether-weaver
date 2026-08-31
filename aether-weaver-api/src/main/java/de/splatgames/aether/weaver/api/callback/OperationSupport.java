package de.splatgames.aether.weaver.api.callback;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Objects;

/**
 * The {@link Operation} implementation woven code is handed, and the bootstrap that builds it.
 *
 * <p>Not part of the supported surface. It is {@code public} because a woven class reaches it by name: the
 * engine writes the operation into the call site as a dynamic constant whose bootstrap is
 * {@link #operation(MethodHandles.Lookup, String, Class, String, MethodHandle...)}, and that call has to resolve
 * from whatever package and class loader the target lives in. A handler receives instances of this class through
 * the {@link Operation} interface and has no reason to name it.
 *
 * <p>Building the operation in a bootstrap is what makes the handle free at run time. The JVM resolves a dynamic
 * constant once per constant pool entry and caches it, so every execution of the wrapped site loads the same
 * instance and the nesting of several wraps is assembled once rather than per call.
 *
 * <h2>How a nest is assembled</h2>
 *
 * <p>The static arguments are the description first, then the operation's own method handle, then one handle
 * per handler that nests around it, innermost first. Each handler's trailing {@link Operation} parameter is
 * bound, with {@link MethodHandles#insertArguments(MethodHandle, int, Object...)}, to everything resolved so
 * far; the result is the operation handed to the next handler out. The outermost handler is not among them,
 * because its call is what the engine emitted at the site — the constant is the argument that call passes.
 *
 * <h2>Shape</h2>
 *
 * <p>Every handle is adapted to {@link MethodType#generic()} before it is stored, so parameters and result are
 * {@code Object} and the whole chain is callable through one {@code (Object[])Object} spreader. That adaptation
 * is also what gives an operation producing nothing the {@code null} that {@code Operation<Void>} promises, and
 * what converts each argument on the way in. The instance holds only final state, so it is safely published to
 * every thread that reaches it; whether invoking the wrapped handle itself is safe from several threads at once
 * is a property of that handle, not of this class.
 *
 * @param <T> the operation's result type, which is erased here; {@link #call(Object...)} casts to it without
 *            checking, and the cast that can fail is the one the handler's own code performs
 * @author Erik Pförtner
 * @since 0.1.0
 * @see Operation
 * @see de.splatgames.aether.weaver.api.Wrap
 */
@ApiStatus.Internal
public final class OperationSupport<T> implements Operation<T> {

    /** Stands in for a {@code null} argument array, so that the arity check has something to measure. */
    private static final Object[] NO_ARGUMENTS = new Object[0];

    /** The whole chain, adapted to {@code (Object[])Object} so that one array performs it. */
    private final MethodHandle spread;

    /** The number of arguments {@link #call(Object...)} requires, fixed when the handle was adapted. */
    private final int arity;

    /** The operation as it reads in a message: the call, field access or instantiation that was wrapped. */
    private final String describe;

    /**
     * Creates an operation over an already adapted handle.
     *
     * @param spread   the chain as a spreader over {@code Object[]}; must not be {@code null}
     * @param arity    the number of arguments the spreader takes
     * @param describe the operation as it reads in a message; must not be {@code null}
     */
    private OperationSupport(@NotNull final MethodHandle spread,
                             final int arity,
                             @NotNull final String describe) {
        this.spread = spread;
        this.arity = arity;
        this.describe = describe;
    }

    /**
     * Builds the operation a wrapped call site hands its outermost handler.
     *
     * <p>This is the bootstrap of a dynamic constant. The first three parameters are the ones every constant
     * bootstrap receives; they are required to be present and are not otherwise read, and the engine names the
     * constant {@code operation} and types it as {@link Operation}.
     *
     * @param lookup   the lookup of the woven class; must not be {@code null}
     * @param name     the constant's name; must not be {@code null}
     * @param type     the constant's type; must not be {@code null}
     * @param describe the operation as it reads in a message, which becomes part of the arity failure and of
     *                 {@link #toString()}; must not be {@code null}
     * @param chain    the operation's own handle first, then one handler per level of nesting, innermost first,
     *                 each of whose last parameter is its {@link Operation}; must not be {@code null} and must
     *                 hold at least the operation
     * @return the operation to pass to the outermost handler
     * @throws NullPointerException if any argument, or any element of {@code chain}, is {@code null}
     * @throws IllegalArgumentException if {@code chain} is empty
     */
    @Contract(pure = true)
    @NotNull
    public static Operation<?> operation(@NotNull final MethodHandles.Lookup lookup,
                                         @NotNull final String name,
                                         @NotNull final Class<?> type,
                                         @NotNull final String describe,
                                         final MethodHandle @NotNull ... chain) {
        Objects.requireNonNull(lookup, "lookup");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(describe, "describe");
        Objects.requireNonNull(chain, "chain");
        if (chain.length == 0) {
            throw new IllegalArgumentException(
                    "a wrapped operation needs at least the operation itself");
        }

        MethodHandle beneath = Objects.requireNonNull(chain[0], "chain[0]");
        for (int level = 1; level < chain.length; level++) {
            final MethodHandle handler = Objects.requireNonNull(chain[level], "chain[" + level + ']');
            // The handler's last parameter is its Operation, and what it wraps is everything
            // resolved so far. Binding it here rather than pushing it at the site is what makes the
            // nesting cost nothing per call.
            beneath = MethodHandles.insertArguments(handler, handler.type().parameterCount() - 1,
                    adapt(beneath, describe));
        }
        return adapt(beneath, describe);
    }

    /**
     * Wraps one level of the chain in an {@link Operation}.
     *
     * <p>The arity is taken from the adapted handle rather than from the operation, which keeps every level of a
     * nest taking the same arguments: a handler declares the operation's inputs plus its own {@link Operation},
     * and binding that last parameter leaves exactly the operation's inputs behind.
     *
     * @param operation the handle to perform; must not be {@code null}
     * @param describe  the operation as it reads in a message; must not be {@code null}
     * @return an operation over that handle
     */
    @Contract(value = "_, _ -> new", pure = true)
    @NotNull
    private static Operation<?> adapt(@NotNull final MethodHandle operation,
                                      @NotNull final String describe) {
        // generic() makes every parameter and the result Object, which is also what turns a void
        // operation into one yielding null — exactly what Operation<Void> promises.
        final MethodType generic = operation.type().generic();
        final int arity = generic.parameterCount();
        return new OperationSupport<>(operation.asType(generic).asSpreader(Object[].class, arity),
                arity, describe);
    }

    /**
     * Performs this level of the operation and returns what it produced.
     *
     * <p>The arity is checked before the handle is invoked, so a handler that passes the wrong number of values
     * is told which operation it was and what it takes, rather than meeting the adapter's own message for an
     * array of the wrong length. The remaining conversions are the adapter's: an argument is cast to the
     * operation's own parameter type, and a primitive parameter unboxes what it is given.
     *
     * <p>The cast to {@code T} is unchecked and cannot fail here, because the handle's adapted result type is
     * {@code Object}. A wrong type argument on the handler's {@link Operation} parameter fails at the cast the
     * handler's own compiled code performs.
     *
     * @param args the operation's inputs, in push order; {@code null} is read as no arguments
     * @return what the operation produced, boxed for a primitive result, or {@code null} for an operation that
     *         produces nothing
     * @throws IllegalArgumentException if the number of arguments is not the number this operation takes
     * @throws ClassCastException if an argument is not of the operation's own parameter type
     * @throws NullPointerException if a {@code null} is passed where the operation takes a primitive
     */
    @Override
    @Nullable
    public T call(final Object @Nullable ... args) {
        final Object[] actual = args == null ? NO_ARGUMENTS : args;
        if (actual.length != this.arity) {
            throw new IllegalArgumentException(this.describe + " takes " + this.arity
                    + " argument(s), but was called with " + actual.length);
        }
        try {
            return (T) this.spread.invoke(actual);
        } catch (final Throwable thrown) {
            throw rethrow(thrown);
        }
    }

    /**
     * Throws the given throwable as it stands, whether or not it is checked.
     *
     * <p>{@link MethodHandle#invoke(Object...)} is declared to throw {@link Throwable}, and an operation that
     * threw a checked exception has to reach the target's own {@code catch} blocks as that exception: wrapping
     * it would change which of them runs. The type variable is inferred as an unchecked exception at the call
     * site, which is what lets the throw happen without a {@code throws} clause anywhere on the way out. The
     * declared {@link RuntimeException} return exists so that the caller can write {@code throw rethrow(...)}
     * and leave the compiler in no doubt that the path ends there.
     *
     * @param <E>    the exception type inferred at the call site
     * @param thrown the throwable to rethrow; must not be {@code null}
     * @return never returns normally
     * @throws E always, holding {@code thrown} unchanged
     */
    @Contract("_ -> fail")
    @NotNull
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException rethrow(
            @NotNull final Throwable thrown) throws E {
        throw (E) thrown;
    }

    /**
     * Returns a description of the operation this handle performs, in the form
     * {@code Operation[com.acme.Ledger.post(BigDecimal)Receipt]}.
     *
     * <p>The text between the brackets is the description the engine put into the constant: a call renders as
     * its owner, name and descriptor, a field access as its opcode and the field, and an instantiation as
     * {@code new} with the constructor's descriptor. Every level of a nest carries the same one, since all of
     * them perform the same operation in the end.
     *
     * @return a description of the operation this handle performs
     */
    @Override
    @NotNull
    public String toString() {
        return "Operation[" + this.describe + ']';
    }
}
