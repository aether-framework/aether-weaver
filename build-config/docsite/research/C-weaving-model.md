# C-weaving-model — The weaving model

Anchors are `file:line` against the working tree. Paths are relative to the repository root.
Abbreviations used below:

- `API/` = `aether-weaver-api/src/main/java/de/splatgames/aether/weaver/api/`
- `ENG/` = `aether-weaver-engine/src/main/java/de/splatgames/aether/weaver/engine/`
- `ENGT/` = `aether-weaver-engine/src/test/java/de/splatgames/aether/weaver/engine/`
- `PROC/` = `aether-weaver-processor/src/main/java/de/splatgames/aether/weaver/processor/`
- `AGENT/` = `aether-weaver-agent/src/main/java/de/splatgames/aether/weaver/agent/`

## Facts

### What a weave class is

- **`@Weave` is `@Documented`, `RUNTIME`-retained and applies to a type only.** — `API/Weave.java:179-182`
- **A weave class is modelled as `WeaveClass`, a record of `weaveType, targets, kind, priority, require, phase, tags, groups, members, injectors, origin`.** — `ENG/model/WeaveClass.java:48-58`
- **`WeaveClass` copies every collection in its canonical constructor, so the plan cannot be changed underneath.** — `ENG/model/WeaveClass.java:75-79`
- **Two invariants are enforced at construction: at least one target, and no two groups of one name; both throw `IllegalArgumentException`.** — `ENG/model/WeaveClass.java:80-88`
- **The model carries declarations and no code: `WeaveMember` names a member's type and flags and never a body.** — `ENG/model/WeaveMember.java:286-297`, `ENG/model/package-info.java:133-142`
- **A member is one of exactly four shapes — `Merged`, `Shadowed`, `Accessor`, `Invoker` — as a sealed interface.** — `ENG/model/WeaveMember.java:302`, `362`, `396`, `432`, `478`
- **`WeaveMember.type()` is a `ClassDesc` for a field and a `MethodTypeDesc` for a method; anything else is rejected at construction with `IllegalArgumentException`.** — `ENG/model/WeaveMember.java:506-517`
- **A member name and, where present, `targetName`/`targetField`/`targetMethod` must be non-blank.** — `ENG/model/WeaveMember.java:509-511`, `410-413`, `443-446`, `489-492`
- **A `TargetRef` refuses a primitive or array descriptor: `type.isClassOrInterface()` or `IllegalArgumentException`.** — `ENG/model/TargetRef.java:211-217`
- **`TargetRef` records the spelling used (`declaredAsClassLiteral`), and the parser produces either literals or names for one weave and never both, so the flag is uniform over one weave's targets.** — `ENG/model/TargetRef.java:192-203`, `ENG/parse/WeaveClassParser.java:282-288`

### How a weave class is classified into members

- **The method ladder in the engine is: handler → `@Accessor` → `@Invoker` → `@Shadow` → `@Unique` → merged, first match wins.** — `ENG/parse/WeaveClassParser.java:498` (handler test), `533-535` (handler returns), `537-543`, `544-550`, `551-561`, `562-566`, `567-569`
- **A method is a handler when it carries any of `@Inject` (repeatable), `@Redirect` or `@Wrap`; a handler is never also modelled as a member.** — `ENG/parse/WeaveClassParser.java:494-498`, `533-535`
- **The field ladder is: `@Shadow` → (`@Unique` static-weave check) → merged. No accessor or invoker branch exists on the field path.** — `ENG/parse/WeaveClassParser.java:373-390`, `391-395`, `405-406`
- **`@Accessor` and `@Invoker` are `@Target(ElementType.METHOD)`; `@Shadow` and `@Unique` are `FIELD, METHOD`.** — `API/Accessor.java:91`, `API/Invoker.java:75`, `API/Shadow.java:101`, `API/Unique.java:99`
- **The annotation processor classifies in a different order: handler → `SHADOW` → `ACCESSOR` → `INVOKER` → `UNIQUE` → `MERGED`.** — `PROC/MemberChecks.java:826-838`, and the same order again in `PROC/SourceMembers.java:104-111`
- **`@Unique` is permission to rename, not a request: a free name is kept and only a collision mangles.** — `ENG/model/WeaveMember.java:349-352`, `ENG/merge/MemberBindings.java:358`
- **A merged field's `ConstantValue` is recognised by the attribute alone, no access flag is read, and it is `AW1093` (INFO) on a merged field and `AW1032` (WARNING) on a shadowed one.** — `ENG/parse/WeaveClassParser.java:371`, `379-385`, `396-404`
- **Merging `toString()`, `equals(Object)`, `hashCode()` or `main(String[])` is `AW1083`, matched on the whole name+descriptor so an overload is left alone.** — `ENG/parse/WeaveClassParser.java:110-114`, `579-591`

