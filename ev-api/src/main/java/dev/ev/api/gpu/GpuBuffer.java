package dev.ev.api.gpu;

/** Opaque handle to a GPU buffer. Lifecycle owned by whoever created it via RenderBackend. */
public interface GpuBuffer {
    long sizeBytes();
    BufferUsage usage();

    /**
     * For STAGING_UPLOAD buffers only: returns a CPU-writable memory address
     * (persistent mapped pointer) valid until free(). Throws UnsupportedOperationException
     * for other usages.
     */
    long mappedAddress();

    void free();
}
