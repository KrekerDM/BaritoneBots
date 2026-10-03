// Kits: named sets of slots, each slot = ranked item globs + count.

import { api, enc, listOf } from "../api.js";
import { h, mount, btn, busy, toast, confirmDialog, errorBox, empty, field } from "../dom.js";
import { t } from "../i18n.js";
import { store } from "../store.js";

const SLOT_SUGGESTIONS = ["head", "chest", "legs", "feet", "offhand", "pickaxe", "axe", "shovel", "sword", "food", "blocks", "torches"];

export function render(root, params, app) {
  const listHost = h("div");
  const editorHost = h("div");
  let kits = [];

  mount(
    root,
    h(
      "div",
      { class: "stack-lg" },
      h("h1", { class: "h1" }, t("kits.title")),
      h("p", { class: "prose" }, t("kits.text")),
      h("div", { class: "split" }, h("nav", { class: "section", "aria-label": t("kits.list") }, listHost), editorHost),
    ),
  );

  async function load() {
    try {
      kits = listOf(await api.get("/api/kits"), "kits");
      store.kits = kits;
      renderList();
      openEditor();
    } catch (e) {
      mount(listHost, errorBox(e, "kits.loadFailed"));
    }
  }

  function renderList() {
    mount(
      listHost,
      h("div", { class: "head" }, h("h2", { class: "h3" }, t("kits.list")), h("a", { class: "btn btn-ghost btn-sm", href: "#/kits/new" }, t("kits.new"))),
      kits.length
        ? h(
            "ul",
            { class: "list" },
            kits.map((k) =>
              h(
                "li",
                null,
                h("a", { href: `#/kits/${enc(k.id)}`, "aria-current": params.id === k.id ? "page" : null }, k.name || k.id, h("div", { class: "small dim" }, t("kits.slotsN", { n: Object.keys(k.slots || {}).length }))),
              ),
            ),
          )
        : h("p", { class: "empty" }, t("kits.empty")),
    );
  }

  function openEditor() {
    if (!params.id) {
      mount(editorHost, h("section", { class: "section" }, h("p", { class: "empty" }, kits.length ? t("kits.pick") : t("kits.emptyHint"))));
      return;
    }
    const isNew = params.id === "new";
    const found = kits.find((k) => k.id === params.id);
    if (!isNew && !found) {
      mount(editorHost, h("section", { class: "section" }, errorBox({ code: "not_found", message: params.id })));
      return;
    }
    editor(isNew ? { name: "", slots: { head: { any: ["minecraft:diamond_helmet", "minecraft:iron_helmet"], count: 1 } } } : structuredClone(found), isNew);
  }

  function editor(kit, isNew) {
    const nameIn = h("input", { type: "text", value: kit.name || "", maxlength: 60 });
    const rowsHost = h("tbody");
    const note = h("p", { class: "field-hint", "aria-live": "polite" });
    const rows = [];

    const dl = h("datalist", { id: "dl-kit-slots" }, SLOT_SUGGESTIONS.map((s) => h("option", { value: s })));

    function addRow(name = "", slot = { any: [], count: 1 }) {
      const slotIn = h("input", { type: "text", value: name, list: "dl-kit-slots", "aria-label": t("kits.col.slot"), spellcheck: "false", autocomplete: "off" });
      const anyIn = h("input", { type: "text", value: (slot.any || []).join(" "), list: "dl-items", "aria-label": t("kits.col.any"), spellcheck: "false", autocomplete: "off" });
      const countIn = h("input", { type: "number", min: 1, max: 2304, step: 1, value: String(slot.count ?? 1), inputmode: "numeric", "aria-label": t("kits.col.count") });
      const r = { slotIn, anyIn, countIn, tr: null };
      const rm = btn(t("bot.remove"), () => {
        rows.splice(rows.indexOf(r), 1);
        r.tr.remove();
      });
      r.tr = h("tr", null, h("td", null, slotIn), h("td", { class: "wrap-cell" }, anyIn), h("td", null, countIn), h("td", { class: "actions" }, rm));
      rows.push(r);
      rowsHost.append(r.tr);
      return r;
    }

    for (const [name, slot] of Object.entries(kit.slots || {})) addRow(name, slot);

    function collectKit() {
      const name = nameIn.value.trim();
      if (!name) return { error: t("kits.err.name") };
      const slots = {};
      for (const r of rows) {
        const slotName = r.slotIn.value.trim();
        const any = r.anyIn.value.split(/[\s,]+/).map((s) => s.trim()).filter(Boolean);
        const count = Number(r.countIn.value);
        for (const el of [r.slotIn, r.anyIn, r.countIn]) el.removeAttribute("aria-invalid");
        if (!slotName && !any.length) continue;
        if (!slotName || !/^[a-z0-9_]+$/.test(slotName)) {
          r.slotIn.setAttribute("aria-invalid", "true");
          return { error: t("kits.err.slot", { slot: slotName || "?" }) };
        }
        if (slots[slotName]) {
          r.slotIn.setAttribute("aria-invalid", "true");
          return { error: t("kits.err.dup", { slot: slotName }) };
        }
        if (!any.length) {
          r.anyIn.setAttribute("aria-invalid", "true");
          return { error: t("kits.err.any", { slot: slotName }) };
        }
        if (!Number.isInteger(count) || count < 1) {
          r.countIn.setAttribute("aria-invalid", "true");
          return { error: t("kits.err.count", { slot: slotName }) };
        }
        slots[slotName] = { any, count };
      }
      if (!Object.keys(slots).length) return { error: t("kits.err.empty") };
      return { value: { ...kit, name, slots } };
    }

    const saveBtn = h("button", { type: "button", class: "btn btn-primary" }, t("kits.save"));
    saveBtn.addEventListener("click", () =>
      busy(saveBtn, async () => {
        const r = collectKit();
        if (r.error) {
          note.textContent = r.error;
          return;
        }
        if (isNew) {
          const body = { ...r.value };
          delete body.id;
          const created = await api.post("/api/kits", body);
          toast("info", t("kits.saved"));
          store.kits = null;
          if (created && created.id) app.navigate(`/kits/${created.id}`);
          else load();
        } else {
          await api.put(`/api/kits/${enc(kit.id)}`, r.value);
          note.textContent = t("kits.saved");
          kits = listOf(await api.get("/api/kits"), "kits");
          store.kits = kits;
          renderList();
        }
      }),
    );
    const delBtn = isNew
      ? null
      : btn(
          t("kits.delete"),
          async (e) => {
            const target = e.currentTarget;
            if (!(await confirmDialog(t("kits.delete"), t("kits.deleteConfirm", { name: kit.name }), t("kits.delete")))) return;
            busy(target, async () => {
              await api.del(`/api/kits/${enc(kit.id)}`);
              store.kits = null;
              app.navigate("/kits");
            });
          },
          { small: false },
        );

    mount(
      editorHost,
      h(
        "section",
        { class: "section" },
        dl,
        h("div", { class: "head" }, h("h2", { class: "h2" }, isNew ? t("kits.newTitle") : kit.name || kit.id)),
        h("div", { class: "fields" }, field(t("kits.name"), nameIn)),
        h("p", { class: "small" }, t("kits.slotsText")),
        h(
          "div",
          { class: "table-wrap" },
          h(
            "table",
            { class: "table" },
            h("thead", null, h("tr", null, h("th", { scope: "col" }, t("kits.col.slot")), h("th", { scope: "col" }, t("kits.col.any")), h("th", { scope: "col" }, t("kits.col.count")), h("th", { scope: "col" }, t("bot.qcol.actions")))),
            rowsHost,
          ),
        ),
        h("div", { class: "row-sm" }, btn(t("kits.addSlot"), () => addRow().slotIn.focus())),
        h("div", { class: "row" }, saveBtn, delBtn, note),
      ),
    );
  }

  mount(listHost, empty(t("ui.loading")));
  load();
  return null;
}
