# R-diag-1-injection — AW11xx injection-point codes and AW1200

Scope: `AW1101 AW1102 AW1103 AW1104 AW1105 AW1110 AW1111 AW1112 AW1120 AW1121 AW1122 AW1130
AW1131 AW1200`. Paths below are repo-relative.

Abbreviations used for file paths:

- `PR` = `aether-weaver-engine/src/main/java/de/splatgames/aether/weaver/engine/inject/point/PointResolver.java`
- `SS` = `aether-weaver-engine/src/main/java/de/splatgames/aether/weaver/engine/inject/point/SiteSafety.java`
- `BIP` = `aether-weaver-engine/src/main/java/de/splatgames/aether/weaver/engine/inject/point/BuiltInPoints.java`
- `WP` = `aether-weaver-engine/src/main/java/de/splatgames/aether/weaver/engine/inject/WeavingPipeline.java`
- `II` = `aether-weaver-engine/src/main/java/de/splatgames/aether/weaver/engine/inject/InjectInjector.java`
- `RI` = `aether-weaver-engine/src/main/java/de/splatgames/aether/weaver/engine/inject/RedirectInjector.java`
- `WI` = `aether-weaver-engine/src/main/java/de/splatgames/aether/weaver/engine/inject/WrapInjector.java`
- `DC` = `aether-weaver-api/src/main/java/de/splatgames/aether/weaver/api/diagnostic/DiagnosticCode.java`
- `TB` = `aether-weaver-processor/src/main/java/de/splatgames/aether/weaver/processor/TargetBytes.java`
- `PC` = `aether-weaver-processor/src/main/java/de/splatgames/aether/weaver/processor/PointChecks.java`
- `SPEC` = `aether-weaver-processor/src/main/java/de/splatgames/aether/weaver/processor/SourceSpecs.java`

---

## Facts

### Frame: what runs, in what order, and where the registry comes from

- **`PointResolver.resolve` runs five stages in a fixed order: slice, find, ordinal, shift,
  safety.** — `PR:140` (slice), `PR:147-159` (find + index translation), `PR:162` (ordinal),
  `PR:169` (shift), `PR:172` (`SiteSafety.usable`).
- **A refusal in any stage returns `List.of()` for that one `@At` only, not for the
  declaration.** A declaration with several `@At`s has each resolved separately by the caller
  loop and their sites merged. — `PR:135`, `PR:142`, `PR:159`, `PR:165`; caller loop
  `WP:168-183`.
- **`@At` element names as the reader types them:** `value` (default `Point.HEAD`), `custom`
  (default `""`), `target` (`""`), `ordinal` (`-1`), `shift` (`Shift.NONE`), `by` (`0`),
  `access` (`Access.ANY`), `slice` (`""`). —
  `aether-weaver-api/src/main/java/de/splatgames/aether/weaver/api/At.java:92,107,132,150,168,181,191,203`.
- **The built-in point identifiers are exactly the `Point` enum constant names**, registered
  into a `LinkedHashMap` then `Map.copyOf`: `HEAD`, `RETURN`, `TAIL`, `INVOKE`,
  `INVOKE_AFTER`, `FIELD`, `NEW`, `CONSTANT`, `THROW`. — `BIP:125-136`;
  `aether-weaver-api/src/main/java/de/splatgames/aether/weaver/api/Point.java:113,136,157,182,204,226,247,273`
  (`THROW` is the ninth, past line 273).
- **`INVOKE` and `INVOKE_AFTER` share one implementation, `InvokePoint`, differing only in
  the `Site.Kind` returned.** — `BIP:130-131`, `BIP:433-434`, `BIP:495-497`.
- **The weaver's point registry is the plugin registry, and the only plugin ever installed by
  `WeaverBuilder.build()` is `CorePlugin`.** — `aether-weaver-engine/.../Weaver.java:199-202`
  (`plugins.points().lookup(id, DiagnosticListener.NOOP).map(f -> f.create(id)).orElse(null)`);
  `aether-weaver-engine/.../WeaverBuilder.java:371-372`
  (`PluginLoader.load(new CorePlugin(), discovered, ...)`).
- **`CorePlugin`'s point factory serves exactly `BuiltInPoints.all().keySet()` and declares no
  aliases.** — `aether-weaver-engine/.../inject/CorePlugin.java:245` (`ids()`),
  `:255-261` (`aliases()` returns `Set.of()`), `:280-286` (`create`).
- **Third-party points are unreachable from every shipped driver.** `discoverPlugins(ClassLoader)`
  is declared at `WeaverBuilder.java:244` and `plugin(WeaverPlugin)` at `WeaverBuilder.java:230`;
  neither is called anywhere under any module's `src/main/java`. The default
  `discoveryLoader` is `null`, meaning "search none". — `WeaverBuilder.java:80`,
  `WeaverBuilder.java:367-369`. The testkit builds its weaver with
  `Weaver.builder().weaves(...).classSource(...).verification(...).diagnostics(...).build()`
  and no plugin call —
  `aether-weaver-testkit/src/main/java/de/splatgames/aether/weaver/testkit/Weaving.java:152-157`.
- **A missing entry and an alias whose replacement is unregistered both come back as
  `Optional.empty()`, which the `Weaver` maps to `null` and the resolver reads as AW1101.** —
  `aether-weaver-engine/.../plugin/NamespacedRegistry.java:111-126`.
- **A contributed point identifier (one containing `:`) is resolved inside `PluginIsolation`;
  a built-in one is called directly.** — `WP:323-329`.
- **No site in this range calls `.severity(...)`.** The only `.severity(` in product code is
  `aether-weaver-engine/.../parse/WeaveClassParser.java:1101`, outside this range. So every
  code in this range reports at its constant's declared severity.
- **`Diagnostic.of(code, message)` is `builder(code).message(message).build()` — no remedy.** —
  `aether-weaver-api/.../diagnostic/Diagnostic.java:138-140`; `remedy()` is
  `Optional.ofNullable` — `:239-241`.

### Where the compiler raises the same codes

- **The annotation processor runs the *same* `PointResolver`, over the target's compiled class
  file, at compile time.** — `PC:164-168`
  (`new PointResolver(BuiltInPoints.all()::get).resolve(method, code, spec, bridge)`).
- **The compile-time registry is `BuiltInPoints.all()` and nothing else**, so a
  `@At(custom = "ns:NAME")` is unknown at compile time and fails the build as AW1101 whether
  or not any plugin would register it at runtime. — `PC:53-57`, `PC:165`.
