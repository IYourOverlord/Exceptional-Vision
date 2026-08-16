# Анализ архива Distant.zip (мод "Voxy", package me.cortex.voxy)

## Платформа
- **Fabric**, НЕ NeoForge (fabric.mod.json, fabric-loom, mixins в client/common namespace, accesswidener).
- Java 21-стиль, LWJGL напрямую (OpenGL 4.5/4.6 compute shaders), без Sodium API — свой рендер-конвейер,
  с опциональной интеграцией Iris (IrisVoxyRenderPipeline) и Nvidium (ivy-репозиторий в build.gradle).
- Цель мода: экстремальная дальняя прорисовка (far render distance) поверх LOD-октрива, отдельно от
  ванильного chunk rendering.

## Ключевая архитектурная идея
Полностью **GPU-driven разреженный воксельный октодерево (sparse voxel octree) LOD** пайплайн:
1. Мир хранится в собственном персистентном сторадже (WorldEngine/SectionStorage/Mapper) —
   отдельно от ChunkSection ванильной игры, в компактном "voxelized" формате с уровнями LOD (0..4).
2. Активные секции трекаются через ActiveSectionTracker с многоуровневым (64 sharded map) кэшем
   и weak/soft reference-подобным вытеснением.
3. Рендер-данные (мешлеты) строятся асинхронным пулом воркеров (RenderGenerationService,
   PriorityBlockingQueue с приоритетом = f(LOD-уровень, попытки, insertion order)).
4. На GPU крутится HierarchicalOcclusionTraverser — compute-shader traversal иерархии узлов
   (octree) с Hierarchical-Z occlusion culling, ping-pong очередями (scratchQueueA/B),
   indirect dispatch по итерациям (MAX_ITERATIONS = MAX_LOD_LAYER+1).
   Узлы, которые нужно подгрузить, "request"-буфером улетают обратно на CPU (AsyncNodeManager),
   тем самым цикл load/stream управляется самим GPU, а не CPU-heuristics.
5. NodeManager/NodeStore/NodeCleaner — управление GPU-резидентной иерархией узлов
   (аллокация/деаллокация слотов, freelist, LRU-подобная очистка).
6. Собственная система шейдеров (AutoBindingShader, авто-байндинг SSBO/UBO по имени).

## Сильные стороны (что стоит перенять принципиально, не копируя код)
- Разделение "world data" (воксельное хранилище LOD) и "render data" (мешлеты/буферы GPU) —
  чистая слоистая архитектура: storage -> section tracker -> mesh builder -> GPU node hierarchy -> traversal.
- GPU-driven culling убирает readback-latency и CPU bottleneck на traversal большого дерева.
- Приоритетная очередь для генерации мешей с "aging" (attempts) — предотвращает starvation дальних/сложных задач.
- Persistent mapped buffers, ring-buffers, upload/download streaming (UploadStream/DownloadStream) —
  избегают stall-ов GPU<->CPU.
- Отдельный ServiceManager/UnifiedServiceThreadPool — свой лёгкий планировщик задач вместо ForkJoinPool
  общего назначения, с приоритетным семафором (MultiThreadPrioritySemaphore) для балансировки IO/CPU работ.

## Слабые/рискованные места (потенциал для превосходства)
1. **Жёсткая привязка к Fabric и к OpenGL напрямую через LWJGL** — не абстрагировано от рендер-бэкенда;
   на NeoForge (по сути тот же Minecraft, но другой modloader) миксины/точки входа придётся переписывать,
   но сама GL-архитектура НЕ платформозависима — можно и нужно перенести именно она.
2. **Много "TODO/FIXME" прямо в проде** (например: "swap to persistent gpu threads instead of MAX_ITERATIONS
   compute dispatches", "add render cache", workaround под Intel/AMD/Mesa драйверные баги встроены в hot path).
3. Traversal делает **N последовательных compute dispatch'ей с barrier'ами** на глубину дерева (до 5 уровней) —
   каждый dispatch+barrier это синхронизационная точка; персистентные GPU-потоки (persistent kernel с
   собственной work-stealing очередью в самом шейдере) убрали бы barrier-cost между уровнями.
3b. Фиксированные `MAX_QUEUE_SIZE = 200_000`, `MAX_REQUEST_QUEUE_SIZE = 50` — константы на глаз, не адаптивные
    к размеру мира/VRAM.
4. RenderDataFactory — монолит на **1806 строк** в одном классе: явное нарушение SRP, сложно
   параллельно развивать/тестировать/профилировать по частям.
5. Использование `MemoryUtil.nmemAlloc` статического scratch-буфера (32 байта, `static final`) в
   HierarchicalOcclusionTraverser — общий мутируемый статический стейт, потенциальный источник багов
   при нескольких мирах/instances (VoxyInstance подразумевает multi-world, но статик on top ломает это).
6. Нет отдельного слоя абстракции над GPU API (Vulkan/DX12 недостижимы) — при появлении новых бэкендов
   LWJGL/Minecraft (RenderSystem NeoForge/Vanilla меняется версия к версии) весь GL-код придётся переписывать руками.
7. Сохранение мира (SaveLoadSystem3, "3" в имени намекает на минимум 2 прошлых переписывания) —
   признак того, что формат хранения секций менялся с нуля несколько раз: нет версионируемой,
   миграционно-устойчивой схемы сериализации с самого начала.
8. Нет видимого модуля тестов (не нашли src/test) для настолько сложной конкурентной/GPU-логики.

## Вывод для нашей архитектуры
Строим для NeoForge 1.21.1 мод дальней прорисовки/LOD-рендеринга со следующими улучшениями относительно Voxy:
- Чёткое разделение на независимые Gradle-подмодули (api / storage / meshing / gpu / render / platform-neoforge)
  вместо одного монолитного пакета — облегчает тестирование, параллельную разработку, повторное использование.
- GPU-абстракция (RenderBackend interface) поверх LWJGL, чтобы traversal/meshing-логика не была
  завязана напрямую на голые GL-вызовы разбросанные по классам — упрощает поддержку будущих
  версий Minecraft/RenderSystem и even Vulkan через lwjgl3 vk bindings в будущем.
- Traversal через **persistent-thread compute kernel** (один dispatch, шейдер сам крутит work-stealing
  цикл через atomic-очередь в SSBO) вместо N dispatch+barrier по глубине дерева — меньше CPU-GPU
  synchronization overhead, что даёт реальный выигрыш там, где Voxy сам пишет TODO об этом.
- Адаптивные размеры очередей (авто-подбор от VRAM/render distance), с graceful degradation вместо
  жёстких констант с риском overflow.
- Версионированный формат хранения секций с миграциями с первого дня (SemVer-like schema version в заголовке).
- Юнит-тесты для чистой (non-GL) логики: приоритетные очереди, allocator, octree indexing, sparse storage.
- Явный ServiceManager с метриками (queue depth, latency) — наблюдаемость с первого дня, не post-hoc.
