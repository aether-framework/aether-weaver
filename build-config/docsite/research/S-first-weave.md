# S-first-weave — Your first weave

Section `start`, order 30, file `Writerside/topics/start/first-weave.md`, kind `howto`.
Every line number was read from the file named. Anchors are repository-relative.

## Facts

### The shape of a minimal weave, taken from the end-to-end suite

`CrossDriverEquivalenceTest` compiles four source strings, weaves them through all three
drivers, and asserts the three produce byte-identical output. Its fixture is the smallest
complete working weave in the repository.

- **The target is an ordinary class with one instance method returning a value** —
  `aether-weaver-tests/src/test/java/de/splatgames/aether/weaver/e2e/CrossDriverEquivalenceTest.java:193-201`:
  `package fixture; public class Target { public String greet() { return "hello"; } }`.
  It carries no annotation and knows nothing about the weaver.
- **The weave class is four lines of annotation plus a handler** — same file, `:213-229`:

  ```java
  package fixture;

  import de.splatgames.aether.weaver.api.At;
  import de.splatgames.aether.weaver.api.Inject;
  import de.splatgames.aether.weaver.api.Point;
  import de.splatgames.aether.weaver.api.Weave;

  @Weave(Target.class)
  public final class Audit {

      @Inject(method = "greet()", at = @At(Point.HEAD))
      void onGreet() {
          Trace.say("woven");
      }
  }
  ```

  `@Weave(Target.class)` is at `:221`, `public final class Audit` at `:222`, the `@Inject` at
  `:224`, the package-private `void onGreet()` at `:225`.
- **The handler's side effect goes through a third class, not through `System.out` directly**
  — `Trace` at `:203-211`. The same fixture in `TwoArtefactsTest` uses
  `public static final List<String> RECORD = new ArrayList<>()` instead
  (`aether-weaver-tests/src/test/java/de/splatgames/aether/weaver/e2e/TwoArtefactsTest.java:176-185`),
  so the test can assert on order.
- **The proof the fixture is really modified is a separate test**, comparing the digest of the
  woven bytes against the digest of the original class file —
  `CrossDriverEquivalenceTest.java:65-74` (`theFixtureIsWoven`).
- **The four e2e fixtures compile with `-proc:none`** —
  `aether-weaver-tests/src/test/java/de/splatgames/aether/weaver/e2e/Fixtures.java:43-49`. The
  comment at `:44-46` states the reason: the processor is on that module's classpath and
  validating the fixtures is a different test's job. So the e2e suite writes the manifest by
  hand; it is not evidence that the processor is optional in a real build.

### What the annotations on that weave actually say

- **`@Weave` is `@Documented`, `RUNTIME` retention, `@Target(ElementType.TYPE)`** —
  `aether-weaver-api/src/main/java/de/splatgames/aether/weaver/api/Weave.java:179-182`.
- **`value()` takes class literals and defaults to an empty array** — `Weave.java:198`.
  **`targets()` takes binary names and defaults to an empty array** — `Weave.java:216`.
  Declaring both is `AW1002`, declaring neither is `AW1001`, and in both cases the weave
  contributes nothing — `Weave.java:26-28`. `AW1001` is
  `WEAVE_NO_TARGETS("AW1001", Severity.ERROR, Category.DECLARATION, "…")` —
  `aether-weaver-api/src/main/java/de/splatgames/aether/weaver/api/diagnostic/DiagnosticCode.java:132`;
  `AW1002` is `WEAVE_DUPLICATE_TARGET_DECLARATION` — `DiagnosticCode.java:145`.
- **Naming a class as text when it is on the compile classpath is `AW1009`, informational, with
  the literal to write instead** — `Weave.java:31-33`, `Weave.java:204-206`. The literal form
  is the one to show first.
- **`kind()` defaults to `Kind.INSTANCE`** — `Weave.java:227`. An instance weave is dissolved
  into each target: merged members are copied onto the target, handlers become methods of the
  target, and **the weave class itself is never loaded** — `Weave.java:297-307`.
- **`priority()` defaults to `0`, `require()` to `Require.REQUIRED`, `tags()` to an empty
  array, `phase()` to `Phase.DEFAULT`** — `Weave.java:246`, `:257`, `:276`, `:287`.
- **`phase()` is recorded and never acted upon** — `Weave.java:280-283`: no stage of planning,
  conflict detection or injection selects weaves by it.
- **A weave class must be `final` or `AW1008` (a warning) is reported** — `Weave.java:101-104`,
  `DiagnosticCode.java:235` (`WEAVE_NOT_FINAL`, `Severity.WARNING`). An `abstract` weave is
  exempt.
