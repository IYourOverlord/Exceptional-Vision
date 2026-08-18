package dev.ev.neoforge.adapter;

import dev.ev.storage.coarsegen.HeightmapSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Objects;

/**
 * {@link HeightmapSource} backed by a Minecraft {@link LevelReader}.
 * Delegates to the world's {@link Heightmap} for surface height and to the
 * actual block state for surface material (resolved via {@link BlockPalette}).
 * <p>
 * <b>Thread safety:</b> Minecraft world access is NOT thread-safe. This source
 * must only be called from the main server/client thread, or from a thread that
 * holds appropriate chunk access (e.g. via chunk tickets). For MVP, calls happen
 * on the main thread during tick-time scheduling, not from worker threads.
 */
public final class MinecraftHeightmapSource implements HeightmapSource {

    private final LevelReader level;
    private final BlockPalette palette;

    public MinecraftHeightmapSource(LevelReader level, BlockPalette palette) {
        this.level = Objects.requireNonNull(level);
        this.palette = Objects.requireNonNull(palette);
    }

    @Override
    public int surfaceHeight(int worldX, int worldZ) {
        // Heightmap.Types.WORLD_SURFACE returns the highest non-air block + 1
        // We want the highest non-air Y, so subtract 1
        int h = level.getHeight(Heightmap.Types.WORLD_SURFACE, worldX, worldZ);
        return h > level.getMinBuildHeight() ? h - 1 : level.getMinBuildHeight();
    }

    @Override
    public int surfaceMaterial(int worldX, int worldZ) {
        int surfaceY = surfaceHeight(worldX, worldZ);
        var state = level.getBlockState(new net.minecraft.core.BlockPos(worldX, surfaceY, worldZ));
        return palette.getOrAssign(state);
    }

    @Override
    public boolean isAvailable(int worldX, int worldZ) {
        // Check if the chunk containing these coordinates is loaded
        int chunkX = worldX >> 4;
        int chunkZ = worldZ >> 4;
        return level.hasChunk(chunkX, chunkZ);
    }
}
