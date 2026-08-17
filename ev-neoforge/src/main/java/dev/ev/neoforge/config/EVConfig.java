package dev.ev.neoforge.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Immutable, validated configuration for all EV subsystems.
 * <p>
 * Defaults provided via {@link #defaults()} are sane out-of-the-box settings for EV.
 * Range validation and clamping is enforced by {@link #validated()}.
 * <p>
 * Note: Fields {@code coherenceMaxPositionalDeltaBlocks} and {@code coherenceMaxAngularDeltaRadians}
 * are reserved for Wave 2 temporal coherence (ticket 25-render-temporal-reprojection-opt) and
 * are currently unused by Wave 1 MVP code.
 */
public record EVConfig(
        int maxRenderDistanceBlocks,
        long vramBudgetBytes,
        float screenSpaceErrorThresholdPx,
        float coherenceMaxPositionalDeltaBlocks,
        float coherenceMaxAngularDeltaRadians,
        int workerThreadCount,
        boolean enableDebugOverlay
) {
    private static final Logger LOGGER = LoggerFactory.getLogger(EVConfig.class);

    // Validation boundaries and rationale:
    // MIN_RENDER_DISTANCE_BLOCKS: 128 (8 chunks). Anything below is handled natively by Minecraft vanilla rendering.
    public static final int MIN_RENDER_DISTANCE_BLOCKS = 128;
    // MAX_RENDER_DISTANCE_BLOCKS: 65536 (4096 chunks). Prevents integer overflow and absurd memory allocation.
    public static final int MAX_RENDER_DISTANCE_BLOCKS = 65536;

    // MIN_VRAM_BUDGET_BYTES: 0 (auto-detect fallback). Maximum: 64 GB.
    public static final long MIN_VRAM_BUDGET_BYTES = 0L;
    public static final long MAX_VRAM_BUDGET_BYTES = 64L * 1024L * 1024L * 1024L;

    // MIN_SSE_PX: 0.1f (sub-pixel precision). MAX_SSE_PX: 50.0f (coarse LOD).
    public static final float MIN_SSE_PX = 0.1f;
    public static final float MAX_SSE_PX = 50.0f;

    // MIN_WORKER_THREADS: 1 (single worker thread minimum). MAX: available processors.
    public static final int MIN_WORKER_THREADS = 1;

    /**
     * Creates default configuration instance.
     *
     * @return default EVConfig
     */
    public static EVConfig defaults() {
        return new EVConfig(
                4096,
                0L,
                1.5f,
                8.0f,
                (float) Math.toRadians(15),
                Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
                false
        );
    }

    /**
     * Validates field ranges and returns a corrected copy with any out-of-range values
     * clamped to safe bounds, logging a warning for each correction made.
     *
     * @return validated and clamped EVConfig instance
     */
    public EVConfig validated() {
        int clampedMaxRenderDistanceBlocks = clampInt("maxRenderDistanceBlocks", maxRenderDistanceBlocks,
                MIN_RENDER_DISTANCE_BLOCKS, MAX_RENDER_DISTANCE_BLOCKS);

        long clampedVramBudgetBytes = clampLong("vramBudgetBytes", vramBudgetBytes,
                MIN_VRAM_BUDGET_BYTES, MAX_VRAM_BUDGET_BYTES);

        float clampedSse = clampFloat("screenSpaceErrorThresholdPx", screenSpaceErrorThresholdPx,
                MIN_SSE_PX, MAX_SSE_PX);

        float clampedPosDelta = clampFloat("coherenceMaxPositionalDeltaBlocks", coherenceMaxPositionalDeltaBlocks,
                0.01f, 1000.0f);

        float clampedAngularDelta = clampFloat("coherenceMaxAngularDeltaRadians", coherenceMaxAngularDeltaRadians,
                0.001f, (float) Math.PI);

        int maxThreads = Math.max(1, Runtime.getRuntime().availableProcessors());
        int clampedWorkerThreads = clampInt("workerThreadCount", workerThreadCount,
                MIN_WORKER_THREADS, maxThreads);

        return new EVConfig(
                clampedMaxRenderDistanceBlocks,
                clampedVramBudgetBytes,
                clampedSse,
                clampedPosDelta,
                clampedAngularDelta,
                clampedWorkerThreads,
                enableDebugOverlay
        );
    }

    private static int clampInt(String name, int val, int min, int max) {
        if (val < min) {
            LOGGER.warn("EVConfig option '{}' value {} is below min {}; clamping to min", name, val, min);
            return min;
        }
        if (val > max) {
            LOGGER.warn("EVConfig option '{}' value {} exceeds max {}; clamping to max", name, val, max);
            return max;
        }
        return val;
    }

    private static long clampLong(String name, long val, long min, long max) {
        if (val < min) {
            LOGGER.warn("EVConfig option '{}' value {} is below min {}; clamping to min", name, val, min);
            return min;
        }
        if (val > max) {
            LOGGER.warn("EVConfig option '{}' value {} exceeds max {}; clamping to max", name, val, max);
            return max;
        }
        return val;
    }

    private static float clampFloat(String name, float val, float min, float max) {
        if (Float.isNaN(val) || val < min) {
            LOGGER.warn("EVConfig option '{}' value {} is invalid/below min {}; clamping to min", name, val, min);
            return min;
        }
        if (val > max) {
            LOGGER.warn("EVConfig option '{}' value {} exceeds max {}; clamping to max", name, val, max);
            return max;
        }
        return val;
    }
}
