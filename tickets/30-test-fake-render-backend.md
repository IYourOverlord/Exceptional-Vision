# Тикет 30 — FakeRenderBackend (in-memory реализация для тестов)

## Контекст
EV — мод дальней прорисовки для NeoForge 1.21.1. Модуль `ev-test`. Реализует
`RenderBackend` (тикет 04) полностью в памяти, без единого вызова LWJGL/OpenGL — ключевой
элемент архитектуры, позволяющий тестировать доменную логику (`ev-render`,
потенциально части `ev-gpu`, не завязанные напрямую на реальные GL-вызовы, такие как
`FrameGraphBuilder` из тикета 24) в CI без GPU/дисплея. Несколько предыдущих тикетов (19,
24) уже ссылались на возможность использовать этот класс, если он существует к моменту их
выполнения — если это так, они уже могли создать локальные упрощённые заглушки вместо
ожидания этого тикета; в таком случае эта реализация должна быть достаточно полной и
удобной, чтобы в будущем (не обязательно в рамках этого тикета) можно было заменить те
локальные заглушки на неё для консолидации тестовой инфраструктуры.

## Готовый контракт из зависимости (тикет 04, уже реализован)
```java
package dev.ev.api.gpu;

public interface RenderBackend {
    GpuBuffer createBuffer(long sizeBytes, BufferUsage usage);
    GpuTexture createTexture(TextureDesc desc);
    ComputePipeline compilePipeline(ShaderSource source, PipelineLayout layout);
    GraphicsPipeline compileGraphicsPipeline(ShaderSource vertexSrc, ShaderSource fragmentSrc, PipelineLayout layout);
    void submit(CommandList commands);
    FenceHandle insertFence();
    boolean isSignaled(FenceHandle fence);
    boolean waitForFence(FenceHandle fence, long timeoutNanos);
    void shutdown();
}
// ... (см. полный контракт всех связанных интерфейсов в тикете 04: GpuBuffer, GpuTexture,
// BufferUsage, TextureDesc, TextureFormat, ShaderSource, PipelineLayout, ComputePipeline,
// GraphicsPipeline, CommandList, BarrierScope, FenceHandle)
```

## Задача

### `FakeRenderBackend`
```java
package dev.ev.test.gpu;

import dev.ev.api.gpu.*;

/**
 * Fully in-memory implementation of RenderBackend, for unit-testing domain logic
 * (ev-render, parts of ev-meshing/storage that interact with the GPU
 * abstraction) without a real GPU context. Compute "dispatch" is simulated by
 * NOT actually executing any shader logic (GLSL source is stored but never
 * interpreted) — this backend validates the CALLING CODE's usage of the
 * RenderBackend contract (correct buffer creation, correct binding names, correct
 * submit/fence sequencing, correct barrier placement) but cannot validate GLSL
 * shader correctness itself (that requires a real GPU, see tickets 21-23's
 * integration-test notes). Document this scope limitation clearly in the class
 * Javadoc so users of this fake don't mistake it for a full GPU simulator.
 */
public final class FakeRenderBackend implements RenderBackend {

    // Internal state: in-memory byte arrays backing each "GpuBuffer", simple
    // Java objects backing "GpuTexture"/pipelines, a monotonic fence counter,
    // and recorded call logs for assertions (see requirement 4).

    @Override
    public GpuBuffer createBuffer(long sizeBytes, BufferUsage usage) { /* ... */ }

    @Override
    public GpuTexture createTexture(TextureDesc desc) { /* ... */ }

    @Override
    public ComputePipeline compilePipeline(ShaderSource source, PipelineLayout layout) { /* ... */ }

    @Override
    public GraphicsPipeline compileGraphicsPipeline(ShaderSource vertexSrc, ShaderSource fragmentSrc, PipelineLayout layout) { /* ... */ }

    @Override
    public void submit(CommandList commands) { /* ... */ }

    @Override
    public FenceHandle insertFence() { /* ... */ }

    @Override
    public boolean isSignaled(FenceHandle fence) { /* ... */ }

    @Override
    public boolean waitForFence(FenceHandle fence, long timeoutNanos) { /* ... */ }

    @Override
    public void shutdown() { /* ... */ }

    // Test-inspection API (not part of RenderBackend interface, additional public
    // methods specific to this fake, used by test assertions):

    /** All buffers currently allocated (not yet free()'d), for leak-detection assertions. */
    public java.util.List<GpuBuffer> activeBuffers();

    /** All textures currently allocated (not yet free()'d). */
    public java.util.List<GpuTexture> activeTextures();

    /** Raw byte contents of a buffer created by this backend, for test assertions on uploaded/copied data. */
    public byte[] readBufferContents(GpuBuffer buffer);

    /** Directly writes bytes into a buffer's backing store, bypassing any upload
     * mechanism, for test setup convenience (e.g. seeding a "GPU" buffer with
     * known data before running domain logic that reads it). */
    public void writeBufferContents(GpuBuffer buffer, byte[] data);

    /** Chronological log of recorded CommandList operations across all submit() calls, for sequencing assertions. */
    public java.util.List<String> recordedOperationLog();
}
```

