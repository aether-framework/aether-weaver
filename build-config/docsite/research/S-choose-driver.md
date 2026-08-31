# S-choose-driver — Choose a driver

Section `start/`, kind `explain`, file `start/choose-a-driver.md`. Four drivers: the Maven
plugin (build time), the agent (load time), `WeavingClassLoader` (runtime), the testkit (in a
test). Paths below are relative to the repository root.

## Facts

### What a "driver" is to the engine

- **The engine knows exactly two drivers, `BUILD` and `LOAD`, and the choice changes exactly one behaviour: what happens to a class that already carries a *different* plan's weave record.** — `aether-weaver-engine/src/main/java/de/splatgames/aether/weaver/engine/Weaver.java:135` (enum `Driver`), `:122-129` (field JavaDoc: "The only behaviour this changes is the treatment of a class that already carries a weave record from a different plan")
- **`Driver.BUILD` refuses such a class with `AW2201` and returns `false` (the class is left alone); `Driver.LOAD` warns with `AW2202` and weaves it anyway, so both plans apply and a weave they have in common runs twice.** — `Weaver.java:737-772`, branch at `:744`
- **A class already carrying *this* plan's fingerprint is skipped before the driver is consulted at all, under either driver.** — `Weaver.java:426` (`Provenance.wovenBy(bytes, this.plan.fingerprint())` returns `null`)
- **`WeaverBuilder`'s default driver is `BUILD`.** — `aether-weaver-engine/src/main/java/de/splatgames/aether/weaver/engine/WeaverBuilder.java:89`
- **Only the agent and `WeavingClassLoader` call `.driver(Weaver.Driver.LOAD)`. The Maven plugin and the testkit never call `.driver(...)`, so both run as `BUILD`.** — `aether-weaver-agent/.../WeaverAgent.java:210`, `aether-weaver-runtime/.../WeavingClassLoader.java:221`; no `.driver(` in `aether-weaver-maven-plugin/.../AbstractWeaveMojo.java:281-290` or `aether-weaver-testkit/.../Weaving.java:152-157`
- **The three non-test drivers produce byte-identical output for the same fixture: build time, `-javaagent` and `WeavingClassLoader` are compared by digest and then by array.** — `aether-weaver-tests/src/test/java/de/splatgames/aether/weaver/e2e/CrossDriverEquivalenceTest.java:37-63`
- **The no-match path allocates nothing measurable on both `weave` overloads; the `byte[]` overload is called the agent's hot path.** — `aether-weaver-tests/src/test/java/de/splatgames/aether/weaver/perf/NoMatchAllocationTest.java:30-57` (`TOLERANCE = 0.1` bytes/call at `:23`)
- **The fast path is two map lookups and no bytes fetched; the supplier overload does not call the supplier when the plan names nothing and no extension is in force.** — `Weaver.java:284-300` (comment at `:290`), `:318-322`

### Build time — `aether-weaver-maven-plugin`

