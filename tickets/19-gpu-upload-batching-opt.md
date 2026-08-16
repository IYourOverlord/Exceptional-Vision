# Тикет 19-opt — Batched Staging Upload (амортизация driver call overhead)

## ⚠️ Когда это применять

Это тикет Волны 2 (см. `MVP_INDEX.md`). MVP-версия использует обычный `GpuBuffer`-upload
(например, через отдельный `CommandList.uploadToBuffer` вызов на каждую готовую секцию — см.
раздел "Требования" в оригинальной MVP-реализации тикета 17/19, если она разделена явно;
если тикет 17 не разделён на mvp/opt — используй просто прямой upload без ring-buffer
батчинга как часть тикета 17 при первом заходе, эта заметка здесь фиксирует контраст с тем,
что делает этот opt-тикет). **Не выполняй этот тикет**, пока не пройден
`P0-profiling-checkpoint.md` и результаты не показали:
- Разбивка холодного старта по стадиям (Сценарий A) указывает на GPU upload как на заметную
  долю общего времени, И конкретно — на driver call overhead (не на сам объём передаваемых
  байт, который батчинг не уменьшает, только амортизирует количество вызовов) — эти две вещи
  стоит по возможности разделить в профилировании (например, через RenderDoc frame capture,
  который покажет число отдельных upload-вызовов за кадр холодного старта).

Если объём данных небольшой (умеренный render distance) и число одновременно готовых секций
за кадр холодного старта не очень велико — прямой upload на каждую секцию, скорее всего,
не создаёт заметного overhead, и этот тикет не нужен.

## Контекст
EV — мод дальней прорисовки для NeoForge 1.21.1. Модуль `ev-gpu`. Реализует
батчинг заливки готовых мешлетов в GPU-буферы, обоснованный в PERFORMANCE_MATH.md разделе
A.6: вместо отдельного `glBufferSubData`/upload-вызова на каждую готовую секцию (тысячи
вызовов за первые кадры холодного старта), готовая геометрия копится в персистентно
замапленном staging-буфере (создан через `RenderBackend.createBuffer(...,
BufferUsage.STAGING_UPLOAD)`, реализовано в тикете 17) и заливается **одним** batched-copy в
целевой GPU-буфер раз в кадр (или раз в фиксированный интервал), а не раз в готовую секцию.

## Готовый контракт из зависимости (тикет 17, уже реализован)
```java
package dev.ev.api.gpu;
public interface GpuBuffer {
    long sizeBytes();
    BufferUsage usage();
    long mappedAddress(); // valid for STAGING_UPLOAD buffers, throws otherwise
    void free();
}
public interface RenderBackend {
    GpuBuffer createBuffer(long sizeBytes, BufferUsage usage);
    void submit(CommandList commands);
    // ... (see full contract, ticket 04)
}
public interface CommandList {
    void copyBuffer(GpuBuffer src, long srcOffset, GpuBuffer dst, long dstOffset, long sizeBytes);
    // ... (see full contract, ticket 04)
}
```

## Задача

### `StagingUploadRing`
```java
package dev.ev.gpu.upload;

import dev.ev.api.gpu.GpuBuffer;
import dev.ev.api.gpu.RenderBackend;

/**
 * Accumulates small CPU-side writes (finished meshlet data ready for GPU upload)
 * into a persistently-mapped staging buffer, and flushes them as a single batched
 * copyBuffer command per flush() call, instead of one GPU upload call per piece of
 * data (see PERFORMANCE_MATH.md section A.6).
 *
 * Usage pattern: call stageWrite(...) repeatedly as data becomes ready (e.g. once
 * per finished MeshletBatch), then call flush() once per frame (or on a fixed
 * interval) to emit ONE copyBuffer command list covering everything staged since
 * the last flush. If the staging buffer fills up before flush() is called, stageWrite
 * blocks the caller until the next flush frees space (documented backpressure
 * behavior — do not silently drop data).
 *
 * Not thread-safe by itself for concurrent stageWrite calls from multiple threads —
 * callers must externally synchronize concurrent writers (e.g. a single dedicated
 * "upload coordinator" thread that drains a queue of ready MeshletBatches and calls
 * stageWrite sequentially). Document this clearly; do not add internal locking that
 * would defeat the purpose of batching (a lock per stageWrite call reintroduces
 * per-call overhead this class exists to avoid) — instead a single writer thread is
 * the intended usage pattern.
 */
public final class StagingUploadRing {

    public StagingUploadRing(RenderBackend backend, long stagingBufferSizeBytes) { /* ... */ }

    /**
     * Copies sourceBytes into the staging ring buffer and records a pending
     * copyBuffer operation targeting targetBuffer at targetOffsetBytes. Returns
     * immediately after the CPU-side memcpy into the mapped staging buffer (does
     * not wait for the GPU copy — that happens at the next flush()).
     *
     * @throws IllegalStateException if sourceBytes.length exceeds the total staging
     *         buffer capacity (a single write can never exceed total ring capacity —
     *         this is a hard misuse error, not a backpressure condition)
     */
    public void stageWrite(byte[] sourceBytes, GpuBuffer targetBuffer, long targetOffsetBytes);

    /**
     * Emits one batched CommandList covering all writes staged since the last
     * flush(), submits it via RenderBackend.submit, and resets the ring's write
     * cursor for reuse (respecting fence completion of prior GPU reads of the
     * region being reused — see requirement 3 below).
     */
    public void flush();

    /** Approximate bytes currently staged and not yet flushed, for metrics. */
    public long pendingBytes();
}
```

## Требования к реализации