### `FakeCommandList`
```java
package dev.ev.test.gpu;

/**
 * In-memory CommandList implementation used by FakeRenderBackend.submit(). Actually
 * performs the requested operations against the fake backend's in-memory buffers
 * (uploadToBuffer/copyBuffer/clearBuffer really copy bytes; dispatchCompute/draw
 * are recorded in the operation log but do not simulate shader execution — see
 * class-level scope limitation note on FakeRenderBackend).
 */
final class FakeCommandList implements dev.ev.api.gpu.CommandList { /* ... */ }
```

## Требования к реализации

1. **`createBuffer`**: возвращает `FakeGpuBuffer` (простой класс, package-private или
   публичный по необходимости, implementing `GpuBuffer`), backed by `byte[]` (или
   `ByteBuffer`) размером `sizeBytes`. Для `STAGING_UPLOAD` — `mappedAddress()` должен
   вернуть что-то полезное для теста: поскольку это чистый Java fake (не реальная нативная
   память), `mappedAddress()` в этом контексте не может быть реальным адресом — задокументируй
   это явно и реши: либо брось `UnsupportedOperationException` с понятным сообщением "fake
   backend does not support raw memory addresses, use writeBufferContents()/
   readBufferContents() for test setup instead" (простой честный подход), либо, если
   какой-то тестируемый вызывающий код (например, `StagingUploadRing` из тикета 19)
   реально нуждается в работающем `mappedAddress()` для полноценного теста без реального GL
   — рассмотри выделение через `MemoryUtil.nmemAlloc`-подобный off-heap allocation ТОЛЬКО
   для этого конкретного случая (LWJGL's `MemoryUtil` не требует реального GL-контекста для
   простого выделения памяти, это отдельная от GL функциональность) — выбери один подход и
   обоснуй.

