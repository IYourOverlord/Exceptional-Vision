# Тикет 21-mvp — CPU-Side Frustum Culling & Simple Traversal

## Контекст
Часть Волны 1 (MVP) плана EV — см. `MVP_INDEX.md` за полным обоснованием MVP-first
подхода. Это САМОЕ существенное упрощение относительно "целевой" архитектуры: вместо
GPU-driven persistent-kernel traversal (тикет `21-gpu-persistent-traversal-shader-opt.md`),
MVP использует простой, линейный, CPU-side проход по всем известным (загруженным)
секциям: для каждой — frustum test (нет occlusion culling в MVP, см. также
`22-gpu-hiz-occlusion-opt.md`, которого в этой волне нет), и видимые секции передаются на
рендер обычными draw call'ами (не indirect, см. `23-gpu-indirect-multidraw-opt.md`).

Это архитектурно проще персистентного GPU traversal и НЕ требует решения самой рискованной
проблемы всего проекта (GPU work-stealing termination detection) до того, как есть хоть
что-то работающее и измеримое. Стоимость: `O(numLoadedSections)` CPU-работы каждый кадр,
без persistent kernel'ов, без temporal coherence (эта версия делает полный проход каждый
кадр — да, это заведомо не оптимально, это осознанное упрощение, чтобы иметь простую,
предсказуемую точку отсчёта для последующего профилирования).

## Готовый контракт из зависимостей (тикеты 01, 04, 05, 20-gpu-node-buffer-mvp.md — уже реализованы)
```java
package dev.ev.api;
public record SectionPos(int level, int x, int y, int z) {
    public int sizeInBlocks();
    public long minBlockX(); public long minBlockY(); public long minBlockZ();
}

package dev.ev.api.metrics;
public interface MetricsRegistry {
    void recordGpuPassDuration(String passName, long nanos);
    // ... (см. полный контракт, тикет 05)
}

package dev.ev.api.gpu;
public interface RenderBackend {
    void submit(CommandList commands);
    // ... (см. полный контракт, тикет 04)
}
```

## Задача

### `FrustumTester`
```java
package dev.ev.render.culling;

/**
 * Simple CPU-side sphere-vs-frustum-planes visibility test. Pure math, no GPU
 * dependency — used by SimpleTraversal (below) to decide which loaded sections
 * are visible this frame.
 */
public final class FrustumTester {

    /** 6 planes (xyz = normal, w = distance from origin), typically extracted
     * from the current view-projection matrix once per frame by the caller. */
    public FrustumTester(float[] planeXyzw24 /* 6 planes * 4 floats = 24 */) { /* ... */ }

    /** True if the bounding sphere (worldX/Y/Z center, radius) is at least
     * partially inside the frustum (standard conservative sphere-vs-plane test:
     * for each plane, if the sphere center's signed distance to the plane is
     * less than -radius, the sphere is fully outside — reject; otherwise it's
     * potentially visible against this plane, continue to the next). */
    public boolean isVisible(float worldX, float worldY, float worldZ, float radius);
}
```

### `SimpleTraversal`
```java
package dev.ev.render.culling;

import dev.ev.api.SectionPos;
import java.util.List;

/**
 * MVP traversal: linear O(numLoadedSections) CPU-side pass over every currently
 * loaded section, testing frustum visibility for each, with no occlusion culling
 * and no temporal coherence (full pass every frame, even if the camera hasn't
 * moved — see MVP_INDEX.md for why this is an intentional, accepted simplification
 * for Wave 1, to be replaced by 21-gpu-persistent-traversal-shader-opt.md and/or
 * 25-render-temporal-reprojection-opt.md only if profiling
 * (P0-profiling-checkpoint.md) shows this pass is actually a meaningful fraction
 * of frame time).
 *
 * This class does NOT touch the GPU directly — it produces a plain list of
 * visible SectionPos, which the caller (likely in ev-neoforge's render hook,
 * ticket 27) uses to issue ordinary (non-indirect) draw calls, one per visible
 * section, via RenderBackend (ticket 04).
 */
public final class SimpleTraversal {

    /**
     * @param metrics used to report per-call timing via
     *        MetricsRegistry.recordGpuPassDuration("cpu-traversal", nanos) — despite
     *        the method name (shared with actual GPU pass timing for consistency
     *        of the debug overlay/P0 profiling data, see requirement below), this
     *        records CPU-side wall-clock time for this MVP's traversal pass, since
     *        there is no GPU pass here yet. This instrumentation is NOT optional —
     *        P0-profiling-checkpoint.md's Scenario B explicitly requires a
     *        "CPU time vs GPU time breakdown" metric, and this is the primary
     *        CPU-side cost this class exists to measure; omitting it would force
     *        profiling to rely solely on external tools (JFR/RenderDoc) for the
     *        single most important MVP performance question this ticket answers.
     */
    public SimpleTraversal(dev.ev.api.metrics.MetricsRegistry metrics) { /* ... */ }

    /**
     * @param loadedSections every section currently resident (has uploaded GPU
     *        geometry ready to draw) — the caller is responsible for maintaining
     *        this set (e.g. from SectionCache / the meshing pipeline's output),
     *        this class only filters it by visibility, it does not track residency.
     * @param sectionBounds function/lookup providing world-space bounding sphere
     *        (center + radius) for a given SectionPos — likely backed by
     *        SectionPos.minBlockX/Y/Z() + sizeInBlocks()/2 for center and an
     *        appropriately computed radius (e.g. sizeInBlocks() * sqrt(3)/2 for
     *        a cube's circumscribed sphere).
     *
     * Implementation must wrap the full linear pass in System.nanoTime() before/after
     * and report the elapsed duration via metrics.recordGpuPassDuration("cpu-traversal", elapsedNanos)
     * before returning — every call, not sampled/throttled (unlike some other
     * MVP metrics recommended to batch — see ticket 08-mvp requirement 5 — this
     * one is cheap to record every call since it's already timing the call itself,
     * and P0 profiling needs per-frame granularity here specifically).
     */
    public List<SectionPos> computeVisible(
        List<SectionPos> loadedSections,
        FrustumTester frustum,
        java.util.function.Function<SectionPos, float[]> sectionBounds // returns [x,y,z,radius]
    );
}
```

