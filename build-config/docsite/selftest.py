#!/usr/bin/env python3
"""Exercise the content rules in check-docs.py against cases written for them.

The rules those checks apply cannot be exercised by the site itself: a rule only fires on a
page that breaks it, and a page that breaks it does not get committed. Without this file the
checks would be asserted rather than tested, which is the failure they exist to prevent.

    python3 build-config/docsite/selftest.py
"""
import importlib.util
import sys
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "checkdocs", Path(__file__).with_name("check-docs.py"))
cd = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cd)

failures: list[str] = []


def expect(name: str, condition: bool, detail: str = "") -> None:
    if not condition:
        failures.append(f"{name}: {detail}")


# --- version literals -----------------------------------------------------------------
# Every row in PAGES.tsv names a .topic file, so an exemption keyed on any other extension
# can never match the page it was written for.
expect("the version-literal exemption names files that exist",
       all(name in {row for row in cd.VERSION_LITERAL_ALLOWED}
           and name.endswith(".topic")
           for name in cd.VERSION_LITERAL_ALLOWED),
       f"non-.topic entry in {cd.VERSION_LITERAL_ALLOWED}")

# --- register -----------------------------------------------------------------------------
expect("filler is caught",
       any("simply" in m for _, m in cd.register_findings("Simply add the plugin.", "howto")))
expect("filler inside a table cell is caught",
       any("actually" in m for _, m in
           cd.register_findings("| a | b |\n| --- | --- |\n| x | name the class actually meant |",
                                "explain")))
expect("first person is caught",
       any("we" in m for _, m in cd.register_findings("We chose this order.", "explain")))
expect("second person is allowed on an explain page",
       not cd.register_findings("Your build fails here.", "explain"))
expect("second person is allowed on a howto page",
       not cd.register_findings("You add the plugin to your build.", "howto"))
expect("i.e. is not first person",
       not cd.register_findings("The target, i.e. the class named by the weave, is rewritten.",
                                "explain"))
expect("code spans are not prose",
       not cd.register_findings("The flag `--just-in-time` is read.", "explain"))
expect("clean prose passes",
       not cd.register_findings("The engine builds a plan once, before any class is offered.",
                                "explain"))

# --- self-announcing opening --------------------------------------------------------------
expect("self-announcing opening is caught",
       cd.re.match(cd.SELF_ANNOUNCING[0],
                   cd.opening_of("# T\n\nThis page turns an empty module into one that weaves.\n"
                                 ).strip().lower()))
expect("a real opening passes",
       not any(cd.re.match(p, cd.opening_of(
           "# T\n\nA weave is an ordinary Java class that names the classes it changes.\n"
       ).strip().lower()) for p in cd.SELF_ANNOUNCING))

# --- whole-page budgets -------------------------------------------------------------------
def topic(chapters: int, words_per: int, links: int = 0, diagram_after: int = 0) -> str:
    """A .topic built to a shape, so a budget rule can be aimed at one thing at a time."""
    body = []
    for _ in range(diagram_after):
        body.append("    <p>%s</p>" % " ".join(["word"] * 40))
    body.append('    <code-block lang="mermaid">flowchart LR\n  A --> B</code-block>')
    for n in range(chapters):
        paragraph = " ".join(["word"] * words_per)
        anchors = "".join(' <a href="tba.topic">detail</a>' for _ in range(links))
        body.append(f'    <chapter title="Part {n}" id="p{n}">\n'
                    f'      <p>{paragraph}{anchors}</p>\n    </chapter>')
    return ('<?xml version="1.0" encoding="UTF-8"?>\n'
            '<topic xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" id="t" title="T">\n'
            + "\n".join(body) + "\n</topic>\n")


def budget(text: str, kind: str = "explain") -> list[str]:
    return [m for _, m in cd.budget_findings(cd.Path("Writerside/topics/x.topic"), text, kind)]


expect("a page over its word budget is caught",
       any("words of prose" in m for m in budget(topic(6, 200, links=20))))
expect("a page inside its word budget passes that rule",
       not any("words of prose" in m for m in budget(topic(2, 100, links=4))))
expect("too many chapters is caught",
       any("chapters on" in m for m in budget(topic(8, 30, links=4))))
expect("six chapters on an explain page passes",
       not any("chapters on" in m for m in budget(topic(6, 30, links=4))))
expect("a column of paragraphs is caught however long it is",
       any("running text" in m for m in budget(topic(1, 400, links=8))))
expect("a page broken up by lists and rows passes",
       not any("running text" in m for m in budget(topic(6, 60, links=6))))
expect("a long page with no links is caught",
       any("links in" in m for m in budget(topic(3, 90, links=0))))
