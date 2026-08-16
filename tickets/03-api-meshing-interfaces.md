# Тикет 03 — Интерфейсы мешинга (MeshBuilder, MeshletBatch)

## Контекст
EV — мод дальней прорисовки для NeoForge 1.21.1. Модуль `ev-api`, чистый Java,
без NeoForge/LWJGL. Этот тикет определяет контракт между воксельными данными секции и
GPU-совместимым геометрическим представлением ("meshlet" — небольшой пакет геометрии
фиксированного максимального размера, удобный для GPU culling по частям одной секции).

## Готовый контракт из зависимостей (тикеты 01, 02 — уже реализованы)
```java
package dev.ev.api;
public record SectionPos(int level, int x, int y, int z) { /* см. тикет 01 */ }

package dev.ev.api.storage;
public interface WorldSectionHandle {
    SectionPos position();
    int getVoxel(int localX, int localY, int localZ);
    void setVoxel(int localX, int localY, int localZ, int paletteIndex);
    boolean isEmpty();
    WorldSectionHandle retain();
    void release();
    int refCount();
}
```

## Задача
Создать в пакете `dev.ev.api.meshing` следующие типы.

### `MeshingContext`
```java
package dev.ev.api.meshing;

/**
 * Per-invocation context passed to mesh building, carrying anything that isn't
 * part of the section's own voxel data but affects mesh generation (e.g. neighbor
 * section handles for greedy-merge across boundaries, material palette lookups).
 * Implementations are provided by the caller (ev-render), consumed by
 * ev-meshing without ev-meshing needing to know where they came from.
 */
public interface MeshingContext {

    /**
     * Returns the palette index of the voxel immediately adjacent to this section's
     * boundary, needed for correct greedy-mesh quad merging across section edges.
     * faceDirection: 0=+X, 1=-X, 2=+Y, 3=-Y, 4=+Z, 5=-Z.
     * (a, b) are the two local coordinates spanning that face, each in [0, 31].
     */
    int getNeighborBoundaryVoxel(int faceDirection, int a, int b);

    /** Resolves a palette index to an opaque material identifier used for atlas binning. */
    int resolveMaterialId(int paletteIndex);
}
```

### `Quad`
```java
package dev.ev.api.meshing;

/**
 * A single axis-aligned quad produced by greedy meshing, in section-local integer
 * coordinates (0..32 inclusive, since a quad spans voxel boundaries, not voxel centers).
 */
public record Quad(
    int faceDirection, // 0=+X, 1=-X, 2=+Y, 3=-Y, 4=+Z, 5=-Z
    int x, int y, int z,     // origin corner, in local voxel-boundary units
    int width, int height,   // extent along the two axes perpendicular to faceDirection
    int materialId
) {}
```

### `MeshletBatch`
```java
package dev.ev.api.meshing;

import dev.ev.api.SectionPos;
import java.util.List;

/**
 * Output of the meshing pipeline for one section: a set of meshlets, each capped
 * at MAX_QUADS_PER_MESHLET quads, ready to be uploaded to a GPU buffer.
 * Splitting into fixed-size meshlets (rather than one variable-size mesh per section)
 * allows GPU-side per-meshlet visibility culling instead of only per-section culling.
 */
public record MeshletBatch(SectionPos section, List<Meshlet> meshlets) {

    public static final int MAX_QUADS_PER_MESHLET = 128;

    public int totalQuadCount() {
        return meshlets.stream().mapToInt(m -> m.quads().size()).sum();
    }
}
```

### `Meshlet`
```java
package dev.ev.api.meshing;

import java.util.List;

/**
 * A bounded batch of quads sharing spatial locality, with a precomputed bounding box
 * for GPU-side occlusion/frustum culling at sub-section granularity.
 */
public record Meshlet(
    List<Quad> quads,
    float boundsMinX, float boundsMinY, float boundsMinZ,
    float boundsMaxX, float boundsMaxY, float boundsMaxZ
) {
    public Meshlet {
        if (quads.size() > MeshletBatch.MAX_QUADS_PER_MESHLET) {
            throw new IllegalArgumentException(
                "Meshlet exceeds MAX_QUADS_PER_MESHLET: " + quads.size());
        }
    }
}
```

### `MeshBuilder`
```java
package dev.ev.api.meshing;

import dev.ev.api.storage.WorldSectionHandle;

/**
 * Builds renderable geometry from a section's voxel data. Pure CPU-side computation,
 * no GPU access — implementations must be safely callable from any worker thread and
 * must not retain references to the WorldSectionHandle beyond the call (caller manages
 * its lifecycle via retain/release).
 */
public interface MeshBuilder {
    MeshletBatch build(WorldSectionHandle section, MeshingContext ctx);
}
```

## Требования
1. Ровно эти сигнатуры/пакет `dev.ev.api.meshing`.
2. Полные Javadoc.
3. Никакой реализации в этом тикете. Реализация (occupancy/greedy-mesh/material-bin/
   meshlet-pack стадии) — отдельные тикеты 10-13 в модуле `ev-meshing`.
4. Не зависит от NeoForge/LWJGL.

## Критерии приёмки
1. Файлы в `ev-api/src/main/java/dev/ev/api/meshing/`: `MeshingContext.java`,
   `Quad.java`, `MeshletBatch.java`, `Meshlet.java`, `MeshBuilder.java`.
2. Модуль компилируется без ошибок.
3. Юнит-тест `MeshletTest`: конструктор `Meshlet` бросает `IllegalArgumentException`, если
   передано больше `MAX_QUADS_PER_MESHLET` quad'ов.
