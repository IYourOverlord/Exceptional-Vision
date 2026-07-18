#version 460 core
// Unpacks this quad's material color from the MaterialPalette SSBO - see
// MaterialPalette.java for the exact packing this must match.

layout(std430, binding = 2) readonly buffer MaterialPaletteBuffer {
    uint colors[]; // RGBA8, one packed uint per palette entry
};

flat in uint vMaterialIndex;
in float vLight;

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

void main() {
    vec4 base = unpackColor(colors[vMaterialIndex]);
    fragColor = vec4(base.rgb * mix(0.35, 1.0, vLight), base.a);
}
