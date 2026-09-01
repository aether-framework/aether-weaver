# S-install — Install

Section `start`, kind `howto`, file `Writerside/topics/start/install.md`.
All anchors are repository-relative; every line number was read from the file named.

## Facts

### Coordinates

- **The groupId of every artifact is `de.splatgames.aether.weaver`** — `pom.xml:21`. Every
  module inherits it; no module overrides `<groupId>`.
- **The reactor root is `de.splatgames.aether.weaver:aether-weaver`, packaging `pom`**
  — `pom.xml:22`, `pom.xml:24`. It is the parent of all nine modules and is not something a
  consumer inherits from.
- **The reactor version is `0.1.0-SNAPSHOT`** — `pom.xml:23`, repeated verbatim in every
  module's `<parent>` block: `aether-weaver-bom/pom.xml:14`, `aether-weaver-api/pom.xml:10`,
  `aether-weaver-processor/pom.xml:10`, `aether-weaver-testkit/pom.xml:10`,
  `aether-weaver-maven-plugin/pom.xml:18`, `aether-weaver-engine/pom.xml:10`,
  `aether-weaver-runtime/pom.xml:10`, `aether-weaver-agent/pom.xml:10`,
  `aether-weaver-tests/pom.xml:13`.
- **`v.list` declares `%version%` as `0.1.0`, not `0.1.0-SNAPSHOT`** — `Writerside/v.list:10`
  (`<var name="version" value="0.1.0"/>`). See **Surprises**.
- **Nine modules are in the reactor**, in this order: `aether-weaver-bom`, `-api`, `-engine`,
  `-runtime`, `-agent`, `-processor`, `-maven-plugin`, `-testkit`, `-tests` — `pom.xml:62-72`.
- **Seven modules are the published jars.** `ProjectStructureTest.PUBLISHED_MODULES` lists
  `aether-weaver-api`, `-engine`, `-runtime`, `-agent`, `-processor`, `-maven-plugin`,
  `-testkit` —
  `aether-weaver-tests/src/test/java/de/splatgames/aether/weaver/architecture/ProjectStructureTest.java:19-26`.
- **`aether-weaver-tests` is never installed or deployed**: `maven.deploy.skip` and
  `maven.install.skip` are both `true` — `aether-weaver-tests/pom.xml:23-24`. Nothing sets
  those properties in the BOM, so the BOM pom is deployed.
- **`aether-weaver-ide` is not a module and has no pom at all**, asserted by a test —
  `ProjectStructureTest.java:229-250` (`assertThat(ide.resolve("pom.xml")).doesNotExist()`,
  and the root pom must not contain `<module>aether-weaver-ide`).

### What each artifact is

Each `<description>` is the module's own one-line statement of purpose.

- **`aether-weaver-api`** — "Annotations, selector grammar, SPI contracts and diagnostic codes.
  Zero dependencies." — `aether-weaver-api/pom.xml:17`. Its only non-test dependency is
  `org.jetbrains:annotations` — `aether-weaver-api/pom.xml:24-27`.
- **`aether-weaver-engine`** — "The byte[] to byte[] weaving engine: parsing, resolution,
  planning, injection, verification." — `aether-weaver-engine/pom.xml:17`. Depends on
  `aether-weaver-api` — `aether-weaver-engine/pom.xml:28-31`.
- **`aether-weaver-runtime`** — "In-application Weaver facade, weaving class loader and
  classpath discovery." — `aether-weaver-runtime/pom.xml:17`. Depends on
  `aether-weaver-engine` — `aether-weaver-runtime/pom.xml:28-31`.
- **`aether-weaver-agent`** — "Java agent: premain, agentmain and the ClassFileTransformer
  driver." — `aether-weaver-agent/pom.xml:17`. Depends on `aether-weaver-runtime` —
  `aether-weaver-agent/pom.xml:28-31`.
- **`aether-weaver-processor`** — "JSR 269 annotation processor: compile-time validation and
  weave manifest emission." — `aether-weaver-processor/pom.xml:17`. Depends on the api
  (`:28-31`) **and on the engine** (`:40-43`), because it resolves injection points with the
  same code the weaver runs — `aether-weaver-processor/pom.xml:32-39`.
