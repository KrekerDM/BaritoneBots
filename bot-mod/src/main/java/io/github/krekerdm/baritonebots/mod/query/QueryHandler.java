package io.github.krekerdm.baritonebots.mod.query;

import baritone.api.schematic.IStaticSchematic;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.geom.Box;
import io.github.krekerdm.baritonebots.common.ids.Ids;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.Envelope;
import io.github.krekerdm.baritonebots.common.msg.MessageTypes;
import io.github.krekerdm.baritonebots.common.msg.Query;
import io.github.krekerdm.baritonebots.common.msg.QueryKinds;
import io.github.krekerdm.baritonebots.common.msg.QueryResult;
import io.github.krekerdm.baritonebots.mod.BotRuntime;
import io.github.krekerdm.baritonebots.mod.ModInfo;
import io.github.krekerdm.baritonebots.mod.schematic.Placement;
import io.github.krekerdm.baritonebots.mod.schematic.SchematicArgs;
import io.github.krekerdm.baritonebots.mod.schematic.SchematicScan;
import io.github.krekerdm.baritonebots.mod.schematic.SchematicStore;
import io.github.krekerdm.baritonebots.mod.util.Inv;
import io.github.krekerdm.baritonebots.mod.util.McIds;
import io.github.krekerdm.baritonebots.mod.util.Positions;
import io.github.krekerdm.baritonebots.mod.util.Recipes;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Answers {@code query} messages (SPEC §2.4) on the client thread: inventory, entities, player, block_at,
 * containers_nearby, recipe_book right away; {@code bom}/{@code progress} through a queue — the schematic is parsed
 * on a loader thread, then scanned chunk column by chunk column within {@value #SCAN_BUDGET_MS} ms per tick, and the
 * answer is sent when the scan is done.
 */
public final class QueryHandler {
    private static final long SCAN_BUDGET_MS = 15;
    private static final long SCAN_BUDGET_NANOS = SCAN_BUDGET_MS * 1_000_000;
    private static final long SCHEMATIC_TIMEOUT_MS = 120_000;
    private static final int MAX_SCHEMATIC_JOBS = 8;
    private static final int MAX_RADIUS = 128;
    private static final int MAX_SCAN_RADIUS = 32;
    private static final List<String> CONTAINER_GLOBS = List.of("minecraft:chest", "minecraft:trapped_chest",
            "minecraft:barrel", "minecraft:*shulker_box", "minecraft:furnace", "minecraft:blast_furnace",
            "minecraft:smoker", "minecraft:crafting_table", "minecraft:hopper", "minecraft:dispenser",
            "minecraft:dropper");

    private final BotRuntime bot;
    private final ArrayDeque<SchematicJob> schematicJobs = new ArrayDeque<>();
    private long budgetTick = -1;
    private long budgetEnd;

    public QueryHandler(BotRuntime bot) {
        this.bot = bot;
    }

    public void handle(Envelope e) {
        if (e.id() == null) {
            bot.warn("query without id cannot be answered");
            return;
        }
        QueryResult result;
        try {
            Query q = e.payload(Query.class);
            if (QueryKinds.BOM.equals(q.kind()) || QueryKinds.PROGRESS.equals(q.kind())) {
                result = enqueue(e.id(), q.kind(), q.args() == null ? new JsonObject() : q.args());
            } else {
                result = answer(q.kind(), q.args());
            }
        } catch (RuntimeException ex) {
            ModInfo.LOG.error("Query failed", ex);
            result = QueryResult.failure("error: " + ex);
        }
        if (result != null) {
            reply(e.id(), result);
        }
    }

    private void reply(String id, QueryResult result) {
        bot.link.send(Envelope.reply(MessageTypes.RESULT, id, result), true);
    }

    /** Advances queued {@code bom}/{@code progress} scans within the per-tick budget (client thread). */
    public void tick() {
        pump();
    }

    /** Queues a schematic query; returns an immediate answer for bad input, or null when it will answer later. */
    private QueryResult enqueue(String id, String kind, JsonObject args) {
        SchematicArgs sa;
        try {
            sa = SchematicArgs.parse(args);
        } catch (IllegalArgumentException ex) {
            return QueryResult.failure("bad_args: " + ex.getMessage());
        }
        if (QueryKinds.PROGRESS.equals(kind) && !bot.inGame()) {
            return QueryResult.failure("not_in_game");
        }
        if (schematicJobs.size() >= MAX_SCHEMATIC_JOBS) {
            return QueryResult.failure("busy: " + schematicJobs.size() + " schematic queries queued");
        }
        schematicJobs.add(new SchematicJob(id, kind, sa, SchematicStore.load(sa.file())));
        pump();
        return null;
    }

    /** Runs schematic jobs in order until the tick budget is used up. */
    private void pump() {
        if (bot.ticks() != budgetTick) {
            budgetTick = bot.ticks(); // one budget per client tick, shared by every pump in it
            budgetEnd = System.nanoTime() + SCAN_BUDGET_NANOS;
        }
        long deadline = budgetEnd;
        while (!schematicJobs.isEmpty()) {
            SchematicJob job = schematicJobs.peek();
            QueryResult r;
            try {
                r = job.advance(deadline);
            } catch (RuntimeException ex) {
                ModInfo.LOG.error("Schematic query failed", ex);
                r = QueryResult.failure("error: " + ex);
            }
            if (r == null) {
                return; // waiting for the loader or out of time
            }
            schematicJobs.poll();
            reply(job.id, r);
            if (System.nanoTime() >= deadline) {
                return;
            }
        }
    }

    /** One {@code bom}/{@code progress} query: wait for the schematic, then scan chunk columns across ticks. */
    private final class SchematicJob {
        private final String id;
        private final String kind;
        private final SchematicArgs args;
        private final CompletableFuture<IStaticSchematic> load;
        private final long startedMs = System.currentTimeMillis();
        private SchematicScan scan;

        SchematicJob(String id, String kind, SchematicArgs args, CompletableFuture<IStaticSchematic> load) {
            this.id = id;
            this.kind = kind;
            this.args = args;
            this.load = load;
        }

        /** Result when finished, else null. */
        QueryResult advance(long deadline) {
            if (System.currentTimeMillis() - startedMs > SCHEMATIC_TIMEOUT_MS) {
                return QueryResult.failure("timeout");
            }
            if (scan == null) {
                if (!load.isDone()) {
                    return null;
                }
                IStaticSchematic s;
                try {
                    s = load.join();
                } catch (RuntimeException ex) {
                    SchematicStore.LoadException le = SchematicStore.unwrap(ex);
                    return QueryResult.failure(le.reason() + ": " + le.getMessage());
                }
                boolean progress = QueryKinds.PROGRESS.equals(kind);
                if (progress && !bot.inGame()) {
                    return QueryResult.failure("not_in_game");
                }
                Placement placement = args.placement(s);
                Box region = placement.region(args.box());
                if (region == null) {
                    return QueryResult.success(progress
                            ? Json.obj("total", 0, "correct", 0, "missing", 0, "wrong", 0, "unloaded", 0,
                                    "toClear", 0, "remaining", new JsonObject())
                            : Json.obj("items", new JsonObject(), "blocks", 0));
                }
                scan = new SchematicScan(s, placement, region,
                        progress ? SchematicScan.Mode.PROGRESS : SchematicScan.Mode.BOM, progress ? bot.level() : null);
            }
            if (scan.level() != null && scan.level() != bot.level()) {
                return QueryResult.failure("not_in_game");
            }
            if (!scan.step(deadline)) {
                return null;
            }
            return QueryResult.success(scan.level() == null ? scan.bomJson() : scan.progressJson(args.box() != null));
        }
    }

    private QueryResult answer(String kind, JsonObject args) {
        if (kind == null || !QueryKinds.ALL.contains(kind)) {
            return QueryResult.failure("unsupported");
        }
        boolean needsGame = switch (kind) {
            case QueryKinds.INVENTORY, QueryKinds.ENTITIES, QueryKinds.PLAYER, QueryKinds.BLOCK_AT,
                 QueryKinds.CONTAINERS_NEARBY, QueryKinds.RECIPE_BOOK -> true;
            default -> false;
        };
        if (!needsGame) {
            return QueryResult.failure("unsupported");
        }
        if (!bot.inGame()) {
            return QueryResult.failure("not_in_game");
        }
        return switch (kind) {
            case QueryKinds.INVENTORY -> QueryResult.success(inventory(bot.player()));
            case QueryKinds.ENTITIES -> QueryResult.success(entities(args));
            case QueryKinds.PLAYER -> QueryResult.success(player(args));
            case QueryKinds.BLOCK_AT -> blockAt(args);
            case QueryKinds.CONTAINERS_NEARBY -> QueryResult.success(containersNearby(args));
            case QueryKinds.RECIPE_BOOK -> recipeBook(args);
            default -> QueryResult.failure("unsupported");
        };
    }

    private JsonObject inventory(LocalPlayer p) {
        JsonArray slots = new JsonArray();
        for (int i = 0; i < Inv.MAIN_SIZE; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.isEmpty()) {
                continue;
            }
            JsonObject o = Json.obj("slot", i, "item", McIds.item(s), "count", s.getCount(),
                    "damage", s.getDamageValue(), "maxDamage", s.getMaxDamage());
            if (s.has(DataComponents.CUSTOM_NAME)) {
                o.addProperty("name", s.getHoverName().getString());
            }
            slots.add(o);
        }
        JsonArray armor = new JsonArray();
        for (EquipmentSlot slot : Inv.ARMOR_HEAD_TO_FEET) {
            String id = McIds.item(p.getItemBySlot(slot));
            if (id == null) {
                armor.add(JsonNull.INSTANCE);
            } else {
                armor.add(id);
            }
        }
        JsonObject d = Json.obj("slots", slots, "armor", armor, "selected", p.getInventory().getSelectedSlot());
        String off = McIds.item(p.getOffhandItem());
        d.add("offhand", off == null ? JsonNull.INSTANCE : Json.toTree(off));
        return d;
    }

    private JsonObject entities(JsonObject args) {
        LocalPlayer self = bot.player();
        int radius = Math.max(1, Math.min(MAX_RADIUS, Json.getInt(args, "radius", 16)));
        List<String> types = Json.getStringList(args, "types").stream().map(Ids::normalize).toList();
        JsonArray out = new JsonArray();
        AABB box = self.getBoundingBox().inflate(radius);
        for (Entity e : bot.level().getEntities(self, box, e -> e.isAlive() && self.distanceTo(e) <= radius)) {
            String type = McIds.entity(e);
            if (!types.isEmpty() && !types.contains(type)) {
                continue;
            }
            JsonObject o = Json.obj("type", type, "x", e.getX(), "y", e.getY(), "z", e.getZ(),
                    "player", e instanceof Player);
            if (e instanceof Player pl) {
                o.addProperty("name", pl.getGameProfile().name());
            } else if (e.hasCustomName()) {
                o.addProperty("name", e.getName().getString());
            }
            if (e instanceof LivingEntity le) {
                o.addProperty("health", le.getHealth());
                o.addProperty("baby", le.isBaby());
            }
            out.add(o);
        }
        return Json.obj("entities", out);
    }

    private JsonObject player(JsonObject args) {
        String name = Json.getString(args, "name", "");
        for (Player pl : bot.level().players()) {
            if (pl.getGameProfile().name().equalsIgnoreCase(name)) {
                return Json.obj("found", true, "pos", Positions.json(pl.blockPosition()), "dim", McIds.dim(bot.level()));
            }
        }
        return Json.obj("found", false);
    }

    private QueryResult blockAt(JsonObject args) {
        Pos p = Pos.fromJson(args.get("pos"));
        if (p == null) {
            return QueryResult.failure("bad_args: pos required");
        }
        BlockPos bp = Positions.toBlockPos(p);
        ClientLevel level = bot.level();
        boolean loaded = level.isLoaded(bp) && level.getChunkSource().getChunk(bp.getX() >> 4, bp.getZ() >> 4,
                ChunkStatus.FULL, false) != null;
        if (!loaded) {
            return QueryResult.success(Json.obj("block", null, "loaded", false));
        }
        return QueryResult.success(Json.obj("block", McIds.state(level.getBlockState(bp)), "loaded", true));
    }

    /** {@code recipe_book {item}}: known crafting recipes for the item; {@code craftingTable} = needs the 3×3 grid. */
    private QueryResult recipeBook(JsonObject args) {
        String item = Json.getString(args, "item", "");
        if (item.isBlank()) {
            return QueryResult.failure("bad_args: item required");
        }
        JsonArray out = new JsonArray();
        for (Recipes.Option o : Recipes.forItem(bot.player(), bot.level(), Ids.normalize(item.trim()))) {
            out.add(Json.obj("displayId", o.displayId(), "craftingTable", o.needsTable(), "count", o.perCraft()));
        }
        return QueryResult.success(Json.obj("recipes", out));
    }

    private JsonObject containersNearby(JsonObject args) {
        LocalPlayer self = bot.player();
        ClientLevel level = bot.level();
        int radius = Math.max(1, Math.min(MAX_RADIUS, Json.getInt(args, "radius", 16)));
        BlockPos center = self.blockPosition();
        String dim = McIds.dim(level);
        JsonArray out = new JsonArray();
        Set<BlockPos> seen = new HashSet<>();
        int minCx = (center.getX() - radius) >> 4;
        int maxCx = (center.getX() + radius) >> 4;
        int minCz = (center.getZ() - radius) >> 4;
        int maxCz = (center.getZ() + radius) >> 4;
        long r2 = (long) radius * radius;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) {
                    continue;
                }
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    BlockPos pos = be.getBlockPos();
                    if (pos.distSqr(center) > r2) {
                        continue;
                    }
                    addIfContainer(out, seen, level, pos, dim);
                }
            }
        }
        // Crafting tables have no block entity: scan a bounded cube.
        int sr = Math.min(radius, MAX_SCAN_RADIUS);
        int minY = Math.max(level.getMinY(), center.getY() - sr);
        int maxY = Math.min(level.getMaxY(), center.getY() + sr);
        for (BlockPos pos : BlockPos.betweenClosed(center.getX() - sr, minY, center.getZ() - sr,
                center.getX() + sr, maxY, center.getZ() + sr)) {
            if (pos.distSqr(center) <= (long) sr * sr && level.isLoaded(pos)) {
                BlockState st = level.getBlockState(pos);
                if (!st.isAir() && "minecraft:crafting_table".equals(McIds.block(st))) {
                    addIfContainer(out, seen, level, pos.immutable(), dim);
                }
            }
        }
        return Json.obj("containers", out);
    }

    private static void addIfContainer(JsonArray out, Set<BlockPos> seen, ClientLevel level, BlockPos pos, String dim) {
        String id = McIds.block(level.getBlockState(pos));
        if (Ids.matchesAny(CONTAINER_GLOBS, id) && seen.add(pos.immutable())) {
            out.add(Json.obj("pos", Positions.json(pos), "dim", dim, "block", id));
        }
    }
}
