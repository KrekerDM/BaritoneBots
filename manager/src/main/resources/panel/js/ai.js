// Optional local AI (SPEC §5.7c, §5.7d): the command box with its plan
// confirmation, the project dispatcher feed and the connection check.
// Everything here stays hidden while ai.enabled is false.

import { api, request, enc } from "./api.js";
import { h, mount, btn, busy, toast, errorBox, errorText, dialog, field, table, spec } from "./dom.js";
import { t, tid } from "./i18n.js";
import { store } from "./store.js";
import { templateTitle } from "./forms.js";
import { argsSummary, shortId, num, noData, timeEl } from "./format.js";

export const aiEnabled = () => Boolean(store.ai && store.ai.enabled);

const PLAN_MARGIN_MS = 10000;
const SUMMARIES_SHOWN = 8;
const LOG_SHOWN = 30;

const botName = (id) => (id === "any" ? t("ai.anyBot") : store.bots.get(id)?.username || id);

function reasonText(code, detail) {
  const text = t(`ai.reason.${code}`, null, t(`error.${code}`, null, code));
  return detail ? `${text}: ${detail}` : text;
}

/** A task template as a title plus its arguments in mono. */
function stepEl(step) {
  const args = argsSummary(step?.args);
  return h("span", null, h("span", { class: "strong" }, templateTitle(step)), args ? h("span", { class: "mono dim" }, `  ${args}`) : null);
}

function orderEl(o) {
  return h(
    "span",
    null,
    t("ai.order"),
    " ",
    h("span", { class: "mono" }, shortId(o.item)),
    " ",
    num(o.min, { unit: t("unit.items"), max: o.max }),
    " → ",
    h("span", { class: "mono" }, o.into),
  );
}

function projectEl(p) {
  const bots = Array.isArray(p.bots) ? p.bots.map(botName).join(", ") : t("ai.allBots");
  return h(
    "span",
    null,
    h("span", { class: "strong" }, `${tid("kind", p.kind)} «${p.name}»`),
    ` · ${bots}`,
    h("div", { class: "mono dim small" }, argsSummary(p.config)),
  );
}

function itemsList(items) {
  return h("ul", { class: "list ai-items" }, items.map((el) => h("li", null, el)));
}

function rejectedList(list) {
  if (!list || !list.length) return null;
  return h(
    "div",
    { class: "stack-sm" },
    h("p", { class: "label" }, t("ai.rejected", { n: list.length })),
    h("p", { class: "small dim" }, t("ai.rejectedHint")),
    itemsList(list.map((r) => h("span", null, h("span", { class: "st-warn" }, reasonText(r.reason, r.detail)), h("div", { class: "mono dim small" }, r.what || "")))),
  );
}

/** Shows the validated plan; the only primary button runs it. Resolves true when it ran. */
async function confirmPlan(res, botIds) {
  const steps = res.plan || [];
  const orders = res.orders || [];
  const project = res.project || null;
  const runnable = steps.length + orders.length + (project ? 1 : 0);
  const errHost = h("div");
  const body = h(
    "div",
    { class: "stack" },
    h("p", { class: "small dim" }, h("span", { class: "mono" }, res.model || ""), res.ms ? [" · ", num(res.ms / 1000, { unit: t("unit.s"), digits: 1 })] : null),
    steps.length ? h("div", { class: "stack-sm" }, h("p", { class: "label" }, t("ai.steps")), itemsList(steps.map((s) => h("span", null, h("span", { class: "dim" }, `${botName(s.botId)} — `), stepEl(s.step))))) : null,
    orders.length ? h("div", { class: "stack-sm" }, h("p", { class: "label" }, t("ai.orders")), itemsList(orders.map(orderEl))) : null,
    project ? h("div", { class: "stack-sm" }, h("p", { class: "label" }, t("ai.project")), itemsList([projectEl(project)])) : null,
    runnable ? null : h("p", { class: "empty" }, t("ai.planEmpty")),
    res.notes ? h("div", { class: "stack-sm" }, h("p", { class: "label" }, t("ai.notes")), h("p", { class: "prose" }, res.notes)) : null,
    rejectedList(res.rejected),
    errHost,
  );
  let running = false;
  const run = async () => {
    if (running) return false;
    running = true;
    try {
      const out = await request("POST", "/api/ai/run", {
        body: { id: res.id, plan: steps, orders, project, text: res.text, botIds },
        timeoutMs: 40000,
      });
      const failed = out.failed || [];
      toast("info", t("ai.ran", { tasks: (out.queued || []).length, orders: (out.orders || []).length }) + (out.project ? ` ${t("ai.ranProject", { name: out.project.name })}` : ""));
      if (failed.length) toast("warn", t("ai.runFailed", { list: failed.map((f) => reasonText(f.reason, f.detail)).join("; ") }));
      return true;
    } catch (e) {
      mount(errHost, errorBox(e));
      return false;
    } finally {
      running = false;
    }
  };
  const actions = runnable
    ? [
        { label: t("ai.confirm"), value: true, primary: true, validate: run },
        { label: t("ui.cancel"), value: false },
      ]
    : [{ label: t("ui.close"), value: false }];
  return (await dialog(t("ai.planTitle"), body, actions)) === true;
}

