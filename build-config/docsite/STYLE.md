# Documentation site house style

The reference every implementer and reviewer is measured against. Read it together with
`CLAUDE.md`, which states the scope and the prohibitions. Where the two disagree,
`CLAUDE.md` wins.

## How to read this file

Every rule below carries one of two marks, and there is no third category.

- **[gate]** — `python3 build-config/docsite/check-docs.py` fails on it. It is not a matter
  of taste, not a matter for a reviewer, and not negotiable in review.
- **[review]** — a person judges it. It is here because it could not be reduced to a check,
  not because it is optional.

A rule stated in prose and enforced by nobody has now failed twice on this site: the first
seven pages were rejected by the reader after passing both reviewers, and the five that
replaced them passed every mechanical limit and came back from a clarity review with
twenty-two findings — prose restating the table beside it, a fact written on two pages, a
diagnostic code whose remedy was a hundred lines away, and vocabulary no published page
defines. Each of those four is now **[gate]**.

**When a rule can be moved from [review] to [gate], move it.** The check goes in
`check-docs.py`, its case goes in `selftest.py`, and the rule here gains the mark. The gate
runs `selftest.py` and fails if a rule stops behaving as specified, so a check cannot quietly
rot into a no-op.

## The rule this file exists to enforce

**A page that is true and unreadable has failed.**

The first seven pages of this site were written under a process that checked two things:
whether every sentence could be anchored to a line of code, and whether the page conformed
to the rules below it. Both passed. The pages were rejected anyway, because nobody could
read them: eighty-seven words to the paragraph, twenty-eight to the sentence, and across
seven pages not one diagram, not one screenshot, and two pages with no code sample at all.

Being right is the floor, not the goal. A reader arrives because something did not behave
as expected or because they are about to write something. The page has done its job when
they can act. Prose that is accurate and unusable wastes their time more efficiently than
prose that is wrong, because wrong prose at least gets corrected.

## The principle above all the others: leave it out

**Optimise for time to value. Every sentence a reader passes before they can act is a cost
they pay, and most of them buy nothing.**

The reader is not studying this framework. They have a question and something else to do.
They arrived from a search box, they will scan the page for the shape of an answer, and if
the answer is not visible they leave. Write for that person.

Three rules follow from it, and they are the same three whatever kind the page is.

1. **Answer first.** No approach, no framing, no "before we look at X". The first screen
   carries the answer — a snippet, a diagram, a table — and the prose explains what the
   reader is already looking at.
2. **Cut to one question.** A page answers the question its title asks, and nothing else.
   The related question is a different page.
3. **Defer through a link, never through silence.** A detail dropped from a page owes a
   link where it stood. *"Details of how the class loader is isolated are in [X]"* is one
   sentence and costs the reader nothing; the four paragraphs it replaces cost everyone who
   did not need them. A page that defers nothing has not been edited.

This is the principle the local limits could not reach. A sentence limit, a paragraph limit
and a chapter limit are all satisfied by a long page made of short compliant pieces — which
is exactly what this site produced. **[gate]** now counts the totals: words on the page,
chapters on the page, links per hundred words, words before the payload, and words of
running text per place the eye can stop.

### What the three kinds look like under it

**A `howto` — the reader has a task.** One or two sentences saying what this does and when,
the whole configuration or the whole class as one sample, and a short list of what to know
about it. Then stop. Anything conceptual is a clause with a link in it: *"Slices select
which instructions a weave applies to; [how selection works] covers the syntax."*

**An `explain` page — the reader wants the model.** The diagram comes first, before the
prose, and it carries the flow. Under it, three or four components in a `<deflist>` or three
or four short paragraphs — what each one does, one line each. Where a component is genuinely
deep, name it in a sentence and link out: *"Bytecode stamps let the runtime tell a woven
class from an original; [stamps] has the layout."* Never explain why the design was chosen
unless a reader cannot use the framework without knowing.

**A `reference` page — the reader needs the exact rule.** The table is the page. No
narrative run-up, no recommendation, no philosophy of build tools.

On diagnostic codes specifically: a reference page states **what the codes are for, why they
exist, and how to read a range** — `AW1xxx` is this, `AW2xxx` is that. It does not list every
code with a paragraph each. The reader arrived with one code and needs to know which family
it belongs to and where to look.

### Sentences this site does not publish

