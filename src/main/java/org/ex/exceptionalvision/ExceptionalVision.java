package org.ex.exceptionalvision;

import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import org.ex.exceptionalvision.client.ExceptionalVisionClient;
import org.ex.exceptionalvision.config.ExceptionalVisionConfig;
import org.slf4j.Logger;

/**
 * Entry point of the Exceptional Vision mod.
 * <p>
 * Independent implementation of GPU-driven LOD terrain rendering for far view
 * distances, inspired by the concept popularized by mods such as Distant
 * Horizons and Voxy. No code shared with either project.
 */
@Mod(ExceptionalVision.MODID)
public final class ExceptionalVision {

    public static final String MODID = "exceptional_vision";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** Recorded into {@code index.json} by the disk cache (see {@code 06_disk_cache_format.md}); read from mod metadata rather than hardcoded so it can't drift from {@code neoforge.mods.toml}. */
    public static String MOD_VERSION = "unknown";

    public ExceptionalVision(IEventBus modEventBus, ModContainer modContainer) {
        MOD_VERSION = modContainer.getModInfo().getVersion().toString();

        modEventBus.addListener(this::onCommonSetup);
        modEventBus.addListener(this::onClientSetup);

        modContainer.registerConfig(ModConfig.Type.COMMON, ExceptionalVisionConfig.SPEC);



        // Stage 4's render/pipeline wiring touches client-only classes (ClientLevel,
        // Minecraft, ...) - keeping it behind this guard, in a separate class that's
        // never referenced outside the guarded branch, means it's never classloaded on
        // a dedicated server.
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ExceptionalVisionClient.init(modEventBus);
        }

        LOGGER.info("Exceptional Vision initializing (modid={}, version={})", MODID, MOD_VERSION);
    }

    private void onCommonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("Exceptional Vision common setup complete");
    }

    private void onClientSetup(final FMLClientSetupEvent event) {
        LOGGER.info("Exceptional Vision client setup complete");
    }
}
