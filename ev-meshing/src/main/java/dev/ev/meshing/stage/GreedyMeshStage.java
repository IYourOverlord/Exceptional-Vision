package dev.ev.meshing.stage;

import dev.ev.api.meshing.MeshingContext;
import dev.ev.api.meshing.Quad;
import dev.ev.api.storage.WorldSectionHandle;

import java.util.ArrayList;
import java.util.List;

/**
 * Second stage of the meshing pipeline: converts an OccupancySet + the section's
 * palette data into a list of greedily-merged Quads, one list per face direction
 * internally but returned as a single combined list (direction is encoded per-Quad
 * via Quad.faceDirection()).
 *
 * <p>Algorithm: standard binary/greedy 2D meshing per axis-slice, adapted for a fixed
 * 32^3 grid. For each of the 6 face directions, iterate 32 slices along the axis
 * normal to that direction; for each slice build a 32x32 mask where a cell is
 * "paintable" if the voxel is occupied AND the neighboring voxel in faceDirection
 * is NOT occupied (i.e. this face is externally visible), tagged with the material
 * id of the occupied voxel; then greedily cover the mask with maximal rectangles
 * of uniform material, clearing covered cells as you go (standard greedy meshing:
 * scan for an uncovered cell, grow width while the run has the same material,
 * grow height while every cell in the row has the same material, emit one Quad,
 * clear the covered region, repeat).
 *
 * <p><b>Width/height convention (ticket 11, requirement 4a)</b>: for a face direction
 * whose normal lies along a given axis, {@code width} grows along the next axis in
 * the cyclic order X&rarr;Y&rarr;Z&rarr;X, and {@code height} grows along the axis after
 * that. Both directions of the same normal axis (e.g. {@code +X} and {@code -X}) share
 * the same width/height orientation; only the source voxel used for face
 * visibility/material differs (see requirement 2).
 *
 * <p><b>Convention for {@link MeshingContext#getNeighborBoundaryVoxel}</b>: this stage
 * calls it with {@code a} bound to the local width-axis coordinate and {@code b} bound
 * to the local height-axis coordinate of the boundary face being tested, per the same
 * cyclic axis convention above. This ordering is not pinned down by the ticket 03/11
 * text itself (only "the two local coordinates spanning that face" is specified) —
 * documented explicitly here so future {@code MeshingContext} implementations
 * (ev-render) agree with it.
 */
public final class GreedyMeshStage {

    private static final int GRID_SIZE = OccupancySet.GRID_SIZE;

    // Axis indices used throughout this class: 0 = X, 1 = Y, 2 = Z.
    private static final int AXIS_X = 0;
    private static final int AXIS_Y = 1;
    private static final int AXIS_Z = 2;

    // Per-faceDirection axis assignment, faceDirection: 0=+X, 1=-X, 2=+Y, 3=-Y, 4=+Z, 5=-Z.
    private static final int[] NORMAL_AXIS = {AXIS_X, AXIS_X, AXIS_Y, AXIS_Y, AXIS_Z, AXIS_Z};
    private static final int[] SIGN = {1, -1, 1, -1, 1, -1};
    // Cyclic convention from requirement 4a (X -> Y -> Z -> X): width follows the
    // normal axis, height follows the width axis.
    private static final int[] WIDTH_AXIS = {AXIS_Y, AXIS_Y, AXIS_Z, AXIS_Z, AXIS_X, AXIS_X};
    private static final int[] HEIGHT_AXIS = {AXIS_Z, AXIS_Z, AXIS_X, AXIS_X, AXIS_Y, AXIS_Y};