| Written | Why it goes |
| --- | --- |
| In this chapter you will learn how weaving works. | Announces the page instead of being it. **[gate]** |
| Before we look at the API, some background on bytecode. | The reader did not ask for background. |
| It is worth noting that the engine builds the plan once. | Then note it. Six words spent on saying that something is worth saying. **[gate]** |
| The design of the pipeline reflects a deliberate trade-off between... | Nobody with a failing build needs this. |

## Where the rules come from

Four sources, in order of authority.

1. **The source code.** A sentence here is a claim about a program, and the program is the
   only thing that can settle it. Anything that cannot be anchored to a line is not written.
2. **`python3 build-config/docsite/check-docs.py`.** Sentence length, paragraph length,
   wall-of-text runs and unrenderable code fences are checked mechanically. They are not
   matters of taste and not matters for a reviewer.
3. **The [Writerside markup reference][markup]** decides what the markup *is*.
4. **`build-config/reweave/STYLE.md`**, the JavaDoc house style, decides the register. One
   voice across both bodies of text.

[markup]: https://www.jetbrains.com/help/writerside/markup-reference.html

## What the gate enforces, so you do not have to argue about it

Twenty-three checks, and one measurement. Every number below came from a measurement or from a failure, and the
column that gives its origin is part of the rule: a limit whose reason is forgotten is a
limit somebody argues with.

**Passing is not the goal.** `check-docs.py --measure <page>` prints a page's figures beside
their limit *and* beside the reference corpus. A page sitting on every ceiling passes and
reads like a page sitting on every ceiling. Aim at the reference column.

### Readability

| Rule | Limit | Where the figure comes from |
| --- | --- | --- |
| Sentence length | 25 words | Just above the 90th percentile of the reference corpus, which is 21. |
| Median sentence on a page | 13 words | Their median is 9, their 75th percentile 13. Every sentence clearing the ceiling is not the same as the page reading like the reference. |
| Second person | expected, not banned | 117 `you` per 10,000 words in the reference. The old ban is the single biggest reason pages here read as machine-written. |
| A mermaid label | no character reference, no repeated word | `Target - the target` shipped on this site. |
| A mermaid node label | no `<br/>`, and no markup of any kind | Writerside parses the topic as XML and strips the tag before mermaid ever sees the diagram, so two lines are joined without a space. Measured: `validate phase` and `line width` rendered as `validate phaseline width` in the built SVG, and the source looked correct. Mermaid wraps a long label at its own box width; let it. |
| A mermaid edge label | only where the arrow is long enough to carry it | At 843px a `-->|label|` on a short connector is struck through by the connector itself. Fold what the edge says into the node it points at. |
| Prose paragraph | 75 words | The same reference runs two to four sentences a paragraph. The rejected pages ran to eighty-seven words. |
| Consecutive prose paragraphs | 4 | With no heading, list, table, code block, admonition or image between them. Shortening sentences does not satisfy it. |
| Prose under one heading | 320 words | The contents panel is generated from headings and is the only index the reader gets. A heading covering 500 words hides 500 words. |
| Between the H1 and the first heading | 90 words | The reference opens with one lead paragraph of 35 to 40 words and is then inside its first chapter. |
| `<tldr>`, if the page has one | 40 words, 2 facts | It renders as a filled box, and a box promises that what is in it is short. One reference page in six uses it at all. |

### Time to value — what the page costs the reader as a whole

Every limit under *Readability* is local. A page passes all of them and is still a book if
nothing counts the total, which is what happened. These five count the total.

**A page is allowed to be long.** What it is not allowed to be is padded, and padding is
caught by the density rule rather than by a smaller word count.

| Rule | Limit | Where the figure comes from |
| --- | --- | --- |
| Words of prose on a page | `howto` 600, `explain` 900, `reference` 1200, `hub` 300 | Counting running prose only — headings, lists, tables and code are skimmed to, not through — the reference corpus runs a median of 360 words a page and 854 at the 75th percentile, which is where these sit. |
| Words of running text per stopping point | 120 | A heading, list item, table row, definition, step, code block, picture or admonition. Over the 930 corpus pages above 150 words their median carries one every 38 words, their 90th percentile every 104, and 8% run past 120. This is the rule that lets a page be long. |
| Chapters below the H1 | `howto` and `explain` 6, `reference` 8, `hub` 3 | Their median page has two chapters and their 90th percentile seven. The first page written here had eight and 502 words. |
| Links per 100 words | at least 1.0, above 120 words | Their pages carry 1.8, and 97% of them link out at all. This is the rule that makes leaving something out possible: a detail dropped owes a link where it stood, or it was destroyed rather than deferred. |
| Words before the payload | `howto` 120, `explain` 160, `reference` 120 | The payload is the sample on a `howto`, the diagram on an `explain` page, the table on a `reference` page. Everything above it is what the reader pays to reach the thing they came for. |

