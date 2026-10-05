// One project: progress and the kind's counters, BOM, sector map,
// assignments (bot → role → work item), blocked work, controls.

import { api, enc, listOf } from "../api.js";
import { h, mount, btn, busy, confirmDialog, errorBox, empty, table, spec, throttle } from "../dom.js";
import { t, tid } from "../i18n.js";
import { store, serverName } from "../store.js";
import { num, noData, isNum, durationEl, posText, dimLabel, shortId, timeEl, levelEl, argsSummary, boxText, refText } from "../format.js";
import { statusEl } from "./projects.js";
import { dispatcherSection } from "../ai.js";

/**
 * Normalised progress numbers of any project kind (SPEC §5.7: done/total
 * where it makes sense, else counters and a rate).
 */
export function projectProgress(p) {
  const pr = p.progress || {};
  const total = isNum(pr.total) ? pr.total : null;
  const done = isNum(pr.placed) ? pr.placed : isNum(pr.done) ? pr.done : null;
  let fraction = null;
  if (isNum(pr.percent)) fraction = pr.percent / 100;
  else if (total !== null && total > 0 && done !== null) fraction = done / total;
  else if (total === 0) fraction = 1;
  let rate = null;
  let rateUnit = null;
  if (isNum(pr.blocksPerMin)) {
    rate = pr.blocksPerMin;
    rateUnit = t("unit.blocksPerMin");
  } else if (isNum(pr.itemsPerHour)) {
    rate = pr.itemsPerHour;
    rateUnit = t("unit.itemsPerHour");
  }
  const unit = p.kind === "build" || p.kind === "clear" ? t("unit.blocks") : p.kind === "farm" ? t("unit.rounds") : t("unit.items");
  return { total, done: done ?? (total !== null ? 0 : null), fraction, unit, rate, rateUnit, etaSec: pr.etaSec, remaining: pr.remaining };
}

const n = (v, unit, opts) => (isNum(v) ? num(v, { unit, ...opts }) : noData());

/** Kind-specific counters as [label, element] pairs (null entries are skipped). */
export function kindCounters(p) {
  const pr = p.progress || {};
  switch (p.kind) {
    case "build":
      return [
        [t("proj.c.sectors"), isNum(pr.sectors) ? num(pr.sectorsDone ?? 0, { max: pr.sectors }) : noData()],
        [t("proj.c.missing"), n(pr.missing, t("unit.blocks"))],
        [t("proj.wrong"), n(pr.wrong, t("unit.blocks"), { warn: pr.wrong > 0 })],
        [t("proj.unloaded"), n(pr.unloaded, t("unit.blocks"))],
        pr.finalPass ? [t("proj.c.finalPass"), t("set.yes")] : null,
      ];
    case "clear":
      return [[t("proj.c.slabs"), isNum(pr.slabs) ? num(pr.slabsDone ?? 0, { max: pr.slabs }) : noData()]];
    case "farm":
      return [
        [t("proj.c.rounds"), isNum(pr.total) ? num(pr.rounds ?? 0, { max: pr.total }) : n(pr.rounds)],
        [t("proj.c.harvested"), n(pr.harvested, t("unit.items"))],
        [t("proj.c.farming"), n(pr.farmingMin, t("unit.min"))],
      ];
    case "ranch":
      return [
        [t("proj.c.population"), isNum(pr.population) ? num(pr.population, { max: pr.max, unit: t("unit.animals"), threshold: t("proj.c.keep", { n: pr.keep ?? 0 }) }) : noData("proj.c.notCounted")],
        [t("proj.c.rounds"), n(pr.rounds)],
        [t("proj.c.fed"), n(pr.fed, t("unit.animals"))],
        [t("proj.c.killed"), n(pr.killed, t("unit.animals"))],
        [t("proj.c.sheared"), n(pr.sheared, t("unit.animals"))],
        [t("proj.c.next"), isNum(pr.nextAt) ? timeEl(pr.nextAt) : noData()],
      ];
    case "gather":
      return [[t("proj.c.rounds"), n(pr.rounds)]];
    case "sort":
      return [
        [t("proj.c.remaining"), n(pr.remaining, t("unit.items"))],
        [t("proj.c.moved"), n(pr.moved, t("unit.items"))],
        [t("proj.c.unsorted"), pr.unsorted && Object.keys(pr.unsorted).length ? h("span", { class: "mono" }, argsSummary(pr.unsorted)) : num(0)],
      ];
    case "smelt":
      return [
        [t("proj.c.inFurnaces"), n(pr.inFurnaces, t("unit.items"))],
        [t("proj.c.remainingInputs"), n(pr.remainingInputs, t("unit.items"))],
        [t("proj.c.furnacesBusy"), n(pr.furnacesBusy)],
      ];
    default:
      return [];
  }
}

