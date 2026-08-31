# R-diag-1-extension — AW1300–AW1316, the extension (`Category.EXTENSION`) codes

Scope: the 17 codes AW1300–AW1316. All carry `Category.EXTENSION` in
`aether-weaver-api/src/main/java/de/splatgames/aether/weaver/api/diagnostic/DiagnosticCode.java:1197-1414`.

## Facts

### The catalogue

- **All 17 constants are declared consecutively** — `DiagnosticCode.java:1197` (AW1300) through
  `DiagnosticCode.java:1409` (AW1316); the next constant is `AW2003` at `DiagnosticCode.java:1424`.
- **Exactly two of the 17 are `Severity.WARNING`: AW1300 and AW1312** — `DiagnosticCode.java:1197`,
  `DiagnosticCode.java:1352`. The other fifteen are `Severity.ERROR`.
- **No reporting site in the whole range calls `.severity(...)`** — every one of the 19 build sites
  (listed below) passes only `.message(...)`, `.detail(...)`, `.remedy(...)`. Severity therefore always
  resolves from the constant: `Diagnostic.java:118` (`builder.severity != null ? ... : code.defaultSeverity()`).
- **Every one of the 19 sites supplies a remedy.** There is no AW13xx diagnostic built without
  `.remedy(...)`.

### Where they are raised — the complete list

Compile time, all in
`aether-weaver-processor/src/main/java/de/splatgames/aether/weaver/processor/ExtensionChecks.java`:

- **AW1300 `EXTENSION_NOT_FINAL`** — `ExtensionChecks.java:430`.
- **AW1301 `EXTENSION_METHOD_NOT_STATIC`** — `ExtensionChecks.java:526`.
- **AW1302 `EXTENSION_RECEIVER_MISSING`** — `ExtensionChecks.java:571`.
- **AW1303 `EXTENSION_RECEIVER_NOT_FIRST`** — `ExtensionChecks.java:602`.
- **AW1304 `EXTENSION_RECEIVER_NOT_A_TYPE`** — three sites: `ExtensionChecks.java:304` (field),
  `ExtensionChecks.java:615` (`@Receiver` parameter), `ExtensionChecks.java:684` (`@Receiver` on the method).
- **AW1305 `EXTENSION_COLLIDES_WITH_MEMBER`** — two sites: `ExtensionChecks.java:335` (field, name only),
  `ExtensionChecks.java:768` (method, name + descriptor).
- **AW1306 `EXTENSION_IS_GENERIC`** — `ExtensionChecks.java:439`.
- **AW1307 `EXTENSION_HAS_SUPERTYPE`** — `ExtensionChecks.java:452`.
- **AW1308 `DUPLICATE_EXTENSION`** — `ExtensionChecks.java:202` (within one holder) **and**
  `aether-weaver-engine/.../extension/ExtensionIndex.java:188` (across the classpath).
- **AW1309 `EXTENSION_SHADOWED_AT_CALL_SITE`** — `ExtensionIndex.java:240` only. Nothing in the processor
  raises it.
- **AW1310 `EXTENSION_METHOD_IS_GENERIC`** — `ExtensionChecks.java:536`.
- **AW1311 `EXTENSION_RECEIVER_IS_PARAMETERISED`** — three sites: `ExtensionChecks.java:317` (field),
  `:628` (parameter), `:697` (method).
- **AW1312 `EXTENSION_RECEIVER_IS_OBJECT`** — two sites: `ExtensionChecks.java:346` (constant),
  `:784` (method, both shapes).
- **AW1313 `EXTENSION_RECEIVER_DECLARED_TWICE`** — `ExtensionChecks.java:552`.
- **AW1314 `EXTENSION_CONSTANT_NOT_FINAL`** — `ExtensionChecks.java:290`.
- **AW1315 `EXTENSION_NULLS_WITHOUT_RECEIVER`** — `ExtensionChecks.java:120`, reached from two callers:
  `:282` (field) and `:674` (method-level `@Receiver`).
- **AW1316 `EXTENSION_RECEIVER_NOT_THE_CLASSES`** — `ExtensionChecks.java:587`.

- **The engine raises exactly two of the 17** — `grep DiagnosticCode\.` over
  `aether-weaver-engine/.../engine/extension/` yields only `ExtensionIndex.java:188` (AW1308) and
  `ExtensionIndex.java:240` (AW1309).