### Layout, measured in a browser at the article's 843px

| Rule | Limit | Where the figure comes from |
| --- | --- | --- |
| Table columns | 3 | Every three-column table measured on this site fits the 843px column. |
| Table cell | 120 characters | Longer is a paragraph, and a paragraph in a cell either widens the table off the page or wraps into a column of single words. |
| Table width | 85 characters summed across the widest cell of each column | A column is as wide as its longest cell, and that costs 8.5 to 9.9px a character, measured. At the worst rate 85 characters is 841px. **A table inside this budget fits with no stylesheet at all** — which is the only kind of fitting that also holds in the editor's preview, where a stylesheet under `cfg/static` is not guaranteed to be applied. |
| Diagram viewBox | 700 units | An `<img>` is capped at 843px, so a wider drawing is scaled down and its labels with it. At 700 it renders at its own size and still fits a preview pane. |
| Smallest `font-size` in a diagram | 12 | With a viewBox of 700 that reaches the reader at 12px against 16px body text. |
| Smallest label as rendered | 9.5px | `font-size` × 843 ÷ viewBox width. Three diagrams were drawn in viewBoxes up to 1720 units wide and reached the reader at 5.4 to 6.6px. |
| A `.topic` element | defined by `topic.v2.xsd` | The schema is vendored under `build-config/docsite/schema/`. Required attributes are read out of it, so a schema update changes the check without anyone editing it. |
| A topic `id` | equals the file name | Writerside identifies a topic by its bare file name whatever directory it is in. |
| An image inlined in `custom.css` | digest matches its source | Two copies of one drawing drift silently otherwise. |

### Content

| Rule | Limit | Where the figure comes from |
| --- | --- | --- |
| Filler and hedging | a closed list | `simply`, `just`, `easily`, `of course`, `actually`, `obviously`, `note that`, `it is important to note`, `basically`, `essentially`, `very`, `really`, `quite`. Prose forbade these already; `actually` shipped anyway. |
| First person | none | `we`, `our`, `ours`, `us`, `i`, `my`, `mine`, `let's`. The documentation has no author speaking in it. |
| An opening that announces the page | rejected | `This page…`, `In this guide…`, `The following…`. The most valuable paragraph, spent on a label already in the title and the tree. |
| A diagnostic code | carries its remedy in the same block | Same paragraph, cell or definition. The remedy is an imperative, so its verb opens a clause: *a field the target does not declare* is not a remedy, *declare it `@Unique`* is. Where the source states none, write that. |
| The same 8 words of prose on two pages | rejected | A fact lives in exactly one place; the copy is what goes stale. Long enough that a shared technical phrase does not trip it, short enough to catch a restated rule. |

### Structure and wiring

| Rule | Limit | Where the figure comes from |
| --- | --- | --- |
| Code fence language | an allowlist | A fence this builder does not know is **dropped from the page silently** — no error, no warning, the content is gone. |
| Diagram on an `explain` or `howto` page | at least one | Seven pages shipped with none between them. |
| `<procedure>` on a `howto` page | at least one | Steps a reader follows are numbered steps. |
| Table or deflist on a `reference` page | at least one | A reference page enumerates. |
| Card on a hub | a `summary` and a `badge` or `type` | Without both it is a bare link and the reader cannot tell what the page gives them. |
| `<spotlight>` | exactly two cards | The builder renders two. A third is dropped. |
| A version | `%version%`, never a literal | One edit per release, and the gate keeps `writerside.cfg` and `versions.json` in step. |
| Topic ids, unique file names, the tree, links, anchors, orphans, `PAGES.tsv` | exact | Writerside identifies a topic by bare file name whatever directory it is in. |
| The rules themselves | `selftest.py` passes | A content rule only fires on a page that breaks it, and such a page never gets committed. Without the selftest the checks would be asserted rather than tested. |

Everything else in this file is **[review]**.

## Lead with the answer

**The lead paragraph is what the reader came for.** Not the history of the model, not a
definition of a term they have already met, not a paragraph establishing the framework's
philosophy. One paragraph, and then a heading.

| Kind | The lead paragraph gives them |
| --- | --- |
| `explain` | The one-sentence version of the mechanism, then the picture of it |
| `howto` | What they will have when they are done |
| `reference` | Nothing — go straight to the table |
| `hub` | Nothing beyond `<description>` |