### What the weave class may not be

- **A superclass other than `Object` is `AW1006`; any interface is `AW1084` (only the first is named); a class type parameter is `AW1007`.** — `ENG/parse/WeaveClassParser.java:225-232`, `233-239`, `240-249`
- **Not being `final` is `AW1008` (WARNING), and is checked only when the class is neither `abstract` nor an interface.** — `ENG/parse/WeaveClassParser.java:253-260`
- **A declared constructor is `AW1081`, but only when `isImplicitConstructor` judges it written; a `<clinit>` is `AW1082` unconditionally. Both return before any annotation is read.** — `ENG/parse/WeaveClassParser.java:471-486`
- **Any error diagnostic discards the whole weave: `parse` returns `Optional.empty()` when `report.failed` or the target list is empty.** — `ENG/parse/WeaveClassParser.java:187-189`

### How a target is named and resolved

- **`value()` (class literals) and `targets()` (binary names) are mutually exclusive; both is `AW1002`, neither is `AW1001`, and either returns an empty target list, which alone discards the weave.** — `ENG/parse/WeaveClassParser.java:282-294`, `187-188`
- **A `targets()` string that `ClassDesc.of` refuses is `AW1004` and that one entry is skipped, so three bad names produce three diagnostics — but because `AW1004` is an error the weave is discarded whole anyway.** — `ENG/parse/WeaveClassParser.java:300-313`, `187-188`
- **The annotation processor reports `AW1004` for a `targets()` name not on the compile classpath, and suppresses exactly that diagnostic when `require = OPTIONAL`.** — `PROC/SourceTargets.java:107`, `113-125`
- **`AW1009` is reported for every resolvable `targets()` string whatever `require` says, and the resolved target is then kept.** — `PROC/SourceTargets.java:126-133`
- **The processor resolves both spellings even after `AW1002`, so a class named as a literal and again as a string is checked twice and recorded twice in the manifest — unlike the engine, which gives such a weave no targets at all.** — `PROC/SourceTargets.java:73-77`, `ENG/parse/WeaveClassParser.java:282-288`
- **A class literal whose value is not a declared type is dropped without a diagnostic.** — `PROC/SourceTargets.java:57-58`, `189-195`
- **At weave time a target is resolved by internal-name lookup in two prebuilt maps: `entriesFor(internalName)` for injections and `structuralFor(internalName)` for weaves that dissolve. Both default to `List.of()`.** — `ENG/plan/WeavePlan.java:118-121`, `151-154`
- **`WeaveClass.targets(String internalName)` compares internal names linearly and throws `NullPointerException` on `null`.** — `ENG/model/WeaveClass.java:171-179`
- **`plan.targets()` is the union of injection targets and dissolving-weave targets, so a class that is only merged into is named there without appearing in `entriesFor`.** — `ENG/plan/WeavePlan.java:156-170`

### What `@Require` does

- **`Require` has exactly two constants, `REQUIRED` and `OPTIONAL`; `Weave.require()` defaults to `REQUIRED`.** — `API/Require.java:399`, `415`, `API/Weave.java:257`
- **`Require` is read by the annotation processor's target resolution and by `StubsMojo` and by nothing else in the weaving path.** — `PROC/SourceTargets.java:107`, `aether-weaver-maven-plugin/src/main/java/de/splatgames/aether/weaver/maven/StubsMojo.java:367`; no reader in the engine (`WeaveClass.require()` recorded at `ENG/model/WeaveClass.java:52`, stated unread at `ENG/model/package-info.java:163-165`)
- **`OPTIONAL` affects only the string form; a class literal has already been resolved by the compiler.** — `API/Require.java:404-407`, `PROC/SourceTargets.java:100-105`
- **`OPTIONAL` does not save an unresolvable name from being dropped: the processor `continue`s past it either way, so its members and handlers are never checked against that target.** — `PROC/SourceTargets.java:114-124`

### What happens when a weave is applied

