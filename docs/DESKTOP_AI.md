# Desktop AI and `./daddyshome`

BlackLab's phone assistant can think with the local model on your computer: Ollama, through the
BlackLab Desktop companion. While the phone is linked, the assistant, Summarize, Talk, Ask my
files, meeting actions and Brainstorm's smart tools use the desktop model. When it isn't
linked, they fall back to Gemini Nano on the phone and then the offline rules, so the assistant
always works.

## Start everything

```bash
./daddyshome            # from the repo, or from your home folder (~/daddyshome)
./daddyshome qwen2.5:14b    # switch the desktop assistant's model (and make it the default)
./daddyshome --status   # status board only
./daddyshome --open     # …then open the BlackLab Desktop window
./daddyshome --repair   # re-send the phone's key (after reinstalling the app)
./daddyshome --stop     # unload the model and unlink the phone
```

`./daddyshome`:

1. Starts Ollama if it isn't running (system service, or `ollama serve`).
2. Loads the model selected in BlackLab Desktop (`~/.config/blacklab-desktop/config.json` → `model`)
   and pins it in memory (`keep_alive: -1`), at the same context size the companion uses
   (`num_ctx`, default 8192). Ollama reloads a model whenever its context size changes, so the
   two must match.
3. Restarts the BlackLab Desktop companion so it runs the latest code.
4. Links every connected phone, over USB or Wireless debugging, both ways:
   - desktop → phone: `adb forward tcp:8765` (dashboard, settings, files)
   - phone → desktop: `adb reverse tcp:8770` (the phone reaches the companion at its own `127.0.0.1:8770`)
5. Pairs the phone the first time: it pushes a shared key to `BlackLab/.provision/keys.json`, which
   the app imports into its Keystore-encrypted store and deletes when it next opens or comes to the
   front.
6. Prints a status board and checks the whole path end to end.

## How it stays private

- The companion still listens on `127.0.0.1` only. The phone reaches it through the adb tunnel,
  so no network port is opened on the computer.
- `/phone/status`, `/phone/generate` and `/phone/ask` need the shared key (`Authorization: Bearer`),
  our Host header, and no browser `Origin`, so web pages can't use them.
- The app allows plain HTTP only to its own loopback address (`res/xml/network_security_config.xml`).
  Everything else stays HTTPS.
- The Behavioral Health workspace never uses this path.

## Wi-Fi

Pair the phone with Android's **Wireless debugging** (Developer options):
`adb pair <ip:pairing-port>`, then `adb connect <ip:port>`, then `./daddyshome`. `adb reverse` works
the same over Wi-Fi, so the phone keeps its desktop brain away from the cable.

## On the phone

- **Settings › Assistant & desktop › Desktop AI** shows the model, latency and a Test button,
  and has the switch to turn it on or off.
- Ask "desktop AI status", or send work to the computer: "on my computer open my downloads",
  "ask my desktop how much disk space is left".
- Source labels show who answered: "Desktop AI · qwen3:8b" or "Gemini Nano · on-device".
