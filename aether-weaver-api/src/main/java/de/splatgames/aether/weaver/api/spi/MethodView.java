package de.splatgames.aether.weaver.api.spi;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.Optional;
import java.util.Set;

/**
 * One method of the class being woven, read from its class file.
 *
 * <p>This is what an {@link InjectionPoint} is asked about, what an {@link Injector} reasons over,
 * and what {@link TargetView#methods()} lists. It carries the four things a class file records about
 * a method — name, erased descriptor, access flags and body — and nothing that would require loading
 * a class: no reflection is performed, no type is resolved, and no supertype is consulted. A view is
 * a snapshot of the method as it was read, and weaving builds a new class rather than editing this
 * one, so a view never reflects what an injection is about to add.
 *
 * <h2>Name and descriptor together identify it</h2>
 *
 * <p>{@link #name()} alone does not: overloads share a name, and a constructor is named
 * {@code <init>} while a static initialiser is named {@code <clinit>}. {@link TargetView#method}
 * takes both for that reason. Where a declaration's selector resolves to more than one method the
 * build is refused as {@code AW1021}, listing the methods the selector matched, rather than one of
 * them being chosen; where it resolves to none it is {@code AW1020}, listing the target's own
 * methods instead. Both render the methods they list with {@link #describe()}.
 *
 * <h2>Not every method can be injected into</h2>
 *
 * <p>Three shapes are refused before an injector is consulted, each with its own code:
 *
 * <ul>
 *   <li>{@link #isNative()} — {@code AW1025}. A native method's implementation is not a class file,
 *       so there is nothing to inject into. Inject into the Java method that calls it, or use
 *       {@code @Redirect} at the call site.
 *   <li>{@link #code()} empty — {@code AW1023}. An abstract declaration says what happens, not how.
 *       Name an implementing method instead.
 *   <li>{@link #isSynthetic()} — {@code AW1024}. A compiler-generated method has a body and the
 *       injection would work; what it would not do is survive a recompilation that changes the
 *       generated shape. Name the method the author wrote.
 * </ul>
 *
 * <p>The check is made in that order, and it uses {@link #code()} rather than {@link #isAbstract()}
 * for the second: a native method is equally bodyless without carrying the abstract flag, and an
 * interface method with a body carries neither.
 *
 * <p>Instances are supplied by the engine.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see TargetView
 * @see CodeView
 */
@ApiStatus.NonExtendable
public interface MethodView {

    /**
     * Returns the method's name exactly as the class file spells it.
     *
     * <p>Which is {@code <init>} for a constructor and {@code <clinit>} for a static initialiser,
     * neither of which is a legal Java identifier. {@link #isConstructor()} is the test for the
     * first.
     *
     * @return the method name, never empty
     */
    @Contract(pure = true)
    @NotNull
    String name();

    /**
     * Returns the method's erased descriptor: its parameter types and its return type.
     *
     * <p>Erased, so a generic signature is not visible here and two methods differing only in type
     * arguments have the same descriptor. This is the form parameters are compared in when a
     * handler is bound to a target: {@link HandlerBinding} matches each claimed parameter by
     * {@link java.lang.constant.ClassDesc} equality, with no widening, boxing or subtyping.
     *
     * @return the descriptor
     */
    @Contract(pure = true)
    @NotNull
    MethodTypeDesc type();

    /**
     * Returns the method's access flags.
     *
     * <p>Exactly the flags the class file sets, translated into {@link AccessFlag} constants. The
     * predicates below are the questions this SPI asks of them; anything else is asked of the set
     * directly.
     *
     * @return the flags, never {@code null} and not modifiable
     */
    @Contract(pure = true)
    @Unmodifiable
    @NotNull
    Set<AccessFlag> flags();

