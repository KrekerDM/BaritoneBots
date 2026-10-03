// Settings: every field of GET /api/settings drawn from its schema, saved
// with PUT /api/settings as a JSON merge patch; servers and bots CRUD; the
// runtime (HeadlessMC, Fabric, mods) with live install progress from SSE.

import { api, enc } from "../api.js";
import { h, mount, btn, busy, toast, confirmDialog, errorBox, empty, table, field, select, throttle } from "../dom.js";
import { t, tid } from "../i18n.js";
import { store, loadSettings } from "../store.js";
import { num, noData, pct, bytes, timeEl, processEl } from "../format.js";

const ITEM_LISTS = new Set(["servers", "bots"]);

export function render(root, params, app) {
  const tabsHost = h("div");
  const host = h("div", { class: "stack-lg" });
  let live = null;

  mount(
    root,
    h("div", { class: "stack" }, h("h1", { class: "h1" }, t("set.title")), h("p", { class: "prose" }, t("set.text")), tabsHost, host),
  );
  mount(host, empty(t("ui.loading")));

  loadSettings(true).then(
    () => {
      const sections = (store.settings?.schema?.sections || []).map((s) => s.id);
      const tab = sections.includes(params.tab) ? params.tab : sections[0];
      mount(
        tabsHost,
        h(
          "nav",
          { class: "tabs", "aria-label": t("set.sections") },
          (store.settings.schema.sections || []).map((s) =>
            h("a", { href: `#/settings/${enc(s.id)}`, "aria-current": s.id === tab ? "page" : null }, t(s.label, null, s.id)),
          ),
        ),
      );
      if (tab === "servers") live = itemsView(host, SERVERS, params);
      else if (tab === "bots") live = itemsView(host, BOTS, params);
      else live = sectionView(host, tab);
    },
    (e) => mount(host, errorBox(e, "set.loadFailed")),
  );

  return {
    update(type, data) {
      if (live && live.update) live.update(type, data);
    },
  };
}

// ------------------------------------------------------------------
// Paths and merge patches
// ------------------------------------------------------------------

const isObj = (v) => v !== null && typeof v === "object" && !Array.isArray(v);

function getPath(obj, path) {
  let cur = obj;
  for (const part of path.split(".")) {
    if (!isObj(cur)) return undefined;
    cur = cur[part];
  }
  return cur;
}

function setPath(obj, path, value) {
  const parts = path.split(".");
  let cur = obj;
  for (const part of parts.slice(0, -1)) {
    if (!isObj(cur[part])) cur[part] = {};
    cur = cur[part];
  }
  cur[parts[parts.length - 1]] = value;
}

/**
 * RFC 7386 merge patch that turns `a` into `b`: keys missing from `b` become
 * null, changed values are replaced, objects recurse. Undefined = no change.
 */
function mergeDiff(a, b) {
  if (isObj(a) && isObj(b)) {
    const out = {};
    let changed = false;
    for (const k of new Set([...Object.keys(a), ...Object.keys(b)])) {
      let d;
      if (!(k in b) || b[k] === undefined) d = a[k] === undefined || a[k] === null ? undefined : null;
      else if (!(k in a)) d = b[k] === null ? undefined : b[k];
      else d = mergeDiff(a[k], b[k]);
      if (d !== undefined) {
        out[k] = d;
        changed = true;
      }
    }
    return changed ? out : undefined;
  }
  if (JSON.stringify(a ?? null) === JSON.stringify(b ?? null)) return undefined;
  return b === undefined ? null : b;
}

function pruneNulls(v) {
  if (Array.isArray(v)) return v;
  if (!isObj(v)) return v;
  const out = {};
  for (const [k, x] of Object.entries(v)) if (x !== null && x !== undefined) out[k] = pruneNulls(x);
  return out;
}

// ------------------------------------------------------------------
// One control per schema field
// ------------------------------------------------------------------

