# Security Policy

Aether Weaver rewrites bytecode. It runs inside a build and, under the agent, inside the JVM of
the application it modifies. That places it in the same trust position as a build plugin or a
class-loading agent, and this policy says what that means for you and how to report a problem.

---

## Supported Versions

| Version | Support status   | End of support |
|---------|------------------|----------------|
| 0.1.x   | ✅ Active support | -              |

Security fixes are released for the latest minor line. If you are on an older one,
**upgrade before reporting**: the fix will be published for the current line only.

---

## Security model

### What the framework does on your behalf

A weave is code you wrote, compiled and put on a classpath, and the engine executes the
instructions it carries. It is not a sandbox and does not try to be one. What follows is the exact
shape of the gate, because a security guarantee stated loosely is worse than none.

**Every driver runs the same policy, and it refuses three things** before any byte is written: a
class of Aether Weaver itself, `java.*` and every other JDK prefix that has not been explicitly
reopened, and a class file below major version 50. A refusal hands the original bytes back
unchanged, under every driver.

**Signed jars are handled per driver, not by that policy.** The policy has a rule for them, but the
engine builds the class it shows the policy from the parsed class file alone and cannot know
whether the bytes came from a signed artefact — so the rule never fires there. What actually
happens depends on where you weave:

| Driver | A class from a signed jar |
|---|---|
| Maven plugin (`weave`) | Refused, unless `allowSigned` is set — and only where `weaveDependencies` is on |
| Weaving class loader | Refused with `AW3002`, unless the signed override is set |
| Java agent | **Woven, silently.** It is handed a `ProtectionDomain` it never reads |