- **A weave class must declare no constructor (`AW1081`), no static initialiser (`AW1082`), no
  type parameters (`AW1007`), no interface (`AW1084`), and must extend `Object` (`AW1006`)** —
  `Weave.java:90-104`; `AW1081` is `WEAVE_DECLARES_CONSTRUCTOR`, `Severity.ERROR` —
  `DiagnosticCode.java:817`. A compiler-generated default constructor is not a declared one and
  is ignored — `Weave.java:96-99`.
- **`@Inject` is `RUNTIME` retention, `@Target(ElementType.METHOD)` and
  `@Repeatable(Inject.Container.class)`** —
  `aether-weaver-api/src/main/java/de/splatgames/aether/weaver/api/Inject.java:175-179`.
- **`@Inject` elements**: `method()` is mandatory (`Inject.java:192`), `at()` is mandatory
  (`:202`), `slice()` defaults to `{}` (`:215`), `id()` to `""` (`:225`), `require()` to `0`
  (`:240`), `allow()` to `0` (`:250`), `group()` to `""` (`:262`).
- **A handler must return `void`, or `AW1041`** — `Inject.java:35-39`;
  `HANDLER_RETURN_TYPE_NOT_VOID`, `Severity.ERROR` — `DiagnosticCode.java:548`.
- **A handler's parameters must be a prefix of the target method's own arguments, in
  declaration order, matched by erased type — otherwise `AW1040`** — `Inject.java:40-45`. A
  handler taking nothing, as in the fixture, is the trivially valid case.
- **`@At` is `@Target({})`, so it can only be written inside another annotation** —
  `aether-weaver-api/src/main/java/de/splatgames/aether/weaver/api/At.java:78-81`.
- **`@At` elements**: `value()` defaults to `Point.HEAD` (`At.java:92`), `custom()` to `""`
  (`:107`), `target()` to `""` (`:132`), `ordinal()` to `-1` (`:150`), `shift()` to
  `Shift.NONE` (`:168`), `by()` to `0` (`:181`), `access()` to `Access.ANY` (`:191`), `slice()`
  to `""` (`:203`).
- **`Point.HEAD` resolves to exactly one position and forbids `At.target()`** —
  `aether-weaver-api/src/main/java/de/splatgames/aether/weaver/api/Point.java:94-113`. In a
  constructor it is the position immediately *after* the constructor's own `super(...)` or
  `this(...)` call — `Point.java:97-104`. `HEAD` refuses any `shift()` with `AW1102` —
  `Point.java:106-108`. `HEAD` is usable by `@Inject` only; `@Redirect` and `@Wrap` report
  `AW1061` — `Point.java:110-111`.
- **`Point.RETURN` matches every return instruction, so a method with three `return`s produces
  three sites** — `Point.java:115-136`. **`Point.TAIL` keeps only the last in body order** —
  `Point.java:138-157`, and body order is not execution order (`:146-148`).
- **`method = "greet()"` means "named `greet`, taking no parameters, any return type".**
  A bare `greet` constrains no signature at all; a present-but-empty parameter list means "no
  parameters" —
  `aether-weaver-api/src/main/java/de/splatgames/aether/weaver/api/select/MethodSelector.java:24-35`.
- **A selector resolving to no method is `AW1020`, and the diagnostic lists every method the
  target declares** — `DiagnosticCode.java:330-341` (`METHOD_NOT_FOUND`, `Severity.ERROR`).
  **An inherited method is not a declared one**: a method the target only inherits has to be
  woven where it is declared — `Inject.java:186-188`, `DiagnosticCode.java:335-336`.
- **A selector resolving to more than one method is `AW1021`, listing the overloads; add the
  parameter types or use the `desc:` form** — `DiagnosticCode.java:358`, `:356-357`.

### The build-time run: what the reader adds and what happens

- **Goal `weave`, default phase `process-classes`, `requiresDependencyResolution =
  COMPILE_PLUS_RUNTIME`, `threadSafe = true`** —
  `aether-weaver-maven-plugin/src/main/java/de/splatgames/aether/weaver/maven/WeaveMojo.java:44-47`.
  Because the phase is a default of the mojo, an execution needs `<goal>weave</goal>` and no
  `<phase>`.
- **It rewrites `${project.build.outputDirectory}`, and that parameter is `readonly = true`** —
  `WeaveMojo.java:64-65`. A reader cannot point it elsewhere.
- **Other goals of the same plugin**: `weave-tests` (default phase `process-test-classes`,
  `ResolutionScope.TEST`) — `WeaveTestsMojo.java:31-34`; `stubs` (default phase
  `generate-sources`) — `StubsMojo.java:90-93`; `audit` (`requiresProject = false`, no phase) —
  `AuditMojo.java:66`.
- **Plugin coordinates**: groupId `de.splatgames.aether.weaver` (inherited, `pom.xml:21`),
  artifactId `aether-weaver-maven-plugin`, packaging `maven-plugin` —
  `aether-weaver-maven-plugin/pom.xml:21-22`.
