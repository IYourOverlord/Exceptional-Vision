package dev.ev.storage.coarsegen;

/**
 * Abstract source of surface height + dominant surface material, queried per
 * block column (world X/Z). Implemented elsewhere by adapting Minecraft's
 * Heightmap/ChunkAccess/BiomeSource to this interface — this package must not
 * depend on Minecraft/NeoForge classes.
 */
public interface HeightmapSource {

    /** Highest non-air block Y coordinate at this world-space column, or Integer.MIN_VALUE if unknown/unloaded. */
    int surfaceHeight(int worldX, int worldZ);

    /** Palette index of the dominant surface material (e.g. grass, sand, stone) at this column. */
    int surfaceMaterial(int worldX, int worldZ);

    /** True if data for this column is available (chunk generated/loaded); false = treat as unknown, caller decides fallback. */
    boolean isAvailable(int worldX, int worldZ);
}