/** Text for the hint under a field: description, unit, limits, default, when it applies. */
function hintFor(f) {
  const parts = [];
  const desc = t(f.description, null, "");
  if (desc) parts.push(desc);
  if (f.unit) parts.push(t("form.unit", { unit: t(`unit.${f.unit}`, null, f.unit) }));
  if (f.min !== undefined || f.max !== undefined) parts.push(t("form.range", { min: f.min ?? "", max: f.max ?? "" }));
  if (f.default !== undefined && f.default !== null && f.default !== "" && !isObj(f.default) && !Array.isArray(f.default) && f.type !== "secret") {
    parts.push(t("form.default", { value: f.type === "bool" ? t(f.default ? "set.on" : "set.off") : f.default }));
  }
  if (f.nullable) parts.push(t("set.nullable"));
  if (f.applies && f.applies !== "live") parts.push(t(`set.applies.${f.applies}`, null, f.applies));
  return parts.join(" · ");
}

function optionLabel(f, v) {
  return t(`${f.label}.option.${v}`, null, t(`role.${v}`, null, String(v)));
}

/**
 * f: schema field; rel: path inside the edited object; over: optional
 * {control, get, set} replacing the default renderer.
 * Returns {f, rel, el, get() -> {value}|{error}, set(v), mark(text|null)}.
 */
function settingControl(f, rel, over) {
  const label = t(f.label, null, rel);
  const err = h("span", { class: "field-error", "aria-live": "polite" });
  const r = over || renderer(f);
  let el;
  if (f.type === "bool" && !over) {
    el = h(
      "div",
      { class: "field" },
      h("label", { class: "check" }, r.control, h("span", null, label)),
      h("span", { class: "field-hint" }, hintFor(f)),
      err,
    );
  } else {
    const hint = [hintFor(f), r.hint].filter(Boolean).join(" · ");
    el = field(label, r.control, { hint, wide: r.wide });
    // A wrapped control (input + toggle) gets no <label for>; name its input directly.
    const inner = /^(INPUT|SELECT|TEXTAREA)$/.test(r.control.tagName) ? null : r.control.querySelector("input:not([type=checkbox]),select,textarea");
    if (inner) inner.setAttribute("aria-label", label);
    el.append(err);
  }
  const mark = (text) => {
    err.textContent = text || "";
    for (const inp of el.querySelectorAll("input,select,textarea")) {
      if (text) inp.setAttribute("aria-invalid", "true");
      else inp.removeAttribute("aria-invalid");
    }
  };
  return {
    f,
    rel,
    el,
    get() {
      const out = r.get();
      mark(out.error || null);
      return out;
    },
    set: (v) => r.set(v),
    mark,
  };
}