- **The order of one run** is stated on the shared superclass —
  `aether-weaver-maven-plugin/src/main/java/de/splatgames/aether/weaver/maven/AbstractWeaveMojo.java:42-63`:
  skip check, directory check, read classpath manifests, read the module's own manifest, weave
  every `.class` under the directory in path order, write back only what changed, warn about
  planned targets that were not found, log every diagnostic, fail on error.
- **`META-INF/aether/weaves.json` in the class directory is the only source of the weaves that
  are applied** — `AbstractWeaveMojo.java:52-54`. Weaves found on other classpath entries are
  *not* applied; only their extension declarations are kept, and a weave declared by an entry
  `directEntries()` does not name is `AW3010` — `AbstractWeaveMojo.java:48-51`.
- **A missing manifest yields `null` and is deliberately not an error** —
  `AbstractWeaveMojo.java:261-268`, with the reason at `:263-265`: a module with no weaves is
  the overwhelmingly common case. The goal then returns after a debug-level line —
  `AbstractWeaveMojo.java:270-273`.
- **`ClassDirectory.manifest` returns `null` for absent, unreadable (`AW2300`) and unparseable
  (`AW2300`, or `AW2301` for a schema version this release does not read) alike** —
  `aether-weaver-maven-plugin/src/main/java/de/splatgames/aether/weaver/maven/ClassDirectory.java:68-96`.
  `MANIFEST_MALFORMED("AW2300", Severity.ERROR, Category.CONFIGURATION)` —
  `DiagnosticCode.java:1495`; `MANIFEST_VERSION_TOO_NEW("AW2301", …)` — `DiagnosticCode.java:1507`.
- **A class the manifest names but the directory does not hold is skipped in silence** —
  `ClassDirectory.java:103-108`.
- **The success line is `Aether Weaver: <n> weave(s), <m> class(es) rewritten (main).`** —
  `AbstractWeaveMojo.java:420-422`, with `describe()` returning `"main"` for the `weave` goal
  (`WeaveMojo.java:177-179`) and `"test"`-scope wording coming from `WeaveTestsMojo`.
- **A file the weaver returned no bytes for is left on disk untouched** —
  `AbstractWeaveMojo.java:362-367`; the comment gives the reason: rewriting identical content
  would change every class file's modification time on every build.
- **Fewer classes woven than the plan named targets is a warning naming the shortfall** —
  `AbstractWeaveMojo.java:296-305`, with the reason at `:297-299`: at build time it means a
  weave did not apply to an artefact that is about to be published.
- **The build fails only when a collected diagnostic has `Severity.ERROR` and `failOnError` is
  left set** — `AbstractWeaveMojo.java:413-419`. `failOnError` reads
  `aether.weaver.failOnError` and defaults to `true` — `AbstractWeaveMojo.java:97`.
- **Other goal parameters**: `aether.weaver.skip` default `false` (`AbstractWeaveMojo.java:86`),
  `aether.weaver.explain` default `false` (`:107`), `aether.weaver.dump` with no default
  (`:119`), `aether.weaver.weaveDependencies` default `false` (`WeaveMojo.java:74-75`),
  `aether.weaver.allowSigned` default `false` (`WeaveMojo.java:85-86`),
  `dependencyOutputDirectory` default `${project.build.directory}/aether-weaver/dependencies`
  (`WeaveMojo.java:96-97`).

### The manifest, and why the processor is not optional

- **`WeaveManifest.RESOURCE` is `"META-INF/aether/weaves.json"`** —
  `aether-weaver-api/src/main/java/de/splatgames/aether/weaver/api/manifest/WeaveManifest.java:219`.
  Discovery looks for that exact resource on every classpath root, so a manifest anywhere else
  is not found at all — `WeaveManifest.java:215-218`.
- **It is written once, at compile time, by the annotation processor** — `WeaveManifest.java:26-28`.
- **`ManifestEmitter` writes it through
  `filer.createResource(StandardLocation.CLASS_OUTPUT, "", WeaveManifest.RESOURCE)`** —
  `aether-weaver-processor/src/main/java/de/splatgames/aether/weaver/processor/ManifestEmitter.java:170-171`;
  nothing is written when the compilation declared nothing (`:155`).
- **The processor is registered as a service under
  `de.splatgames.aether.weaver.processor.WeaveProcessor`** —
  `aether-weaver-processor/src/main/resources/META-INF/services/javax.annotation.processing.Processor:1`.
  Its own JavaDoc: "a project that has this module on its annotation processor path gets it
  without configuring anything" —
  `aether-weaver-processor/src/main/java/de/splatgames/aether/weaver/processor/WeaveProcessor.java:40-42`.
