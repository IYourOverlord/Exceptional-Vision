# EV — GPU-driven LOD дальней прорисовки для NeoForge 1.21.1

## 0. Ключевые архитектурные решения

| Аспект | EV (NeoForge, этот дизайн) |
|---|---|
| Модлоадер | NeoForge, ModEvent/Bus API, минимум mixins |
| Структура кода | 6 gradle-подмодулей по слоям, файлы ≤400 строк |
| GPU-абстракция | `RenderBackend` интерфейс, GL-реализация изолирована в 1 модуле |
| Traversal иерархии | Persistent-thread compute kernel, 1 dispatch, work-stealing очередь в SSBO |
| Размеры очередей | Адаптивный расчёт от VRAM/render distance + graceful degradation |
| Формат хранения | Версионированная схема (schema version в заголовке) + миграторы с v1 |
| Наблюдаемость | Метрики (queue depth, GPU timing, cache hit-rate) как часть API с первого дня |
| Тесты | Юнит-тесты для всей non-GL логики (allocator, octree indexing, приоритеты) |
| Многомировая поддержка | Никакого mutable static state; всё через instance-scoped контекст |

---

## 1. Принципы архитектуры

1. **Разделение по слоям, а не по фичам.** Каждый слой — отдельный Gradle-модуль с чётким контрактом
   (интерфейсом), не знающий о деталях реализации соседних слоёв.
2. **GPU-абстракция обязательна.** Ни один класс вне модуля `ev-gpu` не вызывает LWJGL напрямую.
3. **Никакого мутируемого статического состояния.** Все ресурсы принадлежат `RenderContext`,
   передаваемому явно — это единственный способ корректно поддержать несколько миров/пересоздание
   контекста (F3+T, смена шейдерпака, dimension change) без утечек и гонок.
4. **CPU и GPU работы декларативно разделены** через `FrameGraph` (граф проходов кадра) —
   упрощает профилирование, вставку debug-маркеров, будущий переход на Vulkan.
5. **Данные версионируются с первого дня.** Любой персистентный формат несёт `schemaVersion`.
6. **Наблюдаемость — не опция.** `MetricsRegistry` собирает счётчики по умолчанию (с низкими накладными
   расходами через sampling), а не только под debug-флагом.

---

## 2. Модульная структура (Gradle multi-module)

```
ev/
├── ev-api/              # публичные интерфейсы, никаких зависимостей на LWJGL/NeoForge
├── ev-storage/          # персистентное воксельное хранилище + LOD-мипы, без GL
├── ev-meshing/          # генерация мешлетов из воксельных секций, без GL
├── ev-gpu/              # RenderBackend: единственное место с LWJGL/OpenGL кодом
├── ev-render/           # FrameGraph, traversal-оркестрация, использует ev-gpu через интерфейс
├── ev-neoforge/         # платформенный слой: mod entrypoint, события NeoForge, конфиг, команды
└── ev-test/             # интеграционные и юнит-тесты (headless, mock RenderBackend)
```

Зависимости строго однонаправленные:
`ev-neoforge -> ev-render -> ev-gpu`
`ev-render -> ev-meshing -> ev-storage -> ev-api`

`ev-gpu` не зависит от `ev-storage`/`ev-meshing` — он ничего не знает о вокселях,
только о буферах/шейдерах/командах отрисовки. Это разделение исключает ситуацию, когда GL-вызовы
вплетены в бизнес-логику traversal, а не изолированы за интерфейсом.

---

## 3. ev-api — контракты

