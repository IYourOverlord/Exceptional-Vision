# Тикет 14 — MeshPriority (screen-space error + приоритет задачи мешинга)

## 📌 Статус относительно MVP/opt разделения

Этот тикет реализуется **одинаково в обеих волнах** (см. `MVP_INDEX.md`) — не разделён на
`-mvp`/`-opt` версии. Причина: screen-space error metric и двухъярусный near-player приоритет
предотвращают конкретные, наблюдаемые баги (неверный выбор LOD-уровня, дыры в геометрии
вокруг игрока при агрессивной farthest-first приоритизации — см. эмпирический урок из
Exceptional Vision ниже), а не улучшают абстрактную производительность. Даже MVP-версия
`SimpleTraversal` (тикет `21-gpu-simple-traversal-mvp.md`) нуждается в корректном выборе LOD
и приоритете загрузки — без этого тикета MVP-версия будет либо загружать всё на одном
уровне детализации (неверно), либо в произвольном порядке (оставляя дыры у игрока).

## Контекст
EV — мод дальней прорисовки для NeoForge 1.21.1. Модуль `ev-meshing`, чистый Java.
Реализует две связанные, но разные вещи, обоснованные в PERFORMANCE_MATH.md:
1. **Screen-space error metric (A.3)** — определяет, какой LOD-уровень нужен для секции,
   чтобы её геометрическая погрешность проецировалась не более чем в `errorThresholdPx`
   пикселей экрана.
2. **Приоритет задачи мешинга (A.1)** — определяет порядок обработки задач в очереди
   (тикет 15), взвешивая угол обзора, дистанцию и LOD-уровень так, чтобы то, что видит игрок
   прямо сейчас, строилось первым.

Обе функции — чистая математика, без побочных эффектов, легко тестируемая изолированно от
самой очереди.

## Готовый контракт из зависимости (тикет 01, уже реализован)
```java
package dev.ev.api;
public record SectionPos(int level, int x, int y, int z) {
    public static final int MAX_LOD_LEVEL = 6;
    public int sizeInBlocks();
    public long minBlockX(); public long minBlockY(); public long minBlockZ();
}
```

## Задача

### `ScreenSpaceErrorMetric`
```java
package dev.ev.meshing.priority;

/**
 * Determines the minimum LOD level whose voxel size projects to no more than
 * errorThresholdPx screen pixels at a given distance, per PERFORMANCE_MATH.md
 * section A.3:
 *
 *   projectedError(voxelSize, distance, fovY, screenHeightPx) =
 *       (voxelSize / distance) * (screenHeightPx / (2 * tan(fovY / 2)))
 *
 * The (screenHeightPx / (2*tan(fovY/2))) term is constant per-frame (only changes
 * on window resize / FOV change) and should be precomputed once per frame by the
 * caller and passed in as projectionScale, NOT recomputed per section (recomputing
 * tan() per section across thousands of sections per frame is measurably wasteful —
 * see PERFORMANCE_MATH.md A.3).
 */
public final class ScreenSpaceErrorMetric {

    /** Precompute once per frame: screenHeightPx / (2 * tan(fovYRadians / 2)). */
    public static float computeProjectionScale(float fovYRadians, int screenHeightPx) {
        return screenHeightPx / (2.0f * (float) Math.tan(fovYRadians / 2.0));
    }

    /**
     * voxelSizeAtLevel: size in blocks of a single voxel at the LOD level being
     * evaluated (NOT the section size — sizeInBlocks()/32, since a section is a
     * 32^3 grid of voxels).
     */
    public static float projectedErrorPx(float voxelSizeAtLevel, float distance, float projectionScale) {
        if (distance <= 0.0001f) distance = 0.0001f; // avoid division blowup at/behind camera
        return (voxelSizeAtLevel / distance) * projectionScale;
    }

    /**
     * Returns the coarsest (highest) LOD level in [0, SectionPos.MAX_LOD_LEVEL] whose
     * projected error is still <= errorThresholdPx at the given distance. Coarser is
     * preferred when multiple levels satisfy the threshold (less geometry = cheaper),
     * consistent with PERFORMANCE_MATH.md A.3's intent of not over-detailing what
     * isn't visually distinguishable.
     */
    public static int selectLodLevel(float distance, float projectionScale, float errorThresholdPx) {
        // implement: iterate/binary-search levels 0..MAX_LOD_LEVEL, find coarsest satisfying threshold
    }
}
```

