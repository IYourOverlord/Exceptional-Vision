# Тикет 15-opt — Sharded Work-Stealing Bucket Queue (Chase-Lev deque)

## ⚠️ Когда это применять

Это тикет Волны 2 (см. `MVP_INDEX.md`). **Не выполняй его**, пока не пройден
`P0-profiling-checkpoint.md` и результаты не показали один из следующих диагностических
признаков:
- Заметный lock/contention overhead на `MeshTaskQueue` (из `15-meshing-priority-queue-mvp.md`,
  использующей `PriorityBlockingQueue`) во время холодного старта, измеренный через JFR.
- Latency между постановкой задачи в очередь и её взятием worker-потоком заметно растёт при
  большом backlog задач (сотни тысяч секций в очереди одновременно) — что указывает на
  `O(log n)`-вставку под общей блокировкой как узкое место, а не просто на общую загрузку CPU
  самой работой мешинга.

Если ни один из этих признаков не подтверждён — MVP-версия
(`15-meshing-priority-queue-mvp.md`) остаётся действующей реализацией.

Этот тикет **заменяет внутреннюю реализацию** класса, обслуживающего постановку/извлечение
задач мешинга, сохраняя тот же публичный контракт использования (submit/poll по приоритету),
что и MVP-версия.

## Контекст
EV — мод дальней прорисовки для NeoForge 1.21.1. Модуль `ev-meshing`, чистый Java.
Реализует очередь задач мешинга для пула worker-потоков, обоснованную в PERFORMANCE_MATH.md
раздел A.4: единая `PriorityBlockingQueue` под высокой конкурентностью (холодный старт —
сотни тысяч задач за секунды) становится узким местом из-за `O(log n)` вставки под общей
блокировкой. Решение — по одной lock-free work-stealing очереди (Chase-Lev deque) на
worker-поток, с приоритетом, квантованным в фиксированное число "корзин" (bucket queue,
`O(1)` вставка/извлечение вместо кучи), плюс возможность "воровать" работу у других потоков,
когда собственная очередь пуста.

## Готовый контракт из зависимости (тикет 14, уже реализован)
```java
package dev.ev.meshing.priority;
public final class MeshPriority {
    public static long compute(int lodLevel, int maxLodLevel, int attempts,
                                float cosAngleToViewDir, float facingThreshold, long insertionSeq);
    // Возвращает long по конвенции "lower = higher priority" — см. полный Javadoc в
    // тикете 14 за точной битовой раскладкой, включая двухъярусную near-player схему
    // (near-tier результаты — ОТРИЦАТЕЛЬНЫЕ long, т.к. бит 63 зарезервирован как флаг
    // тира: near-tier имеет бит 63 = 1, normal-tier — бит 63 = 0; см. тикет 14,
    // Javadoc NEAR_PLAYER_PRIORITY_BASE, за полным математическим обоснованием этого
    // направления и почему оно НЕ может быть выбрано произвольно).
    //
    // КРИТИЧНО для этого тикета: bucket index НЕЛЬЗЯ извлекать наивным
    // `priority >>> (64 - 6)` — при такой формуле near-tier значения (отрицательные,
    // бит 63 = 1) дадут БОЛЬШИЕ unsigned-значения в старших 6 битах (т.к. Long.MIN_VALUE
    // в unsigned-интерпретации равен 2^63, что "больше" любого positive normal-tier
    // значения меньше 2^63), то есть near-tier секции попадут в bucket'ы с большим
    // номером — а в этой системе bucket 0 = высший приоритет (см. требования ниже),
    // так что наивная формула ИНВЕРТИРУЕТ приоритет near-tier секций относительно
    // намерения тикета 14. Правильная формула инвертирует знаковый бит перед
    // извлечением bucket index — см. требование 1 ниже за точным кодом.
}
```

## Задача

### `ChaseLevDeque<T>`
```java
package dev.ev.meshing.queue;

/**
 * Lock-free work-stealing double-ended queue (Chase-Lev algorithm). The owning
 * thread pushes and pops from the "bottom" without synchronization overhead beyond
 * a memory fence; other threads may concurrently "steal" from the "top" using a
 * CAS loop. Backed by a resizable circular array.
 *
 * Reference algorithm: Chase & Lev, "Dynamic Circular Work-Stealing Deque" (2005).
 * Implement it correctly and safely for the JVM memory model (use VarHandle or
 * AtomicLongArray/AtomicReferenceArray as appropriate — do not use plain array
 * writes for fields read concurrently across threads without synchronization).
 */
public final class ChaseLevDeque<T> {

    public ChaseLevDeque(int initialCapacityPowerOfTwo) { /* ... */ }

    /** Called only by the owning thread. */
    public void pushBottom(T item);

    /** Called only by the owning thread. Returns null if empty. */
    public T popBottom();

    /** Called by any thread (including the owner) to steal from the top. Returns null if empty or lost a race. */
    public T steal();

    public boolean isEmpty();
}
```