Weave at build time wherever a signature has to keep describing what runs.
[Policy and safety](https://software.splatgames.de/docs/aether-weaver/latest/policy-and-safety.html)
has every code the gate can report and what to do about each one.

**A different policy is something you write, not something you configure.** The API can reopen one
JDK package at a time, and matches it exactly rather than as a subtree — but that reaches the
engine only from a program that assembles its own `Weaver`. The configuration key
`aether.weaver.policy.allowPackage` parses and resolves and then decides nothing; the documentation
says so, and writing it gets the class refused again.

### What is on you

- **A weave is trusted code.** Treat a dependency that contributes weaves the way you treat a
  dependency that contributes a Maven plugin — it executes in your build.
- **Weaving at load time widens the blast radius**, because the transformation happens in the
  running JVM rather than in an artefact you can inspect afterwards. Prefer build-time weaving
  where you have the choice; the woven class file is then a reviewable, diffable artefact.
- **A `WeaverPlugin` runs inside the engine, and installing one is a deliberate act.** No shipped
  driver scans the classpath for plugins: a plugin arrives only through
  `Weaver.builder().plugin(…)` or `discoverPlugins(loader)`, both of which mean a program you
  wrote asked for it. If you wrote such a program, everything that loader can see is code you are
  choosing to run.
- **Isolation is containment, not a sandbox.** A plugin that throws is caught and reported against
  its own identity. It gets no class loader of its own and no reduced privileges.

---

## Supply chain

### Artefact integrity

Release artefacts published to Maven Central are **GPG signed** in the release pipeline. Every
published file is accompanied by its `.asc` signature.

```bash
gpg --verify aether-weaver-api-0.1.0.jar.asc aether-weaver-api-0.1.0.jar
```

An unsigned or unverifiable artefact claiming to be an official release **must not be trusted**.

### Signing keys

The public key is in [`KEYS`](KEYS) at the root of this repository.

| | |
|---|---|
| Owner | `Splatgames.de Software CI Release Signing <release@splatgames.de>` |
| Fingerprint | `C6BE 25BF 2A46 39A6 7A49  1EBD 37B5 9B93 DC75 6EE8` |
| Key ID | `37B59B93DC756EE8` |
| Type | RSA 4096, created 2026-01-11, no expiry |

```bash
gpg --import KEYS
gpg --fingerprint 37B59B93DC756EE8
gpg --verify aether-weaver-api-0.1.0.jar.asc aether-weaver-api-0.1.0.jar
```

**Check the fingerprint against a source that is not this repository.** A `KEYS` file and the
signatures it validates can be replaced by the same person in the same push, so importing it and
verifying against it proves only that the two agree. The same key is published on
`keyserver.ubuntu.com`, one of the keyservers Maven Central validates a release against:

```bash
gpg --keyserver keyserver.ubuntu.com --recv-keys 37B59B93DC756EE8
```

- Releases are signed by a key that belongs to the release pipeline rather than to a person, and
  the [release runbook](.github/RELEASING.md) requires it to be generated for that purpose. The
  address on it is a role, not a mailbox a human reads; vulnerability reports go to
  `security@splatgames.de`.
- Private key material is never committed to this repository. It is held as a repository secret and
  reaches the build only as an environment variable. `KEYS` carries the public half alone.
- Rotating the key means replacing `KEYS`, this table and the fingerprint in the README in the
  same commit.

### Reproducible builds

The build is reproducible: two clean builds of the same sources produce byte-identical jars, and
CI fails the run if they do not. You can rebuild a release from its tag and compare hashes with
what Maven Central serves.

### Automated scanning

- **Dependency Review** — flags a dependency change in a pull request: a transitive dependency with
  a known vulnerability, a licence that does not fit the project, and any of the seven banned
  bytecode coordinates.
- **Dependabot** — Maven and GitHub Actions updates.

There is no static analysis service running over this repository. What the build enforces itself —
the banned dependencies, the architecture test, the two documentation gates — it enforces on every
run, and that is described under [Building from Source](README.md#-building-from-source).

The framework itself ships no third-party runtime dependency: the annotations it compiles against
are `provided` and CLASS-retention, so nothing reaches your runtime classpath but Aether Weaver.
That is enforced by the build, not by convention.

---

## Reporting a Vulnerability

Report privately. Do **not** open a public issue.

- **GitHub Security Advisories** —
  https://github.com/aether-framework/aether-weaver/security/advisories/new
- **Email** — `security@splatgames.de`

Include the version, the driver you were running (Maven goal, agent, runtime API), a minimal
reproducer if you have one, and what you believe the impact is.

### Response times

| Severity                 | Acknowledgment | Fix timeline |
|--------------------------|----------------|--------------|
| Critical (CVSS 9.0-10.0) | 24 hours       | 72 hours     |
| High (CVSS 7.0-8.9)      | 48 hours       | 14 days      |
| Medium (CVSS 4.0-6.9)    | 48 hours       | 30 days      |
| Low (CVSS 0.1-3.9)       | 72 hours       | Next release |

### Disclosure process

1. You report privately.
2. We acknowledge within the window above.
3. We confirm the issue and agree a fix timeline with you.
4. The fix is released, and an advisory is published.
5. Public disclosure follows the release, crediting you if you want to be credited.

We follow coordinated disclosure and will not publish before a fix is available, unless the issue
is already public.

---

## Out of scope

These are documented behaviour rather than vulnerabilities:

- A weave you wrote doing something harmful to your own application. The engine executes what the
  weave declares.
- A `WeaverPlugin` your own program installed contributing a hostile injector.
- Weaving a JDK package that your own program reopened with `allowPackage`.
- The agent transforming classes in the JVM it was attached to. That is what attaching it does.
- The agent weaving a class from a signed jar. It reads no `ProtectionDomain`; the table above says
  so, and build-time weaving is the answer where a signature has to keep describing what runs.

A way to make the engine ignore its own policy, to weave a refused class without reopening it, or
to escape the guard that contains a misbehaving plugin **is** in scope.

---

## Security Audits

Audits are welcome.

- Write to `security@splatgames.de` before you start.
- Follow responsible disclosure.
- Researchers are credited with their permission.

---

Thank you for helping keep **Aether Weaver** secure.
