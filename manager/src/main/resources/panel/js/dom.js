// DOM helpers. Everything user- or server-provided goes in through
// textContent; innerHTML is never used, so bot names, chat lines and
// item ids cannot inject markup.

import { t } from "./i18n.js";

let uid = 0;
export const nextId = (prefix = "f") => `${prefix}-${++uid}`;

/**
 * h("button", {class: "btn", on: {click: fn}, type: "button"}, "text", child)
 * Props: class, text, on, dataset, style (object), any other key becomes an
 * attribute (boolean true = empty attribute, false/null = omitted).
 */
export function h(tag, props, ...children) {
  const el = tag.startsWith("svg:")
    ? document.createElementNS("http://www.w3.org/2000/svg", tag.slice(4))
    : document.createElement(tag);
  let value;
  if (props) {
    for (const [k, v] of Object.entries(props)) {
      if (v === undefined || v === null || v === false) continue;
      if (k === "class") el.setAttribute("class", Array.isArray(v) ? v.filter(Boolean).join(" ") : v);
      else if (k === "text") el.textContent = String(v);
      else if (k === "on") for (const [ev, fn] of Object.entries(v)) el.addEventListener(ev, fn);
      else if (k === "dataset") Object.assign(el.dataset, v);
      else if (k === "style") Object.assign(el.style, v);
      else if (k === "value" && "value" in el) value = v;
      else if (k === "checked" || k === "selected" || k === "disabled" || k === "multiple") {
        el[k] = Boolean(v);
        if (v) el.setAttribute(k, "");
      } else el.setAttribute(k, v === true ? "" : String(v));
    }
  }
  append(el, children);
  // A <select> only accepts a value once its options exist.
  if (value !== undefined) el.value = value;
  return el;
}

