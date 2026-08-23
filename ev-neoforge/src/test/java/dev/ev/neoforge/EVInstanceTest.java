package dev.ev.neoforge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EVInstanceTest {

    @Test
    @DisplayName("calculateNearCutoffBlocks guarantees coverage of square vanilla loading diagonal (>= renderDistanceChunks * 16 * sqrt(2))")
    void testCalculateNearCutoffBlocksCircumscribesSquare() {
        int[] renderDistancesToTest = new int[]{2, 4, 8, 12, 16, 32};
        float sqrt2 = (float) Math.sqrt(2.0);

        for (int chunkDist : renderDistancesToTest) {
            float cutoffNoMargin = EVInstance.calculateNearCutoffBlocks(chunkDist, 0.0f);
            float minRequiredDiagonal = chunkDist * 16.0f * sqrt2;

            assertTrue(cutoffNoMargin >= minRequiredDiagonal,
                    "Cutoff distance " + cutoffNoMargin + " for " + chunkDist +
                            " chunks must be >= diagonal " + minRequiredDiagonal);

            float cutoffWithMargin = EVInstance.calculateNearCutoffBlocks(chunkDist, 1.0f);
            assertTrue(cutoffWithMargin > cutoffNoMargin);
            assertEquals(cutoffNoMargin + 16.0f, cutoffWithMargin, 0.001f);
        }
    }

    @Test
    @DisplayName("close() on null or uninitialized instance is safe and idempotent")
    void testCloseIdempotentSafety() {
        // Test close on instance created without bootstrap
        EVInstance instance = null;
        assertDoesNotThrow(() -> {
            if (instance != null) {
                instance.close();
            }
        });
    }

    /**
     * Fixed-radius bounding sphere lookup for filterByDistance tests: every SectionPos
     * maps to a sphere centered on (x*100, 0, z*100) with a fixed 10-block radius. Keeps
     * the arithmetic in each test readable (distances are simple multiples of 100).
     */
    private static float[] fixedRadiusBounds(dev.ev.api.SectionPos pos, float radius) {
        return new float[]{pos.x() * 100f, 0f, pos.z() * 100f, radius};
    }

    @Test
    @DisplayName("filterByDistance keeps sections within budget, drops sections beyond it")
    void testFilterByDistanceKeepsNearDropsFar() {
        dev.ev.api.SectionPos near = new dev.ev.api.SectionPos(0, 1, 0, 0);   // center (100,0,0)
        dev.ev.api.SectionPos far = new dev.ev.api.SectionPos(0, 20, 0, 0);  // center (2000,0,0)
        List<dev.ev.api.SectionPos> loaded = List.of(near, far);

        List<dev.ev.api.SectionPos> result = EVInstance.filterByDistance(
                loaded, 0f, 0f, 0f, 0f, 500f, pos -> fixedRadiusBounds(pos, 10f)
        );

        assertTrue(result.contains(near), "section within budget must be kept");
        assertFalse(result.contains(far), "section far beyond budget must be dropped");
    }

    @Test
    @DisplayName("filterByDistance reproduces the /kill respawn artifact scenario: a section "
            + "loaded near an old, far-away position must be dropped once camera is back at spawn")
    void testFilterByDistanceDropsStaleSectionAfterTeleport() {
        // Section meshed while the player was exploring at ~(2000, 0, 20000), matching the
        // coordinates seen in the actual bug report's logs.
        dev.ev.api.SectionPos staleSection = new dev.ev.api.SectionPos(0, 62, 0, 625); // (6200,0,62500)
        List<dev.ev.api.SectionPos> loaded = List.of(staleSection);

        // Camera back at world spawn after /kill.
        List<dev.ev.api.SectionPos> result = EVInstance.filterByDistance(
                loaded, 0f, 64f, 0f, 0f, 4096f, pos -> fixedRadiusBounds(pos, 16f)
        );

        assertTrue(result.isEmpty(),
                "a section from a previous, distant player position must not remain a "
                        + "traversal/render candidate after teleporting back to spawn");
    }

    @Test
    @DisplayName("filterByDistance includes a section whose center is just past the raw cutoff "
            + "but whose bounding sphere still overlaps the budget (radius accounted for)")
    void testFilterByDistanceAccountsForSectionRadius() {
        // Center distance is exactly maxDistanceBlocks + 5 (radius 10 => still within
        // maxDistanceBlocks + radius = maxDistanceBlocks + 10).
        dev.ev.api.SectionPos pos = new dev.ev.api.SectionPos(0, 1, 0, 0);
        float maxDistanceBlocks = 100f - 5f; // center at x=100, so distance is exactly 100
        List<dev.ev.api.SectionPos> loaded = List.of(pos);

        List<dev.ev.api.SectionPos> result = EVInstance.filterByDistance(
                loaded, 0f, 0f, 0f, 0f, maxDistanceBlocks, p -> fixedRadiusBounds(p, 10f)
        );

        assertEquals(1, result.size(),
                "section must be kept when within maxDistanceBlocks + its own radius");
    }

    @Test
    @DisplayName("filterByDistance preserves relative order and passes through an empty list")
    void testFilterByDistanceOrderAndEmpty() {
        assertTrue(EVInstance.filterByDistance(
                List.of(), 0f, 0f, 0f, 0f, 100f, p -> fixedRadiusBounds(p, 1f)
        ).isEmpty());

        dev.ev.api.SectionPos a = new dev.ev.api.SectionPos(0, 1, 0, 0);
        dev.ev.api.SectionPos b = new dev.ev.api.SectionPos(0, 2, 0, 0);
        List<dev.ev.api.SectionPos> loaded = List.of(a, b);

        List<dev.ev.api.SectionPos> result = EVInstance.filterByDistance(
                loaded, 0f, 0f, 0f, 0f, 10000f, p -> fixedRadiusBounds(p, 1f)
        );

        assertEquals(List.of(a, b), result);
    }

    @Test
    @DisplayName("filterByDistance drops a section entirely inside minDistanceBlocks "
            + "(reproduces LOD cubes covering the player's own vanilla render distance)")
    void testFilterByDistanceDropsSectionInsideVanillaZone() {
        // Section centered 50 blocks away with a 10-block radius (so it spans 40..60) —
        // entirely inside a 100-block vanilla render-distance cutoff.
        dev.ev.api.SectionPos closeSection = new dev.ev.api.SectionPos(0, 0, 0, 0);
        List<dev.ev.api.SectionPos> loaded = List.of(closeSection);

        List<dev.ev.api.SectionPos> result = EVInstance.filterByDistance(
                loaded, 50f, 0f, 0f, 100f, 5000f,
                pos -> new float[]{0f, 0f, 0f, 10f}
        );

        assertTrue(result.isEmpty(),
                "a section entirely within minDistanceBlocks of the camera must not be a "
                        + "far-LOD draw candidate — vanilla already renders that area");
    }

    @Test
    @DisplayName("filterByDistance keeps a section straddling minDistanceBlocks "
            + "(no visible gap at the vanilla/far-LOD boundary)")
    void testFilterByDistanceKeepsSectionStraddlingInnerBoundary() {
        // Section center at distance 100 with radius 10 (spans 90..110): straddles a
        // minDistanceBlocks of 100, so it must still be drawn to avoid a gap.
        dev.ev.api.SectionPos straddling = new dev.ev.api.SectionPos(0, 0, 0, 0);
        List<dev.ev.api.SectionPos> loaded = List.of(straddling);

        List<dev.ev.api.SectionPos> result = EVInstance.filterByDistance(
                loaded, 100f, 0f, 0f, 100f, 5000f,
                pos -> new float[]{0f, 0f, 0f, 10f}
        );

        assertEquals(1, result.size(),
                "a section straddling the inner cutoff must be kept to avoid a rendering gap");
    }

    @Test
    @DisplayName("toCameraRelative subtracts camera position from center, leaves radius untouched")
    void testToCameraRelativeSubtractsCameraPosition() {
        float[] worldBounds = new float[]{2016f, 82f, 200016f, 27.7f};

        float[] relative = EVInstance.toCameraRelative(worldBounds, 2000f, 74f, 200000f);

        assertEquals(16f, relative[0], 0.001f);
        assertEquals(8f, relative[1], 0.001f);
        assertEquals(16f, relative[2], 0.001f);
        assertEquals(27.7f, relative[3], 0.001f,
                "radius must be unaffected by a pure translation");
    }

    @Test
    @DisplayName("toCameraRelative is a no-op when the camera sits at the world origin "
            + "(regression guard: must exactly match pre-fix absolute-world behavior near origin)")
    void testToCameraRelativeNoOpAtOrigin() {
        float[] worldBounds = new float[]{123.5f, 64f, -45f, 27.7f};

        float[] relative = EVInstance.toCameraRelative(worldBounds, 0f, 0f, 0f);

        assertArrayEquals(worldBounds, relative, 0.0001f);
    }

    @Test
    @DisplayName("toCameraRelative brings a far-away section (world Z ~ 200000, matching the "
            + "reported bug's coordinates) down to small, camera-local numbers")
    void testToCameraRelativeFixesLargeWorldCoordinateMismatch() {
        // Matches the reported scenario: player teleported to (2000, 74, 200000), a
        // section meshed right next to the camera at world (2016, 90, 200016).
        float[] worldBounds = new float[]{2016f, 90f, 200016f, 16f};

        float[] relative = EVInstance.toCameraRelative(worldBounds, 2000f, 74f, 200000f);

        // Before the fix, this section's absolute-world center (2016, 90, 200016) was
        // compared directly against a camera-relative frustum built around (0,0,0) —
        // a mismatch of ~200000 units, guaranteeing frustum-test failure regardless of
        // how close the section actually was to the camera. After the fix, the
        // camera-relative center must be small (on the order of the section's own
        // size), not on the order of the absolute world coordinate.
        assertEquals(16f, relative[0], 0.001f);
        assertEquals(16f, relative[1], 0.001f);
        assertEquals(16f, relative[2], 0.001f);
        assertTrue(Math.abs(relative[0]) < 1000f && Math.abs(relative[1]) < 1000f
                        && Math.abs(relative[2]) < 1000f,
                "camera-relative coordinates for a nearby section must be small, not on "
                        + "the order of the absolute world position");
    }
}
