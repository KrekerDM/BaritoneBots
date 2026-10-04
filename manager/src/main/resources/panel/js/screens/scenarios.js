// Scenarios: list, step editor (bot tasks and manager steps), run on bots.

import { api, enc, listOf } from "../api.js";
import { h, mount, btn, busy, toast, confirmDialog, errorBox, empty, field } from "../dom.js";
import { t } from "../i18n.js";
import { loadCatalog } from "../store.js";
import { stepsEditor, botPicker } from "../forms.js";

export function render(root, params, app) {
  const listHost = h("div");
  const editorHost = h("div");
  let scenarios = [];

  mount(
    root,
    h(
      "div",
      { class: "stack-lg" },
      h("div", { class: "head" }, h("h1", { class: "h1" }, t("scen.title"))),
      h("p", { class: "prose" }, t("scen.text")),
      h("div", { class: "split" }, h("nav", { class: "section", "aria-label": t("scen.list") }, listHost), editorHost),
    ),
  );

  async function load() {
    try {
      scenarios = listOf(await api.get("/api/scenarios"), "scenarios");
      renderList();
      await openEditor();
    } catch (e) {
      mount(listHost, errorBox(e, "scen.loadFailed"));
      mount(editorHost, h("div"));
    }
  }

  function renderList() {
    const items = scenarios.map((s) =>
      h(
        "li",
        null,
        h(
          "a",
          { href: `#/scenarios/${enc(s.id)}`, "aria-current": params.id === s.id ? "page" : null },
          s.name || s.id,
          h("div", { class: "small dim" }, t("scen.stepsN", { n: (s.steps || []).length }), s.repeat ? ` · ${t("scen.repeats")}` : ""),
        ),
      ),
    );
    mount(
      listHost,
      h("div", { class: "head" }, h("h2", { class: "h3" }, t("scen.list")), h("a", { class: "btn btn-ghost btn-sm", href: "#/scenarios/new" }, t("scen.new"))),
      items.length ? h("ul", { class: "list" }, items) : h("p", { class: "empty" }, t("scen.empty")),
    );
  }

  async function openEditor() {
    if (!params.id) {
      mount(editorHost, h("section", { class: "section" }, h("p", { class: "empty" }, scenarios.length ? t("scen.pick") : t("scen.emptyHint"))));
      return;
    }
    const isNew = params.id === "new";
    const found = scenarios.find((s) => s.id === params.id);
    if (!isNew && !found) {
      mount(editorHost, h("section", { class: "section" }, errorBox({ code: "not_found", message: params.id })));
      return;
    }
    let catalog;
    try {
      catalog = await loadCatalog();
    } catch (e) {
      mount(editorHost, errorBox(e, "ui.catalogFailed"));
      return;
    }
    editor(isNew ? { name: "", steps: [], repeat: false } : structuredClone(found), isNew, catalog);
  }

  function editor(sc, isNew, catalog) {
    let dirty = false;
    const nameIn = h("input", { type: "text", value: sc.name || "", maxlength: 80, required: true });
    const repeatIn = h("input", { type: "checkbox", checked: !!sc.repeat });
    const saveNote = h("p", { class: "field-hint", "aria-live": "polite" });
    const stepsLabel = h("p", { class: "label" }, t("scen.steps", { n: sc.steps.length }));
    const markDirty = () => {
      dirty = true;
      saveNote.textContent = t("scen.unsaved");
    };
    nameIn.addEventListener("input", markDirty);
    repeatIn.addEventListener("change", markDirty);
    const steps = stepsEditor({
      catalog,
      steps: sc.steps,
      onChange: (list) => {
        sc.steps = list;
        stepsLabel.textContent = t("scen.steps", { n: list.length });
        markDirty();
      },
    });

    const saveBtn = h("button", { type: "button", class: "btn btn-primary" }, t("scen.save"));
    saveBtn.addEventListener("click", () =>
      busy(saveBtn, async () => {
        const name = nameIn.value.trim();
        if (!name) {
          nameIn.setAttribute("aria-invalid", "true");
          saveNote.textContent = t("form.err.required");
          return;
        }
        nameIn.removeAttribute("aria-invalid");
        const body = { ...sc, name, repeat: repeatIn.checked, steps: steps.steps() };
        if (isNew) {
          delete body.id;
          const created = await api.post("/api/scenarios", body);
          toast("info", t("scen.saved"));
          dirty = false;
          if (created && created.id) app.navigate(`/scenarios/${created.id}`);
          else load();
        } else {
          await api.put(`/api/scenarios/${enc(sc.id)}`, body);
          dirty = false;
          saveNote.textContent = t("scen.saved");
          scenarios = listOf(await api.get("/api/scenarios"), "scenarios");
          renderList();
        }
      }),
    );

    const delBtn = isNew
      ? null
      : btn(
          t("scen.delete"),
          async (e) => {
            const target = e.currentTarget;
            if (!(await confirmDialog(t("scen.delete"), t("scen.deleteConfirm", { name: sc.name }), t("scen.delete")))) return;
            busy(target, async () => {
              await api.del(`/api/scenarios/${enc(sc.id)}`);
              app.navigate("/scenarios");
            });
          },
          { small: false },
        );

    // Run
    const picker = botPicker([]);
    const runRepeat = h("input", { type: "checkbox", checked: !!sc.repeat });
    const runNote = h("p", { class: "field-hint", "aria-live": "polite" });
    const runBtn = btn(
      t("scen.run"),
      (e) =>
        busy(e.currentTarget, async () => {
          if (dirty || isNew) {
            runNote.textContent = t("scen.saveFirst");
            return;
          }
          const botIds = picker.get();
          if (!botIds.length) {
            runNote.textContent = t("bots.noSelection");
            return;
          }
          const r = await api.post(`/api/scenarios/${enc(sc.id)}/run`, { botIds, repeat: runRepeat.checked });
          // POST .../run answers {runs: {botId: runId}}.
          const runs = r && r.runs && typeof r.runs === "object" ? Object.values(r.runs) : [];
          runNote.textContent = t("scen.started", { run: runs.join(", "), n: runs.length || botIds.length });
        }),
      { small: false },
    );

    mount(
      editorHost,
      h(
        "div",
        { class: "stack" },
        h(
          "section",
          { class: "section" },
          h("div", { class: "head" }, h("h2", { class: "h2" }, isNew ? t("scen.newTitle") : sc.name || sc.id)),
          h(
            "div",
            { class: "fields" },
            field(t("scen.name"), nameIn),
            field(t("scen.repeat"), h("label", { class: "check" }, repeatIn, h("span", null, t("scen.repeatHint")))),
          ),
          stepsLabel,
          steps.listEl,
          h("div", { class: "row" }, saveBtn, delBtn, saveNote),
        ),
        h("section", { class: "section" }, steps.formEl),
        h(
          "section",
          { class: "section" },
          h("div", { class: "head" }, h("h2", { class: "h2" }, t("scen.runTitle"))),
          h("p", { class: "small" }, t("scen.runText")),
          field(t("scen.bots"), picker.el),
          h("label", { class: "check" }, runRepeat, h("span", null, t("scen.runRepeat"))),
          h("div", { class: "row" }, runBtn, runNote),
        ),
      ),
    );
  }

  mount(listHost, empty(t("ui.loading")));
  load();
  return null;
}
