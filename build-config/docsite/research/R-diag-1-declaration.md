# R-diag-1 — AW1xxx: declarations and injection (the 63 `Category.DECLARATION` codes)

Scope: `AW1001`–`AW1097`, every constant carrying `Category.DECLARATION`. `AW11xx`, `AW1200`,
`AW13xx` belong to other pages.

## Proposed grouping — read this first

63 rows in one table is unusable. Every code below groups by **what the reader was doing when the
build stopped**, which also matches the file that reports it. Group order is arrival order: a reader
meets A before B before C.

| # | Group | Codes | One line |
| --- | --- | --- | --- |
| A | The weave class itself | 1001 1002 1004 1006 1007 1008 1009 1081 1082 1084 1087 1096 | The `@Weave` class's own shape and what it names, before any target is looked at |
| B | The selector text | 1015 1016 1017 1018 1019 | The `method = "…"` string did not parse |
| C | Finding the target method | 1010 1020 1021 1022 1023 1024 1025 | The selector parsed but resolved to nothing, to several things, or to a method with no body |
| D | The handler's signature | 1005 1040 1041 1042 1070 1071 1072 | The handler cannot be called from where the call is emitted |
| E | Where the injection lands | 1043 1044 1026 1027 | The point matched too few, too many, or an unusable position |
| F | `@Local` captures | 1050 1051 1052 1053 1054 | The captured variable could not be bound at the site |
| G | `@Redirect` and `@Wrap` | 1060 1061 1062 1063 | The declaration needs an operation, and did not get one it can take over |
| H1 | `@Shadow` | 1030 1031 1032 1033 1034 | A promise about a member the target already has |
| H2 | Merged members | 1080 1083 1088 1089 1093 1094 | Members the weave adds to the target |
| H3 | `@Accessor` / `@Invoker` | 1095 1097 (+ 1030, 1031, 1020 reused) | Members the engine generates onto the target |
| I | Static weaves | 1090 1091 (+ 1005, 1042) | A declaration whose whole meaning is a merge, in a weave that is never merged |
| J | The target class's shape | 1092 | The target is an anonymous or local class |
| — | Reserved | 1003 1085 1086 | No reporting site anywhere |

**Codes a reader most often arrives with** (breadth of reporting sites × test count):
`AW1043` (6 sites), `AW1061` (8 sites), `AW1040` (5 sites), `AW1005` (6 sites), `AW1020` (6 sites),
`AW1080` (6 sites), `AW1030` (4 sites). Open each group with these.

**Tail material** — one line each, at the end of their group, never in the opening:
`AW1016` (informational, and misfires on `<init>`), `AW1024`, `AW1027`, `AW1034`, `AW1052`,
`AW1072`, `AW1082`, `AW1084`, `AW1088`, `AW1089`, `AW1092`, `AW1093`, `AW1094`, `AW1096`,
`AW1003`/`AW1085`/`AW1086`.

---

## Facts — catalogue level

- **`1000`–`1099` is `Category.DECLARATION`, "the shape of a weave class, its members and its handlers"** — `aether-weaver-api/src/main/java/de/splatgames/aether/weaver/api/diagnostic/DiagnosticCode.java:39`
- **Category follows from the four digits, not from a field** — `DiagnosticCode.java:32`
- **`isSuppressible()` is false for exactly the `ERROR` codes** — `DiagnosticCode.java:71`
- **A site may raise or lower one report's severity with `Diagnostic.Builder.severity(Severity)`; it cannot change the catalogue** — `DiagnosticCode.java:73`
- **A diagnostic's severity defaults to the code's** — `api/diagnostic/Diagnostic.java:118`
- **Exactly one `.severity(...)` call exists in all of `src/main/java`: the engine weave parser's `emit`** — `engine/parse/WeaveClassParser.java:1101`. It is passed `ERROR`/`WARNING`/`INFO` by the `error`/`warn`/`info` helpers (`WeaveClassParser.java:1049`, `:1063`, `:1077`), and for **every** AW10xx code the value equals the constant's default. **No AW10xx report is ever raised or lowered from its catalogue severity.**
- **Severities in this range**: `WARNING` — 1008, 1027, 1032, 1033, 1083, 1085, 1089, 1092. `INFO` — 1009, 1016, 1093, 1094. Everything else `ERROR`.
- **Maven fails the build on any `ERROR` diagnostic unless `aether.weaver.failOnError` is cleared** — `aether-weaver-maven-plugin/.../AbstractWeaveMojo.java:97` (parameter, `defaultValue = "true"`), `:414`
- **Two stages report this range.** The annotation processor is auto-discovered (`aether-weaver-processor/src/main/resources/META-INF/services/javax.annotation.processing.Processor`); the engine's weave parser runs in all four shipped drivers — Maven `ClassDirectory.java:122`, runtime `WeaveDiscovery.java:91`, testkit `Weaving.java:139` (the agent goes through the runtime's discovery).
- **The processor reuses the engine's own point resolution**, so `AW1043`, `AW1044`, `AW1026` and `AW1061` are compile-time codes too — `aether-weaver-processor/.../PointChecks.java:90`, `:97`, `:170`, `:178`
- **`Weaver`'s policy gate builds its `WeaveTarget` with `declaredWeaveClass = false` and `signed = false`** — `engine/Weaver.java:405`. A policy denial's diagnostic is built from the `Deny`'s code and reason with **no remedy at all** — `engine/Weaver.java:409`.

---

## Facts — per code

Format: **code** — constant, severity, `DiagnosticCode.java:line` → then one bullet per reporting
site: `file:line` — condition — remedy — supported.

### Group A — the weave class itself

**AW1001** `WEAVE_NO_TARGETS`, ERROR, `DiagnosticCode.java:132`
- `engine/parse/WeaveClassParser.java:290` — both `value()` and `targets()` empty; returns `List.of()` so the weave gets no targets — remedy `"name the class it modifies: @Weave(TheTarget.class)"` — supported.
- `processor/WeaveProcessor.java:510` — same condition on the mirror; anchored on the annotation because there is no literal to underline (`:517`) — remedy `"give @Weave a class literal — @Weave(Session.class) — or a name, @Weave(targets = \"com.acme.Session\") when the target is not on the compile classpath"` — supported.
- Reachable from every driver and from javac.

**AW1002** `WEAVE_DUPLICATE_TARGET_DECLARATION`, ERROR, `:145`
- `WeaveClassParser.java:283` — both spellings written; **returns `List.of()`, so the weave ends with no targets and `AW1001` is not additionally reported** — remedy `"keep the class literals and delete targets=, or the other way round"` — supported.
- `WeaveProcessor.java:499` — same; caret on the `targets` literal (`:502`). **The processor then resolves both forms anyway** (`SourceTargets.java:74`), so a class named twice is checked twice and recorded twice in the manifest — unlike the engine, which gives such a weave no targets at all.

**AW1004** `WEAVE_TARGET_UNRESOLVABLE`, ERROR, `:172`
- `processor/SourceTargets.java:116` — a `targets()` name `Elements.getTypeElement` cannot resolve, **and only when `require` is not `OPTIONAL`** (`:107`) — remedy `"check the spelling, or declare require = Require.OPTIONAL when the target is deliberately absent at compile time and present at run time"` — supported.
- `WeaveClassParser.java:305` — `ClassDesc.of(name)` threw `IllegalArgumentException`, i.e. not a usable binary class name; **`require()` is not consulted here**, and the loop `continue`s so three bad names produce three reports — remedy `"a nested class is written with a dollar sign, as in \"com.acme.Outer$Inner\""` — supported.
- The two sites test different things: a spelling that resolves nowhere (processor) versus a string that is not a class name at all (engine).

**AW1006** `WEAVE_HAS_SUPERCLASS`, ERROR, `:212`
- `WeaveClassParser.java:226` — `model.superclass()` is not `Object` — remedy `"declare the weave to extend Object, and reach the superclass's members through @Shadow instead"` — supported.
- `WeaveProcessor.java:549` — same on the element model; **an interface is never reported, its superclass kind being `NONE`** (`:547`).
- **No test in the seven reactor modules asserts `AW1006`** (only `aether-weaver-ide/.../ProcessorCrossCheckTest.java`, outside the reactor).

**AW1007** `WEAVE_IS_GENERIC`, ERROR, `:222`
- `WeaveClassParser.java:245` — the class `Signature` attribute declares type parameters — **no remedy** (`null` passed at `:249`).
- `WeaveProcessor.java:565` — `getTypeParameters()` non-empty; caret on the first type parameter (`:570`) — **no remedy**.

