// Form fields generated from the /api/catalog argument schema
// ({name, type, required, default, enum, min, max, unit}), and the task
// form built from them. Every field returns {value} or {error}; nothing is
// sent until every field validates.

import { h, field, nextId, select, btn, table } from "./dom.js";
import { t } from "./i18n.js";
import { store, botList, botsWithPos, loadWorld, serverOf } from "./store.js";
import { posText, dimLabel, argsSummary } from "./format.js";
import { posInputs, botPosButton, knownContainerSelect, refPicker } from "./refpick.js";

export { botPosButton };

export const DIMS = ["minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"];

// Farm animals offered for "animal" fields (any entity id is accepted).
const ANIMALS = ["minecraft:cow", "minecraft:sheep", "minecraft:pig", "minecraft:chicken", "minecraft:goat", "minecraft:rabbit", "minecraft:mooshroom", "minecraft:horse", "minecraft:llama", "minecraft:turtle", "minecraft:bee"];

// Argument types that take position references (SPEC §5.7e).
const REF_TYPES = new Set(["pos", "box", "container", "containers"]);

// Container roles a delivery may target besides sorted:<category> (SettingsSchema.ORDER_ROLES).
export const ORDER_ROLES = ["storage", "supply", "kit", "fuel", "inbox"];

/** Options for "into" fields: roles, then sorting categories from the autopilot settings. */
export function intoOptions() {
  const cats = (store.settings?.config?.autopilot?.categories || []).map((c) => c.name).filter(Boolean);
  return [
    ...ORDER_ROLES.map((r) => ({ value: r, label: t(`containerRole.${r}`, null, r) })),
    ...cats.map((c) => ({ value: `sorted:${c}`, label: `${t("containerRole.sorted")}: ${c}` })),
  ];
}

// Used when the catalog lists manager steps by name only (SPEC §5.5).
const STEP_ARGS = {
  kit: [{ name: "kitId", type: "kit", required: true }],
  deposit_storage: [],
  home: [],
  wait: [{ name: "sec", type: "int", required: true, min: 1, unit: "s", default: 10 }],
  goto_waypoint: [{ name: "name", type: "waypoint", required: true }],
  sort_storage: [],
  smelt_all: [],
};

/** Catalog task types and manager steps as one list of {type, step, continuous, heavy, args}. */
export function catalogEntries(catalog) {
  const tasks = (catalog?.tasks || []).map((x) => ({
    type: x.type,
    step: false,
    continuous: !!x.continuous,
    heavy: !!x.heavy,
    supported: x.supported !== false,
    args: Array.isArray(x.args) ? x.args : [],
  }));
  // "internal" steps (auto-supply) are inserted by the manager, never picked by hand
  const steps = (catalog?.managerSteps || []).filter((s) => !(typeof s === "object" && s.internal)).map((s) => {
    const name = typeof s === "string" ? s : s.name || s.type || s.step;
    const args = typeof s === "object" && Array.isArray(s.args) ? s.args : STEP_ARGS[name] || [];
    const supported = !(typeof s === "object" && s.supported === false);
    return { type: name, step: true, continuous: false, heavy: false, supported, args };
  });
  return { tasks, steps, all: [...tasks, ...steps] };
}

/** Title of a task template or manager step; bot task and step names never collide (SPEC §3, §5.5). */
export function templateTitle(tpl) {
  if (!tpl || !tpl.type) return "";
  return t(`task.${tpl.type}.title`, null, t(`step.${tpl.type}.title`, null, tpl.type));
}