- **It claims nothing: `process` always returns `false`**, leaving the annotations visible to
  every other processor in the round — `WeaveProcessor.java:41-42`.
- **It is registered for `@Weave` and `@Extension` only** — `WeaveProcessor.java:87`, with the
  two names at `:91` and `:94`.
- **The manifest is written in the final round and nowhere else**, because a `Filer` refuses to
  reopen a resource it created — `WeaveProcessor.java:190-193`.
- **`maven-compiler-plugin` 3.14 does not run annotation processors unless asked.** The
  repository's own statement: "implicit discovery was deprecated, and the default is now to skip
  processing entirely" — `aether-weaver-tests/pom.xml:120-126`, which sets `<proc>full</proc>`
  to fix it. The build pins `maven-compiler-plugin` 3.14.0 — `pom.xml:92`.
- **A missing manifest resource is the signal that a module was compiled without the processor,
  not that it had nothing to say** — `WeaveManifest.java:168-174`. The processor writes no
  resource at all for a module declaring neither a weave nor an extension.
- **`WeaveManifest.VERSION` is `1`; a document omitting `"version"` is read as `1`** —
  `WeaveManifest.java:203-215`.

### Verifying the weave applied

- **The `audit` goal lists what was woven into a compiled artefact from the artefact alone**,
  reading the class file attribute a woven class carries; it writes nothing, re-weaves nothing
  and raises no diagnostic — `AuditMojo.java:29-36`.
- **Its `artifact` parameter reads `aether.weaver.artifact` and defaults to
  `${project.build.outputDirectory}`** — `AuditMojo.java:82-84`.
- **Its report format**, one line per woven class and one indented line per modification, with
  a worked example, is at `AuditMojo.java:38-61`; the example output is
  `fixture/Target.class` / `  <- fixture.Greeting  INJECT  onGreet()V  ->  greet()` and a
  summary line `1 class, 1 modification, fingerprint <plan fingerprint>, no policy overrides`.
- **Arrows are degraded to `<-` and `->` when the charset of `System.out` cannot encode them**
  — `AuditMojo.java:46-50`.
- **A woven class carries the `AetherWeave` class file attribute** —
  `aether-weaver-engine/src/main/java/de/splatgames/aether/weaver/engine/stamp/WeaveAttributeWriter.java:13`.
- **It also carries the `@Woven` annotation by default.** `WeaverBuilder`'s field initialiser
  is `private Woven.Detail detail = Woven.Detail.SUMMARY;` —
  `aether-weaver-engine/src/main/java/de/splatgames/aether/weaver/engine/WeaverBuilder.java:86`.
  `SUMMARY` writes everything but `Woven.entries()` —
  `aether-weaver-api/src/main/java/de/splatgames/aether/weaver/api/Woven.java:281-287`; `NONE`
  suppresses the annotation and keeps the attribute (`:273-278`); `FULL` adds one `Entry` per
  modification, capped at 32 (`:290-295`). `@Woven` is `RUNTIME` retention, `@Target(TYPE)` —
  `Woven.java:81-83`.

### Running it a second time

- **A class already carrying this plan's fingerprint is skipped, and `weave` returns `null`** —
  `aether-weaver-engine/src/main/java/de/splatgames/aether/weaver/engine/Weaver.java:426-428`.
  The comment above it (`:417-425`) records that this gate must read the raw bytes, not the
  parsed `ClassModel`, because a plain `ClassFile.of()` sees `AetherWeave` as an unknown
  attribute and answers "not woven" for every class — a build-plugin test running the goal
  twice over one directory found the handler call emitted twice.
- **A class carrying a record from a *different* plan is `AW2201`, an error at build time** —
  `Weaver.java:759-770`, `ALREADY_WOVEN_DIFFERENT_PLAN("AW2201", Severity.ERROR,
  Category.IDEMPOTENCE)` — `DiagnosticCode.java:1462`. The remedy the diagnostic itself gives:
  a clean build, since the usual cause is an output directory woven once already and not
  rebuilt since a weave changed — `Weaver.java:765-770`.
- **The same situation under a load-time driver is `AW2202`, a warning** —
  `Weaver.java:744-757`, `LOAD_TIME_OVER_BUILD_TIME_WEAVE("AW2202", Severity.WARNING,
  Category.IDEMPOTENCE)` — `DiagnosticCode.java:1474`; both plans apply and any weave they have
  in common runs twice, and `alreadyWovenElsewhere` returns `true` so the class is woven again
  regardless (`Weaver.java:757`).
- **Under any other driver the class is left alone**: `alreadyWovenElsewhere` returns `false`
  (`Weaver.java:772`) and `weave` therefore returns `null` (`Weaver.java:429-430`), so the
  `AW2201` build failure comes from `failOnError`, not from anything having been written.

### The other two ways the same fixture is run (S-choose-driver's subject)