- **`aether-weaver-maven-plugin`** — "Maven plugin that weaves compiled classes at build
  time.", packaging `maven-plugin` — `aether-weaver-maven-plugin/pom.xml:22,25`. Depends on
  `aether-weaver-engine` (`:40-43`); its Maven dependencies are all `provided`
  (`maven-plugin-api`, `maven-plugin-annotations`, `maven-core` — `:44-61`).
- **`aether-weaver-testkit`** — "JUnit 5 support, bytecode assertions and in-memory weaving for
  tests." — `aether-weaver-testkit/pom.xml:17`. Depends on `aether-weaver-engine` (`:28-31`)
  and on `org.junit.jupiter:junit-jupiter-api` at **`provided`** scope (`:38-42`).

### Transitive closure a consumer actually gets

- **Depending on `aether-weaver-engine` brings `aether-weaver-api` transitively** —
  `aether-weaver-engine/pom.xml:28-31` (compile scope, no `<scope>` element).
- **Depending on `aether-weaver-testkit` brings the engine and the api, and does not bring
  JUnit** — `aether-weaver-testkit/pom.xml:28-31` plus the `provided` scope at `:38-42`. The
  pom states the reason: "a consumer already has JUnit on its test classpath, and a testkit
  that dragged its own version in would decide the JUnit version of every project that used
  it" — `aether-weaver-testkit/pom.xml:32-37`.
- **No module leaks `org.jetbrains:annotations` onto a consumer classpath.** The parent manages
  it at `provided` scope — `pom.xml:172-177` — and every module declares it without a `<scope>`
  element (`aether-weaver-api/pom.xml:24-27`, `-engine:24-27`, `-runtime:24-27`,
  `-agent:24-27`, `-processor:24-27`, `-testkit:24-27`, `-maven-plugin:36-39`), so the managed
  scope applies. The pom states the intent: "every annotation used here has CLASS retention, so
  nothing reaches the runtime and consumers inherit no dependency" — `pom.xml:164-166`.
- **`org.jetbrains:annotations` is the only third-party dependency the api and engine are
  permitted** — `pom.xml:167`.
- **Pinned versions in this build**: `jetbrains-annotations` 26.1.0 (`pom.xml:117`), Maven API
  3.9.9 (`pom.xml:107`), maven-plugin-tools 3.15.2 (`pom.xml:108`), JUnit BOM 5.11.4
  (`pom.xml:115`), AssertJ 3.27.2 (`pom.xml:116`), JMH 1.37 (`pom.xml:114`). Only the first is
  visible to a consumer, and only at `provided`.

### The BOM

- **`aether-weaver-bom` has packaging `pom` and contains nothing but a
  `<dependencyManagement>` block** — `aether-weaver-bom/pom.xml:17-18`, `:23-61`. It declares
  no `<dependencies>`, no `<build>` and no `<properties>`.
- **Its stated purpose**: "Consumers import this to get a consistent set of Aether Weaver
  versions without repeating `<version>` on every dependency. Aether family convention." —
  `aether-weaver-bom/pom.xml:2-5`.
- **The BOM manages seven entries**: `aether-weaver-api` (`:25-29`), `-engine` (`:30-34`),
  `-runtime` (`:35-39`), `-agent` (`:40-44`), `-processor` (`:45-49`), `-testkit` (`:50-54`),
  and **`aether-weaver`** (`:55-59`).
- **The BOM does not manage `aether-weaver-maven-plugin`** — the list at
  `aether-weaver-bom/pom.xml:23-60` has no such entry. The parent's own
  `<dependencyManagement>` does manage it (`pom.xml:157-161`), but only so that
  `aether-weaver-tests` can instantiate a mojo — `pom.xml:153-156`,
  `aether-weaver-tests/pom.xml:58-67`.
- **`<version>` in every BOM entry is `${project.version}`** — e.g.
  `aether-weaver-bom/pom.xml:28`. It resolves to the BOM's own version, so importing the BOM
  fixes all Aether Weaver versions to the BOM's.
- **The BOM inherits from `aether-weaver`** — `aether-weaver-bom/pom.xml:11-15`. The
  parent's `<dependencyManagement>` (`pom.xml:120-219`) additionally manages
  `org.junit:junit-bom` (imported, `:192-198`), `org.assertj:assertj-core` at test scope with a
  `net.bytebuddy:byte-buddy` exclusion (`:199-217`), two JMH artifacts (`:179-190`) and
  `org.jetbrains:annotations` at `provided` (`:172-177`). See **Could not establish** for what
  a consumer importing the BOM inherits from that.

