// One bot: status, queue, task form, chat, inventory, launcher log,
// events, Microsoft login and companion plugin actions.

import { api, enc, listOf } from "../api.js";
import { h, mount, btn, busy, toast, confirmDialog, errorBox, throttle, empty, spec, table, field } from "../dom.js";
import { t, tid } from "../i18n.js";
import { store, loadCatalog, serverName, behaviourOf } from "../store.js";
import { processEl, botStateEl, num, noData, isNum, timeEl, levelEl, argsSummary, shortId, durationEl } from "../format.js";
import { taskForm, templateTitle } from "../forms.js";
import * as bv from "../botview.js";
import { eventText } from "./events.js";

const LOG_LINES = 200;
const LOG_KEEP = 600;
const EVENTS_SHOWN = 100;
const ARMOR_SLOTS = ["head", "chest", "legs", "feet"];

export function render(root, params) {
  const id = params.id;
  const bot = () => store.bots.get(id);

  if (store.loaded && !bot()) {
    mount(root, h("div", { class: "stack" }, h("h1", { class: "h1" }, t("bot.notFound", { id })), h("a", { href: "#/bots" }, t("bot.back"))));
    return null;
  }

  const title = h("h1", { class: "h1" });
  const subtitle = h("p", { class: "small" });
  const statusHost = h("div");
  const queueHost = h("div");
  const formHost = h("div");
  const invHost = h("div");
  const logPre = h("pre", { class: "log", tabindex: "0", "aria-label": t("bot.log") });
  const logState = h("p", { class: "small dim" });
  const eventsHost = h("div");
  const msHost = h("div", { class: "stack" });
  let events = [];
  let eventsError = null;
  let logLines = [];

  // ----------------------------------------------------------------
  // Header and process actions
  // ----------------------------------------------------------------
  const act = (verb, confirmKey) => async (e) => {
    const target = e.currentTarget;
    if (confirmKey && !(await confirmDialog(t(`bot.act.${verb}`), t(confirmKey), t(`bot.act.${verb}`)))) return;
    busy(target, () => api.post(`/api/bots/${enc(id)}/${verb}`));
  };
  const actions = h(
    "div",
    { class: "row-sm" },
    btn(t("bot.act.start"), act("start")),
    btn(t("bot.act.stop"), act("stop")),
    btn(t("bot.act.restart"), act("restart")),
    btn(t("bot.act.kill"), act("kill", "bot.killConfirm")),
    btn(t("bot.act.connect"), act("connect")),
    btn(t("bot.act.disconnect"), act("disconnect")),
  );

  function renderHeader() {
    const b = bot();
    if (!b) return;
    title.textContent = b.username || b.id;
    mount(
      subtitle,
      h("span", { class: "mono" }, b.id),
      b.serverId ? ` · ${serverName(b.serverId)}` : "",
      ` · ${tid("account", b.account?.type || "offline")}`,
      " · ",
      processEl(b.process),
      b.status ? [" · ", botStateEl(b.status.state)] : null,
    );
  }

  // ----------------------------------------------------------------
  // Status
  // ----------------------------------------------------------------
  function renderStatus() {
    const b = bot();
    if (!b) return;
    const s = b.status;
    const pi = b.processInfo || {};
    const procPairs = [
      [t("bot.f.process"), h("span", null, processEl(b.process), isNum(pi.pid) ? h("span", { class: "num dim" }, ` pid ${pi.pid}`) : null)],
      [t("bot.f.restarts"), isNum(pi.restarts) ? num(pi.restarts) : undefined],
      [t("bot.f.exitCode"), isNum(pi.exitCode) ? num(pi.exitCode) : undefined],
      [t("bot.f.role"), bv.roleEl(b)],
      [t("bot.f.project"), b.projectId ? h("a", { href: `#/projects/${enc(b.projectId)}` }, store.projects.get(b.projectId)?.name || b.projectId) : undefined],
    ];
    if (!s) {
      mount(
        statusHost,
        spec(procPairs),
        h("p", { class: "empty" }, t(`bots.noStatus.${b.process}`, null, t("bots.noStatus"))),
      );
      return;
    }
    const baritone = s.baritone || {};
    const beh = behaviourOf(b);
    mount(
      statusHost,
      spec([
        ...procPairs,
        [t("bot.f.state"), h("span", null, botStateEl(s.state), s.server ? h("span", { class: "mono dim" }, ` ${s.server}`) : null)],
        [t("bot.f.task"), bv.taskEl(b)],
        [
          t("bot.f.baritone"),
          h(
            "span",
            null,
            baritone.process ? h("span", { class: "mono" }, baritone.process) : h("span", { class: "dim" }, t("bot.baritoneIdle")),
            baritone.pathing ? ` · ${t("bot.pathing")}` : "",
            baritone.goal ? h("span", { class: "dim" }, ` · ${baritone.goal}`) : null,
            isNum(baritone.eta) ? [" · ETA ", durationEl(baritone.eta)] : null,
          ),
        ],
        [t("bot.f.hp"), bv.hpEl(b)],
        [t("bot.f.food"), h("span", null, bv.foodEl(b), isNum(s.saturation) ? h("span", { class: "num-threshold" }, t("bot.saturation", { v: s.saturation.toFixed(1) })) : null)],
        [
          t("bot.f.defense"),
          beh.defense
            ? h(
                "span",
                null,
                tid("defense", beh.defense.mode),
                isNum(beh.defense.radius) ? [` · ${t("bot.radius")} `, num(beh.defense.radius, { unit: t("unit.blocks") })] : null,
              )
            : noData(),
        ],
        [t("bot.f.xp"), num(s.xpLevel)],
        [t("bot.f.pos"), bv.posEl(b)],
        [t("bot.f.look"), isNum(s.yaw) ? h("span", { class: "num" }, `yaw ${s.yaw.toFixed(0)} · pitch ${(s.pitch ?? 0).toFixed(0)}`) : noData()],
        [t("bot.f.slots"), bv.slotsEl(b)],
        [t("bot.f.mainHand"), s.mainHand ? h("span", { class: "mono" }, shortId(s.mainHand)) : h("span", { class: "dim" }, t("bot.emptyHand"))],
        [t("bot.f.offhand"), s.offhand ? h("span", { class: "mono" }, shortId(s.offhand)) : h("span", { class: "dim" }, t("bot.emptyHand"))],
        [t("bot.f.armor"), armorEl(s.armor)],
        [t("bot.f.ram"), bv.ramEl(b)],
        [t("bot.f.cpu"), bv.cpuEl(b)],
        [t("bot.f.fps"), bv.fpsEl(b)],
        [t("bot.f.ping"), bv.pingEl(b)],
        [t("bot.f.uptime"), bv.uptimeEl(b)],
        [t("bot.f.updated"), timeEl(s.time)],
      ]),
    );
  }

  function armorEl(armor) {
    if (!Array.isArray(armor)) return noData();
    return h(
      "span",
      { class: "stack-sm" },
      ARMOR_SLOTS.map((slot, i) =>
        h("div", null, h("span", { class: "dim" }, `${t(`armor.${slot}`)}: `), armor[i] ? h("span", { class: "mono" }, shortId(armor[i])) : h("span", { class: "dim" }, t("bot.emptySlot"))),
      ),
    );
  }

  // ----------------------------------------------------------------
  // Queue
  // ----------------------------------------------------------------
  function renderQueue() {
    const b = bot();
    if (!b) return;
    const q = b.queue || { current: null, queued: [] };
    const cur = q.current;
    const queued = q.queued || [];

    const curEl = cur
      ? h(
          "div",
          { class: "raised box stack-sm" },
          h("div", { class: "row spread" }, h("span", { class: "strong" }, templateTitle(cur), cur.label ? h("span", { class: "dim" }, ` (${cur.label})`) : null), btn(t("bot.cancelCurrent"), (e) => busy(e.currentTarget, () => api.post(`/api/bots/${enc(id)}/cancel`)))),
          h("p", { class: "small mono" }, argsSummary(cur.args) || t("bot.noArgs")),
          h("p", { class: "small dim" }, [cur.origin ? tid("origin", originKind(cur.origin)) : null, cur.timeoutSec ? t("bot.timeout", { s: cur.timeoutSec }) : null, cur.id ? `id ${cur.id}` : null].filter(Boolean).join(" · ")),
        )
      : h("p", { class: "empty" }, t("bot.noCurrent"));

    const items = queued.map((task, i) => {
      const up = btn(t("bot.up"), () => move(i, -1), { disabled: i === 0, ariaLabel: t("bot.upAria", { n: i + 1 }) });
      const down = btn(t("bot.down"), () => move(i, 1), { disabled: i === queued.length - 1, ariaLabel: t("bot.downAria", { n: i + 1 }) });
      const rm = btn(t("bot.remove"), (e) => busy(e.currentTarget, () => api.del(`/api/bots/${enc(id)}/tasks/${enc(task.id)}`)), { ariaLabel: t("bot.removeAria", { n: i + 1 }) });
      return h(
        "tr",
        null,
        h("td", { class: "num" }, String(i + 1)),
        h("td", null, h("span", { class: "strong" }, templateTitle(task)), task.label ? h("div", { class: "small dim" }, task.label) : null),
        h("td", { class: "wrap-cell mono" }, argsSummary(task.args) || t("bot.noArgs")),
        h("td", null, task.origin ? tid("origin", originKind(task.origin)) : t("origin.panel")),
        h("td", { class: "actions" }, h("div", { class: "row-sm" }, up, down, rm)),
      );
    });

    const clearBtn = btn(t("bot.clearQueue"), async (e) => {
      const target = e.currentTarget;
      if (!(await confirmDialog(t("bot.clearQueue"), t("bot.clearConfirm"), t("bot.clearQueue")))) return;
      busy(target, () => api.post(`/api/bots/${enc(id)}/clear`));
    });

    mount(
      queueHost,
      h("div", { class: "stack" }, h("p", { class: "label" }, t("bot.current")), curEl),
      h(
        "div",
        { class: "stack" },
        h("div", { class: "row spread" }, h("p", { class: "label" }, t("bot.queued", { n: queued.length })), clearBtn),
        queued.length
          ? table([t("bot.qcol.n"), t("bot.qcol.task"), t("bot.qcol.args"), t("bot.qcol.origin"), t("bot.qcol.actions")], items)
          : h("p", { class: "empty" }, t("bot.queueEmpty")),
      ),
    );

    async function move(index, delta) {
      const ids = queued.map((x) => x.id);
      const j = index + delta;
      if (j < 0 || j >= ids.length) return;
      [ids[index], ids[j]] = [ids[j], ids[index]];
      try {
        await api.post(`/api/bots/${enc(id)}/tasks/reorder`, { ids });
      } catch (e) {
        toast("error", `${e.code}: ${e.message || ""}`);
      }
    }
  }

  function originKind(origin) {
    return String(origin).split(":")[0];
  }

  // ----------------------------------------------------------------
  // Task form
  // ----------------------------------------------------------------
  async function mountForm() {
    try {
      const catalog = await loadCatalog();
      mount(
        formHost,
        taskForm({
          catalog,
          bot: bot(),
          withMode: true,
          primary: true,
          submitLabel: t("form.addTask"),
          onSubmit: (task, mode) => api.post(`/api/bots/${enc(id)}/tasks`, { task, mode }),
        }),
      );
    } catch (e) {
      mount(formHost, errorBox(e, "ui.catalogFailed"));
    }
  }

  // ----------------------------------------------------------------
  // Chat
  // ----------------------------------------------------------------
  const chatInput = h("input", { type: "text", maxlength: 256, autocomplete: "off", spellcheck: "false" });
  const chatNote = h("p", { class: "field-hint", "aria-live": "polite" });
  const chatForm = h(
    "form",
    { class: "stack-sm" },
    field(t("bot.chat"), chatInput, { hint: t("bot.chatHint") }),
    h("div", { class: "row" }, h("button", { type: "submit", class: "btn btn-ghost" }, t("bot.chatSend")), chatNote),
  );
  chatForm.addEventListener("submit", async (e) => {
    e.preventDefault();
    const text = chatInput.value.trim();
    if (!text) return;
    try {
      await api.post(`/api/bots/${enc(id)}/chat`, { text });
      chatNote.textContent = t("bot.chatSent", { text });
      chatInput.value = "";
    } catch (err) {
      chatNote.textContent = `${err.code}: ${err.message || ""}`;
    }
  });

  // ----------------------------------------------------------------
  // Inventory (live query)
  // ----------------------------------------------------------------
  const invBtn = btn(t("bot.invLoad"), () => loadInventory());
  async function loadInventory() {
    mount(invHost, h("p", { class: "empty" }, t("ui.loading")));
    invBtn.disabled = true;
    try {
      let data = await api.get(`/api/bots/${enc(id)}/inventory`);
      if (data && data.ok === false) throw Object.assign(new Error(data.error || ""), { code: data.error || "query_failed" });
      if (data && data.data && !data.slots) data = data.data;
      renderInventory(data || {});
    } catch (e) {
      mount(invHost, errorBox(e, "bot.invFailed"));
    } finally {
      invBtn.disabled = false;
    }
  }

  function renderInventory(inv) {
    const slots = Array.isArray(inv.slots) ? inv.slots : [];
    const low = behaviourOf(bot()).lowToolDurability;
    const rows = slots
      .filter((s) => s.item && s.item !== "minecraft:air")
      .sort((a, b) => a.slot - b.slot)
      .map((s) => {
        let dur = h("span", { class: "dim" }, t("bot.noDurability"));
        if (isNum(s.maxDamage) && s.maxDamage > 0) {
          const left = s.maxDamage - (s.damage || 0);
          dur = num(left, {
            max: s.maxDamage,
            threshold: isNum(low) ? t("th.toolLow", { v: Math.round(low * 100) }) : null,
            bad: isNum(low) && left / s.maxDamage < low,
          });
        }
        return h(
          "tr",
          { "aria-selected": String(s.slot === inv.selected) },
          h("td", { class: "num" }, String(s.slot), s.slot === inv.selected ? h("span", { class: "st-accent" }, ` ${t("bot.selectedSlot")}`) : null),
          h("td", { class: "mono" }, s.item, s.name ? h("div", { class: "small dim" }, s.name) : null),
          h("td", { class: "r" }, num(s.count)),
          h("td", null, dur),
        );
      });
    const armor = Array.isArray(inv.armor) ? inv.armor : null;
    mount(
      invHost,
      rows.length
        ? table([t("bot.icol.slot"), t("bot.icol.item"), { text: t("bot.icol.count"), class: "r" }, t("bot.icol.durability")], rows)
        : h("p", { class: "empty" }, t("bot.invEmpty")),
      spec([
        [t("bot.f.armor"), armorEl(armor)],
        [t("bot.f.offhand"), inv.offhand ? h("span", { class: "mono" }, shortId(inv.offhand)) : h("span", { class: "dim" }, t("bot.emptySlot"))],
        [t("bot.f.selected"), isNum(inv.selected) ? num(inv.selected) : noData()],
      ]),
    );
  }

  // ----------------------------------------------------------------
  // Launcher log
  // ----------------------------------------------------------------
  const logBtn = btn(t("bot.logReload"), () => loadLog());
  async function loadLog() {
    logBtn.disabled = true;
    try {
      const data = await api.get(`/api/bots/${enc(id)}/log`, { lines: LOG_LINES });
      if (typeof data === "string") logLines = data.split(/\r?\n/);
      else logLines = listOf(data, "lines").map((l) => (typeof l === "string" ? l : formatLogLine(l)));
      logState.textContent = t("bot.logLoaded", { n: logLines.length });
      paintLog(true);
    } catch (e) {
      logState.replaceChildren(errorBox(e, "bot.logFailed"));
    } finally {
      logBtn.disabled = false;
    }
  }

  function formatLogLine(l) {
    if (typeof l === "string") return l;
    const tm = isNum(l.time) ? new Date(l.time).toLocaleTimeString() + " " : "";
    return `${tm}${l.level ? l.level.toUpperCase() + " " : ""}${l.source ? "[" + l.source + "] " : ""}${l.message ?? l.line ?? ""}`;
  }

  function paintLog(forceBottom) {
    const atBottom = forceBottom || logPre.scrollTop + logPre.clientHeight >= logPre.scrollHeight - 8;
    if (logLines.length > LOG_KEEP) logLines = logLines.slice(-LOG_KEEP);
    logPre.textContent = logLines.length ? logLines.join("\n") : t("bot.logEmpty");
    if (atBottom) logPre.scrollTop = logPre.scrollHeight;
  }

  // ----------------------------------------------------------------
  // Events
  // ----------------------------------------------------------------
  async function loadEvents() {
    try {
      events = listOf(await api.get("/api/events", { bot: id, limit: EVENTS_SHOWN }), "events");
      events.sort((a, b) => (b.time || 0) - (a.time || 0));
      eventsError = null;
    } catch (e) {
      eventsError = e;
    }
    renderEvents();
  }

  function renderEvents() {
    if (eventsError) {
      mount(eventsHost, errorBox(eventsError, "ui.eventsFailed"));
      return;
    }
    if (!events.length) {
      mount(eventsHost, h("p", { class: "empty" }, t("events.emptyBot")));
      return;
    }
    mount(
      eventsHost,
      table(
        [t("events.col.time"), t("events.col.level"), t("events.col.kind"), t("events.col.message")],
        events.slice(0, EVENTS_SHOWN).map((ev) =>
          h("tr", null, h("td", null, timeEl(ev.time)), h("td", null, levelEl(ev.level)), h("td", null, tid("event", ev.kind)), h("td", { class: "wrap-cell" }, eventText(ev), ev.data ? h("div", { class: "small mono dim" }, argsSummary(ev.data)) : null)),
        ),
      ),
    );
  }

  // ----------------------------------------------------------------
  // Microsoft login
  // ----------------------------------------------------------------
  function renderMicrosoft(result) {
    const b = bot();
    const type = b?.account?.type || "offline";
    if (type !== "microsoft") {
      mount(msHost, h("p", { class: "small" }, t("bot.msOffline")));
      return;
    }
    // The manager answers 400 "unsupported" for Microsoft accounts until phase 2.
    if (!result) {
      mount(msHost, h("p", { class: "small st-warn" }, t("bot.msUnsupported")));
      return;
    }
    const startBtn = btn(t("bot.msStart"), (e) =>
      busy(e.currentTarget, async () => {
        const r = await api.post(`/api/bots/${enc(id)}/microsoft-login`);
        renderMicrosoft({ url: r?.url, code: r?.code, waiting: true });
      }),
    );
    const parts = [h("p", { class: "small" }, t("bot.msText")), h("div", { class: "row" }, startBtn)];
    if (result && result.url) {
      parts.push(
        spec([
          [t("bot.msUrl"), h("a", { href: result.url, target: "_blank", rel: "noopener noreferrer" }, result.url)],
          [t("bot.msCode"), h("span", { class: "code-big" }, result.code || "")],
        ]),
        h("p", { class: "small", "aria-live": "polite" }, result.waiting ? t("bot.msWaiting") : ""),
      );
    }
    if (result && result.done) {
      parts.push(h("p", { class: result.ok ? "st-ok" : "st-bad", role: "status" }, result.ok ? t("bot.msOk") : `${t("bot.msFailed")}: ${result.message || ""}`));
    }
    mount(msHost, ...parts);
  }

  // ----------------------------------------------------------------
  // Companion plugin
  // ----------------------------------------------------------------
  const rbMinutes = h("input", { type: "number", min: 1, max: 10080, step: 1, value: "10", inputmode: "numeric" });
  const rbTarget = h("input", { type: "text", autocomplete: "off", spellcheck: "false" });
  const companionNote = h("p", { class: "small", "aria-live": "polite" });

  function pluginPayload(kind) {
    const minutes = Number(rbMinutes.value);
    if (!Number.isInteger(minutes) || minutes < 1) {
      companionNote.textContent = t("form.err.min", { min: 1 });
      return null;
    }
    const target = rbTarget.value.trim() || bot()?.username || id;
    return { t: kind, d: { target, minutes } };
  }

  async function sendPlugin(kind, button) {
    const payload = pluginPayload(kind);
    if (!payload) return;
    if (kind === "rollback" && !(await confirmDialog(t("bot.rollback"), t("bot.rollbackConfirm", { target: payload.d.target, m: payload.d.minutes }), t("bot.rollback")))) return;
    await busy(button, async () => {
      const r = await api.post(`/api/bots/${enc(id)}/plugin`, { payload });
      const res = r && (r.payload || r.result || r.data);
      companionNote.textContent = res ? `${t("bot.pluginReply")}: ${argsSummary(res.d || res)}` : t("bot.pluginSent");
    });
  }

  const companionForm = h(
    "div",
    { class: "stack" },
    h("p", { class: "small" }, t("bot.companionText")),
    h("div", { class: "fields" }, field(t("bot.minutes"), rbMinutes, { hint: t("bot.minutesHint") }), field(t("bot.target"), rbTarget, { hint: t("bot.targetHint") })),
    h(
      "div",
      { class: "row-sm" },
      btn(t("bot.journal"), (e) => sendPlugin("journal", e.currentTarget), { small: false }),
      btn(t("bot.rollback"), (e) => sendPlugin("rollback", e.currentTarget), { small: false }),
    ),
    companionNote,
  );

  // ----------------------------------------------------------------
  // Layout
  // ----------------------------------------------------------------
  const sec = (key, ...children) => h("section", { class: "section" }, h("div", { class: "head" }, h("h2", { class: "h2" }, t(key))), children);

  mount(
    root,
    h(
      "div",
      { class: "stack-lg" },
      h("div", { class: "stack-sm" }, h("p", null, h("a", { href: "#/bots" }, t("bot.back"))), title, subtitle, actions),
      h(
        "div",
        { class: "cols" },
        sec("bot.status", statusHost),
        h("div", { class: "stack" }, sec("bot.queue", queueHost), sec("bot.addTask", formHost)),
      ),
      h("div", { class: "cols" }, sec("bot.chatTitle", chatForm), sec("bot.msTitle", msHost), sec("bot.companion", companionForm)),
      sec("bot.inventory", h("div", { class: "row" }, invBtn, h("span", { class: "small dim" }, t("bot.invHint"))), invHost),
      sec("bot.logTitle", h("div", { class: "row" }, logBtn, logState), logPre),
      sec("bot.events", eventsHost),
    ),
  );

  renderHeader();
  renderStatus();
  renderQueue();
  renderMicrosoft();
  mountForm();
  loadLog();
  loadEvents();
  mount(invHost, h("p", { class: "empty" }, t("bot.invNotLoaded")));
  if (bot()?.status?.state === "online") loadInventory();

  const refreshStatus = throttle(() => {
    renderHeader();
    renderStatus();
  }, 500);

  return {
    update(type, data) {
      const forMe = data && (data.botId === id || data.id === id || data.status?.botId === id);
      if (type === "snapshot") {
        if (!bot()) {
          mount(root, h("div", { class: "stack" }, h("h1", { class: "h1" }, t("bot.notFound", { id })), h("a", { href: "#/bots" }, t("bot.back"))));
          return;
        }
        refreshStatus();
        renderQueue();
        return;
      }
      if (!forMe) return;
      if (type === "bot" || type === "process") refreshStatus();
      if (type === "queue") renderQueue();
      if (type === "process") renderQueue();
      if (type === "log") {
        logLines.push(formatLogLine(data.line ?? data));
        paintLog(false);
      }
      if (type === "event") {
        events.unshift(data);
        if (events.length > EVENTS_SHOWN) events.length = EVENTS_SHOWN;
        renderEvents();
        if (data.kind && /^microsoft/.test(data.kind)) {
          renderMicrosoft({ done: true, ok: data.level !== "error" && data.data?.ok !== false, message: data.message });
        }
      }
    },
  };
}
