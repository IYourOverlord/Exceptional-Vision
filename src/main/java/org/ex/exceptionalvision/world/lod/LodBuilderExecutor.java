package org.ex.exceptionalvision.world.lod;

import org.ex.exceptionalvision.ExceptionalVision;
import org.ex.exceptionalvision.world.RegionResult;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs {@link LodBuilder#buildRegion} on a dedicated thread pool, decoupled from both
 * the region I/O pool ({@code RegionImportQueue}, stage 1) and the render thread — per
 * "Даунсемплинг выполняется в пуле потоков, не блокирует I/O и рендер" in
 * {@code 08_roadmap_milestones.md}.
 * <p>
 * Typical wiring: {@code new RegionImportQueue(ioThreads, lodBuilderExecutor::submit)} —
 * stage 1's I/O pool hands each {@link RegionResult} straight to this pool as soon as
 * it's read, and {@code onBuilt} (e.g. {@code LodCacheWriter::writeFull} for a first
 * full import) picks it up on yet another thread once downsampling finishes.
 */
public final class LodBuilderExecutor implements AutoCloseable {

    private final ExecutorService pool;
    private final LodBuilder builder;
    private final Consumer<LodBuildResult> onBuilt;

    public LodBuilderExecutor(int threads, MaterialPalette palette, Consumer<LodBuildResult> onBuilt) {
        this.builder = new LodBuilder(palette);
        this.onBuilt = onBuilt;
        this.pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "exceptional-vision-lod-builder");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Submits a region's raw column data (from {@code RegionImportQueue}) for downsampling. */
    public void submit(RegionResult region) {
        pool.submit(() -> {
            try {
                onBuilt.accept(builder.buildRegion(region));
            } catch (RuntimeException e) {
                ExceptionalVision.LOGGER.error("LOD build failed for region {}", region.coordinate(), e);
            }
        });
    }

    @Override
    public void close() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
