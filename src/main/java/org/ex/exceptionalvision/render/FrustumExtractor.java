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
}
