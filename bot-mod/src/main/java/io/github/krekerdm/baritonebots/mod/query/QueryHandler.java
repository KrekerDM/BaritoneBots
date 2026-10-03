package io.github.krekerdm.baritonebots.mod.query;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.ids.Ids;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.Envelope;
import io.github.krekerdm.baritonebots.common.msg.MessageTypes;
import io.github.krekerdm.baritonebots.common.msg.Query;
import io.github.krekerdm.baritonebots.common.msg.QueryKinds;
import io.github.krekerdm.baritonebots.common.msg.QueryResult;
import io.github.krekerdm.baritonebots.mod.BotRuntime;
import io.github.krekerdm.baritonebots.mod.ModInfo;
import io.github.krekerdm.baritonebots.mod.util.Inv;
import io.github.krekerdm.baritonebots.mod.util.McIds;
import io.github.krekerdm.baritonebots.mod.util.Positions;
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

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Answers {@code query} messages (SPEC §2.4) on the client thread. Implemented: inventory, entities, player,
 * block_at, containers_nearby. Other kinds (bom, progress, recipe_book) answer {@code ok:false, error:"unsupported"}.
 */
public final class QueryHandler {
    private static final int MAX_RADIUS = 128;
    private static final int MAX_SCAN_RADIUS = 32;
    private static final List<String> CONTAINER_GLOBS = List.of("minecraft:chest", "minecraft:trapped_chest",
            "minecraft:barrel", "minecraft:*shulker_box", "minecraft:furnace", "minecraft:blast_furnace",
            "minecraft:smoker", "minecraft:crafting_table", "minecraft:hopper", "minecraft:dispenser",
            "minecraft:dropper");

    private final BotRuntime bot;

    public QueryHandler(BotRuntime bot) {
        this.bot = bot;
    }

    public void handle(Envelope e) {
        QueryResult result;
        try {
            Query q = e.payload(Query.class);
            result = answer(q.kind(), q.args());
        } catch (RuntimeException ex) {
            ModInfo.LOG.error("Query failed", ex);
            result = QueryResult.failure("error: " + ex);
        }
        if (e.id() == null) {
            bot.warn("query without id cannot be answered");
            return;
        }
        bot.link.send(Envelope.reply(MessageTypes.RESULT, e.id(), result), true);
    }

    private QueryResult answer(String kind, JsonObject args) {
        if (kind == null || !QueryKinds.ALL.contains(kind)) {
            return QueryResult.failure("unsupported");
        }
        boolean needsGame = switch (kind) {
            case QueryKinds.INVENTORY, QueryKinds.ENTITIES, QueryKinds.PLAYER, QueryKinds.BLOCK_AT,
                 QueryKinds.CONTAINERS_NEARBY -> true;
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
