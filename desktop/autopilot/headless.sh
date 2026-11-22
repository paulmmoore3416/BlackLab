#!/usr/bin/env bash
# desktop/autopilot/headless.sh — run this computer without a monitor, with both GPUs working for the AI, and reachable
# from anywhere on your tailnet (companion 1.13).
#
#   headless.sh plan              what each step would change (no sudo, changes nothing)
#   sudo headless.sh setup        1. boot to the console instead of the desktop (frees the RX 580's VRAM and 1–2 GB RAM)
#                                 2. Tailscale SSH on (log in from the phone or a laptop over the tailnet; no passwords,
#                                    no open ports, nothing on the internet)
#                                 3. Ollama: quantized context cache (q8_0 — half the VRAM per token of context),
#                                    3 models loaded at once, and only this computer may call it (127.0.0.1; it was
#                                    open to the whole LAN without a password)
#                                 4. NVIDIA persistence mode (the 1060 stays initialised with no display attached)
#                                 then asks to reboot
#   sudo headless.sh tune         after the headless reboot: benchmarks the chat model split across both GPUs against
#                                 one-model-per-GPU, keeps the faster, and prints the numbers
#   sudo headless.sh revert       back to the desktop at boot and Ollama as it was (Tailscale SSH stays; turn it off
#                                 with: sudo tailscale set --ssh=false)
#   headless.sh info              how to reach and check everything remotely
#
# Nothing here touches BlackLab's data. Every change is a drop-in file or a single setting, listed by `plan`.
set -euo pipefail
USER_NAME="${SUDO_USER:-$(id -un)}"
DROP=/etc/systemd/system/ollama.service.d
CONF="$DROP/90-blacklab-headless.conf"
TUNE="$DROP/91-blacklab-tune.conf"
STATE_DIR="$(getent passwd "$USER_NAME" | cut -d: -f6)/.local/state/blacklab-desktop"
MODEL="${BLACKLAB_MODEL:-qwen3:8b}"
say() { printf '  %s\n' "$*"; }
need_root() { [ "$(id -u)" = 0 ] || { echo "  Run with sudo:  sudo $0 $1"; exit 1; }; }

plan() {
  say "Boot target now: $(systemctl get-default)  →  multi-user.target (no desktop; SSH/Tailscale/BlackLab all still start)"
  say "Lingering for $USER_NAME: $(loginctl show-user "$USER_NAME" -p Linger --value 2>/dev/null || echo '?') (must be yes so BlackLab starts without a login)"
  say "Tailscale SSH: $(tailscale debug prefs 2>/dev/null | grep -o '"RunSSH": [a-z]*' || echo 'unknown')  →  on"
  say "Ollama drop-in $CONF:"
  say "    OLLAMA_HOST=127.0.0.1:11434   OLLAMA_KV_CACHE_TYPE=q8_0   OLLAMA_MAX_LOADED_MODELS=3   OLLAMA_FLASH_ATTENTION=1"
  say "NVIDIA persistence: nvidia-persistenced enabled"
  say "Then: sudo reboot  →  unplug the monitor  →  sudo $0 tune"
}

setup() {
  need_root setup
  systemctl set-default multi-user.target
  loginctl enable-linger "$USER_NAME"
  if command -v tailscale >/dev/null; then tailscale set --ssh && say "✓ Tailscale SSH on"; fi
  mkdir -p "$DROP"
  cat > "$CONF" <<EOF
# BlackLab headless (desktop/autopilot/headless.sh) — remove this file and restart ollama to undo
[Service]
Environment="OLLAMA_HOST=127.0.0.1:11434"
Environment="OLLAMA_KV_CACHE_TYPE=q8_0"
Environment="OLLAMA_FLASH_ATTENTION=1"
Environment="OLLAMA_MAX_LOADED_MODELS=3"
EOF
  systemctl daemon-reload
  systemctl restart ollama
  if systemctl list-unit-files nvidia-persistenced.service >/dev/null 2>&1; then systemctl enable --now nvidia-persistenced || true; fi
  say "✓ Boots to the console from now on (the desktop is one command away: sudo systemctl isolate graphical.target)"
  say "✓ Ollama: local-only, q8_0 context cache, 3 models at once"
  say "Next: sudo reboot, unplug the monitor, then (over SSH or here)  sudo $0 tune"
}

