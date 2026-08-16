package dev.ev.api.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Unit tests for {@link ImportStageStatus#completionFraction()} arithmetic. */
class ImportStageStatusTest {

    @Test
    void completionFractionIsZeroWhenTotalKnownIsZero() {
        ImportStageStatus status = new ImportStageStatus(0, 0, 0, 0, 0);
        double fraction = status.completionFraction();
        assertEquals(0.0, fraction);
        assertFalse(Double.isNaN(fraction), "completionFraction() must not be NaN when totalKnown is 0");
    }

    @Test
    void completionFractionComputesRatio() {
        ImportStageStatus status = new ImportStageStatus(10, 2, 5, 50, 200);
        assertEquals(0.25, status.completionFraction(), 1e-9);
    }

    @Test
    void completionFractionForEmptyStatus() {
        assertEquals(0.0, ImportStageStatus.empty().completionFraction());
    }

    @Test
    void completionFractionAtFullCompletion() {
        ImportStageStatus status = new ImportStageStatus(0, 0, 0, 100, 100);
        assertEquals(1.0, status.completionFraction(), 1e-9);
    }
}
