package io.github.krekerdm.baritonebots.mod.mixin;

import io.github.krekerdm.baritonebots.mod.lowpower.LowPower;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;

/** Forces low-power options right after options.txt is loaded (SPEC §4.4). */
@Mixin(Options.class)
public abstract class OptionsMixin {
    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void baritonebots$forceLowPower(Minecraft minecraft, File workingDirectory, CallbackInfo ci) {
        LowPower.onOptionsLoaded((Options) (Object) this);
    }
}
