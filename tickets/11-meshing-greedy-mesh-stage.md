# Тикет 11 — GreedyMeshStage (2D greedy quad meshing)

## Контекст
EV — мод дальней прорисовки для NeoForge 1.21.1. Модуль `ev-meshing`, чистый Java.
Вторая стадия конвейера мешинга (после `OccupancyStage`, тикет 10). Greedy meshing —
стандартный алгоритм объединения соседних одинаковых по материалу и копланарных граней
вокселей в минимальное число прямоугольных quad'ов, вместо генерации отдельной грани на
каждый видимый воксель (что дало бы до `6 * 32768` граней на полностью занятую секцию —
неприемлемо много геометрии).

Алгоритм: для каждого из 6 направлений грани и для каждого из 32 срезов (slice) вдоль оси,
перпендикулярной этому направлению, строится 2D-маска видимых граней этого среза (грань
видима, если воксель занят, а сосед в этом направлении — нет, либо сосед за границей секции —
см. `MeshingContext.getNeighborBoundaryVoxel` из тикета 03), затем 2D-маска жадно
покрывается минимальным числом прямоугольников одинакового материала.

## Готовый контракт из зависимостей (тикеты 03, 10 — уже реализованы)
```java
package dev.ev.api.meshing;
public record Quad(int faceDirection, int x, int y, int z, int width, int height, int materialId) {}
public interface MeshingContext {
    int getNeighborBoundaryVoxel(int faceDirection, int a, int b);
    int resolveMaterialId(int paletteIndex);
}

package dev.ev.meshing.stage;
public final class OccupancySet {
    public static final int GRID_SIZE = 32;
    public boolean get(int x, int y, int z);
    public boolean isEmpty();
}
```

## Задача

### `GreedyMeshStage`
```java
package dev.ev.meshing.stage;

import dev.ev.api.meshing.MeshingContext;
import dev.ev.api.meshing.Quad;
import dev.ev.api.storage.WorldSectionHandle;
import java.util.List;

/**
 * Second stage of the meshing pipeline: converts an OccupancySet + the section's
 * palette data into a list of greedily-merged Quads, one list per face direction
 * internally but returned as a single combined list (direction is encoded per-Quad
 * via Quad.faceDirection()).
 *
 * Algorithm: standard binary/greedy 2D meshing per axis-slice, adapted for a fixed
 * 32^3 grid. For each of the 6 face directions, iterate 32 slices along the axis
 * normal to that direction; for each slice build a 32x32 mask where a cell is
 * "paintable" if the voxel is occupied AND the neighboring voxel in faceDirection
 * is NOT occupied (i.e. this face is externally visible), tagged with the material
 * id of the occupied voxel; then greedily cover the mask with maximal rectangles
 * of uniform material, clearing covered cells as you go (standard greedy meshing:
 * scan for an uncovered cell, grow width while the run has the same material,
 * grow height while every cell in the row has the same material, emit one Quad,
 * clear the covered region, repeat).
 */
public final class GreedyMeshStage {
    public List<Quad> process(WorldSectionHandle section, OccupancySet occupancy, MeshingContext ctx);
}
```

## Требования к реализации

1. Face direction encoding (согласовано с тикетом 03): `0=+X, 1=-X, 2=+Y, 3=-Y, 4=+Z, 5=-Z`.
2. Для направления `+X` (face direction 0): грань в позиции `(x,y,z)` видима, если
   `occupancy.get(x,y,z) == true` И (`x == 31` И сосед снаружи секции по `+X` пуст, ИЛИ
   `x < 31` И `occupancy.get(x+1,y,z) == false`). "Сосед снаружи секции" разрешается через
   `ctx.getNeighborBoundaryVoxel(faceDirection, a, b)` — если результат равен палитровому
   индексу 0 (воздух), грань видима; иначе (сосед непустой снаружи) — грань не видима, даже
   на границе секции (что корректно скрывает грани на стыке двух занятых соседних секций).
   Аналогичная логика (со знаком/осью, зависящими от направления) для остальных 5 направлений
   — реализуй симметрично, не копируй код 6 раз, вынеси общую параметризованную по оси логику.
3. Материал грани — `ctx.resolveMaterialId(paletteIndex)` вокселя, породившего эту грань (не
   соседа). Прямоугольники объединяются только если материал совпадает на всех вокселях
   покрываемой области (стандартное свойство greedy meshing — не объединяй разные материалы
   в один Quad).
4. Результирующие координаты `Quad.x/y/z` — в единицах границ вокселей (voxel boundary units,
   диапазон 0..32 включительно, не voxel-центры) — то есть если quad покрывает вокселы с
   локальными координатами x=0..2 (3 вокселя) по одной из осей, `width` этого измерения
   равен 3, а не 2 — уточни это в реализации и покрой тестом.