- **The IntelliJ plugin raises none of them.** `AW13` appears in `aether-weaver-ide/` only in
  `sample/README.md` and sample sources and in JavaDoc prose
  (e.g. `aether-weaver-ide/aether-weaver-idea/src/main/java/.../psi/ExtensionDeclarations.java:35`);
  no `Diagnostic`/`DiagnosticCode` is constructed there.

### Reachability — which tool

- **The processor is registered for `@Extension` unconditionally** — `WeaveProcessor.java:87`
  `@SupportedAnnotationTypes({WeaveProcessor.WEAVE, WeaveProcessor.EXTENSION})`, with
  `EXTENSION = "de.splatgames.aether.weaver.api.experimental.Extension"` at `WeaveProcessor.java:94`.
- **The processor declares no options at all.** No `@SupportedOptions`, no `getSupportedOptions`,
  no `processingEnv.getOptions()` call anywhere in `aether-weaver-processor/src/main/java`. Nothing has to
  be switched on: putting the processor on the processor path is the whole of the configuration.
- **The 15 compile-time codes all come from one entry point** — `WeaveProcessor.checkExtension` at
  `WeaveProcessor.java:246-250`, which calls `ExtensionChecks.of(type, elements, reporter)`; that is
  reached from `WeaveProcessor.process` at `:229-232` for any `TypeElement` annotated `@Extension`.
- **Compile-time severity reaches javac unchanged** — `MessagerReporter.report` at
  `MessagerReporter.java:65-70` and `kindOf` at `:158-165`: `ERROR -> Kind.ERROR`,
  `WARNING -> Kind.WARNING`, `INFO -> Kind.NOTE`, `DEBUG -> Kind.OTHER`. So AW1300 and AW1312 fail a
  build only under `-Werror`.
- **Nothing deduplicates compile-time reports** — `MessagerReporter.java:56-58` (JavaDoc) and the body
  at `:65-70`, which has no set.
- **The weave-time index is built only by the Maven plugin.** `ExtensionIndex.of(list, receivers, reporter)`
  has exactly two callers in `*/src/main/java`: `StubsMojo.java:179` and `AbstractWeaveMojo.java:232`.
- **The agent and the testkit never build one.** `WeaverBuilder.extensions(...)`
  (`WeaverBuilder.java:328`) is called from exactly one place, `AbstractWeaveMojo.java:283`; the field
  defaults to `ExtensionIndex.EMPTY` at `WeaverBuilder.java:92`. `aether-weaver-agent/src/main/java` and
  `aether-weaver-testkit/src/main/java` contain no reference to `ExtensionIndex`. AW1308/AW1309 are
  therefore unreachable from the agent and from the testkit.
- **`ExtensionIndex.of(List)` — the one-argument overload — reports nothing.** It passes
  `Reporter.NOOP` and `ClassSource.NONE` (`ExtensionIndex.java:136`), so duplicates are dropped silently
  and shadowing is not looked for at all.

### The three Maven goals, and what each does with AW1308/AW1309

- **`stubs` logs `Severity.ERROR` at `error` and every other severity at `warn`** —
  `StubsMojo.java:169-175`. The listener is the only consumer of both `Manifests.of` and
  `ExtensionIndex.of` diagnostics on that goal (`:177-180`).
- **`stubs` never fails on a diagnostic.** `StubsMojo.execute` throws `MojoExecutionException` in exactly
  three places, none of them diagnostic-driven: an unresolved compile classpath (`:164`), a stub that
  cannot be written (`:232`), and a *required* receiver absent from classpath and runtime image
  (`missing`, `:377`). A declaration refused as AW1308 or AW1309 is simply absent from the index and is
  not stubbed.
- **`stubs` prints neither the details nor the remedy.** `StubsMojo.render` at `:278-280` is
  `"Aether Weaver: " + code.code() + ' ' + message`. The remedy strings quoted below are invisible to a
  reader of the `stubs` log.
- **`weave` and `weave-tests` do fail on them.** `AbstractWeaveMojo.report` at `:413-418` counts
  `Severity.ERROR` diagnostics and throws when `failOnError` is set; the parameter is
  `aether.weaver.failOnError`, default `true` (`AbstractWeaveMojo.java:97-98`). Those goals print
  `diagnostic.format()`, which includes details and remedy (`AbstractWeaveMojo.java:405`).