### Required JDK and Maven

- **`maven.compiler.release` is 25 for every module** — `pom.xml:75`. The property is set once
  in the parent and no module overrides it.
- **The enforcer requires JDK 25 or newer to build**, with the message "Aether Weaver requires
  JDK 25+: it is built on the standard Class-File API." — `pom.xml:305-309`
  (`<requireJavaVersion><version>[25,)</version>`).
- **The enforcer requires Maven 3.9 or newer** — `pom.xml:310-312`
  (`<requireMavenVersion><version>[3.9,)</version>`).
- **The Maven plugin declares `<prerequisites><maven>3.9</maven></prerequisites>`** —
  `aether-weaver-maven-plugin/pom.xml:27-29`. This one is consumer-facing: Maven refuses to run
  a plugin whose prerequisite its own version does not satisfy.
- **CI builds and verifies on JDK 25 only**, on ubuntu, windows and macos —
  `.github/workflows/build.yml:34-35`. JDK 26 early access runs as an informational,
  `continue-on-error: true` job — `.github/workflows/build.yml:206-222`.
- **The sample consumer project sets `maven.compiler.release` 25 too** —
  `aether-weaver-ide/aether-weaver-idea/sample/pom.xml:24`; the IntelliJ plugin build pins a
  Java 25 toolchain — `aether-weaver-ide/aether-weaver-idea/build.gradle.kts:57-59`.
- **`v.list` already carries `%jdk%` = `25` and `%maven%` = `3.9`** — `Writerside/v.list:11-12`.

### What a consumer's pom looks like, from the one consumer in the tree

- **The sample project depends on `aether-weaver-api` alone to write weaves** —
  `aether-weaver-ide/aether-weaver-idea/sample/pom.xml:29-35`, with the version held in a
  property `<aether.weaver.version>0.1.0-SNAPSHOT</aether.weaver.version>` (`:26`).
- **It is deliberately standalone — no parent, not in the reactor — so that it resolves the API
  "from the repository rather than from a sibling module"** —
  `aether-weaver-ide/aether-weaver-idea/sample/pom.xml:2-9`.
- **It pins `maven-compiler-plugin` 3.14.0 and sets `<proc>none</proc>`**, deliberately leaving
  the annotation processor out — `aether-weaver-ide/aether-weaver-idea/sample/pom.xml:39-58`.
  The comment states the consequence: without the processor and the weaver plugin, a source
  file calling extension methods does not compile
  (`aether-weaver-ide/aether-weaver-idea/sample/pom.xml:44-55`).
- **Annotation processing has to be switched on explicitly with maven-compiler-plugin 3.14**:
  "maven-compiler-plugin 3.14 does not run annotation processors unless asked: implicit
  discovery was deprecated, and the default is now to skip processing entirely" —
  `aether-weaver-tests/pom.xml:120-126`, which sets `<proc>full</proc>` to fix it.
- **The processor registers itself for service discovery** as
  `de.splatgames.aether.weaver.processor.WeaveProcessor` —
  `aether-weaver-processor/src/main/resources/META-INF/services/javax.annotation.processing.Processor:1`.
  On the compile classpath with processing enabled, javac finds it without an
  `annotationProcessorPaths` entry.

### Why the processor is not optional for a Maven build

- **The processor writes `META-INF/aether/weaves.json` into `CLASS_OUTPUT`** —
  `aether-weaver-processor/src/main/java/de/splatgames/aether/weaver/processor/ManifestEmitter.java:170-171`
  (`filer.createResource(StandardLocation.CLASS_OUTPUT, "", WeaveManifest.RESOURCE)`), named in
  `aether-weaver-processor/src/main/java/de/splatgames/aether/weaver/processor/package-info.java:357-358`.
- **The Maven plugin finds weaves only through that manifest.** `ClassDirectory.manifest`
  resolves `WeaveManifest.RESOURCE` under the class directory and **returns `null` when the file
  is not there** —
  `aether-weaver-maven-plugin/src/main/java/de/splatgames/aether/weaver/maven/ClassDirectory.java:83-88`.
  There is no directory scan fallback in that method.
- **A `null` manifest is not an error**: the JavaDoc states all three failure modes "return
  `null`, and the caller is expected to go on with no weaves rather than to fail" —
  `ClassDirectory.java:70-76`. So a build with the plugin but without the processor weaves
  nothing and does not complain.
