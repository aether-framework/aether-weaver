# S-what-is — What Aether Weaver is

Page: `Writerside/topics/start/what-is-aether-weaver.md`, kind `explain`, section `start`.
It is also the instance's **start page** — `Writerside/aw.tree:5` sets
`start-page="what-is-aether-weaver.md"`, and `aw.tree:8` is the only `toc-element` with a
topic. Every other section is `wip="true"` (`aw.tree:10-14`), so **no other page exists to
link to**. `<seealso>` may therefore use only `<category ref="external">` (`Writerside/c.list:7`).

## Facts

### What the thing is

- **Aether Weaver is described by its own build as "a general-purpose bytecode weaving framework for the JVM, built on the standard Java Class-File API".** — `pom.xml:27-30`
- **The group id is `de.splatgames.aether.weaver` and the reactor version is `0.1.0-SNAPSHOT`.** — `pom.xml:21`, `pom.xml:23`
- **The engine writes the literal string `"0.1.0"` into every weave record as the weaver version.** — `aether-weaver-engine/.../engine/Weaver.java:210` (`static final String VERSION = "0.1.0"`); the agent prints the same literal on its startup line, `aether-weaver-agent/.../agent/WeaverAgent.java:101`
- **The build refuses to run below JDK 25**, with the message "Aether Weaver requires JDK 25+: it is built on the standard Class-File API." — `pom.xml:305-309` (`<requireJavaVersion>[25,)`)
- **`maven.compiler.release` is 25.** — `pom.xml:75`
- **Maven 3.9 or later is required.** — `pom.xml:310-312` (`<requireMavenVersion>[3.9,)`)
- **ASM, Javassist, Byte Buddy and cglib are banned dependencies in every module, tests included; bytecode goes through `java.lang.classfile`.** — `pom.xml:313-330` (excludes `org.ow2.asm:*`, `asm:*`, `org.javassist:*`, `javassist:*`, `net.bytebuddy:*`, `cglib:*`, `cglib-nodep:*`), reason stated at `pom.xml:10-14`
- **Nine Maven modules are in the reactor**, in this order: `aether-weaver-bom`, `-api`, `-engine`, `-runtime`, `-agent`, `-processor`, `-maven-plugin`, `-testkit`, `-tests`. — `pom.xml:62-72`
- **The dependency arrow points one way, `api <- engine <- drivers`, and drivers contribute only I/O and lifecycle.** — `pom.xml:5-8`; enforced by `aether-weaver-tests/.../architecture/ProjectStructureTest.java:98-117` (engine imports no driver) and `:119-148` (no driver imports another)
- **There are five drivers: `runtime`, `agent`, `processor`, `maven`, `testkit`.** — `ProjectStructureTest.java:101-102`, `:122-123`
- **The agent is layered on the runtime by design and is the one exempted pair.** — `ProjectStructureTest.java:137-138`; `aether-weaver-agent/pom.xml:30` depends on `aether-weaver-runtime`

### The model — a weave, its targets, its handlers

- **`@Weave` is a runtime-retained annotation that may only go on a type.** — `aether-weaver-api/.../api/Weave.java:179-182` (`@Documented`, `@Retention(RetentionPolicy.RUNTIME)`, `@Target(ElementType.TYPE)`)
- **A weave names its targets one of two ways: `value()` as class literals, or `targets()` as binary-name strings. Both default to an empty array.** — `Weave.java:198`, `Weave.java:216`
- **Declaring both is `AW1002` and declaring neither is `AW1001`; in both cases the weave is left with no targets.** Both are reported twice, once by each stage: engine `aether-weaver-engine/.../engine/parse/WeaveClassParser.java:282-294` (returns `List.of()`), processor `aether-weaver-processor/.../processor/WeaveProcessor.java:497-518`.
  - `AW1002` remedy as the code writes it: *"keep the class literals and delete targets=, or the other way round"* — `WeaveClassParser.java:286`, `WeaveProcessor.java:502`
  - `AW1001` remedy as the code writes it: *"name the class it modifies: @Weave(TheTarget.class)"* — `WeaveClassParser.java:292`; the processor's longer form is `WeaveProcessor.java:512-514`
  - Declared: `DiagnosticCode.WEAVE_NO_TARGETS("AW1001", Severity.ERROR, Category.DECLARATION, "@Weave declares no targets")` — `aether-weaver-api/.../api/diagnostic/DiagnosticCode.java:132-133`; `WEAVE_DUPLICATE_TARGET_DECLARATION("AW1002", …, "Both value() and targets() given")` — `DiagnosticCode.java:145-146`
