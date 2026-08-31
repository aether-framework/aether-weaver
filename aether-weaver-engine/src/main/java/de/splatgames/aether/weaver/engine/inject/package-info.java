/**
 * Rewrites method bodies: the stage that turns a plan for one class into woven bytes.
 *
 * <p>{@link de.splatgames.aether.weaver.engine.inject.WeavingPipeline} is the way in, and
 * {@link de.splatgames.aether.weaver.engine.Weaver} calls it with the plan entries that name the class
 * being woven. Everything else in this package either implements one kind of rewrite, decides whether
 * a declaration may be rewritten at all, or works out what has to be pushed onto the stack before a
 * handler can be called. What lands where inside a body is not decided here but in
 * {@link de.splatgames.aether.weaver.engine.inject.point}; what a class <em>declares</em> is not
 * changed here but in {@link de.splatgames.aether.weaver.engine.merge}. Each of the three injectors
 * the engine ships reports that it changes no class member.
 *
 * <h2>Two phases, and why the split is forced</h2>
 *
 * <p>An injection point answers with indices into the body as it was read, and those indices are all
 * an emitter has to recognise its own positions by. Resolution, validation and argument binding
 * therefore have to finish before the first instruction is written; once the rewrite begins, an index
 * identifies nothing that can be looked up again. That single constraint is why the pipeline resolves
 * every declaration first and rewrites once at the end, and why
 * {@link de.splatgames.aether.weaver.engine.explain.SiteObserver} is told what matched during
 * resolution rather than asked afterwards.
 *
 * <h2>What is per declaration, and what is per class</h2>
 *
 * <p>Each entry is taken as far as it can go on its own — its target method is found, its points are
 * resolved, its injector validates it, its handler is bound at every position it matched — and an
 * entry that fails any of those is dropped while the class is still woven from whatever survives.
 *
 * <p>Two things are settled for the class as a whole, because neither is answerable from one
 * declaration. {@code DelegationChains} warns as {@code AW1027} when one weave attached to two
 * constructors of a class where one calls the other, since a single {@code new} then calls its handler
 * twice for one object; detection is per weave class, because two weaves each attaching to one link
 * are two handlers each called once. And
 * {@link de.splatgames.aether.weaver.engine.inject.MatchAccounting} checks a declaration's match
 * count against its own bounds — {@code AW1043} below {@code require}, {@code AW1044} above a
 * non-zero {@code allow} — only when it belongs to no group; a declaration naming a group instead
 * contributes its count to that group's total and is never checked against its own bounds. A group's
 * total is checked against the group's own bounds in turn, but only for a group present among the
 * declarations passed in — a declaration naming a group that is absent there is accounted nowhere at
 * all, and raises neither code. When accounting refuses, the pipeline returns nothing and the class is
 * left exactly as it arrived rather than woven without that injection.
 *
 * <p>The outcome is all or nothing for a different reason than it might appear: the rewrite builds
 * into a fresh class, so abandoning it costs only the work and there is never a half-woven target to
 * hand back.
 *
 * <h2>The three kinds</h2>
 *
 * <ul>
 *   <li>{@link de.splatgames.aether.weaver.engine.inject.InjectInjector} adds. It answers
 *       {@code KEEP} for every element it is offered and writes the handler call in front of it, so
 *       the target's own instructions stay — though a {@code REPLACE} from another emitter at the same
 *       position still removes the element, since one {@code KEEP} does not outvote it. A handler that
 *       returns anything is {@code AW1041}: the value would have nowhere to go. A handler that
 *       captures the preceding call's result at a position that does not follow a call, or follows one
 *       returning {@code void}, is {@code AW1104}, and one such position abandons the declaration.
 *   <li>{@link de.splatgames.aether.weaver.engine.inject.RedirectInjector} substitutes. The matched
 *       operation is replaced by a call to the handler, which never sees the original; the operands
 *       the operation was about to consume are already on the stack in the order the handler declares
 *       them, which is why the handler has to begin with the operation's own inputs — the receiver
 *       first for an instance operation — and why one that does not is {@code AW1040}. The enclosing
 *       method's parameters may follow them.
 *   <li>{@link de.splatgames.aether.weaver.engine.inject.WrapInjector} substitutes and hands the
 *       operation over as a value. That value is a dynamic constant, which is also what makes several
 *       wraps at one position nest instead of colliding: two calls cannot stand where one set of
 *       operands does, so only the first declaration at a shared position emits and the handlers
 *       behind it travel as static arguments of its constant, innermost first.
 * </ul>
 *
 * <p>All three report {@code AW1005} against a handler they cannot call, and the three conditions are
 * not the same. An injection and a redirect accept an instance handler when the class the call will
 * name is the target itself, which happens only for a weave that dissolves into its target and so
 * makes the handler one of the target's own methods; the test is on the owner the call will name
 * rather than on the handler's declaring class, because those two differ in exactly that case. A wrap
 * accepts no instance handler at all, since an inner level of a nest is reached through the operation
 * and carries no receiver.
 *
 * <p>A redirect and a wrap additionally need a position that <em>is</em> an operation. Each checks the
 * point twice: at validation, where a built-in point naming a bare position is {@code AW1061} and a
 * contributed point is not checked at all, and again while emitting, where a matched position holding
 * no operation is {@code AW1061} and abandons the declaration. Neither accepts a shift of any kind,
 * reported as {@code AW1102}. A wrap's handler must end with the operation parameter: a handler that
 * declares parameters whose last is not the operation is {@code AW1062}, and one that declares no
 * operation parameter anywhere is {@code AW1063} — a handler with parameters and no operation trips
 * both and is reported under both.
 *
 * <h2>Registration</h2>
 *
 * <p>{@link de.splatgames.aether.weaver.engine.inject.CorePlugin} contributes those three injectors,
 * and the built-in injection points, as an ordinary
 * {@link de.splatgames.aether.weaver.api.spi.WeaverPlugin}. It differs from a third party's plugin in
 * its namespace: it claims the built-in one, which no discovered plugin may take, so {@code HEAD} and
 * {@code inject} are written unqualified while a contributed identifier carries its owner's prefix.
 * The pipeline reaches every injector through the SPI, built-in or not, so a gap in the SPI is visible
 * from inside the engine rather than papered over by calling a wider concrete method.
 *
 * <h2>Calling a handler</h2>
 *
 * <p>What has to be on the stack is worked out once per distinct operand count and shared across the
 * positions that agree, which is what keeps one unusable handler from reporting the same fault once
 * per position. Captured locals are the exception: a slot's occupant depends on where in the body the
 * injection lands, because a compiler reuses a slot once a scope ends, so {@code LocalCaptures}
 * resolves them per position and a declaration failing at three of its positions says so three times,
 * each naming what was live at that one. It refuses rather than guesses — every strategy other than an
 * explicit slot needs the target's local variable table, and a wrong slot reads a different value of a
 * compatible type instead of failing — a target with no such table is {@code AW1052}, a capture that
 * resolves to nothing usable at the position is {@code AW1050}, and one that leaves several equally
 * good candidates is {@code AW1051}. {@code LocalRefs} recognises the carrier a mutable capture is
 * declared through, by descriptor and therefore erased, which is why a carrier's type argument plays
 * no part in resolving anything; {@code mutable} on a parameter that is not a carrier is
 * {@code AW1053}, and a carrier parameter without {@code mutable} is {@code AW1054}.
 *
 * <p>{@code CallbackEmission} writes the longer form: a callback object has to be reachable both as an
 * argument to the handler and again after the handler has returned, and an operand stack consumed by
 * an {@code invoke} is not a place anything can wait, so it is constructed into a local before the
 * handler's operands are pushed. {@code Assignability} decides whether a value may stand where a
 * parameter was declared, and is exact for primitives and permissive for references — it is handed two
 * descriptors and nothing else, so the real subtype question cannot be asked here at all.
 *
 * <h2>Exception ranges</h2>
 *
 * <p>Code inserted inside a target's {@code try} would make the target's own {@code catch} answer for a
 * failure that did not come from the target, which is invisible wherever the target catches broadly.
 * {@code ProtectedRanges} works out how the exception table has to be re-cut so that injected code sits
 * outside it, and the cut is reported as {@code AW1131} rather than refused: the weave is correct, and
 * what changed is which exceptions the target observes.
 *
 * <h2>Refusals that belong to the target rather than the declaration</h2>
 *
 * <p>A method with no body cannot be injected into and says which kind of nothing it has:
 * {@code AW1025} for a native method, whose implementation is not a class file at all; {@code AW1023}
 * for an abstract one; {@code AW1024} for a compiler-generated one, which would weave but whose shape
 * is not the author's to rely on. A selector matching no method of the target is {@code AW1020} with
 * every method listed, and one matching several is {@code AW1021}.
 *
 * <p>The writer can refuse too, and only while writing: {@code AW4003} where a method no longer fits
 * the class file's 65535-byte limit on one method's code, and {@code AW4004} for any other refusal,
 * which asks for the class file rather than for a change to the weave. A plan entry whose injector
 * kind resolves to nothing in the registry is {@code AW4090} and drops that entry alone.
 *
 * <h2>What else is here</h2>
 *
 * <p>{@code RedirectedOperation} reduces the three redirectable bytecode shapes — a call, a field
 * access, an instantiation — to one description, so that a redirect and a wrap can ask the same
 * question of a matched position; its inputs are in stack order rather than descriptor order, which is
 * exactly where the three shapes stop looking alike.
 * {@link de.splatgames.aether.weaver.engine.inject.RedirectShapes} publishes that reading in a form a
 * caller outside this package can hold, without the method handle and the input list that only
 * emission needs; the engine's own weaving path does not go through it.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
package de.splatgames.aether.weaver.engine.inject;
