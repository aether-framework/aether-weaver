#!/usr/bin/env bash
# Build the egress allowlist. Run as root, from sandbox-entrypoint.sh, before
# any session exists.
#
# It is deliberately not runnable by the developer or by an agent. The
# container drops every capability but the handful in devcontainer.json, keeps
# `no-new-privileges`, and gives the `vscode` user no sudo — so the rules
# written here cannot be read, flushed or amended by anything that runs later.
# That is the whole point: an allowlist a process can lift is a comment.
#
# It fails loudly. An earlier revision of this file used `sudo` and swallowed
# every error, so the container reported an active firewall while having none
# at all. Any failure below now stops the container from starting.
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
    echo "refresh-firewall: must run as root; it is called from sandbox-entrypoint.sh" >&2
    exit 1
fi

# Everything this project reaches on purpose. A host that is not here is not
# reachable, which is the point; add to this list rather than working around
# it, and say in the comment what needs it.
DOMAINS=(
    # Claude Code itself.
    api.anthropic.com
    claude.ai
    statsig.anthropic.com
    # The MCP server configured in post-create.sh.
    context7.com
    mcp.context7.com
    # Git, and the hosts a clone, a release download and the API actually use.
    github.com
    api.github.com
    codeload.github.com
    raw.githubusercontent.com
    objects.githubusercontent.com
    # Maven. repo.maven.apache.org is the host Maven resolves Central through
    # by default; repo1.maven.org is the same content under its own name, and
    # both appear depending on which mirror a pom or settings.xml names.
    repo.maven.apache.org
    repo1.maven.org
    maven.apache.org
    # Gradle: the wrapper's distribution, and the plugin portal the IDE plugin
    # build resolves org.jetbrains.intellij.platform through.
    services.gradle.org
    downloads.gradle.org
    plugins.gradle.org
    # The IntelliJ Platform. The plugin build downloads a full IDE, and the
    # Plugin Verifier downloads one per release it checks against, plus every
    # bundled plugin it walks to.
    plugins.jetbrains.com
    downloads.marketplace.jetbrains.com
    cache-redirector.jetbrains.com
    download.jetbrains.com
    resources.jetbrains.com
    # Node, for anything under the frontend skills.
    registry.npmjs.org
    registry.yarnpkg.com
)

iptables -F OUTPUT
iptables -P OUTPUT DROP

# Loopback first, and before anything is resolved: Docker's embedded resolver
# listens on 127.0.0.11, so every getent below depends on this rule already
# being in place. Setting the policy to DROP without it makes the loop that
# follows resolve nothing and produce an allowlist of exactly zero hosts.
iptables -A OUTPUT -o lo -j ACCEPT

# Replies to connections this container opened. Without it every allowed
# request goes out and nothing comes back.
iptables -A OUTPUT -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT

# DNS to whatever resolver /etc/resolv.conf names, in case it is not the
# embedded one on loopback.
while read -r _ resolver _; do
    [ -n "${resolver:-}" ] || continue
    iptables -A OUTPUT -d "$resolver" -p udp --dport 53 -j ACCEPT
    iptables -A OUTPUT -d "$resolver" -p tcp --dport 53 -j ACCEPT
done < <(grep '^nameserver' /etc/resolv.conf || true)

resolved=0
for domain in "${DOMAINS[@]}"; do
    # `|| true`: one host that does not resolve right now must not stop the
    # container. It stays blocked, which is the safe direction, and the count
    # below reports how many did resolve.
    for ip in $(getent ahostsv4 "$domain" | awk '{print $1}' | sort -u || true); do
        iptables -A OUTPUT -d "$ip" -j ACCEPT
        resolved=$((resolved + 1))
    done
done

if [ "$resolved" -eq 0 ]; then
    echo "refresh-firewall: not one host resolved; DNS is broken and the container would" >&2
    echo "                  come up with no egress at all. Refusing to start." >&2
    exit 1
fi

echo "refresh-firewall: OUTPUT policy DROP, ${resolved} addresses allowed across ${#DOMAINS[@]} hosts"

# These endpoints sit behind CDNs that rotate addresses, so an allowlist of
# pinned IPs is only correct at the moment it is written. It is rebuilt on
# every container start; a session left running for days can still watch an
# address rotate out from under it, and the symptom is a connection that hangs
# rather than one that is refused. Restarting the container re-resolves.
