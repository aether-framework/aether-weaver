# Aether Weaver Dev Container

Hardened reproducible dev environment for the Aether Weaver project. Bakes
Temurin JDK 25 + Maven 3.9.15 + Python 3.12 + Node LTS + native Claude Code
(with MCP servers, plugin marketplaces, plugins and user-scope skills) into a
single image, locks the runtime down with capability drops + an egress-only
firewall, persists the user's `~/.claude` directory across rebuilds, and ships
a ready-to-use Writerside documentation builder on the side.

## Files

| File                                                      | Purpose                                                                                                                                                                         |
|-----------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `Dockerfile`                                              | Multi-stage image: clones third-party skill/marketplace repos, then layers them onto the Java devcontainer base, plus the Writerside builder and user-scope Claude Code skills. |
| `devcontainer.json`                                       | Devcontainer spec: features, security flags, port forwarding, post-create hook.                                                                                                 |
| `post-create.sh`                                          | One-shot provisioning run on first container start. Installs Claude Code, writes MCP/plugin config, pre-populates the plugin cache. Runs as `vscode`; it does not touch the firewall and cannot. |
| `sandbox-entrypoint.sh`                                   | The image's `ENTRYPOINT`, PID 1, root. Applies the egress firewall before any session exists, then hands over to the container command. Fails the start if the rules cannot be written.          |
| `refresh-firewall.sh`                                     | The allowlist itself. Called only by `sandbox-entrypoint.sh`, as root; refuses to run otherwise.                                                                                                 |
| `verify-firewall.sh`                                      | `postStartCommand`. Proves the firewall is in force by requesting a host outside the allowlist and failing if it answers.                                                                        |
| `writerside.sh`                                           | Wrapper around JetBrains' headless Writerside builder (`helpbuilderinspect`). Starts an Xvfb display on demand and forwards arguments. Available on `$PATH` as `writerside`.    |
| `claude/skills/`                                          | User-scope Claude Code skills, staged into `/opt/aether-skills/skills/` at image build and synced into `~/.claude/skills/` on every `post-create.sh` run.                       |
| `claude/statusline.sh`                                    | The status line: context bar, model, 5h/7d limits with reset times, cost, lines changed, directory, branch. Staged into `/opt/aether-claude/` and installed into `~/.claude/` on every `post-create.sh` run, which also registers it in `settings.json`. |
| `claude/marketplaces/java-dev-assistant/marketplace.json` | Local wrapper marketplace pointing at the cloned `pluginagentmarketplace/custom-plugin-java` plugin.                                                                            |

## Image build

Two stages:

1. **`skill-sources`** (`debian:bookworm-slim` + `git`): clones five upstream
   repos in separate `RUN` layers — three skill collections and two plugin
   marketplaces. Each clone has a 3-attempt retry loop and is its own layer,
   so a transient DNS hiccup only invalidates the one repo that failed. Debian
    + glibc here instead of `alpine/git` because rootless BuildKit's resolver
      is intermittently flaky for back-to-back clones, and Alpine's musl libc
      makes that worse than glibc.
2. **Runtime stage** (`mcr.microsoft.com/devcontainers/java:1-21-bookworm`):
   adds curl/jq/Xvfb/font libs (Writerside dependencies), promotes Temurin
   JDK 25 + Maven 3.9.15 to the SDKMAN defaults, installs Node LTS via
   NodeSource, bakes Eclipse JDT.LS and JetBrains Kotlin LSP onto `$PATH`,
   copies the Writerside builder from `jetbrains/writerside-builder`,
   stages skills under `/opt/aether-skills/skills/` (post-create.sh syncs
   them into `~/.claude/skills/` on every start — see *Persistence* below),
   and stages the three plugin marketplaces under `/opt/claude-marketplaces/`.

Everything that needs network egress happens at build time, so the allowlist
the entrypoint applies can be narrow.

## Toolchain on `$PATH`