- **The engine applies injections first and structural merging second, over the class the injections produced.** — `ENG/Weaver.java:433-457`, `ENG/merge/package-info.java:6-10`
- **A weave dissolves when `kind == INSTANCE` and it declares any member or any handler of its own. That condition, in the planner, is what decides which weaves reach the merge stage.** — `ENG/plan/WeavePlanner.java:85-86`, `95-98`
- **A handler declared in another class is not the weave's to move: `handlersOf` filters by `weave.weaveType().equals(handler.owner())` and deduplicates by name+descriptor.** — `ENG/merge/MemberBindings.java:148-157`
- **The target is rebuilt into a fresh class, its own elements first and unchanged, then each weave's contributions.** — `ENG/merge/StructuralWeaver.java:106-121`
- **Within one weave, declared members are emitted in declaration order, and its handlers last.** — `ENG/merge/StructuralWeaver.java:261-278`
- **A `Shadowed` member is never copied into the target.** — `ENG/merge/StructuralWeaver.java:268-271`
- **A merged field is emitted with the declared type and the weave's own class-file flags, and nothing else of that field is carried over — the `ConstantValue` is deliberately dropped.** — `ENG/merge/StructuralWeaver.java:370-380`
- **A merged method is copied element by element; only the `CodeModel` goes through the rebinding transform, everything else (checked exceptions, signature, annotations) is copied untouched.** — `ENG/merge/StructuralWeaver.java:394-408`
- **Two transforms rebind a moved body, composed in a fixed order: `MergedBodyTransform` first (it matches on the owner still being the weave type), then `ClassRemapper` rewrites the weave type into the target.** — `ENG/merge/StructuralWeaver.java:347-349`, `ENG/merge/MergedBodyTransform.java:22-23`, `89-90`
- **An instruction naming the weave that the bindings do not know is re-emitted with its own opcode and name against the target; that is how a handler calling another handler, and a call to a generated accessor or invoker, survive.** — `ENG/merge/MergedBodyTransform.java:93-99`, `104-109`
- **A field access keeps its opcode across the move; only the owner and the name change.** — `ENG/merge/MergedBodyTransform.java:104-109`
- **The injected call names the target rather than the weave for a dissolving weave's own handler: `handlerOwner()` returns `target` when `dissolved && belongsToTheWeave()`.** — `ENG/plan/PlanEntry.java:104`, and the call is emitted against that owner at `ENG/inject/CallbackEmission.java:198`
- **A test asserts the woven target's bytes do not mention the weave class's internal name at all.** — `ENGT/merge/InstanceHandlerTest.java:78-87`
- **Weaving twice from the same inputs is byte-identical, and the `@Unique` mangling suffix is stable because it is a digest of the weave's binary name rather than a counter.** — `ENGT/merge/InstanceHandlerTest.java:89-94`, `ENGT/merge/StructuralWeaverTest.java:300-313` (test `manglingIsStable`), `ENG/merge/MemberBindings.java:540-565`

### Opcode selection — the rule with two halves

- **A merged member's call opcode comes from the flags the weave declared: static → `INVOKESTATIC`, private → `INVOKESPECIAL`, otherwise `INVOKEINTERFACE` on an interface target and `INVOKEVIRTUAL` otherwise.** — `ENG/merge/MemberBindings.java:438-447`
- **A shadowed member's opcode comes from the target's own resolved flags, never from how the weave wrote the call.** — `ENG/merge/MemberBindings.java:304-307`, `ENG/merge/TargetMembers.java:197-221`
- **A private target method is called with `INVOKESPECIAL` and not `INVOKEVIRTUAL`, because the latter happens to work inside a nestmate and dispatches to an override the moment the target is not one.** — `ENG/merge/TargetMembers.java:215-219`, test at `ENGT/merge/InstanceHandlerTest.java:108-117`
- **A handler's opcode is read from the handler's own declared flags, which are the flags it will carry on the target.** — `ENG/merge/MemberBindings.java:203-214`

### Binding, and what stops a rebuild

- **Binding is all or nothing per rebuild: every weave is resolved against the target before a byte is written, and one weave that cannot bind stops the rebuild before it starts writing.** — `ENG/merge/StructuralWeaver.java:42-46`, `189-197`
- **The `prepare` loop runs to the end after the first failure, so one run reports every weave that is wrong.** — `ENG/merge/StructuralWeaver.java:170-196`
- **`MemberBindings.of` likewise accumulates with `complete &=` rather than returning early.** — `ENG/merge/MemberBindings.java:118-134`
- **`TargetMembers` indexes only the target's own declarations, not inherited ones, and is built once per class being woven.** — `ENG/merge/TargetMembers.java:73-93`, `29-33`; the remedy text says why: resolving the hierarchy would mean loading classes from inside class loading — `ENG/merge/MemberBindings.java:298-300`
- **A field is indexed by name alone and a method by name+descriptor; where a class file declares one field name twice, the later declaration wins.** — `ENG/merge/TargetMembers.java:55-65`, `85-92`
- **`StructuralWeaver.apply` returns `null` both for a refusal and for having nothing to emit, and the two cannot be told apart from the return value.** — `ENG/merge/StructuralWeaver.java:66-81`, `91-93`, `101-104`
- **A weave admitted only for a mutable shadow that turns out to have no final field to unfinalise causes no rebuild at all.** — `ENG/merge/StructuralWeaver.java:95-104`, test at `ENGT/merge/StructuralWeaverTest.java:484-491`
- **A weave with only accessors and invokers needs no class file: `needsBodies` is true only for a `Merged` member or an own handler.** — `ENG/merge/StructuralWeaver.java:241-244`, test at `ENGT/merge/StructuralWeaverTest.java:198-208`
- **`WeaveBytes.NONE` is the default, and answers `null` for every weave type.** — `ENG/merge/WeaveBytes.java:35`, `ENG/WeaverBuilder.java:61`
- **`WeaveBytes.bytesOf` is asked once per dissolving weave per target woven, and the answer is parsed rather than cached by the caller.** — `ENG/merge/WeaveBytes.java:37-38`, `ENG/merge/StructuralWeaver.java:176` (inside the per-weave loop of a per-class `apply`)
- **`WeaveBytes.from(ClassSource)` maps an absent class to `null` and lets an `UncheckedIOException` from the source pass through; it also special-cases the default package, where `packageName()` is empty.** — `ENG/merge/WeaveBytes.java:62-72`
- **A member the weave's class file turns out not to declare is passed over in silence.** — `ENG/merge/StructuralWeaver.java:303-310`, `351-355`

