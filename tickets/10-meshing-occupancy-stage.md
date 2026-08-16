# Тикет 10 — OccupancyStage (маска занятых вокселей)

## Контекст
EV — мод дальней прорисовки для NeoForge 1.21.1. Модуль `ev-meshing`, чистый Java
(без NeoForge/LWJGL). Первая стадия конвейера мешинга (см. INDEX.md, тикеты 10-13 образуют
пайплайн: Occupancy → GreedyMesh → MaterialBin → MeshletPack). Эта стадия строит компактную
битовую маску "какие вокселы непустые" (`paletteIndex != 0`) из полного массива секции —
последующая greedy-mesh стадия использует эту маску для быстрого определения границ граней,
не трогая палитровые индексы напрямую на этом этапе.

## Готовый контракт из зависимостей (тикеты 02, 03 — уже реализованы)
```java
package dev.ev.api.storage;
public interface WorldSectionHandle {
    dev.ev.api.SectionPos position();
    int getVoxel(int localX, int localY, int localZ); // 0 = air/empty
}
```

## Задача

### `OccupancySet`
```java
package dev.ev.meshing.stage;

/**
 * Compact bitset over a 32x32x32 voxel grid, one bit per voxel (1 = occupied,
 * i.e. paletteIndex != 0). Backed by a long[] of 32768/64 = 512 longs.
 * Provides O(1) occupancy queries and face-visibility queries used by greedy meshing.
 */
public final class OccupancySet {

    public static final int GRID_SIZE = 32;
    public static final int VOXEL_COUNT = GRID_SIZE * GRID_SIZE * GRID_SIZE;

    public OccupancySet() { /* backing long[512], all zero */ }

    public boolean get(int x, int y, int z);
    public void set(int x, int y, int z, boolean occupied);

    /** Flat index helper: x + y*32 + z*32*32, bounds NOT checked (hot path — callers must pre-validate). */
    public static int flatIndex(int x, int y, int z);

    /** True if every bit is 0 (fully empty section — caller can skip meshing entirely). */
    public boolean isEmpty();

    /** Number of set bits — useful for metrics/sanity checks, not required on the hot path. */
    public int popCount();
}
```

### `OccupancyStage`
```java
package dev.ev.meshing.stage;

import dev.ev.api.storage.WorldSectionHandle;

/**
 * First stage of the meshing pipeline: builds an OccupancySet from a section's raw
 * voxel data. Pure function, no GPU, safe to call from any worker thread.
 */
public final class OccupancyStage {
    public OccupancySet process(WorldSectionHandle section);
}
```

## Требования к реализации
1. `OccupancySet` backing store — `long[512]` (32768 бит / 64 бит на long). Используй
   битовые операции (`|=`, `&`, сдвиги) напрямую, не `java.util.BitSet` (чтобы контролировать
   layout и избежать object overhead — это горячий путь, вызывается на каждую секцию,
   потенциально сотни тысяч раз за холодный старт).
2. `flatIndex` — согласуй порядок осей с тем, что уже используется в
   `WorldSectionHandle.getVoxel(localX, localY, localZ)` и в `PaletteCodec` (тикет 06,
   который использует flat index `x + y*32 + z*32*32` — сохрани тот же порядок здесь для
   консистентности между модулями, даже если это два независимых класса).
3. `OccupancyStage.process` должен использовать
   `WorldSectionHandle.isEmpty()` как быстрый путь: если true — вернуть пустой `OccupancySet`
   без обхода всех 32768 вокселей (`getVoxel` в цикле).
4. Никаких зависимостей на NeoForge/LWJGL.

## Юнит-тесты (обязательно, JUnit 5)
Тестовая реализация `WorldSectionHandle` в тестовом коде (in-memory `int[32768]`-backed,
простая заглушка — не входит в объём предыдущих тикетов, создай минимальную сама для этого
теста; если считаешь, что такая тестовая заглушка будет нужна другим тикетам meshing-слоя
тоже — можешь оформить её как переиспользуемый класс в `ev-test`, но это не обязательно,
допустимо продублировать небольшую заглушку в каждом тестовом файле).

Создай `OccupancySetTest`:
1. `get`/`set` корректны для произвольных координат, включая углы (0,0,0) и (31,31,31).
2. `isEmpty()` true для нового/незаполненного набора, false после любого `set(..., true)`.
3. `popCount()` корректен после нескольких `set` вызовов.
4. `set(x,y,z,false)` после `set(x,y,z,true)` корректно очищает бит (проверка, что не только
   установка, но и снятие бита работает).

Создай `OccupancyStageTest`:
1. Полностью пустая секция (`isEmpty() == true` на `WorldSectionHandle`) → результат
   `OccupancySet.isEmpty() == true`, и тестовая заглушка `getVoxel` не должна быть вызвана
   ни разу (проверь через заглушку, считающую вызовы — это гарантирует, что fast-path из
   требования 3 действительно используется, а не полный обход).
2. Секция с несколькими непустыми вокселями в известных позициях → `OccupancySet` содержит
   ровно те же позиции как occupied, остальные — не occupied.
3. Полностью заполненная секция (все 32768 вокселей ненулевые) → `popCount() == 32768`.

## Критерии приёмки
1. Файлы в `ev-meshing/src/main/java/dev/ev/meshing/stage/`:
   `OccupancySet.java`, `OccupancyStage.java`.
2. Все юнит-тесты проходят.
3. Модуль компилируется без ошибок, без NeoForge/LWJGL зависимостей.