// ------------------------------------------------------------------
// Shared datalists (item ids, player names)
// ------------------------------------------------------------------
export function refreshDatalists() {
  let items = document.getElementById("dl-items");
  if (!items) {
    items = h("datalist", { id: "dl-items" });
    document.body.append(items);
  }
  const ids = new Set();
  for (const b of store.bots.values()) for (const id of Object.keys(b.status?.items || {})) ids.add(id);
  for (const w of store.worlds.values()) {
    for (const c of w.containers) for (const it of c.snapshot?.items || []) ids.add(it.item);
  }
  items.replaceChildren(...[...ids].sort().map((id) => h("option", { value: id })));

  let players = document.getElementById("dl-players");
  if (!players) {
    players = h("datalist", { id: "dl-players" });
    document.body.append(players);
  }
  const names = new Set();
  for (const b of store.bots.values()) if (b.username) names.add(b.username);
  const owner = store.settings?.config?.general?.ownerPlayer;
  if (owner) names.add(owner);
  players.replaceChildren(...[...names].sort().map((n) => h("option", { value: n })));

  if (!document.getElementById("dl-animals")) {
    document.body.append(h("datalist", { id: "dl-animals" }, ANIMALS.map((a) => h("option", { value: a }))));
  }
}

// ------------------------------------------------------------------
// Field factory
// ------------------------------------------------------------------

/**
 * ctx: {labelKey(name) -> i18n key, refBot() -> bot|null, serverId() -> id|null}
 * Returns {name, el, get() -> {value}|{error}, set(value)}.
 */
export function argField(arg, ctx) {
  const type = arg.type || "string";
  const label = t(ctx.labelKey(arg.name), null, arg.name);
  let make = RENDERERS[type] || (arg.name === "dim" ? RENDERERS.dim : RENDERERS.json);
  if (arg.refs && REF_TYPES.has(type)) make = refPicker;
  else if (arg.name === "dim" && type === "enum") make = dimRenderer;
  else if (arg.name === "animal" && (type === "string" || type === "item")) make = textRenderer({ list: "dl-animals" });
  else if (arg.name === "into" && type === "string") make = intoRenderer;
  const f = make(arg, ctx);
  // A composite control has no <label for>; its first input carries the name.
  if (f.control && !/^(INPUT|SELECT|TEXTAREA)$/.test(f.control.tagName)) {
    const inner = f.control.querySelector("select,input:not([type=checkbox]),textarea");
    if (inner && !inner.getAttribute("aria-label")) inner.setAttribute("aria-label", label);
  }
  const err = h("span", { class: "field-error", "aria-live": "polite" });
  const hintParts = [];
  if (arg.hint) hintParts.push(arg.hint);
  if (arg.required) hintParts.push(t("form.required"));
  if (arg.unit) hintParts.push(t("form.unit", { unit: t(`unit.${arg.unit}`, null, arg.unit) }));
  if (arg.min !== undefined || arg.max !== undefined) {
    hintParts.push(t("form.range", { min: arg.min ?? "", max: arg.max ?? "" }));
  }
  if (arg.default !== undefined && arg.default !== null && type !== "bool") {
    hintParts.push(t("form.default", { value: typeof arg.default === "object" ? JSON.stringify(arg.default) : arg.default }));
  }
  if (f.hint && !arg.hint) hintParts.push(f.hint);
  const wrapper = field(label, f.control, { hint: hintParts.join(" · "), wide: f.wide });
  wrapper.append(err);
  if (arg.default !== undefined && arg.default !== null) f.set(arg.default);
  return {
    name: arg.name,
    el: wrapper,
    get() {
      const r = f.get();
      const bad = r.error ? r.error : r.value === undefined && arg.required ? t("form.err.required") : null;
      err.textContent = bad || "";
      for (const inp of wrapper.querySelectorAll("input,select,textarea")) {
        if (bad) inp.setAttribute("aria-invalid", "true");
        else inp.removeAttribute("aria-invalid");
      }
      return bad ? { error: bad } : { value: r.value };
    },
    set: (v) => f.set(v),
  };
}

function numberRenderer(isInt) {
  return (arg) => {
    const input = h("input", {
      type: "number",
      step: isInt ? "1" : "any",
      min: arg.min,
      max: arg.max,
      inputmode: isInt ? "numeric" : "decimal",
    });
    return {
      control: input,
      get() {
        const raw = input.value.trim();
        if (raw === "") return { value: undefined };
        const n = Number(raw);
        if (!Number.isFinite(n)) return { error: t("form.err.number") };
        if (isInt && !Number.isInteger(n)) return { error: t("form.err.int") };
        if (arg.min !== undefined && n < arg.min) return { error: t("form.err.min", { min: arg.min }) };
        if (arg.max !== undefined && n > arg.max) return { error: t("form.err.max", { max: arg.max }) };
        return { value: n };
      },
      set(v) {
        input.value = v === undefined || v === null ? "" : String(v);
      },
    };
  };
}

