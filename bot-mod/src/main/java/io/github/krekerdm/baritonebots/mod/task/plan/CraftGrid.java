package io.github.krekerdm.baritonebots.mod.task.plan;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import io.github.krekerdm.baritonebots.common.ids.Ids;
import io.github.krekerdm.baritonebots.common.json.Json;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * The {@code craft} task's manual pattern (SPEC §3 {@code grid}): up to 3×3 cells, row-major from the top-left, each
 * {@code null} or a list of acceptable item ids/globs. The pattern is shifted to the top-left of the crafting grid
 * (shaped recipes match anywhere in the grid). Pure logic, unit-tested.
 */
public final class CraftGrid {
    public static final int MAX = 3;

    /** One non-empty pattern cell placed in a crafting grid: input-slot index (row-major) and accepted globs. */
    public record Cell(int gridIndex, List<String> accepts) {
    }

    /** One placement round: {@code sets} items go into every cell; {@code ids} = chosen item id per grid index. */
    public record Round(int sets, Map<Integer, String> ids) {
        public static final Round NONE = new Round(0, Map.of());
    }

    private final List<List<String>> cells; // 9 entries, row-major, null = empty
    private final int minX;
    private final int minY;
    private final int width;
    private final int height;

    private CraftGrid(List<List<String>> cells) {
        this.cells = cells;
        int x0 = MAX, y0 = MAX, x1 = -1, y1 = -1;
        for (int y = 0; y < MAX; y++) {
            for (int x = 0; x < MAX; x++) {
                if (cells.get(y * MAX + x) != null) {
                    x0 = Math.min(x0, x);
                    y0 = Math.min(y0, y);
                    x1 = Math.max(x1, x);
                    y1 = Math.max(y1, y);
                }
            }
        }
        this.minX = x1 < 0 ? 0 : x0;
        this.minY = y1 < 0 ? 0 : y0;
        this.width = x1 < 0 ? 0 : x1 - x0 + 1;
        this.height = y1 < 0 ? 0 : y1 - y0 + 1;
    }

    /**
     * Parses {@code [[cell ×≤3] ×≤3]} where a cell is {@code null}, an id string, or a list of ids (empty list =
     * empty cell). A JSON string holding that array is accepted too. Returns {@code null} for a missing/null grid.
     *
     * @throws IllegalArgumentException on any other shape
     */
    public static CraftGrid parse(JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (e.isJsonPrimitive()) {
            String s = e.getAsString().trim();
            if (s.isEmpty()) {
                return null;
            }
            try {
                e = Json.parse(s);
            } catch (RuntimeException ex) {
                throw new IllegalArgumentException("grid is not valid JSON");
            }
        }
        if (!e.isJsonArray()) {
            throw new IllegalArgumentException("grid must be an array of rows");
        }
        JsonArray rows = e.getAsJsonArray();
        if (rows.size() > MAX) {
            throw new IllegalArgumentException("grid has more than 3 rows");
        }
        List<List<String>> cells = new ArrayList<>();
        for (int i = 0; i < MAX * MAX; i++) {
            cells.add(null);
        }
        for (int y = 0; y < rows.size(); y++) {
            JsonElement row = rows.get(y);
            if (row == null || row.isJsonNull()) {
                continue;
            }
            if (!row.isJsonArray() || row.getAsJsonArray().size() > MAX) {
                throw new IllegalArgumentException("grid row " + y + " must be an array of at most 3 cells");
            }
            JsonArray r = row.getAsJsonArray();
            for (int x = 0; x < r.size(); x++) {
                cells.set(y * MAX + x, parseCell(r.get(x), x, y));
            }
        }
        return new CraftGrid(cells);
    }

    private static List<String> parseCell(JsonElement c, int x, int y) {
        if (c == null || c.isJsonNull()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        if (c.isJsonPrimitive()) {
            addId(out, c.getAsString());
        } else if (c.isJsonArray()) {
            for (JsonElement id : c.getAsJsonArray()) {
                if (id == null || !id.isJsonPrimitive()) {
                    throw new IllegalArgumentException("grid cell " + x + "," + y + " must list item ids");
                }
                addId(out, id.getAsString());
            }
        } else {
            throw new IllegalArgumentException("grid cell " + x + "," + y + " must be null, an id or a list of ids");
        }
        return out.isEmpty() ? null : List.copyOf(out);
    }

    private static void addId(List<String> out, String s) {
        if (s != null && !s.isBlank()) {
            out.add(Ids.normalizeGlob(s.trim()));
        }
    }

    public boolean isEmpty() {
        return width == 0;
    }

    /** Width of the pattern's bounding box. */
    public int width() {
        return width;
    }

    /** Height of the pattern's bounding box. */
    public int height() {
        return height;
    }

    public boolean fits(int gridWidth, int gridHeight) {
        return width <= gridWidth && height <= gridHeight;
    }

    /** Pattern cells shifted to the top-left of a {@code gridWidth}-wide crafting grid. */
    public List<Cell> layout(int gridWidth) {
        List<Cell> out = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                List<String> accepts = cells.get((minY + y) * MAX + minX + x);
                if (accepts != null) {
                    out.add(new Cell(y * gridWidth + x, accepts));
                }
            }
        }
        return out;
    }

    /**
     * Plans one round of manual placement: picks an available item id for every cell and the number of sets
     * (items per cell) so that every cell gets the same amount, capped by {@code maxSets}, the chosen items' stack
     * sizes and how many of each id the inventory holds. Cells prefer the id that leaves the most sets possible.
     *
     * @param available item id → count in the inventory
     * @param maxStack  item id → max stack size
     * @return {@link Round#NONE} when some cell has no available item or {@code maxSets} ≤ 0
     */
    public static Round planRound(List<Cell> cells, Map<String, Integer> available, ToIntFunction<String> maxStack,
                                  int maxSets) {
        if (cells.isEmpty() || maxSets <= 0) {
            return Round.NONE;
        }
        Map<String, Integer> uses = new HashMap<>();
        Map<Integer, String> chosen = new LinkedHashMap<>();
        for (Cell cell : cells) {
            String best = null;
            int bestScore = -1;
            boolean bestUsed = false;
            for (String glob : cell.accepts()) {
                for (Map.Entry<String, Integer> e : available.entrySet()) {
                    String id = e.getKey();
                    int have = e.getValue() == null ? 0 : e.getValue();
                    if (have <= 0 || !Ids.matches(glob, id)) {
                        continue;
                    }
                    int u = uses.getOrDefault(id, 0);
                    int score = have / (u + 1);
                    boolean used = u > 0;
                    if (score > bestScore || (score == bestScore && used && !bestUsed)) {
                        best = id;
                        bestScore = score;
                        bestUsed = used;
                    }
                }
            }
            if (best == null || bestScore <= 0) {
                return Round.NONE;
            }
            chosen.put(cell.gridIndex(), best);
            uses.merge(best, 1, Integer::sum);
        }
        int sets = maxSets;
        for (Map.Entry<String, Integer> u : uses.entrySet()) {
            int have = available.getOrDefault(u.getKey(), 0);
            sets = Math.min(sets, have / u.getValue());
            sets = Math.min(sets, Math.max(1, maxStack.applyAsInt(u.getKey())));
        }
        return sets <= 0 ? Round.NONE : new Round(sets, chosen);
    }
}