**AW1008** `WEAVE_NOT_FINAL`, WARNING, `:235`
- `WeaveClassParser.java:256` — not `FINAL`, and neither `ABSTRACT` nor `INTERFACE` (`:253`) — remedy `"declare it final"` — supported.
- `WeaveProcessor.java:579` — not `ABSTRACT`, not `FINAL`, **and `getKind() == ElementKind.CLASS`**, so an enum or record weave is exempt at compile time — remedy `"declare it final"`.

**AW1009** `WEAVE_TARGET_PREFER_CLASS_LITERAL`, INFO, `:248`
- `processor/SourceTargets.java:126` — a `targets()` name that **did** resolve — remedy `"write @Weave(<Simple>.class): a class literal is checked by the compiler, follows a rename, and survives the class being moved to another package"` — supported.
- **Processor only.** No engine site.

**AW1081** `WEAVE_DECLARES_CONSTRUCTOR`, ERROR, `:817`
- `WeaveClassParser.java:473` — a `<init>` that `isImplicitConstructor` rejects. The engine's test for implicit is **no parameters and at most three instructions**, so a hand-written no-argument constructor with a trivial body is *not* reported at weave time — remedy `"initialise merged state from an @Inject at the target constructor's HEAD"` — supported.
- `WeaveProcessor.java:621` — a constructor whose `Elements.Origin` is `EXPLICIT` (`:640`); one report per written constructor — same remedy.
- The two tests disagree by construction: a trivial written constructor is `AW1081` at compile time and silent at weave time.

**AW1082** `WEAVE_DECLARES_STATIC_INITIALISER`, ERROR, `:827`
- `WeaveClassParser.java:481` — the method name is `<clinit>` — **no remedy** (`null` at `:484`).
- **Engine only.** `WeaveProcessor.checkMembers` (`:610`) tests only `ElementKind.CONSTRUCTOR`, so a weave assigning to one of its own static fields compiles clean and fails at weave time.

**AW1084** `WEAVE_IMPLEMENTS_INTERFACE`, ERROR, `:851`
- `WeaveClassParser.java:234` — `model.interfaces()` non-empty; only the first is named — **no remedy** (`null` at `:238`).
- `WeaveProcessor.java:558` — `getInterfaces()` non-empty — **no remedy**.

**AW1087** `WEAVE_TARGETS_WEAVE`, ERROR, `:886` — **three sites, and they are not equivalent.**
- `processor/SourceTargets.java:162` — a resolved target that itself carries `@Weave`; the target is dropped from the list — remedy `"target the class the other weave targets, and order the two with priority = …"` — supported.
- `engine/plan/ConflictDetector.java:106` — a target whose `ClassDesc` is another weave **of the same run**; a weave naming itself is reported too — remedy `"…Target the class you actually want to modify"` — supported. Runs inside `WeaverPlanner.plan` (`WeavePlanner.java:107`), which is called from `WeaverBuilder.build()` (`WeaverBuilder.java:376`). **Conflicts do not abort planning** (`WeavePlanner.java:27`); the plan is returned and whoever owns the listener decides.
- `engine/policy/DefaultWeavePolicy.java:118` — `target.declaredWeaveClass()`. **Dead through the shipped path**: `Weaver` always passes `false` (`engine/Weaver.java:406`), and the class's own JavaDoc says so (`DefaultWeavePolicy.java:101`). Only a caller invoking the policy itself can raise it, and that report carries **no remedy** (`Weaver.java:409` builds message + detail only).

**AW1096** `WEAVE_BYTES_UNAVAILABLE`, ERROR, `:1005`
- `engine/merge/StructuralWeaver.java:178` — the weave is `Kind.INSTANCE`, has a structural effect, `needsBodies` (a merged member or a handler of its own), and `WeaveBytes.bytesOf` returned `null` — remedy `"give the weaver a byte source with WeaverBuilder.weaveBytes(…)…"` — supported but **misleading for a consumer**: `WeaverBuilder.classSource(...)` delegates to `weaveBytes(...)` (`WeaverBuilder.java:152`) and **every shipped driver calls `classSource`** — Maven `AbstractWeaveMojo.java:287` (`ClassSource.ofPath(classes.root())`), agent `WeaverAgent.java:212`, class loader `WeavingClassLoader.java:225`, testkit `Weaving.java:154`. The default is `WeaveBytes.NONE` (`WeaverBuilder.java:61`), so the code is reached by a hand-built `Weaver`, or by a driver whose source cannot answer for the weave class (weave outside the woven directory / not visible to the loader).
- A weave that only injects is unaffected: `needsBodies` is false for accessors and invokers (`StructuralWeaver.java:241`).

### Group B — the selector text

All five are **thrown**, not reported, by `api/select/SelectorParser.java`, as
`SelectorSyntaxException(code, text, offset, message, suggestion)` (`SelectorParser.java:781`). Two
places turn one into a diagnostic:

- `processor/SelectorChecks.java:130` — `builder.message(malformed.getMessage())`, and
  `malformed.suggestion().ifPresent(builder::remedy)` (`:132`).
- `engine/parse/WeaveClassParser.java:897` — `report.error(e.code(), "handler … : " + e.getMessage(), e.suggestion().orElse(null))`.

**Only `AW1017` ever carries a suggestion**, so `AW1015`, `AW1018` and `AW1019` reach the reader
**with no remedy line at all** at both sites. The message carries the offset, and
`SelectorSyntaxException.formatWithCaret()` renders a caret under it (`DiagnosticCode.java:284`).

**AW1015** `SELECTOR_SYNTAX_ERROR`, ERROR, `:276` — thrown at `SelectorParser.java:128` (empty),
`:208` (not a valid class name after `class:`), `:221` (not a valid `int`/`long`/`float`/`double`
literal), `:257` (no member name), `:327` (`#` not followed by a member name), `:335`/`:347` (invalid
member name), `:343` (invalid owner name), `:378` (expected a type name), `:395` (unbalanced `<`),
`:701` (`expect(char)` — missing character or the selector ended), `:718` (`requireEnd` — trailing
text). Two sites report it directly and both **do** give a remedy:
- `processor/SelectorChecks.java:111` — the text is blank, decided before the parser is reached — remedy `"name the member to inject into, for example \"run()\" or \"com.acme.Session#run()\""`.
- `WeaveClassParser.java:889` — `text.isBlank()` — **no remedy** (`null`).

**AW1016** `SELECTOR_TYPE_ARGUMENTS_IGNORED`, INFO, `:289`
- `processor/SelectorChecks.java:119` — **`text.indexOf('<') >= 0`, the character and not a parsed type-argument list** — remedy `"nothing needs doing; delete them to say what the selector means"`.
- **Surprise, and documented in the source**: `method = "<init>()"` is reported as carrying type arguments (`SelectorChecks.java:57`). Parsing then continues and the text becomes a `MethodSelector` named `<init>`.
- **Processor only.** The engine never reports it. Nothing sets it from the parser.

**AW1017** `SELECTOR_MISSING_DESC_PREFIX`, ERROR, `:301`
- Thrown at `SelectorParser.java:149`, **only after the source-form parse has already failed** and `looksLikeDescriptor(body)` answers yes (`:147`). The suggestion is the body with `desc:` prepended (`:152`), so this is the one code in group B whose remedy is a one-step fix.

**AW1018** `SELECTOR_MALFORMED_DESCRIPTOR`, ERROR, `:315` — thrown at `SelectorParser.java:439`
(a `*` anywhere in the descriptor form), `:478` (no method name), `:492` (JDK refused the method
descriptor), `:515` (field selector with no `:type`), `:525` (no field name), `:534` (JDK refused the
field descriptor), `:556` (`internalNameToDesc` — not a class descriptor). **No suggestion at any of
them, so no remedy reaches the reader.**

**AW1019** `SELECTOR_DESCRIPTOR_MISSING_RETURN_TYPE`, ERROR, `:324` — thrown once, at
`SelectorParser.java:484`, when the `desc:` method text ends with `)`. **No suggestion**; the message
says `"descriptor selectors must be exact: \"…\" is missing the return type (use 'V' for void)"`.

**`MemberSelector.parse` also throws `IllegalArgumentException`** for `v:void[]` and for `desc: ()V`;
`SelectorChecks` does not catch it, and it ends the compilation with
*An annotation processor threw an uncaught exception* and no position — `SelectorChecks.java:74`.

### Group C — finding the target method

