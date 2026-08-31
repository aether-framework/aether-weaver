#!/usr/bin/env python3
"""
The documentation gate.

Two gates in one command, and they are not the same kind of check.

    python3 build-config/docsite/check-docs.py            # project rules, about a second
    python3 build-config/docsite/check-docs.py --build    # and the Writerside builder, about a minute

**The builder is the real gate.** `writerside` is installed in this container and runs 180
inspections over the module: dead links, a toc-element with no file, a topic id that does
not match its file name, duplicate file names, an undefined variable, a seealso category
that c.list does not declare, a missing image, a code snippet whose `src` cannot be read, a
`spotlight` without exactly two cards, a card with an empty summary, a second `<tldr>` in
one topic, an element used where the schema does not allow it, and an invalid value in
buildprofiles.xml. Nothing here can compete with that, and nothing here tries to replace it.

**What this script adds is the project's own rules**, which the builder cannot know: that a
page is where `PAGES.tsv` says it is, that `PAGES.tsv` and the repository agree, that only
one page is being written at a time, that a topic no tree names is a mistake rather than a
draft, and that a version is written as `%version%` rather than spelled out.

Some checks below do overlap the builder. They are kept because they run in a second rather
than in a minute and because their message names the line, which is what an agent iterating
on a page needs. Where the two disagree, the builder is right.

Exit status is 0 when every check passes and 1 otherwise. Nothing in the repository is
written; `--build` writes only to a temporary directory and removes it.
"""

from __future__ import annotations

import csv
import hashlib
import json
import math
import os
import re
import shutil
import statistics
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT / "Writerside"
TOPICS = MODULE / "topics"
PAGES = ROOT / "build-config" / "docsite" / "PAGES.tsv"
INSTANCE = "aw"

# Built-in badge names accepted on a <card>. An unknown badge renders nothing, silently.
BADGES = set(
    """academy account branch bug case check-list cloud community computer container creative
    cross-check cross-platform cup data development documents experiment file filtering folder
    idea install integration key keyboard learn library location lock mail mixed navigation
    network offer open-source presentation search server settings start support thumb-up tools
    top turn-on world""".split()
)

# Variables Writerside defines itself, which v.list does not declare.
BUILTIN_VARS = {"instance", "instance-lowercase", "currentId", "thisTopic"}

# Block elements whose opening and closing tags must balance inside a Markdown topic. An
# unbalanced one swallows the rest of the page into the element, which the builder reports
# far from the line that caused it.
BLOCK_TAGS = [
    "tldr", "tabs", "tab", "procedure", "step", "deflist", "def", "table", "tr", "td",
    "seealso", "category", "snippet", "note", "warning", "tip", "quote", "cards", "links",
    "group", "spotlight", "primary", "secondary", "misc", "chapter", "code-block", "if",
    "compare", "web-summary", "card-summary", "link-summary",
]

# Files allowed to name a version literally instead of through %version%.
# Languages this Writerside build actually renders. One it does not know is dropped from the
# output silently -- no error, no warning, the content simply is not there -- so the allowlist
# is a content check rather than a style preference.
#
# `mermaid` is on it, and this file used to say the opposite. Measured: a
# <code-block lang="mermaid"> is rendered by the builder into a flowchart SVG, and the built
# page carries `aria-roledescription="flowchart-v2"` where the source had the diagram's text.
# The renderer needs the JetBrains runtime's CEF, which cannot load its shared libraries in
# this container unless LD_LIBRARY_PATH names them; without that the builder reports INT009,
# "Rendering of the diagram timed out." That is a container problem, not a Writerside one,
# and run_builder sets the variable.
FENCE_LANGUAGES = {
    "java", "xml", "kotlin", "groovy", "json", "properties", "yaml", "sql",
    "bash", "shell", "console", "text", "plain", "diff", "http", "mermaid",
}

# Where the JetBrains runtime's CEF libraries were unpacked. The diagram renderer is a
# headless browser, and in this container its libraries live outside the system prefix
# because there is neither an apt list nor passwordless sudo. See Writerside/README.md.
CEF_LIBRARY_PATH = (Path.home() / ".local/chromium-libs/usr/lib/x86_64-linux-gnu",
                    Path.home() / ".local/chromium-libs/lib/x86_64-linux-gnu")

# A sentence longer than this, or a prose paragraph longer than this, is where a page stops
# being read and starts being skimmed. The figures are not taste: JetBrains' own reference
# documentation runs about twenty words to the sentence and two to four sentences to the
# paragraph, and the pages that failed review here ran to twenty-eight and eighty-seven.
# The limits are set well above the reference so that only the genuinely unreadable trips
# them.
MAX_SENTENCE_WORDS = 25
MAX_PARAGRAPH_WORDS = 75

# Consecutive prose paragraphs with nothing between them -- no heading, list, table, code,
# admonition or image. This is the "wall of text" rule, and it is the one that cannot be
# satisfied by shortening sentences.
MAX_PROSE_RUN = 4

# A chapter longer than this has stopped being scannable: the right-hand contents panel is
# generated from headings and is the only index a reader gets, so a heading that covers 500
# words hides 500 words. JetBrains' own reference pages put a heading every 150 to 250 words.
MAX_WORDS_PER_CHAPTER = 320

# The opening of a page: from the H1 to the first `##`. Measured on the reference this site is
# modelled on, a page is into its first chapter after a single lead paragraph of 35 to 40 words.
# The cap is set at two short paragraphs so that a page has room to name a prerequisite.
MAX_OPENING_WORDS = 90

# <tldr> renders as a filled box above the lead, and a box is a promise that what is in it is
# short. One of six reference pages measured uses it at all. Ours used it on every page, at 72 to
# 96 words, which put a block of grey text between the reader and the first sentence of the page.
MAX_TLDR_WORDS = 40
MAX_TLDR_FACTS = 2

# A table is sized to its content and the article column is 843px wide, measured in a browser
# at a 1440px viewport. Writerside sizes a table to max-content and lets the wrapper scroll, so
# a table too wide for that column is hidden rather than wrapped; cfg/static/custom.css caps it
# at the column width, and these two limits keep a table inside what the cap can rescue.
#
# Measured on this site's own tables with the cap applied: every three-column table fits, the
# one four-column table with cells under 90 characters fits, and every table above these limits
# either still scrolls -- an unbreakable code token sets a floor no cap can move -- or squeezes a
# column under 110px. A cell longer than MAX_TABLE_CELL_CHARS is a paragraph, and a paragraph
# belongs in prose or in a <deflist>, where it has the full column to wrap in.
# The narrowest label a diagram may end up with on screen, in CSS pixels. An <img> is capped at
# the article's 843px, so a drawing laid out in a viewBox wider than that is scaled down and its
# text with it: a 14-unit label in a 1720-unit viewBox reaches the reader at 6.9px against the
# site's 16px body text. Three diagrams were drawn that way and measured at 5.4, 6.3 and 6.6px.
MIN_DIAGRAM_LABEL_PX = 9.5
ARTICLE_WIDTH_PX = 843

MAX_TABLE_COLUMNS = 3
MAX_TABLE_CELL_CHARS = 120

# The width a table costs, before any stylesheet helps it. The builder sizes a table to its
# content, and measured across this site's own tables that content costs 8.5 to 9.9px per
# character of the longest cell in each column. At the worst of those rates the 843px column
# holds 85 characters summed across the columns, so a table inside this budget fits with no
# custom CSS at all -- which is the only kind of fitting that also holds in the IDE preview,
# where a stylesheet under cfg/static is not guaranteed to be applied.
MAX_TABLE_WIDTH_CHARS = 85
PIXELS_PER_TABLE_CHARACTER = 9.9

# A diagram is laid out for the column and never scaled down. At 700 units it renders at its
# own size inside the 843px article and still fits a preview pane narrower than that; a
# 12-unit label then reaches the reader at 12px against 16px body text.
MAX_DIAGRAM_VIEWBOX = 700
MIN_DIAGRAM_FONT = 12

# Card types and badges are the same set of built-in icons; `type` is what a <spotlight> entry
# takes and `badge` is what the other groups take.
CARD_ICON_ATTRS = ("badge", "type")

# Filler and hedging. Every one of these was in the style contract as prose and every one of
# them shipped anyway; `actually` reached a published page. A closed list is the only form of
# this rule that survives contact with a writer in a hurry.
BANNED_WORDS = ("simply", "just", "easily", "of course", "actually", "obviously",
                "note that", "it is important to note", "basically", "essentially",
                "very", "really", "quite")

# First person has no place in either body of documentation.
#
# Second person used to be banned outside a `howto`, and that rule was wrong. Measured against
# the documentation this site claims to be modelled on -- build-config/docsite/REFERENCE.md,
# regenerated from JetBrains' own published corpus -- `you` appears 117 times per 10,000 words
# and `your` 46. The reader is in almost every paragraph of it. The ban produced exactly the
# register it was meant to prevent: prose with nobody in it, which reads as machine-written
# because avoiding the reader costs words and warmth and buys nothing.
FIRST_PERSON = ("we", "our", "ours", "us", "i", "my", "mine", "let's")

# A sentence is measured against their median of 9 words, not against how long a sentence can
# get before it is unreadable. p90 in that corpus is 21 and p99 is 52; a hard limit of 25 sits
# just above their 90th percentile, and MEDIAN_SENTENCE_WORDS holds the page as a whole to the
# shape of the reference rather than letting every sentence sit at the ceiling. 13 is their
# 75th percentile: half of what this site publishes has to be at least as short as three
# quarters of what theirs does.
MEDIAN_SENTENCE_WORDS = 13

# Whole-page budgets, per kind.
#
# Every limit above this point is local: a sentence, a paragraph, a chapter. A page can pass
# all of them and still be a book, because a book is what short compliant pieces add up to
# when nothing counts the total. The first page written under those rules ran to 1231 words
# across 8 chapters and carried 6 links. The corpus this site is modelled on -- 1,296
# published JetBrains pages, measured in build-config/docsite/REFERENCE.md -- has a median
# page of 697 words with 2 chapters and 12 links.
#
# What this rule counts is running prose, the same figure the chapter limit counts: headings,
# lists, tables and code are the parts a reader skims to, not through. Measured that way the
# same corpus runs a median of 360 words a page, 488 at the 60th percentile and 854 at the
# 75th. The budgets sit at that 75th percentile. A page is allowed to be long when it has
# something to say; what it may not be is padded, and padding is caught by the density rule
# below rather than by a word count. Past these figures it is a page that should be two.
MAX_PAGE_WORDS = {"howto": 600, "explain": 900, "reference": 1200, "hub": 300}

