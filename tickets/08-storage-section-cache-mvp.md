# Тикет 08-mvp — SectionCache (простой, нешардированный кэш активных секций)

## Контекст
Часть Волны 1 (MVP) плана EV — см. `MVP_INDEX.md` за полным обоснованием MVP-first
подхода. Это упрощённая версия кэша секций: тот же публичный контракт, что и в
"целевой" версии (`08-storage-section-cache-opt.md`), но реализованный самым прямолинейным
способом — одна `ConcurrentHashMap` с одной блокировкой, БЕЗ шардирования. Шардирование —
оптимизация под конкурентный contention, которую имеет смысл вводить только если
профилирование (`P0-profiling-checkpoint.md`) реально покажет contention на этом кэше при
холодном старте — не заранее.

**Важно**: контракт (публичные сигнатуры класса `SectionCache`) в этом MVP-тикете должен
СОВПАДАТЬ с контрактом `08-storage-section-cache-opt.md`, чтобы переход на opt-версию позже
не потребовал менять вызывающий код (тикеты 27+, интеграция). Если позже понадобится
`08-opt`, он просто заменяет внутреннюю реализацию этого же класса (или пакета), не меняя,
как его вызывают.

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

### `EvictionPolicy` и `LruEvictionPolicy`
Идентичны версии из `08-storage-section-cache-opt.md` — см. тот файл за полным контрактом
(`onInsert`/`onAccess`/`selectEvictionCandidates`, LRU-реализация вытесняющая только записи с
`refCount() == 0`). Не переписывай эту часть иначе — политика вытеснения не зависит от того,
шардирован кэш или нет, это ортогональная концепция.

### `SectionCache` — MVP-реализация
```java
package dev.ev.storage.cache;

import dev.ev.api.storage.StorageMetrics;
import dev.ev.api.storage.WorldSectionHandle;
import dev.ev.api.metrics.MetricsRegistry;

/**
 * MVP implementation: a single ConcurrentHashMap-backed cache of active
 * WorldSectionHandle instances, keyed by SectionPos.encode(), with a single
 * ReentrantLock guarding eviction-policy bookkeeping (the map itself is
 * concurrent-safe on its own via ConcurrentHashMap, but eviction candidate
 * selection needs external synchronization since EvictionPolicy implementations
 * are documented as not-thread-safe-by-themselves).
 *
 * NOT sharded — this is the deliberately simple Wave 1 (MVP) version, see
 * MVP_INDEX.md. If profiling (P0-profiling-checkpoint.md) shows meaningful lock
 * contention on this cache under high concurrent load (many worker threads
 * acquiring sections simultaneously during cold-start bulk loading), replace
 * this implementation with the sharded version from
 * 08-storage-section-cache-opt.md — same public contract, different internals.
 *
 * The public contract intentionally matches 08-storage-section-cache-opt.md
 * exactly (same method signatures) so that swapping implementations later does
 * not require touching any calling code.
 */
public final class SectionCache {

    /**
     * @param shardCountPowerOfTwo IGNORED in this MVP implementation — accepted
     *        for contract compatibility with the opt version's constructor
     *        signature (so calling code doesn't need to change when swapping
     *        implementations), but has no effect here since there is no sharding.
     *        Document this explicitly with a code comment at the parameter, not
     *        just in Javadoc, so it's impossible to miss when reading the
     *        constructor call site.
     */
    public SectionCache(int shardCountPowerOfTwo, SectionLoader loader,
                         EvictionPolicy eviction, MetricsRegistry metrics) {
        // single ConcurrentHashMap<Long, WorldSectionHandle> + single ReentrantLock
        // guarding eviction bookkeeping only (not guarding the map itself, which
        // is independently concurrent-safe via ConcurrentHashMap's own guarantees)
    }

    public WorldSectionHandle acquire(long encodedPos, boolean onlyIfExists);

    public int activeCount();

    public StorageMetrics metricsSnapshot();

    public void assertEmpty();
}
```