```java
package dev.ev.api;

/** Позиция LOD-секции. Компактная long-кодировка, с явной версией формата. */
public record SectionPos(int level, int x, int y, int z) {
    public static final int MAX_LOD_LEVEL = 6; // достаточно глубоко — меньше геометрии на горизонте

    public long encode() {
        // level:4 | y:8 | z:26 | x:26 — оставляет запас на будущее
        return ((long) level << 60)
             | ((long) (y() & 0xFF) << 52)
             | ((long) (z() & 0x3FF_FFFF) << 26)
             | ((long) (x() & 0x3FF_FFFF));
    }

    public static SectionPos decode(long id) {
        int level = (int) (id >>> 60);
        int y = (byte) (id >>> 52);
        int z = (int) ((id << 6) >> 38); // sign-extend 26 бит
        int x = (int) ((id << 38) >> 38);
        return new SectionPos(level, x, y, z);
    }
}

public interface VoxelStorage extends AutoCloseable {
    int schemaVersion();
    WorldSectionHandle acquire(SectionPos pos);
    WorldSectionHandle acquireIfExists(SectionPos pos);
    void markDirty(WorldSectionHandle handle, DirtyFlags flags);
    boolean save(WorldSectionHandle handle);
    StorageMetrics metrics();
}

public interface MeshBuilder {
    /** Чисто CPU-side, тестируемо без GPU-контекста. */
    MeshletBatch build(WorldSectionHandle section, MeshingContext ctx);
}

/** Единственная граница между доменной логикой и рендер-бэкендом. */
public interface RenderBackend {
    GpuBuffer createBuffer(long sizeBytes, BufferUsage usage);
    GpuTexture createTexture(TextureDesc desc);
    ComputePipeline compilePipeline(ShaderSource source, PipelineLayout layout);
    void submit(CommandList commands);
    FenceHandle insertFence();
    boolean isSignaled(FenceHandle fence);
}
```

`RenderBackend` — это интерфейс. В `ev-gpu` лежит единственная
реализация `GLRenderBackend`, но архитектурно это позволяет:
- писать юнит-тесты доменной логики (`ev-render`) с `FakeRenderBackend` без окна/контекста GL;
- в будущем добавить `VkRenderBackend`, не трогая ни строчки в `ev-render`/`ev-meshing`.

---

## 4. ev-storage — персистентное хранилище

Ключевые проектные решения:

- **Версионированный формат.** Заголовок региона: `[magic:4][schemaVersion:2][compressionCodec:1][reserved:1]`.
  Загрузчик выбирает миграцию `SchemaMigrator` по цепочке v1→v2→...→current, вместо переписывания
  всей системы сохранения с нуля при каждом изменении формата.
- **Шардированный кэш секций** (`SectionCache`) — N shard-карт для снижения contention, с явным
  `CacheMetrics` (hit/miss/eviction rate) и настраиваемой политикой
  вытеснения (LRU по умолчанию, pluggable).
- **Компрессия по уровню LOD**: дальние/грубые LOD-секции почти всегда однородны (один материал) —
  используем RLE + палитровое кодирование с ранним выходом для однородных секций, реализованное
  явным, тестируемым, отдельным классом `PaletteCodec`.

```java
package dev.ev.storage;

public final class SectionCache {
    private final SectionShard[] shards; // степень двойки, число шардов подбирается под concurrency-профиль
    private final SectionLoader loader;
    private final EvictionPolicy eviction;
    private final CacheMetrics metrics = new CacheMetrics();

    public SectionCache(int shardBits, SectionLoader loader, EvictionPolicy eviction) {
        this.shards = new SectionShard[1 << shardBits];
        for (int i = 0; i < shards.length; i++) shards[i] = new SectionShard();
        this.loader = loader;
        this.eviction = eviction;
    }

    public WorldSectionHandle acquire(long encodedPos, boolean onlyIfExists) {
        SectionShard shard = shards[shardIndex(encodedPos)];
        WorldSectionHandle handle = shard.get(encodedPos);
        if (handle != null) { metrics.recordHit(); return handle.retain(); }
        metrics.recordMiss();
        if (onlyIfExists && !loader.exists(encodedPos)) return null;
        handle = loader.load(encodedPos);
        shard.put(encodedPos, handle);
        eviction.onInsert(handle);
        return handle.retain();
    }

    private int shardIndex(long pos) {
        // хорошее перемешивание битов позиции вместо простого modulo — снижает hash-collisions
        long h = pos * 0x9E3779B97F4A7C15L;
        return (int) (h >>> (64 - Integer.numberOfTrailingZeros(shards.length)));
    }
}
```

