package org.ex.exceptionalvision.render;

import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Extracts the 6 frustum planes (classic Gribb/Hartmann method) from a combined
 * view-projection matrix, in the {@code (normal.xyz, distance)} form
 * {@code quad_cull.comp} expects, with normals pointing INTO the frustum.
 * <p>
 * Deliberately uses {@link Matrix4f#getRow} rather than hand-picking {@code mCR}
 * fields: JOML names its fields column-major ({@code m01} is column 0, row 1), which is
 * an easy way to silently transpose the extraction and break culling in a way that's
 * hard to notice visually (frustum-shaped bugs look like "some stuff missing", not a
 * crash) - {@code getRow} sidesteps that ambiguity entirely.
 */
public final class FrustumExtractor {

    private FrustumExtractor() {
    }

    /** @return 6 planes, each {@code [normalX, normalY, normalZ, distance]}, in left/right/bottom/top/near/far order. */
    public static float[][] extractPlanes(Matrix4f viewProjection) {
        Vector4f row0 = viewProjection.getRow(0, new Vector4f());
        Vector4f row1 = viewProjection.getRow(1, new Vector4f());
        Vector4f row2 = viewProjection.getRow(2, new Vector4f());
        Vector4f row3 = viewProjection.getRow(3, new Vector4f());

        float[][] planes = new float[6][4];
        setPlane(planes[0], row3.x() + row0.x(), row3.y() + row0.y(), row3.z() + row0.z(), row3.w() + row0.w()); // left
        setPlane(planes[1], row3.x() - row0.x(), row3.y() - row0.y(), row3.z() - row0.z(), row3.w() - row0.w()); // right
        setPlane(planes[2], row3.x() + row1.x(), row3.y() + row1.y(), row3.z() + row1.z(), row3.w() + row1.w()); // bottom
        setPlane(planes[3], row3.x() - row1.x(), row3.y() - row1.y(), row3.z() - row1.z(), row3.w() - row1.w()); // top
        setPlane(planes[4], row3.x() + row2.x(), row3.y() + row2.y(), row3.z() + row2.z(), row3.w() + row2.w()); // near
        setPlane(planes[5], row3.x() - row2.x(), row3.y() - row2.y(), row3.z() - row2.z(), row3.w() - row2.w()); // far
        return planes;
    }

    private static void setPlane(float[] out, float a, float b, float c, float d) {
        float len = (float) Math.sqrt(a * a + b * b + c * c);
        if (len > 1e-8f) {
            a /= len;
            b /= len;
            c /= len;
            d /= len;
        }
        out[0] = a;
        out[1] = b;
        out[2] = c;
        out[3] = d;
    }

    /**
     * Vanilla's per-frame projection matrix (what {@code RenderLevelStageEvent
     * #getProjectionMatrix} hands us) has its far clipping plane tied to the player's
     * configured vanilla render distance, not to this mod's {@code lodRenderDistance}.
     * Reusing that matrix as-is for the LOD pass - both for {@code gl_Position} in
     * {@code lod_quad.vert} and for the frustum planes {@link #extractPlanes} feeds
     * {@code quad_cull.comp} - means LOD geometry gets hard-clipped at roughly the
     * vanilla render distance no matter what {@code lodRenderDistance}/
     * {@code nearCutoffDistance} say, which is the opposite of the point of a far-LOD
     * mod. This confirmed via a real playtest: lowering render distance to 7 chunks
     * left almost no valid window between {@code nearCutoffDistance} and vanilla's own
     * far plane, so nothing drew at all near the ground; looking down from height only
     * showed a small disc directly below, because the far plane is measured along the
     * view ray, not horizontally, and a downward ray to the ground is short even from
     * a modest altitude.
     * <p>
     * Rebuilds just the two matrix entries that encode the far plane
     * ({@code m22}/{@code m32}, JOML's {@code perspective(...)} convention -
     * {@code m22 = (far+near)/(near-far)}, {@code m32 = 2*far*near/(near-far)}),
     * leaving FOV/aspect/near (and hence everything else about how vanilla's frame
     * looks) untouched. Deliberately does NOT try to recover the current near/far from
     * the existing matrix algebraically - with near around 0.05 and far in the
     * hundreds of blocks, that ratio makes such an inversion numerically unstable in
     * float32 (the whole point of a near-plane this close to 0 relative to a distant
     * far plane). Instead assumes {@code assumedNear}, matching vanilla
     * {@code GameRenderer}'s long-standing 0.05-block near plane - <b>not verified
     * against a real build in this session, worth double-checking (e.g. via a debug
     * print of the original {@code m22}/{@code m32} once, and confirming they match
     * what plugging 0.05 in here predicts) on the first real playtest.</b>
     *
     * @param projection  vanilla's projection matrix for this frame, unmodified
     * @param minFar      the far plane must reach at least this many blocks (typically {@code LodGpuPipeline#lodRenderDistance()} plus a margin)
     * @param assumedNear vanilla's near plane, in blocks - see caveat above
     * @return a new matrix, identical to {@code projection} except for its far plane. Always a fresh {@link Matrix4f} - never {@code projection} itself, so callers are free to mutate the result (e.g. via {@code mul}) without touching the original.
     */
    public static Matrix4f withExtendedFarPlane(Matrix4f projection, float minFar, float assumedNear) {
        float e = 1.0f / (assumedNear - minFar);
        Matrix4f extended = new Matrix4f(projection);
        extended.m22((minFar + assumedNear) * e);
        extended.m32(2.0f * minFar * assumedNear * e);
        return extended;
    }
}