### `MeshPriority`
```java
package dev.ev.meshing.priority;

import dev.ev.api.SectionPos;

/**
 * Computes a total-order priority value for a mesh-build task, per PERFORMANCE_MATH.md
 * section A.1. Lower returned value = higher priority (processed first) — this
 * convention matches typical priority-queue/bucket-index usage where bucket 0 is
 * drained first (see MeshTaskQueue, ticket 15).
 *
 * Components, from most to least significant in the packed result:
 *   1. lodWeight: how close to the finest LOD this section is relative to the current
 *      max LOD in use — finer/nearer sections matter more.
 *   2. attemptWeight: number of prior failed/deferred attempts, capped, to prevent
 *      starvation (a section that keeps losing priority contention eventually climbs
 *      to the front — aging).
 *   3. facingBonus: 0 if the section is roughly in the camera's forward view cone,
 *      1 otherwise (peripheral/behind) — deprioritizes what the player is not looking at.
 *   4. insertionSeq: low bits, breaks ties in FIFO order among otherwise-equal-priority
 *      tasks, for determinism and fairness.
 */
public final class MeshPriority {

    /**
     * @param lodLevel this section's LOD level (0 = finest)
     * @param maxLodLevel the coarsest LOD level currently in use for this world (usually SectionPos.MAX_LOD_LEVEL)
     * @param attempts number of prior attempts at building this section (0 for first try)
     * @param cosAngleToViewDir dot product of normalized(sectionCenter - camera) and camera's
     *        forward vector; range [-1, 1], 1 = dead center of view, -1 = directly behind
     * @param facingThreshold cosine threshold below which a section is considered "not facing"
     *        (e.g. cos(60 degrees) as a generous view-cone bound — tune via config, not hardcoded here)
     * @param insertionSeq monotonically increasing counter assigned at task-submission time
     */
    public static long compute(int lodLevel, int maxLodLevel, int attempts,
                                float cosAngleToViewDir, float facingThreshold, long insertionSeq) {
        // implement per the packing scheme documented above; must be a pure function
        // (no shared/global state), and must be deterministic for identical inputs.
    }

    /**
     * Convenience overload computing cosAngleToViewDir internally from raw vectors,
     * for callers that have not already computed it (avoids duplicating the dot-product
     * math at every call site).
     */
    public static long compute(SectionPos section, int maxLodLevel, int attempts,
                                float cameraX, float cameraY, float cameraZ,
                                float viewDirX, float viewDirY, float viewDirZ,
                                float facingThreshold, long insertionSeq) {
        // compute section center in world space (see SectionPos.minBlockX/Y/Z + sizeInBlocks()/2),
        // compute normalized direction from camera to that center, dot with (viewDirX,viewDirY,viewDirZ)
        // (viewDir is assumed already normalized — document this assumption), then delegate
        // to the primary compute() overload above.
    }
}
```

## Эмпирический урок из независимой реализации (Exceptional Vision) — двухъярусный приоритет

Другой независимо реализованный мод той же концепции (Exceptional Vision, репозиторий
`IYourOverlord/Exceptional-Vision`) первоначально приоритизировал bulk-импорт исключительно
"farthest-first" (дальние регионы обрабатываются раньше — на предположении, что ближние к
игроку регионы уже покрыты ванильным рендером и потому менее приоритетны). Реально
плейтестированный, задокументированный результат (их `PROGRESS.md`, пункт 0.8): это оставляло
**кольцо необработанных дыр ровно вокруг точки спавна игрока**, потому что предположение
"ближнее уже покрыто ванилью" верно только когда near-cutoff дистанция сравнима с размером
единицы работы — на практике render distance игрока часто существенно меньше, так что
"покрыто ванилью" — это лишь маленький пятачок в центре, не вся ближняя область.