A page that opens by explaining what it is about has spent its most valuable paragraph on
the reader's least valuable question.

## Show it before you describe it

Anything with a shape gets shown. An order, a flow, a state change, a decision between
options, a before and after — these are pictures and tables, and prose about them is a
transcription of a picture the reader cannot see.

- **A sequence of stages** is a diagram. `Writerside/images/*.svg`, `<img>`.
- **A comparison across three or more things** is a `<table style="header-row">`, of at most
  four columns. Never prose.
- **A rule with exceptions** is a `<deflist>` or a table, not a sentence with three
  subordinate clauses.
- **A decision** is a table whose first column is the reader's situation.
- **Anything a reader would paste** is a code block from a file, `{ src="File.java" }`.

Every `explain` page longer than about two screens carries at least one diagram or table.
If you cannot find one, the page is probably explaining several things and should say so.

## The shape of a page

The reference this site is measured against is JetBrains' own product help, which is built
with this same builder. The difference between it and the seven pages that were rejected is
not register and it is not accuracy. It is how much of the page is not prose.

Counted from the built HTML of three of their pages and of ours:

| | Their tutorial page | Their reference page | Our worst page |
| --- | --- | --- | --- |
| Words | 1,000 | 2,100 | 1,300 |
| Headings | 7 | 24 | 6 |
| Procedures | 4 | 22 | 0 |
| Lists | 4 | 26 | 0 |
| Figures | 13 | 7 | 1 |
| Tab groups | 0 | 4 | 0 |

There is a second difference the table does not show. Across all 1,296 of their pages the
median carries **12 links**, and 97% link out at all. Ours carried three in 502 words.

**A page of ours is allowed to be shorter than theirs. It is not allowed to be flatter, and
it is not allowed to be a dead end.**
The reader of a reference site does not read it; they scan it, stop once, and leave. Every
heading, list marker, table rule, step number and picture is a place the eye can stop. A
paragraph is not.

Three habits produce most of the difference, and none of them is a writing skill:

1. **A chapter reaching 320 words is too long, not short of a heading.** Cutting it in two
   gives the reader two chapters to read instead of one. Take the material out: to a list, to
   a table, or to the page that owns it, with a link where it stood. The chapter cap is four
   on an `explain` or `howto` page precisely so that splitting cannot be the answer.
2. **A sentence with a comma-separated series in it is a list.** Three things joined by
   "and" in running text are three list items, and the reader can count them.
3. **Anything done in order is a `<procedure>`, even inside an `explain` page.** Steps get
   numbers, and a number is something to come back to.

## Labels, and saying which release a thing belongs to

`labels.list` declares four labels and no page uses one. A label renders beside the heading,
carries a tooltip, and appears in the navigation — which is where a caveat belongs, because
prose saying "this may change" goes stale where nobody sees it.

| Label | On a topic or chapter that |
| --- | --- |
| `<secondary-label ref="experimental"/>` | Documents `api.experimental`. |
| `<secondary-label ref="internal"/>` | Documents engine internals, covered by no promise. |
| `<secondary-label ref="0.1.0"/>` | Names something introduced in a specific release. |
| `<secondary-label ref="wip"/>` | Is a stub. Never on a page in `done`. |

## Hubs are cards, not link lists

A hub page is a `.topic` holding `<section-starting-page>`, and it renders as three groups
with different weights. Get the groups wrong and the section has no shape.

| Group | Holds | Each entry carries |
| --- | --- | --- |
| `<spotlight>` | Exactly two: where a reader of this section starts, and the one page most of them actually want | `type=` |
| `<primary>` | Four to eight: the section's pages, in reading order | `badge=` |
| `<secondary>` | Two to four: what is next to the section — the source, the artefacts, the tracker | `badge=` |

Every entry needs a `summary`, and the summary says **what the reader gets**, not what the
page is about. `"Three artifacts and one plugin execution"` is a summary. `"Explains how to
install Aether Weaver"` is a title written twice.

Badges are a fixed set of built-in icons; the gate rejects a name outside it. Pick the one
that stands for the work the page asks for — `install`, `development`, `tools`, `idea`,
`learn`, `start`, `bug`, `library`, `key`, `open-source` — not the one that decorates best.

A page's own `<link-summary>` sets what a hover on a link to it shows; `<card-summary>` sets
what a card for it says when the hub gives no `summary`. Prefer writing the summary on the
hub, where the ordering is visible and two summaries can be made to differ.

## Structural devices this site has and did not use