**AW1010** `SELECTOR_OWNER_UNRESOLVABLE`, ERROR, `:260`
- `processor/HandlerChecks.java:533` — the selector has an explicit owner, the name **contains a dot**, and `Elements.getTypeElement` returns null. An unqualified owner is never reported (`:530`) — remedy `"check the spelling, or drop the owner when the member belongs to the weave's own target"` — supported.
- **Processor only.**

**AW1020** `METHOD_NOT_FOUND`, ERROR, `:340` — six sites, four different questions.
- `processor/HandlerChecks.java:605` — an injection's `method` selector matched no declared method of the target; **only `ElementKind.METHOD` members are candidates, so `<init>()` never matches at compile time** (`:586`) — remedy `"an inherited method is not a declared one; name the class that declares it, or add the parameter types to pick an overload"`.
- `processor/MemberChecks.java:315` — a `@Shadow` **method** the target does not declare — remedy `"a @Shadow declaration is a promise that the target has this member; the erased parameter types must match exactly, and an inherited member is not a declared one"`.
- `processor/MemberChecks.java:511` — an `@Invoker` whose name+erased parameters match nothing — remedy `"the declaration's parameters must match the target method's exactly"`.
- `engine/inject/WeavingPipeline.java:698` — the parsed selector matched no method of the target — remedy `"check the selector against the listing above"`; every method of the target is a detail line.
- `engine/merge/MemberBindings.java:290` — a `@Shadow` method missing at merge time — remedy `"the descriptor must match exactly; an inherited member is not a declared one, and resolving the hierarchy would mean loading classes from inside class loading"`.
- `engine/merge/GeneratedMembers.java:226` — an `@Invoker` whose target method is missing — remedy `"an invoker's signature must match the target method's exactly — it is the same call, made from inside the class"`.
- All supported. A compiled target **does** carry `<init>` in the engine's method list, so `<init>()` resolves at weave time and not at compile time (`SelectorChecks.java:60`).

**AW1021** `SELECTOR_AMBIGUOUS`, ERROR, `:358`
- `processor/HandlerChecks.java:634` — more than one match **and the selector's name is not the wildcard** (`:620`, `:632`) — remedy `"add the parameter types — run(java.lang.String) — so that exactly one overload is named"` — supported.
- `engine/inject/WeavingPipeline.java:705` — more than one match, **wildcard or not** — remedy `"add the parameter types, or use the desc: form to pin one exactly"` — supported.
- **The limit that matters**: silencing `AW1022` with `allow` at compile time does not silence the `AW1021` the engine still reports (`DiagnosticCode.java:347`).

**AW1022** `SELECTOR_WILDCARD_TOO_BROAD`, ERROR, `:369`
- `processor/HandlerChecks.java:621` — several matched, the name **is** the wildcard, and `allowOf(injection) <= 0` — remedy `"set allow = <n> to say that matching several is intended, so that matching a different number later is caught rather than silently woven"` — supported at compile time only.
- **Processor only**, and `allow` written as `0` is indistinguishable from omitted here (`HandlerChecks.java:914`).

**AW1023** `TARGET_METHOD_ABSTRACT`, ERROR, `:381`
- `processor/HandlerChecks.java:688` — `Modifier.ABSTRACT` on the resolved target method — remedy `"name an implementing method instead; an abstract declaration says what happens, not how"`.
- `engine/inject/WeavingPipeline.java:641` — `method.code().isEmpty()`, i.e. no `Code` attribute — same remedy. `native` is tested first at both sites (`HandlerChecks.java:678`, `WeavingPipeline.java:631`) so a native method never gets this advice.

**AW1024** `TARGET_METHOD_SYNTHETIC`, ERROR, `:393`
- `engine/inject/WeavingPipeline.java:649` — `method.isSynthetic()` — remedy `"name the method the author wrote — for a bridge, the one with the specific parameter types; for a lambda body, the method containing the lambda"` — supported.
- **Engine only.** No compile-time site.

**AW1025** `TARGET_METHOD_NATIVE`, ERROR, `:404`
- `processor/HandlerChecks.java:678` and `engine/inject/WeavingPipeline.java:632` — `native`; both return immediately so no `AW1023` follows — remedy at both `"inject into the Java method that calls it, or use @Redirect at the call site to intercept the transition"` — supported.

### Group D — the handler's signature

**AW1005** `STATIC_WEAVE_INSTANCE_HANDLER`, ERROR, `:200` — **six sites, four distinct conditions,
and two of them carry a remedy the engine does not honour.**

| Site | Condition | Remedy | Supported |
| --- | --- | --- | --- |
| `api/spi/HandlerBinding.java:388` | `!handler.isStatic() && target.isStatic()` — a merged instance handler bound to a static target method | `"declare the handler static, or target an instance method. A static method has no \`this\` for a merged handler to be invoked against"` | yes |
| `engine/inject/InjectInjector.java:124` | `!handler.isStatic() && !target.type().equals(entry.handlerOwner())` | `"declare the handler static, or declare the weave @Weave(kind = Kind.INSTANCE) so that it is dissolved into its target…"` | yes |
| `engine/inject/RedirectInjector.java:111` | same test | same remedy | yes |
| `engine/inject/WrapInjector.java:152` | `!handler.isStatic()` — **unconditional, in a weave of any kind** | `"declare the handler static; what it needs from the operation is already in its parameters, and state it needs beyond that belongs in a static field of the weave"` | yes |
| `processor/HandlerChecks.java:240` | a non-static `@Wrap` handler, any weave kind | `"declare the handler static; state it needs beyond the operation's own arguments belongs in a static field of the weave"` | yes |
| `engine/parse/WeaveClassParser.java:501` | `isHandler && kind == STATIC && !flags.contains(STATIC)` | **`"declare it static and take the target as the first parameter"`** | **NO** |
| `processor/WeaveProcessor.java:631` | `isStatic && isHandler(method) && !STATIC` | **`"declare it static and take the target as the first parameter"`** | **NO** |

**Why the last two are unsupported.** `HandlerBinding.bind` reads a handler's parameters as
*skipLeading operands, then a prefix of the target method's own parameters, then a callback, then
captures* (`HandlerBinding.java:40`–`:48`). It compares each claimed parameter against
`targetType.parameterType(i)` (`HandlerBinding.java:398`), loads them from slot 1 upwards for an
instance target — *slot 0 is skipped* (`HandlerBinding.java:380`) — and pushes a receiver **only for
a non-static handler** (`emitReceiver`, `HandlerBinding.java:676`; `InjectInjector.java:491`). For
`@Inject`, `skipLeading` is 0 unless the declaration captures a result (`InjectInjector.java:266`).
A static handler that declares the target as its first parameter therefore reports **`AW1040`** at
weave time — `"it takes N target parameters and … has only M"` (`HandlerBinding.java:373`) or a
parameter-type mismatch (`HandlerBinding.java:403`).

The annotation processor **compensates for it and hides the discrepancy**: `skipReceiver` drops a
leading parameter whose erasure is the target's declaring type when the handler is static and the
target method is not (`HandlerChecks.java:817`–`:830`), with the comment *"which is what AW1005's own
remedy asks for"*. `WeaveProcessorTest.theReceiverIsNotATargetArgument` asserts the compile is clean
for exactly this shape (`processor/src/test/java/.../WeaveProcessorTest.java:886`–`:905`). So the
remedy compiles and then fails to weave.

- A non-static `@Wrap` handler in a static weave reports `AW1005` **twice at compile time**, with the two different remedies — `HandlerChecks.java:213`, `WeaveProcessor.java:597`.
- `api/spi/Injector.java:135` is a JavaDoc example, not a reporting site.

**AW1040** `HANDLER_PARAMETERS_NOT_PREFIX`, ERROR, `:535` — one code, five shapes.
- `api/spi/HandlerBinding.java:466` (`capturesOccupyTheTail`) — a `@Local` parameter that is not one of the last `locals.size()` positions — remedy `"a handler's parameters are, in order: the target's argument prefix, then an optional Callback, then the @Local captures. Move the captured parameters to the end"`.
- `HandlerBinding.java:357` via `mismatch` (`:693`) — fewer visible parameters than the operation supplies.
- `HandlerBinding.java:374` — more claimed parameters than the target has.
- `HandlerBinding.java:403` — a claimed parameter's type is not **`ClassDesc`-equal** to the target's: no widening, no boxing, no subtyping (`HandlerBinding.java:80`).
- All three of those share the remedy at `HandlerBinding.java:701`: `"a handler's parameters must be a PREFIX of the target's — take the first n, in order, or take none…"` — supported.
- `engine/inject/RedirectInjector.java:237` — the handler's descriptor is not the shape of the matched operation — remedy `"a redirect handler begins with the operation's own inputs, in order — the receiver first for an instance operation — and returns what the operation returned. The enclosing method's parameters may follow them"`.
- `engine/inject/WrapInjector.java:296` — same for a wrap — remedy `"…then one Operation parameter, and returns what the operation returned"`.
- `processor/HandlerChecks.java:850` (`prefixFailure`, called from `:783` and `:790`) — too many parameters, or one at the wrong erasure — remedy `"drop the parameters that do not correspond, or capture them with @Local when they are locals rather than arguments"`.
- **Order matters**: `HandlerBinding` checks captures-in-tail, then operand count, then the callback, then the count, then `AW1005`, then the types, and the **first failure ends the attempt** (`HandlerBinding.java:87`).
- Fewer parameters than the target has is **not** a failure at either stage (`HandlerChecks.java:746`, `HandlerBindingTest.java:97`).