**Исправление, подтверждённое плейтестом:** добавлен безусловный высокоприоритетный ярус —
фиксированный радиус вокруг игрока (в их случае `NEAR_PLAYER_RADIUS_REGIONS = 2`) обрабатывается
**полностью, целиком, раньше ЛЮБОЙ задачи вне этого радиуса**, независимо от дистанции той
внешней задачи. Внутри самого near-radius яруса порядок — nearest-first (интуитивно ожидаемое:
сначала под ногами игрока). Технически реализовано через отдельный, безусловно более
приоритетный числовой диапазон (константа-база на порядки выше любого значения, которое может
выдать обычная ветка приоритета) — так что два яруса никогда не сравниваются напрямую по
точной дистанции, один ярус всегда полностью побеждает другой.

**Требование к `MeshPriority.compute` в этом тикете:** добавь аналогичный явный двухъярусный
механизм, а не полагайся только на непрерывную формулу приоритета из компонентов
`lodWeight`/`attemptWeight`/`facingBonus`, описанных выше. Конкретно:

```java
/**
 * Radius (in sections at the finest LOD level) around the camera within which
 * every section is guaranteed to be prioritized above ANY section outside this
 * radius, regardless of that outside section's own priority score — see the
 * "two-tier priority" empirical lesson from an independent implementation of
 * this same mod concept (Exceptional Vision), which found via real playtesting
 * that a pure distance/angle-weighted formula alone leaves a ring of unprocessed
 * holes immediately around the player when the outer (farther) region backlog
 * is large, because "near player" and "far but high angular priority" can
 * otherwise compete on close-enough scores for the near tier to lose sometimes.
 */
public static final int NEAR_PLAYER_UNCONDITIONAL_RADIUS_SECTIONS = 4; // tune via config, not hardcoded in production

/**
 * Priority base for the near-player unconditional tier.
 *
 * IMPLEMENTATION REQUIREMENT (not just a design note — this is how correctness
 * is actually guaranteed, independent of whatever specific bit-packing scheme is
 * chosen for the normal tier's lodWeight/attemptWeight/facingBonus/insertionSeq
 * components): reserve bit 63 (the sign bit of the returned long) as an explicit
 * TIER FLAG, with this EXACT direction (verified against standard Java signed
 * long comparison, which is what both MeshTaskQueue implementations — ticket 15
 * MVP's PriorityBlockingQueue via Comparable, and the opt version's bucket index
 * extraction via unsigned right-shift — ultimately rely on for ordering):
 *
 *   - NEAR-TIER results: bit 63 SET (e.g. OR in Long.MIN_VALUE, i.e. (1L << 63),
 *     making the packed value a large-magnitude NEGATIVE signed long).
 *   - NORMAL-TIER results: bit 63 CLEAR (a non-negative signed long, produced
 *     naturally by the normal lodWeight/attemptWeight/facingBonus/insertionSeq
 *     packing as long as none of those fields' bit positions extend into bit 63
 *     — verify this explicitly for whatever exact packing layout you choose).
 *
 * Why this direction, not the reverse: under standard Java signed long
 * comparison (Long.compare, and Comparable<Long>'s natural ordering used by
 * PriorityBlockingQueue), any negative long is LESS THAN any non-negative long.
 * Since this class's convention is "lower = higher priority" (see class Javadoc
 * above), a near-tier value with bit 63 set (negative) is therefore guaranteed
 * to compare as lower — i.e. higher priority — than ANY normal-tier value with
 * bit 63 clear (non-negative), regardless of the specific numeric value either
 * side's remaining 63 bits happen to encode. This is the opposite bit convention
 * from what a naive reading of "reserve a flag bit" might suggest (setting the
 * flag to mark the tier you want to LOSE comparisons, not win them, is
 * counter-intuitive but mathematically necessary here) — do not swap the
 * direction without re-deriving this argument from scratch, and do not assume
 * an unsigned comparison anywhere in the pipeline unless you have verified it
 * explicitly for the actual queue implementation in use (ticket 15).
 *
 * Concretely: near-tier priority = Long.MIN_VALUE + (small non-negative offset
 * encoding lodWeight/insertionSeq for tie-breaking WITHIN the near tier, per the
 * requirement below) — NOT literally 0L as a placeholder name might suggest;
 * rename or repurpose this constant during implementation to reflect the actual
 * Long.MIN_VALUE-based base, and update this Javadoc's stated value accordingly
 * if the final chosen encoding differs from this description.
 *
 * ARITHMETIC SAFETY: the offset added to Long.MIN_VALUE must be small enough
 * and always non-negative so the sum never overflows past 0 back into positive
 * territory (which would collide with the normal tier's value space and defeat
 * the entire mechanism silently — Java long arithmetic wraps around on overflow
 * without throwing). Concretely: use only the low ~32 bits for the within-tier
 * offset (e.g. `Long.MIN_VALUE + (offsetValue & 0xFFFFFFFFL)`), which leaves an
 * enormous safety margin (2^31 possible offset values against Long.MIN_VALUE's
 * magnitude of 2^63) — verify this bound explicitly in a unit test (see
 * requirement below) rather than trusting the arithmetic by inspection alone.
 */
private static final long NEAR_PLAYER_PRIORITY_BASE = Long.MIN_VALUE;
```