- **Goal `weave`, default phase `process-classes`, dependency resolution `COMPILE_PLUS_RUNTIME`, `threadSafe = true`.** — `aether-weaver-maven-plugin/src/main/java/de/splatgames/aether/weaver/maven/WeaveMojo.java:44-47`
- **Goal `weave-tests`, default phase `process-test-classes`, resolution `TEST`, `threadSafe = true`; it rewrites `${project.build.testOutputDirectory}` and never weaves dependencies.** — `WeaveTestsMojo.java:31-34`, `:51-52`, class JavaDoc `:25-26`
- **The directory rewritten by `weave` is `${project.build.outputDirectory}` and is `readonly`, so it cannot be configured.** — `WeaveMojo.java:64-65`
- **The weaves that are applied come only from `META-INF/aether/weaves.json` inside the directory being rewritten. Weaves found on the classpath are read only to decide `AW3010` and are otherwise unused.** — `AbstractWeaveMojo.java:48-53` (JavaDoc), code `:260-268`; `ClassDirectory.java:29-32`
- **Extension declarations, by contrast, are taken from the whole classpath and indexed before anything is rewritten.** — `AbstractWeaveMojo.java:224-234`, `:260`
- **Every `.class` file under the directory is woven in path order, and only files the weaver returned new bytes for are written back; an unchanged file keeps its content and its modification time.** — `AbstractWeaveMojo.java:344-383`, `:362-367`; `ClassDirectory.java:147-152` (sorted by path string)
- **A class the weaver refuses with `WeaveException` is reported as `AW4090` and left on disk; the loop continues.** — `AbstractWeaveMojo.java:356-361`
- **An `IOException` reading or writing a class file becomes an `UncheckedIOException` and escapes the goal rather than a `MojoExecutionException`.** — `AbstractWeaveMojo.java:378-380`, `:432-438`
- **The goal is idempotent: a second run recognises the class as woven by this plan and leaves it alone.** — test `WeaveMojoTest.java:90-107`; mechanism `Weaver.java:426`
- **The build fails when any collected diagnostic has `ERROR` severity and `failOnError` is set; `failOnError` defaults to `true` both in the annotation and in the field initialiser, so an instance Maven never injected still fails.** — `AbstractWeaveMojo.java:97-98`, `:413-419`
- **When the plan named more targets than were woven, the goal logs a warning naming the shortfall. This is a warning here and deliberately not one at load time.** — `AbstractWeaveMojo.java:296-305`
- **`explain` prints the report *after* weaving, so it names what each point matched; the build-time report never contains `not woven yet`.** — `AbstractWeaveMojo.java:307-311`; test `WeaveMojoTest.java:145-169`
- **`Weaver.finish()` is called only by this driver, so `WeavingFinished` reaches plugins only at build time.** — `AbstractWeaveMojo.java:293`; nothing else in `*/src/main/java` calls it
- **Dependency weaving is off unless `weaveDependencies` is set; with it off, a weave whose target lives in a dependency never reaches that target.** — `WeaveMojo.java:74-75` and its JavaDoc `:70-73`; `:122-124`
- **With it on, every resolved artefact that is an existing file ending in `.jar` is woven, sorted by path string first, and only classes the plan changed are written under `dependencyOutputDirectory` (default `${project.build.directory}/aether-weaver/dependencies`). The artefacts themselves are not modified.** — `WeaveMojo.java:125-137`, `:96-97`; `DependencyWeaver.java:117-141`
- **Nothing places that output directory on any classpath. `AW2501` lists every class written and says the directory has to be put ahead of the dependency jars by hand.** — `DependencyWeaver.java:99-111`
- **A signed dependency jar is refused as `AW3002` (nothing of it is written) unless `allowSigned` is set, which weaves it and reports `AW3020` once per signed artefact instead.** — `DependencyWeaver.java:146-181`, `:126`
- **`DependencyWeaver` never clears its modified list, so a second `weave(...)` call on one instance reports the first call's classes again. The goal builds one instance per run.** — `DependencyWeaver.java:83-86`, `WeaveMojo.java:137`
- **`AW3010` is a membership test against the set of entries the project declared itself, not an analysis of how the entry arrived: an entry the caller's `direct` set fails to name is reported the same way. The declaration is still read and still merged.** — `Manifests.java:74-83`, `:109-124`
- **An entry carrying extensions alone is never reported as `AW3010`; only the weave count is consulted.** — `Manifests.java:84-86`, `:111`; test `TransitiveWeaveTest.java:71-78`
- **A mojo run with no `MavenProject` injected skips the `AW3010` check entirely.** — `Manifests.java:55-69` (two-argument overload passes `direct = null`); test `TransitiveWeaveTest.java:58-69`
- **A module with no manifest, and a module whose classes directory does not exist, are both silent non-failures (debug level).** — `AbstractWeaveMojo.java:253-273`, comment `:263-265`

### Load time — `aether-weaver-agent`

