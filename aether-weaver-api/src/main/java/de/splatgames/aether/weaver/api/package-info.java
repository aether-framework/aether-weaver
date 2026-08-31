/**
 * The annotations a weave is written with, and the front door to the rest of the published API.
 *
 * <p>A weave is an ordinary Java class that describes a change to another class. It is compiled like
 * any other class, it is never instantiated, and it is read as bytes rather than loaded: what it
 * declares is applied to its targets by a weaver, either while a build writes class files or while a
 * JVM loads them. Everything in this package is a way of writing one of those descriptions down.
 *
 * <p>Every type here but two is a declaration the weaver reads rather than something a program calls.
 * The two are {@link Woven}, the record the weaver stamps onto a class it changed, and
 * {@link WovenInfo}, which reads that record and is the supported way to ask a class at run time what
 * was done to it.
 *
 * <h2>The shape of a weave</h2>
 *
 * <p>{@link Weave} is what makes a class a weave, and it names the classes the weave changes. Without
 * it a class is an ordinary class and every other annotation on it is dead text. With it, three
 * groups of declarations become meaningful.
 *
 * <ul>
 *   <li><b>Handlers</b> — a method carrying {@link Inject}, {@link Redirect} or {@link Wrap}. Each
 *       names a target method with a selector, names positions inside it with {@link At}, and says
 *       what happens there.
 *   <li><b>Members</b> — a field or method carrying {@link Shadow}, {@link Unique}, {@link Accessor}
 *       or {@link Invoker}, or carrying none of them. What becomes of each is decided by
 *       {@link Weave#kind()}: an instance weave is dissolved into each target and its members are
 *       copied there, while a static weave stays a class of its own and merges nothing.
 *   <li><b>Accounting</b> — {@link Group} on the weave class, together with the {@code require},
 *       {@code allow} and {@code group} elements of the handler annotations, states how many
 *       positions the weave expects to match and fails the build when the target has moved.
 * </ul>
 *
 * <h2>What each handler annotation does at the position it matched</h2>
 *
 * <p>The three are not variations on one another; the difference is what happens to the instruction
 * that was matched.
 *
 * <table border="1">
 *   <caption>The three handler annotations</caption>
 *   <tr><th>Annotation</th><th>The matched instruction</th><th>The handler receives</th>
 *       <th>The handler returns</th></tr>
 *   <tr><td>{@link Inject}</td><td>still runs; a call is added beside it</td>
 *       <td>a prefix of the target method's arguments, optionally a callback, optionally captured
 *           locals</td>
 *       <td>{@code void}; {@code AW1041} otherwise</td></tr>
 *   <tr><td>{@link Redirect}</td><td>is replaced and never happens</td>
 *       <td>the operation's own inputs, then optionally a prefix of the enclosing method's
 *           arguments</td>
 *       <td>what the operation produced</td></tr>
 *   <tr><td>{@link Wrap}</td><td>is handed over as an
 *       {@link de.splatgames.aether.weaver.api.callback.Operation} the handler may perform, repeat or
 *       skip</td>
 *       <td>exactly the operation's own inputs, then the
 *           {@link de.splatgames.aether.weaver.api.callback.Operation}</td>
 *       <td>what the target goes on with, which need not be what the operation produced</td></tr>
 * </table>
 *
 * <p>{@link Redirect} and {@link Wrap} stand in for an operation, so they may only name a point that
 * <em>is</em> one: {@link Point#INVOKE}, {@link Point#FIELD} or {@link Point#NEW}. Any other point
 * names a place in the instruction sequence with nothing to take over, and is reported as
 * {@code AW1061}; use {@link Inject} there, or point at the call, field access or instantiation that
 * was meant.
 *
 * <h2>Saying where</h2>
 *
 * <p>Two pieces of text decide what a declaration attaches to, and they are read by different
 * grammars.
 *
 * <p>The <b>target method</b> — {@link Inject#method()} and its equivalents — is a selector, parsed by
 * {@link de.splatgames.aether.weaver.api.select.MemberSelector}. It must resolve to exactly one method
 * of the target that has a body: none is {@code AW1020}, several is {@code AW1021}, and a method with
 * no body is {@code AW1023} when abstract, {@code AW1025} when native and {@code AW1024} when
 * compiler-generated. An inherited method is not a declared one and has to be woven where it is
 * declared.
 *
 * <p>The <b>position inside it</b> is an {@link At}, which names a {@link Point}, optionally what that
 * point matches against, which of the matches to take, and how far to move from it. {@link Slice}
 * narrows the search to a region of the body first, and an {@link At#ordinal()} then counts inside
 * that region rather than inside the method — which is the interaction that surprises most often. The
 * order the elements take effect in is given on {@link At}.
 *
 * <h2>Reaching the target's own members</h2>
 *
 * <ul>
 *   <li>{@link Shadow} declares that the target already has a member, so the weave's own source can
 *       name it. Nothing is added to the target; the reference is rebound to the target's member when
 *       the weave is dissolved. A member the target does not declare is {@code AW1030} for a field and
 *       {@code AW1020} for a method.
 *   <li>{@link Accessor} and {@link Invoker} generate a method <em>onto</em> the target that reads or
 *       writes one of its fields, or calls one of its methods. Both are declarations rather than
 *       implementations, and the usual spelling is an {@code abstract} method on an {@code abstract}
 *       weave class.
 *   <li>{@link Unique} lets a merged member be renamed rather than refused when the target already
 *       uses its name; without it the collision is {@code AW1080}.
 * </ul>
 *
 * <p>All four depend on the weave being dissolved into its target. {@link Shadow} in a
 * {@code @Weave(kind = Kind.STATIC)} weave is {@code AW1090} and {@link Unique} there is
 * {@code AW1091}; both discard the whole weave rather than the offending member.
 *
 * <h2>What a handler may take beyond the target's arguments</h2>
 *
 * <p>A handler's parameter list is read as consecutive runs and in one order only: the captured result
 * of the matched call where the handler asks for it with {@link Result}, then a prefix of the target
 * method's own arguments, then at most one
 * {@link de.splatgames.aether.weaver.api.callback.Callback} or
 * {@link de.splatgames.aether.weaver.api.callback.ReturnableCallback}, then every {@link Local}
 * capture. Anything out of that order is {@code AW1040}. The callback and the carriers a mutable
 * capture uses live in {@link de.splatgames.aether.weaver.api.callback}.
 *
 * <h2>Rules that hold across the whole package</h2>
 *
 * <p><b>Most declaration rules are checked twice.</b> The annotation processor sees source and can put
 * a caret on the offending element; the engine sees class files and is the only stage that runs for a
 * weave compiled elsewhere. Where only one of the two can check a rule, the annotation that carries it
 * says which.
 *
 * <p><b>Where two declarations meet at one place, {@link Weave#priority()} decides.</b> They are
 * sorted by priority descending, then by weave class name, then by handler name, then by handler
 * descriptor. Those four do not distinguish every pair — two {@link Inject} annotations on one handler
 * compare equal by all of them — so the sort is a preorder rather than a total order; it is stable, so
 * declarations that compare equal keep the order they were read in and two builds of the same inputs
 * agree. Injected calls are emitted in that order and wraps nest in it, the highest priority ending up
 * outermost.
 *
 * <p><b>An omitted {@code require} is not {@code require = 0}.</b> A class file records only the
 * elements that were written, so the engine can tell the two apart: written nowhere, it reads as one
 * match, and a declaration that matches nothing is {@code AW1043}; written as {@code 0}, the
 * declaration is deliberately optional. An {@code allow} of {@code 0} imposes no upper bound rather
 * than permitting none, and exceeding a bound that was set is {@code AW1044}.
 *
 * <p><b>A declaration naming a {@link Group} is accounted through it.</b> Its own {@code require} and
 * {@code allow} are then not checked at all, and the group's combined total is checked instead.
 *
 * <h2>What is not here</h2>
 *
 * <p>A weave describes changes to the bodies and the member set of a class. It does not change the
 * class's place in the type hierarchy: a weave class extends {@link Object} and nothing else
 * ({@code AW1006}), implements no interface ({@code AW1084}), declares no type parameters
 * ({@code AW1007}), no constructor ({@code AW1081}) and no static initialiser ({@code AW1082}). Only
 * one shape of field initialiser survives into the class file at all — a {@code ConstantValue} on a
 * {@code static final} field of constant type — and a merged field of that shape arrives with the
 * JVM's default value rather than its initialiser, reported as {@code AW1093}; every other
 * initialiser is compiled into a constructor or a static initialiser and is caught there instead, as
 * {@code AW1081} or {@code AW1082}.
 *
 * <h2>The eight packages</h2>
 *
 * <p>This one is where a weave is written. The other seven are what a caller reaches for once it needs
 * more than the annotations.
 *
 * <ul>
 *   <li><b>{@link de.splatgames.aether.weaver.api.callback}</b> — the objects a handler declares as
 *       parameters and the engine constructs for it: the callbacks that end a target method early, the
 *       carriers that let a handler write one of the target's local variables, and the
 *       {@link de.splatgames.aether.weaver.api.callback.Operation} a {@link Wrap} handler performs.
 *   <li><b>{@link de.splatgames.aether.weaver.api.diagnostic}</b> — the vocabulary of a failing build.
 *       Every {@code AW####} named above is a constant of
 *       {@link de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode}, and every message is a
 *       {@link de.splatgames.aether.weaver.api.diagnostic.Diagnostic}.
 *   <li><b>{@link de.splatgames.aether.weaver.api.select}</b> — the grammar behind every piece of
 *       selector text this package accepts, and the parsed forms it produces.
 *   <li><b>{@link de.splatgames.aether.weaver.api.model}</b> — the parsed form of the declarations
 *       above, after a weave class has been read and before anything has been matched against a
 *       target.
 *   <li><b>{@link de.splatgames.aether.weaver.api.manifest}</b> — the JSON document an artefact
 *       carries so that its weaves can be found without scanning every class on the classpath.
 *   <li><b>{@link de.splatgames.aether.weaver.api.spi}</b> — the contract a plugin implements to add
 *       injection points, injector kinds and observers, together with the read-only views the engine
 *       hands such a plugin.
 *   <li><b>{@link de.splatgames.aether.weaver.api.experimental}</b> — extension members contributed to
 *       types the declaring code does not own. Every type there carries
 *       {@code @ApiStatus.Experimental} and is expected to be rewritten between versions.
 * </ul>
 *
 * <p>They compose in one direction. A weave written with the annotations here is parsed into
 * {@link de.splatgames.aether.weaver.api.model} types, whose selector text has been read by
 * {@link de.splatgames.aether.weaver.api.select}; the built-in discovery finds those weave classes
 * through a {@link de.splatgames.aether.weaver.api.manifest} document; the engine resolves and emits
 * through the
 * {@link de.splatgames.aether.weaver.api.spi} contracts; handlers receive
 * {@link de.splatgames.aether.weaver.api.callback} objects at run time; and anything that goes wrong
 * along the way is reported as a {@link de.splatgames.aether.weaver.api.diagnostic.Diagnostic}.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * @Weave(value = Ledger.class, priority = 100, tags = {"audit"})
 * public final class LedgerAudit {
 *
 *     // Ledger declares: private java.math.BigDecimal balance;
 *     @Shadow
 *     private java.math.BigDecimal balance;
 *
 *     // Merged into Ledger, under a mangled name if Ledger already has one called `charges`.
 *     @Unique
 *     private int charges;
 *
 *     // Called at the start of charge(BigDecimal), before the target's own code.
 *     @Inject(method = "charge(java.math.BigDecimal)", at = @At(Point.HEAD), require = 1)
 *     private void onCharge(java.math.BigDecimal amount) {
 *         this.charges++;
 *         Audit.log(this.balance, amount);
 *     }
 *
 *     // Replaces the gateway.send(payment) call inside charge, which then never happens.
 *     @Redirect(method = "charge(java.math.BigDecimal)",
 *               at = @At(value = Point.INVOKE, target = "Gateway.send"),
 *               require = 1)
 *     private static Receipt insteadOfSend(Gateway gateway, Payment payment) {
 *         return Receipt.deferred(payment);
 *     }
 * }
 * }</pre>
 *
 * <p>Afterwards, {@code WovenInfo.of(Ledger.class)} answers with the record the weaver stamped onto
 * the class — which weaver, which plan, and which weave classes contributed — or with an empty
 * {@link java.util.Optional} when the weaver was configured to write none.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
package de.splatgames.aether.weaver.api;
