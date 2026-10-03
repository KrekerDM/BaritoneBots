package io.github.krekerdm.baritonebots.mod.mixin;

import io.github.krekerdm.baritonebots.mod.lowpower.LowPower;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Skips frame extraction and rendering on a headless client with no screen or overlay (SPEC §4.4). */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "extract", at = @At("HEAD"), cancellable = true, require = 0)
    private void baritonebots$skipExtract(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        if (LowPower.shouldSkipExtract()) {
            ci.cancel();
        }
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0)
    private void baritonebots$skipRender(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        if (LowPower.shouldSkipRender()) {
            ci.cancel();
        }
    }
}