**AW1041** `HANDLER_RETURN_TYPE_NOT_VOID`, ERROR, `:548`
- `engine/inject/InjectInjector.java:134` — the handler's return type is not `void` — remedy `"an @Inject handler influences the target through its Callback, not through a return value"` — supported.
- `processor/HandlerChecks.java:716` — same — remedy `"to change what the target returns, take a ReturnableCallback and cancel with a value"` — supported.
- Both sites report and **carry on**, so a handler can collect this and a parameter diagnostic in one run (`InjectInjector.java:99`, `HandlerChecks.java:703`).

**AW1042** `HANDLER_NOT_ACCESSIBLE`, ERROR, `:563`
- `processor/HandlerChecks.java:483` — **only** for `kind = Kind.STATIC` (`:455`); unreachable when the handler is `private`, or not `public` while the weave class is `public` and the packages differ (`:472`) — remedy `"make the handler and its class public, or declare the weave @Weave(kind = Kind.INSTANCE) so that it moves into the target"` — supported.
- **Processor only.** The engine never checks accessibility, so a weave compiled elsewhere reaches `IllegalAccessError` at the first execution of the injected call (`HandlerChecks.java:489`).

**AW1070** `CANCEL_ON_NON_VOID_TARGET`, ERROR, `:753`
- `api/spi/HandlerBinding.java:501` — a plain `Callback` in the callback position and the target returns a value — remedy `"declare ReturnableCallback<T> instead — cancelling a value-returning method without a value would leave it with nothing to return"`, with `T` printed as the target's return type — supported.
- **One site only, in the engine's binding path.** `HandlerChecks.checkCallback` (`:896`) reports `AW1071` and never this, so **a plain `Callback` on a value-returning target compiles clean and fails at weave time.**

**AW1071** `CALLBACK_TYPE_MISMATCH`, ERROR, `:767` — two different shapes.
- `api/spi/HandlerBinding.java:512` — a `ReturnableCallback` on a **`void`** target — remedy `"declare a plain Callback — there is no value for a void method to return instead"`. The engine cannot compare the type argument: it is erased in the class file.
- `processor/HandlerChecks.java:896` — the declared type argument's erasure is not the target's boxed return type; a raw declaration, a non-`DeclaredType`, and a `void` target all pass unchecked (`:868`, `:890`) — remedy `"declare ReturnableCallback<" + boxed + ">"` — supported.
- So the type-argument half of this code exists **only at compile time**.

**AW1072** `CALLBACK_VALUE_UNAVAILABLE`, ERROR, `:781`
- `engine/parse/WeaveClassParser.java:670` — the handler's own instructions contain a call to `value()` whose owner is `ReturnableCallback` (`readsCallbackValue`, `:690`) and the declaration names a point that is not in `VALUE_BEARING`; **reported once per point, not once per handler** — remedy names the value-bearing points and says to move the injection or drop the call — supported.
- **Engine only, and deliberately so**: the check is on instructions, which `javax.lang.model` does not model (`WeaveClassParser.java:645`).
- Not seen when the compile-time receiver is some other type, or when the call is made by a method the handler delegates to (`WeaveClassParser.java:683`).

### Group E — where the injection lands

**AW1043** `NO_INJECTION_POINT_MATCHED`, ERROR, `:589` — the commonest code, six sites.
- `engine/parse/WeaveClassParser.java:758` — the declaration carries **no `@At` at all** — remedy `"add at = @At(Point.HEAD), or whichever point it should attach to"`.
- `engine/inject/point/PointResolver.java:215` — the point's `targetRequirement()` is `REQUIRED` and no target was given — remedy `"add target = \"…\" naming what to match, for example target = \"Gateway.send(Payment)\" or the name-only \"#send\""`.
- `PointResolver.java:223` — the requirement is `FORBIDDEN` and one was given — remedy `"remove the target; <point> locates a position rather than matching something"`.
- `engine/inject/point/BuiltInPoints.java:1046` — the point searched the body and found nothing; the listing of what *was* found, capped at ten, is the substance (`:1063`), with notes for skipped `invokedynamic` (`:1071`) and for a selector that constrains the signature (`:1078`) — remedy `"check the target against the listing above; a name-only selector such as '#send' matches any owner, and a slice narrows where the search runs"`.
- `engine/inject/MatchAccounting.java:114` (`tooFew`) — `matched < spec.require()` — remedy `"the point's own diagnostic above lists what was found instead. If the target legitimately varies — two library versions, say — declare @Group(name = \"…\", min = 1) across the alternatives instead of requiring each one"`.
- `MatchAccounting.java:178` (`groupUnsatisfied`) — a `@Group`'s **total** is outside `min`/`max` — remedy `"a group says 'at least one of these had to work'. If none did, the target has changed in a way none of the alternatives anticipated"`.
- All supported. Reachable at compile time as well through `PointChecks.run` (`processor/PointChecks.java:132`, `:177`).
- **`require` defaults to 1 only when the element was omitted**; an explicitly written `require = 0` stays 0 (`WeaveClassParser.java:768`). At compile time the number checked is what the source wrote, so an **omitted `require` never produces `AW1043` at compile time** (`PointChecks.java:111`).
- **A whole-class verdict**: `MatchAccounting` returning false makes `WeavingPipeline` hand the class back untouched (`MatchAccounting.java:21`).

**AW1044** `TOO_MANY_INJECTION_POINTS`, ERROR, `:606`
- `engine/inject/MatchAccounting.java:141` — `spec.isBounded() && matched > spec.allow()`; `allow = 0` means no upper bound — remedy `"narrow it with an ordinal or a slice. An upper bound exists so that a target gaining a second matching call is an error rather than a silent doubling of whatever the handler does"` — supported, though it omits the catalogue's third option, raising `allow` (`DiagnosticCode.java:611`).
- `api/diagnostic/Diagnostic.java:74` and `api/diagnostic/package-info.java:127` are JavaDoc examples, not sites.
- **Surprise, in the same method**: a declaration naming a `group` is accounted **only** through the group, and *"a group absent from `groups` is never read back out of the running totals, so a declaration naming it is not accounted at all: no `AW1043`, no `AW1044`, nothing"* — `MatchAccounting.java:44`, code at `:79`. A mistyped group name silently disables both bounds.

**AW1026** `THIS_UNAVAILABLE_BEFORE_SUPER_CALL`, ERROR, `:418`
- `engine/inject/point/SiteSafety.java:138` — the target method is `<init>`, the injector kind is `INJECT`, the handler is **not** static, and `site.index() <= initialiser`. `<=` and not `<`: a site at the initialiser's own index emits in front of the `super()` call (`SiteSafety.java:132`) — remedy `"…Declare the handler static, or move the point after the super() call, which is where Point.HEAD already puts it"` — supported.
- Single site; reachable at compile time through `PointChecks` (`PointChecks.java:96`).

**AW1027** `CONSTRUCTOR_DELEGATION_CHAIN`, WARNING, `:433`
- `engine/inject/DelegationChains.java:93` — one weave attached to two or more **distinct constructor descriptors** of the target, and one of them `this()`-delegates to another — remedy `"…Attach to the constructor the chain ends at — the one that calls super() rather than this() — if the handler should run once per object"` — supported. Called from `WeavingPipeline.java:217`.
- **Engine only.** Two declarations of one weave on the same constructor collapse to one and are not a chain (`DelegationChains.java:60`).
- **The catalogue's summary string ends `"… (spike 4)"`** — `DiagnosticCode.java:434`. It is user-visible text; nothing else in the range carries such a marker.

### Group F — `@Local` captures

Every site is in the engine, and all of them run from
`WeavingPipeline.bindPerSite` → `LocalCaptures.resolve` (`WeavingPipeline.java:475`).

