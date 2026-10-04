// Automation: autopilot state and per-bot overrides (SPEC §5.7a), standing
// orders, schedules and rules (§5.7b), and the inventory hygiene settings
// (keep profiles, sign words, auto-trash; §5.7e, §5.7f) drawn by the
// schema-driven settings form.

import { api, enc, listOf } from "../api.js";
import { h, mount, btn, busy, toast, confirmDialog, errorBox, errorText, empty, table, field, select, spec } from "../dom.js";
import { t, tid } from "../i18n.js";
import { store, botList, loadCatalog, loadSettings, loadWorld, serverName } from "../store.js";
import { num, noData, isNum, timeEl, shortId, posText } from "../format.js";
import { stepsEditor, botsChoice, intoOptions } from "../forms.js";
import { containerLabel, ownerName } from "../refpick.js";
import { renderSettingsSection } from "./settings.js";

const TABS = ["autopilot", "orders", "schedules", "rules", "hygiene"];
const POLL_MS = 10000;
// Keys a bot may override in bots[].autopilot (ManagerConfig.AutopilotCfg.BOT_KEYS).
const BOT_FLAGS = ["supply", "sort", "idleWork", "discovery"];
const BOT_NUMBERS = [
  { key: "foodMin", unit: "items", min: 0, max: 256, step: 1 },
  { key: "blocksMin", unit: "items", min: 0, max: 1024, step: 1 },
  { key: "toolMinDurability", unit: null, min: 0, max: 1, step: 0.05 },
  { key: "stuckSec", unit: "s", min: 0, max: 3600, step: 1 },
];
const GLOBAL_FLAGS = ["supply", "sort", "idleWork", "discovery", "useFound"];
const TRIGGERS = ["event", "containerFull", "itemBelow", "playerOnline", "healthBelow"];
// Event kinds offered for "event" triggers; any lower_snake kind is accepted.
const EVENT_KINDS = [
  "death",
  "damaged",
  "threat",
  "inventory_full",
  "tool_low",
  "food_low",
  "disconnected",
  "kicked",
  "joined",
  "task_failed",
  "task_done",
  "stuck",
  "crashed",
  "link_lost",
  "project_blocked",
  "project_done",
  "order_blocked",
  "sort_full",
  "manual",
  "goal_done",
  "goal_failed",
  "plugin_rollback",
];
const PRIORITIES = ["normal", "high"];
const WHEN_MODES = ["every", "hourly", "daily", "day", "night", "cron"];

export function render(root, params, app) {
  const tab = TABS.includes(params.tab) ? params.tab : "autopilot";
  const host = h("div", { class: "stack-lg" });
  mount(
    root,
    h(
      "div",
      { class: "stack" },
      h("h1", { class: "h1" }, t("auto.title")),
      h("p", { class: "prose" }, t("auto.text")),
      h(
        "nav",
        { class: "tabs", "aria-label": t("auto.sections") },
        TABS.map((x) => h("a", { href: `#/automation/${x}`, "aria-current": x === tab ? "page" : null }, t(`auto.tab.${x}`))),
      ),
      host,
    ),
  );
  mount(host, empty(t("ui.loading")));
  let live = null;
  const views = { autopilot: autopilotView, orders: ordersView, schedules: schedulesView, rules: rulesView, hygiene: hygieneView };
  // Owner name, categories and keep profiles come from the settings.
  loadSettings().then(
    () => (live = views[tab](host, params, app)),
    () => (live = views[tab](host, params, app)),
  );
  return {
    update(type, data) {
      if (live && live.update) live.update(type, data);
    },
    destroy() {
      if (live && live.destroy) live.destroy();
    },
  };
}

const sec = (titleKey, ...children) => h("section", { class: "section" }, h("div", { class: "head" }, h("h2", { class: "h2" }, t(titleKey))), children);

const onOff = (v) => (v ? h("span", { class: "st-ok" }, t("set.on")) : h("span", { class: "dim" }, t("set.off")));

function serverOptions(withAll) {
  const opts = store.servers.map((s) => ({ value: s.id, label: s.name || s.id }));
  return withAll ? [{ value: "", label: t("auto.allServers") }, ...opts] : opts;
}

const botLabel = (id) => store.bots.get(id)?.username || id;

function lastEl(last) {
  if (!last) return h("span", { class: "dim" }, t("auto.never"));
  return h(
    "div",
    { class: "stack-sm" },
    h("span", null, timeEl(last.lastFiredAt), last.why ? h("span", { class: "dim" }, ` · ${last.why}`) : null),
    Array.isArray(last.bots) && last.bots.length ? h("span", { class: "small" }, last.bots.map(botLabel).join(", ")) : null,
    last.error ? h("span", { class: "small st-bad" }, last.error) : null,
  );
}