- **The plugin also reads the manifest out of classpath entries** —
  `aether-weaver-maven-plugin/src/main/java/de/splatgames/aether/weaver/maven/AbstractWeaveMojo.java:226-228`,
  `Manifests.java:28` (`META-INF/aether/weaves.json` inside an archive).

### The Maven plugin's goals

- **`weave`** — default phase `process-classes`, `requiresDependencyResolution =
  COMPILE_PLUS_RUNTIME`, `threadSafe = true` — `WeaveMojo.java:44-47`. Weaves
  `${project.build.outputDirectory}` (`WeaveMojo.java:64-65`, `readonly = true`).
- **`weave-tests`** — default phase `process-test-classes`, `requiresDependencyResolution =
  TEST`, `threadSafe = true` — `WeaveTestsMojo.java:31-34`.
- **`stubs`** — default phase `generate-sources`, `requiresDependencyResolution = COMPILE`,
  `threadSafe = true` — `StubsMojo.java:90-93`.
- **`audit`** — `requiresProject = false`, `threadSafe = true` — `AuditMojo.java:66`. Its
  `artifact` parameter reads the property `aether.weaver.artifact` and defaults to
  `${project.build.outputDirectory}` — `AuditMojo.java:82-84`.
- **`aether.weaver.skip` (default `false`) turns the weaving goals off**, and `StubsMojo` reads
  the same property — `AbstractWeaveMojo.java:79-87`.

### The agent jar

- **`Premain-Class` and `Agent-Class` are both
  `de.splatgames.aether.weaver.agent.WeaverAgent`** — `aether-weaver-agent/pom.xml:49-50`.
- **`Can-Retransform-Classes` and `Can-Redefine-Classes` are both `true`** —
  `aether-weaver-agent/pom.xml:51-52`.
- The pom states why both entry points exist: "`-javaagent` uses `Premain-Class`, a dynamic
  attach uses `Agent-Class`, and having one without the other produces a jar that works exactly
  one of the two ways with no explanation" — `aether-weaver-agent/pom.xml:42-48`.

### Jar manifests and JPMS

- **Every published jar carries `Automatic-Module-Name`, `Implementation-Title`,
  `Implementation-Version` and `Implementation-Vendor`** — `pom.xml:265-268`, from the managed
  `maven-jar-plugin` configuration at `pom.xml:252-272`.
- **The names**: `de.splatgames.aether.weaver.api` (`aether-weaver-api/pom.xml:20`), `.engine`
  (`aether-weaver-engine/pom.xml:20`), `.runtime` (`aether-weaver-runtime/pom.xml:20`),
  `.agent` (`aether-weaver-agent/pom.xml:20`), `.processor`
  (`aether-weaver-processor/pom.xml:20`), `.testkit` (`aether-weaver-testkit/pom.xml:20`), and
  **`de.splatgames.aether.weaver.maven`** for the Maven plugin
  (`aether-weaver-maven-plugin/pom.xml:32`). The parent's fallback value is
  `de.splatgames.aether.weaver`, which "nothing reads" in the modules that do not override it —
  `pom.xml:82-86`.
- **A test fails the build if a published module drops the property** —
  `ProjectStructureTest.java:252-272`, with the reason "a published jar without a stable
  Automatic-Module-Name breaks JPMS consumers on every version bump".
- **There is no `module-info.java` and a test enforces that** — `ProjectStructureTest.java:63-66`.
- **`Automatic-Module-Name` "imposes nothing on this build: no modular compilation, no
  requires, no module path"** — `pom.xml:259-264`.

### Licence, provenance, reproducibility

- **MIT License, `distribution` `repo`** — `pom.xml:39-45`.
- **Project URL `https://github.com/aether-framework/aether-weaver`** — `pom.xml:31`, and the
  same URL as `<scm><url>` — `pom.xml:59`; `scm:git:https://github.com/aether-framework/aether-weaver.git`
  — `pom.xml:57`. `v.list` holds this as `%repo%` — `Writerside/v.list:13`.
- **Organization `Splatgames.de Software`, `https://splatgames.de`** — `pom.xml:34-37`; `v.list`
  has `%vendor%` and `%vendor-url%` — `Writerside/v.list:15-16`.