### Diagnostic codes this model raises, and the path each is raised from

- **`AW1096` `WEAVE_BYTES_UNAVAILABLE` (ERROR) — raised in `StructuralWeaver.prepare` when `bytesOf` is `null` and the weave has a body to move.** — `ENG/merge/StructuralWeaver.java:177-187`, declared at `API/diagnostic/DiagnosticCode.java:1005`
- **`AW1080` `MERGED_MEMBER_COLLIDES` (ERROR) — raised from three distinct places: a handler colliding with the target's own member, a non-`@Unique` merged member colliding, and plan-time detection of two weaves claiming one member or one handler signature.** — `ENG/merge/MemberBindings.java:176-186`, `347-356`, `ENG/plan/ConflictDetector.java:311`, `235-248`; declared at `API/diagnostic/DiagnosticCode.java:801`
- **`AW1094` `UNIQUE_MEMBER_MANGLED` (INFO) — raised only where a `@Unique` member actually collided and `silent` is false.** — `ENG/merge/MemberBindings.java:359-368`; declared at `API/diagnostic/DiagnosticCode.java:980`
- **`AW1030` `FIELD_NOT_FOUND` (ERROR) — raised for a shadowed field the target does not declare and for an accessor's missing field.** — `ENG/merge/MemberBindings.java:247-253`, `ENG/merge/GeneratedMembers.java:94-101`; declared at `API/diagnostic/DiagnosticCode.java:450`
- **`AW1020` `METHOD_NOT_FOUND` (ERROR) — raised for a shadowed method and for an invoker's missing method; both attach every method of that name as detail lines.** — `ENG/merge/MemberBindings.java:290-301`, `ENG/merge/GeneratedMembers.java:226-236`; declared at `API/diagnostic/DiagnosticCode.java:340`
- **`AW1031` `SHADOW_TYPE_MISMATCH` (ERROR) — raised for a shadowed field at the wrong type and, reusing the same code, for an accessor whose descriptor is neither a read nor a write of the field's type.** — `ENG/merge/MemberBindings.java:257-264`, `ENG/merge/GeneratedMembers.java:179-187`; declared at `API/diagnostic/DiagnosticCode.java:463`
- **`AW1033` `SHADOW_REMOVES_FINAL` (WARNING) — raised only when `mutable` is declared and the target's field really carries `ACC_FINAL`; the detail differs for a `static final` field.** — `ENG/merge/MemberBindings.java:266-281`; declared at `API/diagnostic/DiagnosticCode.java:495`
- **`AW1088` `MERGE_FIELD_INTO_RECORD` (ERROR) and `AW1089` `MERGE_FIELD_INTO_ENUM` (WARNING) — both reached only for a non-static merged field; a static field returns before either check.** — `ENG/merge/MemberBindings.java:402-425`; declared at `API/diagnostic/DiagnosticCode.java:899`, `914`
- **`AW1089` does not stop the merge; `checkFieldShape` falls through to `return true`.** — `ENG/merge/MemberBindings.java:416-426`
- **`AW1095` `GENERATED_MEMBER_COLLIDES` (ERROR) — raised from `GeneratedMembers.isFree` for an accessor or an invoker whose name and descriptor the target already declares; there is no `@Unique` escape for a generated member.** — `ENG/merge/GeneratedMembers.java:288-298`; declared at `API/diagnostic/DiagnosticCode.java:992`
- **`AW1097` `ACCESSOR_WRITES_FINAL_FIELD` (ERROR) — raised for a setter over a final field, because the class verifies and throws `IllegalAccessError` the first time the setter is called.** — `ENG/merge/GeneratedMembers.java:110-126`; test asserting it was found by running the setter at `ENGT/merge/StructuralWeaverTest.java:162-172`
- **`AW1087` `WEAVE_TARGETS_WEAVE` (ERROR) — raised by `ConflictDetector` for a target that is itself a weave of the same run, once per offending target, and by the processor when the target carries `@Weave`.** — `ENG/plan/ConflictDetector.java:106-114`, `PROC/SourceTargets.java:161-171`
- **`AW1034` `SHADOW_OF_LOWER_PRIORITY_MEMBER` (ERROR) — raised when a shadow names something another weave merges at a priority that is not strictly higher.** — `ENG/plan/ConflictDetector.java:370-391`; tests at `ENGT/plan/WeavePlannerTest.java:227-245`
- **`AW1005`, `AW1090`, `AW1091` are the static-weave refusals, all raised in the parser.** — `ENG/parse/WeaveClassParser.java:500-505`, `375-378`/`553-556`, `392-394`/`563-565`
- **`AW1092` `TARGET_IS_ANONYMOUS_OR_LOCAL` (WARNING) — raised in `Weaver.weavableShape` for any class carrying an `EnclosingMethod` attribute; the wording distinguishes local from anonymous by the `InnerClasses` inner name.** — `ENG/Weaver.java:690-700`, `ENG/Weaver.java:406-411` (the `namedInSource` doc)