function textRenderer({ list, area } = {}) {
  return () => {
    const input = area ? h("textarea", { rows: 3 }) : h("input", { type: "text", list, autocomplete: "off", spellcheck: "false" });
    return {
      control: input,
      wide: area,
      get() {
        const v = input.value.trim();
        return { value: v === "" ? undefined : v };
      },
      set(v) {
        input.value = v === undefined || v === null ? "" : String(v);
      },
    };
  };
}

function listRenderer() {
  return () => {
    const input = h("input", { type: "text", list: "dl-items", autocomplete: "off", spellcheck: "false" });
    return {
      control: input,
      hint: t("form.hint.list"),
      get() {
        const parts = input.value.split(/[\s,]+/).map((s) => s.trim()).filter(Boolean);
        return { value: parts.length ? parts : undefined };
      },
      set(v) {
        input.value = Array.isArray(v) ? v.join(" ") : v ? String(v) : "";
      },
    };
  };
}

function intoRenderer(arg) {
  const opts = intoOptions();
  const sel = select(opts, arg.default || "storage");
  return {
    control: sel,
    get: () => ({ value: sel.value || undefined }),
    set(v) {
      if (v && ![...sel.options].some((o) => o.value === v)) sel.append(h("option", { value: v }, v));
      sel.value = v || "storage";
    },
  };
}

function posRenderer(arg, ctx) {
  const p = posInputs();
  return {
    control: h("div", { class: "stack-sm" }, p.wrap, botPosButton(ctx, p.set)),
    get: p.get,
    set: p.set,
  };
}

function boxRenderer(arg, ctx) {
  const a = posInputs();
  const b = posInputs();
  return {
    control: h(
      "div",
      { class: "stack-sm" },
      h("span", { class: "field-hint" }, t("form.cornerA")),
      a.wrap,
      botPosButton(ctx, a.set, "form.useBotPosA"),
      h("span", { class: "field-hint" }, t("form.cornerB")),
      b.wrap,
      botPosButton(ctx, b.set, "form.useBotPosB"),
    ),
    wide: true,
    get() {
      const ra = a.get();
      const rb = b.get();
      if (ra.error) return ra;
      if (rb.error) return rb;
      if (ra.value === undefined && rb.value === undefined) return { value: undefined };
      if (ra.value === undefined || rb.value === undefined) return { error: t("form.err.boxPartial") };
      return { value: { a: ra.value, b: rb.value } };
    },
    set(v) {
      a.set(v?.a);
      b.set(v?.b);
    },
  };
}

function boolRenderer() {
  const input = h("input", { type: "checkbox" });
  return {
    control: h("label", { class: "check" }, input, h("span", null, t("form.enabled"))),
    get: () => ({ value: input.checked }),
    set(v) {
      input.checked = v === true || v === "true";
    },
  };
}

function enumRenderer(arg, ctx) {
  const values = Array.isArray(arg.enum) ? arg.enum : [];
  const opts = [];
  if (!arg.required) opts.push({ value: "", label: t("form.notSet") });
  // The catalog labels options as "<argument label key>.option.<value>".
  const base = ctx && ctx.labelKey ? ctx.labelKey(arg.name) : "";
  const labelOf = arg.labelOf || ((v) => t(`${base}.option.${v}`, null, t(`enum.${v}`, null, String(v))));
  for (const v of values) opts.push({ value: v, label: labelOf(v) });
  const sel = select(opts, arg.required && values.length ? values[0] : "");
  return {
    control: sel,
    get() {
      if (sel.value === "") return { value: undefined };
      const raw = values.find((v) => String(v) === sel.value);
      return { value: raw === undefined ? sel.value : raw };
    },
    set(v) {
      sel.value = v === undefined || v === null ? "" : String(v);
    },
  };
}

