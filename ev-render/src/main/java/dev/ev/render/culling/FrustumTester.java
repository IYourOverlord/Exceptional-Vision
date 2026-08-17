package dev.ev.render.culling;

import java.util.Objects;

/**
 * Simple CPU-side sphere-vs-frustum-planes visibility test. Pure math, no GPU
 * dependency — used by SimpleTraversal to decide which loaded sections are visible
 * this frame.
 */
public final class FrustumTester {

    private final float[] planeXyzw24;

    /**
     * Constructs a FrustumTester with 6 planes.
     *
     * @param planeXyzw24 6 planes (xyz = normal, w = distance from origin), array of length 24.
     *                    Each plane i uses indices [i*4 .. i*4+3].
     * @throws IllegalArgumentException if planeXyzw24 is null or length != 24
     */
    public FrustumTester(float[] planeXyzw24) {
        Objects.requireNonNull(planeXyzw24, "planeXyzw24 cannot be null");
        if (planeXyzw24.length != 24) {
            throw new IllegalArgumentException("planeXyzw24 must have length 24, got: " + planeXyzw24.length);
        }
        this.planeXyzw24 = planeXyzw24.clone();
    }

    /**
     * True if the bounding sphere (worldX/Y/Z center, radius) is at least partially
     * inside the frustum (standard conservative sphere-vs-plane test).
     * <p>
     * Boundary behavior: If the signed distance equals {@code -radius} (sphere surface exactly
     * touching the plane boundary), it is considered VISIBLE ({@code >= -radius}).
     *
     * @param worldX sphere center X
     * @param worldY sphere center Y
     * @param worldZ sphere center Z
     * @param radius sphere radius, non-negative
     * @return true if visible against all 6 planes; false if fully outside any plane
     */
    public boolean isVisible(float worldX, float worldY, float worldZ, float radius) {
        for (int i = 0; i < 6; i++) {
            int offset = i * 4;
            float nx = planeXyzw24[offset];
            float ny = planeXyzw24[offset + 1];
            float nz = planeXyzw24[offset + 2];
            float d = planeXyzw24[offset + 3];

            float signedDistance = nx * worldX + ny * worldY + nz * worldZ + d;
            if (signedDistance < -radius) {
                return false;
            }
        }
        return true;
    }
}
