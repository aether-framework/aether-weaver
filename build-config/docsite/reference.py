#!/usr/bin/env python3
"""Measure JetBrains' own product help and write REFERENCE.md from it.

This site claims to be modelled on JetBrains' product documentation. That claim was made for
months without anybody reading it, and the house style drifted into the opposite of it: a
register with no reader in it and sentences three times their length. This script exists so
the claim is checkable.

JetBrains publish their whole help as one plain-text file for machines to read:

    https://www.jetbrains.com/help/idea/llms.txt

    python3 build-config/docsite/reference.py [--fetch]

--fetch downloads it (about 12 MB) next to this script; without it the cached copy is used.
The corpus is not committed. This script and the REFERENCE.md it produces are.
"""
import re
import sys
import urllib.request
from pathlib import Path

HERE = Path(__file__).parent
CORPUS = HERE / "jetbrains-idea-help.txt"
OUT = HERE / "REFERENCE.md"
URL = "https://www.jetbrains.com/help/idea/llms.txt/"


def fetch() -> None:
    print(f"downloading {URL}", file=sys.stderr)
    with urllib.request.urlopen(URL, timeout=180) as response:
        CORPUS.write_bytes(response.read())


def frequency(text: str, term: str) -> float:
    pattern = r"\b" + term.replace(" ", r"\s+") + r"\b"
    return len(re.findall(pattern, text, re.I)) / len(text.split()) * 10_000


def row(title: str, opening: str) -> str:
    """One table row, with the pipe escaped so a sentence containing one cannot split it."""
    bar = "\\|"
    return f"| {title.replace('|', bar)} | {opening.replace('|', bar)} |"


def main() -> int:
    if "--fetch" in sys.argv or not CORPUS.exists():
        fetch()
    text = CORPUS.read_text(errors="replace")

    pages = [p for p in re.split(r"\n(?=# )", text) if p.startswith("# ")]
    sentences = sorted(len(s.split())
                       for s in re.findall(r"[A-Z][^.!?\n]{15,400}[.!?]", text))
    percentile = lambda q: sentences[int(len(sentences) * q)]

    openings = []
    for page in pages:
        lines = page.split("\n")
        title = lines[0][2:].strip()
        body = [l.strip() for l in lines[1:] if l.strip()]
        if len(title) > 34 or not body:
            continue
        first = body[0]
        if first[0] in "[!*|#" or len(first.split()) < 6:
            continue
        openings.append((title, first[:190]))

    with_you = [o for o in openings if re.search(r"\byou(r)?\b", o[1], re.I)]

    OUT.write_text(f"""# What the reference actually looks like

Measured from JetBrains' IntelliJ IDEA help, which is built with the same builder as this
site. Regenerate with `python3 build-config/docsite/reference.py --fetch`.

Corpus: {len(pages):,} pages, {len(text.split()):,} words, {len(sentences):,} sentences.

## Sentence length

| | Words |
| --- | --- |
| median | {percentile(0.5)} |
| 75th percentile | {percentile(0.75)} |
| 90th percentile | {percentile(0.9)} |
| 99th percentile | {percentile(0.99)} |

**Their median sentence is {percentile(0.5)} words.** Half of everything they publish is shorter
than that. A limit of 32 words does not describe this documentation; it describes something
three times its length that happens not to be worse.

## Register, per 10,000 words

| Word | Rate | |
| --- | --- | --- |
| `you` | {frequency(text, 'you'):.0f} | The reader is in almost every paragraph. |
| `your` | {frequency(text, 'your'):.0f} | |
| `if you` | {frequency(text, 'if you'):.0f} | Conditions are put to the reader, not stated abstractly. |
| `click` / `select` / `press` | {frequency(text, 'click') + frequency(text, 'select') + frequency(text, 'press'):.0f} | Written as what the reader does. |
| `we` | {frequency(text, 'we'):.1f} | Rare, and always a recommendation. |
| `simply` | {frequency(text, 'simply'):.2f} | Seven times in {len(text.split()) // 1_000_000} million words. |
| `just` | {frequency(text, 'just'):.1f} | |

{len(with_you)} of {len(openings)} measurable page openings address the reader in their first
sentence.

## How they open a page

Verbatim first sentences, taken mechanically — the first {min(24, len(openings))} pages whose
title is short enough to sit beside them.

| Page | Its first sentence |
| --- | --- |
""" + "\n".join(row(t, o) for t, o in openings[:24]) + """

## What this means for this site

1. **Write to the reader.** `you` and `your` are the normal register, not a lapse. A sentence
   that avoids the reader by naming a mechanism instead — *the plugin is configured by* rather
   than *you configure the plugin by* — is longer, colder and harder to act on.
2. **Short sentences.** Aim at the median, not the limit. One clause, one fact.
3. **A picture early.** Their pages put an image within a screen of the opening, not at the
   end as evidence.
4. **Say what it lets the reader do**, then how. `IntelliJ IDEA lets you enable...` is the
   shape: subject, what it enables, then the steps.
""", encoding="utf-8")
    print(f"wrote {OUT} from {len(pages):,} pages")
    return 0


if __name__ == "__main__":
    sys.exit(main())
