#!/usr/bin/env bash
# Run shoot.py against the Chromium installed under $HOME. Both the browser and its shared
# libraries live outside the system prefix, because this container has no apt lists and no
# passwordless sudo; see the module README under "Looking at the built site".
set -euo pipefail
export LD_LIBRARY_PATH="$HOME/.local/chromium-libs/usr/lib/x86_64-linux-gnu:$HOME/.local/chromium-libs/lib/x86_64-linux-gnu${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
exec "$HOME/.local/venvs/docshot/bin/python" "$(dirname "$0")/shoot.py" "$@"