- **A weave with no target cannot be constructed in the engine's model at all: the record's compact constructor throws `IllegalArgumentException("weave … declares no target")` for an empty target list.** — `aether-weaver-engine/.../engine/model/WeaveClass.java:80-83`
- **A target must be a class or an interface; a primitive or an array descriptor is `IllegalArgumentException`.** — `aether-weaver-engine/.../engine/model/TargetRef.java:33-36`
- **A target reference records which spelling was used, and the spelling is uniform across one weave because the parser refuses a mixture.** — `TargetRef.java:23` (`record TargetRef(ClassDesc type, boolean declaredAsClassLiteral)`), `TargetRef.java:12-14`
- **A handler is "the method control is handed to".** — `aether-weaver-api/.../api/model/InjectorSpec.java:128` (`@param handler`), record component at `InjectorSpec.java:149`
- **One injection specification names one handler, one target method and one or more positions inside it.** — `InjectorSpec.java:18-19`
- **A method carrying `@Inject`, `@Redirect` or `@Wrap` is a handler; that check comes first for a method.** — `Weave.java:115-117`
- **The three handler annotations differ in what happens to the matched instruction: `@Inject` leaves it and adds a call beside it, `@Redirect` replaces it so it never happens, `@Wrap` hands it over as an `Operation` the handler may perform, repeat or skip.** — `aether-weaver-api/.../api/package-info.java:38-56`
- **A weave declaring several targets is planned once per target per injector: three targets and two injectors become six plan entries.** — `aether-weaver-engine/.../engine/plan/WeavePlanner.java:55-58`, loop at `:90-99`

### Kind, and what "dissolve" means

- **`kind()` defaults to `Kind.INSTANCE`.** — `Weave.java:227`
- **`Kind` has exactly two constants, `INSTANCE` and `STATIC`.** — `Weave.java:295`, `:314`, `:335`
- **`INSTANCE`: the weave's merged fields and methods are copied onto the target, its handlers become methods of the target, and every reference among them is rebound to the target's own members.** — `Weave.java:299-303`
- **`STATIC`: nothing is merged, the target's member set is unchanged, and the weave is applicable by retransformation.** — `Weave.java:317-322`
- **Under `STATIC`, a non-static handler is `AW1005`, a `@Shadow` member is `AW1090`, a `@Unique` member is `AW1091`, and an unreachable handler is `AW1042`.** — `Weave.java:324-332`
- **The operational definition of "dissolves" is in one line of the planner: a weave dissolves when `weave.kind() == Weave.Kind.INSTANCE && (!weave.members().isEmpty() || declaresItsOwnHandler(weave))`.** — `WeavePlanner.java:85-86`. An `INSTANCE` weave with no members and no handler of its own therefore does **not** dissolve.
- **A dissolving weave is additionally indexed against each of its targets in the plan's structural index, which is what keeps a weave that declares members and no injector from vanishing from the plan.** — `WeavePlanner.java:56-58`, `:95-98`
- **The plan answers the per-class question through two indexes: `entriesFor(String)` for injections and `structuralFor(String)` for weaves that dissolve into the class.** — `aether-weaver-engine/.../engine/plan/WeavePlan.java:23-24`, `:103-118`
- **Moving a member's body needs the weave's own class file, because the engine's model carries declarations and no code.** — `WeaveClass.java:23-27`; missing bytes is `AW1096`

### Where weaving happens — one engine, four entry points

