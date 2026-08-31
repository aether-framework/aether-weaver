# Aether Weaver documentation

The source of the documentation site, authored in [Writerside](https://www.jetbrains.com/writerside/).
This directory is the *help module*: the directory holding `writerside.cfg` is what makes it one,
and every path inside the configuration is resolved against it.

It is not part of the Maven reactor. `mvn verify` neither builds nor checks it; `.github/workflows/docs.yml`
does both, and that workflow is the gate.

Seven topics are written of the forty-eight `PAGES.tsv` plans; the four sections none of them
reaches yet stand in `aw.tree` as `wip="true"` entries, which render greyed rather than as dead
links. Pages are written one at a time by the `docsite` pipeline: the work breakdown is
`build-config/docsite/PAGES.tsv`, the style contract is `build-config/docsite/STYLE.md`, and
`python3 build-config/docsite/check-docs.py` is the gate.

## Layout

| Path | What it is |
|---|---|
| `writerside.cfg` | Module configuration. Registers every directory and list file below. |
| `aw.tree` | The table of contents, and the only thing that decides what is published. |
| `v.list` | Variables. Every fact that changes on a release lives here and nowhere else. |
| `c.list` | The categories a `<seealso>` block may sort links into. |
| `labels.list` | Topic and chapter labels: `wip`, `experimental`, `internal`, `0.1.0`. |
| `keymap.xml` | The IntelliJ actions `<shortcut key="$Action"/>` resolves against, per platform. |
| `versions.json` | The releases the header's version switcher lists. Not copied by the builder. |
| `cfg/buildprofiles.xml` | Site-wide build settings: colours, widths, logo, favicons, footer, sitemap, contribution links. |
| `cfg/glossary.xml` | Terms that `<tooltip term="...">` resolves against. |
| `cfg/static/custom.css` | Presentation adjustments. Referenced by `<custom-css>`. |
| `cfg/head.html` | Injected into every page's `<head>`. Carries the content security policy. |
| `topics/` | Every topic, in its section's directory. `.topic` is semantic XML, `.md` is Markdown. |
| `topics/start/` `concepts/` `guides/` `reference/` `tooling/` `contributing/` | The six sections. What each may contain is in `build-config/docsite/STYLE.md`. |
| `snippets/` | The code samples `src=` on a code block resolves against. Real sources, compiled by hand rather than by the build. |
| `images/` | Diagrams, each with a `_dark` twin, plus the logo, the favicon set and the link-preview card. |

## Conventions

**A topic id equals its file name without the extension.** For a `.topic` file it must also be
written out as `id="..."`. Writerside enforces this; there is no way to opt out.

**A topic is published only if `aw.tree` names it.** A file in `topics/` that no `<toc-element>`
references is not part of the site.

**Topics live in section directories, and the tree still names them by bare file name.**
`concepts/selectors.md` is referenced as `<toc-element topic="selectors.md"/>`. Writerside
identifies a topic by its file name whatever directory it sits in, which is why file names
have to be unique across every section.

**Card grids need semantic XML.** `<section-starting-page>` — the element that renders them — is
not valid in Markdown, so any page built out of cards has to be a `.topic`. Markdown pages can
still carry semantic XML inline: `<tldr>`, `<tabs>`, `<procedure>`, `<deflist>`, `<table>` and
`<seealso>` all work there.

**Injected XML must be a continuous block.** A blank line inside an injected element ends the XML
block, unless the blank line is there deliberately so that Markdown can be written inside the
element — which is what `<tab>` and `<snippet>` bodies do. Never indent Markdown inside XML with a
tab; it becomes a block quote.

**Code samples come from `snippets/`, and nothing in this module compiles them.** A block written
as an empty fence followed by `{ src="Greeting.java" }` resolves against `snippets/`, and the
builder checks only that the file and any `include-symbol` can be read. Whoever changes a sample
compiles it themselves against the published artefacts.

**A diagram is mermaid, and this builder renders it.** `<code-block lang="mermaid">` comes out
of the build as a flowchart SVG — the built page carries `aria-roledescription="flowchart-v2"`
where the source had the diagram's text. The renderer is the JetBrains runtime's CEF, which in
this container cannot load its shared libraries unless `LD_LIBRARY_PATH` names the prefix they
were unpacked into; without that every diagram fails as `INT009`, "Rendering of the diagram timed
out". `check-docs.py` sets it, which is another reason to build through the gate.

Lay a graph out top to bottom. Mermaid sizes itself from the graph, so `flowchart LR` grows
without limit and is then scaled into the 843px column, labels and all; the gate measures the
rendered width in the built page.

**A hand-written SVG is the exception**, for a drawing mermaid cannot express. It comes in a pair:
an SVG loaded through `<img src>` is an independent document, so `currentColor` resolves against
nothing and the page's CSS never reaches it. Every such diagram states its own colours, and
`name_dark.svg` beside `name.svg` is what the dark theme loads; the builder requires the twin.

**Images carry no border by default.** `writerside.cfg` sets none, because a hairline box around a
line drawing on a transparent ground reads as a frame the drawing failed to fill. A screenshot asks
for one itself with `border-effect="line"`.

**The article column is 843 pixels, and tables and diagrams are made to fit it.** The builder sizes
a table to its content and gives the wrapper a horizontal scrollbar, so a table too wide for the
column is not wrapped — it is cut off, and the reader sees the first two columns of six. `custom.css`
caps the width so it wraps instead, and below a 1400px window it also lets a long code token break,
because breaking `aether.weaver.onError=fail` beats hiding the column it sits in. The cap rescues a
table that is merely wide; a table of the wrong shape it cannot, which is why the gate holds a table
to four columns and 160 characters a cell. A diagram is capped the same way, so it is laid out in a
viewBox no wider than about 880 units: a drawing 1720 units wide arrives at less than half size, and
its 14-unit labels reach the reader at 6.9px. The gate computes that figure and rejects anything
under 9.5px.

## Settings that are easy to get wrong

Every one of these was arrived at by breaking it first. `cfg/buildprofiles.xml` carries no comments;
this is where the reasons live.

**No width overrides.** The builder ships a two-width typographic system, and the JetBrains help this
site is modelled on runs on its defaults: a paragraph and an `h2` are capped at 706px, an `h3` at
540, and a table, a code block or a figure widens past them to 952. A table reaching further right
than the paragraph above it is that system working, not a bug. `content-max-width` scales the whole
system rather than only the outer container, so 1000 gives a cramped column and 1360 gives lines
around 900px that are hard to track back. Absent is the setting that matches. The same goes for
`cfg/static/custom.css`: if the builder already does it, do not do it again. An earlier version of
that file drew a rule above each `h2` at the heading's 706px while the table under it ran to 952,
which made a correct layout look broken.

**`contribute-url` ends at `/Writerside`.** The builder appends the topic path, which already starts
with `topics/`. Naming that directory here produces `topics/topics/` and a 404 on every page.

**`versions-switcher` needs an absolute URL.** A relative one fails the build with `CNF003`, "is an
invalid URL". `versions.json` is not a topic, so the builder does not copy it either — whoever
publishes the site puts it beside the version directories, which is written down in
`.github/RELEASING.md`. The switcher renders only when the file lists more than one release, and it
replaces the header's plain version string.

**`version=` and `web-path=` on `<instance>` belong together.** See *Releasing a version* below.

**`header-logo` is the square mark, not the wordmark.** The header already prints
"Aether Weaver 0.1.0 Help" beside it, so a logo carrying the name a second time reads as a
duplicate. `custom-favicons` is likewise not optional: without it the site serves the JetBrains
favicon, which is the builder's default. The two smallest sizes drop the faint second strand of the
mark, which turns to mush below about 48px.

**The footer mark is CSS, not a `<footer>` element.** The schema allows only `notice`, `icp`,
`copyright`, `social` and `link` there, and `icon=` on a link renders an inline glyph at the height
of the link text. So `custom.css` packs the footer row to the start, which brings the front end's
"Powered by JetBrains Writerside" back beside the copyright, and paints the Splatgames.de mark into
the 48px corner it frees. The drawing is inlined because the builder copies an image only when a
topic or a configuration value names one, and a URL in a stylesheet is neither; that leaves two
copies, so the rule records the source file's digest and `check-docs.py` compares them.

**`keymap.xml` holds IntelliJ platform actions, not the plugin's own.** The plugin declares no
keyboard shortcut; it reaches the reader through the generate menu and the intention popup, and what
a reader has to press is whatever opens those. `$Generate` is there because `AetherWeaver.AddHandler`
is registered into `GenerateGroup`, `$ShowIntentionActions` because of three `<intentionAction>`
registrations, both in `aether-weaver-idea/src/main/resources/META-INF/plugin.xml`. The keystrokes
are the IntelliJ default keymaps as JetBrains publishes them.

**The announcement banner earns its place only while it announces something.** `<custom-banner>`
renders a strip under the header on every page of the site. `date=` is the day it stops rendering,
and the reader can dismiss it before then. Its text carries no version number on purpose: the
release already lives in `writerside.cfg` and `versions.json`, the gate keeps those two in step, and
a third copy would go stale silently.

## Build it locally

The Writerside builder is installed in this container. Build through the gate rather than
calling it directly:

```bash
python3 build-config/docsite/check-docs.py --build
```

That runs the builder with an IntelliJ system path of its own and fails on any error or
warning in its report. The direct call is:

```bash
writerside Writerside/aw <output-dir>
```

but it reuses the caches under `~/.cache/JetBrains`, and those **do not invalidate when a
topic changes** — a second run rebuilds the first run's content and reports it clean. If a
page you know is broken comes back clean, that is why. The gate exists to avoid it.

With the IDE, open this directory as a Writerside project and the preview is live.

CI does not use the local builder: `.github/workflows/docs.yml` runs the JetBrains Docker
image pinned by `DOCKER_VERSION`, which is versioned separately from the builder installed
here. The build writes `webHelpAW2-all.zip` and `report.json`; the report is what the CI
checker fails on.

## Looking at the built site

The site is a single-page application. What the builder writes to disk is a shell plus JSON;
the header, the table of contents and the footer are painted by the front end, so reading the
built HTML says nothing about what the page looks like. Two rounds of footer work were shipped
on the strength of reading the builder's stylesheet and reasoning about where an element would
land, and both were wrong.

```bash
build-config/docsite/shoot.sh <url> <out-prefix> [--clip .footer] [--theme light] [--width 820]
```

It renders the page in Chromium and writes a PNG. `--clip` takes a CSS selector and
photographs that element alone, which is how the header and the footer get looked at.

Chromium is not in the image. It was installed without root, because this container has no apt
lists and no passwordless sudo:

1. `python3 -m venv ~/.local/venvs/docshot` and `pip install playwright` inside it.
2. `python -m playwright install chromium`, which downloads into `~/.cache/ms-playwright`.
3. Its eighteen missing shared libraries come from the Debian archive: the `bookworm` package
   index is fetched directly, the dependency closure resolved, and each `.deb` unpacked into
   `~/.local/chromium-libs`. `shoot.sh` puts that prefix on `LD_LIBRARY_PATH`.

None of it is in the repository and none of it is on the CI path. If the container is rebuilt,
the three steps have to be repeated.

## What the published page loads

Nothing from a third party. A reader in Germany who opens these docs must not have their IP
address sent to somebody they have no relationship with, and there is no consent banner here to
ask them first, so the site serves everything it loads from its own origin.

Two settings do it, and neither is enough alone:

- `<offline-docs>true</offline-docs>` bundles the front end, its stylesheet, its fonts and the
  favicons into the output instead of loading them from `resources.jetbrains.com`. It grows the
  artefact from about 1 MB to about 7.5 MB.
- `<include-in-head>head.html</include-in-head>` adds `connect-src 'self'`. The front end asks
  `resources.jetbrains.com` for a list of JetBrains webinars on every page load, unconditionally,
  and offline-docs does not stop it. The policy governs `fetch` and `XHR` only, so images, styles,
  fonts and scripts are untouched, and everything the site fetches for itself is same-origin.

Measured in a browser rather than assumed: with both, fifteen requests, all to the site's own
origin, and the article, the contents panel, the footer and the banner all still render. The
`--build` half of the gate refuses a built page that names an external `src` or `href` on a
`<script>` or a `<link>`.

One consequence: the version switcher fetches `versions.json`, which is same-origin on the
published site and cross-origin in a local preview. `connect-src 'self'` therefore blocks it
locally. The switcher works where it is published.

## What the published page stores

One cookie, and only after a reader asks for it.

The front end writes a `userToken` on load: a random identifier with a ninety-day life, passed to
Algolia as `search(query, {clickAnalytics: true, userToken})` and attached to every result click.
This site configures no Algolia index, so it was written and never read by anything. Storage on a
reader's device has to be necessary for what they asked for, and an identifier for a search that
does not exist is not, so `cfg/head.html` refuses that one cookie name at the property the front
end writes through and passes every other write to the real setter untouched.

What is deliberately left alone is the cookie the announcement banner sets when a reader closes
it, `wh_custom_banner_<title>_closed`, thirty days. Remembering that somebody dismissed something
is exactly the storage that is necessary for a function they asked for, and removing it would
bring the banner back on every page load.

Measured in a browser: no cookies on load, only the site's own origin contacted, the page renders,
and after dismissing the banner it stays dismissed across a reload.

## Not configured

**Search.** A published Writerside site has no search field unless one is wired up. The supported
route is Algolia: add `<algolia-id>`, `<algolia-index>` and `<algolia-api-key>` to
`cfg/buildprofiles.xml`, and a `publish-indexes` job to the workflow using the artefact
`algolia-indexes-AW.zip`.

`<search-service>local</search-service>`, which the Writerside documentation describes as a static
index queried in the reader's browser, is **accepted and ignored** by the builder in this container
(IntelliJ IDEA 2026.1): no `search-index.json` is written and `config.json` comes out unchanged. It
was tried. Do not re-add it without checking the output for that file.

**Other languages.** One instance, one locale. A translation is a second instance and a second tree.

## Releasing a version

The site is published under its release: `/aether-weaver/0.1.0/`, with the project-page root
redirecting to whichever release `versions.json` marks current. That layout is not optional once the
instance carries a `version=` — the builder writes the version into every canonical URL, OG URL and
sitemap entry, and a site published flat under those URLs is a sitemap of 404s.

A release is two edits and nothing else:

1. `writerside.cfg` — the `version=` on `<instance>`.
2. `versions.json` — a row for the new release, and `isCurrent` moved to it.

`check-docs.py` fails if those two disagree, or if `isCurrent` is on none or several. The builder
writes the number it used into `current.help.version` inside the archive, which is what says which
directory a build belongs under rather than anybody retyping it.
