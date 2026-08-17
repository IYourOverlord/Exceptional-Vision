package dev.ev.neoforge.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EVConfigTest {

    @Test
    @DisplayName("defaults() returns a valid config where validated() does not modify any field")
    void testDefaultsAreValid() {
        EVConfig defaults = EVConfig.defaults();
        EVConfig validated = defaults.validated();

        assertEquals(defaults, validated);
    }

    @Test
    @DisplayName("validated() clamps negative maxRenderDistanceBlocks to minimum limit without throwing")
    void testClampNegativeRenderDistance() {
        EVConfig invalid = new EVConfig(
                -1,
                0L,
                1.5f,
                8.0f,
                0.2f,
                4,
                false
        );

        EVConfig validated = invalid.validated();
        assertEquals(EVConfig.MIN_RENDER_DISTANCE_BLOCKS, validated.maxRenderDistanceBlocks());
    }

    @Test
    @DisplayName("validated() clamps workerThreadCount of 0 to minimum 1")
    void testClampZeroWorkerThreadCount() {
        EVConfig invalid = new EVConfig(
                4096,
                0L,
                1.5f,
                8.0f,
                0.2f,
                0,
                false
        );

        EVConfig validated = invalid.validated();
        assertEquals(1, validated.workerThreadCount());
    }

    @Test
    @DisplayName("validated() clamps negative screenSpaceErrorThresholdPx to minimum positive value")
    void testClampNegativeSse() {
        EVConfig invalid = new EVConfig(
                4096,
                0L,
                -5.0f,
                8.0f,
                0.2f,
                4,
                false
        );

        EVConfig validated = invalid.validated();
        assertEquals(EVConfig.MIN_SSE_PX, validated.screenSpaceErrorThresholdPx());
    }

    @Test
    @DisplayName("validated() on an already valid config is idempotent")
    void testValidatedIsIdempotent() {
        EVConfig valid = new EVConfig(
                2048,
                1024L * 1024L * 1024L,
                2.0f,
                10.0f,
                0.3f,
                4,
                true
        );

        EVConfig first = valid.validated();
        EVConfig second = first.validated();

        assertEquals(first, second);
        assertEquals(valid, first);
    }
}
