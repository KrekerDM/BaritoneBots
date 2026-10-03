// One project: progress, BOM, sector map, assignments, controls.

import { api, enc, listOf } from "../api.js";
import { h, mount, btn, busy, confirmDialog, errorBox, empty, table, spec, throttle } from "../dom.js";
import { t, tid } from "../i18n.js";
import { store, serverName } from "../store.js";
import { num, noData, isNum, durationEl, posText, dimLabel, shortId, timeEl, levelEl, argsSummary, boxText } from "../format.js";
import { statusEl, withProjectsApi } from "./projects.js";

/** Normalised progress numbers of any project kind. */
export function projectProgress(p) {
  const pr = p.progress || {};
  const total = isNum(pr.total) ? pr.total : null;
  const done = isNum(pr.placed) ? pr.placed : isNum(pr.done) ? pr.done : isNum(pr.correct) ? pr.correct : null;
  let fraction = null;
  if (total !== null && total > 0 && done !== null) fraction = done / total;
  else if (isNum(pr.percent)) fraction = pr.percent / 100;
  else if (total === 0) fraction = 1;
  return {
    total,
    done: done ?? (total !== null ? 0 : null),
    fraction,
    unit: p.kind === "build" || p.kind === "clear" ? t("unit.blocks") : t("unit.items"),
    rate: pr.blocksPerMin ?? pr.ratePerMin ?? pr.rate,
    etaSec: pr.etaSec ?? pr.eta,
    remaining: pr.remaining,
  };
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

function sectorsOf(p) {
  const raw = p.sectors ?? p.progress?.sectors;
  return Array.isArray(raw) ? raw.map((s, i) => ({ ...s, index: s.index ?? s.id ?? i, st: sectorState(s) })) : [];
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
    const fs = Math.min(r.w, r.h) * 0.45;
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
  return withProjectsApi(root, () => renderFull(root, params, app));
}

function renderFull(root, params, app) {
  const id = params.id;
  const host = h("div", { class: "stack-lg" });
  const eventsHost = h("div");
  let events = [];
  let eventsError = null;

  const p = () => store.projects.get(id);

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
      if (v && typeof v === "object" && "x" in v && "z" in v) val = h("span", { class: "num" }, posText(v));
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
      [t("proj.col.rate"), isNum(g.rate) ? num(g.rate, { unit: t("unit.perMin"), digits: 1 }) : noData()],
      [t("proj.col.eta"), isNum(g.etaSec) ? durationEl(g.etaSec) : noData()],
    ];
    if (pr.progress && isNum(pr.progress.unloaded)) pairs.push([t("proj.unloaded"), num(pr.progress.unloaded, { unit: t("unit.blocks") })]);
    if (pr.progress && isNum(pr.progress.wrong)) pairs.push([t("proj.wrong"), num(pr.progress.wrong, { unit: t("unit.blocks"), warn: pr.progress.wrong > 0 })]);
    if (isNum(pr.updatedAt)) pairs.push([t("bot.f.updated"), timeEl(pr.updatedAt)]);
    return h("div", { class: "stack" }, spec(pairs), g.fraction !== null ? h("progress", { max: 1000, value: Math.round(g.fraction * 1000), "aria-label": t("proj.col.progress") }) : null);
  }

  function bomSection(pr) {
    const rows = bomRows(pr);
    if (!rows.length) {
      return h("p", { class: "empty" }, pr.kind === "build" ? t("proj.bomNone") : t("proj.bomNotUsed"));
    }
    const manual = rows.filter((r) => r.source === "manual" && r.deficit > 0);
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
      manual.length
        ? h(
            "div",
            { class: "error-box", role: "status" },
            t("proj.manualNote", { n: manual.length, list: manual.map((r) => `${shortId(r.item)} ${r.deficit}`).join(", ") }),
          )
        : null,
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
    if (!sectors.length) return h("p", { class: "empty" }, pr.kind === "build" ? t("proj.sectorsNone") : t("proj.sectorsNotUsed"));
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
    if (!host.contains(headHost)) mount(host, headHost, dataHost);
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
    mount(
      dataHost,
      h("div", { class: "cols" }, sec("proj.progress", progressSpec(pr)), sec("proj.config", configSpec(pr))),
      sec("proj.bom", bomSection(pr)),
      sec("proj.sectors", sectorsSection(pr)),
      sec("proj.assignments", assignmentsSection(pr)),
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
