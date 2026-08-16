package dev.ev.api.gpu;

/** Enumerates the intended access pattern for a {@link GpuBuffer}, guiding backend allocation. */
public enum BufferUsage {
    /** Written rarely from CPU, read often by GPU shaders (e.g. static geometry). */
    STATIC_DRAW,
    /** Written every frame from CPU (e.g. uniform/scene data). */
    DYNAMIC_DRAW,
    /** Read/written by compute shaders, rarely touched by CPU (e.g. node buffer, queues). */
    STORAGE,
    /** CPU-visible persistent-mapped staging buffer for streaming uploads. */
    STAGING_UPLOAD,
    /** GPU-to-CPU readback buffer (e.g. streaming request queue results). */
    STAGING_DOWNLOAD
}