Everything is baked into the image directly — no `ghcr.io/devcontainers/features/*`
OCI features are used, because JetBrains Gateway's feature resolver fails
ghcr.io's anonymous auth scope (the token request goes out as
`repository:user/image:pull` and ghcr.io 403s).

| Tool                         | Version            | Provided by                                                                                                |
|------------------------------|--------------------|------------------------------------------------------------------------------------------------------------|
| `java` / `javac`             | Temurin **25.0.3** | SDKMAN candidate `25.0.3-tem`, set as default at image build (`/usr/local/sdkman/candidates/java/current`) |
| `mvn`                        | **3.9.15**         | SDKMAN candidate `3.9.15`, set as default at image build (`/usr/local/sdkman/candidates/maven/current`)    |
| `node` / `npm`               | Node LTS           | NodeSource apt repo (`setup_lts.x`)                                                                        |
| `python3` / `pipx`           | 3.x (Bookworm)     | Debian apt                                                                                                 |
| `jdtls`                      | snapshot           | Eclipse JDT Language Server tarball, symlinked into `/usr/local/bin/`                                      |
| `kotlin-lsp`                 | 262.2310.0         | JetBrains standalone Kotlin LSP, symlinked into `/usr/local/bin/`                                          |
| `typescript-language-server` | latest             | Global npm install in `post-create.sh`                                                                     |
| `writerside`                 | 2026.04.8711       | Wrapper around JetBrains' headless `helpbuilderinspect`                                                    |

The base image still ships Microsoft's OpenJDK 21 in `/usr/local/sdkman/candidates/java/`
as a second candidate; `sdk use java 21.x.x-ms` (or `sdk default …`) switches
back if you need it for an ad-hoc test. New SDKMAN installs only succeed
during image build, before the egress firewall is applied — `api.sdkman.io`
is not in the runtime allowlist.

## Persistence

`devcontainer.json` mounts a Docker named volume on `/home/vscode/.claude` so
the following survive `Rebuild Container`:

- **Login** (`~/.claude/.credentials.json`) — no re-running `claude` and
  re-doing OAuth after every rebuild.
- **Sessions and conversations** (`~/.claude/projects/<workspace>/<sessionId>.jsonl`).
- **Auto-memory** (`~/.claude/projects/<workspace>/memory/`).
- **Settings + plugin enable state** (`~/.claude/settings.json`) — re-merged
  by `post-create.sh` on each run, so flag additions from the script land
  on top of preserved user changes rather than replacing them.
- **Plugin cache** (`~/.claude/plugins/cache/<marketplace>/<plugin>/<version>/`)
  — refreshed in place by `post-create.sh` on every start.

The volume name is `splatgames-claude-${devcontainerId}`, so each cloned
working copy of the repo gets its own isolated state. Inspect / wipe via:

```bash
docker volume ls   | grep splatgames-claude
docker volume rm   splatgames-claude-<id>   # forces a clean re-init on next start
```

Because the volume hides whatever the image had at `/home/vscode/.claude`
after first init, **skills are NOT baked directly into that path**. Instead
the Dockerfile stages them at `/opt/aether-skills/skills/`, and
`post-create.sh` runs `cp -a /opt/aether-skills/skills/. ~/.claude/skills/`
on every start. That way skill updates from a rebuilt image always reach the
user without having to wipe the volume; user-installed skills sitting next to
them in `~/.claude/skills/` are untouched (no `--delete`).

`~/.claude.json` (recent-projects metadata + per-project allowed tools) lives
in the home directory itself, *not* under `~/.claude/`, and is therefore
**not persisted** by the volume. `post-create.sh` re-creates it with the
Context7 MCP server entry on every start.

## Hardening

`runArgs` in `devcontainer.json`:

- `--cap-drop=ALL` then re-adds only what's actually needed
  (`CHOWN`, `SETGID`, `SETUID`, `KILL`, `NET_ADMIN`, `FOWNER`).
  `NET_ADMIN` is required because `post-create.sh` configures `iptables`.