function dimRenderer(arg) {
  return enumRenderer({ ...arg, enum: DIMS, labelOf: dimLabel });
}

/** "minecraft:oak_log 64" / "*_ingot all" per line. */
export function parseItemCounts(text) {
  const out = [];
  const lines = text.split(/\n|;/).map((l) => l.trim()).filter(Boolean);
  for (const line of lines) {
    const m = line.match(/^(\S+)\s+(-?\d+|all|все)$/i);
    if (!m) return { error: t("form.err.itemCountLine", { line }) };
    const count = /^(all|все)$/i.test(m[2]) ? -1 : Number(m[2]);
    if (count === 0 || count < -1) return { error: t("form.err.itemCountLine", { line }) };
    out.push({ item: m[1], count });
  }
  return { value: out };
}

function itemCountsRenderer(arg) {
  const area = h("textarea", { rows: 3, spellcheck: "false", placeholder: "minecraft:oak_log 64\n*_ingot all" });
  return {
    control: area,
    wide: true,
    hint: t("form.hint.itemCounts"),
    get() {
      const r = parseItemCounts(area.value);
      if (r.error) return r;
      if (!r.value.length) return { value: undefined };
      if (arg.map) return { value: Object.fromEntries(r.value.map((x) => [x.item, x.count])) };
      return { value: r.value };
    },
    set(v) {
      let list = [];
      if (Array.isArray(v)) list = v;
      else if (v && typeof v === "object") list = Object.entries(v).map(([item, count]) => ({ item, count }));
      area.value = list.map((x) => `${x.item} ${x.count === -1 ? "all" : x.count}`).join("\n");
    },
  };
}

function containerRenderer(arg, ctx) {
  const p = posInputs();
  return {
    control: h("div", { class: "stack-sm" }, p.wrap, knownContainerSelect(ctx, p.set), botPosButton(ctx, p.set)),
    get: p.get,
    set: p.set,
  };
}

export function parsePositions(text) {
  const out = [];
  for (const line of text.split(/\n|;/).map((l) => l.trim()).filter(Boolean)) {
    const n = line.split(/[\s,]+/).map(Number);
    if (n.length !== 3 || n.some((v) => !Number.isInteger(v))) return { error: t("form.err.posLine", { line }) };
    out.push({ x: n[0], y: n[1], z: n[2] });
  }
  return { value: out };
}

function containersRenderer(arg, ctx) {
  const area = h("textarea", { rows: 3, spellcheck: "false", placeholder: "100 64 -20\n102 64 -20" });
  const add = (pos) => {
    const line = posText(pos);
    area.value = area.value.trim() ? `${area.value.trim()}\n${line}` : line;
  };
  return {
    control: h("div", { class: "stack-sm" }, area, knownContainerSelect(ctx, add)),
    wide: true,
    hint: t("form.hint.positions"),
    get() {
      const r = parsePositions(area.value);
      if (r.error) return r;
      return { value: r.value.length ? r.value : undefined };
    },
    set(v) {
      area.value = Array.isArray(v) ? v.map(posText).join("\n") : "";
    },
  };
}

function kitRenderer() {
  const sel = h("select");
  const fill = (current) => {
    const kits = store.kits || [];
    sel.replaceChildren(
      h("option", { value: "" }, kits.length ? t("form.pickKit") : t("form.noKits")),
      ...kits.map((k) => h("option", { value: k.id }, k.name || k.id)),
    );
    if (current) sel.value = current;
  };
  fill();
  return {
    control: sel,
    get: () => ({ value: sel.value || undefined }),
    set(v) {
      if (v && ![...sel.options].some((o) => o.value === v)) sel.append(h("option", { value: v }, v));
      sel.value = v || "";
    },
  };
}

