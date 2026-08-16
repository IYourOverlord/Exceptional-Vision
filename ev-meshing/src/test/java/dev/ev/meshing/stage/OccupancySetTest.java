package dev.ev.meshing.stage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OccupancySetTest {

    @Test
    void getSetCorrectForArbitraryAndCornerCoordinates() {
        OccupancySet set = new OccupancySet();
        assertFalse(set.get(0, 0, 0));
        assertFalse(set.get(31, 31, 31));

        set.set(0, 0, 0, true);
        set.set(31, 31, 31, true);
        set.set(15, 3, 20, true);

        assertTrue(set.get(0, 0, 0));
        assertTrue(set.get(31, 31, 31));
        assertTrue(set.get(15, 3, 20));
        assertFalse(set.get(1, 0, 0));
        assertFalse(set.get(30, 31, 31));
    }

    @Test
    void isEmptyTrueInitiallyFalseAfterSet() {
        OccupancySet set = new OccupancySet();
        assertTrue(set.isEmpty());

        set.set(10, 10, 10, true);
        assertFalse(set.isEmpty());
    }

    @Test
    void popCountCorrectAfterMultipleSets() {
        OccupancySet set = new OccupancySet();
        assertEquals(0, set.popCount());

        set.set(0, 0, 0, true);
        set.set(1, 0, 0, true);
        set.set(2, 0, 0, true);
        assertEquals(3, set.popCount());

        set.set(0, 0, 0, true); // idempotent, still occupied
        assertEquals(3, set.popCount());
    }

    @Test
    void clearingBitWorks() {
        OccupancySet set = new OccupancySet();
        set.set(5, 5, 5, true);
        assertTrue(set.get(5, 5, 5));

        set.set(5, 5, 5, false);
        assertFalse(set.get(5, 5, 5));
        assertTrue(set.isEmpty());
    }
}
