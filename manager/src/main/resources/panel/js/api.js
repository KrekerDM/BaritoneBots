// HTTP API client (SPEC §6) and the SSE stream with reconnect backoff.

const TOKEN_KEY = "bb.token";
const DEFAULT_TIMEOUT_MS = 20000;
const UPLOAD_TIMEOUT_MS = 180000;
const BACKOFF_MS = [1000, 2000, 4000, 8000, 15000, 30000];

export const STREAM_EVENTS = ["bot", "queue", "process", "event", "project", "runtime", "log", "world"];

let memoryToken = null;

/**
 * The manager opens the panel as /#token=<t>. The token moves to
 * localStorage and leaves the address bar, so it does not end up in
 * history, bookmarks or screenshots.
 */
export function takeTokenFromHash() {
  const hash = location.hash || "";
  const m = hash.match(/[#&?]token=([^&]+)/);
  if (!m) return false;
  setToken(decodeURIComponent(m[1]));
  let rest = hash.replace(/[#&?]?token=[^&]+&?/, "");
  if (rest === "" || rest === "#" || rest === "#/") rest = "#/bots";
  if (!rest.startsWith("#")) rest = "#" + rest;
  history.replaceState(null, "", location.pathname + location.search + rest);
  return true;
}

export function getToken() {
  if (memoryToken) return memoryToken;
  try {
    return localStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

export function setToken(token) {
  memoryToken = token || null;
  try {
    if (token) localStorage.setItem(TOKEN_KEY, token);
    else localStorage.removeItem(TOKEN_KEY);
  } catch {
    /* storage blocked: the token lives in memory until reload */
  }
}

export class ApiError extends Error {
  constructor(status, code, message, body) {
    super(message || code);
    this.status = status;
    this.code = code;
    this.body = body;
  }
}

function buildUrl(path, query) {
  if (!query) return path;
  const params = new URLSearchParams();
  for (const [k, v] of Object.entries(query)) {
    if (v === undefined || v === null || v === "") continue;
    params.set(k, String(v));
  }
  const qs = params.toString();
  return qs ? `${path}${path.includes("?") ? "&" : "?"}${qs}` : path;
}

export async function request(method, path, { body, raw, query, timeoutMs } = {}) {
  const headers = {};
  const token = getToken();
  if (token) headers.Authorization = `Bearer ${token}`;
  let payload;
  if (raw !== undefined) {
    payload = raw;
    headers["Content-Type"] = "application/octet-stream";
  } else if (body !== undefined) {
    payload = JSON.stringify(body);
    headers["Content-Type"] = "application/json";
  }
  const ctrl = new AbortController();
  const limit = timeoutMs ?? (raw !== undefined ? UPLOAD_TIMEOUT_MS : DEFAULT_TIMEOUT_MS);
  const timer = setTimeout(() => ctrl.abort(), limit);
  let res;
  try {
    res = await fetch(buildUrl(path, query), { method, headers, body: payload, signal: ctrl.signal, cache: "no-store" });
  } catch (e) {
    clearTimeout(timer);
    if (e && e.name === "AbortError") throw new ApiError(0, "timeout", `${method} ${path}: ${limit / 1000} s`);
    throw new ApiError(0, "network", e && e.message ? e.message : String(e));
  }
  clearTimeout(timer);
  const type = res.headers.get("content-type") || "";
  let data = null;
  if (res.status !== 204) {
    const text = await res.text();
    if (text && type.includes("json")) {
      try {
        data = JSON.parse(text);
      } catch {
        data = text;
      }
    } else data = text || null;
  }
  if (!res.ok) {
    const code = data && typeof data === "object" && data.error ? String(data.error) : `http_${res.status}`;
    const message = data && typeof data === "object" ? data.message : typeof data === "string" ? data.slice(0, 300) : "";
    throw new ApiError(res.status, code, message, data);
  }
  return data;
}

export const api = {
  get: (path, query) => request("GET", path, { query }),
  post: (path, body, query) => request("POST", path, { body: body === undefined ? {} : body, query }),
  put: (path, body) => request("PUT", path, { body }),
  del: (path) => request("DELETE", path),
  upload: (path, file, query) => request("POST", path, { raw: file, query }),
};

/** Unwraps list responses that may come as a bare array or as {key: [...]}. */
export function listOf(data, key) {
  if (Array.isArray(data)) return data;
  if (data && typeof data === "object") {
    if (Array.isArray(data[key])) return data[key];
    if (Array.isArray(data.items)) return data.items;
  }
  return [];
}

export const enc = encodeURIComponent;

// ------------------------------------------------------------------
// SSE stream
// ------------------------------------------------------------------

/**
 * Keeps one EventSource open. On any error the source is closed and a new
 * one is opened after 1, 2, 4, 8, 15, 30, 30 ... seconds; `onState` gets
 * {state, attempt, retryAt}. `onOpen` runs on every (re)connect so the
 * caller can reload the snapshot it may have missed.
 */
export function connectStream({ onMessage, onState, onOpen, onAuthFailed }) {
  let source = null;
  let attempt = 0;
  let timer = null;
  let stopped = false;

  const open = () => {
    const token = getToken();
    if (!token) {
      onState({ state: "no_token", attempt });
      return;
    }
    // ?static: one snapshot, no live stream. Headless screenshots need it, because an open
    // EventSource keeps the network busy and the browser's virtual clock never advances.
    if (new URLSearchParams(location.search).has("static")) {
      onState({ state: "open", attempt, since: Date.now() });
      onOpen && onOpen();
      return;
    }
    onState({ state: "connecting", attempt });
    source = new EventSource(`/api/stream?token=${encodeURIComponent(token)}`);
    source.onopen = () => {
      attempt = 0;
      onState({ state: "open", attempt, since: Date.now() });
      onOpen && onOpen();
    };
    source.onerror = () => {
      if (stopped) return;
      source.close();
      source = null;
      scheduleRetry();
    };
    for (const name of STREAM_EVENTS) {
      source.addEventListener(name, (ev) => {
        let data;
        try {
          data = JSON.parse(ev.data);
        } catch {
          data = ev.data;
        }
        onMessage(name, data);
      });
    }
  };

  const scheduleRetry = async () => {
    // EventSource does not expose the HTTP status; a cheap probe tells a
    // rejected token apart from a manager that is down.
    try {
      await request("GET", "/api/runtime", { timeoutMs: 5000 });
    } catch (e) {
      if (e.status === 401 || e.status === 403) {
        onState({ state: "closed", attempt, reason: "unauthorized" });
        onAuthFailed && onAuthFailed(e);
        return;
      }
    }
    const delay = BACKOFF_MS[Math.min(attempt, BACKOFF_MS.length - 1)];
    attempt += 1;
    onState({ state: "retry", attempt, retryAt: Date.now() + delay });
    timer = setTimeout(open, delay);
  };

  open();

  return {
    close() {
      stopped = true;
      clearTimeout(timer);
      if (source) source.close();
      onState({ state: "closed", attempt });
    },
    reconnectNow() {
      clearTimeout(timer);
      if (source) source.close();
      attempt = 0;
      open();
    },
  };
}