- **`project.build.outputTimestamp` is fixed at `2026-01-01T00:00:00Z`** — `pom.xml:79-80` —
  and CI proves two clean builds produce byte-identical jars —
  `.github/workflows/build.yml:135-166`.
- **ASM, Javassist, Byte Buddy and cglib are banned in every module including tests**, enforced
  by `bannedDependencies` — `pom.xml:313-330`. A consumer inherits nothing of this, but it is
  why the artifacts pull in no bytecode library.

### Writerside variables the page must use

- **Declared in `Writerside/v.list`**: `%product%` = `Aether Weaver` (`:8`), `%group%` =
  `de.splatgames.aether.weaver` (`:9`), `%version%` = `0.1.0` (`:10`), `%jdk%` = `25` (`:11`),
  `%maven%` = `3.9` (`:12`), `%repo%` (`:13`), `%issues%` (`:14`), `%vendor%` (`:15`),
  `%vendor-url%` (`:16`), `%classfile-api%` (`:17`).
- **The gate rejects a three-part version literal in any topic** except
  `contributing/release-notes.md` — `build-config/docsite/check-docs.py:72-73`, `:290-292`. The
  regex is `(?<![\w.%-])\d+\.\d+\.\d+(?![\w.%-])` (`:291`), so `25` and `3.9` are not caught,
  but `0.1.0` is.
- **An undeclared `%name%` is an error** — `build-config/docsite/check-docs.py:259-261`.
- **No snippets directory is registered.** `Writerside/writerside.cfg:16-25` has no
  `<snippets src="..."/>` element, and `Writerside/README.md:98-99` names its absence
  explicitly. A fenced block with `{ src="..." }` has nothing to resolve against, so pom XML on
  this page has to be an inline fenced block.
- **`<seealso>` categories are `concepts`, `guides`, `reference`, `external`** —
  `Writerside/c.list:5-8`.
- **The only topic that exists and is in the tree today is `overview.topic`** —
  `Writerside/aw.tree:12`, and `Writerside/topics/` holds six empty section directories plus
  that file. Any internal link this page makes must be to a topic already committed.
- **Labels available**: `wip`, `experimental`, `internal`, `0.1.0` —
  `Writerside/labels.list:12-29`.

## Identifiers

Spelled as the source spells them.

```
groupId:            de.splatgames.aether.weaver
artifacts:          aether-weaver (pom)             aether-weaver-bom (pom)
                    aether-weaver-api               aether-weaver-engine
                    aether-weaver-runtime           aether-weaver-agent
                    aether-weaver-processor         aether-weaver-testkit
                    aether-weaver-maven-plugin (maven-plugin packaging)
in the BOM only:    aether-weaver          <- no module produces this
never published:    aether-weaver-tests
reactor version:    0.1.0-SNAPSHOT
```

Maven properties in the reactor: `maven.compiler.release`=25, `project.build.outputTimestamp`,
`automatic.module.name`, `build.config.dir`, `maven.api.version`=3.9.9,
`maven-plugin-tools.version`=3.15.2, `jmh.version`=1.37, `junit.version`=5.11.4,
`assertj.version`=3.27.2, `jetbrains-annotations.version`=26.1.0 (`pom.xml:74-118`).

Sample-project property name for the version: `aether.weaver.version`
(`aether-weaver-ide/aether-weaver-idea/sample/pom.xml:26`).

Plugin goals: `weave`, `weave-tests`, `stubs`, `audit`.
Plugin user properties: `aether.weaver.skip`, `aether.weaver.failOnError`,
`aether.weaver.explain`, `aether.weaver.dump`, `aether.weaver.artifact`,
`aether.weaver.weaveDependencies`, `aether.weaver.allowSigned`
(`AbstractWeaveMojo.java:86,97,107,119`; `AuditMojo.java:82`; `WeaveMojo.java:74,85`).

Manifest resource the processor writes and the plugin reads: `META-INF/aether/weaves.json`
(`Manifests.java:28`, `package-info.java:357`).

Service file: `META-INF/services/javax.annotation.processing.Processor` →
`de.splatgames.aether.weaver.processor.WeaveProcessor`.

Agent manifest keys: `Premain-Class`, `Agent-Class`, `Can-Retransform-Classes`,
`Can-Redefine-Classes`; agent class `de.splatgames.aether.weaver.agent.WeaverAgent`.

Automatic module names: `.api`, `.engine`, `.runtime`, `.agent`, `.processor`, `.testkit`,
`.maven` (note: **`maven`**, not `maven-plugin`).

