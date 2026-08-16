# EV — GPU-driven LOD дальней прорисовки для NeoForge 1.21.1

> Спроектировано как архитектурное превосходство над Voxy (проанализирован из Distant.zip).
> См. `notes_voxy_analysis.md` за разбором прототипа.

## 0. Итог сравнения с Voxy

| Аспект | Voxy (Fabric) | EV (NeoForge, этот дизайн) |
|---|---|---|
| Модлоадер | Fabric + mixins, привязан к Fabric API | NeoForge, ModEvent/Bus API, минимум mixins |
| Структура кода | 1 gradle-модуль, монолит (RenderDataFactory — 1806 строк) | 6 gradle-подмодулей по слоям, файлы ≤400 строк |
| GPU-абстракция | Прямые вызовы LWJGL GL45 разбросаны по классам | `RenderBackend` интерфейс, GL-реализация изолирована в 1 модуле |
| Traversal иерархии | N последовательных compute-dispatch + barrier по глубине дерева (TODO в коде: "swap to persistent gpu threads") | Persistent-thread compute kernel, 1 dispatch, work-stealing очередь в SSBO |
| Размеры очередей | Жёсткие константы (`MAX_QUEUE_SIZE=200_000`) | Адаптивный расчёт от VRAM/render distance + graceful degradation |
| Формат хранения | `SaveLoadSystem3` — переписан минимум дважды, без версии схемы | Версионированная схема (schema version в заголовке) + миграторы с v1 |
| Наблюдаемость | Статистика добавлена частично, только под флагом | Метрики (queue depth, GPU timing, cache hit-rate) как часть API с первого дня |
| Тесты | Не обнаружены | Юнит-тесты для всей non-GL логики (allocator, octree indexing, приоритеты) |
| Многомировая поддержка | Статический scratch-буфер в traverser — риск при нескольких инстансах | Никакого mutable static state; всё через instance-scoped контекст |

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
только о буферах/шейдерах/командах отрисовки. Это устраняет главный недостаток Voxy: GL-вызовы,
вплетённые в бизнес-логику traversal (см. `HierarchicalOcclusionTraverser`, где GL45-константы,
приоритетная логика и доменные структуры данных живут в одном файле).

---

## 3. ev-api — контракты

```java
package dev.ev.api;

/** Позиция LOD-секции. Компактная long-кодировка, аналогично Voxy, но с явной версией формата. */
public record SectionPos(int level, int x, int y, int z) {
    public static final int MAX_LOD_LEVEL = 6; // на 2 больше чем у Voxy — глубже LOD, меньше геометрии на горизонте

    public long encode() {
        // level:4 | y:8 | z:26 | x:26  — оставляет запас на будущее (Voxy использовал 24 бита z/x)
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

Ключевое отличие от Voxy: `RenderBackend` — это интерфейс. В `ev-gpu` лежит единственная
реализация `GLRenderBackend`, но архитектурно это позволяет:
- писать юнит-тесты доменной логики (`ev-render`) с `FakeRenderBackend` без окна/контекста GL;
- в будущем добавить `VkRenderBackend`, не трогая ни строчки в `ev-render`/`ev-meshing`.

---

## 4. ev-storage — персистентное хранилище

Улучшения относительно `WorldEngine`/`Mapper`/`SaveLoadSystem3` из Voxy:

- **Версионированный формат.** Заголовок региона: `[magic:4][schemaVersion:2][compressionCodec:1][reserved:1]`.
  Загрузчик выбирает миграцию `SchemaMigrator` по цепочке v1→v2→...→current, вместо переписывания
  всей системы сохранения с нуля при изменении формата (что явно произошло у Voxy минимум дважды,
  судя по суффиксу `3` в `SaveLoadSystem3`).
- **ActiveSectionTracker-аналог** (`SectionCache`) — та же идея шардированного кэша (N shard-карт для
  снижения contention), но с явным `CacheMetrics` (hit/miss/eviction rate) и настраиваемой политикой
  вытеснения (LRU по умолчанию, pluggable).
- **Компрессия по уровню LOD**: дальние/грубые LOD-секции почти всегда однородны (один материал) —
  используем RLE + палитровое кодирование с ранним выходом для однородных секций (Voxy тоже использует
  палитры через `Mapper`, но у нас это явный, тестируемый, отдельный класс `PaletteCodec`, а не
  вплетено в 443-строчный `Mapper`).

```java
package dev.ev.storage;

