# Тикет 25-opt — Temporal Reprojection (working set + delta pass)

## ⚠️ Когда это применять

Это тикет Волны 2 (см. `MVP_INDEX.md`). В Волне 1 (MVP) `21-gpu-simple-traversal-mvp.md`
делает полный проход по всем загруженным секциям каждый кадр — простое, предсказуемое,
но заведомо не оптимальное поведение (стоимость кадра не падает при неподвижной камере).
**Не выполняй этот тикет**, пока не пройден `P0-profiling-checkpoint.md` и результаты не
показали:
- "Frame time при неподвижной камере" (Сценарий B) заметно отличается от нуля/фонового
  значения — то есть traversal реально стоит заметного времени даже когда камере некуда
  давать новую видимую геометрию. Если этот показатель уже мал (например, общее число
  загруженных секций при типичном render distance невелико, и линейный CPU-проход по ним
  дёшев сам по себе) — этот тикет не даст заметного выигрыша, пропусти его.
- Разница между "Frame time при плавном движении" и "Frame time при резком развороте"
  подтверждает, что стоимость действительно зависит от объёма изменившегося/нового видимого
  множества, не постоянна независимо от движения — что подтверждает, что инкрементальный
  подход (переиспользование working set) применим и даст эффект.

Если `21-opt` (GPU persistent-kernel traversal) уже применён к моменту, когда рассматривается
этот тикет — см. явную рекомендацию в `21-gpu-persistent-traversal-shader-opt.md`: этот тикет
(`25-opt`) стоит попробовать ПЕРЕД `21-opt`, не после, если оба кажутся релевантными
одновременно — инкрементальный CPU-side traversal может закрыть тот же разрыв дешевле, без
риска, сопутствующего переносу всего traversal на GPU.

## Контекст
EV — мод дальней прорисовки для NeoForge 1.21.1. Модуль `ev-render`. Реализует
temporal coherence оптимизацию, обоснованную в PERFORMANCE_MATH.md разделе B.2: между
соседними кадрами камера обычно сдвигается на доли градуса, поэтому полный обход дерева
(тикет 21, traversal) с нуля каждый кадр избыточен. Вместо этого: (1) **re-verify pass** —
для узлов, видимых в прошлом кадре (working set), повторно проверяем occlusion/frustum
(дёшево — набор мал); (2) **delta pass** — обходим только те top-level поддеревья, чей
bounding volume попадает в разницу между frustum текущего и предыдущего кадра. Полный
traversal с нуля запускается только при "разрыве когерентности" (телепорт, резкий рывок
камеры, смена измерения).

