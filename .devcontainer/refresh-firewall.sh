#!/usr/bin/env bash
# Rebuild the egress-allowlist iptables ruleset from scratch.
#
# Why this exists: api.anthropic.com and most other allowed endpoints sit
# behind Cloudflare and rotate IPs frequently. The original post-create
# pinned IPs once at container-create time, so the allowlist drifted out
# of sync with reality after the next DNS rotation and Claude Code hung
# on "Retrying...". This script is idempotent — flushes OUTPUT and
# reapplies the rules from a fresh DNS lookup — and is called from both
# postCreate (initial setup) and postStart (every container start).
set -u

DOMAINS=(
    api.anthropic.com
    claude.ai
    github.com
    raw.githubusercontent.com
    maven.apache.org
    repo1.maven.org
    services.gradle.org
    gradle.org
    registry.npmjs.org
    registry.yarnpkg.com
    context7.com
    mcp.context7.com
)

sudo iptables -F OUTPUT
sudo iptables -P OUTPUT DROP
sudo iptables -A OUTPUT -o lo -j ACCEPT
sudo iptables -A OUTPUT -d 127.0.0.1 -j ACCEPT
sudo iptables -A OUTPUT -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT

for domain in "${DOMAINS[@]}"; do
    for ip in $(getent ahosts "$domain" | awk '{print $1}' | sort -u); do
        sudo iptables -A OUTPUT -d "$ip" -j ACCEPT
    done
done
