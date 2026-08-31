package de.splatgames.aether.weaver.testkit;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;


/**
 * Hands a test a {@link Weaving} built from the {@link Weaves} declaration in scope.
 *
 * <p>Registered with {@code @ExtendWith(WeaverExtension.class)}. It implements
 * {@link ParameterResolver} and nothing else: it never intercepts a test, never weaves anything of
 * its own accord, and resets no state between tests. A test that wants a class woven calls
 * {@link Weaving#weave(Class)} on the parameter it was given.
 *
 * <h2>What it claims</h2>
 *
 * <p>{@link #supportsParameter(ParameterContext, ExtensionContext)} claims a parameter whose
 * declared type is exactly {@link Weaving}, wherever JUnit offers parameter resolution, and claims
 * nothing else. No other type is resolved, and a parameter typed as a supertype of
 * {@link Weaving} is not claimed either.
 *
 * <h2>How far one weaver is shared</h2>
 *
 * <p>The {@link Weaving} is built on first resolution and kept in the store of the
 * {@link ExtensionContext} that resolution happened in, under a namespace private to this class
 * and keyed by {@link Weaving}. Because a store lookup consults ancestor stores, the scope of the
 * sharing follows whichever context asked first. Resolved only into test method parameters, every
 * test method gets a weaver of its own, with its own plan, its own statistics and its own
 * diagnostics. A test class that also takes a {@link Weaving} in its constructor gets one built at
 * class level, and each of its test methods is then handed that same instance — under either
 * {@code @TestInstance} lifecycle, and with the statistics of the whole class accumulated in it.
 *
 * <h2>What it does when it cannot answer</h2>
 *
 * <p>Every failure is a {@link ParameterResolutionException}, thrown out of
 * {@link #resolveParameter(ParameterContext, ExtensionContext)}:
 *
 * <ul>
 *   <li>No {@link Weaves} on the test method, its class or any enclosing class. The message states
 *       the requirement and why an unplanned weaver is not an acceptable substitute.
 *   <li>The nearest {@link Weaves} names no class. The message quotes the display name of the
 *       context that carries it.
 *   <li>{@link Weaving#of(Class[])} refused the classes — a class with no readable class file, or
 *       one that does not parse as a weave. The refusal's own message is appended and the refusal
 *       is kept as the cause. Only a {@link RuntimeException} is converted this way; an
 *       {@link Error} propagates untouched.
 * </ul>
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see Weaves
 * @see Weaving
 */
public final class WeaverExtension implements ParameterResolver {

    /** The store namespace this extension keeps its {@link Weaving} in, private to this class. */
    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(WeaverExtension.class);

    /**
     * Creates the extension.
     *
     * <p>Invoked reflectively by JUnit for {@code @ExtendWith(WeaverExtension.class)}; the instance
     * carries no state, so registering it more than once changes nothing.
     */
    public WeaverExtension() {
        // Stateless: everything lives in the per-test store.
    }

    /**
     * Reports whether this extension supplies the given parameter.
     *
     * <p>Compares the parameter's declared type with {@link Weaving} by identity, so a parameter
     * declared as {@link Object} or as any other type is left to another resolver. Nothing about
     * the {@link Weaves} declaration is examined here, so a parameter is claimed whether or not it
     * can be satisfied; a missing or empty declaration surfaces from
     * {@link #resolveParameter(ParameterContext, ExtensionContext)} instead.
     *
     * @param parameter the parameter under consideration; must not be {@code null}
     * @param context   the context the parameter belongs to, unused
     * @return {@code true} when the parameter's type is exactly {@link Weaving}
     */
    @Override
    public boolean supportsParameter(@NotNull final ParameterContext parameter,
                                     @NotNull final ExtensionContext context) {
        return parameter.getParameter().getType() == Weaving.class;
    }

