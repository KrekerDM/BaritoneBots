package io.github.krekerdm.baritonebots.mod.task;

import io.github.krekerdm.baritonebots.mod.util.Inv;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Container clicks sent at most {@value #MAX_PER_TICK} per tick (servers kick or desync on click bursts). Clicks
 * are bound to a menu; if the open menu changes the remaining clicks are dropped.
 */
public final class ClickQueue {
    public static final int MAX_PER_TICK = 2;

    private record Click(int containerId, int slot, int button, ContainerInput input) {
    }

    private final Deque<Click> queue = new ArrayDeque<>();

    public void add(AbstractContainerMenu menu, int slot, int button, ContainerInput input) {
        queue.add(new Click(menu.containerId, slot, button, input));
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    public void clear() {
        queue.clear();
    }

    /** Sends up to {@value #MAX_PER_TICK} clicks; returns {@code false} if the menu changed and clicks were dropped. */
    public boolean tick(Minecraft mc, LocalPlayer p) {
        int sent = 0;
        while (sent < MAX_PER_TICK && !queue.isEmpty()) {
            Click c = queue.peek();
            AbstractContainerMenu menu = p.containerMenu;
            if (menu == null || menu.containerId != c.containerId()) {
                queue.clear();
                return false;
            }
            queue.poll();
            Inv.click(mc, p, menu, c.slot(), c.button(), c.input());
            sent++;
        }
        return true;
    }
}
