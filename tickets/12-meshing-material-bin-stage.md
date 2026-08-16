# Тикет 12 — MaterialBinStage (группировка quad'ов по материалу)

## Контекст
EV — мод дальней прорисовки для NeoForge 1.21.1. Модуль `ev-meshing`, чистый Java.
Третья стадия конвейера мешинга (после `GreedyMeshStage`, тикет 11). GPU-рендеринг эффективнее,
когда geometry, использующая один материал/текстурный атлас-регион, идёт подряд в буфере —
снижает переключения состояния и упрощает будущую сортировку по материалу для батчинга draw
call'ов (см. B.5 в PERFORMANCE_MATH.md — indirect multi-draw). Эта стадия просто
перегруппировывает уже посчитанный список quad'ов по `materialId`, без изменения самой
геометрии.

## Готовый контракт из зависимости (тикет 03, уже реализован)
```java
package dev.ev.api.meshing;
public record Quad(int faceDirection, int x, int y, int z, int width, int height, int materialId) {}
```

## Задача

### `MaterialBin`
```java
package dev.ev.meshing.stage;

import dev.ev.api.meshing.Quad;
import java.util.List;

/** All quads sharing one materialId, grouped together for contiguous GPU upload. */
public record MaterialBin(int materialId, List<Quad> quads) {}
```

### `MaterialBinStage`
```java
package dev.ev.meshing.stage;

import dev.ev.api.meshing.Quad;
import java.util.List;

/**
 * Third stage of the meshing pipeline: groups a flat list of Quads into MaterialBins
 * by materialId, preserving the original relative order of quads within each bin
 * (stable grouping — do not reorder quads that share a material, only separate
 * quads with different materials into different bins).
 *
 * Bins are returned sorted by materialId ascending, for deterministic output
 * (useful for testing and for consistent GPU buffer layout across rebuilds of
 * the same section, which helps minimize buffer diffing/upload churn — though
 * that optimization itself is out of scope for this stage).
 */
public final class MaterialBinStage {
    public List<MaterialBin> process(List<Quad> quads);
}
```

## Требования к реализации
1. Реализация — по сути `groupBy(Quad::materialId)` с гарантией стабильного порядка внутри
   группы и сортировкой групп по `materialId` на выходе. Используй `LinkedHashMap` или
   аналогичный подход, сохраняющий порядок вставки внутри группы, затем отсортируй ключи
   для финального порядка бинов (не полагайся на порядок итерации обычной `HashMap`, он не
   гарантирован).
2. Пустой входной список → пустой список бинов (не список из одного пустого бина).
3. Сложность: `O(n log n)` (из-за сортировки бинов по materialId) или `O(n)` с последующей
   сортировкой только числа уникальных материалов (обычно значительно меньше `n` quad'ов) —
   предпочти второй вариант, если несложно: группировка `O(n)`, затем сортировка списка
   ключей-бинов `O(k log k)`, где `k` = число уникальных материалов.
4. Никаких зависимостей на NeoForge/LWJGL.

## Юнит-тесты (обязательно, JUnit 5)
Создай `MaterialBinStageTest`:
1. Пустой вход → пустой выход.
2. Quad'ы одного материала → один бин, содержащий все quad'ы в исходном относительном
   порядке.
3. Quad'ы вперемешку из 3 разных материалов (не сгруппированные заранее во входном списке,
   например порядок material id в списке: 5, 2, 5, 8, 2) → 3 бина, отсортированные по
   `materialId` (2, 5, 8), каждый содержит правильные quad'ы, и порядок quad'ов внутри
   каждого бина совпадает с их относительным порядком во входном списке (для materialId=2:
   сначала quad, который был на позиции 1 во входном списке, затем quad с позиции 4 — не
   переставлены местами).
4. Один quad → один бин с одним элементом.

## Критерии приёмки
1. Файлы в `ev-meshing/src/main/java/dev/ev/meshing/stage/`: `MaterialBin.java`,
   `MaterialBinStage.java`.
2. Все юнит-тесты проходят.
3. Модуль компилируется без ошибок, без NeoForge/LWJGL зависимостей.
