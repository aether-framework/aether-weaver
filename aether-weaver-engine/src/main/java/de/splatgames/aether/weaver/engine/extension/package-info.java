/**
 * Makes a method that lives in one class callable as though it were declared on another.
 *
 * <p>An extension is a static method whose first parameter is the receiver, held in a class of its
 * own and declared in a weave manifest. What the author writes at the call site is an ordinary member
 * call on the receiver type. Nothing in the JVM makes that work, and this package is the whole of what
 * does: the receiver is patched at compile time so that {@code javac} accepts the call, and the call
 * {@code javac} emitted is repointed at the implementation before the class runs.
 *
 * <p>Four classes, and each belongs to a different moment.
 *
 * <ul>
 *   <li>{@link de.splatgames.aether.weaver.engine.extension.ExtensionIndex} decides what exists and
 *       which call reaches which implementation. Everything else asks it.
 *   <li>{@link de.splatgames.aether.weaver.engine.extension.ExtensionStubs} patches a receiver class
 *       with a declaration per extension, so that a compilation against the patched class compiles.
 *       Used by the build, not by weaving.
 *   <li>{@link de.splatgames.aether.weaver.engine.extension.ExtensionCalls} rewrites the call sites:
 *       an {@code invokevirtual} or {@code invokeinterface} on the receiver becomes an
 *       {@code invokestatic} on the holder with the receiver as its first argument, a
 *       {@code getstatic} of a contributed constant is repointed at the holder's field, and a method
 *       handle among an {@code invokedynamic}'s bootstrap arguments is rebound the same way, which is
 *       what carries a method reference to an extension.
 *   <li>{@link de.splatgames.aether.weaver.engine.extension.ExtensionGuards} puts the null check back.
 *       A call on {@code null} does not throw where an ordinary member call would — it enters the
 *       implementation and fails later and somewhere else — so a declaration whose receiver is
 *       {@code CHECKED} gets a branch-free prologue at the entry of the implementation, which is what
 *       leaves the method's existing stack map frames valid.
 * </ul>
 *
 * <h2>Stubs are a compile-time artefact and nothing else</h2>
 *
 * <p>A patched receiver exists to be compiled against. Its generated bodies throw
 * {@link java.lang.UnsupportedOperationException} with a message saying so, because a stub that
 * reached a runtime classpath would shadow the real class and every extension call would fail there
 * instead of being rewritten. Nothing is added for a member the receiver already declares, so patching
 * an already patched class adds nothing rather than declaring it twice.
 *
 * <h2>Where the rewrite runs</h2>
 *
 * <p>{@link de.splatgames.aether.weaver.engine.Weaver} applies both rewrites to a class the plan
 * mentions nowhere, and this is the one thing that takes its fast path away: with an extension in
 * force, such a class is still fetched, parsed and examined. For a class the plan does name, the
 * extension pass runs last, behind the shape check, the policy decision, the idempotence gate and the
 * pipeline's own refusal — any of which can end processing of that class before the extension pass is
 * reached. A woven target is rewritten in the same pass rather than in a second sweep, since the class is
 * already in hand and a sweep over only the classes the plan missed would leave every woven target
 * unrewritten. Neither rewrite counts as weaving: a class changed only this way is counted as seen and
 * as nothing else.
 *
 * <p>Both rewrites are written to decline cheaply, because every class an agent loads passes through
 * them. An empty index answers before the class is parsed, and a constant pool that holds no name and
 * descriptor the index knows answers before a transform is built.
 *
 * <h2>Resolving a call</h2>
 *
 * <p>A call may name a subtype of the type an extension was declared on, so answering it means reading
 * the receiver's hierarchy, which means reading class files while classes are being loaded. Two things
 * follow. Both answers are cached, keyed by the query and by internal name, and neither cache can hold
 * a {@code null} — an unresolved query is an empty {@link java.util.Optional} and an unreadable class
 * is a sentinel. And where part of the hierarchy cannot be read the answer is "no extension" rather
 * than a partial one: a call left alone fails loudly at run time rather than quietly doing something
 * else. A call naming exactly a declared receiver never reaches the walk and is answered with no
 * hierarchy at all.
 *
 * <p>A real member found on the way up beats an extension, because {@code javac} resolved the call to
 * that member and rewriting it would redirect a call that was already correct.
 *
 * <h2>What is reported</h2>
 *
 * <p>Two codes, both raised while the index is built and both dropping the offending declaration
 * rather than failing the run:
 *
 * <ul>
 *   <li>{@code AW1308} where two holders contribute the same call. Both would rewrite the same
 *       instruction and the winner would be whichever manifest the classpath yielded last.
 *   <li>{@code AW1309} where the receiver, or something it inherits from, already declares a method of
 *       that name and descriptor, so the extension is never reached. Only methods are consulted for
 *       this: a field of that name does not shadow an extension.
 * </ul>
 *
 * <h2>What is not here</h2>
 *
 * <p>Nothing in this package checks whether an extension is well formed. Whether the holder is final,
 * whether the method is static, whether it declares a receiver and declares it first, whether either
 * is generic — all of that is checked at compile time, by the annotation processor, against the source
 * that declares it; the two codes above are the only ones raised here. {@code AW1308} is raised in
 * both places for different collisions: the processor reports one holder contributing two methods that
 * erase to the same descriptor, and this package reports two holders contributing the same call.
 *
 * <p>Nor does this package read a manifest. The declarations arrive as parsed records, and a caller
 * supplies the source that receiver class files are read from.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
package de.splatgames.aether.weaver.engine.extension;
