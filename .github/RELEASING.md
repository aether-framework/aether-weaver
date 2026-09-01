# 🚢 Releasing Aether Weaver

The maintainer's runbook. Everything here is done once per release except the first section, which
is done once ever.

A publication to Maven Central is **immutable** — there is no delete, no overwrite and no
re-publish of a botched version, only a new one. The workflow is built to fail loudly before it
uploads anything, and this page exists so that the parts a workflow cannot check are not left to
memory.

---

## 1. One-time setup

### Maven Central

1. The namespace `de.splatgames` must be verified in the
   [Central Portal](https://central.sonatype.com/). A verified parent namespace covers every child,
   so `de.splatgames.aether.weaver` needs no separate verification.
2. Generate a **user token** at <https://central.sonatype.com/usertoken>. It has a username half
   and a password half; both become repository secrets below.

### The signing key

Releases are signed by a key that belongs to the release pipeline, not to a person's laptop.

```bash
gpg --full-generate-key                    # RSA 4096, no expiry or a long one
gpg --list-secret-keys --keyid-format=long # note the key id
gpg --keyserver keyserver.ubuntu.com --send-keys <key id>
gpg --armor --export-secret-keys <key id>  # this whole block becomes a secret
```

Central checks the public key against `keyserver.ubuntu.com`, `keys.openpgp.org` and
`pgp.mit.edu`. Publish it to at least one of them **before** the first release, or validation
fails after the artefacts have already been built.

The public half is committed as [`KEYS`](../KEYS) at the repository root, and its fingerprint is
in [SECURITY.md](../SECURITY.md#signing-keys) and the README, so that consumers can verify a
signature without asking for it. Rotating the key means replacing all three in the same commit.

### Repository secrets

`Settings → Secrets and variables → Actions`:

| Secret | What it is |
|---|---|
| `MAVEN_CENTRAL_USERNAME` | The user token's username half |
| `MAVEN_CENTRAL_TOKEN` | The user token's password half |
| `MAVEN_GPG_PRIVATE_KEY` | The armoured private key, including the BEGIN and END lines |
| `MAVEN_GPG_PASSPHRASE` | That key's passphrase |

### Repository settings

- **Environment `maven-central`** (`Settings → Environments`). The publish job runs in it; add a
  required reviewer there if you want a human gate in front of an irreversible upload.
- **Default branch `main`**, with `develop` as the integration branch. Both are referenced by the
  workflows, the contribution links on the documentation site, and `CONTRIBUTING.md`.
- **Private vulnerability reporting**: on. [SECURITY.md](../SECURITY.md) sends people to it.
- **Branch protection on `main`**: require the `Build` checks, require a pull request.
- **The DCO app** (<https://github.com/apps/dco>), which reads `.github/dco.yml`.
- **Description and topics**, since they are how the repository is found:

  > A general-purpose bytecode weaving framework for the JVM, built on the standard Java
  > Class-File API.

  `java` `jvm` `bytecode` `bytecode-manipulation` `classfile-api` `weaving` `aop` `maven-plugin`
  `java-agent` `framework`

  Homepage: `https://software.splatgames.de/docs/aether-weaver/`

---

## 2. Cutting a release

### Step 1 — the version lives in four files

The release workflow reads all four and refuses a tag that disagrees with any of them. Update them
in one commit:

| File | What to change |
|---|---|
| `CHANGELOG.md` | Rename `## [Unreleased]` to `## [x.y.z] - <date>`, open a fresh `Unreleased`, and update the link definitions at the bottom |
| `Writerside/writerside.cfg` | `<instance … version="x.y.z">` |
| `Writerside/v.list` | `<var name="version" value="x.y.z"/>` |
| `aether-weaver-engine/…/engine/Weaver.java` | `static final String VERSION = "x.y.z";` — it is stamped into every weave record |

The poms are **not** touched. They stay on `-SNAPSHOT`; the workflow stamps the release version in
from the tag. That way a build from `main` can never overwrite a released artefact.

Also worth doing, though nothing checks it: `Writerside/versions.json`, if the site is to offer a
version switcher, and `aether-weaver-ide/aether-weaver-idea/gradle.properties`, which pins the
framework version the IDE plugin resolves.

### Step 2 — check it locally

```bash
mvn -B clean verify
python3 build-config/docsite/check-docs.py --build
```

Both must exit 0. The second one takes about a minute and runs the real Writerside builder.

A dry run of the release packaging, which builds the sources and javadoc jars the way the release
does, without signing or uploading anything:

```bash
mvn -B -Prelease clean package -DskipTests
```

### Step 3 — tag and push

```bash
git tag -a v0.1.0 -m "Aether Weaver 0.1.0"
git push origin v0.1.0
```

The tag is what triggers the release. Nothing else does.

### Step 4 — watch it

The `Release` workflow runs four jobs:

1. **Verify** — checks the version against those four files, stamps it into the poms, and runs the
   full gate.
2. **Publish** — builds sources, javadoc and signatures, and uploads to Central with
   `autoPublish` and `waitUntil=published`. The job fails if Central rejects the deployment,
   rather than leaving it sitting in `VALIDATED` for somebody to notice.
3. **SBOM** — a CycloneDX bill of materials. Allowed to fail; it must not hold up a release.
4. **GitHub Release** — created from this version's section of `CHANGELOG.md`.

Artefacts usually appear on `search.maven.org` within a few minutes and on `repo1.maven.org`
shortly after.

### Step 5 — afterwards

- Publish the documentation — section 3 below — and confirm
  <https://software.splatgames.de/docs/aether-weaver/> serves the new version.
- Add the release's contributors to [CONTRIBUTORS.md](../CONTRIBUTORS.md).

---

## 3. Publishing the documentation

CI builds the site and fails on any error or warning the Writerside builder reports, but it does
**not** publish it. The site is served from `software.splatgames.de`; building and uploading is done
by hand, and this is the layout the result has to have.

Build it, either locally or by downloading the `docs` artefact from the Documentation workflow:

```bash
python3 build-config/docsite/check-docs.py --build
```

The archive is `webHelpAW2-all.zip`, and it carries a file `current.help.version` holding the
version the builder used — that is what decides the directory, rather than anybody retyping the
number.

Under the document root, `docs/aether-weaver/` must end up looking like this:

```
docs/aether-weaver/
├── 0.1.0/               the unzipped archive, under its own version
├── latest/              a copy of the release that versions.json marks current
├── index.html           a redirect to latest/
├── versions.json        Writerside/versions.json, copied verbatim
└── social-preview.png   Writerside/images/social-preview.png, copied verbatim
```

Three of those are not optional and none of them comes out of the archive:

- **`latest/`** is the address every link from outside the site uses — the README, the security
  policy, the issue templates. The pages inside it keep the versioned canonical URL the builder
  wrote, so the versioned path stays the one search engines index.
- **`versions.json`** is what the header's version switcher reads. It is not a topic, so the
  builder does not copy it, and a relative URL to it fails the build outright.
- **`social-preview.png`** is the card a shared link unfurls as. `<og-image>` names it by URL, so
  without it every shared link carries a 404 image.

Keeping older version directories is optional and costs nothing; `versions.json` decides what the
switcher offers.

---

## 4. If something goes wrong

| Symptom | What it means |
|---|---|
| The verify job fails on a version mismatch | One of the four files was not updated. Fix it, delete the tag, tag again |
| Central rejects the deployment | Read the job log; it carries Central's own validation messages. Nothing was published |
| Signing fails, or the job hangs | The passphrase secret or the key secret is wrong. The key must be armoured, whole, including its BEGIN and END lines |
| The deployment sits in `VALIDATED` | `autoPublish` did not take effect. Publish it by hand in the portal, and fix the configuration before the next release |
| The release published, the GitHub Release did not | Re-run only the `github-release` job. Central is already done and cannot be redone |

A released version is never re-released. If a release is wrong, release the next patch version.

---

## 5. A pre-release

A qualifier makes it one: `v0.2.0-rc.1` publishes as a pre-release on GitHub and as an ordinary
version on Central, which has no notion of pre-release. The workflow decides that from the version
string alone.
