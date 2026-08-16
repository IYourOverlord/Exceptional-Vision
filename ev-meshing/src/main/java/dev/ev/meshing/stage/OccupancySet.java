package dev.ev.meshing.stage;

/**
 * Compact bitset over a 32x32x32 voxel grid, one bit per voxel (1 = occupied,
 * i.e. paletteIndex != 0). Backed by a long[] of 32768/64 = 512 longs.
 * Provides O(1) occupancy queries and face-visibility queries used by greedy meshing.
 */
public final class OccupancySet {

    public static final int GRID_SIZE = 32;
    public static final int VOXEL_COUNT = GRID_SIZE * GRID_SIZE * GRID_SIZE;

    private static final int WORDS = VOXEL_COUNT / Long.SIZE; // 512

    private final long[] bits = new long[WORDS];

    public OccupancySet() {
    }

    public boolean get(int x, int y, int z) {
        int idx = flatIndex(x, y, z);
        return (bits[idx >>> 6] & (1L << (idx & 63))) != 0;
    }

    public void set(int x, int y, int z, boolean occupied) {
        int idx = flatIndex(x, y, z);
        int word = idx >>> 6;
        long mask = 1L << (idx & 63);
        if (occupied) {
            bits[word] |= mask;
        } else {
            bits[word] &= ~mask;
        }
    }

    /** Flat index helper: x + y*32 + z*32*32, bounds NOT checked (hot path — callers must pre-validate). */
    public static int flatIndex(int x, int y, int z) {
        return x + y * GRID_SIZE + z * GRID_SIZE * GRID_SIZE;
    }

    /** True if every bit is 0 (fully empty section — caller can skip meshing entirely). */
    public boolean isEmpty() {
        for (long word : bits) {
            if (word != 0) {
                return false;
            }
        }
        return true;
    }

    /** Number of set bits — useful for metrics/sanity checks, not required on the hot path. */
    public int popCount() {
        int count = 0;
        for (long word : bits) {
            count += Long.bitCount(word);
        }
        return count;
    }
}
