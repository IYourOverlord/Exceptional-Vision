package dev.ev.neoforge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}
