// Bot status fragments shared by the bots table and the bot page. Each
// value carries its unit and, where the config defines one, its threshold.

import { h } from "./dom.js";
import { t, tid } from "./i18n.js";
import { behaviourOf } from "./store.js";
import { num, noData, isNum, posText, dimLabel, durationEl, fmt } from "./format.js";
import { templateTitle } from "./forms.js";

const digitsFor = (v) => (isNum(v) && !Number.isInteger(v) ? 1 : 0);

export function hpEl(bot) {
  const s = bot.status;
  if (!s || !isNum(s.health)) return noData();
  const def = behaviourOf(bot).defense || {};
  const flee = def.mode !== "ignore" && isNum(def.fleeBelowHealth) ? def.fleeBelowHealth : null;
  return num(s.health, {
    max: s.maxHealth,
    digits: digitsFor(s.health),
    threshold: flee !== null ? t("th.flee", { v: fmt(flee, digitsFor(flee)) }) : null,
    bad: flee !== null && s.health <= flee,
  });
}

export function foodEl(bot) {
  const s = bot.status;
  if (!s || !isNum(s.food)) return noData();
  const eat = behaviourOf(bot).autoEat || {};
  const below = eat.enabled !== false && isNum(eat.belowFood) ? eat.belowFood : null;
  return num(s.food, {
    max: 20,
    threshold: below !== null ? t("th.eat", { v: below }) : t("th.eatOff"),
    warn: below !== null && s.food < below,
  });
}

export function slotsEl(bot) {
  const s = bot.status;
  if (!s || !isNum(s.freeSlots)) return noData();
  const full = behaviourOf(bot).inventoryFullFreeSlots;
  return num(s.freeSlots, {
    max: 36,
    threshold: isNum(full) ? t("th.full", { v: full }) : null,
    bad: isNum(full) && s.freeSlots <= full,
  });
}

export function ramEl(bot) {
  const p = bot.status?.perf;
  if (!p || !isNum(p.heapUsedMb)) return noData();
  return num(p.heapUsedMb, { max: p.heapMaxMb, unit: "MB", warn: isNum(p.heapMaxMb) && p.heapUsedMb > p.heapMaxMb * 0.9 });
}

export function cpuEl(bot) {
  const p = bot.status?.perf;
  if (!p || !isNum(p.cpu)) return noData();
  return num(p.cpu * 100, { unit: "%", digits: 0 });
}

export function fpsEl(bot) {
  const p = bot.status?.perf;
  if (!p || !isNum(p.fps)) return noData();
  return num(p.fps, { unit: t("unit.fps") });
}

export function pingEl(bot) {
  const p = bot.status?.perf;
  if (!p || !isNum(p.pingMs)) return noData();
  return num(p.pingMs, { unit: "ms" });
}

export function uptimeEl(bot) {
  const s = bot.status;
  if (!s || !isNum(s.uptimeSec)) return noData();
  return durationEl(s.uptimeSec);
}

export function posEl(bot) {
  const s = bot.status;
  if (!s || !s.pos) return noData();
  return h("span", null, h("span", { class: "num" }, posText(s.pos)), " ", h("span", { class: "dim" }, dimLabel(s.dim)));
}

/** "Mine · step: mining · 42 %" from the bot's own status; falls back to the manager's dispatched task. */
export function taskEl(bot) {
  const s = bot.status;
  const queued = bot.queue?.queued?.length || 0;
  const more = queued ? h("span", { class: "dim" }, " ", t("bots.queuedMore", { n: queued })) : null;
  const tk = s?.task;
  if (tk) {
    const title = t(`task.${tk.type}.title`, null, tk.type);
    const progress = isNum(tk.progress) && tk.progress >= 0 ? num(tk.progress * 100, { unit: "%" }) : h("span", { class: "dim" }, t("ui.progressUnknown"));
    return h(
      "span",
      null,
      h("span", { class: tk.state === "paused" ? "st-warn" : "strong" }, title),
      tk.label ? h("span", { class: "dim" }, ` (${tk.label})`) : null,
      tk.state === "paused" ? h("span", { class: "st-warn" }, ` · ${t("bots.paused")}`) : null,
      tk.step ? h("span", null, ` · ${tid("taskStep", tk.step)}`) : null,
      " · ",
      progress,
      more,
    );
  }
  const cur = bot.queue?.current;
  if (cur) {
    return h("span", null, h("span", { class: "strong" }, templateTitle(cur)), h("span", { class: "dim" }, ` · ${t("bots.dispatched")}`), more);
  }
  return h("span", { class: "dim" }, t("bots.noTask"), more);
}

export function roleEl(bot) {
  const role = bot.role || bot.assignment?.role;
  if (!role) return h("span", { class: "dim" }, t("bots.noRole"));
  return h("span", null, tid("role", role));
}
