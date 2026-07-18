package org.ex.exceptionalvision.world.lod;

/**
 * Serializable mirror of the GPU {@code NodeData} struct (std430, 48 bytes), see
 * {@code 03_data_formats.md}. This is the on-disk/on-GPU representation of a quadtree
 * node; the mutable, tree-shaped {@code LodNode} used while building/streaming LOD
 * data (stage 2) is expected to convert to/from this record when it hands nodes to
 * the disk cache (stage 3) or the GPU data manager (stage 4).
 *
 * <pre>
 * struct NodeData {
 *     vec4 aabbMin;    // xyz = мировые координаты минимума, w = lodLevel
 *     vec4 aabbMax;    // xyz = мировые координаты максимума, w не используется
 *     uint quadOffset; // смещение первого квада узла в QuadBuffer
 *     uint quadCount;  // количество квадов узла
 *     uint lodLevel;
 *     uint _pad;       // выравнивание до 48 байт
 * };
 * </pre>
 *
 * @param quadOffset offset of the node's first quad in the (global) quad buffer;
 *                   {@code -1} is used in-memory by {@code LodNode} to mean "not built
 *                   yet", but that sentinel must be resolved to a real, non-negative
 *                   offset before a node is serialized here
 * @param quadCount  number of quads belonging to this node
 * @param lodLevel   0 = most detailed level
 */
public record NodeData(
        float minX, float minY, float minZ,
        float maxX, float maxY, float maxZ,
        int quadOffset,
        int quadCount,
        int lodLevel) {

    /** Size of one record when serialized to {@code nodes.bin}. See {@code 06_disk_cache_format.md}. */
    public static final int BYTES = 48;

    public NodeData {
        if (quadOffset < 0) {
            throw new IllegalArgumentException("quadOffset must be resolved (>= 0) before a NodeData is serialized, got " + quadOffset);
        }
        if (quadCount < 0) {
            throw new IllegalArgumentException("quadCount must not be negative, got " + quadCount);
        }
    }

    /** Returns a copy of this node pointing at a different (offset, count) range in the quad buffer. */
    public NodeData withQuadRange(int newQuadOffset, int newQuadCount) {
        return new NodeData(minX, minY, minZ, maxX, maxY, maxZ, newQuadOffset, newQuadCount, lodLevel);
    }
}
