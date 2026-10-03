package io.github.krekerdm.baritonebots.mod.task.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.json.Json;
import io.github.krekerdm.baritonebots.common.msg.Reasons;
import io.github.krekerdm.baritonebots.common.msg.TaskResult;
import io.github.krekerdm.baritonebots.common.msg.TaskTypes;
import io.github.krekerdm.baritonebots.mod.task.SubTask;
import io.github.krekerdm.baritonebots.mod.task.TaskArgs;
import io.github.krekerdm.baritonebots.mod.task.TaskContext;
import io.github.krekerdm.baritonebots.mod.task.TaskExecutor;
import io.github.krekerdm.baritonebots.mod.util.Inv;

import java.util.Map;

/**
 * {@code transfer from to items}: {@link TakeTask} from {@code from}, then {@link DepositTask} into {@code to} with
 * {@code only} = the taken ids and {@code keepCounts} = what the bot held of them before, so exactly the taken items
 * move on. ok data {@code {taken, missing, moved, left, failed}}; a failing step fails the transfer with its reason.
 */
public final class TransferTask implements TaskExecutor {
    private JsonElement to;
    private Map<String, Integer> before;
    private SubTask take;
    private SubTask deposit;
    private JsonObject takeData = new JsonObject();

    @Override
    public void start(TaskContext ctx) {
        JsonObject a = ctx.args();
        if (TaskArgs.pos(a, "from") == null || TaskArgs.posList(a, "to").isEmpty()
                || TaskArgs.itemRequests(a, "items").isEmpty()) {
            ctx.fail(Reasons.BAD_ARGS, "transfer needs 'from', 'to' and 'items'", null);
            return;
        }
        to = a.get("to");
        before = Inv.totals(ctx.player(), false);
        take = new SubTask(ctx, TaskTypes.TAKE, Json.obj("container", a.get("from"), "items", a.get("items")),
                new TakeTask());
        take.start();
        afterTick(ctx);
    }

    @Override
    public void tick(TaskContext ctx) {
        if (deposit == null) {
            take.tick();
        } else {
            deposit.tick();
        }
        afterTick(ctx);
    }

    private void afterTick(TaskContext ctx) {
        if (deposit == null && take.finished()) {
            take.end(false);
            TaskResult r = take.result();
            takeData = r.data() == null ? new JsonObject() : r.data();
            if (!r.ok()) {
                ctx.fail(r.reason(), "take: " + r.message(), data(null));
                return;
            }
            JsonObject taken = Json.getObj(takeData, "taken");
            if (taken == null || taken.size() == 0) {
                ctx.succeed("nothing to transfer", data(null));
                return;
            }
            JsonArray only = new JsonArray();
            JsonObject keepCounts = new JsonObject();
            for (String id : taken.keySet()) {
                only.add(id);
                int had = before.getOrDefault(id, 0);
                if (had > 0) {
                    keepCounts.addProperty(id, had);
                }
            }
            deposit = new SubTask(ctx, TaskTypes.DEPOSIT, Json.obj("containers", to, "only", only,
                    "keepCounts", keepCounts), new DepositTask());
            deposit.start();
        }
        if (deposit != null && deposit.finished()) {
            deposit.end(false);
            TaskResult r = deposit.result();
            if (r.ok()) {
                ctx.succeed("transferred", data(r.data()));
            } else {
                ctx.fail(r.reason(), "deposit: " + r.message(), data(r.data()));
            }
        }
    }

    private JsonObject data(JsonObject depositData) {
        JsonObject d = new JsonObject();
        for (String k : new String[]{"taken", "missing", "inventoryFull"}) {
            if (takeData.has(k)) {
                d.add(k, takeData.get(k));
            }
        }
        if (depositData != null) {
            for (String k : new String[]{"moved", "left", "failed"}) {
                if (depositData.has(k)) {
                    d.add(k, depositData.get(k));
                }
            }
        }
        return d;
    }

    @Override
    public void cancel(TaskContext ctx) {
        if (take != null) {
            take.end(true);
        }
        if (deposit != null) {
            deposit.end(true);
        }
    }

    @Override
    public void cleanup(TaskContext ctx) {
        if (take != null) {
            take.end(false);
        }
        if (deposit != null) {
            deposit.end(false);
        }
    }
}