Build commands attested by the repository: `mvn -B -o verify` (CLAUDE.md gate),
`mvn -B -ntp -e clean verify` (`.github/workflows/build.yml:19,51`),
`mvn -B -ntp -e -pl aether-weaver-processor -am install -DskipTests`
(`.github/workflows/build.yml:107`).

## Surprises

- **`%version%` is `0.1.0` but every pom in the tree says `0.1.0-SNAPSHOT`** —
  `Writerside/v.list:10` against `pom.xml:23`. A page that writes `<version>%version%</version>`
  documents coordinates no build in this repository produces. The gate forbids writing the
  literal (`check-docs.py:291`), so the page cannot state the snapshot version either. This
  needs a decision above the page; it is not resolvable by writing.
- **The BOM manages an artifact that does not exist.**
  `aether-weaver-bom/pom.xml:55-59` manages `de.splatgames.aether.weaver:aether-weaver`, and no
  `<module>` produces it — `pom.xml:62-72`. A grep across every pom in the tree finds that
  artifactId in exactly one place: the BOM. A consumer who writes
  `<artifactId>aether-weaver</artifactId>` after importing the BOM gets a resolution failure
  with a version, not a helpful one. **Do not present `aether-weaver` as an artifact a reader
  may depend on.**
- **The BOM does not manage the Maven plugin.** `aether-weaver-bom/pom.xml:23-60`. A reader who
  imports the BOM and then writes the plugin without a `<version>` gets a build that either
  fails or silently resolves whatever Maven's plugin-version resolution finds. The plugin's
  `<version>` must be written out.
- **The Maven plugin's pom claims its goals are stubs** — "The goals are stubs for now; the
  module exists so the reactor structure is complete and the plugin coordinates are reserved."
  — `aether-weaver-maven-plugin/pom.xml:7-8`. The source contradicts it: four fully implemented
  mojos with parameters, diagnostics and tests (`WeaveMojo.java:44`, `WeaveTestsMojo.java:31`,
  `StubsMojo.java:90`, `AuditMojo.java:66`, plus six test classes under
  `aether-weaver-maven-plugin/src/test/java/`). **The comment is wrong; the code wins.** Do not
  repeat the comment on the page.
- **The plugin without the processor is silent, not loud.** No `META-INF/aether/weaves.json`
  means `ClassDirectory.manifest` returns `null` (`ClassDirectory.java:86-88`) and the goal
  proceeds "with no weaves rather than to fail" (`ClassDirectory.java:70-76`). The failure mode
  a reader hits is a green build that wove nothing. This is the single most useful warning the
  install page can carry.
- **Annotation processing is off by default with maven-compiler-plugin 3.14**, per
  `aether-weaver-tests/pom.xml:120-126`. Putting `aether-weaver-processor` on the classpath is
  therefore not sufficient on that plugin version; `<proc>full</proc>` or an
  `annotationProcessorPaths` entry is needed. The sample project's `<proc>none</proc>`
  (`sample/pom.xml:56`) is a deliberate opt-out, not a template.
- **`aether-weaver-api`'s description says "Zero dependencies"** (`aether-weaver-api/pom.xml:17`)
  while the pom declares one, `org.jetbrains:annotations`
  (`aether-weaver-api/pom.xml:24-27`). Both are true as stated: the managed scope is `provided`
  (`pom.xml:172-177`), so the transitive closure a consumer receives is empty.
- **The testkit brings no JUnit.** `provided` scope at `aether-weaver-testkit/pom.xml:38-42`. A
  consumer must already have `junit-jupiter` on the test classpath.
- **The plugin's automatic module name is `de.splatgames.aether.weaver.maven`**
  (`aether-weaver-maven-plugin/pom.xml:32`), which does not match the artifactId pattern the
  other six follow.

## Could not establish

1. **Which repository a consumer resolves these artifacts from.** No `<distributionManagement>`
   and no `<repositories>` element exists in any pom in the reactor (grepped across every
   `pom.xml`; the only external repositories named anywhere are `mavenLocal()` and
   `mavenCentral()` in the IntelliJ plugin's Gradle build,
   `aether-weaver-ide/aether-weaver-idea/build.gradle.kts:10-14`). There is also no release or
   deploy workflow — `.github/workflows/` contains only `build.yml` and `docs.yml`. Settling
   this needs a deployment target to be configured in the poms or a release workflow to exist.
   The only distribution route the source attests is building from `%repo%` and installing into
   the local repository, as CI does (`.github/workflows/build.yml:106-107`).
