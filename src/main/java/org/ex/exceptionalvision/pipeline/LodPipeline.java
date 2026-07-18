package org.ex.exceptionalvision.pipeline;

import org.ex.exceptionalvision.ExceptionalVision;
import org.ex.exceptionalvision.cache.LodCacheData;
import org.ex.exceptionalvision.cache.LodCacheLoader;
import org.ex.exceptionalvision.cache.LodCacheWriter;
import org.ex.exceptionalvision.cache.RegionCacheEntry;
import org.ex.exceptionalvision.world.RegionImportQueue;
import org.ex.exceptionalvision.world.WorldDirectoryWatcher;
import org.ex.exceptionalvision.world.lod.LodBuildResult;
import org.ex.exceptionalvision.world.lod.LodBuilderExecutor;
import org.ex.exceptionalvision.world.lod.MaterialPalette;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Wires stage 1 (region I/O) → stage 2 (LOD downsampling) → stage 3 (disk cache) into
 * one running pipeline for a single dimension. This is the piece flagged as missing in
 * {@code PROGRESS.md} ("LodCacheWriter/LodBuilder пока не связаны друг с другом
 * кодом") — everything it calls was already built and tested individually; this class
 * is deliberately thin glue, not new algorithmic logic.
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>{@link #start()} loads whatever cache already exists (fast path — most of a
 *       previously-processed world needs nothing more), scans {@code regionDir} for
 *       region files whose {@code mtimeMs}/hash no longer match the cache, and queues
 *       just those for background (re)processing.</li>
 *   <li>{@link WorldDirectoryWatcher} keeps watching {@code regionDir} for further
 *       changes (e.g. a concurrent Chunky pregeneration run) for the lifetime of the
 *       pipeline.</li>
 *   <li>Each region flows: {@link RegionImportQueue} (I/O thread) → {@link LodBuilderExecutor}
 *       (downsampling thread) → {@link #onLodBuilt} (writes to {@link LodCacheWriter}).</li>
 * </ol>
 *
 * <h2>What this does <em>not</em> do</h2>
 * {@link #cachedAtStartup()} is a one-time snapshot taken during {@link #start()} — it
 * is <em>not</em> kept in sync as regions finish (re)processing afterwards (re-mapping a
 * growing memory-mapped file on every single region write isn't practical with plain
 * Java NIO). A GPU buffer manager that wants to see newly-built regions live, not just
 * at startup, should use the {@code onRegionCached} constructor callback instead, which
 * fires with the same {@link LodBuildResult} that was just written to disk — see stage 4.
 */
public final class LodPipeline implements AutoCloseable {

    private final Path worldDir;
    private final Path regionDir;
    private final String dimensionId;
    private final String modVersion;

    private final LodCacheWriter cacheWriter = new LodCacheWriter();
    private final LodCacheLoader cacheLoader = new LodCacheLoader();
    private final MaterialPalette materialPalette = new MaterialPalette();
    private final WorldDirectoryWatcher watcher;
    private final RegionImportQueue regionImportQueue;
    private final LodBuilderExecutor lodBuilderExecutor;
    private final Consumer<LodBuildResult> onRegionCached;

    private volatile LodCacheData cachedAtStartup;

    /**
     * @param worldDir       the save's root directory (parent of {@code level.dat});
     *                       cache files live under {@code worldDir/lodcache/<dimensionId>}
     * @param regionDir      the region directory to watch/import for this specific
     *                       dimension (e.g. {@code worldDir/region} for the overworld,
     *                       {@code worldDir/DIM-1/region} for the nether) — resolving
     *                       this from a dimension key is a Minecraft-API detail left to
     *                       the caller, see {@code ExceptionalVision} for the intended
     *                       call site
     * @param dimensionId    e.g. {@code "minecraft:overworld"}
     * @param modVersion     current mod version, recorded in {@code index.json}
     * @param ioThreads      region-file I/O threads (spec recommends 2)
     * @param builderThreads LOD downsampling threads
     * @param onRegionCached invoked after a region's {@link LodBuildResult} has been
     *                       written to the disk cache — hook point for stage 4's GPU
     *                       buffer manager to pick up newly-built regions live
     */
    public LodPipeline(Path worldDir, Path regionDir, String dimensionId, String modVersion,
                        int ioThreads, int builderThreads, Consumer<LodBuildResult> onRegionCached) throws IOException {
        this.worldDir = worldDir;
        this.regionDir = regionDir;
        this.dimensionId = dimensionId;
        this.modVersion = modVersion;
        this.onRegionCached = onRegionCached;

        this.lodBuilderExecutor = new LodBuilderExecutor(builderThreads, materialPalette, this::onLodBuilt);
        this.regionImportQueue = new RegionImportQueue(ioThreads, lodBuilderExecutor::submit);
        this.watcher = new WorldDirectoryWatcher();
    }

    /** The cache as it was when {@link #start()} ran — see the class javadoc for why this isn't kept live. */
    public LodCacheData cachedAtStartup() {
        return cachedAtStartup;
    }

    /**
     * The shared {@link MaterialPalette} this pipeline's {@link LodBuilderExecutor} has
     * been assigning indices from since {@code start()}. Stage 4's GPU buffer manager
     * needs {@link MaterialPalette#colorsSnapshot()} alongside every {@link #cachedAtStartup()}
     * or {@link #reloadCache()} call, since the palette indices baked into
     * {@code NodeData}/{@code PackedQuad} are only meaningful relative to this exact
     * palette instance.
     */
    public MaterialPalette materialPalette() {
        return materialPalette;
    }

    /**
     * Re-reads the on-disk cache from scratch. Unlike {@link #cachedAtStartup()} this
     * reflects whatever has been written since, at the cost of a full re-read/mmap —
     * see the class javadoc's note on why {@code cachedAtStartup()} isn't kept live.
     * Intended for stage 4's {@code onRegionCached} hook to call (on the render thread,
     * not the builder thread that invoked the hook) when it wants a fresh snapshot to
     * re-upload, until stage 5 replaces this with incremental buffer updates.
     */
    public LodCacheData reloadCache() {
        return cacheLoader.load(worldDir, dimensionId, modVersion);
    }

    public void start() {
        cachedAtStartup = cacheLoader.load(worldDir, dimensionId, modVersion);
        ExceptionalVision.LOGGER.info("LOD pipeline starting for {}: cache has {} nodes, {} quads",
                dimensionId, cachedAtStartup.nodeCount(), cachedAtStartup.quadCount());

        try {
            watcher.watch(regionDir, this::onRegionFileChanged);
        } catch (IOException e) {
            ExceptionalVision.LOGGER.error("Failed to watch region directory {}", regionDir, e);
        }

        try {
            queueRegionsNeedingRecompute();
        } catch (IOException e) {
            ExceptionalVision.LOGGER.error("Failed to scan region directory {}", regionDir, e);
        }
    }

    private void queueRegionsNeedingRecompute() throws IOException {
        if (!Files.isDirectory(regionDir)) {
            return; // dimension not generated at all yet - nothing to do until it is
        }
        try (Stream<Path> files = Files.list(regionDir)) {
            files.filter(p -> p.toString().endsWith(".mca")).forEach(this::queueIfStale);
        }
    }

    private void queueIfStale(Path mcaFile) {
        try {
            if (cacheWriter.needsRecompute(worldDir, dimensionId, mcaFile)) {
                regionImportQueue.submitLowPriority(mcaFile);
            }
        } catch (IOException e) {
            ExceptionalVision.LOGGER.warn("Failed to check cache status for {}: {}", mcaFile, e.toString());
        }
    }

    /** Called by {@link WorldDirectoryWatcher} when a region file settles after being created/modified. */
    private void onRegionFileChanged(Path mcaFile) {
        try {
            if (cacheWriter.needsRecompute(worldDir, dimensionId, mcaFile)) {
                // Higher priority than the startup backlog: a live change is more likely to be
                // near the player (or otherwise currently relevant) than a background import.
                regionImportQueue.submit(mcaFile, 0);
            }
        } catch (IOException e) {
            ExceptionalVision.LOGGER.warn("Failed to check cache status for {}: {}", mcaFile, e.toString());
        }
    }

    /** Called by {@link LodBuilderExecutor} once a region's downsampling finishes. */
    private void onLodBuilt(LodBuildResult result) {
        try {
            Path regionFile = regionDir.resolve(result.coordinate().fileName());
            long mtimeMs = Files.getLastModifiedTime(regionFile).toMillis();
            String hash = cacheWriter.computeSourceHash(regionFile);

            Optional<RegionCacheEntry> existing = cacheWriter.findRegionEntry(
                    worldDir, dimensionId, result.coordinate().x(), result.coordinate().z());

            if (existing.isPresent() && existing.get().nodeCount() == result.nodes().size()) {
                cacheWriter.patchRegion(worldDir, dimensionId, modVersion, result.coordinate(),
                        mtimeMs, hash, existing.get(), result.nodes(), result.quads());
            } else {
                cacheWriter.appendRegion(worldDir, dimensionId, modVersion, result.coordinate(),
                        mtimeMs, hash, result.nodes(), result.quads());
            }
        } catch (IOException e) {
            ExceptionalVision.LOGGER.error("Failed to write LOD cache for region {}", result.coordinate(), e);
            return; // don't fire onRegionCached for a region that failed to persist
        }

        if (onRegionCached != null) {
            onRegionCached.accept(result);
        }
    }

    /** Queues every existing region in {@code regionDir}, regardless of cache freshness — for a manual "force reimport". */
    public void forceFullReimport() throws IOException {
        regionImportQueue.queueFullWorldImport(regionDir);
    }

    @Override
    public void close() {
        regionImportQueue.close();
        lodBuilderExecutor.close();
        try {
            watcher.close();
        } catch (IOException e) {
            ExceptionalVision.LOGGER.warn("Failed to close region directory watcher: {}", e.toString());
        }
    }
}