// ------------------------------------------------------------------
// Autopilot
// ------------------------------------------------------------------
function autopilotView(host) {
  const stateHost = h("div", { class: "stack-lg" });
  const overridesHost = h("div");
  let view = null;
  let error = null;
  let timer = null;

  async function load() {
    clearTimeout(timer);
    try {
      view = await api.get("/api/autopilot");
      error = null;
    } catch (e) {
      error = e;
    }
    draw();
    timer = setTimeout(load, POLL_MS);
  }

  function draw() {
    if (error) {
      mount(stateHost, errorBox(error, "auto.loadFailed"));
      return;
    }
    if (!view) return;
    const s = view.settings || {};
    const flags = spec([
      ...GLOBAL_FLAGS.map((k) => [t(`auto.flag.${k}`), onOff(s[k])]),
      [t("auto.flag.autoTrash"), onOff(s.autoTrash?.enabled)],
      [t("auto.homeRadius"), isNum(s.homeRadius) ? num(s.homeRadius, { unit: t("unit.blocks") }) : noData()],
      [t("auto.holdAfterOwner"), isNum(s.holdAfterOwnerSec) ? num(s.holdAfterOwnerSec, { unit: t("unit.s") }) : noData()],
      [t("auto.stuckSec"), isNum(s.stuckSec) ? num(s.stuckSec, { unit: t("unit.s") }) : noData()],
    ]);

    const servers = Array.isArray(view.servers) ? view.servers : [];
    const serverRows = servers.map((sv) => {
      const notes = sv.notes && typeof sv.notes === "object" ? Object.entries(sv.notes) : [];
      const as = Array.isArray(sv.assignments) ? sv.assignments : [];
      return h(
        "tr",
        null,
        h("td", null, serverName(sv.serverId)),
        h("td", { class: "r" }, num(sv.offered ?? 0, { unit: t("unit.items") })),
        h(
          "td",
          { class: "wrap-cell" },
          as.length
            ? as.map((a) => h("div", { class: "small" }, h("a", { href: `#/bots/${enc(a.botId)}` }, botLabel(a.botId)), " · ", a.workItem?.label || tid("work", a.workItem?.kind)))
            : h("span", { class: "dim" }, t("auto.noAssignments")),
        ),
        h("td", { class: "wrap-cell" }, notes.length ? notes.map(([k, v]) => h("div", { class: "small" }, h("span", { class: "mono dim" }, `${k}: `), v)) : num(0)),
      );
    });

    const held = view.heldSec || {};
    const still = view.stillSec || {};
    const refill = view.supply?.refill || {};
    const goals = Array.isArray(view.goals) ? view.goals : [];
    const botRows = botList().map((b) => {
      const g = goals.filter((x) => x.botId === b.id);
      const hs = held[b.id];
      return h(
        "tr",
        null,
        h("td", null, h("a", { href: `#/bots/${enc(b.id)}` }, b.username || b.id)),
        h("td", null, hs === undefined ? num(0, { unit: t("unit.s") }) : hs < 0 ? t("auto.heldFollowing") : num(hs, { unit: t("unit.s") })),
        h("td", null, num(still[b.id] ?? 0, { unit: t("unit.s"), threshold: isNum(s.stuckSec) ? t("auto.stuckAt", { s: s.stuckSec }) : null, warn: isNum(s.stuckSec) && (still[b.id] ?? 0) >= s.stuckSec / 2 })),
        h("td", { class: "wrap-cell" }, Array.isArray(refill[b.id]) && refill[b.id].length ? h("span", { class: "mono" }, refill[b.id].map(shortId).join(", ")) : num(0)),
        h(
          "td",
          { class: "wrap-cell" },
          g.length
            ? g.map((x) => h("div", { class: "small" }, h("span", { class: "mono" }, `${shortId(x.item)} ${x.count}`), ` · ${t("auto.goalSteps", { n: x.steps ?? 0 })}`, x.lastAction ? h("span", { class: "dim" }, ` · ${x.lastAction}`) : null))
            : h("span", { class: "dim" }, t("ui.none")),
        ),
      );
    });

    mount(
      stateHost,
      sec("auto.ap.settings", flags, h("p", { class: "small" }, h("a", { href: "#/settings/autopilot" }, t("auto.editSettings")), " · ", h("a", { href: "#/automation/hygiene" }, t("auto.tab.hygiene")))),
      sec(
        "auto.ap.servers",
        serverRows.length
          ? table([t("proj.col.server"), { text: t("auto.offered"), class: "r" }, t("auto.assignments"), t("auto.notes")], serverRows)
          : h("p", { class: "empty" }, t("auto.noServers")),
      ),
      sec(
        "auto.ap.bots",
        h("p", { class: "small" }, t("auto.ap.botsText")),
        botRows.length ? table([t("bots.col.bot"), t("auto.held"), t("auto.still"), t("auto.refill"), t("auto.goals")], botRows) : h("p", { class: "empty" }, t("bots.empty")),
      ),
    );
  }

  function drawOverrides() {
    const bots = botList();
    if (!bots.length) {
      mount(overridesHost, h("p", { class: "empty" }, t("bots.empty")));
      return;
    }
    const global = store.settings?.config?.autopilot || {};
    const rows = bots.map((b) => {
      const o = b.autopilot && typeof b.autopilot === "object" ? b.autopilot : {};
      const flagSels = BOT_FLAGS.map((k) => {
        const sel = select(
          [
            { value: "", label: t("auto.asGlobal", { v: global[k] ? t("set.on") : t("set.off") }) },
            { value: "true", label: t("set.on") },
            { value: "false", label: t("set.off") },
          ],
          o[k] === true ? "true" : o[k] === false ? "false" : "",
          { "aria-label": `${t(`auto.flag.${k}`)}: ${b.username || b.id}` },
        );
        return { k, sel };
      });
      const numIns = BOT_NUMBERS.map((d) => {
        const input = h("input", {
          type: "number",
          min: d.min,
          max: d.max,
          step: d.step,
          inputmode: "decimal",
          value: isNum(o[d.key]) ? String(o[d.key]) : "",
          placeholder: isNum(global[d.key]) ? String(global[d.key]) : "",
          "aria-label": `${t(`auto.num.${d.key}`)}: ${b.username || b.id}`,
          class: "input-num",
        });
        return { d, input };
      });
      const note = h("span", { class: "field-hint", "aria-live": "polite" });
      const save = btn(t("ui.save"), (e) =>
        busy(e.currentTarget, async () => {
          const patch = {};
          for (const { k, sel } of flagSels) patch[k] = sel.value === "" ? null : sel.value === "true";
          for (const { d, input } of numIns) {
            const raw = input.value.trim();
            if (raw === "") {
              patch[d.key] = null;
              continue;
            }
            const v = Number(raw);
            if (!Number.isFinite(v) || v < d.min || v > d.max) {
              note.textContent = `${t(`auto.num.${d.key}`)}: ${t("form.range", { min: d.min, max: d.max })}`;
              input.setAttribute("aria-invalid", "true");
              return;
            }
            input.removeAttribute("aria-invalid");
            patch[d.key] = v;
          }
          const res = await api.put(`/api/bots/${enc(b.id)}`, { autopilot: patch });
          if (res && typeof res === "object") store.bots.set(b.id, { ...store.bots.get(b.id), autopilot: res.autopilot || {} });
          note.textContent = t("set.saved");
        }),
      );
      return h(
        "tr",
        null,
        h("td", null, b.username || b.id),
        flagSels.map(({ sel }) => h("td", null, sel)),
        numIns.map(({ input }) => h("td", null, input)),
        h("td", { class: "actions" }, h("div", { class: "row-sm" }, save, note)),
      );
    });
    mount(
      overridesHost,
      table(
        [
          t("bots.col.bot"),
          ...BOT_FLAGS.map((k) => t(`auto.flag.${k}`)),
          ...BOT_NUMBERS.map((d) => (d.unit ? `${t(`auto.num.${d.key}`)}, ${t(`unit.${d.unit}`, null, d.unit)}` : t(`auto.num.${d.key}`))),
          t("bot.qcol.actions"),
        ],
        rows,
      ),
    );
  }

  mount(host, stateHost, sec("auto.ap.overrides", h("p", { class: "small" }, t("auto.ap.overridesText")), overridesHost));
  mount(stateHost, empty(t("ui.loading")));
  load();
  drawOverrides();
  return {
    destroy() {
      clearTimeout(timer);
    },
  };
}

