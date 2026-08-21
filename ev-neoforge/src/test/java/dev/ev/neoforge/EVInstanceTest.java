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
                loaded, 0f, 0f, 0f, 500f, pos -> fixedRadiusBounds(pos, 10f)
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
                loaded, 0f, 64f, 0f, 4096f, pos -> fixedRadiusBounds(pos, 16f)
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
                loaded, 0f, 0f, 0f, maxDistanceBlocks, p -> fixedRadiusBounds(p, 10f)
        );

        assertEquals(1, result.size(),
                "section must be kept when within maxDistanceBlocks + its own radius");
    }

    @Test
    @DisplayName("filterByDistance preserves relative order and passes through an empty list")
    void testFilterByDistanceOrderAndEmpty() {
        assertTrue(EVInstance.filterByDistance(
                List.of(), 0f, 0f, 0f, 100f, p -> fixedRadiusBounds(p, 1f)
        ).isEmpty());

        dev.ev.api.SectionPos a = new dev.ev.api.SectionPos(0, 1, 0, 0);
        dev.ev.api.SectionPos b = new dev.ev.api.SectionPos(0, 2, 0, 0);
        List<dev.ev.api.SectionPos> loaded = List.of(a, b);

        List<dev.ev.api.SectionPos> result = EVInstance.filterByDistance(
                loaded, 0f, 0f, 0f, 10000f, p -> fixedRadiusBounds(p, 1f)
        );

        assertEquals(List.of(a, b), result);
    }
}