    /**
     * Supplies the {@link Weaving} for the given parameter.
     *
     * <p>Returns the instance already held in {@code context}'s store or an ancestor's, and builds
     * one only when neither has it. Two {@link Weaving} parameters of the same test method
     * therefore receive the same instance, and so does a later parameter in a context nested
     * inside the one that first built it.
     *
     * @param parameter the parameter to supply; must not be {@code null}, otherwise unused
     * @param context   the context to resolve in and to store the result under; must not be
     *                  {@code null}
     * @return the {@link Weaving} for this context
     * @throws ParameterResolutionException if no {@link Weaves} is in scope, if the nearest one
     *                                      names no class, or if the named classes could not be
     *                                      planned
     */
    @Override
    @NotNull
    public Object resolveParameter(@NotNull final ParameterContext parameter,
                                   @NotNull final ExtensionContext context) {
        return context.getStore(NAMESPACE)
                .getOrComputeIfAbsent(Weaving.class, key -> build(context), Weaving.class);
    }

    /**
     * Builds the weaver for a context that has none yet.
     *
     * <p>Reads the classes with {@link #declared(ExtensionContext)} and plans them with
     * {@link Weaving#of(Class[])}. Anything {@link Weaving#of(Class[])} throws that is a
     * {@link RuntimeException} is re-thrown as a {@link ParameterResolutionException} naming the
     * context's display name, with the original message appended and the original kept as the
     * cause; an {@link Error} is not caught.
     *
     * @param context the context being resolved for; must not be {@code null}
     * @return the planned weaver
     * @throws ParameterResolutionException if no usable {@link Weaves} is in scope or the classes
     *                                      it names could not be planned
     */
    @NotNull
    private static Weaving build(@NotNull final ExtensionContext context) {
        final Class<?>[] weaves = declared(context);
        try {
            return Weaving.of(weaves);
        } catch (final RuntimeException refused) {
            throw new ParameterResolutionException("the weaves declared for "
                    + context.getDisplayName() + " could not be planned: " + refused.getMessage(),
                    refused);
        }
    }

    /**
     * Finds the {@link Weaves} declaration that governs the given context.
     *
     * <p>Walks from {@code context} to the root through {@link ExtensionContext#getParent()},
     * asking each context's own element for the annotation, and returns the classes named by the
     * first element that carries one. The walk stops at that element: an outer declaration is
     * shadowed rather than merged. A context whose element is absent, such as the engine's root,
     * contributes nothing and the walk continues past it. Because {@link Weaves} is
     * {@link java.lang.annotation.Inherited}, a test class also answers with its superclass's
     * declaration.
     *
     * <p>Finding a declaration that names no class ends the walk in a failure rather than falling
     * through to an outer one.
     *
     * @param context the context to start from; must not be {@code null}
     * @return the declared weave classes, never empty
     * @throws ParameterResolutionException if the nearest declaration names no class, or if the
     *                                      walk reaches the root without finding one
     */
    private static Class<?> @NotNull [] declared(@NotNull final ExtensionContext context) {
        for (ExtensionContext current = context; current != null;
             current = current.getParent().orElse(null)) {
            final Weaves weaves = current.getElement()
                    .map(element -> element.getAnnotation(Weaves.class))
                    .orElse(null);
            if (weaves == null) {
                continue;
            }
            if (weaves.value().length == 0) {
                throw new ParameterResolutionException("@Weaves on "
                        + current.getDisplayName() + " names no weave class");
            }
            return weaves.value();
        }
        throw new ParameterResolutionException(
                "a Weaving parameter needs an @Weaves declaration on the test method, its class, "
                        + "or an enclosing class; without one the weaver would plan nothing and "
                        + "every assertion about nothing having been applied would pass");
    }

    /**
     * Returns a fixed description of what this extension resolves.
     *
     * <p>The text is the same for every instance; the extension holds no state that could vary it.
     *
     * @return {@code WeaverExtension[resolves Weaving]}
     */
    @Override
    @NotNull
    public String toString() {
        return "WeaverExtension[resolves " + Weaving.class.getSimpleName() + ']';
    }
}
