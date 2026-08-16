# Тикет 13 — MeshletPackStage (упаковка в GPU meshlet-формат)

## Контекст
EV — мод дальней прорисовки для NeoForge 1.21.1. Модуль `ev-meshing`, чистый Java.
Финальная (четвёртая) стадия конвейера мешинга (после `MaterialBinStage`, тикет 12).
Превращает сгруппированные по материалу quad'ы в список `Meshlet` — пакетов ограниченного
размера (`MAX_QUADS_PER_MESHLET = 128`, см. тикет 03) с посчитанным bounding box'ом каждого,
готовых к GPU-side per-meshlet culling (см. B.1/B.4 в PERFORMANCE_MATH.md — traversal
работает с узлами/meshlet'ами, не с отдельными quad'ами, для эффективности).

**Важная зависимость**: интерпретация полей `Quad.width`/`Quad.height` (какая ось есть
which) определена в тикете 11 (`GreedyMeshStage`, requirement 4a) — этот тикет ОБЯЗАН
использовать ту же конвенцию, не изобретать свою, иначе bounding box будет неверным для
корректно построенной геометрии.

## Готовый контракт из зависимостей (тикеты 01, 03, 12 — уже реализованы)
```java
package dev.ev.api;
public record SectionPos(int level, int x, int y, int z) {}

package dev.ev.api.meshing;
public record Quad(int faceDirection, int x, int y, int z, int width, int height, int materialId) {}
public record Meshlet(
    List<Quad> quads,
    float boundsMinX, float boundsMinY, float boundsMinZ,
    float boundsMaxX, float boundsMaxY, float boundsMaxZ
) {
    // constructor throws IllegalArgumentException if quads.size() > MeshletBatch.MAX_QUADS_PER_MESHLET
}
public record MeshletBatch(SectionPos section, List<Meshlet> meshlets) {
    public static final int MAX_QUADS_PER_MESHLET = 128;
    public int totalQuadCount();
}

package dev.ev.meshing.stage;
public record MaterialBin(int materialId, List<Quad> quads) {}
```

## Задача

### `MeshletPackStage`
```java
package dev.ev.meshing.stage;

import dev.ev.api.SectionPos;
import dev.ev.api.meshing.Meshlet;
import dev.ev.api.meshing.MeshletBatch;
import java.util.List;

/**
 * Final stage of the meshing pipeline: packs MaterialBins into MeshletBatch, splitting
 * any bin whose quad count exceeds MeshletBatch.MAX_QUADS_PER_MESHLET into multiple
 * Meshlets, and computing each Meshlet's bounding box from its quads' voxel-boundary
 * coordinates.
 *
 * Splitting strategy: quads within one material bin are consumed in order, filling
 * each Meshlet up to MAX_QUADS_PER_MESHLET before starting a new one — no attempt at
 * spatial clustering beyond the order already produced by greedy meshing (which tends
 * to be reasonably spatially coherent since it processes slices in order). A future
 * ticket may improve this with explicit spatial clustering; out of scope here.
 */
public final class MeshletPackStage {
    public MeshletBatch process(SectionPos section, List<MaterialBin> bins);
}
```

## Требования к реализации
1. Для каждого `MaterialBin`, если `bin.quads().size() <= MAX_QUADS_PER_MESHLET` — один
   `Meshlet` на весь бин. Если больше — раздели на `ceil(size / MAX_QUADS_PER_MESHLET)`
   meshlet'ов, каждый (кроме последнего) содержит ровно `MAX_QUADS_PER_MESHLET` quad'ов,
   последний — остаток.
2. Bounding box каждого meshlet'а вычисляется из min/max координат всех его quad'ов.
   Для одного `Quad(faceDirection, x, y, z, width, height, materialId)` его пространственный
   охват определяется **точной таблицей конвенции width/height из тикета 11, requirement
   4a** (нормаль/width/height по каждой оси для каждого из 6 `faceDirection` — не изобретай
   свою конвенцию здесь, используй именно ту, что уже зафиксирована в тикете 11, так как
   `Quad` производится там и должен интерпретироваться этим тикетом идентично, иначе
   bounding box будет вычислен неверно для геометрии, которая на самом деле корректна).
   Перпендикулярная нормали ось имеет нулевую толщину в точке грани (значение `x`, `y` или
   `z` из `Quad`, в зависимости от того, какая ось — нормаль для данного `faceDirection`),
   две другие оси — от `(x,y,z)`-соответствующих компонент (начало прямоугольника) до
   `+width`/`+height` вдоль осей, назначенных этим двум измерениям согласно таблице тикета
   11. Реализуй вспомогательную функцию `quadWorldBounds(Quad q) -> {minX,minY,minZ,maxX,maxY,maxZ}`
   в локальных координатах секции 0..32, задокументируй в её Javadoc прямую ссылку на
   таблицу тикета 11 — это единственное место, где эта логика должна жить (не дублируй её
   позже в GPU-коде — GPU-код будет использовать уже посчитанный bounding box из `Meshlet`,
   не пересчитывать заново).
3. Итоговый `MeshletBatch.meshlets()` — порядок бинов сохраняется таким же, каким пришёл
   из `MaterialBinStage` (уже отсортирован по `materialId`), внутри бина — порядок
   разбиения по чанкам `MAX_QUADS_PER_MESHLET` последовательный (не переставлен).
4. Пустой список бинов на входе → `MeshletBatch` с пустым списком meshlet'ов (валидный
   вызов, не исключение).
5. Никаких зависимостей на NeoForge/LWJGL.

## Юнит-тесты (обязательно, JUnit 5)
Создай `MeshletPackStageTest`:
1. Пустой список бинов → `MeshletBatch` с пустым `meshlets()`, `totalQuadCount() == 0`.
2. Один бин с 5 quad'ами (меньше `MAX_QUADS_PER_MESHLET`) → ровно 1 meshlet с этими 5
   quad'ами.
3. Один бин с ровно `MAX_QUADS_PER_MESHLET * 2 + 10` quad'ами → ровно 3 meshlet'а: первые
   два по `MAX_QUADS_PER_MESHLET`, третий — 10 quad'ов, и порядок quad'ов внутри
   сохранён (первый meshlet содержит первые `MAX_QUADS_PER_MESHLET` quad'ов из исходного
   списка бина, и т.д.).
4. Bounding box: один quad с известными `faceDirection`, `x/y/z`, `width/height` →
   посчитанный bounding box meshlet'а соответствует ожидаемому вручную вычисленному
   диапазону (протестируй минимум для 2-3 разных `faceDirection`, чтобы проверить
   правильность маппинга оси нормали/width/height, не только одну ось).
5. Meshlet с несколькими quad'ами в разных частях секции → bounding box корректно
   охватывает ВСЕ quad'ы (min по каждой оси — минимум среди всех quad'ов, max — максимум),
   не только первый/последний.
6. `totalQuadCount()` на непустом `MeshletBatch` равен сумме размеров всех входных бинов.

## Критерии приёмки
1. Файл `ev-meshing/src/main/java/dev/ev/meshing/stage/MeshletPackStage.java`.
2. Все юнит-тесты проходят, особенно тест bounding box (№4-5) — это единственное место,
   где считается геометрия для GPU culling, ошибка здесь означает неверный occlusion/frustum
   culling позже в конвейере (тикеты 21-22).
3. Модуль компилируется без ошибок, без NeoForge/LWJGL зависимостей.
