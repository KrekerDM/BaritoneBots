package io.github.krekerdm.baritonebots.mod.mixin;

import io.github.krekerdm.baritonebots.mod.lowpower.LowPower;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Caps the main loop of a headless client at {@code client.maxFps} (SPEC §4.4). */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "runTick", at = @At("TAIL"), require = 0)
    private void baritonebots$capLoop(boolean advanceGameTime, CallbackInfo ci) {
        LowPower.afterFrame();
    }
}
