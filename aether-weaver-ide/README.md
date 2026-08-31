# Editor integrations

Everything in this directory integrates Aether Weaver into an editor. It is **not** part of the
Maven reactor, and the root `pom.xml` deliberately does not list it — building an IntelliJ plugin
downloads an IntelliJ Platform distribution, and `mvn install` must never depend on that.
`ProjectStructureTest.ideIsNotInTheReactor` asserts exactly that, so the arrangement is a checked
fact rather than something somebody "helpfully" fixes six months from now.

| Directory | What | Build |
|---|---|---|
| `aether-weaver-idea` | The IntelliJ IDEA plugin | Gradle |

Each integration consumes the framework as a **resolved artefact**, the same way any other
consumer does, rather than reaching into the reactor. Today that artefact is the snapshot
`mvn install` puts in your local repository; the arrangement is what lets the IDE and the build
share one selector grammar rather than two implementations of it.

What the plugin does for a reader, rather than for whoever builds it, is
[the IntelliJ IDEA plugin](https://software.splatgames.de/docs/aether-weaver/latest/intellij-plugin.html)
on the documentation site.

## Building the IntelliJ plugin

```bash
# Once, so that the artefacts the plugin resolves are in ~/.m2. The processor rather than the API
# alone: the cross-check test drives the real annotation processor.
mvn -B -pl aether-weaver-processor -am install -DskipTests

cd aether-weaver-idea
./gradlew build
```

Requires JDK 25. The Gradle wrapper brings its own Gradle, so nothing has to be installed for it.
The first build downloads a full IntelliJ Platform distribution and takes a while; subsequent ones
are fast.

For the checks that need no running IDE — which is what a documentation change should run — use:

```bash
./gradlew checkstyleMain checkstyleTest checkstyleSample javadoc compileSample
```

`./gradlew check` starts the IntelliJ platform test fixtures. They are slow and prone to hanging
locally; CI runs them, along with JetBrains' Plugin Verifier, on every pull request and on every
push to a long-lived branch.