- **One class answers both entry points: the jar manifest declares `Premain-Class` and `Agent-Class` as `de.splatgames.aether.weaver.agent.WeaverAgent`, plus `Can-Retransform-Classes` and `Can-Redefine-Classes` set to `true`.** — `aether-weaver-agent/pom.xml:49-52`
- **`premain(String, Instrumentation)` and `agentmain(String, Instrumentation)` both delegate to one private `install`, differing only in the `mode` string and a `dynamic` flag.** — `WeaverAgent.java:125-146`
- **Configuration is two layers, system properties first and the agent argument string second; the later layer wins.** — `WeaverAgent.java:170-176`
- **`enabled=false` prints the collected diagnostics and a summary line and installs nothing.** — `WeaverAgent.java:178-183`
- **Weaves are discovered from `WeaverAgent`'s own class loader, or from the system class loader when that is `null` (bootstrap).** — `WeaverAgent.java:185-187`
- **Discovery walks `getResources("META-INF/aether/weaves.json")`, which delegates to parents, in classpath order.** — `aether-weaver-runtime/.../ManifestWeaveSource.java:150`; constant `aether-weaver-api/.../manifest/WeaveManifest.java:219`
- **When discovery yields no weave, a summary line is printed and no transformer is installed.** — `WeaverAgent.java:199-205`
- **A plan that cannot be built is printed to `System.err` and the exception is rethrown, before the transformer exists.** — `WeaverAgent.java:217-224`
- **The transformer is installed with `canRetransform = true`.** — `WeaverAgent.java:226-227`
- **Under `premain`, classes the JVM had already defined are not retransformed.** — `WeaverAgent.java:118-119` (JavaDoc), `:188` and `:229` guard both on `dynamic`
- **Under `agentmain` two extra things happen: `RetransformApplicability.report` runs before the plan is used, and the applicable already-loaded targets are retransformed explicitly.** — `WeaverAgent.java:188-198`, `:229-235`
- **A loaded class is a retransformation candidate only when the plan holds an injection entry for it, holds no structural (dissolving) weave against it, and `Instrumentation.isModifiableClass` accepts it. A class the plan reaches only through an extension is not a candidate, because the plan holds no entry for it.** — `WeaverAgent.java:277-286`, JavaDoc `:256-266`
- **All candidates go into one `retransformClasses` call, so a refusal loses all of them together; the refusal is reported as `AW2101` and not thrown, and the transformer stays installed.** — `WeaverAgent.java:290-304`
- **`AW2101` from the applicability report and `AW2101` from a refused retransformation are two different tests: a class skipped by the candidate filter was not necessarily reported.** — `WeaverAgent.java:262-264`
- **`RetransformApplicability` is reached only from `agentmain`; nothing removes a weave from the plan, because narrowing it would change the fingerprint and make the same weave set stamp classes differently depending on how the agent started.** — `RetransformApplicability.java:19-20`, `:26-28`; `WeaverAgent.java:193-196`
- **A weave is "structural" for this purpose when it merges a member, generates an accessor, generates an invoker, declares `@Shadow(mutable = true)` on a *field*, or is an `INSTANCE` weave with at least one injector. The first member with an answer decides, so a weave with several is reported by one.** — `RetransformApplicability.java:130-158`
- **`@Shadow(mutable = true)` counts without checking whether the target's field is really final; an ordinary shadow, and a mutable shadow of a *method*, count for nothing.** — `RetransformApplicability.java:140-146`
- **A structural weave whose targets are all still unloaded is passed over in silence and is applied in full.** — `RetransformApplicability.java:70-76`; test `RetransformApplicabilityTest.java:134`
- **Every class the JVM defines or retransforms after installation passes through `WeavingTransformer`. A class the JVM offers with a `null` name is declined outright, because the plan is keyed by name.** — `WeavingTransformer.java:132-136`
- **A class the plan says nothing about is answered with `null` rather than with `buffer`, which is what stops the JVM re-verifying every class in the application.** — `WeavingTransformer.java:139-143`
- **The module graph is expanded and the dump is written only when `weave` returned non-`null`.** — `WeavingTransformer.java:144-150`
- **A `RuntimeException` and an `Error` thrown out of `transform` are both discarded by the JVM without a message and the class is defined from the original bytes, so the `catch` in `transform` is the last point at which the error policy can act. Measured on OpenJDK 25 (Temurin 25.0.3+9, Linux).** — `WeavingTransformer.java:29-33`, `:152-156`
- **`AW4090` is reported first, then the policy applies: `ErrorPolicy.REPORT` returns `null` and the class loads unwoven; `ErrorPolicy.FAIL` prints three lines and the stack trace to `System.err` and calls `Runtime.getRuntime().halt(70)`.** — `WeavingTransformer.java:183-203`, constant `HALT_STATUS = 70` at `:46`
- **`halt`, not `exit`: no shutdown hook runs and nothing buffered elsewhere is flushed, because this runs on a class-loading thread.** — `WeavingTransformer.java:172-174`, `:200-202`
- **The default error policy is `FAIL`.** — `aether-weaver-runtime/.../config/ConfigLayer.java:172`; `WeaverConfig.java:78-79`
- **`AW2402` fires only when the weave class lives in a *named* module; a weave class on the classpath needs no edge because the JVM grants it itself. Both the success and the refusal are reported.** — `ModuleAccess.java:60-72`, `:108-136`
- **When the JVM refuses the read edge, the class is still woven and throws `IllegalAccessError` the first time the injected instruction runs.** — `ModuleAccess.java:110-124`
- **The module a target is made to read is whatever module `WeaverAgent` itself was loaded into, not the weave class's module; they coincide only when both are on the class path.** — `WeavingTransformer.java:63-70`, `WeaverAgent.java:227`
- **Diagnostics are drained at four points only — the two returns that install nothing, the rethrow, and the closing line. A diagnostic raised after the closing line is appended to a list nothing drains again, and prints nothing at all.** — `WeaverAgent.java:346-352` (`print` clears at `:352`), call sites `:179`, `:201`, `:220`, `:237`; JavaDoc `:83-90`
- **`explain=true` prints the plan on the startup pass. Under `premain` every declaration renders `not woven yet`; under `agentmain` a declaration whose target was among the retransformed ones renders what it matched.** — `WeaverAgent.java:238-246`, JavaDoc `:52-59`; test `WeaverAgentEndToEndTest.java:120-135`
- **The closing line names the version, the weave count, the target count, the plan fingerprint, the configuration summary and which entry point ran.** — `WeaverAgent.java:247-251`; test `:60-72`
- **Nothing calls `Weaver.finish()` under the agent, so plugins never see `WeavingFinished` there.** — `WeaverAgent.java:92-93`
- **Nothing is undone on the way out: once the transformer is added it stays added.** — `WeaverAgent.java:151-154`

### Runtime — `aether-weaver-runtime`, `WeavingClassLoader`