### Ordering, and its limits

- **Order is decided by `OrderKey`: priority descending, then weave binary name, then handler name, then handler descriptor, all ascending.** — `ENG/plan/OrderKey.java:33-37`
- **`OrderKey` names nothing about the target, the injector kind or the injection point, so two entries of one weave sharing a handler name and descriptor compare equal: the order is an order with ties.** — `ENG/plan/OrderKey.java:12-18`
- **Reproducibility comes from `List.sort` being stable over entries built in parse order, not from the comparator being total.** — `ENG/plan/OrderKey.java:15-18`, `ENG/plan/WeavePlanner.java:105`
- **Every component of an `OrderKey` must be non-blank or the constructor throws `IllegalArgumentException`.** — `ENG/plan/OrderKey.java:45-54`
- **Priority may be negative; the default is `0`.** — `API/Weave.java:242`, `246`
- **The structural index is populated in the iteration order of the weave list, not by priority, so merged members are emitted in the order the weaves were given.** — `ENG/plan/WeavePlanner.java:90-98`, consumed unchanged at `ENG/merge/StructuralWeaver.java:118-120`

### What conflict detection does and does not stop

- **`ConflictDetector.detect` runs five independent passes and all of them run; nothing there stops the plan.** — `ENG/plan/ConflictDetector.java:73-79`, `33-34`
- **A plan is still returned when conflicts were found.** — test `planSurvivesConflicts` at `ENGT/plan/WeavePlannerTest.java:247-258`
- **Two weaves merging one member into one target is excused only when *every* claimant is `@Unique`; marking only some does not help, because a mangled member and a plainly named one still collide on the plain name.** — `ENG/plan/ConflictDetector.java:308`, `318-323`, tests at `ENGT/plan/WeavePlannerTest.java:210-225`
- **The merged-member pass counts claims, so one weave declaring the same member twice is reported against itself; the handler pass counts distinct weaves, so one weave naming one handler from two declarations is not.** — `ENG/plan/ConflictDetector.java:278-279`, `308`, `195-196`, `231-232`
- **Only a dissolving weave's handlers are checked for collision: a static weave's handler stays where it is and two of them never meet.** — `ENG/plan/ConflictDetector.java:208-213`
- **`AW1034` keys additions by member name alone, without the descriptor, so a shadow is compared against every merged member of that name.** — `ENG/plan/ConflictDetector.java:355`, `369`
- **Report order is fixed rather than incidental: passes walk `sorted(weaves)` by binary name and group into a `TreeMap`.** — `ENG/plan/ConflictDetector.java:36-38`, `207`, `290`, `350`

### Thread safety, as the code shows it

- **`Weaver`'s counters are `LongAdder`s, one per count, except `plannedTargets`, which is a plain `long` fixed at construction.** — `ENG/observe/Statistics.java:28-43`
- **`statistics()` is a snapshot taken without locking, so individual counts may be read at slightly different moments.** — `ENG/Weaver.java:612-616` (doc), `ENG/Weaver.java:619-620`
- **`WeaverBuilder` is not thread-safe; the weaver it returns is, and the one mutable field it hands over is a `volatile ExplainReport`.** — `ENG/WeaverBuilder.java:49`, `417-418`
- **`MemberBindings` copies its two maps and its set into immutable ones at construction.** — `ENG/merge/MemberBindings.java:86-89`
- **`InjectInjector`'s per-body transform carries an element counter, so one transform instance belongs to one method body; reusing it across two bodies continues the count into the second.** — `ENG/inject/InjectInjector.java:355-360`

## Identifiers