/** One short line of counters for the project list (kinds without an ETA). */
export function kindShort(p) {
  const pr = p.progress || {};
  const parts = [];
  if (p.kind === "farm" && isNum(pr.harvested)) parts.push(`${t("proj.c.harvested")} ${pr.harvested}`);
  if (p.kind === "ranch" && isNum(pr.population)) parts.push(`${t("proj.c.population")} ${pr.population} / ${pr.max ?? "?"}`);
  if (p.kind === "clear" && isNum(pr.slabs)) parts.push(`${t("proj.c.slabs")} ${pr.slabsDone ?? 0} / ${pr.slabs}`);
  if (p.kind === "sort" && isNum(pr.remaining)) parts.push(`${t("proj.c.remaining")} ${pr.remaining}`);
  if (p.kind === "smelt" && isNum(pr.inFurnaces)) parts.push(`${t("proj.c.inFurnaces")} ${pr.inFurnaces}`);
  if ((p.kind === "farm" || p.kind === "gather" || p.kind === "ranch") && isNum(pr.rounds)) parts.push(`${t("proj.c.rounds")} ${pr.rounds}`);
  const blocked = Array.isArray(p.blocked) ? p.blocked.length : 0;
  if (blocked) parts.push(t("proj.blockedN", { n: blocked }));
  return parts.length ? h("span", { class: "small" }, parts.join(" · ")) : noData();
}

function bomRows(p) {
  let bom = p.bom ?? p.progress?.bom;
  if (bom && !Array.isArray(bom) && typeof bom === "object" && Array.isArray(bom.items)) bom = bom.items;
  let rows = [];
  if (Array.isArray(bom)) rows = bom.map((r) => ({ ...r, item: r.item ?? r.id }));
  else if (bom && typeof bom === "object") rows = Object.entries(bom).map(([item, v]) => (isNum(v) ? { item, needed: v } : { item, ...v }));
  return rows
    .map((r) => {
      const needed = r.needed ?? r.need ?? r.count;
      const inSupply = r.inSupply ?? r.supply;
      const inBots = r.inBots ?? r.bots;
      const inTransit = r.inTransit ?? r.transit;
      let deficit = r.deficit;
      if (!isNum(deficit) && [needed, inSupply, inBots].every(isNum)) deficit = Math.max(0, needed - inSupply - inBots - (inTransit || 0));
      return { item: r.item, needed, inSupply, inBots, inTransit, deficit, source: r.source };
    })
    .sort((a, b) => (b.deficit || 0) - (a.deficit || 0) || String(a.item).localeCompare(String(b.item)));
}

function sectorState(s) {
  const st = String(s.state || s.status || "pending");
  if (["active", "in_progress", "building", "running"].includes(st)) return "active";
  if (["done", "complete", "completed"].includes(st)) return "done";
  if (["blocked", "failed", "stuck"].includes(st)) return "blocked";
  return "pending";
}

