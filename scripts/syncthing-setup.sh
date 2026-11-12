#!/usr/bin/env bash
# scripts/syncthing-setup.sh — continuous Obsidian vault sync between this computer and the phone, over Tailscale only.
#
# Replaces the companion's every-5-minutes USB (adb) vault pass with Syncthing: changes flow both ways as they
# happen, conflicts are kept side by side instead of overwritten, and USB debugging no longer needs to be on.
#
# Private by construction: Syncthing here listens only on this computer's Tailscale address, and global
# discovery, local (LAN) discovery, relays, NAT traversal, usage reporting and crash reporting are all off —
# the two devices find each other by their tailnet addresses and nothing else. The same ignore rules as the USB
# sync (desktop/companion/phone_vault.py) keep "85 Atlas", "86 Imported", "87 Entities", "88 Drive" and
# "89 Mobile" on this computer.
#
#   scripts/syncthing-setup.sh                      install + configure this computer, print its device ID
#   scripts/syncthing-setup.sh --phone-id <ID>      add the phone (Syncthing-Fork → ⋮ → Show device ID) and switch
#                                                   the companion from the USB pass to Syncthing
#   scripts/syncthing-setup.sh --status             what's set up
#   scripts/syncthing-setup.sh --undo               back to the USB pass (Syncthing stopped and disabled)
#
# On the phone (once): install Syncthing-Fork, add this computer's device ID with address
# tcp://<this computer's Tailscale IP>:22000, accept the "BlackLab vault" folder into Documents/BlackLab, and in
# that folder's Ignore patterns put the single line:  #include .stglobalignore
# In Syncthing-Fork settings turn off Global discovery, Local discovery, Relaying and NAT traversal too.
set -euo pipefail

VERSION="v2.1.7"   # released 2026-10-21; bump deliberately
HERE="$(cd "$(dirname "$(readlink -f "$0")")/.." && pwd)"
TOOLS="$HOME/.local/blacklab-toolchain"
BIN="$TOOLS/syncthing-$VERSION/syncthing"
ST_HOME="$HOME/.local/state/blacklab-syncthing"
GUI="127.0.0.1:8384"
UNIT="$HOME/.config/systemd/user/blacklab-syncthing.service"
CFG="$HOME/.config/blacklab-desktop/config.json"
FOLDER_ID="blacklab-vault"

say() { printf '  %s\n' "$*"; }
die() { printf '  ✘ %s\n' "$*" >&2; exit 1; }

cfg_get() { python3 -c "import json,sys;d=json.load(open('$CFG')) if __import__('os').path.exists('$CFG') else {};print(d.get('$1',''))"; }
cfg_set() { python3 - "$CFG" "$1" "$2" <<'PY'
import json, os, sys
p, k, v = sys.argv[1:4]
d = json.load(open(p)) if os.path.exists(p) else {}
if v == "__delete__": d.pop(k, None)
else: d[k] = json.loads(v)
open(p, "w").write(json.dumps(d, indent=2))
PY
}

VAULT="$(cfg_get vault_dir)"; VAULT="${VAULT:-$HOME/Obsidian/BlackLab}"; VAULT="${VAULT/#\~/$HOME}"
TS_IP="$(tailscale ip -4 2>/dev/null | head -1 || true)"

api_key() { sed -n 's:.*<apikey>\(.*\)</apikey>.*:\1:p' "$ST_HOME/config.xml" 2>/dev/null | head -1; }
api() { local m="$1" p="$2"; shift 2; curl -fsS -m 20 -X "$m" -H "X-API-Key: $(api_key)" -H 'Content-Type: application/json' "http://$GUI$p" "$@"; }

case "${1:-}" in
  --status)
    systemctl --user is-active blacklab-syncthing.service >/dev/null 2>&1 && say "Syncthing running ($VERSION)" || say "Syncthing not running"
    say "Vault sync: $( [ "$(cfg_get vault_sync)" = syncthing ] && echo 'Syncthing (USB pass off)' || echo 'USB pass (companion, every 5 min)')"
    [ -n "$(api_key)" ] && api GET /rest/system/status 2>/dev/null | python3 -c 'import json,sys;print("  This computer: "+json.load(sys.stdin)["myID"])' || true
    exit 0 ;;
  --undo)
    systemctl --user disable --now blacklab-syncthing.service 2>/dev/null || true
    cfg_set vault_sync __delete__
    systemctl --user restart blacklab-desktop.service 2>/dev/null || true
    say "Back to the USB vault pass; Syncthing stopped. Its files stay in ${ST_HOME/#$HOME/\~} and ${BIN%/*}."
    exit 0 ;;
esac

