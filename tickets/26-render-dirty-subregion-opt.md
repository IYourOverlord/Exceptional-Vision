# Тикет 26-opt — Dirty Sub-Region Tracking (инкрементальное обновление при правке блоков)

## ⚠️ Когда это применять

Это тикет Волны 2 (см. `MVP_INDEX.md`). MVP-версия (`26-render-dirty-tracking-mvp.md`)
помечает и пересобирает секцию ЦЕЛИКОМ при любой правке блока внутри неё — простое,
предсказуемое поведение. **Не выполняй этот тикет**, пока не пройден
`P0-profiling-checkpoint.md` и результаты не показали:
- Частые правки блоков (типичный игровой сценарий — стройка, добыча) вызывают заметные,
  наблюдаемые фризы/просадки FPS, коррелирующие по времени именно с событиями изменения
  блока (не с чем-то ещё, что тоже может происходить в тот момент) — эта метрика не
  входит в стандартный набор `P0-profiling-checkpoint.md` (которая фокусируется на холодном
  старте и steady-state без активного редактирования) — если решишь применять этот тикет,
  сначала явно замерь frame time до/во время/после серии быстрых правок блоков рядом с
  игроком (например, быстрая добыча несколькими инструментами подряд) отдельно, как
  дополнительный, специфичный для этого тикета замер, не предусмотренный основным
  чек-листом.

**Важно — этот тикет требует больше работы, чем просто "включить sub-region tracking"**:
исходная версия этого тикета (сохранённая ниже) реализует `RebuildDecisionPolicy` с явно
задокументированным ограничением — `shouldFullRebuild` всегда возвращает `true`, потому что
`GreedyMeshStage` (тикет 11) в его текущем виде не поддерживает partial-scope пересчёт (он
всегда обрабатывает секцию целиком, 6 направлений × 32 среза). **Чтобы этот тикет реально дал
эффект (не просто добавил неиспользуемую инфраструктуру учёта), нужно ДОПОЛНИТЕЛЬНО
расширить `GreedyMeshStage`**, чтобы он принимал необязательный параметр "область
пересчёта" (набор `SubRegion`) и пропускал срезы, не пересекающие ни одного грязного
саб-региона — это отдельная, не полностью специфицированная в исходном тексте задача,
которую нужно доделать в рамках выполнения этого тикета, а не считать её "уже готовой"
только потому, что структуры данных `SubRegion`/`DirtyRegionTracker` ниже уже полностью
описаны. Если чувствуешь, что это делает тикет существенно крупнее, чем обычно — это
корректное наблюдение, а не ошибка формулировки: данный тикет действительно требует и
инфраструктуры учёта (ниже), и нетривиального расширения уже существующего алгоритма
мешинга — оцени объём работы соответственно перед тем, как браться (см. также
`MODEL_ASSIGNMENT.md` — вероятно, стоит отнести это к Tier 3 при выполнении).