# Chapters below the H1. Their median is 2 and their 90th percentile is 7.
MAX_CHAPTERS = {"howto": 6, "explain": 6, "reference": 8, "hub": 3}

# Prose words per element the eye can stop on: a heading, a list item, a table row, a
# definition, a step, a code block, a picture, an admonition. This is the rule that lets a
# page be long -- length is fine, an undifferentiated column of paragraphs is not.
#
# Measured over the 930 corpus pages above 150 words: the median carries one such element
# every 38 words of prose, the 90th percentile every 104, and 8% run past 120. The limit is
# set at that 8%, so it catches a wall of text and nothing else.
MAX_WORDS_PER_STRUCTURE = 120
STRUCTURE_FLOOR_WORDS = 150

# What counts as one of those elements in a .topic. Counted per item rather than per
# container, because a list of six is six places to stop and a list of two is two.
STOPPING_POINT_TAGS = ("chapter", "li", "tr", "def", "step", "code-block", "img",
                       "note", "tip", "warning", "tabs")

# Links per 100 words of prose, and the length below which a page is short enough not to owe
# one. This is the rule that makes leaving something out possible rather than merely allowed:
# a detail dropped from a page has to leave a link where it stood, or it was not deferred, it
# was destroyed. Measured over the same corpus: 1.8 links per 100 words, and 97% of pages
# carry at least one. The floor is set at 1.0, which is a little over half their rate.
MIN_LINKS_PER_100_WORDS = 1.0
LINK_DENSITY_FLOOR_WORDS = 120

# Words a reader passes before the page hands over the thing it exists to hand over: the
# snippet on a howto, the diagram on an explain page, the table on a reference page. Time to
# value, counted in words. Their median page is into its first heading after 180 words, and
# the payload is normally the first thing under it.
MAX_WORDS_TO_PAYLOAD = {"howto": 120, "explain": 160, "reference": 120}

# What counts as that payload, per kind.
PAYLOAD_TAGS = {"howto": ("code-block", "procedure"),
                "explain": ("code-block", "img"),
                "reference": ("table", "deflist")}
PAYLOAD_NAMES = {"howto": "a code sample or a <procedure>",
                 "explain": "a diagram",
                 "reference": "a <table> or a <deflist>"}

# An opening that names the page instead of answering it. The most valuable paragraph on the
# page, spent on a label the reader already read in the title and the tree.
SELF_ANNOUNCING = (r"^this (page|document|guide|section|topic|chapter)\b",
                   r"^in this (page|document|guide|section|topic|chapter)\b",
                   r"^the following\b",
                   r"^this (describes|explains|covers|documents)\b")

# A diagnostic code is only useful with what to do about it. One of these has to appear in the
# same block -- paragraph, table cell or definition -- as the code. "no remedy" is an allowed
# answer where the source states none; silence is not.
REMEDY_VERBS = ("mark", "rename", "declare", "supply", "add", "give", "check", "match",
                "name", "run", "use", "set", "remove", "move", "put", "split", "exclude",
                "restore", "verify", "delete", "start", "weave", "call", "point", "make")
REMEDY_ESCAPE = ("no remedy", "nothing to do", "none needed", "not an error")

# Words of running text that may repeat across two pages before it counts as the same fact
# written twice. Eight is long enough that a shared technical phrase does not trip it and short
# enough to catch a restated rule: "the build refuses a JDK below" is seven.
DUPLICATE_SHINGLE_WORDS = 8

# The release-notes page names every released version by number, which is its whole
# content. The extension here has to match what PAGES.tsv gives the row: this set held
# ".md" while the row said ".topic", so the one page the exemption exists for was not
# in it, and the exemption had never fired.
VERSION_LITERAL_ALLOWED = {"contributing/release-notes.topic"}

VALID_STATES = {"todo", "doing", "done"}
VALID_KINDS = {"hub", "explain", "howto", "reference"}

errors: list[str] = []
notes: list[str] = []


def error(where: str, message: str) -> None:
    errors.append(f"{where}: {message}")


def read(path: Path) -> str:
    """The file with its XML comments removed.

    A comment is not content. An outline placeholder that names a topic not written yet, or
    a worked example of markup, must not be reported as a dead link or an unbalanced tag --
    which is exactly what a comment in a half-written page is full of."""
    text = path.read_text(encoding="utf-8")
    return re.sub(r"<!--.*?-->", "", text, flags=re.DOTALL)


