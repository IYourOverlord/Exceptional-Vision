package dev.ev.test.gpu;

import dev.ev.api.gpu.GpuTexture;
import dev.ev.api.gpu.TextureDesc;

/**
 * In-memory {@link GpuTexture} implementation backing {@link FakeRenderBackend}.
 *
 * <p>Holds only the {@link TextureDesc} it was created with — no pixel storage. Nothing
 * in the {@link dev.ev.api.gpu.RenderBackend} contract or in {@link FakeRenderBackend}'s
 * requirements (ticket 30) needs texture contents to be inspectable or mutable from
 * tests; only buffers get that treatment (see {@link FakeRenderBackend#readBufferContents}/
 * {@link FakeRenderBackend#writeBufferContents}). If a future ticket needs fake texture
 * pixel contents, extend this class then rather than speculatively adding unused storage
 * now.
 */
final class FakeGpuTexture implements GpuTexture {

    private final TextureDesc descriptor;
    private boolean freed;

    FakeGpuTexture(TextureDesc descriptor) {
        this.descriptor = descriptor;
    }

    @Override
    public TextureDesc descriptor() {
        return descriptor;
    }

    boolean isFreed() {
        return freed;
    }

    @Override
    public void free() {
        freed = true;
    }
}