- **A `URLClassLoader` subclass that overrides `findClass` and nothing else, so delegation is ordinary parent-first: only classes this loader defines are woven, and a target the parent can also see is defined by the parent, unwoven, with no diagnostic at all.** — `WeavingClassLoader.java:86`, `:255`, JavaDoc `:39-42`; test `WeavingClassLoaderTest.java:129-153` ("there is no diagnostic for it, because a target loaded by a parent is one this loader is never asked about")
- **Registered parallel capable in a static initialiser, so the definition lock is per class name. Parallel capability is not inherited from `URLClassLoader`; measured on Temurin 25.0.3.** — `WeavingClassLoader.java:88-92`, JavaDoc `:44-47`
- **Classes it defines land in the unnamed module, which reads every module unconditionally, so no read edge is ever needed here.** — JavaDoc `:49-50`; test `WeavingClassLoaderTest.java:251-273`
- **Two ways in. The public constructor takes a weaver the caller already built and discovers nothing; `create(URL[], ClassLoader, WeaverConfig, DiagnosticListener)` discovers the roots' manifests and builds the weaver itself.** — `:136-142`, `:204-232`
- **Discovery reads through a *second* `URLClassLoader` over the same roots that never defines anything, because reading manifests through the loader being built would define its classes before its weaver existed. That loader stays open for the weaver's lifetime and is closed by `close()`.** — `:212-218`, `:616-645`
- **The weaver's class source is the discovered weave classes' bytes, falling back to `ClassSource.ofClassLoader(search)`, which is what the hierarchy resolver reads targets through.** — `:225`
- **`findClass` uses `findResource`, not `getResource`, so no delegation happens when reading bytes; delegating would read from an artefact the parent already defined a class from and the woven copy would duplicate a live type.** — `:257-263`
- **The defined class keeps the `CodeSource` its bytes came from, certificates included, and its package is defined from the artefact's manifest so a sealed package stays sealed.** — `:278-289`, `:535-558`
- **A class from a signed artefact is defined from the original bytes and reported as `AW3002`, unless `aether.weaver.policy.allowSigned` is set — in which case it is woven and *nothing* is reported.** — `:320-331`
- **Only `RuntimeException` and `LinkageError` are caught while weaving. Any other `Error` propagates out of the load and neither error policy applies to it.** — `:336`, JavaDoc `:301-302`
- **`ErrorPolicy.FAIL` throws `ClassNotFoundException` naming `aether.weaver.onError=fail` rather than halting; unlike the transformer, this driver's exceptions are not discarded by the JVM.** — `:341-347`; test `WeavingClassLoaderTest.java:205-224`
- **`ErrorPolicy.REPORT` reports `AW4090` and defines the original bytes.** — `:337-348`; test `WeavingClassLoaderTest.java:225-247`
- **`AW2401` is reported once per construction — not per class — when the JVM was started with `-XX:AOTCache=`, `-XX:AOTCacheOutput=` or `-XX:AOTConfiguration=` and not `-XX:AOTMode=off`.** — `:171`, `:581-599`; flag list `AotCache.java:27`, `:35-36`, veto at `:88-91`
- **Classic CDS (`-XX:SharedArchiveFile`) is deliberately not among the flags that trigger `AW2401`.** — `AotCache.java:30-34`
- **`AotCache.active()` swallows `LinkageError` and `RuntimeException` and returns `null`, so a jlinked image without `java.management` loses the warning rather than the loader.** — `AotCache.java:58-66`
- **A local jar is read through a `JarFile` handle this loader owns, keyed by artefact URL, opened verifying and `Runtime.version()`-aware, and released by `close()`. A remote jar, or one no `Path` can express, falls back to the `URLConnection`, whose handle survives `close()` regardless.** — `:275-289`, `:391-415`, `:430-447`, JavaDoc `:74-81`
- **Measured cost of the alternative: opening the jar per class costs 198.7 µs against 0.404 µs for the cached handle on a 173-entry jar, a factor of 492.** — `:269-274`
- **`close()` closes this loader, the discovery loader and every jar handle, even if an earlier one refuses; a refusing jar handle carries the later ones as suppressed exceptions and replaces a refusal from either of the first two.** — `:616-645`
- **`WeavingClassLoader` never reads `config.enabled()`, so `aether.weaver.enabled=false` does not disarm this driver.** — no `enabled()` call in `aether-weaver-runtime/src/main/java` outside `config/`; stated at `aether-weaver-runtime/.../config/package-info.java:203-205`

### In a test — `aether-weaver-testkit`