- `--security-opt=no-new-privileges` blocks setuid escalations.
- Resource caps: `--memory=6g`, `--cpus=4`, `--pids-limit=1024`.
- `host.docker.internal` mapped to the host gateway for OAuth callback flows.

`refresh-firewall.sh` runs an iptables `OUTPUT DROP` and only allows traffic
to the hosts named in its `DOMAINS` array, resolved at container start. If a
new tool needs an external host, add it there with a comment saying what needs
it — and expect the build to be the thing that tells you, since a host that is
not listed is not reachable.

### Who applies it, and why it cannot be you

The rules are written by `sandbox-entrypoint.sh`, which is the image's
`ENTRYPOINT` and therefore PID 1, running as root before any session exists.
Nothing afterwards can touch them: the session is `vscode`, `sudo` cannot
raise privileges under `no-new-privileges`, and the unprivileged user holds no
`CAP_NET_ADMIN`. A process inside the container — an agent included — can hit
the wall but cannot move it.

That is also the only place the rules *can* be written. Every dev container
lifecycle hook runs as `remoteUser`, and `no-new-privileges` makes `sudo`
unusable there by design.

> **This configuration previously had no firewall at all.** `refresh-firewall.sh`
> was called with `sudo` from `post-create.sh` and `postStartCommand`, and
> three separate faults each made it fail: `iptables` was not installed in the
> image, `sudo` cannot elevate under `no-new-privileges`, and neither the
> script nor its caller checked an exit status. `post-create.sh` printed
> `Firewall: active — only whitelisted hosts reachable` over a container with
> entirely open egress, for the life of the configuration.

`verify-firewall.sh` runs from `postStartCommand` and exists so that this
cannot happen again quietly. It requests a host deliberately absent from the
allowlist and fails the start if the request succeeds. It asserts rather than
announces.

### The limitation that remains

Several allowed hosts sit behind CDNs that rotate addresses, and the allowlist
pins IPs. It is rebuilt on every container start, so a rotation between
sessions is handled — but a container left running for days can watch an
address rotate out from under it. The symptom is a request that hangs rather
than one refused. Restart the container; the rules are re-resolved by PID 1.
Refreshing from inside is deliberately not possible.

## Provisioning at first start (`post-create.sh`)

Runs in this order:

1. **Take ownership** of `$WORKSPACE_DIR` and `~/.claude`.
2. **Sync skills** from the image's `/opt/aether-skills/skills/` staging area
   into the volume-mounted `~/.claude/skills/` (see *Persistence* above).
3. **Install the Claude Code native binary** via the official installer.
4. **Pre-install Context7 MCP** (`@upstash/context7-mcp`) globally so `npx`
   resolves it offline once the firewall blocks the npm registry.
5. **Write user-scope MCP config** to `~/.claude.json` directly via `jq`. This
   sidesteps the `claude mcp add` CLI, which behaves inconsistently in a
   fresh, unauthenticated devcontainer.
6. **Provision plugins** (see below).
7. **Verify state** by listing MCP servers / marketplaces / plugins.
The firewall is not among these steps. It is already in force before this
script runs, applied by `sandbox-entrypoint.sh` as PID 1.

## MCP servers

Currently baked in: **Context7** (`@upstash/context7-mcp`).

To add another MCP server, append a `jq` invocation in the same block of
`post-create.sh`. If it ships via npm, also add a global install line in the
`Dockerfile` so `npx` finds it offline.

## Plugins

All marketplaces are baked into the image at `/opt/claude-marketplaces/` and
`post-create.sh` pre-installs the three plugins deterministically. **No
`claude login` is required to provision them**, and the egress firewall does
not need to allow the upstream marketplace hosts at runtime.

For each target plugin, `post-create.sh`:

1. Reads the marketplace's `name` and the plugin's `source` from
   `<marketplace>/.claude-plugin/marketplace.json`.
