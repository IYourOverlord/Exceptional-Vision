# Тикет 26-mvp — Dirty Section Tracking (полная пересборка секции при правке блока)

## Контекст
Часть Волны 1 (MVP) плана EV — см. `MVP_INDEX.md` за полным обоснованием MVP-first
подхода. Это САМАЯ простая версия отслеживания изменений: когда игрок ломает/ставит блок,
затронутая секция целиком помечается "грязной" и целиком пересобирается через
`GreedyMeshStage.process` (тикет 11) — без под-регионов, без частичного пересчёта. Именно
такое поведение прямо реализовывал первый вариант этого тикета (`shouldFullRebuild` всегда
`true`) — MVP делает это же самое, но БЕЗ лишней инфраструктуры саб-регионов, которая была бы
преждевременной сложностью, раз она всё равно не используется, пока `GreedyMeshStage` не
поддерживает partial-scope пересчёт (это отдельная, более крупная работа, отложенная в
`26-render-dirty-subregion-opt.md`).

**Важно**: включена дедупликация по геометрическому хэшу (`GeometryChangeDeduplicator`) —
это НЕ вынесено в opt-волну, так как это эмпирически подтверждённый (см. раздел ниже),
дешёвый в реализации, но значимый по эффекту фикс, того же класса, что и тикеты 09/14/27
(предотвращение реального наблюдаемого бага, не производительность-ради-производительности) —
см. `MVP_INDEX.md`, категория "тикеты, где проблема слишком дёшево предотвратить заранее,
чтобы откладывать до профилирования".

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

### `DirtySectionTracker`
```java
package dev.ev.render.dirty;

import dev.ev.api.SectionPos;
import java.util.Set;

/**
 * MVP implementation: tracks which SECTIONS (not sub-regions) have been modified
 * since their last mesh rebuild. Any block change marks the entire containing
 * section dirty; the mesh rebuild step reprocesses the whole section via
 * GreedyMeshStage.process (ticket 11), no partial/sub-region-scoped rebuild.
 *
 * See MVP_INDEX.md for why this simple version comes first — the more granular
 * 26-render-dirty-subregion-opt.md, which tracks dirty state at 8^3 sub-region
 * granularity within a section AND extends GreedyMeshStage to support
 * partial-scope rebuilds, is only worth the added complexity if profiling
 * (P0-profiling-checkpoint.md) shows frequent block edits causing noticeable
 * frame time cost from full-section rebuilds.
 *
 * Not thread-safe by design — intended to be driven from the main world-tick
 * thread where block change events originate (Minecraft's block update events
 * are main-thread-only), with the accumulated dirty set drained and handed off
 * to worker threads (via the meshing queue, ticket 15) once per tick/frame.
 */
public final class DirtySectionTracker {

    /** Records a block change anywhere within the given section — marks the
     * whole section dirty (no finer granularity in this MVP version). */
    public void markSectionDirty(SectionPos section);

    /** Returns all sections currently marked dirty, needing a full rebuild. */
    public Set<SectionPos> getDirtySections();

    /** Clears dirty state for a section after its rebuild has been dispatched. */
    public void clearSection(SectionPos section);

    /** True if any section is currently marked dirty. */
    public boolean hasPendingWork();

    /** Number of sections currently marked dirty, for metrics. */
    public int pendingSectionCount();
}
```

## Эмпирический урок из независимой реализации (Exceptional Vision) — дедупликация по геометрическому хэшу

Другой независимо реализованный мод той же концепции (Exceptional Vision,
`IYourOverlord/Exceptional-Vision`) столкнулся с реальным, плейтестом подтверждённым багом
(их `PROGRESS.md`, пункт 0.13): периодические жёсткие просадки FPS оказались вызваны тем, что
`.mca`-файлы региона меняют свои байты на диске (автосейв) из-за тикающих блок-энтити
(механизмы Create, таймеры хопперов и т.п.), НЕ влияющих на LOD-геометрию — но их система
изменений-на-диске видела только "файл изменился" и безусловно пересчитывала регион, писала
на диск и заливала на GPU заново, каждый раз получая байт-в-байт идентичную геометрию.

**Исправление, подтверждённое плейтестом (170 строк в их логе показали срабатывание
дедупликации):** после пересборки затронутой области считается дешёвый хэш (CRC32 в их
случае) результирующей геометрии и сравнивается с хэшем, сохранённым при прошлой успешной
записи для той же области. Если хэш совпал — пересборка (дешёвая, на worker-потоке) всё равно
происходит, но дальнейшая цепочка (запись в постоянное хранилище, GPU-заливка) не
запускается вообще.