- **Goal names and phases** — `stubs` / `generate-sources` / `ResolutionScope.COMPILE`
  (`StubsMojo.java:90-93`); `weave` / `process-classes` / `COMPILE_PLUS_RUNTIME`
  (`WeaveMojo.java:44-47`); `weave-tests` / `process-test-classes` / `TEST`
  (`WeaveTestsMojo.java:31-34`). All three are `threadSafe = true`.
- **The same AW1309 can be reported twice in one build** — once by `stubs` at `generate-sources`
  (logged at `error`, build continues) and again by `weave` at `process-classes` (fails the build).
  Both index the classpath the same way: `StubsMojo.java:179`, `AbstractWeaveMojo.java:232`.
- **The two goals do not index the same set.** `stubs` first drops every `Scope.MODULE` declaration that
  is not also declared by this module's own output directory (`StubsMojo.visible`, `:295-313`) and reads
  only `Manifests.of(classpath, listener)` (`:177`); `AbstractWeaveMojo` indexes
  `Manifests.of(classpath, directEntries(), listener).extensions()` unfiltered
  (`AbstractWeaveMojo.java:227-232`). A duplicate involving a dependency's `MODULE`-scoped declaration is
  therefore AW1308 at `weave` and silent at `stubs`.

### The compile-time pass, in order

- **Only `public` methods are examined; every other method is skipped silently** —
  `ExtensionChecks.java:187-191`. Non-public helpers are neither checked nor contributed.
- **Only fields carrying `@Receiver` are examined** — `ExtensionChecks.java:214-220`. A field without it
  is ignored even when the holder names a class-level receiver.
- **The holder is checked first, and AW1306 or AW1307 aborts the whole holder** —
  `ExtensionChecks.java:175-177` returns `List.of()`; `checkHolder` returns `false` at `:445` (AW1306)
  and `:458` (AW1307). AW1300 does not return (`:429-436`), so a non-final *and* generic holder reports
  both AW1300 and AW1306.
- **AW1306 is tested before AW1307** — `:438` before `:451`; a generic holder with a supertype reports
  only AW1306.
- **Per-method order is AW1301, AW1310, AW1313, then the shape checks** — `:525`, `:535`, `:551`, then
  `:564` (method-level `@Receiver` → `staticContribution`), `:569` (no `@Receiver` anywhere), `:601`
  (`@Receiver` on a later parameter), `:614` (not a declared type), `:627` (parameterised).
- **Methods are enumerated before fields** — the method loop is `:187-212`, the field loop `:214-227`.
  The order within each is `ElementFilter.methodsIn(holder.getEnclosedElements())` /
  `fieldsIn(...)`, i.e. whatever the compiler enumerates; nothing sorts.
- **The duplicate key is `receiver + '.' + name + descriptor`** in a `LinkedHashSet` — `:185`, `:198`,
  `:223`. **The kind is not part of the key**, so a static contribution and an instance contribution that
  produce the same call collide (see Surprises).
- **A refused member is left out of the returned list; a warned one is kept** — `:195-197` (null return
  skipped), and AW1300/AW1312 fall through to a `return new WeaveManifest.Extension(...)` at `:355` and
  `:794`.
- **The holder is registered even when it contributes nothing** — `WeaveProcessor.java:248-249` calls
  `manifest.addExtensions(binaryName, ExtensionChecks.of(...))` with whatever list came back.

### Conditions, per code

- **AW1300**: `!holder.getModifiers().contains(Modifier.FINAL)` — `ExtensionChecks.java:429`.
- **AW1301**: `!method.getModifiers().contains(Modifier.STATIC)` on a `public` method — `:525`.
- **AW1302**: no parameter carries `@Receiver`, the method carries none, and the holder's
  `@Extension.value()` is not a declared type — `:569-570`, with `receiverNamedBy` returning `null` at
  `:71-78` for an unwritten value or a non-`DECLARED` one.
- **AW1303**: `receiverIndex(parameters) > 0` — `:601`, where `receiverIndex` (`:809-816`) returns the
  index of the **first** parameter carrying `@Receiver`.
- **AW1304**: field — `receiverNamedOn(named) == null || kind != DECLARED` (`:302-303`); parameter —
  `parameters.get(0).asType().getKind() != DECLARED` (`:613-614`); method — the written `value` is
  absent or not a `TypeMirror` or not `DECLARED` (`:678-683`). `receiverNamedOn` returns `null` when the
  source wrote no `value` at all (`:375-380`), which is how the `void.class` default arrives here.