function renderer(f) {
  switch (f.type) {
    case "bool": {
      const input = h("input", { type: "checkbox" });
      return { control: input, get: () => ({ value: input.checked }), set: (v) => (input.checked = v === true) };
    }
    case "int":
    case "double": {
      const isInt = f.type === "int";
      const input = h("input", { type: "number", step: isInt ? "1" : "any", min: f.min, max: f.max, inputmode: isInt ? "numeric" : "decimal" });
      return {
        control: input,
        get() {
          const raw = input.value.trim();
          if (raw === "") return f.nullable ? { value: null } : { error: t("form.err.required") };
          const n = Number(raw);
          if (!Number.isFinite(n)) return { error: t("form.err.number") };
          if (isInt && !Number.isInteger(n)) return { error: t("form.err.int") };
          if (f.min !== undefined && n < f.min) return { error: t("form.err.min", { min: f.min }) };
          if (f.max !== undefined && n > f.max) return { error: t("form.err.max", { max: f.max }) };
          return { value: n };
        },
        set: (v) => (input.value = v === undefined || v === null ? "" : String(v)),
      };
    }
    case "secret": {
      const input = h("input", { type: "password", autocomplete: "off", spellcheck: "false" });
      return secretControl(input, () => ({ value: input.value }));
    }
    case "enum": {
      const values = Array.isArray(f.enum) ? f.enum : [];
      const opts = f.nullable ? [{ value: "", label: t("form.notSet") }] : [];
      for (const v of values) opts.push({ value: v, label: optionLabel(f, v) });
      const sel = select(opts, "");
      return {
        control: sel,
        get: () => ({ value: sel.value === "" ? (f.nullable ? null : values[0]) : sel.value }),
        set: (v) => (sel.value = v === undefined || v === null ? (f.nullable ? "" : String(values[0] ?? "")) : String(v)),
      };
    }
    case "enum_list": {
      const values = Array.isArray(f.enum) ? f.enum : [];
      const boxes = values.map((v) => ({ v, input: h("input", { type: "checkbox", value: v }) }));
      return {
        control: h("div", { class: "row" }, boxes.map((b) => h("label", { class: "check" }, b.input, h("span", null, optionLabel(f, b.v))))),
        wide: true,
        get: () => ({ value: boxes.filter((b) => b.input.checked).map((b) => b.v) }),
        set(v) {
          const list = Array.isArray(v) ? v : [];
          for (const b of boxes) b.input.checked = list.includes(b.v);
        },
      };
    }
    case "string_list": {
      const area = h("textarea", { rows: 4, spellcheck: "false" });
      return {
        control: area,
        wide: true,
        hint: t("form.hint.lines"),
        get: () => ({ value: area.value.split(/\r?\n/).map((l) => l.trim()).filter(Boolean) }),
        set: (v) => (area.value = Array.isArray(v) ? v.join("\n") : ""),
      };
    }
    case "map":
    case "json":
    case "list": {
      const area = h("textarea", { rows: f.type === "list" ? 10 : 4, spellcheck: "false" });
      let shape = f.type === "list" ? "array" : "object";
      return {
        control: area,
        wide: true,
        hint: t("form.hint.json"),
        get() {
          const raw = area.value.trim();
          if (!raw) return { value: f.nullable ? null : shape === "array" ? [] : {} };
          try {
            const v = JSON.parse(raw);
            if (f.type === "map" && !isObj(v)) return { error: t("set.err.object") };
            if (f.type === "list" && !Array.isArray(v)) return { error: t("set.err.array") };
            return { value: v };
          } catch (e) {
            return { error: t("form.err.json", { msg: e.message }) };
          }
        },
        set(v) {
          if (Array.isArray(v)) shape = "array";
          area.value = v === undefined || v === null ? "" : JSON.stringify(v, null, 2);
        },
      };
    }
    default: {
      const input = h("input", { type: "text", autocomplete: "off", spellcheck: "false" });
      return {
        control: input,
        wide: String(f.default || "").length > 40,
        get() {
          const v = input.value.trim();
          return { value: v === "" && f.nullable ? null : v };
        },
        set: (v) => (input.value = v === undefined || v === null ? "" : String(v)),
      };
    }
  }
}

/** Password-type input with a text toggle; the stored value is shown on request. */
function secretControl(input, get) {
  const toggle = btn(t("set.show"), () => {
    const hidden = input.type === "password";
    input.type = hidden ? "text" : "password";
    toggle.textContent = hidden ? t("set.hide") : t("set.show");
  });
  return {
    control: h("div", { class: "row-sm" }, input, toggle),
    get,
    set: (v) => (input.value = v === undefined || v === null ? "" : String(v)),
  };
}

/** Puts {path: code} validation errors on the matching controls; returns the ones left over. */
function showFieldErrors(fieldsMap, controls, strip) {
  const rest = [];
  for (const c of controls) c.mark(null);
  for (const [rawPath, code] of Object.entries(fieldsMap || {})) {
    const path = strip ? rawPath.replace(strip, "") : rawPath;
    const text = t(`error.field.${code}`, null, code);
    const c = controls.find((x) => path === x.rel || path.startsWith(`${x.rel}.`) || path.startsWith(`${x.rel}[`));
    if (c) c.mark(path === c.rel ? text : `${path}: ${text}`);
    else rest.push(`${rawPath}: ${text}`);
  }
  return rest;
}

