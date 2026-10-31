#!/usr/bin/env bash
# Start the BlackLab Desktop companion (if needed), link the phone over USB and open the UI.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
PORT="${BLACKLAB_DESKTOP_PORT:-8770}"
URL="http://127.0.0.1:${PORT}/"
LOG="${HOME}/.cache/blacklab-desktop/companion.log"
mkdir -p "$(dirname "$LOG")"

if ! curl -fs -H 'X-BlackLab: 1' "${URL}local/status" >/dev/null 2>&1; then
  if systemctl --user is-enabled blacklab-desktop.service >/dev/null 2>&1; then
    systemctl --user start blacklab-desktop.service
  else
    nohup python3 "${HERE}/companion/blacklab_desktop.py" >>"$LOG" 2>&1 &
  fi
  for _ in $(seq 1 30); do curl -fs -H 'X-BlackLab: 1' "${URL}local/status" >/dev/null 2>&1 && break; sleep 0.2; done
fi

# Open as an app window when a Chromium browser is available, else the default browser.
for b in google-chrome chromium chromium-browser brave-browser microsoft-edge; do
  if command -v "$b" >/dev/null 2>&1; then exec "$b" --app="$URL" --class=BlackLabDesktop; fi
done
if command -v firefox >/dev/null 2>&1; then exec firefox --new-window "$URL"; fi
exec xdg-open "$URL"
