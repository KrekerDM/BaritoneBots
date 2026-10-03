// Entry point: token, language, snapshot, SSE and the hash router.

import { api, takeTokenFromHash, getToken, setToken, connectStream } from "./js/api.js";
import { loadLang, initialLang, applyStatic, t, tid, getLang } from "./js/i18n.js";
import { h, mount, toast, errorBox, field } from "./js/dom.js";
import { store, subscribe, loadState, loadCatalog, loadSettings, loadKits, applyStream } from "./js/store.js";
import { refreshDatalists } from "./js/forms.js";
import * as botsScreen from "./js/screens/bots.js";
import * as botScreen from "./js/screens/bot.js";
import * as scenariosScreen from "./js/screens/scenarios.js";
import * as projectsScreen from "./js/screens/projects.js";
import * as projectScreen from "./js/screens/project.js";
import * as worldScreen from "./js/screens/world.js";
import * as kitsScreen from "./js/screens/kits.js";
import * as settingsScreen from "./js/screens/settings.js";
import * as eventsScreen from "./js/screens/events.js";

const ROUTES = [
  { re: /^\/bots\/([^/]+)$/, nav: "bots", screen: botScreen, keys: ["id"] },
  { re: /^\/bots\/?$/, nav: "bots", screen: botsScreen, keys: [] },
  { re: /^\/scenarios(?:\/([^/]+))?$/, nav: "scenarios", screen: scenariosScreen, keys: ["id"] },
  { re: /^\/projects\/?$/, nav: "projects", screen: projectsScreen, keys: [] },
  { re: /^\/projects\/new$/, nav: "projects", screen: projectsScreen, keys: [], extra: { create: true } },
  { re: /^\/projects\/([^/]+)$/, nav: "projects", screen: projectScreen, keys: ["id"] },
  { re: /^\/world(?:\/([^/]+))?$/, nav: "world", screen: worldScreen, keys: ["serverId"] },
  { re: /^\/kits(?:\/([^/]+))?$/, nav: "kits", screen: kitsScreen, keys: ["id"] },
  { re: /^\/settings(?:\/([^/]+))?$/, nav: "settings", screen: settingsScreen, keys: ["tab"] },
  { re: /^\/events\/?$/, nav: "events", screen: eventsScreen, keys: [] },
];

const view = () => document.getElementById("view");
let current = null;
let stream = null;
let connState = { state: "connecting", attempt: 0 };
let connTimer = null;
let started = false;

