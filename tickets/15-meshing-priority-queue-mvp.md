# Тикет 15-mvp — MeshTaskQueue (простая PriorityBlockingQueue)

## Контекст
Часть Волны 1 (MVP) плана EV — см. `MVP_INDEX.md` за полным обоснованием MVP-first
подхода. Это упрощённая версия очереди задач мешинга: обычная встроенная в JDK
`PriorityBlockingQueue`, БЕЗ lock-free work-stealing, БЕЗ шардирования по воркер-потокам.
Более сложная версия (`15-meshing-work-stealing-queue-opt.md`) вводится только если
профилирование (`P0-profiling-checkpoint.md`) реально покажет узкое место именно здесь.

## Готовый контракт из зависимости (тикет 14, уже реализован)
```java
package dev.ev.meshing.priority;
public final class MeshPriority {
    public static long compute(int lodLevel, int maxLodLevel, int attempts,
                                float cosAngleToViewDir, float facingThreshold, long insertionSeq);
    // "lower = higher priority" convention — см. Javadoc тикета 14 за полной битовой раскладкой.
}
```

## Задача

### `MeshTaskQueue`
```java
package dev.ev.meshing.queue;

import java.util.concurrent.PriorityBlockingQueue;

/**
 * MVP implementation: a single JDK PriorityBlockingQueue ordering tasks by the
 * long priority value from MeshPriority.compute() (lower = higher priority — see
 * ticket 14's Javadoc for the full convention). No work-stealing, no per-worker
 * sharding — every worker thread polls from the same shared queue.
 *
 * See MVP_INDEX.md for why this simple version comes first: PriorityBlockingQueue
 * is a well-tested, well-understood JDK primitive; the more complex lock-free
 * work-stealing alternative (15-meshing-work-stealing-queue-opt.md) is only
 * introduced if profiling (P0-profiling-checkpoint.md) actually shows contention
 * on this queue as a bottleneck — not preemptively.
 *
 * The public contract intentionally matches the method shapes used by
 * 15-meshing-work-stealing-queue-opt.md's MeshTaskQueue as closely as reasonably
 * possible (submit/poll), though exact parameter lists may differ slightly since
 * this MVP version has no concept of "workerHint" (there is only one shared
 * queue, not per-worker deques) — document any such difference clearly so a
 * future swap to the opt version has a clear, minimal adaptation surface at
 * call sites, even if it isn't a byte-for-byte identical signature.
 */
public final class MeshTaskQueue<T> {

    public MeshTaskQueue() { /* backed by a single PriorityBlockingQueue<Entry<T>> */ }

    /** @param priority result of MeshPriority.compute(...) */
    public void submit(long priority, T task);

    /** Blocks the calling worker thread until a task is available, then returns it. */
    public T poll();

    /** Non-blocking variant: returns null immediately if the queue is empty. */
    public T pollNonBlocking();

    /** Current queue size, for metrics. */
    public int size();
}
```

## Требования к реализации
1. Внутри — `PriorityBlockingQueue<Entry<T>>`, где `Entry<T>` — простая обёртка `(long
   priority, T task)`, implementing `Comparable<Entry<T>>` по `priority` (естественный
   порядок: меньшее значение приоритета — выше в очереди, согласно конвенции "lower = higher
   priority" из тикета 14).
2. `poll()` — используй `PriorityBlockingQueue.take()` (блокирующий вызов, ждёт появления
   элемента) как основу.
3. `pollNonBlocking()` — используй `PriorityBlockingQueue.poll()` (не путать с методом этого
   же класса `poll()` без аргументов — именованный по-другому здесь во избежание путаницы;
   `java.util.concurrent.PriorityBlockingQueue.poll()` без таймаута уже non-blocking по
   умолчанию в самом JDK — используй его напрямую для `pollNonBlocking()`).
4. Отправляй метрику `MetricsRegistry.recordQueueDepth("mesh-task-queue", size())`
   периодически (не на каждый вызов) — та же наблюдаемость, что и в MVP-версии кэша (тикет
   08-mvp), нужна для `P0-profiling-checkpoint.md`. Если `MetricsRegistry` ещё не
   пробрасывается в конструктор в этой версии — добавь параметр конструктора для него (не
   оставляй эту очередь без наблюдаемости, это единственный способ понять позже, нужен ли
   `15-opt`).
5. Никаких зависимостей на NeoForge/LWJGL.

## Юнит-тесты (обязательно, JUnit 5)

Создай `MeshTaskQueueTest`:
1. `submit` с высоким приоритетом (меньшее число) и `submit` с низким приоритетом (большее
   число) → `poll()` возвращает сначала задачу с высоким приоритетом.
2. `pollNonBlocking()` на пустой очереди возвращает `null` немедленно (не блокирует).
3. `size()` корректно отражает число задач в очереди до/после `submit`/`poll`.
4. Конкурентный smoke-тест: N потоков-производителей вызывают `submit` со случайными
   приоритетами, M потоков-потребителей вызывают `poll()` в цикле, пока не извлекут ожидаемое
   общее число элементов — все элементы успешно извлекаются без потерь (это встроенная в JDK
   структура, тест здесь скорее проверяет корректность ОБЁРТКИ `Entry<T>`/`Comparable`, не
   саму `PriorityBlockingQueue`, чья корректность уже гарантирована JDK).
5. Задачи с одинаковым приоритетом — оба извлекаются в конечном счёте, ни одна не теряется
   (не важен точный порядок между ними при равном приоритете, важно отсутствие потери).
6. **Совместимость с двухъярусной схемой тикета 14**: `submit` задачи с приоритетом,
   полученным из near-tier пути `MeshPriority.compute(...)` (отрицательный `long`, см. тикет
   14), и отдельно задачи с приоритетом из обычного пути (неотрицательный `long`) → `poll()`
   возвращает near-tier задачу первой. В отличие от opt-версии (`15-opt`, где этот же
   инвариант потребовал специального XOR-преобразования при извлечении bucket index — см.
   тот файл за подробностями), здесь это должно работать "бесплатно" через естественный
   `Comparable<Long>` порядок (отрицательные `long` меньше положительных по стандартному
   Java-сравнению) — тест подтверждает это ожидание, а не просто предполагает его.

## Критерии приёмки
1. Файл `ev-meshing/src/main/java/dev/ev/meshing/queue/MeshTaskQueue.java`.
2. Все юнит-тесты проходят.
3. Модуль компилируется без ошибок, без NeoForge/LWJGL зависимостей.
4. Javadoc класса явно документирует, что это MVP/Волна-1 реализация и что `15-opt`
   существует как замена, если профилирование покажет нужду.
5. `PROJECT_INDEX.md` (тикет 32) обновлён с пометкой "MeshTaskQueue — MVP
   (PriorityBlockingQueue) версия активна, opt-версия (Chase-Lev) в банке, не применена".
