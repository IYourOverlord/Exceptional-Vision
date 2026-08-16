package dev.ev.meshing.stage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OccupancyStageTest {

    @Test
    void emptySection_fastPathSkipsGetVoxelEntirely() {
        FakeWorldSectionHandle section = new FakeWorldSectionHandle();
        OccupancySet result = new OccupancyStage().process(section);

        assertTrue(result.isEmpty());
        assertEquals(0, section.getVoxelCallCount(), "isEmpty() fast path must avoid the getVoxel loop");
    }

    @Test
    void sparseSection_marksExactVoxelsOccupied() {
        FakeWorldSectionHandle section = new FakeWorldSectionHandle();
        section.setVoxel(0, 0, 0, 1);
        section.setVoxel(31, 31, 31, 5);
        section.setVoxel(10, 20, 5, 2);

        OccupancySet result = new OccupancyStage().process(section);

        assertTrue(result.get(0, 0, 0));
        assertTrue(result.get(31, 31, 31));
        assertTrue(result.get(10, 20, 5));
        assertEquals(3, result.popCount());
        assertFalse(result.get(1, 1, 1));
    }

    @Test
    void fullyFilledSection_popCountEqualsVoxelCount() {
        FakeWorldSectionHandle section = new FakeWorldSectionHandle();
        for (int z = 0; z < 32; z++) {
            for (int y = 0; y < 32; y++) {
                for (int x = 0; x < 32; x++) {
                    section.setVoxel(x, y, z, 1);
                }
            }
        }

        OccupancySet result = new OccupancyStage().process(section);

        assertEquals(OccupancySet.VOXEL_COUNT, result.popCount());
    }
}