**AW1050** `LOCAL_NOT_RESOLVABLE`, ERROR, `:624` — five sites, one per strategy plus a type check.
- `engine/inject/LocalCaptures.java:218` (`bySlot`) — the slot is occupied there by a type the parameter's declared type does not accept — remedy `"slots are assigned by the compiler and are reused once a scope ends; capture by name instead, or correct the slot"`.
- `:259` (`byName`) — the name is **not live at the site**, whether or not it exists elsewhere in the table — remedy `"a variable declared later in the method, or one whose scope has already ended, does not match even though it exists somewhere in the table — pick one from the listing above, or inject where the variable is live"`.
- `:304` (`byOrdinal`) — the ordinal is past the number of live candidates of that type — remedy `"ordinals are counted in slot order over the locals of the parameter's type that are live here, from zero"`.
- `:349` (`byType`) — no live local of the declared type — remedy `"name the variable with @Local(name = \"…\"), or inject where one of that type is live"`.
- `:402` (`checked`) — a name resolved but `Assignability` refuses the declared type — remedy `"declare the parameter with the variable's own type"`.
- All five carry `liveAt(locals, site)` as details. All supported.

**AW1051** `LOCAL_AMBIGUOUS`, ERROR, `:637`
- `LocalCaptures.java:360` — capture by type alone, and more than one live candidate; the **candidates** are listed rather than the live locals (`:328`) — remedy `"say which: @Local(name = \"…\") is the readable form, @Local(ordinal = n) the positional one…"` — supported.

**AW1052** `LOCAL_VARIABLE_TABLE_MISSING`, ERROR, `:650`
- `LocalCaptures.java:165` — the strategy is anything other than `BY_SLOT` and `locals.isAvailable()` is false — remedy `"recompile the target with -g, or capture by index = <slot> having read its bytecode — the engine will not infer a slot from the method's shape, because a wrong slot reads a different value rather than failing"` — supported.

**AW1053** `LOCAL_MUTABLE_NEEDS_REF`, ERROR, `:662`
- `engine/inject/LocalRefs.java:134` — `local.mutable()` and the parameter is not a carrier type — remedy `"…Declare the parameter as LocalRef<T> — or LocalIntRef and friends for a primitive — which is what carries the write back into the target's slot"`.
- `processor/HandlerChecks.java:183` — the same, read off the mirror; `mutable` counts only where the source wrote `true` (`:178`) — remedy `"…Declare it as LocalRef<T>, or LocalIntRef and friends for a primitive"`.
- Both supported. Both are checked **before** the capture is resolved, so a declaration wrong in this way never also produces a slot message (`LocalRefs.java:117`).

**AW1054** `LOCAL_REF_WITHOUT_MUTABLE`, ERROR, `:675`
- `engine/inject/LocalRefs.java:147` and `processor/HandlerChecks.java:192` — a carrier-typed parameter carrying `@Local` without `mutable = true` — remedy at both `"add mutable = true if the handler means to write the variable, or declare the parameter as the variable's own type if it only reads it"` — supported.
- A carrier-typed parameter with **no** `@Local` at all is not a capture and is left alone (`HandlerChecks.java:164`).

### Group G — `@Redirect` and `@Wrap`

**AW1060** `DUPLICATE_REDIRECT`, ERROR, `:692`
- `engine/plan/ConflictDetector.java:164` — two or more claimants of one call-site key, **at least one of them a `@Redirect`**; any number of wraps alone is passed over (`:159`) — two remedies, chosen by whether the set is mixed (`:174`): mixed → `"a redirect removes the operation, and a wrap hands that same operation to its handler… Make both of them @Wrap, which nests, or narrow one with an ordinal or a slice"`; all redirects → `"a call has one callee, so two redirects of it cannot both apply…"` — supported.
- The site key is built **from the text the author wrote** (`callSiteOf`, `ConflictDetector.java:428`), so two declarations naming one call site in different words are not seen as sharing it (`ConflictDetector.java:30`).
- Single site; reported during planning, which does not abort (`WeavePlanner.java:27`).

**AW1061** `OPERATION_TARGET_UNSUPPORTED`, ERROR, `:710` — eight sites.
- `processor/HandlerChecks.java:414` — a `@Redirect`/`@Wrap` naming a built-in point outside `REDIRECTABLE`; **an omitted `value` means `HEAD` and is exactly as wrong as a wrong one** (`:409`) — remedy `"use @Inject for a position, or point the @Redirect/@Wrap at the call, field access or instantiation you mean to replace/wrap"`.
- `engine/inject/RedirectInjector.java:122` and `engine/inject/WrapInjector.java:186` — the same test at weave time; a **contributed** point is not checked against the list and is judged by the shape it resolves to.
- `engine/inject/point/SiteSafety.java:205` — the resolved site's kind is `AFTER_ELEMENT`, i.e. the position after an operation — remedy `"…@Inject is what adds code there, and it is what INVOKE_AFTER exists for…"`.
- `RedirectInjector.java:225` / `WrapInjector.java:284` — `RedirectedOperation.at` returned null: the instruction is not a call, a field access or an instantiation — remedy `"a redirect replaces / a wrap takes over a call, a field access or an instantiation; @Inject is what adds code at an arbitrary position"`.
- `RedirectInjector.java:262` / `WrapInjector.java:327` — an instantiation whose constructor call could not be located — remedy **`"this is a body shape the engine does not understand; report it with the class file rather than working around it"`**. This is the one shape in the range whose remedy is *file a bug*, and the reader's declaration is not at fault.
- All supported.

**AW1062** `WRAP_PARAMETERS_AFTER_OPERATION`, ERROR, `:729`
- `engine/inject/WrapInjector.java:166` — the parameter list is non-empty and the **last** parameter is not `Operation`. This fires for a handler with **no** `Operation` at all, so such a handler reports `AW1062` **and** `AW1063` (`:176`).
- `processor/HandlerChecks.java:268` — the last declared `Operation` is not the last parameter; the no-`Operation` case returns first (`:265`), so the processor reports only `AW1063` there. Caret on the first offending parameter.
- Remedy at both: `"the Operation must be last. A @Redirect handler may append the enclosing method's parameters, and a wrap handler may not…"` — supported.
- **A handler declaring two `Operation` parameters satisfies the processor's rule as long as the second is last** (`HandlerChecks.java:229`).

**AW1063** `WRAP_OPERATION_MISSING`, ERROR, `:742`
- `engine/inject/WrapInjector.java:176` — `!handler.type().parameterList().contains(CD_OPERATION)`.
- `processor/HandlerChecks.java:258` — no parameter whose erased name is `Operation`; returns.
- Remedy at both: `"add a trailing Operation<R> parameter, where R is the operation's result type boxed — or use @Redirect, which replaces the operation instead of wrapping it and needs no handle to it"` — supported.

### Group H1 — `@Shadow`

**AW1030** `FIELD_NOT_FOUND`, ERROR, `:450` — four sites.
- `processor/MemberChecks.java:330` — a `@Shadow` field the target does not declare; the target's own field names are details — remedy `"check the spelling, or the target's version"`.
- `processor/MemberChecks.java:460` — an `@Accessor` whose field name (written, or inferred by stripping `get`/`set`/`is`) is not declared — remedy `"name the field with @Accessor(\"…\") when it cannot be inferred from the method's name"`.
- `engine/merge/MemberBindings.java:247` — the same for a `@Shadow` at merge time; **no field listing** — remedy `"a @Shadow declaration is a promise that the target has this member; check the name, or the target's version"`.
- `engine/merge/GeneratedMembers.java:94` — the same for an `@Accessor` — remedy `"name the field explicitly with @Accessor(\"…\") if the inference from the method name picked the wrong one"`.
- All supported. Inherited fields never count: `fieldOf` searches enclosed elements only (`MemberChecks.java:560`).

**AW1031** `SHADOW_TYPE_MISMATCH`, ERROR, `:463` — three sites, two conditions.
- `processor/MemberChecks.java:339` — erasures of the shadowed field's declared and actual types differ — **no remedy at all**, message only.
- `engine/merge/MemberBindings.java:258` — `ClassDesc` inequality at merge time — **no remedy at all**, message only.
- `engine/merge/GeneratedMembers.java:179` (`shapeFits`) — a generated accessor that is neither a read nor a write of the field — remedy `"a getter takes nothing and returns the field's type; a setter takes the field's type and returns void"` — supported.
- **Two of three sites ship no remedy.** A page writing this code must supply the imperative itself: *declare the member with the type the target declares* (`DiagnosticCode.java:472`).