- **Load time**: a jar whose manifest carries
  `Premain-Class: de.splatgames.aether.weaver.agent.WeaverAgent` and
  `Can-Retransform-Classes: true`, passed as `-javaagent:<jar>=dump=<dir>` —
  `CrossDriverEquivalenceTest.java:154-166`, `:93-97`.
- **In-process**: `WeavingClassLoader.create(URL[] roots, ClassLoader parent, WeaverConfig
  config, DiagnosticListener listener)` —
  `aether-weaver-runtime/src/main/java/de/splatgames/aether/weaver/runtime/WeavingClassLoader.java:203-207`;
  used with `WeaverConfig.defaults()` at `TwoArtefactsTest.java:41-44` and with a configured
  dump directory at `CrossDriverEquivalenceTest.java:110-125`.
- **All three produce byte-identical output for this fixture** —
  `CrossDriverEquivalenceTest.java:36-63`.

### Writerside mechanics this page is bound by

- **No snippets directory is registered.** `Writerside/writerside.cfg:16-25` declares
  `<topics>`, `<images>`, `<categories>`, `<vars>`, `<build-config>`, `<settings>` and
  `<instance>` — and no `<snippets>` element.
- **The gate fails any `src="…​.java"` while that is so**, with
  `code block names src="…" but no <snippets> directory is registered` —
  `build-config/docsite/check-docs.py:284-288`. The check fires for `src` values ending in
  `.java`, `.xml`, `.kt`, `.json`, `.properties`, `.mermaid` or `.puml` (`:284`).
- **The directory is read out of `writerside.cfg` as `<snippets … src="…"/>` and resolved
  relative to `Writerside/`** — `check-docs.py:233-237` (`snippets_dir = MODULE /
  match.group(1)`), and a sample is looked up as `snippets_dir / src` (`:287`). So with, for
  example, `<snippets src="snippets"/>`, a block written `{ src="FirstWeave.java" }` resolves to
  `Writerside/snippets/FirstWeave.java`.
- **`Writerside/README.md:98-99` names the absence deliberately**: "`<snippets src="..."/>` in
  `writerside.cfg` is what `src=` on a code block resolves against. Add it when there are code
  samples to pull from files rather than paste."
- **STYLE.md requires samples to come from such files**, "pulled with an empty fence and
  `{ src="File.java" }`, narrowed with `include-symbol`" — `build-config/docsite/STYLE.md:145-149`.
  The builder additionally checks that `include-symbol` resolves and that `include-lines` names
  lines the file has — `STYLE.md:209-211`.
- **A `howto` must carry `<show-structure>`, a `<tldr>` naming the prerequisite page and the
  outcome, at least one `<procedure>` or a complete configuration block, and a `<seealso>`** —
  `STYLE.md:73-82`. "A snippet with an ellipsis in the middle is a finding" — `STYLE.md:81-82`.
- **`%version%` is `0.1.0`, `%group%` is `de.splatgames.aether.weaver`, `%jdk%` is `25`,
  `%maven%` is `3.9`** — `Writerside/v.list:10`, `:9`, `:11`, `:12`. A three-part literal in a
  topic is rejected — `check-docs.py:290-292`.
- **`<seealso>` categories are `concepts`, `guides`, `reference`, `external`** —
  `Writerside/c.list:5-8`. Labels available: `wip`, `experimental`, `internal`, `0.1.0` —
  `Writerside/labels.list:12-28`.
- **Only `overview.topic` exists and is in the tree** — `Writerside/aw.tree:12`,
  `Writerside/topics/` otherwise holds six empty section directories. Every link and every
  `<seealso>` target must already be committed, and the topic must be added to `aw.tree` or
  `check_orphans` fails it — `check-docs.py:200-204`.
- **Glossary terms usable with `<tooltip term="…">`** include `weave`, `target`, `handler`,
  `selector`, `injection point`, `site`, `slice`, `plan`, `driver`, `instance weave`, `static
  weave`, `stamp`, `manifest` — `Writerside/cfg/glossary.xml:9-21`.

### What the reader must have done already (S-install)

`S-install` is `todo`; nothing may be assumed about its wording, only about the facts it
covers. The minimum this page depends on:

- **`de.splatgames.aether.weaver:aether-weaver-api`** on the compile classpath — it is the only
  dependency the one sample consumer in the tree declares to write weaves —
  `aether-weaver-ide/aether-weaver-idea/sample/pom.xml:29-35`. Its only non-test dependency is
  `org.jetbrains:annotations` at `provided` — `aether-weaver-api/pom.xml:24-27`, `pom.xml:172-177`.
- **`de.splatgames.aether.weaver:aether-weaver-processor`** reachable by javac, plus annotation
  processing actually switched on — see the `<proc>full</proc>` fact above.
