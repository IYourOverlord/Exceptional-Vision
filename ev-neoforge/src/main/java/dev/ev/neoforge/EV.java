package dev.ev.neoforge;

import dev.ev.api.SectionPos;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main NeoForge entrypoint for the Exceptional Vision (EV) far-render mod.
 * Registered via {@code @Mod("ev")}.
 * <p>
 * Client-only lifecycle events (world load/unload, level render stage, block changes)
 * are registered on {@link NeoForge#EVENT_BUS} only when running on physical client
 * ({@code FMLEnvironment.dist == Dist.CLIENT}), preventing crashes or accidental
 * classloading on dedicated servers.
 */
@Mod(EV.MODID)
public final class EV {

    public static final String MODID = "ev";
    private static final Logger LOGGER = LoggerFactory.getLogger(EV.class);

    private EVInstance instance;

    public EV(IEventBus modBus, net.neoforged.fml.ModContainer container) {
        LOGGER.info("EV mod initializing (Loader: NeoForge 1.21.1)");

        dev.ev.neoforge.config.EVConfigLoader.register(container);

        modBus.addListener(this::onClientSetup);

        // Register client-side game lifecycle listeners on NeoForge.EVENT_BUS only if on physical client.
        if (FMLEnvironment.dist == Dist.CLIENT) {
            NeoForge.EVENT_BUS.addListener(this::onLevelLoad);
            NeoForge.EVENT_BUS.addListener(this::onLevelUnload);
            NeoForge.EVENT_BUS.addListener(this::onRenderLevelStage);
            NeoForge.EVENT_BUS.addListener(this::onRegisterClientCommands);
            NeoForge.EVENT_BUS.addListener(this::onBlockBreak);
            NeoForge.EVENT_BUS.addListener(this::onBlockPlace);
        }
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        LOGGER.info("EV client setup completed");
    }

    private void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        dev.ev.neoforge.command.EVCommands.register(event.getDispatcher(), () -> instance);
        LOGGER.info("EV client commands registered");
    }

    private void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ClientLevel) {
            LOGGER.info("Client level loaded, initializing EVInstance");
            if (instance != null) {
                instance.close();
            }
            try {
                instance = EVInstance.bootstrap();
            } catch (Exception e) {
                LOGGER.error("Failed to bootstrap EVInstance on level load", e);
                instance = null;
            }
        }
    }

    private void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ClientLevel) {
            LOGGER.info("Client level unloaded, shutting down EVInstance");
            if (instance != null) {
                instance.close();
                instance = null;
            }
        }
    }

    private void onRenderLevelStage(RenderLevelStageEvent event) {
        if (instance == null) {
            return;
        }
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) {
            instance.renderFarLod(event);
        }
    }

    /**
     * Marks the containing EV section dirty when a player breaks a block.
     * DirtySectionTracker is not thread-safe, but BlockEvent fires on the main thread.
     */
    private void onBlockBreak(BlockEvent.BreakEvent event) {
        markBlockDirty(event.getPos());
    }

    /**
     * Marks the containing EV section dirty when an entity places a block.
     */
    private void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        markBlockDirty(event.getPos());
    }

    private void markBlockDirty(BlockPos pos) {
        if (instance == null) return;
        // LOD level 0 section containing this block coordinate
        SectionPos section = SectionPos.fromBlockCoord(0, pos.getX(), pos.getY(), pos.getZ());
        instance.dirtyTracker().markSectionDirty(section);
    }
}
