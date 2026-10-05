package io.github.krekerdm.baritonebots.mod.mixin;

import io.github.krekerdm.baritonebots.mod.behaviour.ProtectionGuard;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Protection guard (SPEC §4.3, §5.7g): refuses to start or continue breaking protected blocks and to place blocks inside
 * protection zones ({@link ProtectionGuard}).
 * {@code require = 0} like every mixin here: a signature change disables the guard instead of crashing the client.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
    @Inject(method = "startDestroyBlock", at = @At("HEAD"), cancellable = true, require = 0)
    private void baritonebots$guardStart(BlockPos pos, Direction face, CallbackInfoReturnable<Boolean> cir) {
        if (ProtectionGuard.refuses(pos)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "continueDestroyBlock", at = @At("HEAD"), cancellable = true, require = 0)
    private void baritonebots$guardContinue(BlockPos pos, Direction face, CallbackInfoReturnable<Boolean> cir) {
        if (ProtectionGuard.refuses(pos)) {
            MultiPlayerGameMode self = (MultiPlayerGameMode) (Object) this;
            if (self.isDestroying()) {
                self.stopDestroyBlock(); // a break that started before the zone/config arrived
            }
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true, require = 0)
    private void baritonebots$guardPlace(LocalPlayer player, InteractionHand hand, BlockHitResult hit,
                                         CallbackInfoReturnable<InteractionResult> cir) {
        if (ProtectionGuard.refusesUse(player, hand, hit)) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }
}
