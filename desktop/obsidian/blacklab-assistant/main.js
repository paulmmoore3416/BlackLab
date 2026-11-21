/* BlackLab Assistant — Paul's assistant inside Obsidian, with the same model dropdown as the phone and the desktop.
 *
 *  • A sidebar chat (ribbon 🤖 or "BlackLab: Open assistant"). Pick ★ Claude (Opus, Sonnet, Haiku, Fable — through
 *    Paul's Claude subscription on this computer), ★ Gemini, a local Ollama model, or Auto.
 *  • "Ask about this note", "Summarize this note", "Make flashcards from this note", "Ask about the selection" — the
 *    note travels as context; the saved conversation links back to it, so the graph shows what was discussed where.
 *  • Every exchange is saved by the BlackLab Desktop companion: Claude in 70 AI/Claude (glowing orange), Gemini in
 *    70 AI/Gemini (glowing red). Replies can be inserted into the note or copied.
 *
 * Talks only to the companion on this computer (http://127.0.0.1:8770, X-BlackLab header). Installed and kept current by
 * the companion from desktop/obsidian/blacklab-assistant in the BlackLab repo.
 */
"use strict";
let obsidian;
try { obsidian = require("obsidian"); } catch (_) {
  obsidian = { Plugin: class {}, ItemView: class {}, PluginSettingTab: class {}, Setting: class {}, Notice: class {}, MarkdownRenderer: {}, requestUrl: null, Platform: {} };
}
const { Plugin, ItemView, PluginSettingTab, Setting, Notice, MarkdownRenderer, requestUrl, Platform } = obsidian;

const VIEW = "blacklab-assistant";
const DEFAULTS = { url: "http://127.0.0.1:8770", model: "claude:sonnet", history: 6, sendNote: true };
const GROUPS = [["claude", "★ Claude"], ["gemini", "★ Gemini"], ["local", "Local · this computer"], ["auto", "Auto"]];

// ------------------------------------------------------------------ pure helpers (tests: assistant.test.mjs)

/** Catalog entries grouped for the dropdown: Claude, Gemini, local, auto — in that order, unavailable ones marked. */
function groupModels(models) {
  const out = [];
  for (const [p, label] of GROUPS) {
    const items = (models || []).filter((m) => m.provider === p);
    if (items.length) out.push({ provider: p, label, items: items.map((m) => ({ id: m.id, label: m.label + (m.available ? "" : " — unavailable"), available: !!m.available, note: m.note || "" })) });
  }
  return out;
}

/** The id to select: the saved one if it's still offered and available, else the first available Claude, else anything. */
function pickModel(models, saved) {
  const ok = (models || []).filter((m) => m.available);
  if (ok.some((m) => m.id === saved)) return saved;
  return (ok.find((m) => m.provider === "claude") || ok[0] || { id: "auto" }).id;
}

function providerOf(id) { const p = String(id || "auto").split(":")[0]; return ["claude", "gemini", "local"].includes(p) ? p : "auto"; }

/** [[question, answer], …] from the chat, newest last, at most n. */
function historyPairs(msgs, n) {
  const pairs = [];
  for (let i = 0; i + 1 < msgs.length; i++) if (msgs[i].role === "user" && msgs[i + 1].role === "ai" && !msgs[i + 1].error) pairs.push([msgs[i].text, msgs[i + 1].text]);
  return pairs.slice(-n);
}

/** A note's text without frontmatter and BlackLab's managed-block markers, trimmed for the model. */
function noteContext(text, max) {
  let t = String(text || "");
  if (t.startsWith("---\n")) { const e = t.indexOf("\n---", 4); if (e !== -1) t = t.slice(t.indexOf("\n", e + 4) + 1); }
  t = t.replace(/%% blacklab:(begin|end) %%/g, "").trim();
  return t.length > max ? t.slice(0, max) + "\n…(trimmed)" : t;
}

const PROMPTS = {
  ask: (q) => q,
  summarize: () => "Summarize this note: the key points, decisions and open questions, then any action items as a checklist.",
  flashcards: () => "Make 10–20 flashcards from this note, one per line, exactly as `front :: back` (no numbering, no extra text).",
  actions: () => "List the action items in this note as a Markdown checklist (`- [ ] …`), each with an owner and a date if the note says so.",
};

// ------------------------------------------------------------------ the sidebar

class AssistantView extends ItemView {
  constructor(leaf, plugin) { super(leaf); this.plugin = plugin; this.msgs = []; this.models = []; this.busy = false; }
  getViewType() { return VIEW; }
  getDisplayText() { return "BlackLab Assistant"; }
  getIcon() { return "bot"; }

