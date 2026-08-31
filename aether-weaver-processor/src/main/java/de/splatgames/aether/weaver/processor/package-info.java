/**
 * The annotation processor: what a compilation settles about a weave before any bytes are written.
 *
 * <p>Two jobs, independent of each other. One is to report the mistakes in a weave that can be found without running
 * the weaver — a target that does not resolve, a handler whose signature cannot be called, a member that will not
 * merge, an injection point that matches no instruction — so that a build fails at the declaration rather than when
 * the weaver runs. The other is to write the weave manifest, the resource that tells everything after this
 * compilation which classes declare a weave and which contribute an extension.
 *
 * <p>Neither gates the other for the weave class's own manifest entry: it is built and added unconditionally, whether
 * or not the pass reported anything about that class. An individual declaration is not the same story — a
 * contribution or an injection that was refused with an error is dropped before it reaches the manifest, and only
 * what survived checking is recorded for it.
 *
 * <h2>How a compilation reaches this package</h2>
 *
 * <p>{@link de.splatgames.aether.weaver.processor.WeaveProcessor} is named in
 * {@code META-INF/services/javax.annotation.processing.Processor}, so a project that puts this module on its
 * annotation processor path runs it without configuring anything else. It is registered for two annotations,
 * {@link de.splatgames.aether.weaver.api.Weave} and {@link de.splatgames.aether.weaver.api.experimental.Extension},
 * and claims neither: every call answers {@code false}, leaving both visible to every other processor in the round.
 * The supported source version is {@link javax.lang.model.SourceVersion#latestSupported()}, so no newer compiler
 * warns about a fixed one.
 *
 * <p>Every round but the last checks; the last round writes and does nothing else. The manifest is emitted only
 * where {@link javax.annotation.processing.RoundEnvironment#processingOver()} holds, because a
 * {@link javax.annotation.processing.Filer} refuses to reopen a resource it has created and writing once per round
 * throws the moment another processor generates a source file.
 *
 * <h2>The pieces</h2>
 *
 * <p>{@link de.splatgames.aether.weaver.processor.WeaveProcessor} drives the pass and reports the weave class's own
 * shape; every other check lives in a class of its own. {@code SourceTargets} resolves what a {@code @Weave} names,
 * {@code SelectorChecks} parses the {@code method} selector, {@code HandlerChecks} checks a handler on its own and
 * against the method it names, {@code MemberChecks} takes everything a weave declares that is not a handler,
 * {@code TargetBytes} finds a target's compiled class file, and {@code PointChecks} resolves injection points
 * against it. {@code SourceSpecs} builds an {@link de.splatgames.aether.weaver.api.model.InjectorSpec} out of an
 * injection annotation and {@code SourceMembers} reduces the weave's other members to the entries
 * {@link de.splatgames.aether.weaver.api.manifest.WeaveManifest} records; {@code ManifestEmitter} collects those and
 * writes them, and {@code ExtensionChecks} is the whole of the extension side.
 *
 * <p>Three small types serve all of them. {@link de.splatgames.aether.weaver.processor.Anchors} reads annotations as
 * mirrors, {@link de.splatgames.aether.weaver.processor.Anchor} names the place in the source a message is printed
 * against, and {@link de.splatgames.aether.weaver.processor.MessagerReporter} turns a diagnostic into the text a
 * build log shows.
 *
 * <h2>What one weave class goes through</h2>
 *
 * <p>In this order. Everything true of the weave whatever it targets happens first and once, which is what keeps a
 * weave with three targets from being told three times that it declares a constructor.
 *
 * <ol>
 *   <li><b>How the targets were declared.</b> Both forms written is {@code AW1002}; neither is {@code AW1001}.
 *   <li><b>The weave class's own shape</b> — its superclass, its interfaces, its type parameters and whether it is
 *       final.
 *   <li><b>Its methods</b>, for a written constructor and for an instance handler in a static weave.
 *   <li><b>Its members</b>, for the merge-related annotations a static weave cannot honour, for a field initialiser
 *       that will not survive, and for merging a method the platform calls on its own.
 *   <li><b>Each injection declaration on its own.</b> One pass per injection annotation, a repeated
 *       {@code @Inject} being flattened back out of the {@code @Inject.Container} the compiler rewrote it into, so a
 *       handler with one annotation and a handler with several are the same thing to every later stage. The
 *       handler's signature is checked here and its {@code method} selector parsed. A selector that does not parse
 *       costs its own declaration and nothing else: it is reported, dropped, never checked against a target and
 *       never recorded.
 *   <li><b>The targets are resolved</b>, class literals first in source order and then names.
 *   <li><b>Each target in turn</b>, for the weave's members, then every surviving injection's handler in source
 *       order, then every surviving injection's points against the target's compiled bytes, again in source order —
 *       two full passes over the injections rather than one pass interleaving handler and points per declaration.
 *   <li><b>The manifest entry is built</b>, last and unconditionally.
 * </ol>
 *
 * <p>A check that runs per target reports per target, which is the point — whether a target declares the method a
 * selector names is a fact about that target, and saying it once would leave the author guessing which one. The
 * handler checks run once per injection declaration per target as well, so a method carrying two {@code @Inject}
 * annotations that fails the accessibility check is told so twice for each target. {@code TargetBytes} is the one
 * exception: it caches a target's parsed class file, success or failure alike, so {@code AW1200} is reported at most
 * once per target however many weaves name it, rather than once per weave.
 *
 * <h2>What the checks can see</h2>
 *
 * <p>Everything but the injection points is answered from the compiler's element model, and every lookup a weave's
 * members are checked against considers declared members only. {@code javax.lang.model} exposes inherited members
 * separately and none of those lookups asks for them, so a {@code @Shadow}, an {@code @Accessor} or an
 * {@code @Invoker} naming something the target inherits rather than declares is reported as missing. The extension
 * side is deliberately the opposite: a contribution collides with whatever a call site would resolve to, so its
 * collision lookups read {@link javax.lang.model.util.Elements#getAllMembers(javax.lang.model.element.TypeElement)}
 * and count an inherited member as a collision.
 *
 * <p>Annotations are read as mirrors rather than through {@code Element.getAnnotation}, which cannot be used for an
 * annotation type that may be absent from the compile classpath and which answers a {@code Class}-typed element by
 * throwing {@link javax.lang.model.type.MirroredTypeException} instead of returning it. The consequence runs through
 * everything here: an {@link javax.lang.model.element.AnnotationMirror} holds only the elements the source spelled
 * out, so an omitted element is invisible whatever default the annotation declares, and each reader supplies the
 * fallback itself.
 *
 * <p>Injection points cannot be answered from the element model, a point naming an instruction and the model having
 * none, so that one check reads the target's class file off {@link javax.tools.StandardLocation#CLASS_PATH}. The
 * bytes are there only for a target compiled before this round: a target compiled from source alongside the weave
 * has no class file yet, because annotation processing runs ahead of code generation. Every way of not finding them
 * ends the same way, in one {@code AW1200} note and no point checking for that target; the result is cached, so the
 * note arrives once per target however many weaves name it. A local or anonymous target is skipped without even
 * that note, and a weave declaring no injections never reaches for a class file at all.
 *
 * <h2>What this package reports</h2>
 *
 * <p>Every code below is an error, and fails the compilation, unless it is marked as a warning or a note. A warning
 * reaches {@code javac} as a warning and fails a build run with {@code -Werror}; a note leaves the compilation
 * succeeding.
 *
 * <p><b>The weave class.</b>
 *
 * <ul>
 *   <li>{@code AW1001} — the weave declares no target. Give the annotation a class literal,
 *       {@code @Weave(Session.class)}, or a name where the target is not on the compile classpath,
 *       {@code @Weave(targets = "com.acme.Session")}.
 *   <li>{@code AW1002} — the weave declares its targets both ways. Keep the class literals and delete the
 *       {@code targets} element, or the other way round. Reporting it ends that check, so {@code AW1001} never
 *       joins it, and both forms are still resolved and checked afterwards.
 *   <li>{@code AW1006} — the weave extends something other than {@link java.lang.Object}. A weave's members are
 *       copied into its target, which has a superclass of its own; reach the superclass's members through
 *       {@code @Shadow}.
 *   <li>{@code AW1084} — the weave implements an interface. Adding an interface to a target is not a 0.1.0
 *       capability.
 *   <li>{@code AW1007} — the weave is generic. Its members are copied verbatim into the target, where a type
 *       variable has nothing to bind to.
 *   <li>{@code AW1008} <i>(warning)</i> — the weave is a class that is neither {@code abstract} nor {@code final}.
 *       Declare it final. An {@code abstract} weave is exempt, and so is anything that is not a class.
 *   <li>{@code AW1081} — the weave declares a constructor, which cannot be merged because the target has its own.
 *       Initialise merged state from an {@code @Inject} at the target constructor's {@code Point.HEAD}. A
 *       constructor the compiler supplied is not reported; each one the source wrote is reported separately.
 * </ul>
 *
 * <p><b>Targets.</b>
 *
 * <ul>
 *   <li>{@code AW1004} — a named target is not on the compile classpath, and is dropped. Check the spelling, or
 *       declare {@code require = Require.OPTIONAL} where the target is deliberately absent at compile time.
 *       {@link de.splatgames.aether.weaver.api.Require#OPTIONAL} suppresses that one diagnostic and nothing else:
 *       the target is dropped either way, and one that does resolve is checked exactly as a required target is.
 *   <li>{@code AW1009} <i>(note)</i> — a named target that does resolve. A class literal is checked by the
 *       compiler, follows a rename and survives a move between packages. Reported whatever {@code require} says.
 *   <li>{@code AW1087} — a target that is itself a weave, dropped. A weave class is dissolved into its own target
 *       and never loaded as itself; target the class the other weave targets and order the two with
 *       {@code priority}.
 *   <li>{@code AW1200} <i>(note)</i> — the target's class file could not be read, so its injection points were not
 *       checked here. Nothing needs doing: this is the ordinary case for a target compiled in the same round, and
 *       the points are checked again at weave time.
 * </ul>
 *
 * <p><b>Selectors.</b>
 *
 * <ul>
 *   <li>{@code AW1015} — the selector is empty, or the parser refused it. A blank one is refused here without
 *       reaching the parser; anything else carries the parser's own message, and its suggestion where it offers
 *       one.
 *   <li>{@code AW1016} <i>(note)</i> — the selector carries type arguments, which are ignored because selectors
 *       match erased signatures. Parsing continues past it.
 *   <li>{@code AW1017}, {@code AW1018}, {@code AW1019} — the parser's codes for a descriptor written without its
 *       {@code desc:} prefix, a malformed descriptor and a {@code desc:} selector missing its return type. They are
 *       reported under the parser's code rather than one chosen here, so a selector cannot be accepted at compile
 *       time and refused at weave time with a different explanation.
 *   <li>{@code AW1010} — the selector's explicit owner is not on the compile classpath. An owner written without a
 *       dot is a simple name, which cannot be resolved without the file's imports, and is accepted unchecked.
 *   <li>{@code AW1020} — the target declares no method matching the selector, the diagnostic listing the ones it
 *       does declare. An inherited method is not a declared one.
 *   <li>{@code AW1021} — a plain name matched several methods, which is an overload the author did not know about.
 *       Add the parameter types.
 *   <li>{@code AW1022} — a {@code *} matched several methods and {@code allow} is not a positive number, an omitted
 *       {@code allow} and one written {@code 0} being alike here. Set {@code allow} to the number matched, so that
 *       matching a different number later is caught rather than woven silently.
 * </ul>
 *
 * <p><b>The target method, and the handler against it.</b>
 *
 * <ul>
 *   <li>{@code AW1025} — the target method is {@code native}, so its implementation is not a class file. Inject into
 *       the Java method that calls it, or use {@code @Redirect} at the call site.
 *   <li>{@code AW1023} — the target method is {@code abstract} and has no body. Name an implementing method.
 *   <li>{@code AW1042} — the call woven into the target could not reach the handler. Checked only for a weave
 *       declaring {@code kind = Kind.STATIC}, which is never merged, so the call is an ordinary cross-class
 *       invocation subject to ordinary access rules. The handler is reachable when it is not {@code private} and
 *       either it and its weave class are both {@code public} or the weave and the target share a package; anything
 *       else is refused. A package-private handler beside its target is therefore accepted, and a {@code public}
 *       handler in a weave class that is not public, in another package, is not. Make the handler and its class
 *       public, or declare the weave {@code @Weave(kind = Kind.INSTANCE)} so that it moves into the target.
 *   <li>{@code AW1041} — an {@code @Inject} handler returns something. The injected call is a statement in the
 *       middle of the target's own code; to change what the target returns, take a {@code ReturnableCallback}.
 *   <li>{@code AW1040} — an {@code @Inject} handler's parameters are not a prefix of the target's arguments, either
 *       because there are too many or because one is at the wrong erased type. Fewer than the target has is not a
 *       failure. Three kinds of parameter are set aside before the comparison: a leading one of the target's own
 *       type, dropped where the handler is {@code static} and the target method is not; a {@code @Local} capture;
 *       and a callback. {@code @Redirect} and {@code @Wrap} handlers are not subject to this rule.
 *   <li>{@code AW1071} — a {@code ReturnableCallback}'s type argument does not match what the target returns, boxed.
 *       A raw declaration, and a target returning {@code void}, pass unchecked.
 *   <li>{@code AW1053} — {@code @Local(mutable = true)} on a parameter that is not a carrier type. A Java parameter
 *       is passed by value, so the assignment would change the handler's own copy; declare it {@code LocalRef<T>},
 *       or {@code LocalIntRef} and its siblings for a primitive.
 *   <li>{@code AW1054} — a carrier-typed {@code @Local} that did not ask to write. Add {@code mutable = true}, or
 *       declare the parameter as the variable's own type where the handler only reads it.
 *   <li>{@code AW1061} — a {@code @Redirect} or {@code @Wrap} names a point that is not an operation. Only
 *       {@code INVOKE}, {@code FIELD} and {@code NEW} name one; an {@code @At} whose {@code value} was omitted means
 *       {@code Point.HEAD} and is exactly as wrong as a wrong one.
 *   <li>{@code AW1005} — a handler that is not {@code static}, from two different checks. One is any handler in a
 *       weave declaring {@code kind = Kind.STATIC}, which is never merged and so has no instance to be called on;
 *       the other is any {@code @Wrap} handler in a weave of any kind, because a wrap can end up nested inside
 *       another weave's wrap and an inner level is reached through {@code Operation.call}, which carries the
 *       operation's own arguments and no receiver. A non-static {@code @Wrap} handler in a static weave trips both
 *       and reports the code twice, with a different remedy each time.
 *   <li>{@code AW1063} — a {@code @Wrap} handler declares no {@code Operation} parameter, which is a
 *       {@code @Redirect} wearing the wrong annotation. This report returns, so such a handler never also reports
 *       {@code AW1062}.
 *   <li>{@code AW1062} — a {@code @Wrap} handler declares parameters after its {@code Operation}. Such a handler
 *       works as the outermost wrap and fails as soon as another weave nests inside it.
 * </ul>
 *
 * <p><b>The members a weave contributes.</b>
 *
 * <ul>
 *   <li>{@code AW1090} and {@code AW1091} — {@code @Shadow} and {@code @Unique} on a member of a static weave,
 *       which is never merged, so a declaration whose whole meaning is a binding inside the target has nothing to
 *       bind to. A member reported this way is not examined further at declaration time, but is still checked
 *       against each target, so both diagnostics reach the reader.
 *   <li>{@code AW1032} <i>(warning)</i> — a {@code @Shadow} field with an initialiser, whose value is never written
 *       anywhere. Delete it.
 *   <li>{@code AW1093} <i>(note)</i> — a merged field with an initialiser, which is copied into the target holding
 *       the JVM's default value because the initialising code belongs to a constructor a weave does not have. Write
 *       the value from an {@code @Inject} at the target constructor's {@code Point.HEAD}. Both this and
 *       {@code AW1032} see only an initialiser
 *       {@link javax.lang.model.element.VariableElement#getConstantValue()} reports, which is a constant variable in
 *       the JLS sense; a field initialised any other way is dropped just as silently and is not reported.
 *   <li>{@code AW1083} <i>(warning)</i> — a merged method takes over {@code toString()}, {@code equals(Object)},
 *       {@code hashCode()} or {@code main(String[])}. Matching is on the whole erased signature, so an overload
 *       sharing only the name is left alone.
 *   <li>{@code AW1030} — the target declares no field of that name, for a {@code @Shadow} to bind to or an
 *       {@code @Accessor} to expose.
 *   <li>{@code AW1031} — a {@code @Shadow} field's erased type differs from the target's.
 *   <li>{@code AW1033} <i>(warning)</i> — {@code @Shadow(mutable = true)} removes {@code final} from the target's
 *       own field. Nothing needs doing; the cost is that removing {@code final} is a structural change, and a
 *       structural change is unavailable under retransformation.
 *   <li>{@code AW1020} — the target declares no method of that name and erased parameter types, for a
 *       {@code @Shadow} to bind to or an {@code @Invoker} to call.
 *   <li>{@code AW1080} — a merged member the target already declares, by name for a field and by erased signature
 *       for a method. Declare it {@code @Unique} to have it renamed, or rename it; overwriting the target's own
 *       member is not offered. A member already {@code @Unique} is exempt.
 *   <li>{@code AW1088} — an instance field merged into a record, whose {@code equals}, {@code hashCode},
 *       {@code toString} and accessors are all derived from its components and would ignore the added state.
 *       {@code @Unique} does not exempt it: renaming changes the member, not the shape of the target.
 *   <li>{@code AW1089} <i>(warning)</i> — an instance field merged into an enum, whose constants are already
 *       constructed by the time anything could assign to it.
 *   <li>{@code AW1095} — the target already declares the signature an {@code @Accessor} or {@code @Invoker} would
 *       be generated under. The signature compared is the declaration's own, not the member it names, so an invoker
 *       called {@code run()} collides with a target's {@code run()} however well it resolves. Rename it; a
 *       generated member cannot be {@code @Unique}, because callers reach it by the name it is declared under.
 *   <li>{@code AW1097} — an {@code @Accessor} would write a field the target declares {@code final}. The woven class
 *       verifies and loads, and throws {@link java.lang.IllegalAccessError} the first time the setter is called.
 *       Use {@code @Shadow(mutable = true)}, which removes the flag deliberately and says so.
 * </ul>
 *
 * <p><b>Extensions.</b> An extension holder is checked and collected in one pass, and a contribution reporting an
 * error is left out of the manifest while one reporting a warning is kept. A method that is not {@code public} is
 * the holder's own helper and is passed over in silence, and a field with no {@code @Receiver} is the holder's own
 * state.
 *
 * <ul>
 *   <li>{@code AW1300} <i>(warning)</i> — the holder is not {@code final}. It goes on to contribute.
 *   <li>{@code AW1306} — the holder declares type parameters, which have nothing to bind to at a call site that
 *       resolves contributions by descriptor. The holder contributes nothing at all.
 *   <li>{@code AW1307} — the holder has a superclass other than {@link java.lang.Object} or implements something.
 *       The holder contributes nothing at all. The generic test runs first, so a generic holder with a supertype
 *       reports only {@code AW1306}.
 *   <li>{@code AW1301} — a contributed method is not {@code static}. Declare it static, or private if it is a
 *       helper.
 *   <li>{@code AW1310} — a contributed method declares its own type parameters, so inference at the call site would
 *       differ from what the declaration says.
 *   <li>{@code AW1313} — {@code @Receiver} on the method and on a parameter. The two forms mean different things —
 *       a static contribution to a type, and an instance method on its values — so keep one.
 *   <li>{@code AW1302} — the method marks no {@code @Receiver} and the holder names none for the whole class.
 *   <li>{@code AW1316} — the holder names a class-level receiver and the method does not take that type first. A
 *       class-level receiver makes parameter zero the receiver by position; a method taking no parameters at all is
 *       refused the same way, and nothing is inferred from the type.
 *   <li>{@code AW1303} — {@code @Receiver} on a parameter other than the first. The rewrite passes the receiver
 *       through as argument zero, which is where the JVM has already put it.
 *   <li>{@code AW1304} — the receiver is not a declared type. Its default, {@code void}, reads this way too.
 *   <li>{@code AW1311} — the receiver is parameterised, and erasure is all the call site has. Name the raw type.
 *   <li>{@code AW1305} — the receiver already answers to that name and descriptor, or to that constant's name, so
 *       {@code javac} would resolve to the real member and the contribution would never be reached. Inherited
 *       members count.
 *   <li>{@code AW1312} <i>(warning)</i> — the receiver is {@link java.lang.Object}, so every expression in every
 *       module reading the extension would offer it. Contributed anyway.
 *   <li>{@code AW1314} — a {@code @Receiver} field is not {@code public static final}.
 *   <li>{@code AW1315} — a {@code nulls} policy on a form that has no receiver value to check, which is
 *       {@code @Receiver} on a method or on a field. Only a parameter's {@code @Receiver} honours it.
 *   <li>{@code AW1308} — one holder contributes the same receiver, name and descriptor twice, which two overloads
 *       erasing alike will do. Rename one. A field whose key is already taken is dropped without a diagnostic, and
 *       methods are keyed before fields. The same code is reported again, for a different collision, by
 *       {@link de.splatgames.aether.weaver.engine.extension.ExtensionIndex} when two holders contribute one call.
 * </ul>
 *
 * <p><b>Injection points.</b> No check in this package raises these. They come from
 * {@link de.splatgames.aether.weaver.engine.inject.point.PointResolver}, from the point it dispatched to, or from
 * {@link de.splatgames.aether.weaver.engine.inject.MatchAccounting}, and reach the compiler through a reporter that
 * adds a position: {@code AW1101} for a point identifier that is not registered, {@code AW1043} for a point that
 * matched nothing or was given a target it forbids or denied one it requires, {@code AW1102} for a shift the point
 * refuses, {@code AW1110} for an ordinal past the last match, {@code AW1111} for a shift that leaves the range it
 * was found in, {@code AW1112} <i>(warning)</i> for a large {@code shift = BY}, {@code AW1103} <i>(note)</i> for a
 * selector that also matched something reached through an {@code invokedynamic}, and {@code AW1044} for exceeding a
 * non-zero {@code allow}. Sites that resolve but may not be injected at are dropped one at a time, with
 * {@code AW1026}, {@code AW1105} or {@code AW1130} <i>(warning)</i>, and — for a declaration standing in for an
 * operation — with {@code AW1061}, the same code the handler check above reports for a point that names no
 * operation at all. Everything arriving this way is anchored on the {@code method} selector literal, whatever
 * element the fault is really in, because a handler carrying two points needs a position that says which injection
 * failed.
 *
 * <p><b>The manifest.</b> {@code AW2300} is reported when the resource cannot be created or written, and again by
 * {@link de.splatgames.aether.weaver.api.manifest.ManifestReader} for a manifest already on disk that does not
 * parse; {@code AW2301} for one from a schema this release does not read. Neither reading failure stops the write:
 * the fresh manifest goes out without the unreadable file's entries, which is why the diagnostic asks for a rebuild.
 *
 * <h2>Where a compile-time answer differs from the weaver's</h2>
 *
 * <p>{@code PointChecks} is the only class here that touches the engine, and it does so to run the weaver's own
 * resolver rather than a second one: a refusal at compile time is ordinarily the refusal the weaver would raise,
 * worded the same way and under the same code. Five things break that equivalence, and each is a place where a
 * build and a run can disagree.
 *
 * <ul>
 *   <li><b>Slices are not carried into the specification.</b> Every point therefore searches the whole method here
 *       and an ordinal is counted over the whole method, while at weave time it is counted within the slice. A
 *       declaration combining a slice with an ordinal is resolved here against a position the weaver need not
 *       agree with, and the slice diagnostics {@code AW1120}, {@code AW1121} and {@code AW1122} cannot arise at
 *       compile time at all.
 *   <li><b>An omitted {@code require} reads as {@code 0}.</b> The mirror records only what the source wrote, so a
 *       declaration that left {@code require} out is never reported as {@code AW1043} by the accounting here, even
 *       though the weaver's own reading of the same annotation turns it into one match. {@code AW1044} is checked
 *       as usual.
 *   <li><b>A declaration naming a {@code group} is not accounted here at all.</b> The group's total is a fact about
 *       the whole weave, and the accounting sees one declaration.
 *   <li><b>A {@code @Wrap} is built as an inject.</b> Its specification carries
 *       {@code InjectorKind.INJECT} rather than {@code WRAP}, and the resolver's site-safety check branches on that
 *       kind, so a wrap is subject here to the checks an {@code @Inject} is subject to — {@code AW1026},
 *       {@code AW1105} and {@code AW1130} — and to the operation check, {@code AW1061}, at weave time instead.
 *   <li><b>Only the built-in points are registered.</b> The resolver is handed
 *       {@link de.splatgames.aether.weaver.engine.inject.point.BuiltInPoints#all()} and nothing else, so an
 *       {@code @At(custom = "namespace:NAME")} naming a point a plugin contributes is unknown here and is refused as
 *       {@code AW1101}, which is an error, whether or not that plugin is on the runtime classpath.
 * </ul>
 *
 * <p>Two more divergences sit outside the point checks. A weave that declared its targets both ways has both forms
 * resolved here and is checked and recorded against each, while the engine's own reading gives such a weave no
 * targets. And a declaration whose {@code at} array is empty yields no specification: it is silently left out of
 * the manifest and its points are not checked, and the first sign of it is the weaver refusing it as
 * {@code AW1043}.
 *
 * <h2>What the manifest holds</h2>
 *
 * <p>Entries accumulate across the compilation and are written once, to
 * {@link de.splatgames.aether.weaver.api.manifest.WeaveManifest#RESOURCE} — {@code META-INF/aether/weaves.json} —
 * under {@link javax.tools.StandardLocation#CLASS_OUTPUT}. A compilation that saw no weave and no extension holder
 * writes nothing, so the absence of the file stays a usable signal that a module was compiled without this
 * processor.
 *
 * <p>Writing merges over whatever the output directory already holds rather than replacing it. An incremental build
 * recompiles a subset of the sources, sees only that subset, and would otherwise drop every weave it did not visit —
 * the failure that works after a clean build and not afterwards. Merging is by class name for a weave and by holder
 * for an extension, so a recompiled class replaces its own entry and leaves the rest alone.
 *
 * <p>The two kinds of entry are not read the same way afterwards. A weave entry is a name to look up: a driver
 * finds the class through it and then parses that class's own compiled bytes, so what the entry records about an
 * injector describes the declaration rather than driving the weaving. An extension entry is the description itself,
 * being what {@link de.splatgames.aether.weaver.engine.extension.ExtensionIndex} is built from.
 *
 * <p>Three things reach the file that a reader may not expect. A weave class's own entry is written whether or not
 * the pass reported anything about that class, unlike an individual contribution or injection, which an error
 * refusal drops before it reaches the manifest. An {@code @Extension} holder that contributes nothing is still
 * registered, so a compilation whose only extension was refused still writes a manifest. And an injector's
 * identifier is {@code "unnamed"} where the source wrote none, while
 * {@link de.splatgames.aether.weaver.engine.parse.WeaveClassParser} derives one from the handler's description and
 * kind, so one declaration is named one thing in the manifest and another in a weave-time diagnostic.
 *
 * <h2>What this package does not do</h2>
 *
 * <ul>
 *   <li><b>It weaves nothing.</b> A target's class file is read and never written, and the only file this package
 *       creates is the manifest.
 *   <li><b>It does not decide whether the build fails.</b> That follows from the severity a
 *       {@link de.splatgames.aether.weaver.api.diagnostic.DiagnosticCode} declares, no check here overriding one:
 *       {@link de.splatgames.aether.weaver.api.diagnostic.Severity#ERROR} becomes a compiler error,
 *       {@link de.splatgames.aether.weaver.api.diagnostic.Severity#WARNING} a compiler warning,
 *       {@link de.splatgames.aether.weaver.api.diagnostic.Severity#INFO} a note and
 *       {@link de.splatgames.aether.weaver.api.diagnostic.Severity#DEBUG} an {@code OTHER} that {@code javac} prints
 *       exactly as it prints a note. The error count the reporter keeps is read by nothing here; in particular the
 *       manifest is written whether or not the compilation reported errors.
 *   <li><b>It does not parse an {@code @At} target.</b> That text is carried through the specification as the author
 *       wrote it and parsed by the injection point that uses it, so a malformed one is not reported at this stage.
 *   <li><b>It does not model a handler's local captures.</b> A {@code @Local} declaration is checked for whether its
 *       type agrees with its mutability, and is then left out of the specification along with the slices, so a
 *       compile-time check sees the declaration as unsliced and with no local bindings.
 *   <li><b>It claims no annotation.</b> Both stay visible to every other processor in the round, and an annotated
 *       element that is not a type is skipped rather than misread.
 * </ul>
 *
 * <h2>Threading, and what one instance is</h2>
 *
 * <p>Most of the package holds no state to share in the first place: nine of the fifteen types are static-only
 * utility classes whose constructors throw {@link java.lang.AssertionError}, and
 * {@link de.splatgames.aether.weaver.processor.Anchor} is an immutable record of three references whose one method
 * delegates to the caller's own {@link javax.annotation.processing.Messager}. What is not safe to share is
 * {@link de.splatgames.aether.weaver.processor.WeaveProcessor} itself and the two collaborators it holds mutable
 * state through, {@code TargetBytes} and {@code ManifestEmitter}, plus the error count
 * {@link de.splatgames.aether.weaver.processor.MessagerReporter} keeps. One processor instance belongs to one
 * compilation task, which the host compiler drives from a single thread: {@code init} is {@code synchronized} only
 * because
 * {@link javax.annotation.processing.AbstractProcessor} declares it so, and the three fields it sets are then read
 * without synchronisation. The class-file cache and the manifest collector accumulate state across the rounds of
 * that one compilation, which is what keeps {@code AW1200} to one note per target and lets the manifest be written
 * once at the end.
 *
 * @author Erik Pförtner
 * @since 0.1.0
 */
package de.splatgames.aether.weaver.processor;
