package io.github.krekerdm.baritonebots.mod.mixin;

import io.github.krekerdm.baritonebots.mod.lowpower.LowPower;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Silences the sound engine when {@code client.muteSounds} is set (SPEC §4.4). */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, require = 0)
    private void baritonebots$skipTick(boolean paused, CallbackInfo ci) {
        if (LowPower.shouldMute()) {
            ci.cancel();
        }
    }

    @Inject(method = "play", at = @At("HEAD"), cancellable = true, require = 0)
    private void baritonebots$skipPlay(SoundInstance sound, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
        if (LowPower.shouldMute()) {
            cir.setReturnValue(SoundEngine.PlayResult.NOT_STARTED);
        }
    }

    @Inject(method = "playDelayed", at = @At("HEAD"), cancellable = true, require = 0)
    private void baritonebots$skipPlayDelayed(SoundInstance sound, int delay, CallbackInfo ci) {
        if (LowPower.shouldMute()) {
            ci.cancel();
        }
    }
}