def read_raw(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def rel(path: Path) -> str:
    return str(path.relative_to(ROOT))


# ---------------------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------------------

def config_files() -> list[Path]:
    found = [MODULE / "writerside.cfg", MODULE / "keymap.xml"]
    found += sorted(MODULE.glob("*.tree"))
    found += sorted(MODULE.glob("*.list"))
    found += sorted((MODULE / "cfg").glob("*.xml"))
    return [p for p in found if p.exists()]


def check_xml_well_formed(paths: list[Path]) -> None:
    for path in paths:
        try:
            ET.parse(path)
        except ET.ParseError as exc:
            error(rel(path), f"not well-formed XML -- {exc}")


# ---------------------------------------------------------------------------------------
# Topics
# ---------------------------------------------------------------------------------------

def topic_files() -> list[Path]:
    """Every topic under topics/, at any depth. Sub-directories are how this module is
    organised; the tree still refers to a topic by its bare file name."""
    return sorted(p for p in TOPICS.rglob("*") if p.suffix in {".md", ".topic"})


def check_unique_names(topics: list[Path]) -> dict[str, Path]:
    """A topic id is its file name without the extension, whatever directory it sits in, so
    two files of the same name in different sections are the same topic to Writerside."""
    by_name: dict[str, Path] = {}
    for path in topics:
        if path.name in by_name:
            error(rel(path), f"duplicate topic file name, also at {rel(by_name[path.name])}")
        else:
            by_name[path.name] = path
    return by_name


def check_topic_ids(topics: list[Path]) -> None:
    for path in topics:
        if path.suffix != ".topic":
            continue
        text = read(path)
        match = re.search(r'\bid="([^"]+)"', text)
        if not match:
            error(rel(path), "no id attribute on <topic>")
        elif match.group(1) != path.stem:
            error(rel(path), f'id="{match.group(1)}" does not match the file name "{path.stem}"')


def check_block_balance(topics: list[Path]) -> None:
    for path in topics:
        if path.suffix != ".md":
            continue
        text = read(path)
        for tag in BLOCK_TAGS:
            opened = len(re.findall(rf"<{re.escape(tag)}(?=[\s>/])", text))
            selfclosed = len(re.findall(rf"<{re.escape(tag)}(?=[\s>/])[^>]*/>", text))
            closed = len(re.findall(rf"</{re.escape(tag)}>", text))
            if opened - selfclosed != closed:
                error(
                    rel(path),
                    f"<{tag}> does not balance -- {opened - selfclosed} open, {closed} closed",
                )


# ---------------------------------------------------------------------------------------
# The tree
# ---------------------------------------------------------------------------------------

def check_tree(by_name: dict[str, Path]) -> None:
    for tree in sorted(MODULE.glob("*.tree")):
        text = read(tree)
        referenced = re.findall(r'<toc-element[^>]*\btopic="([^"]+)"', text)

        for name in referenced:
            if name not in by_name:
                error(rel(tree), f'toc-element names "{name}", which no topic file provides')

        start = re.search(r'\bstart-page="([^"]+)"', text)
        is_library = 'is-library="true"' in text
        if not start and not is_library:
            error(rel(tree), "no start-page, so the instance has no home page")
        elif start:
            if start.group(1) not in by_name:
                error(rel(tree), f'start-page "{start.group(1)}" does not exist')
            elif start.group(1) not in referenced:
                error(rel(tree), f'start-page "{start.group(1)}" is not in the tree')

        instance_id = re.search(r'<instance-profile[^>]*\bid="([^"]+)"', text)
        if instance_id and instance_id.group(1) != tree.stem:
            error(rel(tree), f'id="{instance_id.group(1)}" must equal the file name "{tree.stem}"')


def tree_referenced_names() -> set[str]:
    names: set[str] = set()
    for tree in MODULE.glob("*.tree"):
        names.update(re.findall(r'<toc-element[^>]*\btopic="([^"]+)"', read(tree)))
    return names


def check_orphans(topics: list[Path], referenced: set[str]) -> None:
    for path in topics:
        if path.name not in referenced:
            error(rel(path), "not named by any tree, so it is not published")


# ---------------------------------------------------------------------------------------
# Cross-references
# ---------------------------------------------------------------------------------------

def declared(path: Path, pattern: str) -> set[str]:
    return set(re.findall(pattern, read(path))) if path.exists() else set()


def check_references(topics: list[Path], by_name: dict[str, Path]) -> None:
    labels = declared(MODULE / "labels.list", r'<(?:primary|secondary)-label id="([^"]+)"')
    categories = declared(MODULE / "c.list", r'<category id="([^"]+)"')
    variables = declared(MODULE / "v.list", r'<var name="([^"]+)"') | BUILTIN_VARS
    terms = declared(MODULE / "cfg" / "glossary.xml", r'<term name="([^"]+)"')

    snippets: set[str] = set()
    for path in topics:
        snippets.update(re.findall(r'<snippet id="([^"]+)"', read(path)))

    snippets_dir = None
    cfg = read(MODULE / "writerside.cfg")
    match = re.search(r'<snippets[^>]*\bsrc="([^"]+)"', cfg)
    if match:
        snippets_dir = MODULE / match.group(1)

    for path in topics:
        text = read(path)
        where = rel(path)

        for ref in re.findall(r'<(?:primary|secondary)-label ref="([^"]+)"', text):
            if ref not in labels:
                error(where, f'label "{ref}" is not declared in labels.list')

        for ref in re.findall(r'<category ref="([^"]+)"', text):
            if ref not in categories:
                error(where, f'seealso category "{ref}" is not declared in c.list')

        for badge in re.findall(r'\bbadge="([^"]+)"', text):
            if badge not in BADGES:
                error(where, f'badge "{badge}" is not a built-in badge name')

        for term in re.findall(r'<tooltip term="([^"]+)"', text):
            if term not in terms:
                error(where, f'tooltip term "{term}" is not declared in cfg/glossary.xml')

        for name in re.findall(r"%([a-zA-Z][a-zA-Z0-9_-]*)%", text):
            if name not in variables:
                error(where, f'variable %{name}% is not declared in v.list')

        for element in re.findall(r'<include[^>]*\belement-id="([^"]+)"', text):
            if element not in snippets:
                error(where, f'include names snippet "{element}", which no topic declares')

        # Both spellings of a link to another topic: the semantic <a href> and, in a
        # Markdown topic, the ordinary Markdown link. A page uses whichever is natural
        # where it stands, so checking only one of them checks half the page.
        links = re.findall(r'href="([^"#%][^"#]*\.(?:md|topic))(?:#[^"]*)?"', text)
        links += re.findall(r"\]\(([^)\s#][^)\s#]*\.(?:md|topic))(?:#[^)\s]*)?\)", text)
        for target in links:
            if target.startswith(("http", "%", "$", "mailto:")):
                continue
            if os.path.basename(target) not in by_name:
                error(where, f'link to "{target}", which no topic file provides')

        images = re.findall(r'<img[^>]*\bsrc="([^"]+)"', text)
        images += re.findall(r"!\[[^\]]*\]\(([^)\s]+)\)", text)
        for src in images:
            if not src.startswith(("http", "$", "%")) and not (MODULE / "images" / src).exists():
                error(where, f'image "{src}" is not in images/')

        for src in re.findall(r'\bsrc="([^"]+\.(?:java|xml|kt|json|properties|mermaid|puml))"', text):
            if snippets_dir is None:
                error(where, f'code block names src="{src}" but no <snippets> directory is registered')
            elif not (snippets_dir / src).exists():
                error(where, f'code sample "{src}" is not in {rel(snippets_dir)}/')

        # A qualifier is part of the version, so `0.1.0-SNAPSHOT` is as much a literal as
        # `0.1.0` and goes as stale as fast. The trailing lookahead used to exclude anything
        # followed by a hyphen, which let exactly that through and put it on a published page.
        if rel(path).replace("Writerside/topics/", "") not in VERSION_LITERAL_ALLOWED:
            for literal in re.findall(r"(?<![\w.%-])\d+\.\d+\.\d+(?:-[A-Za-z][\w.]*)?(?![\w.%])",
                                      text):
                error(where, f'version "{literal}" written out; use %version%')


def check_versions() -> None:
    """versions.json feeds the version switcher in the header, and the builder never reads it.

    It is fetched by the front end at run time, so a typo in it is invisible until the
    published header comes up empty. The current release has to be the one writerside.cfg
    gives the instance, or the switcher marks the wrong release as current."""
    versions = MODULE / "versions.json"
    config = MODULE / "writerside.cfg"
    declared = re.search(r'<instance[^>]*\bversion="([^"]+)"', read(config)) if config.exists() else None

    if not versions.exists():
        if declared:
            error(rel(config), 'the instance declares a version but Writerside/versions.json '
                               "is missing; the header's version switcher reads it")
        return

    try:
        entries = json.loads(read(versions))
    except json.JSONDecodeError as broken:
        error(rel(versions), f"is not valid JSON: {broken}")
        return

    current = [e for e in entries if e.get("isCurrent")]
    if len(current) != 1:
        error(rel(versions), f"{len(current)} entries are marked isCurrent; exactly one is")
    elif declared and current[0].get("version") != declared.group(1):
        error(rel(versions),
              f'the current entry is "{current[0].get("version")}" but writerside.cfg builds '
              f'"{declared.group(1)}"')

    for entry in entries:
        for key in ("version", "url", "isCurrent"):
            if key not in entry:
                error(rel(versions), f'an entry is missing "{key}"')
        # The switcher navigates to this URL. Every release is published under its own
        # version, so a URL that does not end in one sends the reader to the wrong release
        # -- or, for the current one, to a redirect that happens to cover the mistake.
        version, url = entry.get("version"), entry.get("url", "")
        if version and not url.rstrip("/").endswith(version):
            error(rel(versions),
                  f'"{version}" points at "{url}", which is not the path that release is '
                  "published under")


def check_inlined_images() -> None:
    """An image inlined into custom.css cannot drift from the file it came from.

    The builder copies an image into the output only when a topic or a configuration value
    names one, so an image the stylesheet needs has to be inlined rather than referenced.
    That leaves two copies. custom.css records the digest of its source beside the rule and
    this compares them: editing the SVG without regenerating the rule is otherwise invisible
    until somebody looks at the published page."""
    css = MODULE / "cfg" / "static" / "custom.css"
    if not css.exists():
        return
    for name, digest in re.findall(r"source: images/(\S+) sha256:([0-9a-f]+)", read_raw(css)):
        source = MODULE / "images" / name
        if not source.exists():
            error(rel(css), f'inlines "{name}", which is not in images/')
            continue
        actual = hashlib.sha256(source.read_bytes()).hexdigest()[:len(digest)]
        if actual != digest:
            error(rel(css),
                  f'the inlined copy of "{name}" is stale: the file is {actual}, the rule '
                  f"records {digest}. Regenerate the data URI and the digest together")


def check_header_logo() -> None:
    profiles = MODULE / "cfg" / "buildprofiles.xml"
    if not profiles.exists():
        return
    for logo in re.findall(r"<header-logo>([^<]+)</header-logo>", read(profiles)):
        if not (MODULE / "images" / logo).exists():
            error(rel(profiles), f'header-logo "{logo}" is not in images/')

    for css in re.findall(r"<custom-css>([^<]+)</custom-css>", read(profiles)):
        if not (MODULE / "cfg" / "static" / css).exists():
            error(rel(profiles), f'custom-css "{css}" is not in cfg/static/')



# ---------------------------------------------------------------------------------------
# Readability
# ---------------------------------------------------------------------------------------

STRUCTURE_TAGS = ("tldr", "table", "deflist", "procedure", "warning", "note", "tip",
                  "seealso", "tabs", "snippet", "code-block", "img")


SCHEMA = Path(__file__).with_name("schema")

# A <p> inside one of these is content of a structural element, not a paragraph of running
# text: it still has to obey the sentence and paragraph limits, but it does not count towards
# the wall-of-text run, because the element around it is already something for the eye to stop
# on. This is the .topic equivalent of a markdown list item or table cell.
TOPIC_CONTAINERS = ("td", "th", "step", "def", "tldr", "note", "warning", "tip", "li",
                    "seealso", "card", "snippet", "tab", "primary", "secondary", "spotlight")


def topic_events(text: str) -> list[tuple[int, str, tuple[str, ...], dict[str, str], str]]:
    """Every element of a .topic as (line, tag, ancestors, attributes, text).

    Written on expat rather than ElementTree because a diagnostic without a line number
    sends the reader looking, and ElementTree does not keep positions."""
    import xml.parsers.expat

    events: list[tuple[int, str, tuple[str, ...], dict[str, str], str]] = []
    stack: list[tuple[str, int, dict[str, str], list[str]]] = []
    parser = xml.parsers.expat.ParserCreate()

    def start(tag, attrs):
        stack.append((tag, parser.CurrentLineNumber, dict(attrs), []))

    def chars(data):
        if stack:
            stack[-1][3].append(data)

    def end(_tag):
        tag, line, attrs, body = stack.pop()
        events.append((line, tag, tuple(t for t, _, _, _ in stack), attrs,
                       " ".join("".join(body).split())))

    parser.StartElementHandler = start
    parser.EndElementHandler = end
    parser.CharacterDataHandler = chars
    parser.Parse(text, True)
    return events


def in_document_order(events: list) -> list:
    """Events sorted by line, and by nothing else.

    A plain ``sorted()`` over these tuples compares the attribute dict whenever two events
    share a line, a tag and an ancestry -- and dicts do not order, so the checker raised
    TypeError instead of reporting. Two links to different targets on one source line was
    enough to hit it, which is why it survived twenty-eight pages: identical attributes
    compare equal and never reach the dict. Sorting on the line alone is what the callers
    wanted anyway, since Python's sort is stable and leaves the rest in document order."""
    return sorted(events, key=lambda event: event[0])


def topic_paragraphs(text: str) -> list[tuple[int, str]]:
    """The prose of a .topic, in the form prose_paragraphs returns for a Markdown page.

    A <p> of running text is a paragraph; every other element is reported as structure, so
    that the rule about consecutive paragraphs counts the same thing in both formats."""
    out: list[tuple[int, str]] = []
    for line, tag, ancestors, _attrs, body in in_document_order(topic_events(text)):
        if tag == "p" and not any(a in TOPIC_CONTAINERS for a in ancestors):
            out.append((line, body))
        else:
            out.append((line, None))
    return out


def paragraphs_of(path: Path, text: str) -> list[tuple[int, str]]:
    """The prose of a page, whichever of the two formats it is written in."""
    if path.suffix == ".topic":
        try:
            return topic_paragraphs(text)
        except Exception:
            return []          # check_xml_well_formed reports the parse failure itself
    return prose_paragraphs(text)


def schema_index() -> dict[str, set[str]]:
    """Every element topic.v2.xsd defines, to the attributes it requires.

    The schema is vendored under schema/ so the gate does not depend on the network, and it
    is the authority on what a .topic may hold -- not anybody's memory of the markup
    reference. Full XSD validation needs a library this container does not have; the builder
    does that at `--build`. This catches the two mistakes that are worth catching in a
    second rather than in a minute: an element the schema does not define, and a required
    attribute left off."""
    xsd = SCHEMA / "topic.v2.xsd"
    if not xsd.exists():
        return {}
    ns = "{http://www.w3.org/2001/XMLSchema}"
    root = ET.parse(xsd).getroot()
    named = {c.get("name"): c for c in root.iter(f"{ns}complexType") if c.get("name")}

    index: dict[str, set[str]] = {}
    for element in root.iter(f"{ns}element"):
        name = element.get("name")
        if not name:
            continue
        body = element.find(f"{ns}complexType")
        if body is None:
            body = named.get((element.get("type") or "").split(":")[-1])
        # An attribute is declared either inline with name= or by reference with ref=.
        required = {a.get("name") or a.get("ref") for a in body.iter(f"{ns}attribute")
                    if a.get("use") == "required"} if body is not None else set()
        required.discard(None)
        index.setdefault(name, set()).update(required)
    return index


def schema_elements() -> set[str]:
    return set(schema_index())


def check_topic_schema(topics: list[Path]) -> None:
    """A .topic is XML against a published schema, so an element it does not define is a
    mistake the writer can be told about in a second rather than in a minute of builder."""
    index = schema_index()
    allowed = set(index)
    if not allowed:
        error("schema", "build-config/docsite/schema/topic.v2.xsd is missing; .topic files "
                        "cannot be checked against the schema")
        return

    for path in topics:
        if path.suffix != ".topic":
            continue
        text = read_raw(path)
        try:
            events = topic_events(text)
        except Exception:
            continue           # check_xml_well_formed reports it

        for line, tag, _ancestors, attrs, _body in in_document_order(events):
            if tag not in allowed:
                error(f"{rel(path)}:{line}",
                      f"<{tag}> is not an element topic.v2.xsd defines; the schema is "
                      "vendored under build-config/docsite/schema/")
            for required in sorted(index.get(tag, set()) - set(attrs)):
                error(f"{rel(path)}:{line}",
                      f"<{tag}> is missing {required}=, which topic.v2.xsd requires")

            # Not in the schema, but the builder rejects both and the reader needs both.
            if tag == "topic":
                for required in ("id", "title"):
                    if required not in attrs:
                        error(f"{rel(path)}:{line}",
                              f"<topic> is missing {required}=; the id has to equal the file "
                              "name without its extension")
                if attrs.get("id") and attrs["id"] != path.stem:
                    error(f"{rel(path)}:{line}",
                          f'id="{attrs["id"]}" does not match the file name "{path.stem}"')


def prose_paragraphs(text: str) -> list[tuple[int, str]]:
    """The prose of a topic: what is left once code fences, injected XML, headings, list
    items and table rows are taken out. Those carry the page's structure, and a rule about
    how long a paragraph may run has nothing to say about them.

    A structural element is reported as ``(line, None)`` rather than dropped, because the
    run counter has to know that something stood between two paragraphs."""
    found: list[tuple[int, str]] = []
    buf: list[str] = []
    start = 0
    fence = False
    depth = 0
    for number, line in enumerate(text.split("\n"), 1):
        stripped = line.strip()
        if stripped.startswith("```"):
            fence = not fence
            if buf:
                found.append((start, " ".join(buf)))
                buf = []
            found.append((number, None))
            continue
        if fence:
            continue
        opened = len(re.findall(r"<(%s)\b" % "|".join(STRUCTURE_TAGS), stripped))
        closed = len(re.findall(r"</(%s)>" % "|".join(STRUCTURE_TAGS), stripped))
        if opened or closed or depth:
            depth = max(0, depth + opened - closed)
            if buf:
                found.append((start, " ".join(buf)))
                buf = []
            found.append((number, None))
            continue
        if (not stripped or stripped[0] in "#<|-*{" or re.match(r"^\d+\.", stripped)):
            if buf:
                found.append((start, " ".join(buf)))
                buf = []
            if stripped:
                found.append((number, None))
            continue
        if not buf:
            start = number
        buf.append(stripped)
    if buf:
        found.append((start, " ".join(buf)))
    return found


def sentences_of(paragraph: str) -> list[str]:
    parts = re.split(r"(?<=[.!?])\s+", paragraph)
    return [s for s in parts if len(s.split()) > 2]


def check_prose(topics: list[Path]) -> None:
    """The rules the review process could not hold on its own. Two adversarial reviewers
    checked every page for truth and for conformance and passed pages nobody could read,
    because neither was asked whether the page helps. A limit a regular expression can
    apply does not depend on which reviewer read the page that day."""
    for path in topics:
        text = read(path)
        where = rel(path)

        run = 0
        lengths: list[int] = []
        for line, paragraph in paragraphs_of(path, text):
            if paragraph is None:
                run = 0
                continue
            words = len(paragraph.split())
            if words > MAX_PARAGRAPH_WORDS:
                error(f"{where}:{line}",
                      f"paragraph runs to {words} words (limit {MAX_PARAGRAPH_WORDS}); "
                      "split it, or make it a list, a table or a deflist")
            for sentence in sentences_of(paragraph):
                length = len(sentence.split())
                if length > MAX_SENTENCE_WORDS:
                    error(f"{where}:{line}",
                          f"sentence runs to {length} words (limit {MAX_SENTENCE_WORDS}): "
                          f'"{sentence.split(chr(32))[0]} ... {sentence.split()[-1]}"')
            lengths.extend(len(s.split()) for s in sentences_of(paragraph))
            run += 1
            if run == MAX_PROSE_RUN + 1:
                error(f"{where}:{line}",
                      f"{run} prose paragraphs in a row with no heading, list, table, code "
                      f"block, admonition or image between them (limit {MAX_PROSE_RUN})")

        if len(lengths) >= 8:
            lengths.sort()
            median = lengths[len(lengths) // 2]
            if median > MEDIAN_SENTENCE_WORDS:
                error(where,
                      f"the median sentence on this page is {median} words (limit "
                      f"{MEDIAN_SENTENCE_WORDS}). The documentation this site is modelled on "
                      "runs a median of 9; see build-config/docsite/REFERENCE.md. Every "
                      "sentence being under the ceiling is not the same as the page reading "
                      "like the reference")


def page_kinds() -> dict[str, str]:
    """The `kind` column of PAGES.tsv, keyed by the topic's bare file name.

    The kind decides what a page must contain, so the layout checks need it before
    check_pages has run. A row for a file that does not exist yet is simply not returned."""
    if not PAGES.exists():
        return {}
    with PAGES.open(encoding="utf-8") as handle:
        return {os.path.basename(r["file"]): r["kind"]
                for r in csv.DictReader(handle, delimiter="\t")}


def chapters(text: str) -> list[tuple[int, str, list[str]]]:
    """Split a Markdown topic at its headings.

    Returns one entry per chapter as ``(line, heading, body lines)``. Everything before the
    first `##` belongs to the H1, which is where the labels, <show-structure> and <tldr> sit;
    it is returned under the heading "(opening)"."""
    out: list[tuple[int, str, list[str]]] = []
    line, heading, body = 1, "(opening)", []
    fence = False
    for number, raw in enumerate(text.split("\n"), 1):
        stripped = raw.strip()
        if stripped.startswith("```"):
            fence = not fence
        if not fence and re.match(r"^#{2,4} ", stripped):
            out.append((line, heading, body))
            line, heading, body = number, stripped, []
            continue
        body.append(raw)
    out.append((line, heading, body))
    return out


def prose_words(body: list[str]) -> int:
    """Words of running text in a chapter body, with code fences, injected XML, tables and
    list markers taken out. It is the figure the reader has to read through, not the figure
    the file holds."""
    return sum(len(paragraph.split())
               for _, paragraph in prose_paragraphs("\n".join(body))
               if paragraph is not None)


def chapter_words(path: Path, text: str) -> list[tuple[int, str, int]]:
    """(line, heading, words of running text) for every chapter of a page.

    Everything before the first heading is returned as "(opening)". A Markdown page is cut
    at `##`; a .topic at its top-level <chapter>. The rule the caller applies is the same,
    because a heading is a place for the eye to stop whichever format produced it."""
    if path.suffix != ".topic":
        return [(line, heading, prose_words(body)) for line, heading, body in chapters(text)]

    try:
        events = in_document_order(topic_events(text))
    except Exception:
        return []

    starts = [(line, attrs.get("title", "(untitled chapter)"))
              for line, tag, ancestors, attrs, _body in events
              if tag == "chapter" and "chapter" not in ancestors]
    out = [(1, "(opening)", 0)] + [(line, title, 0) for line, title in starts]
    counts = [0] * len(out)

    for line, tag, ancestors, _attrs, body in events:
        if tag != "p" or any(a in TOPIC_CONTAINERS for a in ancestors):
            continue
        index = 0
        for position, (start, _heading, _words) in enumerate(out):
            if start <= line:
                index = position
        counts[index] += len(body.split())

    return [(line, heading, counts[i]) for i, (line, heading, _w) in enumerate(out)]


def has_diagram(text: str) -> bool:
    """Whether a page carries a diagram, in either of the two forms that produce one.

    Mermaid is the default and a hand-written SVG is the exception, so a page satisfies this
    with either. This function exists because the check and its own error message disagreed
    for one commit: the message named mermaid, the test still looked for <img>, and a page
    that did exactly what it was told could not pass."""
    return bool(re.search(r"<img\b", text)
                or re.search(r'lang\s*=\s*"mermaid"', text)
                or re.search(r"^```\s*mermaid\b", text, re.MULTILINE))


def article(kind: str) -> str:
    """"a howto" but "an explain" -- a diagnostic that misspells its own subject reads as
    carelessly as the page it is complaining about."""
    return f"{'an' if kind[0] in 'aeiou' else 'a'} {kind}"


def links_of(path: Path, text: str) -> int:
    """Links in the body of a page, in either format.

    A link is how this site defers a detail instead of deleting it, so it is counted like
    any other budget."""
    if path.suffix == ".topic":
        try:
            return sum(1 for _line, tag, _anc, _attrs, _body in topic_events(text)
                       if tag == "a")
        except Exception:
            return 0
    return len(re.findall(r"\[[^\]]+\]\([^)]+\)", text))


def structures_of(path: Path, text: str) -> int:
    """Places on the page where the eye can stop, other than the end of a paragraph."""
    if path.suffix == ".topic":
        try:
            return sum(1 for _line, tag, _anc, _attrs, _body in topic_events(text)
                       if tag in STOPPING_POINT_TAGS)
        except Exception:
            return 0
    return len(re.findall(r"(?m)^\s*(?:#{2,}\s|[*-]\s|\||!\[|```)", text))


def payload_line(path: Path, text: str, kind: str) -> int | None:
    """The line where the page hands the reader what it exists to hand over.

    A howto is its sample, an explain page is its diagram, a reference page is its table.
    Everything above that line is what the reader pays to get there."""

    def payload(tag: str, attrs: dict[str, str]) -> bool:
        if kind == "explain":
            return tag == "img" or (tag == "code-block" and attrs.get("lang") == "mermaid")
        return tag in PAYLOAD_TAGS.get(kind, ())

    if path.suffix == ".topic":
        try:
            lines = [line for line, tag, _anc, attrs, _body in topic_events(text)
                     if payload(tag, attrs)]
        except Exception:
            return None
        return min(lines) if lines else None

    patterns = {"howto": r"^```", "explain": r"^```\s*mermaid\b|^!\[|<img\b",
                "reference": r"^\s*\|"}
    found = re.search(patterns.get(kind, r"(?!x)x"), text, re.MULTILINE)
    return text[:found.start()].count("\n") + 1 if found else None


def budget_findings(path: Path, text: str, kind: str) -> list[tuple[str, str]]:
    """The four whole-page rules, as (line suffix, message) pairs.

    Separated from the check that reports them so selftest.py can drive them against a page
    written to break each one. A rule nothing exercises is an assertion, not a test."""
    out: list[tuple[str, str]] = []
    prose = [(line, body) for line, body in paragraphs_of(path, text) if body]
    words = sum(len(body.split()) for _line, body in prose)

    limit = MAX_PAGE_WORDS.get(kind)
    if limit and words > limit:
        out.append(("", f"{words} words of prose on {article(kind)} page (limit {limit}). "
                        "Half the documentation this site is modelled on runs under 360. "
                        "Cut to the question the page answers and put the rest behind a link"))

    count = max(0, len(chapter_words(path, text)) - 1)
    cap = MAX_CHAPTERS.get(kind)
    if cap and count > cap:
        out.append(("", f"{count} chapters on {article(kind)} page (limit {cap}); their "
                        "median page has two. A page cut into this many parts is several "
                        "pages, or one page that kept the parts it should have linked to"))

    if words >= LINK_DENSITY_FLOOR_WORDS:
        links = links_of(path, text)
        needed = math.ceil(words * MIN_LINKS_PER_100_WORDS / 100)
        if links < needed:
            out.append(("", f"{links} links in {words} words (at least {needed} at this "
                            "length). A page this long that points nowhere has kept "
                            "everything it should have deferred; every detail this page "
                            "decided not to cover is a link it owes the reader"))

    if words >= STRUCTURE_FLOOR_WORDS:
        structures = structures_of(path, text)
        spacing = words / max(structures, 1)
        if spacing > MAX_WORDS_PER_STRUCTURE:
            out.append(("", f"one heading, list item, row, step, picture or code block every "
                            f"{spacing:.0f} words of running text (limit "
                            f"{MAX_WORDS_PER_STRUCTURE}); {structures} of them carry {words} "
                            "words. A page may be long. It may not be a column of paragraphs"))

    start = payload_line(path, text, kind)
    budget = MAX_WORDS_TO_PAYLOAD.get(kind)
    if start is not None and budget is not None:
        before = sum(len(body.split()) for line, body in prose if line < start)
        if before > budget:
            out.append((f":{start}",
                        f"{before} words before {PAYLOAD_NAMES[kind]} (limit {budget}); a "
                        f"reader who opened {article(kind)} page came for that, and "
                        "everything above it is what they pay to reach it"))
    return out


def check_budget(topics: list[Path]) -> None:
    """What a page costs the reader as a whole, rather than sentence by sentence.

    Nothing above this counts a total. A page of twenty compliant paragraphs passes every
    readability limit there is and is still twenty paragraphs, and the reader who arrived
    with one question reads all of it or gives up. These four rules are the total: how long
    the page runs, how many headings it is cut into, how much of it is deferred through a
    link rather than written out, and how far the reader walks before the page pays."""
    kinds = page_kinds()

    for path in topics:
        text = read(path)
        kind = kinds.get(path.name)
        if kind is None:
            continue                      # check_pages reports a page PAGES.tsv does not know
        if path.suffix == ".topic" and "<section-starting-page>" in text:
            continue                      # a card page is checked by check_starting_page
        for suffix, message in budget_findings(path, text, kind):
            error(rel(path) + suffix, message)


def check_layout(topics: list[Path]) -> None:
    """The shape of a page, as opposed to the length of its sentences.

    Seven pages passed every readability limit and still read as undifferentiated text,
    because a limit on a paragraph says nothing about a page that is nothing but paragraphs.
    These rules are the shape of the reference documentation this site is modelled on: a
    heading often enough to be an index, a picture of anything with a shape, a procedure for
    anything done in order, and a table for anything enumerated."""
    kinds = page_kinds()

    for path in topics:
        where = rel(path)
        text = read(path)
        kind = kinds.get(path.name)

        if path.suffix == ".topic" and "<section-starting-page>" in text:
            check_starting_page(where, text)
            continue

        check_opening(where, text)

        for line, heading, words in chapter_words(path, text):
            if heading == "(opening)":
                if words > MAX_OPENING_WORDS:
                    error(f"{where}:{line}",
                          f"{words} words between the H1 and the first heading (limit "
                          f"{MAX_OPENING_WORDS}); a reader should be inside the first chapter "
                          "after one lead paragraph, and the rest belongs under a heading")
                continue
            if words > MAX_WORDS_PER_CHAPTER:
                error(f"{where}:{line}",
                      f'{words} words of prose under "{heading}" (limit '
                      f"{MAX_WORDS_PER_CHAPTER}); give it sub-headings, or move part of it "
                      "into a table, a procedure or a diagram")

        if kind in ("explain", "howto") and not has_diagram(text):
            error(where,
                  f"an {kind} page with no diagram; anything with an order, a flow, a state "
                  "change or a comparison is a picture. Write it as "
                  '<code-block lang="mermaid">, which this builder renders')

        if kind == "howto" and "<procedure" not in text:
            error(where, "a howto page with no <procedure>; the steps a reader follows are "
                         "numbered steps, not paragraphs")

        if kind == "reference" and not re.search(r"<(table|deflist)\b", text):
            error(where, "a reference page with no <table> and no <deflist>; a reference "
                         "page enumerates, and an enumeration is a table")


def blocks_of(text: str, suffix: str = ".md") -> list[tuple[int, str]]:
    """The page cut into the units a rule about "the same block" means: a prose paragraph, a
    table cell, a definition body. A remedy two cells away is not in the same block."""
    out: list[tuple[int, str]] = []

    if suffix == ".topic":
        for line, tag, _ancestors, attrs, body in in_document_order(topic_events(text)):
            if tag in ("code", "code-block"):
                continue
            if body:
                out.append((line, body))
            # A title is prose the reader reads, and it is an attribute rather than text.
            for name in ("title", "summary", "alt"):
                if attrs.get(name):
                    out.append((line, attrs[name]))
        return out

    for line, paragraph in prose_paragraphs(text):
        if paragraph:
            out.append((line, paragraph))
    for line, cells in table_rows(text):
        out.extend((line, cell) for cell in cells)
    for match in re.finditer(r"<def\b[^>]*>(.*?)</def>", text, re.DOTALL):
        out.append((text[:match.start()].count("\n") + 1, match.group(1)))
    return out


def words_of(text: str) -> list[str]:
    """Lower-cased words of running text, with code spans, XML and variables taken out. What
    a rule about wording applies to, as opposed to what a rule about markup applies to."""
    stripped = re.sub(r"`[^`]*`|<code>.*?</code>|<[^>]+>|%[a-z-]+%|https?://\S+", " ",
                      text, flags=re.DOTALL)
    # i.e. and e.g. tokenise into single letters, and one of them is the first person singular.
    stripped = re.sub(r"\b(?:i\.e\.|e\.g\.)", " ", stripped, flags=re.IGNORECASE)
    return re.findall(r"[a-z']+", stripped.lower())


def register_findings(text: str, kind: str | None,
                      suffix: str = ".md") -> list[tuple[int, str]]:
    """Filler, first person, and second person where the kind does not allow it."""
    found: list[tuple[int, str]] = []
    for line, paragraph in blocks_of(text, suffix):
        words = words_of(paragraph)
        joined = " ".join(words)

        for banned in BANNED_WORDS:
            if re.search(rf"\b{re.escape(banned)}\b", joined):
                found.append((line, f'"{banned}" is filler; state the thing without it'))

        for person in FIRST_PERSON:
            if person in words:
                found.append((line, f'first person "{person}"; the documentation has no author '
                                    "speaking in it"))
    return found


def opening_of(text: str, suffix: str = ".md") -> str:
    """Everything before the page's first chapter, with the structure taken out.

    On a Markdown page that is the H1 to the first `##`; on a .topic it is the text before
    the first <chapter>. The rule is the same in both: the first thing the reader reads."""
    if suffix == ".topic":
        opening: list[str] = []
        for line, tag, ancestors, _attrs, body in in_document_order(topic_events(text)):
            if tag == "chapter" and not any(a == "chapter" for a in ancestors):
                break
            if tag == "p" and not any(a in TOPIC_CONTAINERS for a in ancestors):
                opening.append(body)
        return " ".join(opening)

    body = text.split("\n## ")[0]
    return " ".join(p for _, p in prose_paragraphs(body) if p)


def remedy_findings(text: str, suffix: str = ".md") -> list[tuple[int, str]]:
    """Every diagnostic code needs what to do about it, in the block that names it."""
    found: list[tuple[int, str]] = []
    for line, block in blocks_of(text, suffix):
        codes = re.findall(r"\bAW\d{4}\b", block)
        if not codes:
            continue
        prose = re.sub(r"`[^`]*`|<code>.*?</code>|<[^>]+>", " ", block, flags=re.DOTALL)
        if any(escape in " ".join(words_of(block)) for escape in REMEDY_ESCAPE):
            continue
        # A remedy is an imperative, so the verb opens a clause. The same verb in the middle of
        # one is describing the refusal rather than answering it: "a field the target does not
        # declare" is not a remedy, "declare it @Unique" is.
        imperative = r"(?:^|[;:.—–]|,\s+(?:or|and))\s*(?:%s)\b" % "|".join(REMEDY_VERBS)
        if re.search(imperative, prose.strip(), re.IGNORECASE | re.MULTILINE):
            continue
        found.append((line, f"{', '.join(sorted(set(codes)))} named with nothing to do about "
                            "it; give the remedy here, or write that the source states none"))
    return found


def shingles_of(text: str) -> dict[tuple[str, ...], int]:
    """Every run of DUPLICATE_SHINGLE_WORDS words of prose on the page, to the line it starts
    on. Prose only: a shared table header or code sample is not a fact written twice."""
    out: dict[tuple[str, ...], int] = {}
    for line, paragraph in prose_paragraphs(text):
        if not paragraph:
            continue
        words = words_of(paragraph)
        for i in range(len(words) - DUPLICATE_SHINGLE_WORDS + 1):
            out.setdefault(tuple(words[i:i + DUPLICATE_SHINGLE_WORDS]), line)
    return out


def check_rules_behave() -> None:
    """Run the rules against the cases written for them.

    A content rule only fires on a page that breaks it, and such a page does not get
    committed, so the site itself cannot exercise these. Without this the checks would be
    asserted rather than tested -- which is the failure they were added to prevent."""
    selftest = Path(__file__).with_name("selftest.py")
    if not selftest.exists():
        error("selftest", "selftest.py is missing; the content rules are unverified")
        return
    done = subprocess.run([sys.executable, str(selftest)], capture_output=True, text=True)
    if done.returncode != 0:
        detail = (done.stdout + done.stderr).strip().splitlines()
        error("selftest", "the content rules do not behave as specified:\n    "
              + "\n    ".join(detail[:12]))


def mermaid_findings(text: str) -> list[tuple[int, str]]:
    """What a diagram's source says about whether anybody read it back.

    A rendered diagram is checked for width in the built page, and its meaning is a reviewer's
    job. These are the two defects visible in the source: a label carrying a character
    reference instead of the character, which means it was assembled rather than written, and
    a label that says the same word twice -- "Target - the target" shipped on this site."""
    findings: list[tuple[int, str]] = []
    for block in re.finditer(r'<code-block[^>]*lang="mermaid"[^>]*>(.*?)</code-block>',
                             text, re.DOTALL):
        start = text[:block.start()].count("\n") + 1
        for offset, line in enumerate(block.group(1).split("\n")):
            for label in re.findall(r'\["([^"]+)"\]|\{"?([^"}]+)"?\}', line):
                label = (label[0] or label[1]).strip()
                if "&#" in label or "&amp;" in label:
                    findings.append((start + offset,
                                     f'diagram label "{label}" carries a character reference; '
                                     "write the character"))
                words = [w.lower().strip(",.:;()") for w in label.split() if len(w) > 2]
                if len(words) != len(set(words)):
                    findings.append((start + offset,
                                     f'diagram label "{label}" repeats a word; a node labelled '
                                     "with its own name tells the reader nothing"))
    return findings


def check_mermaid(topics: list[Path]) -> None:
    for path in topics:
        for line, message in mermaid_findings(read_raw(path)):
            error(f"{rel(path)}:{line}", message)


def check_register(topics: list[Path]) -> None:
    """Register, kept by a closed list rather than by whoever reviews the page."""
    kinds = page_kinds()
    for path in topics:
        for line, message in register_findings(read(path), kinds.get(path.name), path.suffix):
            error(f"{rel(path)}:{line}", message)


def check_self_announcing(topics: list[Path]) -> None:
    """The opening answers the reader's question or it is wasted."""
    for path in topics:
        opening = opening_of(read(path), path.suffix).strip().lower()
        for pattern in SELF_ANNOUNCING:
            if re.match(pattern, opening):
                error(rel(path),
                      "the page opens by announcing itself; the first sentence states what "
                      "the reader came for, not what the page is")
                break


def check_remedies(topics: list[Path]) -> None:
    """A diagnostic code with no remedy is a dead end for the reader who hit it."""
    for path in topics:
        for line, message in remedy_findings(read(path), path.suffix):
            error(f"{rel(path)}:{line}", message)


def check_duplicate_facts(topics: list[Path]) -> None:
    """A fact lives in exactly one place. The copy is what goes stale."""
    seen: dict[tuple[str, ...], tuple[str, int]] = {}
    for path in sorted(topics):
        where = rel(path)
        for shingle, line in shingles_of(read(path)).items():
            if shingle in seen:
                first, first_line = seen[shingle]
                if first != where:
                    error(f"{where}:{line}",
                          f'"{" ".join(shingle)}" is already written at {first}:{first_line}; '
                          "a fact lives in exactly one place and the other page links to it")
            else:
                seen[shingle] = (where, line)


def table_width_findings(rows: list[tuple[int, list[str]]]) -> list[tuple[int, str]]:
    """What a table costs in pixels before any stylesheet helps it.

    Each column is as wide as its longest cell, so the page's own content decides whether the
    table fits. A table inside this budget needs no CSS to fit, which matters because the
    stylesheet under cfg/static reaches the published site and is not guaranteed to reach the
    editor's preview -- and a table that only fits in one of the two is a table that gets
    reported as broken."""
    if not rows:
        return []
    columns = max(len(cells) for _line, cells in rows)
    widest = [0] * columns
    for _line, cells in rows:
        for index, cell in enumerate(cells[:columns]):
            widest[index] = max(widest[index], len(re.sub(r"[`*]", "", cell)))

    total = sum(widest)
    if total <= MAX_TABLE_WIDTH_CHARS:
        return []
    pixels = round(total * PIXELS_PER_TABLE_CHARACTER)
    return [(rows[0][0],
             f"the table is {total} characters across its widest cells, about {pixels}px, "
             f"and the column it renders in is {ARTICLE_WIDTH_PX}px (budget "
             f"{MAX_TABLE_WIDTH_CHARS} characters). Shorten the cells, drop a column, or "
             "make it a <deflist>, which has the whole width to wrap in")]


def check_diagrams() -> None:
    """A hand-written SVG is drawn in its own coordinate system and rendered into 843px.

    Mermaid is the default and is measured from the built page instead, in run_builder;
    this governs the rare drawing mermaid cannot express.

    Nothing in the builder or the browser reports a diagram whose text arrived too small to read;
    it simply arrives too small. The one number that decides it is the ratio between the viewBox
    width and the column, so that is what is checked here."""
    for svg in sorted((MODULE / "images").glob("*.svg")):
        text = read_raw(svg)

        box = re.search(r'viewBox="\s*[-\d.]+\s+[-\d.]+\s+([\d.]+)', text)
        sizes = [float(s) for s in re.findall(r'font-size="([\d.]+)"', text)]
        if box is None or not sizes:
            continue

        width = float(box.group(1))
        if width > MAX_DIAGRAM_VIEWBOX:
            error(rel(svg),
                  f"its viewBox is {width:g} units wide (limit {MAX_DIAGRAM_VIEWBOX}); a "
                  "drawing wider than that is scaled down to fit the column and its labels "
                  "with it. Stack what is side by side and let the diagram be tall")
        if min(sizes) < MIN_DIAGRAM_FONT:
            error(rel(svg),
                  f"its smallest font-size is {min(sizes):g} (minimum {MIN_DIAGRAM_FONT})")

        scale = min(1.0, ARTICLE_WIDTH_PX / width)
        smallest = min(sizes) * scale
        if smallest < MIN_DIAGRAM_LABEL_PX:
            error(rel(svg),
                  f"its smallest label reaches the reader at {smallest:.1f}px (minimum "
                  f"{MIN_DIAGRAM_LABEL_PX}px): a viewBox {width:g} units wide is scaled to the "
                  f"article's {ARTICLE_WIDTH_PX}px, and the text is scaled with it. Lay the "
                  "drawing out in a narrower viewBox -- stack what is side by side -- rather "
                  "than enlarging the type inside the one it has")


def table_rows(text: str) -> list[tuple[int, list[str]]]:
    """Every table row on a page, as (line, cells), from both forms a topic may use.

    Markdown pipe tables and injected <table> elements render to the same thing, so they are
    held to the same limits. The alignment row of a pipe table is not a row of content."""
    rows: list[tuple[int, list[str]]] = []

    for number, line in enumerate(text.splitlines(), start=1):
        stripped = line.strip()
        if not stripped.startswith("|") or not stripped.endswith("|"):
            continue
        cells = [c.strip() for c in stripped[1:-1].split("|")]
        if all(re.fullmatch(r":?-{2,}:?", c) for c in cells):
            continue
        rows.append((number, cells))

    for match in re.finditer(r"<tr\b.*?</tr>", text, re.DOTALL):
        cells = [re.sub(r"<[^>]+>", "", c).strip()
                 for c in re.findall(r"<t[dh]\b[^>]*>(.*?)</t[dh]>", match.group(0), re.DOTALL)]
        if cells:
            rows.append((text[:match.start()].count("\n") + 1, cells))

    return rows


def check_tables(topics: list[Path]) -> None:
    """A table has to fit the column it is rendered in, and the column is 843px wide.

    The builder gives a table `width: max-content` and a wrapper that scrolls, so a table that
    does not fit is not wrapped, it is cut off: the reader sees the first two columns of six and
    a scroll arrow. Capping the width in CSS rescues a table that is merely wide. It cannot
    rescue one that is the wrong shape, which is what these limits are for."""
    for path in topics:
        where = rel(path)
        text = read(path)

        for line, message in table_width_findings(table_rows(text)):
            error(f"{where}:{line}", message)

        for line, cells in table_rows(text):
            if len(cells) > MAX_TABLE_COLUMNS:
                error(f"{where}:{line}",
                      f"a table row of {len(cells)} cells (limit {MAX_TABLE_COLUMNS} columns); "
                      "at this width the columns are squeezed under 110px each and an "
                      "unbreakable code token pushes the table off the page. Split it, or give "
                      "each row of it a <deflist> term")

            for cell in cells:
                # A code span costs its own characters; the text is what has to wrap.
                plain = re.sub(r"[`*]", "", cell)
                if len(plain) > MAX_TABLE_CELL_CHARS:
                    error(f"{where}:{line}",
                          f"a table cell of {len(plain)} characters (limit "
                          f"{MAX_TABLE_CELL_CHARS}); that is a paragraph, and a paragraph in a "
                          "cell either widens the table off the page or wraps into a column of "
                          "single words. Put it in prose or in a <deflist>")


def check_opening(where: str, text: str) -> None:
    """<tldr> is a box, and a box has to be short enough to be read as one.

    It is optional. Use it for a precondition the reader must have before the first
    paragraph -- "requires Install", "Maven only" -- and for nothing else. The facts that
    used to be crammed into it belong in the chapter that needs them."""
    block = re.search(r"<tldr>(.*?)</tldr>", text, re.S)
    if block is None:
        return
    inner = block.group(1)
    line = text[:block.start()].count("\n") + 1
    words = len(re.sub(r"<[^>]+>", " ", inner).split())
    facts = len(re.findall(r"<p\b", inner))
    if words > MAX_TLDR_WORDS:
        error(f"{where}:{line}",
              f"<tldr> runs to {words} words (limit {MAX_TLDR_WORDS}); it is a box above the "
              "page, not a summary of it. Keep the precondition and move the rest into the "
              "chapter that needs it, or drop the block")
    if facts > MAX_TLDR_FACTS:
        error(f"{where}:{line}",
              f"<tldr> holds {facts} facts (limit {MAX_TLDR_FACTS})")


def check_starting_page(where: str, text: str) -> None:
    """A hub is cards, and a card is a title, an icon and a summary.

    The builder only warns about a missing summary, and says nothing at all about a missing
    icon, so a hub degrades into a list of bare links without anything failing."""
    for group, entries in re.findall(
            r"<(spotlight|primary|secondary)>(.*?)</\1>", text, re.S):
        cards = re.findall(r"<(?:a|card)\b[^>]*>", entries)
        for card in cards:
            name = re.search(r'href="([^"]+)"', card)
            name = name.group(1) if name else card[:40]
            if "summary=" not in card:
                error(where, f'card "{name}" in <{group}> has no summary; a card without one '
                             "is a bare link and the reader cannot tell what it gets")
            if not any(f"{attr}=" in card for attr in CARD_ICON_ATTRS):
                error(where, f'card "{name}" in <{group}> has no badge or type; the icon is '
                             "the only part of a card read before the text")
        if group == "spotlight" and len(cards) != 2:
            error(where, f"<spotlight> holds {len(cards)} cards; it renders exactly two")


def check_fences(topics: list[Path]) -> None:
    """A fence language the builder does not render is dropped from the page without a
    diagnostic. The builder reports the topic as clean and the content is simply gone."""
    for path in topics:
        text = read(path)
        where = rel(path)
        for number, line in enumerate(text.split("\n"), 1):
            match = re.match(r"^```(\w[\w+-]*)", line.strip())
            if match and match.group(1).lower() not in FENCE_LANGUAGES:
                error(f"{where}:{number}",
                      f'code fence language "{match.group(1)}" is not one this builder '
                      "renders; its content is dropped silently. For a diagram, put an SVG "
                      "in images/ and reference it with <img>")
        for lang in re.findall(r'<code-block[^>]*\blang="([^"]+)"', text):
            if lang.lower() not in FENCE_LANGUAGES:
                error(where, f'<code-block lang="{lang}"> is not rendered by this builder; '
                             "its content is dropped silently")


# ---------------------------------------------------------------------------------------
# The work breakdown
# ---------------------------------------------------------------------------------------

def check_stale_placeholders(topics: list[Path], by_name: dict[str, Path]) -> None:
    """A link to the placeholder whose text names a page that now exists.

    The link resolves, so nothing else on this site notices. What the reader gets is
    "Not written yet" for a page they could have been reading, and the page that just
    landed collects no inbound links at all -- which happened to extension-methods.topic,
    reachable from nothing, three batches after the habit started. Hand-fixing this after
    every batch is what this check replaces.

    Matching the link text against the title alone was half a check. A writer promising a
    page writes the promise in the sentence's own words -- "the annotation reference", not
    "Annotations" -- so the two links that were already stale when this check was written
    were both invisible to it, and thirty-five more were queued to go the same way. The
    aliases column carries those words, and an unresolvable placeholder is an error rather
    than a silent debt: whoever writes the promise decides which page keeps it, at the
    moment they write it, while they still know."""
    if not PAGES.exists():
        return
    with PAGES.open(encoding="utf-8") as handle:
        rows = list(csv.DictReader(handle, delimiter="\t"))

    names: dict[str, tuple[str, bool]] = {}
    for row in rows:
        target, exists = os.path.basename(row["file"]), (TOPICS / row["file"]).exists()
        for name in [row["title"]] + (row.get("aliases") or "").split(";"):
            name = name.strip().lower()
            if name:
                names[name] = (target, exists)

    for path in topics:
        for line_number, message in placeholder_findings(os.path.basename(str(path)),
                                                         read(path), names):
            error(f"{rel(path)}:{line_number}", message)


def placeholder_findings(file_name: str, text: str,
                         names: dict[str, tuple[str, bool]]) -> list[tuple[int, str]]:
    """Every placeholder link in one page, judged against the map of promised names.

    Split out from the check so selftest.py can drive it the way it drives the budgets."""
    findings = []
    # Over the whole text rather than a line at a time. A card on a section hub carries its
    # type and its summary on the lines after the href, so every one of them was invisible to
    # a per-line scan -- including the seven on reference.topic promising pages that existed.
    for match in re.finditer(r'<a\b[^>]*\bhref="tba\.topic"[^>]*>(.*?)</a>', text, re.S):
        line_number = text.count("\n", 0, match.start()) + 1
        # Collapse whitespace: a label that wraps across two source lines is the same
        # promise as one that does not, and matching it raw let a wrapped label name a
        # page that exists and go unreported.
        label = re.sub(r"\s+", " ", re.sub(r"<[^>]+>", "", match.group(1))).strip().lower()
        if label not in names:
            findings.append((line_number,
                             f'promises "{label}", which names no page in PAGES.tsv; put '
                             "those words in the aliases column of the page that will keep "
                             "the promise"))
            continue
        target, exists = names[label]
        if exists and file_name != target:
            findings.append((line_number,
                             f'links "{label}" to the placeholder, but {target} exists and '
                             "is that page; point at it"))
    return findings


def check_nesting(topics: list[Path]) -> None:
    """Where PAGES.tsv gives a page a parent, the tree must nest it there.

    The tree was a flat list per section, and the hub cards were not: one section described
    its own shape two ways. Nesting it by hand does not survive, because the next writer adds
    a toc-element wherever there is room -- so the parent column is the source of truth and
    this check is what makes it one.

    A parent is only ever a page, never a heading. A toc-element with a title and no topic is
    a node nothing happens when you click, which is the defect the sections were fixed for."""
    trees = sorted(MODULE.glob("*.tree"))
    if not PAGES.exists() or not trees:
        return
    with PAGES.open(encoding="utf-8") as handle:
        rows = {r["page"]: r for r in csv.DictReader(handle, delimiter="\t")}

    files = {page: os.path.basename(row["file"]) for page, row in rows.items()}
    tree_path = trees[0]
    tree = read(tree_path)

    for page, row in rows.items():
        parent = (row.get("parent") or "").strip()
        if not parent:
            continue
        if parent not in rows:
            error(f"PAGES.tsv[{page}]", f'parent "{parent}" is not a page id')
            continue
        if rows[parent]["section"] != row["section"]:
            error(f"PAGES.tsv[{page}]",
                  f'parent "{parent}" is in section {rows[parent]["section"]}, not '
                  f'{row["section"]}; a page is nested inside its own section')
            continue
        if row["state"] != "done" or rows[parent]["state"] != "done":
            continue                      # nothing to check until both are in the tree

        child, owner = files[page], files[parent]
        # The parent's element must be the one that opens, and the child must sit inside it
        # before it closes. Writerside nests by containment, so a self-closing parent cannot
        # have children at all.
        opened = re.search(r'<toc-element topic="%s"\s*>' % re.escape(owner), tree)
        if not opened:
            error(rel(tree_path),
                  f'{owner} is written self-closing but {child} names it as its parent; '
                  "a parent element has to be opened so its children sit inside it")
            continue
        depth, position, found = 0, opened.end(), False
        for match in re.finditer(r"<toc-element[^>]*?(/?)>|</toc-element>", tree[position:]):
            token = match.group(0)
            if token.startswith("</"):
                if depth == 0:
                    break
                depth -= 1
            elif not match.group(1):
                depth += 1
            if f'topic="{child}"' in token:
                found = True
                break
        if not found:
            error(rel(tree_path),
                  f'{child} is not nested under {owner}, which PAGES.tsv gives as its parent')


def check_inbound_links(topics: list[Path], by_name: dict[str, Path]) -> None:
    """A finished page nothing links to.

    Twice in two days a page landed that no other page pointed at: extension-methods.topic,
    reachable from nothing after three batches, and weave-discovery.topic, which exists only
    because three pages needed it and none of them linked to it once it did. Both passed
    every other check, because a link that is missing breaks nothing.

    A hub is exempt: its inbound link is the tree. So is the placeholder, and so is a page a
    tree names as its start page."""
    if not PAGES.exists():
        return
    with PAGES.open(encoding="utf-8") as handle:
        rows = [r for r in csv.DictReader(handle, delimiter="\t")]

    starts = set()
    for tree in MODULE.glob("*.tree"):
        starts.update(re.findall(r'start-page="([^"]+)"', read(tree)))

    linked: set[str] = set()
    for path in topics:
        text = read(path)
        for target in re.findall(r'href="([^"#]+\.topic)(?:#[^"]*)?"', text):
            target = os.path.basename(target)
            if target != path.name:
                linked.add(target)

    for row in rows:
        # `doing` counts too. A page being written is exactly when its author can still
        # find the pages whose subject stops where theirs starts; waiting for `done` means
        # the gap is only reported on the next run, after the commit.
        if row["state"] not in ("done", "doing") or row["kind"] == "hub":
            continue
        name = os.path.basename(row["file"])
        if name in linked or name in starts or not (TOPICS / row["file"]).exists():
            continue
        error(f"PAGES.tsv[{row['page']}]",
              f"{name} is written and no other page links to it; a reader reaches it only "
              "through the tree. Find the pages whose subject stops where this one starts")


def check_pages(topics: list[Path], referenced: set[str]) -> None:
    if not PAGES.exists():
        error(rel(PAGES), "missing")
        return

    with PAGES.open(encoding="utf-8") as handle:
        rows = list(csv.DictReader(handle, delimiter="\t"))

    seen_pages: set[str] = set()
    seen_files: set[str] = set()
    doing = [r["page"] for r in rows if r["state"] == "doing"]

    for row in rows:
        page, where = row["page"], f"PAGES.tsv[{row['page']}]"

        if page in seen_pages:
            error(where, "duplicate page id")
        seen_pages.add(page)

        if row["file"] in seen_files:
            error(where, f'duplicate file "{row["file"]}"')
        seen_files.add(row["file"])

        if row["state"] not in VALID_STATES:
            error(where, f'state "{row["state"]}" is not one of {sorted(VALID_STATES)}')
        if row["kind"] not in VALID_KINDS:
            error(where, f'kind "{row["kind"]}" is not one of {sorted(VALID_KINDS)}')

        path = TOPICS / row["file"]
        if row["state"] == "done":
            if not path.exists():
                error(where, f'marked done but {row["file"]} does not exist')
            elif path.name not in referenced:
                error(where, f'marked done but {row["file"]} is not in the tree')
            if not row["commit"]:
                error(where, "marked done with no commit recorded")
        elif row["state"] == "todo" and path.exists():
            # A page that exists while its row still says todo was written without claiming
            # the row, which is how two writers end up on one file. A `doing` row having a
            # file is the normal state of a page being written and is not a finding: that
            # check fired on every successful run for five pages and taught everyone reading
            # the gate to skip a line.
            error(where, f'state is "todo" but {row["file"]} already exists; claim the row '
                         "before writing, so a second writer can see it is taken")

        for source in filter(None, row["sources"].split(";")):
            if not (ROOT / source).exists():
                error(where, f'source "{source}" does not exist')

    # More than one page may be written at once. The rule used to forbid it, which made the
    # gate the reason the site could only be written serially; what it was actually guarding
    # against -- a run abandoned half way -- is caught by naming every claimed row below, and
    # by the fact that a `doing` row can never be committed as `done` without a file, a tree
    # entry and a commit hash.
    #
    # What concurrent writers must not share is the tree: three implementers editing
    # aw.tree at once overwrite each other. Whoever launches them wires the tree afterwards.

    planned = {r["file"] for r in rows}
    for path in topics:
        as_listed = str(path.relative_to(TOPICS)).replace(os.sep, "/")
        if as_listed not in planned:
            error(rel(path), "exists but has no row in PAGES.tsv")

    done = sum(1 for r in rows if r["state"] == "done")
    notes.append(f"PAGES.tsv: {done}/{len(rows)} done"
                 + (f", doing: {', '.join(doing)}" if doing else ""))


# ---------------------------------------------------------------------------------------

def run_builder() -> None:
    """Build the instance with the installed Writerside builder and read its report.

    Warnings are treated as failures. The builder grades a dead anchor and a card with no
    summary as warnings, and on a site this small there is no such thing as a warning worth
    keeping."""
    binary = shutil.which("writerside")
    if binary is None:
        error("builder", "`writerside` is not on PATH, so --build cannot run")
        return

    with tempfile.TemporaryDirectory(prefix="docsite-build-") as workspace:
        out = Path(workspace) / "out"

        # The builder is IntelliJ headless, and its caches under ~/.cache/JetBrains do not
        # invalidate when a topic changes: a second run happily rebuilds the first run's
        # content and reports it clean. A gate that validates a stale copy is worse than no
        # gate, so every run gets a system path of its own and throws it away afterwards.
        # It costs a full index each time. Correctness is worth the minute.
        properties = Path(workspace) / "idea.properties"
        properties.write_text(
            f"idea.system.path={workspace}/system\n"
            f"idea.config.path={workspace}/config\n"
            f"idea.plugins.path={workspace}/config/plugins\n"
            f"idea.log.path={workspace}/system/log\n",
            encoding="utf-8",
        )

        # The diagram renderer is CEF, and it cannot find its shared libraries here without
        # this. Without it every mermaid diagram on the site fails as INT009 and the gate
        # reports the page broken for a reason that is the container rather than the page.
        libraries = [str(p) for p in CEF_LIBRARY_PATH if p.exists()]
        if os.environ.get("LD_LIBRARY_PATH"):
            libraries.append(os.environ["LD_LIBRARY_PATH"])

        environment = dict(
            os.environ,
            WRITERSIDE_SOURCE_DIR=str(ROOT),
            IDEA_PROPERTIES=str(properties),
            LD_LIBRARY_PATH=":".join(libraries),
        )
        completed = subprocess.run(
            [binary, f"{MODULE.name}/{INSTANCE}", str(out)],
            cwd=ROOT,
            env=environment,
            capture_output=True,
            text=True,
        )

        report_path = out / "report.json"
        if not report_path.exists():
            tail = "\n".join((completed.stderr or completed.stdout).splitlines()[-15:])
            error("builder", f"produced no report.json (exit {completed.returncode})\n{tail}")
            return

        report = json.loads(report_path.read_text(encoding="utf-8"))
        titles = report.get("idsAndTitles", {})

        # A warning is a failure here. The builder grades a dead anchor and a card with no
        # summary as warnings, and on a site this size there is no warning worth keeping.
        for severity in ("testsErrors", "testsWarnings"):
            for code, entries in (report.get(severity) or {}).items():
                title = titles.get(code, code)
                for entry in entries if isinstance(entries, list) else [entries]:
                    # `description` already reads `"what" in file:line:column`.
                    detail = entry.get("description") or entry.get("message") or "" \
                        if isinstance(entry, dict) else str(entry)
                    error(f"builder/{code}", f"{title}: {detail}".strip())

        notes.append(
            f"builder: {report.get('testsPassedCount', 0)}/{report.get('testsTotal', 0)} inspections passed, "
            f"{report.get('testsErrorsCount', 0)} error(s), {report.get('testsWarningsCount', 0)} warning(s)"
        )

        check_no_third_party(out)
        check_rendered_diagrams(out)


def check_rendered_diagrams(out: Path) -> None:
    """How wide a mermaid diagram came out, measured from the page the builder wrote.

    Mermaid decides its own size from the graph, so the width is a consequence of the
    diagram rather than something the writer sets. A flowchart laid out left to right grows
    without limit and is then scaled into the 843px column, taking its labels down with it --
    the same defect as a hand-drawn SVG in too wide a viewBox, arrived at from the other
    direction. `flowchart TB` is the remedy: height is free, width is not."""
    for page in sorted(out.rglob("*.html")):
        text = page.read_text(encoding="utf-8", errors="replace")
        for tag in re.findall(r"<svg\b[^>]*aria-roledescription=[^>]*>", text):
            box = re.search(r'viewBox="[-\d.]+ [-\d.]+ ([\d.]+)', tag)
            if not box:
                continue
            width = float(box.group(1))
            if width <= ARTICLE_WIDTH_PX:
                continue
            scale = ARTICLE_WIDTH_PX / width
            error(f"builder/{page.name}",
                  f"a rendered diagram is {width:.0f}px wide and the column is "
                  f"{ARTICLE_WIDTH_PX}px, so it is shown at {scale:.0%} and its labels with "
                  "it. Lay the graph out top to bottom rather than left to right, or split it")


def check_no_third_party(out: Path) -> None:
    """The published page must load nothing from a third party.

    A visitor in Germany who opens these docs must not have their IP address sent to
    anybody they have no relationship with, and there is no consent banner here to ask
    them first. <offline-docs> bundles the front end, its stylesheet, its fonts and the
    favicons into the output; cfg/head.html closes the one request that survives it, the
    front end's unconditional webinar lookup. Both were verified in a browser: fifteen
    requests, all to the site's own origin.

    This is what keeps it that way. A stylesheet or a script pulled from somewhere else is
    the failure; a link a reader may choose to follow is not, so only loaded resources
    count."""
    loader = re.compile(
        r'<(?:script|link)\b[^>]*\b(?:src|href)="(https?://[^"]+)"', re.IGNORECASE)
    for page in sorted(out.rglob("*.html")):
        for url in loader.findall(page.read_text(encoding="utf-8", errors="replace")):
            error(f"builder/{page.name}",
                  f"loads {url.split('/')[2]} from the page: {url[:90]}. The site has to serve "
                  "everything it loads from its own origin; see <offline-docs> and cfg/head.html")





def measure(target: str) -> int:
    """Print one page's figures beside their limit and beside the reference.

    The gate answers pass or fail, and pass is not the goal: a page sitting on every ceiling
    passes and reads like a page sitting on every ceiling. This prints where the page
    actually is, so the writer can see that it clears the bar the way the reference does
    rather than the way a rule allows."""
    path = (ROOT / target) if not Path(target).is_absolute() else Path(target)
    if not path.exists():
        candidates = [p for p in topic_files() if p.name == Path(target).name]
        if not candidates:
            print(f"no such page: {target}", file=sys.stderr)
            return 1
        path = candidates[0]

    text = read(path)
    kind = page_kinds().get(path.name, "explain")
    prose = [(line, body) for line, body in paragraphs_of(path, text) if body]
    words = sum(len(body.split()) for _line, body in prose)
    sentences = [len(s.split()) for _line, body in prose
                 for s in re.split(r"(?<=[.!?])\s+", body) if s.split()]
    chapters = max(0, len(chapter_words(path, text)) - 1)
    links = links_of(path, text)
    stops = structures_of(path, text)
    start = payload_line(path, text, kind)
    before = (sum(len(b.split()) for line, b in prose if line < start)
              if start is not None else None)

    # Each row carries the reference as a number as well as a phrase, because a page that
    # merely passes is the failure this whole file exists to catch and a writer cannot see
    # that from a pass. The marker says which of the two a figure is nearer.
    rows = [
        ("prose words", words, MAX_PAGE_WORDS.get(kind), 360, "median 360, p75 854"),
        # No drift marker: the corpus's 90th percentile for chapters is 7, above our own
        # limit of 6, so a page between their median and our ceiling is normal there and
        # marking it would train a writer to ignore the marker.
        ("chapters", chapters, MAX_CHAPTERS.get(kind), None, "median 2, p90 7"),
        ("links", links, None, 12, "median 12"),
        ("links per 100 words", round(words and links * 100 / words, 1),
         None, 1.8, "1.8, and 97% link out"),
        ("median sentence", statistics.median(sentences) if sentences else 0,
         MEDIAN_SENTENCE_WORDS, 9, "median 9, p75 13"),
        ("longest sentence", max(sentences) if sentences else 0,
         MAX_SENTENCE_WORDS, 21, "p90 21, p99 52"),
        ("words per stopping point", round(words / max(stops, 1), 1),
         MAX_WORDS_PER_STRUCTURE, 38, "median 38, p90 104"),
        (f"words before {PAYLOAD_NAMES.get(kind, 'the payload')}", before,
         MAX_WORDS_TO_PAYLOAD.get(kind), 180, "first heading at 180"),
    ]

    drifting = 0
    print(f"{rel(path)}  ({kind})")
    print(f"  {'':32} {'this page':>10}  {'limit':>7}          the reference")
    for name, value, limit, target, reference in rows:
        if value is None:
            continue
        mark = "      "
        if limit is not None:
            if value > limit:
                mark = "  OVER"
            elif target is not None and value > target and value - target > limit - value:
                # Past the reference and nearer the ceiling than the standard.
                mark = " DRIFT"
                drifting += 1
        print(f"  {name:32} {value:>10}  {limit if limit is not None else '-':>7}"
              f"{mark}  {reference}")

    if drifting:
        print(f"\n  {drifting} figure(s) marked DRIFT sit nearer the limit than the reference.")
        print("  A page there passes and reads like a page that passes. Cut, do not qualify.")
    print("\n  The limit is where a page stops being publishable. The reference is where the"
          "\n  documentation a reader finishes actually sits. Aim at the last column.")
    return 0


def main() -> int:
    if "--measure" in sys.argv:
        index = sys.argv.index("--measure")
        if index + 1 >= len(sys.argv):
            print("--measure needs a page: --measure start/install.topic", file=sys.stderr)
            return 1
        return measure(sys.argv[index + 1])

    if not MODULE.is_dir():
        print(f"no Writerside module at {rel(MODULE)}", file=sys.stderr)
        return 1

    configs = config_files()
    topics = topic_files()

    check_xml_well_formed(configs + [p for p in topics if p.suffix == ".topic"])
    by_name = check_unique_names(topics)
    check_topic_ids(topics)
    check_block_balance(topics)
    check_tree(by_name)

    referenced = tree_referenced_names()
    check_orphans(topics, referenced)
    check_references(topics, by_name)
    check_header_logo()
    check_prose(topics)
    check_fences(topics)
    check_layout(topics)
    check_budget(topics)
    check_tables(topics)
    check_diagrams()
    check_topic_schema(topics)
    check_rules_behave()
    check_register(topics)
    check_mermaid(topics)
    check_self_announcing(topics)
    check_remedies(topics)
    check_duplicate_facts(topics)
    check_versions()
    check_inlined_images()
    check_pages(topics, referenced)
    check_stale_placeholders(topics, by_name)
    check_nesting(topics)
    check_inbound_links(topics, by_name)

    if "--build" in sys.argv:
        run_builder()

    for note in notes:
        print(note)
    print(f"checked {len(topics)} topics and {len(configs)} configuration files")
    if "--build" not in sys.argv:
        print("project rules only -- pass --build to run the Writerside builder as well")

    if errors:
        print(f"\n{len(errors)} problem(s):\n", file=sys.stderr)
        for problem in errors:
            print(f"  {problem}", file=sys.stderr)
        return 1

    print("ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
