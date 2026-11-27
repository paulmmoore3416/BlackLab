#!/usr/bin/env bash
# BlackLab autopilot — user part (no sudo). Installs three systemd --user units:
#
#   blacklab-boot.service           at every power-on/restart: BlackLab-only mode (other projects stopped),
#                                   waits for Ollama, runs ./daddyshome, then the after-restart check
#   blacklab-nightly-review.timer   02:30 Claude Code reviews the day's commits, read-only (Sonnet works, Opus advises;
#                                   only on days with commits; report in the vault, summary on the phone — nightly_review.py)
#   blacklab-review-now.service     on demand (no timer): the phone's "Run review now" — only commits since the last review
#   blacklab-nightly-scan.timer     03:00 (America/Chicago) full system check → report on the phone
#   blacklab-watchdog.timer         every 5 min: companion answering, plugged-in phone linked
#   blacklab-kb-maintenance.timer   03:20 kb-core maintenance (keeps vault/library search fast)
#   blacklab-backup.timer           03:30 encrypted restic backup (blacklab-backup.sh; result in the next check)
#
# Lingering (loginctl enable-linger) makes these run at boot without anyone logging in.
# The 04:00 restart needs root: run  sudo desktop/autopilot/install-system.sh  once.
# Remove:  desktop/autopilot/install.sh --uninstall
set -euo pipefail
HERE="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
UNITS="$HOME/.config/systemd/user"
PY="/usr/bin/python3"
SCAN_TIME="$(python3 -c 'import json,os;print(json.load(open(os.path.expanduser("~/.config/blacklab-desktop/autopilot.json"))).get("scan_time","03:00"))' 2>/dev/null || echo 03:00)"
REVIEW_TIME="$(python3 -c 'import json,os;print(json.load(open(os.path.expanduser("~/.config/blacklab-desktop/autopilot.json"))).get("review_time","02:30"))' 2>/dev/null || echo 02:30)"

if [ "${1:-}" = "--uninstall" ]; then
  systemctl --user disable --now blacklab-boot.service blacklab-nightly-scan.timer blacklab-watchdog.timer blacklab-backup.timer blacklab-kb-maintenance.timer blacklab-nightly-review.timer blacklab-review-now.service 2>/dev/null || true
  rm -f "$UNITS"/blacklab-{boot,nightly-scan,watchdog,backup,kb-maintenance,nightly-review,review-now}.{service,timer}
  systemctl --user daemon-reload
  echo "Autopilot removed (other projects you turned off stay off until you re-enable them)."
  exit 0
fi

mkdir -p "$UNITS" "$HOME/.config/blacklab-desktop"
chmod +x "$HERE/blacklab_health.py" "$HERE/repair-vault-git.sh" "$HERE/nightly_review.py" "$HERE/blacklab-ops" "$REPO/daddyshome"

cat > "$UNITS/blacklab-boot.service" <<UNIT
[Unit]
Description=BlackLab autopilot: BlackLab-only mode + ./daddyshome at boot
After=blacklab-desktop.service network-online.target
Wants=network-online.target blacklab-desktop.service

[Service]
Type=oneshot
RemainAfterExit=yes
# adb's server outlives this unit; keep it alive when the oneshot finishes
KillMode=process
TimeoutStartSec=20min
Environment=PATH=$HOME/Android/Sdk/platform-tools:/usr/local/bin:/usr/bin:/bin
ExecStartPre=$PY $HERE/blacklab_health.py --enforce
ExecStartPre=/bin/sh -c 'for i in \$(seq 1 150); do curl -fsS -m 2 http://127.0.0.1:11434/api/version >/dev/null 2>&1 && exit 0; sleep 2; done; exit 0'
ExecStart=$REPO/daddyshome
ExecStartPost=$PY $HERE/blacklab_health.py --boot

[Install]
WantedBy=default.target
UNIT

cat > "$UNITS/blacklab-nightly-scan.service" <<UNIT
[Unit]
Description=BlackLab autopilot: nightly system check (report shows on the phone)

[Service]
Type=oneshot
Nice=10
IOSchedulingClass=idle
TimeoutStartSec=50min
Environment=PATH=$HOME/Android/Sdk/platform-tools:/usr/local/bin:/usr/bin:/bin
ExecStart=$PY $HERE/blacklab_health.py --nightly
UNIT

cat > "$UNITS/blacklab-nightly-scan.timer" <<UNIT
[Unit]
Description=BlackLab autopilot: nightly system check at $SCAN_TIME Central

[Timer]
OnCalendar=*-*-* $SCAN_TIME:00 America/Chicago
Persistent=false
AccuracySec=1min

[Install]
WantedBy=timers.target
UNIT

