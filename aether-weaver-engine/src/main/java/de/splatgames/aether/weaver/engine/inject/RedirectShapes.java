package de.splatgames.aether.weaver.engine.inject;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.classfile.CodeElement;
import java.lang.constant.MethodTypeDesc;
import java.util.List;
import java.util.Objects;

/**
 * Answers what a {@code @Redirect} handler at one element of a body would have to look like.
 *
 * <p>A published view over the reading the injectors do internally. The engine's own weaving path
 * does not go through it — {@code RedirectInjector} reads {@code RedirectedOperation} directly — and
 * what it adds is a form a caller outside this package can hold: a descriptor and a description,
 * without the method handle and the input list that only emission needs.
 *
 * <p>Both methods answer from the elements they are handed and keep nothing, so a caller that has
 * already rewritten the body is asking about positions that no longer exist.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
public final class RedirectShapes {

    /**
     * Refuses instantiation.
     *
     * @throws AssertionError always
     */
    private RedirectShapes() {
        throw new AssertionError("no instances");
    }

    /**
     * The shape of one redirectable operation.
     *
     * <p>{@link #handler()} is the shortest descriptor that fits: the operation's own inputs, in
     * stack order and with the receiver of an instance operation first, returning what the
     * operation returns. A real handler may declare further parameters after those, so
     * {@link RedirectShapes#accepts(List, int, MethodTypeDesc)} rather than equality is what
     * decides whether a given handler is acceptable.
     *
     * @param handler  the descriptor a handler must begin with
     * @param describe the operation as it reads in a message
     * @author Erik Pförtner
     * @since 0.1.0
     */
    public record Shape(@NotNull MethodTypeDesc handler, @NotNull String describe) {

        /**
         * Checks that both components are present.
         *
         * @throws NullPointerException if either component is {@code null}
         */
        public Shape {
            Objects.requireNonNull(handler, "handler");
            Objects.requireNonNull(describe, "describe");
        }
    }

    /**
     * Returns the shape of the operation at one element.
     *
     * @param elements the body; must not be {@code null}
     * @param site     the element index to read
     * @return the shape, or {@code null} when the position holds nothing a redirect can replace,
     *         which includes an index outside the body
     * @throws NullPointerException if {@code elements} is {@code null}
     */
    @Contract(pure = true)
    @Nullable
    public static Shape at(@NotNull final List<CodeElement> elements, final int site) {
        final RedirectedOperation operation =
                RedirectedOperation.at(Objects.requireNonNull(elements, "elements"), site);
        return operation == null
                ? null
                : new Shape(operation.signature(), operation.describe());
    }

    /**
     * Reports whether a handler of the given descriptor could redirect the operation at one
     * element.
     *
     * <p>A position holding no operation and a handler that does not fit one are the same answer
     * here; {@link #at(List, int)} is what tells them apart.
     *
     * @param elements the body; must not be {@code null}
     * @param site     the element index to read
     * @param handler  the handler's descriptor; must not be {@code null}
     * @return {@code true} when the position holds an operation the handler begins with
     * @throws NullPointerException if {@code elements} or {@code handler} is {@code null}
     */
    @Contract(pure = true)
    public static boolean accepts(@NotNull final List<CodeElement> elements,
                                  final int site,
                                  @NotNull final MethodTypeDesc handler) {
        Objects.requireNonNull(handler, "handler");
        final RedirectedOperation operation =
                RedirectedOperation.at(Objects.requireNonNull(elements, "elements"), site);
        return operation != null && operation.isMatchedBy(handler);
    }
}