Annotation elements of `@Weave`, spelled exactly: `value()`, `targets()`, `kind()`, `priority()`, `require()`, `tags()`, `phase()`.

Defaults: `value() default {}` (`API/Weave.java:198`), `targets() default {}` (`:216`), `kind() default Kind.INSTANCE` (`:227`), `priority() default 0` (`:246`), `require() default Require.REQUIRED` (`:257`), `tags() default {}` (`:276`), `phase() default Phase.DEFAULT` (`:287`).

Enum constants: `Weave.Kind.INSTANCE` (`API/Weave.java:314`), `Weave.Kind.STATIC` (`:335`); `Require.REQUIRED` (`API/Require.java:399`), `Require.OPTIONAL` (`:415`).

Engine types: `WeaveClass`, `TargetRef`, `WeaveMember` with nested `Merged`, `Shadowed`, `Accessor`, `Invoker`; `StructuralWeaver`, `MemberBindings` (package-private), `MemberBindings.FieldRebind`, `MemberBindings.MethodRebind`, `TargetMembers` (package-private), `GeneratedMembers` (package-private), `MergedBodyTransform` (package-private), `WeaveBytes` (public interface).

Public methods a reader may type: `WeaveClass.binaryName()`, `WeaveClass.isStructural()`, `WeaveClass.groupNamed(String)`, `WeaveClass.targets(String)`; `TargetRef.ofClassLiteral(ClassDesc)`, `TargetRef.ofName(ClassDesc)`, `TargetRef.binaryName()`, `TargetRef.internalName()`; `WeaveMember.name()`, `type()`, `flags()`, `isField()`, `Accessor.isGetter()`; `WeaveBytes.NONE`, `WeaveBytes.bytesOf(ClassDesc)`, `WeaveBytes.from(ClassSource)`; `StructuralWeaver(WeaveBytes)`, `StructuralWeaver.apply(ClassModel, List<WeaveClass>, Reporter)`; `WeaverBuilder.weaveBytes(WeaveBytes)` and `WeaverBuilder.weaveBytes(ClassSource)`-shorthand (`ENG/WeaverBuilder.java:135`, `143-152`).

Mangling: infix `"$aw$"` plus eight lower-case hex characters, being the first four bytes of the SHA-256 of the weave's binary name. — `ENG/merge/MemberBindings.java:52`, `55`, `553-565`. A member's mangled name is therefore `name + "$aw$" + digest8`.

Generated-member flags: `GENERATED_FLAGS = ClassFile.ACC_PUBLIC` and nothing else; never `ACC_STATIC`, so slot zero of a generated body is the receiver. — `ENG/merge/GeneratedMembers.java:42-49`

Name inference: accessor prefixes `"get"`, `"set"`, `"is"` (`ENG/parse/WeaveClassParser.java:101`); the processor uses the same three and invoker prefixes `"call"`, `"invoke"` (`PROC/SourceMembers.java:152-153`).

`OBJECT_METHODS` set, matched whole: `toString()Ljava/lang/String;`, `equals(Ljava/lang/Object;)Z`, `hashCode()I`, `main([Ljava/lang/String;)V`. — `ENG/parse/WeaveClassParser.java:110-114`

Diagnostic codes named above, with severity as declared in `API/diagnostic/DiagnosticCode.java`:
`AW1001` ERROR (:132), `AW1002` ERROR (:145), `AW1004` ERROR (:172), `AW1005` ERROR (:200), `AW1006` ERROR (:212), `AW1007` ERROR (:222), `AW1008` WARNING (:235), `AW1009` INFO (:248), `AW1020` ERROR (:340), `AW1030` ERROR (:450), `AW1031` ERROR (:463), `AW1032` WARNING (:477), `AW1033` WARNING (:495), `AW1034` ERROR (:509), `AW1080` ERROR (:801), `AW1081` ERROR (:817), `AW1082` ERROR (:827), `AW1083` WARNING (:840), `AW1084` ERROR (:851), `AW1087` ERROR (:886), `AW1088` ERROR (:899), `AW1089` WARNING (:914), `AW1090` ERROR (:927), `AW1091` ERROR (:938), `AW1092` WARNING (:952), `AW1093` INFO (:969), `AW1094` INFO (:980), `AW1095` ERROR (:992), `AW1096` ERROR (:1005), `AW1097` ERROR (:1019), `AW2003` ERROR (:1421), `AW2101` ERROR (:1447), `AW3001` ERROR (:1618), `AW3002` ERROR (:1634), `AW3003` ERROR (:1643).

## Surprises

