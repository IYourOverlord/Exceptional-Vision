package dev.ev.neoforge.config;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges EVConfig to NeoForge's TOML-backed config system via {@link ModConfigSpec}.
 * <p>
 * Registered during mod initialization using {@link ModContainer#registerConfig(ModConfig.Type, ModConfigSpec)}.
 */
public final class EVConfigLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger(EVConfigLoader.class);

    private static final ModConfigSpec SPEC;
    private static final ModConfigSpec.IntValue MAX_RENDER_DISTANCE_BLOCKS;
    private static final ModConfigSpec.LongValue VRAM_BUDGET_BYTES;
    private static final ModConfigSpec.DoubleValue SCREEN_SPACE_ERROR_THRESHOLD_PX;
    private static final ModConfigSpec.DoubleValue COHERENCE_MAX_POSITIONAL_DELTA_BLOCKS;
    private static final ModConfigSpec.DoubleValue COHERENCE_MAX_ANGULAR_DELTA_RADIANS;
    private static final ModConfigSpec.IntValue WORKER_THREAD_COUNT;
    private static final ModConfigSpec.BooleanValue ENABLE_DEBUG_OVERLAY;

    private static volatile EVConfig currentSnapshot = EVConfig.defaults();

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("Exceptional Vision (EV) Far-LOD Rendering Configuration").push("rendering");

        MAX_RENDER_DISTANCE_BLOCKS = builder
                .comment("Maximum render distance for LOD geometry in block units.")
                .defineInRange("maxRenderDistanceBlocks", 4096, 128, 65536);

        VRAM_BUDGET_BYTES = builder
                .comment("Target VRAM budget in bytes for EV LOD buffers. 0 = auto-detect via GL extensions (2GB fallback).")
                .defineInRange("vramBudgetBytes", 0L, 0L, 64L * 1024L * 1024L * 1024L);

        SCREEN_SPACE_ERROR_THRESHOLD_PX = builder
                .comment("Screen space error threshold in pixels for LOD geometric simplification.")
                .defineInRange("screenSpaceErrorThresholdPx", 1.5, 0.1, 50.0);

        COHERENCE_MAX_POSITIONAL_DELTA_BLOCKS = builder
                .comment("Reserved for Wave 2 Temporal Reprojection: maximum camera positional movement delta.")
                .defineInRange("coherenceMaxPositionalDeltaBlocks", 8.0, 0.01, 1000.0);

        COHERENCE_MAX_ANGULAR_DELTA_RADIANS = builder
                .comment("Reserved for Wave 2 Temporal Reprojection: maximum camera angular movement delta.")
                .defineInRange("coherenceMaxAngularDeltaRadians", Math.toRadians(15), 0.001, Math.PI);

        WORKER_THREAD_COUNT = builder
                .comment("Number of background worker threads dedicated to LOD meshing tasks.")
                .defineInRange("workerThreadCount", Math.max(2, Runtime.getRuntime().availableProcessors() / 2), 1, 128);

        ENABLE_DEBUG_OVERLAY = builder
                .comment("Enables debug rendering overlays and stats.")
                .define("enableDebugOverlay", false);

        builder.pop();

        SPEC = builder.build();
    }

    private EVConfigLoader() {
    }

    /**
     * Registers the NeoForge client configuration spec with the mod container.
     *
     * @param container mod container instance provided in mod constructor
     */
    public static void register(ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, SPEC);
        LOGGER.info("Registered EV client configuration spec with NeoForge");
    }

    /**
     * Returns the currently active, validated EVConfig snapshot.
     *
     * @return current validated EVConfig instance
     */
    public static EVConfig current() {
        return currentSnapshot;
    }

    /**
     * Internal/test method to refresh snapshot values from ModConfigSpec.
     */
    public static void reloadFromSpec() {
        try {
            EVConfig rawConfig = new EVConfig(
                    MAX_RENDER_DISTANCE_BLOCKS.get(),
                    VRAM_BUDGET_BYTES.get(),
                    SCREEN_SPACE_ERROR_THRESHOLD_PX.get().floatValue(),
                    COHERENCE_MAX_POSITIONAL_DELTA_BLOCKS.get().floatValue(),
                    COHERENCE_MAX_ANGULAR_DELTA_RADIANS.get().floatValue(),
                    WORKER_THREAD_COUNT.get(),
                    ENABLE_DEBUG_OVERLAY.get()
            );
            currentSnapshot = rawConfig.validated();
        } catch (Exception e) {
            LOGGER.warn("Config spec values not yet loaded; using defaults", e);
            currentSnapshot = EVConfig.defaults().validated();
        }
    }
}
