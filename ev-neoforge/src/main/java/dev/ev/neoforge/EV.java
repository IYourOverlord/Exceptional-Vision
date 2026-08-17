package dev.ev.neoforge;

import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main NeoForge entrypoint for the Exceptional Vision (EV) far-render mod.
 * Registered via {@code @Mod("ev")}.
 * <p>
 * Client-only lifecycle events (world load/unload, level render stage) are registered on
 * {@link NeoForge#EVENT_BUS} only when running on physical client ({@code FMLEnvironment.dist == Dist.CLIENT}),
 * preventing crashes or accidental classloading on dedicated servers.
 */
@Mod(EV.MODID)
public final class EV {

    public static final String MODID = "ev";
    private static final Logger LOGGER = LoggerFactory.getLogger(EV.class);

    private EVInstance instance;

    public EV(IEventBus modBus) {
        LOGGER.info("EV mod initializing (Loader: NeoForge 1.21.1)");

        modBus.addListener(this::onClientSetup);

        // Register client-side game lifecycle listeners on NeoForge.EVENT_BUS only if on physical client.
        if (FMLEnvironment.dist == Dist.CLIENT) {
            NeoForge.EVENT_BUS.addListener(this::onLevelLoad);
            NeoForge.EVENT_BUS.addListener(this::onLevelUnload);
            NeoForge.EVENT_BUS.addListener(this::onRenderLevelStage);
        }
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        // FMLClientSetupEvent runs early before OpenGL context and world levels are ready.
        // EVInstance.bootstrap() is deliberately deferred to onLevelLoad (or first render pass)
        // when a live GL context is guaranteed.
        LOGGER.info("EV client setup completed");
    }

    private void onLevelLoad(LevelEvent.Load event) {
        // Act ONLY on physical client and client-side levels (event.getLevel() instanceof ClientLevel)
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

        // Target stage: AFTER_SOLID_BLOCKS for rendering far LOD geometry over solid terrain
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) {
            instance.renderFarLod(event);
        }
    }
}
