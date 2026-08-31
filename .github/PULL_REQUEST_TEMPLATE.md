## Summary

What this pull request changes, and why.

## Type of Change

- [ ] Bug fix
- [ ] New feature
- [ ] Breaking change
- [ ] Refactoring
- [ ] Documentation
- [ ] Build / CI

## Related Issues

Closes #…

## Changes

- …
- …

## Verification

- [ ] `mvn -B verify` passes (the reactor gate: enforcer, Checkstyle, tests, JavaDoc)
- [ ] Tests were added or updated for the behaviour this changes
- [ ] Every new or changed public, protected, package-private and private member carries JavaDoc
- [ ] If `Writerside/` changed: `python3 build-config/docsite/check-docs.py --build` exits 0
- [ ] If `aether-weaver-ide/` changed: the Gradle build passes

## Project Rules

Four rules are enforced by the build rather than by review. Confirm none of them was worked around:

- [ ] Bytecode goes through `java.lang.classfile` only — no ASM, Javassist, Byte Buddy or cglib,
      not even in tests
- [ ] No `module-info.java` was added
- [ ] The dependency arrow still points one way: `api <- engine <- drivers`
- [ ] `aether-weaver-ide` is still outside the Maven reactor

## Breaking Changes

Any breaking change and the migration step a consumer has to take. Write "None" if there is none.

## AI Disclosure

- [ ] This pull request contains AI-generated or AI-assisted content
- If checked, which parts and which tool: …

See [AI Usage Guidelines](/AI_USAGE.md).

## Checklist

- [ ] The commit messages say what changed and why
- [ ] Commits are signed off (`git commit -s`), per the [DCO](/DCO)
- [ ] No new dependency was added, or the pull request explains why one was needed
- [ ] Documentation was updated where behaviour changed
