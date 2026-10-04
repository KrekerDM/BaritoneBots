// Number, time and id formatting. Numbers are mono with their unit and,
// where a threshold exists, the threshold next to them. Zero prints as
// "0"; a missing value prints as "no data", never as a dash.

import { h } from "./dom.js";
import { t, tid, getLang } from "./i18n.js";

export function isNum(v) {
  return typeof v === "number" && Number.isFinite(v);
}

export function fmt(v, digits = 0) {
  if (!isNum(v)) return null;
  return v.toLocaleString(getLang() === "ru" ? "ru-RU" : "en-US", {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  });
}

export function noData(reasonKey) {
  return h("span", { class: "nodata" }, reasonKey ? t(reasonKey) : t("ui.noData"));
}

/**
 * num(14, {max: 20, unit: "HP", threshold: "flee below 6", bad: true})
 * -> "14 / 20 HP  flee below 6"
 */
export function num(value, { unit, max, digits = 0, threshold, bad, warn } = {}) {
  if (!isNum(value)) return noData();
  const cls = ["num", bad ? "num-bad" : warn ? "num-warn" : ""];
  return h(
    "span",
    null,
    h("span", { class: cls }, fmt(value, digits)),
    isNum(max) ? h("span", { class: "num" }, ` / ${fmt(max, digits)}`) : null,
    unit ? h("span", { class: "num-unit" }, unit) : null,
    threshold ? h("span", { class: "num-threshold" }, threshold) : null,
  );
}

export function pct(fraction, digits = 0) {
  if (!isNum(fraction) || fraction < 0) return noData("ui.progressUnknown");
  return num(fraction * 100, { unit: "%", digits });
}

export function duration(sec) {
  if (!isNum(sec)) return null;
  const s = Math.max(0, Math.round(sec));
  const d = Math.floor(s / 86400);
  const hh = Math.floor((s % 86400) / 3600);
  const mm = Math.floor((s % 3600) / 60);
  const ss = s % 60;
  if (d > 0) return `${d} ${t("unit.d")} ${hh} ${t("unit.h")}`;
  if (hh > 0) return `${hh} ${t("unit.h")} ${String(mm).padStart(2, "0")} ${t("unit.min")}`;
  if (mm > 0) return `${mm} ${t("unit.min")} ${String(ss).padStart(2, "0")} ${t("unit.s")}`;
  return `${ss} ${t("unit.s")}`;
}

export function durationEl(sec) {
  const s = duration(sec);
  return s === null ? noData() : h("span", { class: "num" }, s);
}

export function time(ms) {
  if (!isNum(ms) || ms <= 0) return null;
  const d = new Date(ms);
  const now = new Date();
  const loc = getLang() === "ru" ? "ru-RU" : "en-GB";
  const tm = d.toLocaleTimeString(loc, { hour: "2-digit", minute: "2-digit", second: "2-digit" });
  if (d.toDateString() === now.toDateString()) return tm;
  return `${d.toLocaleDateString(loc, { year: "numeric", month: "2-digit", day: "2-digit" })} ${tm}`;
}

export function timeEl(ms) {
  const s = time(ms);
  return s === null ? noData() : h("span", { class: "num" }, s);
}

export function posText(pos) {
  if (!pos || !isNum(pos.x)) return null;
  return `${Math.floor(pos.x)} ${Math.floor(pos.y)} ${Math.floor(pos.z)}`;
}

export function posEl(pos) {
  const s = posText(pos);
  return s === null ? noData() : h("span", { class: "num" }, s);
}

export function boxText(box) {
  if (!box || !box.a || !box.b) return null;
  return `${posText(box.a)} .. ${posText(box.b)}`;
}

export function dimLabel(dim) {
  if (!dim) return "";
  return t(`dim.${dim}`, null, dim.replace(/^minecraft:/, ""));
}

export function shortId(id) {
  return typeof id === "string" ? id.replace(/^minecraft:/, "") : id;
}

/** "12.4 MB" style for byte counts. */
export function bytes(n) {
  if (!isNum(n)) return noData();
  if (n >= 1024 * 1024 * 1024) return num(n / 1024 / 1024 / 1024, { unit: "GB", digits: 1 });
  if (n >= 1024 * 1024) return num(n / 1024 / 1024, { unit: "MB", digits: 1 });
  if (n >= 1024) return num(n / 1024, { unit: "kB", digits: 1 });
  return num(n, { unit: "B" });
}

export function levelEl(level) {
  const cls = level === "error" ? "st-bad" : level === "warn" ? "st-warn" : "dim";
  return h("span", { class: cls }, tid("level", level));
}

export const PROCESS_OK = new Set(["linked", "online"]);

export function processEl(state) {
  const cls =
    state === "online" ? "st-ok" : state === "crashed" ? "st-bad" : state === "stopped" ? "dim" : "st-warn";
  return h("span", { class: cls }, tid("process", state || "stopped"));
}

export function botStateEl(state) {
  if (!state) return noData();
  const cls =
    state === "online" ? "st-ok" : state === "dead" || state === "disconnected" ? "st-bad" : "st-warn";
  return h("span", { class: cls }, tid("state", state));
}

/** "Waypoint mine", "Where I stand": a position reference (SPEC §5.7e) in words. */
export function refText(v) {
  if (!v || typeof v !== "object" || typeof v.ref !== "string") return null;
  const label = t(`ref.${v.ref}`, null, v.ref).replace(/…$/, "");
  const extra = v.name || v.id;
  return extra ? `${label} ${extra}` : label;
}

function placeText(v) {
  if (!v || typeof v !== "object") return null;
  if (typeof v.ref === "string") return refText(v);
  if ("x" in v && "z" in v) return posText(v);
  if (v.a && v.b) return `${placeText(v.a)} .. ${placeText(v.b)}`;
  return null;
}

/** Short one-line summary of task arguments for queue lists. */
export function argsSummary(args) {
  if (!args || typeof args !== "object") return "";
  const parts = [];
  for (const [k, v] of Object.entries(args)) {
    if (v === undefined || v === null || v === "") continue;
    let s;
    if (placeText(v) !== null) s = placeText(v);
    else if (Array.isArray(v)) s = v.map((x) => (x && typeof x === "object" ? (x.item ? `${shortId(x.item)}×${x.count}` : placeText(x) || JSON.stringify(x)) : shortId(x))).join(", ");
    else if (typeof v === "object") s = JSON.stringify(v);
    else s = shortId(String(v));
    parts.push(`${k}=${s}`);
  }
  const out = parts.join("  ");
  return out.length > 160 ? out.slice(0, 157) + "..." : out;
}
