package de.splatgames.aether.weaver.api.model;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.Objects;
import java.util.Set;

/**
 * Names the method a declaration hands control to, and records enough of its declaration for the
 * call to be emitted without loading it.
 *
 * <p>A handler is identified by owner, name and descriptor, which is exactly what a JVM method
 * reference needs and exactly what the class file already holds. Nothing here is resolved, loaded
 * or reflected upon: the weave class that declares the handler is read as bytes and never
 * initialised, so a handler reference is the only thing that exists of it at weave time.
 *
 * <h2>The four components</h2>
 *
 * <p>{@link #owner()} is the class that <em>declares</em> the handler, which is not always the
 * class the call is emitted in. An instance weave is dissolved into its target, and its handlers
 * become methods of that target; the class the {@code invoke} instruction names in that case is
 * {@link de.splatgames.aether.weaver.api.spi.PlanEntryView#handlerOwner()}, not this. The
 * distinction is invisible in a static weave, where the handler stays where it was written.
 *
 * <p>{@link #name()} and {@link #type()} are the handler's own name and erased descriptor. They are
 * compared literally: a handler is never matched by shape, so an overload is a different handler.
 *
 * <p>{@link #flags()} is the method's declared access flags, copied on construction into an
 * unmodifiable set. Two of them are load-bearing and the rest are carried for completeness.
 *
 * <h2>What the flags decide</h2>
 *
 * <p>The flags choose the invocation opcode, which is why they travel with the reference rather
 * than being looked up later:
 *
 * <ul>
 *   <li><b>{@link AccessFlag#STATIC}</b> emits {@code invokestatic}. A static handler needs no
 *       receiver and can be called from anywhere it is accessible.
 *   <li><b>{@link AccessFlag#PRIVATE}</b>, on a non-static handler, emits {@code invokespecial}
 *       rather than {@code invokevirtual}, because a private method is not dispatched virtually and
 *       naming it with {@code invokevirtual} does not link.
 *   <li>Anything else, on a non-static handler, emits {@code invokevirtual}.
 * </ul>
 *
 * <p>A non-static handler is only usable at all when the weave dissolves into its target, so that
 * the handler ends up a method of the class calling it. A non-static handler in a weave that is not
 * dissolved is reported as {@code AW1005}; declare the handler {@code static}, or declare the weave
 * {@code @Weave(kind = Kind.INSTANCE)} so that it is merged. {@link de.splatgames.aether.weaver.api.Wrap}
 * requires {@code static} unconditionally and reports the same code otherwise, because a wrap can
 * be nested inside another weave's wrap and the inner level is reached with no receiver.
 *
 * <p>An {@link de.splatgames.aether.weaver.api.Inject} handler must also return {@code void};
 * {@link #type()} showing anything else is reported as {@code AW1041}. An {@code @Inject} handler
 * influences its target through its callback, not through a return value.
 *
 * <h2>Ordering</h2>
 *
 * <p>Where several declarations apply to one place, they are ordered by the declaring weave's
 * priority first, highest first, then by the weave class's binary name, then by {@link #name()},
 * then by {@link #type()} rendered as a descriptor. These tie-breakers do not make the order total:
 * two declarations compare equal whenever one weave names the same handler for two targets, or when
 * one handler carries two {@code @Inject} annotations — {@link InjectorSpec} produces one
 * specification per annotation occurrence, and neither the target nor the declaration itself enters
 * this comparison. Two builds of the same inputs still agree, because the sort that applies this
 * order is stable and the declarations are read in the same order every time.
 *
 * <h2>Diagnostics</h2>
 *
 * <p>{@link #describe()} is the form a handler appears in throughout the build output. It is a
 * single line and it is the same line everywhere, so a code reported at planning time and a code
 * reported during emission can be recognised as being about the same handler.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * HandlerRef handler = new HandlerRef(
 *         ClassDesc.of("com.acme.audit.LedgerWeave"),
 *         "onCharge",
 *         MethodTypeDesc.ofDescriptor("(Ljava/math/BigDecimal;)V"),
 *         Set.of(AccessFlag.PRIVATE, AccessFlag.STATIC));
 *
 * handler.isStatic();    // true  -> invokestatic
 * handler.isPrivate();   // true  -> ignored, because static wins
 * handler.describe();    // com.acme.audit.LedgerWeave.onCharge(Ljava/math/BigDecimal;)V
 * }</pre>
 *
 * @param owner the class declaring the handler
 * @param name  the handler's method name; never blank
 * @param type  the handler's erased descriptor
 * @param flags the handler's declared access flags, held as an unmodifiable copy
 * @author Erik Pförtner
 * @since 0.1.0
 * @see InjectorSpec
 */
public record HandlerRef(ClassDesc owner, String name, MethodTypeDesc type, Set<AccessFlag> flags) {

    /**
     * Checks the reference and takes an unmodifiable copy of the flags.
     *
     * <p>The copy is what makes a handler reference safe to share: it is built from a set the
     * caller still holds, and a later change to that set must not alter which opcode is emitted.
     *
     * @throws NullPointerException     if any argument is {@code null}, or if {@code flags}
     *                                  contains {@code null}
     * @throws IllegalArgumentException if {@code name} is empty or contains only whitespace
     */
    public HandlerRef {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        if (name.isBlank()) {
            throw new IllegalArgumentException("a handler name must not be blank");
        }
        flags = Set.copyOf(Objects.requireNonNull(flags, "flags"));
    }

    /**
     * Reports whether the handler is declared {@code static}.
     *
     * @return {@code true} when {@link #flags()} contains {@link AccessFlag#STATIC}
     */
    @Contract(pure = true)
    public boolean isStatic() {
        return this.flags.contains(AccessFlag.STATIC);
    }

    /**
     * Reports whether the handler is declared {@code private}.
     *
     * <p>Only consulted for a non-static handler, where it selects {@code invokespecial} over
     * {@code invokevirtual}.
     *
     * @return {@code true} when {@link #flags()} contains {@link AccessFlag#PRIVATE}
     */
    @Contract(pure = true)
    public boolean isPrivate() {
        return this.flags.contains(AccessFlag.PRIVATE);
    }

    /**
     * Returns the handler as one line of build output.
     *
     * <p>The owner's binary name, a dot, the method name, and the raw descriptor:
     * {@code com.acme.audit.LedgerWeave.onCharge(Ljava/math/BigDecimal;)V}. The descriptor is not
     * translated into source syntax, because it is what distinguishes two overloads and a
     * prettified form would make two different handlers print identically.
     *
     * @return the fully qualified handler, owner in binary form and signature as a descriptor
     */
    @Contract(pure = true)
    @NotNull
    public String describe() {
        final String descriptor = this.owner.descriptorString();
        return descriptor.substring(1, descriptor.length() - 1).replace('/', '.')
                + '.' + this.name + this.type.descriptorString();
    }
}