1. **Ring buffer механика**: `stageWrite` пишет байты через `MemoryUtil`-подобный доступ по
   адресу `mappedAddress() + writeCursor` (используй `sun.misc.Unsafe`-независимый способ —
   LWJGL предоставляет `MemoryUtil.memCopy`/`memPutByte` и аналогичные удобные обёртки для
   работы с raw-адресами персистентно замапленной памяти; используй их, не пиши руками через
   `Unsafe`). `writeCursor` продвигается на длину записи; если запись не помещается до конца
   буфера (`writeCursor + length > capacity`) — либо wrap around на начало (если начало уже
   освобождено предыдущим `flush`+fence-ожиданием), либо блокируй вызывающего до
   освобождения места (см. п.3) — выбери один подход, задокументируй.

2. **Batched flush**: за один `flush()` собери ОДИН `CommandList` со всеми
   `copyBuffer(stagingBuffer, srcOffset, targetBuffer, targetOffset, size)`-командами,
   накопленными с прошлого `flush` (по одной команде `copyBuffer` на каждый `stageWrite`
   с прошлого flush — сама команда всё ещё одна на запись, НО они все идут в ОДНОМ
   `CommandList`/`submit`, а не отдельными `RenderBackend.submit` вызовами — именно в этом
   экономия driver call overhead согласно математике из A.6: `cost(1 батч) ≈ 1 *
   driver_call_overhead + N * memcpy_cost` против `N * (driver_call_overhead + memcpy_cost)`
   при отдельных submit'ах).

3. **Backpressure/безопасность переиспользования буфера**: поскольку staging-буфер —
   персистентный и переиспользуемый (ring), нельзя перезаписывать область, которую GPU ещё не
   дочитал (GPU-копирование из области, которую CPU уже начал перезаписывать новыми данными —
   гонка). Используй `RenderBackend.insertFence()` после каждого `flush()`, отслеживай, какая
   область буфера "защищена" неисполненным ещё fence'ом, и в `stageWrite` (или в начале
   `flush`) проверяй/жди (`waitForFence`) перед тем, как переиспользовать эту область под новую
   запись. Задокументируй эту логику подробно в Javadoc класса — это самая тонкая часть
   тикета, ошибка здесь даёт視ual-коррупцию геометрии (данные повреждаются "иногда",
   трудно воспроизводимый баг) на реальном железе.

4. Размер ring-буфера (`stagingBufferSizeBytes`) — параметр конструктора, не хардкодь; тикет
   17/23 или интеграционный код (тикет 27) решает конкретное значение исходя из бюджета
   памяти (см. `QueueBudget`-подобную адаптивную логику из ARCHITECTURE.md раздела 6.3 —
   сам расчёт бюджета НЕ входит в этот тикет, только параметризация).

5. Никакого мутируемого статического состояния — всё state instance-scoped внутри
   `StagingUploadRing`.

## Юнит-тесты
Полноценный тест требует реального GL-контекста (персистентный mapped buffer — реальная
GPU-память). Для этого тикета:
1. Если в тестовом окружении доступен headless GL context (см. тикет 17 обсуждение) —
   напиши интеграционный тест: `stageWrite` несколько раз, `flush`, прочитай обратно через
   отдельный `STAGING_DOWNLOAD` буфер (или через `copyBuffer` в него и последующее чтение) —
   проверь, что данные дошли до целевого буфера корректно, побайтово.
2. **Логика ring cursor management (позиция записи, wrap-around, отслеживание "занятых"
   регионов до сигнала fence) — вынеси в отдельный чистый класс/метод, тестируемый БЕЗ
   `RenderBackend`/`GpuBuffer` вообще** (например, `RingCursorTracker`, оперирующий только
   `long`-позициями и явно передаваемым "какие регионы уже свободны" состоянием, без
   реального fence-объекта). **Не используй `FakeRenderBackend` (тикет 30) для тестирования
   именно этой backpressure/fence-ожидания логики** — `FakeRenderBackend` по контракту
   (см. тикет 30, требование 4) всегда сигнализирует fence немедленно после `insertFence()`,
   то есть в fake-мире условие "область защищена неисполненным fence'ом" никогда не
   выполняется — тест, использующий `FakeRenderBackend` для проверки этой логики, будет
   проходить независимо от того, реализована ли реальная защита от гонки правильно или нет
   (ложноположительный "зелёный" результат, не доказывающий корректность). Вместо этого:
   вынеси решение "эта область свободна для переиспользования?" в чистую функцию, принимающую
   явные параметры (текущая позиция курсора, список пар "регион + последний известный статус
   fence'а по этому региону", переданный тестом напрямую, а не через реальный/fake
   `RenderBackend.isSignaled`) — так тест может симулировать ЛЮБОЕ состояние (fence ещё не
   сигнализирован, сигнализирован, отсутствует) без зависимости от поведения конкретного
   `RenderBackend`-объекта, реального или fake.
3. Тест: `stageWrite` с данными, превышающими полную ёмкость ring-буфера, бросает
   `IllegalStateException` (единичная запись не может превышать полную ёмкость — это
   контрактное требование, не backpressure-ситуация).

## Критерии приёмки
1. Файл `ev-gpu/src/main/java/dev/ev/gpu/upload/StagingUploadRing.java`.
2. Логика ring-buffer cursor/backpressure протестирована (через fake или реальный GL,
   согласно доступности среды).
3. Модуль компилируется без ошибок.
4. Javadoc подробно объясняет, как предотвращается data race между CPU-перезаписью и
   GPU-чтением переиспользуемой области буфера (через fence-отслеживание).