// ------------------------------------------------------------------
// Standing orders
// ------------------------------------------------------------------
function ordersView(host) {
  const listHost = h("div");
  const formHost = h("div");
  let orders = [];
  let error = null;
  let editing = null; // order being edited, or null for a new one
  let timer = null;

  async function load() {
    clearTimeout(timer);
    try {
      orders = listOf(await api.get("/api/orders"), "orders");
      error = null;
    } catch (e) {
      error = e;
    }
    drawList();
    timer = setTimeout(load, POLL_MS);
  }

  function intoLabel(into, serverId) {
    if (!into) return t("containerRole.storage");
    if (into.startsWith("sorted:")) return `${t("containerRole.sorted")}: ${into.slice(7)}`;
    if (["storage", "supply", "kit", "fuel", "inbox"].includes(into)) return t(`containerRole.${into}`, null, into);
    const c = (store.worlds.get(serverId)?.containers || []).find((x) => x.id === into);
    return c ? containerLabel(c) : into;
  }

  function drawList() {
    if (error) return mount(listHost, errorBox(error, "auto.loadFailed"));
    if (!orders.length) return mount(listHost, h("p", { class: "empty" }, t("auto.orders.empty")));
    const rows = orders.map((o) => {
      const st = o.status || {};
      const enabled = h("input", { type: "checkbox", checked: o.enabled !== false, "aria-label": `${t("auto.enabled")}: ${o.item}` });
      enabled.addEventListener("change", () =>
        busy(enabled, async () => {
          await api.put(`/api/orders/${enc(o.id)}`, { enabled: enabled.checked });
          load();
        }),
      );
      return h(
        "tr",
        { "aria-selected": String(editing?.id === o.id) },
        h("td", null, enabled),
        h("td", { class: "mono" }, shortId(o.item)),
        h("td", null, num(o.min ?? 0, { unit: t("unit.items") }), isNum(o.max) ? h("span", { class: "num-threshold" }, t("auto.orders.upTo", { n: o.max })) : null),
        h("td", null, intoLabel(o.into, o.serverId), store.servers.length > 1 && o.serverId ? h("div", { class: "small dim" }, serverName(o.serverId)) : null),
        h(
          "td",
          null,
          h("span", { class: st.state === "active" ? "st-accent" : st.state === "ok" ? "st-ok" : st.state === "error" || st.state === "no_containers" ? "st-bad" : "dim" }, tid("orderState", st.state || "unknown")),
          st.message ? h("div", { class: "small dim" }, st.message) : null,
        ),
        h("td", { class: "r" }, isNum(st.stock) ? num(st.stock, { max: st.target, threshold: t("auto.orders.min", { n: o.min ?? 0 }), warn: st.stock < (o.min ?? 0) }) : noData()),
        h("td", { class: "r" }, num(st.inTransit ?? 0)),
        h(
          "td",
          { class: "actions" },
          h(
            "div",
            { class: "row-sm" },
            btn(t("set.edit"), () => {
              editing = o;
              drawList();
              drawForm();
              formHost.querySelector("input")?.focus();
            }),
            btn(t("set.delete"), async (e) => {
              const target = e.currentTarget;
              if (!(await confirmDialog(t("set.delete"), t("auto.orders.deleteConfirm", { item: shortId(o.item) }), t("set.delete")))) return;
              busy(target, async () => {
                await api.del(`/api/orders/${enc(o.id)}`);
                if (editing?.id === o.id) editing = null;
                drawForm();
                load();
              });
            }),
          ),
        ),
      );
    });
    mount(
      listHost,
      table(
        [t("auto.enabled"), t("proj.bom.item"), t("auto.orders.min"), t("auto.orders.into"), t("auto.state"), { text: t("auto.orders.stock"), class: "r" }, { text: t("proj.bom.transit"), class: "r" }, t("bot.qcol.actions")],
        rows,
      ),
    );
  }

  function drawForm() {
    const o = editing || {};
    const itemIn = h("input", { type: "text", list: "dl-items", autocomplete: "off", spellcheck: "false", value: o.item || "", placeholder: "minecraft:torch" });
    const minIn = h("input", { type: "number", min: 0, max: 100000, step: 1, inputmode: "numeric", value: String(o.min ?? 64) });
    const maxIn = h("input", { type: "number", min: 0, max: 100000, step: 1, inputmode: "numeric", value: isNum(o.max) ? String(o.max) : "" });
    const serverSel = select(serverOptions(false), o.serverId || store.servers[0]?.id || "");
    const intoSel = h("select");
    const fillInto = () => {
      const cur = intoSel.value || o.into || "storage";
      const containers = store.worlds.get(serverSel.value)?.containers || [];
      intoSel.replaceChildren(
        h("optgroup", { label: t("auto.orders.roles") }, intoOptions().map((x) => h("option", { value: x.value }, x.label))),
        containers.length ? h("optgroup", { label: t("auto.orders.containers") }, containers.filter((c) => c.id).map((c) => h("option", { value: c.id }, containerLabel(c)))) : null,
      );
      if (![...intoSel.options].some((x) => x.value === cur)) intoSel.append(h("option", { value: cur }, cur));
      intoSel.value = cur;
    };
    fillInto();
    const loadServerWorld = () => serverSel.value && loadWorld(serverSel.value).then(fillInto, () => {});
    serverSel.addEventListener("change", loadServerWorld);
    loadServerWorld();
    const enabledIn = h("input", { type: "checkbox", checked: o.enabled !== false });
    const note = h("p", { class: "field-hint", "aria-live": "polite" });
    const submit = h("button", { type: "submit", class: "btn btn-primary" }, editing ? t("auto.orders.save") : t("auto.orders.add"));
    const form = h(
      "form",
      { class: "stack", novalidate: true },
      h(
        "div",
        { class: "fields" },
        field(t("proj.bom.item"), itemIn, { hint: t("auto.orders.itemHint") }),
        field(t("auto.orders.minField"), minIn, { hint: t("auto.orders.minHint") }),
        field(t("auto.orders.maxField"), maxIn, { hint: t("auto.orders.maxHint") }),
        field(t("auto.orders.into"), intoSel, { hint: t("auto.orders.intoHint") }),
        store.servers.length > 1 ? field(t("proj.server"), serverSel) : null,
      ),
      h("label", { class: "check" }, enabledIn, h("span", null, t("auto.enabled"))),
      h(
        "div",
        { class: "row" },
        submit,
        editing
          ? btn(t("ui.cancel"), () => {
              editing = null;
              drawList();
              drawForm();
            })
          : null,
        note,
      ),
    );
    form.addEventListener("submit", async (e) => {
      e.preventDefault();
      const item = itemIn.value.trim();
      const min = Number(minIn.value);
      const maxRaw = maxIn.value.trim();
      const max = maxRaw === "" ? null : Number(maxRaw);
      if (!item) {
        note.textContent = t("quick.err.item");
        itemIn.focus();
        return;
      }
      if (!Number.isInteger(min) || min < 0) {
        note.textContent = t("form.err.int");
        minIn.focus();
        return;
      }
      if (max !== null && (!Number.isInteger(max) || max < min)) {
        note.textContent = t("auto.orders.err.max");
        maxIn.focus();
        return;
      }
      const body = { item, min, max, into: intoSel.value, enabled: enabledIn.checked };
      if (serverSel.value) body.serverId = serverSel.value;
      await busy(submit, async () => {
        try {
          if (editing) await api.put(`/api/orders/${enc(editing.id)}`, body);
          else await api.post("/api/orders", { ...body, max: max === null ? undefined : max });
          toast("info", t("set.saved"));
          editing = null;
          drawForm();
          load();
        } catch (err) {
          note.textContent = errorText(err);
          throw err;
        }
      });
    });
    mount(formHost, h("h3", { class: "h3" }, editing ? t("auto.orders.editTitle", { item: shortId(editing.item) }) : t("auto.orders.newTitle")), form);
  }

  mount(host, sec("auto.tab.orders", h("p", { class: "small" }, t("auto.orders.text")), listHost), h("section", { class: "section" }, formHost));
  mount(listHost, empty(t("ui.loading")));
  drawForm();
  load();
  return {
    destroy() {
      clearTimeout(timer);
    },
  };
}

