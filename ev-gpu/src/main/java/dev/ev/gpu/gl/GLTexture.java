package dev.ev.gpu.gl;

import dev.ev.api.gpu.GpuTexture;
import dev.ev.api.gpu.TextureDesc;

import org.lwjgl.opengl.GL45;

/**
 * OpenGL texture object wrapper (glTextureStorage2D/3D-backed, immutable storage).
 *
 * <p>All methods on this class, like all GL calls in {@code ev-gpu}, must only be called from
 * the render thread (the thread holding the current GL context) — see {@link GLRenderBackend}'s
 * class Javadoc for the full requirement.
 */
public final class GLTexture implements GpuTexture {

    private final int handle;
    private final TextureDesc descriptor;

    private boolean freed;

    /**
     * @param handle raw GL texture object name, obtained via {@code glCreateTextures}
     * @param descriptor the {@link TextureDesc} this texture was created with
     */
    public GLTexture(int handle, TextureDesc descriptor) {
        this.handle = handle;
        this.descriptor = descriptor;
    }

    /** Raw GL texture object name, for use by other {@code ev-gpu} classes (e.g. binding). */
    public int handle() {
        return handle;
    }

    @Override
    public TextureDesc descriptor() {
        return descriptor;
    }

    @Override
    public void free() {
        if (freed) {
            return;
        }
        GL45.glDeleteTextures(handle);
        freed = true;
    }
}