---

## 5. ev-meshing — генерация геометрии

Задача — избежать монолитного класса, который параллельно занимается
occupancy-масками, гринедовым (greedy) мешингом, атласной упаковкой текстур и биннингом по материалам.

Решение — конвейер отдельных **стадий** (`MeshingStage`), каждая — небольшой тестируемый класс:

```
VoxelSection
   │
   ▼
[OccupancyStage]      → OccupancySet (какие вокселы непустые, с юнит-тестами на границы/edge cases)
   │
   ▼
[GreedyMeshStage]      → список quad'ов (2D-плоскостной greedy meshing по осям)
   │
   ▼
[MaterialBinStage]     → группировка по материалу/атласу
   ▼
[MeshletPackStage]     → упаковка в GPU-совместимые meshlet-буферы (fixed-size, для meshlet culling)
   ▼
MeshletBatch (готово к загрузке в GPU через RenderBackend)
```

Каждая стадия — `interface MeshingStage<In, Out> { Out process(In in, MeshingContext ctx); }`,
конвейер собирается декларативно в `MeshingPipeline`, что даёт:
- независимое юнит-тестирование каждой стадии;
- возможность профилировать стадию отдельно (микро-бенчмарки), а не гадать, что в одном большом
  файле является узким местом;
- возможность подменить/расширить одну стадию (например, marching-cubes для дальних LOD вместо
  greedy quads) без переписывания остального пайплайна.

Приоритизация задач мешинга вычисляется как чистая
функция (тестируема отдельно от очереди):

```java
package dev.ev.meshing;

public final class MeshPriority {
    // Ниже — выше приоритет. Разложено на явные компоненты вместо магической однострочной формулы:
    public static long compute(int lodLevel, int maxLodLevel, int attempts, boolean playerFacing, long insertionSeq) {
        int lodWeight = Math.min(maxLodLevel - lodLevel, 3);      // ближние/детальные LOD важнее
        int attemptWeight = Math.min(attempts, 3);                // старение — избегаем starvation
        int facingBonus = playerFacing ? 0 : 1;                   // то, что не в кадре — чуть позже
        long primary = ((long) lodWeight << 8) | (attemptWeight << 4) | facingBonus;
        return (primary << 32) | (insertionSeq & 0xFFFFFFFFL);    // FIFO-разрыв тайов по вставке
    }
}
```

---

## 6. ev-gpu — рендер-бэкенд и traversal

### 6.1 Главное архитектурное улучшение: persistent-thread traversal

Наивный подход — N последовательных `glDispatchComputeIndirect` с `glMemoryBarrier` между каждым, по
одному проходу на уровень LOD-дерева — то есть до `MAX_LOD_LAYER+1` CPU-GPU синхронизационных точек
за кадр только на обход дерева, что при большом render distance (глубоком дереве) масштабируется
линейно по глубине.

**EV решает это одним dispatch**: один compute pass запускает ровно `numWorkgroups` персистентных
групп потоков, которые сами вычитывают работу из shared atomic-очереди (SSBO ring-buffer с
`atomicAdd`/`atomicCompSwap` head/tail), обходят иерархию узлов и **сами** добавляют дочерние узлы
обратно в ту же очередь (self-feeding queue), пока очередь не опустеет — техника "persistent kernel /
GPU work queue", применяемая в GPU-driven рендерерах (Nanite-подобные системы).

