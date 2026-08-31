package de.splatgames.aether.weaver.api.spi;

import org.jetbrains.annotations.Contract;

/**
 * A contributed way of turning a member selector into the members it names.
 *
 * <p>Resolution is the step between a selector, which is a piece of text a weave author wrote, and
 * the members of a class it stands for. A resolver is registered through
 * {@link PluginContext#selectorResolvers(SelectorResolver...)}, carries a name so that it can be
 * told apart from other resolvers, and carries a priority for ranking among them.
 *
 * <h2>What this interface declares, and what it does not</h2>
 *
 * <p>At this SPI generation the interface declares an identity and a priority and nothing else.
 * There is no resolution method on it, so implementing it commits an implementor to nothing beyond
 * being nameable and orderable; the engine's own {@code DefaultSelectorResolver} implements this
 * interface and declares its resolution methods on itself rather than here.
 *
 * <p>The engine collects the resolvers a plugin registers, in the order the plugins were loaded,
 * and exposes them on its plugin registry; no call site in this project reads that list, and
 * neither {@link #priority()} nor {@link #name()} is consulted anywhere in the engine, the drivers
 * or the build plugin. Registering a resolver changes nothing about how the built-in stages
 * resolve a selector.
 *
 * <h2>How the built-in resolver resolves</h2>
 *
 * <p>{@code DefaultSelectorResolver} is the reference implementation and is worth reading as the
 * shape a resolver has. It answers from the class file it is given and applies these rules:
 *
 * <ul>
 *   <li><b>The candidates are the class's own declarations.</b> Methods for a
 *       {@code MethodSelector}, fields for a {@code FieldSelector}; nothing inherited. A
 *       {@code ConstantSelector} resolves to nothing at all, since a constant is not a member.
 *   <li><b>A name matches exactly, or is {@code *}.</b> The wildcard matches every name; there is
 *       no other pattern.
 *   <li><b>A part the selector left out is not a constraint.</b> A selector with no parameter list
 *       matches any arity, and one with no return type or field type matches any.
 *   <li><b>A parameter list matches on exact arity</b>, then position by position; a prefix does
 *       not match.
 *   <li><b>A type matches by resolved identity.</b> A wildcard type pattern matches anything.
 *       Anything else is resolved against the imports the caller supplied, falling back to
 *       {@code java.lang} for an unqualified name with no import, and the result is compared for
 *       equality. A name that resolves to nothing matches nothing, rather than being treated as a
 *       wildcard, so a mistyped type binds no member instead of binding every one.
 *   <li><b>The selector's owner takes no part.</b> Resolution happens against the class it was
 *       handed, and a selector naming a different owner still matches that class's members.
 * </ul>
 *
 * <p>Those rules are not the ones the {@code de.splatgames.aether.weaver.api.select} package
 * documents. Its account of matching describes
 * {@code de.splatgames.aether.weaver.engine.inject.point.Targets}, the matcher behind an
 * {@code @At} target, which compares an unresolved name by rendered source name and lets a name
 * without a dot match any type whose binary name ends with a dot and that name. The two matchers
 * genuinely differ, and a selector that matches under one can fail to match under the other.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 * @see PluginContext#selectorResolvers(SelectorResolver...)
 */
public interface SelectorResolver {

    /**
     * The priority a resolver has unless it says otherwise.
     *
     * <p>Zero, and the built-in resolver returns it as well, so a resolver that has no opinion
     * ranks equally with the one it is joining.
     */
    int DEFAULT_PRIORITY = 0;

    /**
     * Returns this resolver's priority relative to the others.
     *
     * <p>Higher is not defined to mean anything by this interface, and nothing in the engine reads
     * the value: resolvers arrive in the order their plugins were loaded and are kept in it.
     *
     * @return the priority; {@link #DEFAULT_PRIORITY} unless overridden
     */
    @Contract(pure = true)
    default int priority() {
        return DEFAULT_PRIORITY;
    }

    /**
     * Returns the name this resolver is known by.
     *
     * <p>The default is the implementation class's simple name, which is enough to tell two
     * resolvers apart while requiring nothing of an implementor. The built-in resolver overrides it
     * with {@code default}.
     *
     * @return the resolver's name
     */
    @Contract(pure = true)
    default String name() {
        return getClass().getSimpleName();
    }
}