**AW1032** `SHADOW_FIELD_INITIALISER_IGNORED`, WARNING, `:477`
- `engine/parse/WeaveClassParser.java:380` — a `@Shadow` field carrying a `ConstantValue` attribute (`:371`) — remedy `"delete the initialiser — the target's own value is what the weave reads"`.
- `processor/MemberChecks.java:190` — `VariableElement.getConstantValue() != null` — same remedy.
- **The limit is severe and measured**: only a *constant variable* in the JLS sense is seen. `final Integer d = 5` and `final Object i = "s"` both return `null` from `getConstantValue()` on Temurin 25.0.3+9, and `private long startedAt = System.nanoTime()` produces nothing at all — `MemberChecks.java:165`–`:170`. Every other initialiser compiles into `<init>`/`<clinit>` and surfaces as `AW1081`/`AW1082`.
- A `@Shadow` field in a **static** weave never reaches this: `reportPointless` returns first (`MemberChecks.java:100`, `:80`).

**AW1033** `SHADOW_REMOVES_FINAL`, WARNING, `:495`
- `processor/MemberChecks.java:348` — `mutable = true` and the target's field is `final` — remedy `"nothing needs doing; drop mutable = true if the weave only reads the field, so that the target keeps the guarantee it declared"`; detail says the change is structural and unavailable under retransformation.
- `engine/merge/MemberBindings.java:268` — the same, and the field is added to `unfinalised` so the target is rewritten (`:266`) — same remedy, with a different detail for a `static final` field of constant type: javac has already inlined it at every call site compiled against it.
- Supported. This is a **warning that reports work being done**, not a refusal.

**AW1034** `SHADOW_OF_LOWER_PRIORITY_MEMBER`, ERROR, `:509`
- `engine/plan/ConflictDetector.java:376` — a `@Shadow` whose named member another weave merges into the same target, where that other weave's `priority()` is **not strictly greater** and it is not the same weave (`:371`) — remedy `"give the adding weave a strictly higher priority than <n>. Equal priority is not enough — the tie is broken by class name, which is stable but arbitrary…"` — supported.
- Additions are keyed **by name alone, without the descriptor** (`ConflictDetector.java:338`), so a shadow is compared against every merged member of that name.
- Single site, in planning. Listed in `ConflictDetector.reportableCodes()` (`:468`).

### Group H2 — merged members

**AW1080** `MERGED_MEMBER_COLLIDES`, ERROR, `:801` — six sites, three questions.
- `engine/merge/MemberBindings.java:177` — a **handler** whose name+descriptor the target already declares — remedy `"rename the handler. A handler cannot be @Unique — the injection sites call it by name, so a renamed one would be called under a name that no longer exists"`.
- `engine/merge/MemberBindings.java:348` — a merged member colliding and **not** `@Unique`; a field collides on its name alone, a method on name+descriptor (`:342`) — remedy `"declare the member @Unique to have it renamed instead, or rename it yourself…"`.
- `processor/MemberChecks.java:420` — the same at compile time; a member already `@Unique` is exempt (`:418`) — same remedy.
- `engine/plan/ConflictDetector.java:236` — **two or more weaves** dissolving a handler of the same name and descriptor into one target; only weaves that `dissolves(...)` are considered, so two static weaves never collide (`:209`) — remedy `"rename all but one of them, or make the weaves static — a static weave's handler stays where it is and is called there, so two of them never meet"`.
- `engine/plan/ConflictDetector.java:311` — two or more claims on one merged member; **excused only when every claimant is `@Unique`** (`:308`), and this counts claims rather than distinct weaves, so one weave declaring a member twice is reported against itself (`:276`) — remedy `"mark every one of them @Unique so each is mangled to its own private name, or rename all but one. Marking only some does not help: a mangled member and a plainly named one still collide on the plain name"`.
- All supported.

**AW1083** `MERGED_OBJECT_METHOD`, WARNING, `:840`
- `engine/parse/WeaveClassParser.java:586` — the merged method's `name + descriptor` is in `OBJECT_METHODS` — remedy `"make sure this is meant: collections, debuggers and logging all call these without the target's author being able to see it happen"`.
- `processor/MemberChecks.java:230` — the same, matched on the whole erased signature, so an overload sharing only the name is left alone (`:217`) — same remedy.
- The set is `toString()`, `equals(java.lang.Object)`, `hashCode()`, `main(java.lang.String[])` (`MemberChecks.java:57`). **The merge is performed**; nothing is refused.

**AW1088** `MERGE_FIELD_INTO_RECORD`, ERROR, `:899`
- `engine/merge/MemberBindings.java:406` and `processor/MemberChecks.java:392` — a **non-static** merged field and the target is a record; both return so no collision check follows — remedy at both `"a record's equals, hashCode, toString and accessors are all derived from its components, so a merged field is state that every one of them ignores. Declare the field static, or keep the state outside the record"` — supported.
- **`@Unique` does not exempt it**: `@Unique` changes the name, not the target's shape (`MemberChecks.java:377`).

**AW1089** `MERGE_FIELD_INTO_ENUM`, WARNING, `:914`
- `engine/merge/MemberBindings.java:417` and `processor/MemberChecks.java:403` — a non-static merged field into an enum; **the case does not return**, so such a field can also report `AW1080` — remedy at both `"nothing needs doing if the default value is what you want; otherwise write the field from an @Inject at the enum constructor's HEAD"` — supported. The field is added and holds the JVM default.

**AW1093** `MERGED_FIELD_INITIALISER_IGNORED`, INFO, `:969`
- `engine/parse/WeaveClassParser.java:397` — a non-`@Shadow` field carrying `ConstantValue` — remedy `"write the value from an @Inject at the target constructor's HEAD, which is the only place that runs once per instance"`.
- `processor/MemberChecks.java:199` — `getConstantValue() != null` on a non-shadow field — same remedy.
- Same `getConstantValue()` limit as `AW1032`. An instance-field initialiser with no hand-written constructor is caught as `AW1081` **at weave time only** (`DiagnosticCode.java:962`).

**AW1094** `UNIQUE_MEMBER_MANGLED`, INFO, `:980`
- `engine/merge/MemberBindings.java:360` — the member is `@Unique`, collides, and `silent()` is false — remedy `"nothing needs doing; declare @Unique(silent = true) to stop saying so. The name appears in stack traces and profiles of the woven class, which is why it is worth hearing once"` — supported.
- **Engine only.** Reported once, and only when there really was a collision.

### Group H3 — generated `@Accessor` / `@Invoker`

**AW1095** `GENERATED_MEMBER_COLLIDES`, ERROR, `:992`
- `engine/merge/GeneratedMembers.java:291` (`isFree`) — the target already declares the name+descriptor the member would be generated under — remedy `"rename the accessor or invoker; generating over the target's own method would replace working code"`.
- `processor/MemberChecks.java:540` — the same. **The signature compared is the declaration's, not the member it names**: an invoker called `run()` that resolves perfectly still collides when the target declares `run()` (`:524`) — remedy `"rename the declaration; a generated member cannot be @Unique, because callers reach it by the name it is declared under"`.
- Both supported.

**AW1097** `ACCESSOR_WRITES_FINAL_FIELD`, ERROR, `:1019`
- `engine/merge/GeneratedMembers.java:115` — `!accessor.isGetter()` and the field is `final`; returns, so no `AW1095` follows.
- `processor/MemberChecks.java:471` — the accessor takes a parameter and the field is `final`; returns.
- Remedy at both: `"a final field is written once, by the constructor. Use @Shadow(mutable = true), which removes the flag deliberately and says so — an accessor has no way to express that intent"` — supported, and it hands the reader straight to `AW1033`.
- Both sites add the detail that the class **would verify** and throws `IllegalAccessError` at the first call.

### Group I — static weaves

