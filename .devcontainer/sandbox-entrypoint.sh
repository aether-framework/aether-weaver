#!/usr/bin/env bash
# PID 1. Applies the egress firewall as root, then hands the container over.
#
# This exists because the firewall cannot be applied from any other place.
# `--security-opt=no-new-privileges` stops a setuid binary from raising
# privileges, so `sudo iptables` fails for the `vscode` user in every
# lifecycle hook the dev container offers — postCreate and postStart alike.
# The only process that can write the rules is one that is already root, and
# under this configuration that is exactly one process: this one, before any
# session exists.
#
# The property that buys is not convenience. Nothing that runs afterwards --
# the developer's shell, the build, Claude Code -- can reach iptables at all:
# no sudo, no CAP_NET_ADMIN in the unprivileged session, no way to flush what
# is set here. An agent inside the container can hit the wall; it cannot move
# it.
#
# Failure is fatal on purpose. A container that comes up believing it is
# firewalled while it is not is worse than one that refuses to start, and that
# is precisely the state this repository was in before: three independent
# faults -- no iptables binary in the image, sudo blocked by
# no-new-privileges, and errors swallowed for want of `set -e` -- each of
# which alone was enough, and none of which was ever reported.
set -euo pipefail

readonly HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ "$(id -u)" -ne 0 ]; then
    echo "sandbox-entrypoint: must run as root (uid $(id -u)); check containerUser in devcontainer.json" >&2
    exit 1
fi

echo "sandbox-entrypoint: applying egress firewall"
"$HERE/refresh-firewall.sh"

# The workspace and the ~/.claude volume, made writable by the session user.
# post-create.sh used to attempt this with `sudo chown ... || true`, which
# under no-new-privileges failed silently on every start -- the same fault
# that left the firewall unapplied. Here it is root doing it, before the
# session exists, and a failure is reported rather than discarded.
for dir in "${WORKSPACE_DIR:-}" /home/vscode/.claude; do
    [ -n "$dir" ] && [ -d "$dir" ] || continue
    chown -R vscode:vscode "$dir"
done

echo "sandbox-entrypoint: firewall applied, dropping to the container command"

exec "$@"
