---
name: reweave
description: Run one unit of the JavaDoc rebuild end to end - pick it from UNITS.tsv, document it, put it through the gates and two adversarial reviews, and commit. Use when asked to document a unit, to continue the rebuild, to run the next unit, or when given a unit id such as L1-api-api-manifest.
---

# Reweave: one unit, start to commit

The documentation of this project is being rebuilt from the source, one unit at a time.
This skill runs a single unit through the whole cycle. It does not run two.

## Before anything else

Read these two files. They are the contract, and every agent below is measured against
them:

- `CLAUDE.md` — scope, mandatory tags, and the prohibitions
- `build-config/reweave/STYLE.md` — the form, the depth expected per tier, and three
  worked exemplars

**The single rule that is easiest to break by accident:** the past is not evidence. No
`git log`, no `git show`, no `git diff` against an older revision, no deleted file, no
commit message. The previous documentation described a state that no longer exists and
was removed on purpose. Everything is written from the source in front of you.

## Picking the unit

`build-config/reweave/UNITS.tsv` is the work breakdown and the resume point. Columns:

| Column | Meaning |
| --- | --- |
| `unit` | the identifier, e.g. `L1-api-api-manifest` |
| `layer` | 1 api, 2 engine, 3 drivers, 4 tests, 5 IDE plugin, 6 package-info |
| `module` | the Maven or Gradle module |
| `package` | one package, or several joined by `; ` when small siblings were merged |
| `files` | how many |
| `state` | `todo`, `doing`, or `done` |
| `commit` | the commit that completed it |
| `paths` | the explicit file list, `;` separated — **this is the assignment** |

If the user named a unit, use it. Otherwise take the first `todo` row in the lowest layer
that still has one; layers run in order, because terminology flows downhill and the API
settles the vocabulary the engine then uses.

Set `state` to `doing` before starting and write the row back. If a row is already
`doing`, a previous run stopped part way: check whether the files carry documentation,
and either finish that unit or reset it to `todo`. Never start a second unit beside it.

## The cycle

Run these in order. Each step is a subagent except the gates.

1. **`reweave-implementer`** (Opus) writes the documentation for the unit's files.
2. **Gates.** Run them yourself, below. A unit that fails a machine check does not go to a
   reviewer — that would spend two Opus-grade reviews on something `grep` already knows.
3. **`reweave-reviewer-correctness`** (Opus) and **`reweave-reviewer-style`** (Sonnet).
   Launch both; they are independent. Neither is given the implementer's reasoning, and
   that is deliberate: a reviewer who reads why a sentence was written tends to agree
   with it.
4. **`reweave-fixer`** (Sonnet) applies every finding. A finding the fixer disagrees with
   is reported back to the user, not overruled.
5. **Gates again**, then commit.

Repeat 3–5 only if the fixer changed something a reviewer had not already seen. Two
review rounds is the ceiling; a third means the unit was too large or the source is
genuinely unclear, and both are worth stopping to say.

## The gates

For a unit in a Maven module:

```
mvn -B -o verify
```

That runs Checkstyle (120 columns, unused imports, module boundaries), the architecture
tests, `JavadocStyleTest`, and the Javadoc build with `doclint=all,-missing`, which is the
only thing that resolves a `{@link}`.

For a unit in `aether-weaver-ide` (layer 5), which is a separate Gradle build:

```
cd aether-weaver-ide/aether-weaver-idea && ./gradlew checkstyleMain checkstyleTest checkstyleSample javadoc
```

Do not run `./gradlew check` there — it starts the IntelliJ platform test fixtures, which
are slow and prone to hanging, and they prove nothing about documentation.

**Completeness is gated.** All three `JavadocCoverageTest` cases are live: every type
carries JavaDoc with `@since`, every field, method and constructor carries JavaDoc, and
`@since` appears only on types. `reweave-reviewer-style` still reads for it, but a gap now
fails `mvn verify` before a reviewer sees it.

## Committing

One commit per unit, so a bad unit is one revert. Subject in the imperative with a closing
period, matching the log; body says what the unit covered and anything the source could
not settle. Name the unit id in the body.

Then set `state` to `done` and record the short commit hash in UNITS.tsv, and commit that
too — the file is the resume point, and a stale row is worse than no row.

Never `git stash`, never `git reset`, never `--force`.

## When to stop and ask

- The source contradicts itself, or a member's behaviour cannot be established from the
  code and its tests.
- A reviewer and the fixer disagree.
- A gate fails for a reason that is not the documentation.
- The unit turns out to need more than two review rounds.

Say what was found and stop. A guess written into a specification is worse than an
unfinished unit, because the next reader cannot tell it from a fact.
