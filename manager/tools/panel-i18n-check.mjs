// Lists panel translation keys that are used but missing from
// panel/i18n/{ru,en}.json (keys the manager merges in from i18n-catalog
// count as present), keys whose {placeholders} differ between the two
// languages, panel keys that duplicate catalog keys, and unused panel keys.
//
//   node manager/tools/panel-i18n-check.mjs
//
// Exit code 1 when a key is missing or placeholders differ.

import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const res = join(here, "..", "src", "main", "resources");
const panel = join(res, "panel");
const LANGS = ["ru", "en"];

// Keys built at run time from a known set of values: `prefix.${value}`.
const DYNAMIC = {
  conn: ["connecting", "open", "retry", "closed", "no_token"],
  mode: ["append", "front", "replace"],
  level: ["info", "warn", "error"],
  nav: ["bots", "scenarios", "projects", "automation", "world", "kits", "settings", "events"],
  "bot.act": ["start", "stop", "restart", "kill", "connect", "disconnect"],
  "proj.act": ["start", "pause", "resume", "stop"],
  armor: ["head", "chest", "legs", "feet"],
  state: ["starting", "menu", "connecting", "logging_in", "online", "dead", "disconnected"],
  account: ["offline", "microsoft"],
  defense: ["fight", "flee", "ignore"],
  origin: ["panel", "scenario", "project", "recovery"],
  source: ["bot", "manager", "plugin", "storage", "mine", "craft", "smelt", "manual", "haul"],
  work: ["build_sector", "haul", "mine", "craft", "smelt"],
  pfield: ["origin", "dim", "rotation", "mirror", "supply"],
  containerRole: ["storage", "supply", "kit", "fuel", "inbox", "sorted", "trash"],
  sector: ["active", "done", "pending", "blocked"],
  pick: ["waypointLabel", "areaLabel"],
  "pick.hint": ["none", "home", "owner", "owner_look", "bot", "waypoint", "area", "auto", "two", "manual", "known"],
  msState: ["idle", "starting", "waiting", "ok", "failed", "cancelled"],
  "quick.tier": ["wood", "stone", "iron", "diamond"],
  "auto.tab": ["autopilot", "orders", "schedules", "rules", "hygiene"],
  "auto.flag": ["supply", "sort", "idleWork", "discovery", "useFound"],
  "auto.num": ["foodMin", "blocksMin", "toolMinDurability", "stuckSec"],
  "auto.bots": ["any", "all", "list"],
  "auto.priority": ["normal", "high"],
  "auto.when": ["every", "hourly", "daily", "day", "night", "cron"],
  "auto.trig": ["event", "containerFull", "itemBelow", "playerOnline", "healthBelow"],
  orderState: ["ok", "active", "inspecting", "no_containers", "disabled", "error", "unknown"],
  evk: [
    "death", "damaged", "threat", "inventory_full", "tool_low", "food_low", "disconnected", "kicked", "joined", "task_failed", "task_done",
    "stuck", "crashed", "link_lost", "project_blocked", "project_done", "order_blocked", "sort_full", "manual", "goal_done", "goal_failed", "plugin_rollback",
  ],
  "set.kp.armor": ["worn", "none"],
  "set.kp.weapon": ["best", "none"],
  "set.kp.tool": ["pickaxe", "axe", "shovel", "hoe", "sword", "shears"],
  enum: ["none", "front_back", "left_right", "wood", "stone", "iron", "diamond"],
  pstatus: ["draft", "running", "paused", "done", "failed", "stopped"],
  "set.applies": ["bot_restart", "manager_restart", "reinstall"],
  error: ["network", "panel_error"],
  wfield: ["name", "dim", "pos", "box", "label", "roles"],
  "bots.noStatus": ["stopped", "installing", "starting", "linked", "stopping", "crashed"],
};

// String literals that look like keys but are not: storage keys, tid() prefixes, field paths.
const NOT_KEYS = new Set([
  "bb.token",
  "bb.lang",
  "a.b",
  "runtime.state",
  "runtime.step",
  "account.type",
  "autopilot.signWords",
  "autopilot.autoTrash.keepCounts",
]);
const KEY_RE = /^[a-z][A-Za-z0-9]*(\.[A-Za-z0-9_]+)+$/;

function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, out);
    else out.push(p);
  }
  return out;
}

function flatten(obj, prefix = "", out = {}) {
  for (const [k, v] of Object.entries(obj || {})) {
    const key = prefix ? `${prefix}.${k}` : k;
    if (v && typeof v === "object" && !Array.isArray(v)) flatten(v, key, out);
    else out[key] = String(v);
  }
  return out;
}

const readJson = (p) => {
  try {
    return flatten(JSON.parse(readFileSync(p, "utf8")));
  } catch (e) {
    if (e.code === "ENOENT") return {};
    throw new Error(`${p}: ${e.message}`);
  }
};

const used = new Map(); // key -> first file
const note = (key, file) => {
  if (!used.has(key)) used.set(key, file);
};

for (const file of walk(panel)) {
  const rel = file.slice(panel.length + 1);
  const text = readFileSync(file, "utf8");
  if (file.endsWith(".js")) {
    // Comments go first so an apostrophe or an example in prose is not read as code.
    const code = text.replace(/\/\*[\s\S]*?\*\//g, "").replace(/(^|\s)\/\/.*$/gm, "$1");
    for (const m of code.matchAll(/"((?:[^"\\\n]|\\.)*)"|'((?:[^'\\\n]|\\.)*)'/g)) {
      const s = m[1] ?? m[2];
      if (s && KEY_RE.test(s) && !NOT_KEYS.has(s) && !/^[\w-]+\.(js|css|json|html)$/.test(s)) note(s, rel);
    }
  } else if (file.endsWith(".html")) {
    for (const m of text.matchAll(/data-i18n(?:-aria)?="([^"]+)"/g)) note(m[1], rel);
  }
}
for (const [prefix, values] of Object.entries(DYNAMIC)) for (const v of values) note(`${prefix}.${v}`, "(dynamic)");

const catalog = Object.fromEntries(LANGS.map((l) => [l, readJson(join(res, "i18n-catalog", `${l}.json`))]));
const own = Object.fromEntries(LANGS.map((l) => [l, readJson(join(panel, "i18n", `${l}.json`))]));

let failed = false;
for (const lang of LANGS) {
  const missing = [...used.keys()].filter((k) => !(k in own[lang]) && !(k in catalog[lang])).sort();
  if (missing.length) {
    failed = true;
    console.log(`missing in panel/i18n/${lang}.json (${missing.length}):`);
    for (const k of missing) console.log(`  ${k}    <- ${used.get(k)}`);
  }
  const dup = Object.keys(own[lang]).filter((k) => k in catalog[lang]);
  if (dup.length) console.log(`panel/i18n/${lang}.json repeats catalog keys (the catalog wins): ${dup.join(", ")}`);
}

const ph = (s) => [...String(s).matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort().join(",");
for (const k of Object.keys(own.en)) {
  if (k in own.ru && ph(own.ru[k]) !== ph(own.en[k])) {
    failed = true;
    console.log(`placeholders differ for ${k}: ru {${ph(own.ru[k])}} en {${ph(own.en[k])}}`);
  }
}

const unused = Object.keys(own.en).filter((k) => !used.has(k));
if (unused.length) console.log(`unused panel keys (${unused.length}): ${unused.join(", ")}`);

console.log(failed ? "panel i18n: problems found" : `panel i18n: ${used.size} keys used, none missing`);
process.exit(failed ? 1 : 0);