- **`Weaver` is the single class a driver hands a class to.** — `Weaver.java:48` ("Applies a plan to one class at a time, and is where a driver hands a class to the engine"), class at `Weaver.java:72`
- **Two overloads: `byte[] weave(String internalName, byte[] original)` and `byte[] weave(String internalName, ByteSupplier original)`; both return `@Nullable`.** — `Weaver.java:259-260`, `Weaver.java:284-285`
- **`null` from either means "use this class unchanged"; it does not distinguish "nothing to do" from "refused along the way".** — `Weaver.java:55-57`, `:256`, `:281`
- **Under a verifier refusal at `REPORT`, the return is not `null` but the *original* bytes, so a driver that writes whatever it gets back still writes a loadable class.** — `Weaver.java:57-59`, `:479-488`
- **Build-time weaving goes through the Maven goal `weave`, bound by default to `process-classes`.** — `aether-weaver-maven-plugin/.../maven/WeaveMojo.java:44-47` (`@Mojo(name = "weave", defaultPhase = LifecyclePhase.PROCESS_CLASSES, requiresDependencyResolution = COMPILE_PLUS_RUNTIME, threadSafe = true)`)
- **Test classes have their own goal, `weave-tests`, bound to `process-test-classes`.** — `WeaveTestsMojo.java:31-34`
- **The plugin declares two further goals, `stubs` (bound to `generate-sources`) and `audit` (`requiresProject = false`).** — `StubsMojo.java:90-93`, `AuditMojo.java:66`
- **Load-time weaving before `main` is `WeaverAgent.premain(String, Instrumentation)`, called by the JVM for `-javaagent`.** — `WeaverAgent.java:125-128`
- **Dynamic attach is `WeaverAgent.agentmain(String, Instrumentation)`; it additionally retransforms already-loaded classes, because installing a transformer alone changes nothing already in memory.** — `WeaverAgent.java:143-146`, `Weaver`-side install at `WeaverAgent.java:226-235`
- **Classes the JVM had already defined when `premain` ran are not retransformed.** — `WeaverAgent.java:118-119`
- **A weave that would change an already-loaded target's member set is `AW2101` under dynamic attach; the remaining classes are still woven in full and the agent stays installed.** — `DiagnosticCode.java:1447-1448`, prose at `:1443-1446`; `Weave.java:309-312` adds that one `AW2101` is reported per weave, naming every affected target together, not one per target
- **The third driver is `WeavingClassLoader`, created with `WeavingClassLoader.create(URL[] roots, …)`.** — `aether-weaver-runtime/.../runtime/WeavingClassLoader.java:204`
- **The fourth entry point is the testkit: `Weaving.of(Class<?>... weaves)`, which reads each weave's own class file, parses it, and hands it to `Weaver.builder()`.** — `aether-weaver-testkit/.../testkit/Weaving.java:132`, body `:143-157`. It runs the weaver as `Driver.BUILD` (the default) with `VerificationPolicy.STRICT` (`Weaving.java:155`).
- **The agent and the class loader both set `Weaver.Driver.LOAD`; the Maven plugin sets no driver and so takes the default, `Weaver.Driver.BUILD`.** — `WeaverAgent.java:210`, `WeavingClassLoader.java:221`, `AbstractWeaveMojo.java:281-290` (no `.driver(…)` call), default at `WeaverBuilder.java:89`

### The guarantee this page exists to state

- **All three drivers produce byte-identical output.** `CrossDriverEquivalenceTest.allThreeDriversAgree` weaves one fixture at build time, under `-javaagent`, and through `WeavingClassLoader`, and asserts the three byte arrays are equal. — `aether-weaver-tests/.../e2e/CrossDriverEquivalenceTest.java:36-63`; title at `:37` "build time, -javaagent and WeavingClassLoader produce the same bytes"
- **The test also asserts the fixture really changes, so the agreement is not an agreement about nothing.** — `CrossDriverEquivalenceTest.java:65-74`
- **The reason given in the test itself: the class-loader driver reaches the engine through a third discovery and a third configuration, and agreeing with the other two is what says none of them carries weaving logic of its own.** — `CrossDriverEquivalenceTest.java:58-60`
- **The fixture is a four-file source set compiled in the test: `fixture.Target` with `greet()`, `fixture.Trace.say(String)`, the weave `fixture.Audit`, and `fixture.Main`.** — `CrossDriverEquivalenceTest.java:193-239`. The weave is exactly:
  `@Weave(Target.class) public final class Audit { @Inject(method = "greet()", at = @At(Point.HEAD)) void onGreet() { Trace.say("woven"); } }` — `:213-229`

### What the weaver refuses, whatever the configuration

`DefaultWeavePolicy` is the default policy (`WeaverBuilder.java:70`). `decide` is at
`aether-weaver-engine/.../engine/policy/DefaultWeavePolicy.java:113` and checks in this order:

- **A declared weave class → `AW1087`** — `DefaultWeavePolicy.java:117-121`
- **Anything under `de.splatgames.aether.weaver.` → `AW3003`, "under any configuration"** — `DefaultWeavePolicy.java:45` (`OWN_PREFIX`), `:123-127`
- **`java.*` → `AW3001`, under any configuration, because its classes load before any transformer can be installed** — `DefaultWeavePolicy.java:42` (`ALWAYS_DENIED_PREFIX = "java."`), `:129-133`
- **Class file major version below 50 → `AW2003`** — `DefaultWeavePolicy.java:39` (`public static final int MINIMUM_MAJOR_VERSION = 50`), `:135-139`
- **A signed code source → `AW3002` unless `allowSigned()`** — `DefaultWeavePolicy.java:141-145`, builder switch at `:297-298`
- **`javax.`, `jdk.`, `sun.`, `com.sun.` → `AW3001` unless the exact package is reopened** — `DefaultWeavePolicy.java:48-49` (`JDK_PREFIXES`), `:147-152`. The remedy is spelled in the message: `aether.weaver.policy.allowPackage=<package>` — `DefaultWeavePolicy.java:150-151`. The reopening is a set-membership test on the exact package, so reopening `com.sun.crypto` says nothing about `com.sun.crypto.provider` — `DefaultWeavePolicy.java:224-225`.
- **Otherwise `Decision.allow()`** — `DefaultWeavePolicy.java:154`