Добавь метод со следующей конкретной сигнатурой (не оставляй это открытым решением —
неопределённость здесь создаёт риск, что реализация добавит несогласованный с остальным
API вариант, или забудет связать near-tier проверку с обоими существующими `compute`
overload'ами, из-за чего near-tier логика окажется применённой только в одном из двух
путей вызова, доступных вызывающему коду):

```java
/**
 * Primary entry point for callers that already have a camera position and section
 * center in world space (e.g. ticket 27's render/scheduling code) — wraps both the
 * near-player unconditional tier check AND delegates to the appropriate compute()
 * overload above for the normal-tier path. Callers should use THIS method, not the
 * two compute() overloads directly, unless they have a specific reason to bypass
 * the near-tier check (document any such reason at the call site if it occurs).
 */
public static long computeWithNearTierCheck(
    SectionPos section, int maxLodLevel, int attempts,
    float cameraX, float cameraY, float cameraZ,
    float viewDirX, float viewDirY, float viewDirZ,
    float facingThreshold, long insertionSeq
) {
    // 1. Compute Chebyshev or Euclidean distance in SECTIONS (not blocks) between
    //    camera and section center, at level-0 section granularity (i.e. convert
    //    both camera position and section center to level-0 section-grid units
    //    before comparing, regardless of the section's own actual LOD level —
    //    this keeps NEAR_PLAYER_UNCONDITIONAL_RADIUS_SECTIONS meaningful independent
    //    of which LOD level a given candidate section happens to be at).
    // 2. If within NEAR_PLAYER_UNCONDITIONAL_RADIUS_SECTIONS: return a near-tier
    //    value (Long.MIN_VALUE + small offset encoding within-tier distance/insertionSeq
    //    for tie-breaking — see NEAR_PLAYER_PRIORITY_BASE Javadoc for the exact
    //    arithmetic safety requirements).
    // 3. Otherwise: delegate to the vector-based compute() overload above (which
    //    itself delegates to the primary lodLevel-based compute() overload) —
    //    reuse that logic rather than duplicating the cosAngleToViewDir computation.
}
```

`EVInstance.renderFarLod`-adjacent scheduling code (ticket 27, wherever mesh-build tasks
are actually submitted to `MeshTaskQueue`) must call this method, not the bare `compute()`
overloads, whenever a live camera position is available (which it always should be, at task
submission time) — otherwise the near-tier guarantee this ticket exists to provide never
actually reaches production code paths.

