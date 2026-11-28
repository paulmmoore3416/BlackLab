#!/usr/bin/env bash
# BlackLab nightly backup (restic: encrypted, deduplicated, versioned). Run by blacklab-backup.timer at 03:30,
# between the 03:00 system check and the 04:00 restart. The next night's check reports how it went.
#
# What: the vault, kb-core's memory (copied with kb-core paused for a moment, so it's consistent), BlackLab's
# settings + keys, the app signing keystore, the code repo, the desktop AI folder, Syncthing's identity,
# Claude's memory/settings and the FamilyCraft world. Not: Ollama models, caches, build output (all re-creatable).
#
# Where: every repository listed in ~/.config/blacklab-backup/repos (one per line). The first is local
# (~/.local/share/blacklab-backup/restic: protects against deleting or corrupting files); add an offsite one,
# e.g.  rclone:gdrive:BlackLab-backup  or  b2:<bucket>:blacklab , to survive losing this drive.
# The repository password is ~/.config/blacklab-backup/password — keep a copy OUTSIDE this computer.
#
#   blacklab-backup.sh             back up now
#   blacklab-backup.sh --init      create the password/repos file and initialize every repository
#   blacklab-backup.sh --snapshots list what's stored
#   blacklab-backup.sh --restore <snapshot> <dir>   restore a snapshot into <dir> (never over the live files)
set -uo pipefail
CFG="$HOME/.config/blacklab-backup"
STAGE="$HOME/.cache/blacklab-backup/stage"
STATUS="$HOME/.local/state/blacklab-desktop/health/backup.json"
export RESTIC_PASSWORD_FILE="$CFG/password"
export RESTIC_CACHE_DIR="$HOME/.cache/restic"
KB="$HOME/.local/share/omnix/kb-core"

SOURCES=(
  "$HOME/Obsidian/BlackLab"
  "$HOME/.config/omnix" "$HOME/.config/blacklab-desktop" "$HOME/.config/blacklab" "$HOME/.config/blacklab-crypto"
  "$HOME/.local/state/blacklab-syncthing"
  "$HOME/Desktop/BlackLab" "$HOME/Desktop/BlackLab AI"
  "$HOME/Desktop/FamilyCraft/server/worlds" "$HOME/Desktop/FamilyCraft/server/server.properties"
  "$HOME/Desktop/FamilyCraft/server/allowlist.json" "$HOME/Desktop/FamilyCraft/server/permissions.json"
  "$HOME/.claude/settings.json" "$HOME/.claude.json" "$HOME/.claude/skills"
  "$STAGE"
)
EXCLUDES=(--exclude "$HOME/Desktop/BlackLab/build" --exclude "$HOME/Desktop/BlackLab/app/build" --exclude "$HOME/Desktop/BlackLab/.gradle"
          --exclude "$HOME/Desktop/BlackLab/baselineprofile/build" --exclude "$HOME/Desktop/BlackLab/releases" --exclude "*.apk"
          --exclude "$HOME/Desktop/BlackLab/Minecraft - Bedrock Edition*" --exclude "__pycache__" --exclude ".stversions")

repos() { grep -vE '^\s*(#|$)' "$CFG/repos" 2>/dev/null; }

status() {  # status <ok|fail> <message> [per-repo json]
  mkdir -p "$(dirname "$STATUS")"
  local per="${3:-}"; [ -n "$per" ] || per='{}'
  python3 - "$STATUS" "$1" "$2" "$per" <<'PY'
import json, sys, time
path, ok, msg, repos = sys.argv[1:5]
json.dump({"at": time.time(), "ok": ok == "ok", "message": msg, "repos": json.loads(repos)}, open(path, "w"), indent=1)
PY
  chmod 600 "$STATUS"
}