- **AW1305**: field — `fieldOf(receiverElement, simpleName, elements) != null`, matching **name alone**
  over `elements.getAllMembers(receiver)` (`:332-334`, `:396-406`); method —
  `memberOf(receiver, name, descriptor, elements) != null`, matching name **and** descriptor over
  `getAllMembers` (`:765-767`, `:862-874`). Inherited members count in both.
- **AW1306**: `!holder.getTypeParameters().isEmpty()` — `:438`.
- **AW1307**: superclass is neither `TypeKind.NONE` nor `java.lang.Object`, **or**
  `!holder.getInterfaces().isEmpty()` — `:448-451`.
- **AW1308** (compile time): the key `receiver.name+descriptor` was already added — `:198`.
- **AW1308** (weave time): `byCall.get(Call.of(extension)) != null` — `ExtensionIndex.java:184-185`,
  where `Call` is `(receiverInternalName, name, descriptor)` and carries no kind
  (`ExtensionIndex.java:620-635`). Checked **before** the shadow check, and it never consults the
  classpath.
- **AW1309**: `declarerOf(receivers, receiverInternalName, name, descriptor, new HashSet<>()) != null` —
  `ExtensionIndex.java:232-237`. `declarerOf` (`:274-313`) walks superclass then interfaces depth-first,
  guards on a `seen` set, and **returns `null` silently when the class is absent from the `ClassSource`
  or fails to parse** (`:283-288`, `:293-295`).
- **AW1310**: `!method.getTypeParameters().isEmpty()` — `:535`.
- **AW1311**: `!declared.getTypeArguments().isEmpty()` — `:316` (field), `:627` (parameter), `:696`
  (method).
- **AW1312**: `"java.lang.Object".contentEquals(receiverBinaryName)` — `:345` (constant), `:780`
  (method); the constant `OBJECT` is at `:131`.
- **AW1313**: `onMethod != null && receiverAt >= 0` — `:551`.
- **AW1314**: the field lacks any of `PUBLIC`, `STATIC`, `FINAL` — `:286-289`.
- **AW1315**: `nullsOf(annotation) != null`, i.e. the source **wrote** a `nulls` element on a
  method-level or field-level `@Receiver` — `:117`, via `Anchors.enumOf` → `Anchors.valueOf`, which reads
  `mirror.getElementValues()` (`Anchors.java:127`), the written values only.
- **AW1316**: the holder named a class-level receiver, the method marks no `@Receiver`, and
  `parameters.isEmpty() || !declaredReceiver.contentEquals(nameOf(parameters.get(0).asType()))` —
  `:585-586`. `nameOf` returns the **canonical name with type arguments dropped**
  (`:889-894`), so `@Extension(List.class)` is satisfied by a first parameter of `List<String>`.

### Remedy strings, verbatim, and whether they work