function waypointRenderer(arg, ctx) {
  const input = h("input", { type: "text", list: nextId("dl-wp"), autocomplete: "off" });
  const dl = h("datalist", { id: input.getAttribute("list") });
  const fill = () => {
    const sid = ctx.serverId && ctx.serverId();
    const w = sid ? store.worlds.get(sid) : null;
    dl.replaceChildren(...(w ? w.waypoints : []).map((p) => h("option", { value: p.name })));
  };
  fill();
  const sid = ctx.serverId && ctx.serverId();
  if (sid && !store.worlds.has(sid)) loadWorld(sid).then(fill, () => {});
  return {
    control: h("div", null, input, dl),
    get() {
      const v = input.value.trim();
      return { value: v || undefined };
    },
    set(v) {
      input.value = v || "";
    },
  };
}

function jsonRenderer() {
  const area = h("textarea", { rows: 3, spellcheck: "false" });
  return {
    control: area,
    wide: true,
    hint: t("form.hint.json"),
    get() {
      const raw = area.value.trim();
      if (!raw) return { value: undefined };
      try {
        return { value: JSON.parse(raw) };
      } catch (e) {
        return { error: t("form.err.json", { msg: e.message }) };
      }
    },
    set(v) {
      area.value = v === undefined || v === null ? "" : JSON.stringify(v, null, 2);
    },
  };
}

/** One entry per line; used for regex lists, commands and globs in settings. */
function linesRenderer() {
  return () => {
    const area = h("textarea", { rows: 4, spellcheck: "false" });
    return {
      control: area,
      wide: true,
      hint: t("form.hint.lines"),
      get() {
        const lines = area.value.split(/\r?\n/).map((l) => l.trim()).filter(Boolean);
        return { value: lines };
      },
      set(v) {
        area.value = Array.isArray(v) ? v.join("\n") : v ? String(v) : "";
      },
    };
  };
}

/** Write-only secret: an empty field means "keep the stored value". */
function passwordRenderer() {
  const input = h("input", { type: "password", autocomplete: "new-password", spellcheck: "false" });
  return {
    control: input,
    hint: t("form.hint.secret"),
    get() {
      const v = input.value;
      return { value: v === "" ? undefined : v };
    },
    set() {
      input.value = "";
    },
  };
}

const RENDERERS = {
  int: numberRenderer(true),
  integer: numberRenderer(true),
  long: numberRenderer(true),
  double: numberRenderer(false),
  float: numberRenderer(false),
  number: numberRenderer(false),
  boolean: boolRenderer,
  lines: linesRenderer(),
  list: linesRenderer(),
  string_list: linesRenderer(),
  patterns: linesRenderer(),
  globs: linesRenderer(),
  password: passwordRenderer,
  secret: passwordRenderer,
  map: jsonRenderer,
  object: jsonRenderer,
  array: jsonRenderer,
  string: textRenderer(),
  player: textRenderer({ list: "dl-players" }),
  text: textRenderer({ area: true }),
  item: textRenderer({ list: "dl-items" }),
  items: listRenderer(),
  ids: listRenderer(),
  bool: boolRenderer,
  enum: enumRenderer,
  dim: dimRenderer,
  pos: posRenderer,
  box: boxRenderer,
  item_counts: itemCountsRenderer,
  container: containerRenderer,
  containers: containersRenderer,
  kit: kitRenderer,
  waypoint: waypointRenderer,
  json: jsonRenderer,
};

/** Collects every field; returns {value: object} or {error} after marking each bad field. */
export function collect(fields) {
  const out = {};
  let failed = 0;
  for (const f of fields) {
    const r = f.get();
    if (r.error) failed++;
    else if (r.value !== undefined) out[f.name] = r.value;
  }
  return failed ? { error: t("form.err.fields", { n: failed }) } : { value: out };
}

// ------------------------------------------------------------------
// Reference bot selector ("use bot position" source)
// ------------------------------------------------------------------
export function refBotSelect(initialId) {
  const sel = h("select");
  const fill = () => {
    const cur = sel.value || initialId || "";
    const bots = botsWithPos();
    sel.replaceChildren(
      h("option", { value: "" }, bots.length ? t("form.pickRefBot") : t("form.noBotsWithPos")),
      ...bots.map((b) => h("option", { value: b.id }, `${b.username || b.id} · ${posText(b.status.pos)} ${dimLabel(b.status.dim)}`)),
    );
    sel.value = [...sel.options].some((o) => o.value === cur) ? cur : "";
  };
  fill();
  sel.addEventListener("focus", fill);
  return {
    el: field(t("form.refBot"), sel, { hint: t("form.refBotHint") }),
    bot: () => (sel.value ? store.bots.get(sel.value) : null),
    refresh: fill,
  };
}

