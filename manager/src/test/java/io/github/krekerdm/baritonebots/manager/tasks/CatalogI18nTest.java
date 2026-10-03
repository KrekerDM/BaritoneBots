package io.github.krekerdm.baritonebots.manager.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.TaskTypes;
import io.github.krekerdm.baritonebots.manager.config.SchemaField;
import io.github.krekerdm.baritonebots.manager.config.SettingsSchema;
import io.github.krekerdm.baritonebots.manager.events.I18n;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** The catalog covers SPEC §3, and every label the manager or panel needs exists in RU and EN. */
class CatalogI18nTest {
    private final TaskCatalog catalog = TaskCatalog.load();
    private final I18n i18n = new I18n();

    private void requireKey(List<String> missing, String key) {
        for (String lang : I18n.LANGS) {
            if (!i18n.has(lang, key)) {
                missing.add(lang + ":" + key);
            }
        }
    }

    @Test
    void catalogListsEverySpecTask() {
        for (String type : TaskTypes.ALL) {
            assertTrue(catalog.isTask(type), "catalog misses " + type);
        }
        for (String unsupported : List.of("build", "breed", "slaughter", "shear")) {
            assertFalse(catalog.isSupported(unsupported), unsupported + " should be marked unsupported");
        }
        for (String supported : List.of("goto", "goto_player", "follow", "explore", "baritone", "mine", "farm",
                "selection", "collect_drops", "recover", "eat", "idle", "take", "deposit", "inspect", "equip",
                "craft", "transfer", "drop", "smelt_load", "smelt_collect", "guard", "attack")) {
            assertTrue(catalog.isSupported(supported), supported + " should be supported");
        }
        assertTrue(catalog.isStep("kit") && catalog.isSupported("kit"));
        assertFalse(catalog.isSupported("sort_storage"));
        assertTrue(catalog.isHeavy("mine"));
        assertTrue(catalog.isContinuous("follow"));
    }

    @Test
    void everyCatalogLabelExists() {
        List<String> missing = new ArrayList<>();
        JsonObject json = catalog.json();
        for (JsonElement t : json.getAsJsonArray("tasks")) {
            String type = Json.getString(t.getAsJsonObject(), "type", "");
            requireKey(missing, "task." + type + ".title");
            for (JsonElement a : t.getAsJsonObject().getAsJsonArray("args")) {
                requireKey(missing, "task." + type + ".arg." + Json.getString(a.getAsJsonObject(), "name", ""));
            }
        }
        for (JsonElement s : json.getAsJsonArray("managerSteps")) {
            String step = Json.getString(s.getAsJsonObject(), "step", "");
            requireKey(missing, "step." + step + ".title");
            for (JsonElement a : s.getAsJsonObject().getAsJsonArray("args")) {
                requireKey(missing, "step." + step + ".arg." + Json.getString(a.getAsJsonObject(), "name", ""));
            }
        }
        for (JsonElement r : json.getAsJsonArray("roles")) {
            requireKey(missing, "role." + r.getAsString());
        }
        assertEquals(List.of(), missing);
    }

    @Test
    void everySettingHasLabelAndDescription() {
        List<String> missing = new ArrayList<>();
        for (SchemaField f : SettingsSchema.fields()) {
            requireKey(missing, f.labelKey());
            requireKey(missing, f.descriptionKey());
            for (String v : f.values()) {
                if (SchemaField.ENUM.equals(f.type())) {
                    requireKey(missing, f.labelKey() + ".option." + v);
                }
            }
        }
        for (String s : SettingsSchema.SECTIONS) {
            requireKey(missing, "settings.section." + s);
        }
        assertEquals(List.of(), missing);
    }

    @Test
    void everyEventKeyUsedInCodeExists() throws IOException {
        Path src = Path.of("src", "main", "java");
        Pattern p = Pattern.compile("\"(event\\.[a-zA-Z]+\\.[a-zA-Z]+)\"");
        List<String> missing = new ArrayList<>();
        try (Stream<Path> files = Files.walk(src)) {
            for (Path f : files.filter(x -> x.toString().endsWith(".java")).toList()) {
                Matcher m = p.matcher(Files.readString(f, StandardCharsets.UTF_8));
                while (m.find()) {
                    requireKey(missing, m.group(1));
                }
            }
        }
        assertEquals(List.of(), missing);
    }

    @Test
    void normaliseCoercesFormValues() {
        JsonObject args = catalog.normalize("mine", Json.obj("blocks", "stone, minecraft:coal_ore", "amount", "64"));
        assertEquals(2, args.getAsJsonArray("blocks").size());
        assertEquals(64, args.get("amount").getAsInt());
        JsonObject sel = catalog.normalize("selection", Json.obj("box", Json.obj()));
        assertEquals("clear", sel.get("op").getAsString(), "required args with a default are filled in");
        JsonObject fill = catalog.normalize("selection", Json.obj("op", "fill", "box", Json.obj(), "block", "stone"));
        assertEquals("fill", fill.get("op").getAsString(), "every selection op is supported");
        assertThrows(TaskCatalog.BadArgsException.class, () -> catalog.normalize("goto", Json.obj("x", 1)));
        assertThrows(TaskCatalog.BadArgsException.class, () -> catalog.normalize("goto", Json.obj("x", "abc", "z", 1)));
        assertThrows(TaskCatalog.BadArgsException.class, () -> catalog.normalize("nope", new JsonObject()));
    }

    @Test
    void placeholdersAreFilled() {
        assertEquals("bot1: stopped", i18n.t("en", "event.bot.stopped", java.util.Map.of("bot", "bot1")));
        assertEquals("missing.key", i18n.t("ru", "missing.key", null));
    }
}
