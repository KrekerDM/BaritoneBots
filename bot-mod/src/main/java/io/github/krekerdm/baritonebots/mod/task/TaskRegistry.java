package io.github.krekerdm.baritonebots.mod.task;

import io.github.krekerdm.baritonebots.common.msg.TaskTypes;
import io.github.krekerdm.baritonebots.mod.task.impl.AttackTask;
import io.github.krekerdm.baritonebots.mod.task.impl.BaritoneCommandTask;
import io.github.krekerdm.baritonebots.mod.task.impl.CollectDropsTask;
import io.github.krekerdm.baritonebots.mod.task.impl.CraftTask;
import io.github.krekerdm.baritonebots.mod.task.impl.DepositTask;
import io.github.krekerdm.baritonebots.mod.task.impl.DropTask;
import io.github.krekerdm.baritonebots.mod.task.impl.EatTask;
import io.github.krekerdm.baritonebots.mod.task.impl.EquipTask;
import io.github.krekerdm.baritonebots.mod.task.impl.ExploreTask;
import io.github.krekerdm.baritonebots.mod.task.impl.FarmTask;
import io.github.krekerdm.baritonebots.mod.task.impl.FollowTask;
import io.github.krekerdm.baritonebots.mod.task.impl.GotoPlayerTask;
import io.github.krekerdm.baritonebots.mod.task.impl.GotoTask;
import io.github.krekerdm.baritonebots.mod.task.impl.GuardTask;
import io.github.krekerdm.baritonebots.mod.task.impl.IdleTask;
import io.github.krekerdm.baritonebots.mod.task.impl.InspectTask;
import io.github.krekerdm.baritonebots.mod.task.impl.MineTask;
import io.github.krekerdm.baritonebots.mod.task.impl.RecoverTask;
import io.github.krekerdm.baritonebots.mod.task.impl.SelectionTask;
import io.github.krekerdm.baritonebots.mod.task.impl.SmeltCollectTask;
import io.github.krekerdm.baritonebots.mod.task.impl.SmeltLoadTask;
import io.github.krekerdm.baritonebots.mod.task.impl.TakeTask;
import io.github.krekerdm.baritonebots.mod.task.impl.TransferTask;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Task type → executor factory (SPEC §4.2). Types from {@link TaskTypes#ALL} that are not registered finish with
 * {@code unsupported}; unknown types with {@code bad_args}.
 */
public final class TaskRegistry {
    private static final Map<String, Supplier<TaskExecutor>> FACTORIES = new ConcurrentHashMap<>();

    static {
        register(TaskTypes.IDLE, IdleTask::new);
        register(TaskTypes.GOTO, GotoTask::new);
        register(TaskTypes.GOTO_PLAYER, GotoPlayerTask::new);
        register(TaskTypes.FOLLOW, FollowTask::new);
        register(TaskTypes.EXPLORE, ExploreTask::new);
        register(TaskTypes.BARITONE, BaritoneCommandTask::new);
        register(TaskTypes.MINE, MineTask::new);
        register(TaskTypes.FARM, FarmTask::new);
        register(TaskTypes.SELECTION, SelectionTask::new);
        register(TaskTypes.COLLECT_DROPS, CollectDropsTask::new);
        register(TaskTypes.RECOVER, RecoverTask::new);
        register(TaskTypes.EAT, EatTask::new);
        register(TaskTypes.TAKE, TakeTask::new);
        register(TaskTypes.DEPOSIT, DepositTask::new);
        register(TaskTypes.INSPECT, InspectTask::new);
        register(TaskTypes.EQUIP, EquipTask::new);
        register(TaskTypes.TRANSFER, TransferTask::new);
        register(TaskTypes.DROP, DropTask::new);
        register(TaskTypes.CRAFT, CraftTask::new);
        register(TaskTypes.SMELT_LOAD, SmeltLoadTask::new);
        register(TaskTypes.SMELT_COLLECT, SmeltCollectTask::new);
        register(TaskTypes.ATTACK, AttackTask::new);
        register(TaskTypes.GUARD, GuardTask::new);
    }

    private TaskRegistry() {
    }

    public static void register(String type, Supplier<TaskExecutor> factory) {
        FACTORIES.put(type, factory);
    }

    /** New executor for {@code type}, or {@code null} when not registered. */
    public static TaskExecutor create(String type) {
        Supplier<TaskExecutor> f = type == null ? null : FACTORIES.get(type);
        return f == null ? null : f.get();
    }

    public static Set<String> types() {
        return Set.copyOf(FACTORIES.keySet());
    }
}
