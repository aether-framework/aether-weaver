---
name: docsite
description: Write one page of the Aether Weaver documentation site end to end - pick it from PAGES.tsv, research it from the source, write it, put it through the gate and two adversarial reviews, and commit. Use when asked to write a documentation page, to continue the site, to run the next page, or when given a page id such as C-selectors.
---

# Docsite: one page, start to commit

The documentation site in `Writerside/` is written from the source, one page at a time.
This skill runs a single page through the whole cycle. It does not run two.

## Before anything else

Read these two files. They are the contract, and every agent below is measured against
them:

- `CLAUDE.md` — scope, the section contract, and the prohibitions
- `build-config/docsite/STYLE.md`, and with it `build-config/docsite/REFERENCE.md`, which is measured from JetBrains' own published help: their median sentence is 9 words and `you` appears 117 times per 10,000. Write to the reader — what goes where, the four page kinds, the form rules
  and the exemplars

**The single rule that is easiest to break by accident:** the past is not evidence. No
`git log`, no `git show`, no `git diff` against an older revision, no deleted file, no
commit message. An earlier scaffold of this site was deleted on purpose because outlines in
a repository read like documentation. Everything is written from the source in front of
you.

## Picking the page

`build-config/docsite/PAGES.tsv` is the work breakdown and the resume point. Columns:

| Column | Meaning |
| --- | --- |
| `page` | the identifier, e.g. `C-selectors` |
| `section` | `start`, `concepts`, `guides`, `reference`, `tooling`, `contributing`, `site` |
| `order` | position within the section, and the order the tree lists it in |
| `file` | path under `Writerside/topics/` — **this is where the file goes** |
| `kind` | `hub`, `explain`, `howto`, `reference` |
| `title` | the H1 and the topic title |
| `state` | `todo`, `doing`, or `done` |
| `commit` | the commit that completed it |
| `sources` | `;` separated repository paths that back the page — the researcher's starting set |
| `parent` | the page this one is nested under in the tree, or empty — the only place nesting is decided |
| `aliases` | `;` separated wordings other pages use when they promise this one through `tba.topic` |

If the user named a page, use it. Otherwise take the first `todo` row by `section` in the
order the table lists them and then by `order`. Sections run in that order because
terminology flows downhill: `start` fixes the vocabulary, `concepts` settles the model, and
`reference` can then be terse because the model is already established.

**A `hub` row is not eligible while any page in its section is still `todo` or `doing`.**
Its cards link to those pages, and the gate rejects a card whose target does not exist.
Skip it and take the next row.

Set `state` to `doing` before starting and write the row back. A row already `doing` from a
previous run that stopped part way is finished or reset to `todo` with its file deleted; the
gate names every claimed row so an abandoned one is visible.

**Several pages may run at once.** Claim every row first, launch one implementer per page,
and tell each of them not to touch `Writerside/aw.tree` — parallel edits to it overwrite
each other. Add every `toc-element` yourself once they are all back. Two further things are
yours when running in parallel: the implementers cannot see each other's pages, so the
duplicate-fact rule and the shared vocabulary are checked only when the gate runs over all
of them, and a card on the section hub is repointed once rather than per page.

## The cycle

Run these in order. Each step is a subagent except the gate.

1. **`docsite-researcher`** (Opus) establishes what the page may say and writes
   `build-config/docsite/research/<page>.md`. Anchored facts only.
2. **`docsite-implementer`** (Opus) writes the page into its section directory, adds the
   `toc-element` to `aw.tree`, and runs the gate.
3. **Gate.** Run it yourself, below. A page that fails a machine check does not go to a
   reviewer — that would spend an Opus-grade review on something a regular expression
   already knows.
4. **`docsite-reviewer-correctness`** (Opus) asks whether every sentence is true.
   **`docsite-reviewer-clarity`** (Sonnet) asks whether a developer with a problem can use
   the page at all. Launch both; they are independent. Neither is given the implementer's
   reasoning **or the dossier**, and that is deliberate: the dossier is anchored, so a
   reviewer who reads it reviews the dossier instead of the page.

   The clarity seat replaced a style reviewer that checked conformance. The gate now applies
   the form rules mechanically, and conformance was never what was wrong: seven pages passed
   both old reviewers and were rejected as unreadable. A page that is true and unusable has
   failed, and this is the seat that says so.
5. **`docsite-fixer`** (Sonnet) applies every finding. A finding the fixer disagrees with is
   reported back to the user, not overruled. The fixer *is* given the dossier.
6. **Gate again**, then commit.

Repeat 4–6 only if the fixer changed something a reviewer had not already seen. Two review
rounds is the ceiling; a third means the page was scoped too widely or the source is
genuinely unclear, and both are worth stopping to say.

## The gate

```
python3 build-config/docsite/check-docs.py            # while iterating, about a second
python3 build-config/docsite/check-docs.py --build    # before every commit, about a minute
python3 build-config/docsite/check-docs.py --measure <page>   # where the page actually sits
```