    /**
     * Returns the method's body, if it has one.
     *
     * <p>Empty for an abstract or a native method, and present for every method an injection can
     * reach — a declaration whose target has no body is refused as {@code AW1023} long before an
     * injector sees it, which is why {@link InjectionContext#method()} may be read with
     * {@code code().orElseThrow()}.
     *
     * <p>The view returned is the whole body. An {@link InjectionPoint} is handed a
     * {@link CodeView} separately, and that one may be narrower: where the declaration names a slice,
     * the point searches the slice alone. The two are not interchangeable, and an index into one is
     * not an index into the other.
     *
     * @return the body, or an empty {@link Optional} for a method that has none
     */
    @Contract(pure = true)
    @NotNull
    Optional<CodeView> code();

    /**
     * Reports whether the method is static.
     *
     * <p>What decides the first local variable slot: slot zero holds {@code this} in an instance
     * method, so an instance method's parameters begin at slot one and a static method's at slot
     * zero. It also decides whether an instance handler can be called at all — a static target has
     * no receiver to invoke one on, which is {@code AW1005}.
     *
     * @return {@code true} when {@link #flags()} contains {@link AccessFlag#STATIC}
     */
    @Contract(pure = true)
    default boolean isStatic() {
        return flags().contains(AccessFlag.STATIC);
    }

    /**
     * Reports whether the method is declared abstract.
     *
     * <p>The flag alone. This is not the test for "has no body": a native method has none without
     * carrying the flag, so {@link #code()} is what the engine asks before injecting.
     *
     * @return {@code true} when {@link #flags()} contains {@link AccessFlag#ABSTRACT}
     */
    @Contract(pure = true)
    default boolean isAbstract() {
        return flags().contains(AccessFlag.ABSTRACT);
    }

    /**
     * Reports whether the method is implemented outside the class file.
     *
     * <p>A native method is refused as an injection target with {@code AW1025}.
     *
     * @return {@code true} when {@link #flags()} contains {@link AccessFlag#NATIVE}
     */
    @Contract(pure = true)
    default boolean isNative() {
        return flags().contains(AccessFlag.NATIVE);
    }

    /**
     * Reports whether the method was generated by the compiler rather than written by an author.
     *
     * <p>True for the synthetic flag and for the bridge flag alike, because both describe a method
     * whose existence and shape are a compiler's decision. Such a method is refused as an injection
     * target with {@code AW1024}: the injection would work, and would stop working the moment a
     * recompilation changed the generated shape.
     *
     * @return {@code true} when {@link #flags()} contains {@link AccessFlag#SYNTHETIC} or
     *         {@link AccessFlag#BRIDGE}
     */
    @Contract(pure = true)
    default boolean isSynthetic() {
        return flags().contains(AccessFlag.SYNTHETIC) || flags().contains(AccessFlag.BRIDGE);
    }

    /**
     * Reports whether the method is an instance initialiser.
     *
     * <p>Compares {@link #name()} against {@code <init>}, so a static initialiser — named
     * {@code <clinit>} — is not one. The distinction changes where the built-in {@code HEAD} point
     * lands: in a constructor it resolves to the position just after the constructor's own call to a
     * superclass or sibling initialiser, and elsewhere to the first instruction of the body.
     *
     * @return {@code true} when {@link #name()} is {@code <init>}
     */
    @Contract(pure = true)
    default boolean isConstructor() {
        return "<init>".equals(name());
    }

    /**
     * Returns the method rendered for a human reading a diagnostic.
     *
     * <p>The form is the name followed by the parameter types in parentheses, each written as its
     * display name — {@code String} rather than {@code Ljava/lang/String;}, and {@code int[]} rather
     * than {@code [I}. Neither the declaring class nor the return type appears, so two methods
     * differing only in return type render identically and a constructor renders as
     * {@code <init>(...)}.
     *
     * <p>Diagnostics about a target method quote this rendering, including the listings under
     * {@code AW1020} and {@code AW1021} and the messages of {@code AW1023}, {@code AW1024} and
     * {@code AW1025}. A method {@code charge(BigDecimal amount, int retries)} renders as
     * {@code charge(BigDecimal, int)}.
     *
     * @return the rendered method, never empty
     */
    @Contract(pure = true)
    @NotNull
    String describe();
}
