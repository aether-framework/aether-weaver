# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Each release's section is also the body of its
[GitHub Release](https://github.com/aether-framework/aether-weaver/releases) — the release workflow
reads it out of this file, so there is one copy of it and it cannot drift.

## [Unreleased]

Nothing yet.

## [0.1.0] - 2026-08-31

The first release. Everything below is new, so this entry describes the shape of the framework
rather than a list of changes.

### Added

#### 🧬 The weaving model

- **`@Weave`** — a plain Java class becomes a weave and names the classes it modifies, by class
  literal or by binary name, never both.
- **Three handler annotations.** `@Inject` runs your code inside a target method and the matched
  instruction still runs; `@Redirect` replaces one operation — a call, a field access, a `new` —
  and the original never happens; `@Wrap` hands the operation over as an `Operation` the handler
  may perform, repeat or skip.
- **Four member annotations.** `@Shadow` declares a member the target already has, `@Unique` adds
  one that cannot collide, and `@Accessor` and `@Invoker` reach a field or method the target keeps
  to itself.
- **`@Local`** captures a local variable of the target method, optionally writing it back.
- **`@Group`** lets several declarations answer for one another, so a weave written against a
  target that legitimately varies can require that at least one of its alternatives matched.
- **Nine injection points** — `HEAD`, `RETURN`, `TAIL`, `INVOKE`, `INVOKE_AFTER`, `FIELD`, `NEW`,
  `CONSTANT` and `THROW` — each with slices, ordinals and shifts where the point supports them.
- **A selector grammar** for naming members: `greet()`, `Gateway.send(Payment)`, `get():String`,
  `*(*)`, and descriptor form for the cases a Java signature cannot express.
- **`@Woven`** stamps every class the engine rewrote, so a woven artefact says so about itself.

#### ⚙️ The engine

- A `byte[]` to `byte[]` weaving engine built on **`java.lang.classfile`** and nothing else. ASM,
  Javassist, Byte Buddy and cglib are banned dependencies in every module, tests included, and the
  build fails if one arrives transitively.
- **Deterministic planning.** Every modification for a class is ordered before a byte is written,
  and weaving the same class file under one plan, one weaver version and one detail level gives
  back the same bytes. Nothing woven records where it was built, or when.
- **A policy gate** that runs before any rewriting and refuses Aether Weaver's own classes,
  `java.*` and every other JDK prefix not explicitly reopened, class files below major version 50,
  and classes from signed jars. A refusal hands the original bytes back unchanged.
- **Structural weaving** — merged members, accessors and invokers — applied separately from the
  body-rewriting injectors, with self-checks that catch malformed exception ranges and unbound
  labels that `ClassFile.verify` does not.
- **132 diagnostics** in one enum: 127 reportable codes and 5 reserved, banded by number into 13
  categories, each carrying its own severity rather than deriving it from its digits.

#### 🚗 Four drivers, one engine

- **`aether-weaver-maven-plugin`** — build-time weaving with four goals: `weave`
  (`process-classes`), `weave-tests` (`process-test-classes`), `stubs` (`generate-sources`) and
  `audit`, which runs without a project.
- **`aether-weaver-agent`** — load-time weaving through `premain` and `agentmain`, declaring both
  `Can-Retransform-Classes` and `Can-Redefine-Classes`.
- **`aether-weaver-runtime`** — `WeavingClassLoader` for weaving inside a running application,
  plus classpath weave discovery and the layered configuration model.
- **`aether-weaver-testkit`** — a JUnit 5 extension that weaves in memory, `WovenAssert` for
  asserting on the result, and golden-file comparison for when the bytes themselves are the thing
  under review.

  The Maven plugin, the agent and the weaving class loader are proven to produce **byte-identical
  output** by an end-to-end test that weaves one fixture all three ways and compares digests — with
  a companion test asserting the fixture really was modified, so three drivers that all did nothing
  cannot pass it.

#### ✅ Compile-time checking

- **`aether-weaver-processor`**, a JSR 269 annotation processor that validates weaves against the
  source and emits the weave manifest (`META-INF/aether/weaves.json`) the build-time driver reads.
- The processor and the engine report the **same diagnostic code** for the same mistake, so a code
  learned once means the same thing at compile time, at build time and at load time.

#### 🧩 The plugin SPI

- **`WeaverPlugin`** contributes injection points and injectors under its own namespace, so two
  plugins cannot collide and a weave names a contribution as `@At(custom = "acme:AFTER_LOGGING")`.
- **Contained failure.** A plugin that throws while being instantiated, while contributing, while
  planning or while a class is being woven is caught at the perimeter and reported against *its*
  identity — `AW3114` through `AW3117` — rather than the engine's.
- **`PluginDiagnosticId`** lets a plugin report in its own namespace rather than borrowing an
  engine code.

#### 💻 Tooling

- An **IntelliJ IDEA plugin** with completion for merged members and selectors, six inspections
  whose codes match the annotation processor's, quick fixes, intentions, gutter markers in both
  directions between a weave and its target, inlay hints, and a *Weaves* tool window. On the
  [JetBrains Marketplace](https://plugins.jetbrains.com/vendor/splatgames-software), and buildable
  from source.

#### 📦 Distribution and build

- **`aether-weaver-bom`** versions every artefact from one import.
- **No third-party dependency reaches a consumer's runtime classpath.** The annotations the
  framework compiles against have CLASS retention and `provided` scope. What the Maven plugin and
  the testkit compile against — Maven's own API, JUnit — is `provided` too, and is what the build
  using them already has.
- **A classpath library.** No `module-info.java` anywhere; every published jar carries a stable
  `Automatic-Module-Name` instead.
- **Reproducible builds** — two clean builds produce byte-identical jars, and CI fails the run if
  they do not.
- CI builds on Linux, Windows and macOS, under a non-English locale and timezone, and against the
  next JDK's early-access build for warning.

#### 📚 Documentation

- A **58-page documentation site** at
  [software.splatgames.de/docs/aether-weaver](https://software.splatgames.de/docs/aether-weaver/).
  Every change to it is built by the real Writerside builder in CI, which fails on any error or
  warning in its report.
- **Complete JavaDoc**: every type, nested type, field, constant, constructor and method of the
  seven published modules, regardless of visibility, with a test that fails the build when one is
  missing.

### Known limitations

Stated here because a first release that hides its edges is worse than one that names them.

- **`api.experimental` is experimental**, and says so on every type in it. Extension methods live
  there, and no compatibility guarantee is stated for those declarations.
- **Plugin discovery is opt-in.** A `WeaverPlugin` is installed by a program that builds its own
  `Weaver` — `Weaver.builder().plugin(…)` or `discoverPlugins(loader)`. The shipped drivers do not
  scan the classpath for third-party plugins.
- **The agent jar is not self-contained.** `-javaagent:aether-weaver-agent.jar` needs
  `aether-weaver-runtime`, `-engine` and `-api` on the system classpath; the jar carries the
  manifest entries and the transformer, not its dependencies.
- **The agent stops reporting after start-up.** Diagnostics raised once start-up has finished are
  not drained, so they are never printed.
- **Build-time weaving is silently a no-op without the annotation processor**, and current `javac`
  releases do not run a classpath processor unless `<proc>full</proc>` asks them to. A build
  missing either one stays green and weaves nothing.
- **Four configuration keys parse and resolve without deciding anything**:
  `aether.weaver.phase`, `weave[name].priority`, `injector[name].enabled` and the package list in
  `policy.allowPackage`. They are documented as inert rather than removed.
- **There is no Gradle plugin.** A Gradle build can declare and compile-check weaves; applying them
  means the agent or the runtime driver.

[Unreleased]: https://github.com/aether-framework/aether-weaver/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/aether-framework/aether-weaver/releases/tag/v0.1.0