bench() {  # tokens/second for $MODEL after a warm-up (prints a number)
  local url=http://127.0.0.1:11434
  for _ in $(seq 1 60); do curl -sf "$url/api/version" >/dev/null && break; sleep 1; done
  curl -s "$url/api/generate" -d "{\"model\":\"$MODEL\",\"prompt\":\"hi\",\"stream\":false,\"think\":false,\"options\":{\"num_predict\":4,\"num_ctx\":8192}}" >/dev/null
  curl -s "$url/api/generate" -d "{\"model\":\"$MODEL\",\"prompt\":\"List eight preventive maintenance tasks for a hospital boiler room, one line each.\",\"stream\":false,\"think\":false,\"options\":{\"num_predict\":220,\"num_ctx\":8192}}" \
    | python3 -c "import json,sys;r=json.load(sys.stdin);print(round(r['eval_count']/r['eval_duration']*1e9,1))"
}

placement() {
  curl -s http://127.0.0.1:11434/api/ps | python3 -c "import json,sys;[print(f\"    {m['name']}: {m['size_vram']/2**30:.1f} GB on GPU\") for m in json.load(sys.stdin).get('models',[])]"
  nvidia-smi --query-gpu=name,memory.used --format=csv,noheader 2>/dev/null | sed 's/^/    /'
  for c in /sys/class/drm/card*/device/mem_info_vram_used; do [ -f "$c" ] && echo "    AMD VRAM used: $(( $(cat "$c") / 1048576 )) MiB"; done
}

tune() {
  need_root tune
  local out="$STATE_DIR/gpu-tune.json"
  say "Benchmarking $MODEL (about 2 minutes)…"
  printf '[Service]\nEnvironment="OLLAMA_SCHED_SPREAD=1"\n' > "$TUNE"; systemctl daemon-reload; systemctl restart ollama
  local spread; spread=$(bench); say "split across both GPUs:   $spread tokens/s"; placement
  printf '[Service]\nEnvironment="OLLAMA_SCHED_SPREAD=0"\n' > "$TUNE"; systemctl daemon-reload; systemctl restart ollama
  local single; single=$(bench); say "one GPU per model:        $single tokens/s"; placement
  local pick=0
  python3 -c "import sys;sys.exit(0 if float('$spread') > float('$single') * 1.05 else 1)" && pick=1
  printf '# BlackLab GPU tune %s: spread=%s tok/s, single=%s tok/s\n[Service]\nEnvironment="OLLAMA_SCHED_SPREAD=%s"\n' "$(date -Is)" "$spread" "$single" "$pick" > "$TUNE"
  systemctl daemon-reload; systemctl restart ollama
  mkdir -p "$STATE_DIR"
  printf '{"at": "%s", "model": "%s", "spread_tps": %s, "single_tps": %s, "chosen": "%s"}\n' "$(date -Is)" "$MODEL" "$spread" "$single" \
    "$([ $pick = 1 ] && echo spread || echo single)" > "$out"; chown "$USER_NAME": "$out"
  say "✓ Kept: $([ $pick = 1 ] && echo 'split across both GPUs' || echo 'one GPU per model — the chat model and the embedding/vision models run side by side')"
  say "  (BlackLab re-loads $MODEL by itself within a minute.)"
}

revert() {
  need_root revert
  systemctl set-default graphical.target
  rm -f "$CONF" "$TUNE"; systemctl daemon-reload; systemctl restart ollama
  say "✓ The desktop starts at the next boot; Ollama is back to its previous settings."
}

info() {
  local ip dns; ip=$(tailscale ip -4 2>/dev/null | head -1); dns=$(tailscale status --json 2>/dev/null | python3 -c "import json,sys;print(json.load(sys.stdin)['Self']['DNSName'].rstrip('.'))" 2>/dev/null || true)
  cat <<EOF
  This computer on your tailnet: ${dns:-?}  (${ip:-?})
  • Phone assistant:  "on my computer, status report"   (also: "computer: is everything ok?")
                       it answers even if the AI model is down; alerts arrive by themselves when something breaks
  • Phone Obsidian:    05 Dashboards/🖥️ Home Lab (synced copy; refreshed every few minutes)
  • SSH (Tailscale):   ssh $USER_NAME@${dns:-<name>}     (JuiceSSH/Termius on the phone, with the Tailscale app on)
  • Desktop web UI:    ssh -L 8770:127.0.0.1:8770 $USER_NAME@${dns:-<name>}   then open http://127.0.0.1:8770 on that device
  • Quick checks over SSH:
        systemctl --user status blacklab-desktop     journalctl --user -u blacklab-desktop -n 50
        curl -s -H 'X-BlackLab: 1' 127.0.0.1:8770/local/dashboard/live | head -c 300
        python3 ~/Desktop/BlackLab/desktop/autopilot/blacklab_health.py --print
EOF
}

case "${1:-plan}" in
  plan) plan ;; setup) setup ;; tune) tune ;; revert) revert ;; info) info ;;
  *) sed -n '2,22p' "$0"; exit 2 ;;
esac