- **`de.splatgames.aether.weaver:aether-weaver-maven-plugin`** with an execution binding the
  `weave` goal.
- **JDK 25 and Maven 3.9 or newer** — `pom.xml:75`, `pom.xml:305-312`; the plugin declares
  `<prerequisites><maven>3.9</maven></prerequisites>` — `aether-weaver-maven-plugin/pom.xml:27-29`.
- **The BOM does not manage the Maven plugin**, so its version is written out where the plugin
  is declared — `aether-weaver-bom/pom.xml:23-61` has entries for api, engine, runtime, agent,
  processor, testkit and `aether-weaver`, and none for the plugin.

## Identifiers

Spelled as the source spells them.

| Thing | Spelling | Anchor |
| --- | --- | --- |
| groupId | `de.splatgames.aether.weaver` | `pom.xml:21` |
| annotation package | `de.splatgames.aether.weaver.api` | `Weave.java:1` |
| weave annotation | `@Weave`, elements `value`, `targets`, `kind`, `priority`, `require`, `tags`, `phase` | `Weave.java:198,216,227,246,257,276,287` |
| weave kinds | `Weave.Kind.INSTANCE`, `Weave.Kind.STATIC` | `Weave.java:314,335` |
| inject annotation | `@Inject`, elements `method`, `at`, `slice`, `id`, `require`, `allow`, `group` | `Inject.java:192,202,215,225,240,250,262` |
| repeat container | `Inject.Container`, `@ApiStatus.Internal` | `Inject.java:275-279` |
| point annotation | `@At`, elements `value`, `custom`, `target`, `ordinal`, `shift`, `by`, `access`, `slice` | `At.java:92,107,132,150,168,181,191,203` |
| shifts | `At.Shift.NONE`, `BEFORE`, `AFTER`, `BY` | `At.java:216,221,233,239` |
| field access | `At.Access.ANY`, `GET`, `PUT`, `STATIC_GET`, `STATIC_PUT` | `At.java:256,261,266,271,276` |
| points | `Point.HEAD`, `RETURN`, `TAIL`, `INVOKE`, `INVOKE_AFTER`, `FIELD`, `NEW`, `CONSTANT`, `THROW` | `Point.java:113,136,157,182,204,226,247,273,291` |
| manifest resource | `META-INF/aether/weaves.json` | `WeaveManifest.java:219` |
| processor class | `de.splatgames.aether.weaver.processor.WeaveProcessor` | service file `:1` |
| agent class | `de.splatgames.aether.weaver.agent.WeaverAgent` (`Premain-Class` and `Agent-Class`) | `CrossDriverEquivalenceTest.java:158`, `DynamicAttachTest.java:141` |
| goals | `weave`, `weave-tests`, `stubs`, `audit` | `WeaveMojo.java:44`, `WeaveTestsMojo.java:31`, `StubsMojo.java:90`, `AuditMojo.java:66` |
| goal properties | `aether.weaver.skip`, `aether.weaver.failOnError`, `aether.weaver.explain`, `aether.weaver.dump`, `aether.weaver.weaveDependencies`, `aether.weaver.allowSigned`, `aether.weaver.artifact` | `AbstractWeaveMojo.java:86,97,107,119`; `WeaveMojo.java:74,85`; `AuditMojo.java:82` |
| class file attribute | `AetherWeave` | `WeaveAttributeWriter.java:13` |
| stamp annotation | `@Woven`, `Woven.Detail.NONE/SUMMARY/FULL` | `Woven.java:83,278,287,295` |
| runtime driver | `WeavingClassLoader.create(URL[], ClassLoader, WeaverConfig, DiagnosticListener)` | `WeavingClassLoader.java:203-207` |
| default config | `WeaverConfig.defaults()` | `WeaverConfig.java:86` |

Diagnostic codes this page can legitimately name, with what to write instead:

| Code | Severity | Raised where | Remedy the source gives |
| --- | --- | --- | --- |
| `AW1001` | ERROR | processor, reported by `WeaveProcessor` itself (`WeaveProcessor.java:71-73`) | name a target with `value` or `targets` (`Weave.java:26-28`) |
| `AW1002` | ERROR | same | declare only one of the two (`Weave.java:26-28`) |
| `AW1008` | WARNING | processor (`WeaveProcessor.java:71-74`) | make the weave class `final` (`Weave.java:101-104`) |
| `AW1009` | INFO | processor | write the class literal instead (`Weave.java:31-33`) |
| `AW1020` | ERROR | selector resolution (`DiagnosticCode.java:330-341`) | name the declaring class, or add parameter types |
| `AW1021` | ERROR | selector resolution (`DiagnosticCode.java:346-359`) | add parameter types, or use `desc:` |
| `AW1041` | ERROR | handler shape (`DiagnosticCode.java:540-549`) | declare the handler `void`; use a `ReturnableCallback` to change the return |
| `AW1043` | ERROR | `WeaveClassParser.java:758-761` for an empty `at`, and injection-point resolution for no match | "add at = @At(Point.HEAD), or whichever point it should attach to" — the parser's own remedy string, `WeaveClassParser.java:760` |
| `AW1081` | ERROR | `WeaveProcessor.java:71-74` and the engine | initialise merged state from an `@Inject` at the constructor's `HEAD` (`Weave.java:96-99`) |
| `AW2201` | ERROR | `Weaver.java:759` | a clean build |
| `AW2202` | WARNING | `Weaver.java:745` | configure agent and build plugin with different weaves, or drop one |
| `AW2300` | ERROR | `ClassDirectory.java:93-95` | the manifest could not be read |