### What a weave cannot change

- **A weave describes changes to bodies and to the member set; it does not change the class's place in the type hierarchy.** The five refusals: extends anything but `Object` `AW1006`, implements an interface `AW1084`, declares type parameters `AW1007`, declares a constructor `AW1081`, declares a static initialiser `AW1082`. — `Weave.java:90-100`; the same list at `api/package-info.java:137-140`
- **Adding an interface to a target is not a 0.1.0 capability.** — `Weave.java:92-93`
- **A merged field's initialiser does not travel with it; the field arrives with the JVM's default value and `AW1093` says so. The remedy in the source: write the value from an `@Inject` at the target constructor's `Point.HEAD`.** — `Weave.java:132-135`
- **A `final` weave class is `AW1008`, a warning; an `abstract` weave is exempt because `@Accessor` and `@Invoker` have an abstract spelling.** — `Weave.java:101-104`

### What the class carries afterwards

- **The engine stamps an `AetherWeave` class-file attribute onto every class it changed, and additionally a `@Woven` annotation at the level `wovenDetail` asks for.** — `Weaver.java:505-533`; attribute name constant `WeaveAttribute.NAME = "AetherWeave"` at `aether-weaver-engine/.../engine/internal/transform/WeaveAttribute.java:78`
- **The attribute is written whatever the detail level says, and it is the attribute the idempotence gate reads; only the annotation is affected by `wovenDetail`.** — `WeaverBuilder.java:302-304`
- **`Woven.detail()` defaults to `Detail.SUMMARY`; the three constants are `NONE`, `SUMMARY`, `FULL`.** — `aether-weaver-api/.../api/Woven.java:130`, `:270-295`
- **The supported way to ask a class at run time what was done to it is `WovenInfo.of(Class<?>)`, which returns `Optional<WovenInfo>`.** — `aether-weaver-api/.../api/WovenInfo.java:93`
- **A plan's fingerprint is a 64-character hex string.** — asserted at `aether-weaver-engine/src/test/.../engine/WeaverTest.java:204` (`assertThat(weaver.fingerprint()).hasSize(64)`), accessor `Weaver.java:553`
- **Re-weaving the same plan is silently skipped; a *different* plan's record is `AW2201` (error, class left alone) at build time and `AW2202` (warning, woven anyway) at load time.** — `Weaver.java:426-431`, `:737-773`; codes at `DiagnosticCode.java:1462-1463`, `:1474-1475`. The `AW2202` remedy as the code writes it: *"Configure the agent and the build plugin with different weaves, or drop one of them"* — `Weaver.java:752-755`.

### How a weave is found

- **Discovery reads one resource per classpath root, at the exact path `META-INF/aether/weaves.json`; a manifest anywhere else is not found.** — `aether-weaver-api/.../api/manifest/WeaveManifest.java:216-219` (`public static final String RESOURCE = "META-INF/aether/weaves.json"`)
- **The manifest format version is 1.** — `WeaveManifest.java:211`
- **The engine never loads a weave class; it parses the class file.** `WeaveClassParser.parse(ClassModel, Origin)` is the entry — `WeaveClassParser.java:158`. The only `Class.forName` calls in the engine are a JFR probe (`observe/WeaveEvents.java:90-91`) and a bootstrap-loader existence test for `java.lang` types (`select/ResolutionContext.java:182-190`); neither touches a weave. The runtime and agent modules contain no `Class.forName` or `loadClass` call at all.

### Ordering and determinism

- **Where two declarations meet at one place they are sorted by priority descending, then weave class name, handler name, handler descriptor.** — comparator at `aether-weaver-engine/.../engine/plan/OrderKey.java:33-37`
- **`priority()` defaults to 0 and higher runs first; negative values mean the weave runs after the ones that say nothing.** — `Weave.java:246`, `:234`, `:242`
- **The sort is a stable `List.sort` over entries built in parse order, which is what makes two builds of the same inputs agree.** — `WeavePlanner.java:103-105`, reasoning at `OrderKey.java:15-18`

### Concurrency, as the code shows it

