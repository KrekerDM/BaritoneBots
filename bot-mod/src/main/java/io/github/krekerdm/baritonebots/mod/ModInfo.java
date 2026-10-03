package io.github.krekerdm.baritonebots.mod;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Static identity of the mod: id, logger and the versions reported in {@code hello}. */
public final class ModInfo {
    public static final String MOD_ID = "baritonebots";
    public static final Logger LOG = LoggerFactory.getLogger("BaritoneBots");

    private ModInfo() {
    }

    public static String modVersion() {
        return version(MOD_ID);
    }

    public static String mcVersion() {
        return version("minecraft");
    }

    public static String baritoneVersion() {
        return version("baritone");
    }

    private static String version(String modId) {
        return FabricLoader.getInstance().getModContainer(modId)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }
}
