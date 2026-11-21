#!/usr/bin/env bash
# BlackLab autopilot — system part (run once with sudo).
#
#   * blacklab-nightly-reboot.timer: restarts the computer at 04:00 America/Chicago, after the 03:00 check
#     (waits up to 30 min if the check is still running). The reboot script is copied to /usr/local/sbin so
#     root never runs a file your user account can edit.
#   * turns off the system services listed under "stop_system_services" in
#     ~/.config/blacklab-desktop/autopilot.json (other projects — BlackLab doesn't use them)
#
# Usage:  sudo desktop/autopilot/install-system.sh               install / update
#         sudo desktop/autopilot/install-system.sh --lock-ollama   also limit Ollama to this computer (127.0.0.1)
#         sudo desktop/autopilot/install-system.sh --uninstall     remove the nightly restart
set -euo pipefail
[ "$(id -u)" = 0 ] || { echo "Run with sudo: sudo $0 $*"; exit 1; }
OWNER="${SUDO_USER:-paul}"
OWNER_HOME="$(getent passwd "$OWNER" | cut -d: -f6)"
CONF="$OWNER_HOME/.config/blacklab-desktop/autopilot.json"
REBOOT_TIME="$(python3 -c "import json;print(json.load(open('$CONF')).get('reboot_time','04:00'))" 2>/dev/null || echo 04:00)"
[[ "$REBOOT_TIME" =~ ^[0-2][0-9]:[0-5][0-9]$ ]] || REBOOT_TIME=04:00

if [ "${1:-}" = "--uninstall" ]; then
  systemctl disable --now blacklab-nightly-reboot.timer 2>/dev/null || true
  rm -f /etc/systemd/system/blacklab-nightly-reboot.{service,timer} /usr/local/sbin/blacklab-nightly-reboot
  systemctl daemon-reload
  echo "Nightly restart removed."; exit 0
fi

install -m 755 -o root -g root /dev/stdin /usr/local/sbin/blacklab-nightly-reboot <<SH
#!/bin/sh
# Restart after the nightly BlackLab check — but never in the middle of it.
PID_FILE="$OWNER_HOME/.local/state/blacklab-desktop/health/scan.pid"
for i in \$(seq 1 60); do
  [ -f "\$PID_FILE" ] && kill -0 "\$(cat "\$PID_FILE" 2>/dev/null)" 2>/dev/null || break
  sleep 30
done
logger -t blacklab-autopilot "nightly restart"
exec /usr/bin/systemctl reboot
SH

cat > /etc/systemd/system/blacklab-nightly-reboot.service <<UNIT
[Unit]
Description=BlackLab autopilot: nightly restart

[Service]
Type=oneshot
ExecStart=/usr/local/sbin/blacklab-nightly-reboot
UNIT

cat > /etc/systemd/system/blacklab-nightly-reboot.timer <<UNIT
[Unit]
Description=BlackLab autopilot: restart at $REBOOT_TIME Central (after the nightly check)

[Timer]
OnCalendar=*-*-* $REBOOT_TIME:00 America/Chicago
# never "catch up" a missed restart right after booting
Persistent=false
AccuracySec=1min

[Install]
WantedBy=timers.target
UNIT

systemctl daemon-reload
systemctl enable --now blacklab-nightly-reboot.timer
echo "Nightly restart: $(systemctl list-timers blacklab-nightly-reboot.timer --no-pager | sed -n 2p)"

# other projects' system services
for s in $(python3 -c "import json,re;[print(x) for x in json.load(open('$CONF')).get('stop_system_services',[]) if re.fullmatch(r'[A-Za-z0-9@._-]+',x)]" 2>/dev/null); do
  if [[ "$s" == snap.*.*.service ]]; then
    app="${s#snap.}"; app="${app%.service}"
    snap stop --disable "$app" >/dev/null 2>&1 && echo "Turned off $s (snap)" || echo "Couldn't turn off $s"
  else
    systemctl disable --now "$s" >/dev/null 2>&1 && echo "Turned off $s" || echo "Couldn't turn off $s"
  fi
done

if [ "${1:-}" = "--lock-ollama" ]; then
  mkdir -p /etc/systemd/system/ollama.service.d
  printf '[Service]\nEnvironment=OLLAMA_HOST=127.0.0.1:11434\n' > /etc/systemd/system/ollama.service.d/zz-blacklab-localhost.conf
  systemctl daemon-reload && systemctl restart ollama
  echo "Ollama now listens on 127.0.0.1 only"
fi
echo "Done. Undo a service with: sudo systemctl enable --now <name>"
