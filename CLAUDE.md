# Aether Weaver — Agent Instructions

## The project

A general-purpose bytecode weaving framework for the JVM. Nine Maven modules plus one
Gradle build that is deliberately outside the reactor.

Four rules are enforced mechanically rather than by convention. Do not work around any of
them; each has a test or a plugin that will fail the build.

- **Bytecode manipulation goes through `java.lang.classfile` and nothing else.** ASM,
  Javassist, Byte Buddy and cglib are banned dependencies in every module, tests included.
- **No `module-info.java` anywhere.** Aether Weaver is a classpath library.
- **The dependency arrow points one way**: `api <- engine <- drivers`. The api imports
  nothing but the JDK and JSpecify, the engine may not import a driver, drivers may not
  import each other, and engine internals stay inside the engine. `ProjectStructureTest`
  reads `import` lines to enforce this, which is why an import added only to satisfy a
  documentation link is a build failure.
- **`aether-weaver-ide` is not in the reactor.** Building an IntelliJ plugin downloads an
  IntelliJ Platform distribution and `mvn install` must never depend on that.

Gate for everything in the reactor:

```
mvn -B -o verify
```

For the IDE plugin, which is a separate Gradle build:

```
cd aether-weaver-ide/aether-weaver-idea && ./gradlew checkstyleMain checkstyleTest checkstyleSample javadoc
```

Do not run `./gradlew check` there. It starts the IntelliJ platform test fixtures, which
are slow, prone to hanging, and prove nothing about documentation.

## Two bodies of documentation

They have different audiences, different depths and different gates, but one voice. A
reader moving from a generated API page to the site must not notice a change of author.

| | JavaDoc | The documentation site |
| --- | --- | --- |
| Lives in | `src/main/java` of the seven published modules | `Writerside/` |
| Audience | somebody holding a signature | somebody who hit a problem or is about to write something |
| Style contract | `build-config/reweave/STYLE.md` | `build-config/docsite/STYLE.md` |
| Work breakdown | `build-config/reweave/UNITS.tsv` | `build-config/docsite/PAGES.tsv` |
| Pipeline | the `reweave` skill | the `docsite` skill |
| Gate | `mvn -B -o verify` | `python3 build-config/docsite/check-docs.py --build` |

## The rule both run under

**The past is not evidence.**

Do not read git history, earlier revisions, deleted files, stashes, commit messages, or any
document describing how the project used to work. Do not run `git log`, `git show`,
`git diff` against an older revision, or any equivalent, to decide what a type does or why.
All of it documents a state that no longer exists, and text anchored to it reads exactly
like text anchored to the current state — which is the failure this rule exists to prevent.

The current source is the only source of truth. Where behaviour is not visible in it — a
measured timing, a JVM flag interaction — it is established by reading the code and its
tests, or measured again now, or omitted. It is never recovered from an older version of
the text.

This applies to both bodies of documentation and to any agent working on either.

---

# JavaDoc policy

## Scope

`JavadocCoverageTest` scans `src/main/java` of these seven modules:

`api`, `engine`, `runtime`, `agent`, `processor`, `testkit`, `maven-plugin`

Within that scope document every type, every nested type, and every field, constant,
constructor and method — regardless of visibility, and including `@Override` methods. The
engine is held to the same standard as the API; `internal` packages are not an exception.
What differs is the audience — API JavaDoc explains how to use a type, engine JavaDoc
explains why the code has the shape it has — not whether the text exists.

Required but **not** machine-checked: the 28 `package-info.java` files, test sources, the
`aether-weaver-ide` plugin, and any sample project. `JavadocCoverageTest` skips
`package-info.java` by filename.

## Mandatory tags

- `@author Erik Pförtner` on every type
- `@since 0.1.0` on every type

Members must **not** carry `@since`. A member cannot predate its declaring type, so a
member-level `@since` states something true by construction and dilutes the tag where it
matters. `JavadocCoverageTest.sinceOnlyOnTypes` fails on it.

## Style