The first seven pages used prose, tables, deflists and admonitions. Everything below is
available, supported by this builder, and was left on the shelf.

| Device | Use it for |
| --- | --- |
| `<code-block lang="mermaid">` | Any flow, order or state change. The builder renders it. `flowchart TB`, never `LR` — the gate measures the rendered width against the 843px column. |
| `<img src="x.svg"/>` | The drawing mermaid cannot express. A pair, `x.svg` and `x_dark.svg`. |
| `<procedure>` with `<step>` | Anything the reader does in order, even on an `explain` page's worked example |
| `<tabs>` / `<tab>` | The same task under Maven, the agent and the class loader |
| `<tip>` | The shortcut a reader would otherwise miss. Rare, but not forbidden |
| `<snippet>` / `<include>` | A block that belongs on two pages, so the copy cannot drift |
| `cfg/glossary.xml` + `<tooltip term="">` | A term whose definition a reader should not have to leave the page for |
| `<list>` / `-` | Three or more things joined by "and" in a sentence |
| `<primary-label>` / `<secondary-label>` | "Experimental", "internal", "new in 0.1.0" — beside the heading, never in prose |
| `<shortcut>` | A key combination, once the tooling section documents the IDE plugin |
| `<control>`, `<ui-path>`, `<path>` | A button, a menu path, a file path — they render distinctly and search better than `<code>` |
| `%version%`, `%group%` and friends | Every version, coordinate and URL |

## The four kinds

`PAGES.tsv` gives every page a `kind`, and the kind decides what it must contain.

### `explain`

*What is this and why does it behave that way.* Declarative, third person, no instructions.

Required: `<show-structure>`, a lead paragraph, a `<seealso>`, **and at least one diagram
or table**. Chapters follow the shape of the thing, not the shape of the source tree.

State the rule, then its limit. But a rule under three qualifications has stopped being a
rule: if a limit needs more than a sentence, the rule and its limits are a table.

### `howto`

*How do I do this.* Imperative, addressed to the reader — `you` and `your` belong here more
than anywhere else on the site.

Required: `<show-structure>`, at least one `<procedure>`, a configuration complete enough
to run, and a `<seealso>`. The prerequisite goes in the `<tldr>`; the outcome goes in the
lead paragraph, where the reader is already looking.

An ellipsis in the middle of a snippet is a finding. So is a step that says what to do
without saying how the reader knows it worked.

### `reference`

*What exactly.* No narrative. The reader arrives knowing what they want and leaves as soon
as they have it.

Required: `<show-structure>` and one table or definition list per enumerated thing, every
cell filled. Exhaustive or explicitly bounded. No lead paragraph worth the name — a
reference page that opens with prose has misjudged why the reader is there.

### `hub`

A `.topic` file with `<section-starting-page>`. Cards only, and every card carries a
`summary` and an icon. The groups and their sizes are in **Hubs are cards, not link lists**
above; the gate checks all of it.

Written after every page it links to.

## What goes where

| Directory | Holds | Never holds |
| --- | --- | --- |
| `topics/start/` | The path from an empty pom to a class a weave has modified | Anything a reader can skip; anything assuming a concepts page |
| `topics/concepts/` | How the framework works, and why | Instructions, and configuration to copy |
| `topics/guides/` | One task per page, with the whole configuration it needs | Exhaustive parameter tables |
| `topics/reference/` | The exact rule: every element, parameter, key and code | Narrative and recommendation |
| `topics/tooling/` | Editor integration | The framework's own behaviour |
| `topics/contributing/` | The repository, the build, the standards | Anything a consumer needs |

**A fact lives in exactly one place** — the most specific section that can hold it. A rule
goes in `reference/`, the reason for it in `concepts/`, and a guide links to both.

A concept page **names** the diagnostic codes and links to the reference page that lists
them. It does not tabulate them.

## Every page is a `.topic`

Markdown is not used on this site. A `.topic` is semantic XML against a published schema, and
it reaches the builder's whole element set — 86 elements — where a Markdown page reaches it
only by injecting XML, which breaks on a blank line in a way nothing reports.

The schema is vendored at `build-config/docsite/schema/topic.v2.xsd` and it is the authority.
The `topic-files` skill carries the element set, the four elements that require an attribute,
and the traps. Read it before writing a page.

## Form

**One page is one file, and the file name is the topic id.** File names are unique across
every section, because Writerside identifies a topic by its bare name.

**Every page is a `.topic`.** See **Every page is a `.topic`** above; what follows applies
to the Markdown that can still appear inside `<tab>`, `<snippet>` and `<def>`.