1. **The engine and the annotation processor disagree about a method carrying both `@Shadow` and `@Accessor` (or `@Invoker`).** The engine checks `@Accessor` at `ENG/parse/WeaveClassParser.java:537` and `@Invoker` at `:544`, both *before* `@Shadow` at `:551`, so it generates the member onto the target and never reads the `@Shadow`. The processor checks `SHADOW` first at `PROC/MemberChecks.java:829`, before `ACCESSOR` at `:832`, so it validates the same declaration as a shadow. Nothing reports the redundant annotation — `PROC/MemberChecks.java:811-812` says so plainly.

2. **`WeaveClass.isStructural()` has no caller anywhere in the main sources.** The planner asks a different question with its own condition (`ENG/plan/WeavePlanner.java:85-86`), and `ConflictDetector.dissolves` writes the planner's condition out a third time (`ENG/plan/ConflictDetector.java:265-269`). The two conditions do not imply each other: an instance weave with no injectors and one plain, non-mutable `@Shadow` field dissolves under the planner's condition while `isStructural()` answers `false` — `ENG/model/package-info.java:163-171`, `ENG/model/WeaveClass.java:123-139`.

3. **A refused merge after a successful injection does not abandon the class.** `Weaver` keeps the injected bytes whenever `merged == null && current != bytes` (`ENG/Weaver.java:448-457`), and `StructuralWeaver.apply` returns `null` for a refusal and for nothing-to-emit alike (`ENG/merge/StructuralWeaver.java:78-79`). The injected call site names the target as the handler's owner (`ENG/plan/PlanEntry.java:104`), so a class that reached `AW1080` in the merge stage is still handed back carrying a call to a method that was never added. The comment at `ENG/Weaver.java:433-435` reads as though the whole class were abandoned in that case; `ENG/merge/package-info.java:12-17` states the actual behaviour.

4. **A shadow never resolves against another weave's merged member within one rebuild.** `TargetMembers` is built once from the class as it arrived (`ENG/merge/StructuralWeaver.java:89`) and is never updated as contributions are written (`:106-121`). So a shadow naming a member that a second weave in the *same* plan merges is `AW1030`/`AW1020` at weave time no matter what `AW1034`'s priority rule said at plan time. `AW1034`'s "strictly higher priority" rule (`ENG/plan/ConflictDetector.java:370-374`) can only be satisfied across two separate weaving passes, because priority does not order merges at all — the structural index is filled in weave-list order (`ENG/plan/WeavePlanner.java:96-97`).

5. **`GeneratedMembers.isFree` consults only the target's own declarations, and is explicit that the `ClassBuilder` is not consulted** (`ENG/merge/GeneratedMembers.java:273-274`, `288`). `ConflictDetector.collidingMergedMembers` filters to `WeaveMember.Merged` only (`ENG/plan/ConflictDetector.java:293`), so two weaves generating an accessor or invoker of the same name and descriptor onto one target are caught by neither. Both are emitted into the same builder.

6. **A `STATIC` weave still models its plain methods as `Merged`.** `readMethod` reaches `members.add(new WeaveMember.Merged(...))` at `ENG/parse/WeaveClassParser.java:568` with no test on `kind`. It is never actually merged, because the planner requires `kind == INSTANCE` (`ENG/plan/WeavePlanner.java:85`). But `RetransformApplicability.refusalFor` walks the member list *before* it looks at `kind` (`AGENT/RetransformApplicability.java:131-152`, `156`), so a static weave carrying a plain helper method reports `AW2101` for an already-loaded target it could never have changed.

7. **The engine's policy gate can never report `AW1087`.** `DefaultWeavePolicy` denies on `target.declaredWeaveClass()` (`ENG/policy/DefaultWeavePolicy.java:117-118`), but the single construction site passes `false, false` for `signed` and `declaredWeaveClass` (`ENG/Weaver.java:405-406`). The two paths that actually catch it are `ConflictDetector` (`ENG/plan/ConflictDetector.java:106`) and the processor (`PROC/SourceTargets.java:162`). The processor's version sees only a `@Weave` written directly on the target and only where the target is an element it can read (`PROC/SourceTargets.java:147-148`).

8. **`AW1004` from the engine skips one entry and reports each bad name, and then discards the weave anyway.** The loop `continue`s (`ENG/parse/WeaveClassParser.java:310`), but `AW1004` is an error, so `report.failed` is set and `parse` returns empty (`:187-188`). The per-entry behaviour is therefore visible only in the number of diagnostics, never in the outcome.

9. **`AW1031` is reused for a shape it does not describe.** It is declared as a shadow type mismatch (`API/diagnostic/DiagnosticCode.java:463`) but is also the code for an `@Accessor` whose descriptor is neither a read nor a write of its field (`ENG/merge/GeneratedMembers.java:179`). `GeneratedMembers.java:156-158` acknowledges the reuse.