### `MeshTaskQueue`
```java
package dev.ev.meshing.queue;

/**
 * Sharded (per-worker-thread) bucket-priority work-stealing queue for mesh-build
 * tasks, per PERFORMANCE_MATH.md section A.4. Each worker owns BUCKET_COUNT
 * ChaseLevDeques (one per priority bucket); submission targets a specific worker's
 * buckets (by workerHint, e.g. round-robin or spatial-locality hash chosen by the
 * caller — this class does not decide the hint), polling first drains the calling
 * worker's own buckets from highest to lowest priority, then steals from other
 * workers' buckets (also highest-to-lowest) if its own are empty.
 */
public final class MeshTaskQueue<T> {

    public static final int BUCKET_COUNT = 64;

    public MeshTaskQueue(int workerCount) { /* ... */ }

    /**
     * @param workerHint which worker's deques to push into, in [0, workerCount)
     * @param priority result of MeshPriority.compute(...) — bucket index derived internally
     */
    public void submit(int workerHint, long priority, T task);

    /**
     * Polls for work as the given worker: own buckets first (highest priority bucket
     * first), then steals from other workers if own buckets are all empty.
     * @param workerId which worker is calling (in [0, workerCount)) — determines
     *        which deques are "own" vs "stealable"
     */
    public T poll(int workerId);

    /** Approximate total item count across all workers/buckets, for metrics (not exact under concurrency). */
    public int approximateSize();
}
```

## Требования к реализации

1. **`ChaseLevDeque`**: реализуй корректный lock-free алгоритм. Ключевые моменты
   классического Chase-Lev:
   - Внутреннее хранилище — массив (степень двойки для дешёвого modulo через битовую маску),
     с возможностью роста (реаллокация большего массива), если `pushBottom` заполняет текущую
     ёмкость — задокументируй, как именно растишь массив без потери элементов, которые
     конкурентно ворует другой поток (это самая тонкая часть алгоритма — если не уверен в
     безопасной реализации growable-версии, ДОПУСТИМО реализовать fixed-capacity версию с
     `IllegalStateException` при переполнении и явно задокументировать это ограничение —
     не жертвуй корректностью ради поддержки роста, если не уверен; фиксированный размер
     с разумным запасом — приемлемый компромисс для этого тикета).
   - `top`/`bottom` индексы — атомарные (`AtomicLong` или `VarHandle` над полями).
   - `steal()` использует CAS на `top`, чтобы разрешить гонку с другим вором и с
     `popBottom()` того же элемента.