    /**
     * Builds the combined, greedily-merged quad list for one section.
     *
     * @param section   source of palette indices (for material resolution) —
     *                  must be the same section {@code occupancy} was built from.
     * @param occupancy precomputed occupancy bitset (see {@link OccupancyStage}).
     * @param ctx       neighbor-lookup and material-resolution context.
     * @return combined list of quads across all 6 face directions; empty if the
     *     section is entirely unoccupied.
     */
    public List<Quad> process(WorldSectionHandle section, OccupancySet occupancy, MeshingContext ctx) {
        List<Quad> result = new ArrayList<>();

        if (occupancy.isEmpty()) {
            return result;
        }

        for (int faceDirection = 0; faceDirection < 6; faceDirection++) {
            meshFaceDirection(faceDirection, section, occupancy, ctx, result);
        }

        return result;
    }

    /** Meshes all 32 slices for a single face direction, appending quads to {@code out}. */
    private void meshFaceDirection(int faceDirection, WorldSectionHandle section, OccupancySet occupancy,
                                    MeshingContext ctx, List<Quad> out) {
        int normalAxis = NORMAL_AXIS[faceDirection];
        int sign = SIGN[faceDirection];
        int widthAxis = WIDTH_AXIS[faceDirection];
        int heightAxis = HEIGHT_AXIS[faceDirection];

        int[] coords = new int[3];
        int[] neighborCoords = new int[3];

        for (int n = 0; n < GRID_SIZE; n++) {
            boolean[][] visible = new boolean[GRID_SIZE][GRID_SIZE];
            int[][] material = new int[GRID_SIZE][GRID_SIZE];

            // --- Build the 2D visibility/material mask for this slice. ---
            for (int h = 0; h < GRID_SIZE; h++) {
                for (int w = 0; w < GRID_SIZE; w++) {
                    coords[normalAxis] = n;
                    coords[widthAxis] = w;
                    coords[heightAxis] = h;

                    if (!occupancy.get(coords[0], coords[1], coords[2])) {
                        continue;
                    }

                    int neighborN = n + sign;
                    boolean neighborOccupied;
                    if (neighborN >= 0 && neighborN < GRID_SIZE) {
                        neighborCoords[0] = coords[0];
                        neighborCoords[1] = coords[1];
                        neighborCoords[2] = coords[2];
                        neighborCoords[normalAxis] = neighborN;
                        neighborOccupied = occupancy.get(neighborCoords[0], neighborCoords[1], neighborCoords[2]);
                    } else {
                        // Off the edge of this section — defer to the neighboring section
                        // via the context. Palette index 0 == air == face visible.
                        neighborOccupied = ctx.getNeighborBoundaryVoxel(faceDirection, w, h) != 0;
                    }

                    if (!neighborOccupied) {
                        visible[w][h] = true;
                        int paletteIndex = section.getVoxel(coords[0], coords[1], coords[2]);
                        material[w][h] = ctx.resolveMaterialId(paletteIndex);
                    }
                }
            }

            // --- Greedily cover the mask with maximal same-material rectangles. ---
            for (int h = 0; h < GRID_SIZE; h++) {
                for (int w = 0; w < GRID_SIZE; w++) {
                    if (!visible[w][h]) {
                        continue;
                    }

                    int materialId = material[w][h];

                    int width = 1;
                    while (w + width < GRID_SIZE
                        && visible[w + width][h]
                        && material[w + width][h] == materialId) {
                        width++;
                    }

                    int height = 1;
                    growHeight:
                    while (h + height < GRID_SIZE) {
                        for (int k = 0; k < width; k++) {
                            if (!visible[w + k][h + height] || material[w + k][h + height] != materialId) {
                                break growHeight;
                            }
                        }
                        height++;
                    }

                    // Clear the covered region so it isn't re-emitted by a later scan cell.
                    for (int hh = 0; hh < height; hh++) {
                        for (int ww = 0; ww < width; ww++) {
                            visible[w + ww][h + hh] = false;
                        }
                    }

                    coords[normalAxis] = (sign > 0) ? n + 1 : n;
                    coords[widthAxis] = w;
                    coords[heightAxis] = h;

                    out.add(new Quad(faceDirection, coords[0], coords[1], coords[2], width, height, materialId));
                }
            }
        }
    }
}
