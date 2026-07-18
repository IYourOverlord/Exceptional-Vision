package org.ex.exceptionalvision.render;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

import static org.lwjgl.opengl.GL46C.*;

/**
 * One-time detection of whether the current OpenGL context supports what the LOD GPU
 * pipeline needs. See "GPU Capabilities Detection" in {@code 04_gpu_pipeline.md}.
 * <p>
 * This pipeline requires <b>OpenGL 4.6 core</b>: compute shaders + SSBOs are GL 4.3+,
 * and the vertex shader relies on the core (not extension) {@code gl_DrawID} builtin,
 * which needs GLSL 4.60 / GL 4.6. Minecraft 1.21.1 itself only requires GL 3.2 core, so
 * this is a strictly higher bar than the base game.
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
    private final int glMajor;
    private final int glMinor;
    private final String unsupportedReason;

    private GpuCapabilities(boolean supported, int glMajor, int glMinor, String unsupportedReason) {
        this.supported = supported;
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
            return new GpuCapabilities(false, major, minor,
                    "OpenGL 4.6 core is required (compute shaders + core gl_DrawID); found " + major + "." + minor);
        }
        return new GpuCapabilities(true, major, minor, null);
    }

    public boolean supported() {
        return supported;
    }

    /** {@code null} when {@link #supported()} is {@code true}. */
    public String unsupportedReason() {
        return unsupportedReason;
    }

    public String glVersionString() {
        return glMajor + "." + glMinor;
    }
}