// ------------------------------------------------------------------
// Schedules and rules share the list / editor frame
// ------------------------------------------------------------------

/** "*\/30 * * * *" → {mode: "every", n: 30}; day / night; anything else is raw cron. */
export function parseWhen(when) {
  const w = String(when || "").trim();
  let m;
  if (w === "day" || w === "night") return { mode: w };
  if ((m = w.match(/^\*\/(\d{1,2}) \* \* \* \*$/))) return { mode: "every", n: Number(m[1]) };
  if ((m = w.match(/^(\d{1,2}) \* \* \* \*$/))) return { mode: "hourly", minute: Number(m[1]) };
  if ((m = w.match(/^(\d{1,2}) (\d{1,2}) \* \* \*$/))) return { mode: "daily", hour: Number(m[2]), minute: Number(m[1]) };
  return { mode: "cron", cron: w || "0 * * * *" };
}

export function whenText(when) {
  const p = parseWhen(when);
  const two = (v) => String(v).padStart(2, "0");
  switch (p.mode) {
    case "every":
      return t("auto.when.everyText", { n: p.n });
    case "hourly":
      return t("auto.when.hourlyText", { m: two(p.minute) });
    case "daily":
      return t("auto.when.dailyText", { time: `${two(p.hour)}:${two(p.minute)}` });
    case "day":
    case "night":
      return t(`auto.when.${p.mode}`);
    default:
      return p.cron;
  }
}

