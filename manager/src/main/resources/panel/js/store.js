// Client-side copy of the manager state: filled from GET /api/state and
// kept current by SSE. Screens read from here and subscribe to changes.

import { api, listOf } from "./api.js";

const EVENTS_KEPT = 500;

export const store = {
  loaded: false,
  version: null,
  bots: new Map(),
  projects: new Map(),
  servers: [],
  runtime: null,
  events: [], // newest first
  catalog: null,
  settings: null, // {config, schema}; loaded once for thresholds and defaults
  kits: null,
  ai: null, // {enabled, mode, model, timeoutSec, superviseSec} (SPEC §5.7c/d)
  owner: null, // {player, candidate: {player, serverId, via, time} | absent} (SPEC §5.7e)
  listeners: new Set(),
};

export function subscribe(fn) {
  store.listeners.add(fn);
  return () => store.listeners.delete(fn);
}

function emit(type, data) {
  for (const fn of [...store.listeners]) {
    try {
      fn(type, data);
    } catch (e) {
      console.error("listener failed", type, e);
    }
  }
}

function procState(p) {
  if (!p) return null;
  if (typeof p === "string") return p;
  return p.state || p.process || null;
}

function normQueue(q, fallback) {
  if (Array.isArray(q)) return { current: fallback?.current ?? null, queued: q };
  if (q && typeof q === "object") {
    return { current: q.current ?? null, queued: q.queued ?? q.queue ?? q.items ?? [] };
  }
  return fallback || { current: null, queued: [] };
}

/** Accepts the manager's bot view in a few plausible shapes; keeps unknown fields. */
export function normBot(raw, prev) {
  const b = { ...(prev || {}), ...raw };
  b.id = raw.id ?? raw.botId ?? prev?.id;
  const proc = raw.process ?? raw.processState ?? raw.lifecycle;
  if (proc !== undefined) {
    b.process = procState(proc) || "stopped";
    b.processInfo = typeof proc === "object" ? proc : { ...(prev?.processInfo || {}), state: b.process };
  } else if (!prev) {
    b.process = "stopped";
    b.processInfo = {};
  }
  if (raw.queue !== undefined || raw.current !== undefined) {
    b.queue = normQueue(raw.queue ?? { current: raw.current, queued: raw.queued }, prev?.queue);
  } else if (!b.queue) b.queue = { current: null, queued: [] };
  if (raw.status === undefined && !prev) b.status = null;
  if (!Array.isArray(b.roles)) b.roles = [];
  return b;
}

export function applySnapshot(s) {
  store.version = s.version ?? store.version;
  const bots = new Map();
  for (const raw of listOf(s.bots, "bots")) {
    const id = raw.id ?? raw.botId;
    bots.set(id, normBot(raw, null));
  }
  store.bots = bots;
  store.projects = new Map(listOf(s.projects, "projects").map((p) => [p.id, p]));
  store.servers = listOf(s.servers, "servers");
  store.runtime = s.runtime ?? store.runtime;
  store.ai = s.ai ?? store.ai;
  store.owner = s.owner ?? store.owner;
  const tail = listOf(s.eventsTail ?? s.events, "events");
  store.events = sortEvents(tail).slice(0, EVENTS_KEPT);
  store.loaded = true;
  emit("snapshot", s);
}

function sortEvents(list) {
  return [...list].sort((a, b) => (b.time || 0) - (a.time || 0));
}

export async function loadState() {
  const s = await api.get("/api/state");
  applySnapshot(s || {});
  return s;
}

export async function loadCatalog(force) {
  if (store.catalog && !force) return store.catalog;
  store.catalog = await api.get("/api/catalog");
  return store.catalog;
}

export async function loadSettings(force) {
  if (store.settings && !force) return store.settings;
  store.settings = await api.get("/api/settings");
  return store.settings;
}

export async function loadKits(force) {
  if (store.kits && !force) return store.kits;
  store.kits = listOf(await api.get("/api/kits"), "kits");
  return store.kits;
}

store.worlds = new Map();

/** World knowledge of one server profile; cached until a screen forces a reload. */
export async function loadWorld(serverId, force) {
  if (!serverId) return null;
  if (store.worlds.has(serverId) && !force) return store.worlds.get(serverId);
  const w = normWorld(await api.get(`/api/world/${encodeURIComponent(serverId)}`));
  store.worlds.set(serverId, w);
  return w;
}

export function normWorld(w) {
  const o = w && typeof w === "object" ? { ...w } : {};
  for (const k of ["waypoints", "areas", "containers", "zones", "deaths"]) {
    if (!Array.isArray(o[k])) o[k] = [];
  }
  return o;
}

