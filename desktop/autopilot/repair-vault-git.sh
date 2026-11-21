#!/usr/bin/env bash
# repair-vault-git.sh — fix the Obsidian vault's version history after a crash or power cut.
#
# A crash while git writes leaves empty object files; from then on HEAD can't be read and the companion
# stops saving vault versions. Your notes (the working tree) are never touched — only .git is repaired:
#   1. pauses the BlackLab Desktop companion (it commits to the vault) and backs up .git
#   2. removes empty object files
#   3. points the branch back at the newest commit that is still whole (from the reflog)
#   4. rebuilds the index from that commit if it's damaged, then checks everything with git fsck
#   5. starts the companion again — its next pass saves today's notes as a new version
#
# Usage: desktop/autopilot/repair-vault-git.sh [vault path]   (default: the vault BlackLab Desktop uses)
set -uo pipefail
VAULT="${1:-$(python3 -c 'import json,os;print(json.load(open(os.path.expanduser("~/.config/blacklab-desktop/config.json"))).get("vault_path") or os.path.expanduser("~/Obsidian/BlackLab"))' 2>/dev/null)}"
GIT="$VAULT/.git"
[ -d "$GIT" ] || { echo "No git history at $GIT"; exit 1; }
BACK="$HOME/.local/state/blacklab-desktop/backups"; mkdir -p "$BACK"; chmod 700 "$BACK"

echo "Vault: $VAULT"
systemctl --user stop blacklab-desktop.service 2>/dev/null && echo "Paused the companion"
trap 'systemctl --user start blacklab-desktop.service 2>/dev/null && echo "Companion started again"' EXIT

SNAP="$BACK/vault-git-$(date +%Y%m%d-%H%M%S).tgz"
tar -C "$VAULT" -czf "$SNAP" .git && echo "Backed up .git → $SNAP"

n=$(find "$GIT/objects" -type f -empty | wc -l)
find "$GIT/objects" -type f -empty -delete
echo "Removed $n empty object file(s)"

branch="$(git -C "$VAULT" symbolic-ref -q HEAD || echo refs/heads/main)"
if ! git -C "$VAULT" rev-parse -q --verify "HEAD^{commit}" >/dev/null 2>&1 || ! git -C "$VAULT" cat-file -e "HEAD^{tree}" 2>/dev/null; then
  good=""
  # newest first: every commit the branch has pointed at
  for c in $(tac "$GIT/logs/HEAD" 2>/dev/null | awk '{print $2}'); do
    if git -C "$VAULT" cat-file -e "$c^{commit}" 2>/dev/null && git -C "$VAULT" rev-list --objects -1 "$c" >/dev/null 2>&1 \
       && git -C "$VAULT" ls-tree -r "$c" >/dev/null 2>&1; then good="$c"; break; fi
  done
  if [ -z "$good" ]; then echo "No whole commit found — history can't be recovered (notes are fine). Backup kept."; exit 1; fi
  git -C "$VAULT" update-ref "$branch" "$good"
  echo "Pointed $branch at the last whole commit: $(git -C "$VAULT" log -1 --format='%h · %s' "$good")"
fi

if ! git -C "$VAULT" status --porcelain >/dev/null 2>&1; then
  rm -f "$GIT/index"
  git -C "$VAULT" reset -q && echo "Rebuilt the index (notes untouched)"
fi

if git -C "$VAULT" fsck --full --no-dangling 2>&1 | tee /dev/stderr | grep -qiE "^(error|missing|broken)"; then
  echo "git fsck still reports problems (listed above). Notes are fine; the backup is at $SNAP"
  exit 2
fi
echo "Vault history repaired: $(git -C "$VAULT" status --porcelain | wc -l) changed note(s) will be saved on the companion's next pass."