init() {
  mkdir -p "$CFG" && chmod 700 "$CFG"
  [ -s "$CFG/password" ] || { (umask 077; head -c 32 /dev/urandom | base64 > "$CFG/password"); echo "New repository password in $CFG/password"; }
  [ -s "$CFG/repos" ] || printf '# one restic repository per line; the first is local\n%s\n' "$HOME/.local/share/blacklab-backup/restic" > "$CFG/repos"
  local r; while read -r r; do
    restic -r "$r" cat config >/dev/null 2>&1 && echo "already initialized: $r" || restic -r "$r" init
  done < <(repos)
}

stage_kb_core() {  # consistent copy of kb-core's databases: pause it for the seconds the copy takes
  rm -rf "$STAGE"; mkdir -p "$STAGE/kb-core"
  local was_up=0
  systemctl --user is-active --quiet omnix-kb-core && was_up=1 && systemctl --user stop omnix-kb-core
  find "$KB" -maxdepth 1 -type f \( -name "memory.db*" -o -name "*.jsonl" -o -name "kb.sqlite3*" \) -exec cp -p {} "$STAGE/kb-core/" \;
  [ -d "$KB/nli" ] && cp -rp "$KB/nli" "$STAGE/kb-core/" 2>/dev/null
  [ "$was_up" = 1 ] && systemctl --user start omnix-kb-core
  # Claude Code memory (small) — not the session transcripts
  mkdir -p "$STAGE/claude-memory"
  for d in "$HOME"/.claude/projects/*/memory; do [ -d "$d" ] && cp -rp "$d" "$STAGE/claude-memory/$(basename "$(dirname "$d")")"; done
}

backup() {
  [ -s "$CFG/password" ] && [ -n "$(repos)" ] || { status fail "not set up: run blacklab-backup.sh --init"; exit 1; }
  stage_kb_core
  local src=() s; for s in "${SOURCES[@]}"; do [ -e "$s" ] && src+=("$s"); done
  local results="{}" failed=0 r out snap
  while read -r r; do
    out=$(nice -n 10 ionice -c3 restic -r "$r" backup --quiet --json --tag nightly --host "$(hostname)" "${EXCLUDES[@]}" "${src[@]}" 2>&1 | tail -1)
    if snap=$(python3 -c 'import json,sys;d=json.loads(sys.argv[1]);print(d["snapshot_id"][:8], d.get("total_bytes_processed",0))' "$out" 2>/dev/null); then
      restic -r "$r" forget --quiet --prune --keep-daily 7 --keep-weekly 4 --keep-monthly 12 >/dev/null 2>&1
      # Sundays: verify the repository and re-read 5% of the stored data
      [ "$(date +%u)" = 7 ] && { restic -r "$r" check --read-data-subset=5% >/dev/null 2>&1 || { failed=1; snap="$snap CHECK-FAILED"; }; }
      results=$(python3 -c 'import json,sys;d=json.loads(sys.argv[1]);i,b,*x=sys.argv[3].split();d[sys.argv[2]]={"ok":not x,"snapshot":i,"bytes":int(b)};print(json.dumps(d))' "$results" "$r" "$snap")
    else
      failed=1
      results=$(python3 -c 'import json,sys;d=json.loads(sys.argv[1]);d[sys.argv[2]]={"ok":False,"error":sys.argv[3][-300:]};print(json.dumps(d))' "$results" "$r" "$out")
    fi
  done < <(repos)
  rm -rf "$STAGE"
  if [ "$failed" = 0 ]; then status ok "backed up to $(repos | wc -l) repositor$( [ "$(repos | wc -l)" = 1 ] && echo y || echo ies)" "$results"
  else status fail "a repository failed" "$results"; exit 1; fi
}

case "${1:-}" in
  --init) init ;;
  --snapshots) while read -r r; do echo "== $r"; restic -r "$r" snapshots --compact; done < <(repos) ;;
  --restore) [ $# -eq 3 ] || { echo "usage: $0 --restore <snapshot> <empty dir>"; exit 1; }
             restic -r "$(repos | head -1)" restore "$2" --target "$3" ;;
  "") backup ;;
  *) sed -n '2,19p' "$0"; exit 1 ;;
esac