function whenControl(initial) {
  const p = parseWhen(initial);
  const two = (v) => String(v).padStart(2, "0");
  const modeSel = select(WHEN_MODES.map((m) => ({ value: m, label: t(`auto.when.${m}`) })), p.mode);
  const nIn = h("input", { type: "number", min: 1, max: 59, step: 1, inputmode: "numeric", value: String(p.n ?? 30), "aria-label": t("auto.when.minutes") });
  const minIn = h("input", { type: "number", min: 0, max: 59, step: 1, inputmode: "numeric", value: String(p.minute ?? 0), "aria-label": t("auto.when.minute") });
  const timeIn = h("input", { type: "time", value: p.mode === "daily" ? `${two(p.hour)}:${two(p.minute)}` : "08:00", "aria-label": t("auto.when.time") });
  const cronIn = h("input", { type: "text", spellcheck: "false", autocomplete: "off", value: p.cron || initial || "0 * * * *", "aria-label": "cron", class: "mono" });
  const hint = h("span", { class: "field-hint" });
  const parts = {
    every: field(t("auto.when.minutes"), nIn),
    hourly: field(t("auto.when.minute"), minIn),
    daily: field(t("auto.when.time"), timeIn),
    cron: field("cron", cronIn, { hint: t("auto.when.cronHint") }),
  };
  const sync = () => {
    for (const [k, el] of Object.entries(parts)) el.hidden = k !== modeSel.value;
    hint.textContent = modeSel.value === "day" || modeSel.value === "night" ? t("auto.when.dayNightHint") : "";
  };
  modeSel.addEventListener("change", sync);
  sync();
  return {
    el: h("div", { class: "fields" }, field(t("auto.when.title"), modeSel), Object.values(parts), hint),
    get() {
      switch (modeSel.value) {
        case "every": {
          const n = Number(nIn.value);
          return Number.isInteger(n) && n >= 1 && n <= 59 ? { value: `*/${n} * * * *` } : { error: t("form.range", { min: 1, max: 59 }) };
        }
        case "hourly": {
          const m = Number(minIn.value);
          return Number.isInteger(m) && m >= 0 && m <= 59 ? { value: `${m} * * * *` } : { error: t("form.range", { min: 0, max: 59 }) };
        }
        case "daily": {
          const mt = timeIn.value.match(/^(\d{2}):(\d{2})/);
          return mt ? { value: `${Number(mt[2])} ${Number(mt[1])} * * *` } : { error: t("form.err.required") };
        }
        case "cron": {
          const c = cronIn.value.trim().replace(/\s+/g, " ");
          return c.split(" ").length === 5 ? { value: c } : { error: t("auto.when.err.cron") };
        }
        default:
          return { value: modeSel.value };
      }
    },
  };
}

// Latin ids from Russian names (the manager's own slug keeps only a-z0-9).
const TRANSLIT = { а: "a", б: "b", в: "v", г: "g", д: "d", е: "e", ё: "e", ж: "zh", з: "z", и: "i", й: "y", к: "k", л: "l", м: "m", н: "n", о: "o", п: "p", р: "r", с: "s", т: "t", у: "u", ф: "f", х: "h", ц: "ts", ч: "ch", ш: "sh", щ: "sch", ъ: "", ы: "y", ь: "", э: "e", ю: "yu", я: "ya" };

/** "Сортировка каждый час" → "sortirovka-kazhdyy-chas", unique among `taken` (ConfigValidator.ID, ≤ 32). */
export function slugId(name, taken) {
  const base =
    String(name || "")
      .toLowerCase()
      .split("")
      .map((c) => (c in TRANSLIT ? TRANSLIT[c] : c))
      .join("")
      .replace(/[^a-z0-9]+/g, "-")
      .replace(/^-+|-+$/g, "")
      .slice(0, 28) || "item";
  let id = base;
  for (let i = 2; taken.has(id); i++) id = `${base}-${i}`;
  return id;
}

function botsText(v) {
  if (Array.isArray(v)) return v.map(botLabel).join(", ");
  return t(`auto.bots.${v === "all" ? "all" : "any"}`);
}

/**
 * List + editor frame. cfg: {kind: "schedules"|"rules", path, listKey, columns(), row(item, actions),
 * editor(item, catalog) -> {el, get() -> {value}|{error}}, emptyKey, newKey}
 */