**A code sample from a file is written two different ways, and using the wrong one loses the
sample.** On the element it is `src=`. In Markdown it is an empty fence followed by an
attribute block, which does not run inside injected XML — a `<step>`, a `<tab>`, a `<def>` —
so there the source goes on the element:

```markdown
```java
```
{ src="Greeting.java" }

<step><p>Write the target.</p>
<code-block lang="java" src="Greeting.java"/></step>
```

The wrong form builds as an empty `<code-block>` and the builder fails it with `CDE006`,
which is the good case. The bad case is the attribute block rendering as literal text.

**Injected XML must be a continuous block.** A blank line ends it — *except* inside `<tab>`,
`<snippet>` and `<def>`, where a blank line is exactly how you get Markdown to run. Six
`<def>` elements shipped with literal backticks in the built site because of this. Inside a
`<warning>` or `<note>`, Markdown does not run at all: write `<code>`, not backticks.

**Every content page opens the same way**: H1, labels if any,
`<show-structure for="chapter,procedure" depth="2"/>`, `<web-summary>` if the first
paragraph is a poor snippet, `<tldr>` only if the page has a precondition, then one lead
paragraph, then the first `##`.

**`<tldr>` is optional and usually absent.** Counted across six pages of the reference this
site is modelled on, one uses it. Ours used it on every page at 72 to 96 words in three
facts, which put a block of grey text between the reader and the first sentence — the box
does not make the text shorter, it makes it look like something worth reading twice.

Use it for what a reader must know **before the first paragraph** and cannot find out by
reading on: a prerequisite page, a driver the page assumes, a limit on where the page
applies. Two facts, forty words, both true away from the page.

```markdown
<tldr>
<p>Requires <a href="install.md">Install</a>. Maven only; the runtime drivers are in
<a href="choose-a-driver.md">Choose a driver</a>.</p>
</tldr>
```

Everything else that used to go there belongs in the chapter that needs it, or nowhere.

**Admonitions are for what does not fit the flow.** `<warning>` for what costs the reader,
`<note>` for a limitation. Three on one page means the page is structured wrong.

**Enumerations over five entries are a `<table style="header-row">`.** Under five is a
`<deflist>`: `full` when a definition runs to a paragraph, `medium` for members and methods,
`narrow` for options and flags.

**A table is 843 pixels wide and no wider.** Writerside sizes a table to its content and lets
the wrapper scroll, so a table that does not fit is not wrapped — it is cut off, and the reader
sees the first two columns of six with a scroll arrow. `cfg/static/custom.css` caps the width,
which rescues a table that is merely wide; nothing rescues one that is the wrong shape. Each
column costs about nine pixels per character of its longest cell, so four columns of short cells
fit and five columns of sentences do not.

A comparison too wide for that is still a comparison. Split it into two tables that share their
first column — *what it reaches* and *what it never sees* in one, *where the output goes* in the
next — rather than turning a table the reader can scan into a deflist they have to read. Turn it
into a `<deflist>` when the rows stop being comparable: when each entry needs a paragraph, the
table was carrying prose.

**A diagram is laid out for 843 pixels.** An `<img>` is capped at the article width, so a
drawing in a viewBox 1720 units wide is scaled to less than half size and its labels with it.
Lay the drawing out in a viewBox no wider than about 880 units — stack what was side by side,
and let the diagram be tall rather than wide. Enlarging the type inside a wide viewBox does not
work: the boxes were sized for the smaller type, and the text overflows them.

**Diagnostic codes are written `AW1234` in `<code>`, always with what to do instead.**

**`<seealso>` sorts into the categories `c.list` declares** — `start`, `concepts`, `guides`,
`reference`, `external` — and links only to pages that exist.

## Register

**The reader is a working developer with the code available.** [review] Not a beginner, not
being sold anything.

**No filler and no hedging.** [gate] The list is closed and it is in the table above. If a
sentence needs `simply` to sound reasonable, the sentence is claiming something the code
does not support.

**No first person.** [gate] The documentation has no author speaking in it.

**Write to the reader.** [review] `you` and `your` are the normal register of this site, not
a lapse. This file said the opposite until it was measured: in JetBrains' own help — the
documentation this site claims to be modelled on — `you` appears 117 times per 10,000 words
and `your` 46. The reader is in almost every paragraph of it. Banning the reader produced
exactly the voice the ban was meant to prevent, because a sentence that avoids them has to
name a mechanism instead, and that is longer, colder and harder to act on.