PHONE_ID=""; PHONE_IP=""
while [ $# -gt 0 ]; do case "$1" in --phone-id) PHONE_ID="${2:-}"; shift 2 ;; --phone-ip) PHONE_IP="${2:-}"; shift 2 ;; *) die "unknown option $1" ;; esac; done

[[ "$TS_IP" =~ ^100\. ]] || die "Tailscale isn't up on this computer (tailscale ip -4) — Syncthing is only ever set up on the tailnet."
[ -d "$VAULT" ] || die "No vault at $VAULT"

echo "Syncthing for the BlackLab vault (Tailscale only)"

# 1. the binary: pinned version, checked against the release's published SHA-256 list
if [ ! -x "$BIN" ]; then
  TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
  TAR="syncthing-linux-amd64-$VERSION.tar.gz"
  URL="https://github.com/syncthing/syncthing/releases/download/$VERSION"
  curl -fsSL -m 300 -o "$TMP/$TAR" "$URL/$TAR"
  curl -fsSL -m 60 -o "$TMP/sha256sum.txt.asc" "$URL/sha256sum.txt.asc"
  WANT="$(grep -E "  $TAR\$" "$TMP/sha256sum.txt.asc" | awk '{print $1}')"
  GOT="$(sha256sum "$TMP/$TAR" | awk '{print $1}')"
  [ -n "$WANT" ] && [ "$WANT" = "$GOT" ] || die "Checksum mismatch for $TAR — not installing."
  mkdir -p "${BIN%/*}"
  tar -xzf "$TMP/$TAR" -C "$TMP"
  install -m 755 "$TMP/syncthing-linux-amd64-$VERSION/syncthing" "$BIN"
  say "✔ Installed Syncthing $VERSION (sha256 verified) in ${BIN%/*}"
fi

# 2. a user service of its own (separate from any other Syncthing on this computer)
mkdir -p "$ST_HOME" "${UNIT%/*}"
cat > "$UNIT" <<UNIT
[Unit]
Description=Syncthing for the BlackLab vault (Tailscale only)
After=network-online.target

[Service]
ExecStart=$BIN serve --no-browser --no-restart --home=$ST_HOME --gui-address=http://$GUI
Restart=on-failure
RestartSec=5
SuccessExitStatus=3 4

[Install]
WantedBy=default.target
UNIT
systemctl --user daemon-reload
systemctl --user enable --now blacklab-syncthing.service
for _ in $(seq 1 60); do [ -n "$(api_key)" ] && api GET /rest/system/ping >/dev/null 2>&1 && break; sleep 0.5; done
api GET /rest/system/ping >/dev/null 2>&1 || die "Syncthing didn't start — journalctl --user -u blacklab-syncthing"
MY_ID="$(api GET /rest/system/status | python3 -c 'import json,sys;print(json.load(sys.stdin)["myID"])')"

# 3. tailnet only: no discovery servers, no LAN broadcasts, no relays, no NAT punching, no reporting
api PATCH /rest/config/options -d "$(python3 -c "import json;print(json.dumps({
  'listenAddresses': ['tcp://$TS_IP:22000'], 'globalAnnounceEnabled': False, 'localAnnounceEnabled': False,
  'relaysEnabled': False, 'natEnabled': False, 'urAccepted': -1, 'crashReportingEnabled': False,
  'autoUpgradeIntervalH': 0, 'startBrowser': False}))")" >/dev/null
say "✔ Listening on tcp://$TS_IP:22000 only; discovery, relays, NAT traversal and reporting off"

# 4. the vault folder, with the same ignore rules as the USB sync
python3 -c "import sys;sys.path.insert(0,'$HERE/desktop/companion');import phone_vault;from pathlib import Path;phone_vault.write_stignore(Path('$VAULT'), $( [ "$(cfg_get phone_vault_local)" = True ] && echo True || echo False))"
DEVICES="[{\"deviceID\":\"$MY_ID\"}]"
if [ -n "$PHONE_ID" ]; then
  if [ -z "$PHONE_IP" ]; then PHONE_IP="$(tailscale status 2>/dev/null | awk '$4=="android"{print $1; exit}')"; fi
  [[ "$PHONE_IP" =~ ^100\. ]] || die "Couldn't find the phone on the tailnet — pass --phone-ip <its 100.x address>"
  api PUT "/rest/config/devices/$PHONE_ID" -d "{\"deviceID\":\"$PHONE_ID\",\"name\":\"Phone\",\"addresses\":[\"tcp://$PHONE_IP:22000\"],\"autoAcceptFolders\":false}" >/dev/null
  DEVICES="[{\"deviceID\":\"$MY_ID\"},{\"deviceID\":\"$PHONE_ID\"}]"
  say "✔ Phone added at tcp://$PHONE_IP:22000"
fi
api PUT "/rest/config/folders/$FOLDER_ID" -d "{\"id\":\"$FOLDER_ID\",\"label\":\"BlackLab vault\",\"path\":\"$VAULT\",\"type\":\"sendreceive\",\"devices\":$DEVICES,\"fsWatcherEnabled\":true,\"versioning\":{\"type\":\"trashcan\",\"params\":{\"cleanoutDays\":\"30\"}}}" >/dev/null
say "✔ Folder \"BlackLab vault\" → ${VAULT/#$HOME/\~} (ignore rules in .stglobalignore; deleted files kept 30 days in .stversions)"

# 5. hand the vault over from the USB pass once the phone is in
if [ -n "$PHONE_ID" ]; then
  cfg_set vault_sync '"syncthing"'
  systemctl --user restart blacklab-desktop.service 2>/dev/null || true
  say "✔ The companion's USB vault pass is off (scripts/syncthing-setup.sh --undo turns it back on)"
fi

echo
say "This computer's device ID:  $MY_ID"
say "Its address for the phone:  tcp://$TS_IP:22000"
[ -z "$PHONE_ID" ] && say "Next: add that on the phone in Syncthing-Fork, then run: scripts/syncthing-setup.sh --phone-id <the phone's device ID>"
exit 0
