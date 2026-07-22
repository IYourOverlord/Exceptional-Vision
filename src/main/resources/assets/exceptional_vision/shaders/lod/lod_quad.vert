#version 460 core
// Procedurally generates quad geometry straight from the QuadBuffer SSBO - no vertex
// buffer at all (see 04_gpu_pipeline.md, "Vertex/fragment shader генерируют геометрию
// квадов из SSBO"). Each draw command from quad_cull.comp covers one node's quads;
// gl_DrawID (core since GL 4.6, hence the #version 460 requirement - see
// GpuCapabilities.java) tells us which node this invocation belongs to.
//
// Coordinate/packing conventions here MUST match PackedQuad.java / NodeData.java /
// GreedyMesher.java exactly - see GreedyMesher's javadoc for the width/height-per-axis
// convention this reconstructs.

struct NodeData {
    vec4 aabbMin;
    vec4 aabbMax;
    uint quadOffset;
    uint quadCount;
    uint lodLevel;
    uint _pad;
};

layout(std430, binding = 0) readonly buffer NodeBuffer {
    NodeData nodes[];
};

layout(std430, binding = 1) readonly buffer QuadBuffer {
    uvec2 quads[]; // .x = packed0, .y = packed1 - see PackedQuad.java
};

layout(std430, binding = 3) readonly buffer VisibleNodeIndices {
    uint visibleNodeIndices[];
};

uniform mat4 viewProjectionMatrix;
uniform vec3 cameraWorldPos; // subtracted before the projection multiply - keeps vertex math well-conditioned far from the world origin

flat out uint vMaterialIndex;
out float vLight;
out float vDistanceToCamera; // FIX: lets lod_quad.frag dither-fade quads near lodRenderDistance
                              // instead of them hard-popping out of existence at the cutoff -
                              // see that shader for why. Computed here rather than recomputed
                              // per-fragment since cameraRelative is already available.

const vec2 CORNERS[6] = vec2[6](
    vec2(0.0, 0.0), vec2(1.0, 0.0), vec2(0.0, 1.0),
    vec2(0.0, 1.0), vec2(1.0, 0.0), vec2(1.0, 1.0)
);

void main() {
    uint quadIndex = uint(gl_VertexID) / 6u;
    uint cornerIndex = uint(gl_VertexID) % 6u;

    uint nodeIndex = visibleNodeIndices[gl_DrawID];
    NodeData node = nodes[nodeIndex];

    // BUGFIX: quad_cull.comp already encodes this node's quadOffset into the indirect
    // command's "first" field (commands[slot].first = node.quadOffset * 6u). Per the GL
    // spec, gl_VertexID for a non-indexed draw equals first + i, so quadIndex above is
    // ALREADY node.quadOffset + local-quad-index. Adding node.quadOffset again here was
    // double-counting it, reading past this node's quad range (and, for any node past
    // the first in the buffer, past unrelated data or the buffer's end entirely - on
    // most drivers an out-of-bounds std430 SSBO read returns zero, which yields a
    // degenerate zero-size quad, i.e. invisible geometry for every node except the very
    // first one in NodeBuffer).
    uvec2 packedQuad = quads[quadIndex];
    uint packed0 = packedQuad.x;
    uint packed1 = packedQuad.y;

    uint localX = packed0 & 0x3Fu;
    uint localY = (packed0 >> 6u) & 0xFFFu;
    uint localZ = (packed0 >> 18u) & 0x3Fu;
    uint axis   = (packed0 >> 24u) & 0x7u;

    uint width  = packed1 & 0x3Fu;
    uint height = (packed1 >> 6u) & 0x3Fu;
    uint materialIndex = (packed1 >> 12u) & 0xFFFFu;
    uint light  = (packed1 >> 28u) & 0xFu;

    float cellSize = float(1u << node.lodLevel); // see LodBuilder.java - one grid cell covers 2^lodLevel blocks
    float worldX = node.aabbMin.x + float(localX) * cellSize;
    float worldZ = node.aabbMin.z + float(localZ) * cellSize;
    float worldY = float(localY) - 64.0; // GreedyMesher.Y_OFFSET

    vec2 corner = CORNERS[cornerIndex];
    vec3 worldPos;

    if (axis == 2u) {
        // +Y top face: width/height are both in CELLS (need cellSize scaling).
        worldPos = vec3(worldX + corner.x * float(width) * cellSize, worldY, worldZ + corner.y * float(height) * cellSize);
    } else if (axis == 0u || axis == 1u) {
        // +-X wall: localX is the FIXED boundary coord (no expansion); width runs along Z
        // (in cells), height is the vertical extent in raw BLOCKS (not cells).
        float z = worldZ + corner.x * float(width) * cellSize;
        float y = worldY + corner.y * float(height);
        worldPos = vec3(worldX, y, z);
    } else {
        // +-Z wall (axis 4/5): localZ is the FIXED boundary coord; width runs along X (in cells).
        float x = worldX + corner.x * float(width) * cellSize;
        float y = worldY + corner.y * float(height);
        worldPos = vec3(x, y, worldZ);
    }

    vec3 cameraRelative = worldPos - cameraWorldPos;
    gl_Position = viewProjectionMatrix * vec4(cameraRelative, 1.0);

    vMaterialIndex = materialIndex;
    vLight = float(light) / 15.0;
    // FIX: quad_cull.comp's node-level cutoff now measures horizontal (XZ) distance to
    // decide whether a node draws at all (see that shader's lodLevelAppropriate) - this
    // per-vertex distance feeds lod_quad.frag's dither fade over the last stretch before
    // lodRenderDistance, and must use the same horizontal-only metric. Using the full 3D
    // length(cameraRelative) here would fade quads based on slant distance (including
    // height), disagreeing with the cutoff that already decided this node is visible, and
    // reintroducing the same altitude-dependent shrinkage in the fade band specifically.
    vDistanceToCamera = length(cameraRelative.xz);
}
