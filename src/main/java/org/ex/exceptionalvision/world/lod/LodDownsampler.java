package org.ex.exceptionalvision.world.lod;

/**
 * Builds one level's {@link ColumnGrid} from its four (level - 1) children's grids —
 * the mip-pyramid step that lets {@link LodBuilder} avoid re-scanning raw block data
 * at every level. A parent cell combines the 2x2 group of same-quadrant child cells
 * that cover the same world-space footprint: since every node's grid is a fixed
 * {@value ColumnGrid#SIZE}x{@value ColumnGrid#SIZE}, and a child's footprint is
 * exactly one quadrant (half the side length) of its parent's, child cell size is
 * always half the parent's — so 2x2 child cells always correspond to exactly 1
 * parent cell.
 * <p>
 * The representative sample of a 2x2 group is the <em>highest</em> of the (up to 4)
 * present cells, not an average — preserving silhouette peaks (a lone tower, a single
 * tall tree) matters more for a distant LOD than smoothing them away level by level. A
 * parent cell is only absent if all 4 contributing child cells are absent.
 */
public final class LodDownsampler {

    private static final int HALF = ColumnGrid.SIZE / 2;

    /**
     * @param nw north-west child (parent-local x in 0..7, z in 0..7), may be {@code null} if that quadrant has no data
     * @param ne north-east child (parent-local x in 8..15, z in 0..7), may be {@code null}
     * @param sw south-west child (parent-local x in 0..7, z in 8..15), may be {@code null}
     * @param se south-east child (parent-local x in 8..15, z in 8..15), may be {@code null}
     */
    public ColumnGrid downsample(ColumnGrid nw, ColumnGrid ne, ColumnGrid sw, ColumnGrid se) {
        ColumnGrid parent = new ColumnGrid();
        combineQuadrant(parent, nw, 0, 0);
        combineQuadrant(parent, ne, HALF, 0);
        combineQuadrant(parent, sw, 0, HALF);
        combineQuadrant(parent, se, HALF, HALF);
        return parent;
    }

    private void combineQuadrant(ColumnGrid parent, ColumnGrid child, int parentOffsetX, int parentOffsetZ) {
        if (child == null) {
            return; // leave these parent cells absent
        }
        for (int pz = 0; pz < HALF; pz++) {
            for (int px = 0; px < HALF; px++) {
                int cx = px * 2;
                int cz = pz * 2;
                Sample best = null;
                for (int dz = 0; dz < 2; dz++) {
                    for (int dx = 0; dx < 2; dx++) {
                        int x = cx + dx;
                        int z = cz + dz;
                        if (!child.present(x, z)) {
                            continue;
                        }
                        int h = child.height(x, z);
                        if (best == null || h > best.height) {
                            best = new Sample(h, child.material(x, z), child.light(x, z));
                        }
                    }
                }
                if (best != null) {
                    parent.set(parentOffsetX + px, parentOffsetZ + pz, best.height, best.material, best.light);
                }
            }
        }
    }

    private record Sample(int height, int material, int light) {
    }
}