| Code | Site | Remedy passed to `.remedy(...)` | Reachable by following it |
| --- | --- | --- | --- |
| AW1300 | `:434` | `declare it final` | Yes for a class. **No for an interface or an annotation type**, neither of which can be `final` (see Surprises). |
| AW1301 | `:530` | `declare it static, or make it private if it is a helper` | Both work. Understated: `:189` skips every **non-public** method, so package-private and `protected` work too. |
| AW1302 | `:576` | `annotate the first parameter @Receiver, name one for the whole class with @Extension(Type.class), or make the method private` | All three work, but the second can trade AW1302 for AW1316 when the method's first parameter is not that type (`:585`). |
| AW1303 | `:607` | `move the @Receiver parameter to the front` | Yes. |
| AW1304 | `:310`, `:690` | `name a class or interface, as in @Receiver(BigDecimal.class)` | Yes. |
| AW1304 | `:620` | `use a class or interface type, boxing the value if that is what is wanted` | Yes. |
| AW1305 | `:340` | `rename the constant` | Yes. |
| AW1305 | `:774` | `rename the extension, or use @Weave with @Inject or @Redirect to change what the existing method does` | Renaming works. The `@Weave` alternative is a different feature, not a way past this branch. |
| AW1306 | `:443` | `remove the type parameters` | Yes. |
| AW1307 | `:456` | `make it extend Object and implement nothing` | Yes for a class. **Impossible for an `enum`, a `record` or an annotation type**, which is exactly what the branch catches for them (see Surprises). |
| AW1308 (processor) | `:207` | `rename one of them` | Yes. |
| AW1308 (engine) | `:194` | `remove one of them, or rename it so the two calls differ` | Yes, but the reader must edit **the declaring holder's source and recompile it**; the diagnostic is raised over manifests read off the classpath, which may be a third-party artefact. Not visible in the `stubs` log at all (`StubsMojo.java:278-280`). |
| AW1309 | `:246` | `delete the extension, or rename it so it no longer collides` | Yes, same caveat: the extension may be a dependency's. Remedy not printed by `stubs`. |
| AW1310 | `:542` | `use the erased type, or move the method to an ordinary utility class` | Yes. |
| AW1311 | `:323`, `:703` | `name the raw type` | Yes. |
| AW1311 | `:634` | `use the raw type and check inside the method, or narrow the receiver to a type that is not parameterised` | Yes. |
| AW1312 | `:351` | `name the narrowest type the constant is meaningful on` | Yes — and the entry is contributed either way. |
| AW1312 | `:790` | `name the narrowest type the method is meaningful on` | Yes — contributed either way. |
| AW1313 | `:559` | `keep @Receiver on the method for a static extension, or on the first parameter for an instance one` | Yes. |
| AW1314 | `:296` | `declare it public static final, or drop the @Receiver and keep it as the extension class's own field` | Both work (`:214-220` ignores an unannotated field). |
| AW1315 | `:124` | `remove nulls, or mark a parameter @Receiver to contribute an instance method instead` | Both work. First branch is the only exit when the intent really is a static contribution. |
| AW1316 | `:595` | `take <receiver> as the first parameter, make the method private, or name its own receiver` | All three work; the remedy interpolates the class-level receiver's name. |

### Experimental status

- **`@Extension`, `@Receiver`, `Nulls` and `Scope` all carry `@ApiStatus.Experimental`** —
  `Extension.java:147`, `Receiver.java:95`, `Nulls.java:48`, `Scope.java:32`.
- **Nothing in any diagnostic message, detail or remedy in the range says so.** No AW13xx site mentions
  experimental status, and `DiagnosticCode.java:1197-1414` does not either. A reader meets the status
  only in the JavaDoc of the four annotation types and of
  `aether-weaver-api/.../api/experimental/package-info.java`.
- **No code in the range is gated behind a switch.** There is no processor option, no Maven parameter and
  no engine flag that enables extensions; the only relevant toggles are `aether.weaver.skip`
  (`StubsMojo.java:117`) and `aether.weaver.failOnError` (`AbstractWeaveMojo.java:97`).

### Verified by running the processor

Compiled with `javac 25.0.3 -proc:full` against
`aether-weaver-api/target/classes:aether-weaver-processor/target/classes`. These are observations of the
current build, reproducible with the same command:

- **An `@Extension` `interface` is accepted as a holder**: it reports AW1300 (`interface` is not `final`)
  and nothing else, and its implicitly-public `static` method reaches the manifest.
- **An `@Extension` `enum` and an `@Extension` `record` each report AW1307 and contribute nothing.**
- **An `@Extension` annotation type reports both AW1300 and AW1307** (it implements
  `java.lang.annotation.Annotation`).
- **`@Receiver` on parameters 0 and 2 of one method reports nothing**; the entry is contributed with the
  later `@Receiver` parameter still in the descriptor.
- **`@Receiver(value = Integer.class, nulls = Nulls.UNCHECKED)` on a method reports AW1315**, although
  `UNCHECKED` is the element's default.
- **`@Receiver(value = Integer.class)` on a `String` parameter is silent**; the receiver recorded is
  `java.lang.String`.
- **AW1308 fires at compile time for one instance contribution and one static contribution that produce
  the same call** — e.g. `d(@Receiver String, int)` plus `@Receiver(String.class) static d(int)`.
- **AW1305 fires for a static contribution `@Receiver(String.class) public static int length()`**, whose
  collision is with `String`'s *instance* `length()`.
- **The manifest is written even when the compilation reported AW13xx errors** — `META-INF/aether/weaves.json`
  appeared containing every surviving entry. The writing site is `WeaveProcessor.java:212`
  (`round.processingOver()` branch), which does not consult `reporter.errors()`.

## Identifiers

