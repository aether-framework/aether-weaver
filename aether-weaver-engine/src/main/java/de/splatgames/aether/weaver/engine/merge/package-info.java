/**
 * Dissolves an instance weave into a target class: copies the members it declares, generates the
 * accessors and invokers it asks for, and rebinds every reference the moved code makes to the weave
 * it came from.
 *
 * <p>This is the half of weaving that changes what a class <em>declares</em>. Its counterpart,
 * {@link de.splatgames.aether.weaver.engine.inject}, changes what a method <em>does</em>; each of the
 * three injectors the engine ships reports that it changes no class member.
 * {@link de.splatgames.aether.weaver.engine.Weaver} runs the injections first and this package
 * second, over the class the injections produced.
 *
 * <p>The two refusals are not answered alike, and the difference is visible only from the weaver's
 * side. A refusal from the injection pipeline abandons the class outright. A refusal from
 * {@link de.splatgames.aether.weaver.engine.merge.StructuralWeaver#apply} is a {@code null}, and so is
 * having nothing to emit — the two cannot be told apart from the return value — so a class the
 * injections already changed stands with those injections in it, and only a class nothing else changed
 * is handed back untouched. Every reason for the refusal has been reported first.
 *
 * <p>Which weaves dissolve into which class is not decided here. The planner decides it and records
 * it, and {@link de.splatgames.aether.weaver.engine.plan.WeavePlan#structuralFor(String)} is what the
 * weaver asks; {@link de.splatgames.aether.weaver.engine.merge.StructuralWeaver#apply} is handed the
 * answer.
 *
 * <h2>The weave's class file is read a second time</h2>
 *
 * <p>{@link de.splatgames.aether.weaver.engine.model.WeaveClass} carries declarations and no bodies,
 * so a member that has a body — a merged member, or a handler the weave declares itself — can only
 * come from the weave's own class file. {@link de.splatgames.aether.weaver.engine.merge.WeaveBytes}
 * supplies it, keyed by the {@link java.lang.constant.ClassDesc} a parsed weave names itself with, and
 * defaults to {@link de.splatgames.aether.weaver.engine.merge.WeaveBytes#NONE}. A weave that needs a
 * body and has no source for it is refused as {@code AW1096}. An accessor and an invoker need none:
 * both are generated from the declaration's shape alone, which is what keeps a weave of nothing but
 * those from having to supply a byte source.
 *
 * <h2>Binding before writing</h2>
 *
 * <p>Every weave is resolved against the target before a byte is written. {@code MemberBindings}
 * works out, for each declared member and each handler the weave owns, the name it will be emitted or
 * called under and — for a method — the opcode a call to it must carry, and it keeps going after the
 * first failure so that one run tells a weave everything that is wrong with it. If any weave fails to
 * bind, the rebuild does not start; what becomes of the class then is the caller's decision.
 *
 * <p>The opcode is worth stating twice because the two cases differ. A shadowed member's opcode comes
 * from the target's own declaration, never from how the weave wrote the call, since the weave compiled
 * against its own idea of the member. A merged member's comes from the flags the weave declared,
 * because those are the flags it will carry once emitted.
 *
 * <p>The checks an accessor or an invoker needs run later, while the class is being written, and a
 * refusal there costs only the member it names.
 *
 * <h2>What each declaration does to the target, and what it reports</h2>
 *
 * <ul>
 *   <li><b>Merged.</b> Copied onto the target under its own name where that name is free. A collision
 *       — a field on its name alone, a method on name and descriptor — is {@code AW1080} and the
 *       member does not bind, unless the declaration is {@code @Unique}, in which case the member
 *       takes a suffix derived from the weave's binary name and {@code AW1094} says so unless silence
 *       was asked for. An instance field merged into a record is {@code AW1088} and refused; into an
 *       enum it is {@code AW1089} and allowed. A {@code ConstantValue} on a merged field is not
 *       carried over.
 *   <li><b>Shadowed.</b> Never copied — it is a promise about the target, and the promise is checked.
 *       A field the target does not declare is {@code AW1030} and a method is {@code AW1020}; a field
 *       of another type is {@code AW1031}. {@code mutable} is honoured only where there is something
 *       to honour: a target field that really is final is rewritten without that flag and
 *       {@code AW1033} reports it, and a field that was never final costs nothing and says nothing.
 *   <li><b>Accessor.</b> A getter or a setter generated onto the target. Refused as {@code AW1030}
 *       when the field is not declared, {@code AW1031} when the descriptor is neither a read nor a
 *       write of that field's type, {@code AW1097} when a setter would write a field the target
 *       declares final, and {@code AW1095} when the name and descriptor are already taken.
 *   <li><b>Invoker.</b> A method generated onto the target that makes the same call from inside the
 *       class, so the descriptor must be the target method's exactly; a miss is {@code AW1020} and a
 *       taken name and descriptor is {@code AW1095}.
 * </ul>
 *
 * <p>A handler the weave declares itself is bound like a member but cannot be renamed: the injection
 * sites call it by name, so a collision with something the target already declares is {@code AW1080}
 * with no {@code @Unique} escape.
 *
 * <h2>Rebinding a moved body</h2>
 *
 * <p>A copied method arrives still speaking of the weave — reading the weave's fields, calling the
 * weave's methods, naming a shadowed member under the weave's own spelling. {@code MergedBodyTransform}
 * re-emits each such instruction against the target under its resolved name and opcode, and only then
 * does {@code de.splatgames.aether.weaver.engine.internal.transform.ClassRemapper} rewrite the weave
 * type itself into the target. The order is forced: the first matches on the owner still being the
 * weave type, and would match nothing once the second had run.
 *
 * <h2>Rebuild, not edit</h2>
 *
 * <p>The target is copied element by element into a fresh class and the contributions follow, so
 * nothing the target had can be overwritten by what is added — a collision was already refused. The
 * one element that is not copied verbatim is a field whose final flag a mutable shadow asked to drop.
 * A weave admitted only because it asks for such a shadow can turn out to have nothing to do, and the
 * rebuild is then skipped rather than spending a parse and an emit to produce the bytes it started
 * with.
 *
 * <p>{@code TargetMembers} indexes the target's own declarations once per class being woven, since
 * every binding, every generated member and every refusal message resolves a name through it. Only
 * declarations are indexed: an inherited member is not a declared one, and walking the hierarchy would
 * mean loading classes from inside class loading.
 *
 * <h2>What is not here</h2>
 *
 * <p>Collisions <em>between</em> weaves are found before any class is read, by
 * {@link de.splatgames.aether.weaver.engine.plan.ConflictDetector}, which reports {@code AW1080} of
 * its own from the declarations alone. Nothing in this package touches a method body other than one it
 * is moving, and nothing here writes the stamp.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
package de.splatgames.aether.weaver.engine.merge;