## Требования к реализации
1. `ScreenSpaceErrorMetric.selectLodLevel` — реализуй либо линейный перебор 7 уровней
   (0..6), либо бинарный поиск; для всего 7 значений линейный перебор абсолютно приемлем и
   проще для чтения — не усложняй бинарным поиском без необходимости, но задокументируй
   монотонность допущения (что `projectedErrorPx` монотонно убывает с ростом `distance` при
   фиксированном уровне, и монотонно растёт с ростом `voxelSizeAtLevel`/уровня — это
   гарантирует корректность как линейного перебора, так и потенциального бинарного поиска).
2. `voxelSizeAtLevel` для уровня `L` — вычисляется как `SectionPos.sizeInBlocks()` для
   секции этого уровня, делённое на 32 (32 вокселя на сторону секции); можно вычислить прямо
   внутри `selectLodLevel` через временный `new SectionPos(L, 0, 0, 0).sizeInBlocks() / 32f`
   или напрямую как `(32 << L) / 32f = 1 << L` — используй прямую формулу `1 << L`, не
   создавай лишний объект `SectionPos` только чтобы прочитать константу (избегай ненужных
   аллокаций в этой функции — она может вызываться на каждую секцию каждый кадр).
3. `MeshPriority.compute` — используй битовую упаковку в `long` явно (аналогично
   `SectionPos.encode()` из тикета 01: выдели старшие биты под `lodWeight`/`attemptWeight`/
   `facingBonus`, младшие — под `insertionSeq`), задокументируй точную битовую раскладку в
   Javadoc класса, чтобы тикет 15 (bucket queue) мог корректно извлекать номер корзины из
   старших бит результата.
4. `attempts` должен быть **capped** (например, `Math.min(attempts, 3)`) перед упаковкой —
   не позволяй неограниченно растущему `attempts` переполнить отведённые под него биты или
   задавить остальные компоненты приоритета.
5. Никаких зависимостей на NeoForge/LWJGL. `Math.tan`/`Math.sqrt` из `java.lang.Math` — ок,
   это не GPU-код.

## Юнит-тесты (обязательно, JUnit 5)

Создай `ScreenSpaceErrorMetricTest`:
1. `computeProjectionScale` для известных `fovYRadians`/`screenHeightPx` даёт ожидаемое
   значение (посчитай вручную для, например, `fovY = 70°` в радианах, `screenHeightPx =
   1080`, сравни с результатом функции с разумной точностью `assertEquals(expected, actual,
   delta)`).
2. `projectedErrorPx` — при увеличении `distance` вдвое (прочие параметры неизменны)
   результат уменьшается примерно вдвое (проверка обратной пропорциональности).
3. `projectedErrorPx` не бросает исключение и не возвращает `NaN`/`Infinity` при
   `distance == 0` (проверка защиты от деления на ноль из требований).
4. `selectLodLevel` на очень большой дистанции возвращает уровень, близкий к
   `SectionPos.MAX_LOD_LEVEL` (грубый LOD достаточен так далеко).
5. `selectLodLevel` на очень малой дистанции (например, `distance = 1`) возвращает уровень 0
   (нужна максимальная детализация вблизи).
6. `selectLodLevel` монотонно не убывает при увеличении `distance` (протестируй на
   нескольких возрастающих значениях дистанции, убедись что выбранный уровень не
   уменьшается) — ключевое свойство корректности LOD-подбора.

Создай `MeshPriorityTest`:
1. При прочих равных, меньший `lodLevel` (более детальный, ближе к 0) даёт приоритетное
   (меньшее по значению — выше приоритет, согласно конвенции "lower = higher priority")
   значение, чем больший `lodLevel`.