- Enum constants: `EXTENSION_NOT_FINAL`, `EXTENSION_METHOD_NOT_STATIC`, `EXTENSION_RECEIVER_MISSING`,
  `EXTENSION_RECEIVER_NOT_FIRST`, `EXTENSION_RECEIVER_NOT_A_TYPE`, `EXTENSION_COLLIDES_WITH_MEMBER`,
  `EXTENSION_IS_GENERIC`, `EXTENSION_HAS_SUPERTYPE`, `DUPLICATE_EXTENSION`,
  `EXTENSION_SHADOWED_AT_CALL_SITE`, `EXTENSION_METHOD_IS_GENERIC`,
  `EXTENSION_RECEIVER_IS_PARAMETERISED`, `EXTENSION_RECEIVER_IS_OBJECT`,
  `EXTENSION_RECEIVER_DECLARED_TWICE`, `EXTENSION_CONSTANT_NOT_FINAL`,
  `EXTENSION_NULLS_WITHOUT_RECEIVER`, `EXTENSION_RECEIVER_NOT_THE_CLASSES`.
- Default summaries (second-to-last constructor argument), verbatim:
  - AW1300 `Extension class is not final`
  - AW1301 `A public method of an extension class is not static`
  - AW1302 `A contributed method declares no @Receiver`
  - AW1303 `@Receiver is not on the first parameter`
  - AW1304 `The receiver's type cannot carry a method`
  - AW1305 `The extension collides with a member the receiver already has`
  - AW1306 `Extension class is generic`
  - AW1307 `Extension class has a superclass or an interface`
  - AW1308 `Two extensions contribute the same method to the same receiver`
  - AW1309 `A call site names an extension whose receiver genuinely declares that method`
  - AW1310 `An extension method declares its own type parameters`
  - AW1311 `The receiver is a parameterised type, which erasure cannot tell from any other`
  - AW1312 `The receiver is java.lang.Object, so the method is offered on every expression`
  - AW1313 `A contributed method names a receiver both on the method and on a parameter`
  - AW1314 `A contributed constant is not static final`
  - AW1315 `nulls is declared where there is no receiver value to check`
  - AW1316 `A contributed method does not take the class's declared receiver first`
- Annotations: `de.splatgames.aether.weaver.api.experimental.Extension`
  (`@Target(ElementType.TYPE)`, `@Retention(RUNTIME)`, `Extension.java:148-151`);
  `de.splatgames.aether.weaver.api.experimental.Receiver`
  (`@Target({PARAMETER, METHOD, FIELD})`, `@Retention(RUNTIME)`, `Receiver.java:96-99`).
- Annotation elements and defaults: `Extension.value()` default `void.class` (`Extension.java:168`);
  `Extension.require()` default `Require.REQUIRED` (`Extension.java:185`); `Extension.scope()` default
  `Scope.PUBLIC` (`Extension.java:198`); `Receiver.value()` default `void.class` (`Receiver.java:113`);
  `Receiver.nulls()` default `Nulls.UNCHECKED` (`Receiver.java:128`).
- Manifest record: `WeaveManifest.Extension(className, receiver, name, descriptor, kind, require, nulls,
  scope)` — `WeaveManifest.java:605-618`; convenience constructors at `:635-641` (kind given, policies
  defaulted) and `:670-676` (`Kind.INSTANCE`). `guarded()` at `:659-661`.
- Kinds written into the manifest: `instance` (omitted, the default), `static`, `constant` — observed in
  the emitted `weaves.json`; the enum is `WeaveManifest.Extension.Kind`.
- Maven goals: `stubs`, `weave`, `weave-tests`, `audit`. Properties: `aether.weaver.skip`,
  `aether.weaver.failOnError`. `StubsMojo.outputDirectory` names **no** property and defaults to
  `${project.build.directory}/aether-weaver/stubs` (`StubsMojo.java:128`); stub subdirectories are
  `patch` and `classpath` (`StubsMojo.java:97`, `:100`).
- Processor internals a reader will not type but a writer may need: `ExtensionChecks.EXTENSION` and
  `ExtensionChecks.RECEIVER` hold the two annotation names (`ExtensionChecks.java:56`, `:59`).

## Surprises