## Требования к реализации
1. `FrustumTester.isVisible` — стандартный sphere-vs-6-planes тест, каждая плоскость
   представлена как `(nx, ny, nz, d)` где `nx*x + ny*y + nz*z + d` — знаковое расстояние от
   точки до плоскости (положительное — внутри полупространства frustum). Для каждой из 6
   плоскостей: если `signedDistance(center) < -radius` — сфера полностью снаружи этой
   плоскости, сразу возвращай `false` (early exit, не проверяй остальные плоскости). Если ни
   одна плоскость не отвергла сферу — возвращай `true`.
2. `SimpleTraversal.computeVisible` — простой линейный проход (`for` по `loadedSections`),
   для каждой секции получи bounding sphere через `sectionBounds.apply(pos)`, протестируй
   через `frustum.isVisible(...)`, добавь в результат если видима. Никакой сортировки по
   приоритету здесь не требуется (это не очередь построения, а просто список того, что рисовать
   в этом кадре) — MeshPriority/приоритизация (тикет 14) уже применена раньше, на этапе
   решения, ЧТО строить и загружать, не на этапе решения, что рисовать из уже загруженного.
3. Ни `FrustumTester`, ни `SimpleTraversal` не должны обращаться к `org.lwjgl.*` напрямую —
   они живут в `ev-render`, который использует GPU только через `RenderBackend`
   (интерфейс, тикет 04) — сам этот тикет не эмитит GPU-команды, он только решает, ЧТО видимо,
   вызывающий код (тикет 27) берёт этот список и делает обычные draw call'ы.
4. Не пытайся оптимизировать этот класс преждевременно (например, не добавляй spatial
   partitioning/BVH "на всякий случай") — весь смысл MVP-версии в том, чтобы быть максимально
   простой отправной точкой для профилирования, а не предвосхищённой оптимизацией.
5. Никакого мутируемого статического состояния.

## Юнит-тесты (обязательно, JUnit 5)

Создай `FrustumTesterTest`:
1. Сфера точно в центре simple orthographic-подобного тестового frustum (все 6 плоскостей
   заданы вручную для простого случая, например AABB-подобный frustum) → `isVisible == true`.
2. Сфера далеко за пределами любой из 6 плоскостей → `isVisible == false`.
3. Сфера, частично пересекающая границу frustum (центр снаружи, но радиус достаточен, чтобы
   зацепить объём) → `isVisible == true` (консервативный тест — частичная видимость считается
   видимой, не отвергается).
4. Сфера с радиусом 0 (точка) точно на границе одной из плоскостей → задокументированное,
   протестированное граничное поведение (реши, включительно или исключительно, и протестируй
   именно выбранное поведение).

Создай `SimpleTraversalTest`:
1. Пустой `loadedSections` → пустой результат, `sectionBounds`/`frustum.isVisible` ни разу
   не вызваны (или вызваны 0 раз для frustum, так как нечего тестировать) — тривиальный
   случай, но стоит явно проверить отсутствие исключений.
2. Все секции видимы (fake `FrustumTester`, всегда возвращающий `true`) → результат содержит
   все секции из `loadedSections`, в том же порядке.
3. Ни одна секция не видима (fake `FrustumTester`, всегда `false`) → пустой результат.
4. Смешанный случай (fake `FrustumTester`, возвращающий `true`/`false` в зависимости от
   конкретной позиции) → результат содержит ровно ожидаемое подмножество.
5. **Инструментирование метрик**: с fake `MetricsRegistry` (записывающим все вызовы
   `recordGpuPassDuration`) — после вызова `computeVisible(...)` метод
   `recordGpuPassDuration("cpu-traversal", ...)` вызван РОВНО один раз, с неотрицательным
   значением `nanos` (не проверяй конкретное значение — это wall-clock время, не
   детерминировано — только факт вызова и разумность диапазона, например `>= 0`).

## Критерии приёмки
1. Файлы в `ev-render/src/main/java/dev/ev/render/culling/`: `FrustumTester.java`,
   `SimpleTraversal.java`.
2. Все юнит-тесты проходят, включая тест на инструментирование метрик (№5) — это не
   опциональная часть тикета, см. обоснование в Javadoc конструктора `SimpleTraversal`.
3. Модуль компилируется без ошибок, без `org.lwjgl.*` импортов.
4. Javadoc `SimpleTraversal` явно документирует, что это MVP/Волна-1 реализация без occlusion
   culling и без temporal coherence, и что `21-opt`/`22-opt`/`25-opt` существуют как замены/
   дополнения, применяемые только по результатам `P0-profiling-checkpoint.md`.
5. `PROJECT_INDEX.md` (тикет 32) обновлён с пометкой "Traversal — MVP (CPU-side linear
   frustum-only) версия активна; GPU persistent-kernel traversal (21-opt), Hi-Z occlusion
   (22-opt), temporal reprojection (25-opt) в банке, не применены".