2. **`compilePipeline`/`compileGraphicsPipeline`**: не выполняй реальную компиляцию GLSL
   (нет GL-контекста) — сохрани `ShaderSource`/`PipelineLayout` как есть, верни fake
   pipeline-объект, чьи `dispatch`/`bindBuffer`/etc. методы просто записывают вызов в
   `recordedOperationLog()` вызывающего `FakeRenderBackend` (нужна ссылка на родительский
   backend из fake pipeline'а).

3. **`submit(CommandList commands)`**: если `commands` — это `FakeCommandList`
   (созданный этим же backend'ом), выполни его накопленные операции последовательно:
   `uploadToBuffer`/`copyBuffer`/`clearBuffer` реально копируют байты между
   `FakeGpuBuffer`-объектами (используй реальный `System.arraycopy` или аналог); операции
   `dispatchCompute`/`draw`/`memoryBarrier` только логируются в `recordedOperationLog()` (не
   имеют реального эффекта на "GPU-память", так как нет интерпретатора шейдеров). Как
   вызывающий код получает `CommandList` для записи операций ДО `submit` — реши через,
   например, дополнительный публичный метод `FakeRenderBackend.newCommandList() ->
   CommandList` (если исходный контракт `RenderBackend` из тикета 04 не предоставляет
   явного способа создать `CommandList` — если он предоставляет, используй его; если нет,
   этот метод — оправданное дополнение специфичное для fake-реализации, задокументируй его
   как test-только API, не часть контракта `RenderBackend`).

4. **Fence-семантика**: `insertFence()` возвращает monotonically increasing `FakeFenceHandle`
   (обёртка над `long` counter); поскольку в fake-реализации нет реальной асинхронности —
   разумное упрощение: все fence считаются немедленно сигнализированными сразу после
   `insertFence()` (fake backend исполняет всё синхронно внутри `submit`, нет отложенного
   GPU-выполнения) — `isSignaled` всегда возвращает `true` для любого fence, созданного этим
   backend'ом; `waitForFence` возвращает `true` немедленно.

   **Это не безобидное упрощение — это делает `FakeRenderBackend` НЕПРИГОДНЫМ для проверки
   любой логики, зависящей от того, что fence НЕ сигнализирован сразу** (backpressure,
   защита от переиспользования ring-buffer региона до завершения GPU-чтения, и подобное).
   Конкретный пример, уже отмеченный в проекте: `19-gpu-upload-batching-opt.md` явно
   предупреждает не использовать `FakeRenderBackend` для тестирования своей
   fence-ожидающей backpressure-логики именно по этой причине — используй эту ссылку как
   образец при работе с любым другим тикетом, полагающимся на отложенную сигнализацию
   fence. Задокументируй это ограничение явно в Javadoc класса `FakeRenderBackend`, а не
   только в этом тикете — исполнитель другого тикета, читающий только Javadoc готового
   класса (не текст этого тикета), должен сразу увидеть предупреждение.

5. **`activeBuffers()`/`activeTextures()`**: отслеживай через `List`/`Set`, добавляемый при
   создании, удаляемый при `free()` — используй для leak-detection ассертов в тестах,
   потребляющих `FakeRenderBackend` (например, `NodeBufferSoA.close()` из тикета 20 должен
   привести `activeBuffers()` к пустому списку после теста).

6. Никакого мутируемого статического состояния — весь стейт instance-scoped внутри
   `FakeRenderBackend` (каждый тест создаёт свежий инстанс, полная изоляция между тестами).

## Юнит-тесты (обязательно, JUnit 5 — тестирует саму fake-реализацию, мета-уровень)

Создай `FakeRenderBackendTest`:
1. `createBuffer` возвращает буфер с ожидаемым `sizeBytes()`/`usage()`.
2. `writeBufferContents` затем `readBufferContents` на том же буфере возвращает
   идентичные байты (round-trip).
3. `free()` на буфере удаляет его из `activeBuffers()`.
4. `submit` с `FakeCommandList`, содержащим `uploadToBuffer`, реально копирует байты в
   целевой буфер (проверь через `readBufferContents` после `submit`).
5. `submit` с `FakeCommandList`, содержащим `copyBuffer` между двумя fake-буферами,
   корректно копирует данные из src в dst с учётом offset'ов.
6. `dispatchCompute`/`draw` вызовы попадают в `recordedOperationLog()` в правильном
   относительном порядке при нескольких операциях в одном `CommandList`.
7. `insertFence()`/`isSignaled()`/`waitForFence()` ведут себя согласно
   задокументированному "always immediately signaled" упрощению.
8. `shutdown()` не бросает исключение (idempotent-safe при повторном вызове, если
   разумно — задокументируй ожидаемое поведение при двойном `shutdown()`).

## Критерии приёмки
1. Файлы в `ev-test/src/main/java/dev/ev/test/gpu/` (или
   `ev-test/src/test/java/...`, если предпочитаешь держать fake-инфраструктуру
   исключительно в test source set, не main — выбери и обоснуй, учитывая, что другие
   МОДУЛИ, а не только тесты внутри `ev-test`, могут захотеть импортировать этот класс
   как test-dependency; если это важно, `main` source set в `ev-test` подходит лучше
   для повторного использования как обычной библиотечной зависимости для тестов других
   модулей).
2. Все юнит-тесты проходят.
3. Модуль компилируется без ошибок.
4. Scope-ограничения fake-реализации (не симулирует реальное исполнение шейдеров, упрощённая
   fence-семантика) явно задокументированы в Javadoc класса.