1. **AW1308's own JavaDoc names a cause javac forbids.** `DiagnosticCode.java:1292-1297` says the usual
   compile-time cause is "two overloads that erase to the same descriptor", and the site's detail at
   `ExtensionChecks.java:205-206` says "both would rewrite the same call, because they erase to the same
   descriptor". Two methods in one class whose *erasures* collide are rejected by javac itself before the
   processor sees them. The route that actually reaches `ExtensionChecks.java:198` is a **call-site**
   collision between two differently-shaped declarations — verified: one instance contribution
   `d(@Receiver String, int)` and one static contribution `@Receiver(String.class) static d(int)` both
   key as `java.lang.String.d(I)Ljava/lang/String;`. The key at `:198` and `Call.of` at
   `ExtensionIndex.java:632-634` both omit `Kind`, which is why.
2. **AW1300's remedy cannot be followed by the declaration that most often triggers it.** An
   `@Extension` `interface` reaches `ExtensionChecks.java:429` (an interface is never `final`) and is
   otherwise accepted — `checkHolder` treats `TypeKind.NONE` as extending `Object` (`:449-450`), so an
   interface passes the AW1307 test. The remedy "declare it final" is unavailable; the only fix is to
   stop being an interface. Verified by running the processor.
3. **AW1307's remedy is impossible for the forms that most naturally hit it.** An `enum` (superclass
   `java.lang.Enum`), a `record` (superclass `java.lang.Record`) and an annotation type (interface
   `java.lang.annotation.Annotation`) all fail `:448-451`, and none can "extend Object and implement
   nothing". Verified by running the processor.
4. **AW1315 fires on a value that equals the default.** `Anchors.valueOf` reads
   `mirror.getElementValues()` (`Anchors.java:127`), the elements the source actually wrote, so
   `@Receiver(value = X.class, nulls = Nulls.UNCHECKED)` on a method or a field is refused even though
   `UNCHECKED` is what `Receiver.nulls()` defaults to (`Receiver.java:128`). Verified by running.
5. **AW1303 catches only the first `@Receiver`.** `receiverIndex` returns the first annotated index
   (`ExtensionChecks.java:809-816`). With `@Receiver` on parameters 0 and 2 nothing is reported and the
   parameter at index 2 stays an ordinary argument in the recorded descriptor. Verified by running.
6. **AW1305 does not distinguish a static member from an instance one.** `memberOf`
   (`:862-874`) compares name and descriptor over `getAllMembers` with no modifier test, so a static
   contribution collides with the receiver's instance method of the same shape, and vice versa. Verified:
   `@Receiver(String.class) public static int length()` reports AW1305 against `String.length()`.
   `declarerOf` at weave time does the same (`ExtensionIndex.java:296-301`), so the two agree.
7. **AW1309 can never fire for a contributed constant.** `declarerOf` iterates `model.methods()` only
   (`ExtensionIndex.java:296`); `ClassModel.fields()` is never read. A hand-written manifest whose
   constant collides with a real field of the receiver is indexed and rewritten. The processor catches
   that case as AW1305 (`ExtensionChecks.java:335`); the engine has no counterpart. **This is the
   processor–engine disagreement in the range.**
8. **AW1309 is classpath-dependent and fails open.** An unreadable or absent receiver ends the walk
   returning `null` (`ExtensionIndex.java:283-288`, `:293-295`), so the declaration is kept unexamined.
   `ExtensionIndexTest.unreadableReceiverIsSilent` (`ExtensionIndexTest.java:78-93`) asserts exactly this
   and states the reason in a comment at `:82-85`. AW1308 has no such dependence; it is checked first and
   never consults the class source (`ExtensionIndex.java:184-197`).
9. **Fourteen of the fifteen compile-time rules have no weave-time counterpart.** The engine's extension
   package raises AW1308 and AW1309 and nothing else. A hand-edited or hand-written manifest asking for a
   parameterised receiver, a receiver of `Object`, a `nulls` policy on a static contribution or a constant
   is accepted at weave time. The one place the engine gives a second opinion is `guarded()`
   (`WeaveManifest.java:659-661`), which silently declines to emit the null check for a non-`INSTANCE`
   kind rather than reporting AW1315; `ExtensionGuards.java:85-92` is the only consumer.
10. **The `stubs` goal logs a `Severity.ERROR` diagnostic at `error` level and then carries on.** Combined
    with `render` dropping details and remedy (`StubsMojo.java:278-280`), a reader sees a red line, no
    remedy, a successful goal, and a build that fails an hour later at `process-classes` for the same
    cause. Confirmed at `StubsMojo.java:169-175` and by the absence of any diagnostic-driven throw in
    `execute`.