// ------------------------------------------------------------------
// Task form
// ------------------------------------------------------------------

/**
 * opts: {catalog, bot (fixed reference bot) | null, withMode, submitLabel,
 *        primary, onSubmit(template, mode) -> Promise, initial (template)}
 */
export function taskForm(opts) {
  const { catalog } = opts;
  const entries = catalogEntries(catalog);
  const ref = opts.bot ? null : refBotSelect();
  const ctx = {
    labelKey: () => "",
    refBot: () => (opts.bot ? store.bots.get(opts.bot.id) || opts.bot : ref.bot()),
    serverId: () => serverOf(ctx.refBot()) || (store.servers[0] && store.servers[0].id) || null,
  };

  // Types the manager marks "supported": false stay visible but cannot be picked (phase 2).
  const option = (e) => {
    const title = t(`${e.step ? "step" : "task"}.${e.type}.title`, null, e.type);
    return h("option", { value: e.type, disabled: !e.supported }, e.supported ? title : `${title} (${t("form.phase2")})`);
  };
  const typeSel = h("select", { required: true });
  typeSel.append(h("optgroup", { label: t("form.botTasks") }, entries.tasks.map(option)));
  if (entries.steps.length) {
    typeSel.append(h("optgroup", { label: t("form.managerSteps") }, entries.steps.map(option)));
  }
  const firstSupported = entries.all.find((e) => e.supported);
  if (firstSupported) typeSel.value = firstSupported.type;
  const typeNote = h("p", { class: "field-hint", "aria-live": "polite" });
  const argsBox = h("div", { class: "fields" });
  let fields = [];

  const timeout = h("input", { type: "number", min: 0, step: 1, inputmode: "numeric", value: "0" });
  const labelIn = h("input", { type: "text", maxlength: 80 });

  const modeName = nextId("mode");
  const modes = ["append", "front", "replace"].map((m, i) => {
    const r = h("input", { type: "radio", name: modeName, value: m, checked: i === 0 });
    return { m, r, el: h("label", { class: "check" }, r, h("span", null, t(`mode.${m}`))) };
  });

  const status = h("p", { class: "field-hint", "aria-live": "polite" });

  const build = (initialArgs) => {
    const e = entries.all.find((x) => x.type === typeSel.value);
    argsBox.replaceChildren();
    fields = [];
    if (!e) return;
    const prefix = e.step ? `step.${e.type}.arg.` : `task.${e.type}.arg.`;
    ctx.labelKey = (name) => prefix + name;
    ctx.what = e.type;
    // goto takes either a pos (reference or x y z behind "manual") or loose x/y/z.
    const posRef = e.args.some((a) => a.name === "pos" && a.refs);
    const loose = posRef ? new Set(["x", "y", "z"]) : new Set();
    for (const a of e.args) {
      if (loose.has(a.name)) continue;
      // Without the loose x/z the pos is the only way to say where: it becomes required.
      const f = argField(posRef && a.name === "pos" ? { ...a, required: true } : a, ctx);
      if (initialArgs && initialArgs[a.name] !== undefined) f.set(initialArgs[a.name]);
      else if (a.name === "pos" && posRef && initialArgs && initialArgs.x !== undefined) f.set({ x: initialArgs.x, y: initialArgs.y ?? 64, z: initialArgs.z });
      fields.push(f);
      argsBox.append(f.el);
    }
    const notes = [];
    if (!e.supported) notes.push(t("form.unsupported"));
    const desc = t(`${e.step ? "step" : "task"}.${e.type}.desc`, null, "");
    if (desc) notes.push(desc);
    if (e.continuous) notes.push(t("form.continuous"));
    if (e.heavy) notes.push(t("form.heavy"));
    if (!e.args.length) notes.push(t("form.noArgs"));
    typeNote.textContent = notes.join(" ");
  };

  typeSel.addEventListener("change", () => build());

  if (opts.initial) {
    typeSel.value = opts.initial.type;
    timeout.value = String(opts.initial.timeoutSec || 0);
    labelIn.value = opts.initial.label || "";
  }
  build(opts.initial ? opts.initial.args : null);

  const submit = h(
    "button",
    { type: "submit", class: ["btn", opts.primary ? "btn-primary" : "btn-ghost"] },
    opts.submitLabel || t("form.addTask"),
  );

  const form = h(
    "form",
    { class: "stack", novalidate: true },
    ref ? h("div", { class: "fields" }, ref.el) : null,
    h("div", { class: "fields" }, field(t("form.type"), typeSel, { wide: true }), typeNote),
    argsBox,
    h(
      "div",
      { class: "fields" },
      field(t("form.timeout"), timeout, { hint: t("form.timeoutHint") }),
      field(t("form.label"), labelIn, { hint: t("form.labelHint") }),
    ),
    opts.withMode
      ? h("fieldset", null, h("legend", null, t("form.mode")), h("div", { class: "row" }, modes.map((x) => x.el)))
      : null,
    h("div", { class: "row" }, submit, status),
  );

  form.addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const r = collect(fields);
    const to = Number(timeout.value || 0);
    if (!Number.isInteger(to) || to < 0) {
      status.textContent = t("form.err.timeout");
      return;
    }
    if (r.error) {
      status.textContent = r.error;
      return;
    }
    const picked = entries.all.find((x) => x.type === typeSel.value);
    if (!picked || !picked.supported) {
      status.textContent = t("form.unsupported");
      return;
    }
    const tpl = { type: typeSel.value, args: r.value };
    if (to > 0) tpl.timeoutSec = to;
    if (labelIn.value.trim()) tpl.label = labelIn.value.trim();
    const mode = (modes.find((x) => x.r.checked) || modes[0]).m;
    submit.disabled = true;
    status.textContent = t("ui.sending");
    try {
      await opts.onSubmit(tpl, mode);
      status.textContent = t("ui.sent");
    } catch (e) {
      status.textContent = `${e.code || "error"}: ${e.message || ""}`;
    } finally {
      submit.disabled = false;
    }
  });

  return form;
}

