// One bot: status, queue, task form, chat, inventory, launcher log,
// events, Microsoft login and companion plugin actions.

import { api, enc, listOf } from "../api.js";
import { h, mount, btn, busy, toast, confirmDialog, errorBox, throttle, spec, table, field, select } from "../dom.js";
import { t, tid } from "../i18n.js";
import { store, loadCatalog, loadSettings, serverName, behaviourOf } from "../store.js";
import { processEl, botStateEl, num, noData, isNum, timeEl, levelEl, argsSummary, shortId, durationEl, duration } from "../format.js";
import { taskForm, templateTitle } from "../forms.js";
import { ownerName } from "../refpick.js";
import * as bv from "../botview.js";
import { eventText } from "./events.js";
import { commandBox } from "../ai.js";

const LOG_LINES = 200;
const LOG_KEEP = 600;
const EVENTS_SHOWN = 100;
const ARMOR_SLOTS = ["head", "chest", "legs", "feet"];
const TIERS = ["wood", "stone", "iron", "diamond"];
const TRASH_TO = ["drop", "store", "trash_chest"];
const PLUGIN_EVENTS_SHOWN = 5;
// HeadlessMC keeps a device-code login open for 15 min (MicrosoftLogin.LOGIN_TIMEOUT_MS).
const MS_LOGIN_TTL_SEC = 900;
const MS_POLL_MS = 3000;