// Build sectors and clear slabs share the map: boxes with pending / active / done / blocked.
function sectorsOf(p) {
  const raw = Array.isArray(p.sectors) ? p.sectors : Array.isArray(p.slabs) ? p.slabs : null;
  return raw ? raw.map((s, i) => ({ ...s, index: s.index ?? i, st: sectorState(s) })) : [];
}

function assignmentsOf(p) {
  const raw = p.assignments;
  if (Array.isArray(raw)) return raw;
  if (raw && typeof raw === "object") return Object.entries(raw).map(([botId, v]) => ({ botId, ...(typeof v === "object" ? v : { role: v }) }));
  return [];
}

/** Top-down map of the sectors; columns drawn to scale when boxes are known. */
function sectorSvg(sectors) {
  const withBox = sectors.filter((s) => s.box && s.box.a && s.box.b);
  const svg = h("svg:svg", { class: "sectors", role: "img", preserveAspectRatio: "xMidYMid meet" });
  const counts = { pending: 0, active: 0, done: 0, blocked: 0 };
  for (const s of sectors) counts[s.st]++;
  svg.setAttribute(
    "aria-label",
    t("proj.sectorsAria", { n: sectors.length, done: counts.done, active: counts.active, pending: counts.pending, blocked: counts.blocked }),
  );
  let rects;
  if (withBox.length === sectors.length && sectors.length) {
    const xs = withBox.flatMap((s) => [s.box.a.x, s.box.b.x]);
    const zs = withBox.flatMap((s) => [s.box.a.z, s.box.b.z]);
    const minX = Math.min(...xs);
    const minZ = Math.min(...zs);
    const W = Math.max(...xs) - minX + 1;
    const H = Math.max(...zs) - minZ + 1;
    svg.setAttribute("viewBox", `0 0 ${W} ${H}`);
    rects = withBox.map((s) => {
      const x = Math.min(s.box.a.x, s.box.b.x) - minX;
      const y = Math.min(s.box.a.z, s.box.b.z) - minZ;
      const w = Math.abs(s.box.b.x - s.box.a.x) + 1;
      const hh = Math.abs(s.box.b.z - s.box.a.z) + 1;
      return { s, x, y, w, h: hh };
    });
  } else {
    const n = Math.max(1, sectors.length);
    svg.setAttribute("viewBox", `0 0 ${n * 10} 40`);
    rects = sectors.map((s, i) => ({ s, x: i * 10, y: 0, w: 10, h: 40 }));
  }
  for (const r of rects) {
    const rect = h("svg:rect", { x: r.x, y: r.y, width: r.w, height: r.h, class: `sec sec-${r.s.st}`, "vector-effect": "non-scaling-stroke" });
    rect.append(h("svg:title", null, sectorTitle(r.s)));
    svg.append(rect);
    const fs = Math.min(r.w, r.h) * 0.3;
    if (fs > 0) {
      svg.append(
        h("svg:text", { x: r.x + r.w / 2, y: r.y + r.h / 2, "font-size": fs, "text-anchor": "middle", "dominant-baseline": "central", class: "sec-label", "aria-hidden": "true" }, String(Number(r.s.index) + 1)),
      );
    }
  }
  return svg;
}

function sectorTitle(s) {
  const parts = [t("proj.sectorN", { n: Number(s.index) + 1 }), tid("sector", s.st)];
  if (s.botId) parts.push(s.botId);
  if (isNum(s.placed) && isNum(s.total)) parts.push(`${s.placed} / ${s.total}`);
  return parts.join(" · ");
}

