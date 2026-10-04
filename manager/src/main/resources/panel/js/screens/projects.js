// Projects: list with progress per kind, and the create form generated
// from the catalog's projectKinds (SPEC §5.7). Places are picked as
// references ("find automatically", "where I look", an area) instead of
// coordinates; the manager resolves them when the project is saved.

import { api, enc, listOf } from "../api.js";
import { h, mount, btn, busy, toast, confirmDialog, errorBox, errorText, empty, table, field, select, throttle, nextId } from "../dom.js";
import { t, has, tid } from "../i18n.js";
import { store, projectList, loadCatalog, loadWorld, loadSettings, serverName } from "../store.js";
import { num, noData, isNum, durationEl } from "../format.js";
import { argField, collect, refBotSelect, botPicker } from "../forms.js";
import { projectProgress, kindShort } from "./project.js";

function kindDef(catalog, kind) {
  return (catalog?.projectKinds || []).find((k) => (k.kind || k.type) === kind) || null;
}

/**
 * The kind's arguments as form fields. A box argument that takes references
 * covers the old "area" name field (an area is one of its options), so the
 * name field is left out and the box counts as required.
 */
export function kindFields(catalog, kind) {
  const args = Array.isArray(kindDef(catalog, kind)?.args) ? kindDef(catalog, kind).args : [];
  const boxRef = args.some((a) => a.name === "box" && a.refs);
  return args.filter((a) => !(boxRef && a.name === "area")).map((a) => (boxRef && a.name === "box" ? { ...a, primary: true } : a));
}

function kindList(catalog) {
  return (catalog?.projectKinds || []).filter((k) => k.supported !== false).map((k) => k.kind || k.type).filter(Boolean);
}

export function statusEl(status) {
  const cls = status === "running" ? "st-accent" : status === "done" ? "st-ok" : status === "failed" ? "st-bad" : status === "paused" ? "st-warn" : "dim";
  return h("span", { class: cls }, tid("pstatus", status || "draft"));
}

export function render(root, params, app) {
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
        h("td", { class: "r" }, isNum(pr.rate) ? num(pr.rate, { unit: pr.rateUnit, digits: 1 }) : noData()),
        h("td", null, isNum(pr.etaSec) ? durationEl(pr.etaSec) : kindShort(p)),
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
          t("proj.col.counters"),
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
  // Summaries in /api/state may predate the last restart; one list call refreshes them.
  api.get("/api/projects").then(
    (d) => {
      for (const p of listOf(d, "projects")) store.projects.set(p.id, { ...(store.projects.get(p.id) || {}), ...p });
      renderList();
    },
    () => {},
  );
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
  mount(root, h("div", { class: "stack" }, h("p", null, h("a", { href: "#/projects" }, t("proj.back"))), h("h1", { class: "h1" }, t("proj.newTitle")), host));
  mount(host, empty(t("ui.loading")));
  // Settings give the owner's name and the sorting categories the pickers show.
  Promise.all([loadCatalog(), loadSettings().catch(() => null)]).then(
    ([catalog]) => buildCreate(host, catalog, app),
    (e) => buildCreate(host, null, app, e),
  );
  return null;
}

function buildCreate(host, catalog, app, catalogError) {
  const kinds = kindList(catalog);
  const nameIn = h("input", { type: "text", maxlength: 80, required: true });
  const kindSel = select(kinds.map((k) => ({ value: k, label: tid("kind", k) })), kinds[0] || "build");
  const servers = store.servers;
  const serverSel = select(
    servers.length ? servers.map((s) => ({ value: s.id, label: s.name || s.id })) : [{ value: "", label: t("proj.noServers") }],
    servers[0]?.id || "",
  );
  const botsMode = nextId("bm");
  const anyRadio = h("input", { type: "radio", name: botsMode, value: "any", checked: true });
  const subsetRadio = h("input", { type: "radio", name: botsMode, value: "subset" });
  const picker = botPicker([]);
  const syncBots = () => (picker.el.hidden = !subsetRadio.checked);
  anyRadio.addEventListener("change", syncBots);
  subsetRadio.addEventListener("change", syncBots);
  syncBots();
  const ref = refBotSelect();
  const startIn = h("input", { type: "checkbox", checked: true });
  const kindHost = h("div", { class: "stack" });
  const kindNote = h("p", { class: "small" });
  const note = h("p", { class: "field-hint", "aria-live": "polite" });
  let fields = [];
  let schematicPick = null;

  const ctx = {
    labelKey: (name) => (has(`project.${kindSel.value}.arg.${name}`) ? `project.${kindSel.value}.arg.${name}` : `pfield.${name}`),
    refBot: () => ref.bot(),
    serverId: () => serverSel.value || null,
    what: `project:${kindSel.value}`,
  };

  const buildKind = () => {
    const kind = kindSel.value;
    ctx.what = `project:${kind}`;
    kindNote.textContent = t(`kind.${kind}.desc`, null, "");
    fields = kindFields(catalog, kind).map((a) => argField(a, ctx));
    const dimField = fields.find((f) => f.name === "dim");
    ctx.onBotDim = dimField ? (d) => dimField.set(d) : null;
    schematicPick = kind === "build" ? schematicPicker() : null;
    mount(kindHost, schematicPick ? schematicPick.el : null, h("div", { class: "fields" }, fields.map((f) => f.el)));
  };
  kindSel.addEventListener("change", buildKind);
  serverSel.addEventListener("change", () => {
    if (serverSel.value) loadWorld(serverSel.value).then(buildKind, buildKind);
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
    h(
      "section",
      { class: "section" },
      h("h2", { class: "h2" }, t("proj.kindSettings")),
      h("p", { class: "small" }, t("proj.placeText")),
      kindHost,
      h("details", null, h("summary", { class: "small" }, t("proj.refBotMore")), h("div", { class: "fields sub" }, ref.el)),
    ),
    h("div", { class: "row" }, submit, h("label", { class: "check" }, startIn, h("span", null, t("proj.startNow"))), note),
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
      note.textContent = t("proj.resolving");
      try {
        const created = await api.post("/api/projects", { name, kind: kindSel.value, serverId: serverSel.value, bots, config, start: startIn.checked });
        toast("info", t("proj.created"));
        app.navigate(created && created.id ? `/projects/${created.id}` : "/projects");
      } catch (err) {
        // 400 ref_no_owner / ref_auto_none / validation: say which part failed next to the button.
        note.textContent = errorText(err);
        throw err;
      }
    });
  });

  mount(host, form);
}

/** Schematic chooser: list from /api/schematics, upload (POST ?name=), delete. */
function schematicPicker() {
  const sel = h("select", { required: true });
  const info = h("p", { class: "small" });
  const fileIn = h("input", { type: "file", accept: ".schem,.litematic,.schematic" });
  const upNote = h("p", { class: "field-hint", "aria-live": "polite" });
  let list = [];

  const describe = () => {
    const s = list.find((x) => x.name === sel.value);
    if (!s) {
      // An empty list already says so in the select itself.
      info.textContent = "";
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
      else if (list.length === 1) sel.value = list[0].name;
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
  // Picking a file is the intent to use it: upload right away.
  fileIn.addEventListener("change", () => {
    if (fileIn.files && fileIn.files[0]) upBtn.click();
  });
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
