// Position, container and box pickers (SPEC §5.7e). A field that takes
// coordinates offers references first ("home", "where I stand", "where I
// look", a bot, a waypoint, an area, automatic detection); x y z are the
// last option. The manager resolves a reference when the work starts, so
// a scenario step "go to me" follows the owner wherever they are.

import { h, btn } from "./dom.js";
import { t } from "./i18n.js";
import { store, botList, loadWorld } from "./store.js";
import { posText, dimLabel, shortId } from "./format.js";

// Arguments the manager can detect by itself (manager/refs/AutoDetect):
// fields, pens and the build site, by task type or project kind; every
// container list besides.
const AUTO_ARGS = {
  farm: ["center", "box"],
  breed: ["box"],
  slaughter: ["box"],
  shear: ["box"],
  ranch: ["box"],
  build: ["origin"],
};

const SIMPLE = new Set(["home", "owner", "owner_look", "auto"]);

export function isRef(v) {
  return !!v && typeof v === "object" && !Array.isArray(v) && typeof v.ref === "string";
}

export function autoAvailable(what, arg, type) {
  if (type === "containers") return true;
  const w = String(what || "").replace(/^project:/, "");
  return Object.prototype.hasOwnProperty.call(AUTO_ARGS, w) && AUTO_ARGS[w].includes(arg);
}

export function ownerName() {
  return store.settings?.config?.general?.ownerPlayer || "";
}

function worldOf(ctx) {
  const sid = ctx.serverId && ctx.serverId();
  return sid ? store.worlds.get(sid) || null : null;
}

/** Fetches the world of the form's server once; `then` refills the lists. */
function ensureWorld(ctx, then) {
  const sid = ctx.serverId && ctx.serverId();
  if (!sid || store.worlds.has(sid)) return;
  loadWorld(sid).then(then, () => {});
}

// ------------------------------------------------------------------
// Coordinates (the "manual" option and the world screen)
// ------------------------------------------------------------------
export function posInputs() {
  const mk = (axis) => h("input", { type: "number", step: "1", inputmode: "numeric", "aria-label": axis, placeholder: axis });
  const x = mk("x");
  const y = mk("y");
  const z = mk("z");
  const wrap = h("div", { class: "pos-inputs" }, x, y, z);
  return {
    wrap,
    get() {
      const vals = [x, y, z].map((i) => i.value.trim());
      if (vals.every((v) => v === "")) return { value: undefined };
      if (vals.some((v) => v === "")) return { error: t("form.err.posPartial") };
      const n = vals.map(Number);
      if (n.some((v) => !Number.isInteger(v))) return { error: t("form.err.int") };
      return { value: { x: n[0], y: n[1], z: n[2] } };
    },
    set(p) {
      x.value = p && p.x !== undefined ? String(Math.floor(p.x)) : "";
      y.value = p && p.y !== undefined ? String(Math.floor(p.y)) : "";
      z.value = p && p.z !== undefined ? String(Math.floor(p.z)) : "";
    },
  };
}

/** Button that copies the reference bot's block position into `set`. */
export function botPosButton(ctx, set, labelKey = "form.useBotPos", onDim) {
  const note = h("span", { class: "field-hint", "aria-live": "polite" });
  const b = btn(t(labelKey), () => {
    const bot = ctx.refBot && ctx.refBot();
    const pos = bot?.status?.pos;
    if (!pos) {
      note.textContent = t("form.noBotPos");
      return;
    }
    note.textContent = t("form.tookPos", { bot: bot.username || bot.id, pos: posText(pos) });
    set({ x: Math.floor(pos.x), y: Math.floor(pos.y), z: Math.floor(pos.z) });
    // Read at click time: forms assign ctx.onBotDim after their fields exist.
    const dimCb = onDim || ctx.onBotDim;
    if (dimCb && bot.status.dim) dimCb(bot.status.dim);
  });
  return h("span", { class: "row-sm" }, b, note);
}

export function containerLabel(c) {
  const roles = c.roles?.length ? ` · ${c.roles.join(", ")}` : "";
  return `${c.label || shortId(c.block) || ""} ${posText(c.pos)} ${dimLabel(c.dim)}${roles}`.trim();
}

const samePos = (a, b) => !!a && !!b && Math.floor(a.x) === Math.floor(b.x) && Math.floor(a.y) === Math.floor(b.y) && Math.floor(a.z) === Math.floor(b.z);