- **`Weaver`'s counters are `LongAdder`s, so one weaver instance serves every thread a parallel-capable class loader weaves on.** — `aether-weaver-engine/.../engine/observe/Statistics.java:31-43` (five `LongAdder` fields), claim at `Weaver.java:66-67`
- **`WeaverBuilder` is not thread-safe and is not reusable: `build()` twice plans twice and produces two weavers.** — `WeaverBuilder.java:46-49`; the accumulating fields are plain `ArrayList`s, `WeaverBuilder.java:57`, `:64`
- **`build()` returns a weaver even when planning reported errors, so a driver can print what went wrong.** — `WeaverBuilder.java:41-43`, `:354`; asserted by `WeaverTest.java:176-188` ("conflicts are reported and a weaver is still returned", `AW1087` reported, plan size still 2)

### Builder defaults (all read at `WeaverBuilder`)

- `weaveBytes` = `WeaveBytes.NONE` — `:61`
- `listener` = `DiagnosticListener.NOOP`, i.e. diagnostics are **discarded** unless a driver sets one — `:67`
- `policy` = `DefaultWeavePolicy.standard()` — `:70`
- `verification` = `VerificationPolicy.STRICT` — `:73`
- `explain` = `false` — `:77`
- `discoveryLoader` = `null`, meaning no plugin discovery — `:80`
- `permitted` = `PluginLoader.acceptAll()` — `:83`
- `detail` = `Woven.Detail.SUMMARY` — `:86`
- `driver` = `Weaver.Driver.BUILD` — `:89`
- `extensions` = `ExtensionIndex.EMPTY`, meaning the extension pass is skipped entirely — `:92`
- **Defaults alone produce a usable weaver** — `WeaverTest.java:136-145`

## Identifiers

Spelled as the source spells them.

| Thing | Exact spelling | Anchor |
| --- | --- | --- |
| Group id | `de.splatgames.aether.weaver` | `pom.xml:21` |
| Reactor version | `0.1.0-SNAPSHOT` | `pom.xml:23` |
| Weaver version constant | `"0.1.0"` | `Weaver.java:210` |
| Annotation | `@Weave` | `Weave.java:182` |
| Elements | `value()`, `targets()`, `kind()`, `priority()`, `require()`, `tags()`, `phase()` | `Weave.java:198,216,227,246,257,276,287` |
| Enum | `Weave.Kind` with `INSTANCE`, `STATIC` | `Weave.java:295,314,335` |
| Enum | `Weaver.Driver` with `BUILD`, `LOAD` | `Weaver.java:135,142,150` |
| Enum | `Woven.Detail` with `NONE`, `SUMMARY`, `FULL` | `Woven.java:270,278,287,295` |
| Enum | `Require` with `REQUIRED`, `OPTIONAL` | `Weave.java:209-210` (referenced), `api/Require.java` |
| Handler annotations | `@Inject`, `@Redirect`, `@Wrap` | `Weave.java:115` |
| Member annotations | `@Shadow`, `@Unique`, `@Accessor`, `@Invoker` | `api/package-info.java:24-25` |
| Position annotations | `@At`, `@Point`, `@Slice`, `@Local`, `@Result`, `@Group` | `api/package-info.java:76-80`, `:28` |
| Engine entry | `Weaver.builder()` → `WeaverBuilder` | `Weaver.java:245` |
| Engine entry | `Weaver.weave(String, byte[])`, `Weaver.weave(String, ByteSupplier)` | `Weaver.java:259,284` |
| Nested interface | `Weaver.ByteSupplier` (`@FunctionalInterface`, `byte[] get()`) | `Weaver.java:795-804` |
| Maven goals | `weave`, `weave-tests`, `stubs`, `audit` | `WeaveMojo.java:44`, `WeaveTestsMojo.java:31`, `StubsMojo.java:90`, `AuditMojo.java:66` |
| Default phases | `process-classes`, `process-test-classes`, `generate-sources` | `WeaveMojo.java:45`, `WeaveTestsMojo.java:32`, `StubsMojo.java:91` |
| Agent entry points | `premain`, `agentmain` | `WeaverAgent.java:125,143` |
| Class-loader entry | `WeavingClassLoader.create(URL[], …)` | `WeavingClassLoader.java:204` |
| Testkit entry | `Weaving.of(Class<?>...)` | `Weaving.java:132` |
| Runtime lookup | `WovenInfo.of(Class<?>)` → `Optional<WovenInfo>` | `WovenInfo.java:93` |
| Manifest resource | `META-INF/aether/weaves.json` | `WeaveManifest.java:219` |
| Class-file attribute | `AetherWeave` | `WeaveAttribute.java:78` |
| Config key | `aether.weaver.policy.allowPackage` | `DefaultWeavePolicy.java:150-151` |
| Config key | `aether.weaver.policy.allowSigned` | `WeavingClassLoader.java:248` |
| Artifact ids | `aether-weaver-bom`, `-api`, `-engine`, `-runtime`, `-agent`, `-processor`, `-maven-plugin`, `-testkit` | `pom.xml:63-71` |

