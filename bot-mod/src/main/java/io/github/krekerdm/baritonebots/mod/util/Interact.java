package io.github.krekerdm.baritonebots.mod.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Direct player actions (look, use a block, attack, close menus, release keys). Client thread only. */
public final class Interact {
    /** Vanilla block interaction range is 4.5 from the eyes; stay a little inside it. */
    public static final double BLOCK_REACH = 4.3;
    /** Vanilla entity interaction range is 3.0. */
    public static final double ENTITY_REACH = 3.0;

    private Interact() {
    }

    /** Turns the player to look at {@code target}. */
    public static void lookAt(LocalPlayer p, Vec3 target) {
        Vec3 eye = p.getEyePosition();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        p.setYRot(yaw);
        p.setXRot(Math.max(-90f, Math.min(90f, pitch)));
        p.setYHeadRot(yaw);
    }

    /** Face of {@code pos} that points towards the player's eyes. */
    public static Direction faceTowards(LocalPlayer p, BlockPos pos) {
        Vec3 eye = p.getEyePosition();
        double dx = eye.x - (pos.getX() + 0.5);
        double dy = eye.y - (pos.getY() + 0.5);
        double dz = eye.z - (pos.getZ() + 0.5);
        double ax = Math.abs(dx);
        double ay = Math.abs(dy);
        double az = Math.abs(dz);
        if (ay >= ax && ay >= az) {
            return dy > 0 ? Direction.UP : Direction.DOWN;
        }
        if (ax >= az) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    public static double eyeDistanceTo(LocalPlayer p, BlockPos pos) {
        return p.getEyePosition().distanceTo(Positions.center(pos));
    }

    /** Looks at and right-clicks a block with the main hand (opens containers). */
    public static InteractionResult useOnBlock(Minecraft mc, LocalPlayer p, BlockPos pos) {
        Direction face = faceTowards(p, pos);
        Vec3 hit = new Vec3(pos.getX() + 0.5 + face.getStepX() * 0.5,
                pos.getY() + 0.5 + face.getStepY() * 0.5,
                pos.getZ() + 0.5 + face.getStepZ() * 0.5);
        lookAt(p, hit);
        return mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, pos, false));
    }

    /** Looks at and attacks an entity with the main hand. */
    public static void attack(Minecraft mc, LocalPlayer p, Entity target) {
        lookAt(p, target.getBoundingBox().getCenter());
        mc.gameMode.attack(p, target);
        p.swing(InteractionHand.MAIN_HAND);
    }

    /** True while a non-inventory menu (chest, furnace, ...) is open. */
    public static boolean containerOpen(LocalPlayer p) {
        return p != null && p.containerMenu != p.inventoryMenu;
    }

    /** Closes any open container menu (tells the server) and clears the screen. */
    public static void closeContainer(LocalPlayer p) {
        if (containerOpen(p)) {
            p.closeContainer();
        }
    }

    /** Releases use/attack keys and stops using an item. */
    public static void releaseKeys(Minecraft mc) {
        mc.options.keyUse.setDown(false);
        mc.options.keyAttack.setDown(false);
        LocalPlayer p = mc.player;
        if (p != null && p.isUsingItem() && mc.gameMode != null) {
            mc.gameMode.releaseUsingItem(p);
        }
    }
}