## Требования к реализации
1. Используй `java.util.concurrent.ConcurrentHashMap<Long, WorldSectionHandle>` directly —
   no custom sharding, no bit-mixing, no `fastutil` primitive maps for this MVP version (the
   boxing overhead of `Long` keys is an accepted, deliberate simplification here — it is
   exactly the kind of overhead `08-opt` would address if profiling shows it matters, but
   guessing that it matters without profiling is exactly the mistake this MVP-first approach
   avoids).
2. `acquire` при cache miss вызывает `loader.load`/`loader.exists` — как и в opt-версии, это
   может быть блокирующий I/O вызов; в MVP-версии он просто блокирует любые другие потоки,
   ожидающие тот же ключ через `ConcurrentHashMap.computeIfAbsent`-подобный паттерн (или
   эквивалент) — это ожидаемо менее параллельно, чем шардированная версия, это ЕСТЬ разница
   между MVP и opt, не баг.
3. Используй `ConcurrentHashMap.compute`/`computeIfAbsent` для атомарного "проверить-и-создать
   при отсутствии" паттерна вместо ручного `get`+`put` (которое было бы гонкой) — это
   встроенная в JDK атомарность, простая и корректная, без необходимости своей блокировки на
   уровне всего кэша для этой операции.
4. Отдельный `ReentrantLock` (или `synchronized` блок) нужен ТОЛЬКО вокруг вызовов
   `EvictionPolicy` методов (`onInsert`/`onAccess`/`selectEvictionCandidates`), так как эти
   реализации по контракту не потокобезопасны сами — задокументируй это явно.
5. Каждый `acquire` должен отправлять метрику через `MetricsRegistry.recordCacheAccess`
   (hit/miss), как и в opt-версии — наблюдаемость не является частью того, что упрощается в
   MVP, она нужна как раз для того, чтобы `P0-profiling-checkpoint.md` мог опираться на
   реальные числа при решении, нужен ли `08-opt`.
6. Никаких зависимостей на NeoForge/LWJGL.

## Юнит-тесты (обязательно, JUnit 5)
Идентичны тестам из `08-storage-section-cache-opt.md`, ЗА ИСКЛЮЧЕНИЕМ теста №7 (тест
шардирования — неприменим здесь, в MVP-версии шардирования нет):
1. `acquire` с новой позицией и `onlyIfExists=false` создаёт секцию через loader, возвращает
   с `refCount() == 1`.
2. Повторный `acquire` той же позиции возвращает тот же инстанс, `refCount()` увеличивается.
3. `acquire` с `onlyIfExists=true` на несуществующей позиции возвращает `null`, не создаёт.
4. `activeCount()` корректно отражает число уникальных закэшированных секций.
5. Конкурентный тест: N потоков одновременно вызывают `acquire` на M разных позициях — после
   завершения все ожидаемые секции присутствуют, ref-counts корректны, нет
   `ConcurrentModificationException`/потерянных обновлений. **Этот тест особенно важен в MVP
   версии** — раз параллелизм здесь ограничен единственным `ConcurrentHashMap`+lock, важно
   убедиться, что корректность не нарушена (медленнее — ожидаемо и приемлемо в MVP; неверно —
   нет).
6. `assertEmpty()` бросает `IllegalStateException`, если есть хотя бы одна закэшированная
   секция.

## Критерии приёмки
1. Файлы в `ev-storage/src/main/java/dev/ev/storage/cache/`.
2. Все юнит-тесты проходят.
3. Модуль компилируется без ошибок, без NeoForge/LWJGL зависимостей.
4. Javadoc класса `SectionCache` явно документирует: (а) что это MVP/Волна-1 реализация,
   (б) что `08-opt` существует как замена той же самой сигнатуры, если профилирование
   покажет нужду, (в) что `acquire` не делает допущений о вызывающем потоке (та же гарантия,
   что и в opt-версии — см. эмпирический урок в исходном тикете про полную перезагрузку на
   render-потоке в независимой реализации Exceptional Vision, актуальный и для MVP-версии
   тоже).
5. `PROJECT_INDEX.md` (тикет 32) обновлён с пометкой "SectionCache — MVP (нешардированная)
   версия активна, opt-версия в банке, не применена" — см. `MVP_INDEX.md` про обязательность
   ведения этой информации.
