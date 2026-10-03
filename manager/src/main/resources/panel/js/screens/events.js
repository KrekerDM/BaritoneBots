// Event log: GET /api/events with level / bot filters, then live SSE "event"
// entries that pass the same filters. Newest first.

import { api, listOf } from "../api.js";
import { h, mount, btn, busy, errorBox, empty, table, field, select, throttle } from "../dom.js";
import { t, tid, has } from "../i18n.js";
import { store, botList } from "../store.js";
import { num, timeEl, levelEl, argsSummary } from "../format.js";

const LIMITS = [100, 200, 500, 1000, 5000];
const RANK = { info: 0, warn: 1, error: 2 };

/** The manager stores the text in its own language; key + args re-render it in the panel's. */
export function eventText(ev) {
  if (ev && ev.key && has(ev.key)) return t(ev.key, ev.args || {});
  return (ev && ev.message) || "";
}

export function render(root, params, app) {
  const levelSel = select(
    [
      { value: "", label: t("events.level.all") },
      { value: "warn", label: t("events.level.warn") },
      { value: "error", label: t("events.level.error") },
    ],
    params.level || "",
  );
  const botSel = select([{ value: "", label: t("events.bot.all") }, ...botList().map((b) => ({ value: b.id, label: b.username || b.id }))], params.bot || "");
  const limitSel = select(LIMITS.map((n) => ({ value: String(n), label: String(n) })), params.limit || "200");
  const liveBox = h("input", { type: "checkbox", checked: true });
  const countEl = h("span", { class: "field-hint", "aria-live": "polite" });
  const listHost = h("div");
  let events = [];
  let error = null;
  let pending = 0;

  const filters = () => ({ level: levelSel.value || null, bot: botSel.value || null, limit: Number(limitSel.value) || 200 });

  function matches(ev) {
    const f = filters();
    if (f.bot && String(ev.botId || "").toLowerCase() !== f.bot.toLowerCase()) return false;
    if (f.level && (RANK[ev.level] ?? 0) < RANK[f.level]) return false;
    return true;
  }

  async function load(button) {
    const f = filters();
    await busy(button, async () => {
      try {
        events = listOf(await api.get("/api/events", { limit: f.limit, bot: f.bot, level: f.level }), "events");
        // The endpoint returns oldest first; the screen shows newest first.
        events.sort((a, b) => (b.time || 0) - (a.time || 0) || (b.seq || 0) - (a.seq || 0));
        error = null;
      } catch (e) {
        error = e;
      }
      pending = 0;
      paint();
    });
  }

  function botCell(id) {
    if (!id) return h("span", { class: "dim" }, t("events.manager"));
    const b = store.bots.get(id);
    return h("a", { href: `#/bots/${encodeURIComponent(id)}` }, b ? b.username || id : id);
  }

  function paint() {
    mount(
      countEl,
      num(events.length, { unit: t("events.unit") }),
      pending ? h("span", null, " ", t("events.pending", { n: pending })) : null,
    );
    if (error) {
      mount(listHost, errorBox(error, "ui.eventsFailed"));
      return;
    }
    if (!events.length) {
      mount(listHost, empty(t("events.empty")));
      return;
    }
    mount(
      listHost,
      table(
        [t("events.col.time"), t("events.col.level"), t("events.col.bot"), t("events.col.source"), t("events.col.kind"), t("events.col.message")],
        events.map((ev) =>
          h(
            "tr",
            null,
            h("td", null, timeEl(ev.time)),
            h("td", null, levelEl(ev.level)),
            h("td", null, botCell(ev.botId)),
            h("td", { class: "dim" }, tid("source", ev.source)),
            h("td", { class: "mono small" }, ev.kind || ""),
            h("td", { class: "wrap-cell" }, eventText(ev), ev.data ? h("div", { class: "small mono dim" }, argsSummary(ev.data)) : null),
          ),
        ),
        { caption: t("events.title") },
      ),
    );
  }

  const repaint = throttle(paint, 500);
  const reloadBtn = btn(t("events.reload"), (e) => load(e.currentTarget), { kind: "primary", small: false });
  for (const sel of [levelSel, botSel, limitSel]) sel.addEventListener("change", () => load(reloadBtn));
  liveBox.addEventListener("change", () => {
    if (liveBox.checked && pending) load(reloadBtn);
  });

  mount(
    root,
    h(
      "div",
      { class: "stack" },
      h("h1", { class: "h1" }, t("events.title")),
      h("p", { class: "prose" }, t("events.text")),
      h(
        "div",
        { class: "fields" },
        field(t("events.f.level"), levelSel, { hint: t("events.f.levelHint") }),
        field(t("events.f.bot"), botSel),
        field(t("events.f.limit"), limitSel, { hint: t("events.f.limitHint") }),
        h("div", { class: "field" }, h("label", { class: "check" }, liveBox, h("span", null, t("events.live"))), h("span", { class: "field-hint" }, t("events.liveHint"))),
      ),
      h("div", { class: "row" }, reloadBtn, countEl),
      listHost,
    ),
  );
  load(reloadBtn);

  return {
    update(type, data) {
      if (type === "snapshot") {
        load(reloadBtn);
        return;
      }
      if (type !== "event" || !data || typeof data !== "object" || !matches(data)) return;
      if (!liveBox.checked) {
        pending++;
        repaint();
        return;
      }
      events.unshift(data);
      const limit = filters().limit;
      if (events.length > limit) events.length = limit;
      repaint();
    },
  };
}
