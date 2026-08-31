/**
 * Resolves a member selector against one class, and describes the members it could have meant.
 *
 * <p>A selector is text a weave author wrote. This package turns it into the members of a given class
 * that it names, using the class file as the only source of truth: only declared members, in the
 * order the class file lists them, with nothing inherited, since a class file lists no member it did
 * not declare.
 *
 * <p>Four types. {@link de.splatgames.aether.weaver.engine.select.ResolutionContext} is what a
 * selector is resolved against — the class being searched, plus the imports that give an unqualified
 * type name a meaning. {@link de.splatgames.aether.weaver.engine.select.DefaultSelectorResolver}
 * performs the match. {@link de.splatgames.aether.weaver.engine.select.MemberRef} is one result, with
 * everything a call site would need to reference it. {@link
 * de.splatgames.aether.weaver.engine.select.CandidateListing} turns a class's members into the lines
 * a diagnostic prints when the selector matched the wrong thing or nothing.
 *
 * <h2>How a match is decided</h2>
 *
 * <p>A part the selector omitted is not a constraint. A name alone matches every overload of that
 * name; {@code name()} matches only the one taking no arguments. A written parameter list matches on
 * exact arity and then position by position, so a prefix never matches. Types are compared by
 * descriptor equality after resolution, which makes the comparison exact and erased: no subtype
 * matches a supertype, and a type argument written in the selector is gone before the comparison
 * happens. {@code *} is the only pattern, and it stands for a whole name rather than part of one.
 *
 * <p>Two answers are deliberate and easy to misread. An unresolvable type name matches nothing rather
 * than everything, because treating it as a wildcard would silently bind a weave to a member its
 * author never named. And a constant selector resolves to no member at all, because a constant is an
 * instruction operand rather than a declared member.
 *
 * <p>An owner written into the selector is never read here: the class searched is the one the context
 * carries, so a selector naming some other class still matches this one's members. Deciding whether
 * an owner was appropriate belongs to whoever built the context.
 *
 * <h2>Imports</h2>
 *
 * <p>The imports are supplied by the caller rather than read from anywhere, because the class file a
 * weave was compiled to no longer records what its source imported. An import wins over everything,
 * so a weave may name a type whose simple name collides with a {@code java.lang} one; an unqualified
 * name with no import is tried against {@code java.lang} and, if no such class exists on the running
 * JVM, is unresolvable rather than resolved to a type that is not there. A qualified name outside
 * {@code java.lang} is taken at face value and is checked against no class path.
 *
 * <h2>Its place in the engine</h2>
 *
 * <p>This is not the matcher the weaving path uses, and the distinction matters when a selector
 * behaves differently in two places. A declaration's target method is resolved by
 * {@link de.splatgames.aether.weaver.engine.inject.WeavingPipeline} through
 * {@link de.splatgames.aether.weaver.engine.inject.point.Targets}, and an {@code @At} target is
 * matched by that same class, which compares an unresolved name by its rendered source name and
 * treats a simple owner as matching any package. Matching here is by resolved type identity instead.
 * A selector can therefore match under one and not the other.
 *
 * <p>No other class in the engine's main sources names any of these four types.
 * {@link de.splatgames.aether.weaver.engine.select.DefaultSelectorResolver} implements
 * {@link de.splatgames.aether.weaver.api.spi.SelectorResolver}, which declares only a name and a
 * priority and no resolution method, so the resolution entry points are declared on the class and are
 * reached through it. Resolvers a plugin contributes are collected into
 * {@link de.splatgames.aether.weaver.engine.plugin.PluginRegistry} and are asked nothing by the
 * engine.
 *
 * <h2>Candidate listings</h2>
 *
 * <p>{@link de.splatgames.aether.weaver.engine.select.CandidateListing} orders by Levenshtein distance
 * from the requested name, case folded, so a one-character typo puts the intended member first, and
 * caps the listing at {@link de.splatgames.aether.weaver.engine.select.CandidateListing#MAX_ENTRIES}
 * with a final line counting the rest. It renders each candidate in the spelling the selector was
 * written in, which is what lets an author compare their line against the listing directly. An empty
 * candidate list produces one line saying so rather than none.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
package de.splatgames.aether.weaver.engine.select;
