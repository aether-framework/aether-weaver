package de.splatgames.aether.weaver.engine.inject;

import de.splatgames.aether.weaver.api.diagnostic.Diagnostic;
import de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode;
import de.splatgames.aether.weaver.api.model.HandlerRef;
import de.splatgames.aether.weaver.api.model.LocalSpec;
import de.splatgames.aether.weaver.api.spi.MethodView;
import de.splatgames.aether.weaver.api.spi.Reporter;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.util.Map;

/**
 * Recognises the carrier classes a mutable {@code @Local} capture is declared through, and checks a
 * declaration's carrier against its {@code mutable} flag.
 *
 * <p>Recognition is by descriptor, because a handler's parameter types reach the engine as a
 * {@link java.lang.constant.MethodTypeDesc}. The consequence is erasure: a parameter written
 * {@code LocalRef<String>} arrives here as the bare {@code LocalRef} descriptor, so the type
 * argument cannot be read and plays no part in resolving the capture.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
final class LocalRefs {

    /** Prefix of every carrier's binary name, kept apart so the nine descriptors read as a set. */
    private static final String PACKAGE = "de.splatgames.aether.weaver.api.callback.";

    /** The reference carrier, which erases and therefore names no element type. */
    private static final ClassDesc GENERIC = ClassDesc.of(PACKAGE + "LocalRef");

    /**
     * The eight primitive carriers, each mapped to the descriptor its slot must match exactly.
     *
     * <p>There is one carrier per primitive and no widening between them, because
     * {@code Assignability} compares primitives for equality: the entry chosen here is the whole of
     * what the target's slot is checked against.
     */
    private static final Map<ClassDesc, ClassDesc> PRIMITIVES = Map.of(
            ClassDesc.of(PACKAGE + "LocalIntRef"), ConstantDescs.CD_int,
            ClassDesc.of(PACKAGE + "LocalLongRef"), ConstantDescs.CD_long,
            ClassDesc.of(PACKAGE + "LocalFloatRef"), ConstantDescs.CD_float,
            ClassDesc.of(PACKAGE + "LocalDoubleRef"), ConstantDescs.CD_double,
            ClassDesc.of(PACKAGE + "LocalBooleanRef"), ConstantDescs.CD_boolean,
            ClassDesc.of(PACKAGE + "LocalByteRef"), ConstantDescs.CD_byte,
            ClassDesc.of(PACKAGE + "LocalShortRef"), ConstantDescs.CD_short,
            ClassDesc.of(PACKAGE + "LocalCharRef"), ConstantDescs.CD_char);

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private LocalRefs() {
        throw new AssertionError("no instances");
    }

    /**
     * Reports whether a handler parameter is one of the carriers, returning it unchanged when it is.
     *
     * <p>The identity return is what makes the result usable as both an answer and a value: the
     * caller needs the carrier's own descriptor to construct it, and a {@code null} here is the
     * signal that the parameter is an ordinary by-value capture.
     *
     * @param parameter the declared type of the handler parameter; must not be {@code null}
     * @return {@code parameter} itself when it names a carrier, otherwise {@code null}
     * @throws NullPointerException if {@code parameter} is {@code null}
     */
    @Contract(pure = true)
    @Nullable
    static ClassDesc carrierOf(@NotNull final ClassDesc parameter) {
        if (GENERIC.equals(parameter) || PRIMITIVES.containsKey(parameter)) {
            return parameter;
        }
        return null;
    }

    /**
     * Returns the descriptor a carrier's contents are matched against.
     *
     * <p>{@link Object} for anything absent from {@code PRIMITIVES}, which is how the erased generic
     * carrier is handled without a separate branch — and, because the fallback is unconditional, how
     * an argument that is not a carrier at all would be handled too. The caller reaches this only
     * after {@code carrierOf} has said yes.
     *
     * <p>{@link Object} is a real constraint further down and not merely a placeholder. A capture
     * that resolves by type or by ordinal compares the descriptor returned here against the type the
     * local variable table records, so a mutable reference capture matches a variable the table
     * describes as {@link Object} and no other. Naming the variable, or its slot, is what a mutable
     * capture of any other reference type needs.
     *
     * @param carrier the carrier class; must not be {@code null}
     * @return the primitive the carrier holds, or {@link Object} for the generic carrier
     * @throws NullPointerException if {@code carrier} is {@code null}
     */
    @Contract(pure = true)
    @NotNull
    static ClassDesc heldBy(@NotNull final ClassDesc carrier) {
        return PRIMITIVES.getOrDefault(carrier, ConstantDescs.CD_Object);
    }

    /**
     * Checks a capture's {@code mutable} flag against the parameter it was declared on.
     *
     * <p>The two halves are separate diagnostics rather than one, because the remedies point in
     * opposite directions. {@code AW1053} covers {@code mutable = true} on a plain parameter, which
     * would compile and weave and then quietly discard the handler's assignment, since a Java
     * parameter is passed by value; the fix is to declare a carrier. {@code AW1054} covers a carrier
     * without {@code mutable = true}, where the handler holds an object it may not write through;
     * the fix is either flag or plain type, depending on what was meant.
     *
     * <p>Both are reported before the capture is resolved against the target, so a declaration that
     * is wrong in this way never produces a second message about a slot.
     *
     * @param local    the capture declaration; must not be {@code null}
     * @param carrier  what {@code carrierOf} answered for the parameter, or {@code null} for a
     *                 by-value parameter
     * @param handler  the handler, named in both diagnostics; must not be {@code null}
     * @param method   the target method, named as a detail; must not be {@code null}
     * @param reporter where to report; must not be {@code null}
     * @return {@code true} when the flag and the parameter agree, {@code false} after reporting
     */
    static boolean agree(@NotNull final LocalSpec local,
                         @Nullable final ClassDesc carrier,
                         @NotNull final HandlerRef handler,
                         @NotNull final MethodView method,
                         @NotNull final Reporter reporter) {
        if (local.mutable() && carrier == null) {
            reporter.report(Diagnostic.builder(DiagnosticCode.LOCAL_MUTABLE_NEEDS_REF)
                    .message(handler.describe() + " declares @Local(mutable = true) on parameter "
                            + local.parameter() + ", which is not a LocalRef")
                    .detail("target: " + method.describe())
                    .remedy("a Java parameter is passed by value, so assigning to it would change "
                            + "the handler's own copy and leave " + method.describe()
                            + " holding the old one. Declare the parameter as LocalRef<T> — or "
                            + "LocalIntRef and friends for a primitive — which is what carries the "
                            + "write back into the target's slot")
                    .build());
            return false;
        }
        if (!local.mutable() && carrier != null) {
            reporter.report(Diagnostic.builder(DiagnosticCode.LOCAL_REF_WITHOUT_MUTABLE)
                    .message(handler.describe() + " takes a " + carrier.displayName()
                            + " on parameter " + local.parameter()
                            + " without @Local(mutable = true)")
                    .detail("target: " + method.describe())
                    .remedy("add mutable = true if the handler means to write the variable, or "
                            + "declare the parameter as the variable's own type if it only reads "
                            + "it — a carrier that may not be written to is a handle nobody should "
                            + "be holding")
                    .build());
            return false;
        }
        return true;
    }
}
