# Тикет 08-opt — SectionCache (шардированный кэш активных секций)

## ⚠️ Когда это применять

Это тикет Волны 2 (см. `MVP_INDEX.md`). **Не выполняй его**, пока не пройден
`P0-profiling-checkpoint.md` и результаты не показали один из следующих диагностических
признаков:
- Заметный lock contention на `SectionCache` (из `08-storage-section-cache-mvp.md`) во
  время холодного старта, измеренный через JFR (потоки суммарно проводят значимую долю
  времени заблокированными на единственной блокировке кэша).
- Throughput секций/сек (Сценарий A из `P0-profiling-checkpoint.md`) заметно ниже
  ожидаемого, и разбивка по стадиям указывает именно на storage-доступ (не на meshing/GPU
  upload) как на узкое место.

Если ни один из этих признаков не подтверждён профилированием — MVP-версия
(`08-storage-section-cache-mvp.md`) остаётся действующей реализацией, этот тикет не нужен.

Этот тикет **заменяет внутреннюю реализацию** класса `SectionCache`, сохраняя тот же
публичный контракт, что и MVP-версия — вызывающий код (тикеты 27+) не требует изменений.

## Контекст
EV — мод дальней прорисовки для NeoForge 1.21.1. Модуль `ev-storage`, чистый Java.
Секции запрашиваются очень часто и конкурентно (много worker-потоков мешинга + основной
поток тика мира). Единая `ConcurrentHashMap`/lock на всё хранилище стала бы узким местом при
высокой конкурентности (типично для холодного старта — см. PERFORMANCE_MATH.md, раздел A.4,
хотя это отдельная структура от work-stealing очереди задач мешинга — здесь кэш ДАННЫХ, там
очередь ЗАДАЧ). Нужен шардированный кэш: N независимых карт (shard), каждая со своей
блокировкой/структурой, снижающих contention пропорционально числу шардов.

## Эмпирический урок из независимой реализации (Exceptional Vision)

Другой независимо реализованный мод той же концепции (Exceptional Vision, репозиторий
`IYourOverlord/Exceptional-Vision`, реально плейтестированный) НЕ шардирует свой кэш —
`LodCacheLoader` перечитывает **весь** `nodes.bin`/`quads.bin` целиком с диска в память при
КАЖДОЙ перезагрузке кэша (`reloadCache()`), вызываемой на каждый завершённый/патченый
регион. Задокументированный, воспроизведённый и исправленный баг оттуда (их `PROGRESS.md`,
пункт 0.12): этот полный синхронный reload изначально был обёрнут в вызов на **рендер-потоке**
(`Minecraft.execute(...)`), из-за чего каждое фоновое завершение обработки региона вызывало
чтение уже 17+ МБ файла целиком на рендер-потоке — прямая причина периодических жёстких
просадок FPS, подтверждённая логами (`Loaded LOD cache ...` на потоке `Render thread`,
десятки раз за несколько секунд во время bulk-импорта).

Это конкретное, эмпирически подтверждённое обоснование ДВУХ требований к `SectionCache` в
этом тикете, которые иначе выглядели бы как чисто теоретическая осторожность:
1. `SectionCache` в принципе не должен требовать полной перезагрузки/повторного чтения
   всего датасета при обновлении отдельных секций — шардированная, per-section архитектура
   этого тикета архитектурно устраняет самую возможность такого бага, а не просто
   "оптимизирует" уже существующий full-reload паттерн.
2. Любой I/O внутри `acquire`/`loader.load` (см. требование 3 в основной части тикета)
   должен оставаться на worker-потоке, вызывающем `acquire` — НИКОГДА не оборачиваться в
   вызов на render/main-потоке кем-либо из вызывающего кода выше по стеку (это ответственность
   вызывающих `SectionCache` тикетов — 27 и далее — но сам факт, что `SectionCache.acquire`
   не делает никаких допущений о потоке вызова и не требует синхронизации с render-потоком,
   должен быть explicit в Javadoc класса как гарантия, на которую вызывающий код может
   полагаться).

