package io.github.krekerdm.baritonebots.mod.task.impl;

import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.process.IBuilderProcess;
import baritone.api.schematic.IStaticSchematic;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.geom.Box;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.Reasons;
import io.github.krekerdm.baritonebots.mod.baritone.SettingsOverride;
import io.github.krekerdm.baritonebots.mod.schematic.PlacedSchematic;
import io.github.krekerdm.baritonebots.mod.schematic.Placement;
import io.github.krekerdm.baritonebots.mod.schematic.SchematicArgs;
import io.github.krekerdm.baritonebots.mod.schematic.SchematicScan;
import io.github.krekerdm.baritonebots.mod.schematic.SchematicStore;
import io.github.krekerdm.baritonebots.mod.task.TaskArgs;
import io.github.krekerdm.baritonebots.mod.task.TaskContext;
import io.github.krekerdm.baritonebots.mod.task.TaskExecutor;
import io.github.krekerdm.baritonebots.mod.util.Inv;
import io.github.krekerdm.baritonebots.mod.util.Positions;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * {@code build file origin [rotation=0] [mirror=none] [box] [name] [buildInLayers] [layerOrder]}: loads the schematic
 * with Baritone's schematic system, wraps it in a {@link PlacedSchematic} (rotation/mirror like common's
 * {@code SchematicTransform}, cropped to {@code box}) and runs {@code IBuilderProcess.build(name, schematic,
 * regionMin)}. Builder inactive and not paused → ok. Builder paused (Baritone cannot place anything) → the region is
 * scanned (loaded chunks, across ticks) and the task fails {@code missing_materials} with {@code data.missing} =
 * items for still-wrong positions minus the inventory, or {@code stuck} when nothing is missing.
 */
public final class BuildTask implements TaskExecutor {
    private static final long SCAN_BUDGET_NANOS = 10_000_000;
    private static final int DONE_AFTER_INACTIVE_TICKS = 5;

    private enum Phase { LOADING, BUILDING, SCANNING }

    private final SettingsOverride settings = new SettingsOverride();
    private SchematicArgs args;
    private String name;
    private CompletableFuture<IStaticSchematic> load;
    private IStaticSchematic schematic;
    private Placement placement;
    private Box region;
    private SchematicScan scan;
    private Phase phase = Phase.LOADING;
    private int inactive;

    @Override
    public void start(TaskContext ctx) {
        try {
            args = SchematicArgs.parse(ctx.args());
        } catch (IllegalArgumentException e) {
            ctx.fail(Reasons.BAD_ARGS, e.getMessage(), null);
            return;
        }
        name = Json.getString(ctx.args(), "name", "");
        if (name.isBlank()) {
            name = "BaritoneBots " + new java.io.File(args.file()).getName();
        }
        load = SchematicStore.load(args.file());
        ctx.step("loading schematic", -1);
    }

    @Override
    public void tick(TaskContext ctx) {
        switch (phase) {
            case LOADING -> startBuilding(ctx);
            case BUILDING -> watchBuilder(ctx);
            case SCANNING -> finishScan(ctx);
        }
    }

    private void startBuilding(TaskContext ctx) {
        if (!load.isDone()) {
            return;
        }
        try {
            schematic = load.join();
        } catch (RuntimeException e) {
            SchematicStore.LoadException le = SchematicStore.unwrap(e);
            ctx.fail(Reasons.NOT_FOUND.equals(le.reason()) || Reasons.UNSUPPORTED.equals(le.reason())
                    ? Reasons.BAD_ARGS : le.reason(), le.getMessage(), Json.obj("file", args.file()));
            return;
        }
        placement = args.placement(schematic);
        region = placement.region(args.box());
        if (region == null) {
            ctx.fail(Reasons.BAD_ARGS, "box does not overlap the schematic footprint " + placement.footprint(),
                    Json.obj("footprint", Json.toTree(placement.footprint())));
            return;
        }
        applySettings(ctx.args());
        ctx.baritone().getBuilderProcess().build(name, new PlacedSchematic(schematic, placement, region),
                Positions.toBlockPos(region.min()));
        phase = Phase.BUILDING;
        ctx.step("building " + name, -1);
    }

    /** Settings that would shift or cut the build are neutralised; layer options come from the task args. */
    private void applySettings(JsonObject a) {
        Settings s = BaritoneAPI.getSettings();
        settings.set(s.schematicOrientationX, false);
        settings.set(s.schematicOrientationY, false);
        settings.set(s.schematicOrientationZ, false);
        settings.set(s.buildOnlySelection, false);
        settings.set(s.startAtLayer, 0);
        if (a.has("buildInLayers")) {
            settings.set(s.buildInLayers, Json.getBool(a, "buildInLayers", s.buildInLayers.value));
        }
        if (a.has("layerOrder")) {
            settings.set(s.layerOrder, parseLayerOrder(a, s.layerOrder.value));
        }
    }

    /** {@code layerOrder}: Baritone's boolean (true = top to bottom) or "top_down" / "bottom_up". */
    private static boolean parseLayerOrder(JsonObject a, boolean def) {
        String v = Json.getString(a, "layerOrder", "").trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "true", "top_down", "top", "down" -> true;
            case "false", "bottom_up", "bottom", "up" -> false;
            default -> Json.getBool(a, "layerOrder", def);
        };
    }

    private void watchBuilder(TaskContext ctx) {
        IBuilderProcess b = ctx.baritone().getBuilderProcess();
        if (b.isActive() && b.isPaused()) {
            scan = new SchematicScan(schematic, placement, region, SchematicScan.Mode.PROGRESS,
                    ctx.bot().level());
            phase = Phase.SCANNING;
            ctx.step("builder paused, checking materials", -1);
            return;
        }
        if (b.isActive()) {
            inactive = 0;
        } else if (++inactive >= DONE_AFTER_INACTIVE_TICKS) {
            ctx.succeed("build " + name + " done", Json.obj("box", Json.toTree(region), "name", name));
        }
    }

    private void finishScan(TaskContext ctx) {
        if (scan.level() != ctx.bot().level()) {
            ctx.fail(Reasons.STUCK, "builder paused (world changed during the material check)", null);
            return;
        }
        if (!scan.step(System.nanoTime() + SCAN_BUDGET_NANOS)) {
            ctx.step("builder paused, checking materials", scan.fraction());
            return;
        }
        Map<String, Integer> missing = scan.missingVs(Inv.totals(ctx.player(), false));
        JsonObject data = Json.obj("box", Json.toTree(region), "todo", scan.loadedTodo());
        if (missing.isEmpty()) {
            ctx.fail(Reasons.STUCK, "Baritone builder is paused", data);
        } else {
            data.add("missing", TaskArgs.counts(missing));
            ctx.fail(Reasons.MISSING_MATERIALS, "not enough materials for " + name, data);
        }
    }

    @Override
    public void cleanup(TaskContext ctx) {
        if (phase != Phase.LOADING && ctx.baritone() != null) {
            ctx.baritone().getBuilderProcess().onLostControl();
        }
        settings.restore();
    }
}
