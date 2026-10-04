// World knowledge per server profile: waypoints, areas, containers,
// protected zones, deaths. Every edit is saved with PUT /api/world/{id}.

import { api, enc } from "../api.js";
import { h, mount, btn, busy, toast, confirmDialog, errorBox, empty, table, field, select, dialog } from "../dom.js";
import { t, tid } from "../i18n.js";
import { store, botList, loadWorld, serverOf } from "../store.js";
import { num, noData, isNum, posText, boxText, dimLabel, shortId, timeEl } from "../format.js";
import { argField, collect, refBotSelect } from "../forms.js";

const ROLE_RE = /^(kit|storage|supply|fuel|inbox|furnace|crafting|sorted:[a-z0-9_]+)$/;
export const CONTAINER_ROLES = ["kit", "storage", "supply", "fuel", "inbox", "furnace", "crafting", "found", "sorted:<category>"];

export function render(root, params, app) {
  const servers = store.servers;
  if (!params.serverId && servers.length) {
    app.navigate(`/world/${servers[0].id}`);
    return null;
  }
  const serverId = params.serverId;
  const host = h("div", { class: "stack-lg" });
  // Shown when another client or a bot changed this world while a form here holds unsaved input.
  const notice = h("div", { "aria-live": "polite" });
  let world = null;
  let dirty = false;
  host.addEventListener("input", () => {
    dirty = true;
  });

  const tabs = h(
    "nav",
    { class: "tabs", "aria-label": t("world.servers") },
    servers.map((s) => h("a", { href: `#/world/${enc(s.id)}`, "aria-current": s.id === serverId ? "page" : null }, s.name || s.id)),
  );

  mount(
    root,
    h(
      "div",
      { class: "stack" },
      h("h1", { class: "h1" }, t("world.title")),
      h("p", { class: "prose" }, t("world.text")),
      servers.length ? tabs : h("p", { class: "empty" }, store.loaded ? t("world.noServers") : t("ui.loading")),
      notice,
      host,
    ),
  );
  if (!serverId) return null;

  const ref = refBotSelect(botList().find((b) => serverOf(b) === serverId && b.status?.pos)?.id);
  const ctxFor = (dimField) => ({
    labelKey: (n) => `wfield.${n}`,
    refBot: () => ref.bot(),
    serverId: () => serverId,
    onBotDim: (dim) => dimField && dimField.set(dim),
  });

  async function save(mutate, okText) {
    const next = structuredClone(world);
    mutate(next);
    try {
      const res = await api.put(`/api/world/${enc(serverId)}`, next);
      world = res && typeof res === "object" && Array.isArray(res.waypoints) ? res : next;
      store.worlds.set(serverId, world);
      if (okText) toast("info", okText);
      draw();
      return true;
    } catch (e) {
      toast("error", `${t("world.saveFailed")}: ${e.code} ${e.message || ""}`);
      return false;
    }
  }

  // ----------------------------------------------------------------
  // Add forms (generic: a few fields + add button)
  // ----------------------------------------------------------------
  function addForm(defs, onAdd, submitKey) {
    let dimField = null;
    const ctx = ctxFor(null);
    const fields = defs.map((d) => {
      const f = argField(d, ctx);
      if (d.type === "dim") dimField = f;
      return f;
    });
    ctx.onBotDim = (dim) => dimField && dimField.set(dim);
    const note = h("span", { class: "field-hint", "aria-live": "polite" });
    const submit = h("button", { type: "submit", class: "btn btn-ghost" }, t(submitKey));
    const form = h("form", { class: "stack", novalidate: true }, h("div", { class: "fields" }, fields.map((f) => f.el)), h("div", { class: "row" }, submit, note));
    form.addEventListener("submit", async (e) => {
      e.preventDefault();
      const r = collect(fields);
      if (r.error) {
        note.textContent = r.error;
        return;
      }
      const err = onAdd(r.value);
      if (typeof err === "string") {
        note.textContent = err;
        return;
      }
      submit.disabled = true;
      const ok = await err;
      submit.disabled = false;
      if (ok) note.textContent = t("world.added");
    });
    return form;
  }

  const removeBtn = (listKey, index, label) =>
    btn(t("bot.remove"), async () => {
      if (!(await confirmDialog(t("world.removeTitle"), t("world.removeConfirm", { what: label }), t("bot.remove")))) return;
      save((w) => w[listKey].splice(index, 1), t("world.removed"));
    }, { ariaLabel: `${t("bot.remove")}: ${label}` });

  // ----------------------------------------------------------------
  // Sections
  // ----------------------------------------------------------------
  function waypointsSection() {
    const rows = world.waypoints.map((w, i) =>
      h("tr", null, h("td", null, w.name === "home" ? h("span", { class: "strong" }, w.name, h("span", { class: "dim" }, ` · ${t("world.home")}`)) : w.name), h("td", null, dimLabel(w.dim)), h("td", { class: "num" }, posText(w.pos) || ""), h("td", { class: "actions" }, removeBtn("waypoints", i, w.name))),
    );
    return sec(
      "world.waypoints",
      rows.length ? table([t("world.col.name"), t("world.col.dim"), t("world.col.pos"), t("bot.qcol.actions")], rows) : h("p", { class: "empty" }, t("world.noWaypoints")),
      h("h3", { class: "h3" }, t("world.addWaypoint")),
      addForm(
        [
          { name: "name", type: "string", required: true },
          { name: "dim", type: "dim", required: true, default: "minecraft:overworld" },
          { name: "pos", type: "pos", required: true },
        ],
        (v) => {
          if (world.waypoints.some((w) => w.name === v.name)) return t("world.dupName");
          return save((w) => w.waypoints.push({ name: v.name, dim: v.dim, pos: v.pos }));
        },
        "world.add",
      ),
    );
  }

  function areasSection() {
    const rows = world.areas.map((a, i) =>
      h("tr", null, h("td", null, a.name), h("td", null, dimLabel(a.dim)), h("td", { class: "num" }, boxText(a.box) || ""), h("td", { class: "num" }, a.box ? sizeText(a.box) : ""), h("td", { class: "actions" }, removeBtn("areas", i, a.name))),
    );
    return sec(
      "world.areas",
      rows.length ? table([t("world.col.name"), t("world.col.dim"), t("world.col.box"), t("world.col.size"), t("bot.qcol.actions")], rows) : h("p", { class: "empty" }, t("world.noAreas")),
      h("h3", { class: "h3" }, t("world.addArea")),
      addForm(
        [
          { name: "name", type: "string", required: true },
          { name: "dim", type: "dim", required: true, default: "minecraft:overworld" },
          { name: "box", type: "box", required: true },
        ],
        (v) => {
          if (world.areas.some((a) => a.name === v.name)) return t("world.dupName");
          return save((w) => w.areas.push({ name: v.name, dim: v.dim, box: v.box }));
        },
        "world.add",
      ),
    );
  }

  function zonesSection() {
    const rows = world.zones.map((z, i) =>
      h("tr", null, h("td", null, z.name || h("span", { class: "dim" }, t("world.unnamed"))), h("td", null, dimLabel(z.dim)), h("td", { class: "num" }, boxText(z.box) || ""), h("td", { class: "actions" }, removeBtn("zones", i, z.name || boxText(z.box)))),
    );
    return sec(
      "world.zones",
      h("p", { class: "small" }, t("world.zonesText")),
      rows.length ? table([t("world.col.name"), t("world.col.dim"), t("world.col.box"), t("bot.qcol.actions")], rows) : h("p", { class: "empty" }, t("world.noZones")),
      h("h3", { class: "h3" }, t("world.addZone")),
      addForm(
        [
          { name: "name", type: "string" },
          { name: "dim", type: "dim", required: true, default: "minecraft:overworld" },
          { name: "box", type: "box", required: true },
        ],
        (v) => save((w) => w.zones.push({ name: v.name, dim: v.dim, box: v.box })),
        "world.add",
      ),
    );
  }

  function snapshotEl(c) {
    const s = c.snapshot;
    if (!s) return h("span", { class: "nodata" }, t("world.noSnapshot"));
    const items = Array.isArray(s.items) ? s.items : [];
    const totals = new Map();
    for (const it of items) totals.set(it.item, (totals.get(it.item) || 0) + it.count);
    const sorted = [...totals.entries()].sort((a, b) => b[1] - a[1]);
    return h(
      "div",
      { class: "stack-sm" },
      h("span", null, isNum(s.size) ? num(s.size - (s.free ?? 0), { max: s.size, unit: t("unit.slots") }) : noData(), h("span", { class: "num-threshold" }, t("world.freeSlots", { n: s.free ?? 0 }))),
      sorted.length
        ? h(
            "details",
            null,
            h("summary", { class: "small" }, t("world.contents", { n: sorted.length })),
            h("ul", { class: "list small" }, sorted.map(([id, n]) => h("li", { class: "row spread" }, h("span", { class: "mono" }, shortId(id)), num(n)))),
          )
        : h("span", { class: "small dim" }, t("world.emptyContainer")),
    );
  }

  async function editContainer(index) {
    const c = world.containers[index];
    const rolesIn = h("input", { type: "text", value: (c.roles || []).join(" "), spellcheck: "false", autocomplete: "off" });
    const labelIn = h("input", { type: "text", value: c.label || "", maxlength: 60 });
    const err = h("p", { class: "field-error", "aria-live": "polite" });
    const parseRoles = () => rolesIn.value.split(/[\s,]+/).map((s) => s.trim()).filter(Boolean);
    const ok = await dialog(
      t("world.editContainer", { pos: posText(c.pos) }),
      h(
        "div",
        { class: "stack" },
        field(t("world.roles"), rolesIn, { hint: t("world.rolesHint", { roles: CONTAINER_ROLES.join(", ") }) }),
        field(t("world.label"), labelIn),
        err,
      ),
      [
        {
          label: t("ui.save"),
          primary: true,
          value: true,
          validate: () => {
            const bad = parseRoles().filter((r) => !ROLE_RE.test(r));
            err.textContent = bad.length ? t("world.badRoles", { list: bad.join(", ") }) : "";
            return !bad.length;
          },
        },
        { label: t("ui.cancel"), value: false },
      ],
    );
    if (!ok) return;
    const roles = parseRoles();
    const label = labelIn.value.trim();
    save((w) => {
      w.containers[index] = { ...w.containers[index], roles, label: label || undefined };
    }, t("world.saved"));
  }

  function containersSection() {
    const sorted = world.containers.map((c, i) => ({ c, i })).sort((a, b) => (b.c.lastSeen || 0) - (a.c.lastSeen || 0));
    const rows = sorted.map(({ c, i }) =>
      h(
        "tr",
        null,
        h("td", null, c.label || h("span", { class: "dim" }, t("world.unnamed")), h("div", { class: "small mono dim" }, shortId(c.block) || "")),
        h("td", null, h("span", { class: "num" }, posText(c.pos) || ""), h("div", { class: "small dim" }, dimLabel(c.dim))),
        h("td", null, c.roles && c.roles.length ? c.roles.map((r) => tid("containerRole", r.startsWith("sorted:") ? "sorted" : r) + (r.startsWith("sorted:") ? ` ${r.slice(7)}` : "")).join(", ") : h("span", { class: "dim" }, t("world.noRoles"))),
        h("td", { class: "wrap-cell" }, snapshotEl(c)),
        h("td", null, isNum(c.lastSeen) ? timeEl(c.lastSeen) : h("span", { class: "nodata" }, t("world.neverSeen"))),
        h("td", { class: "actions" }, h("div", { class: "row-sm" }, btn(t("world.edit"), () => editContainer(i), { ariaLabel: `${t("world.edit")}: ${posText(c.pos)}` }), removeBtn("containers", i, posText(c.pos)))),
      ),
    );
    return sec(
      "world.containers",
      h("p", { class: "small" }, t("world.containersText")),
      rows.length
        ? table([t("world.col.container"), t("world.col.pos"), t("world.col.roles"), t("world.col.snapshot"), t("world.col.lastSeen"), t("bot.qcol.actions")], rows)
        : h("p", { class: "empty" }, t("world.noContainers")),
      h("h3", { class: "h3" }, t("world.addContainer")),
      addForm(
        [
          { name: "dim", type: "dim", required: true, default: "minecraft:overworld" },
          { name: "pos", type: "pos", required: true },
          { name: "roles", type: "items", hint: t("world.rolesHint", { roles: CONTAINER_ROLES.join(", ") }) },
          { name: "label", type: "string" },
        ],
        (v) => {
          const roles = v.roles || [];
          const bad = roles.filter((r) => !ROLE_RE.test(r));
          if (bad.length) return t("world.badRoles", { list: bad.join(", ") });
          if (world.containers.some((c) => c.dim === v.dim && posText(c.pos) === posText(v.pos))) return t("world.dupContainer");
          return save((w) => w.containers.push({ dim: v.dim, pos: v.pos, roles, label: v.label }));
        },
        "world.add",
      ),
    );
  }

  function deathsSection() {
    const deaths = [...world.deaths].sort((a, b) => (b.time || 0) - (a.time || 0));
    const rows = deaths.map((d) => {
      const bot = d.botId ? store.bots.get(d.botId) : null;
      const recover = btn(t("world.recover"), (e) =>
        busy(e.currentTarget, async () => {
          await api.post(`/api/bots/${enc(d.botId)}/tasks`, { task: { type: "recover", args: { pos: roundPos(d.pos), dim: d.dim } }, mode: "front" });
          toast("info", t("world.recoverSent", { bot: bot?.username || d.botId }));
        }),
        { disabled: !bot, ariaLabel: `${t("world.recover")}: ${d.botId}` },
      );
      return h(
        "tr",
        null,
        h("td", null, timeEl(d.time)),
        h("td", null, bot ? h("a", { href: `#/bots/${enc(d.botId)}` }, bot.username || d.botId) : d.botId || noData()),
        h("td", null, dimLabel(d.dim)),
        h("td", { class: "num" }, posText(d.pos) || ""),
        h("td", { class: "wrap-cell" }, d.cause || h("span", { class: "dim" }, t("world.noCause"))),
        h("td", { class: "actions" }, recover),
      );
    });
    return sec(
      "world.deaths",
      rows.length ? table([t("events.col.time"), t("events.col.bot"), t("world.col.dim"), t("world.col.pos"), t("world.col.cause"), t("bot.qcol.actions")], rows) : h("p", { class: "empty" }, t("world.noDeaths")),
    );
  }

  function discoverSection() {
    const bots = botList().filter((b) => serverOf(b) === serverId);
    const botSel = select(
      bots.length ? bots.map((b) => ({ value: b.id, label: `${b.username || b.id}${b.status?.state === "online" ? "" : ` (${tid("process", b.process)})`}` })) : [{ value: "", label: t("world.noBotsOnServer") }],
      bots.find((b) => b.status?.state === "online")?.id || bots[0]?.id || "",
    );
    const radius = h("input", { type: "number", min: 1, max: 128, step: 1, value: "32", inputmode: "numeric" });
    const note = h("p", { class: "field-hint", "aria-live": "polite" });
    const go = h("button", { type: "button", class: "btn btn-primary" }, t("world.discover"));
    go.addEventListener("click", () =>
      busy(go, async () => {
        const r = Number(radius.value);
        if (!botSel.value) {
          note.textContent = t("world.noBotsOnServer");
          return;
        }
        if (!Number.isInteger(r) || r < 1 || r > 128) {
          note.textContent = t("form.err.max", { max: 128 });
          return;
        }
        note.textContent = t("ui.sending");
        const res = await api.post(`/api/world/${enc(serverId)}/discover`, { botId: botSel.value, radius: r });
        const found = res?.found ?? res?.containers?.length;
        note.textContent = t("world.discovered", { found: found ?? 0, added: res?.added ?? 0 });
        world = await loadWorld(serverId, true);
        draw();
      }),
    );
    return sec(
      "world.discoverTitle",
      h("p", { class: "small" }, t("world.discoverText")),
      h("div", { class: "fields" }, field(t("world.bot"), botSel), field(t("world.radius"), radius, { hint: t("world.radiusHint") })),
      h("div", { class: "row" }, go, note),
    );
  }

  const sec = (key, ...children) => h("section", { class: "section" }, h("div", { class: "head" }, h("h2", { class: "h2" }, t(key))), children);

  function draw() {
    dirty = false;
    notice.replaceChildren();
    mount(
      host,
      h("section", { class: "section" }, h("p", { class: "small" }, t("world.refText")), h("div", { class: "fields" }, ref.el)),
      discoverSection(),
      containersSection(),
      h("div", { class: "cols" }, waypointsSection(), areasSection()),
      zonesSection(),
      deathsSection(),
    );
  }

  mount(host, empty(t("ui.loading")));
  loadWorld(serverId, true).then(
    (w) => {
      world = w;
      draw();
    },
    (e) => mount(host, errorBox(e, "world.loadFailed")),
  );

  const reload = () =>
    loadWorld(serverId, true).then(
      (w) => {
        world = w;
        draw();
      },
      (e) => toast("error", `${t("world.loadFailed")}: ${e.code} ${e.message || ""}`),
    );
  return {
    update(type, data) {
      if (type !== "world" || !world || String(data?.serverId).toLowerCase() !== String(serverId).toLowerCase()) return;
      if (!dirty) {
        reload();
        return;
      }
      mount(
        notice,
        h("div", { class: "row" }, h("span", { class: "st-warn" }, t("world.changedRemote")), btn(t("world.reload"), () => reload())),
      );
    },
  };
}

function roundPos(p) {
  return p ? { x: Math.floor(p.x), y: Math.floor(p.y), z: Math.floor(p.z) } : p;
}

function sizeText(box) {
  const w = Math.abs(box.b.x - box.a.x) + 1;
  const hh = Math.abs(box.b.y - box.a.y) + 1;
  const l = Math.abs(box.b.z - box.a.z) + 1;
  return `${w} × ${hh} × ${l}`;
}