## Готовый контракт из зависимостей (тикеты 01, 02, 05 — уже реализованы)
```java
package dev.ev.api;
public record SectionPos(int level, int x, int y, int z) {
    public long encode();
    public static SectionPos decode(long id);
}

package dev.ev.api.storage;
public interface WorldSectionHandle {
    SectionPos position();
    int getVoxel(int localX, int localY, int localZ);
    void setVoxel(int localX, int localY, int localZ, int paletteIndex);
    boolean isEmpty();
    WorldSectionHandle retain();
    void release();
    int refCount();
}
public record StorageMetrics(
    long cacheHits, long cacheMisses, int activeSectionCount,
    int secondaryCacheSize, long bytesOnDisk
) {
    public double hitRate();
}

package dev.ev.api.metrics;
public interface MetricsRegistry {
    void recordQueueDepth(String queueName, int depth);
    void recordCacheAccess(String cacheName, boolean hit);
    void recordGpuPassDuration(String passName, long nanos);
    void recordCounter(String name, long delta);
}
```

## Задача

### `SectionLoader` (интерфейс — реализация загрузки с диска НЕ входит в этот тикет)
```java
package dev.ev.storage.cache;

import dev.ev.api.storage.WorldSectionHandle;

/**
 * Loads section data from persistent storage. Implemented elsewhere (region file I/O,
 * separate ticket) — SectionCache only depends on this interface, never touches disk itself.
 */
public interface SectionLoader {
    boolean exists(long encodedPos);

    /** Loads the section, or creates a new empty one if it doesn't exist on disk. */
    WorldSectionHandle load(long encodedPos);
}
```

### `EvictionPolicy`
```java
package dev.ev.storage.cache;

import dev.ev.api.storage.WorldSectionHandle;

/**
 * Decides which cached sections to evict when a shard exceeds its capacity.
 * Called by SectionCache internals; implementations must be cheap (called on
 * every cache insert) and thread-safe per-shard (SectionCache guarantees calls
 * for a given shard are already serialized by that shard's lock, so a simple
 * non-thread-safe LRU implementation per shard instance is acceptable —
 * document this assumption in the implementing class).
 */
public interface EvictionPolicy {
    void onInsert(WorldSectionHandle handle);
    void onAccess(WorldSectionHandle handle);
    /** Returns handles that should be evicted, or empty if under capacity. */
    java.util.List<WorldSectionHandle> selectEvictionCandidates(int shardCapacity, int currentShardSize);
}
```

### `LruEvictionPolicy` — конкретная реализация
Реализуй классическую LRU-политику (например, на основе `LinkedHashMap` с
`accessOrder=true`, либо явного intrusive double-linked list — выбери по своему усмотрению,
задокументируй сложность операций в Javadoc). Должна вытеснять только секции с `refCount()
== 0` (нельзя вытеснять то, что кто-то держит) — если верхние N по LRU-порядку заняты
(refCount > 0), пропускай их и ищи следующих кандидатов, либо верни пустой список, если не
из чего вытеснять (это нормальная ситуация — вызывающий код должен просто расти в размере
до момента освобождения, не бросать исключение).

### `SectionCache`
```java
package dev.ev.storage.cache;

import dev.ev.api.storage.StorageMetrics;
import dev.ev.api.storage.WorldSectionHandle;
import dev.ev.api.metrics.MetricsRegistry;

/**
 * Sharded concurrent cache of active WorldSectionHandle instances, keyed by
 * SectionPos.encode(). Sharding reduces lock contention under high concurrent
 * access (many worker threads acquiring sections simultaneously during cold-start
 * bulk loading). Shard index is derived from a bit-mixed hash of the position,
 * not naive modulo, to avoid clustering when section positions are spatially
 * correlated (nearby sections would otherwise hash to nearby shard indices with
 * naive modulo on a poorly mixed key).
 */
public final class SectionCache {

    public SectionCache(int shardCountPowerOfTwo, SectionLoader loader,
                         EvictionPolicy eviction, MetricsRegistry metrics) {
        // shardCountPowerOfTwo: number of shards = 1 << shardCountPowerOfTwo
    }

    /**
     * Acquires a handle, incrementing its ref count. If onlyIfExists is true and the
     * section is not cached and not present in storage, returns null instead of creating one.
     */
    public WorldSectionHandle acquire(long encodedPos, boolean onlyIfExists);

    public int activeCount();

    public StorageMetrics metricsSnapshot();

    /** For shutdown: asserts activeCount() == 0, throws IllegalStateException otherwise. */
    public void assertEmpty();
}
```

