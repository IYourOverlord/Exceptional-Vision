package dev.ev.gpu.gl;

import dev.ev.api.gpu.TextureFormat;

import org.lwjgl.opengl.GL45;

/**
 * Maps {@link TextureFormat} enum values to GL internal format constants, used by {@link
 * GLRenderBackend#createTexture}. Kept in its own small pure method (no GL calls, just
 * returning {@code int} constants defined by LWJGL's {@link GL45}) so it can be unit-tested
 * without a real OpenGL context.
 *
 * <p>Kept exhaustive on purpose (throws on any unmapped value rather than silently defaulting)
 * so that a future addition to {@link TextureFormat} that forgets to update this mapping fails
 * loudly at the call site instead of silently misbehaving on the GPU.
 */
final class GLTextureFormats {

    private GLTextureFormats() {}

    static int toGlInternalFormat(TextureFormat format) {
        return switch (format) {
            case R32F -> GL45.GL_R32F;
            case RGBA8 -> GL45.GL_RGBA8;
            case RGBA16F -> GL45.GL_RGBA16F;
            case DEPTH32F -> GL45.GL_DEPTH_COMPONENT32F;
            case R32UI -> GL45.GL_R32UI;
        };
    }
}