## Контекст
EV — мод дальней прорисовки для NeoForge 1.21.1. Модуль `ev-render` (координирует
storage + meshing при изменении блока — сам не содержит GL/GPU-кода). Реализует
оптимизацию, обоснованную в PERFORMANCE_MATH.md разделе B.6: когда игрок ломает/ставит
блок, наивный подход — пересобрать всю секцию (32³ мешинг заново). Правильная математика —
dirty-region tracking на уровне под-чанков (например, 8³ саб-регионов внутри секции 32³):
изменение одного блока помечает грязным только 1 из 64 саб-регионов, greedy-mesh
пересчитывается только для затронутых саб-регионов и их непосредственных соседей по
граничной плоскости (нужно для корректного merge quad'ов на стыке).

## Готовый контракт из зависимостей (тикеты 02, 11 — уже реализованы)
```java
package dev.ev.api.storage;
public interface WorldSectionHandle {
    dev.ev.api.SectionPos position();
    int getVoxel(int localX, int localY, int localZ);
    void setVoxel(int localX, int localY, int localZ, int paletteIndex);
    boolean isEmpty();
}
public final class DirtyFlags {
    public static final int BLOCK_CHANGED = 1;
    public static final int CHILD_EXISTENCE_CHANGED = 2;
    public static final int SKIP_PERSIST = 4;
    public static boolean has(int flags, int flag);
}

package dev.ev.meshing.stage;
public final class GreedyMeshStage {
    public java.util.List<dev.ev.api.meshing.Quad> process(
        WorldSectionHandle section, OccupancySet occupancy, dev.ev.api.meshing.MeshingContext ctx);
}
```

## Задача

### `SubRegion`
```java
package dev.ev.render.dirty;

/**
 * Identifies one of the 4x4x4 = 64 sub-regions within a 32^3 section (each
 * sub-region covering an 8x8x8 block of voxels: subRegionIndex maps to voxel
 * range [sx*8, sx*8+8) x [sy*8, sy*8+8) x [sz*8, sz*8+8)).
 */
public record SubRegion(int sx, int sy, int sz) {
    public static final int SUB_REGIONS_PER_AXIS = 4;
    public static final int VOXELS_PER_SUB_REGION_AXIS = 8;

    public SubRegion {
        if (sx < 0 || sx >= SUB_REGIONS_PER_AXIS || sy < 0 || sy >= SUB_REGIONS_PER_AXIS
            || sz < 0 || sz >= SUB_REGIONS_PER_AXIS) {
            throw new IllegalArgumentException("sub-region coordinates out of range");
        }
    }

    public static SubRegion fromLocalVoxel(int localX, int localY, int localZ) {
        return new SubRegion(
            localX / VOXELS_PER_SUB_REGION_AXIS,
            localY / VOXELS_PER_SUB_REGION_AXIS,
            localZ / VOXELS_PER_SUB_REGION_AXIS
        );
    }

    /** Flat index 0..63 for use as an array/bitset index. */
    public int flatIndex() {
        return sx + sy * SUB_REGIONS_PER_AXIS + sz * SUB_REGIONS_PER_AXIS * SUB_REGIONS_PER_AXIS;
    }

    /** The 6 face-adjacent neighboring sub-regions, excluding any that would fall
     * outside [0, SUB_REGIONS_PER_AXIS) — those are section-boundary neighbors,
     * handled separately (see DirtyRegionTracker requirement 3). */
    public java.util.List<SubRegion> faceNeighborsWithinSection();
}
```

### `DirtyRegionTracker`
```java
package dev.ev.render.dirty;

import dev.ev.api.SectionPos;
import java.util.Set;

/**
 * Tracks which sub-regions of which sections have been modified since their
 * last mesh rebuild, so the mesh rebuild step (see requirement 4) only reprocesses
 * affected sub-regions (plus their neighbors, for correct greedy-merge across
 * sub-region boundaries) instead of the entire 32^3 section, per PERFORMANCE_MATH.md
 * section B.6.
 *
 * Not thread-safe by design — intended to be driven from the main world-tick
 * thread where block change events originate (Minecraft's block update events
 * are main-thread-only), with the accumulated dirty set drained and handed off
 * to worker threads (via the meshing queue, ticket 15) once per tick/frame.
 * Document this threading assumption clearly.
 */
public final class DirtyRegionTracker {

    /** Records a single block change at the given section-local voxel coordinate. */
    public void markBlockChanged(SectionPos section, int localX, int localY, int localZ);

    /**
     * Returns the set of sub-regions (within the given section) that need mesh
     * rebuilding: every sub-region directly marked dirty, PLUS their face-adjacent
     * neighbors (within the same section — cross-section boundary handling is
     * out of scope for this ticket, see note in requirement 3), for correct
     * greedy-mesh boundary merging.
     */
    public Set<SubRegion> getRebuildRegions(SectionPos section);

    /** Clears tracked dirty state for a section after its rebuild has been dispatched. */
    public void clearSection(SectionPos section);

    /** True if any section currently has pending dirty sub-regions. */
    public boolean hasPendingWork();

    /** Number of sections with at least one pending dirty sub-region, for metrics. */
    public int pendingSectionCount();
}
```

## Требования к реализации

1. **Внутреннее хранилище**: `Map<Long, BitSet-или-long>` (ключ — `SectionPos.encode()`,
   значение — 64-битная маска dirty sub-regions, ОДИН `long` идеально подходит для 64
   саб-регионов — используй его напрямую как битовую маску вместо `java.util.BitSet` для
   компактности и простоты, аналогично подходу `OccupancySet` из тикета 10, но здесь только
   64 бита — влезает в один `long` без массива).

2. **`markBlockChanged`**: вычисли `SubRegion.fromLocalVoxel(localX, localY, localZ)`,
   установи соответствующий бит в маске для данной `SectionPos` (создай запись в карте, если
   её ещё нет).

3. **Соседи через границу секции**: пограничные саб-регионы (например, `sx == 0` или
   `sx == 3`) имеют соседей уже В ДРУГОЙ секции — этот тикет НЕ реализует
   cross-section-propagation дальше (то есть не помечает dirty саб-регион соседней секции
   автоматически). Задокументируй это явно как известное упрощение/ограничение текущего
   тикета: greedy-merge на стыке двух РАЗНЫХ секций уже обрабатывается через
   `MeshingContext.getNeighborBoundaryVoxel` (тикет 03/11) при полном перестроении секции,
   что остаётся корректным; данный тикет лишь оптимизирует объём работы ВНУТРИ одной секции
   при точечных правках, не меняя корректность существующего механизма межсекционных границ.

4. **Расширение `GreedyMeshStage` под partial-scope (см. предупреждение в начале файла) —
   ЭТО ОБЯЗАТЕЛЬНАЯ ЧАСТЬ ЭТОГО ТИКЕТА, не опционально**: чтобы sub-region tracking реально
   давал эффект (не просто собирал метаданные, которые никто не использует), нужно добавить
   в `GreedyMeshStage` (тикет 11) перегрузку/параметр, принимающий необязательный
   `Set<SubRegion> scopeFilter` — если передан не-null, метод должен пропускать те из 6×32
   срезов, которые не пересекают ни одного `SubRegion` из `scopeFilter` (расширенного
   face-neighbors, как возвращает `getRebuildRegions`), обрабатывая только пересекающиеся
   срезы, и корректно объединяя результат с уже существующей (не пересобираемой) частью
   геометрии секции — то есть результат должен быть эквивалентен полному пересчёту всей
   секции, только дешевле по стоимости вычисления для сцен, где изменилась малая доля
   объёма. Это самая техническая, рискованная часть этого тикета — она затрагивает уже
   написанный и протестированный код тикета 11, требует аккуратности, чтобы не сломать
   существующую корректность полного пересчёта, и должна сопровождаться дополнительными
   юнит-тестами именно на этот новый режим (сравнение partial-rebuild результата с полным
   rebuild для того же исходного состояния секции — они должны давать идентичный набор
   quad'ов при правильной реализации).

5. **`RebuildDecisionPolicy`** (после того, как п.4 реализован, эта политика может реально
   решать не "always full", а на основе доли/числа грязных регионов — например, если
   грязных регионов больше некоторого порога от 64, дешевле сделать полный пересчёт, чем
   собирать частичный с overhead на объединение результатов; если дирижёр грязных регионов
   мал — используй partial-scope путь из п.4):
   ```java
   package dev.ev.render.dirty;

   public final class RebuildDecisionPolicy {
       public boolean shouldFullRebuild(java.util.Set<SubRegion> dirtyRegions);
   }
   ```
   Определи и обоснуй конкретный порог (например, "если dirtyRegions.size() > 32 из 64,
   полный пересчёт дешевле, чем partial с overhead" — это предположение стоит либо обосновать
   математически, либо явно пометить как эвристику, которую при желании можно перепроверить
   отдельным профилированием).

6. Никакого мутируемого статического состояния (кроме однопоточной, задокументированной
   модели доступа — main-thread-only, как в MVP-версии).

## Юнит-тесты (обязательно, JUnit 5)

Создай `SubRegionTest`:
1. `fromLocalVoxel` корректно вычисляет sub-region для нескольких известных координат
   (включая углы каждого sub-region диапазона).
2. Конструктор бросает `IllegalArgumentException` при координатах вне 0..3.
3. `flatIndex` даёт уникальные значения 0..63 для всех 64 комбинаций (sx,sy,sz).
4. `faceNeighborsWithinSection` для sub-region в углу (0,0,0) возвращает ровно 3 соседа; для
   sub-region в центре — ровно 6.

Создай `DirtyRegionTrackerTest`:
1. `markBlockChanged` затем `getRebuildRegions` для той же секции содержит соответствующий
   sub-region И его face-neighbors.
2. Несколько `markBlockChanged` вызовов в одном sub-region не дублируют результат.
3. `markBlockChanged` для двух разных секций — полная изоляция между секциями.
4. `clearSection` очищает dirty-состояние.
5. `hasPendingWork`/`pendingSectionCount` корректны.

Создай `GreedyMeshStagePartialScopeTest` (новый, специфичный для этого тикета — проверяет
расширение из требования 4):
1. Полный набор `scopeFilter` (все 64 sub-region) даёт результат, идентичный вызову без
   `scopeFilter` (полному пересчёту) — базовая проверка регрессии.
2. Частичный `scopeFilter` (например, один угловой sub-region) даёт результат, содержащий
   только quad'ы, затрагивающие этот регион и его соседей — не полный набор quad'ов всей
   секции.
3. **Критический тест эквивалентности**: для секции со сложной геометрией (не тривиально
   однородной), сравни результат `process(section, occupancy, ctx, scopeFilter=null)`
   (полный пересчёт) с результатом объединения последовательности partial-rebuild вызовов,
   покрывающих ту же секцию по частям — итоговое множество quad'ов должно быть
   эквивалентно (с точностью до порядка, если порядок не гарантирован между вызовами).

Создай `RebuildDecisionPolicyTest`:
1. Малое число dirty regions → `shouldFullRebuild == false` (используется partial path).
2. Большое число dirty regions (выше выбранного порога) → `shouldFullRebuild == true`.
3. Пустой набор → `shouldFullRebuild == false` (нечего пересобирать вообще — уточни, что в
   этом случае вызывающий код просто не должен инициировать rebuild, это пограничный случай
   для документирования, не обязательно для явного теста самого метода, если он для пустого
   набора логически "не должен вызываться" — но если вызван, не должен падать).

## Критерии приёмки
1. Файлы в `ev-render/src/main/java/dev/ev/render/dirty/`: `SubRegion.java`,
   `DirtyRegionTracker.java`, `RebuildDecisionPolicy.java`.
2. `GreedyMeshStage` (тикет 11, модуль `ev-meshing`) дополнен partial-scope
   перегрузкой/параметром согласно требованию 4 — это изменение существующего, уже
   протестированного класса, не нового файла.
3. Все юнит-тесты проходят, включая новый `GreedyMeshStagePartialScopeTest` — особенно тест
   №3 (эквивалентность partial и full rebuild) — это единственная проверка, реально
   гарантирующая, что оптимизация не изменила видимый результат, только его стоимость.
4. Модуль компилируется без ошибок, без `org.lwjgl.*` импортов.
5. Javadoc `RebuildDecisionPolicy` объясняет выбранный порог полного/частичного пересчёта.
6. Threading-модель `DirtyRegionTracker` (main-thread-only) задокументирована явно.
7. `PROJECT_INDEX.md` (тикет 32) обновлён, отмечая переход с MVP (whole-section) на
   sub-region granular dirty tracking, включая факт расширения `GreedyMeshStage`.