function itemsView(host, params, app, cfg) {
  const listHost = h("div");
  const editHost = h("div");
  let items = [];
  let error = null;
  let timer = null;
  const id = params.id || null;

  async function load() {
    clearTimeout(timer);
    try {
      items = listOf(await api.get(cfg.path), cfg.listKey);
      error = null;
    } catch (e) {
      error = e;
    }
    drawList();
    timer = setTimeout(load, POLL_MS * 3);
  }

  const runBtn = (item, small = true) =>
    btn(
      t("auto.runNow"),
      (e) =>
        busy(e.currentTarget, async () => {
          const r = await api.post(`${cfg.path}/${enc(item.id)}/run`);
          const bots = Array.isArray(r?.bots) ? r.bots : [];
          toast("info", bots.length ? t("auto.ranOn", { bots: bots.map(botLabel).join(", ") }) : t("auto.ranNone"));
          load();
        }),
      { small },
    );

  function drawList() {
    if (error) return mount(listHost, errorBox(error, "auto.loadFailed"));
    if (!items.length) return mount(listHost, h("p", { class: "empty" }, t(cfg.emptyKey)));
    const rows = items.map((it) => {
      const enabled = h("input", { type: "checkbox", checked: it.enabled !== false, "aria-label": `${t("auto.enabled")}: ${it.name || it.id}` });
      enabled.addEventListener("change", () =>
        busy(enabled, async () => {
          await api.put(`${cfg.path}/${enc(it.id)}`, { enabled: enabled.checked });
          load();
        }),
      );
      return h(
        "tr",
        { "aria-selected": String(it.id === id) },
        h("td", null, enabled),
        h("td", null, h("a", { href: `#/automation/${cfg.kind}/${enc(it.id)}` }, it.name || it.id)),
        cfg.row(it),
        h("td", null, lastEl(it.last)),
        h("td", { class: "actions" }, h("div", { class: "row-sm" }, runBtn(it))),
      );
    });
    mount(listHost, table([t("auto.enabled"), t("proj.col.name"), ...cfg.columns(), t("auto.last"), t("bot.qcol.actions")], rows));
  }

  async function openEditor() {
    if (!id) {
      mount(editHost, null);
      return;
    }
    const isNew = id === "new";
    let item = null;
    if (!isNew) {
      item = items.find((x) => x.id === id) || null;
      if (!item) {
        try {
          items = listOf(await api.get(cfg.path), cfg.listKey);
          item = items.find((x) => x.id === id) || null;
        } catch (e) {
          mount(editHost, errorBox(e, "auto.loadFailed"));
          return;
        }
      }
      if (!item) {
        mount(editHost, h("section", { class: "section" }, errorBox({ code: "not_found", message: id })));
        return;
      }
    }
    let catalog;
    try {
      catalog = await loadCatalog();
    } catch (e) {
      mount(editHost, errorBox(e, "ui.catalogFailed"));
      return;
    }
    const base = item ? structuredClone(item) : cfg.blank();
    const nameIn = h("input", { type: "text", maxlength: 80, value: base.name || "", required: true });
    const enabledIn = h("input", { type: "checkbox", checked: base.enabled !== false });
    const serverSel = select(serverOptions(true), base.serverId || "");
    const prioritySel = select(PRIORITIES.map((p) => ({ value: p, label: t(`auto.priority.${p}`) })), base.priority || "normal");
    const bots = botsChoice(base.botIds ?? "any");
    const specific = cfg.editor(base);
    const stepsCount = h("p", { class: "label" });
    const stepsKey = cfg.kind === "rules" ? "then" : "steps";
    const steps = stepsEditor({
      catalog,
      steps: Array.isArray(base[stepsKey]) ? base[stepsKey] : [],
      onChange: (list) => (stepsCount.textContent = t("scen.steps", { n: list.length })),
    });
    stepsCount.textContent = t("scen.steps", { n: steps.count() });
    const note = h("p", { class: "field-hint", "aria-live": "polite" });
    const saveBtn = h("button", { type: "button", class: "btn btn-primary" }, t("auto.save"));
    saveBtn.addEventListener("click", () =>
      busy(saveBtn, async () => {
        const name = nameIn.value.trim();
        if (!name) {
          nameIn.setAttribute("aria-invalid", "true");
          note.textContent = t("form.err.required");
          nameIn.focus();
          return;
        }
        nameIn.removeAttribute("aria-invalid");
        const sp = specific.get();
        if (sp.error) {
          note.textContent = sp.error;
          return;
        }
        const b = bots.get();
        if (b.error) {
          note.textContent = b.error;
          return;
        }
        if (!steps.count()) {
          note.textContent = t("auto.err.noSteps");
          return;
        }
        const body = {
          name,
          enabled: enabledIn.checked,
          serverId: serverSel.value || null,
          priority: prioritySel.value,
          botIds: b.value,
          [stepsKey]: steps.steps(),
          ...sp.value,
        };
        try {
          if (isNew) {
            body.id = slugId(name, new Set(items.map((x) => String(x.id).toLowerCase())));
            const created = await api.post(cfg.path, body);
            toast("info", t("set.saved"));
            app.navigate(`/automation/${cfg.kind}/${created && created.id ? created.id : ""}`);
          } else {
            await api.put(`${cfg.path}/${enc(id)}`, body);
            note.textContent = t("set.saved");
            load();
          }
        } catch (err) {
          note.textContent = errorText(err);
          throw err;
        }
      }),
    );
    const delBtn = isNew
      ? null
      : btn(
          t("set.delete"),
          async (e) => {
            const target = e.currentTarget;
            if (!(await confirmDialog(t("set.delete"), t("auto.deleteConfirm", { name: base.name || id }), t("set.delete")))) return;
            busy(target, async () => {
              await api.del(`${cfg.path}/${enc(id)}`);
              app.navigate(`/automation/${cfg.kind}`);
            });
          },
          { small: false },
        );
    mount(
      editHost,
      h(
        "div",
        { class: "stack" },
        h(
          "section",
          { class: "section" },
          h("div", { class: "head" }, h("h2", { class: "h2" }, isNew ? t(cfg.newKey) : base.name || base.id), h("a", { href: `#/automation/${cfg.kind}` }, t("auto.closeEditor"))),
          h("div", { class: "fields" }, field(t("proj.col.name"), nameIn), store.servers.length > 1 ? field(t("proj.server"), serverSel) : null, field(t("auto.priority"), prioritySel, { hint: t("auto.priorityHint") })),
          h("label", { class: "check" }, enabledIn, h("span", null, t("auto.enabled"))),
          specific.el,
          bots.el,
          stepsCount,
          steps.listEl,
          h("div", { class: "row" }, saveBtn, isNew ? null : runBtn(base, false), delBtn, note),
        ),
        h("section", { class: "section" }, steps.formEl),
      ),
    );
  }

  mount(
    host,
    h(
      "section",
      { class: "section" },
      h("div", { class: "head" }, h("h2", { class: "h2" }, t(`auto.tab.${cfg.kind}`)), id === "new" ? null : h("a", { class: "btn btn-ghost btn-sm", href: `#/automation/${cfg.kind}/new` }, t(cfg.newKey))),
      h("p", { class: "small" }, t(cfg.textKey)),
      listHost,
    ),
    editHost,
  );
  mount(listHost, empty(t("ui.loading")));
  load().then(openEditor);
  return {
    destroy() {
      clearTimeout(timer);
    },
  };
}

