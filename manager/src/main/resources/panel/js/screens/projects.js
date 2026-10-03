// Projects: list with progress, and the create form per project kind.

import { api, enc, listOf } from "../api.js";
import { h, mount, btn, busy, toast, confirmDialog, errorBox, empty, table, field, select, throttle, nextId } from "../dom.js";
import { t, has, tid } from "../i18n.js";
import { store, projectList, loadCatalog, loadWorld, serverName } from "../store.js";
import { num, noData, isNum, durationEl } from "../format.js";
import { argField, collect, refBotSelect, botPicker } from "../forms.js";
import { projectProgress } from "./project.js";

export const KINDS = ["build", "gather", "clear", "farm", "ranch", "sort", "smelt"];

const DIM = { name: "dim", type: "dim", required: true, default: "minecraft:overworld" };

// Used when /api/catalog has no `projectKinds` entry for a kind.
const KIND_FIELDS = {
  build: [
    { name: "origin", type: "pos", required: true },
    DIM,
    { name: "rotation", type: "enum", enum: [0, 90, 180, 270], required: true, default: 0 },
    { name: "mirror", type: "enum", enum: ["none", "front_back", "left_right"], required: true, default: "none" },
    { name: "supply", type: "containers", required: true },
  ],
  gather: [DIM, { name: "quotas", type: "item_counts", map: true, required: true }, { name: "storage", type: "containers", required: true }],
  clear: [DIM, { name: "box", type: "box", required: true }, { name: "deposit", type: "containers" }],
  farm: [DIM, { name: "box", type: "box", required: true }, { name: "deposit", type: "containers", required: true }],
  ranch: [
    DIM,
    { name: "box", type: "box", required: true },
    { name: "animal", type: "string", required: true },
    { name: "food", type: "item" },
    { name: "keep", type: "int", min: 2, default: 4 },
    { name: "max", type: "int", min: 2, default: 20 },
    { name: "deposit", type: "containers" },
  ],
  sort: [DIM, { name: "inbox", type: "containers", required: true }],
  smelt: [
    DIM,
    { name: "furnaces", type: "containers", required: true },
    { name: "input", type: "items", required: true },
    { name: "fuel", type: "item", required: true, default: "minecraft:coal" },
    { name: "output", type: "containers" },
  ],
};

function kindFields(catalog, kind) {
  const fromCatalog = (catalog?.projectKinds || []).find((k) => (k.kind || k.type) === kind);
  return fromCatalog && Array.isArray(fromCatalog.args) ? fromCatalog.args : KIND_FIELDS[kind] || [];
}

function kindList(catalog) {
  const fromCatalog = (catalog?.projectKinds || []).map((k) => k.kind || k.type).filter(Boolean);
  return fromCatalog.length ? fromCatalog : KINDS;
}

export function statusEl(status) {
  const cls = status === "running" ? "st-accent" : status === "done" ? "st-ok" : status === "failed" ? "st-bad" : status === "paused" ? "st-warn" : "dim";
  return h("span", { class: cls }, tid("pstatus", status || "draft"));
}

// Projects and the automatic planner are phase 2: the manager answers 501
// for /api/projects*. One probe per page load decides which screen shows;
// the full screens below stay for the planner.
let projectsApi = null; // null = not probed yet, true = available, false = 501

function phase2Note(root) {
  mount(root, h("div", { class: "stack" }, h("h1", { class: "h1" }, t("proj.title")), h("p", { class: "prose" }, t("proj.phase2"))));
}

export function withProjectsApi(root, renderFull) {
  if (projectsApi === true) return renderFull();
  if (projectsApi === false) {
    phase2Note(root);
    return null;
  }
  mount(root, empty(t("ui.loading")));
  let live = null;
  let gone = false;
  api.get("/api/projects").then(
    () => {
      projectsApi = true;
      if (!gone) live = renderFull();
    },
    (e) => {
      projectsApi = e.status !== 501;
      if (gone) return;
      if (projectsApi) live = renderFull();
      else phase2Note(root);
    },
  );
  return {
    update(type, data) {
      if (live && live.update) live.update(type, data);
    },
    destroy() {
      gone = true;
      if (live && live.destroy) live.destroy();
    },
  };
}

export function render(root, params, app) {
  return withProjectsApi(root, () => renderFull(root, params, app));
}