/** Multi-select of bots as checkboxes; returns {el, get() -> [ids], set(ids)}. */
export function botPicker(selected = [], { onlyIds } = {}) {
  const boxes = [];
  const list = h("div", { class: "row" });
  for (const b of botList()) {
    if (onlyIds && !onlyIds.includes(b.id)) continue;
    const input = h("input", { type: "checkbox", value: b.id, checked: selected.includes(b.id) });
    boxes.push(input);
    list.append(h("label", { class: "check" }, input, h("span", null, b.username || b.id)));
  }
  if (!boxes.length) list.append(h("span", { class: "nodata" }, t("bots.none")));
  return {
    el: list,
    get: () => boxes.filter((b) => b.checked).map((b) => b.value),
    set(ids) {
      for (const b of boxes) b.checked = ids.includes(b.value);
    },
  };
}

/**
 * "Which bots" for schedules and rules: any free bot, every online bot, or
 * the listed ones. Returns {el, get() -> "any"|"all"|[ids] | {error}, set(v)}.
 */
export function botsChoice(initial = "any") {
  const name = nextId("bots");
  const radios = ["any", "all", "list"].map((v) => h("input", { type: "radio", name, value: v }));
  const picker = botPicker(Array.isArray(initial) ? initial : []);
  const sync = () => (picker.el.hidden = !radios[2].checked);
  for (const r of radios) r.addEventListener("change", sync);
  const set = (v) => {
    const mode = Array.isArray(v) ? "list" : v === "all" ? "all" : "any";
    for (const r of radios) r.checked = r.value === mode;
    if (Array.isArray(v)) picker.set(v);
    sync();
  };
  set(initial);
  return {
    el: h(
      "fieldset",
      null,
      h("legend", null, t("auto.bots")),
      h("div", { class: "row" }, radios.map((r) => h("label", { class: "check" }, r, h("span", null, t(`auto.bots.${r.value}`))))),
      picker.el,
    ),
    get() {
      if (radios[0].checked) return { value: "any" };
      if (radios[1].checked) return { value: "all" };
      const ids = picker.get();
      return ids.length ? { value: ids } : { error: t("bots.noSelection") };
    },
    set,
  };
}