expect("a long page that links out passes",
       not any("links in" in m for m in budget(topic(3, 90, links=2))))
expect("a short page owes no links",
       not any("links in" in m for m in budget(topic(1, 60, links=0))))
expect("prose piled in front of the diagram is caught",
       any("before a diagram" in m for m in budget(topic(2, 60, links=4, diagram_after=6))))
expect("a diagram the reader reaches quickly passes",
       not any("before a diagram" in m for m in budget(topic(2, 60, links=4, diagram_after=1))))
expect("a howto is measured against its sample, not a diagram",
       any("code sample" in m for _, m in
           cd.budget_findings(cd.Path("Writerside/topics/x.topic"),
                              topic(2, 60, links=4, diagram_after=6), "howto")))

# --- remedies -----------------------------------------------------------------------------
expect("a bare code is caught",
       cd.remedy_findings("`AW1030` is reported for a field the target does not declare.\n"))
expect("a code with a remedy passes",
       not cd.remedy_findings("`AW1030` names a field the target does not declare; check the "
                              "name against the target's own version.\n"))
expect("a code stating no remedy passes",
       not cd.remedy_findings("`AW1031` is a field at the wrong type, and the source states no "
                              "remedy for it.\n"))
expect("a code in a table cell needs a remedy too",
       cd.remedy_findings("| Code | Meaning |\n| --- | --- |\n| `AW1087` | the target is a weave |"))

# --- event ordering ------------------------------------------------------------------------
# Two links to different targets on one source line tie on line, tag and ancestry, and the
# next thing a plain sorted() compares is the attribute dict. The checker raised TypeError
# instead of reporting, and a page hit it while being written.
two_links = ('<topic id="x" title="X"><p><a href="a.topic">A</a> '
             '<a href="b.topic">B</a></p></topic>')
expect("events on one line with different attributes still order",
       [t for _, t, *_ in cd.in_document_order(cd.topic_events(two_links))].count("a") == 2)

# --- placeholder promises -------------------------------------------------------------------
# Matching only the title let two links sit dead on a written page for days: a sentence
# promises "the annotation reference", never the row's own "Annotations".
names = {"annotations": ("annotations.topic", True),
         "the annotation reference": ("annotations.topic", True),
         "maven goals": ("maven-goals.topic", False)}
placeholders = lambda text, name="x.topic": [m for _, m in cd.placeholder_findings(name, text, names)]

expect("a promise kept by an alias of a written page is caught",
       any("exists" in m for m in placeholders('<a href="tba.topic">The annotation reference</a>')))
expect("a promise to an unwritten page passes",
       not placeholders('<a href="tba.topic">Maven goals</a>'))
expect("a promise naming no planned page is caught",
       any("names no page" in m
           for m in placeholders('<a href="tba.topic">The slice reference</a>')))
expect("markup inside the link text does not hide the name",
       any("exists" in m
           for m in placeholders('<a href="tba.topic"><code>Annotations</code></a>')))
expect("a page does not report a promise it keeps itself",
       not placeholders('<a href="tba.topic">Annotations</a>', "annotations.topic"))
expect("a label wrapped across two lines is still the same promise",
       any("exists" in m for m in placeholders(
           '<a href="tba.topic">The annotation\n   reference</a>')))
expect("a card carrying its summary on the next line is still seen",
       any("exists" in m for m in placeholders(
           '<a href="tba.topic" type="library"\n   summary="every one">Annotations</a>')))

# --- duplicate facts ----------------------------------------------------------------------
sentence = "the build refuses a jdk below the version the pom declares for it"
a, b = cd.shingles_of(f"# A\n\n{sentence}.\n"), cd.shingles_of(f"# B\n\n{sentence}.\n")
expect("a repeated sentence shares shingles", set(a) & set(b))
expect("different prose shares none",
       not (set(cd.shingles_of("# A\n\nThe engine builds a plan once before any class arrives.\n"))
            & set(cd.shingles_of("# B\n\nA driver contributes input and output and nothing else.\n"))))

# --- tables and diagrams, already enforced, guarded here against regression -----------------
expect("a five-column row is over the limit",
       len(cd.table_rows("| a | b | c | d | e |\n| --- | --- | --- | --- | --- |\n"
                         "| 1 | 2 | 3 | 4 | 5 |")[0][1]) > cd.MAX_TABLE_COLUMNS)
expect("an xml row is read like a markdown row",
       len(cd.table_rows("<tr><td>a</td><td>b</td><td>c</td></tr>")[0][1]) == 3)
expect("the alignment row is not a row",
       len(cd.table_rows("| a | b |\n| --- | --- |\n| 1 | 2 |")) == 2)