/** Shows the outcome of a failed save: field errors in place, everything else in `box`. */
function reportError(e, controls, box, strip) {
  if (e && e.code === "validation" && e.body && e.body.fields) {
    const rest = showFieldErrors(e.body.fields, controls, strip);
    mount(box, h("div", { class: "error-box", role: "alert" }, t("set.err.validation", { n: Object.keys(e.body.fields).length }), rest.map((line) => h("div", { class: "small mono" }, line))));
    const first = controls.find((c) => c.el.querySelector("[aria-invalid]"));
    if (first) first.el.scrollIntoView({ block: "center" });
  } else mount(box, errorBox(e, "set.saveFailed"));
}

function appliesNote(controls, patch) {
  const kinds = new Set();
  for (const c of controls) {
    if (getPath(patch, c.rel) !== undefined && c.f.applies && c.f.applies !== "live") kinds.add(c.f.applies);
  }
  return [...kinds].map((k) => t(`set.applies.${k}`, null, k)).join(" · ");
}

// ------------------------------------------------------------------
// Global sections (general, runtime, behaviour, baritone, client, status, planner)
// ------------------------------------------------------------------

function sectionView(host, sectionId) {
  const schema = store.settings.schema;
  const fields = schema.fields.filter((f) => !f.path.includes("[]") && f.path.split(".")[0] === sectionId && !ITEM_LISTS.has(f.path));
  const controls = fields.map((f) => settingControl(f, f.path));
  const status = h("p", { class: "field-hint", "aria-live": "polite" });
  const errHost = h("div");
  const fill = () => {
    for (const c of controls) c.set(getPath(store.settings.config, c.rel));
  };
  fill();

  const rt = sectionId === "runtime" ? runtimeBox() : null;
  const needInstall = rt && rt.needInstall;

  const saveBtn = h("button", { type: "submit", class: ["btn", needInstall ? "btn-ghost" : "btn-primary"] }, t("set.save"));
  const form = h(
    "form",
    { class: "stack", novalidate: true },
    h("div", { class: "fields" }, controls.map((c) => c.el)),
    errHost,
    h("div", { class: "row" }, saveBtn, btn(t("set.revert"), () => {
      fill();
      for (const c of controls) c.mark(null);
      errHost.replaceChildren();
      status.textContent = "";
    }), status),
  );
  form.addEventListener("submit", async (ev) => {
    ev.preventDefault();
    errHost.replaceChildren();
    const next = {};
    const orig = {};
    let bad = 0;
    for (const c of controls) {
      const r = c.get();
      if (r.error) bad++;
      else setPath(next, c.rel, r.value);
      setPath(orig, c.rel, structuredClone(getPath(store.settings.config, c.rel) ?? null));
    }
    if (bad) {
      status.textContent = t("form.err.fields", { n: bad });
      return;
    }
    const patch = mergeDiff(orig, next);
    if (!patch) {
      status.textContent = t("set.noChanges");
      return;
    }
    await busy(saveBtn, async () => {
      try {
        store.settings = await api.put("/api/settings", patch);
        fill();
        const note = appliesNote(controls, patch);
        status.textContent = note ? `${t("set.saved")} ${note}` : t("set.saved");
        if (rt) rt.reload();
      } catch (e) {
        status.textContent = "";
        reportError(e, controls, errHost);
      }
    });
  });

  mount(
    host,
    rt ? rt.el : null,
    h("section", { class: "section" }, h("div", { class: "head" }, h("h2", { class: "h2" }, t(`settings.section.${sectionId}`, null, sectionId))), form),
  );
  return rt ? { update: rt.update } : null;
}

// ------------------------------------------------------------------
// Runtime install state
// ------------------------------------------------------------------

