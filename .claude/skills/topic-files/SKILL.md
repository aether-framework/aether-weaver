---
name: topic-files
description: Write a Writerside .topic file without guessing. The element set, the attributes each one requires, the traps that produce a silently wrong page, and how to check a file in a second instead of a minute.
---

# Writing a `.topic`

Every page of this site is a `.topic`: semantic XML against a published schema. Markdown was
tried and is not used, because a Markdown page reaches the builder's element set only by
injecting XML into it, and injected XML breaks on a blank line in a way nothing reports.

**The schema is vendored, and it is the authority.** Not this file, not the markup reference,
not memory:

```
build-config/docsite/schema/topic.v2.xsd
```

86 elements. When this file and the schema disagree, the schema is right and this file is a
bug.

## The shell

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE topic SYSTEM "https://resources.jetbrains.com/writerside/1.0/xhtml-entities.dtd">
<topic xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
       xsi:noNamespaceSchemaLocation="https://resources.jetbrains.com/writerside/1.0/topic.v2.xsd"
       id="what-is-aether-weaver"
       title="What Aether Weaver is">
</topic>
```

`id` must equal the file name without its extension. The gate checks it.

## What the schema requires

Most elements require nothing. These four are the exceptions, and leaving the attribute off
is the commonest way to produce a file the builder rejects:

| Element | Required |
| --- | --- |
| `<img>` | `src` and `alt` |
| `<category>` | `ref` |
| `<include>` | `from` |
| `<secondary-label>` | `ref` |

The gate reads those out of the schema rather than from this table, so a schema update
changes the check without anyone editing it.

## The elements this site uses

| Element | For | Attributes worth knowing |
| --- | --- | --- |
| `<chapter>` | a heading and its content | `title`, `id` |
| `<p>` | a paragraph | |
| `<procedure>` | steps done in order | `title`, `id`, `type="steps"` |
| `<step>` | one step | |
| `<deflist>` | term and definition | `type="full"`, `"medium"`, `"narrow"` |
| `<def>` | one entry | `title`, `collapsible` |
| `<table>` | a comparison | `style="header-row"`, `column-width` |
| `<code-block>` | a sample | `lang`, `src`, `include-symbol`, `include-lines` |
| `<img>` | a diagram | `src`, `alt`, `dark-src`, `border-effect` |
| `<tabs>` / `<tab>` | the same task three ways | `group`, `title` |
| `<note>` `<warning>` `<tip>` | what does not fit the flow | `title` |
| `<tldr>` | a precondition, and nothing else | |
| `<seealso>` | links out, sorted | `<category ref="...">` |
| `<list>` / `<li>` | three or more things | `type="bullet"`, `"decimal"` |
| `<control>` `<path>` `<ui-path>` | a button, a file, a menu path | |
| `<shortcut>` | a key combination, per platform | `key="$Generate"` |
| `<tooltip>` | a glossary term in place | `term` |
| `<secondary-label>` | experimental, internal, a release | `ref` |
| `<snippet>` / `<include>` | a block used on two pages | `id`, `from` |
| `<show-structure>` | the right-hand contents panel | `for`, `depth` |

## The traps

**A code sample comes from a file.**

```xml
<code-block lang="java" src="Greeting.java"/>
```

`src` resolves against `Writerside/snippets/`. The builder checks only that the file can be
read, so whoever changes a sample compiles it by hand. The Markdown fence form
`{ src="..." }` does not exist here; using it inside a `<step>` produced empty code blocks
and the diagnostic `CDE006`.

**A diagram is mermaid.** This builder renders it:

```xml
<code-block lang="mermaid">
    flowchart TB
        A[Class as handed to the weaver] --> B{Any weave injects?}
        B -- yes --> C[Injection pipeline]
        B -- no --> D[Handed back unchanged]
</code-block>
```

Measured: the built page carries `aria-roledescription="flowchart-v2"` and the mermaid
classes where the source had the diagram's text. Nothing is hand-drawn, the theme follows
the reader's, and the source is a diff a reviewer can read.

**Lay the graph out top to bottom.** `flowchart TB`, not `LR`. Mermaid sizes itself from the
graph, and a left-to-right chart grows without limit and is then scaled into the 843px
column, taking its labels with it. Height costs nothing. The gate measures the rendered
width in the built page and rejects anything over 843px.

**A hand-written SVG is the exception, not the rule**, for the drawing mermaid cannot
express. Then it is a pair — `name.svg` and `name_dark.svg`, identical but for the colour,
`#24202E` light and `#E4E0EC` dark — because an SVG loaded through `<img src>` is an
independent document whose `currentColor` resolves against nothing. The gate holds it to a
viewBox of 700 units and no `font-size` under 12.

**A table costs about 9.9px per character of the longest cell in each column**, and the
column is 843px. The gate holds a table to 3 columns, 120 characters a cell, and 85
characters summed across the columns — a table inside that budget fits with no stylesheet at
all, which is the only kind of fitting that also holds in the editor's preview. A comparison
too wide for it splits into two tables that share their first column, or becomes a
`<deflist>`, which has the whole width to wrap in.

**A hub is a `<section-starting-page>`**, and it is the only place `<spotlight>`,
`<primary>`, `<secondary>` and `<card>` are valid. **`<spotlight>` is required and takes
exactly two cards** — none at all is `MRK015`, and a third is dropped. Every card needs a
`summary` and a `badge` or `type`.

`type` is a closed set and the builder rejects anything outside it as `MRK026`. These values
are checked and pass:

`start`, `install`, `learn`, `idea`, `tools`, `library`, `development`, `open-source`,
`creative`, `mixed`, `search`, `account`, `cross-platform`, `support`, `medium`

`check` and `node` read like members of that set and are not. Guessing one costs a full
builder run, so take a value from the list.

**`<tooltip term="">` only resolves against `cfg/glossary.xml`**, which is empty. A term
enters it in the same commit as the page that first uses it.

**Every version is `%version%`**, never a literal. The variables are in `v.list`.

## Checking it

```bash
python3 build-config/docsite/check-docs.py            # a second
python3 build-config/docsite/check-docs.py --build    # a minute, and the real gate
```

The plain run reads every `.topic` with expat, so a diagnostic carries a line number. It
rejects an element the schema does not define, a required attribute left off, an `id` that
does not match the file name, and every readability and layout limit in
`build-config/docsite/STYLE.md`.

`--build` runs the Writerside builder, which does the full schema validation and 180
inspections. Run it before committing. Its caches do not invalidate when a topic changes, so
build into a directory of your own with a fresh IntelliJ system path — the gate does that for
you, which is why its verdict can be trusted and a bare `writerside` call's cannot.