- **`SourceSpecs` drops the annotation's `slice` declarations, `@Local` captures and the
  `@Result` flag.** `InjectorSpec` is built with `List.of()` for slices and `List.of()` for
  captures. — `SPEC:49-51`, `SPEC:124-131` (`points, List.of(), id, require, allow, group,
  List.of()`).
- **`SourceSpecs` builds a `@Wrap` as `InjectorKind.INJECT`; `InjectorKind.WRAP` is never
  produced there.** — `SPEC:78-80`, `SPEC:124`.
- **Every compile-time report from this path is anchored on the `method` selector literal,
  whatever element the fault is really in.** — `PC:146-149`.
- **Severity mapping into `javac`: ERROR -> `Kind.ERROR`, WARNING -> `Kind.WARNING`,
  INFO -> `Kind.NOTE`, DEBUG -> `Kind.OTHER`.** —
  `aether-weaver-processor/.../MessagerReporter.java:157-163`.

---

## Per-code dossier

### AW1101 `INJECTION_POINT_UNKNOWN` — Severity.ERROR, Category.INJECTION_POINT

- Constant: `DC:1028`. Summary string: `"No injection point is registered under that identifier"`.
- **Site 1 — `PR:127-134`.** Condition: `this.points.apply(spec.point()) == null` for the
  `@At`'s own identifier, checked before anything else. Returns `List.of()`.
  Remedy: `"check the spelling; a contributed point is always namespace:NAME and needs its
  plugin on the classpath"`.
  Supported? **Only partly.** "Check the spelling" is actionable. "needs its plugin on the
  classpath" is **not reachable through any shipped driver**: no driver calls
  `WeaverBuilder.plugin(...)` or `discoverPlugins(...)`, and the discovery loader defaults to
  `null` (`WeaverBuilder.java:80`, `:367-369`, `:230`, `:244`). Putting a plugin jar on the
  classpath changes nothing under Maven, the agent, the weaving class loader or the testkit.
  The remedy is only satisfiable by a hand-built `Weaver`.
  Severity: ERROR, no override.
  Reachable? Yes — any `@At(custom = "…")` whose text is not one of the nine built-in names.
  Test: `PointResolverTest.unknownPointIsRefused` (`…/point/PointResolverTest.java:657-666`)
  for `"acme:NOT_INSTALLED"`, and `anUnqualifiedCustomIdentifierIsUnknown` (`:669-681`) for
  `"AFTER_LOGGING"`.
- **Site 2 (indirect, via a slice bound) — does not exist.** A slice bound naming an
  unregistered point reports AW1120/AW1121, not AW1101. — `PR:347-350`.
- **Also raised at compile time from the same line**, through `PC:165`, with the registry
  restricted to built-ins. A contributed point therefore fails the *build* as an error even
  where the weaver would have resolved it. — `PC:53-57`.
- **The unqualified case has no check of its own.** `"AFTER_LOGGING"` lands here because no
  factory can register an unqualified identifier, not because a namespace rule fired. —
  `PointResolverTest.java:672-680` (the test says so explicitly).

### AW1102 `SHIFT_NOT_SUPPORTED` — Severity.ERROR, Category.INJECTION_POINT

- Constant: `DC:1042`. Summary string: `"shift not supported by this point"` (lower case, no
  full stop, unlike its neighbours).
- **Site 1 — `PR:251-257`, from `checkShiftSupport` (`PR:248-259`).** Condition:
  `!point.supportsShift(spec.shift())`, asked once per `@At` *before* `find` is called, with
  whatever the declaration wrote, including `NONE`. Returns `false`, `resolve` returns
  `List.of()`.
  Remedy: `"remove the shift, or use a point that names the position you want directly — a
  shift that a point refuses would land somewhere the verifier rejects"`. Supported: yes.
  Severity: ERROR.
  **Of the nine built-in points, only `HEAD` can raise this**: `InjectionPoint.supportsShift`
  defaults to `true` for every shift
  (`aether-weaver-api/.../spi/InjectionPoint.java:218-220`), and `HeadPoint` is the only
  override — `BIP:178-180`, returning `shift == At.Shift.NONE`. Test:
  `PointResolverTest.shiftIsRefused` (`:98-104`), `HEAD` + `Shift.AFTER`, asserts
  `containsExactly("AW1102")`.
  Reachable? Yes, from every driver and from the compiler (`PC:88-90` lists AW1102 among what
  the compile-time resolver can report).
- **Site 2 — `RI:132-139`, in `RedirectInjector.validate`.** Condition:
  `point.shift() != At.Shift.NONE` for any `PointSpec` of a `@Redirect`, **whatever point it
  names, built-in or contributed** — the `isBuiltIn` guard on the preceding branch (`RI:121`)
  does not cover this one (`RI:131`).
  Remedy: `"a redirect replaces the operation it matches, so there is nothing for a shift to
  mean — a shifted position names a neighbouring instruction that the handler's signature does
  not describe"`. This is an explanation, not an instruction: it never says "remove the shift".
  Severity: ERROR; `WeavingPipeline.validates` turns any ERROR from `validate` into a refusal
  of the whole declaration (`WP:404-414`, `WP:198-205`).
  Test: `RedirectInjectorTest.shiftIsRefused` (`…/inject/RedirectInjectorTest.java:282-293`),
  `INVOKE` + `Shift.AFTER`.
- **Site 3 — `WI:196-203`, in `WrapInjector.validate`.** Same condition and same structure for
  `@Wrap`. Remedy: `"a wrap takes over the operation it matches, so there is nothing for a
  shift to mean — a shifted position names a neighbouring instruction that the handler's
  signature does not describe"`. Same caveat: no instruction to act on.
- **Limit worth stating: sites 2 and 3 only run if resolution produced at least one site.**
  `validate` is called at `WP:198`, after `if (sites.isEmpty()) continue;` at `WP:187-189`. A
  `@Redirect` at `HEAD` with a shift therefore reports AW1102 from site 1 only (the resolver
  refuses first); a `@Redirect` whose shift pushes the site out of the range reports AW1111
  and *not* AW1102, because there are then no sites to validate against.
- **Sites 2 and 3 are unreachable from the annotation processor**: `PointChecks` never calls
  `Injector.validate` — it calls only `PointResolver.resolve` and `MatchAccounting.check`
  (`PC:160-176`). And `SourceSpecs` never produces `InjectorKind.WRAP` (`SPEC:78-80`).