/**
 * The command box: a text field and a "parse" button; the plan opens in a
 * dialog. `botIds()` limits the plan to those bots (empty = all bots).
 */
export function commandBox({ botIds, hint }) {
  const input = h("input", { type: "text", class: "ai-input", maxlength: 2000, autocomplete: "off", placeholder: t("ai.placeholder") });
  const parse = h("button", { type: "submit", class: "btn btn-ghost" }, t("ai.parse"));
  const status = h("span", { class: "field-hint", "aria-live": "polite" });
  const errHost = h("div");
  const modelEl = h("span", { class: "small dim mono" });
  const form = h("form", { class: "stack-sm", novalidate: true }, field(t("ai.field"), input, { hint: hint || t("ai.hint"), wide: true }), h("div", { class: "row" }, parse, status), errHost);
  const el = h("section", { class: "section" }, h("div", { class: "head" }, h("h2", { class: "h2" }, t("ai.title")), modelEl), form);
  const sync = () => {
    el.hidden = !aiEnabled();
    modelEl.textContent = store.ai?.model || "";
  };
  sync();
  form.addEventListener("submit", async (e) => {
    e.preventDefault();
    errHost.replaceChildren();
    const text = input.value.trim();
    if (!text) {
      status.textContent = t("error.empty_text");
      return;
    }
    const ids = botIds ? botIds() : [];
    status.textContent = t("ai.thinking", { s: store.ai?.timeoutSec ?? 30 });
    let res;
    try {
      res = await busy(
        parse,
        () => request("POST", "/api/ai/plan", { body: { text, botIds: ids }, timeoutMs: (store.ai?.timeoutSec ?? 30) * 1000 + PLAN_MARGIN_MS }),
        { toastErrors: false },
      );
    } catch (err) {
      status.textContent = "";
      mount(errHost, errorBox(err));
      return;
    }
    status.textContent = "";
    if (await confirmPlan(res, ids)) input.value = "";
  });
  return {
    el,
    update(type) {
      if (type === "ai" || type === "snapshot") sync();
    },
  };
}

// ------------------------------------------------------------------
// Dispatcher feed (project page)
// ------------------------------------------------------------------

/** One supervisor action in words. */
function actionEl(act) {
  const a = act.args || {};
  const name = t(`aiAct.${act.type}`, null, act.type || "");
  switch (act.type) {
    case "set_priority":
      return h("span", null, name, " → ", num(a.priority));
    case "reassign":
      return h("span", null, name, `: ${botName(a.bot)} → ${tid("role", a.role)}`);
    case "add_standing_order":
      return h("span", null, name, ": ", orderEl(a));
    case "add_task":
      return h("span", null, name, `: ${botName(a.bot)} — `, stepEl(a.step));
    case "notify":
      return h("span", null, name, `: «${a.text || ""}»`);
    case "pause_project":
    case "resume_project":
      return h("span", null, name);
    default:
      return h("span", null, name, act.raw ? h("span", { class: "mono dim" }, `  ${act.raw}`) : null);
  }
}