// ------------------------------------------------------------------
// Schedules
// ------------------------------------------------------------------
function schedulesView(host, params, app) {
  return itemsView(host, params, app, {
    kind: "schedules",
    path: "/api/schedules",
    listKey: "schedules",
    emptyKey: "auto.sch.empty",
    newKey: "auto.sch.new",
    textKey: "auto.sch.text",
    blank: () => ({ name: "", enabled: true, when: "*/30 * * * *", botIds: "any", steps: [], priority: "normal" }),
    columns: () => [t("auto.when.title"), t("auto.sch.next"), t("auto.bots"), t("auto.steps")],
    row: (s) => [
      h("td", null, whenText(s.when), h("div", { class: "small mono dim" }, s.when || "")),
      h("td", null, nextEl(s)),
      h("td", null, botsText(s.botIds)),
      h("td", null, num(Array.isArray(s.steps) ? s.steps.length : 0)),
    ],
    editor(s) {
      const when = whenControl(s.when);
      return {
        el: when.el,
        get() {
          const w = when.get();
          return w.error ? w : { value: { when: w.value } };
        },
      };
    },
  });
}

function nextEl(s) {
  if (s.next) {
    const d = new Date(s.next);
    return Number.isNaN(d.getTime()) ? h("span", { class: "mono" }, s.next) : timeEl(d.getTime());
  }
  if (s.night && typeof s.night === "object") {
    const vals = Object.entries(s.night);
    if (!vals.length) return noData();
    return h("span", null, vals.map(([sid, night]) => `${store.servers.length > 1 ? `${serverName(sid)}: ` : ""}${night ? t("auto.sch.nowNight") : t("auto.sch.nowDay")}`).join(", "));
  }
  return noData();
}

// ------------------------------------------------------------------
// Rules
// ------------------------------------------------------------------
export function triggerText(cond) {
  const c = cond && typeof cond === "object" ? cond : {};
  if (c.event !== undefined) return t("auto.trig.eventText", { kind: t(`evk.${c.event}`, null, c.event) });
  if (c.containerFull !== undefined) return t("auto.trig.containerFullText", { c: typeof c.containerFull === "object" ? posText(c.containerFull) : c.containerFull });
  if (c.itemBelow !== undefined) return t("auto.trig.itemBelowText", { item: shortId(c.itemBelow?.item || "?"), n: c.itemBelow?.count ?? "?" });
  if (c.playerOnline !== undefined) return t("auto.trig.playerOnlineText", { name: c.playerOnline });
  if (c.healthBelow !== undefined) return t("auto.trig.healthBelowText", { n: c.healthBelow });
  return t("auto.trig.none");
}