function parseHash() {
  const raw = (location.hash || "").replace(/^#/, "");
  const [path, qs] = raw.split("?");
  return { path: path || "/bots", query: new URLSearchParams(qs || "") };
}

export function navigate(path) {
  if (location.hash !== `#${path}`) location.hash = path;
  else route();
}

function route() {
  if (!getToken()) {
    renderTokenScreen();
    return;
  }
  const { path, query } = parseHash();
  let match = null;
  let params = {};
  for (const r of ROUTES) {
    const m = path.match(r.re);
    if (m) {
      match = r;
      params = { ...(r.extra || {}) };
      r.keys.forEach((k, i) => {
        if (m[i + 1] !== undefined) params[k] = decodeURIComponent(m[i + 1]);
      });
      break;
    }
  }
  for (const [k, v] of query) params[k] = v;
  if (current && current.destroy) current.destroy();
  current = null;
  for (const a of document.querySelectorAll("#nav a")) {
    if (match && a.dataset.route === match.nav) a.setAttribute("aria-current", "page");
    else a.removeAttribute("aria-current");
  }
  const root = view();
  if (!match) {
    mount(root, h("div", { class: "stack" }, h("h1", { class: "h1" }, t("ui.notFound")), h("p", { class: "prose" }, path)));
    return;
  }
  try {
    current = match.screen.render(root, params, { navigate }) || null;
  } catch (e) {
    console.error(e);
    mount(root, errorBox({ code: "panel_error", message: e.message }));
  }
  document.title = `${t(`nav.${match.nav}`)} · BaritoneBots`;
}

function renderTokenScreen(error) {
  if (current && current.destroy) current.destroy();
  current = null;
  const input = h("input", { type: "password", autocomplete: "off", spellcheck: "false" });
  const form = h(
    "form",
    { class: "stack" },
    field(t("token.field"), input, { hint: t("token.hint") }),
    h("div", { class: "row" }, h("button", { type: "submit", class: "btn btn-primary" }, t("token.save"))),
  );
  form.addEventListener("submit", (e) => {
    e.preventDefault();
    const v = input.value.trim();
    if (!v) return;
    setToken(v);
    restart();
  });
  mount(
    view(),
    h(
      "div",
      { class: "stack", style: { maxWidth: "36rem" } },
      h("h1", { class: "h1" }, t("token.title")),
      h("p", { class: "prose" }, t("token.text")),
      error ? errorBox(error) : null,
      form,
    ),
  );
  input.focus();
}

function showConn() {
  const el = document.getElementById("conn");
  const st = document.getElementById("conn-state");
  const detail = document.getElementById("conn-detail");
  if (!el) return;
  el.dataset.state = connState.state;
  st.textContent = t(`conn.${connState.state}`, null, connState.state);
  let d = "";
  if (connState.state === "retry" && connState.retryAt) {
    const s = Math.max(0, Math.ceil((connState.retryAt - Date.now()) / 1000));
    d = t("conn.retryIn", { s, n: connState.attempt });
  } else if (connState.reason) {
    d = t(`error.${connState.reason}`, null, connState.reason);
  }
  detail.textContent = d;
  const action = document.getElementById("conn-action");
  const canRetry = stream && (connState.state === "retry" || (connState.state === "closed" && connState.reason !== "unauthorized"));
  if (canRetry && !action.firstChild) {
    action.append(
      h("button", { type: "button", class: "btn btn-ghost btn-sm", on: { click: () => stream && stream.reconnectNow() } }, t("conn.retryNow")),
    );
  } else if (!canRetry) action.replaceChildren();
  clearTimeout(connTimer);
  if (connState.state === "retry") connTimer = setTimeout(showConn, 1000);
}

function onStreamMessage(name, data) {
  applyStream(name, data);
  if (name === "event" && data && (data.level === "warn" || data.level === "error")) {
    const bot = data.botId ? store.bots.get(data.botId) : null;
    const who = bot ? bot.username || bot.id : data.botId || data.projectId || "";
    const kind = data.kind ? tid("event", data.kind) : "";
    toast(data.level, [who, kind, eventsScreen.eventText(data)].filter(Boolean).join(": "));
  }
}

async function loadSnapshot() {
  try {
    await loadState();
    refreshDatalists();
    return null;
  } catch (e) {
    return e;
  }
}

async function start() {
  const err = await loadSnapshot();
  if (err && (err.status === 401 || err.status === 403)) {
    setToken(null);
    renderTokenScreen(err);
    return;
  }
  // Catalog, settings and kits feed forms and thresholds; a failure here
  // leaves the affected forms showing the error instead of blocking the panel.
  loadCatalog().catch((e) => console.warn("catalog", e));
  loadSettings()
    .then(refreshDatalists)
    .catch((e) => console.warn("settings", e));
  loadKits().catch((e) => console.warn("kits", e));

  if (stream) stream.close();
  stream = connectStream({
    onMessage: onStreamMessage,
    onState: (s) => {
      connState = s;
      showConn();
    },
    onOpen: async () => {
      // Events sent while disconnected are lost; the snapshot fills the gap.
      if (started) await loadSnapshot();
      started = true;
    },
    onAuthFailed: (e) => {
      setToken(null);
      renderTokenScreen(e);
    },
  });
  route();
  if (err) toast("error", `${t("ui.snapshotFailed")}: ${err.code} ${err.message || ""}`);
}

async function restart() {
  started = false;
  await loadLang(getLang(), getToken() ? api.get : null);
  applyStatic();
  start();
}

function wireHeader() {
  for (const b of document.querySelectorAll("[data-lang]")) {
    b.addEventListener("click", async () => {
      const apiErr = await loadLang(b.dataset.lang, getToken() ? api.get : null);
      applyStatic();
      showConn();
      route();
      if (apiErr) toast("warn", `${t("ui.i18nApiFailed")}: ${apiErr.code}`);
    });
  }
}

subscribe((type, data) => {
  if (type === "snapshot" || type === "bot") refreshDatalistsLater();
  if (current && current.update) {
    try {
      current.update(type, data);
    } catch (e) {
      console.error("screen update failed", e);
    }
  }
});

let dlTimer = null;
function refreshDatalistsLater() {
  if (dlTimer) return;
  dlTimer = setTimeout(() => {
    dlTimer = null;
    refreshDatalists();
  }, 5000);
}

window.addEventListener("hashchange", () => {
  if (takeTokenFromHash()) {
    restart();
    return;
  }
  route();
});

async function boot() {
  takeTokenFromHash();
  const apiErr = await loadLang(initialLang(), getToken() ? api.get : null);
  applyStatic();
  wireHeader();
  showConn();
  if (!getToken()) {
    connState = { state: "no_token", attempt: 0 };
    showConn();
    renderTokenScreen();
    return;
  }
  if (apiErr && apiErr.status !== 401) console.warn("i18n api", apiErr);
  start();
}

boot();