## Требования к реализации
1. Индекс шарда: используй bit-mixing (например, множитель Фибоначчи по золотому сечению
   `0x9E3779B97F4A7C15L`, затем сдвиг верхних бит) на `encodedPos`, НЕ простой
   `encodedPos % shardCount` — обоснуй в Javadoc почему (пространственно близкие позиции
   иначе кластеризуются в один шард, снижая эффективность шардирования именно там, где
   конкурентность выше всего — соседние потоки мешинга часто работают над соседними
   секциями одновременно).
2. Каждый шард — независимая структура с собственной блокировкой (например,
   `ReentrantLock` + `Long2ObjectOpenHashMap` из fastutil, либо `ConcurrentHashMap` per-shard
   — выбери и обоснуй; учти, что `Long2ObjectOpenHashMap` не потокобезопасна сама по себе и
   требует внешней синхронизации, что и даёт смысл шардированию — если бы она была
   потокобезопасна, шардирование поверх неё было бы избыточно).
3. `acquire` при cache miss вызывает `loader.load`/`loader.exists` — задокументируй, что это
   может быть блокирующий (I/O) вызов, происходящий под блокировкой шарда, поэтому шарды
   должны быть достаточно многочисленны (не менее 32-64, конфигурируется через
   `shardCountPowerOfTwo`), чтобы конкурентные загрузки разных секций редко попадали в один
   и тот же шард и не сериализовались без необходимости.
4. Каждый `acquire` должен отправлять метрику через `MetricsRegistry.recordCacheAccess`
   (hit/miss) и периодически (не на каждый вызов — раз в N вызовов или по таймеру, чтобы не
   создавать overhead) `recordQueueDepth`/`recordCounter` для `activeCount()`.
5. Полная потокобезопасность: конкурентные `acquire` на РАЗНЫХ позициях в одном шарде должны
   быть безопасны (сериализуются блокировкой шарда — это ожидаемо и корректно, не гонка).
   Конкурентные `acquire` на позициях в РАЗНЫХ шардах не должны блокировать друг друга.
6. Никаких зависимостей на NeoForge/LWJGL.

## Юнит-тесты (обязательно, JUnit 5)
Создай `SectionCacheTest` с тестовой заглушкой `SectionLoader` (in-memory, без реального I/O)
и заглушкой `MetricsRegistry` (например, no-op реализация в тесте):
1. `acquire` с новой позицией и `onlyIfExists=false` создаёт секцию через loader, возвращает
   с `refCount() == 1`.
2. Повторный `acquire` той же позиции возвращает тот же инстанс, `refCount()` увеличивается.
3. `acquire` с `onlyIfExists=true` на несуществующей позиции возвращает `null`, не создаёт.
4. `activeCount()` корректно отражает число уникальных закэшированных секций.
5. Конкурентный тест: N потоков одновременно вызывают `acquire` на M разных позициях
   (используй `ExecutorService` + `CountDownLatch`/`CompletableFuture.allOf`), после
   завершения все ожидаемые секции присутствуют, ref-counts корректны, нет
   `ConcurrentModificationException`/потерянных обновлений (запусти с достаточным числом
   потоков/итераций, чтобы дать шанс проявиться гонкам, если они есть).
6. `assertEmpty()` бросает `IllegalStateException`, если есть хотя бы одна закэшированная
   секция.
7. Тест шардирования (не строгий, sanity-check): для набора пространственно смежных позиций
   (например, все с одинаковым `level`, соседние `x`) индексы шардов не все совпадают (это
   косвенно проверяет, что bit-mixing работает, а не naive modulo кластеризует всё в 1 шард)
   — тестируй через публичный метод, либо сделай метод определения шарда
   package-private/visible-for-testing, если это нужно для проверки напрямую.

## Критерии приёмки
1. Файлы в `ev-storage/src/main/java/dev/ev/storage/cache/`.
2. Все юнит-тесты проходят, включая конкурентный тест (запусти несколько раз локально в
   голове/рассуждении на предмет отсутствия недетерминизма, если есть сомнения в
   потокобезопасности — пересмотри реализацию, а не тест).
3. Модуль компилируется без ошибок, без NeoForge/LWJGL зависимостей.
4. Javadoc класса `SectionCache` явно документирует, что `acquire` не делает никаких
   допущений о вызывающем потоке и не требует вызова с render/main-потока — это гарантия,
   на которую полагаются более поздние тикеты (см. раздел "Эмпирический урок" выше).