Professional, precise, JDK-level English. Describe the code and only the code: no narrating
your own process, no weighing rejected alternatives, no addressing the reader, no
commentary on the state of the documentation. No emoji and no decorative Unicode.

Where behaviour is surprising, state what it is. Explain why only where the code itself
shows the reason.

The form rules — first sentence as an extracted summary, third person, tag order, `{@code}`
over `<code>`, `<p>` unclosed — are in `build-config/reweave/STYLE.md` with three worked
exemplars. That file is the standard.

## Build constraints

Each of these fails `mvn verify`. They are not style preferences.

- **Line length 120.** Checkstyle `LineLength` counts JavaDoc lines like any other. The
  only exemption is a line matching `^\s*\*.*https?://`.
- **A type used only in JavaDoc still needs its import.** Checkstyle `UnusedImports` counts
  a `{@link Foo}` reference as a use. `AvoidStarImport` and `RedundantImport` apply too.
- **Never add an import to satisfy a `{@link}` across a module boundary.**
  `ProjectStructureTest` reads import lines to enforce the architecture. Use a fully
  qualified `{@link de.splatgames.aether.weaver...}`; it needs no import and breaks no rule.
- **A `{@link}` on a `public` or `protected` declaration may not name a package-private
  type of the same package.** Published pages are generated at protected visibility, so the
  link resolves for doclint and renders as dead text for the reader. Use `{@code}` instead.
  `JavadocStyleTest` fails on it. The same link inside a `private` member's comment is fine.
- **Every `AW####` named in JavaDoc must be a code `DiagnosticCode` declares.**
  `JavadocStyleTest` checks this.
- `JavadocStyleTest` also fails on emoji, first person, self-reference, and text describing
  a state the project has left.

## `/**` that is not a comment

A few places hold `/**` as data rather than as documentation: the generator in
`aether-weaver-ide/.../generate/AddHandlerHandler.java` that emits JavaDoc, the text blocks
in `aether-weaver-ide/.../augment/ExtensionAugmentTest.java`, and assertions that check
generated output for it. Editing them as though they were documentation breaks tests.

---

# Documentation site policy

## Where it lives

