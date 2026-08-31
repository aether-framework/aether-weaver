# 🤖 AI Usage Guidelines

Aether Weaver is developed with AI assistance, and it says so rather than hiding it. This document
states what that means here: where AI is used, what it is never allowed to decide, what we ask of a
contributor who uses it, and how a maintainer reviews the result.

The short version: **a tool may write text; it may not be the reason the text is believed.**

---

## 📋 Scope

This applies to every contribution — code, tests, JavaDoc, the documentation site, build files,
workflows and issue reports — whether it comes from a maintainer or from outside.

---

## ⚖️ Principles

1. **The author is accountable, not the tool.** Whoever opens a pull request vouches for every line
   in it. "The model wrote it" is not a defence for a defect, a licence violation or a wrong claim.
2. **Every statement must be anchored.** A sentence in a doc comment or on the documentation site
   has to be traceable to a line of code or a test that shows the behaviour. Plausible and
   unverifiable is the failure mode this project spends the most effort catching, because it reads
   exactly like a fact.
3. **The past is not evidence.** Neither a human nor a model may establish what the code does from
   commit messages, older revisions or a deleted document. The current source is the only source of
   truth. Where behaviour is not visible in it, it is measured again or left out.
4. **Generation is not review.** The agent that writes a unit never reviews it. Review is separate,
   adversarial, and is given the output without the reasoning that produced it — a reviewer who
   reads why a sentence was written tends to agree with it.
5. **A gate is not negotiable.** No contribution weakens, skips or deletes a test to make a build
   pass. If a gate fails for a reason that is not the change, the work stops and the reason is
   reported.

---

## ✅ Permitted

- Drafting implementations, tests, doc comments and documentation pages.
- Refactoring, renaming, and mechanical transformations across many files.
- Investigating a failure, reading the source to answer a question, summarising behaviour.
- Reviewing a change adversarially, provided a human decides what to do with the findings.
- Writing commit messages, changelog entries and release notes from a diff the author has read.

---

## 🚫 Not permitted

- **Pasting code the model did not derive from this repository and cannot license.** If a snippet
  came from somewhere else, it needs a compatible licence and attribution, or it does not go in.
- **Changing the code to make a comment, a test or a documentation page correct.** The code decides
  what the text says, never the other way round.
- **Adding a dependency to make something easier.** In particular, the banned bytecode libraries —
  ASM, Javassist, Byte Buddy, cglib — are banned in every module including tests, and a model that
  reaches for one has misunderstood the project rather than found a shortcut.
- **Bulk-generated issues or pull requests.** One change, one reason, reviewable by a person.
- **Numbers nobody measured.** A timing, a benchmark figure or a memory claim is measured on the
  current source or it is omitted.

---

## 📢 Disclosure

The pull request template carries an AI disclosure checkbox. Check it when AI wrote or materially
shaped part of the change, and say which parts. Disclosure is not a black mark — it tells a
reviewer where to look hardest.

You do not need to disclose editor completion, a formatter, or a search tool.

---

## 🔍 What a maintainer checks

An AI-assisted contribution is reviewed the same way as any other, with three questions weighted
more heavily:

1. **Can each claim be pointed at?** For any sentence in a comment or a page that a reviewer cannot
   anchor to a line of code, either the anchor is produced or the sentence goes.
2. **Does the change respect the rules the build enforces?** The one-way dependency arrow, the
   Class-File API rule, no `module-info.java`, the reactor's shape. These are mechanically checked,
   so a change that fights them fails visibly — but it is worth understanding *why* it fought them.
3. **Is the test real?** A test that asserts what the implementation happens to do, rather than what
   the behaviour should be, passes forever and protects nothing. Each fix in this project is
   expected to come with a test that was verified to fail without the fix.

---

## ⚖️ Licensing

By contributing you certify the [Developer Certificate of Origin](DCO) with a sign-off
(`git commit -s`). That certification covers AI-assisted work exactly as it covers everything else:
you are stating that you have the right to submit it under the project's MIT licence.

---

## 🔒 Accountability

A contribution that turns out to contain fabricated behaviour, an unlicensed copy, or a claim no
source supports is reverted. Repeated instances end the ability to contribute. This is not about
the tool — a human who writes a plausible untruth is treated the same way.

---

## 📝 Changes to this policy

This document changes as the practice does. If a rule here stops matching how the project actually
works, that is a defect in the document; open an issue.