Diagnostic codes this page may name, each with the remedy the source states:

| Code | Severity | Fires when | Remedy as the source states it | Anchor |
| --- | --- | --- | --- | --- |
| `AW1001` | ERROR | `@Weave` names no target | Name the class it modifies: `@Weave(TheTarget.class)` | `WeaveClassParser.java:290-292` |
| `AW1002` | ERROR | `value()` and `targets()` both given | Keep the class literals and delete `targets=`, or the other way round | `WeaveClassParser.java:283-286` |
| `AW3001` | ERROR | Target is `java.*`, or `javax.`/`jdk.`/`sun.`/`com.sun.` not reopened | For `java.*` there is none. Otherwise reopen exactly the package with `aether.weaver.policy.allowPackage=<pkg>` | `DefaultWeavePolicy.java:129-133`, `:147-152` |
| `AW3003` | ERROR | Target is under `de.splatgames.aether.weaver.` | None; the source states it holds under any configuration | `DefaultWeavePolicy.java:123-127` |
| `AW2003` | ERROR | Class file major version below 50 | Recompile the target (the message states only the reason: stack map frames) | `DefaultWeavePolicy.java:135-139` |
| `AW2004` | ERROR | Preview class file of another release | Recompile the target against the JVM that will run it, or run the weaver on the JVM the target was compiled with | `Weaver.java:683-686` |
| `AW2101` | ERROR | A structural weave has already-loaded targets under dynamic attach | Weave at build time with the Maven plugin, or start the JVM with `-javaagent` so the targets are woven as they load | `DiagnosticCode.java:1443-1446` |
| `AW2201` | ERROR | Build-time weaving a class already woven by a different plan | A clean build settles the usual cause; otherwise weave the original classes rather than a jar that has been through this before | `Weaver.java:765-770` |
| `AW2202` | WARNING | Load-time weaving over a build-time-woven class, different plan | Configure the agent and the build plugin with different weaves, or drop one of them | `Weaver.java:752-755` |

## Glossary entries to commit with this page

`Writerside/cfg/glossary.xml` is empty. Each of these is a term the page needs and no
published page defines. One sentence each, anchored.

- **weave** — A class carrying `@Weave` that describes a change to one or more other classes; without the annotation the class is ordinary and every other Aether Weaver annotation on it is never read. — `Weave.java:10`, `:12-16`, `:182`
- **target** — A class or interface a weave names with `@Weave(value = …)` or `@Weave(targets = …)`, and the class the weaver modifies. — `Weave.java:185`, `TargetRef.java:10`, `:33-36`
- **handler** — A method of a weave carrying `@Inject`, `@Redirect` or `@Wrap`; it is the method control is handed to at the position the declaration matched. — `Weave.java:115`, `InjectorSpec.java:128`
- **kind** — The `@Weave(kind = …)` element, `INSTANCE` or `STATIC`, deciding whether the weave's members become members of the target. — `Weave.java:227`, `:290-291`, `:295`
- **dissolve** — What happens to an instance weave that has members or a handler of its own: its members and handlers are copied into each target and every reference among them is rebound to the target's own members. — `WeavePlanner.java:85-86`, `Weave.java:299-303`
- **plan** — What is to be woven where, built once by `WeaverBuilder.build()` and then only read. — `Weaver.java:74`, `WeavePlan.java:21-22`
- **driver** — The module that supplies I/O and lifecycle around the engine: the Maven plugin, the agent, the class loader, the processor or the testkit. — `pom.xml:5-8`, `ProjectStructureTest.java:101-102`, `:115`

Optional, only if the page uses them: **merge** (`Weave.java:128-129`), **shadow**
(`Weave.java:123-125`), **plan fingerprint** (`Weaver.java:547`).

## The diagram

One diagram is mandatory for an `explain` page — `check-docs.py:649-654` fires on a page
with no `<img`. There is currently **no SVG in `Writerside/images/` except the three brand
marks** (`aether-weaver-logo.svg`, `aether-weaver-mark.svg`, `splatgames-logo.svg`), so the
implementer draws it. Constraints: `viewBox` no wider than 880 units, no `font-size` below
12 (`check-docs.py:815-845`; `MIN_DIAGRAM_LABEL_PX = 9.5` at `:128`, `ARTICLE_WIDTH_PX = 843` at `:129`); at
viewBox 860 a 12-unit label reaches the reader at 11.8px.

Suggested file name: `what-aether-weaver-does.svg`.

**What it must show** — a vertical flow, three bands. Every label below is anchored.

