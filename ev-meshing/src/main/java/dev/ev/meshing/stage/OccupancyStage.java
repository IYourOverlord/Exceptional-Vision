package dev.ev.meshing.stage;

import dev.ev.api.storage.WorldSectionHandle;

/**
 * First stage of the meshing pipeline: builds an OccupancySet from a section's raw
 * voxel data. Pure function, no GPU, safe to call from any worker thread.
 */
public final class OccupancyStage {

    public OccupancySet process(WorldSectionHandle section) {
        OccupancySet occupancy = new OccupancySet();

        if (section.isEmpty()) {
            return occupancy;
        }

        for (int z = 0; z < OccupancySet.GRID_SIZE; z++) {
            for (int y = 0; y < OccupancySet.GRID_SIZE; y++) {
                for (int x = 0; x < OccupancySet.GRID_SIZE; x++) {
                    if (section.getVoxel(x, y, z) != 0) {
                        occupancy.set(x, y, z, true);
                    }
                }
            }
        }

        return occupancy;
    }
}
