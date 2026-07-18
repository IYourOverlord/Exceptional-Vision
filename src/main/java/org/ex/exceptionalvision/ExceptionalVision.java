package org.ex.exceptionalvision;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
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

    public ExceptionalVision(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::onCommonSetup);
        modEventBus.addListener(this::onClientSetup);

        modContainer.registerConfig(ModConfig.Type.COMMON, ExceptionalVisionConfig.SPEC);

        NeoForge.EVENT_BUS.register(this);

        LOGGER.info("Exceptional Vision initializing (modid={})", MODID);
    }

    private void onCommonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("Exceptional Vision common setup complete");
    }

    private void onClientSetup(final FMLClientSetupEvent event) {
        LOGGER.info("Exceptional Vision client setup complete");
    }
}
