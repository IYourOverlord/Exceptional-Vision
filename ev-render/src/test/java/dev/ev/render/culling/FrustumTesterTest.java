package dev.ev.render.culling;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FrustumTesterTest {

    // Simple orthographic box frustum [-10, 10] along X, Y, Z
    // Planes: nx*x + ny*y + nz*z + d >= 0
    // Left:   +1*x + 0*y + 0*z + 10 >= 0  (x >= -10)
    // Right:  -1*x + 0*y + 0*z + 10 >= 0  (x <= 10)
    // Bottom: 0*x + 1*y + 0*z + 10 >= 0   (y >= -10)
    // Top:    0*x - 1*y + 0*z + 10 >= 0   (y <= 10)
    // Near:   0*x + 0*y + 1*z + 10 >= 0   (z >= -10)
    // Far:    0*x + 0*y - 1*z + 10 >= 0   (z <= 10)
    private static final float[] BOX_FRUSTUM_24 = new float[]{
            1.0f, 0.0f, 0.0f, 10.0f,
            -1.0f, 0.0f, 0.0f, 10.0f,
            0.0f, 1.0f, 0.0f, 10.0f,
            0.0f, -1.0f, 0.0f, 10.0f,
            0.0f, 0.0f, 1.0f, 10.0f,
            0.0f, 0.0f, -1.0f, 10.0f
    };

    @Test
    @DisplayName("Sphere inside frustum center is visible")
    void testSphereInCenterIsVisible() {
        FrustumTester tester = new FrustumTester(BOX_FRUSTUM_24);
        assertTrue(tester.isVisible(0.0f, 0.0f, 0.0f, 2.0f));
    }

    @Test
    @DisplayName("Sphere far outside frustum is not visible")
    void testSphereFarOutsideIsNotVisible() {
        FrustumTester tester = new FrustumTester(BOX_FRUSTUM_24);
        assertFalse(tester.isVisible(50.0f, 0.0f, 0.0f, 2.0f));
        assertFalse(tester.isVisible(-50.0f, 0.0f, 0.0f, 2.0f));
        assertFalse(tester.isVisible(0.0f, 50.0f, 0.0f, 2.0f));
        assertFalse(tester.isVisible(0.0f, 0.0f, -50.0f, 2.0f));
    }

    @Test
    @DisplayName("Sphere partially intersecting boundary is visible")
    void testSpherePartiallyIntersectingIsVisible() {
        FrustumTester tester = new FrustumTester(BOX_FRUSTUM_24);
        // Center x=11 is outside [x<=10], but radius=3 reaches x=8 (inside)
        assertTrue(tester.isVisible(11.0f, 0.0f, 0.0f, 3.0f));
    }

    @Test
    @DisplayName("Point sphere (radius=0) exactly on boundary is visible")
    void testPointSphereOnBoundaryIsVisible() {
        FrustumTester tester = new FrustumTester(BOX_FRUSTUM_24);
        // x=10, radius=0 -> signedDistance for plane -1*x + 10 is 0 >= 0 -> visible
        assertTrue(tester.isVisible(10.0f, 0.0f, 0.0f, 0.0f));
        // x=10.001, radius=0 -> signedDistance is -0.001 < 0 -> not visible
        assertFalse(tester.isVisible(10.001f, 0.0f, 0.0f, 0.0f));
    }

    @Test
    @DisplayName("Constructor validates input array length")
    void testConstructorValidation() {
        assertThrows(NullPointerException.class, () -> new FrustumTester(null));
        assertThrows(IllegalArgumentException.class, () -> new FrustumTester(new float[12]));
    }
}
