// First run and owner (SPEC §6 POST /api/bots/quick, §5.7e owner detection):
// the shortest way from an empty manager to working bots. Shown on the bots
// screen while there are no bots, and while the owner is not confirmed.

import { api, enc } from "./api.js";
import { h, mount, btn, busy, toast, field, errorText } from "./dom.js";
import { t } from "./i18n.js";
import { store, loadState, loadSettings, botList } from "./store.js";
import { pct } from "./format.js";

const NAME_RE = /^[A-Za-z0-9_]{1,16}$/;

export function setupPanel() {
  const host = h("div", { class: "stack" });

  function firstRun() {
    const server = store.servers[0] || null;
    const address = h("input", { type: "text", value: server?.address || "", spellcheck: "false", autocomplete: "off" });
    const count = h("input", { type: "number", min: 1, max: 10, step: 1, value: "2", inputmode: "numeric" });
    const note = h("p", { class: "field-hint", "aria-live": "polite" });
    const go = h("button", { type: "submit", class: "btn btn-primary" }, t("setup.go"));
    const form = h(
      "form",
      { class: "stack", novalidate: true },
      h("div", { class: "fields" }, field(t("setup.address"), address, { hint: t("setup.addressHint") }), field(t("setup.count"), count, { hint: t("setup.countHint") })),
      h("div", { class: "row" }, go, note),
    );
    form.addEventListener("submit", (e) => {
      e.preventDefault();
      const addr = address.value.trim();
      const n = Number(count.value);
      if (!addr) {
        note.textContent = t("setup.needAddress");
        return;
      }
      if (!Number.isInteger(n) || n < 1 || n > 10) {
        note.textContent = t("form.err.max", { max: 10 });
        return;
      }
      note.textContent = t("ui.sending");
      busy(
        go,
        async () => {
          let sid = server?.id;
          if (!sid) {
            const created = await api.post("/api/servers", { name: addr, address: addr });
            sid = created?.id;
          } else if (server.address !== addr) {
            // The shipped profile is called "Main server"; the address is a better name than that.
            const rename = !server.name || server.name === "Main server";
            await api.put(`/api/servers/${enc(sid)}`, rename ? { address: addr, name: addr } : { address: addr });
          }
          await api.post("/api/bots/quick", { count: n, serverId: sid, start: true });
          await loadState();
          loadSettings(true).catch(() => {});
          toast("info", t("setup.started", { n }));
          draw();
        },
        { toastErrors: false },
      ).catch((err) => {
        note.textContent = errorText(err);
      });
    });
    return h(
      "section",
      { class: "section" },
      h("div", { class: "head" }, h("h2", { class: "h2" }, t("setup.title"))),
      h("p", { class: "prose" }, t("setup.text")),
      form,
    );
  }

  function ownerBox() {
    const cand = store.owner?.candidate || null;
    const note = h("p", { class: "field-hint", "aria-live": "polite" });
    const confirm = (player, button) =>
      busy(
        button,
        async () => {
          store.owner = (await api.post("/api/owner/confirm", { player })) || { player };
          loadSettings(true).catch(() => {});
          toast("info", t("setup.ownerSaved", { player }));
          draw();
        },
        { toastErrors: false },
      ).catch((err) => {
        note.textContent = errorText(err);
      });
    if (cand) {
      const why = cand.via === "online" ? t("setup.candOnline", { player: cand.player }) : t("setup.candCommand", { player: cand.player });
      const body = h(
        "div",
        { class: "stack-sm" },
        h("p", { class: "prose" }, why),
        h(
          "div",
          { class: "row" },
          btn(t("setup.itsMe"), (e) => confirm(cand.player, e.currentTarget), { kind: "primary", small: false }),
          btn(t("setup.notMe"), (e) =>
            busy(e.currentTarget, async () => {
              store.owner = (await api.post("/api/owner/dismiss")) || { player: null, candidate: null };
              draw();
            }),
          ),
          note,
        ),
      );
      return section(body);
    }
    const name = h("input", { type: "text", maxlength: 16, spellcheck: "false", autocomplete: "off" });
    const save = h("button", { type: "submit", class: "btn btn-ghost" }, t("setup.saveOwner"));
    const form = h("form", { class: "row", novalidate: true }, field(t("setup.ownerName"), name), save, note);
    form.addEventListener("submit", (e) => {
      e.preventDefault();
      const v = name.value.trim();
      if (!NAME_RE.test(v)) {
        note.textContent = t("setup.badName");
        return;
      }
      confirm(v, save);
    });
    const prefix = store.settings?.config?.general?.commandPrefix || "!b";
    return section(h("div", { class: "stack-sm" }, h("p", { class: "prose" }, t("setup.ownerText", { p: prefix })), form));
  }

  const section = (body) => h("section", { class: "section" }, h("div", { class: "head" }, h("h2", { class: "h2" }, t("setup.ownerTitle"))), body);

  function installLine() {
    const rt = store.runtime;
    if (!rt || !rt.installing) return null;
    return h("p", { class: "small", "aria-live": "polite" }, t("setup.installing"), " ", pct(rt.progress));
  }

  function draw() {
    if (!store.loaded) {
      mount(host);
      return;
    }
    const noBots = botList().length === 0;
    const needOwner = !noBots && !store.owner?.player;
    mount(host, noBots ? firstRun() : null, needOwner ? ownerBox() : null, installLine());
  }

  // Redraw only when what the card shows changes, so typing survives status updates.
  const stateKey = () =>
    JSON.stringify([store.loaded, botList().length === 0, store.owner?.player || null, store.owner?.candidate?.player || null, !!store.runtime?.installing, Math.round((store.runtime?.progress || 0) * 20)]);
  let last = stateKey();
  draw();
  return {
    el: host,
    update() {
      const key = stateKey();
      if (key === last) return;
      last = key;
      const typing = host.contains(document.activeElement) && document.activeElement.tagName === "INPUT";
      if (!typing) draw();
    },
  };
}