/** Argument default of a manager step from the catalog (e.g. the default keep profile of "trash"). */
function stepDefault(step, arg) {
  const s = (store.catalog?.managerSteps || []).find((x) => (x.step || x.name || x.type) === step);
  return (s?.args || []).find((a) => a.name === arg)?.default;
}

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
  const pluginHost = h("div", { class: "stack-sm" });
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
  // Quick actions: one click each, no coordinates
  // ----------------------------------------------------------------
  const quickNote = h("p", { class: "field-hint", "aria-live": "polite" });
  const ownerNote = h("p", { class: "field-hint" });

  async function queueTask(button, task, mode, what) {
    await busy(button, async () => {
      quickNote.textContent = t("ui.sending");
      await api.post(`/api/bots/${enc(id)}/tasks`, { task, mode });
      quickNote.textContent = t("quick.queued", { what });
    });
  }

  const profileSel = h("select");
  const fillProfiles = () => {
    const names = Object.keys(store.settings?.config?.keepProfiles || {});
    const def = stepDefault("trash", "profile");
    if (def && !names.includes(def)) names.unshift(def);
    const cur = profileSel.value || def || names[0] || "";
    profileSel.replaceChildren(...names.map((n) => h("option", { value: n }, n)));
    if (cur) profileSel.value = cur;
  };
  profileSel.addEventListener("focus", fillProfiles);
  const trashTo = select(
    TRASH_TO.map((v) => ({ value: v, label: t(`step.trash.arg.to.option.${v}`, null, v) })),
    "drop",
  );
  const trashBtn = btn(
    t("quick.trash"),
    (e) => {
      const args = { to: trashTo.value };
      if (profileSel.value) args.profile = profileSel.value;
      // In front of the queue: the bot clears its inventory before the next task.
      queueTask(e.currentTarget, { type: "trash", args }, "front", t("quick.trash"));
    },
    { kind: "primary", small: false },
  );

  const comeBtn = btn(
    t("quick.come"),
    (e) => queueTask(e.currentTarget, { type: "goto", args: { pos: { ref: "owner" }, range: 2 } }, "replace", t("quick.come")),
    { small: false },
  );
  const followBtn = btn(
    t("quick.follow"),
    (e) => queueTask(e.currentTarget, { type: "follow", args: { player: ownerName() } }, "replace", t("quick.follow")),
    { small: false },
  );
  const stopBtn = btn(
    t("quick.stop"),
    (e) =>
      busy(e.currentTarget, async () => {
        await api.post(`/api/bots/${enc(id)}/clear`);
        quickNote.textContent = t("quick.stopped");
      }),
    { small: false },
  );

  const tierSel = select(
    TIERS.map((v) => ({ value: v, label: t(`quick.tier.${v}`) })),
    stepDefault("progress", "tier") || "iron",
  );
  const progressBtn = btn(
    t("quick.progressGo"),
    (e) => queueTask(e.currentTarget, { type: "progress", args: { tier: tierSel.value } }, "append", `${t("quick.progress")} ${t(`quick.tier.${tierSel.value}`)}`),
    { small: false },
  );

  const obtainItem = h("input", { type: "text", list: "dl-items", autocomplete: "off", spellcheck: "false", placeholder: "minecraft:iron_ingot" });
  const obtainCount = h("input", { type: "number", min: 1, max: 2304, step: 1, value: "16", inputmode: "numeric" });
  const obtainBtn = btn(
    t("quick.obtainGo"),
    (e) => {
      const item = obtainItem.value.trim();
      const count = Number(obtainCount.value);
      if (!item) {
        quickNote.textContent = t("quick.err.item");
        obtainItem.focus();
        return;
      }
      if (!Number.isInteger(count) || count < 1 || count > 2304) {
        quickNote.textContent = t("form.err.max", { max: 2304 });
        obtainCount.focus();
        return;
      }
      queueTask(e.currentTarget, { type: "obtain", args: { item, count } }, "append", `${t("quick.obtain")} ${shortId(item)} ${count}`);
    },
    { small: false },
  );

  function renderOwnerNote() {
    const owner = ownerName();
    comeBtn.disabled = !owner;
    followBtn.disabled = !owner;
    if (owner) {
      ownerNote.textContent = t("quick.ownerIs", { owner });
    } else {
      ownerNote.replaceChildren(t("quick.noOwner"), " ", h("a", { href: "#/settings/general" }, t("quick.setOwner")));
    }
  }

  const quickEl = h(
    "div",
    { class: "stack" },
    h("div", { class: "row row-end quick-row" }, trashBtn, field(t("quick.profile"), profileSel), field(t("quick.to"), trashTo)),
    h("p", { class: "field-hint" }, t("quick.profileHint")),
    h("div", { class: "row-sm" }, comeBtn, followBtn, stopBtn),
    ownerNote,
    h(
      "div",
      { class: "row row-end quick-row" },
      field(t("quick.progress"), tierSel),
      progressBtn,
      field(t("quick.obtain"), obtainItem),
      field(t("quick.count"), obtainCount),
      obtainBtn,
    ),
    quickNote,
  );

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
        [t("bot.f.baritone"), bv.baritoneEl(baritone)],
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
          // The page's one primary action is "throw away junk" above.
          primary: false,
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
    renderPlugin();
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
  // Microsoft login (HeadlessMC device code, SPEC §5.4)
  // ----------------------------------------------------------------
  let ms = null; // GET /microsoft-login view
  let msError = null;
  let msStartError = null; // the last POST failure (not_installed, timeout, ...)
  let msPending = false; // POST in flight: HeadlessMC prints the code within 45 s
  let msExpiresAt = 0;
  let msTimer = null;
  let msKey = null;
  const msLeft = h("span", { class: "num" });

  const msActive = () => ms && (ms.state === "starting" || ms.state === "waiting");

  async function msRefresh() {
    clearTimeout(msTimer);
    if ((bot()?.account?.type || "offline") !== "microsoft") {
      renderMicrosoft();
      return;
    }
    try {
      ms = await api.get(`/api/bots/${enc(id)}/microsoft-login`);
      msError = null;
    } catch (e) {
      msError = e;
    }
    renderMicrosoft();
    if (msActive()) msTimer = setTimeout(msRefresh, MS_POLL_MS);
  }

  function msCountdown() {
    const end = msExpiresAt || (isNum(ms?.startedAt) ? ms.startedAt + MS_LOGIN_TTL_SEC * 1000 : 0);
    if (!end) {
      msLeft.textContent = "";
      return;
    }
    const left = Math.max(0, Math.round((end - Date.now()) / 1000));
    msLeft.textContent = t("bot.msLeft", { left: duration(left), max: duration(MS_LOGIN_TTL_SEC) });
    msLeft.classList.toggle("num-warn", left < 120);
  }

  function renderMicrosoft() {
    const b = bot();
    const type = b?.account?.type || "offline";
    if (type !== "microsoft") {
      msKey = "offline";
      mount(msHost, h("p", { class: "small" }, t("bot.msOffline")));
      return;
    }
    const st = msPending ? "starting" : ms?.state || "idle";
    const key = [st, ms?.code, ms?.hasAccount, ms?.account, ms?.error, msError?.code, msStartError?.code].join("|");
    msCountdown();
    if (key === msKey) return; // only the countdown moved; keep focus on the buttons
    msKey = key;
    const startBtn = btn(
      ms?.hasAccount ? t("bot.msRelogin") : t("bot.msStart"),
      async (e) => {
        const target = e.currentTarget;
        msPending = true;
        msStartError = null;
        renderMicrosoft();
        await busy(target, async () => {
          try {
            const r = await api.post(`/api/bots/${enc(id)}/microsoft-login`);
            msExpiresAt = isNum(r?.expiresInSec) ? Date.now() + r.expiresInSec * 1000 : 0;
            ms = { ...(ms || {}), ...r, state: r?.state || "waiting" };
          } catch (err) {
            msStartError = err; // e.g. 409 not_installed: shown in the section, not only as a toast
            throw err;
          } finally {
            msPending = false;
          }
        });
        msRefresh();
      },
      { small: false, disabled: msPending || st === "starting" || st === "waiting" },
    );
    const cancelBtn = btn(
      t("bot.msCancel"),
      (e) =>
        busy(e.currentTarget, async () => {
          ms = await api.del(`/api/bots/${enc(id)}/microsoft-login`);
          msExpiresAt = 0;
          msRefresh();
        }),
      { small: false },
    );
    const pairs = [
      [t("bot.msState"), h("span", { class: st === "ok" ? "st-ok" : st === "failed" ? "st-bad" : st === "waiting" || st === "starting" ? "st-warn" : "" }, tid("msState", st))],
      [
        t("bot.msSaved"),
        ms?.hasAccount
          ? h("span", { class: "st-ok" }, ms.account ? t("bot.msSavedAs", { account: ms.account }) : t("set.yes"))
          : h("span", { class: "st-warn" }, t("bot.msNotSaved")),
      ],
    ];
    if (ms?.url && (st === "waiting" || st === "starting")) {
      pairs.push(
        [t("bot.msUrl"), h("a", { href: ms.url, target: "_blank", rel: "noopener noreferrer" }, ms.url)],
        [t("bot.msCode"), h("span", { class: "code-big" }, ms.code || "")],
        [t("bot.msExpires"), msLeft],
      );
    }
    if (st === "failed" && ms?.error) pairs.push([t("bot.msError"), h("span", { class: "st-bad" }, ms.error)]);
    mount(
      msHost,
      h("p", { class: "small" }, t("bot.msText")),
      msError ? errorBox(msError) : null,
      msStartError ? errorBox(msStartError, "bot.msStartFailed") : null,
      spec(pairs),
      msPending ? h("p", { class: "small", role: "status" }, t("bot.msWaitCode")) : null,
      st === "waiting" ? h("p", { class: "small", role: "status" }, t("bot.msWaiting")) : null,
      !ms?.hasAccount ? h("p", { class: "small dim" }, t("bot.msNeeded")) : null,
      h("div", { class: "row-sm" }, startBtn, st === "waiting" || st === "starting" ? cancelBtn : null),
    );
  }

  // ----------------------------------------------------------------
  // Companion plugin (SPEC §7)
  // ----------------------------------------------------------------
  function renderPlugin() {
    const p = bot()?.plugin || {};
    const parts = [];
    if (p.welcome) {
      const d = p.welcome.d || {};
      parts.push(
        h(
          "p",
          { class: "small" },
          h("span", { class: "st-ok" }, t("bot.pluginWelcome")),
          Array.isArray(d.features) && d.features.length ? ` · ${t("bot.pluginFeatures", { list: d.features.join(", ") })}` : "",
          d.server ? ` · ${d.server}` : "",
          isNum(p.welcome.receivedAt) ? [" · ", timeEl(p.welcome.receivedAt)] : null,
        ),
      );
    } else if (p.reject) {
      parts.push(h("p", { class: "small st-bad" }, t("bot.pluginRejected", { reason: p.reject.d?.reason || "" })));
    } else {
      parts.push(h("p", { class: "small dim" }, t("bot.pluginSilent")));
    }
    const last = events.filter((ev) => String(ev.kind || "").startsWith("plugin_")).slice(0, PLUGIN_EVENTS_SHOWN);
    parts.push(h("p", { class: "label" }, t("bot.pluginLast", { n: last.length })));
    parts.push(
      last.length
        ? h(
            "ul",
            { class: "list small plugin-events" },
            last.map((ev) => h("li", null, timeEl(ev.time), " ", levelEl(ev.level), " ", eventText(ev))),
          )
        : h("p", { class: "small dim" }, t("bot.pluginNoEvents")),
    );
    mount(pluginHost, ...parts);
  }

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
    pluginHost,
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
  const ai = commandBox({ botIds: () => [id], hint: t("ai.hintBot") });

  mount(
    root,
    h(
      "div",
      { class: "stack-lg" },
      h("div", { class: "stack-sm" }, h("p", null, h("a", { href: "#/bots" }, t("bot.back"))), title, subtitle, actions),
      sec("quick.title", quickEl),
      ai.el,
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
  renderOwnerNote();
  fillProfiles();
  renderPlugin();
  msRefresh();
  // Keep profiles and the owner name come from the settings; the catalog gives the defaults.
  Promise.all([loadSettings().catch(() => null), loadCatalog().catch(() => null)]).then(() => {
    fillProfiles();
    renderOwnerNote();
    if (!tierSel.dataset.touched) tierSel.value = stepDefault("progress", "tier") || tierSel.value;
  });
  tierSel.addEventListener("change", () => (tierSel.dataset.touched = "1"));
  mountForm();
  loadLog();
  loadEvents();
  mount(invHost, h("p", { class: "empty" }, t("bot.invNotLoaded")));
  if (bot()?.status?.state === "online") loadInventory();

  const refreshStatus = throttle(() => {
    renderHeader();
    renderStatus();
    renderPlugin();
  }, 500);

  return {
    destroy() {
      clearTimeout(msTimer);
    },
    update(type, data) {
      ai.update(type);
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
        if (data.kind && /^microsoft_login/.test(data.kind)) msRefresh();
      }
    },
  };
}