/**
 * Ordered list of task templates and manager steps with an add / edit form,
 * shared by scenarios, schedules and rules. Returns
 * {listEl, formEl, steps() -> [template], count()}.
 */
export function stepsEditor({ catalog, steps = [], onChange }) {
  const list = steps.map((s) => structuredClone(s));
  let editing = -1;
  const listEl = h("div");
  const formEl = h("div");
  const changed = () => {
    if (onChange) onChange(list);
    renderList();
    renderForm();
  };

  function renderList() {
    if (!list.length) {
      listEl.replaceChildren(h("p", { class: "empty" }, t("scen.noSteps")));
      return;
    }
    const rows = list.map((st, i) =>
      h(
        "tr",
        { "aria-selected": String(i === editing) },
        h("td", { class: "num" }, String(i + 1)),
        h("td", null, h("span", { class: "strong" }, templateTitle(st)), st.label ? h("div", { class: "small dim" }, st.label) : null),
        h("td", { class: "wrap-cell mono" }, argsSummary(st.args) || t("bot.noArgs")),
        h("td", { class: "num" }, st.timeoutSec ? `${st.timeoutSec} ${t("unit.s")}` : t("scen.noTimeout")),
        h(
          "td",
          { class: "actions" },
          h(
            "div",
            { class: "row-sm" },
            btn(t("bot.up"), () => move(i, -1), { disabled: i === 0, ariaLabel: t("bot.upAria", { n: i + 1 }) }),
            btn(t("bot.down"), () => move(i, 1), { disabled: i === list.length - 1, ariaLabel: t("bot.downAria", { n: i + 1 }) }),
            btn(t("scen.editStep"), () => edit(i), { ariaLabel: t("scen.editStepAria", { n: i + 1 }) }),
            btn(t("bot.remove"), () => remove(i), { ariaLabel: t("bot.removeAria", { n: i + 1 }) }),
          ),
        ),
      ),
    );
    listEl.replaceChildren(table([t("bot.qcol.n"), t("scen.col.step"), t("bot.qcol.args"), t("scen.col.timeout"), t("bot.qcol.actions")], rows));
  }

  function move(i, d) {
    const j = i + d;
    if (j < 0 || j >= list.length) return;
    [list[i], list[j]] = [list[j], list[i]];
    if (editing === i) editing = j;
    changed();
  }

  function remove(i) {
    list.splice(i, 1);
    if (editing === i) editing = -1;
    else if (editing > i) editing--;
    changed();
  }

  function edit(i) {
    editing = i;
    renderList();
    renderForm();
    formEl.querySelector("select")?.focus();
  }

  function renderForm() {
    const isEdit = editing >= 0;
    const form = taskForm({
      catalog,
      withMode: false,
      primary: false,
      initial: isEdit ? list[editing] : null,
      submitLabel: isEdit ? t("scen.saveStep") : t("scen.addStep"),
      onSubmit: async (tpl) => {
        if (isEdit) list[editing] = tpl;
        else list.push(tpl);
        editing = -1;
        changed();
      },
    });
    const cancel = isEdit
      ? btn(t("ui.cancel"), () => {
          editing = -1;
          renderList();
          renderForm();
        })
      : null;
    formEl.replaceChildren(h("div", { class: "head" }, h("h3", { class: "h3" }, isEdit ? t("scen.editingStep", { n: editing + 1 }) : t("scen.addStepTitle")), cancel), form);
  }

  renderList();
  renderForm();
  return { listEl, formEl, steps: () => list, count: () => list.length };
}