function triggerControl(initial) {
  const c = initial && typeof initial === "object" ? initial : {};
  const current = TRIGGERS.find((k) => c[k] !== undefined) || "event";
  const typeSel = select(TRIGGERS.map((k) => ({ value: k, label: t(`auto.trig.${k}`) })), current);

  const evIn = h("input", { type: "text", list: "dl-events", autocomplete: "off", spellcheck: "false", value: c.event || "death", class: "mono" });
  const evList = h("datalist", { id: "dl-events" }, EVENT_KINDS.map((k) => h("option", { value: k }, t(`evk.${k}`))));

  const cfSel = h("select", { "aria-label": t("form.knownContainer") });
  const cfIn = h("input", { type: "text", autocomplete: "off", spellcheck: "false", placeholder: "x,y,z", class: "mono" });
  const cfInitial = typeof c.containerFull === "object" && c.containerFull ? `${c.containerFull.x},${c.containerFull.y},${c.containerFull.z}` : c.containerFull || "";
  const fillContainers = () => {
    const all = [];
    for (const [sid, w] of store.worlds) for (const cont of w.containers || []) if (cont.id) all.push({ sid, cont });
    const cur = cfSel.value || cfInitial;
    cfSel.replaceChildren(
      h("option", { value: "" }, all.length ? t("form.pickContainer") : t("form.noContainers")),
      ...all.map(({ sid, cont }) => h("option", { value: cont.id }, `${containerLabel(cont)}${store.servers.length > 1 ? ` · ${serverName(sid)}` : ""}`)),
    );
    if (cur && [...cfSel.options].some((o) => o.value === cur)) cfSel.value = cur;
    else if (cur) cfIn.value = cur;
  };
  fillContainers();
  for (const s of store.servers) if (!store.worlds.has(s.id)) loadWorld(s.id).then(fillContainers, () => {});

  const ibItem = h("input", { type: "text", list: "dl-items", autocomplete: "off", spellcheck: "false", value: c.itemBelow?.item || "", placeholder: "minecraft:bread" });
  const ibCount = h("input", { type: "number", min: 1, max: 1000000, step: 1, inputmode: "numeric", value: String(c.itemBelow?.count ?? 16) });
  const poIn = h("input", { type: "text", list: "dl-players", autocomplete: "off", spellcheck: "false", maxlength: 16, value: c.playerOnline || ownerName() });
  const hbIn = h("input", { type: "number", min: 1, max: 1024, step: 1, inputmode: "decimal", value: String(c.healthBelow ?? 6) });

  const parts = {
    event: h("div", { class: "fields" }, field(t("auto.trig.eventField"), evIn, { hint: t("auto.trig.eventHint") }), evList),
    containerFull: h("div", { class: "fields" }, field(t("form.knownContainer"), cfSel), field(t("auto.trig.containerManual"), cfIn, { hint: t("auto.trig.containerHint") })),
    itemBelow: h("div", { class: "fields" }, field(t("proj.bom.item"), ibItem), field(t("auto.trig.count"), ibCount, { hint: t("auto.trig.itemBelowHint") })),
    playerOnline: h("div", { class: "fields" }, field(t("auto.trig.player"), poIn)),
    healthBelow: h("div", { class: "fields" }, field(t("auto.trig.health"), hbIn, { hint: t("auto.trig.healthHint") })),
  };
  const sync = () => {
    for (const [k, el] of Object.entries(parts)) el.hidden = k !== typeSel.value;
  };
  typeSel.addEventListener("change", sync);
  sync();
  return {
    el: h("fieldset", null, h("legend", null, t("auto.trig.title")), h("div", { class: "stack" }, field(t("auto.trig.type"), typeSel), Object.values(parts))),
    get() {
      switch (typeSel.value) {
        case "event": {
          const k = evIn.value.trim();
          return /^[a-z0-9_]{1,48}$/.test(k) ? { value: { event: k } } : { error: t("auto.trig.err.event") };
        }
        case "containerFull": {
          const v = cfSel.value || cfIn.value.trim();
          return v ? { value: { containerFull: v } } : { error: t("pick.err.container") };
        }
        case "itemBelow": {
          const item = ibItem.value.trim();
          const count = Number(ibCount.value);
          if (!item) return { error: t("quick.err.item") };
          if (!Number.isInteger(count) || count < 1) return { error: t("form.err.min", { min: 1 }) };
          return { value: { itemBelow: { item, count } } };
        }
        case "playerOnline": {
          const name = poIn.value.trim();
          return /^[A-Za-z0-9_]{1,16}$/.test(name) ? { value: { playerOnline: name } } : { error: t("auto.trig.err.player") };
        }
        default: {
          const v = Number(hbIn.value);
          return v > 0 && v <= 1024 ? { value: { healthBelow: v } } : { error: t("form.range", { min: 1, max: 1024 }) };
        }
      }
    },
  };
}

function rulesView(host, params, app) {
  return itemsView(host, params, app, {
    kind: "rules",
    path: "/api/rules",
    listKey: "rules",
    emptyKey: "auto.rule.empty",
    newKey: "auto.rule.new",
    textKey: "auto.rule.text",
    blank: () => ({ name: "", enabled: true, if: { event: "death" }, then: [], botIds: "any", cooldownSec: 300, priority: "normal" }),
    columns: () => [t("auto.trig.title"), t("auto.rule.cooldown"), t("auto.rule.active"), t("auto.bots")],
    row: (r) => {
      const active = r.state && typeof r.state === "object" ? Object.entries(r.state).filter(([, v]) => v).map(([k]) => (k === "server" || k === r.serverId ? t("auto.rule.scopeServer") : botLabel(k))) : [];
      return [
        h("td", null, triggerText(r.if), r.trigger ? h("div", { class: "small mono dim" }, r.trigger) : null),
        h("td", null, num(r.cooldownSec ?? 300, { unit: t("unit.s") })),
        h("td", null, active.length ? h("span", { class: "st-accent" }, active.join(", ")) : num(0)),
        h("td", null, botsText(r.botIds)),
      ];
    },
    editor(r) {
      const trig = triggerControl(r.if);
      const cdIn = h("input", { type: "number", min: 0, max: 86400, step: 1, inputmode: "numeric", value: String(r.cooldownSec ?? 300) });
      return {
        el: h("div", { class: "stack" }, trig.el, h("div", { class: "fields" }, field(t("auto.rule.cooldown"), cdIn, { hint: t("auto.rule.cooldownHint") }))),
        get() {
          const tr = trig.get();
          if (tr.error) return tr;
          const cd = Number(cdIn.value);
          if (!Number.isInteger(cd) || cd < 0 || cd > 86400) return { error: t("form.range", { min: 0, max: 86400 }) };
          return { value: { if: tr.value, cooldownSec: cd } };
        },
      };
    },
  });
}

// ------------------------------------------------------------------
// Keep profiles, sign words, auto-trash (settings sections)
// ------------------------------------------------------------------
function hygieneView(host) {
  const profilesHost = h("div");
  const wordsHost = h("div");
  mount(host, h("p", { class: "small" }, t("auto.hyg.text")), profilesHost, wordsHost);
  loadSettings(true).then(
    () => {
      renderSettingsSection(profilesHost, "keepProfiles", { filter: (f) => f.path === "keepProfiles" });
      renderSettingsSection(wordsHost, "autopilot", {
        filter: (f) => f.path === "autopilot.signWords" || f.path.startsWith("autopilot.autoTrash."),
        titleKey: "auto.hyg.wordsTitle",
        primary: false, // one primary button per screen: the profiles' save above
      });
    },
    (e) => mount(host, errorBox(e, "set.loadFailed")),
  );
  return null;
}