export function render(root, params, app) {
  const id = params.id;
  const host = h("div", { class: "stack-lg" });
  const eventsHost = h("div");
  let events = [];
  let eventsError = null;

  const p = () => store.projects.get(id);
  const ai = dispatcherSection(id, p);

  function control(verb, { confirm, primary } = {}) {
    return btn(
      t(`proj.act.${verb}`),
      async (e) => {
        const target = e.currentTarget;
        if (confirm && !(await confirmDialog(t(`proj.act.${verb}`), t(confirm, { name: p()?.name || id }), t(`proj.act.${verb}`)))) return;
        busy(target, async () => {
          const res = await api.post(`/api/projects/${enc(id)}/${verb}`);
          if (res && res.id) store.projects.set(id, { ...p(), ...res });
          draw();
        });
      },
      { kind: primary ? "primary" : "ghost", small: false },
    );
  }

  function controls(pr) {
    const st = pr.status || "draft";
    const out = [];
    if (st === "draft" || st === "failed" || st === "done") out.push(control("start", { primary: true }));
    if (st === "running") out.push(control("pause"));
    if (st === "paused") out.push(control("resume", { primary: true }));
    if (st === "running" || st === "paused") out.push(control("stop", { confirm: "proj.stopConfirm" }));
    out.push(
      btn(
        t("proj.act.delete"),
        async (e) => {
          const target = e.currentTarget;
          if (!(await confirmDialog(t("proj.act.delete"), t("proj.deleteConfirm", { name: pr.name || id }), t("proj.act.delete")))) return;
          busy(target, async () => {
            await api.del(`/api/projects/${enc(id)}`);
            store.projects.delete(id);
            app.navigate("/projects");
          });
        },
        { small: false },
      ),
    );
    return out;
  }

  function configSpec(pr) {
    const c = pr.config || {};
    const pairs = [];
    for (const [k, v] of Object.entries(c)) {
      let val;
      if (typeof v === "boolean") val = v ? t("set.yes") : t("set.no");
      else if (refText(v)) val = refText(v);
      else if (v && typeof v === "object" && "x" in v && "z" in v) val = h("span", { class: "num" }, posText(v));
      else if (v && typeof v === "object" && v.a && v.b) val = h("span", { class: "num" }, boxText(v));
      else if (k === "dim") val = dimLabel(v);
      else if (Array.isArray(v)) val = v.length ? h("span", { class: "mono" }, v.map((x) => (x && typeof x === "object" ? posText(x) || JSON.stringify(x) : shortId(x))).join(", ")) : h("span", { class: "dim" }, t("ui.none"));
      else if (v && typeof v === "object") val = h("span", { class: "mono" }, argsSummary(v));
      else val = h("span", { class: "mono" }, String(v));
      pairs.push([t(`pfield.${k}`, null, k), val]);
    }
    pairs.push([t("proj.bots"), pr.bots === "any" || !pr.bots ? t("proj.botsAny") : pr.bots.join(", ")]);
    pairs.push([t("proj.server"), pr.serverId ? serverName(pr.serverId) : noData()]);
    return spec(pairs);
  }

  function progressSpec(pr) {
    const g = projectProgress(pr);
    const pairs = [
      [t("proj.col.status"), h("span", null, statusEl(pr.status), pr.message ? h("span", { class: "dim" }, ` · ${pr.message}`) : null)],
      [t("proj.col.progress"), g.total !== null ? num(g.done, { max: g.total, unit: g.unit }) : noData()],
      ["%", g.fraction !== null ? num(g.fraction * 100, { unit: "%", digits: 1 }) : noData()],
      [t("proj.col.rate"), isNum(g.rate) ? num(g.rate, { unit: g.rateUnit, digits: 1 }) : noData()],
    ];
    if (pr.kind === "build") pairs.push([t("proj.col.eta"), isNum(g.etaSec) ? durationEl(g.etaSec) : pr.status === "done" ? num(0, { unit: t("unit.s") }) : noData()]);
    for (const c of kindCounters(pr)) if (c) pairs.push(c);
    if (isNum(pr.startedAt)) pairs.push([t("proj.startedAt"), timeEl(pr.startedAt)]);
    if (isNum(pr.finishedAt)) pairs.push([t("proj.finishedAt"), timeEl(pr.finishedAt)]);
    return h(
      "div",
      { class: "stack" },
      spec(pairs),
      g.fraction !== null ? h("progress", { max: 1000, value: Math.round(g.fraction * 1000), "aria-label": t("proj.col.progress") }) : null,
      gatherItems(pr),
    );
  }

  /** gather: one row per quota (delivered of count, stock now, target). */
  function gatherItems(pr) {
    const items = pr.kind === "gather" && Array.isArray(pr.progress?.items) ? pr.progress.items : [];
    if (!items.length) return null;
    return table(
      [t("proj.bom.item"), { text: t("proj.g.delivered"), class: "r" }, { text: t("proj.g.stock"), class: "r" }, { text: t("proj.g.target"), class: "r" }],
      items.map((x) =>
        h(
          "tr",
          null,
          h("td", { class: "mono" }, shortId(x.item)),
          h("td", { class: "r" }, num(x.delivered ?? 0, { max: x.count })),
          h("td", { class: "r" }, n(x.stock)),
          h("td", { class: "r" }, n(x.target)),
        ),
      ),
    );
  }

  /** Work the planner could not do (blocked[] {key, reason}) and items only a person can supply (manual[]). */
  function blockedSection(pr) {
    const blocked = Array.isArray(pr.blocked) ? pr.blocked : [];
    const manual = Array.isArray(pr.manual) ? pr.manual.filter((m) => isNum(m.count) && m.count > 0) : [];
    if (!blocked.length && !manual.length) return h("p", { class: "empty" }, t("proj.noBlocked"));
    return h(
      "div",
      { class: "stack" },
      manual.length
        ? h(
            "div",
            { class: "error-box", role: "status" },
            t("proj.manualNote", { n: manual.length, list: manual.map((m) => `${shortId(m.item)} ${m.count}`).join(", ") }),
          )
        : null,
      blocked.length
        ? table(
            [t("proj.bl.key"), t("proj.bl.reason")],
            blocked.map((b) => h("tr", null, h("td", { class: "mono" }, b.key || ""), h("td", { class: "wrap-cell" }, tid("reason", b.reason) || noData()))),
          )
        : null,
    );
  }

  /** build: the production plan behind the deficits (haul / mine / craft / smelt). */
  function productionSection(pr) {
    const list = Array.isArray(pr.production) ? pr.production : Array.isArray(pr.actions) ? pr.actions : [];
    if (!list.length) return null;
    return h(
      "div",
      { class: "stack-sm" },
      h("p", { class: "label" }, t("proj.production", { n: list.length })),
      table(
        [t("proj.pr.kind"), t("proj.bom.item"), { text: t("proj.pr.count"), class: "r" }, t("proj.pr.ready")],
        list.map((a) =>
          h(
            "tr",
            null,
            h("td", null, tid("source", a.kind)),
            h("td", { class: "mono" }, shortId(a.item)),
            h("td", { class: "r" }, n(a.count)),
            h("td", null, a.ready ? h("span", { class: "st-ok" }, t("set.yes")) : h("span", { class: "dim" }, t("proj.pr.waits"))),
          ),
        ),
      ),
    );
  }

  function bomSection(pr) {
    const rows = bomRows(pr);
    if (!rows.length) {
      return h("p", { class: "empty" }, pr.kind === "build" ? t("proj.bomNone") : t("proj.bomNotUsed"));
    }
    const cell = (v, opts) => h("td", { class: "r" }, isNum(v) ? num(v, opts) : noData());
    const trs = rows.map((r) =>
      h(
        "tr",
        null,
        h("td", { class: "mono" }, shortId(r.item)),
        cell(r.needed),
        cell(r.inSupply),
        cell(r.inBots),
        cell(r.inTransit),
        cell(r.deficit, { bad: r.deficit > 0 && r.source === "manual", warn: r.deficit > 0 && r.source !== "manual" }),
        h("td", null, r.deficit > 0 ? tid("source", r.source || "unknown") : h("span", { class: "st-ok" }, t("proj.enough"))),
      ),
    );
    return h(
      "div",
      { class: "stack" },
      table(
        [
          t("proj.bom.item"),
          { text: t("proj.bom.needed"), class: "r" },
          { text: t("proj.bom.supply"), class: "r" },
          { text: t("proj.bom.bots"), class: "r" },
          { text: t("proj.bom.transit"), class: "r" },
          { text: t("proj.bom.deficit"), class: "r" },
          t("proj.bom.source"),
        ],
        trs,
      ),
    );
  }

  function sectorsSection(pr) {
    const sectors = sectorsOf(pr);
    if (!sectors.length) return h("p", { class: "empty" }, t("proj.sectorsNone"));
    const legend = h(
      "p",
      { class: "small row" },
      ["active", "done", "pending", "blocked"].map((st) => h("span", null, h("span", { class: `swatch sec-${st}`, "aria-hidden": "true" }), tid("sector", st))),
    );
    const rows = sectors.map((s) =>
      h(
        "tr",
        null,
        h("td", { class: "num" }, String(Number(s.index) + 1)),
        h("td", null, h("span", { class: s.st === "done" ? "st-ok" : s.st === "active" ? "st-accent" : s.st === "blocked" ? "st-bad" : "dim" }, tid("sector", s.st))),
        h("td", null, s.botId ? h("a", { href: `#/bots/${enc(s.botId)}` }, store.bots.get(s.botId)?.username || s.botId) : h("span", { class: "dim" }, t("proj.noBot"))),
        h("td", { class: "r" }, isNum(s.placed) && isNum(s.total) ? num(s.placed, { max: s.total, unit: t("unit.blocks") }) : noData()),
        h("td", { class: "num" }, s.box ? boxText(s.box) : noData()),
      ),
    );
    return h(
      "div",
      { class: "stack" },
      legend,
      sectorSvg(sectors),
      table([t("proj.sec.n"), t("proj.sec.state"), t("proj.sec.bot"), { text: t("proj.sec.blocks"), class: "r" }, t("proj.sec.box")], rows),
    );
  }

  function assignmentsSection(pr) {
    const list = assignmentsOf(pr);
    if (!list.length) return h("p", { class: "empty" }, pr.status === "running" ? t("proj.noAssignmentsRunning") : t("proj.noAssignments"));
    const rows = list.map((a) => {
      const wi = a.workItem ?? a.work ?? a.item;
      let wiEl;
      if (!wi) wiEl = h("span", { class: "dim" }, t("proj.waiting"));
      else if (typeof wi === "string") wiEl = wi;
      else wiEl = h("span", null, wi.label || tid("work", wi.kind), wi.kind && wi.label ? h("span", { class: "dim" }, ` (${tid("work", wi.kind)})`) : null, wi.location ? h("span", { class: "num dim" }, ` ${posText(wi.location.pos || wi.location) || ""}`) : null);
      return h(
        "tr",
        null,
        h("td", null, h("a", { href: `#/bots/${enc(a.botId)}` }, store.bots.get(a.botId)?.username || a.botId)),
        h("td", null, a.role ? tid("role", a.role) : h("span", { class: "dim" }, t("bots.noRole"))),
        h("td", { class: "wrap-cell" }, wiEl),
        h("td", null, isNum(a.since) ? timeEl(a.since) : noData()),
      );
    });
    return table([t("proj.as.bot"), t("proj.as.role"), t("proj.as.work"), t("proj.as.since")], rows);
  }

  async function loadEvents() {
    try {
      const list = listOf(await api.get("/api/events", { project: id, limit: 200 }), "events");
      events = list.filter((ev) => ev.projectId === id || ev.project === id || ev.data?.projectId === id);
      eventsError = null;
    } catch (e) {
      eventsError = e;
    }
    renderEvents();
  }

  function renderEvents() {
    if (eventsError) return mount(eventsHost, errorBox(eventsError, "ui.eventsFailed"));
    if (!events.length) return mount(eventsHost, h("p", { class: "empty" }, t("events.emptyProject")));
    events.sort((a, b) => (b.time || 0) - (a.time || 0));
    mount(
      eventsHost,
      table(
        [t("events.col.time"), t("events.col.level"), t("events.col.bot"), t("events.col.message")],
        events.slice(0, 100).map((ev) =>
          h("tr", null, h("td", null, timeEl(ev.time)), h("td", null, levelEl(ev.level)), h("td", null, ev.botId || ""), h("td", { class: "wrap-cell" }, ev.message || tid("event", ev.kind))),
        ),
      ),
    );
  }

  const sec = (key, ...children) => h("section", { class: "section" }, h("div", { class: "head" }, h("h2", { class: "h2" }, t(key))), children);

  const headHost = h("div", { class: "stack-sm" });
  const dataHost = h("div", { class: "stack-lg" });
  let headKey = null;

  function draw() {
    const pr = p();
    if (!pr) {
      headKey = null;
      mount(host, store.loaded ? h("div", { class: "stack" }, errorBox({ code: "not_found", message: id }), h("a", { href: "#/projects" }, t("proj.back"))) : empty(t("ui.loading")));
      return;
    }
    // The dispatcher sits outside dataHost: the once-per-second redraw must not move its buttons.
    if (!host.contains(headHost)) mount(host, headHost, ai.el, dataHost);
    // The control row is rebuilt only when the status changes, so a
    // focused button survives the once-per-second progress updates.
    const key = `${pr.status}|${pr.name}`;
    if (key !== headKey) {
      headKey = key;
      mount(
        headHost,
        h("p", null, h("a", { href: "#/projects" }, t("proj.back"))),
        h("h1", { class: "h1" }, pr.name || pr.id),
        h("p", { class: "small" }, tid("kind", pr.kind), " · ", statusEl(pr.status), " · ", h("span", { class: "mono" }, pr.id)),
        h("div", { class: "row" }, controls(pr)),
      );
    }
    const usesBom = pr.kind === "build" || pr.kind === "gather";
    const usesMap = pr.kind === "build" || pr.kind === "clear";
    mount(
      dataHost,
      h("div", { class: "cols" }, sec("proj.progress", progressSpec(pr)), sec("proj.config", configSpec(pr))),
      sec("proj.assignments", assignmentsSection(pr)),
      sec("proj.blocked", blockedSection(pr)),
      usesMap ? sec(pr.kind === "clear" ? "proj.slabs" : "proj.sectors", sectorsSection(pr)) : null,
      usesBom ? sec("proj.bom", pr.kind === "build" ? bomSection(pr) : null, productionSection(pr) || (pr.kind === "gather" ? h("p", { class: "empty" }, t("proj.noProduction")) : null)) : null,
      sec("proj.events", eventsHost),
    );
  }

  // /api/state may carry project summaries only; BOM, sectors and
  // assignments come from the project resource itself.
  let fetchError = null;
  async function fetchFull() {
    try {
      const full = await api.get(`/api/projects/${enc(id)}`);
      if (full && typeof full === "object") store.projects.set(id, { ...(store.projects.get(id) || {}), ...(full.project || full) });
      fetchError = null;
    } catch (e) {
      fetchError = e;
    }
    draw();
    if (fetchError && fetchError.status !== 404) dataHost.prepend(errorBox(fetchError, "proj.loadFailed"));
  }

  mount(root, host);
  draw();
  fetchFull();
  loadEvents();
  const redraw = throttle(draw, 1000);
  const refetch = throttle(fetchFull, 5000);
  return {
    update(type, data) {
      ai.update(type, data);
      if (type === "snapshot") refetch();
      else if (type === "project" && (data?.id === id || data?.project?.id === id)) {
        redraw();
        refetch();
      }
      else if (type === "event" && (data?.projectId === id || data?.data?.projectId === id)) {
        events.unshift(data);
        renderEvents();
      }
    },
  };
}
