package dev.ev.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Unit tests for {@link SectionPos}: encode/decode round-trip, tree navigation, geometry. */
class SectionPosTest {

    /** Bit-field bounds used by {@link SectionPos#encode()}: 26-bit signed x/z, 8-bit signed y. */
    private static final int MAX_XZ = (1 << 25) - 1;
    private static final int MIN_XZ = -(1 << 25);
    private static final int MAX_Y = 127;
    private static final int MIN_Y = -128;

    private static final List<SectionPos> ROUND_TRIP_CASES = List.of(
        new SectionPos(0, 0, 0, 0),
        new SectionPos(0, -1, -1, -1),
        new SectionPos(3, 1234, -56, -7890),
        new SectionPos(SectionPos.MAX_LOD_LEVEL, MAX_XZ, MAX_Y, MAX_XZ),
        new SectionPos(SectionPos.MAX_LOD_LEVEL, MIN_XZ, MIN_Y, MIN_XZ),
        new SectionPos(0, MAX_XZ, MIN_Y, MIN_XZ),
        new SectionPos(6, MIN_XZ, MAX_Y, MAX_XZ),
        new SectionPos(2, -1, 0, 1)
    );

    @Test
    void encodeDecodeRoundTrip() {
        for (SectionPos original : ROUND_TRIP_CASES) {
            long encoded = original.encode();
            SectionPos decoded = SectionPos.decode(encoded);
            assertEquals(original, decoded, "round-trip mismatch for " + original);
        }
    }

    @Test
    void parentThenChildReturnsOriginalPosition() {
        SectionPos original = new SectionPos(2, 5, -3, 7);
        SectionPos parent = original.parent();

        // Determine which child index reproduces the original position under `parent`.
        int childIndex = (original.x() & 1) | ((original.y() & 1) << 1) | ((original.z() & 1) << 2);

        assertEquals(original, parent.child(childIndex));
    }

    @Test
    void parentThenChildRoundTripNegativeCoords() {
        SectionPos original = new SectionPos(1, -5, -3, -8);
        SectionPos parent = original.parent();

        int childIndex = (Math.floorMod(original.x(), 2))
            | (Math.floorMod(original.y(), 2) << 1)
            | (Math.floorMod(original.z(), 2) << 2);

        assertEquals(original, parent.child(childIndex));
    }

    @Test
    void sizeInBlocksForAllLevels() {
        int[] expected = {32, 64, 128, 256, 512, 1024, 2048};
        for (int level = 0; level <= SectionPos.MAX_LOD_LEVEL; level++) {
            assertEquals(expected[level], new SectionPos(level, 0, 0, 0).sizeInBlocks(),
                "sizeInBlocks mismatch at level " + level);
        }
    }

    @Test
    void fromBlockCoordZeroAtLevelZero() {
        assertEquals(new SectionPos(0, 0, 0, 0), SectionPos.fromBlockCoord(0, 0, 0, 0));
    }

    @Test
    void fromBlockCoordNegativeUsesFloorDiv() {
        // blockX = -1 at level 0 (section size 32) must floor to section x = -1, not 0.
        SectionPos pos = SectionPos.fromBlockCoord(0, -1, -1, -1);
        assertEquals(-1, pos.x());
        assertEquals(-1, pos.y());
        assertEquals(-1, pos.z());
    }

    @Test
    void fromBlockCoordNegativeBoundary() {
        // -32 is exactly the start of section x = -1; -33 falls into section x = -2.
        assertEquals(-1, SectionPos.fromBlockCoord(0, -32, 0, 0).x());
        assertEquals(-2, SectionPos.fromBlockCoord(0, -33, 0, 0).x());
    }

    @Test
    void constructorRejectsLevelBelowRange() {
        assertThrows(IllegalArgumentException.class, () -> new SectionPos(-1, 0, 0, 0));
    }

    @Test
    void constructorRejectsLevelAboveRange() {
        assertThrows(IllegalArgumentException.class,
            () -> new SectionPos(SectionPos.MAX_LOD_LEVEL + 1, 0, 0, 0));
    }

    @Test
    void parentThrowsAtMaxLodLevel() {
        SectionPos pos = new SectionPos(SectionPos.MAX_LOD_LEVEL, 0, 0, 0);
        assertThrows(IllegalStateException.class, pos::parent);
    }

    @Test
    void childThrowsAtLevelZero() {
        SectionPos pos = new SectionPos(0, 0, 0, 0);
        assertThrows(IllegalStateException.class, () -> pos.child(0));
    }

    @Test
    void childThrowsOnInvalidIndex() {
        SectionPos pos = new SectionPos(1, 0, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> pos.child(-1));
        assertThrows(IllegalArgumentException.class, () -> pos.child(8));
    }

    @Test
    void minBlockCoordsMatchSectionOrigin() {
        SectionPos pos = new SectionPos(1, 2, -3, 4); // sizeInBlocks = 64
        assertEquals(128L, pos.minBlockX());
        assertEquals(-192L, pos.minBlockY());
        assertEquals(256L, pos.minBlockZ());
    }
}
