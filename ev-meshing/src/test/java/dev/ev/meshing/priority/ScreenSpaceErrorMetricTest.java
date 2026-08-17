package dev.ev.meshing.priority;

import dev.ev.api.SectionPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenSpaceErrorMetricTest {

    @Test
    void computeProjectionScale_matchesManuallyComputedValueFor70DegreesAt1080p() {
        // fovY = 70 degrees = 1.221730476... radians. tan(35 degrees) ~= 0.7002075382.
        // projectionScale = 1080 / (2 * 0.7002075382) ~= 771.1999.
        float fovYRadians = (float) Math.toRadians(70.0);

        float scale = ScreenSpaceErrorMetric.computeProjectionScale(fovYRadians, 1080);

        assertEquals(771.1999f, scale, 0.5f);
    }

    @Test
    void projectedErrorPx_isInverselyProportionalToDistance() {
        float voxelSize = 4.0f;
        float projectionScale = 800.0f;
        float distance = 100.0f;

        float errorAtDistance = ScreenSpaceErrorMetric.projectedErrorPx(voxelSize, distance, projectionScale);
        float errorAtDoubleDistance =
            ScreenSpaceErrorMetric.projectedErrorPx(voxelSize, distance * 2, projectionScale);

        assertEquals(errorAtDistance / 2.0f, errorAtDoubleDistance, 0.01f);
    }

    @Test
    void projectedErrorPx_atZeroDistance_doesNotThrowOrProduceNanOrInfinity() {
        float error = ScreenSpaceErrorMetric.projectedErrorPx(1.0f, 0.0f, 800.0f);

        assertFalse(Float.isNaN(error));
        assertFalse(Float.isInfinite(error));
    }

    @Test
    void selectLodLevel_atVeryLargeDistance_selectsCoarsestOrNearCoarsestLevel() {
        float projectionScale = 771.2f;
        float errorThresholdPx = 1.0f;

        int level = ScreenSpaceErrorMetric.selectLodLevel(10_000_000f, projectionScale, errorThresholdPx);

        assertEquals(SectionPos.MAX_LOD_LEVEL, level);
    }

    @Test
    void selectLodLevel_atVerySmallDistance_selectsFinestLevel() {
        float projectionScale = 771.2f;
        float errorThresholdPx = 50.0f;

        int level = ScreenSpaceErrorMetric.selectLodLevel(1.0f, projectionScale, errorThresholdPx);

        assertEquals(0, level);
    }

    @Test
    void selectLodLevel_isMonotonicallyNonDecreasingAsDistanceIncreases() {
        float projectionScale = 771.2f;
        float errorThresholdPx = 5.0f;
        float[] distances = {1f, 10f, 50f, 100f, 500f, 2_000f, 10_000f, 100_000f, 1_000_000f};

        int previousLevel = -1;
        for (float distance : distances) {
            int level = ScreenSpaceErrorMetric.selectLodLevel(distance, projectionScale, errorThresholdPx);
            assertTrue(level >= previousLevel,
                "selected level must not decrease as distance grows: distance=" + distance
                    + " level=" + level + " previousLevel=" + previousLevel);
            previousLevel = level;
        }
    }
}
