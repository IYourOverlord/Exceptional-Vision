package dev.ev.api.storage;

import dev.ev.api.SectionPos;

/**
 * A reference-counted handle to voxel data for one section. Obtained via
 * {@link VoxelStorage#acquire}, must be released via {@link #release()} exactly once
 * per acquire call (standard retain/release discipline — do not release more times
 * than acquired, do not use after final release).
 */
public interface WorldSectionHandle {

    SectionPos position();

    /**
     * Reads a single voxel's palette index at local coordinates within this section
     * (each coordinate in range [0, 31], since every section is logically a 32^3 grid
     * regardless of LOD level — the LOD level only changes what world-space size that
     * grid covers, via SectionPos.sizeInBlocks()).
     */
    int getVoxel(int localX, int localY, int localZ);

    /** Writes a single voxel's palette index. Does not automatically mark dirty. */
    void setVoxel(int localX, int localY, int localZ, int paletteIndex);

    /** True if every voxel in this section is palette index 0 (air/empty). */
    boolean isEmpty();

    /** Increments the reference count. Returns this for chaining. */
    WorldSectionHandle retain();

    /** Decrements the reference count. When it reaches zero the section may be evicted. */
    void release();

    /** Current reference count, for debugging/assertions only. */
    int refCount();
}