function runtimeBox() {
  let rt = store.runtime ? { ...store.runtime } : null;
  let err = null;
  const needInstall = !!(rt && !rt.ready && !rt.installing);
  const body = h("div", { class: "stack" });
  const el = h("section", { class: "section" }, h("div", { class: "head" }, h("h2", { class: "h2" }, t("set.rt.title"))), body);

  const stateEl = (s) => {
    const cls = s === "ready" ? "st-ok" : s === "failed" ? "st-bad" : s === "installing" ? "st-accent" : "st-warn";
    return h("span", { class: cls }, tid("runtime.state", s || "missing"));
  };

  function paint() {
    if (err && !rt) {
      mount(body, errorBox(err, "set.rt.loadFailed"));
      return;
    }
    if (!rt) {
      mount(body, empty(t("ui.loading")));
      return;
    }
    const v = rt.versions || {};
    const pairs = [
      [t("set.rt.state"), stateEl(rt.state)],
      rt.step && (rt.installing || rt.state === "failed") ? [t("set.rt.step"), h("span", null, tid("runtime.step", rt.step))] : null,
      rt.installing ? [t("set.rt.progress"), pct(rt.progress)] : null,
      rt.message ? [t("set.rt.message"), h("span", { class: "mono small" }, rt.message)] : null,
      rt.error ? [t("set.rt.error"), h("span", { class: "st-bad mono small" }, rt.error)] : null,
      [t("set.rt.minecraft"), h("span", { class: "num" }, v.minecraft || "")],
      [t("set.rt.fabric"), h("span", { class: "num" }, v.fabricLoader || "")],
      [t("set.rt.headlessmc"), h("span", { class: "num" }, v.headlessmc || "")],
      [t("set.rt.manager"), h("span", { class: "num" }, v.manager || "")],
      [t("set.rt.java"), rt.javaPath ? h("span", { class: "mono small" }, rt.javaPath) : noData()],
      [t("set.rt.botmod"), h("span", { class: rt.botModBundled ? "st-ok" : "st-bad" }, t(rt.botModBundled ? "set.yes" : "set.no"))],
      [t("set.rt.installedAt"), timeEl(rt.installedAt)],
      [t("set.rt.disk"), bytes(rt.diskBytes)],
    ].filter(Boolean);
    const mods = Array.isArray(rt.mods) ? rt.mods : [];
    const installBtn = btn(
      rt.ready ? t("set.rt.reinstall") : t("set.rt.install"),
      async (e) => {
        if (rt.ready && !(await confirmDialog(t("set.rt.reinstall"), t("set.rt.reinstallText"), t("set.rt.reinstall")))) return;
        await busy(e.currentTarget, async () => {
          const res = await api.post("/api/runtime/install");
          if (res && typeof res === "object") rt = { ...rt, ...res };
          paint();
        });
      },
      { kind: needInstall ? "primary" : "ghost", small: false, disabled: !!rt.installing },
    );
    mount(
      body,
      h("p", { class: "small" }, t("set.rt.text")),
      h(
        "dl",
        { class: "spec" },
        pairs.map(([k, val]) => [h("dt", null, k), h("dd", null, val)]),
      ),
      mods.length
        ? table(
            [t("set.rt.col.mod"), t("set.rt.col.version"), t("set.rt.col.file")],
            mods.map((m) => h("tr", null, h("td", { class: "mono" }, m.id || ""), h("td", { class: "num" }, m.version || ""), h("td", { class: "mono small" }, m.file || ""))),
            { caption: t("set.rt.mods") },
          )
        : h("p", { class: "empty" }, t("set.rt.noMods")),
      h("div", { class: "row" }, installBtn, rt.installing ? h("span", { class: "field-hint" }, t("set.rt.live")) : null),
    );
  }

  async function reload() {
    try {
      const got = await api.get("/api/runtime");
      rt = { ...(rt || {}), ...got };
      store.runtime = { ...(store.runtime || {}), ...got };
      err = null;
    } catch (e) {
      err = e;
    }
    paint();
  }

  const repaint = throttle(paint, 250);
  paint();
  reload();
  return {
    el,
    needInstall,
    reload,
    update(type, data) {
      if (type !== "runtime" || !data || typeof data !== "object") return;
      const wasInstalling = rt && rt.installing;
      rt = { ...(rt || {}), ...data };
      // The SSE view has no disk use; refresh it once the install ends.
      if (wasInstalling && !rt.installing) reload();
      else repaint();
    },
  };
}

// ------------------------------------------------------------------
// Servers and bots (list + editor)
// ------------------------------------------------------------------