Это — оркестрационная логика НА JAVA-СТОРОНЕ модуля `ev-render`, решающая, КАКОЙ
режим traversal (full / re-verify+delta) запустить в данном кадре, и с какими параметрами
диспетчеризовать `TraversalDispatcher` (тикет 21). Сама GPU-логика re-verify/delta passes
(отдельные compute dispatch'ы с другим начальным содержимым work queue, чем full traversal)
— тоже часть этого тикета, но как ДОПОЛНЕНИЕ к существующему `traversal.comp` (тикет 21),
не переписывание его с нуля.

## Эмпирическое подтверждение важности этого тикета (Exceptional Vision)

Другой независимо реализованный мод той же концепции (Exceptional Vision,
`IYourOverlord/Exceptional-Vision`, реально плейтестированный) **не имеет вообще никакой
temporal coherence оптимизации** — их GPU culling pass (`quad_cull.comp`) делает полный
линейный проход по ВСЕМ загруженным узлам КАЖДЫЙ кадр, безотносительно того, двигалась ли
камера вообще. Это не гипотетическая, а реально наблюдаемая архитектурная стоимость: их
собственный код явно устроен так, что "дальнейший рендеринг дешевле" там не выполняется —
стоимость кадра константна и пропорциональна общему объёму загруженных данных, а не видимому
множеству. Это прямое, эмпирическое подтверждение того, что данный тикет закрывает реальный,
а не умозрительный разрыв, а не просто теоретическую оптимизацию "на всякий случай" —
альтернативная независимая реализация той же задачи без этой оптимизации имеет ровно ту
проблему, которую B.2 (PERFORMANCE_MATH.md) предсказывал.

## Готовый контракт из зависимостей (тикеты 21, 24 — уже реализованы)
```java
package dev.ev.gpu.traversal;
public final class TraversalDispatcher {
    // constructor(RenderBackend, ...), method: void dispatch(int workgroupCount)
    // (exact signature per ticket 21's final implementation — adapt to it)
}

package dev.ev.render.framegraph;
public interface FramePass {
    void record(dev.ev.api.gpu.CommandList commands);
    String name();
}
public final class FrameGraphBuilder {
    PassBuilder addPass(String name, java.util.function.Supplier<FramePass> passFactory);
    void execute();
}
```

## Задача

### `CameraState`
```java
package dev.ev.render.temporal;

/** Immutable snapshot of camera parameters for one frame, used to detect coherence breaks. */
public record CameraState(
    float x, float y, float z,
    float yawRadians, float pitchRadians,
    float fovYRadians,
    long dimensionId // or an equivalent identifier distinguishing Minecraft dimensions/worlds
) {
    /** Angular difference in radians between this and another camera orientation (yaw+pitch combined, e.g. via dot product of forward vectors). */
    public float angularDeltaRadians(CameraState other);
    public float positionalDelta(CameraState other);
}
```

### `CoherenceDetector`
```java
package dev.ev.render.temporal;

/**
 * Decides whether the current frame's camera state is "coherent" with the previous
 * frame's (small enough movement to justify incremental re-verify+delta traversal)
 * or represents a "break" (teleport, dimension change, large single-frame rotation)
 * requiring a full traversal from scratch.
 */
public final class CoherenceDetector {

    public CoherenceDetector(float maxPositionalDeltaBlocks, float maxAngularDeltaRadians) { /* ... */ }

    /**
     * @return true if incremental (re-verify+delta) traversal is safe to use this
     *         frame; false if a full traversal is required.
     */
    public boolean isCoherent(CameraState previous, CameraState current);
}
```

### `TraversalMode` / `TemporalTraversalCoordinator`
```java
package dev.ev.render.temporal;

public enum TraversalMode { FULL, INCREMENTAL }

/**
 * Orchestrates per-frame traversal mode selection and dispatch. Maintains the
 * previous frame's CameraState and delegates to CoherenceDetector to choose
 * FULL vs INCREMENTAL each frame. On FULL, seeds the GPU work queue with just
 * the tree root (standard full traversal, as in ticket 21). On INCREMENTAL,
 * seeds it with (a) the previous frame's working-set node ids for re-verification
 * and (b) top-level nodes whose bounds intersect the frustum delta region for
 * new-geometry discovery — see requirement 2 below for how these are computed
 * and where they're queued.
 */
public final class TemporalTraversalCoordinator {

    public TemporalTraversalCoordinator(CoherenceDetector detector) { /* ... */ }

    /**
     * Call once per frame before dispatching traversal. Returns which mode was
     * selected (for metrics/debugging) and performs any necessary GPU work-queue
     * seeding as a side effect via the provided seeding callback/buffer handle
     * (exact parameter shape depends on how ticket 21's WorkQueue seeding is
     * exposed — adapt this signature to whatever seeding mechanism ticket 21's
     * TraversalDispatcher or a related class provides; if no such mechanism
     * exists yet, define one here as an extension and document it clearly so
     * ticket 21's owner/future maintainers know this dependency exists).
     */
    public TraversalMode selectModeAndSeed(CameraState currentFrame, WorkQueueSeeder seeder);
}
```

### `WorkQueueSeeder` (интерфейс — связывает этот тикет с реальным GPU-буфером очереди из тикета 21)
```java
package dev.ev.render.temporal;

/**
 * Abstraction for writing initial node ids into the GPU traversal work queue
 * (see ticket 21's WorkQueue SSBO) before dispatch. Implemented by whatever
 * Java-side class owns that buffer (likely extended from TraversalDispatcher
 * in ticket 21, or a sibling class) — this ticket only depends on this
 * interface, not on the concrete GPU buffer plumbing.
 */
public interface WorkQueueSeeder {
    void seedWithRoot();
    void seedWithNodeIds(int[] nodeIds);
}
```

## Требования к реализации

1. **`CoherenceDetector.isCoherent`**: возвращает `false` (требуется полный traversal), если:
   - `previous.dimensionId() != current.dimensionId()` (смена измерения — данные полностью
     другие, никакой working set не переиспользуем);
   - `previous.positionalDelta(current) > maxPositionalDeltaBlocks` (телепорт/резкое
     перемещение);
   - `previous.angularDeltaRadians(current) > maxAngularDeltaRadians` (резкий рывок камеры,
     например быстрый разворот мышью);
   - `previous == null` (первый кадр — нет истории для инкрементального режима).
   Иначе — `true` (инкрементальный режим безопасен).

2. **Working set** — набор `nodeId`, видимых в прошлом кадре (заполняется из `RenderList`
   тикета 21 по итогам ПРЕДЫДУЩЕГО кадра — Java-сторона должна прочитать этот список с GPU
   после кадра N, чтобы использовать его при seeding кадра N+1; либо, если это создаёт
   нежелательный CPU-GPU readback stall — рассмотри альтернативу: держать working set
   ЦЕЛИКОМ на GPU между кадрами, никогда не читая на CPU, и seeding для следующего кадра
   делать отдельным compute-проходом, копирующим `RenderList` кадра N в `WorkQueue` кадра
   N+1 напрямую на GPU. **Предпочти этот GPU-side подход**, если можешь его реализовать
   (избегает readback stall, соответствует духу "GPU-driven" архитектуры всего проекта) —
   задокументируй выбор и, если выбираешь CPU-readback как более простой первый шаг,
   явно отметь GPU-side вариант как желаемое будущее улучшение.

3. **Delta pass (новая геометрия, вошедшая в обзор)**: определение "top-level поддеревьев,
   чей bounding volume пересекает разницу frustum(N) и frustum(N-1)" — реализуй как
   отдельный, отличный от полного root-based traversal, seeding: вместо одного корня,
   заполни work queue набором узлов верхнего уровня дерева (например, все прямые дети корня,
   или узлы на некотором фиксированном "top level" LOD), затем полагайся на обычный
   frustum-тест внутри `traversal.comp` (уже реализован в тикете 21/22) чтобы отсеять те, что
   не входят в текущий frustum — то есть "delta" получается естественно из объединения (а)
   уже-видимого working set (не нужно заново фильтровать по frustum, раз оно уже было видимо
   и движение мало) и (б) top-level узлов, которые traversal сам отфильтрует по текущему
   frustum. Это упрощение по сравнению с точным вычислением геометрической разницы двух
   frustum-объёмов (что сложнее и вряд ли даёт заметно лучший результат при малых
   межкадровых изменениях) — задокументируй этот выбор как осознанное упрощение.

4. Никакого мутируемого статического состояния — `TemporalTraversalCoordinator` хранит
   `previous CameraState` и `working set` как instance-поля (не static), поддерживая
   несколько независимых инстансов для нескольких миров одновременно, если когда-либо
   понадобится (соответствует общему архитектурному принципу проекта).

## Юнит-тесты (обязательно, JUnit 5, без GPU — вся логика этого тикета, кроме
GPU-side working-set copy, если выбран этот путь, чистая Java)

Создай `CoherenceDetectorTest`:
1. Идентичные `CameraState` (нулевое перемещение/поворот) → `isCoherent == true`.
2. Позиционное перемещение чуть меньше порога → `true`; чуть больше — `false`.
3. Угловое перемещение чуть меньше порога → `true`; чуть больше — `false`.
4. Разный `dimensionId` при прочих равных (даже нулевое перемещение) → `false`.
5. `previous == null` → `false` (нет истории — принудительно full traversal на первом кадре).

Создай `TemporalTraversalCoordinatorTest` (с fake `WorkQueueSeeder`, записывающим, какие
методы были вызваны с какими аргументами):
1. Первый вызов (`previous` отсутствует внутри координатора, это первый кадр) → режим
   `FULL`, `seeder.seedWithRoot()` вызван.
2. Второй вызов с камерой, близкой к первой (внутри порогов) → режим `INCREMENTAL`,
   `seeder.seedWithNodeIds(...)` вызван (не `seedWithRoot`).
3. Второй вызов с камерой, далеко от первой (превышает порог) → режим `FULL` снова.
4. После `FULL`-кадра следующий (третий) вызов с малым перемещением относительно ВТОРОГО
   (не первого) кадра → `INCREMENTAL` (проверка, что "previous" корректно обновляется каждый
   кадр, не залипает на самом первом сравнении).

## Критерии приёмки
1. Файлы в `ev-render/src/main/java/dev/ev/render/temporal/`: `CameraState.java`,
   `CoherenceDetector.java`, `TraversalMode.java`, `TemporalTraversalCoordinator.java`,
   `WorkQueueSeeder.java`.
2. Все юнит-тесты проходят.
3. Модуль компилируется без ошибок, без `org.lwjgl.*` импортов.
4. Выбор между GPU-side и CPU-readback подходом для working set (требование 2) явно
   задокументирован в Javadoc `TemporalTraversalCoordinator`, включая обоснование выбора.
