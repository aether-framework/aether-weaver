package de.splatgames.aether.weaver.api.spi;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The class being woven, as much of it as the SPI exposes.
 *
 * <p>A read-only view over one parsed class file: its name in the three forms a weave needs, its
 * access flags, what it extends and implements, and the methods it declares. It is what an
 * {@link Injector} is given to check a declaration against the class it is about to change, and
 * what {@link InjectionContext#target()} answers with while code is being emitted.
 *
 * <h2>A snapshot, and of what</h2>
 *
 * <p>The view describes the class as it was read, before this weave touched it. Weaving builds a
 * new class rather than editing the parsed one, so nothing another injection is about to add shows
 * up here — a handler that a merge will fold into the target is not among {@link #methods()} while
 * the injection that expects it is being planned.
 *
 * <p>Everything is declared, never inherited. {@link #methods()} lists what this class file
 * declares and no more; a method the class gets from its superclass or from a default
 * implementation is not here, and finding it is a matter of walking {@link #superclass()} and
 * {@link #interfaces()} with a class source of the caller's own.
 *
 * <h2>Where the engine uses it</h2>
 *
 * <p>Resolving the {@code method} of a declaration is a search through {@link #methods()}: the
 * parsed selector is matched against each declared method, one match is the answer, none is
 * reported as {@code AW1020} — listing every method the class declares, which is where
 * {@link MethodView#describe()} appears — and several as {@code AW1021}. Naming the parameter
 * types, or using the descriptor form of a selector, settles the second; the first means the
 * selector matches nothing on this class and needs a different name or owner rather than a
 * narrower one.
 *
 * <p>An injector receives the view twice for different purposes: in
 * {@link Injector#validate(PlanEntryView, TargetView, Reporter)}, to refuse a declaration that
 * cannot work against this particular class, and in
 * {@link Injector#contribute(java.lang.classfile.ClassBuilder, PlanEntryView, TargetView)}, to
 * decide what members to add. The built-in injector for {@code @Inject} uses the first to report
 * {@code AW1005} when a non-static handler's owner is not {@link #type()} — an instance handler is
 * callable only once it is a method of the class doing the calling.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @Override
 * public void validate(PlanEntryView entry, TargetView target, Reporter reporter) {
 *     if (target.isInterface()) {
 *         reporter.report(NOT_ON_INTERFACES,
 *                 target.binaryName() + " is an interface, and this injector needs a field");
 *     }
 *     if (target.method("close", MethodTypeDesc.of(ConstantDescs.CD_void)).isEmpty()) {
 *         reporter.report(NO_CLOSE, target.binaryName() + " declares no close()");
 *     }
 * }
 * }</pre>
 *
 * <p>Instances are supplied by the engine.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see MethodView
 * @see InjectionContext#target()
 */
@ApiStatus.NonExtendable
public interface TargetView {

    /**
     * Returns the class as a nominal descriptor.
     *
     * <p>The form to compare with, since {@link ClassDesc} has value equality: testing this against
     * a handler's owner is how the engine decides whether a weave owns the class it is weaving.
     *
     * @return the descriptor of the class being woven
     */
    @Contract(pure = true)
    @NotNull
    ClassDesc type();

    /**
     * Returns the class's binary name, with {@code .} between package parts.
     *
     * <p>The form to put in a diagnostic, because it is what the author of the weave wrote. A
     * nested class keeps its {@code $}.
     *
     * @return the binary name of the class being woven
     */
    @Contract(pure = true)
    @NotNull
    String binaryName();

    /**
     * Returns the class's internal name, with {@code /} between package parts.
     *
     * <p>The form to compare against anything read out of a class file — the owner of an
     * invocation, the name a driver offered the class under — none of which is in binary form.
     *
     * @return the internal name of the class being woven
     */
    @Contract(pure = true)
    @NotNull
    String internalName();

    /**
     * Returns the class's access flags.
     *
     * <p>The flags of the class itself, not of any member. An enum, a record and an interface are
     * all recognisable here, and {@link #isInterface()} is the one test common enough to be
     * provided.
     *
     * @return the access flags, never {@code null} and not modifiable
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    Set<AccessFlag> flags();

    /**
     * Returns what the class extends, where that is worth knowing.
     *
     * <p>Empty for a class whose superclass is {@link Object}, which the view filters out because
     * every class that is not {@link Object} has one and reporting it says nothing. Empty as well
     * for a class file with no superclass entry at all, and for an interface, whose class file
     * names {@link Object} there.
     *
     * @return the superclass, or empty when it is {@link Object} or absent
     */
    @Contract(pure = true)
    @NotNull
    Optional<ClassDesc> superclass();

    /**
     * Returns the interfaces the class declares.
     *
     * <p>Directly declared only, in the order the class file lists them, and not transitively: an
     * interface that this class gets through its superclass or through another interface is not
     * here.
     *
     * @return the declared interfaces, never {@code null} and not modifiable; empty when there are
     *         none
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    List<ClassDesc> interfaces();

    /**
     * Returns the methods the class declares.
     *
     * <p>In class-file order, which is the order a compiler happened to emit them in rather than
     * source order. Everything the class file holds is here: constructors as {@code <init>}, the
     * static initialiser as {@code <clinit>}, and the bridge, synthetic and accessor methods a
     * compiler generated. A weave that matches on a name alone can therefore match something that
     * was never written by hand, which is one of the ways an ambiguity reported as {@code AW1021}
     * arises.
     *
     * @return the declared methods, never {@code null} and not modifiable
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    List<MethodView> methods();

    /**
     * Returns the method with exactly this name and type.
     *
     * <p>An exact lookup, not a search: the descriptor must match in full, return type included,
     * since two methods may differ in nothing else in a class file. A name shared by several
     * overloads picks out exactly one of them, and a name with no such overload answers empty
     * rather than the closest match.
     *
     * @param name       the method name, {@code <init>} for a constructor; must not be {@code null}
     * @param descriptor the method type, return type included; must not be {@code null}
     * @return the method, or empty when the class declares no method with that name and type
     * @throws NullPointerException if either argument is {@code null}
     */
    @Contract(pure = true)
    @NotNull
    Optional<MethodView> method(@NotNull String name, @NotNull MethodTypeDesc descriptor);

    /**
     * Returns whether the class being woven is an interface.
     *
     * <p>Decides which opcode a call to one of its methods needs, so an injector that emits a call
     * into the target has to consult it rather than assume a class.
     *
     * @return {@code true} when {@link #flags()} contains {@link AccessFlag#INTERFACE}
     */
    @Contract(pure = true)
    default boolean isInterface() {
        return flags().contains(AccessFlag.INTERFACE);
    }
}