  async onOpen() {
    const root = this.containerEl.children[1];
    root.empty(); root.addClass("bl-asst");
    const head = root.createDiv("bl-asst-head");
    head.createSpan({ cls: "bl-asst-title", text: "BlackLab" });
    this.select = head.createEl("select", { cls: "dropdown bl-asst-model" });
    this.select.onchange = async () => { this.plugin.settings.model = this.select.value; await this.plugin.save(); this.paintModel(); };
    const refresh = head.createEl("button", { cls: "clickable-icon", attr: { "aria-label": "Reload models" }, text: "⟳" });
    refresh.onclick = () => this.loadModels();
    this.log = root.createDiv("bl-asst-log");
    const tools = root.createDiv("bl-asst-tools");
    const chip = (label, kind) => { const b = tools.createEl("button", { cls: "bl-asst-chip", text: label }); b.onclick = () => this.plugin.aboutNote(kind); };
    chip("Summarize note", "summarize"); chip("Action items", "actions"); chip("Flashcards", "flashcards");
    const clear = tools.createEl("button", { cls: "bl-asst-chip", text: "Clear" }); clear.onclick = () => { this.msgs = []; this.render(); };
    const form = root.createDiv("bl-asst-form");
    this.input = form.createEl("textarea", { cls: "bl-asst-input", attr: { placeholder: "Ask anything…  (Enter to send · Shift+Enter for a new line)", rows: 3 } });
    this.withNote = form.createEl("label", { cls: "bl-asst-with" });
    this.noteBox = this.withNote.createEl("input", { type: "checkbox" }); this.noteBox.checked = this.plugin.settings.sendNote;
    this.withNote.appendText(" about the open note");
    this.noteBox.onchange = async () => { this.plugin.settings.sendNote = this.noteBox.checked; await this.plugin.save(); };
    const send = form.createEl("button", { cls: "mod-cta", text: "Send" });
    send.onclick = () => this.submit();
    this.input.addEventListener("keydown", (e) => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); this.submit(); } });
    await this.loadModels();
    this.render();
  }

  async loadModels() {
    try {
      const r = await this.plugin.api("GET", "/local/models");
      this.models = r.models || [];
    } catch (e) {
      this.models = [{ id: "auto", provider: "auto", label: "Auto", available: true }];
      new Notice("BlackLab Assistant: the BlackLab Desktop companion isn't answering on " + this.plugin.settings.url);
    }
    this.select.empty();
    for (const g of groupModels(this.models)) {
      const og = this.select.createEl("optgroup", { attr: { label: g.label } });
      og.addClass("bl-asst-group-" + g.provider);
      for (const m of g.items) { const o = og.createEl("option", { text: m.label, value: m.id }); if (!m.available) o.disabled = true; o.title = m.note; }
    }
    this.select.value = this.plugin.settings.model = pickModel(this.models, this.plugin.settings.model);
    this.paintModel();
  }

  paintModel() {
    const p = providerOf(this.select.value);
    this.containerEl.children[1].dataset.provider = p;
  }

  submit() {
    const q = this.input.value.trim();
    if (!q || this.busy) return;
    this.input.value = "";
    this.plugin.ask(q, this.noteBox.checked ? "ask" : null);
  }

  async send(text, shown, note, context) {
    this.msgs.push({ role: "user", text: shown || text, note });
    const pending = { role: "ai", text: "…", pending: true, model: this.select.value };
    this.msgs.push(pending); this.busy = true; this.render();
    try {
      const r = await this.plugin.api("POST", "/local/assistant", {
        text, model: this.select.value, origin: "Obsidian (" + (Platform && Platform.isMobile ? "phone" : "desktop") + ")",
        history: historyPairs(this.msgs.slice(0, -2), this.plugin.settings.history), note: note || "", context: context || "", target: "desktop",
      });
      Object.assign(pending, { text: r.text || "(no reply)", source: r.source || "", provider: r.provider || providerOf(this.select.value), error: !!r.error, pending: false });
    } catch (e) {
      Object.assign(pending, { text: "Couldn't reach BlackLab Desktop: " + (e.message || e), error: true, pending: false });
    }
    this.busy = false; this.render();
  }

  render() {
    this.log.empty();
    if (!this.msgs.length) {
      const w = this.log.createDiv("bl-asst-empty");
      w.createEl("p", { text: "Ask Claude, Gemini or your local AI. Every conversation is saved in your vault — Claude glows orange, Gemini red." });
      w.createEl("p", { text: "Tip: in any note, end a line with #ask/claude and the answer appears under it." });
    }
    for (const m of this.msgs) {
      const b = this.log.createDiv("bl-asst-msg bl-asst-" + m.role + (m.provider ? " bl-asst-p-" + m.provider : "") + (m.error ? " bl-asst-error" : ""));
      if (m.role === "user") { b.createDiv({ text: m.text }); if (m.note) b.createDiv({ cls: "bl-asst-about", text: "about " + m.note }); continue; }
      const body = b.createDiv("bl-asst-body");
      if (m.pending) { body.setText("Thinking…"); continue; }
      try { MarkdownRenderer.render(this.app, m.text, body, "", this); } catch (_) { body.setText(m.text); }
      const foot = b.createDiv("bl-asst-foot");
      foot.createSpan({ cls: "bl-asst-src", text: m.source || "" });
      if (!m.error) {
        const ins = foot.createEl("button", { cls: "bl-asst-mini", text: "Insert" }); ins.onclick = () => this.plugin.insert(m.text, m.source);
        const cp = foot.createEl("button", { cls: "bl-asst-mini", text: "Copy" }); cp.onclick = () => { navigator.clipboard.writeText(m.text); new Notice("Copied"); };
      }
    }
    this.log.scrollTop = this.log.scrollHeight;
  }
}