/** Server profile a bot plays on; falls back to the only server when there is one. */
export function serverOf(bot) {
  if (bot && bot.serverId) return bot.serverId;
  return store.servers.length === 1 ? store.servers[0].id : null;
}

function botFor(id) {
  if (!id) return null;
  let b = store.bots.get(id);
  if (!b) {
    b = normBot({ id }, null);
    store.bots.set(id, b);
  }
  return b;
}

/** SSE dispatch. Unknown payload shapes are passed through to listeners untouched. */
export function applyStream(name, data) {
  if (!data || typeof data !== "object") {
    emit(name, data);
    return;
  }
  switch (name) {
    case "bot": {
      const status = data.status && typeof data.status === "object" ? data.status : data.state ? data : null;
      const id = data.botId ?? data.id ?? status?.botId;
      const b = botFor(id);
      if (b) {
        if (status) b.status = status;
        if (data.bot && typeof data.bot === "object") store.bots.set(id, normBot(data.bot, b));
        if (data.deleted) store.bots.delete(id);
      }
      break;
    }
    case "process": {
      const id = data.botId ?? data.id;
      const b = botFor(id);
      if (b) {
        const st = procState(data.process ?? data.state);
        if (st) b.process = st;
        b.processInfo = { ...(b.processInfo || {}), ...data, state: b.process };
        // A stopped client sends nothing more; its last status would read as live.
        if (b.process === "stopped" || b.process === "crashed") b.status = null;
      }
      break;
    }
    case "queue": {
      const id = data.botId ?? data.id;
      const b = botFor(id);
      if (b) b.queue = normQueue(data.queue ?? data, b.queue);
      break;
    }
    case "project": {
      const p = data.project && typeof data.project === "object" ? data.project : data;
      if (!p.id) break;
      if (data.deleted || p.deleted) store.projects.delete(p.id);
      else store.projects.set(p.id, { ...(store.projects.get(p.id) || {}), ...p });
      break;
    }
    case "runtime":
      store.runtime = { ...(store.runtime || {}), ...data };
      break;
    case "world":
      // Only the server id arrives; the next loadWorld() fetches the new document.
      if (data.serverId) store.worlds.delete(data.serverId);
      break;
    case "ai":
      // {type:"status", enabled, mode, ...} after a settings change; feed entries go to the screens as they are.
      if (data.type === "status") store.ai = { ...(store.ai || {}), ...data };
      break;
    case "event":
      store.events.unshift(data);
      if (store.events.length > EVENTS_KEPT) store.events.length = EVENTS_KEPT;
      if (data.kind === "owner_candidate" && data.data) {
        const c = data.data.dismissed ? null : { player: data.data.player, serverId: data.data.serverId, via: data.data.via };
        store.owner = { ...(store.owner || {}), candidate: c };
      } else if (data.kind === "owner_set" && data.data) {
        store.owner = { player: data.data.player, candidate: null };
      }
      break;
    default:
      break;
  }
  emit(name, data);
}

export function botList() {
  return [...store.bots.values()].sort((a, b) => String(a.id).localeCompare(String(b.id)));
}

export function projectList() {
  return [...store.projects.values()].sort((a, b) => String(a.name || a.id).localeCompare(String(b.name || b.id)));
}

export function serverName(id) {
  const s = store.servers.find((x) => x.id === id);
  return s ? s.name || s.id : id;
}

/** Bots that have a known position right now (for "use bot position" helpers). */
export function botsWithPos() {
  return botList().filter((b) => b.status && b.status.pos);
}

/**
 * Effective behaviour thresholds for a bot: the config the manager sent
 * (bot.config) when present, otherwise the global defaults with the bot's
 * overrides on top.
 */
export function behaviourOf(bot) {
  const sent = bot?.config?.behaviour;
  if (sent) return sent;
  const base = store.settings?.config?.behaviour || {};
  return deepMerge(base, bot?.behaviour || {});
}

export function deepMerge(a, b) {
  if (!b || typeof b !== "object" || Array.isArray(b)) return b === undefined ? a : b;
  const out = { ...(a && typeof a === "object" && !Array.isArray(a) ? a : {}) };
  for (const [k, v] of Object.entries(b)) out[k] = deepMerge(out[k], v);
  return out;
}

/** Item ids seen anywhere (bot inventories) for the item <datalist>. */
export function knownItems() {
  const ids = new Set();
  for (const b of store.bots.values()) {
    for (const id of Object.keys(b.status?.items || {})) ids.add(id);
  }
  return [...ids].sort();
}