function statusEl(act) {
  const st = act.status || "pending";
  const cls = st === "applied" ? "st-ok" : st === "failed" || st === "rejected" ? "st-bad" : st === "pending" ? "st-accent" : "dim";
  return h("span", { class: cls }, t(`ai.status.${st}`, null, st));
}

/** "Диспетчер" section: summaries, pending suggestions (Apply / Dismiss) and the action log. */
export function dispatcherSection(projectId, getProject) {
  const body = h("div", { class: "stack" });
  const meta = h("span", { class: "small dim" });
  const ask = btn(
    t("ai.feed.ask"),
    (e) =>
      busy(e.currentTarget, async () => {
        await request("POST", "/api/ai/supervise", { body: { project: projectId }, timeoutMs: (store.ai?.timeoutSec ?? 30) * 1000 + PLAN_MARGIN_MS });
        await load();
      }),
    { small: true },
  );
  const el = h("section", { class: "section" }, h("div", { class: "head" }, h("h2", { class: "h2" }, t("ai.feed.title")), h("div", { class: "row-sm" }, meta, ask)), body);
  let feed = null;
  let error = null;
  let version = 0; // bumped on every feed change
  let drawnKey = null;

  async function load() {
    if (!aiEnabled()) return draw();
    try {
      feed = await api.get("/api/ai/feed", { project: projectId });
      error = null;
    } catch (e) {
      error = e;
    }
    version++;
    draw();
  }

  // A round can send several entries at once (new summary + superseded suggestions): one fetch for the burst.
  let loadTimer = null;
  function loadSoon() {
    clearTimeout(loadTimer);
    loadTimer = setTimeout(load, 300);
  }

  function decide(act, verb) {
    return (e) =>
      busy(e.currentTarget, async () => {
        const entry = await api.post(`/api/ai/suggestions/${enc(act.id)}/${verb}`);
        merge(entry);
        const done = (entry.actions || []).find((x) => x.id === act.id);
        if (done && done.status === "failed") toast("error", reasonText(done.why, done.detail));
      });
  }

  function merge(entry) {
    if (!entry || !entry.id) return;
    if (!feed) feed = { entries: [] };
    const i = feed.entries.findIndex((x) => x.id === entry.id);
    if (i >= 0) feed.entries[i] = entry;
    else feed.entries.unshift(entry);
    version++;
    draw();
  }

  /** Redraws only when the feed, the project status or the mode changed, so buttons keep focus and clicks land. */
  function draw() {
    el.hidden = !aiEnabled();
    if (el.hidden) return;
    const pr = getProject();
    const running = pr && pr.status === "running";
    const key = `${version}|${running}|${store.ai?.mode}`;
    if (key === drawnKey) return;
    drawnKey = key;
    ask.disabled = !running;
    mount(meta, t(`ai.mode.${store.ai?.mode || "suggest"}`), feed?.inFlight ? ` · ${t("ai.feed.inFlight")}` : "", running && feed?.nextAt ? [` · ${t("ai.feed.next")} `, timeEl(feed.nextAt)] : null);
    if (error) return mount(body, errorBox(error));
    const entries = feed?.entries || [];
    const pending = [];
    const log = [];
    for (const en of entries) {
      for (const act of en.actions || []) {
        if (act.status === "pending") pending.push(act);
        else log.push({ act, time: act.decidedAt || en.time });
      }
    }
    const parts = [];
    if (!running) parts.push(h("p", { class: "small dim" }, t("ai.feed.notRunning")));
    parts.push(
      h("div", { class: "stack-sm" }, h("p", { class: "label" }, t("ai.feed.pending")), pending.length
        ? itemsList(pending.map((act) => h("div", { class: "row spread" }, h("div", null, actionEl(act), act.reason ? h("div", { class: "small dim" }, act.reason) : null), h("div", { class: "row-sm" }, btn(t("ai.feed.apply"), decide(act, "apply")), btn(t("ai.feed.dismiss"), decide(act, "dismiss"))))))
        : h("p", { class: "small dim" }, t("ai.feed.noPending"))),
    );
    const summaries = entries.slice(0, SUMMARIES_SHOWN);
    parts.push(
      h("div", { class: "stack-sm" }, h("p", { class: "label" }, t("ai.feed.summaries")), summaries.length
        ? itemsList(summaries.map((en) => h("div", null, h("div", { class: "small dim" }, timeEl(en.time), ` · ${t(`ai.trigger.${en.trigger}`, null, en.trigger || "")}`, en.count > 1 ? ` · ${t("ai.feed.times", { n: en.count })}` : ""), en.kind === "error" ? h("p", { class: "st-bad" }, `${t(`error.${en.code}`, null, en.code)} ${en.message || ""}`) : h("p", { class: "prose" }, en.summary || t("ai.feed.noSummary")))))
        : h("p", { class: "small dim" }, t("ai.feed.empty", { s: store.ai?.superviseSec ?? 90 }))),
    );
    log.sort((a, b) => (b.time || 0) - (a.time || 0));
    parts.push(
      h("div", { class: "stack-sm" }, h("p", { class: "label" }, t("ai.feed.log")), log.length
        ? table([t("ai.col.time"), t("ai.col.action"), t("ai.col.status"), t("ai.col.reason")], log.slice(0, LOG_SHOWN).map(({ act, time }) => h("tr", null, h("td", null, timeEl(time)), h("td", { class: "wrap-cell" }, actionEl(act)), h("td", null, statusEl(act), act.by ? h("span", { class: "dim" }, ` · ${t(`aiBy.${act.by}`, null, act.by)}`) : null), h("td", { class: "wrap-cell" }, act.why ? h("div", { class: "st-warn" }, reasonText(act.why, act.detail)) : null, act.reason || (act.why ? null : noData())))))
        : h("p", { class: "small dim" }, t("ai.feed.noLog"))),
    );
    mount(body, parts);
  }

  draw();
  load();
  return {
    el,
    update(type, data) {
      // A new entry also moves "next review" and the in-flight flag: fetch the whole feed (one request per round).
      if (type === "ai" && data?.type === "feed" && data.projectId === projectId) loadSoon();
      else if (type === "ai" && data?.type === "status") load();
      else if (type === "project" && (data?.id === projectId || data?.project?.id === projectId)) draw();
    },
  };
}

