# 🤝 Contributing to Aether Weaver

Thank you for being here. This page is the short version: what you need installed, the one command
that has to stay green, and the handful of rules the build enforces rather than trusting to review.

Everything longer lives on the documentation site:
[Repository layout](https://software.splatgames.de/docs/aether-weaver/latest/repository-layout.html),
[Building and testing](https://software.splatgames.de/docs/aether-weaver/latest/building-and-testing.html),
and the [JavaDoc policy](https://software.splatgames.de/docs/aether-weaver/latest/javadoc-policy.html).

---

## 🔧 What you need

| | |
|---|---|
| JDK | **25 or newer** — the framework is built on `java.lang.classfile`, which is standard from 25 |
| Maven | **3.9 or newer** |
| Gradle | only for `aether-weaver-ide`, and only if you work on the IntelliJ plugin |
| Python | 3, only if you touch the documentation site. The gate is developed against 3.11 |

The enforcer stops the build on anything older, with a message that says which rule you tripped.

---

## 🚀 Getting started

```bash
git clone https://github.com/aether-framework/aether-weaver.git
cd aether-weaver
mvn -B clean verify
```

That is the gate. A green run ends with a reactor summary of ten modules and one verdict, and it
runs everything: the enforcer, Checkstyle, every module's unit suite, the architecture tests, and
the JavaDoc goal resolving every `{@link}`.

While you work, run less:

| What you changed | What to run |
|---|---|
| Code in one module | `mvn -B -o -pl aether-weaver-engine -am test` |
| Imports or formatting | `mvn -B -o validate` |
| A doc comment | `mvn -B -o verify` |
| A documentation page | `python3 build-config/docsite/check-docs.py --build` |
| Anything, before you push | `mvn -B -o clean verify` |

`-o` holds Maven offline; nothing in the reactor needs the network once your local repository has
the build's plugins.

---

## 📐 The four rules the build enforces

These are not style preferences. Each has a test or a plugin that fails the build, and working
around one is never the right fix.

1. **Bytecode manipulation goes through `java.lang.classfile` and nothing else.** ASM, Javassist,
   Byte Buddy and cglib are banned dependencies in every module, tests included. The enforcer
   rejects them, and AssertJ's transitive Byte Buddy is excluded for exactly this reason.
2. **No `module-info.java` anywhere.** Aether Weaver is a classpath library. Consumers who use JPMS
   get a stable `Automatic-Module-Name` from the jar manifest instead.
3. **The dependency arrow points one way:** `api <- engine <- drivers`. The api imports nothing but
   the JDK and its annotations, the engine may not import a driver, no driver imports another, and
   engine internals stay inside the engine. `ProjectStructureTest` reads `import` lines to enforce
   this — which is why an import added only to satisfy a documentation link fails the build. Use a
   fully qualified `{@link de.splatgames.aether.weaver…}` across a module boundary.
4. **`aether-weaver-ide` is not in the Maven reactor.** Building the IntelliJ plugin downloads an
   IntelliJ Platform distribution, and `mvn install` must never depend on that.

---

## 📝 Documentation is part of the change

Two bodies of documentation, one voice. A reader moving from a generated API page to the site
should not notice a change of author.

- **JavaDoc.** Every type, nested type, field, constant, constructor and method in the seven
  published modules carries a doc comment — regardless of visibility, `@Override` methods included.
  `JavadocCoverageTest` checks that mechanically, so an undocumented member is a red build rather
  than a review comment. Types carry `@author` and `@since`; members carry neither.
- **The documentation site.** `Writerside/` is a Writerside help module with its own gate:
  `python3 build-config/docsite/check-docs.py --build`, which runs the real builder and fails on
  any error or warning in its report.

One rule governs both, and it is the one most worth internalising: **the past is not evidence.**
Do not establish what the code does from commit messages, older revisions or a deleted document.
The current source is the only source of truth. Where behaviour is not visible in it, measure it
again or leave it out — a guess written into a specification is worse than a gap, because the next
reader cannot tell it from a fact.

---

## 🌿 Branching

The project follows the Aether family's Git Flow model.

| Branch | What it is |
|---|---|
| `main` | Released code. Protected; only release and hotfix branches merge into it |
| `develop` | The integration branch. **Open your pull request against this one** |
| `feature/<name>` | A new capability |
| `bugfix/<name>` | A fix for something on `develop` |
| `release/<version>` | Stabilising a release |
| `hotfix/<version>` | An urgent fix on top of a release |

If you open a pull request against `main` by mistake — GitHub offers it as the default base, so it
is an easy one to make — it is moved to `develop` automatically and told why. Nothing is wrong with
your change when that happens.

---

## 🔁 How a change gets in

1. **Open an issue first** for anything larger than a fix. It is much cheaper to agree on the shape
   before the code exists.
2. **Fork and branch** from `develop`.
3. **Write the change, and the test that fails without it.** A test that passes both before and
   after a fix protects nothing. This project's habit is to verify that explicitly — revert the fix,
   watch the test fail, put it back.
4. **Document it.** Every new member needs JavaDoc; changed behaviour needs the page that describes
   it updated.
5. **Run the gate.** `mvn -B clean verify`, plus the docs gate if you touched `Writerside/`.
6. **Sign off your commits** — `git commit -s`, certifying the [DCO](DCO).
7. **Open the pull request** against `develop` and fill in the template.

### Commit messages

Write a subject line that says what the change does, in the imperative, as a sentence. The body
explains *why* — the diff already shows what. If a commit fixes an issue, name it.

```
Stop the plugin guard from blaming the engine for a plugin's failure

AW4004 named the engine in its remedy string whenever an injector threw,
including when the injector came from a third-party plugin. …
```

---

## 🤖 AI-assisted contributions

This project is developed with AI assistance and says so. There are rules about it, and they are
about accountability rather than about the tool: see [AI_USAGE.md](AI_USAGE.md). The pull request
template has a disclosure checkbox — checking it tells a reviewer where to look hardest, and is
never held against a change.

---

## 🚫 What a change never does

- Deletes, skips or weakens a test to make a gate pass. If a gate fails for a reason that is not
  your change, say so and stop.
- Changes code to make a comment or a documentation page correct.
- Adds a dependency without a reason the pull request states.

---

## 📢 Need help?

- The [documentation site](https://software.splatgames.de/docs/aether-weaver/) — the getting-started
  path, the concepts, and every `AW####` code the framework can report.
- [Open a question issue](https://github.com/aether-framework/aether-weaver/issues/new/choose).
- Security problems go to `security@splatgames.de`, never to a public issue. See
  [SECURITY.md](SECURITY.md).

Everyone taking part is expected to follow the [Code of Conduct](CODE_OF_CONDUCT.md).

🚀 **Happy weaving!**
