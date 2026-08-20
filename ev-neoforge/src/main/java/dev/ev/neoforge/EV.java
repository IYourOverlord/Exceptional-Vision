package dev.ev.neoforge;

import dev.ev.api.SectionPos;
import dev.ev.api.storage.WorldSectionHandle;
import dev.ev.neoforge.adapter.BlockPalette;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
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
            NeoForge.EVENT_BUS.addListener(this::onChunkLoad);
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

    /**
     * Populates and marks dirty all EV level-0 sections overlapping a newly-loaded
     * chunk column.
     * <p>
     * Two gaps this closes, both required before any geometry can render on a fresh
     * world (neither is fixable by the other alone):
     * <ol>
     *   <li>Sections only ever entered the mesh scheduling pipeline via player block
     *       edits ({@link #onBlockBreak}/{@link #onBlockPlace}) — {@link
     *       dev.ev.render.dirty.DirtySectionTracker} would stay empty forever on an
     *       unedited world.</li>
     *   <li>Even once scheduled, {@link dev.ev.storage.cache.InMemorySectionLoader}
     *       only ever creates empty (all-air) handles — nothing in production code
     *       populated them with real block data, so {@code MeshWorkerPool} silently
     *       discarded every task via its {@code handle.isEmpty()} check. There is no
     *       full block-level voxelizer elsewhere in the codebase for level-0 sections
     *       (only {@code CoarseSectionGenerator}, a heightmap-based approximation
     *       reserved by {@link dev.ev.render.scheduling.SectionGenerationPolicy} for
     *       LOD level ≥ 2) — {@link #voxelizeSection} is that missing piece.</li>
     * </ol>
     * {@code ChunkEvent.Load} fires client-side both for newly generated chunks and for
     * chunks streamed to the client from the server (including pregenerated terrain),
     * so this covers cold start, teleport-into-unloaded-area, and normal exploration.
     * <p>
     * A level-0 section is 32 blocks wide (2x2 chunk columns); voxelizing on every
     * overlapping chunk load is intentionally redundant — up to 4x per section — rather
     * than tracking "have all 4 columns loaded yet". Reading a block position in a
     * still-unloaded neighbor column returns air (Minecraft does not throw for this),
     * so an early pass may under-voxelize a section; the next sibling chunk's load event
     * re-voxelizes the same section and self-corrects. This is a deliberate MVP
     * trade-off (extra CPU work on the main thread during bulk chunk loading, e.g. a
     * Chunky pregeneration burst) in exchange for zero extra state — worth revisiting
     * once P0-profiling-checkpoint.md has real numbers on chunk-load-time cost.
     */
    private void onChunkLoad(ChunkEvent.Load event) {
        if (instance == null) {
            return;
        }
        if (!(event.getLevel() instanceof ClientLevel level)) {
            return;
        }

        ChunkPos chunkPos = event.getChunk().getPos();
        int minBlockX = chunkPos.getMinBlockX();
        int minBlockZ = chunkPos.getMinBlockZ();
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();
        int sectionSize = 32; // level-0 section size, see SectionPos.sizeInBlocks()

        for (int blockY = minY; blockY < maxY; blockY += sectionSize) {
            SectionPos section = SectionPos.fromBlockCoord(0, minBlockX, blockY, minBlockZ);
            voxelizeSection(level, section);
            instance.dirtyTracker().markSectionDirty(section);
        }
    }

    /**
     * Reads real block data for one level-0 EV section directly from the client level
     * and writes it into the section's {@link WorldSectionHandle} via {@link
     * BlockPalette}. Must only be called from the main/client thread (matches the
     * documented thread-affinity constraint on {@code MinecraftHeightmapSource}, which
     * applies equally here — {@link ClientLevel} access is not thread-safe) — this is
     * why voxelization happens here, in the {@code ChunkEvent.Load} handler, rather than
     * being pushed onto the background {@code ev-mesh-worker} threads.
     * <p>
     * {@code onlyIfExists=false} on the {@code acquire} call is deliberate: {@link
     * dev.ev.storage.cache.InMemorySectionLoader#exists} always returns {@code false}
     * (no disk persistence in MVP), so an {@code onlyIfExists=true} acquire here — like
     * the one {@code MeshWorkerPool} uses — could never create the entry in the first
     * place. This is the only call site in the codebase that creates a level-0 entry
     * from nothing; {@code MeshWorkerPool}'s subsequent {@code acquire(pos, true)} then
     * finds it already cached.
     */
    private void voxelizeSection(ClientLevel level, SectionPos section) {
        WorldSectionHandle handle = instance.sectionCache().acquire(section.encode(), false);
        if (handle == null) {
            return;
        }
        try {
            BlockPalette palette = instance.blockPalette();
            long minBlockX = section.minBlockX();
            long minBlockY = section.minBlockY();
            long minBlockZ = section.minBlockZ();
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

            for (int lz = 0; lz < 32; lz++) {
                for (int ly = 0; ly < 32; ly++) {
                    for (int lx = 0; lx < 32; lx++) {
                        cursor.setX((int) (minBlockX + lx));
                        cursor.setY((int) (minBlockY + ly));
                        cursor.setZ((int) (minBlockZ + lz));
                        var state = level.getBlockState(cursor);
                        int paletteIndex = palette.getOrAssign(state);
                        handle.setVoxel(lx, ly, lz, paletteIndex);
                    }
                }
            }
        } finally {
            handle.release();
        }
    }

    private void markBlockDirty(BlockPos pos) {
        if (instance == null) return;
        // LOD level 0 section containing this block coordinate
        SectionPos section = SectionPos.fromBlockCoord(0, pos.getX(), pos.getY(), pos.getZ());
        instance.dirtyTracker().markSectionDirty(section);
    }
}