function renderFull(root, params, app) {
  if (params.create) return createForm(root, app);
  const listHost = h("div");

  const renderList = () => {
    const list = projectList();
    if (!store.loaded) {
      mount(listHost, empty(t("ui.loading")));
      return;
    }
    if (!list.length) {
      mount(listHost, empty(t("proj.empty")));
      return;
    }
    const rows = list.map((p) => {
      const pr = projectProgress(p);
      return h(
        "tr",
        null,
        h("td", null, h("a", { href: `#/projects/${enc(p.id)}` }, p.name || p.id)),
        h("td", null, tid("kind", p.kind)),
        h("td", null, statusEl(p.status)),
        h("td", null, pr.total !== null ? num(pr.done, { max: pr.total, unit: pr.unit }) : noData()),
        h("td", { class: "r" }, pr.fraction !== null ? num(pr.fraction * 100, { unit: "%", digits: 1 }) : noData()),
        h("td", { class: "r" }, isNum(pr.rate) ? num(pr.rate, { unit: t("unit.perMin"), digits: 1 }) : noData()),
        h("td", null, isNum(pr.etaSec) ? durationEl(pr.etaSec) : p.status === "done" ? h("span", { class: "st-ok" }, t("pstatus.done")) : noData()),
        h("td", null, p.bots === "any" || !p.bots ? t("proj.botsAny") : num(p.bots.length, { unit: t("unit.bots") })),
        h("td", null, p.serverId ? serverName(p.serverId) : noData()),
      );
    });
    mount(
      listHost,
      table(
        [
          t("proj.col.name"),
          t("proj.col.kind"),
          t("proj.col.status"),
          t("proj.col.progress"),
          { text: "%", class: "r" },
          { text: t("proj.col.rate"), class: "r" },
          t("proj.col.eta"),
          t("proj.col.bots"),
          t("proj.col.server"),
        ],
        rows,
      ),
    );
  };

  mount(
    root,
    h(
      "div",
      { class: "stack" },
      h("div", { class: "head" }, h("h1", { class: "h1" }, t("proj.title")), h("a", { class: "btn btn-primary", href: "#/projects/new" }, t("proj.create"))),
      h("p", { class: "prose" }, t("proj.text")),
      listHost,
    ),
  );
  renderList();
  const refresh = throttle(renderList, 1000);
  return {
    update(type) {
      if (type === "project" || type === "snapshot") refresh();
    },
  };
}

// ------------------------------------------------------------------
// Create form
// ------------------------------------------------------------------
function createForm(root, app) {
  const host = h("div");
  mount(
    root,
    h(
      "div",
      { class: "stack" },
      h("p", null, h("a", { href: "#/projects" }, t("proj.back"))),
      h("h1", { class: "h1" }, t("proj.newTitle")),
      host,
    ),
  );
  mount(host, empty(t("ui.loading")));
  loadCatalog().then(
    (catalog) => buildCreate(host, catalog, app),
    (e) => buildCreate(host, null, app, e),
  );
  return null;
}

function buildCreate(host, catalog, app, catalogError) {
  const nameIn = h("input", { type: "text", maxlength: 80, required: true });
  const kindSel = select(kindList(catalog).map((k) => ({ value: k, label: tid("kind", k) })), "build");
  const servers = store.servers;
  const serverSel = select(
    servers.length ? servers.map((s) => ({ value: s.id, label: s.name || s.id })) : [{ value: "", label: t("proj.noServers") }],
    servers[0]?.id || "",
  );
  const botsMode = nextId("bm");
  const anyRadio = h("input", { type: "radio", name: botsMode, value: "any", checked: true });
  const subsetRadio = h("input", { type: "radio", name: botsMode, value: "subset" });
  const picker = botPicker([]);
  const ref = refBotSelect();
  const kindHost = h("div", { class: "stack" });
  const kindNote = h("p", { class: "small" });
  const note = h("p", { class: "field-hint", "aria-live": "polite" });
  let fields = [];
  let schematicPick = null;

  const ctx = {
    labelKey: (name) => (has(`project.${kindSel.value}.arg.${name}`) ? `project.${kindSel.value}.arg.${name}` : `pfield.${name}`),
    refBot: () => ref.bot(),
    serverId: () => serverSel.value || null,
  };

  const buildKind = () => {
    const kind = kindSel.value;
    kindNote.textContent = t(`kind.${kind}.desc`, null, "");
    fields = kindFields(catalog, kind).map((a) => argField(a, ctx));
    const dimField = fields.find((f) => f.name === "dim");
    ctx.onBotDim = dimField ? (d) => dimField.set(d) : null;
    schematicPick = kind === "build" ? schematicPicker() : null;
    mount(kindHost, schematicPick ? schematicPick.el : null, h("div", { class: "fields" }, fields.map((f) => f.el)));
  };
  kindSel.addEventListener("change", buildKind);
  serverSel.addEventListener("change", () => {
    if (serverSel.value) loadWorld(serverSel.value).catch(() => {});
  });
  if (serverSel.value) loadWorld(serverSel.value).catch(() => {});
  buildKind();

  const submit = h("button", { type: "submit", class: "btn btn-primary" }, t("proj.createSubmit"));
  const form = h(
    "form",
    { class: "stack-lg", novalidate: true },
    catalogError ? errorBox(catalogError, "ui.catalogFailed") : null,
    h(
      "section",
      { class: "section" },
      h("div", { class: "fields" }, field(t("proj.name"), nameIn), field(t("proj.kind"), kindSel), field(t("proj.server"), serverSel)),
      kindNote,
      h(
        "fieldset",
        null,
        h("legend", null, t("proj.bots")),
        h("div", { class: "row" }, h("label", { class: "check" }, anyRadio, h("span", null, t("proj.botsAny"))), h("label", { class: "check" }, subsetRadio, h("span", null, t("proj.botsSubset")))),
        h("p", { class: "field-hint" }, t("proj.botsHint")),
        picker.el,
      ),
    ),
    h("section", { class: "section" }, h("h2", { class: "h2" }, t("proj.kindSettings")), h("div", { class: "fields" }, ref.el), kindHost),
    h("div", { class: "row" }, submit, note),
  );

  form.addEventListener("submit", async (e) => {
    e.preventDefault();
    const name = nameIn.value.trim();
    if (!name) {
      nameIn.setAttribute("aria-invalid", "true");
      note.textContent = t("form.err.required");
      nameIn.focus();
      return;
    }
    nameIn.removeAttribute("aria-invalid");
    if (!serverSel.value) {
      note.textContent = t("proj.noServers");
      return;
    }
    const r = collect(fields);
    if (r.error) {
      note.textContent = r.error;
      return;
    }
    const config = r.value;
    if (schematicPick) {
      const s = schematicPick.get();
      if (!s) {
        note.textContent = t("proj.err.schematic");
        return;
      }
      config.schematic = s;
    }
    let bots = "any";
    if (subsetRadio.checked) {
      bots = picker.get();
      if (!bots.length) {
        note.textContent = t("bots.noSelection");
        return;
      }
    }
    await busy(submit, async () => {
      const created = await api.post("/api/projects", { name, kind: kindSel.value, serverId: serverSel.value, bots, config });
      toast("info", t("proj.created"));
      if (created && created.id) app.navigate(`/projects/${created.id}`);
      else app.navigate("/projects");
    });
  });

  mount(host, form);
}