**AW1090** `SHADOW_IN_STATIC_WEAVE`, ERROR, `:927`
- `engine/parse/WeaveClassParser.java:375` (field path) and `:553` (method path), both through `reportStaticWeaveMember` (`:430`) — the weave's `kind` is `STATIC` and the member carries `@Shadow`; the member is then modelled as nothing — remedy `"declare the weave @Weave(kind = Kind.INSTANCE) if it is meant to be merged, or reach the target's state through the handler's parameters instead"`.
- `processor/MemberChecks.java:136` → report at `:143` — `Disposition.SHADOW` in a static weave — the **same** remedy string.
- **Half the remedy is unsupported.** *Declare `kind = INSTANCE`* works. *Reach the target's state through the handler's parameters* does not: a handler's parameters are a prefix of the **target method's own parameters** (`HandlerBinding.java:45`), never the target instance, and slot 0 is skipped when the loads are built (`HandlerBinding.java:380`). There is no supported way for a static weave's handler to read the target's fields through its parameter list.
- **The catalogue text is contradicted outright.** `DiagnosticCode.java:934` tells the reader to *"reach the member through an `@Accessor` or an `@Invoker`, which a static weave can use"*. `StructuralWeaver.prepare` skips **every** weave whose `kind() != Weave.Kind.INSTANCE` (`StructuralWeaver.java:173`), and `GeneratedMembers.accessor`/`invoker` are reached only from `StructuralWeaver.emit` (`:265`, `:267`). An `@Accessor` in a static weave is therefore never generated onto anything, and nothing reports it: `reportPointless` covers `SHADOW` and `UNIQUE` only (`MemberChecks.java:135`–`:139`).

**AW1091** `UNIQUE_IN_STATIC_WEAVE`, ERROR, `:938`
- `engine/parse/WeaveClassParser.java:392` (field) and `:563` (method), same helper, same remedy string as `AW1090`.
- `processor/MemberChecks.java:137` → `:143` — `Disposition.UNIQUE` in a static weave.
- The catalogue's own remedy — *"Delete the annotation, or declare the weave `@Weave(kind = Kind.INSTANCE)`"* (`DiagnosticCode.java:944`) — is supported; the site's second clause has the same problem as `AW1090`'s.

### Group J — the target class's shape

**AW1092** `TARGET_IS_ANONYMOUS_OR_LOCAL`, WARNING, `:952`
- `engine/Weaver.java:691` — the parsed class carries an `EnclosingMethod` attribute; "local" or "anonymous" is chosen by `namedInSource(model)` — remedy `"…Target the enclosing class and narrow with a selector, or give the class a name"` — supported.
- **Engine only, and weaving proceeds**: the method returns `true` after reporting (`Weaver.java:672`).

### Reserved — verified

**AW1003** `RESERVED_1003`, ERROR, `DiagnosticCode.java:154`;
**AW1085** `RESERVED_1085`, **WARNING**, `:860`;
**AW1086** `RESERVED_1086`, ERROR, `:869`.

- Summary string for all three is `"(reserved)"`.
- `grep -rn "RESERVED_1003|RESERVED_1085|RESERVED_1086"` across every `*/src/main/java` and `*/src/test/java`: **no reference outside `DiagnosticCode.java`.** No reporting site, no test.
- `grep -rln "AW1003|AW1085|AW1086"` across the whole repository returns exactly two files: `DiagnosticCode.java` and `Writerside/topics/reference/diagnostics.topic`.
- `DiagnosticCode.java:80` names the same five reserved constants — `AW1003`, `AW1085`, `AW1086`, `AW2403`, `AW4002` — and states no build can produce one.
- **`AW1085` is declared `WARNING` while the other two are `ERROR`.** Nothing depends on it; it is the only visible difference between them.

---

## Identifiers

Constant names, exactly: `WEAVE_NO_TARGETS`, `WEAVE_DUPLICATE_TARGET_DECLARATION`, `RESERVED_1003`,
`WEAVE_TARGET_UNRESOLVABLE`, `STATIC_WEAVE_INSTANCE_HANDLER`, `WEAVE_HAS_SUPERCLASS`,
`WEAVE_IS_GENERIC`, `WEAVE_NOT_FINAL`, `WEAVE_TARGET_PREFER_CLASS_LITERAL`,
`SELECTOR_OWNER_UNRESOLVABLE`, `SELECTOR_SYNTAX_ERROR`, `SELECTOR_TYPE_ARGUMENTS_IGNORED`,
`SELECTOR_MISSING_DESC_PREFIX`, `SELECTOR_MALFORMED_DESCRIPTOR`,
`SELECTOR_DESCRIPTOR_MISSING_RETURN_TYPE`, `METHOD_NOT_FOUND`, `SELECTOR_AMBIGUOUS`,
`SELECTOR_WILDCARD_TOO_BROAD`, `TARGET_METHOD_ABSTRACT`, `TARGET_METHOD_SYNTHETIC`,
`TARGET_METHOD_NATIVE`, `THIS_UNAVAILABLE_BEFORE_SUPER_CALL`, `CONSTRUCTOR_DELEGATION_CHAIN`,
`FIELD_NOT_FOUND`, `SHADOW_TYPE_MISMATCH`, `SHADOW_FIELD_INITIALISER_IGNORED`,
`SHADOW_REMOVES_FINAL`, `SHADOW_OF_LOWER_PRIORITY_MEMBER`, `HANDLER_PARAMETERS_NOT_PREFIX`,
`HANDLER_RETURN_TYPE_NOT_VOID`, `HANDLER_NOT_ACCESSIBLE`, `NO_INJECTION_POINT_MATCHED`,
`TOO_MANY_INJECTION_POINTS`, `LOCAL_NOT_RESOLVABLE`, `LOCAL_AMBIGUOUS`,
`LOCAL_VARIABLE_TABLE_MISSING`, `LOCAL_MUTABLE_NEEDS_REF`, `LOCAL_REF_WITHOUT_MUTABLE`,
`DUPLICATE_REDIRECT`, `OPERATION_TARGET_UNSUPPORTED`, `WRAP_PARAMETERS_AFTER_OPERATION`,
`WRAP_OPERATION_MISSING`, `CANCEL_ON_NON_VOID_TARGET`, `CALLBACK_TYPE_MISMATCH`,
`CALLBACK_VALUE_UNAVAILABLE`, `MERGED_MEMBER_COLLIDES`, `WEAVE_DECLARES_CONSTRUCTOR`,
`WEAVE_DECLARES_STATIC_INITIALISER`, `MERGED_OBJECT_METHOD`, `WEAVE_IMPLEMENTS_INTERFACE`,
`RESERVED_1085`, `RESERVED_1086`, `WEAVE_TARGETS_WEAVE`, `MERGE_FIELD_INTO_RECORD`,
`MERGE_FIELD_INTO_ENUM`, `SHADOW_IN_STATIC_WEAVE`, `UNIQUE_IN_STATIC_WEAVE`,
`TARGET_IS_ANONYMOUS_OR_LOCAL`, `MERGED_FIELD_INITIALISER_IGNORED`, `UNIQUE_MEMBER_MANGLED`,
`GENERATED_MEMBER_COLLIDES`, `WEAVE_BYTES_UNAVAILABLE`, `ACCESSOR_WRITES_FINAL_FIELD`.

Summary strings a reader will see in a log (`DiagnosticCode` third argument), spelled as the source
spells them — a selection the page is likely to quote:

| Code | `summary()` |
| --- | --- |
| AW1001 | `@Weave declares no targets` |
| AW1002 | `Both value() and targets() given` |
| AW1004 | `Target is not resolvable and require = REQUIRED` |
| AW1005 | `Static weave declares a non-static handler` |
| AW1015 | `Selector syntax error (with offset)` |
| AW1016 | `Type arguments in a selector were ignored (erasure)` |
| AW1021 | `Selector is ambiguous (overloads listed)` |
| AW1027 | `One weave targets several constructors in the same this() delegation chain — the handler will fire once per constructor (spike 4)` |
| AW1040 | `Handler parameters are not a prefix of the target's` |
| AW1043 | `No injection point matched` |
| AW1044 | `More points matched than allow permits` |
| AW1052 | `Target has no LocalVariableTable; recompile with -g` |
| AW1071 | `ReturnableCallback<T> where T ≠ the target's return type` |
| AW1084 | `Weave class implements an interface (0.1.0)` |
| AW1093 | `Merged field has an initialiser (ignored; use an @Inject at constructor HEAD)` |

Annotation elements named in remedies: `@Weave(value =, targets =, kind =, require =, priority =)`,
`Weave.Kind.STATIC`, `Weave.Kind.INSTANCE`, `Require.REQUIRED`, `Require.OPTIONAL`,
`@Inject(method =, at =, require =, allow =, group =, id =)`, `@At(value =, target =, ordinal =,
shift =, slice =)`, `Point.HEAD`, `@Local(name =, index =, ordinal =, mutable =)`,
`@Shadow(value =, mutable =)`, `@Unique(silent =)`, `@Accessor("…")`, `@Invoker("…")`,
`@Group(name =, min =, max =)`, `Operation<R>`, `Callback`, `ReturnableCallback<T>`, `LocalRef<T>`,
`LocalIntRef`, `@Result`.