2. Resolves the version. Precedence:
   `plugin.json.version` → marketplace entry `version` → 12-char git SHA →
   literal `unknown`.
3. Copies the plugin source to
   `~/.claude/plugins/cache/<marketplace-name>/<plugin-name>/<version>/`
   — the on-disk format the Claude Code CLI itself uses.
4. Appends an entry to `~/.claude/plugins/installed_plugins.json` so the CLI
   treats the plugin as already installed (schema observed in
   `anthropics/claude-code` issue #15642).
5. Registers the marketplace as a `directory` source under
   `extraKnownMarketplaces` in `~/.claude/settings.json` and toggles the
   plugin on under `enabledPlugins`.

Currently provisioned:

| Plugin                       | Marketplace name           | Marketplace source on disk                                                             |
|------------------------------|----------------------------|----------------------------------------------------------------------------------------|
| `frontend-design`            | `aether-vendor-plugins`    | `anthropics/claude-plugins-official` (cloned, renamed at image build — see note below) |
| `jdtls-lsp`                  | `aether-vendor-plugins`    | same as above — wires Eclipse JDT.LS (`jdtls`) into Claude Code's LSP integration      |
| `typescript-lsp`             | `aether-vendor-plugins`    | same as above — wires `typescript-language-server` for `.ts/.tsx/.js/.jsx` and friends |
| `kotlin-lsp`                 | `aether-vendor-plugins`    | same as above — wires JetBrains' standalone Kotlin LSP for `.kt/.kts`                  |
| `impeccable`                 | `impeccable`               | `pbakaus/impeccable` (cloned)                                                          |
| `java-development-assistant` | `java-dev-assistant-local` | `pluginagentmarketplace/custom-plugin-java` (wrapped)                                  |

> **Why `aether-vendor-plugins`?** Claude Code reserves any marketplace name
> matching the regex `^(claude|anthropic)-?` for official Anthropic
> marketplaces ([anthropics/claude-code#46786][reserved-names]) — that
> rejects both the literal `claude-plugins-official` (only allowed for
> `github` sources from the `anthropics` org) and any `claude-…` /
> `anthropic-…` suffix variants. Since the egress firewall blocks GitHub at
> runtime we have to register the local clone as a `directory` source, so
> the `Dockerfile` rewrites the `.name` field of the cloned
> `marketplace.json` to `aether-vendor-plugins` (project-scoped, outside the
> reserved namespace). Plugin contents and IDs are unchanged.

[reserved-names]: https://github.com/anthropics/claude-code/issues/46786

### First-run flow inside the container

1. Run `claude` and complete the OAuth login.
2. `/plugin → Marketplaces`: all three marketplaces are listed.
3. `/plugin → Plugins`: all six plugins already appear as enabled — no
   manual install step required.
4. Open any `.java` file: the `jdtls-lsp` plugin auto-starts the JDT
   language server (`/usr/local/bin/jdtls`, baked into the image) so Claude
   Code sees real diagnostics, hover info, and go-to-definition.
5. Open any `.ts` / `.tsx` / `.js` / `.jsx` file: the `typescript-lsp`
   plugin auto-starts `typescript-language-server` (installed globally via
   npm at postCreate, alongside `typescript`) for the same set of LSP
   capabilities on frontend code.
6. Open any `.kt` / `.kts` file: the `kotlin-lsp` plugin auto-starts
   JetBrains' standalone Kotlin LSP (`/usr/local/bin/kotlin-lsp`, baked
   into the image as a launcher symlink to `/opt/kotlin-lsp/kotlin-lsp.sh`)
   for Kotlin code intelligence on JVM-only Gradle/Maven projects.

### Brittleness disclaimer

`installed_plugins.json` is not part of Claude Code's documented public API.
If a future Claude Code release changes its schema, plugins may fail to load
on a freshly built container. Recovery path:

```bash
rm -rf ~/.claude/plugins/cache ~/.claude/plugins/installed_plugins.json
# inside `claude` after login:
/plugin install frontend-design@aether-vendor-plugins
/plugin install jdtls-lsp@aether-vendor-plugins
/plugin install typescript-lsp@aether-vendor-plugins
/plugin install kotlin-lsp@aether-vendor-plugins
/plugin install impeccable@impeccable
/plugin install java-development-assistant@java-dev-assistant-local
```

### Adding another plugin

1. Pre-clone the marketplace in the `skill-sources` stage of the `Dockerfile`
   and copy it under `/opt/claude-marketplaces/<name>/`.
2. Append a `<marketplace-dir>:<plugin-name>` entry to the `PLUGIN_TARGETS`
   array in `post-create.sh`. The script reads the marketplace name and
   plugin source from `marketplace.json` automatically.
3. If a runtime resource (e.g. an MCP server backing the plugin) needs
   network access, add its host to `DOMAINS` in `refresh-firewall.sh` and
   rebuild the container — the rules are baked in at start and cannot be
   amended from inside.

### Local wrapper marketplaces

When an upstream repo is shaped like a Claude Code plugin (valid
`plugin.json`) but its `marketplace.json` follows a different schema — for
example the SASMP format used by `pluginagentmarketplace/custom-plugin-java` —
Claude Code cannot consume the upstream marketplace directly. The Dockerfile
clones such repos and exposes them through a local wrapper marketplace under
`claude/marketplaces/<id>/marketplace.json`. The wrapper points at the cloned
plugin via a relative `source`, and `post-create.sh` registers it as a
`directory` source automatically.

Layout in the running container:

```
/opt/claude-marketplaces/java-dev-assistant/
├── .claude-plugin/marketplace.json    ← our wrapper
└── custom-plugin-java/                ← cloned upstream plugin
```

#### `custom-plugin-java` plugin.json patch

Upstream `pluginagentmarketplace/custom-plugin-java` ships a `plugin.json`
whose `.skills[]` entries point at `./skills/<name>/SKILL.md` files. Claude
Code's plugin loader now rejects file paths in that field with `path is a
file; skills entries must be directories containing SKILL.md`, producing 12
plugin errors in `/doctor` and silently disabling all of the plugin's
skills. The `Dockerfile` jq-rewrites each entry to its parent directory
right after the `COPY` from the `skill-sources` stage:

```dockerfile
RUN tmp=$(mktemp) && \
    jq '.skills |= map(sub("/SKILL\\.md$"; ""))' \
        /opt/claude-marketplaces/java-dev-assistant/custom-plugin-java/.claude-plugin/plugin.json > "$tmp" && \
    mv "$tmp" /opt/claude-marketplaces/java-dev-assistant/custom-plugin-java/.claude-plugin/plugin.json
```

Drop this `RUN` block once upstream fixes their manifest.

## Skills (user-scope, `claude/skills/`)

Drop user-scope skills under `claude/skills/`, one directory per skill, each
containing a `SKILL.md` plus any supporting files. Layout:

```
claude/skills/
├── my-skill/
│   ├── SKILL.md
│   └── ...
└── another-skill/
    └── SKILL.md
```

The `Dockerfile` copies this tree (plus the third-party collections below)
into the image staging area at `/opt/aether-skills/skills/`.
`post-create.sh` then runs `cp -a /opt/aether-skills/skills/. ~/.claude/skills/`
on every container start — this indirection exists so the persisted
`/home/vscode/.claude` Docker volume (see *Persistence*) doesn't freeze
skills at their first-init state. After the next start every skill in this
directory appears under `~/.claude/skills/<name>/` inside the container and
is auto-discovered by Claude Code.

### Pre-baked skill collections

The `Dockerfile`'s `skill-sources` stage clones third-party collections at
image-build time and merges them into `/opt/aether-skills/skills/` (from
which they are synced into `~/.claude/skills/` at every container start):

| Source                                                                                                  | Skills merged                                                                                                                                                                                                      |
|---------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| [`leonxlnx/taste-skill`](https://github.com/leonxlnx/taste-skill)                                       | `taste-skill`, `gpt-tasteskill`, `image-to-code-skill`, `redesign-skill`, `soft-skill`, `output-skill`, `minimalist-skill`, `brutalist-skill`, `stitch-skill`, `imagegen-frontend-web`, `imagegen-frontend-mobile` |
| [`claudiocebpaz/vite-react-best-practices`](https://github.com/claudiocebpaz/vite-react-best-practices) | `vite-react-best-practices`                                                                                                                                                                                        |

To pin a specific revision, change the `git clone --depth 1 …` line in the
`skill-sources` stage to `git clone … && git -C … checkout <ref>`.

## Writerside documentation builder

The image embeds JetBrains' headless Writerside builder
(`jetbrains/writerside-builder`). Use the `writerside` wrapper:

```bash
# Convenience mode: <module>/<instance> [output-dir]
writerside Writerside/hi "$WORKSPACE_DIR/artifacts/help"

# Pass-through mode: forward flags directly to helpbuilderinspect
writerside --source-dir "$WORKSPACE_DIR" --product Writerside/hi --output-dir /tmp/out --runner other
```

`writerside.sh` starts an Xvfb display on demand
(`DISPLAY=:99`, fallback can be set by exporting `DISPLAY` beforehand),
which is why `xvfb` and the X11 client libs are in the runtime stage.

## Forwarded ports

| Port | Use                        | Auto-forward |
|------|----------------------------|--------------|
| 8080 | Claude Code OAuth callback | ignore       |

## Container env

| Variable                | Value                 | Purpose                                                    |
|-------------------------|-----------------------|------------------------------------------------------------|
| `CLAUDE_CODE_SANDBOX`   | `true`                | Tells Claude Code it's running in a sandboxed environment. |
| `NO_PROXY` / `no_proxy` | `localhost,127.0.0.1` | Bypass any inherited proxy for loopback traffic.           |

## Re-running provisioning

`post-create.sh` is idempotent and safe to re-run. Useful when:

- you've edited `post-create.sh` itself and want to apply the changes
  without rebuilding the image;
- a Claude Code release shipped that broke the cache layout (run after
  `claude login` so `/plugin install` can also work);
- you've added a new plugin or marketplace and want to provision it now.

```bash
bash "$WORKSPACE_DIR/.devcontainer/post-create.sh"
```

`$WORKSPACE_DIR` is set by `containerEnv` in `devcontainer.json` and points
at whatever path the IDE chose to mount the source under (VS Code defaults
to `/workspaces/<basename>`, JetBrains may pick a different path). Inside
the container, use this variable rather than hard-coding `/workspace`.

### Debug mode

`post-create.sh` is silent on the happy path, which makes it hard to tell
whether a long-running step (e.g. the Claude Code installer pulling the
binary) is hung or just slow. Set `DEVCONTAINER_DEBUG=1` to enable verbose
output:

```bash
# Ad-hoc re-run with full tracing:
DEVCONTAINER_DEBUG=1 bash "$WORKSPACE_DIR/.devcontainer/post-create.sh"
```

This activates `bash`'s `set -x` (every command is printed before
execution), drops `curl -s` so the installer download shows progress, and
runs the installer itself under `bash -x` so its internal steps are
visible too. To turn it on for the initial postCreate run, add
`"DEVCONTAINER_DEBUG": "1"` to `containerEnv` in `devcontainer.json`.

### Rebuilding the image

For changes to the `Dockerfile` itself (not just `post-create.sh`), use your
IDE's standard rebuild action — JetBrains Gateway "Rebuild and Restart
Container", VS Code "Dev Containers: Rebuild Container", or
`devcontainer build` from the CLI.