2. **`MeshTaskQueue`**: `submit` вычисляет bucket index из `priority` через формулу
   `bucket = (int) ((priority ^ Long.MIN_VALUE) >>> (64 - 6))`, НЕ через наивный
   `priority >>> (64 - 6)` без XOR — см. подробное объяснение в разделе "Готовый контракт"
   выше. XOR с `Long.MIN_VALUE` инвертирует знаковый бит, переводя signed-порядок `long`
   (где near-tier — отрицательные значения, обязанные сравниваться как "меньше", то есть
   выше приоритет, согласно тикету 14) в unsigned-совместимый порядок для битового
   извлечения: после XOR, near-tier значения (изначально с битом 63=1) получают бит 63=0
   и оказываются в диапазоне МЕНЬШИХ unsigned-значений старших 6 бит — то есть попадают
   именно в bucket 0 (или близкие к нему низкие номера, в зависимости от within-tier
   offset), что корректно соответствует их более высокому приоритету. Normal-tier значения
   (изначально бит 63=0) после XOR получают бит 63=1 и оказываются в диапазоне БОЛЬШИХ
   unsigned-значений (bucket'ы с более высокими номерами) — корректно более низкий
   приоритет относительно near-tier. Обязательно протестируй это преобразование отдельным
   юнит-тестом (см. тесты ниже) — это единственная надёжная проверка, что интеграция с
   двухъярусной схемой тикета 14 действительно работает, а не инвертирует приоритет тихо.
3. `poll(workerId)` — сначала проходит собственные `BUCKET_COUNT` deque от бакета 0 (высший
   приоритет) до 63, вызывая `popBottom()`; при первом непустом результате — возвращает его.
   Если все собственные пусты — переходит к воровству: перебирает других воркеров (например,
   в случайном или round-robin порядке, начиная со случайного не-себя воркера, чтобы не
   создавать систематическое давление кражи всегда с одного и того же соседа), для каждого —
   опять же от бакета 0 до 63, вызывая `steal()`.
4. Полная безопасность относительно JVM memory model — не используй "plain" не-volatile поля
   там, где на них смотрят несколько потоков без другой синхронизации.
5. Никаких зависимостей на NeoForge/LWJGL.

## Юнит-тесты (обязательно, JUnit 5)

Создай `ChaseLevDequeTest`:
1. Однопоточный сценарий: `pushBottom` несколько элементов, затем `popBottom` — возвращает
   их в порядке LIFO (стандартное поведение deque bottom-операций).
2. Однопоточный: `popBottom` на пустой очереди возвращает `null`.
3. `steal()` на пустой очереди возвращает `null`.
4. Смешанный однопоточный: `pushBottom` N элементов, `steal()` (вызванный тем же потоком,
   что владеет очередью, что валидно для теста хоть и не типичный сценарий использования)
   должен вернуть элемент с "top" стороны (FIFO-конец), не тот же, что вернул бы
   `popBottom()` — проверка, что `steal` и `popBottom` берут с разных концов.
5. **Конкурентный тест** (важно): один поток непрерывно `pushBottom`/`popBottom` (владелец),
   несколько других потоков одновременно вызывают `steal()`. После завершения всех операций
   — суммарное число успешно извлечённых элементов (через `popBottom` + все `steal`) равно
   числу вставленных, без дублей и без потерь (используй атомарный счётчик/набор ID
   элементов, чтобы проверить отсутствие дублирования и потерь — это единственный надёжный
   способ проверить lock-free корректность). Запусти с достаточным числом итераций/потоков,
   чтобы дать шанс проявиться гонкам.
6. Тест переполнения при fixed-capacity реализации (если выбран этот путь согласно п.1
   требований) — `pushBottom` сверх ёмкости бросает `IllegalStateException`
   (задокументированное поведение), не молча теряет данные и не падает с
   `ArrayIndexOutOfBoundsException`.

Создай `MeshTaskQueueTest`:
1. `submit` с высоким приоритетом (bucket 0) и `submit` с низким приоритетом (bucket 63) на
   один и тот же `workerHint` → `poll` этим же `workerId` возвращает сначала задачу с bucket 0.
2. `submit` на `workerHint=0`, `poll(workerId=1)` (другой воркер, чья собственная очередь
   пуста) — успешно ворует и возвращает задачу через steal-путь.
3. `poll` на полностью пустой (все воркеры, все бакеты) очереди возвращает `null`.
4. Конкурентный smoke-тест: N потоков-производителей вызывают `submit` со случайными
   приоритетами/workerHint, M потоков-потребителей вызывают `poll` в цикле пока не
   извлекут ожидаемое общее число элементов — все элементы успешно извлекаются без потерь.
5. **Критический тест интеграции с двухъярусной схемой тикета 14**: `submit` задачи с
   приоритетом, полученным из near-tier пути `MeshPriority.compute(...)` (отрицательный
   `long`, см. тикет 14), и отдельно задачи с приоритетом из обычного (normal-tier) пути
   (неотрицательный `long`) — на один и тот же `workerHint` — `poll` этим же `workerId`
   ДОЛЖЕН вернуть near-tier задачу первой. Это прямая проверка того, что формула
   `bucket = (int) ((priority ^ Long.MIN_VALUE) >>> (64 - 6))` из требования 2 действительно
   корректно интегрируется с направлением бита 63, выбранным в тикете 14 — без этого теста
   инверсия приоритета (near-tier тихо получает bucket с большим номером вместо меньшего)
   осталась бы незамеченной, так как компиляция и большинство других тестов не ловят эту
   ошибку (она проявляется только при смешивании near-tier и normal-tier приоритетов в
   одной очереди — см. также юнит-тест №8 в тикете 14, проверяющий сам факт разделения
   знаков, но не проверяющий, что ЭТА конкретная очередь корректно это использует).

## Критерии приёмки
1. Файлы в `ev-meshing/src/main/java/dev/ev/meshing/queue/`:
   `ChaseLevDeque.java`, `MeshTaskQueue.java`.
2. Все юнит-тесты проходят, особенно конкурентные (№5 в `ChaseLevDequeTest`, №4 в
   `MeshTaskQueueTest`) — если есть любые сомнения в потокобезопасности реализации,
   пересмотри её, а не ослабляй тест.
3. Модуль компилируется без ошибок, без NeoForge/LWJGL зависимостей.
4. Если выбран путь fixed-capacity (не growable) для `ChaseLevDeque` — это явно
   задокументировано в Javadoc класса с указанием ёмкости по умолчанию и поведения при
   переполнении.
