#!/usr/bin/env bash
# Installs BlackLab Desktop for the current user:
#   * a systemd --user service that keeps the companion running (auto USB link + sync),
#   * an app-launcher entry, and
#   * ~/.config/blacklab-desktop/ for your private keys (secrets.json, chmod 600).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
CONF="${HOME}/.config/blacklab-desktop"
mkdir -p "$CONF" "${HOME}/.config/systemd/user" "${HOME}/.local/share/applications" "${HOME}/.local/share/icons/hicolor/scalable/apps"

if [ ! -f "$CONF/secrets.json" ]; then
  printf '{\n  "elevenlabs": "",\n  "developer_knowledge": ""\n}\n' > "$CONF/secrets.json"
fi
chmod 700 "$CONF"; chmod 600 "$CONF/secrets.json"
[ -f "$CONF/config.json" ] || printf '{\n  "model": "qwen3:8b",\n  "voice": { "voice_id": "CwhRBWXzGAHq8TQ4Fs17", "model": "eleven_flash_v2_5" }\n}\n' > "$CONF/config.json"

cat > "${HOME}/.config/systemd/user/blacklab-desktop.service" <<UNIT
[Unit]
Description=BlackLab Desktop companion (local UI, phone link, offline assistant)
After=network.target

[Service]
ExecStart=/usr/bin/env python3 ${HERE}/companion/blacklab_desktop.py
Restart=on-failure
RestartSec=3

[Install]
WantedBy=default.target
UNIT

cp "${HERE}/web/icon.svg" "${HOME}/.local/share/icons/hicolor/scalable/apps/blacklab-desktop.svg"
cat > "${HOME}/.local/share/applications/blacklab-desktop.desktop" <<DESK
[Desktop Entry]
Type=Application
Name=BlackLab Desktop
Comment=Assistant, dashboard and settings for your BlackLab phone app
Exec=${HERE}/blacklab-desktop.sh
Icon=blacklab-desktop
Terminal=false
Categories=Office;Utility;
StartupWMClass=BlackLabDesktop
DESK
chmod +x "${HERE}/blacklab-desktop.sh"

systemctl --user daemon-reload
systemctl --user enable --now blacklab-desktop.service
update-desktop-database "${HOME}/.local/share/applications" >/dev/null 2>&1 || true
echo "BlackLab Desktop installed. Open it from your app launcher or run ${HERE}/blacklab-desktop.sh"
echo "Private keys: ${CONF}/secrets.json"
