/**
 * What a weave class declares, as the engine understands it once the annotations have been read.
 *
 * <p>Three types carry the whole model. A
 * {@link de.splatgames.aether.weaver.engine.model.WeaveClass} is one {@code @Weave} class: the
 * targets it names, its kind and priority, the tags and groups it declares, the members and the
 * injections it holds, and where the declaration was found. A
 * {@link de.splatgames.aether.weaver.engine.model.TargetRef} is one of those targets together with
 * the spelling its declaration used. A
 * {@link de.splatgames.aether.weaver.engine.model.WeaveMember} is one declared member, in one of the
 * four shapes the weaver acts on — merged, shadowed, accessor, invoker. Three of the four also refer
 * to something in the target and carry that name as a component of their own; a merged member has no
 * target of its own to refer to.
 *
 * <p>The values are produced by {@code de.splatgames.aether.weaver.engine.parse.WeaveClassParser} and
 * are read by everything downstream: {@link de.splatgames.aether.weaver.engine.plan.WeavePlanner}
 * flattens them into plan entries, {@link de.splatgames.aether.weaver.engine.merge.StructuralWeaver}
 * dissolves them into a target, and {@link de.splatgames.aether.weaver.engine.explain.ExplainReport}
 * lists them.
 *
 * <h2>Declarations, and no code</h2>
 *
 * <p>This is the property the rest of the engine is built around. A
 * {@link de.splatgames.aether.weaver.engine.model.WeaveMember} names a member's type and flags and
 * never its body, and nothing here holds a {@link java.lang.classfile.ClassModel}. A stage that has
 * to move a body therefore reads the weave's class file a second time, through
 * {@link de.splatgames.aether.weaver.engine.merge.WeaveBytes}, and reports {@code AW1096} when
 * nothing supplies it. An accessor and an invoker are the exception that proves the shape: both are
 * generated onto the target from the declaration alone, so a weave of nothing but those needs no
 * class file at all.
 *
 * <h2>Immutable, and checked on construction</h2>
 *
 * <p>Every collection is copied when the record is built, so a weave handed to
 * {@link de.splatgames.aether.weaver.engine.Weaver} cannot change underneath the plan built from it.
 * Two invariants are enforced there rather than assumed later: a weave declares at least one target,
 * and no two of its groups share a name — the second because
 * {@link de.splatgames.aether.weaver.engine.model.WeaveClass#groupNamed(String)} would otherwise
 * answer with whichever was declared first and silently ignore the other. A member name is checked
 * for being non-blank, and a member's type for being a {@link java.lang.constant.ClassDesc} for a
 * field or a {@link java.lang.constant.MethodTypeDesc} for a method, which is what makes the casts
 * every later stage performs safe.
 *
 * <h2>What is not decided here</h2>
 *
 * <p>Nothing in this package reads a target class file, so nothing here knows whether a shadow's
 * promise holds, whether a merged name collides, or which weaves apply to a loading class. Those are
 * answered in {@link de.splatgames.aether.weaver.engine.merge} against the target, and in
 * {@link de.splatgames.aether.weaver.engine.plan} against the other declarations of the run.
 *
 * <p>Two things here are read by nothing else in the engine's main sources. One is
 * {@link de.splatgames.aether.weaver.engine.model.WeaveClass#require()}, which records what the
 * declaration asked for and is acted on nowhere. The other is
 * {@link de.splatgames.aether.weaver.engine.model.WeaveClass#isStructural()}: the planner asks a
 * related but different question for itself, with its own condition — an instance weave that
 * declares a member of its own or a handler of its own. Neither condition implies the other: an
 * instance weave with no injectors and a single non-mutable {@code @Shadow} field dissolves under
 * the planner's condition while {@code isStructural()} answers {@code false} for it. That condition,
 * not this one, decides which weaves dissolve.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
package de.splatgames.aether.weaver.engine.model;