*IntelliJ IDEA lets you enable various accessibility features to accommodate your needs.*
That is the shape: what it lets you do, then how.

**Aim at their median, not at the ceiling.** [gate] Their median sentence is **9 words**;
p90 is 21. This site's hard limit is 25 words and its median limit is 13 — their 75th
percentile. A page where every sentence sits just under the ceiling passes the sentence rule
and fails the median one, which is the point: it does not read like the reference.

The figures, and how to regenerate them, are in `build-config/docsite/REFERENCE.md`.

**State what happens, then what happens when it does not.** [review] Every page documenting
something that can fail names the failure and its code — and the code carries its remedy in
the same block [gate].

**Surprising behaviour is stated plainly.** [review] Explain *why* only where the code shows
the reason.

**Nothing about the past, nothing about the future.** [review] Not `has been redesigned`,
not `in a future release`. No emoji and no decorative Unicode.

**Define a term before using it.** [review] `cfg/glossary.xml` is empty. Every term in it was
written under the process that produced the rejected pages, so none of them survived, and the
file fills again one entry at a time: **a term enters the glossary in the same commit as the
page that first uses it**, anchored to the source like any other claim. Until it is there, a
page either defines the term at first use or does not use it. A `start/` page that uses
*extension method* as settled vocabulary while the page defining it is still `todo` is asking
the reader to know something no published page teaches.

**One fact, one place.** [gate] The most specific section that can hold it: the rule in
`reference/`, the reason in `concepts/`, and a guide links to both. Restating either is how
two pages come to disagree.

**Show it, then do not describe it.** [review] A paragraph after a table or a diagram that
says again what it just showed is the commonest finding on this site. The picture or the
table is the statement; what follows adds the thing neither could carry.

---

# The exemplars

Three pages, one per kind, each one the whole page rather than its opening. **The shape is
normative; the facts in them are not.** Do not carry a class name, a code or a limit out of
an exemplar into a page — check it in the source.

Each one is short because the standard is short. If yours is twice this long, the question
is not how to trim it, it is which half is a different page.

## 1. A `howto` — the reader has a task

*Wie erstelle ich eine Injection.* Say what it does, show the whole thing, list what to know,
stop. The concept behind it is a clause with a link in it.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE topic SYSTEM "https://resources.jetbrains.com/writerside/1.0/xhtml-entities.dtd">
<topic xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
       xsi:noNamespaceSchemaLocation="https://resources.jetbrains.com/writerside/1.0/topic.v2.xsd"
       id="inject-into-a-method" title="Inject into a method">

    <show-structure for="chapter,procedure" depth="2"/>

    <tldr>
        <p>Needs <a href="install.topic">Install</a>.</p>
    </tldr>

    <p>
        An injection runs your code inside somebody else's method. You write an ordinary
        static method, mark it <code>@Inject</code>, and name where it goes.
    </p>

    <code-block lang="java" src="InjectExample.java" include-symbol="onDamage"/>

    <p>Three things decide whether it applies:</p>

    <list>
        <li><p><code>method</code> names the target, with its descriptor when the class
            overloads it.</p></li>
        <li><p><code>at</code> is the point inside the method. <code>HEAD</code> and
            <code>RETURN</code> need nothing further; the rest take a slice, and
            <a href="slices.topic">Slices</a> covers the syntax.</p></li>
        <li><p>A signature that does not match the target is
            <a href="diagnostics.topic">AW1102</a>, and the build stops.</p></li>
    </list>

    <tip>
        <p>
            Cancelling the target method is a separate return type, not a flag —
            <a href="cancellable-injections.topic">Cancellable injections</a>.
        </p>
    </tip>

    <seealso>
        <category ref="related">
            <a href="slices.topic">Slices</a>
            <a href="how-a-class-is-changed.topic">How a class is changed</a>
        </category>
    </seealso>
</topic>
```

What it demonstrates: the sample is on the first screen; the explanation is a three-item
list, not three paragraphs; every idea the reader might not have — slices, diagnostics,
cancellation — leaves a link and no prose.

## 2. An `explain` page — the reader wants the model

*How a class gets changed.* The diagram carries the flow. The prose under it names the parts
and nothing else. Anything genuinely deep is two sentences and a link.

```xml
    <p>
        A class reaches the weaver as bytes and leaves as bytes. Nothing in between holds a
        reference to your objects.
    </p>

    <code-block lang="mermaid">
