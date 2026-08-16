package dev.ev.api.gpu;

/** Opaque handle to a GPU texture. Lifecycle owned by whoever created it via RenderBackend. */
public interface GpuTexture {
    TextureDesc descriptor();
    void free();
}
