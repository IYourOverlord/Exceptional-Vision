package dev.ev.test.gpu;

import dev.ev.api.gpu.BufferUsage;
import dev.ev.api.gpu.GpuBuffer;
import org.lwjgl.system.MemoryUtil;

/**
 * In-memory {@link GpuBuffer} implementation backing {@link FakeRenderBackend}.
 *
 * <p>All buffer contents live in a plain {@code byte[]} ({@link #backingStore()}),
 * which {@link FakeRenderBackend}/{@link FakeCommandList} read and write directly via
 * {@code System.arraycopy}-style operations — see {@link FakeRenderBackend#readBufferContents}
 * and {@link FakeRenderBackend#writeBufferContents}.
 *
 * <p><b>{@link #mappedAddress()} scope decision (ticket 30, requirement 1):</b> for
 * {@link BufferUsage#STAGING_UPLOAD} buffers, this class allocates a real off-heap
 * native memory block via {@link MemoryUtil#nmemAlloc(long)} and returns its address,
 * rather than throwing {@code UnsupportedOperationException}. This was chosen over the
 * simpler "always throw" option because ticket 19 ({@code StagingUploadRing}) is
 * explicitly called out in ticket 30 as a caller that may need a genuinely working
 * {@code mappedAddress()} to be tested without a real GL context — {@code MemoryUtil}
 * performs pure native-heap bookkeeping and does not require a GL/GPU context, so this
 * stays usable in headless CI. The tradeoff: this buffer's backing store is then split
 * across two representations — the {@code byte[]} used by {@link FakeRenderBackend}'s
 * copy/upload/read/write test-inspection API, and the separate off-heap block returned
 * by {@code mappedAddress()}. They are <b>not the same memory and are not kept in
 * sync automatically</b> — writing through the raw pointer returned by
 * {@code mappedAddress()} does not change what {@link FakeRenderBackend#readBufferContents}
 * returns, and vice versa. This is a real scope limitation of the fake, not an
 * oversight: keeping them synchronized would require intercepting native writes, which
 * is not possible from pure Java. Tests that need both call {@link #mappedAddress()}
 * (write through the native pointer directly) and read that same off-heap block back
 * through a plain {@link java.nio.ByteBuffer} created via
 * {@code MemoryUtil.memByteBuffer(address, size)} — not through
 * {@link FakeRenderBackend#readBufferContents}. This is documented here and again at
 * the {@link FakeRenderBackend} class level so a reader who only sees the fake's
 * Javadoc (not this ticket's text) is warned.
 *
 * <p>The off-heap block (if allocated) is freed in {@link #free()}; calling any other
 * method after {@link #free()} throws {@link IllegalStateException}.
 */
final class FakeGpuBuffer implements GpuBuffer {

    private final long sizeBytes;
    private final BufferUsage usage;
    private final byte[] backingStore;

    /** Only allocated for {@link BufferUsage#STAGING_UPLOAD}; 0L otherwise. */
    private long nativeAddress;
    private boolean freed;

    FakeGpuBuffer(long sizeBytes, BufferUsage usage) {
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be > 0, got " + sizeBytes);
        }
        if (sizeBytes > Integer.MAX_VALUE) {
            // A pure-Java fake backs buffers with byte[], which is int-indexed. Real
            // GL buffers can be larger; this is a fake-specific limitation, documented
            // here rather than silently truncating.
            throw new IllegalArgumentException(
                    "FakeRenderBackend cannot back a buffer larger than Integer.MAX_VALUE bytes "
                            + "(requested " + sizeBytes + "); this is a fake-only limitation, "
                            + "real GpuBuffer implementations are not bound by it");
        }
        this.sizeBytes = sizeBytes;
        this.usage = usage;
        this.backingStore = new byte[(int) sizeBytes];
        if (usage == BufferUsage.STAGING_UPLOAD) {
            this.nativeAddress = MemoryUtil.nmemAlloc(sizeBytes);
        }
    }

    @Override
    public long sizeBytes() {
        return sizeBytes;
    }

    @Override
    public BufferUsage usage() {
        return usage;
    }

    @Override
    public long mappedAddress() {
        requireNotFreed();
        if (usage != BufferUsage.STAGING_UPLOAD) {
            // Matches the real contract (GpuBuffer Javadoc, ticket 04): mappedAddress()
            // is only meaningful for STAGING_UPLOAD buffers.
            throw new UnsupportedOperationException(
                    "mappedAddress() is only supported for STAGING_UPLOAD buffers, this buffer's "
                            + "usage is " + usage + ". For test setup/assertions on non-mapped "
                            + "fake buffers, use FakeRenderBackend.writeBufferContents()/"
                            + "readBufferContents() instead.");
        }
        return nativeAddress;
    }

    /** Package-private accessor used by {@link FakeRenderBackend}/{@link FakeCommandList}. */
    byte[] backingStore() {
        requireNotFreed();
        return backingStore;
    }

    boolean isFreed() {
        return freed;
    }

    @Override
    public void free() {
        if (freed) {
            return;
        }
        if (nativeAddress != 0L) {
            MemoryUtil.nmemFree(nativeAddress);
            nativeAddress = 0L;
        }
        freed = true;
    }

    private void requireNotFreed() {
        if (freed) {
            throw new IllegalStateException("buffer has already been free()'d");
        }
    }
}
