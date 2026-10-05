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

const BLOCK_ID = /Block\{([a-z0-9_.-]+:[a-z0-9_/.-]+)\}/g;
const ANY_ID = /\b(?:minecraft:)([a-z0-9_/.-]+)/g;

/**
 * Baritone's process line in words. The mod reports displayName(), which for
 * mining is "Mine BlockOptionalMetaLookup{[BlockOptionalMeta{block=Block{minecraft:coal_ore}, ...}]}";
 * this keeps the verb and the target block ids: {name: "Mine", targets: ["coal_ore", ...]}.
 */
export function baritoneProcess(raw) {
  if (!raw || typeof raw !== "string") return null;
  let ids = [...raw.matchAll(BLOCK_ID)].map((m) => m[1].replace(/^minecraft:/, ""));
  if (!ids.length) ids = [...raw.matchAll(ANY_ID)].map((m) => m[1]);
  const targets = [...new Set(ids)];
  let name = raw.split(/[{[]/)[0].trim();
  // "Mine BlockOptionalMetaLookup" / "Get To BlockOptionalMeta": drop the class name glued to the verb
  name = name.replace(/\s*\b[A-Z][A-Za-z]*(?:Lookup|Meta|Goal|Filter)\b.*$/, "").replace(/\s+Goal[A-Z]\w*$/, "").trim();
  if (targets.length) name = name.replace(/\s*\bminecraft:\S*/g, "").trim();
  if (!name) name = raw.slice(0, 40);
  if (name.length > 48) name = `${name.slice(0, 45)}...`;
  return { name, targets };
}

/**
 * Baritone goal in a few characters: "GoalBlock{x=1,y=64,z=3}" -> "Block 1 64 3",
 * a composite of 120 mining goals -> {targets: 120}.
 */
export function baritoneGoal(raw) {
  if (!raw || typeof raw !== "string") return null;
  if (/^GoalComposite/.test(raw)) {
    const n = (raw.match(/Goal[A-Za-z]+\s*[{[]/g) || []).length - 1;
    return { composite: Math.max(n, 0) };
  }
  const m = raw.match(/^Goal([A-Za-z]+)\s*[{[](.*)[}\]]$/s);
  if (m) {
    const nums = [...m[2].matchAll(/-?\d+(?:\.\d+)?/g)].map((x) => x[0]).slice(0, 3);
    return { text: [m[1], ...nums].join(" ") };
  }
  return { text: raw.length > 48 ? `${raw.slice(0, 45)}...` : raw };
}

/** "Mine coal_ore, deepslate_coal_ore · pathing · goal Block 1 64 3 · ETA 12 s"; the raw text is the tooltip. */
export function baritoneEl(b) {
  const proc = baritoneProcess(b?.process);
  if (!proc) return h("span", { class: "dim" }, t("bot.baritoneIdle"));
  const goal = baritoneGoal(b.goal);
  const shown = proc.targets.slice(0, 4).join(", ") + (proc.targets.length > 4 ? ` +${proc.targets.length - 4}` : "");
  return h(
    "span",
    { title: [b.process, b.goal].filter(Boolean).join("\n") },
    h("span", { class: "strong" }, proc.name),
    shown ? h("span", { class: "mono" }, ` ${shown}`) : null,
    b.pathing ? ` · ${t("bot.pathing")}` : "",
    goal ? h("span", { class: "dim" }, " · ", goal.composite !== undefined ? t("bot.goalTargets", { n: goal.composite }) : [`${t("bot.goal")}: `, h("span", { class: "mono" }, goal.text)]) : null,
    isNum(b.eta) ? [" · ETA ", durationEl(b.eta)] : null,
  );
}

export function roleEl(bot) {
  const role = bot.role || bot.assignment?.role;
  if (!role) return h("span", { class: "dim" }, t("bots.noRole"));
  return h("span", null, tid("role", role));
}