Both checks must exit 0. **`--measure` is not a check** — it prints the page's figures
beside their limit and beside the reference corpus, and it is how the implementer, the
clarity reviewer and the fixer all judge whether a page merely passes or is actually good.
A page sitting on every ceiling passes. The plain run checks the project's own rules: the section a page sits in,
`PAGES.tsv` against the repository, one page `doing` at a time, orphan topics, literal
version strings.

The plain run also applies the readability limits — 25 words to a sentence with a median of
13 across the page, 75 to a prose paragraph, four consecutive prose paragraphs, and a code
block in a language this builder renders. One it does not know is dropped from the page
silently.

And it applies the whole-page budgets, which are the rules the local limits could not reach:
a page made of short compliant paragraphs is still a book. Prose on the page — 600 words on
a `howto`, 900 on an `explain`, 1200 on a `reference`. Chapters below the H1 — six, eight on
a `reference`. **At most 120 words of running text per heading, list item, table row, step,
picture or code block**, which is what lets a page be long without being a wall. **At least
one link per hundred words**, which is the only mechanical proof that
a detail was deferred rather than never considered. And a limit on how far the reader walks
before the page pays: 120 words to the sample on a `howto`, 160 to the diagram on an
`explain` page, 120 to the table on a `reference`. **The principle above all the others** in
`STYLE.md` is where these come from.

A diagram is `<code-block lang="mermaid">`, which this builder renders into a flowchart SVG. Lay the graph out top to bottom (`flowchart TB`): mermaid sizes itself from the graph, and a left-to-right chart is scaled into the 843px column with its labels. A hand-written SVG pair under `Writerside/images/` is the exception, for a drawing mermaid cannot express.

And it applies the layout rules, which are the shape of the page rather than the length of
its sentences: 90 words between the H1 and the first heading, a `<tldr>` of at most 40
words in two facts if the page has one at all, 320 words of prose under one heading, at least one diagram on an `explain` or
`howto` page, a `<procedure>` on a `howto`, a table or deflist on a `reference`, and on a
hub a `summary` plus a `badge` or `type` on every card with exactly two in `<spotlight>`.
**The shape of a page** in `STYLE.md` gives the figures these come from.

`--build` additionally runs the **Writerside builder, which is installed in this
container**, and fails on any error or warning in its report. That is the real gate: 180
inspections covering dead links and anchors, the tree, topic ids, duplicate file names,
undefined variables, undeclared `seealso` categories, missing images, unreadable code
snippet sources, starting-page card rules, and every value in `buildprofiles.xml`.

**A claim about the layout has to be photographed, not reasoned about.** The site is a
single-page application: the header, the tree and the footer are painted by the front end,
so the built HTML shows none of them. `build-config/docsite/shoot.sh <url> <prefix>
[--clip .footer]` renders the page in Chromium and writes a PNG. Look at it.

**Run `--build` before committing, not only at the end of a section.** The builder is the
only thing that reads the module the way the published site will.

The builder can also be invoked directly:

```
writerside Writerside/aw <output-dir>
```

but prefer the gate, because it does one thing the bare command does not. **The builder's
IntelliJ caches under `~/.cache/JetBrains` do not invalidate when a topic changes**: a
second run rebuilds the first run's content and reports it clean. The gate gives each run
a system path of its own and throws it away, which costs a full index and is the only
reason its verdict can be trusted. If you run `writerside` by hand and it reports a page
you know is broken as clean, that is what happened.

## Where the file goes

`PAGES.tsv` gives the path, and it is never flat in `topics/`: `concepts/selectors.topic`,
`guides/testing-woven-code.topic`, `reference/annotations.topic`. Every page is a `.topic`.
The tree refers to a topic by its **bare file name** whatever directory it sits in, which is
also why file names must be unique across every section.

Every section already has a hub page carrying its cards, so adding a page means adding a
child `<toc-element>` and a card on that section's hub — the hub is another page, so the
implementer reports that the card is needed rather than editing it.

**Where the row has a `parent`, the `toc-element` goes inside that parent's**, and the
parent has to be written open rather than self-closing. The gate checks it, so a batch
cannot flatten the tree by accident.

## Committing

One commit per page, so a bad page is one revert. Subject in the imperative with a closing
period, matching the log; body says what the page covers and anything the source could not
settle. Name the page id in the body.

The commit includes the page, the `aw.tree` change, and the dossier under
`build-config/docsite/research/` — the dossier is the evidence the page was written from
and is worth as much as the page.

Then set `state` to `done`, record the short commit hash in `PAGES.tsv`, and commit that
too — the file is the resume point, and a stale row is worse than no row.

Never `git stash`, never `git reset`, never `--force`.

## When to stop and ask

- The source contradicts itself, or the behaviour a page needs cannot be established from
  the code and its tests.
- A reviewer and the fixer disagree.
- The gate fails for a reason that is not the page.
- The page turns out to need more than two review rounds.
- The researcher's dossier comes back with more under **Could not establish** than under
  **Facts**. That is a page that cannot be written yet, not a page to write carefully.

Say what was found and stop. A guess written into documentation is worse than a missing
page, because the next reader cannot tell it from a fact.