2. При прочих равных, больший `attempts` даёт более приоритетное (меньшее) значение, чем
   меньший `attempts` (aging работает — застрявшая задача со временем повышает приоритет).
   Но также проверь, что `attempts` capped: `attempts = 3` и `attempts = 100` дают
   ОДИНАКОВОЕ значение компонента приоритета (после капа разница не должна расти
   неограниченно).
3. При прочих равных, `cosAngleToViewDir` выше `facingThreshold` (в поле зрения) даёт более
   приоритетное значение, чем ниже порога (вне поля зрения).
4. Разный `insertionSeq` при полностью одинаковых остальных параметрах даёт разные
   результаты, с меньшим `insertionSeq` более приоритетным (FIFO tie-break).
5. Overload с сырыми векторами даёт то же значение, что и явный вызов основного `compute`
   с вручную посчитанным `cosAngleToViewDir` для тех же входных данных (проверка
   консистентности двух перегрузок).
6. **Двухъярусный приоритет** (см. раздел "Эмпирический урок" выше): секция внутри
   `NEAR_PLAYER_UNCONDITIONAL_RADIUS_SECTIONS` от камеры получает приоритетное значение
   МЕНЬШЕ (то есть выше приоритет), чем ЛЮБАЯ секция вне этого радиуса — протестируй это
   явно с намеренно "невыгодным" набором параметров для near-секции (например, худший
   `lodLevel`, `attempts=0`, вне поля зрения) против намеренно "выгодного" набора для
   дальней секции (лучший `lodLevel`, высокий `attempts`, точно в центре обзора) — near-tier
   секция всё равно должна выиграть по итоговому значению, доказывая, что тиры не
   пересекаются численно ни при каких значениях компонентов обычной формулы.
7. Внутри near-tier яруса — секция ближе к камере (внутри того же
   `NEAR_PLAYER_UNCONDITIONAL_RADIUS_SECTIONS`) получает более приоритетное значение, чем
   секция дальше (но всё ещё внутри радиуса) — nearest-first порядок внутри самого тира.
8. **Прямая проверка инварианта бита 63** (дополняет тест №6 "белым ящиком", не полагаясь
   только на конкретные числовые примеры): для большого числа случайно сгенерированных
   (фиксированный seed) вызовов основного `compute(...)` (обычный тир) — результат
   неотрицателен (бит 63 НЕ установлен, `result >= 0`), без исключений. Для большого числа
   случайно сгенерированных вызовов near-tier пути — результат отрицателен (бит 63
   установлен, `result < 0`), без исключений. Дополнительно: явный тест
   `Long.compare(anyNearTierResult, anyNormalTierResult) < 0` для нескольких пар — проверяет
   само направление сравнения, а не только знак результата по отдельности, так как именно
   направление (не только факт разных знаков) обеспечивает корректный порядок в очереди.
   Это самый надёжный, не зависящий от конкретных числовых значений способ поймать
   регрессию, если реализация случайно нарушит инвариант разделения тиров при будущих
   изменениях формулы обычного тира.
9. **Тест на отсутствие переполнения near-tier арифметики**: для максимально возможного
   значения within-tier offset (согласно выбранной в реализации маске/сдвигу — например,
   если используются низкие 32 бита, максимум `0xFFFFFFFFL`) — результат
   `Long.MIN_VALUE + maxOffset` остаётся отрицательным (`< 0`), не переворачивается в
   положительное значение через переполнение. Это прямая проверка арифметической
   безопасности, описанной в Javadoc `NEAR_PLAYER_PRIORITY_BASE`.

## Критерии приёмки
1. Файлы в `ev-meshing/src/main/java/dev/ev/meshing/priority/`:
   `ScreenSpaceErrorMetric.java`, `MeshPriority.java`.
2. Все юнит-тесты проходят.
3. Модуль компилируется без ошибок, без NeoForge/LWJGL зависимостей.
4. Битовая раскладка `MeshPriority.compute` задокументирована в Javadoc класса достаточно
   точно, чтобы тикет 15 мог извлечь номер корзины сдвигом без дополнительных вопросов.
