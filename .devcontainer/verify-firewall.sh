#!/usr/bin/env bash
# Prove the egress firewall is real, on every container start.
#
# This replaces the line post-create.sh used to print. That line asserted
# nothing: it said "Firewall: active — only whitelisted hosts reachable" from
# a script that had no way of knowing, over a container whose rules had
# silently failed to apply. A claim nothing checks is how a sandbox becomes
# decorative without anybody noticing.
#
# So this reaches for a host that is deliberately absent from the allowlist
# and requires the attempt to fail. Reachable means there is no firewall,
# whatever the start-up log said, and that is reported as the failure it is.
set -uo pipefail

# Chosen because it is reserved for exactly this kind of use, is not a
# dependency of anything here, and will never have a reason to appear on the
# allowlist.
readonly CANARY="https://example.com"

if curl --silent --show-error --max-time 8 --output /dev/null "$CANARY" 2>/dev/null; then
    cat >&2 <<'EOF'

  EGRESS FIREWALL IS NOT ACTIVE

  example.com answered, and it is not on the allowlist. This container has
  open egress: everything running in it, the agent included, can reach any
  host on the internet.

  The rules are applied by PID 1 in .devcontainer/sandbox-entrypoint.sh. That
  they are missing means the container was not started from the current image
  or the entrypoint did not run. Rebuild the container -- do not carry on and
  do not treat this as cosmetic.

EOF
    exit 1
fi

echo "Aether Weaver sandbox active. Egress restricted to the allowlist in"
echo ".devcontainer/refresh-firewall.sh; verified against a host outside it."
echo "Run \`claude\` to start; plugins and MCP are pre-provisioned."