const SERVERS = {
  list: "servers",
  base: "/api/servers",
  titleKey: "set.servers.title",
  textKey: "set.servers.text",
  addKey: "set.servers.add",
  emptyKey: "set.servers.empty",
  newKey: "set.servers.new",
  editKey: "set.servers.edit",
  deleteTextKey: "set.servers.deleteText",
  idHintKey: "set.servers.idHint",
  name: (s) => s.name || s.id,
  columns: () => [t("set.col.id"), t("settings.servers.name"), t("settings.servers.address"), { text: t("set.col.bots"), class: "r" }, t("settings.servers.autoConnect")],
  cells(s) {
    const n = (store.settings.config.bots || []).filter((b) => String(b.serverId).toLowerCase() === String(s.id).toLowerCase()).length;
    return [
      h("td", { class: "mono" }, s.id),
      h("td", null, s.name || ""),
      h("td", { class: "mono" }, s.address || ""),
      h("td", { class: "r" }, num(n)),
      h("td", null, t(s.autoConnect === false ? "set.no" : "set.yes")),
    ];
  },
  overrides: () => ({}),
};

const BOTS = {
  list: "bots",
  base: "/api/bots",
  titleKey: "set.bots.title",
  textKey: "set.bots.text",
  addKey: "set.bots.add",
  emptyKey: "set.bots.empty",
  newKey: "set.bots.new",
  editKey: "set.bots.edit",
  deleteTextKey: "set.bots.deleteText",
  idHintKey: "set.bots.idHint",
  name: (b) => b.username || b.id,
  columns: () => [
    t("set.col.id"),
    t("settings.bots.username"),
    t("settings.bots.serverId"),
    t("settings.bots.account.type"),
    t("settings.bots.roles"),
    t("settings.bots.enabled"),
    t("settings.bots.autoStart"),
    { text: t("set.col.memory"), class: "r" },
    t("set.col.process"),
  ],
  cells(b) {
    const v = store.bots.get(b.id);
    const server = store.settings.config.servers?.find((s) => String(s.id).toLowerCase() === String(b.serverId).toLowerCase());
    const acct = b.account?.type || "offline";
    return [
      h("td", null, h("a", { href: `#/bots/${enc(b.id)}`, class: "mono" }, b.id)),
      h("td", { class: "mono" }, b.username || ""),
      h("td", null, server ? server.name || server.id : b.serverId ? h("span", { class: "st-bad" }, b.serverId) : noData()),
      h("td", null, h("span", { class: acct === "microsoft" ? "st-warn" : null }, t(`settings.bots.account.type.option.${acct}`, null, acct))),
      h("td", null, (b.roles || []).map((r) => tid("role", r)).join(", ") || h("span", { class: "dim" }, t("set.none"))),
      h("td", null, t(b.enabled === false ? "set.no" : "set.yes")),
      h("td", null, t(b.autoStart === false ? "set.no" : "set.yes")),
      h("td", { class: "r" }, num(v?.memoryMbEffective ?? b.memoryMb, { unit: t("unit.MB", null, "MB") })),
      h("td", null, processEl(v?.process)),
    ];
  },
  overrides(item) {
    const cfg = store.settings.config;
    const servers = cfg.servers || [];
    const serverSel = select(
      [{ value: "", label: t("form.notSet") }, ...servers.map((s) => ({ value: s.id, label: `${s.name || s.id} (${s.id})` }))],
      "",
    );
    const accountSel = h(
      "select",
      null,
      h("option", { value: "offline" }, t("settings.bots.account.type.option.offline", null, "offline")),
      // The catalog label already says "not supported yet"; the option stays visible but cannot be picked.
      h("option", { value: "microsoft", disabled: true }, t("settings.bots.account.type.option.microsoft", null, "microsoft")),
    );
    const presetName = cfg.runtime?.memoryPreset || "normal";
    const presets = store.settings.schema.memoryPresets || {};
    const presetMb = presetName === "custom" ? cfg.runtime?.memoryMb : presets[presetName];
    return {
      serverId: {
        control: serverSel,
        get: () => ({ value: serverSel.value || null }),
        set: (v) => (serverSel.value = v && servers.some((s) => s.id === v) ? v : ""),
      },
      "account.type": {
        control: accountSel,
        hint: t("set.bots.msNote"),
        get: () => ({ value: accountSel.value }),
        set: (v) => (accountSel.value = v === "microsoft" ? "microsoft" : "offline"),
      },
      memoryMb: {
        hintExtra: t("set.bots.memoryHint", { preset: t(`settings.runtime.memoryPreset.option.${presetName}`, null, presetName), mb: presetMb ?? "" }),
      },
    };
  },
};

