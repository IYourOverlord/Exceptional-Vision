package dev.ev.storage.coarsegen;

import dev.ev.api.SectionPos;
import dev.ev.api.storage.WorldSectionHandle;

/**
 * Minimal in-memory {@link WorldSectionHandle} for tests: a flat 32x32x32 array with no
 * eviction/reference-counting semantics beyond satisfying the interface contract.
 */
final class FakeWorldSectionHandle implements WorldSectionHandle {

    private final SectionPos position;
    private final int[] voxels = new int[32 * 32 * 32];
    private int refCount = 1;

    FakeWorldSectionHandle(SectionPos position) {
        this.position = position;
    }

    @Override
    public SectionPos position() {
        return position;
    }

    @Override
    public int getVoxel(int localX, int localY, int localZ) {
        return voxels[localX + localY * 32 + localZ * 32 * 32];
    }

    @Override
    public void setVoxel(int localX, int localY, int localZ, int paletteIndex) {
        voxels[localX + localY * 32 + localZ * 32 * 32] = paletteIndex;
    }

    @Override
    public boolean isEmpty() {
        for (int v : voxels) {
            if (v != 0) {
                return false;
            }
        }
        return true;
    }

    @Override
    public WorldSectionHandle retain() {
        refCount++;
        return this;
    }

    @Override
    public void release() {
        refCount--;
    }

    @Override
    public int refCount() {
        return refCount;
    }
}
