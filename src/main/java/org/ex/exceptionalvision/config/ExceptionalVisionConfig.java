package org.ex.exceptionalvision.config;

import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Common configuration values for the mod.
 * <p>
 * Kept intentionally minimal at this stage (project bootstrap). Further
 * options will be added alongside the corresponding subsystems as described
 * in the roadmap (disk cache location, LOD quality, palette resolution...).
 */
public final class ExceptionalVisionConfig {

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.IntValue LOD_RENDER_DISTANCE;
    public static final ModConfigSpec.BooleanValue GPU_DRIVEN_CULLING;

    static {
        final ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("rendering");

        LOD_RENDER_DISTANCE = builder
                .comment("LOD render distance, in blocks.")
                .defineInRange("lodRenderDistance", 2048, 256, 16384);

        GPU_DRIVEN_CULLING = builder
                .comment("Use GPU compute-shader culling (requires OpenGL 4.3+).",
                        "If disabled or unsupported by the GPU, the mod falls back accordingly.")
                .define("gpuDrivenCulling", true);

        builder.pop();

        SPEC = builder.build();
    }

    private ExceptionalVisionConfig() {
    }
}
