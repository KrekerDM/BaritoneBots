// Translations: the panel's own keys (i18n/<lang>.json, static) with the
// manager's keys from /api/i18n/<lang> merged on top, so labels for task
// types, settings fields and roles follow the running manager version.

const LANG_KEY = "bb.lang";
export const LANGS = ["ru", "en"];

let lang = "ru";
let dict = {};

export function getLang() {
  return lang;
}

export function initialLang() {
  let saved = null;
  try {
    saved = localStorage.getItem(LANG_KEY);
  } catch {
    saved = null;
  }
  if (LANGS.includes(saved)) return saved;
  const nav = (navigator.language || "en").toLowerCase();
  return nav.startsWith("ru") ? "ru" : "en";
}

/**
 * Loads both dictionaries. `apiGet` is optional: without a token only the
 * static file is used. Returns the error of the API part, if any, so the
 * caller can show that manager labels are missing.
 */
export async function loadLang(next, apiGet) {
  const staticDict = await fetch(`i18n/${next}.json`, { cache: "no-cache" })
    .then((r) => (r.ok ? r.json() : {}))
    .catch(() => ({}));
  let apiDict = {};
  let apiError = null;
  if (apiGet) {
    try {
      const got = await apiGet(`/api/i18n/${next}`);
      apiDict = flatten(got && typeof got === "object" ? got.strings || got : {});
    } catch (e) {
      apiError = e;
    }
  }
  dict = { ...flatten(staticDict), ...apiDict };
  lang = next;
  try {
    localStorage.setItem(LANG_KEY, next);
  } catch {
    /* private mode: the choice lasts for this page only */
  }
  document.documentElement.lang = next;
  return apiError;
}

// Accepts both flat {"a.b": "x"} and nested {"a": {"b": "x"}} files.
function flatten(obj, prefix = "", out = {}) {
  for (const [k, v] of Object.entries(obj || {})) {
    const key = prefix ? `${prefix}.${k}` : k;
    if (v && typeof v === "object" && !Array.isArray(v)) flatten(v, key, out);
    else if (typeof v === "string") out[key] = v;
  }
  return out;
}

export function has(key) {
  return Object.prototype.hasOwnProperty.call(dict, key);
}

/**
 * t("bots.count", {n: 3}) -> "Ботов: 3". Missing keys return `fallback`
 * when given, otherwise the key itself, so a gap is visible instead of blank.
 */
export function t(key, vars, fallback) {
  let s = has(key) ? dict[key] : fallback !== undefined ? fallback : key;
  if (vars && typeof s === "string") {
    s = s.replace(/\{(\w+)\}/g, (m, name) => (vars[name] === undefined || vars[name] === null ? m : String(vars[name])));
  }
  return s;
}

/** Label for an identifier that may or may not have a key: shows the raw id when untranslated. */
export function tid(prefix, id) {
  if (id === undefined || id === null || id === "") return "";
  return t(`${prefix}.${id}`, null, String(id));
}

export function applyStatic(root = document) {
  for (const el of root.querySelectorAll("[data-i18n]")) el.textContent = t(el.dataset.i18n, null, el.textContent);
  for (const el of root.querySelectorAll("[data-i18n-aria]")) el.setAttribute("aria-label", t(el.dataset.i18nAria, null, el.getAttribute("aria-label")));
  for (const btn of root.querySelectorAll("[data-lang]")) btn.setAttribute("aria-pressed", String(btn.dataset.lang === lang));
}
