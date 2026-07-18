package org.ex.exceptionalvision.world.lod;

/**
 * A {@value #SIZE}x{@value #SIZE} downsampled heightfield: one cell per local
 * (x, z), each either present (top height + material palette index + light) or
 * absent (no opaque block found for that cell's footprint — an ungenerated chunk,
 * or a genuine hole in the terrain).
 * <p>
 * Every {@link LodNode}, at <em>every</em> level, is backed by exactly one grid of
 * this fixed size. A level-{@code L} node covers {@code 16 * 2^L} blocks per side
 * (see {@code 03_data_formats.md}), so each cell of a level-{@code L} node's grid
 * represents a {@code 2^L}-block-wide area rather than a single block — see
 * {@link LodBuilder} for why this is necessary (it keeps {@link PackedQuad}'s 6-bit
 * local x/z fields valid at every level, not just level 0).
 * <p>
 * This class has no Minecraft dependency on purpose, so the meshing/downsampling
 * algorithms that operate on it ({@link GreedyMesher}, {@link LodDownsampler}) can be
 * exercised and unit-tested without a running game instance.
 */
public final class ColumnGrid {

    public static final int SIZE = 16;

    private final int[] height = new int[SIZE * SIZE];
    private final int[] material = new int[SIZE * SIZE];
    private final int[] light = new int[SIZE * SIZE];
    private final boolean[] present = new boolean[SIZE * SIZE];

    public static int index(int x, int z) {
        if (x < 0 || x >= SIZE || z < 0 || z >= SIZE) {
            throw new IndexOutOfBoundsException("cell (" + x + ", " + z + ") outside 0.." + (SIZE - 1));
        }
        return x + z * SIZE;
    }

    public void set(int x, int z, int height, int material, int light) {
        int idx = index(x, z);
        this.height[idx] = height;
        this.material[idx] = material;
        this.light[idx] = light;
        this.present[idx] = true;
    }

    public boolean present(int x, int z) {
        return present[index(x, z)];
    }

    public int height(int x, int z) {
        return height[index(x, z)];
    }

    public int material(int x, int z) {
        return material[index(x, z)];
    }

    public int light(int x, int z) {
        return light[index(x, z)];
    }

    /** Whether every cell is absent (e.g. all four quadrants a node was downsampled from were themselves absent). */
    public boolean isEmpty() {
        for (boolean p : present) {
            if (p) {
                return false;
            }
        }
        return true;
    }
}
