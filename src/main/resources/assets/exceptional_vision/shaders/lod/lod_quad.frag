#version 460 core
// Unpacks this quad's material color from the MaterialPalette SSBO - see
// MaterialPalette.java for the exact packing this must match.

layout(std430, binding = 2) readonly buffer MaterialPaletteBuffer {
    uint colors[]; // RGBA8, one packed uint per palette entry
};

flat in uint vMaterialIndex;
in float vLight;
in float vDistanceToCamera;

uniform float lodRenderDistance; // FIX: same "how far LOD draws at all" cutoff quad_cull.comp
                                  // already enforces per-node (see that shader) - passed here
                                  // too so this shader can fade the LAST STRETCH before that
                                  // cutoff instead of every node just vanishing the instant it
                                  // crosses the line. quad_cull.comp still does the actual
                                  // node-level "is this visible at all" decision; this is a
                                  // purely cosmetic softening of that hard edge, not a second
                                  // distance check with different semantics.

out vec4 fragColor;

vec4 unpackColor(uint packedColor) {
    // MaterialPalette#computeColor packs as (Java int) 0xFF000000 | rgb, i.e.
    // alpha in bits 24-31, red in 16-23, green in 8-15, blue in 0-7.
    float a = float((packedColor >> 24u) & 0xFFu) / 255.0;
    float r = float((packedColor >> 16u) & 0xFFu) / 255.0;
    float g = float((packedColor >> 8u) & 0xFFu) / 255.0;
    float b = float(packedColor & 0xFFu) / 255.0;
    return vec4(r, g, b, a);
}

// Cheap, standard pseudo-random hash of screen position - just needs to be uncorrelated
// between neighboring pixels, not cryptographically anything. Used below to scatter which
// fragments get discarded near the LOD edge, so the edge dissolves into a dithered gradient
// instead of popping as one hard silhouette.
float screenDither(vec2 screenPos) {
    return fract(sin(dot(screenPos, vec2(12.9898, 78.233))) * 43758.5453);
}

void main() {
    // FIX (playtest report: "invisible wall cutting off my view distance" persisted after
    // the quad_cull.comp/LodGpuPipeline fixes from the previous session): those fixes
    // corrected where the cutoff sits and made sure every level actually reaches it, but
    // the cutoff itself was always a hard "this node either draws in full or not at all"
    // decision in quad_cull.comp - by design, since lodRenderDistance is meant to be an
    // actual limit, not unbounded. At any distance far enough for the player to actually
    // fly to (2048 blocks by default is well past typical vanilla render distances, but
    // this mod's players are specifically testing LOD by flying to its edges), a hard
    // stop reads as a wall/pop-in regardless of how "correct" the underlying math is - a
    // real UX problem worth softening even though a full distance-fog blend
    // (matching Minecraft's own fog color, needing that color piped in from the render
    // path) is more than this stage-4 pass's scope. This uses a dithered discard instead:
    // no new uniforms from outside this shader pair, no GL blend-state changes (this pass
    // deliberately runs with GL_BLEND disabled - see LodGpuPipeline#runDrawPass), just an
    // increasing chance per-fragment of being discarded over the last 12% of
    // lodRenderDistance, so the edge dissolves into a speckled gradient rather than a
    // single hard silhouette. Not a substitute for real fog/atmosphere blending - stage 5
    // territory - just a cheap way to stop the edge reading as a "wall".
    float fadeBand = lodRenderDistance * 0.12;
    float fadeStart = lodRenderDistance - fadeBand;
    float fadeFactor = clamp((vDistanceToCamera - fadeStart) / max(fadeBand, 1.0), 0.0, 1.0);
    if (fadeFactor > 0.0 && screenDither(gl_FragCoord.xy) < fadeFactor) {
        discard;
    }

    vec4 base = unpackColor(colors[vMaterialIndex]);
    fragColor = vec4(base.rgb * mix(0.35, 1.0, vLight), base.a);
}
