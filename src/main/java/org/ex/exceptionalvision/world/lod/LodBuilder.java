package org.ex.exceptionalvision.world.lod;

import net.minecraft.world.level.block.state.BlockState;
import org.ex.exceptionalvision.world.ChunkHeightData;
import org.ex.exceptionalvision.world.RegionCoordinate;
import org.ex.exceptionalvision.world.RegionResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the full LOD quadtree for one region (stage 2): downsamples the region's
 * {@link ChunkHeightData} into a pyramid of {@link LodNode}s and greedy-meshes each
 * one into {@link PackedQuad}s, then flattens the whole tree for {@code LodCacheWriter}
 * (stage 3).
 *
 * <h2>Level layout</h2>
 * A region is always exactly 32x32 chunks (Anvil format). Level 0 has one node per
 * generated chunk (16x16 blocks); each level above halves the node count per axis and
 * doubles {@code chunkSpan}, up to level 5 (the whole region as one node, 512x512
 * blocks). {@link LodDownsampler} builds each level from the one below it rather than
 * re-scanning raw column data every time.
 *
 * <h2>Why every node's grid is 16x16 regardless of level</h2>
 * {@link PackedQuad}'s local x/z fields are only 6 bits (0..63, see
 * {@code 03_data_formats.md}). A level-5 node's footprint is 512x512 blocks — encoding
 * that directly in blocks would need 9 bits. Instead, every node (at any level) is
 * downsampled into the same fixed {@value ColumnGrid#SIZE}x{@value ColumnGrid#SIZE}
 * grid, so a quad's local x/z is always a <em>cell</em> index in {@code 0..15}, and one
 * cell covers {@code 2^level} blocks. This isn't spelled out explicitly in
 * {@code 03_data_formats.md} (which only labels the field "local position within the
 * node"), so it's called out here since the stage-4 GPU vertex shader needs to
 * reconstruct world positions the same way: {@code worldX = node.minWorldX + localX * 2^level}.
 */
public final class LodBuilder {

    private static final int CHUNKS_PER_REGION_SIDE = 32;
    private static final int MAX_LEVEL = 5; // log2(32); level 5 = the whole region as one node

    /**
     * {@link ChunkHeightData} doesn't carry light data yet — {@code RegionFileReader}
     * (stage 1) extracts only height and top block state, not the Light NBT sections —
     * so LOD columns render at a flat full brightness until that's extended. Documented
     * simplification, not a hidden bug.
     */
    private static final int DEFAULT_LIGHT = 15;

    private final MaterialPalette palette;
    private final GreedyMesher mesher = new GreedyMesher();
    private final LodDownsampler downsampler = new LodDownsampler();

    public LodBuilder(MaterialPalette palette) {
        this.palette = palette;
    }

    public LodBuildResult buildRegion(RegionResult region) {
        ChunkHeightData[][] chunksByLocalPos = indexByLocalChunk(region);

        LodNode[][] level = buildLevelZero(region.coordinate(), chunksByLocalPos);

        List<LodNode> allNodes = new ArrayList<>();
        List<LevelStats> stats = new ArrayList<>();
        stitchLevel(level, CHUNKS_PER_REGION_SIDE);
        addLevelNodes(level, 0, allNodes, stats);

        int width = CHUNKS_PER_REGION_SIDE;
        for (int currentLevel = 0; currentLevel < MAX_LEVEL; currentLevel++) {
            level = buildNextLevel(region.coordinate(), level, width, currentLevel);
            width /= 2;
            stitchLevel(level, width);
            addLevelNodes(level, currentLevel + 1, allNodes, stats);
        }

        return flatten(region.coordinate(), allNodes, stats);
    }

    /**
     * Closes the seam between every pair of same-level, horizontally/vertically-adjacent
     * nodes in this level's grid by appending boundary wall quads to the east/north
     * node of each pair (see {@link GreedyMesher#meshBoundaryAlongX}/
     * {@code meshBoundaryAlongZ} for why it's always the east/north side that receives
     * the quads). Only intra-region, same-level seams are closed this way — the region's
     * own outer edge (no neighboring region's grid available here) and LOD-level
     * transitions (a node's neighbor at a coarser/finer level lives in a different
     * level's array, not this one) are out of scope; see "Баг 9" in {@code PROGRESS.md}.
     */
    private void stitchLevel(LodNode[][] level, int width) {
        for (int z = 0; z < width; z++) {
            for (int x = 0; x < width; x++) {
                LodNode node = level[x][z];
                if (node == null) {
                    continue;
                }
                if (x + 1 < width) {
                    LodNode east = level[x + 1][z];
                    if (east != null) {
                        east.quads.addAll(mesher.meshBoundaryAlongX(node.grid, east.grid));
                    }
                }
                if (z + 1 < width) {
                    LodNode north = level[x][z + 1];
                    if (north != null) {
                        north.quads.addAll(mesher.meshBoundaryAlongZ(node.grid, north.grid));
                    }
                }
            }
        }
    }

    private LodNode[][] buildLevelZero(RegionCoordinate regionCoordinate, ChunkHeightData[][] chunksByLocalPos) {
        LodNode[][] level = new LodNode[CHUNKS_PER_REGION_SIDE][CHUNKS_PER_REGION_SIDE];
        for (int cz = 0; cz < CHUNKS_PER_REGION_SIDE; cz++) {
            for (int cx = 0; cx < CHUNKS_PER_REGION_SIDE; cx++) {
                ChunkHeightData data = chunksByLocalPos[cx][cz];
                if (data == null) {
                    continue; // chunk not generated (yet) - no LOD0 node for it
                }
                ColumnGrid grid = toColumnGrid(data);
                LodNode node = buildNode(0, 1,
                        regionCoordinate.minChunkX() + cx, regionCoordinate.minChunkZ() + cz,
                        grid, !data.complete());
                level[cx][cz] = node;
            }
        }
        return level;
    }

    private LodNode[][] buildNextLevel(RegionCoordinate regionCoordinate, LodNode[][] childLevel, int childWidth, int childLevelIndex) {
        int nextWidth = childWidth / 2;
        int nextChunkSpan = 1 << (childLevelIndex + 1);
        LodNode[][] next = new LodNode[nextWidth][nextWidth];
        for (int nz = 0; nz < nextWidth; nz++) {
            for (int nx = 0; nx < nextWidth; nx++) {
                LodNode nw = childLevel[2 * nx][2 * nz];
                LodNode ne = childLevel[2 * nx + 1][2 * nz];
                LodNode sw = childLevel[2 * nx][2 * nz + 1];
                LodNode se = childLevel[2 * nx + 1][2 * nz + 1];
                if (nw == null && ne == null && sw == null && se == null) {
                    continue; // whole quadrant has no data - no parent node either
                }
                ColumnGrid parentGrid = downsampler.downsample(gridOf(nw), gridOf(ne), gridOf(sw), gridOf(se));
                int originChunkX = regionCoordinate.minChunkX() + nx * nextChunkSpan;
                int originChunkZ = regionCoordinate.minChunkZ() + nz * nextChunkSpan;
                boolean dirty = isDirty(nw) || isDirty(ne) || isDirty(sw) || isDirty(se);
                next[nx][nz] = buildNode(childLevelIndex + 1, nextChunkSpan, originChunkX, originChunkZ, parentGrid, dirty);
            }
        }
        return next;
    }

    private LodNode buildNode(int level, int chunkSpan, int originChunkX, int originChunkZ, ColumnGrid grid, boolean dirty) {
        LodNode node = new LodNode(level, chunkSpan, originChunkX, originChunkZ);
        node.grid = grid;
        node.quads = mesher.mesh(grid);
        node.dirty = dirty;
        return node;
    }

    private static ColumnGrid gridOf(LodNode node) {
        return node == null ? null : node.grid;
    }

    /** A missing child (no data at all for that quadrant) also makes the parent provisional. */
    private static boolean isDirty(LodNode node) {
        return node == null || node.dirty;
    }

    private void addLevelNodes(LodNode[][] level, int levelIndex, List<LodNode> out, List<LevelStats> stats) {
        int nodeCount = 0;
        int quadCount = 0;
        for (LodNode[] row : level) {
            for (LodNode node : row) {
                if (node == null) {
                    continue;
                }
                out.add(node);
                nodeCount++;
                quadCount += node.quads.size();
            }
        }
        stats.add(new LevelStats(levelIndex, nodeCount, quadCount));
    }

    private LodBuildResult flatten(RegionCoordinate coordinate, List<LodNode> nodes, List<LevelStats> stats) {
        List<NodeData> nodeData = new ArrayList<>(nodes.size());
        List<PackedQuad> allQuads = new ArrayList<>();
        for (LodNode node : nodes) {
            int offset = allQuads.size();
            allQuads.addAll(node.quads);
            int[] minMaxHeight = minMaxHeight(node.grid);
            nodeData.add(new NodeData(
                    node.minWorldX(), minMaxHeight[0], node.minWorldZ(),
                    node.maxWorldX(), minMaxHeight[1] + 1f, node.maxWorldZ(), // +1: top surface of the highest column, not its floor
                    offset, node.quads.size(), node.level));
        }
        return new LodBuildResult(coordinate, nodeData, allQuads, stats);
    }

    private int[] minMaxHeight(ColumnGrid grid) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int z = 0; z < ColumnGrid.SIZE; z++) {
            for (int x = 0; x < ColumnGrid.SIZE; x++) {
                if (!grid.present(x, z)) {
                    continue;
                }
                int h = grid.height(x, z);
                min = Math.min(min, h);
                max = Math.max(max, h);
            }
        }
        if (min > max) {
            // Defensive only: LodBuilder never creates a node from a fully-empty grid, since
            // buildNextLevel skips a quadrant group entirely when all 4 children are null.
            return new int[] {0, 0};
        }
        return new int[] {min, max};
    }

    private ChunkHeightData[][] indexByLocalChunk(RegionResult region) {
        ChunkHeightData[][] chunks = new ChunkHeightData[CHUNKS_PER_REGION_SIDE][CHUNKS_PER_REGION_SIDE];
        int baseX = region.coordinate().minChunkX();
        int baseZ = region.coordinate().minChunkZ();
        for (ChunkHeightData data : region.chunks()) {
            int lx = data.chunkX() - baseX;
            int lz = data.chunkZ() - baseZ;
            if (lx < 0 || lx >= CHUNKS_PER_REGION_SIDE || lz < 0 || lz >= CHUNKS_PER_REGION_SIDE) {
                continue; // defensive: a chunk that (incorrectly) claims to belong to a different region
            }
            chunks[lx][lz] = data;
        }
        return chunks;
    }

    private ColumnGrid toColumnGrid(ChunkHeightData data) {
        ColumnGrid grid = new ColumnGrid();
        for (int z = 0; z < ColumnGrid.SIZE; z++) {
            for (int x = 0; x < ColumnGrid.SIZE; x++) {
                int y = data.topY(x, z);
                if (y == Integer.MIN_VALUE) {
                    continue; // no opaque block in this column
                }
                BlockState state = data.topState(x, z);
                int materialIndex = palette.indexFor(state);
                grid.set(x, z, y, materialIndex, DEFAULT_LIGHT);
            }
        }
        return grid;
    }
}