/** Schematic chooser: list from /api/schematics, upload, delete. */
function schematicPicker() {
  const sel = h("select", { required: true });
  const info = h("p", { class: "small" });
  const fileIn = h("input", { type: "file", accept: ".schem,.litematic,.schematic" });
  const upNote = h("p", { class: "field-hint", "aria-live": "polite" });
  let list = [];

  const describe = () => {
    const s = list.find((x) => x.name === sel.value);
    if (!s) {
      info.textContent = list.length ? "" : t("proj.noSchematics");
      return;
    }
    const d = s.dims || s.size || {};
    const w = s.width ?? d.width ?? d.x;
    const hgt = s.height ?? d.height ?? d.y;
    const l = s.length ?? d.length ?? d.z;
    info.replaceChildren(
      isNum(w) ? h("span", { class: "num" }, `${w} × ${hgt} × ${l}`) : noData(),
      h("span", { class: "num-unit" }, t("unit.blocks")),
      " · ",
      isNum(s.blocks ?? s.blockCount) ? num(s.blocks ?? s.blockCount, { unit: t("proj.nonAir") }) : noData(),
      s.format ? h("span", { class: "dim" }, ` · ${s.format}`) : null,
    );
  };

  const load = async (selectName) => {
    try {
      list = listOf(await api.get("/api/schematics"), "schematics");
      sel.replaceChildren(
        h("option", { value: "" }, list.length ? t("proj.pickSchematic") : t("proj.noSchematics")),
        ...list.map((s) => h("option", { value: s.name }, s.name)),
      );
      if (selectName) sel.value = selectName;
      describe();
    } catch (e) {
      info.replaceChildren(errorBox(e, "proj.schematicsFailed"));
    }
  };
  sel.addEventListener("change", describe);

  const upBtn = btn(t("proj.upload"), (e) =>
    busy(e.currentTarget, async () => {
      const file = fileIn.files && fileIn.files[0];
      if (!file) {
        upNote.textContent = t("proj.pickFile");
        return;
      }
      upNote.textContent = t("proj.uploading", { name: file.name, kb: Math.ceil(file.size / 1024) });
      const res = await api.upload("/api/schematics", file, { name: file.name });
      upNote.textContent = t("proj.uploaded", { name: res?.name || file.name });
      await load(res?.name || file.name);
    }),
  );
  const delBtn = btn(t("proj.deleteSchematic"), async (e) => {
    const target = e.currentTarget;
    if (!sel.value) return;
    if (!(await confirmDialog(t("proj.deleteSchematic"), t("proj.deleteSchematicConfirm", { name: sel.value }), t("proj.deleteSchematic")))) return;
    busy(target, async () => {
      await api.del(`/api/schematics/${enc(sel.value)}`);
      await load();
    });
  });

  load();
  return {
    el: h(
      "fieldset",
      null,
      h("legend", null, t("pfield.schematic")),
      h("div", { class: "stack-sm" }, field(t("proj.schematicFile"), sel), info, h("div", { class: "row-sm" }, delBtn)),
      h("div", { class: "stack-sm rule-t sub" }, field(t("proj.uploadFile"), fileIn, { hint: t("proj.uploadHint") }), h("div", { class: "row-sm" }, upBtn, upNote)),
    ),
    get: () => sel.value || null,
  };
}
