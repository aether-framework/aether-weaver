# Stage 1: pull third-party skill and plugin-marketplace repos at image-build
# time. Cloning here (not in postCreate) keeps the install reproducible, bakes
# the artifacts into the image, and lets post-create.sh pre-populate the
# plugin cache without ever needing `claude login` or runtime network egress.
#
# Debian + glibc instead of alpine/git: rootless BuildKit's DNS resolver is
# flaky for back-to-back clones, and glibc tolerates that better than musl.
# Cost: one extra apt layer (~50 MB), reused from the BuildKit cache.
#
# Each clone is its own RUN layer with a 3-attempt retry loop. Splitting per
# layer means a transient failure for one repo doesn't invalidate layers
# already cached. The trailing `exit 1` only fires when all 3 attempts failed.
FROM debian:bookworm-slim AS skill-sources
RUN apt-get update && apt-get install -y --no-install-recommends git ca-certificates \
    && rm -rf /var/lib/apt/lists/*
RUN for i in 1 2 3; do \
        git clone --depth 1 https://github.com/leonxlnx/taste-skill.git /skills/taste-skill && exit 0; \
        echo "clone failed (attempt $i), retrying in 5s..."; sleep 5; \
    done; exit 1
# Strip .git/ after clone: this repo is the only one COPYed wholesale into
# /opt/aether-skills/skills/, which post-create.sh then re-syncs into the
# persistent /home/vscode/.claude volume on every container start. Git pack
# files (*.idx, *.pack) are mode 444; once they land in the volume, the next
# `cp -a` can't overwrite them and prints "Permission denied". The .git dir
# isn't used at runtime (skill discovery only reads SKILL.md, and the egress
# firewall blocks runtime git pulls anyway), so dropping it is harmless.
RUN for i in 1 2 3; do \
        git clone --depth 1 https://github.com/claudiocebpaz/vite-react-best-practices.git /skills/vite-react-best-practices \
        && rm -rf /skills/vite-react-best-practices/.git \
        && exit 0; \
        echo "clone failed (attempt $i), retrying in 5s..."; sleep 5; \
    done; exit 1
RUN for i in 1 2 3; do \
        git clone --depth 1 https://github.com/pluginagentmarketplace/custom-plugin-java.git /skills/custom-plugin-java && exit 0; \
        echo "clone failed (attempt $i), retrying in 5s..."; sleep 5; \
    done; exit 1
RUN for i in 1 2 3; do \
        git clone --depth 1 https://github.com/anthropics/claude-plugins-official.git /marketplaces/claude-plugins-official && exit 0; \
        echo "clone failed (attempt $i), retrying in 5s..."; sleep 5; \
    done; exit 1
RUN for i in 1 2 3; do \
        git clone --depth 1 https://github.com/pbakaus/impeccable.git /marketplaces/impeccable && exit 0; \
        echo "clone failed (attempt $i), retrying in 5s..."; sleep 5; \
    done; exit 1

FROM mcr.microsoft.com/devcontainers/java:1-21-bookworm

RUN rm -f /etc/apt/sources.list.d/yarn.list

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    curl \
    jq \
    unzip \
    iptables \
    xvfb \
    x11-utils \
    libxext6 \
    libxrender1 \
    libxtst6 \
    libxi6 \
    libfreetype6 \
    libfontconfig1 \
    python3 \
    python3-pip \
    python3-venv \
    pipx \
    build-essential \
    && rm -rf /var/lib/apt/lists/*

# JDK 25 (Temurin) + Maven via the base image's SDKMAN. The 1-21-bookworm
# image ships SDKMAN at /usr/local/sdkman with Microsoft's OpenJDK 21 as the
# only Java candidate and no Maven; we add Temurin 25 and Maven 3.9.15 and
# flip the SDKMAN `current` symlinks so `java`, `javac`, and `mvn` resolve to
# them in every shell — no `sdk install` required at runtime (the egress
# firewall blocks api.sdkman.io anyway). `</dev/null` answers SDKMAN's
# "set as default?" prompt with the default ('Y'). `sdk flush` strips the
# downloaded archives so they don't bloat the final image layer.
RUN bash -lc 'source /usr/local/sdkman/bin/sdkman-init.sh \
    && sdk install java 25.0.3-tem </dev/null \
    && sdk default java 25.0.3-tem \
    && sdk install maven 3.9.15 </dev/null \
    && sdk default maven 3.9.15 \
    && sdk flush archives \
    && sdk flush tmp'

# Node.js LTS via NodeSource. We install Node here rather than via the
# devcontainers/features/node OCI feature because JetBrains Gateway's feature
# resolver fails to substitute the image into ghcr.io's anonymous auth scope —
# the token request goes out as `repository:user/image:pull` and ghcr.io 403s.
# Baking Node at image-build time also matches the rest of this Dockerfile and
# means the runtime egress firewall doesn't need to allow ghcr.io. NodeSource
# pins their setup script to the current LTS line; bump if you need a specific
# major. nodeGypDependencies (build-essential, python3) are already installed
# above, so native module compilation works out of the box.
RUN curl -fsSL https://deb.nodesource.com/setup_lts.x | bash - && \
    apt-get install -y --no-install-recommends nodejs && \
    rm -rf /var/lib/apt/lists/*

# Eclipse JDT Language Server. The jdtls-lsp plugin shipped in the
# claude-plugins-official marketplace expects a `jdtls` binary on PATH and
# auto-activates for every .java file. Installing here (build time) means the
# egress firewall doesn't need to allow download.eclipse.org at runtime. The
# upstream tarball ships a Python launcher at bin/jdtls that requires Python
# 3.9+, which is satisfied by the python3 package installed above.
#
# --no-same-owner: the upstream tarball encodes its files with a high UID
# (~1001380000) that rootless BuildKit's user namespace cannot set. Without
# this flag, tar fails with "Cannot change ownership ... Invalid argument"
# on every entry. With it, extracted files get the current user's UID/GID.
RUN mkdir -p /opt/jdtls && \
    curl -fsSL https://download.eclipse.org/jdtls/snapshots/jdt-language-server-latest.tar.gz \
        | tar -xz --no-same-owner -C /opt/jdtls && \
    ln -s /opt/jdtls/bin/jdtls /usr/local/bin/jdtls

# JetBrains Kotlin Language Server. The kotlin-lsp plugin (in
# claude-plugins-official) expects a `kotlin-lsp` binary on PATH and
# auto-activates for .kt and .kts. JetBrains distributes the standalone
# Kotlin LSP via download-cdn.jetbrains.com — their GitHub Releases pages
# link to it but don't attach assets directly, so there's no "latest"
# alias to follow; the version is pinned. Bump when newer releases land at
# https://github.com/Kotlin/kotlin-lsp/releases. Linux x64 only — if you
# ever build this image on arm64, swap the asset suffix.
RUN curl -fsSL \
        "https://download-cdn.jetbrains.com/kotlin-lsp/262.2310.0/kotlin-lsp-262.2310.0-linux-x64.zip" \
        -o /tmp/kotlin-lsp.zip && \
    mkdir -p /opt/kotlin-lsp && \
    unzip -q /tmp/kotlin-lsp.zip -d /opt/kotlin-lsp && \
    rm /tmp/kotlin-lsp.zip && \
    kotlin_lsp_sh="$(find /opt/kotlin-lsp -name kotlin-lsp.sh -type f | head -1)" && \
    chmod +x "$kotlin_lsp_sh" && \
    ln -s "$kotlin_lsp_sh" /usr/local/bin/kotlin-lsp

# JetBrains Writerside builder (helpbuilderinspect). Pulled at image-build time
# from the official image so the resulting container needs no Docker socket and
# stays compatible with the hardened egress firewall configured at postCreate.
COPY --from=jetbrains/writerside-builder:2026.04.8711 /opt/builder /opt/builder

COPY writerside.sh /usr/local/bin/writerside
RUN chmod +x /usr/local/bin/writerside

# Bake user-scope Claude Code skills into the image. Drop new skills under
# .devcontainer/claude/skills/ in the repo; post-create.sh syncs them into
# ~/.claude/skills/ on every container start.
#
# Why a /opt staging area instead of copying straight into ~/.claude/skills/?
# devcontainer.json mounts a named Docker volume on /home/vscode/.claude so
# sessions, login (.credentials.json), settings, plugin cache, and auto-memory
# survive container rebuilds. Once that volume is initialized from the image
# (first start), any subsequent rebuild's ~/.claude/skills/ contents would be
# masked by the volume's frozen copy. Staging in /opt/aether-skills/skills/
# keeps the image's skill set outside the volume scope; post-create.sh copies
# from here into the volume on every start, so skill changes from a rebuilt
# image always reach the user without needing to wipe the volume.
RUN install -d -o vscode -g vscode \
        /home/vscode/.claude \
        /opt/aether-skills \
        /opt/aether-skills/skills
COPY --chown=vscode:vscode claude/skills/ /opt/aether-skills/skills/

# leonxlnx/taste-skill: curated frontend/design skill collection. Every entry
# under `skills/` follows the standard `<name>/SKILL.md` layout, so the tree
# merges directly into the staging directory. The stray `llms.txt`
# alongside the skill dirs has no SKILL.md and is ignored by skill discovery.
COPY --from=skill-sources --chown=vscode:vscode /skills/taste-skill/skills/ /opt/aether-skills/skills/

# claudiocebpaz/vite-react-best-practices: single skill with SKILL.md at the
# repo root, so the whole repository is the skill directory.
COPY --from=skill-sources --chown=vscode:vscode /skills/vite-react-best-practices/ /opt/aether-skills/skills/vite-react-best-practices/

# pluginagentmarketplace/custom-plugin-java: a full Claude Code plugin (agents +
# skills + commands). Its upstream marketplace.json follows the SASMP schema
# rather than Claude Code's, so we ship a local wrapper marketplace that points
# at the cloned plugin directory via a relative `./custom-plugin-java` source.
RUN install -d -o vscode -g vscode \
        /opt/claude-marketplaces \
        /opt/claude-marketplaces/java-dev-assistant \
        /opt/claude-marketplaces/java-dev-assistant/.claude-plugin
COPY --chown=vscode:vscode claude/marketplaces/java-dev-assistant/marketplace.json \
        /opt/claude-marketplaces/java-dev-assistant/.claude-plugin/marketplace.json
COPY --from=skill-sources --chown=vscode:vscode /skills/custom-plugin-java/ \
        /opt/claude-marketplaces/java-dev-assistant/custom-plugin-java/

# Upstream pluginagentmarketplace/custom-plugin-java ships a plugin.json whose
# .skills[] entries point at "./skills/<name>/SKILL.md" files. Claude Code's
# loader now rejects file paths in that field with "path is a file; skills
# entries must be directories containing SKILL.md" (12 failures visible in
# /doctor). Rewrite each entry to its parent directory before the plugin is
# cached. Drop this patch once upstream fixes their manifest.
RUN tmp=$(mktemp) && \
    jq '.skills |= map(sub("/SKILL\\.md$"; ""))' \
        /opt/claude-marketplaces/java-dev-assistant/custom-plugin-java/.claude-plugin/plugin.json > "$tmp" && \
    mv "$tmp" /opt/claude-marketplaces/java-dev-assistant/custom-plugin-java/.claude-plugin/plugin.json && \
    chown vscode:vscode /opt/claude-marketplaces/java-dev-assistant/custom-plugin-java/.claude-plugin/plugin.json

# anthropics/claude-plugins-official and pbakaus/impeccable: the two GitHub-hosted
# upstream marketplaces are baked in too, so post-create.sh can pre-populate
# ~/.claude/plugins/cache/ without hitting GitHub at runtime (the egress firewall
# would block the clone after post-create finishes anyway).
COPY --from=skill-sources --chown=vscode:vscode /marketplaces/claude-plugins-official/ \
        /opt/claude-marketplaces/claude-plugins-official/
# Rewrite .name to a non-reserved value. Claude Code rejects any marketplace
# name matching ^(claude|anthropic)-? when registered as a `directory` source
# (anthropics/claude-code#46786) — that namespace is reserved for official
# `github`-source marketplaces from the anthropics org. We have to use a
# directory source because the egress firewall blocks GitHub at runtime, so
# we pick a project-scoped name outside the reserved range. Plugin contents
# and IDs are unchanged; post-create.sh reads .name from this file.
RUN tmp=$(mktemp) && \
    jq '.name = "aether-vendor-plugins"' \
        /opt/claude-marketplaces/claude-plugins-official/.claude-plugin/marketplace.json > "$tmp" && \
    mv "$tmp" /opt/claude-marketplaces/claude-plugins-official/.claude-plugin/marketplace.json && \
    chown vscode:vscode /opt/claude-marketplaces/claude-plugins-official/.claude-plugin/marketplace.json

# Synthesize a plugin manifest for the LSP plugins listed below. Their
# upstream source dirs (anthropics/claude-plugins-official/plugins/<name>/)
# ship only LICENSE + README.md — no .claude-plugin/plugin.json — and Claude
# Code's plugin loader silently ignores cached plugins without a manifest, so
# /plugin shows them missing even though installed_plugins.json and
# enabledPlugins are set. We reconstruct the manifest from marketplace.json's
# plugins[] entry (which carries name, description, version, author,
# lspServers etc.), stripping fields that only make sense in marketplace
# context (source, category, strict). Single source of truth: anything that
# changes upstream in marketplace.json automatically propagates on next
# image rebuild. Add a plugin to LSP_THIN_PLUGINS to provision its manifest.
RUN for p in jdtls-lsp typescript-lsp kotlin-lsp; do \
        install -d -o vscode -g vscode \
            "/opt/claude-marketplaces/claude-plugins-official/plugins/$p/.claude-plugin" && \
        jq --arg n "$p" \
            '.plugins[] | select(.name == $n) | del(.source, .category, .strict)' \
            /opt/claude-marketplaces/claude-plugins-official/.claude-plugin/marketplace.json \
            > "/opt/claude-marketplaces/claude-plugins-official/plugins/$p/.claude-plugin/plugin.json" && \
        chown vscode:vscode \
            "/opt/claude-marketplaces/claude-plugins-official/plugins/$p/.claude-plugin/plugin.json"; \
    done

COPY --from=skill-sources --chown=vscode:vscode /marketplaces/impeccable/ \
        /opt/claude-marketplaces/impeccable/


# The egress firewall, applied by PID 1 as root before any session exists.
#
# It cannot be applied anywhere else. `--security-opt=no-new-privileges` in
# devcontainer.json stops sudo from raising privileges, so every lifecycle
# hook -- all of which run as the unprivileged `vscode` user -- is unable to
# touch iptables. Until this entrypoint existed the rules were never applied
# at all, and post-create.sh printed "Firewall: active" over a container with
# entirely open egress.
#
# ENTRYPOINT survives the dev container's command override, so the rules are
# in place whatever CMD the tooling substitutes. `exec "$@"` then hands over
# to it, and the developer's session arrives afterwards as `vscode` with no
# route back to root.
COPY --chmod=0755 sandbox-entrypoint.sh refresh-firewall.sh /usr/local/lib/sandbox/
ENTRYPOINT ["/usr/local/lib/sandbox/sandbox-entrypoint.sh"]