function itemsView(host, spec, params) {
  const schema = store.settings.schema;
  const prefix = `${spec.list}[].`;
  const fields = schema.fields.filter((f) => f.path.startsWith(prefix) && !f.path.slice(prefix.length).includes("[]"));
  const strip = new RegExp(`^${spec.list}\\[\\d+\\]\\.`);
  const listHost = h("div");
  const editHost = h("div");
  let editing = params.edit ? params.edit : null; // null | "" (new) | id

  const items = () => store.settings.config[spec.list] || [];

  async function refreshConfig() {
    await loadSettings(true);
    store.servers = store.settings.config.servers || [];
  }

  function drawList() {
    const list = items();
    const addBtn = editing === null ? btn(t(spec.addKey), () => openEditor(""), { kind: "primary", small: false }) : null;
    mount(
      listHost,
      h("div", { class: "head" }, h("h2", { class: "h2" }, t(spec.titleKey)), h("span", { class: "num dim" }, String(list.length)), addBtn),
      h("p", { class: "small" }, t(spec.textKey)),
      list.length
        ? table(
            [...spec.columns(), t("set.col.actions")],
            list.map((it) =>
              h(
                "tr",
                { "aria-current": editing && editing.toLowerCase() === String(it.id).toLowerCase() ? "true" : null },
                spec.cells(it),
                h("td", null, h("div", { class: "row-sm" }, btn(t("set.edit"), () => openEditor(it.id)), btn(t("set.delete"), (e) => remove(it, e.currentTarget)))),
              ),
            ),
            { caption: t(spec.titleKey) },
          )
        : empty(t(spec.emptyKey)),
    );
  }

  async function remove(it, button) {
    if (!(await confirmDialog(t("set.delete"), t(spec.deleteTextKey, { name: spec.name(it) }), t("set.delete")))) return;
    await busy(button, async () => {
      await api.del(`${spec.base}/${enc(it.id)}`);
      await refreshConfig();
      if (editing && editing.toLowerCase() === String(it.id).toLowerCase()) editing = null;
      toast("info", t("set.deleted", { name: spec.name(it) }));
      drawEditor();
      drawList();
    });
  }

  function openEditor(id) {
    editing = id;
    drawEditor();
    drawList();
    editHost.scrollIntoView({ block: "start" });
  }

  function closeEditor() {
    editing = null;
    editHost.replaceChildren();
    drawList();
  }

  function drawEditor() {
    if (editing === null) {
      editHost.replaceChildren();
      return;
    }
    const isNew = editing === "";
    const item = isNew ? null : items().find((x) => String(x.id).toLowerCase() === editing.toLowerCase());
    if (!isNew && !item) {
      mount(editHost, errorBox({ code: "not_found", message: editing }));
      return;
    }
    const over = spec.overrides(item);
    const base = {};
    if (isNew) for (const f of fields) if (f.default !== undefined && f.default !== null) setPath(base, f.path.slice(prefix.length), structuredClone(f.default));
    const src = isNew ? base : item;
    const controls = fields.map((f) => {
      const rel = f.path.slice(prefix.length);
      const o = over[rel];
      const c = settingControl(o && o.hintExtra ? { ...f, description: null } : f, rel, o && o.control ? o : null);
      if (o && o.hintExtra) {
        const hint = c.el.querySelector(".field-hint");
        const text = [t(f.description, null, ""), o.hintExtra].filter(Boolean).join(" · ");
        if (hint) hint.textContent = [text, hint.textContent].filter(Boolean).join(" · ");
      }
      c.set(getPath(src, rel));
      if (rel === "id") {
        const input = c.el.querySelector("input");
        if (!isNew && input) input.disabled = true;
        const hint = c.el.querySelector(".field-hint");
        if (hint) hint.textContent = t(isNew ? spec.idHintKey : "set.idFixed");
      }
      return c;
    });

    // Bot password: kept in secrets.json, not in config.json; sent only when changed.
    let pwControl = null;
    let pwOrig = "";
    if (spec.list === "bots") {
      pwOrig = isNew ? "" : store.bots.get(item.id)?.password || "";
      const input = h("input", { type: "password", autocomplete: "new-password", spellcheck: "false", maxlength: 64 });
      pwControl = settingControl(
        { path: "password", type: "secret", label: "set.bots.password", description: isNew ? "set.bots.passwordNew" : "set.bots.passwordHint" },
        "password",
        secretControl(input, () => {
          if (/\s/.test(input.value)) return { error: t("error.field.pattern") };
          return { value: input.value };
        }),
      );
      pwControl.set(pwOrig);
      controls.push(pwControl);
      if (!isNew && !pwOrig) {
        api.get(`/api/bots/${enc(item.id)}`).then(
          (v) => {
            if (v && v.password && !pwControl.get().value) {
              pwOrig = v.password;
              pwControl.set(pwOrig);
            }
          },
          () => {},
        );
      }
    }

    const status = h("p", { class: "field-hint", "aria-live": "polite" });
    const errHost = h("div");
    const saveBtn = h("button", { type: "submit", class: "btn btn-primary" }, isNew ? t("set.create") : t("set.save"));
    const form = h(
      "form",
      { class: "stack", novalidate: true },
      h("div", { class: "fields" }, controls.map((c) => c.el)),
      errHost,
      h(
        "div",
        { class: "row" },
        saveBtn,
        btn(t("ui.cancel"), closeEditor),
        isNew ? null : btn(t("set.delete"), (e) => remove(item, e.currentTarget)),
        status,
      ),
    );
    form.addEventListener("submit", async (ev) => {
      ev.preventDefault();
      errHost.replaceChildren();
      const next = {};
      let bad = 0;
      let password;
      for (const c of controls) {
        const r = c.get();
        if (r.error) bad++;
        else if (c === pwControl) password = r.value;
        else setPath(next, c.rel, r.value);
      }
      if (bad) {
        status.textContent = t("form.err.fields", { n: bad });
        return;
      }
      let body;
      if (isNew) {
        body = pruneNulls(next);
        if (!body.id) delete body.id;
        if (password) body.password = password;
      } else {
        const orig = {};
        for (const c of controls) if (c !== pwControl) setPath(orig, c.rel, structuredClone(getPath(item, c.rel) ?? null));
        delete next.id;
        delete orig.id;
        body = mergeDiff(orig, next) || {};
        if (pwControl && password !== pwOrig && password) body.password = password;
        if (!Object.keys(body).length) {
          status.textContent = t("set.noChanges");
          return;
        }
      }
      await busy(saveBtn, async () => {
        try {
          const res = isNew ? await api.post(spec.base, body) : await api.put(`${spec.base}/${enc(item.id)}`, body);
          await refreshConfig();
          const name = spec.name(res || body);
          toast("info", isNew ? t("set.created", { name }) : t("set.saved"));
          const note = isNew ? "" : appliesNote(controls, body);
          if (note) toast("warn", note);
          closeEditor();
        } catch (e) {
          status.textContent = "";
          reportError(e, controls, errHost, strip);
        }
      });
    });

    mount(
      editHost,
      h(
        "section",
        { class: "section" },
        h("div", { class: "head" }, h("h2", { class: "h2" }, isNew ? t(spec.newKey) : t(spec.editKey, { name: spec.name(item) }))),
        form,
      ),
    );
    const first = form.querySelector("input:not([disabled]),select,textarea");
    if (first) first.focus();
  }

  mount(host, h("section", { class: "section" }, listHost), editHost);
  drawList();
  drawEditor();

  const redrawList = throttle(drawList, 1000);
  return {
    update(type) {
      if (spec.list === "bots" && (type === "bot" || type === "process" || type === "snapshot")) redrawList();
    },
  };
}