Configuration and API names: `aether.weaver.failOnError` (Maven parameter,
`AbstractWeaveMojo.java:97`, default `true`), `WeaverBuilder.weaveBytes(WeaveBytes)`
(`WeaverBuilder.java:135`), `WeaverBuilder.classSource(ClassSource)` (`:151`),
`WeaverBuilder.diagnostics(DiagnosticListener)` (`:212`), `WeaveBytes.NONE` (`:61`), `javac -g`.

Sentinels: `allow = 0` means **no upper bound** (`MatchAccounting.java:83` via `isBounded`,
`DiagnosticCode.java:609`). `require` omitted becomes **1** in the engine and stays **0** at compile
time (`WeaveClassParser.java:768`, `PointChecks.java:111`). `@Shadow("")` and `@Accessor("")` fall
back to the member's own name / a name inferred from it (`WeaveClassParser.java:386`,
`MemberChecks.java:455`). `@At` `value` omitted means `HEAD` (`HandlerChecks.java:410`).

---

## Surprises

1. **`AW1005`'s remedy at two of its six sites is not a shape the engine accepts.**
   `WeaveClassParser.java:501` and `WeaveProcessor.java:631` both say *"declare it static and take
   the target as the first parameter"*. `HandlerBinding.bind` compares a static handler's parameters
   against the target method's **declared parameters** (`HandlerBinding.java:398`), skips slot 0
   (`:380`), and pushes a receiver only for a non-static handler (`:676`). The shape is refused at
   weave time as `AW1040`. The processor deliberately tolerates it (`HandlerChecks.java:817`) and a
   test asserts the compile is clean (`WeaveProcessorTest.java:886`), so the failure appears only
   after javac has passed.

2. **`AW1090`'s catalogue text recommends a route the merge stage skips.** `DiagnosticCode.java:934`
   sends a static weave to `@Accessor`/`@Invoker`; `StructuralWeaver.java:173` skips every weave
   whose `kind() != INSTANCE` before any generated member is emitted, and nothing reports the
   declaration. `AW1090`'s *site* remedy is different again and its second clause ("reach the
   target's state through the handler's parameters") is unreachable for the same reason as (1).

3. **`AW1016` fires on `<init>`.** The test is `text.indexOf('<') >= 0`
   (`SelectorChecks.java:119`), so `method = "<init>()"` is reported as carrying type arguments. The
   source says so at `SelectorChecks.java:57`. The same selector then resolves against a compiled
   target and reports `AW1020` against the source model, because only `ElementKind.METHOD` members
   are candidates there (`HandlerChecks.java:586`).

4. **A mistyped `group` name silently disables `require` and `allow`.** `MatchAccounting.check`
   accounts a grouped declaration only through its group, and a group name absent from `groups` is
   never read back — *"no `AW1043`, no `AW1044`, nothing"* (`MatchAccounting.java:44`, code `:79`).

5. **Three sites ship no remedy at all**, and two more ship none for a whole family:
   `AW1007` (both sites), `AW1084` (both sites), `AW1082`, and `AW1031` at two of its three sites.
   `AW1015`, `AW1018` and `AW1019` reach the reader with no remedy whenever they come from the
   grammar, because only `AW1017` carries a `suggestion` (`SelectorParser.java:152` is the sole call
   passing one). Any page mentioning these must write the imperative itself — the gate requires one
   in the same block.

6. **`AW1070` and the type-argument half of `AW1071` never both run at the same stage.** A plain
   `Callback` on a value-returning target is caught only at weave time (`HandlerBinding.java:501`);
   a wrong `ReturnableCallback<T>` argument is caught only at compile time
   (`HandlerChecks.java:896`), the type argument being erased in the class file.

7. **`AW1087` has a third site that the shipped path cannot reach.**
   `DefaultWeavePolicy.java:118` reads `WeaveTarget.declaredWeaveClass()`, and `Weaver` always
   constructs the target with `false` (`Weaver.java:406`). A policy denial also arrives with no
   remedy field at all (`Weaver.java:409`).

8. **`AW1062` and `AW1063` are reported together by the engine and separately by the processor.**
   `WrapInjector.java:166` checks the last parameter whenever the list is non-empty, including for a
   handler with no `Operation`, so both codes appear; `HandlerChecks.java:264` returns first.

9. **The processor checks a static weave's members as though they would be merged.**
   `MemberChecks.againstTarget` states *"The weave's kind is not consulted here"* (`:248`), so
   `AW1080`, `AW1088`, `AW1089`, `AW1030`, `AW1095` and `AW1097` are reported for members of a static
   weave that the engine will never merge onto anything.

10. **`AW1027`'s catalogue summary ends `"(spike 4)"`** — `DiagnosticCode.java:434`. It is the string
    `summary()` returns and it is user-visible.

11. **A `final` field's initialiser is the only initialiser either stage sees.** `AW1032` and
    `AW1093` rest on `ConstantValue` / `getConstantValue()`; `final Integer d = 5` and
    `private long startedAt = System.nanoTime()` are dropped in silence
    (`MemberChecks.java:165`–`:170`, `WeaveClassParser.java:368`).

12. **Conflict detection does not stop the plan.** `AW1087`, `AW1060`, `AW1080` and `AW1034` from
    `ConflictDetector` are reported during `WeaverBuilder.build()` and a plan is returned regardless
    (`WeavePlanner.java:27`); whether the run fails is the driver's decision — under Maven,
    `failOnError` (`AbstractWeaveMojo.java:414`).

---

## Could not establish

- **Whether a static weave's handler can reach the target instance at all.** `LocalCaptures.bySlot`
  (`LocalCaptures.java:210`) is the only path that needs no `LocalVariableTable`, and slot 0 of an
  instance method holds `this`, so `@Local(index = 0) Target self` looks as though it would bind.
  No test in any module exercises it and no JavaDoc sanctions it. Settling it needs a testkit run
  weaving such a handler, which is a measurement rather than a reading.
- **Whether `AW1006` can be produced at all by the engine parser for an interface weave.**
  `WeaveProcessor.checkShape` exempts an interface explicitly (`:547`); `WeaveClassParser` reads
  `model.superclass()`, which is present for an interface class file. No test covers `AW1006` in the
  seven reactor modules, so the interface case is unverified either way.
- **What a driver other than Maven does with an `ERROR` in this range.** The agent prints to
  `System.err` (`WeaverAgent.java:349`) and the class loader and testkit collect; whether any of them
  aborts a load is `R-diag-2`'s subject, not readable from these sites.
- **Whether any AW10xx code can be *lowered* to a suppressible severity in practice.** The mechanism
  exists (`Diagnostic.Builder.severity`, `DiagnosticCode.java:73`) and the only caller in
  `src/main/java` passes the code's own default (`WeaveClassParser.java:1101`). A plugin could do it;
  no shipped code does.

---

## Not this page

- **`AW1101`, `AW1102`, `AW1103`, `AW1105`, `AW1110`, `AW1111`, `AW1112`, `AW1120`–`AW1122`,
  `AW1130`** — reported from the same files this dossier covers (`PointResolver`, `SiteSafety`,
  `BuiltInPoints`), which the `AW11xx` page will need. In particular `SiteSafety.java:152`
  (`SITE_IN_UNINITIALISED_WINDOW`) and `:165` (`SITE_IN_DEAD_CODE`) sit in the same loop as
  `AW1026`, and `SHIFT_NOT_SUPPORTED` is raised from `RedirectInjector.java:132` and
  `WrapInjector.java:196` alongside `AW1061`. → **R-diag-1b (AW11xx/AW1200)**
- **`AW2101`** — named by `AW1033`'s catalogue text (`DiagnosticCode.java:490`) as what a load-time
  driver reports when a structural change reaches an already-loaded target;
  `STRUCTURAL_WEAVE_NEEDS_PRELOAD` at `DiagnosticCode.java:1447`. → **R-diag-2**
- **`AW2003`, `AW3001`, `AW3002`, `POLICY_DENIED_SELF_WEAVE`, `POLICY_DENIED_JDK_PACKAGE`** — the
  other branches of `DefaultWeavePolicy.decide` (`:123`, `:129`). → **R-diag-3**
- **`AW4090`, `INTERNAL_ERROR`** — `AbstractWeaveMojo.java:357`. → **R-diag-4**
- **`aether.weaver.failOnError`**, its default and what it changes — the rule belongs to the Maven
  plugin reference page; this dossier records it only because it decides whether an `ERROR` in this
  range stops a build.
- **`ConflictDetector.reportableCodes()`** as an engine-internals fact
  (`ConflictDetector.java:464`) — a concepts page on planning, not a code reference.