## Surprises

- **A build with the plugin but without the annotation processor weaves nothing and says so
  only at debug level.** The manifest is the only source of applied weaves
  (`AbstractWeaveMojo.java:52-54`); no manifest gives `null` (`ClassDirectory.java:83-87`),
  `null` gives an empty weave list (`AbstractWeaveMojo.java:266-268`), and the goal returns
  after `getLog().debug("Aether Weaver: nothing to weave in main classes.")`
  (`AbstractWeaveMojo.java:270-273`). Combined with `maven-compiler-plugin` 3.14 skipping
  processing by default (`aether-weaver-tests/pom.xml:120-126`), the default failure mode of a
  first weave is a green build that changed nothing. This is the single most valuable thing on
  the page and should be a `<warning>`.
- **An omitted `require` is not the same as `require = 0`, and `@Inject.require()`'s declared
  default of `0` is not the effective default.** The engine reads an omitted `require` as `1`:
  `Annotations.has(annotation, "require") ? Annotations.intOr(annotation, "require", 0) : 1` —
  `aether-weaver-engine/.../parse/WeaveClassParser.java:764-769`, with the comment: a class file
  records only the elements that were written, so an explicit `0` is distinguishable from an
  omitted one. So the minimal `@Inject(method = "greet()", at = @At(Point.HEAD))` *does* fail
  with `AW1043` when the selector matches nothing — the reader who writes a typo gets an error,
  not silence. Documented at `Inject.java:230-234`, and the code agrees.
- **`At.ordinal()` defaults to `-1`, not `0`** — `At.java:150`. `-1` keeps every match; `0`
  would silently bind to the first (`At.java:138-140`). As a `Slice` bound the default is `0`
  instead (`At.java:145-146`).
- **An `@At` that resolves to nothing is not itself an error** — `At.java:46-48`; the
  declaration's `require` is what decides.
- **The weave class file stays in the built artefact.** An instance weave is dissolved and "the
  weave class itself is never loaded" (`Weave.java:302`), but the goal only writes back files
  the weaver returned new bytes for (`AbstractWeaveMojo.java:362-367`) and nothing anywhere in
  `aether-weaver-maven-plugin/src/main/java/` or `Weaver.java` calls `Files.delete` or
  `deleteIfExists`. `Audit.class` ships.
- **`Point.HEAD` in a constructor is after `super(...)`, not at offset 0** — `Point.java:97-104`.
  The `super(...)` call is found by scanning for the first constructor invocation that does not
  belong to an instantiation inside the argument list.
- **The handler in the reference fixture is package-private, not `public`** —
  `CrossDriverEquivalenceTest.java:225`. That works only because the weave is an instance weave
  and the handler is merged into the target. Under `kind = Kind.STATIC` the injected call is an
  ordinary cross-class invocation, and a handler that is neither `public` nor in the target's
  package is `AW1042` — checked by the annotation processor alone, so a build that skips the
  processor gets an `IllegalAccessError` at the injected call's first execution instead
  (`Weave.java:328-332`, `Inject.java:66-72`).
- **`@Weave(phase = …)` does nothing** — `Weave.java:280-283`. It is recorded on the parsed
  weave and written to the manifest, and no stage selects weaves by it.
- **The end-to-end tests do not use the annotation processor at all.** They compile fixtures
  with `-proc:none` (`Fixtures.java:47`) and hand-write the manifest
  (`CrossDriverEquivalenceTest.java:131-136`, `TwoArtefactsTest.java:155-161`). A page that
  presents the e2e shape as "what a build does" is describing the fixture, not the build.
- **The `weave` goal's `classesDirectory` is `readonly = true`** — `WeaveMojo.java:64`. There is
  no configuration for weaving somewhere else.

## Could not establish