2. **Whether a consumer importing `aether-weaver-bom` also inherits the parent's other managed
   dependencies** — the junit BOM import, AssertJ with its Byte Buddy exclusion, JMH, and
   `org.jetbrains:annotations` at `provided` (`pom.xml:172-217`). The BOM declares
   `aether-weaver` as its parent (`aether-weaver-bom/pom.xml:11-15`), and whether an
   imported pom's inherited `<dependencyManagement>` reaches the importer is Maven model
   behaviour rather than something a line in this repository states. No test in the repository
   exercises importing the BOM. Settling it needs a real consumer build resolving the deployed
   BOM, or an explicit statement in the BOM.
3. **The Maven plugin prefix.** `mvn <prefix>:audit` needs a prefix; the artifactId follows the
   `<name>-maven-plugin` convention (`aether-weaver-maven-plugin/pom.xml:21`) but no
   `<goalPrefix>` is configured on `maven-plugin-plugin`
   (`aether-weaver-maven-plugin/pom.xml:66-70`) and the generated `plugin.xml` is build output,
   not source. Do not write a `mvn aether-weaver:audit` command line without checking the
   generated descriptor.
4. **Whether `java.lang.classfile` on JDK 25 makes 25 a hard runtime floor for a consumer, or
   only a compile floor.** `maven.compiler.release` is 25 (`pom.xml:75`) and the enforcer's
   message says "Aether Weaver requires JDK 25+" (`pom.xml:307-308`), but `requireJavaVersion`
   constrains this build only, not a consumer's. The class file major version of the published
   jars is a consequence of `release=25` and is not asserted by any test in the repository.
5. **Gradle coordinates and whether a Gradle consumer is supported.** The only Gradle build in
   the tree is the IntelliJ plugin, which is explicitly not a consumer template
   (`build.gradle.kts:10-14` uses `mavenLocal()`). No Gradle documentation, no
   `gradle-metadata` configuration, no test.
6. **Whether the api or the engine is the right compile dependency for a reader writing a
   weave.** The only consumer in the tree depends on the api alone
   (`sample/pom.xml:29-35`), but that project is explicitly not woven
   (`sample/pom.xml:44-55`). Establishing the minimal compile-time set for a woven consumer
   needs the first-weave page's own end-to-end configuration.

## Not this page

- **Plugin parameter semantics** — `aether.weaver.failOnError`, `aether.weaver.explain`,
  `aether.weaver.dump` and their defaults (`AbstractWeaveMojo.java:86-119`),
  `aether.weaver.weaveDependencies`, `aether.weaver.allowSigned`, `dependencyOutputDirectory`
  (`WeaveMojo.java:74-97`) → the Maven plugin reference page.
- **`AW2300` / `AW2301` (manifest unreadable, manifest schema version) and `AW4090` (dump
  failure)** — reported at `ClassDirectory.java:70-76` and `AbstractWeaveMojo.java:110-117` →
  the diagnostics reference page.
- **`stubs` goal, extension methods and the compiler argument they need** —
  `aether-weaver-ide/aether-weaver-idea/sample/README.md:141-186`, `StubsMojo.java:90-100`
  (`PATCH`, `CLASSPATH` subdirectories) → the extension-methods guide.
- **The agent's option syntax and `Can-Retransform-Classes` consequences** → the agent guide
  and the choose-a-driver page (S-choose-driver).
- **The banned-dependency rule, the no-`module-info` rule, the one-way dependency arrow and the
  reproducible-build job** (`pom.xml:313-330`, `ProjectStructureTest.java:63-66`,
  `.github/workflows/build.yml:135-166`) → the contributing section.
- **`Weaving`, `WovenAssert`, `WeaverExtension`, `Weaves`, `GoldenFiles`, `WeaveResult`** in
  `aether-weaver-testkit/src/main/java/de/splatgames/aether/weaver/testkit/` → the testing
  guide.
- **`WeavingClassLoader`, `WeaveDiscovery`, `ManifestWeaveSource`, `AotCache`** in
  `aether-weaver-runtime/src/main/java/de/splatgames/aether/weaver/runtime/` → the runtime
  driver page.
