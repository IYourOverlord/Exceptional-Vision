package org.ex.exceptionalvision.render;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

import static org.lwjgl.opengl.GL46C.*;

/**
 * One-time detection of whether the current OpenGL context supports what the LOD GPU
 * pipeline needs. See "GPU Capabilities Detection" in {@code 04_gpu_pipeline.md}.
 * <p>
 * This pipeline requires <b>OpenGL 4.6 core</b> as a hard floor: compute shaders + SSBOs
 * are GL 4.3+, and the vertex shader relies on the core (not extension) {@code
 * gl_DrawID} builtin, which needs GLSL 4.60 / GL 4.6. Minecraft 1.21.1 itself only
 * requires GL 3.2 core, so this is a strictly higher bar than the base game. There is no
 * further fallback below 4.6 - see "Known platform gap: macOS" below.
 * <p>
 * <b>Stage 5 addition:</b> on top of that floor, {@link #indirectCountSupported()}
 * distinguishes two GPU-driven paths per the roadmap ({@code 08_roadmap_milestones.md},
 * stage 5's "GpuCapabilities.detect() correctly switches between the full and fallback
 * path depending on driver capability"):
 * <ul>
 *   <li><b>Full path</b> (this flag {@code true}): {@code glMultiDrawArraysIndirectCount}
 *   is used - the compute shader's atomic draw counter lives entirely in a GPU buffer
 *   and is read by the GPU itself when issuing the multi-draw, with zero CPU readback
 *   and zero CPU/GPU sync point per frame (see {@link LodGpuPipeline}).</li>
 *   <li><b>Fallback path</b> (this flag {@code false}): the stage-4 behavior - the draw
 *   count is read back to the CPU via {@link LodGpuBuffers#readDrawCount()} and passed
 *   explicitly to {@code glMultiDrawArraysIndirect}, incurring a sync point every
 *   frame.</li>
 * </ul>
 * {@code glMultiDrawArraysIndirectCount} was promoted to core exactly at GL 4.6 (it
 * previously required the {@code ARB_indirect_parameters} extension on top of 4.5), so
 * in practice every context that clears this class's {@link #supported()} floor also
 * has the entry point - {@link #indirectCountSupported()} exists as its own explicit
 * check anyway, rather than being hardcoded to mirror {@link #supported()}, because (a)
 * it documents the two capabilities as logically separate per the roadmap wording, (b)
 * it's the correct place to add a driver denylist later if some real-world 4.6 driver
 * turns out to expose the entry point but implement it incorrectly (seen historically
 * with some indirect-draw extensions on certain driver versions), and (c) it costs
 * nothing extra to check {@code caps.OpenGL46 || caps.GL_ARB_indirect_parameters}
 * explicitly rather than assume.
 * <p>
 * <b>Known platform gap: macOS.</b> Apple's OpenGL implementation is deprecated and
 * capped at 4.1 - it never exposes compute shaders at all, on any hardware. On macOS,
 * {@link #supported()} will always be {@code false}; there is no partial-compatibility
 * fallback available within OpenGL for this (a Metal/MoltenVK backend would be a
 * different rendering pipeline entirely, well out of this stage's scope). The mod must
 * degrade to vanilla-only rendering (no LOD terrain) rather than crash - see
 * {@code LodRenderManager}.
 */
public final class GpuCapabilities {

    private final boolean supported;
    private final boolean indirectCountSupported;
    private final boolean persistentMappingSupported;
    private final int glMajor;
    private final int glMinor;
    private final String unsupportedReason;

    private GpuCapabilities(boolean supported, boolean indirectCountSupported,
                            boolean persistentMappingSupported, int glMajor, int glMinor,
                            String unsupportedReason) {
        this.supported = supported;
        this.indirectCountSupported = indirectCountSupported;
        this.persistentMappingSupported = persistentMappingSupported;
        this.glMajor = glMajor;
        this.glMinor = glMinor;
        this.unsupportedReason = unsupportedReason;
    }

    /** Must be called on the render thread with a current GL context (e.g. during a render-lifecycle event). */
    public static GpuCapabilities detect() {
        GLCapabilities caps = GL.getCapabilities();
        int major = glGetInteger(GL_MAJOR_VERSION);
        int minor = glGetInteger(GL_MINOR_VERSION);

        if (!caps.OpenGL46) {
            return new GpuCapabilities(false, false, false, major, minor,
                    "OpenGL 4.6 core is required (compute shaders + core gl_DrawID); found " + major + "." + minor);
        }

        // Core since 4.6 (formerly ARB_indirect_parameters on top of 4.5) - see class
        // javadoc for why this is still checked explicitly rather than assumed to
        // follow from caps.OpenGL46 alone.
        boolean indirectCount = caps.OpenGL46 || caps.GL_ARB_indirect_parameters;

        // GL_ARB_buffer_storage (core since 4.4, so implied by our 4.6 floor, but
        // checked explicitly for the same denylist-friendliness reason as above) is
        // what glBufferStorage/glMapBufferRange(GL_MAP_PERSISTENT_BIT) need - see
        // LodGpuBuffers' persistent-mapped quad ring buffer.
        boolean persistentMapping = caps.OpenGL46 || caps.GL_ARB_buffer_storage;

        return new GpuCapabilities(true, indirectCount, persistentMapping, major, minor, null);
    }

    public boolean supported() {
        return supported;
    }

    /** See class javadoc's "Stage 5 addition" section. Only meaningful when {@link #supported()} is {@code true}. */
    public boolean indirectCountSupported() {
        return indirectCountSupported;
    }

    /** Whether {@code GL_ARB_buffer_storage}-style persistent mapped buffers are available. Only meaningful when {@link #supported()} is {@code true}. */
    public boolean persistentMappingSupported() {
        return persistentMappingSupported;
    }

    /** {@code null} when {@link #supported()} is {@code true}. */
    public String unsupportedReason() {
        return unsupportedReason;
    }

    public String glVersionString() {
        return glMajor + "." + glMinor;
    }
}