// ------------------------------------------------------------------
// Connection check (settings → ai)
// ------------------------------------------------------------------

export function connectionBox() {
  const out = h("div", { "aria-live": "polite" });
  const check = btn(
    t("ai.check"),
    (e) =>
      busy(e.currentTarget, async () => {
        try {
          paint(await api.get("/api/ai/status"));
        } catch (err) {
          mount(out, errorBox(err));
        }
      }),
    { small: false },
  );
  function paint(s) {
    const pairs = [[t("ai.conn.endpoint"), h("span", { class: "mono" }, s.endpoint || "")]];
    if (s.reachable) {
      pairs.push([t("ai.conn.state"), h("span", { class: "st-ok" }, t("ai.conn.ok"))]);
      pairs.push([
        t("ai.conn.model"),
        h("span", null, h("span", { class: "mono" }, s.model), " ", s.modelPresent ? h("span", { class: "st-ok" }, t("ai.conn.modelOk")) : h("span", { class: "st-warn" }, t("ai.conn.modelMissing", { model: s.model }))),
      ]);
      pairs.push([t("ai.conn.models"), h("span", null, num((s.models || []).length), (s.models || []).length ? h("div", { class: "mono dim small" }, s.models.join(", ")) : null)]);
    } else {
      pairs.push([t("ai.conn.state"), h("span", { class: "st-bad" }, t("ai.conn.down"))]);
      pairs.push([t("ai.conn.error"), errorText(s.error || {})]);
    }
    mount(out, spec(pairs));
  }
  return h(
    "section",
    { class: "section" },
    h("div", { class: "head" }, h("h2", { class: "h2" }, t("ai.conn.title"))),
    h("p", { class: "small" }, t("ai.conn.text")),
    h("div", { class: "row" }, check),
    out,
  );
}