/** Select of indexed containers; `onPick(pos)` gets the container position. */
export function knownContainerSelect(ctx, onPick) {
  const sel = h("select", { "aria-label": t("form.knownContainer") });
  const fill = () => {
    const list = worldOf(ctx)?.containers || [];
    sel.replaceChildren(
      h("option", { value: "" }, list.length ? t("form.pickContainer") : t("form.noContainers")),
      ...list.map((c, i) => h("option", { value: String(i) }, containerLabel(c))),
    );
  };
  fill();
  ensureWorld(ctx, fill);
  sel.addEventListener("focus", fill);
  sel.addEventListener("change", () => {
    const c = (worldOf(ctx)?.containers || [])[Number(sel.value)];
    if (sel.value !== "" && c) onPick(c.pos);
    sel.value = "";
  });
  return sel;
}

// ------------------------------------------------------------------
// The picker
// ------------------------------------------------------------------

function modesFor(arg, ctx) {
  const type = arg.type;
  const auto = autoAvailable(ctx.what, arg.name, type);
  let list;
  if (type === "box") list = ["area", "auto", "two", "manual"];
  else if (type === "containers") list = ["auto", "known", "owner_look", "manual"];
  else if (type === "container") list = ["known", "owner_look", "waypoint", "home", "bot", "manual"];
  else list = ["home", "owner", "owner_look", "bot", "waypoint", "auto", "manual"];
  if (!auto) list = list.filter((m) => m !== "auto");
  if (Array.isArray(arg.refKinds)) list = list.filter((m) => arg.refKinds.includes(m));
  if (!arg.required && !arg.primary) list.unshift("none");
  return list;
}

function modeLabel(m, type) {
  if (m === "none") return t("pick.none");
  if (m === "known") return type === "containers" ? t("pick.knownMany") : t("pick.known");
  return t(`ref.${m}`, null, m);
}

/**
 * arg: {name, type: pos|container|containers|box, required, primary, refKinds?, corner?}
 * ctx: {serverId(), refBot(), what (task type or "project:<kind>"), onBotDim}
 * Returns {control, wide, get() -> {value}|{error}, set(v)}.
 */
