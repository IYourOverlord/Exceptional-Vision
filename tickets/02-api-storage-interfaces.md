# Тикет 02 — Интерфейсы хранилища (VoxelStorage, WorldSectionHandle)

## Контекст
EV — мод дальней прорисовки для NeoForge 1.21.1. Модуль `ev-api` — чистый Java,
без зависимостей на NeoForge/LWJGL. Мир хранится в персистентном воксельном LOD-хранилище.
Этот тикет определяет ПУБЛИЧНЫЙ КОНТРАКТ доступа к хранилищу — реализация будет в отдельном
тикете (модуль `ev-storage`), здесь только интерфейсы.

## Готовый контракт из зависимости (тикет 01, уже реализован)
```java
package dev.ev.api;
public record SectionPos(int level, int x, int y, int z) {
    public static final int MAX_LOD_LEVEL = 6;
    public long encode();
    public static SectionPos decode(long id);
    // ... см. полный контракт в тикете 01, здесь используются только encode()/decode() и сам record
}
```

## Задача
Создать в пакете `dev.ev.api.storage` следующие типы.

### `VoxelStorage`
```java
package dev.ev.api.storage;

import dev.ev.api.SectionPos;
import java.io.Closeable;

/**
 * Persistent storage for voxelized world sections across all LOD levels.
 * A single VoxelStorage instance corresponds to one Minecraft world/dimension save.
 * Thread-safe: acquire/release may be called concurrently from multiple worker threads.
 */
public interface VoxelStorage extends Closeable {

    /** Schema version of the on-disk format currently in use (see SchemaVersion). */
    int schemaVersion();

    /**
     * Acquires a handle to the section at the given position, loading it from disk
     * or creating an empty one if it does not exist yet. Increments the handle's
     * reference count; caller MUST call {@link WorldSectionHandle#release()} when done.
     */
    WorldSectionHandle acquire(SectionPos pos);

    /**
     * Like {@link #acquire(SectionPos)} but returns null instead of creating a new
     * section if none exists on disk or in cache.
     */
    WorldSectionHandle acquireIfExists(SectionPos pos);

    /** Marks a section as modified, scheduling it for persistence. Does not block. */
    void markDirty(WorldSectionHandle handle, DirtyFlags flags);

    /**
     * Forces synchronous persistence of a section to disk.
     * @return true if the save succeeded.
     */
    boolean save(WorldSectionHandle handle);

    /** Snapshot of storage-level metrics (cache hit rate, active section count, etc). */
    StorageMetrics metrics();

    @Override
    void close();
}
```

### `DirtyFlags`
```java
package dev.ev.api.storage;

/** Bit flags describing why a section was marked dirty. */
public final class DirtyFlags {
    public static final int BLOCK_CHANGED = 1;
    public static final int CHILD_EXISTENCE_CHANGED = 2;
    public static final int SKIP_PERSIST = 4; // e.g. transient debug/preview data

    public static final int DEFAULT = BLOCK_CHANGED | CHILD_EXISTENCE_CHANGED;

    private DirtyFlags() {}

    public static boolean has(int flags, int flag) {
        return (flags & flag) != 0;
    }
}
```

### `WorldSectionHandle`
```java
package dev.ev.api.storage;

import dev.ev.api.SectionPos;

/**
 * A reference-counted handle to voxel data for one section. Obtained via
 * {@link VoxelStorage#acquire}, must be released via {@link #release()} exactly once
 * per acquire call (standard retain/release discipline — do not release more times
 * than acquired, do not use after final release).
 */
public interface WorldSectionHandle {

    SectionPos position();

    /**
     * Reads a single voxel's palette index at local coordinates within this section
     * (each coordinate in range [0, 31], since every section is logically a 32^3 grid
     * regardless of LOD level — the LOD level only changes what world-space size that
     * grid covers, via SectionPos.sizeInBlocks()).
     */
    int getVoxel(int localX, int localY, int localZ);

    /** Writes a single voxel's palette index. Does not automatically mark dirty. */
    void setVoxel(int localX, int localY, int localZ, int paletteIndex);

    /** True if every voxel in this section is palette index 0 (air/empty). */
    boolean isEmpty();

    /** Increments the reference count. Returns this for chaining. */
    WorldSectionHandle retain();

    /** Decrements the reference count. When it reaches zero the section may be evicted. */
    void release();

    /** Current reference count, for debugging/assertions only. */
    int refCount();
}
```

### `StorageMetrics`
```java
package dev.ev.api.storage;

/** Immutable snapshot of storage subsystem metrics at a point in time. */
public record StorageMetrics(
    long cacheHits,
    long cacheMisses,
    int activeSectionCount,
    int secondaryCacheSize,
    long bytesOnDisk
) {
    public double hitRate() {
        long total = cacheHits + cacheMisses;
        return total == 0 ? 0.0 : (double) cacheHits / total;
    }
}
```

## Требования
1. Ровно эти сигнатуры, этот пакет `dev.ev.api.storage` — контракт для тикетов 6-9
   (реализация в `ev-storage`) и всех, кто читает данные секций (meshing).
2. Полные Javadoc-комментарии (уже даны выше, сохрани/дополни).
3. Никакой реализации в этом тикете — только интерфейсы/record'ы/константы. Реализация в
   отдельных тикетах модуля `ev-storage`.
4. Не добавляй зависимость на NeoForge/LWJGL/Minecraft-классы — этот модуль их не видит.

## Критерии приёмки
1. Файлы созданы в `ev-api/src/main/java/dev/ev/api/storage/`:
   `VoxelStorage.java`, `DirtyFlags.java`, `WorldSectionHandle.java`, `StorageMetrics.java`.
2. Модуль `ev-api` компилируется без ошибок.
3. Никакой логики реализации — только контракты. Если тесты нужны для record'ов
   (например, `StorageMetrics.hitRate()`), допустимо добавить простой юнит-тест на
   арифметику `hitRate()` (деление на ноль → 0.0, не NaN/exception).