```glsl
// ev_traversal.comp — концептуальный псевдокод persistent-kernel обхода
layout(local_size_x = 64) in;

layout(std430, binding = QUEUE_BINDING) buffer WorkQueue {
    uint head;       // atomic: индекс следующего элемента для чтения
    uint tail;       // atomic: индекс следующей свободной ячейки для записи
    uint capacity;
    uint items[];    // кольцевой буфер id узлов
};

shared uint localBatch[64];

void main() {
    while (true) {
        uint idx = atomicAdd(head, 1);
        if (idx >= atomicLoad_relaxed(tail)) {
            // Очередь временно пуста — не выходим сразу, а barrier + повторная проверка,
            // т.к. другие workgroups могли ещё добавить работу (дочерние узлы).
            memoryBarrierBuffer();
            if (allWorkgroupsIdle()) break; // через глобальный atomic-счётчик активных потоков
            continue;
        }
        uint nodeId = items[idx % capacity];
        Node node = loadNode(nodeId);

        if (isOccluded(node)) continue;               // Hi-Z occlusion test
        if (needsChildren(node)) {
            uint childMask = visibleChildren(node);
            enqueueChildren(node, childMask);          // atomicAdd(tail, n) — сами добавляем работу
        } else {
            appendToRenderList(node);
        }
        if (!isResident(node)) requestStreaming(nodeId); // request queue для потокового стриминга
    }
}
```

Практический эффект: барьеры между уровнями дерева заменяются на barrier'ы только внутри workgroup
и глобальный "все ли простаивают" atomic-счётчик — GPU driver overhead и CPU-side dispatch-count
падают с O(глубина дерева) до O(1) на кадр.

### 6.2 FrameGraph

Вместо ручной последовательности `bindings()`/`glMemoryBarrier()`/`glDispatchCompute()`, разбросанной
по методам, EV описывает кадр декларативно:

```java
package dev.ev.render;

public final class EVFrameGraph {
    public void buildFrame(FrameGraphBuilder fg, RenderContext ctx) {
        var hiZ = fg.addPass("hiz-build", HiZBuildPass::new)
                     .reads(ctx.depthTexture())
                     .writes(ctx.hizPyramid());

        var traversal = fg.addPass("hierarchical-traversal", TraversalPass::new)
                           .reads(hiZ.output(), ctx.nodeBuffer())
                           .writes(ctx.renderList(), ctx.streamingRequests());

        var meshUpload = fg.addPass("mesh-upload", MeshUploadPass::new)
                            .reads(ctx.pendingMeshlets())
                            .writes(ctx.geometryBuffer());

        fg.addPass("lod-render", LodRenderPass::new)
          .reads(traversal.output(), meshUpload.output())
          .writesColor(ctx.mainColorTarget());
    }
}
```

`FrameGraphBuilder` разрешает зависимости, автоматически вставляет минимально необходимые барьеры
(вместо ручных `glMemoryBarrier` россыпью по коду) и даёт единую точку для GPU-таймингов на пасс,
а не набор отдельных несвязанных утилит.

### 6.3 Адаптивные размеры буферов

Наивный подход — жёстко заданные константы вроде `MAX_QUEUE_SIZE = 200_000` и
`MAX_REQUEST_QUEUE_SIZE = 50`, подобранные "на глаз", с ручными workaround'ами при переполнении.

EV вычисляет размеры очередей из конфигурации и бюджета VRAM при инициализации, с политикой
graceful degradation (снижение render distance вместо обрезания данных и артефактов):

```java
public record QueueBudget(int nodeQueueCapacity, int requestQueueCapacity, int meshletBufferCapacity) {
    public static QueueBudget compute(long availableVramBytes, int renderDistanceChunks, int maxLodLevel) {
        long estimatedNodes = estimateOctreeNodeCount(renderDistanceChunks, maxLodLevel);
        long budget = Math.min(availableVramBytes / 8, estimatedNodes * NODE_STRIDE_BYTES * 2);
        int nodeCapacity = (int) Math.min(Integer.MAX_VALUE, budget / NODE_STRIDE_BYTES);
        return new QueueBudget(
            nodeCapacity,
            Math.max(256, nodeCapacity / 512),      // масштабируется, не фиксированное "50"
            Math.max(4096, nodeCapacity / 8)
        );
    }
}
```

---

## 7. ev-neoforge — платформенный слой

NeoForge даёт публичные события для интеграции с рендер-пайплайном — используем их вместо мiксинов
там, где это возможно, снижая хрупкость при обновлениях Minecraft:

