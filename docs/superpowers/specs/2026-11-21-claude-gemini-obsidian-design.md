# Claude + Gemini in BlackLab, and Obsidian as the second main app — design

_2026-11-21 · BlackLab 5.17.0 · companion 1.11.0_

## Intent

Paul uses Claude and Gemini constantly. He wants to pick the model from a dropdown inside his own assistant
(phone, desktop, and now Obsidian), with Claude and Gemini featured. Every conversation lands in the Obsidian vault, so
the local AI (qwen3 via Ollama + kb-core recall) keeps learning from it. On the graph, Claude notes glow orange and Gemini
notes glow red, so Paul and the local AI can find them at a glance. Obsidian and BlackLab become his two main apps,
working together in sync (eight integrations, below).

## Goals

- One model dropdown, the same on the phone, the desktop web app and the Obsidian sidebar:
  ★ Claude (Opus, Sonnet, Haiku, Fable) · ★ Gemini (from Google's model list) · local Ollama models · Gemini Nano (phone) · Auto.
- Claude runs through Paul's **Claude subscription**: the desktop companion runs the `claude` CLI headless
  (`claude -p`). No Anthropic API key. Phone requests reach it over the existing USB/Tailscale link.
- Every Claude and Gemini exchange is saved to the vault: `70 AI/Claude/Conversations/YYYY-MM/YYYY-MM-DD.md` and
  `70 AI/Gemini/…`, with `ai_provider`, `ai_models`, `#ai/claude` / `#ai/gemini`, `up: [[🟠 Claude]]` / `[[🔴 Gemini]]`.
  Claude Code sessions are saved the same way (session hooks).
- The graph: Claude notes orange (#FF8C1A), Gemini notes red (#FF2D3D), placed ahead of every other color group, plus a
  pulsing glow drawn by the BlackLab Graph FX plugin; matching styling for note titles, explorer and tags.
- The local AI finds them fast: provider hub notes with an index, a `recall_ai` tool for the Ollama assistant, and the
  notes are in kb-core recall like the rest of the vault.

## Non-goals and anti-goals

- No way around Anthropic's or Google's usage policies; the models run as they are.
- No Anthropic API key, and no per-token bill (Paul chose option B).
- Cloud models never see the vault by default (the existing rule: vault answers come from the local model). Opt in with
  config `claude_vault_tools: true`, which allows Claude only the read-only vault tools.
- Nothing from the Behavioral Health workspace, nothing "off the record", nothing clinical goes to a cloud model or
  the vault (existing guards: `privacy_check`, `vault_safe`).
- Failure: a chat that silently doesn't save, or a vault edit that clobbers Paul's own text.

## Approaches considered (Claude access)

1. **Anthropic API key**: direct from the phone and desktop, every model, billed per token. Not chosen (Paul picked B).
2. **Claude subscription through the `claude` CLI on the desktop** *(chosen)*: no extra bill. Works when the phone can
   reach the computer (USB or Tailscale). About 2–3 s more per reply (process start). Shares limits with Claude Code.
3. Claude.ai web automation: brittle and against the terms. Rejected.

## Design

### Models (companion `ai_models.py`)

- `catalog()` returns `{id, provider, label, highlight, available, note}`. The ids are `claude:opus|sonnet|haiku|fable`,
  `gemini:<model>`, `local:<ollama model>` and `auto`.
- Claude: `claude -p --model <alias> --output-format json --no-session-persistence --tools "" --strict-mcp-config
  --setting-sources "" --system-prompt …`, with the prompt on stdin, run in a neutral working directory. At most 2 run
  at once, with a 180 s timeout. Each call records the model's real name (e.g. `claude-opus-5-5` → "Claude Opus 5.5"),
  so labels stay current when the aliases move.
- Gemini: the `generateContent` API with the desktop key (`secrets.json` → `gemini`). The model list comes from
  `GET /v1beta/models` (cached a day), with built-in defaults when it can't be reached. The phone calls Gemini directly
  with its own key.
- Earlier turns are sent as a short transcript (no CLI session state).

### Where the dropdown lives

- Phone: the Assistant screen header chip becomes the dropdown (setting `asst_model`, default `auto`). Commands
  (tasks, calendar, facility…) still run first. For everything else the chosen model answers with the
  COMMAND/ANSWER protocol, so Claude can also trigger phone commands. Claude and local models go through the desktop
  (`/phone/generate` with `model`); Gemini goes straight from the phone. Without the computer, Claude entries are
  shown but marked "needs your computer".
- Desktop web app: a dropdown in the Assistant view (`/local/models`, `/local/assistant` with `model`).
- Obsidian: the new BlackLab Assistant sidebar (below).

### Saving (companion `vault_ai.py`)

- `record(provider, model, surface, request, reply)`: provider day notes (append-only, frontmatter refreshed). The
  index note (`🟠 Claude` / `🔴 Gemini`) has counts, the last 30 exchanges and links. A timeline line goes on the daily
  note. The text is guarded and redacted first (`vault_safe`); private turns are never recorded.
- Phone exchanges: `POST /phone/vault/chat`. While the computer is away the call waits in the phone's outbox.
- Desktop and Obsidian exchanges are recorded by the server.

### Glow

- `vault_colors.graph_groups()` puts Claude and Gemini first: `path:"70 AI/Claude" OR ["ai_provider":claude] OR tag:#ai/claude`.
- CSS: `.bl-ai-claude` / `.bl-ai-gemini` give a glowing title, explorer entries and tag pills.
- Graph FX 1.1: "AI beacons", a pulsing radial glow under every Claude/Gemini node (static when reduce-motion is on),
  with colors and on/off in its settings.

## The eight integrations (build order)

1. **Assistant inside Obsidian** (`desktop/obsidian/blacklab-assistant`): sidebar chat with the same dropdown,
   "Ask about this note", "Summarize this note", "Send selection to BlackLab". It talks to `/local/assistant` (desktop
   Obsidian), and every exchange is saved by the server.
2. **Trigger notes** (`vault_triggers.py`, every 60 s): `#task`, `#remind`, `#workorder`, `#event`, `#action` lines
   anywhere, or any note in `00 Inbox`, become phone commands. `#ask/claude`, `#ask/gemini` and `#ask/local` notes are
   answered in place. A handled line's tag becomes `#…/sent` (or `#ask/answered`), so it never runs twice.
3. **Two-way tasks**: `30 Work/Tasks.md` lines carry `^t<id>`. Before each re-render the companion harvests checkbox
   changes and new lines, and queues ops for the phone (`task.complete`, `task.reopen`, `task.add`), which the phone
   applies on its next check-in (`/phone/jobs` → `ops`, acked). A work-order note's `status:` edit does the same.
4. **Facility command center**: `10 Facility/🏢 Command Center.md` + `Facility.base` (Bases views: open work orders,
   PMs due, inspections, assets). Status edits flow back through (3).
5. **Daily dashboard**: the daily note gains the morning brief link, tasks due, open work orders, PMs due, today's
   Claude/Gemini conversations and a quick "Ask" line.
6. **Meeting pipeline**: minutes are mirrored into `60 Meetings`, with their action items as `#action` checkboxes
   (synced as tasks). Action items written in any meeting note become tasks through (2).
7. **Study hub**: `40 Learning/Flashcards/<Deck>.md` from the phone's decks (`front :: back`). New `::` lines under
   `#flashcards` become cards on the phone (`card.add`), plus courses, assignments and CEUs on one hub page.
8. **Project boards**: `02 Projects/🗂️ Project Board.md`, a Kanban board (pinned, verified obsidian-kanban plugin)
   built from project notes' `status:`. Moving a card updates the note's status. Active projects show on a new
   BlackLab Home card ("Projects") and in the desktop web app.

## Phone ⇄ desktop ops channel

`GET /phone/jobs` also returns `ops: [{id, op, …}]`. The phone applies each op (via TaskOps / the assistant's command
grammar / Education DAO) and acks with `POST /phone/ops/ack`. Ops persist in `~/.cache/blacklab-desktop/phone_ops.json`
until acked, and an op is never applied twice (the phone remembers acked ids).

## Left to the builder

Exact visuals of the dropdowns, glow radius/period, CSS details, the exact Bases column choices, and test layout.