10. **`AW1089` is a warning that lets a knowingly-useless merge through.** The instance field is really added to the enum, but the enum's constants are already constructed in the target's own `<clinit>`, so nothing writes anything into it beyond its default; the remedy points at an `@Inject` at the enum constructor's `HEAD` — `ENG/merge/MemberBindings.java:416-425`, `382-391`.

11. **`isGetter()` is decided by arity alone** (`ENG/model/WeaveMember.java:459-461`); whether the descriptor fits the field it names is a separate check, made where the method is generated (`ENG/merge/GeneratedMembers.java:166-188`).

12. **A `@Shadow(mutable = true)` on a *method* makes `WeaveClass.isStructural()` answer `true` without the target changing at all** — `ENG/model/WeaveClass.java:110-118`, `132`. Only a mutable *field* shadow reaches the unfinalise set (`ENG/merge/MemberBindings.java:266-267`, `ENG/merge/StructuralWeaver.java:212-214`).

## Could not establish

- **Whether a class carrying two identically-signed generated members (surprise 5) is rejected anywhere before the JVM.** `Verifier` runs `StructuralCheck` and then `ClassFile.of().verify` (`ENG/verify/Verifier.java:105-114`); `StructuralCheck` examines "the exception table of every method that carries a code attribute, and nothing else" (`ENG/verify/StructuralCheck.java:26-28`). Settling whether `ClassFile.verify` or `defineClass` refuses the duplicate needs a run, not a reading.
- **Whether a driver treats a plan-time `ERROR` diagnostic as fatal and so prevents the class from being woven at all.** The engine does not: `ConflictDetector` states "Nothing here stops the plan" (`ENG/plan/ConflictDetector.java:33-34`) and the only `Severity.ERROR` tests in the engine's main sources are `ENG/inject/WeavingPipeline.java:410` and `ENG/parse/WeaveClassParser.java:1053`. Each driver (maven-plugin, agent, testkit) would have to be read to answer this, and that is another page's source list.
- **Whether the weave class is "never loaded" in any enforced sense.** The test at `ENGT/merge/InstanceHandlerTest.java:78-87` establishes only that the woven target's bytes do not mention it. Nothing read here removes the weave class from the classpath or prevents anything from loading it.
- **The relative cost of the second read of a weave's class file.** `WeaveBytes` documents that `bytesOf` is asked once per dissolving weave per target and that caching belongs in the implementation (`ENG/merge/WeaveBytes.java:37-39`), but any statement about the cost would need a benchmark.
- **Whether a `@Weave` on a target that is not among the run's weaves is detected at all.** `ConflictDetector.weavesTargetingWeaves` matches only against `byType`, built from the weaves handed to the planner (`ENG/plan/ConflictDetector.java:94-102`). The engine never reads a target's own annotations for this. Whether any driver supplies the missing half was not established.

## Not this page

- **`concepts/selectors`** — the selector grammar and `AW1015`–`AW1022`; read at `ENG/parse/WeaveClassParser.java:889` only in passing.
- **`concepts/injection` or equivalent** — what `@Inject`, `@Redirect` and `@Wrap` mean at a site, `AW1040`–`AW1044`, `AW1060`–`AW1063`, `AW1070`–`AW1072`, the callback protocol, and `WeavingPipeline`'s refusal-on-error at `ENG/inject/WeavingPipeline.java:410`.
- **`reference/diagnostics`** — the full code table with severities; the declarations are at `API/diagnostic/DiagnosticCode.java:132-1667`.
- **`reference/annotations`** — the element-by-element rules for `@Weave`, `@Shadow`, `@Unique`, `@Accessor`, `@Invoker`.
- **A policy/security page** — `AW3001` JDK packages, `AW3002` signed artefacts, `AW3003` self-weave, `AW3020` policy override; `ENG/policy/DefaultWeavePolicy.java`, `API/spi/WeavePolicy.java:81`, `API/spi/WeaveTarget.java:22-27`.
- **The agent page** — `AW2101` and retransformation limits; `AGENT/RetransformApplicability.java:46-160`, `AGENT/WeaverAgent.java:135`, `261-266`.
- **A discovery/configuration page** — `tags()` are compared against include and exclude lists at discovery, and an untagged weave is skipped as soon as any include list exists (`API/Weave.java:259-276`); `Phase` is carried and read by nothing (`ENG/model/package-info.java:163-165`, no reader of `WeaveClass.phase()` in any module's main sources).
- **A build-time/idempotence page** — the plan fingerprint, the `AetherWeave` stamp and `AW2201`/`AW2202`; `ENG/Weaver.java:417-431`, `726-767`.
- **A verification page** — `StructuralCheck`, `VerificationPolicy`, `AW4004`; `ENG/verify/`.
- **A stubs/extension page** — `StubsMojo`'s reading of `Require` at `aether-weaver-maven-plugin/.../StubsMojo.java:367`, and `Extension#require()`.