4a. **Явная, обязательная конвенция width/height по осям** (тикет 11 сам по себе не
    специфицировал это, что создавало риск: тикет 13, который вычисляет bounding box из
    `Quad`, мог бы выбрать другую конвенцию независимо и молча разойтись с этим тикетом —
    следуй ЭТОЙ таблице дословно, не изобретай другую):

    | faceDirection | Нормаль (толщина 0) | `width` растёт вдоль | `height` растёт вдоль |
    |---|---|---|---|
    | 0 (+X) | X | Y | Z |
    | 1 (-X) | X | Y | Z |
    | 2 (+Y) | Y | Z | X |
    | 3 (-Y) | Y | Z | X |
    | 4 (+Z) | Z | X | Y |
    | 5 (-Z) | Z | X | Y |

    Это циклическая конвенция (X→Y→Z→X): для грани с нормалью вдоль оси N, `width` идёт
    вдоль следующей оси по циклу после N, `height` — вдоль оси после неё. Направление
    (+/-) одной и той же оси нормали (например, `+X` и `-X`) использует ОДИНАКОВУЮ
    width/height конвенцию — разница между `+X` и `-X` только в том, какой воксель
    (текущий или сосед) считается источником грани (см. requirement 2), не в ориентации
    width/height. `Quad.x/y/z` — координата НАЧАЛА прямоугольника (наименьшие значения по
    обеим осям width/height среди покрытых вокселей), не центр и не какой-либо другой угол.

5. Пустая секция (`occupancy.isEmpty() == true`) — быстрый путь, вернуть пустой список без
   обхода 6×32 срезов.
6. Алгоритмическая сложность: `O(6 * 32 * 32 * 32)` = `O(6 * GRID_SIZE³)` в худшем случае
   на секцию (константный множитель, не зависит от количества итоговых quad'ов) — это
   ожидаемая, приемлемая сложность для greedy meshing на фиксированной сетке 32³, не
   оптимизируй дальше в этом тикете, но не допускай случайного возведения в степень выше
   (например, не делай квадратичный проход по уже обработанным ячейкам).
7. Никаких зависимостей на NeoForge/LWJGL.

## Юнит-тесты (обязательно, JUnit 5)
Тестовые заглушки: простая `WorldSectionHandle` (in-memory, как в тикете 10) и простая
`MeshingContext`, где `getNeighborBoundaryVoxel` всегда возвращает 0 (воздух — секция
окружена пустотой, самый простой случай для проверки) если явно не переопределено в
конкретном тесте, и `resolveMaterialId` — identity-функция (возвращает переданный
paletteIndex как есть) для простоты тестов.

Создай `GreedyMeshStageTest`:
1. Пустая секция → пустой список quad'ов, ноль вызовов `getNeighborBoundaryVoxel`/
   `resolveMaterialId` (fast-path проверка, аналогично тикету 10).
2. Единственный занятый воксель в центре секции, окружённый воздухом (граница секции тоже
   воздух через заглушку) → ровно 6 quad'ов (по одному на каждую грань), каждый размером
   1×1, с ожидаемыми координатами.
3. Сплошной блок 4×4×4 одного материала в углу секции → должен дать существенно меньше
   quad'ов, чем `4*4*4*6` (наивный per-voxel подход) — например, по 1 quad размером 4×4 на
   каждую из 6 внешних граней (итого 6 quad'ов) для внутренне однородного блока без соседей —
   проверь точное ожидаемое число и размеры.
4. Два смежных вокселя РАЗНОГО материала (не сливаются в один quad, т.к. материалы разные) —
   проверь, что получаются раздельные quad'ы, не один объединённый.
5. Плоский слой 32×32×1 одного материала (целая горизонтальная плоскость секции) на
   направлении `+Y`/`-Y` → верхняя и нижняя грани должны дать по одному quad размером 32×32
   каждая (полное покрытие жадным алгоритмом одним прямоугольником), а не 1024 отдельных
   quad'а — это ключевая проверка того, что greedy-объединение действительно работает, не
   просто генерирует грань на каждый воксель.
6. Тест на границу секции: воксель на самом краю (`x=31`) занят, `MeshingContext` явно
   настроен так, что `getNeighborBoundaryVoxel(0 /* +X */, ...)` возвращает ненулевой
   (занятый) индекс для этой позиции → грань `+X` НЕ должна быть сгенерирована (сосед в
   соседней секции её перекрывает).
7. **Тест на конвенцию width/height (requirement 4a)**: прямоугольная (не квадратная) область
   заполненных вокселей одного материала — например, 5 вокселей вдоль Y и 3 вдоль Z для грани
   `+X`/`-X` — quad, покрывающий эту область, должен иметь `width == 5` (не 3) и `height == 3`
   (не 5), согласно таблице из requirement 4a (для `faceDirection` с нормалью X: width растёт
   вдоль Y, height вдоль Z). Повтори этот тест хотя бы для одной пары направлений с нормалью
   по каждой из трёх осей (например, `+X`, `+Y`, `+Z`), с ЗАВЕДОМО РАЗНЫМИ размерами области
   по двум перпендикулярным осям в каждом случае (не квадрат — квадратная область не отличит
   правильную конвенцию от перепутанной width/height, оба дадут одинаковый визуальный
   результат случайно).

## Критерии приёмки
1. Файл `ev-meshing/src/main/java/dev/ev/meshing/stage/GreedyMeshStage.java`.
2. Все юнит-тесты проходят, особенно тест №5 (доказательство реального сжатия геометрии),
   №6 (корректная работа с соседними секциями через MeshingContext) и №7 (конвенция
   width/height, requirement 4a — без этого теста ошибка в конвенции была бы незаметна на
   квадратных тестовых данных и проявилась бы только позже, в тикете 13, при вычислении
   bounding box, или ещё позже, визуально, как растянутая/сжатая геометрия на экране).
3. Модуль компилируется без ошибок, без NeoForge/LWJGL зависимостей.
