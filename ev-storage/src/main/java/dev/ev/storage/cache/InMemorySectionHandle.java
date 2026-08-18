package dev.ev.storage.cache;

import dev.ev.api.SectionPos;
import dev.ev.api.storage.WorldSectionHandle;
import dev.ev.storage.codec.PaletteCodec;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory {@link WorldSectionHandle} backed by a flat {@code int[32768]} voxel array.
 * Reference-counted for SectionCache retain/release discipline. Thread-safe for
 * concurrent voxel reads; writes must be externally synchronized if concurrent.
 */
public final class InMemorySectionHandle implements WorldSectionHandle {

    private static final int DIM = PaletteCodec.DIM;

    private final SectionPos position;
    private final int[] voxels;
    private final AtomicInteger refCount = new AtomicInteger(0);

    public InMemorySectionHandle(SectionPos position) {
        this.position = position;
        this.voxels = new int[PaletteCodec.SECTION_SIZE];
    }

    @Override
    public SectionPos position() {
        return position;
    }

    @Override
    public int getVoxel(int localX, int localY, int localZ) {
        return voxels[localX + localY * DIM + localZ * DIM * DIM];
    }

    @Override
    public void setVoxel(int localX, int localY, int localZ, int paletteIndex) {
        voxels[localX + localY * DIM + localZ * DIM * DIM] = paletteIndex;
    }

    @Override
    public boolean isEmpty() {
        for (int v : voxels) {
            if (v != 0) return false;
        }
        return true;
    }

    @Override
    public WorldSectionHandle retain() {
        refCount.incrementAndGet();
        return this;
    }

    @Override
    public void release() {
        refCount.decrementAndGet();
    }

    @Override
    public int refCount() {
        return refCount.get();
    }
}