- **`Weaving.of(Class<?>...)` reads each weave class's own class file out of its class loader, parses it under `Origin.of("testkit", <binary name>)`, and registers those same bytes as the weaver's class source. Only the classes named there are in that map.** — `Weaving.java:132-158`, `:273-284`
- **`of` fixes three things: the class source (the weave classes' bytes and nothing else), `VerificationPolicy.STRICT`, and one collected diagnostic list.** — `Weaving.java:152-157`, JavaDoc `:40-44`
- **An empty argument array is `IllegalArgumentException("give at least one weave class")`.** — `Weaving.java:134-136`
- **A class that carries no `@Weave`, names no usable target, or draws a parser error is refused with `IllegalArgumentException` rather than skipped, and the message appends every diagnostic collected so far in that call — including those of classes parsed before it.** — `Weaving.java:146-149`, `:334-338`
- **Nothing is loaded or redefined by `weave`. `WeaveResult` carries bytes; loading happens only in `WovenAssert.isAcceptedByTheJvm()` / `loadsAndRuns(...)`, into a throwaway loader created fresh per call, and definition does not run a static initialiser.** — `Weaving.java:33-36`; `WovenAssert.java:196-221`, `:223-241`
- **Every class is woven twice, over the same *original* bytes both times, so `isDeterministic()` has a second pass. The second pass's diagnostics are truncated away without being compared, so a diagnostic only the second pass raises is lost silently.** — `Weaving.java:200-214`, JavaDoc `:46-53`
- **`Weaver.statistics().classesSeen()` therefore advances twice per `weave(Class)` call.** — `Weaving.java:229-233`
- **`WeaveResult.diagnostics()` carries only what its own call reported; a diagnostic raised while `of(...)` planned is visible only through `Weaving.diagnostics()`. The first call is the exception: when `before == 0` the result carries everything reported so far.** — `Weaving.java:218-221`, `:242-257`
- **A target no weave names is not an error: `woven()` is `null`, `wasWoven()` is `false`, and `effective()` hands back the original bytes.** — `Weaving.java:164-168`
- **A class with no class file its loader can supply — an array, a primitive, a hidden class, anything generated at run time — is `IllegalStateException`. An array's internal name comes back as its descriptor (`[I`), which is why it fails in `bytesOf` and not in `internalNameOf`.** — `Weaving.java:273-284`, `:303-318`
- **A bootstrap class is read through the system class loader, since `Class.getClassLoader()` is `null` for it.** — `Weaving.java:298-301`
- **`Weaving` is not safe for concurrent use: `weave` appends to and truncates a plain `ArrayList` that `diagnostics()` also reads.** — JavaDoc `Weaving.java:55-58`
- **Nothing in the testkit reads a weave manifest, discovers weaves from the classpath, or consults a driver's configuration; the only system property it reads is `GoldenFiles.UPDATE_PROPERTY`.** — `testkit/package-info.java:14-17`
- **Plugins are not discovered: no discovery loader is set, so a `WeaverPlugin` published as a service on the test classpath is not loaded.** — `testkit/package-info.java:89-91`
- **`WeaverExtension` implements `ParameterResolver` and nothing else, claiming a parameter whose declared type is exactly `Weaving`. It never intercepts a test and resets no state between tests.** — `WeaverExtension.java:57`, `:86-89`, JavaDoc `:13-17`
- **Sharing follows the `ExtensionContext` that resolved first: a test method parameter gets a weaver per method; a test class that also takes `Weaving` in its constructor gets one built at class level and every test method is handed that same instance, with the statistics of the whole class accumulated in it.** — `WeaverExtension.java:27-34`, `:110-113`
- **`@Weaves` is `@Inherited`, `RUNTIME`-retained, applies to `TYPE` and `METHOD`, and has one element `Class<?>[] value()`.** — `Weaves.java:59-62`, `:74`

### Policy and diagnostics that cut across the drivers

- **The engine builds its `WeaveTarget` from the parsed class file alone and passes `false` for both `signed` and `declaredWeaveClass`, so `AW3002` and `AW1087` are never raised from `Weaver.weave`; a driver that knows those answers decides them before offering the class.** — `Weaver.java:405-406`, JavaDoc `:380-382`; `DefaultWeavePolicy.java:99-104`
- **Consequently, signedness is a per-driver decision: the Maven plugin checks it per dependency *jar*, `WeavingClassLoader` checks it per class from the `CodeSource`, and the agent does not check it at all — its transformer receives the `ProtectionDomain` and does not consult it.** — `DependencyWeaver.java:126`, `WeavingClassLoader.java:320`, `WeavingTransformer.java:119-120` ("`domain` the protection domain of the class; not consulted"), `:126-131`
- **The default policy denies `java.*` under every configuration, denies `de.splatgames.aether.weaver.*` under every configuration, denies `javax.`, `jdk.`, `sun.`, `com.sun.` unless the exact package is reopened, and refuses class file major version below 50 as `AW2003`. This is the same policy under every driver, because no driver replaces it.** — `DefaultWeavePolicy.java:39`, `:42-49`, `:110-155`, `:74-76`; no `.policy(` call in any driver's `WeaverBuilder` use
- **`AW4090` is reported by every driver against the class it was working on, and what happens next is the driver's decision.** — `DiagnosticCode.java:1889-1903`; report sites `AbstractWeaveMojo.java:357`, `DependencyWeaver.java:191`, `WeavingTransformer.java:183`, `WeavingClassLoader.java:337`
- **`AW2101` is the only code in `Category.DRIVER` (range 2100-2199) and is reported only by the agent, from two places.** — `DiagnosticCode.java:1447`, `:49-50`, `:2113-2118`; report sites `RetransformApplicability.java:82` and `WeaverAgent.java:297`
- **`AW2501` is reported only by `DependencyWeaver`, and only when at least one dependency class was actually written.** — `DependencyWeaver.java:99-111`
- **`AW3020` is reported only by the Maven plugin. The runtime class loader and the engine's default policy accept the `allowSigned` override silently.** — `DiagnosticCode.java:1628-1633`; only report site `DependencyWeaver.java:166`
- **`AW3010` is reported only by the Maven plugin.** — only report site `Manifests.java:112`
- **`AW2401` is reported only by `WeavingClassLoader`, and `AW2402` only by the agent.** — `WeavingClassLoader.java:587`; `ModuleAccess.java:114`, `:129`
- **`AW2300`, `AW2302` and `AW2303` come from the shared discovery path, so they reach the agent and `WeavingClassLoader` but not the Maven plugin's own weave reading; the plugin raises `AW2300` from its own manifest reader instead.** — `WeaveDiscovery.java:96-106`, `ManifestWeaveSource.java:118-125`; plugin side `ClassDirectory.java:83-99`, `Manifests.java:105-107`

## Identifiers

- Engine: `de.splatgames.aether.weaver.engine.Weaver.Driver` with constants `BUILD`, `LOAD`; `Weaver.builder()`, `WeaverBuilder.driver(Weaver.Driver)`, `WeaverBuilder.extensions(ExtensionIndex)`, `WeaverBuilder.classSource(ClassSource)`, `WeaverBuilder.verification(VerificationPolicy)`, `WeaverBuilder.explain(boolean)`, `WeaverBuilder.diagnostics(DiagnosticListener)`, `Weaver.weave(String, byte[])`, `Weaver.weave(String, Weaver.ByteSupplier)`, `Weaver.finish()`, `Weaver.fingerprint()`, `Weaver.plan()`, `Weaver.statistics()`, `Weaver.explain()`.
- Coordinates: group `de.splatgames.aether.weaver`, version `0.1.0-SNAPSHOT` (root `pom.xml:21-23`). Artefacts `aether-weaver-maven-plugin` (packaging `maven-plugin`, `<prerequisites><maven>3.9`), `aether-weaver-agent`, `aether-weaver-runtime`, `aether-weaver-testkit`.
- Maven goals: `weave`, `weave-tests`, `stubs` (phase `generate-sources`, resolution `COMPILE`), `audit` (`requiresProject = false`). All four `threadSafe = true`.
- Maven user properties: `aether.weaver.skip`, `aether.weaver.failOnError` (default `true`), `aether.weaver.explain` (default `false`), `aether.weaver.dump` (no default), `aether.weaver.weaveDependencies` (default `false`), `aether.weaver.allowSigned` (default `false`), `aether.weaver.artifact` (audit only). `dependencyOutputDirectory` names no property; default `${project.build.directory}/aether-weaver/dependencies`.
- Agent manifest attributes: `Premain-Class`, `Agent-Class` — both `de.splatgames.aether.weaver.agent.WeaverAgent`; `Can-Retransform-Classes`, `Can-Redefine-Classes` — both `true`.
- Agent entry points: `WeaverAgent.premain(String, Instrumentation)`, `WeaverAgent.agentmain(String, Instrumentation)`. Mode strings printed on the closing line: `premain`, `agentmain`. Version string printed: `0.1.0` (`WeaverAgent.java:101`). Halt status `70`.
- Configuration keys (prefix `aether.weaver.`, `ConfigParser.PREFIX` at `ConfigParser.java:95`): scalar keys `enabled`, `verification`, `onError`, `dump`, `explain`, `phase` (`ConfigParser.java:106-107`); also `tags.include`, `tags.exclude`, `policy.allowSigned`, `policy.allowPackage`, and `weave.<name>.enabled` / `weave.<name>.priority` / `injector.<name>.enabled` (`ConfigParser.java:301-357`). Defaults: enabled, `VerificationPolicy.STRICT`, `ErrorPolicy.FAIL`, `Phase.DEFAULT` (`ConfigLayer.java:171-172`, `WeaverConfig.java:78-80`).
- Manifest resource: `META-INF/aether/weaves.json` (`WeaveManifest.RESOURCE`).
- Runtime: `WeavingClassLoader.create(URL[], ClassLoader, WeaverConfig, DiagnosticListener)`, `new WeavingClassLoader(URL[], ClassLoader, Weaver, WeaverConfig, DiagnosticListener)`, `WeavingClassLoader.close()`, `WeaveDiscovery.discover(ClassLoader, WeaverConfig, DiagnosticListener)`, `WeaveDiscovery.Discovered`. `toString()` is `WeavingClassLoader[roots=2, fingerprint=...]`.
- Testkit: `Weaving.of(Class<?>...)`, `Weaving.weave(Class<?>)`, `Weaving.weaver()`, `Weaving.diagnostics()`, `WeaveResult`, `WovenAssert.assertThatWoven(WeaveResult)`, `WovenAssert.isAcceptedByTheJvm()`, `WovenAssert.loadsAndRuns(ThrowingConsumer)`, `WovenAssert.isDeterministic()`, `GoldenFiles`, `WeaverExtension`, `@Weaves(Class<?>[] value)`.
- AOT flags that trigger `AW2401`: `-XX:AOTCache=`, `-XX:AOTCacheOutput=`, `-XX:AOTConfiguration=`; vetoed by the exact argument `-XX:AOTMode=off`.
- Diagnostic codes in scope: `AW2003`, `AW2101`, `AW2201`, `AW2202`, `AW2300`, `AW2301`, `AW2302`, `AW2303`, `AW2310`, `AW2401`, `AW2402`, `AW2501`, `AW3001`, `AW3002`, `AW3003`, `AW3010`, `AW3020`, `AW4090`.

## Surprises

- **`aether.weaver.enabled=false` does not switch off `WeavingClassLoader`.** Only the agent reads it. — `WeaverAgent.java:178`; nothing in `runtime` outside `config/` calls `enabled()`; stated at `runtime/config/package-info.java:203-205`.
- **`aether.weaver.policy.allowPackage` reopens nothing under any driver.** The key parses, merges and resolves, and a non-empty set only makes the summary say `POLICY RELAXED`; no driver hands the resolved `PolicyConfig` to `WeaverBuilder.policy(...)`, so the engine keeps `DefaultWeavePolicy.standard()` and still denies `javax.`, `jdk.`, `sun.`, `com.sun.`. — `runtime/config/package-info.java:212-214`; no `.policy(` call outside `WeavePolicy` JavaDoc; `WeaverBuilder.java:70`.
- **`aether.weaver.policy.allowSigned` is honoured by `WeavingClassLoader` alone among the runtime drivers, and it is honoured *silently*** — no `AW3020`, no `AW3002`. Only the Maven plugin reports the override. — `WeavingClassLoader.java:320-331`; `DiagnosticCode.java:1628-1633`.
- **The agent never applies the signed-artefact rule at all.** The `ProtectionDomain` reaches `transform` and is not consulted, and the engine passes `signed = false` to the policy. — `WeavingTransformer.java:119-120`, `Weaver.java:405-406`.
- **Extension methods are rewritten by the Maven plugin only.** `WeaverBuilder.extensions` defaults to `ExtensionIndex.EMPTY` and `AbstractWeaveMojo.java:283` is the only call site outside the engine, so extension call sites are not rewritten under the agent, under `WeavingClassLoader`, or in the testkit. — `WeaverBuilder.java:92`.
- **No shipped driver discovers plugins.** None calls `WeaverBuilder.discoveryLoader(...)`, and with it `null` the builder installs only `CorePlugin` plus hand-registered plugins. A `WeaverPlugin` published as a `ServiceLoader` service is never loaded by the Maven plugin, the agent, `WeavingClassLoader` or the testkit. — `WeaverBuilder.java:80`, `:367-372`.
- **Only the Maven plugin ends a run.** `Weaver.finish()` is called nowhere else, so `WeavingFinished` never fires under the agent, the class loader or the testkit. — `AbstractWeaveMojo.java:293`, `WeaverAgent.java:92-93`.
- **A weave declared by a dependency is not applied at build time.** The Maven plugin takes the weaves it applies from the manifest inside the directory it rewrites and reads the classpath only for extensions and for `AW3010`. A reader who ships a weave in a library and adds the plugin downstream gets nothing. — `AbstractWeaveMojo.java:48-53`, `:260-268`, `ClassDirectory.java:29-32`.
- **`weaveDependencies` writes into a directory nothing puts on a classpath.** `AW2501` is a warning precisely because the work otherwise silently has no effect. — `DependencyWeaver.java:99-111`, `DiagnosticCode.java:1595-1605`.
- **Under `-javaagent`, an unrecoverable weave failure kills the process by default.** `ErrorPolicy` defaults to `FAIL`, and `FAIL` in the transformer is `Runtime.halt(70)` with no shutdown hooks. The same failure under `WeavingClassLoader` is a `ClassNotFoundException`, and under the Maven plugin it is a skipped class plus a failed build. — `ConfigLayer.java:172`, `WeavingTransformer.java:189-203`, `WeavingClassLoader.java:341-347`, `AbstractWeaveMojo.java:356-361`.
- **The agent prints diagnostics only while it is installing.** Everything raised afterwards — `AW2202`, `AW4090`, `AW2402` for every class loaded from then on — is appended to a list nothing drains again and is never printed. — `WeaverAgent.java:83-90`, `:346-352`.
- **`AW2101` has `ERROR` severity but stops nothing.** The agent prints it to `System.err` and carries on; nothing in the agent fails on error severity, and the weave stays in the plan. — `DiagnosticCode.java:1447`, `WeaverAgent.java:193-196`, `:346-352`, `RetransformApplicabilityTest.java:146`.
- **`AW3010` does not detect transitivity.** It is a set-membership test against the dependencies the project declared, and version is not compared when the set is built. — `Manifests.java:74-83`, `AbstractWeaveMojo.java:186-206`.
- **A target the parent loader can also see is silently unwoven under `WeavingClassLoader`,** with no diagnostic of any kind — the test asserts the silence rather than describing it. — `WeavingClassLoaderTest.java:129-153`.
- **The testkit runs as `BUILD`,** so bytes that already carry a foreign plan's record are refused with `AW2201` in a test even though the same bytes would be woven at load time. — `testkit/package-info.java:92-94`, `Weaver.java:744-772`.
- **A remote jar leaks a handle that `close()` cannot release** — the owned-handle mechanism covers `file:` jars only. — `WeavingClassLoader.java:391-397`, JavaDoc `:76-81`.
- **`explain` under `premain` says `not woven yet` for every declaration,** because the report is built before any application class has loaded; only the build-time driver can print a complete report. — `WeaverAgent.java:52-59`, `WeaveMojoTest.java:145-169`.

## Could not establish

- **Relative throughput or wall-clock cost of one driver against another.** The only measured figures in the source are the jar-handle comparison (198.7 µs vs 0.404 µs, `WeavingClassLoader.java:274`), the AOT eager-definition observation (a class defined at 0.019 s, `WeavingClassLoader.java:589-591`) and the allocation bound of the no-match path (`NoMatchAllocationTest.java:23`). `aether-weaver-tests/src/test/java/de/splatgames/aether/weaver/perf/WeavingBenchmark.java` declares JMH benchmarks `applyNoMatch`, `applySingleInjection`, `prepare100Weaves`, but no committed numbers; settling any cost claim would need that benchmark run.
- **Startup cost of the agent (discovery plus plan building) in absolute terms.** Nothing measures it.
- **Whether the testkit is called a "driver" by the project.** The source names three: `WeavingClassLoader.java:37-38` says "one of the project's drivers, alongside build-time weaving and the `-javaagent` transformer"; `DiagnosticCode.java:2113-2117` lists "a build-time plugin, a load-time agent or a weaving class loader". The testkit's own package documentation says the opposite of driver-hood — "No agent, no build step and no class redefinition are involved" (`testkit/package-info.java:14`). The four-way framing is `PAGES.tsv`'s, not the source's.
- **Which driver a reader should prefer.** No source states a recommendation; the page can only state what each reaches and costs.
- **Whether `Instrumentation.retransformClasses` succeeds for a given structural weave in practice beyond the measured cases.** `RetransformApplicability.java:22-25` records the measurement on OpenJDK 25 (Temurin 25.0.3+9, Linux) for adding a method, adding a field and clearing `ACC_FINAL`; other JVMs are not established.
- **What the Maven plugin does when two modules of one reactor weave the same class.** Nothing in the plugin sources addresses cross-module ordering.

## Not this page

- Every parameter of `weave`, `weave-tests`, `stubs` and `audit`, in full — **R-maven-goals**.
- Every agent option and its accepted values, `RetransformApplicability` and `ModuleAccess` as reference material — **R-agent-options**.
- The full key list, layer precedence, `AW2310` near-miss suggestions, `TagFilter`, per-weave and per-injection overrides — **R-config-keys** and **G-configuration**. In particular the two dead keys (`policy.allowPackage` decides nothing; `priorityOf` / `isInjectionEnabled` / `phase` are read by nothing) are stated at `runtime/config/package-info.java:206-214` and belong there.
- `AW2003`, `AW2101`, `AW2201`, `AW2202`, `AW2300`-`AW2310`, `AW2401`, `AW2402`, `AW2501` rows with severity and remedy — **R-diag-2**; `AW3001`-`AW3020` — **R-diag-3**; `AW4090` — **R-diag-4**.
- The full step-by-step configuration for each driver — **G-build-time**, **G-load-time**, **G-runtime**, **G-testing**.
- `DefaultWeavePolicy` rules and `WeavePolicy.and(...)` — **G-policy**.
- That no driver sets a plugin discovery loader, so a published `WeaverPlugin` is never loaded — **G-plugins** (anchor `WeaverBuilder.java:80`, `:367-372`).
- Extension indexing, `AW1308`, `AW1309`, `Receivers`, and the fact that only the build-time driver rewrites extension calls — **C-extensions** (anchor `WeaverBuilder.java:92`, `AbstractWeaveMojo.java:224-234`).
- `WovenAssert`'s invariants, `GoldenFiles`, the second-pass truncation as a determinism mechanism — **C-determinism** and **G-testing**.
- `Provenance` / `@Woven` stamping and the fingerprint — **C-pipeline** / **C-determinism**.
- Artefact list, BOM, module dependency arrows — **R-artifacts**.
