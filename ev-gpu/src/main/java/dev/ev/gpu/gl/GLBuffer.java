package dev.ev.gpu.gl;

import dev.ev.api.gpu.BufferUsage;
import dev.ev.api.gpu.GpuBuffer;

import org.lwjgl.opengl.GL45;

/**
 * OpenGL buffer object wrapper. For {@link BufferUsage#STAGING_UPLOAD} usage, uses
 * persistent-mapped buffers ({@code glNamedBufferStorage} with {@code
 * GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT | GL_MAP_WRITE_BIT}, then {@code
 * glMapNamedBufferRange} + {@code MemoryUtil.memAddress(...)} to obtain the raw pointer) so
 * {@link #mappedAddress()} returns a stable CPU-writable pointer for the buffer's lifetime,
 * avoiding repeated map/unmap overhead on every upload (see PERFORMANCE_MATH.md A.6 — batch
 * uploads via a persistently mapped staging buffer).
 *
 * <p>All methods on this class, like all GL calls in {@code ev-gpu}, must only be called from
 * the render thread (the thread holding the current GL context) — see {@link GLRenderBackend}'s
 * class Javadoc for the full requirement.
 */
public final class GLBuffer implements GpuBuffer {

    private final int handle;
    private final long sizeBytes;
    private final BufferUsage usage;

    /**
     * Persistent-mapped CPU pointer, valid only for {@link BufferUsage#STAGING_UPLOAD} and
     * {@link BufferUsage#STAGING_DOWNLOAD} buffers. {@code 0} for all other usages, meaning
     * "not mapped".
     */
    private final long mappedAddress;

    private boolean freed;

    /**
     * @param handle raw GL buffer object name, obtained via {@code glCreateBuffers}
     * @param sizeBytes buffer size in bytes, as requested at creation time
     * @param usage the {@link BufferUsage} this buffer was created with
     * @param mappedAddress persistent-mapped pointer address for STAGING_UPLOAD/
     *        STAGING_DOWNLOAD buffers, obtained via {@code glMapNamedBufferRange} at creation
     *        time; {@code 0} for other usages (STATIC_DRAW/DYNAMIC_DRAW/STORAGE are never
     *        mapped by this class)
     */
    public GLBuffer(int handle, long sizeBytes, BufferUsage usage, long mappedAddress) {
        this.handle = handle;
        this.sizeBytes = sizeBytes;
        this.usage = usage;
        this.mappedAddress = mappedAddress;
    }

    /** Raw GL buffer object name, for use by other {@code ev-gpu} classes (e.g. command recording). */
    public int handle() {
        return handle;
    }

    @Override
    public long sizeBytes() {
        return sizeBytes;
    }

    @Override
    public BufferUsage usage() {
        return usage;
    }

    /**
     * {@inheritDoc}
     *
     * @throws UnsupportedOperationException if {@link #usage()} is not {@link
     *         BufferUsage#STAGING_UPLOAD}, per the {@link GpuBuffer} contract
     */
    @Override
    public long mappedAddress() {
        if (usage != BufferUsage.STAGING_UPLOAD) {
            throw new UnsupportedOperationException(
                "GLBuffer.mappedAddress() is only supported for STAGING_UPLOAD buffers, this buffer is " + usage);
        }
        return mappedAddress;
    }

    @Override
    public void free() {
        if (freed) {
            return;
        }
        // Explicit unmap before deletion for persistently mapped buffers: not strictly
        // required by the GL spec (deletion implicitly unmaps), but done here for clarity
        // and driver-compatibility safety, per requirement 4 of the ticket.
        if (mappedAddress != 0L) {
            GL45.glUnmapNamedBuffer(handle);
        }
        GL45.glDeleteBuffers(handle);
        freed = true;
    }
}