# --- .topic files ---------------------------------------------------------------------------
TOPIC = """<?xml version="1.0" encoding="UTF-8"?>
<topic xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" id="sample" title="Sample">
    <p>A weave names the classes it changes.</p>
    <chapter title="First" id="first">
        <p>The engine builds a plan once, before any class is offered to it.</p>
        <table><tr><td>Code</td><td>Meaning</td></tr><tr><td>AW1030</td><td>simply missing</td></tr></table>
    </chapter>
    <chapter title="Second" id="second">
        <p>Matching happens per class.</p>
    </chapter>
</topic>
"""

events = cd.topic_events(TOPIC)
expect("topic events carry line numbers",
       all(line > 0 for line, _t, _a, _at, _b in events))
expect("a top-level p is prose",
       any(tag == "p" and body.startswith("A weave") for _l, tag, _a, _at, body in events))

expect("the opening stops at the first chapter",
       cd.opening_of(TOPIC, ".topic").strip() == "A weave names the classes it changes.")

chapters = cd.chapter_words(cd.Path("x.topic"), TOPIC)
expect("a topic is cut at its chapters", [h for _l, h, _w in chapters] == ["(opening)", "First", "Second"],
       str([h for _l, h, _w in chapters]))
expect("words land in the chapter they are in",
       [w for _l, _h, w in chapters] == [7, 13, 4], str([w for _l, _h, w in chapters]))

expect("filler inside a topic table cell is caught",
       any("simply" in m for _l, m in cd.register_findings(TOPIC, "explain", ".topic")))
expect("a code in a topic cell needs a remedy",
       any("AW1030" in m for _l, m in cd.remedy_findings(TOPIC, ".topic")))
expect("clean topic prose passes the register",
       not [m for _l, m in cd.register_findings(
           TOPIC.replace("simply missing", "not declared by the target"), "explain", ".topic")])

allowed = cd.schema_elements()
expect("the schema is vendored and parses", len(allowed) > 50, f"{len(allowed)} elements")
for name in ("topic", "chapter", "procedure", "deflist", "tabs", "code-block", "seealso"):
    expect(f"the schema defines <{name}>", name in allowed)
expect("the schema does not define an invented element", "sidebar" not in allowed)

# --- the table width budget, which is what makes a table fit with no stylesheet ------------
narrow = cd.table_rows("| Code | Meaning |\n| --- | --- |\n| `AW1030` | field not declared |")
expect("a narrow table fits", not cd.table_width_findings(narrow))

wide = cd.table_rows(
    "| Driver | Reaches | What it never sees |\n| --- | --- | --- |\n"
    "| Agent | Every class the JVM defines once the transformer installs, nearly everything |"
    " Classes the JVM had already defined before the transformer installed |")
expect("a table of sentences is over budget", cd.table_width_findings(wide))
expect("the finding says what it costs",
       "843px" in cd.table_width_findings(wide)[0][1])

index = cd.schema_index()
expect("img requires src and alt", index.get("img") == {"src", "alt"}, str(index.get("img")))
expect("category requires ref", "ref" in index.get("category", set()))
expect("include requires from", "from" in index.get("include", set()))
expect("chapter requires nothing", index.get("chapter") == set(), str(index.get("chapter")))

# --- a diagram is mermaid or an SVG, and the check must accept both --------------------------
expect("a mermaid code-block counts as a diagram",
       cd.has_diagram('<code-block lang="mermaid">flowchart TB\n A --> B</code-block>'))
expect("a mermaid fence counts as a diagram", cd.has_diagram("```mermaid\nflowchart TB\n```"))
expect("an img counts as a diagram", cd.has_diagram('<img src="x.svg" alt="x"/>'))
expect("a page with neither does not", not cd.has_diagram("<p>Only prose here.</p>"))

# --- mermaid labels a human would have caught -----------------------------------------------
expect("a repeated word in a label is caught",
       cd.mermaid_findings('<code-block lang="mermaid">flowchart TB\n T["Target - the target"]</code-block>'))
expect("a character reference in a label is caught",
       cd.mermaid_findings('<code-block lang="mermaid">flowchart TB\n W["Audit &#8212; the weave"]</code-block>'))
expect("a clean label passes",
       not cd.mermaid_findings('<code-block lang="mermaid">flowchart TB\n W["The weave"] --> T["Greeting.class"]</code-block>'))

if failures:
    print(f"{len(failures)} rule(s) not behaving as specified:\n")
    for f in failures:
        print(f"  {f}")
    sys.exit(1)
print(f"selftest: every content rule fires on its case and stays quiet on clean text")