**Требование к этому тикету:** добавь аналогичную дедупликацию поверх `DirtySectionTracker`.
Это особенно важно именно в MVP-версии: раз здесь пересобирается ВСЯ секция целиком на любую
правку (а не только затронутый саб-регион), доля "бесполезных" полных пересборок (правка,
не изменившая видимую геометрию — например, поворот блока без изменения формы на уровне
greedy-mesh) относительно дороже, чем была бы при sub-region-granular пересчёте — дедупликация
здесь окупается ещё быстрее, чем в потенциальной opt-версии.

```java
package dev.ev.render.dirty;

/**
 * After a dirty section rebuild produces new geometry, compares a cheap hash of
 * the result against the hash from the last successful rebuild of the same
 * section, to avoid propagating a rebuild further down the pipeline (persistent
 * write, GPU upload) when the geometry is byte-identical to what's already
 * resident — see this ticket's "Эмпирический урок" section for why this matters
 * in practice.
 */
public final class GeometryChangeDeduplicator {

    /** Cheap, order-independent hash of a Quad list's content (not identity/memory address). */
    public long hashQuads(java.util.List<dev.ev.api.meshing.Quad> quads);

    /**
     * @return true if newHash differs from the last recorded hash for this section
     *         (i.e. downstream propagation IS warranted), false if identical (skip).
     *         Always returns true for a section seen for the first time (no prior hash).
     */
    public boolean recordAndCheckChanged(dev.ev.api.SectionPos section, long newHash);
}
```

## Требования к реализации

1. **Внутреннее хранилище `DirtySectionTracker`**: простой `Set<Long>` (ключ —
   `SectionPos.encode()`) — например, `LongOpenHashSet` из fastutil для избежания boxing
   `Long`-объектов, или обычный `HashSet<Long>`, если предпочитаешь минимизировать
   зависимости для этого небольшого класса — выбери и обоснуй.
2. `markSectionDirty` — просто добавляет `section.encode()` в множество, идемпотентно
   (повторный вызов для уже грязной секции не имеет дополнительного эффекта).
3. Никакой логики саб-регионов, никакого 64-битного bitmask, никакого `SubRegion` класса —
   вся эта инфраструктура сознательно исключена из MVP как преждевременная (см. Контекст).
4. `GeometryChangeDeduplicator` — используй простой, быстрый, детерминированный хэш
   (например, накопительный `java.util.zip.CRC32` по сериализованным полям каждого `Quad` в
   консистентном порядке — порядок quad'ов уже детерминирован, гарантируется
   `GreedyMeshStage`'s алгоритмом, тикет 11, так что не нужен order-independent хэш). Храни
   последний хэш per-`SectionPos` в простой `Map` внутри `GeometryChangeDeduplicator`
   (instance-scoped, не статическое состояние).
5. Никакого мутируемого статического состояния (кроме однопоточной, задокументированной
   модели доступа — main-thread-only, как и было в исходной версии тикета).

## Юнит-тесты (обязательно, JUnit 5)

Создай `DirtySectionTrackerTest`:
1. `markSectionDirty` затем `getDirtySections` содержит эту секцию.
2. Повторный `markSectionDirty` для той же секции — не дублирует (естественно для `Set`, но
   проверь, что реализация не ломается/не растёт неограниченно).
3. `markSectionDirty` для двух разных секций — `getDirtySections` содержит обе, независимо.
4. `clearSection` очищает dirty-состояние для конкретной секции — последующий
   `getDirtySections` её не содержит, но содержит другие, ранее помеченные, не затронутые
   этим `clearSection` вызовом.
5. `hasPendingWork`/`pendingSectionCount` корректно отражают состояние до/после
   `markSectionDirty`/`clearSection`.

Создай `GeometryChangeDeduplicatorTest`:
1. Первый вызов `recordAndCheckChanged` для новой секции → `true` (нет предыдущего хэша).
2. Второй вызов с тем же хэшем для той же секции → `false` (дедуплицировано).
3. Вызов с другим хэшем для той же секции → `true` (реальное изменение).
4. `hashQuads` на двух списках с идентичным содержимым (но разными объектами `Quad`,
   созданными раздельно) даёт одинаковый хэш (проверка value-based, не identity-based
   хэширования).

## Критерии приёмки
1. Файлы в `ev-render/src/main/java/dev/ev/render/dirty/`:
   `DirtySectionTracker.java`, `GeometryChangeDeduplicator.java`.
2. Все юнит-тесты проходят.
3. Модуль компилируется без ошибок, без `org.lwjgl.*` импортов.
4. Threading-модель `DirtySectionTracker` (main-thread-only) задокументирована явно.
5. Javadoc `DirtySectionTracker` явно документирует MVP-статус и условие перехода на
   sub-region granular версию (`26-opt`).
6. `PROJECT_INDEX.md` (тикет 32) обновлён с пометкой "Dirty tracking — MVP (whole-section)
   версия активна, sub-region opt-версия в банке, не применена".
