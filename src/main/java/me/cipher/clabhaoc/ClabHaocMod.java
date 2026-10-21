package me.cipher.clabhaoc;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * CLab Terrain HAOC - hardware accelerated occlusion culling for terrain sections.
 *
 * <p>Stand-alone mixin mod for Minecraft 1.20.1 + Sodium 0.5.x. The core logic lives in
 * {@link me.cipher.clabhaoc.haoc.TerrainHaocManager}, which is driven by three mixins into
 * Sodium's renderer.
 */
@Environment(EnvType.CLIENT)
public class ClabHaocMod implements ClientModInitializer {
    public static final String MOD_ID = "clab_haoc";
    public static final Logger LOGGER = LogManager.getLogger("CLabTerrainHAOC");

    @Override
    public void onInitializeClient() {
        var configPath = FabricLoader.getInstance()
                .getConfigDir()
                .resolve(MOD_ID + ".json");

        ClabHaocConfig.INSTANCE.load(configPath);

        LOGGER.info("CLab Terrain HAOC initialized (enabled = {}, safeRadius = {}, maxQueriesPerFrame = {})",
                ClabHaocConfig.INSTANCE.enabled,
                ClabHaocConfig.INSTANCE.safeRadius,
                ClabHaocConfig.INSTANCE.maxQueriesPerFrame);
    }
}