Band 1 — the two inputs, side by side:

- Box, left: **`Audit` — the weave**, sub-label `@Weave(Target.class)`. Inside it two rows:
  - `@Inject(method = "greet()", at = @At(Point.HEAD))` — `CrossDriverEquivalenceTest.java:224`
  - `void onGreet()` labelled **handler** — `Weave.java:115`, `InjectorSpec.java:128`
- Box, right: **`Target` — the target**, sub-label `String greet()` — `CrossDriverEquivalenceTest.java:196-199`
- Arrow from the weave box to the target box, labelled **names** — `Weave.java:185`

Band 2 — the engine, one box spanning both:

- Box: **`Weaver`**, sub-label `weave(name, bytes)` — `Weaver.java:259`
- Three arrows entering the box from the left, one per driver, each labelled:
  - `weave` goal — `process-classes` — `WeaveMojo.java:44-45`
  - `-javaagent` — `premain` — `WeaverAgent.java:125`
  - `WeavingClassLoader.create` — `WeavingClassLoader.java:204`
- A brace or bracket across the three arrows labelled **same bytes from all three** —
  `CrossDriverEquivalenceTest.java:37`

Band 3 — the two outcomes, side by side, both arrows leaving the `Weaver` box:

- Box, left: **woven class**, sub-label `AetherWeave attribute + @Woven` —
  `WeaveAttribute.java:78`, `Weaver.java:506-507`. Inside it, one row showing the change:
  `greet() { Trace.say("woven"); … }` — `CrossDriverEquivalenceTest.java:226`
- Box, right, drawn lighter: **`null` — used unchanged**, sub-label *the answer for almost
  every class* — `Weaver.java:256`, `WeaverTest.java:48-56`

Nothing else. In particular the six gates, the policy denials and the ordering rules are
*not* in this diagram; they are separate pages' pictures (see **Not this page**).

If a second figure is wanted, the `INSTANCE`/`STATIC` split is better as a two-row
`<table style="header-row">` than as a drawing: columns *kind* / *what happens to the
target's member set* / *what stops working*, anchored to `Weave.java:299-303` and `:317-332`.

## Surprises

- **`AW1087` ("a weave targets another weave") is declared in the engine's policy gate on a path that can never fire.** `DefaultWeavePolicy.decide` denies when `target.declaredWeaveClass()` is true (`DefaultWeavePolicy.java:117-121`), but the single call site that constructs the `WeaveTarget` passes `false` for it: `new WeaveTarget(internalName, model.majorVersion(), false, false)` — `Weaver.java:405-406`. The annotation processor and conflict detection are what actually catch it; `WeaverTest.java:176-188` shows `AW1087` arriving from planning, not from the gate. `Weave.java:60-64` and `WeaveTarget.java:19-24` both say so in prose.
- **The same is true of `AW3002` from the engine's gate.** `signed` is also passed `false` (`Weaver.java:406`); the drivers make the signed decision themselves before offering the class — `WeaveTarget.java:29-33`.
- **`null` is not "nothing happened".** It is also what comes back when a gate refused the class, and the listener has already been told (`Weaver.java:55-57`). Conversely a *non-null* return is not proof of weaving: under `VerificationPolicy.REPORT` the weaver returns the original bytes (`Weaver.java:481-488`), which is why the statistics count after verification rather than from the return value (`Weaver.java:61-63`, `:492-495`).
- **An `INSTANCE` weave does not necessarily dissolve.** It dissolves only if it has members or declares its own handler — `WeavePlanner.java:85-86`. The `Weave.java:18-22` prose ("an instance weave is *dissolved*") states the kind, not the condition.
- **The default diagnostic listener discards everything.** `WeaverBuilder.java:67` sets `DiagnosticListener.NOOP`; a caller using `Weaver.builder()` without `.diagnostics(…)` sees no errors at all.
- **`weave-tests` is a separate goal.** Binding only `weave` leaves test classes unwoven — `WeaveTestsMojo.java:31-34`.
- **`java.*` is refused before the version and signature checks and before any override is consulted**, by a dedicated `ALWAYS_DENIED_PREFIX` distinct from the `JDK_PREFIXES` list that overrides can reopen — `DefaultWeavePolicy.java:42`, `:129-133` versus `:48-49`, `:147-152`.
- **The processor and the engine report `AW1004` for two different situations** — the processor for a `targets()` name not on the compile classpath and only under `require = REQUIRED`, the engine for text that is not a usable binary class name at all, whatever `require()` says — `DiagnosticCode.java:158-171`. `require()` is read by the annotation processor and by nothing else in the weaving path — `Weave.java:250-252`.
- **`OrderKey` contradicts itself about totality.** Its class comment says two entries of one weave with the same handler name and descriptor compare equal, so the order "is an order with ties" (`OrderKey.java:12-18`), while its constructor's exception message says "the tie-breakers are what make the order total" (`OrderKey.java:51-52`). Determinism comes from the *stable* sort, not from totality (`WeavePlanner.java:105`). Do not write "total order" on the strength of the constructor message.

