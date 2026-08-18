package dev.ev.neoforge.adapter;

import dev.ev.api.SectionPos;
import dev.ev.api.meshing.MeshingContext;
import dev.ev.api.storage.WorldSectionHandle;
import dev.ev.storage.cache.SectionCache;

import java.util.Objects;

/**
 * {@link MeshingContext} backed by real Minecraft/EV data: resolves neighbor
 * boundary voxels by acquiring adjacent sections from {@link SectionCache},
 * and resolves material IDs through {@link BlockPalette}.
 * <p>
 * Each instance is scoped to one section being meshed — the neighbor lookups
 * are relative to that section's position.
 */
public final class MinecraftMeshingContext implements MeshingContext {

    // faceDirection -> offset on the normal axis: +X, -X, +Y, -Y, +Z, -Z
    private static final int[][] FACE_OFFSETS = {
        { 1,  0,  0}, // +X
        {-1,  0,  0}, // -X
        { 0,  1,  0}, // +Y
        { 0, -1,  0}, // -Y
        { 0,  0,  1}, // +Z
        { 0,  0, -1}, // -Z
    };

    private final SectionPos sectionPos;
    private final SectionCache sectionCache;
    private final BlockPalette palette;

    /**
     * @param sectionPos   position of the section currently being meshed
     * @param sectionCache used to acquire neighbor sections
     * @param palette      global BlockState → paletteIndex mapping
     */
    public MinecraftMeshingContext(SectionPos sectionPos,
                                    SectionCache sectionCache,
                                    BlockPalette palette) {
        this.sectionPos = Objects.requireNonNull(sectionPos);
        this.sectionCache = Objects.requireNonNull(sectionCache);
        this.palette = Objects.requireNonNull(palette);
    }

    @Override
    public int getNeighborBoundaryVoxel(int faceDirection, int a, int b) {
        int[] offset = FACE_OFFSETS[faceDirection];
        SectionPos neighborPos = new SectionPos(
            sectionPos.level(),
            sectionPos.x() + offset[0],
            sectionPos.y() + offset[1],
            sectionPos.z() + offset[2]
        );

        WorldSectionHandle neighbor = sectionCache.acquire(neighborPos.encode(), true);
        if (neighbor == null) {
            return 0; // Нет соседней секции → считаем air (лицо видимо)
        }

        try {
            // Вычисляем локальные координаты в соседней секции
            // Для faceDirection +X: мы на границе x=31 текущей секции, нужен x=0 соседней
            // a = width axis coord, b = height axis coord
            // Mapping зависит от faceDirection — используем ту же конвенцию, что GreedyMeshStage
            int localX, localY, localZ;
            switch (faceDirection) {
                case 0 -> { localX = 0;  localY = a; localZ = b; } // +X: сосед начинается с x=0
                case 1 -> { localX = 31; localY = a; localZ = b; } // -X: сосед заканчивается x=31
                case 2 -> { localX = b;  localY = 0; localZ = a; } // +Y: сосед начинается y=0
                case 3 -> { localX = b;  localY = 31; localZ = a; } // -Y: сосед заканчивается y=31
                case 4 -> { localX = a;  localY = b; localZ = 0; } // +Z: сосед начинается z=0
                case 5 -> { localX = a;  localY = b; localZ = 31; } // -Z: сосед заканчивается z=31
                default -> { return 0; }
            }

            return neighbor.getVoxel(localX, localY, localZ);
        } finally {
            neighbor.release();
        }
    }

    @Override
    public int resolveMaterialId(int paletteIndex) {
        // В MVP material ID == palette index (нет текстурного атласа)
        return paletteIndex;
    }
}
