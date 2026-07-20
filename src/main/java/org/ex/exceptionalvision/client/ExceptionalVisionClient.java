package org.ex.exceptionalvision.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.ex.exceptionalvision.ExceptionalVision;
import org.ex.exceptionalvision.cache.CacheCompactor;
import org.ex.exceptionalvision.cache.LodCacheData;
import org.ex.exceptionalvision.config.ExceptionalVisionConfig;
import org.ex.exceptionalvision.pipeline.LodPipeline;
import org.ex.exceptionalvision.render.LodGpuPipeline;
import org.ex.exceptionalvision.render.LodRenderManager;
import org.ex.exceptionalvision.world.lod.LodBuildResult;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The missing link flagged throughout {@code PROGRESS.md}'s stage-4 section: nothing
 * previously called {@link LodGpuPipeline#init()}, registered {@link LodRenderManager},
 * or created a {@link LodPipeline} for the dimension the player is actually in. This
 * class does exactly those three things and nothing else algorithmic - stages 1-4
 * themselves are unchanged.
 * <p>
 * <b>Only ever touched from {@code Dist.CLIENT}</b> - {@link ExceptionalVision}'s
 * constructor only calls {@link #init} inside a {@code FMLEnvironment.dist == CLIENT}
 * guard, specifically so this class (which references {@code ClientLevel}, {@code
 * Minecraft}, etc.) is never classloaded on a dedicated server.
 * <p>
 * <b>Singleplayer/LAN only.</b> Stage 1 reads region files directly off disk; a client
 * connected to a remote server has no local copy of them, so {@link #startPipeline}
 * is a deliberate no-op whenever {@link Minecraft#getSingleplayerServer()} is
 * {@code null}. The GPU pipeline still initializes in that case - it simply never
 * receives a non-empty cache to upload, so {@link LodGpuPipeline#renderLodFrame} stays
 * a no-op (see its {@code nodeCount() == 0} guard).
 * <p>
 * <b>Not verified against a real NeoForge jar in this session</b> (same caveat as the
 * rest of stage 4, see {@code PROGRESS.md}). The two points most worth double-checking
 * on a real build first: (1) that {@link LevelEvent.Load}/{@link LevelEvent.Unload}
 * fire for {@link ClientLevel} instances with the timing assumed here (once per
 * dimension entry/exit, including the initial join and every subsequent portal/respawn
 * dimension change), and (2) that {@link ExceptionalVisionConfig}'s common-config
 * values are already populated by the time {@link FMLClientSetupEvent}'s
 * {@code enqueueWork} runs. Neither could be checked without a compiled game instance.
 */
public final class ExceptionalVisionClient {

    private static final LodGpuPipeline GPU_PIPELINE = new LodGpuPipeline();

    private static LodPipeline activePipeline;
    private static ResourceKey<Level> activeDimension;
    private static Path activeWorldRoot;

    /**
     * Tracks dimensions whose cache is currently being compacted in the background (see
     * {@link #runCompactionOffThread}), so a fast unload→reload of the *same* dimension
     * (e.g. stepping through a portal and immediately back) can wait the compaction out
     * instead of racing {@link LodPipeline#start} against {@link CacheCompactor#compact}
     * rewriting {@code nodes.bin}/{@code index.json} underneath it. Different dimensions
     * never share a cache directory, so this only ever blocks on same-dimension re-entry,
     * not on every dimension change.
     */
    private static final java.util.Map<String, java.util.concurrent.CountDownLatch> COMPACTIONS_IN_PROGRESS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private ExceptionalVisionClient() {
    }

    /** Called once from {@link ExceptionalVision}'s constructor, only on {@code Dist.CLIENT}. */
    public static void init(IEventBus modEventBus) {
        modEventBus.addListener(ExceptionalVisionClient::onClientSetup);
        NeoForge.EVENT_BUS.addListener(ExceptionalVisionClient::onLevelLoad);
        NeoForge.EVENT_BUS.addListener(ExceptionalVisionClient::onLevelUnload);
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        // FMLClientSetupEvent handlers run off the render thread; every GL call in
        // LodGpuPipeline.init() needs the current GL context, so this must go through
        // enqueueWork rather than running directly here.
        event.enqueueWork(() -> {
            if (!ExceptionalVisionConfig.GPU_DRIVEN_CULLING.get()) {
                ExceptionalVision.LOGGER.info(
                        "Exceptional Vision: GPU-driven culling disabled in config, LOD rendering stays off this session");
                return;
            }

            GPU_PIPELINE.init(); // safe even if unsupported - just leaves isActive() == false
            if (!GPU_PIPELINE.isActive()) {
                return;
            }

            // FIX (stage-4 gap): setLodDistanceSettings now derives the level-0 band width
            // from this value internally instead of taking it as that band width directly -
            // see LodGpuPipeline#setLodDistanceSettings and quad_cull.comp for what changed
            // and why (PROGRESS.md, stage-4 "baseLodDistance vs lodRenderDistance").
            GPU_PIPELINE.setLodDistanceSettings(ExceptionalVisionConfig.LOD_RENDER_DISTANCE.get(), 5);
            LodRenderManager renderManager = new LodRenderManager(GPU_PIPELINE);
            NeoForge.EVENT_BUS.addListener(renderManager::onRenderLevelStage);
        });
    }

    private static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ClientLevel clientLevel) {
            startPipeline(clientLevel.dimension());
        }
    }

    private static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ClientLevel clientLevel && clientLevel.dimension().equals(activeDimension)) {
            stopPipeline();
        }
    }

    private static void startPipeline(ResourceKey<Level> dimension) {
        stopPipeline(); // one dimension's region files watched at a time, matching LodPipeline's per-dimension scope

        MinecraftServer integratedServer = Minecraft.getInstance().getSingleplayerServer();
        if (integratedServer == null) {
            ExceptionalVision.LOGGER.info(
                    "Exceptional Vision: no local world files available for {} (not singleplayer/LAN), LOD stays off",
                    dimension.location());
            return;
        }

        Path worldRoot = integratedServer.getWorldPath(LevelResource.ROOT);
        Path regionDir = resolveRegionDir(worldRoot, dimension);
        String dimensionId = dimension.location().toString();

        awaitAnyInProgressCompaction(dimensionId);

        try {
            LodPipeline pipeline = new LodPipeline(
                    worldRoot,
                    regionDir,
                    dimensionId,
                    ExceptionalVision.MOD_VERSION,
                    2,
                    Math.max(1, Runtime.getRuntime().availableProcessors() - 1),
                    ExceptionalVisionClient::onRegionCached);

            activePipeline = pipeline;
            activeDimension = dimension;
            activeWorldRoot = worldRoot;
            pipeline.start();

            if (GPU_PIPELINE.isActive()) {
                LodCacheData startupCache = pipeline.cachedAtStartup();
                if (!startupCache.isEmpty()) {
                    GPU_PIPELINE.uploadCache(startupCache, pipeline.materialPalette().colorsSnapshot());
                }
            }
        } catch (IOException e) {
            ExceptionalVision.LOGGER.error("Failed to start LOD pipeline for {}", dimension.location(), e);
            activePipeline = null;
            activeDimension = null;
            activeWorldRoot = null;
        }
    }

    private static void stopPipeline() {
        if (activePipeline == null) {
            return;
        }
        Path worldRootToCompact = activeWorldRoot;
        String dimensionIdToCompact = activeDimension.location().toString();

        activePipeline.close(); // waits out in-flight region writes before we touch the cache files below
        activePipeline = null;
        activeDimension = null;
        activeWorldRoot = null;
        GPU_PIPELINE.clearCache(); // stop drawing the old dimension's geometry into the new one

        runCompactionOffThread(worldRootToCompact, dimensionIdToCompact);
    }

    /**
     * Blocks the calling thread (the render thread, via {@link #startPipeline}) until any
     * compaction already running for {@code dimensionId} finishes. A no-op — returns
     * immediately — in the overwhelmingly common case where no compaction is in flight for
     * this dimension; only actually waits on the narrow race described in
     * {@link #COMPACTIONS_IN_PROGRESS}'s javadoc. Bounded by how long
     * {@link CacheCompactor#compact} itself takes (a full read/rewrite of the dimension's
     * {@code nodes.bin}/{@code quads.bin}) — no different in kind from the disk I/O
     * {@link LodPipeline#start} is about to do anyway on this same thread.
     */
    private static void awaitAnyInProgressCompaction(String dimensionId) {
        java.util.concurrent.CountDownLatch inProgress = COMPACTIONS_IN_PROGRESS.get(dimensionId);
        if (inProgress == null) {
            return;
        }
        try {
            inProgress.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * {@link CacheCompactor#compact} does full reads/rewrites of {@code nodes.bin}/
     * {@code quads.bin} — fine for "once per dimension unload", but it must not run on
     * whichever thread {@link #stopPipeline} was called from (the render thread, via
     * {@link #onLevelUnload}): blocking that thread on disk I/O for a large, long-lived
     * world would show up as a hitch/freeze right at the moment the player leaves a
     * dimension. {@link LodPipeline#close()} has already been awaited by the time this
     * runs, so there's no writer left to race against — except a fast unload→reload of
     * this same dimension, which {@link #awaitAnyInProgressCompaction} guards against by
     * registering/clearing this run's latch in {@link #COMPACTIONS_IN_PROGRESS}.
     */
    private static void runCompactionOffThread(Path worldRoot, String dimensionId) {
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        COMPACTIONS_IN_PROGRESS.put(dimensionId, latch);

        Thread compactionThread = new Thread(() -> {
            try {
                CacheCompactor.Result result = new CacheCompactor().compact(worldRoot, dimensionId);
                if (result.totalBytesReclaimed() > 0) {
                    ExceptionalVision.LOGGER.info("Exceptional Vision: compacted LOD cache for {} ({} bytes reclaimed)",
                            dimensionId, result.totalBytesReclaimed());
                }
            } catch (IOException e) {
                // Non-fatal: worst case the cache stays a bit larger on disk than necessary
                // until the next successful compaction, exactly as if this run never happened.
                ExceptionalVision.LOGGER.warn("Exceptional Vision: failed to compact LOD cache for {}: {}",
                        dimensionId, e.toString());
            } finally {
                COMPACTIONS_IN_PROGRESS.remove(dimensionId, latch);
                latch.countDown();
            }
        }, "exceptional-vision-cache-compactor");
        compactionThread.setDaemon(true);
        compactionThread.start();
    }

    /**
     * Fires on an {@code LodBuilderExecutor} worker thread (see {@link LodPipeline}'s
     * javadoc), never the render thread - every GL call must be deferred via
     * {@link Minecraft#execute}.
     * <p>
     * Stage-4 note: this reloads and re-uploads the <em>entire</em> cache for every
     * single region that finishes, because {@link LodGpuPipeline#uploadCache} has no
     * incremental path yet (see its class javadoc). Acceptable for now - a background
     * Chunky import will just re-upload a growing SSBO a lot - but this exact cost is
     * what stage 5's persistent mapped buffers / incremental upload are meant to
     * replace, not something to half-fix here (e.g. via debouncing) and re-break later.
     */
    private static void onRegionCached(LodBuildResult result) {
        LodPipeline pipeline = activePipeline;
        if (pipeline == null || !GPU_PIPELINE.isActive()) {
            return;
        }
        Minecraft.getInstance().execute(() -> {
            if (pipeline != activePipeline) {
                return; // world/dimension changed while this was queued - stale, drop it
            }
            LodCacheData fresh = pipeline.reloadCache();
            GPU_PIPELINE.uploadCache(fresh, pipeline.materialPalette().colorsSnapshot());
        });
    }

    /**
     * Manually reimplements vanilla's stable, long-standing save-folder convention
     * (overworld region files live directly under the world root, the nether's under
     * {@code DIM-1}, the end's under {@code DIM1}, and any other dimension's under
     * {@code dimensions/<namespace>/<path>}) rather than reaching for
     * {@code LevelStorageSource.LevelStorageAccess#getDimensionPath}, which
     * {@code MinecraftServer} does not expose publicly. This is documented Minecraft
     * save-format knowledge, not code from any other project - see
     * {@code 01_legal_constraints.md}. Worth double-checking against a real save
     * directory on first real run, per this class's javadoc.
     */
    private static Path resolveRegionDir(Path worldRoot, ResourceKey<Level> dimension) {
        Path dimensionRoot;
        if (dimension.equals(Level.OVERWORLD)) {
            dimensionRoot = worldRoot;
        } else if (dimension.equals(Level.NETHER)) {
            dimensionRoot = worldRoot.resolve("DIM-1");
        } else if (dimension.equals(Level.END)) {
            dimensionRoot = worldRoot.resolve("DIM1");
        } else {
            ResourceLocation id = dimension.location();
            dimensionRoot = worldRoot.resolve("dimensions").resolve(id.getNamespace()).resolve(id.getPath());
        }
        return dimensionRoot.resolve("region");
    }
}