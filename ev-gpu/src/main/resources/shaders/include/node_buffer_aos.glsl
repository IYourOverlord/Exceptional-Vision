// node_buffer_aos.glsl — MVP Array-of-Structures node buffer declaration.
// See NodeBuffer.java for the authoritative byte layout this must match exactly.
//
// std430 layout alignment rules for array of structs:
// - vec4 bounds requires 16-byte alignment and occupies 16 bytes (offset 0..15).
// - uint flags occupies 4 bytes (offset 16..19).
// - uint materialRef occupies 4 bytes (offset 20..23).
// - uint streamState occupies 4 bytes (offset 24..27).
// - uint _padding occupies 4 bytes (offset 28..31) to align struct total size to 32 bytes (multiple of 16).
// Total size per struct Node: 32 bytes.

struct Node {
    vec4 bounds;      // xyz = center, w = radius
    uint flags;
    uint materialRef;
    uint streamState;
    uint _padding;
};

layout(std430, binding = 0) buffer NodeBufferAoS {
    Node nodes[];
};

#define NODE_FLAG_RESIDENT (1u << 0)

#define STREAM_STATE_NOT_REQUESTED 0u
#define STREAM_STATE_REQUESTED     1u
#define STREAM_STATE_RESIDENT      2u