```java
package dev.ev.neoforge;

@Mod(EV.MODID)
public final class EV {
    public static final String MODID = "ev";
    private EVInstance instance;

    public EV(IEventBus modBus) {
        modBus.addListener(this::onClientSetup);
        NeoForge.EVENT_BUS.addListener(this::onRenderLevelStage);
        NeoForge.EVENT_BUS.addListener(this::onLevelUnload);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        this.instance = EVInstance.bootstrap(EVConfig.load());
    }

    // RenderLevelStageEvent — публичный NeoForge render pipeline hook,
    // вместо приватных mixin-инъекций в LevelRenderer.
    private void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) {
            this.instance.renderFarLod(event.getLevelRenderState(), event.getProjectionMatrix());
        }
    }

    private void onLevelUnload(LevelEvent.Unload event) {
        this.instance.releaseWorld(event.getLevel());
    }
}
```

Там, где публичного хука NeoForge объективно нет (например, перехват chunk-culling ванильного
рендерера, чтобы не рисовать дважды перекрытую геометрию), миксины всё равно нужны — но
их держим в отдельном тонком `ev-neoforge-mixin-compat` под-модуле с явно
документированной картой "что именно и почему инъецировано", чтобы обновление MC-версии требовало
правки одного изолированного модуля, а не поиска по всему дереву классов.

---

## 8. Наблюдаемость с первого дня

```java
package dev.ev.api.metrics;

public interface MetricsRegistry {
    void recordQueueDepth(String queueName, int depth);
    void recordCacheHitRate(String cacheName, double hitRate);
    void recordGpuPassDuration(String passName, long nanos);
    EVDebugSnapshot snapshot(); // для /ev debug и F3-оверлея
}
```

`MetricsRegistry` в EV всегда активен с лёгким сэмплированием (раз в N кадров) — не статический
boolean-флаг, включающий дополнительный SSBO и код внутри шейдера только при явной пересборке с
дефайном. Детальный GPU-timing включается точечно через команду `/ev profile <pass>` без
пересборки шейдеров.

---

## 9. Тестирование

```
ev-test/
├── storage/    SectionCacheTest, PaletteCodecTest, SchemaMigrationTest
├── meshing/    OccupancyStageTest, GreedyMeshStageTest, MeshPriorityTest
├── render/     FrameGraphBuilderTest (проверка порядка/барьеров без реального GL контекста)
└── gpu/        FakeRenderBackend — in-memory реализация RenderBackend для тестов выше по стеку
```

`FakeRenderBackend` — ключевой элемент: он позволяет тестировать 90% доменной логики (storage,
meshing, приоритизация, frame graph) в CI без GPU/дисплея.

---

## 10. Итоговый список ключевых архитектурных решений

1. Модульность (6 независимых Gradle-модулей) вместо монолитного пакета — быстрее компиляция
   инкрементально, чётче границы ответственности, легче онбординг новых контрибьюторов.
2. `RenderBackend`-абстракция изолирует весь LWJGL/GL-код в одном модуле — остальные 5 модулей
   тестируемы без GPU и потенциально портируемы на другой графический бэкенд.
3. Persistent-thread traversal устраняет O(глубина дерева) CPU-GPU барьеров за кадр.
4. FrameGraph даёт автоматическое управление барьерами и встроенный per-pass GPU-timing вместо
   ручной расстановки `glMemoryBarrier` и опционального statistics-кода.
5. Версионированный формат хранения секций с миграциями — устраняет необходимость переписывать
   систему сохранения с нуля при каждом инкременте схемы.
6. Адаптивные, вычисляемые из VRAM-бюджета размеры очередей вместо жёстких констант с ручным clamp
   и warning-логами при переполнении.
7. Конвейер мешинга из маленьких тестируемых стадий вместо 1806-строчного класса.
8. Нет мутируемого статического состояния — корректная работа с несколькими мирами/пересозданием
   контекста без риска гонок данных.
9. Наблюдаемость (метрики, debug snapshot) как часть публичного API с первого дня, не как
   постфактум добавленный debug-флаг.
10. Юнит- и интеграционные тесты для всей non-GPU логики через `FakeRenderBackend`.