cat > "$UNITS/blacklab-watchdog.service" <<UNIT
[Unit]
Description=BlackLab autopilot: keep the desktop AI and phone link up

[Service]
Type=oneshot
Environment=PATH=$HOME/Android/Sdk/platform-tools:/usr/local/bin:/usr/bin:/bin
ExecStart=$PY $HERE/blacklab_health.py --watch
UNIT

cat > "$UNITS/blacklab-watchdog.timer" <<UNIT
[Unit]
Description=BlackLab autopilot: link watchdog every 5 minutes

[Timer]
OnBootSec=6min
OnUnitActiveSec=5min
AccuracySec=30s

[Install]
WantedBy=timers.target
UNIT

cat > "$UNITS/blacklab-kb-maintenance.service" <<UNIT
[Unit]
Description=BlackLab autopilot: nightly kb-core maintenance (FTS optimize, planner stats, VACUUM)
# Without it the full-text index fragments under vault re-syncs and searches slow from ~0.3 s to 30+ s
Requisite=omnix-kb-core.service
After=omnix-kb-core.service

[Service]
Type=oneshot
Nice=10
TimeoutStartSec=20min
EnvironmentFile=%h/.config/omnix/kb-core.env
Environment=PYTHONPATH=%h/.local/share/omnix/kb-core/app
ExecStart=%h/.local/share/omnix/kb-core/venv/bin/python -m kb_core maintenance
UNIT

cat > "$UNITS/blacklab-kb-maintenance.timer" <<UNIT
[Unit]
Description=BlackLab autopilot: kb-core maintenance at 03:20 Central (after the check, before the backup)

[Timer]
OnCalendar=*-*-* 03:20:00 America/Chicago
Persistent=true
AccuracySec=1min

[Install]
WantedBy=timers.target
UNIT

cat > "$UNITS/blacklab-nightly-review.service" <<UNIT
[Unit]
Description=BlackLab autopilot: Claude Code reviews the day's commits (read-only; skips days without commits)
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
Nice=10
TimeoutStartSec=28min
Environment=PATH=$HOME/.npm-global/bin:$HOME/.local/bin:/usr/local/bin:/usr/bin:/bin
ExecStart=$PY $HERE/nightly_review.py
UNIT

cat > "$UNITS/blacklab-review-now.service" <<UNIT
[Unit]
Description=BlackLab autopilot: on-demand code review (phone "Run review now"; only commits since the last review)
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
Nice=10
TimeoutStartSec=28min
Environment=PATH=$HOME/.npm-global/bin:$HOME/.local/bin:/usr/local/bin:/usr/bin:/bin
ExecStart=$PY $HERE/nightly_review.py --on-demand
UNIT

cat > "$UNITS/blacklab-nightly-review.timer" <<UNIT
[Unit]
Description=BlackLab autopilot: nightly code review at $REVIEW_TIME Central (done before the 03:00 check)

[Timer]
OnCalendar=*-*-* $REVIEW_TIME:00 America/Chicago
Persistent=false
AccuracySec=1min

[Install]
WantedBy=timers.target
UNIT

cat > "$UNITS/blacklab-backup.service" <<UNIT
[Unit]
Description=BlackLab autopilot: nightly encrypted backup (restic)

[Service]
Type=oneshot
Nice=10
IOSchedulingClass=idle
TimeoutStartSec=25min
ExecStart=$HERE/blacklab-backup.sh
UNIT

cat > "$UNITS/blacklab-backup.timer" <<UNIT
[Unit]
Description=BlackLab autopilot: nightly backup at 03:30 Central (after the check, before the restart)

[Timer]
OnCalendar=*-*-* 03:30:00 America/Chicago
Persistent=true
AccuracySec=1min

[Install]
WantedBy=timers.target
UNIT

systemctl --user daemon-reload
systemctl --user enable blacklab-boot.service blacklab-nightly-scan.timer blacklab-watchdog.timer blacklab-backup.timer blacklab-kb-maintenance.timer blacklab-nightly-review.timer
systemctl --user start blacklab-nightly-scan.timer blacklab-watchdog.timer blacklab-backup.timer blacklab-kb-maintenance.timer blacklab-nightly-review.timer
[ -s "$HOME/.config/blacklab-backup/password" ] || "$HERE/blacklab-backup.sh" --init
loginctl show-user "$USER" -p Linger | grep -q yes || loginctl enable-linger "$USER" || echo "Run: loginctl enable-linger $USER (so this starts at boot without logging in)"
echo "Autopilot installed:"
systemctl --user list-timers blacklab-nightly-scan.timer blacklab-watchdog.timer --no-pager | head -4
echo "Next: sudo $HERE/install-system.sh   (the 04:00 restart and turning off other projects' system services)"
