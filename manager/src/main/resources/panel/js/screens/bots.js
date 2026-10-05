// Bots: one row per configured bot, live status, row actions, batch task.

import { api, enc } from "../api.js";
import { h, mount, btn, busy, toast, confirmDialog, errorBox, throttle, empty } from "../dom.js";
import { t } from "../i18n.js";
import { store, botList, loadCatalog, serverName } from "../store.js";
import { processEl, botStateEl, num } from "../format.js";
import { taskForm } from "../forms.js";
import * as bv from "../botview.js";
import { commandBox } from "../ai.js";
import { setupPanel } from "../setup.js";

const RUNNING = new Set(["installing", "starting", "linked", "online", "stopping"]);
const STATUS_COLS = 10;

export function render(root, params, app) {
  const selected = new Set();
  const rows = new Map(); // id -> {tr, check, zone: [td], actions: {toggle, restart}}

  const summary = h("p", { class: "small" });
  const tbody = h("tbody");
  const allBox = h("input", { type: "checkbox", "aria-label": t("bots.selectAll") });
  const selCount = h("span", { class: "small", "aria-live": "polite" });
  const formHost = h("div");

  allBox.addEventListener("change", () => {
    for (const b of botList()) {
      if (allBox.checked) selected.add(b.id);
      else selected.delete(b.id);
    }
    for (const [id, r] of rows) r.check.checked = selected.has(id);
    updateSelection();
  });

  const startAll = btn(t("bots.startAll"), (e) =>
    busy(e.currentTarget, async () => {
      await api.post("/api/bots/start-all");
      toast("info", t("bots.startAllSent"));
    }),
  );
  const stopAll = btn(t("bots.stopAll"), async (e) => {
    const target = e.currentTarget;
    if (!(await confirmDialog(t("bots.stopAll"), t("bots.stopAllConfirm"), t("bots.stopAll")))) return;
    busy(target, async () => {
      await api.post("/api/bots/stop-all");
      toast("info", t("bots.stopAllSent"));
    });
  });

  const headers = [
    h("th", { scope: "col" }, allBox),
    h("th", { scope: "col" }, t("bots.col.bot")),
    h("th", { scope: "col" }, t("bots.col.process")),
    h("th", { scope: "col" }, t("bots.col.state")),
    h("th", { scope: "col" }, t("bots.col.task")),
    h("th", { scope: "col" }, t("bots.col.hp")),
    h("th", { scope: "col" }, t("bots.col.food")),
    h("th", { scope: "col" }, t("bots.col.pos")),
    h("th", { scope: "col" }, t("bots.col.slots")),
    h("th", { scope: "col" }, t("bots.col.ram")),
    h("th", { scope: "col" }, t("bots.col.cpu")),
    h("th", { scope: "col" }, t("bots.col.uptime")),
    h("th", { scope: "col" }, t("bots.col.role")),
    h("th", { scope: "col" }, t("bots.col.actions")),
  ];
  const tableEl = h(
    "div",
    { class: "table-wrap" },
    h("table", { class: "table bots-table" }, h("caption", { class: "sr-only" }, t("bots.title")), h("thead", null, h("tr", null, headers)), tbody),
  );
  const listHost = h("div");

  function updateSelection() {
    selCount.textContent = t("bots.selected", { n: selected.size });
    const n = botList().length;
    allBox.checked = n > 0 && selected.size === n;
    allBox.indeterminate = selected.size > 0 && selected.size < n;
    for (const [id, r] of rows) r.tr.setAttribute("aria-selected", String(selected.has(id)));
  }

  function action(id, verb) {
    return (e) =>
      busy(e.currentTarget, async () => {
        await api.post(`/api/bots/${enc(id)}/${verb}`);
      });
  }

  function zoneCells(bot) {
    if (!bot.status) {
      const reason = t(`bots.noStatus.${bot.process}`, null, t("bots.noStatus"));
      return [h("td", { colspan: STATUS_COLS, class: "nodata" }, reason)];
    }
    return [
      h("td", null, botStateEl(bot.status.state)),
      h("td", { class: "wrap-cell" }, bv.taskEl(bot)),
      h("td", null, bv.hpEl(bot)),
      h("td", null, bv.foodEl(bot)),
      h("td", null, bv.posEl(bot)),
      h("td", null, bv.slotsEl(bot)),
      h("td", null, bv.ramEl(bot)),
      h("td", null, bv.cpuEl(bot)),
      h("td", null, bv.uptimeEl(bot)),
      h("td", null, bv.roleEl(bot)),
    ];
  }

  function buildRow(bot) {
    const check = h("input", { type: "checkbox", "aria-label": t("bots.selectOne", { bot: bot.username || bot.id }), checked: selected.has(bot.id) });
    check.addEventListener("change", () => {
      if (check.checked) selected.add(bot.id);
      else selected.delete(bot.id);
      updateSelection();
    });
    // Two small buttons keep the column narrow: start or stop (whichever applies) and restart;
    // kill / connect / disconnect stay on the bot page.
    const actions = {
      toggle: btn(t("bots.start"), (e) => action(bot.id, RUNNING.has(store.bots.get(bot.id)?.process) ? "stop" : "start")(e)),
      restart: btn(t("bots.restartShort"), action(bot.id, "restart"), { title: t("bots.restart") }),
    };
    const nameCell = h("td");
    const procCell = h("td");
    const actCell = h("td", { class: "actions" }, h("div", { class: "row-sm row-nowrap" }, actions.toggle, actions.restart));
    const tr = h("tr", null, h("td", null, check), nameCell, procCell, actCell);
    const r = { tr, check, nameCell, procCell, actCell, actions, zone: [], hasStatus: null };
    rows.set(bot.id, r);
    fillRow(bot, r);
    return tr;
  }

  function fillRow(bot, r) {
    r.nameCell.replaceChildren(
      h("a", { href: `#/bots/${enc(bot.id)}` }, bot.username || bot.id),
      h("div", { class: "small dim" }, bot.id, bot.serverId ? ` · ${serverName(bot.serverId)}` : "", bot.enabled === false ? ` · ${t("bots.disabled")}` : ""),
    );
    mount(r.procCell, processEl(bot.process), bot.processInfo?.restarts ? h("div", { class: "small dim" }, t("bots.restarts", { n: bot.processInfo.restarts })) : null);
    for (const td of r.zone) td.remove();
    r.zone = zoneCells(bot);
    for (const td of r.zone) r.tr.insertBefore(td, r.actCell);
    const running = RUNNING.has(bot.process);
    r.actions.toggle.textContent = running ? t("bots.stop") : t("bots.start");
    r.actions.toggle.disabled = bot.process === "installing" || bot.process === "stopping";
    r.actions.restart.disabled = bot.process === "installing";
  }

  function renderList() {
    const bots = botList();
    if (!store.loaded) {
      mount(listHost, empty(t("ui.loading")));
      return;
    }
    if (!bots.length) {
      rows.clear();
      mount(listHost, h("div", { class: "stack" }, empty(t("bots.empty")), h("p", { class: "small" }, h("a", { href: "#/settings/bots" }, t("bots.addInSettings")))));
      summary.textContent = t("bots.summary", { n: 0, online: 0, busy: 0 });
      return;
    }
    // Rows are updated in place so a focused button keeps focus while
    // status messages arrive every second.
    const ids = new Set(bots.map((b) => b.id));
    for (const [id, r] of rows) {
      if (!ids.has(id)) {
        r.tr.remove();
        rows.delete(id);
        selected.delete(id);
      }
    }
    let prev = null;
    for (const b of bots) {
      const r = rows.get(b.id);
      let tr;
      if (r) {
        fillRow(b, r);
        tr = r.tr;
      } else tr = buildRow(b);
      const want = prev ? prev.nextSibling : tbody.firstChild;
      if (want !== tr) tbody.insertBefore(tr, want);
      prev = tr;
    }
    if (!listHost.contains(tableEl)) mount(listHost, tableEl);
    const online = bots.filter((b) => b.status?.state === "online").length;
    const busyN = bots.filter((b) => b.status?.task || b.queue?.current).length;
    summary.replaceChildren(
      num(bots.length, { unit: t("unit.bots") }),
      " · ",
      num(online, { unit: t("bots.onlineUnit") }),
      " · ",
      num(busyN, { unit: t("bots.busyUnit") }),
    );
    updateSelection();
  }

  const refresh = throttle(renderList, 500);
  const ai = commandBox({ botIds: () => [...selected], hint: t("ai.hintSelected") });
  const setup = setupPanel();

  async function mountForm() {
    try {
      const catalog = await loadCatalog();
      mount(
        formHost,
        taskForm({
          catalog,
          withMode: true,
          primary: true,
          submitLabel: t("bots.sendSelected"),
          onSubmit: async (task, mode) => {
            if (!selected.size) {
              const e = new Error(t("bots.noSelection"));
              e.code = "no_selection";
              throw e;
            }
            const res = await api.post("/api/tasks/batch", { botIds: [...selected], task, mode });
            // {results: {botId: {ok, taskId} | {ok: false, error, message}}}
            const results = Object.entries(res?.results || {});
            const failed = results.filter(([, r]) => !r.ok);
            toast("info", t("bots.batchSent", { n: results.length - failed.length }));
            if (failed.length) {
              toast("error", failed.map(([id, r]) => `${store.bots.get(id)?.username || id}: ${r.error} ${r.message || ""}`).join("; "));
            }
          },
        }),
      );
    } catch (e) {
      mount(formHost, errorBox(e, "ui.catalogFailed"));
    }
  }

  mount(
    root,
    h(
      "div",
      { class: "stack-lg" },
      h(
        "div",
        { class: "stack" },
        h("div", { class: "head" }, h("h1", { class: "h1" }, t("bots.title")), h("div", { class: "row-sm" }, startAll, stopAll)),
        setup.el,
        summary,
        listHost,
      ),
      ai.el,
      h(
        "section",
        { class: "section" },
        h("div", { class: "head" }, h("h2", { class: "h2" }, t("bots.batchTitle")), selCount),
        h("p", { class: "small" }, t("bots.batchHint")),
        formHost,
      ),
    ),
  );
  renderList();
  mountForm();

  return {
    update(type) {
      ai.update(type);
      setup.update();
      if (type === "bot" || type === "process" || type === "queue" || type === "snapshot") refresh();
    },
  };
}