`Writerside/` is a [Writerside](https://www.jetbrains.com/writerside/) help module with one
published instance, `aw`. It is not part of the Maven reactor; `mvn verify` neither builds
nor checks it. `.github/workflows/docs.yml` builds it in the pinned JetBrains Docker image,
runs the builder's report through the checker and deploys to GitHub Pages.
`Writerside/README.md` describes the module's own layout and what is deliberately not
configured.

## Topics are not flat

Every page lives in its section's directory under `Writerside/topics/`. The section is not a
filing decision; it decides what the page is allowed to contain, and `PAGES.tsv` fixes it
before the page is written.

| Directory | Holds | Never holds |
| --- | --- | --- |
| `start/` | the path from an empty pom to a class a weave has modified | anything a reader can skip |
| `concepts/` | how the framework works and why it behaves that way | instructions, and configuration to copy |
| `guides/` | one task per page, with the whole configuration it needs | exhaustive parameter tables |
| `plugins/` | extending the engine through `api.spi`: what a `WeaverPlugin` may contribute, how one is loaded, and how it fails | anything a consumer who writes no plugin needs |
| `reference/` | the exact rule: every element, parameter, key and code | narrative and recommendation |
| `tooling/` | editor integration | the framework's own behaviour |
| `contributing/` | the repository, the build, the standards | anything a consumer needs |

**A fact lives in exactly one place** — the most specific section that can hold it. A rule
goes in `reference/`, the reason for the rule goes in `concepts/`, and a guide links to
both rather than restating either. The copy is what goes stale.

**Three separate things in this project are called a plugin**, and a page must never leave
which one ambiguous: a `WeaverPlugin` from `api.spi`, which `plugins/` covers and which the
navigation calls *Extending the engine*; `aether-weaver-maven-plugin`, the build plugin,
which belongs to `guides/` and `reference/`; and the IntelliJ IDEA plugin, which is
`tooling/`. Where the word appears unqualified, qualify it.

Writerside identifies a topic by its **bare file name** whatever directory it sits in, so
file names are globally unique across sections and a `toc-element` never carries a path.

**A page may be nested under another page**, and `PAGES.tsv`'s `parent` column is the only
place that is decided; the gate fails if `aw.tree` disagrees. A parent is always a page and
never a heading — a `toc-element` with a title and no topic is a node nothing happens when
you click, which is what the section hubs were written to fix. Nest only where the child is
a deepening of the parent rather than its sibling: three places earn it across the whole
site, and a section of equal-ranking pages stays flat.

## The four kinds

`PAGES.tsv` gives every page a `kind` and the kind decides what it must contain: `explain`,
`howto`, `reference`, `hub`. What each requires, and the form rules — the opening sequence,
`<tldr>` of at most three standalone facts, when a table rather than a `<deflist>`, code
samples pulled from files rather than pasted, versions through `%version%` rather than
written out — are in `build-config/docsite/STYLE.md` with three worked exemplars. That file
is the standard.

## Gate

```
python3 build-config/docsite/check-docs.py            # project rules, about a second
python3 build-config/docsite/check-docs.py --build    # and the real builder, about a minute
```

Exit 0 or the page is not ready.

The plain run checks the project's own rules: that a page is where `PAGES.tsv` says, that
`PAGES.tsv` and the repository agree, that a page is claimed before it is written, that a
topic no tree names is a mistake rather than a draft, that a written page is reachable from
another page, and that a version is written as `%version%` rather than spelled out.

**A link to `tba.topic` is a promise, and `PAGES.tsv` records who keeps it.** A sentence
promises the page in its own words — *the annotation reference*, not *Annotations* — so the
`aliases` column carries those words for the page that will answer them. The gate rejects a
promise no row claims, and repoints nothing silently: once that page exists, every promise
naming it fails the gate until the link points at it.

**Several pages may be written at once**, and the only thing concurrent writers may not
share is `Writerside/aw.tree` — implementers editing it in parallel overwrite each other, so
whoever launches them adds every `toc-element` afterwards.

`--build` runs the **Writerside builder, which is installed in this container** as
`writerside`, and fails on any error or warning in its report: 180 inspections covering
dead links and anchors, the tree, topic ids, duplicate file names, undefined variables,
undeclared `seealso` categories, missing images, unreadable code snippet sources,
starting-page card rules, and every value in `buildprofiles.xml`. That is the real gate.

**The builder caches and the cache does not invalidate.** Run headlessly a second time it
will rebuild the previous run's content and report it clean. The gate gives every run an
IntelliJ system path of its own and deletes it afterwards, which costs a full index each
time. Never work around that: a gate that validates a stale copy is worse than no gate.

---

# Process

Both bodies of documentation are written the same way, and the split is the point:
**implementers write, separate adversarial reviewers verify, a fixer applies findings and
escalates rather than overruling.** An agent that can talk its way past a reviewer removes
the only thing standing between this project and a large volume of plausible text.

A reviewer who cannot point at the line of code backing a sentence rejects that sentence.
Plausible and unverifiable is the failure mode this process exists to catch.

Reviewers are given neither the implementer's reasoning nor, on the site, the researcher's
dossier. A reviewer who reads why a sentence was written tends to agree with it.

## Prohibitions for every agent

- Never `git stash`, `git reset`, or `--force`.
- Never delete, skip or weaken a test to make a gate pass. If a gate fails for a reason
  that is not the work, stop and say so.
- Never change code to suit a comment or a page.
- Never read git history to establish what the code does. See **the past is not evidence**.

## When to stop and ask

The source contradicts itself; behaviour cannot be established from the code and its tests;
a reviewer and the fixer disagree; a gate fails for a reason that is not the work; a unit or
page needs more than two review rounds.

Say what was found and stop. A guess written into a specification is worse than an
unfinished unit, because the next reader cannot tell it from a fact.