export function refPicker(arg, ctx) {
  const type = arg.type;
  const modes = modesFor(arg, ctx);
  const sel = h("select", { class: "picker-mode" });
  for (const m of modes) sel.append(h("option", { value: m }, modeLabel(m, type)));
  const hint = h("p", { class: "field-hint", "aria-live": "polite" });
  const parts = {};
  let touched = false;

  // -- one control per option that needs more than the option itself
  if (modes.includes("bot")) {
    const s = h("select", { "aria-label": t("pick.botLabel") });
    const fill = () => {
      const cur = s.value;
      s.replaceChildren(
        ...botList().map((b) => h("option", { value: b.id }, `${b.username || b.id}${b.status?.pos ? ` · ${posText(b.status.pos)}` : ` · ${t("pick.noPos")}`}`)),
      );
      if (cur && [...s.options].some((o) => o.value === cur)) s.value = cur;
    };
    fill();
    s.addEventListener("focus", fill);
    parts.bot = {
      el: s,
      get: () => (s.value ? { value: { ref: "bot", id: s.value } } : { error: t("pick.err.bot") }),
      set(v) {
        fill();
        if (v?.id && ![...s.options].some((o) => o.value === v.id)) s.append(h("option", { value: v.id }, v.id));
        if (v?.id) s.value = v.id;
      },
    };
  }

  const namedSelect = (listKey, emptyKey, refKind, errKey) => {
    const s = h("select", { "aria-label": t(`pick.${refKind}Label`) });
    let wanted = "";
    const fill = () => {
      const cur = s.value || wanted;
      const list = worldOf(ctx)?.[listKey] || [];
      s.replaceChildren(
        ...(list.length ? [] : [h("option", { value: "" }, t(emptyKey))]),
        ...list.map((x) => h("option", { value: x.name }, x.name === "home" ? `${x.name} (${t("world.home")})` : x.name)),
      );
      if (cur && ![...s.options].some((o) => o.value === cur)) s.append(h("option", { value: cur }, cur));
      if (cur) s.value = cur;
      showHint();
    };
    s.addEventListener("focus", fill);
    return {
      el: s,
      fill,
      get: () => (s.value ? { value: { ref: refKind, name: s.value } } : { error: t(errKey) }),
      set(v) {
        wanted = v?.name || "";
        fill();
      },
    };
  };
  if (modes.includes("waypoint")) parts.waypoint = namedSelect("waypoints", "pick.noWaypoints", "waypoint", "pick.err.waypoint");
  if (modes.includes("area")) parts.area = namedSelect("areas", "pick.noAreas", "area", "pick.err.area");

  if (modes.includes("known") && type === "container") {
    const s = h("select", { "aria-label": t("form.knownContainer") });
    let wanted = null;
    const fill = () => {
      const list = worldOf(ctx)?.containers || [];
      const curPos = s.value !== "" && list[Number(s.value)] ? list[Number(s.value)].pos : wanted;
      s.replaceChildren(
        ...(list.length ? [] : [h("option", { value: "" }, t("form.noContainers"))]),
        ...list.map((c, i) => h("option", { value: String(i) }, containerLabel(c))),
      );
      const idx = curPos ? list.findIndex((c) => samePos(c.pos, curPos)) : -1;
      if (idx >= 0) s.value = String(idx);
      showHint();
    };
    s.addEventListener("focus", fill);
    parts.known = {
      el: s,
      fill,
      get() {
        const c = (worldOf(ctx)?.containers || [])[Number(s.value)];
        return s.value !== "" && c ? { value: { x: c.pos.x, y: c.pos.y, z: c.pos.z } } : { error: t("pick.err.container") };
      },
      set(v) {
        wanted = v;
        fill();
      },
    };
  }

  if (modes.includes("known") && type === "containers") {
    const box = h("div", { class: "stack-sm picker-list" });
    let checked = [];
    const fill = () => {
      const list = worldOf(ctx)?.containers || [];
      const keep = box.childElementCount ? [...box.querySelectorAll("input:checked")].map((i) => list[Number(i.value)]?.pos).filter(Boolean) : checked;
      box.replaceChildren(
        ...(list.length
          ? list.map((c, i) => h("label", { class: "check" }, h("input", { type: "checkbox", value: String(i), checked: keep.some((p) => samePos(p, c.pos)) }), h("span", { class: "small" }, containerLabel(c))))
          : [h("span", { class: "nodata" }, t("form.noContainers"))]),
      );
      showHint();
    };
    parts.known = {
      el: box,
      fill,
      get() {
        const list = worldOf(ctx)?.containers || [];
        const picked = [...box.querySelectorAll("input:checked")].map((i) => list[Number(i.value)]?.pos).filter(Boolean);
        return picked.length ? { value: picked.map((p) => ({ x: p.x, y: p.y, z: p.z })) } : { error: t("pick.err.containers") };
      },
      set(v) {
        checked = Array.isArray(v) ? v : [];
        box.replaceChildren();
        fill();
      },
    };
  }

  if (modes.includes("manual")) {
    if (type === "box") {
      const a = posInputs();
      const b = posInputs();
      parts.manual = {
        el: h(
          "div",
          { class: "stack-sm" },
          h("span", { class: "field-hint" }, t("form.cornerA")),
          a.wrap,
          botPosButton(ctx, a.set, "form.useBotPosA"),
          h("span", { class: "field-hint" }, t("form.cornerB")),
          b.wrap,
          botPosButton(ctx, b.set, "form.useBotPosB"),
        ),
        get() {
          const ra = a.get();
          const rb = b.get();
          if (ra.error) return ra;
          if (rb.error) return rb;
          if (ra.value === undefined || rb.value === undefined) return { error: t("form.err.boxPartial") };
          return { value: { a: ra.value, b: rb.value } };
        },
        set(v) {
          a.set(v?.a);
          b.set(v?.b);
        },
      };
    } else if (type === "containers") {
      const area = h("textarea", { rows: 3, spellcheck: "false", placeholder: "100 64 -20\n102 64 -20", "aria-label": t("ref.manual") });
      parts.manual = {
        el: h(
          "div",
          { class: "stack-sm" },
          area,
          h("span", { class: "field-hint" }, t("form.hint.positions")),
          knownContainerSelect(ctx, (pos) => {
            const line = posText(pos);
            area.value = area.value.trim() ? `${area.value.trim()}\n${line}` : line;
          }),
        ),
        get() {
          const out = [];
          for (const line of area.value.split(/\n|;/).map((l) => l.trim()).filter(Boolean)) {
            const n = line.split(/[\s,]+/).map(Number);
            if (n.length !== 3 || n.some((v) => !Number.isInteger(v))) return { error: t("form.err.posLine", { line }) };
            out.push({ x: n[0], y: n[1], z: n[2] });
          }
          return out.length ? { value: out } : { error: t("form.err.required") };
        },
        set(v) {
          area.value = Array.isArray(v) ? v.filter((p) => !isRef(p)).map(posText).join("\n") : "";
        },
      };
    } else {
      const p = posInputs();
      parts.manual = {
        el: h("div", { class: "stack-sm" }, p.wrap, botPosButton(ctx, p.set)),
        get() {
          const r = p.get();
          if (r.error) return r;
          return r.value === undefined ? { error: t("form.err.posPartial") } : r;
        },
        set: p.set,
      };
    }
  }

  if (modes.includes("two")) {
    const cornerKinds = ["home", "owner", "owner_look", "bot", "waypoint", "manual"];
    const a = refPicker({ name: `${arg.name}.a`, type: "pos", required: true, refKinds: cornerKinds, corner: "a" }, ctx);
    const b = refPicker({ name: `${arg.name}.b`, type: "pos", required: true, refKinds: cornerKinds, corner: "b" }, ctx);
    parts.two = {
      el: h("div", { class: "stack-sm picker-two" }, h("span", { class: "label" }, t("form.cornerA")), a.control, h("span", { class: "label" }, t("form.cornerB")), b.control),
      get() {
        const ra = a.get();
        const rb = b.get();
        if (ra.error) return ra;
        if (rb.error) return rb;
        return { value: { a: ra.value, b: rb.value } };
      },
      set(v) {
        a.set(v?.a);
        b.set(v?.b);
      },
    };
  }

  const body = h("div", { class: "picker-body" }, Object.values(parts).map((p) => p.el));

  function showHint() {
    const m = sel.value;
    const owner = ownerName();
    const w = worldOf(ctx);
    let text = t(`pick.hint.${m}`, { owner: owner || "?" }, "");
    if ((m === "owner" || m === "owner_look") && !owner) text = t("pick.hint.noOwner");
    else if (m === "waypoint" && w && !w.waypoints.length) text = t("pick.hint.noWaypoints");
    else if (m === "area" && w && !w.areas.length) text = t("pick.hint.noAreas");
    else if (m === "home" && w && !w.waypoints.some((x) => x.name === "home")) text = `${text} ${t("pick.hint.noHome")}`;
    hint.textContent = text;
    hint.classList.toggle("st-warn", (m === "owner" || m === "owner_look") && !owner);
  }

  function setMode(m) {
    if (![...sel.options].some((o) => o.value === m)) sel.append(h("option", { value: m }, modeLabel(m, type)));
    sel.value = m;
    for (const [k, p] of Object.entries(parts)) p.el.hidden = k !== m;
    if (parts[m] && parts[m].fill) parts[m].fill();
    body.hidden = !parts[m];
    showHint();
  }

  function defaultMode() {
    if (modes.includes("none")) return "none";
    if (arg.corner === "a" && modes.includes("owner")) return "owner";
    if (arg.corner === "b" && modes.includes("owner_look")) return "owner_look";
    if (modes.includes("auto")) return "auto";
    const w = worldOf(ctx);
    if (type === "container") return w && w.containers.length && modes.includes("known") ? "known" : modes.includes("owner_look") ? "owner_look" : modes[0];
    if (type === "box") return w && w.areas.length && modes.includes("area") ? "area" : modes.includes("two") ? "two" : modes[0];
    if (type === "pos" && modes.includes("owner")) return "owner";
    return modes[0];
  }

  sel.addEventListener("change", () => {
    touched = true;
    setMode(sel.value);
  });
  sel.addEventListener("focus", () => ensureWorld(ctx, () => setMode(sel.value)));
  ensureWorld(ctx, () => {
    if (!touched) setMode(defaultMode());
    else setMode(sel.value);
  });
  setMode(defaultMode());

  return {
    control: h("div", { class: "stack-sm picker" }, sel, body, hint),
    wide: type === "box" || type === "containers",
    get() {
      const m = sel.value;
      if (m === "none") return { value: undefined };
      if (SIMPLE.has(m)) {
        const ref = { ref: m };
        return { value: type === "containers" ? [ref] : ref };
      }
      return parts[m] ? parts[m].get() : { value: undefined };
    },
    set(v) {
      touched = v !== undefined && v !== null;
      if (v === undefined || v === null) return setMode(defaultMode());
      if (type === "containers") {
        const list = Array.isArray(v) ? v : [v];
        if (list.some((x) => isRef(x) && x.ref === "auto")) return setMode("auto");
        if (list.length === 1 && isRef(list[0]) && list[0].ref === "owner_look") return setMode("owner_look");
        const known = worldOf(ctx)?.containers || [];
        const plain = list.filter((x) => !isRef(x));
        if (parts.known && plain.length && plain.every((p) => known.some((c) => samePos(c.pos, p)))) {
          setMode("known");
          return parts.known.set(plain);
        }
        setMode("manual");
        return parts.manual && parts.manual.set(plain);
      }
      if (type === "box") {
        if (isRef(v)) {
          setMode(v.ref);
          return parts[v.ref] && parts[v.ref].set(v);
        }
        const a = Array.isArray(v) ? v[0] : v.a;
        const b = Array.isArray(v) ? v[1] : v.b;
        if (!isRef(a) && !isRef(b) && parts.manual) {
          setMode("manual");
          return parts.manual.set({ a, b });
        }
        setMode("two");
        return parts.two && parts.two.set({ a, b });
      }
      if (isRef(v)) {
        setMode(v.ref);
        return parts[v.ref] && parts[v.ref].set(v);
      }
      const known = worldOf(ctx)?.containers || [];
      if (type === "container" && parts.known && known.some((c) => samePos(c.pos, v))) {
        setMode("known");
        return parts.known.set(v);
      }
      setMode("manual");
      return parts.manual && parts.manual.set(v);
    },
  };
}
