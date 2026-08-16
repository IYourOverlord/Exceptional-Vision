# Тикет 05 — Интерфейсы наблюдаемости (MetricsRegistry)

## Контекст
EV — мод дальней прорисовки для NeoForge 1.21.1. Модуль `ev-api`, чистый Java, без
NeoForge/LWJGL. Архитектурное требование проекта: наблюдаемость (метрики очередей, cache hit
rate, GPU pass timing) должна быть частью публичного API с первого дня, а не добавляться
постфактум под debug-флагом. Метрики нужны и для `/ev debug` команды (тикет 29), и для
F3-оверлея, и для внутренней адаптивной логики (например, снижение render distance при
нехватке VRAM — см. `QueueBudget` в архитектурном документе, будет использовано в тикете 8/17).

## Задача
Создать в пакете `dev.ev.api.metrics` следующие типы.

### `MetricsRegistry`
```java
package dev.ev.api.metrics;

/**
 * Central registry for lightweight runtime metrics across all EV subsystems.
 * A single instance is created at mod startup and passed to every subsystem that
 * needs to report metrics. Implementations must be safe to call from any thread
 * (worker threads report meshing/storage metrics, the render thread reports GPU
 * pass timings) with minimal overhead — recording a metric must not allocate on
 * the hot path in the common case.
 */
public interface MetricsRegistry {

    void recordQueueDepth(String queueName, int depth);

    void recordCacheAccess(String cacheName, boolean hit);

    void recordGpuPassDuration(String passName, long nanos);

    void recordCounter(String name, long delta);

    /** Updates the current staged import status (see ImportStageStatus). Called
     * periodically (not necessarily on every single task transition — batching
     * updates, e.g. once per tick, is acceptable and preferred to avoid overhead
     * on a potentially very hot path during bulk cold-start import) by whichever
     * subsystem owns the import pipeline (see ticket 15's MeshTaskQueue and
     * ticket 08's SectionCache as likely sources of this data). */
    void recordImportStageStatus(ImportStageStatus status);

    /** Produces an immutable snapshot of all currently tracked metrics, for display/logging. */
    MetricsSnapshot snapshot();
}
```

### `MetricsSnapshot`
```java
package dev.ev.api.metrics;

import java.util.Map;

/**
 * Immutable point-in-time view of all registered metrics, suitable for rendering
 * in a debug overlay or serializing to a log line.
 */
public record MetricsSnapshot(
    Map<String, Integer> queueDepths,
    Map<String, Double> cacheHitRates,
    Map<String, Long> gpuPassDurationsNanos,
    Map<String, Long> counters,
    ImportStageStatus importStageStatus
) {
    public static MetricsSnapshot empty() {
        return new MetricsSnapshot(Map.of(), Map.of(), Map.of(), Map.of(), ImportStageStatus.empty());
    }
}
```

### `ImportStageStatus`
```java
package dev.ev.api.metrics;

/**
 * Snapshot of how many sections/tasks are in each stage of the cold-start bulk
 * import/build pipeline (queued-for-read, retrying-after-failure, actively
 * building, done). Distinct from the generic queue-depth/counter metrics above
 * because during cold start, a single "queue depth" number does not tell the
 * user WHERE the backlog actually is — this staged breakdown is what a user or
 * developer actually needs to diagnose whether progress is being made or the
 * pipeline is stuck (e.g. large "retrying" count indicates a systemic problem
 * like disk contention or a bug, not just "still working").
 *
 * Modeled after an explicit staged status breakdown found valuable in an
 * independent implementation of this same mod concept (Exceptional Vision,
 * `/ev status` command) — a plain aggregate queue-depth number was found in
 * practice to be less actionable for diagnosing stuck/slow bulk imports than
 * an explicit per-stage breakdown.
 */
public record ImportStageStatus(
    int queuedForRead,
    int retryingAfterFailure,
    int activelyBuilding,
    int completed,
    int totalKnown
) {
    public static ImportStageStatus empty() {
        return new ImportStageStatus(0, 0, 0, 0, 0);
    }

    /** Fraction complete in [0,1], or 0.0 if totalKnown is 0 (nothing to report yet, not division-by-zero garbage). */
    public double completionFraction() {
        return totalKnown == 0 ? 0.0 : (double) completed / totalKnown;
    }
}
```

## Требования
1. Ровно эти сигнатуры, пакет `dev.ev.api.metrics`.
2. Полные Javadoc.
3. Только контракты в этом тикете — не пиши реализацию `MetricsRegistry` здесь (реализация
   может появиться либо как отдельный будущий тикет, либо быть встроена в тикет 27/29 —
   не входит в объём текущего тикета, оставь это интерфейсом).
4. Не зависит от NeoForge/LWJGL.

## Критерии приёмки
1. Файлы созданы в `ev-api/src/main/java/dev/ev/api/metrics/`:
   `MetricsRegistry.java`, `MetricsSnapshot.java`, `ImportStageStatus.java`.
2. Модуль компилируется без ошибок.
3. Юнит-тест: `MetricsSnapshot.empty()` возвращает пустые, но не-null коллекции по всем полям,
   и `importStageStatus()` равен `ImportStageStatus.empty()`.
4. Юнит-тест: `ImportStageStatus.completionFraction()` возвращает `0.0` (не `NaN`/исключение)
   при `totalKnown == 0`, и корректную дробь при ненулевом `totalKnown` (например, `completed=50,
   totalKnown=200` → `0.25`).
