package dev.ev.meshing.stage;

import dev.ev.api.SectionPos;
import dev.ev.api.storage.WorldSectionHandle;

/** Minimal in-memory WorldSectionHandle for meshing-stage tests, with a getVoxel call counter. */
final class FakeWorldSectionHandle implements WorldSectionHandle {

    private final SectionPos position = new SectionPos(0, 0, 0, 0);
    private final int[] voxels = new int[32 * 32 * 32];
    private int getVoxelCalls = 0;

    @Override
    public SectionPos position() {
        return position;
    }

    @Override
    public int getVoxel(int localX, int localY, int localZ) {
        getVoxelCalls++;
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
        return this;
    }

    @Override
    public void release() {
    }

    @Override
    public int refCount() {
        return 1;
    }

    int getVoxelCallCount() {
        return getVoxelCalls;
    }
}