### AW1103 `SELECTOR_MATCHES_INVOKEDYNAMIC` — Severity.INFO, Category.INJECTION_POINT

- Constant: `DC:1056`. Summary: `"Selector would have matched an invokedynamic (lambda/concat)"`.
- **Sole site — `BIP:577-593`, from `reportHiddenByIndy`, called at `BIP:500-502`.**
  Condition: after `InvokePoint.find` has walked the body, `!hidden.isEmpty()` — where
  `hidden` holds, for every `InvokeDynamicInstruction` in the body, each bootstrap argument
  that is a `DirectMethodHandleDesc` whose owner/name/type the declaration's target also
  matches (`BIP:522-536`, `hiddenBy`).
  Remedy: `"a lambda, a method reference and string concatenation are invokedynamic
  instructions, and INVOKE matches ordinary calls only. The method behind them is invoked by
  the JVM rather than by this method, so inject into that method directly"`. Supported: yes —
  "inject into that method directly" is a real move, since a lambda body is compiled to a
  synthetic method of the same class.
  Severity: INFO, no override. Nothing is refused; matched ordinary calls still resolve.
- **The detail line says which of two situations this is.** `matched == 0` ->
  `"nothing else matched, so this injection attaches to no call at all"`; otherwise
  `"<n> ordinary call(s) did match and were woven"`. — `BIP:586-589`. The hidden list is capped
  at `MAX_LISTED = 10` (`BIP:68`, `BIP:585`).
- **AW1103 and AW1043 are independent and can both fire for one `@At`.** `sites.isEmpty()`
  reports "nothing matched" (`BIP:497-499`) and the hidden check runs afterwards regardless
  (`BIP:500`).
- **Reported from `InvokePoint` and from nowhere else** — `BIP:56`, and the whole-tree grep for
  `SELECTOR_MATCHES_INVOKEDYNAMIC` finds one product-code construction, `BIP:579`.
- **Never reported for a slice bound**, because a bound's `find` is run with `Reporter.NOOP`
  (`PR:351`) and its diagnostics discarded.
- Tests: `PointResolverTest.aMethodReferenceIsReported` (`:335-349`) — the ordinary call still
  resolves and `codes()` is exactly `AW1103`; `theReportNamesTheRealMethod` (`:351-364`) — the
  detail names the implementation method, not the functional interface's `get`;
  `anUnrelatedSelectorIsSilent` (`:366-374`).
- Reachable? Yes from every driver, and from the compiler (`PC:93-95`).

### AW1104 `INVOKE_AFTER_VOID_CALL` — Severity.ERROR, Category.INJECTION_POINT

- Constant: `DC:1069`. Summary: `"INVOKE_AFTER on a void call, handler expects a value"`.
- **Sole site — `II:213-227`, from `capturedKinds`, called at `II:171-172` inside
  `InjectInjector.emitter(InjectionContext)`.** Condition, per resolved site:
  `produced == null || ConstantDescs.CD_void.equals(produced)` where
  `produced = producedBefore(elements, site)`.
  Remedy: `"@Result receives what the matched call produced, so it belongs at INVOKE_AFTER of a
  call that returns something. Drop the annotation to inject beside the call instead, or point
  it at a call with a result"`. Supported: yes, both moves are real.
  Severity: ERROR. Returning `null` makes `emitter` answer `Emitter.NOTHING` (`II:173-174`),
  which abandons **the whole declaration**, not just that site — `II:188-189`.
- **`producedBefore` is purely positional, not kind-based.** `II:526-538`: scan backwards from
  `site - 1`; the first `InvokeInstruction` yields `invoke.typeSymbol().returnType()`; any other
  `Instruction` first yields `null`; running off the front yields `null`. It never asks whether
  the site's `Site.Kind` is `AFTER_ELEMENT`, and it never asks which `Point` produced it.
- **`capturesResult` is `@Result` on parameter zero and no other parameter.** —
  `aether-weaver-engine/.../parse/WeaveClassParser.java:633-640`; documented at `:626-628`
  ("Parameter zero and no other"). Read only at `II:171` and `II:265`.
- **Nothing anywhere ties `@Result` to `Point.INVOKE_AFTER`.** Grepping `capturesResult()`
  across all `src/main/java` finds exactly two readers, both in `InjectInjector`
  (`II:171`, `II:265`), plus the accessor's own JavaDoc
  (`aether-weaver-api/.../model/InjectorSpec.java:97`). The processor never carries the flag at
  all (`SPEC:49-51`).
- **`stackOperandsAt` deliberately answers `0` for a void-preceded site** so that argument
  binding does not fail first with a worse message — `II:264-269`, with the reasoning in the
  comment at `II:267-268`.
- Reachable? Weave time only, and only for `InjectorKind.INJECT` (this code lives in
  `InjectInjector`). Not reachable at compile time: `SourceSpecs` does not carry
  `capturesResult` (`SPEC:49-51`), so `PC` cannot see a `@Result` declaration.
- Test: `ResultCaptureTest.aVoidCallHasNoResult`
  (`…/inject/ResultCaptureTest.java:95-111`) — `containsExactly("AW1104")`, the class still
  round-trips, and nothing was emitted.

### AW1105 `SITE_IN_UNINITIALISED_WINDOW` — Severity.ERROR, Category.INJECTION_POINT

- Constant: `DC:1082`. Summary: `"Site falls inside a new/<init> uninitialised window"`.
- **Sole site — `SS:151-162`.** Condition: `isUninitialised(elements, site.index())` — a
  forward count from the start of the body of `NewObjectInstruction` (depth++) against
  `INVOKESPECIAL <init>` (depth--, only while `depth > 0`), evaluated over `[0, site)`; if the
  depth is `0`, false; otherwise true **unless** the element *at* the site is itself an
  `INVOKESPECIAL <init>`, which counts as the window's edge rather than its inside. —
  `SS:266-292`, explicitly `SS:288-292`.
  Remedy: `"the stack there holds a reference to an object that does not exist yet, and the JVM
  refuses code that touches it. Move the point after the constructor call — an ordinal, a
  slice, or INVOKE_AFTER on the constructor itself — or use @Redirect or @Wrap, which take the
  whole instantiation over"`. Supported: yes; all three moves exist.
  Severity: ERROR, no override.
- **Only checked for `InjectorKind.INJECT`.** `SiteSafety.usable` routes `REDIRECT` and `WRAP`
  to `operationsOnly` (`SS:66-67`, `SS:121-123`), which reports only AW1061, and returns every
  site untouched for any other kind (`SS:124-126`).
