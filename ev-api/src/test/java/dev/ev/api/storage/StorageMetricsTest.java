package dev.ev.api.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Unit tests for {@link StorageMetrics#hitRate()} arithmetic. */
class StorageMetricsTest {

    @Test
    void hitRateIsZeroWhenNoSamples() {
        StorageMetrics metrics = new StorageMetrics(0, 0, 0, 0, 0);
        double rate = metrics.hitRate();
        assertEquals(0.0, rate);
        assertFalse(Double.isNaN(rate), "hitRate() must not be NaN on zero total");
    }

    @Test
    void hitRateComputesRatio() {
        StorageMetrics metrics = new StorageMetrics(3, 1, 10, 5, 1024L);
        assertEquals(0.75, metrics.hitRate(), 1e-9);
    }

    @Test
    void hitRateAllHits() {
        StorageMetrics metrics = new StorageMetrics(10, 0, 10, 0, 0);
        assertEquals(1.0, metrics.hitRate(), 1e-9);
    }

    @Test
    void hitRateAllMisses() {
        StorageMetrics metrics = new StorageMetrics(0, 10, 0, 0, 0);
        assertEquals(0.0, metrics.hitRate(), 1e-9);
    }
}
