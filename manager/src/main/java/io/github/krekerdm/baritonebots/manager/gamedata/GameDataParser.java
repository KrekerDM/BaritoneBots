package io.github.krekerdm.baritonebots.manager.gamedata;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.krekerdm.baritonebots.common.ids.Ids;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.manager.util.Log;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads {@code data/<ns>/recipe/**}, {@code data/<ns>/tags/item|block/**} and {@code data/<ns>/loot_table/blocks/**}
 * from a client jar (or a zip / directory with the same layout) into {@link GameData}. Pure and blocking: run it on
 * a worker thread. Older folder names ({@code recipes}, {@code tags/items}, {@code loot_tables}) and the legacy
 * ingredient/result objects ({@code {"item":..}}, {@code {"tag":..}}) are accepted too.
 */
public final class GameDataParser {
    private static final int MAX_TAG_DEPTH = 16;

    private final Map<String, List<String>> rawItemTags = new LinkedHashMap<>();
    private final Map<String, List<String>> rawBlockTags = new LinkedHashMap<>();
    private final Map<String, JsonObject> rawRecipes = new LinkedHashMap<>();
    private final Map<String, JsonObject> rawLoot = new LinkedHashMap<>();
    private int unreadable;

    private GameDataParser() {
    }

    /** Parses a jar / zip file or a directory that contains {@code data/}. */
    public static GameData parse(Path source) throws IOException {
        GameDataParser p = new GameDataParser();
        if (Files.isDirectory(source)) {
            Path data = source.resolve("data");
            if (!Files.isDirectory(data)) {
                throw new IOException("no data/ folder in " + source);
            }
            try (Stream<Path> files = Files.walk(data)) {
                for (Path f : files.filter(x -> Files.isRegularFile(x) && x.toString().endsWith(".json")).toList()) {
                    String rel = source.relativize(f).toString().replace('\\', '/');
                    try (InputStream in = Files.newInputStream(f)) {
                        p.accept(rel, in);
                    }
                }
            }
        } else {
            try (ZipFile zip = new ZipFile(source.toFile())) {
                Enumeration<? extends ZipEntry> en = zip.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    String name = e.getName();
                    if (!e.isDirectory() && name.startsWith("data/") && name.endsWith(".json")) {
                        try (InputStream in = zip.getInputStream(e)) {
                            p.accept(name, in);
                        }
                    }
                }
            }
        }
        if (p.unreadable > 0) {
            Log.warn("game data: %d unreadable JSON files skipped in %s", p.unreadable, source);
        }
        return p.build(source.toString());
    }

    // ------------------------------------------------------------------ collecting

    private void accept(String path, InputStream in) {
        // data/<ns>/<rest>.json
        String[] parts = path.split("/", 3);
        if (parts.length < 3) {
            return;
        }
        String ns = parts[1];
        String rest = parts[2].substring(0, parts[2].length() - ".json".length());
        String kind = null;
        String id = null;
        for (String[] prefix : new String[][] {{"recipe/", "recipe"}, {"recipes/", "recipe"},
                {"tags/item/", "itemTag"}, {"tags/items/", "itemTag"}, {"tags/block/", "blockTag"},
                {"tags/blocks/", "blockTag"}, {"loot_table/blocks/", "loot"}, {"loot_tables/blocks/", "loot"}}) {
            if (rest.startsWith(prefix[0])) {
                kind = prefix[1];
                id = ns + ":" + rest.substring(prefix[0].length());
                break;
            }
        }
        if (kind == null) {
            return;
        }
        JsonObject o;
        try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            JsonElement e = JsonParser.parseReader(r);
            if (!e.isJsonObject()) {
                return;
            }
            o = e.getAsJsonObject();
        } catch (IOException | RuntimeException ex) {
            unreadable++;
            return;
        }
        switch (kind) {
            case "recipe" -> rawRecipes.put(id, o);
            case "itemTag" -> rawItemTags.put(id, tagValues(o));
            case "blockTag" -> rawBlockTags.put(id, tagValues(o));
            default -> rawLoot.put(id, o);
        }
    }

    private static List<String> tagValues(JsonObject o) {
        List<String> out = new ArrayList<>();
        JsonArray values = Json.getArr(o, "values");
        if (values == null) {
            return out;
        }
        for (JsonElement v : values) {
            if (v.isJsonPrimitive()) {
                out.add(v.getAsString());
            } else if (v.isJsonObject()) {
                String id = Json.getString(v.getAsJsonObject(), "id", null);
                if (id != null) {
                    out.add(id);
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ building

    private GameData build(String source) {
        Map<String, Set<String>> itemTags = resolveTags(rawItemTags);
        Map<String, Set<String>> blockTags = resolveTags(rawBlockTags);
        List<GameData.Recipe> recipes = new ArrayList<>();
        rawRecipes.forEach((id, o) -> {
            try {
                GameData.Recipe r = recipe(id, o, itemTags);
                if (r != null) {
                    recipes.add(r);
                }
            } catch (RuntimeException e) {
                unreadable++;
            }
        });
        Map<String, List<GameData.Drop>> drops = new LinkedHashMap<>();
        rawLoot.forEach((block, o) -> {
            try {
                drops.put(block, loot(o, itemTags));
            } catch (RuntimeException e) {
                unreadable++;
            }
        });
        return new GameData(source, itemTags, blockTags, recipes, drops);
    }

    private static Map<String, Set<String>> resolveTags(Map<String, List<String>> raw) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (String tag : raw.keySet()) {
            Set<String> acc = new LinkedHashSet<>();
            expand(tag, raw, acc, new HashSet<>(), 0);
            out.put(tag, acc);
        }
        return out;
    }

    private static void expand(String tag, Map<String, List<String>> raw, Set<String> acc, Set<String> seen, int depth) {
        if (depth > MAX_TAG_DEPTH || !seen.add(tag)) {
            return;
        }
        for (String v : raw.getOrDefault(tag, List.of())) {
            if (v.startsWith("#")) {
                expand(Ids.normalize(v.substring(1)), raw, acc, seen, depth + 1);
            } else {
                acc.add(Ids.normalize(v));
            }
        }
    }

    /** One ingredient: {@code "id"}, {@code "#tag"}, a list of those, or legacy {@code {"item"}}/{@code {"tag"}} objects. */
    static List<String> ingredient(JsonElement e, Map<String, Set<String>> itemTags) {
        Set<String> out = new LinkedHashSet<>();
        addIngredient(e, itemTags, out);
        return List.copyOf(out);
    }

    private static void addIngredient(JsonElement e, Map<String, Set<String>> itemTags, Set<String> out) {
        if (e == null || e.isJsonNull()) {
            return;
        }
        if (e.isJsonArray()) {
            e.getAsJsonArray().forEach(x -> addIngredient(x, itemTags, out));
        } else if (e.isJsonPrimitive()) {
            String s = e.getAsString().trim();
            if (s.startsWith("#")) {
                out.addAll(itemTags.getOrDefault(Ids.normalize(s.substring(1)), Set.of()));
            } else if (!s.isEmpty()) {
                out.add(Ids.normalize(s));
            }
        } else if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("item")) {
                out.add(Ids.normalize(Json.getString(o, "item", "")));
            } else if (o.has("tag")) {
                out.addAll(itemTags.getOrDefault(Ids.normalize(Json.getString(o, "tag", "")), Set.of()));
            } else if (o.has("id")) {
                out.add(Ids.normalize(Json.getString(o, "id", "")));
            }
        }
    }

    private static String resultId(JsonElement result) {
        if (result == null || result.isJsonNull()) {
            return null;
        }
        if (result.isJsonPrimitive()) {
            return Ids.normalize(result.getAsString());
        }
        JsonObject o = result.getAsJsonObject();
        String id = Json.getString(o, "id", Json.getString(o, "item", null));
        return id == null ? null : Ids.normalize(id);
    }

    private static int resultCount(JsonElement result) {
        return result != null && result.isJsonObject() ? Json.getInt(result.getAsJsonObject(), "count", 1) : 1;
    }

    private static GameData.Recipe recipe(String id, JsonObject o, Map<String, Set<String>> itemTags) {
        String type = Ids.normalize(Json.getString(o, "type", ""));
        JsonElement res = o.get("result");
        String result = resultId(res);
        if (result == null) {
            return null;
        }
        int count = resultCount(res);
        switch (type) {
            case GameData.SHAPED -> {
                JsonArray pattern = Json.getArr(o, "pattern");
                JsonObject key = Json.getObj(o, "key");
                if (pattern == null || key == null || pattern.isEmpty()) {
                    return null;
                }
                List<String> rows = new ArrayList<>();
                pattern.forEach(r -> rows.add(r.getAsString()));
                int width = rows.stream().mapToInt(String::length).max().orElse(0);
                List<List<String>> cells = new ArrayList<>();
                for (String row : rows) {
                    for (int x = 0; x < width; x++) {
                        char c = x < row.length() ? row.charAt(x) : ' ';
                        if (c == ' ') {
                            cells.add(null);
                        } else {
                            List<String> ing = ingredient(key.get(String.valueOf(c)), itemTags);
                            if (ing.isEmpty()) {
                                return null; // unknown key or empty tag: not craftable here
                            }
                            cells.add(ing);
                        }
                    }
                }
                return new GameData.Recipe(id, type, result, count, width, rows.size(), cells);
            }
            case GameData.SHAPELESS -> {
                JsonArray ings = Json.getArr(o, "ingredients");
                if (ings == null || ings.isEmpty()) {
                    return null;
                }
                List<List<String>> slots = new ArrayList<>();
                for (JsonElement e : ings) {
                    List<String> ing = ingredient(e, itemTags);
                    if (ing.isEmpty()) {
                        return null;
                    }
                    slots.add(ing);
                }
                return new GameData.Recipe(id, type, result, count, 0, 0, slots);
            }
            case GameData.SMELTING, GameData.BLASTING, GameData.SMOKING, GameData.CAMPFIRE, GameData.STONECUTTING -> {
                List<String> ing = ingredient(o.get("ingredient"), itemTags);
                if (ing.isEmpty()) {
                    return null;
                }
                return new GameData.Recipe(id, type, result, count, 0, 0, List.of(ing));
            }
            default -> {
                return null; // special, smithing, transmute, decorated pot, ...
            }
        }
    }

    // ------------------------------------------------------------------ loot tables

    private static List<GameData.Drop> loot(JsonObject table, Map<String, Set<String>> itemTags) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        JsonArray pools = Json.getArr(table, "pools");
        if (pools != null) {
            for (JsonElement p : pools) {
                JsonObject pool = p.getAsJsonObject();
                JsonArray conds = Json.getArr(pool, "conditions");
                if (needsSpecialTool(conds)) {
                    continue;
                }
                boolean guaranteed = !chancy(conds);
                JsonArray entries = Json.getArr(pool, "entries");
                if (entries != null) {
                    entries.forEach(e -> entry(e.getAsJsonObject(), guaranteed, itemTags, out));
                }
            }
        }
        List<GameData.Drop> drops = new ArrayList<>();
        out.forEach((item, g) -> drops.add(new GameData.Drop(item, g)));
        return drops;
    }

    private static final int SKIPPED = 0;
    private static final int MAYBE = 1;
    private static final int ALWAYS = 2;

    /**
     * Adds an entry's items. Returns {@link #ALWAYS} when the entry always produces something (later children of an
     * {@code alternatives} entry never run), {@link #MAYBE} when it may, {@link #SKIPPED} when it never applies to a
     * normal break (silk touch / shears branch, empty entry).
     */
    private static int entry(JsonObject e, boolean guaranteed, Map<String, Set<String>> itemTags,
                             Map<String, Boolean> out) {
        JsonArray conds = Json.getArr(e, "conditions");
        if (needsSpecialTool(conds)) {
            return SKIPPED;
        }
        boolean unconditional = conds == null || conds.isEmpty() || onlyNeutral(conds);
        boolean g = guaranteed && unconditional;
        int self = unconditional ? ALWAYS : MAYBE;
        String type = Ids.normalize(Json.getString(e, "type", "minecraft:item"));
        switch (type) {
            case "minecraft:item" -> {
                String name = Json.getString(e, "name", null);
                if (name == null) {
                    return SKIPPED;
                }
                out.merge(Ids.normalize(name), g, Boolean::logicalOr);
                return self;
            }
            case "minecraft:tag" -> {
                for (String item : itemTags.getOrDefault(Ids.normalize(Json.getString(e, "name", "")), Set.of())) {
                    out.merge(item, false, Boolean::logicalOr);
                }
                return self;
            }
            case "minecraft:alternatives" -> {
                JsonArray children = Json.getArr(e, "children");
                boolean any = false;
                for (int i = 0; children != null && i < children.size(); i++) {
                    int r = entry(children.get(i).getAsJsonObject(), g, itemTags, out);
                    if (r == ALWAYS) {
                        return self;
                    }
                    if (r == MAYBE) {
                        any = true;
                        g = false; // a later alternative only runs when this one did not
                    }
                }
                return any ? MAYBE : SKIPPED;
            }
            case "minecraft:group", "minecraft:sequence" -> {
                JsonArray children = Json.getArr(e, "children");
                for (int i = 0; children != null && i < children.size(); i++) {
                    entry(children.get(i).getAsJsonObject(), g, itemTags, out);
                }
                return self;
            }
            default -> {
                return SKIPPED; // empty, dynamic, nested loot_table
            }
        }
    }

    /** Silk touch / shears branches: a (non-inverted) {@code match_tool}, also inside any_of / all_of. */
    private static boolean needsSpecialTool(JsonArray conds) {
        if (conds == null) {
            return false;
        }
        for (JsonElement c : conds) {
            if (c.isJsonObject() && matchTool(c.getAsJsonObject())) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchTool(JsonObject c) {
        String kind = Ids.normalize(Json.getString(c, "condition", ""));
        return switch (kind) {
            case "minecraft:match_tool" -> true;
            case "minecraft:any_of", "minecraft:alternative" -> {
                JsonArray terms = Json.getArr(c, "terms");
                boolean all = terms != null && !terms.isEmpty();
                for (int i = 0; terms != null && i < terms.size(); i++) {
                    all &= terms.get(i).isJsonObject() && matchTool(terms.get(i).getAsJsonObject());
                }
                yield all;
            }
            case "minecraft:all_of" -> needsSpecialTool(Json.getArr(c, "terms"));
            default -> false; // inverted(match_tool) = the normal branch
        };
    }

    private static boolean chancy(JsonArray conds) {
        if (conds == null) {
            return false;
        }
        for (JsonElement c : conds) {
            String kind = c.isJsonObject() ? Ids.normalize(Json.getString(c.getAsJsonObject(), "condition", "")) : "";
            if (kind.contains("random_chance") || kind.equals("minecraft:table_bonus")) {
                return true;
            }
        }
        return false;
    }

    /** Conditions that do not stop a normal break from producing the item. */
    private static boolean onlyNeutral(JsonArray conds) {
        for (JsonElement c : conds) {
            String kind = c.isJsonObject() ? Ids.normalize(Json.getString(c.getAsJsonObject(), "condition", "")) : "";
            if (!kind.equals("minecraft:survives_explosion") && !kind.equals("minecraft:inverted")) {
                return false;
            }
        }
        return true;
    }
}