- **The site is dropped; the declaration continues with the rest.** `continue` at `SS:162`, and
  the class-level statement at `SS:47-51` ("A declaration matching four calls of which one sits
  in an unreachable branch is therefore woven three times and reports once, and the accounting
  … sees the three"). So an ERROR here does **not** stop the class being woven — but it does
  count as an error for `failOnError` in the Maven mojo
  (`aether-weaver-maven-plugin/.../AbstractWeaveMojo.java:413-419`).
- **Order of the three checks matters**: AW1026 first, then AW1105, then AW1130; the first to
  refuse reports and moves on — `SS:136`, `SS:151`, `SS:164`, documented at `SS:80-93`.
- Reachable? Yes at weave time. Also **reachable at compile time**, and there for `@Wrap` too,
  because `SourceSpecs` builds a `@Wrap` as `INJECT` (`SPEC:78-80`) — `PC:43-50` states this
  divergence outright: "A site this class refuses under AW1105, AW1026 or AW1130 … is one the
  weaver never asks that question about."
- Test: `SiteSafetyTest.aSiteInsideAnUninitialisedWindowIsRefused`
  (`…/point/SiteSafetyTest.java:41-59`) with the counter-test
  `aSiteOutsideTheWindowIsKept` (`:61-74`).

### AW1110 `ORDINAL_OUT_OF_RANGE` — Severity.ERROR, Category.INJECTION_POINT

- Constant: `DC:1091`. Summary: `"ordinal out of range"`.
- **Sole site — `PR:389-398`, from `applyOrdinal` (`PR:378-400`).** Condition: `spec.ordinal()
  >= 0` (a negative ordinal returns every match, `PR:385-387`) **and** `ordinal >= found.size()`
  where `found` is the matches inside the slice, already translated into whole-body indices.
  Returns `List.of()`.
  Remedy: `"ordinals are zero-based and are counted within the slice, so a slice changes the
  numbering"`. This is an explanation of the numbering, not an instruction; the actionable part
  is in the message, which prints `ordinal <n> requested but only <m> match(es) found for
  <point> [target=…] in <method>` — `PR:390-395`.
  Severity: ERROR.
- **`-1` is the sentinel for "every match", not "the first".** `PointSpec.matchesAll()` is
  `ordinal < 0` — `aether-weaver-api/.../model/PointSpec.java:269-271`; the annotation default
  is `-1` — `At.java:150`.
- **A slice-bound ordinal past its own matches does *not* raise AW1110.** `boundOf` returns
  `null` with nothing reported (`PR:363-364`), and `sliceOf` passes the `null` on unreported
  (`PR:292-294`). This is the single refusal in the resolver that carries no diagnostic —
  stated at `PR:33-36` and `PR:84-85`.
- Reachable? Yes at weave time and at compile time (`PC:90`). But at compile time the ordinal
  is counted over the **whole method**, because the spec carries no slices — so a declaration
  combining `slice` with `ordinal` is resolved against a position the weaver need not agree
  with. — `PC:99-105`, `SPEC:124-131`.
- Test: `PointResolverTest.ordinalOutOfRange` (`…/point/PointResolverTest.java:231-247`),
  asserting `containsExactly("AW1110")` and that the message contains the count.

### AW1111 `SHIFT_LEAVES_SLICE` — Severity.ERROR, Category.INJECTION_POINT

- Constant: `DC:1103`. Summary: `"shift moves the site out of the slice"`.
- **Sole site — `PR:455-465`, from `applyShift` (`PR:428-467`).** Condition, per site:
  `moved < range.from() || moved >= Math.max(range.to(), range.from() + 1) || moved >=
  code.size()`, where `moved = site.index() + offset` and `offset` is `0`/`-1`/`+1`/`spec.by()`
  for `NONE`/`BEFORE`/`AFTER`/`BY` (`PR:434-439`).
  Remedy: `"widen the slice, or drop the shift"`. Supported: yes. Details print the original
  index, the shifted index and `range: [from, to)` — `PR:459-461`.
  Severity: ERROR.
- **One site leaving discards every site this `@At` found**, not just itself: `return List.of()`
  at `PR:464`, reasoning at `PR:409-413`.
- **`offset == 0` returns the sites unchanged without checking anything** — `PR:440-442`. So
  `shift = BY` with `by = 0`, and `shift = NONE`, can never raise AW1111 (or AW1112).
- **The range with no slice is the whole body**, `new Range(0, code.size())` — `PR:285-287`. So
  the "slice" in the name is the whole method where none was declared, and the third disjunct
  (`moved >= code.size()`) is what catches a shift past the end.
- **An empty slice does not make every shift fail**: the upper bound is
  `Math.max(range.to(), range.from() + 1)` — `PR:455-456`, reasoning at `PR:416-418`.
- Reachable? Yes at weave time and at compile time (`PC:91`).
- Test: `PointResolverTest.shiftOutOfRangeIsRefused` (`:610-617`), `BY -99999`.

### AW1112 `SHIFT_OFFSET_LARGE` — Severity.WARNING, Category.INJECTION_POINT

- Constant: `DC:1113`. Summary: `"shift = BY with a large offset"`.
- **Sole site — `PR:443-450`.** Condition: `spec.shift() == At.Shift.BY && Math.abs(spec.by())
  > LARGE_SHIFT`, where `LARGE_SHIFT = 4` — `PR:56`. So the threshold is `|by| >= 5`.
  Reached only after the `offset == 0` early return (`PR:440`).
  Remedy: `"large offsets almost always mean a slice or a different point would express the
  intent better, and they break on any recompilation of the target"`. Supported: partly — it
  names two alternatives ("a slice or a different point") without saying which; there is no
  configuration key to raise the threshold, and none exists (`LARGE_SHIFT` is a `private static
  final int`).
  Severity: WARNING; the shift is still applied and resolution continues — `PR:452-466`.
- **`BEFORE` and `AFTER` can never raise it**, whatever their effective distance: the branch is
  gated on `shift() == BY`. — `PR:443`.
- Reachable? Yes at weave time and at compile time (`PC:91-92`).
- Test: `PointResolverTest.largeOffsetWarns` (`:596-608`), `BY 5`, asserts sites are non-empty
  and `codes()` contains `AW1112`.

### AW1120 `SLICE_FROM_UNRESOLVED` — Severity.ERROR, Category.INJECTION_POINT

- Constant: `DC:1125`. Summary: `"Slice from did not resolve"`.
- **Two distinct sites, with different remedies, both inside `boundOf` (`PR:337-365`),
  parameterised by the code (`PR:288-289` passes `SLICE_FROM_UNRESOLVED` with fallback `0`).**
  - **Site A — `PR:347-350`.** Condition: `this.points.apply(bound.point()) == null` — the
    bound names an identifier nothing registered. Built with
    `Diagnostic.of(unresolved, "no injection point is registered under '<id>'")`.
    **Remedy: none.** `Diagnostic.of` sets no remedy — `Diagnostic.java:138-140`.
  - **Site B — `PR:352-361`.** Condition: `point.find(method, code, bound, Reporter.NOOP)`
    returned an empty list — the bound's point searched the whole body and matched nothing.
    Message `"a slice bound matched nothing in <method>"`, detail `"bound: <point>[ target=…]"`.
    Remedy: `"a slice that cannot be located would silently widen to the whole method, so it is
    refused instead"`. **This is a justification, not a remedy** — it tells the reader why the
    engine refused, and gives them nothing to change.
- **A third refusal path in the same method reports nothing at all**: `ordinal >= sites.size()`
  returns `null` silently — `PR:363-364`, called out at `PR:33-36` and `PR:315-317`.
- **The `matchesAll()` early return at `PR:343-344` is dead through `sliceOf`.** `SliceSpec`'s
  constructor throws `IllegalArgumentException` for a bound with a negative ordinal —
  `aether-weaver-api/.../model/SliceSpec.java:128-131` — and that exception is uncaught during
  parsing. Documented at `PR:305-315`.
- **The bound's own diagnostics are suppressed** by `Reporter.NOOP` (`PR:351`), so a bound that
  matched nothing is reported once as the slice failing rather than twice.
- Severity: ERROR. Returning `null` from `sliceOf` makes `resolve` return `List.of()` for the
  whole `@At` — `PR:141-143`.
- **Cannot arise at compile time**, because the compile-time spec carries no slices. —
  `PC:96-98`, `SPEC:124-131`.
- Reachable? Yes at weave time from every driver.
- Test: `PointResolverTest.unresolvableBoundIsRefused` (`:508-518`) exercises site B.

### AW1121 `SLICE_TO_UNRESOLVED` — Severity.ERROR, Category.INJECTION_POINT

- Constant: `DC:1134`. Summary: `"Slice to did not resolve"`.
- **Identical machinery to AW1120: the same two sites in `boundOf` (`PR:347-350` remedy-less,
  `PR:352-361` with the same justification-as-remedy), reached with `SLICE_TO_UNRESOLVED` and
  fallback `code.size()`** — `PR:290-291`.
- **Both bounds are resolved before either `null` is acted on** — `PR:288-291`, then
  `PR:292-294`. So a slice with both bounds unresolvable reports **both** AW1120 and AW1121.
- Severity: ERROR. Cannot arise at compile time (`PC:96-98`).
- Test: `PointResolverTest.unresolvableUpperBoundIsRefused` (`:520-537`). Its `as(...)` text
  records that AW1121 previously had no test at all and that the shared `boundOf` is exactly
  where a copied argument would have survived.

### AW1122 `SLICE_BOUNDS_INVERTED` — Severity.ERROR, Category.INJECTION_POINT

- Constant: `DC:1146`. Summary: `"Slice to precedes from"`.
- **Sole site — `PR:295-303`.** Condition: both bounds resolved non-`null` and `to < from`.
  Note `to == from` is accepted — the check is strict.
  Message: `"slice '<id>' ends before it begins in <method>"`.
  Remedy: `"the 'to' bound must resolve to a position at or after 'from'; check that both name
  what you think they do"`. Supported: yes.
  Severity: ERROR; `sliceOf` returns `null` and the whole `@At` contributes nothing —
  `PR:302`, `PR:141-143`.
- **Each bound carries its own ordinal**, which is why two bounds naming the same point can
  invert — `SliceSpec.java:31`, `:57-59`; bound ordinals default to `0` rather than `-1`
  (`PointSpec.java:88-90`).
- **Cannot arise at compile time** (`PC:96-98`).
- **Could not establish**: no test asserts `AW1122`; grepping `AW1122` across all `src/test`
  finds only documentation references. The code path is unambiguous from `PR:295-303`, but the
  behaviour is not test-pinned.

### AW1130 `SITE_IN_DEAD_CODE` — Severity.WARNING, Category.INJECTION_POINT

- Constant: `DC:1158`. Summary: `"Site is in dead code"`.
- **Sole site — `SS:164-175`.** Condition: `isDead(elements, site.index())` — scan backwards
  from `site - 1`; a `LabelTarget` ends the scan with `false` (something can aim there); the
  first `Instruction` decides, via `transfersUnconditionally`; the start of the body is
  reachable by definition. — `SS:308-322`.
  `transfersUnconditionally`: `ReturnInstruction`, `ThrowInstruction`,
  `TableSwitchInstruction`, `LookupSwitchInstruction` always; a `BranchInstruction` only for
  `Opcode.GOTO` or `Opcode.GOTO_W`. — `SS:333-343`.
  Remedy: `"a handler injected there would never run, and nothing else would say so. This is
  usually a selector matching a compiler-generated leftover rather than the code that was meant
  — narrow it with a slice or an ordinal"`. Supported: yes.
- **A WARNING that still drops the site.** `continue` at `SS:175`, stated at `SS:88-90`
  ("A warning by severity, but the site is dropped all the same"). A dropped site lowers the
  count fed to `MatchAccounting` (`WP:183`), so the declaration may then report AW1043.
- **Only the *first* instruction of a dead run is reported.** A later one is preceded by an
  ordinary instruction and answers `false`. — `SS:302-306`.
- **Only checked for `InjectorKind.INJECT`** — `SS:121-126`.
- **Reachable at compile time as well, including for a `@Wrap`** — `PC:43-50`.
- Tests: `SiteSafetyTest.aSiteInDeadCodeIsRefused` (`…/point/SiteSafetyTest.java:77-87`);
  `aLabelledInstructionIsReachable` (`:89-104`) pins that a label makes it not-dead.

### AW1131 `PROTECTED_RANGE_SPLIT` — Severity.INFO, Category.INJECTION_POINT

- Constant: `DC:1170`. Summary string, **verbatim**:
  `"A protected range was split around the injected code so the handler's exceptions are not
  caught by the target (spike 6)"`.
- **Sole site — `WP:755-768`, from `rangesFor` (`WP:739-770`).** Condition: `ranges.splits()`
  where `ranges = ProtectedRanges.of(method.code().orElseThrow().elements(), insertions)` and
  `insertions` is every site of every `InjectorKind.INJECT` declaration in the target-method-
  name group (`WP:743-748`). Returns early with an empty `ProtectedRanges` when `insertions` is
  empty (`WP:749-751`).
  Message: `"<internalName>.<method> has <n> protected range(s) split around injected code"`,
  singular/plural on `ranges.splitHandlers()`.
  Detail: `"the target's own catch blocks no longer cover the handler calls, so a handler that
  throws is not silently caught by code that was written for the target's own failures"`.
  Remedy: `"nothing needs doing; this is reported because it changes which exceptions the
  target observes, and a weave that meant to be caught by the target has to catch its own"`.
  Supported: yes — it is deliberately a no-op remedy, and the second half is an actionable
  instruction for the case where the author *did* want the target to catch.
  Severity: INFO, no override; nothing is refused.
- **Only injections count as insertions.** A `@Redirect`/`@Wrap` needs no cut, and a
  contributed kind that does add code inside a protected range is not recognised and leaves the
  range whole. — `WP:724-727`, `WP:744`.
- **Grouped by target method *name*, not descriptor, and the cut is computed against
  `resolved.getFirst().method()`** — so a group whose declarations resolved to different
  overloads is computed against one of them. Stated at `WP:716-722`.
- **Reported once per method-name group, not once per split range**; the count of split ranges
  goes into the message. — `WP:755-757`.
- Reachable? Weave time only; not reachable from the processor (`PointChecks` never emits). Yes
  from every weaving driver.
- Test: `ExceptionRangeTest.theSplitIsReported`
  (`…/inject/ExceptionRangeTest.java:121-132`), asserting exactly one diagnostic, code
  `AW1131`, severity `INFO`.

### AW1200 `INJECTION_POINTS_NOT_VALIDATED` — Severity.INFO, Category.COMPILE_TIME

- Constant: `DC:1185`. Summary string, verbatim: `"Injection points could not be validated at
  compile time (the target's class file was not readable); they will be validated at weave
  time"`.
- **The only `Category.COMPILE_TIME` code in the whole enum.** Grepping `Category.COMPILE_TIME`
  in `DC` yields one hit, `DC:1185`. The number block `1200`–`1299` is reserved for the
  category — `DC:43-44`; category description at `DC:2088-2094` ("Reported only from a
  compilation, and about the compilation rather than about the weave").
- **Sole site — `TB:109-118`, in `TargetBytes.of`.** Condition: `read(binary) == null`, i.e.
  the class file was absent from `StandardLocation.CLASS_PATH`, empty, or refused by the
  class-file parser — all three collapse to `null` (`TB:120-132`, and `TB:30-34`). Reported
  only on the first lookup that fails for that binary name; the `null` is cached
  (`TB:104-107`), which is what keeps it to one notice per target however many weaves and
  injections name it (`TB:38-42`).
  Message: `"the class file for <binary> was not readable, so its injection points could not be
  checked here"`. Detail: `"they are validated at weave time, where the class file always
  exists"`.
  Remedy: `"nothing needs doing; this is expected when the target is compiled from source in
  the same round as the weave"`. Supported: yes — it is a deliberate no-op.
  Severity: INFO, no override; maps to `javax.tools.Diagnostic.Kind.NOTE`
  (`MessagerReporter.java:161`), which does not fail a compilation and is not affected by
  `-Werror` (only `Kind.WARNING` is — `MessagerReporter.java:145-148`).
- **Not reported for a weave with no injections, nor for a target whose class file cannot be
  named.** `checkPoints` returns before touching `TargetBytes` when `injections.isEmpty()` or
  `!TargetBytes.isNameable(target.element())` — `WeaveProcessor.java:373-376`. `isNameable` is
  `NestingKind.TOP_LEVEL || NestingKind.MEMBER` — `TB:208-211`, so a local or anonymous target
  is skipped silently.
- **AW1200 is a gate, not a hint: when it fires, *no* AW11xx code is checked for that target at
  compile time.** `checkPoints` returns immediately on `compiled == null` — `WeaveProcessor.java:377-380`.
- Reachable? **Compile time only, from the annotation processor.** Not reachable from any
  weaving driver. Anchored on the `method` selector literal of the injection whose lookup first
  failed (`WeaveProcessor.java:378`, anchor built in `PointChecks`/`Anchors` — the anchor is
  passed in as `target.anchor()` here, `WeaveProcessor.java:378`).
- Tests: `WeaveProcessorTest.aSameRoundTargetIsNotChecked`
  (`aether-weaver-processor/src/test/java/…/WeaveProcessorTest.java:1468-1482`) — a same-round
  target yields exactly `AW1200` and the compilation still succeeds;
  `theNoticeIsNotRepeated` (`:1484-1509`) — two injections on one target still yield exactly
  one `AW1200`. It also rides along with unrelated codes in several tests: `:996-999`,
  `:1022-1024` (`AW1023`, `AW1200`), `:1048` (`AW1025`, `AW1200`), `:1198` (`AW1020`, `AW1200`).

**Verdict on placement.** AW1200 belongs on this page and is not an orphan. Its entire subject
is the AW11xx range: it exists to say that the point checks in that range were not run, its own
JavaDoc says so (`DC:1182-1184`, "the point errors in the {@code AW11xx} range arrive from the
weaver rather than from the compiler for that target"), `TargetBytes`'s class JavaDoc repeats it
(`TB:30-34`), and mechanically it is the branch that skips `PointChecks` entirely
(`WeaveProcessor.java:377-380`). A reader who meets AW1200 is a reader asking why they did not
get an AW11xx code. It is however the only code on the page that is *not* raised by the weaver,
and the only one whose category is `COMPILE_TIME`; the page has to say both.

---

## Identifiers

Exact spellings a reader will type or read.

- Enum constants: `INJECTION_POINT_UNKNOWN`, `SHIFT_NOT_SUPPORTED`,
  `SELECTOR_MATCHES_INVOKEDYNAMIC`, `INVOKE_AFTER_VOID_CALL`, `SITE_IN_UNINITIALISED_WINDOW`,
  `ORDINAL_OUT_OF_RANGE`, `SHIFT_LEAVES_SLICE`, `SHIFT_OFFSET_LARGE`, `SLICE_FROM_UNRESOLVED`,
  `SLICE_TO_UNRESOLVED`, `SLICE_BOUNDS_INVERTED`, `SITE_IN_DEAD_CODE`, `PROTECTED_RANGE_SPLIT`,
  `INJECTION_POINTS_NOT_VALIDATED`. — `DC:1028,1042,1056,1069,1082,1091,1103,1113,1125,1134,
  1146,1158,1170,1185`.
- Categories: `Category.INJECTION_POINT` (AW1101–AW1131), `Category.COMPILE_TIME` (AW1200). —
  `DC:2086,2094`.
- Severities as declared: ERROR for AW1101, AW1102, AW1104, AW1105, AW1110, AW1111, AW1120,
  AW1121, AW1122; WARNING for AW1112, AW1130; INFO for AW1103, AW1131, AW1200.
- `@At` elements: `value`, `custom`, `target`, `ordinal`, `shift`, `by`, `access`, `slice`. —
  `At.java:92,107,132,150,168,181,191,203`.
- `At.Shift` constants used in branches: `NONE`, `BEFORE`, `AFTER`, `BY`. — `PR:434-439`.
- `Point` constants: `HEAD`, `RETURN`, `TAIL`, `INVOKE`, `INVOKE_AFTER`, `FIELD`, `NEW`,
  `CONSTANT`, `THROW`. — `BIP:127-135`.
- `Site.Kind` values that matter here: `BEFORE_ELEMENT`, `AFTER_ELEMENT`, `METHOD_ENTRY`. —
  `PR:155-157`, `SS:204`, `BIP:495-497`.
- `InjectorKind` values that gate `SiteSafety`: `INJECT`, `REDIRECT`, `WRAP`. — `SS:66-67`,
  `SS:121-126`.
- Constants: `PointResolver.LARGE_SHIFT = 4` (`PR:56`); `BuiltInPoints.MAX_LISTED = 10`
  (`BIP:68`); `SourceSpecs.UNNAMED = "unnamed"` (`SPEC:65`).
- Sentinels: `ordinal = -1` means "every match" (`PointSpec.java:269-271`); `by = 0` means
  "no shift, checked nothing" (`PR:440-442`); slice-bound ordinal defaults to `0`, not `-1`
  (`PointSpec.java:88-90`); `allow = 0` means no upper bound (`InjectorSpec.java:65-68`).
- Builder methods: `WeaverBuilder.plugin(WeaverPlugin)` (`WeaverBuilder.java:230`),
  `WeaverBuilder.discoverPlugins(ClassLoader)` (`WeaverBuilder.java:244`) — the only two doors
  to a contributed point, and neither is used by a shipped driver.
- Maven parameter that decides whether an ERROR fails the build: `failOnError` —
  `AbstractWeaveMojo.java:413-419`.
- Contributed point identifier form: `namespace:NAME`; "built-in" is detected as
  `point.point().indexOf(':') < 0` — `WP:323`, `RI:145-148`, `WI:213`.

---

## Surprises

1. **A remedy that is a justification.** AW1120 and AW1121, site B, carry
   `"a slice that cannot be located would silently widen to the whole method, so it is refused
   instead"` — `PR:358-359`. It explains the engine's choice and offers the reader nothing to
   do. Same shape, milder, for AW1110's `"ordinals are zero-based and are counted within the
   slice, so a slice changes the numbering"` — `PR:396-397`.

2. **Two of the fourteen have a reporting site with no remedy at all.** AW1120 and AW1121 are
   built with `Diagnostic.of(...)` at `PR:347-350` when the bound names an unregistered point.
   Any page listing "the remedy for AW1120" as a single string is wrong: it depends on which of
   the two branches fired.

3. **AW1101's remedy points at a door no shipped driver opens.** `"needs its plugin on the
   classpath"` (`PR:130-132`) cannot be acted on: no driver calls `plugin(...)` or
   `discoverPlugins(...)`, and the discovery loader defaults to `null`
   (`WeaverBuilder.java:80`, `:363-369`). A reader who adds the jar and rebuilds sees the same
   error.

4. **A contributed point is refused *at compile time* regardless.** `PointChecks` hands the
   resolver `BuiltInPoints.all()::get` and nothing else, so `@At(custom = "acme:X")` is an
   AW1101 build error even if a hand-built `Weaver` would resolve it — `PC:53-57`, `PC:165`.
   This is stated in the source's own JavaDoc, which makes it intentional rather than a defect.

5. **AW1131's summary string ends in `(spike 6)`.** — `DC:1171-1173`. That is an internal work
   item leaking into the text a user reads when no message is supplied
   (`Diagnostic.java:188-189`: the summary is used as the message when the builder is given
   none). The reporting site does supply a message (`WP:757-761`), so the string surfaces only
   through `DiagnosticCode.summary()` — but that is what a reference page quotes.

6. **AW1105 is an ERROR that does not stop the weave.** The site is dropped and the remaining
   sites are woven — `SS:162`, `SS:47-51`. Under Maven the run still fails, because the mojo
   counts error-severity diagnostics and throws when `failOnError` is set
   (`AbstractWeaveMojo.java:413-419`). The class is nevertheless woven at three of its four
   positions. The same is true of AW1130, which is a WARNING but *also* drops the site
   (`SS:175`) — severity and consequence are decoupled in both directions here.

7. **AW1104 does not check `INVOKE_AFTER`, despite its summary saying so.** The summary is
   `"INVOKE_AFTER on a void call, handler expects a value"` (`DC:1069`), but the condition is
   `producedBefore(elements, site) == null || CD_void.equals(...)` — a purely positional
   backward scan (`II:213`, `II:526-538`). Nothing anywhere requires `@Result` to sit at
   `Point.INVOKE_AFTER`: the only readers of `capturesResult()` are `II:171` and `II:265`.
   Consequence (reasoned from `II:526-531`, not test-pinned): where the element immediately
   before the site *is* an `InvokeInstruction` with a non-void return — a chained call such as
   `x.a().b()` matched at plain `Point.INVOKE` on `#b` — `producedBefore` answers `a()`'s return
   type and no diagnostic fires, so `@Result` silently binds the wrong call's value.

8. **AW1104 abandons the entire declaration for one bad site.** `capturedKinds` returns `null`
   on the first failure (`II:227`) and `emitter` answers `Emitter.NOTHING` (`II:173-174`) —
   unlike AW1105/AW1130, which drop one site each.

9. **The resolver's AW1102 and the injectors' AW1102 test different things and cannot both
   fire.** The resolver asks `point.supportsShift(shift)` before `find` (`PR:250`); of the nine
   built-ins only `HEAD` ever answers `false` (`BIP:178-180` against the `true` default at
   `InjectionPoint.java:218-220`). The injectors ask `point.shift() != NONE` for a `@Redirect`
   or `@Wrap` at *any* point (`RI:131`, `WI:195`), but only after resolution produced sites
   (`WP:187-189`). A `@Redirect` whose shift leaves the range reports AW1111 and never AW1102.

10. **`@Redirect` and `@Wrap` never see AW1105 or AW1130.** `SiteSafety.usable` routes them to
    `operationsOnly`, which reports only AW1061 — `SS:121-123`. But the annotation processor
    builds a `@Wrap` as `InjectorKind.INJECT` (`SPEC:78-80`), so a `@Wrap` **can** be refused
    at compile time under AW1105 or AW1130 for a site the weaver would never have questioned.
    `PC:43-50` says this outright.

11. **Under the agent's `premain`, every diagnostic in this range is collected and never
    printed.** The agent drains its list at the four points where installation ends
    (`WeaverAgent.java:179,201,220,237`) and "A diagnostic raised after the closing line is
    added to the same list, which nothing drains again" — `WeaverAgent.java:83-91`,
    `:340-342`. Weaving happens during class loading, i.e. after that line. Under `agentmain`,
    diagnostics from retransforming the already-loaded targets *are* printed, because the
    retransformation happens before the closing `print` (`WeaverAgent.java:225-236`).
    So "reachable from the agent" is true for `agentmain` and effectively false for `premain`.

12. **One refusal in the resolver carries no diagnostic at all.** A slice bound whose ordinal
    is past its own matches yields `null` from `boundOf` (`PR:363-364`) and `sliceOf` passes it
    on unreported (`PR:292-294`): the `@At` silently contributes nothing. Called out at
    `PR:33-36`.

13. **AW1120 and AW1121 can both fire for one slice.** Both bounds are resolved before either
    `null` is acted on — `PR:288-294`.

14. **`SiteSafety`'s class JavaDoc carries measured JVM behaviour.** `SS:38-45` records a
    Temurin 25 measurement (`define: OK`, `resolveClass: OK`, `new: java.lang.VerifyError`,
    HotSpot message `Bad type on operand stack ... uninitializedThis is not assignable`, and a
    different `ClassFile.verify` message `Bad type on operand stack in Bad1::<init>() @1 (Bad1
    is not assignable from uninit@65535)`). That is a claim about a measurement, not about the
    code; a page repeating it should attribute it to that comment rather than assert it.

---

## Could not establish

- **No test asserts `AW1122`.** Grepping `AW1122` across `*/src/test` finds only the JavaDoc
  cross-references. The path at `PR:295-303` is unambiguous, but nothing pins that a slice with
  `to` before `from` is reported rather than, say, refused earlier. Settling it needs a test
  that builds a `SliceSpec` whose bounds resolve in the wrong order.
- **Whether `ranges.splits()` can be true for a `to == from` degenerate protected range**, and
  what `ProtectedRanges.splitHandlers()` counts exactly. I did not open
  `ProtectedRanges`; the AW1131 facts above rest on `WP:749-768` and
  `ExceptionRangeTest.theSplitIsReported`. Settling it needs `ProtectedRanges.of` and
  `splits()`/`splitHandlers()`.
- **Whether a Maven run that reports AW1105 or AW1130 still writes the woven class file.** The
  mojo throws `MojoExecutionException` after logging (`AbstractWeaveMojo.java:411-419`); I did
  not trace whether the write happens before or after that point. Settling it needs
  `AbstractWeaveMojo`'s execute/write path.
- **Whether `SITE_IN_DEAD_CODE`'s claim that the declaration "may go on to report AW1043"
  (`DC:1164-1165`) is exercised anywhere.** Mechanically the dropped site lowers the count fed
  to `MatchAccounting` at `WP:183`, but I found no test asserting the AW1130 + AW1043 pair.
- **Nothing measured.** No timings, no benchmark, and no claim above rests on a run.

---

## Not this page

- **AW1043 `NO_INJECTION_POINT_MATCHED`** is reported from `PR:215-220` and `PR:223-228` for a
  point whose `TargetRequirement` is `REQUIRED` with no target, or `FORBIDDEN` with one — i.e.
  the resolver raises a `10xx` code, not an `11xx` one, for a target-requirement mismatch. Also
  from `BuiltInPoints`' `reportNothingMatched` and from `MatchAccounting`. Belongs to the
  AW10xx researcher; worth flagging to them that `PointResolver` is a reporting site for it.
- **AW1026 `THIS_UNAVAILABLE_BEFORE_SUPER_CALL`** — reported at `SS:138-149`, the first of
  `SiteSafety`'s three checks, condition `initialiser >= 0 && site.index() <= initialiser &&
  !injector.handler().isStatic()` with `<=` deliberate (`SS:131-134`). AW10xx page.
- **AW1061 `OPERATION_TARGET_UNSUPPORTED`** — reported at `SS:205-217` for an `AFTER_ELEMENT`
  site under `@Redirect`/`@Wrap`, and at `RI:122-129` / `WI:186-193` for a built-in point that
  names a position rather than an operation (`REDIRECTABLE = {INVOKE, FIELD, NEW}` at `RI:62-63` (`Set.of(Point.INVOKE.name(), Point.FIELD.name(), Point.NEW.name())`);
  `WRAPPABLE` at `WI:95`). AW10xx page.
- **AW1044** (`allow` exceeded) and the whole `MatchAccounting` story — AW10xx page. Note for
  them: at compile time `MatchAccounting.check` is called with no groups, so a declaration
  naming a `group` is not accounted at all, and an omitted `require` reads as `0` there
  (`PC:106-116`, `SPEC:82-85`).
- **AW1072** (`checkCallbackValue`, `WeaveClassParser.java:642-660`) — AW10xx page.
- **AW3116** (a contributed point that throws) and **AW3120** (deprecated alias used,
  `NamespacedRegistry.java:119-125`) — AW3xxx page. Relevant cross-reference: point containment
  covers an `@At`'s own contributed point but **not** a contributed point reached only as a
  slice bound (`InjectionPoint.java:104-110`).
- **AW2101, AW2202, AW2402, AW4090** and the agent's print-drain behaviour
  (`WeaverAgent.java:70-91`) — AW2xxx/AW4xxx pages, though the drain fact above bears on
  reachability for every range.
- **AW4003 / AW4004** (the writer's refusal, `WP:335-345`) — AW4xxx page.