flowchart LR
    A["Class bytes"] --> B{"Does a weave match?"}
    B -->|no| D["Handed back unchanged"]
    B -->|yes| C["Injections applied in order"]
    C --> E["New bytes, stamped"]
    </code-block>

    <deflist type="medium">
        <def title="The plan">
            <p>
                Built once, before the first class arrives. It holds every weave the engine
                knows and the classes each one claims.
            </p>
        </def>
        <def title="The match">
            <p>
                Per class, and cheap: a name lookup against the plan. A class no weave claims
                never gets parsed.
            </p>
        </def>
        <def title="The transformer">
            <p>
                Applies the injections in declaration order and writes new bytes. It is the
                only part that reads bytecode.
            </p>
        </def>
        <def title="The stamp">
            <p>
                Marks the result as woven, so a second pass can tell it from an original.
                <a href="stamps.topic">Stamps</a> has the layout.
            </p>
        </def>
    </deflist>

    <note>
        <p>
            Loading is a separate problem, and the isolation it needs is not visible here.
            <a href="class-loading.topic">Class loading</a> covers it.
        </p>
    </note>
```

What it demonstrates: picture first, four parts of one line each, and the two hard subjects —
stamps and class-loader isolation — named and handed off rather than explained. No paragraph
says why the design is this way, because a reader who wants to change a class does not need it.

## 3. A `reference` page — the reader needs the exact rule

*Diagnostic codes.* What they are for, how to read one, and where each family comes from.
**Not one entry per code.** The reader arrived holding a single code and needs to know which
family it belongs to.

```xml
    <p>
        Every diagnostic the build emits carries a code. The number says which stage refused
        the weave, which is usually enough to know what to look at.
    </p>

    <table>
        <tr><td>Range</td><td>Raised by</td><td>Usually means</td></tr>
        <tr><td><code>AW1xxx</code></td><td>The annotation processor</td>
            <td>The weave declaration is wrong: a missing target, a signature that does not match</td></tr>
        <tr><td><code>AW2xxx</code></td><td>The class reader</td>
            <td>The bytes cannot be used: an unsupported class file version</td></tr>
        <tr><td><code>AW3xxx</code></td><td>Policy</td>
            <td>The class may not be woven: a JDK class, a signed jar</td></tr>
    </table>

    <p>
        A code is fatal unless the message says otherwise. The full list, with the remedy for
        each, is generated from <code>DiagnosticCode</code> and lives in
        <a href="diagnostics-full.topic">the code index</a>.
    </p>
```

What it demonstrates: three rows instead of forty; the reader can place a code they have
never seen; the exhaustive list exists and is one link away. A reference page that reprints
its own generated index has copied the thing most likely to go stale.

## Rejected phrasings

| Written | Why it fails |
| --- | --- |
| A 90-word paragraph explaining a four-stage flow | That is a diagram. The gate rejects it at 75 words and it was a picture at 20. |
| `We recommend using class literals.` | First person, and a recommendation where there is a rule. |
| `Simply add the plugin to your build.` | `Simply`. Also hides that the plugin needs a phase binding. |
| `This page describes the selector grammar.` | Announces itself instead of saying something. |
| `The engine will then process the class.` | Future tense about present behaviour, and `process` names nothing. |
| `-- see AW1041` with no remedy | Half a sentence. Name what to write instead. |
| `Injection points have been reworked.` | The past. The reader must not be told what it used to be. |
| A rule followed by three qualifying sentences | The rule has stopped being a rule. Make it a table. |
| `This page turns an empty Maven module into…` | Announces itself. The reader knows what page they opened. [gate] |
| `The module that declares the weave is the one being built` | Written to avoid the reader, and it cost a subject, a verb and four words: `You build the module that declares the weave`. [review] |
| `Before we look at the API, some background on bytecode.` | The reader did not ask for background, and nobody is speaking. [gate] |
| Eight chapters on one page | Several pages, or one page that kept what it should have linked to. [gate] |
| A 500-word page with two links | Nothing was deferred, so nothing was left out. [gate] |
| `name the class actually meant to be modified` | `actually`. [gate] |
| `AW1030` in a table cell with no remedy in that cell | The reader is in the cell, not in the section a hundred lines down. [gate] |
| The JDK minimum stated on the install page and again on the overview | One fact, two pages, and they will disagree within a release. [gate] |
| A paragraph after a diagram restating what the diagram shows | The diagram was the statement. [review] |
| `weaveDependencies` used before any page defines it | Vocabulary the reader is assumed to have and no published page gives them. [review] |
| A five-column comparison of prose | It renders 2,750px wide inside an 843px column and the reader sees two columns. [gate] |