## Could not establish

- **Whether a weave class is ever loaded as a class at run time.** `Weave.java:22` and `DefaultWeavePolicy.java:118-121` both assert "the weave class itself is never loaded", and no engine, runtime or agent code loads one (grep for `Class.forName`/`loadClass` finds only `WeaveEvents.java:90-91` and `ResolutionContext.java:184`). But nothing removes the compiled weave class from the artefact, and nothing in the code prevents application code from loading it. The defensible sentence is "the weaver reads a weave as a class file rather than loading it"; the stronger one needs a test that is not in this repository.
- **Which artefacts a consumer actually declares for each way of running.** Establishing that means reading each driver module's `pom.xml` dependency block and the plugin's own coordinates — that is the install page's work, and no install page exists.
- **Any performance claim.** `aether-weaver-tests` has a `perf` source tree using JMH (`pom.xml:109-114`), but no figure can be quoted without running it. Do not estimate.
- **What `%version%` should mean on the page.** `v.list:5` declares `version = 0.1.0` while the reactor is `0.1.0-SNAPSHOT` (`pom.xml:23`). The page must write `%version%` (gate at `check-docs.py:390`); which of the two is correct is not settled by the source.
- **Whether a code sample can be pulled from a file.** `writerside.cfg:10` registers `<snippets src="snippets"/>` but `Writerside/snippets/` is empty, and `check-docs.py:382-386` fails a `src="X.java"` naming a file that is not there. Either the implementer adds the sample file under `Writerside/snippets/` in the same commit, or the page uses a literal ```` ```java ```` fence (`java` is on the allowlist, `check-docs.py:79-83` (`FENCE_LANGUAGES`)).

## Not this page

- **The six gates of the weaving pipeline** — gate 1 fast path `Weaver.java:290`, gate 2 policy `:404`, gate 3 idempotence `:417`, gate 4 apply `:433`, gate 5 verify `:479`, gate 6 stamp `:490`. This is `concepts/pipeline.md`, and STYLE.md's first exemplar is already written against it.
- **The full ordering rule and its ties** — `OrderKey.java:12-18`, `:33-37`, `WeavePlanner.java:103-105`. A concepts page on ordering, plus a reference row for `priority()`.
- **Every `@Weave` element and its diagnostics** — `Weave.java:184-287`. `reference/annotations.md`; STYLE.md's third exemplar is written against exactly this.
- **The complete `AW####` list, severities and categories** — `DiagnosticCode.java`. A reference page.
- **`require`/`allow`/`@Group` accounting, and why an omitted `require` is not `require = 0`** — `InjectorSpec.java:60-78`, `api/package-info.java:126-133`. A concepts or reference page.
- **The handler parameter order (`@Result`, target arguments, callback, `@Local`) and `AW1040`** — `api/package-info.java:101-109`.
- **Selector grammar and `AW1020`/`AW1021`/`AW1023`/`AW1024`/`AW1025`** — `InjectorSpec.java:35-44`.
- **Plugins, `WeaverPlugin`, `permitPlugins`, discovery and the fingerprint** — `WeaverBuilder.java:217-264`, `:342-346`. Guides or an SPI reference.
- **Extensions and the extension pass** — `Weaver.java:302-335`, `WeaverBuilder.java:316-331`; `@ApiStatus.Experimental`, so the page that covers it carries `<secondary-label ref="experimental"/>` (`labels.list:9`).
- **`explain(true)`, `ExplainReport`, the `Counting` listener and the report footer** — `WeaverBuilder.java:186-201`, `:358-386`, `Weaver.java:568-608`.
- **JFR events and `Weaver.recording()`** — `Weaver.java:624-633`, `observe/WeaveEvents.java`.
- **`stubs` and `audit` goals in detail** — `StubsMojo.java:90-93`, `AuditMojo.java:66`.
- **Agent configuration, `ErrorPolicy`, dump directories, module access** — `WeaverAgent.java:226-227`, `agent/ModuleAccess.java`, `runtime/config/`.
- **Testkit usage** — `Weaving.java:103-158`. A guide; STYLE.md's second exemplar is written against it.
- **The build gates, Checkstyle limits and the architecture tests** — `pom.xml:292-336`, `ProjectStructureTest.java`. `contributing/`.