- **The plugin's goal prefix.** `aether-weaver-maven-plugin/pom.xml:64-71` declares
  `maven-plugin-plugin` with no `<goalPrefix>`, so the prefix is whatever maven-plugin-tools
  derives from the artefact id. That derivation lives in the plugin tools, not in this
  repository. Writing `mvn aether-weaver:audit` cannot be justified from the source here;
  the fully qualified form
  `mvn de.splatgames.aether.weaver:aether-weaver-maven-plugin:audit` can. Settling it needs a
  build of the plugin and a read of the generated `plugin.xml`.
- **Whether `<proc>full</proc>` is strictly required in the reader's pom.** The repository
  asserts it for `maven-compiler-plugin` 3.14 in a comment (`aether-weaver-tests/pom.xml:120-126`)
  and acts on it, while `Fixtures.java:44-47` passes `-proc:none` to an in-process `javac`
  precisely because the processor would otherwise run — which is the opposite default. The two
  are consistent only if the plugin's default differs from raw `javac`'s. Settling it needs a
  run of both, not a reading.
- **Whether `<snippets src="…"/>` is the spelling the Writerside XSD accepts.** The project's
  own checker matches `<snippets … src="…">` (`check-docs.py:235`) and `Writerside/README.md:98`
  writes it that way, but no such element exists in `writerside.cfg` today and the schema is
  fetched from `resources.jetbrains.com`. **Registering the directory is a prerequisite for this
  page**: until it exists, every `{ src="…" }` block fails the gate, and STYLE.md requires such
  blocks. This needs a decision by whoever owns `writerside.cfg` before the page can be written
  as STYLE.md demands.
- **Whether the minimal woven application needs `aether-weaver-api` on its runtime classpath.**
  A woven target references `de.splatgames.aether.weaver.api.callback.CallbackSupport`,
  `Callback` and `ReturnableCallback` only where a handler takes a callback —
  `aether-weaver-engine/.../inject/CallbackEmission.java:47-55` — and `OperationSupport` /
  `Operation` for `@Wrap` — `WrapInjector.java:64-71`. `@Woven` is `RUNTIME` retention
  (`Woven.java:81`). Whether the merged handler of a callback-free weave leaves any other
  reference to the api behind was not traced through the merge stage.
- **What the reader sees in the console for a successful first weave beyond the summary line.**
  `AbstractWeaveMojo.java:420-422` gives the summary; the diagnostics logged before it depend on
  what the plan reported, and no test in the sources read asserts the full log of a clean run.
- **Whether `mvn -B -o verify` currently passes with the e2e suite.** Not run; the instruction
  set forbids measuring by guessing and nothing here required a build.

## Not this page

- **`AW1080`, merged-handler collisions between two artefacts, and the priority ordering that
  decides which handler runs first** — `TwoArtefactsTest.java:88-123` and `:31-81`, message text
  "rename all but one" asserted at `:117-122`. That is `C-ordering` and `R-diag-1`.
- **Dynamic attach: `AW2101` refusing a structural weave over an already-loaded class, and a
  static weave reaching one** — `DynamicAttachTest.java:31-93`. That is `G-load-time` and
  `R-agent-options`.
- **The full `@At` resolution pipeline — slice, then point, then ordinal, then shift, then the
  legality check — and `AW1102`, `AW1110`, `AW1111`, `AW1112`, `AW1105`, `AW1130`, `AW1026`** —
  `At.java:16-44`. That is `C-injection-points`.
- **The full selector grammar, the three forms, `AW1015`–`AW1019`** —
  `MethodSelector.java:24-68`, `At.java:118-128`. That is `C-selectors` / `R-selector-grammar`.
- **`@Weave` element-by-element rules, and every code each element can raise** —
  `Weave.java:184-296`. That is `R-annotations`.
- **The manifest document format, its four keys, and `WeaveManifest.Weave`'s component order as
  used at `CrossDriverEquivalenceTest.java:133-135`** — `WeaveManifest.java:36-60`. That is
  `R-manifest`.
- **`stubs`, extension methods and the `--patch-module` compiler argument** —
  `aether-weaver-ide/aether-weaver-idea/sample/src/test/java/com/acme/payments/AmountsInUse.java:29-50`,
  `StubsMojo.java:90-93`. That is `C-extensions` and `R-maven-goals`.
- **Testkit `Weaving.of(Class…)` / `weave(Class)` / `assertThatWoven`** —
  `aether-weaver-testkit/.../Weaving.java:131-132,194-195`,
  `aether-weaver-testkit/.../WovenAssert.java:115`. That is `G-testing`.
- **Correction for whoever reads the neighbouring dossier**: `build-config/docsite/research/S-install.md`
  cites `Writerside/v.list:13` for `%version%`, `:14-15` for `%jdk%`/`%maven%`, and
  `Writerside/README.md:104-106` for the snippets note. Read directly, those are
  `Writerside/v.list:10`, `:11-12`, and `Writerside/README.md:98-99`. The facts are right; the
  line numbers in that file are shifted.