// ------------------------------------------------------------------ the plugin

class BlackLabAssistant extends Plugin {
  async onload() {
    this.settings = Object.assign({}, DEFAULTS, await this.loadData());
    this.registerView(VIEW, (leaf) => new AssistantView(leaf, this));
    this.addRibbonIcon("bot", "BlackLab Assistant", () => this.open());
    this.addCommand({ id: "open", name: "Open assistant", callback: () => this.open() });
    this.addCommand({ id: "ask-note", name: "Ask about this note", callback: () => this.promptAbout() });
    this.addCommand({ id: "summarize-note", name: "Summarize this note", callback: () => this.aboutNote("summarize") });
    this.addCommand({ id: "actions-note", name: "Action items from this note", callback: () => this.aboutNote("actions") });
    this.addCommand({ id: "flashcards-note", name: "Make flashcards from this note", callback: () => this.aboutNote("flashcards") });
    this.addCommand({ id: "ask-selection", name: "Ask about the selection", editorCallback: (ed) => {
      const sel = ed.getSelection();
      if (!sel) return new Notice("Select some text first.");
      this.open().then((v) => v && v.send("Help me with this:\n\n" + sel, "About the selection:\n" + sel.slice(0, 300)));
    } });
    this.addSettingTab(new AssistantSettings(this.app, this));
  }
  onunload() {}
  async save() { await this.saveData(this.settings); }

  async open() {
    let leaf = this.app.workspace.getLeavesOfType(VIEW)[0];
    if (!leaf) { leaf = this.app.workspace.getRightLeaf(false); await leaf.setViewState({ type: VIEW, active: true }); }
    this.app.workspace.revealLeaf(leaf);
    return leaf.view;
  }

  async api(method, path, body) {
    const url = this.settings.url.replace(/\/+$/, "") + path;
    const r = await requestUrl({ url, method, headers: { "X-BlackLab": "1", "Content-Type": "application/json" }, body: body ? JSON.stringify(body) : undefined, throw: false });
    if (r.status >= 400) throw new Error((r.json && r.json.error) || "HTTP " + r.status);
    return r.json;
  }

  async noteParts() {
    const f = this.app.workspace.getActiveFile();
    if (!f || f.extension !== "md") return null;
    return { name: f.basename, text: noteContext(await this.app.vault.cachedRead(f), 30000) };
  }

  async ask(question, about) {
    const v = await this.open();
    const n = about ? await this.noteParts() : null;
    v.send(PROMPTS.ask(question), question, n && n.name, n && n.text);
  }

  async aboutNote(kind) {
    const n = await this.noteParts();
    if (!n) return new Notice("Open a note first.");
    const v = await this.open();
    v.send(PROMPTS[kind](), PROMPTS[kind](), n.name, n.text);
  }

  promptAbout() {
    this.open().then((view) => { view.noteBox.checked = true; view.input.focus(); });
  }

  insert(text, source) {
    const ed = this.app.workspace.activeEditor && this.app.workspace.activeEditor.editor;
    if (!ed) return new Notice("Open a note to insert into.");
    const kind = /claude/i.test(source || "") ? "claude" : /gemini/i.test(source || "") ? "gemini" : "ai";
    ed.replaceSelection(`\n> [!${kind}] ${source || "BlackLab"}\n` + text.split("\n").map((l) => "> " + l).join("\n") + "\n");
  }
}

class AssistantSettings extends PluginSettingTab {
  constructor(app, plugin) { super(app, plugin); this.plugin = plugin; }
  display() {
    const { containerEl } = this, p = this.plugin;
    containerEl.empty();
    new Setting(containerEl).setName("BlackLab Desktop address").setDesc("The companion on this computer.")
      .addText((t) => t.setValue(p.settings.url).onChange(async (v) => { p.settings.url = v.trim() || DEFAULTS.url; await p.save(); }));
    new Setting(containerEl).setName("Earlier turns to send").setDesc("How much of the conversation the model sees (0–12).")
      .addSlider((s) => s.setLimits(0, 12, 1).setValue(p.settings.history).setDynamicTooltip().onChange(async (v) => { p.settings.history = v; await p.save(); }));
    new Setting(containerEl).setName("Send the open note by default")
      .addToggle((t) => t.setValue(p.settings.sendNote).onChange(async (v) => { p.settings.sendNote = v; await p.save(); }));
  }
}

module.exports = BlackLabAssistant;
module.exports.__test = { groupModels, pickModel, providerOf, historyPairs, noteContext, PROMPTS, DEFAULTS };