11. **The manifest is written even when the compilation failed on an AW13xx error.**
    `WeaveProcessor.java:206-213` writes on `processingOver()` unconditionally; `errors()`
    (`MessagerReporter.java:105-107`) is never consulted there. The refused entries are absent, but a
    `weaves.json` exists.
12. **The silent field-drop the JavaDoc describes cannot happen.** `ExtensionChecks.java:150-154` says
    "A field whose key is already taken is dropped without a diagnostic", implemented at `:223-226`. A
    field's descriptor is a type descriptor (`:330`) while a method's always begins with `(`
    (`callSiteDescriptorOf`, `:835`), and two fields of one class cannot share a simple name, so the key
    can never repeat on that path.
13. **Nothing in the reactor consults `isSuppressible()`.** `Diagnostic.isSuppressible`
    (`Diagnostic.java:255`) has no caller in `*/src/main/java` outside the diagnostic package. There is no
    mechanism to silence AW1300 or AW1312 short of not writing the declaration.
14. **A parameterised class-level receiver is not rejected, and matches by erasure.** AW1311 is checked
    only for a `@Receiver` parameter or a `@Receiver` type (`:316`, `:627`, `:696`); the class-level
    receiver goes through `receiverNamedBy` (`:71-78`) and `nameOf` (`:889-894`), which drops type
    arguments, so `@Extension(List.class)` accepts a first parameter of `List<String>` and AW1311 never
    runs on that path.

## Could not establish

- **Whether AW1308 can be reached at compile time by two contributions of the same shape.** Every
  same-shape pair I could construct is rejected by javac first, or produces different keys. The
  mixed-shape route is demonstrated above; whether another exists would need an exhaustive search of
  descriptor-producing combinations.
- **What order `ElementFilter.methodsIn(holder.getEnclosedElements())` yields**, and therefore which of
  two AW1308-colliding declarations is kept at compile time. The code imposes no order
  (`ExtensionChecks.java:187`). At weave time the order is the merged manifest's, and the *first* read
  stands (`ExtensionIndex.java:184-197`, asserted by `ExtensionIndexTest.duplicateIsRefused` at
  `ExtensionIndexTest.java:99-124`); the manifest merge order is decided in `Manifests`, which I did not
  open.
- **Whether `stubs` is bound to a build by default.** `@Mojo(defaultPhase = GENERATE_SOURCES)` states the
  phase an execution binds to, not that one exists; the plugin's own lifecycle mapping, if any, is in
  resources I did not read.
- **Whether AW1300 or AW1312 is ever reported more than once for one declaration.** Nothing deduplicates
  (`MessagerReporter.java:56-58`), but each site is reached once per member per round, and I did not
  construct a multi-round compilation.
- **No test asserts the `stubs` goal's logging of AW1308/AW1309.** `grep AW1308\|AW1309` over
  `*/src/test/java` finds only a comment in `ExtensionIndexTest.java:82`. The behaviour is established
  from `StubsMojo.java:169-175` and `:278-280` alone; a writer wanting it asserted would need a new test.

## Not this page

- **AW3010** — decided while reading manifests off `classpathElements()`, `AbstractWeaveMojo.java:211-213`
  and `directEntries()` at `:186-206`. Manifest/classpath page.
- **AW2300 and AW2301** — an unparseable manifest and one from a newer release, both arriving on the same
  `stubs` listener (`StubsMojo.java:83-85`). Manifest page.
- **`ExtensionStubs.patch`, the `patch/` and `classpath/` layout, `--patch-module` advice, and the
  `MojoExecutionException` for a missing required receiver** (`StubsMojo.java:186-239`, `:362-382`) — the
  `stubs` guide/reference page. `Require.REQUIRED` / `Require.OPTIONAL` is read only there
  (`StubsMojo.java:367`) and, per `Extension.java:180`, "is not consulted at weave time".
- **`Scope.MODULE` filtering** (`StubsMojo.java:295-313`) — the extension-scope page; it produces no
  diagnostic, only a debug line.
- **`Nulls.CHECKED` code generation** — `ExtensionGuards.java:63-92`; the extensions concept page.
- **`WeaveManifest.Extension.implementationDescriptor()`** and its `IllegalArgumentException` contract
  (`WeaveManifest.java:687-700`) — the manifest reference page.
