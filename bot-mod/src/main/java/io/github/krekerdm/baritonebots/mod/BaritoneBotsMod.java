package io.github.krekerdm.baritonebots.mod;

import io.github.krekerdm.baritonebots.mod.lowpower.LowPower;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;

/**
 * Client entry point. Reads the launch properties (SPEC §2); without {@code -Dbaritonebots.link} the mod stays
 * dormant apart from the low-power options.
 */
public final class BaritoneBotsMod implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        LaunchProps props = LaunchProps.fromSystem();
        LowPower.init(props);
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.options != null) {
            LowPower.onOptionsLoaded(mc.options); // options were built before this entrypoint ran
        }
        if (!props.linked()) {
            ModInfo.LOG.info("BaritoneBots: no -D{} set, staying dormant (low-power: headless={})",
                    io.github.krekerdm.baritonebots.common.Protocol.PROP_LINK, props.headless());
            return;
        }
        BotRuntime.start(props);
    }
}