public final class SectionCache {
    private final SectionShard[] shards; // степень двойки, аналогично 64 шардам ActiveSectionTracker
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

Проблема Voxy: `RenderDataFactory` — 1806 строк в одном файле, который параллельно занимается
occupancy-масками, гринедовым (greedy) мешингом, атласной упаковкой текстур и биннингом по материалам.

Решение — конвейер отдельных **стадий** (`MeshingStage`), каждая — небольшой тестируемый класс:

```
VoxelSection
   │
   ▼
[OccupancyStage]      → OccupancySet (какие вокселы непустые; тот же принцип что OccupancySet2 у Voxy,
   │                     но с юнит-тестами на границы/edge cases)
   ▼
[GreedyMeshStage]      → список quad'ов (2D-плоскостной greedy meshing по осям, аналог ScanMesher2D)
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
- независимое юнит-тестирование каждой стадии (Voxy тестов не имеет вовсе);
- возможность профилировать стадию отдельно (микро-бенчмарки), а не гадать, что в 1806-строчном
  файле является узким местом;
- возможность подменить/расширить одну стадию (например, marching-cubes для дальних LOD вместо
  greedy quads) без переписывания остального пайплайна.

Приоритизация задач мешинга — аналог `RenderGenerationService`, но приоритет вычисляется как чистая
функция (тестируема отдельно от очереди):

```java
package dev.ev.meshing;

public final class MeshPriority {
    // Ниже — выше приоритет. Разложено на явные компоненты вместо магической однострочной формулы Voxy:
    //   (((lvl*3L + min(attempts,3))*2 + addin) << 32) + unique
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

Voxy сам отмечает TODO: *"swap to persistent gpu threads instead of dispatching MAX_ITERATIONS of
compute layers"*. `HierarchicalOcclusionTraverser.traverseInternal()` делает до `MAX_LOD_LAYER+1` (=5)
последовательных `glDispatchComputeIndirect` с `glMemoryBarrier` между каждым — то есть до 5
CPU-GPU синхронизационных точек за кадр только на обход дерева, что при большом render distance
(глубоком дереве) масштабируется линейно по глубине.

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

        if (isOccluded(node)) continue;               // Hi-Z occlusion test, как у Voxy
        if (needsChildren(node)) {
            uint childMask = visibleChildren(node);
            enqueueChildren(node, childMask);          // atomicAdd(tail, n) — сами добавляем работу
        } else {
            appendToRenderList(node);
        }
        if (!isResident(node)) requestStreaming(nodeId); // тот же паттерн что у Voxy: request queue
    }
}
```

Практический эффект: барьеры между уровнями дерева заменяются на barrier'ы только внутри workgroup
и глобальный "все ли простаивают" atomic-счётчик — GPU driver overhead и CPU-side dispatch-count
падают с O(глубина дерева) до O(1) на кадр. Это прямое устранение задокументированного, но
неисправленного узкого места Voxy.

### 6.2 FrameGraph

Вместо ручной последовательности `bindings()`/`glMemoryBarrier()`/`glDispatchCompute()`, разбросанной
по методам (как в `HierarchicalOcclusionTraverser.doTraversal`), EV описывает кадр декларативно:

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
(вместо ручных `glMemoryBarrier` россыпью по коду) и даёт единую точку для GPU-таймингов на пасс —
то, что в Voxy сделано вручную и только частично (`RenderStatistics`, `GPUTiming` как отдельные
несвязанные утилиты).

### 6.3 Адаптивные размеры буферов

Voxy жёстко задаёт `MAX_QUEUE_SIZE = 200_000` и `MAX_REQUEST_QUEUE_SIZE = 50` — константы,
подобранные "на глаз", с ручными workaround'ами при переполнении (`Logger.warn("Count over max
buffer size, clamping...")`).

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

NeoForge даёт события, которых у Fabric нет "из коробки" в таком виде — используем их вместо miксинов
там, где это возможно, снижая хрупкость при обновлениях Minecraft (у Voxy — 180K миксинов в
`client/mixin`, что является главной точкой поломки при апдейте версии игры):

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
    // вместо приватных mixin-инъекций в LevelRenderer, как у Voxy.
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
правки одного изолированного модуля, а не поиска по всему дереву классов, как в Voxy
(`client/mixin` — 180K, `commonImpl/mixin` — 20K, вперемешку с бизнес-логикой).

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

В отличие от Voxy, где `RenderStatistics.enabled` — статический boolean-флаг, включающий
дополнительный SSBO и код внутри шейдера только при явной компиляции с дефайном, `MetricsRegistry`
в EV всегда активен с лёгким сэмплированием (раз в N кадров), а детальный GPU-timing
(`GPUTiming`-подобный) включается точечно через команду `/ev profile <pass>` без пересборки шейдеров.

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
meshing, приоритизация, frame graph) в CI без GPU/дисплея, чего у Voxy нет вовсе.

---

## 10. Итоговый список конкретных архитектурных превосходств

1. Модульность (6 независимых Gradle-модулей) вместо монолитного пакета — быстрее компиляция
   инкрементально, чётче границы ответственности, легче онбординг новых контрибьюторов.
2. `RenderBackend`-абстракция изолирует весь LWJGL/GL-код в одном модуле — остальные 5 модулей
   тестируемы без GPU и потенциально портируемы на другой графический бэкенд.
3. Persistent-thread traversal устраняет O(глубина дерева) CPU-GPU барьеров за кадр — прямое
   исправление зафиксированного, но не сделанного в Voxy TODO.
4. FrameGraph даёт автоматическое управление барьерами и встроенный per-pass GPU-timing вместо
   ручной расстановки `glMemoryBarrier` и опционального statistics-кода.
5. Версионированный формат хранения секций с миграциями — устраняет паттерн "переписать
   SaveLoadSystem с нуля при инкременте номера" (SaveLoadSystem**3** в Voxy).
6. Адаптивные, вычисляемые из VRAM-бюджета размеры очередей вместо жёстких констант с ручным clamp
   и warning-логами при переполнении.
7. Конвейер мешинга из маленьких тестируемых стадий вместо 1806-строчного класса.
8. Нет мутируемого статического состояния — корректная работа с несколькими мирами/пересозданием
   контекста без риска гонок данных.
9. Наблюдаемость (метрики, debug snapshot) как часть публичного API с первого дня, не как
   постфактум добавленный debug-флаг.
10. Юнит- и интеграционные тесты для всей non-GPU логики через `FakeRenderBackend`.