export function append(el, children) {
  for (const c of children.flat(Infinity)) {
    if (c === null || c === undefined || c === false) continue;
    el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
  return el;
}

export function clear(el) {
  while (el.firstChild) el.removeChild(el.firstChild);
  return el;
}

export function mount(el, ...children) {
  clear(el);
  return append(el, children);
}

export function btn(label, onClick, { kind = "ghost", small = true, title, disabled, type = "button", ariaLabel } = {}) {
  return h(
    "button",
    {
      type,
      class: ["btn", kind === "primary" ? "btn-primary" : "btn-ghost", small ? "btn-sm" : ""],
      title,
      "aria-label": ariaLabel,
      disabled,
      on: onClick ? { click: onClick } : undefined,
    },
    label,
  );
}

/**
 * Runs an async action from a button: disables it while running, reports
 * the error as a toast, returns the result (or undefined on failure).
 */
export async function busy(button, fn, { toastErrors = true } = {}) {
  if (button) {
    button.disabled = true;
    button.setAttribute("aria-busy", "true");
  }
  try {
    return await fn();
  } catch (e) {
    if (toastErrors) toast("error", errorText(e));
    else throw e;
    return undefined;
  } finally {
    if (button) {
      button.disabled = false;
      button.removeAttribute("aria-busy");
    }
  }
}

export function errorText(e) {
  if (!e) return "";
  const code = e.code || "error";
  const known = t(`error.${code}`, null, "").replace(/[.\s]+$/, "");
  const msg = e.message && e.message !== code ? e.message : "";
  return [code, known, msg].filter(Boolean).join(": ");
}

export function errorBox(e, prefixKey) {
  const code = (e && e.code) || "error";
  const known = t(`error.${code}`, null, "").replace(/[.\s]+$/, "");
  return h(
    "div",
    { class: "error-box", role: "alert" },
    prefixKey ? h("span", null, t(prefixKey), " ") : null,
    h("span", { class: "code" }, e && e.status ? `${e.status} ${code}` : code),
    known ? h("span", null, known, e && e.message && e.message !== code ? ". " : "") : null,
    e && e.message && e.message !== code ? h("span", null, e.message) : null,
  );
}

export function empty(text) {
  return h("p", { class: "empty" }, text);
}

export function table(headers, rows, { caption, className } = {}) {
  const thead = h(
    "thead",
    null,
    h(
      "tr",
      null,
      headers.map((hd) =>
        typeof hd === "string" ? h("th", { scope: "col" }, hd) : h("th", { scope: "col", class: hd.class }, hd.text),
      ),
    ),
  );
  const tbody = h("tbody", null, rows);
  return h(
    "div",
    { class: "table-wrap" },
    h("table", { class: ["table", className] }, caption ? h("caption", { class: "sr-only" }, caption) : null, thead, tbody),
  );
}

export function spec(pairs) {
  const dl = h("dl", { class: "spec" });
  for (const [k, v] of pairs) {
    if (v === undefined) continue;
    dl.append(h("dt", null, k), h("dd", null, v));
  }
  return dl;
}

export function section(title, ...children) {
  return h("section", { class: "section" }, h("div", { class: "head" }, h("h2", { class: "h2" }, title)), children);
}

// ------------------------------------------------------------------
// Toasts
// ------------------------------------------------------------------
const MAX_TOASTS = 4;

export function toast(level, text, { timeoutMs } = {}) {
  const host = document.getElementById("toasts");
  if (!host) return;
  while (host.children.length >= MAX_TOASTS) host.firstElementChild.remove();
  const close = h("button", {
    type: "button",
    class: "btn btn-ghost btn-sm",
    text: t("ui.close"),
  });
  const el = h(
    "div",
    { class: "toast overlay", "data-level": level, role: level === "error" ? "alert" : "status" },
    h("div", { class: "row spread" }, h("span", { class: "label" }, t(`level.${level}`, null, level)), close),
    h("p", { class: "small" }, text),
  );
  close.addEventListener("click", () => el.remove());
  host.append(el);
  const ms = timeoutMs ?? (level === "error" ? 15000 : 8000);
  setTimeout(() => el.remove(), ms);
}

// ------------------------------------------------------------------
// Dialogs (native <dialog>: focus is trapped and Esc closes it)
// ------------------------------------------------------------------
export function dialog(title, body, actions) {
  return new Promise((resolve) => {
    const dlg = h("dialog", { class: "overlay", "aria-labelledby": "dlg-title" });
    const done = (value) => {
      if (dlg.open) dlg.close();
      dlg.remove();
      resolve(value);
    };
    dlg.addEventListener("cancel", (e) => {
      e.preventDefault();
      done(undefined);
    });
    const buttons = actions.map((a) =>
      h(
        "button",
        {
          type: "button",
          class: ["btn", a.primary ? "btn-primary" : "btn-ghost"],
          on: {
            click: async () => {
              if (a.validate && !(await a.validate())) return;
              done(a.value);
            },
          },
        },
        a.label,
      ),
    );
    append(dlg, [
      h("div", { class: "stack" }, h("h2", { class: "h2", id: "dlg-title" }, title), body, h("div", { class: "row" }, buttons)),
    ]);
    document.body.append(dlg);
    dlg.showModal();
  });
}

export function confirmDialog(title, text, confirmLabel) {
  return dialog(title, h("p", { class: "prose" }, text), [
    { label: confirmLabel || t("ui.confirm"), value: true, primary: true },
    { label: t("ui.cancel"), value: false },
  ]).then((v) => v === true);
}

/** Small labelled control wrapper used by every form. */
export function field(labelText, control, { hint, wide, id } = {}) {
  const cid = id || control.id || nextId();
  if (!control.id && control.tagName && /^(INPUT|SELECT|TEXTAREA)$/.test(control.tagName)) control.id = cid;
  const lbl =
    control.tagName && /^(INPUT|SELECT|TEXTAREA)$/.test(control.tagName)
      ? h("label", { class: "label", for: control.id }, labelText)
      : h("span", { class: "label" }, labelText);
  return h("div", { class: ["field", wide ? "field-wide" : ""] }, lbl, control, hint ? h("span", { class: "field-hint" }, hint) : null);
}

export function select(options, value, props = {}) {
  const el = h("select", props);
  for (const o of options) {
    const opt = typeof o === "string" ? { value: o, label: o } : o;
    el.append(h("option", { value: opt.value, selected: String(opt.value) === String(value) }, opt.label));
  }
  if (value !== undefined && value !== null) el.value = String(value);
  return el;
}

export function checkbox(labelText, checked, props = {}) {
  const input = h("input", { type: "checkbox", checked, ...props });
  return { input, el: h("label", { class: "check" }, input, h("span", null, labelText)) };
}

/** Coalesces bursts of updates (SSE status every second per bot) into one call per interval. */
export function throttle(fn, ms) {
  let timer = null;
  let pending = false;
  return () => {
    if (timer) {
      pending = true;
      return;
    }
    fn();
    timer = setTimeout(function tick() {
      timer = null;
      if (pending) {
        pending = false;
        fn();
        timer = setTimeout(tick, ms);
      }
    }, ms);
  };
}
