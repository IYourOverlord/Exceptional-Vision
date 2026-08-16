# PROJECT_INDEX.md — EV quick reference

Быстрый справочник по текущему состоянию кода. Читай это ПЕРЕД тем, как обходить файлы
вручную. Для истории решений/найденных проблем — см. `PROGRESS.md`; этот файл — только
структура/lookup, не история.

Цель: NeoForge 1.21.1, Java 21, GPU-driven LOD far-render mod. См. ARCHITECTURE.md /
PERFORMANCE_MATH.md для полного архитектурного обоснования, MVP_INDEX.md для плана тикетов
и двухволновой (MVP/opt) структуры (INDEX.md больше не существует — MVP_INDEX.md его заменил
и включает всё, что было там актуального).

**Каждый тикет (00-31, любой mvp/opt суффиксный вариант, и `P0-profiling-checkpoint.md`),
выполняемый ПОСЛЕ этого, обязан завершиться обновлением этого файла** — см. тикет
`tickets/32-project-index.md`, Шаг 2, за точным форматом. Сам факт существования и
заметности этого файла в корне репозитория — достаточный сигнал вести его дальше, даже если
текст конкретного тикета не упоминает тикет 32 напрямую.

## Статус тикетов

| Тикет | Статус | Заметка |
|---|---|---|
| 00-project-skeleton | DONE | Multi-module Gradle-структура в корне репозитория (заменила старый одномодульный проект Exceptional Vision — старые `src/`, `build.gradle`, `settings.gradle` удалены). Версии NeoForge/parchment взяты из `gradle.properties` старого проекта перед удалением (см. PROGRESS.md — изначальный web-search дал неверную версию, было исправлено). Сборка `./gradlew build` НЕ верифицирована в песочнице агента — см. секцию "Сборка/тесты" ниже. |
| 32-project-index | DONE | Этот файл. Выполнен вторым, после 00 — ретроактивных пробелов нет, весь существующий код на момент выполнения отражён ниже. |
| 01-api-sectionpos | DONE | `SectionPos` record реализован 1-в-1 по контракту тикета. Тесты написаны, но не прогнаны фактически (нет `javac`/Gradle в песочнице — см. секцию "Сборка/тесты" ниже). |
| 02-api-storage-interfaces | DONE | `VoxelStorage`/`DirtyFlags`/`WorldSectionHandle`/`StorageMetrics` созданы. **Известная проблема**: `markDirty(handle, DirtyFlags flags)` берёт non-instantiable тип как параметр — см. "Известные архитектурные инварианты" ниже и PROGRESS.md. |
| 03-api-meshing-interfaces | DONE | `MeshingContext`, `Quad`, `Meshlet`, `MeshletBatch`, `MeshBuilder` созданы дословно по контракту. |
| 04-api-renderbackend-interfaces | DONE | 13 типов в `dev.ev.api.gpu` созданы дословно по контракту (по одному публичному типу на файл). Никаких LWJGL/NeoForge импортов — проверено. Пункт 4 требований (доп. методы для persistent-traversal) сознательно не применён — см. PROGRESS.md. |
| 05-api-metrics-interfaces | DONE | `MetricsRegistry`, `MetricsSnapshot`, `ImportStageStatus` созданы дословно. Последний из пяти api-тикетов — весь `ev-api` теперь укомплектован контрактами (01-05). |
| 06-storage-palette-codec | NOT_STARTED | |
| 07-storage-schema-migration | NOT_STARTED | |
| 08-storage-section-cache (mvp/opt) | NOT_STARTED | |
| 09-storage-heightmap-coarse-gen | NOT_STARTED | |
| 10-meshing-occupancy-stage | NOT_STARTED | |
| 11-meshing-greedy-mesh-stage | NOT_STARTED | |
| 12-meshing-material-bin-stage | NOT_STARTED | |
| 13-meshing-meshlet-pack-stage | NOT_STARTED | |
| 14-meshing-priority-function | NOT_STARTED | |
| 15-meshing-priority-queue (mvp/opt) | NOT_STARTED | |
| 16-meshing-mipgen (mvp/opt) | NOT_STARTED | |
| 17-gpu-backend-gl-buffers | NOT_STARTED | |
| 18-gpu-backend-gl-shaders | NOT_STARTED | |
| 19-gpu-upload-batching-opt | NOT_STARTED | |
| 20-gpu-node-buffer (mvp/opt) | NOT_STARTED | |
| 21-gpu-traversal (simple-mvp/persistent-opt) | NOT_STARTED | |
| 22-gpu-hiz-occlusion-opt | NOT_STARTED | |
| 23-gpu-indirect-multidraw-opt | NOT_STARTED | |
| 24-render-frame-graph | NOT_STARTED | |
| 25-render-temporal-reprojection-opt | NOT_STARTED | |
| 26-render-dirty (tracking-mvp/subregion-opt) | NOT_STARTED | |
| 27-neoforge-mod-entrypoint | IN_PROGRESS | Минимальный класс `EV` создан тикетом 00 (пустой entrypoint, только лог). Полноценная реализация (события, worker pool) — предмет самого тикета 27, ещё не выполнена. |
| 28-neoforge-config | NOT_STARTED | |
| 29-neoforge-commands | NOT_STARTED | |
| 30-test-fake-render-backend | NOT_STARTED | |
| 31-integration-checklist (mvp/full) | NOT_STARTED | |
| P0-profiling-checkpoint | NOT_STARTED | |

## 🌊 Статус MVP/opt волн

| Подсистема | Активная версия | Тикет | Дата/повод перехода (если opt) |
|---|---|---|---|
| Кэш секций | нет (не реализовано) | — | — |
| Очередь мешинга | нет (не реализовано) | — | — |
| Node buffer | нет (не реализовано) | — | — |
| Traversal | нет (не реализовано) | — | — |
| Occlusion culling | нет (MVP не имеет) | — | — |
| Temporal coherence | нет (MVP не имеет) | — | — |
| Mip-агрегация | нет (не реализовано) | — | — |
| GPU upload | нет (не реализовано) | — | — |
| Draw calls | нет (не реализовано) | — | — |
| Dirty tracking | нет (не реализовано) | — | — |

## Модульная карта (`ev-*/src/main/java/dev/ev/...`)

| Модуль | Пакет | Назначение |
|---|---|---|
| ev-neoforge | `dev.ev.neoforge` | Entrypoint мода, регистрация в NeoForge. Пока только класс `EV`. |
| ev-api | `dev.ev.api`, `dev.ev.api.storage`, `dev.ev.api.meshing`, `dev.ev.api.gpu`, `dev.ev.api.metrics` | Все 5 api-тикетов (01-05) выполнены — `SectionPos`; storage-, meshing-, gpu- и metrics-контракты. Модуль `ev-api` полностью укомплектован контрактами, дальше только реализация в `ev-storage`/`ev-meshing`/`ev-gpu`/`ev-render`. |
| ev-storage | — | Пусто. |
| ev-meshing | — | Пусто. |
| ev-gpu | — | Пусто. |
| ev-render | — | Пусто. |
| ev-test | — | Пусто, нет ни одного тестового класса. |

## Ключевые классы

### ev-neoforge — `dev.ev.neoforge`
- `EV` — entrypoint класса мода, аннотация `@Mod("ev")`, конструктор принимает
  `IEventBus`. Пока не делает ничего, кроме `LOGGER.info("EV mod loaded")` через SLF4J.
  Событий, worker pool, конфига — нет, это добавит тикет 27.

### ev-api — `dev.ev.api`
- `SectionPos(int level, int x, int y, int z)` — immutable record, позиция LOD-секции.
  `MAX_LOD_LEVEL = 6`. Кодируется в единственный `long` через `encode()`/статический
  `decode(long)` — layout: биты 60-63 level(4), 52-59 y(8 signed), 26-51 z(26 signed),
  0-25 x(26 signed). Методы: `sizeInBlocks()` (32 << level), `parent()` (level+1,
  floorDiv/2, throws на MAX_LOD_LEVEL), `child(int 0..7)` (level-1, throws на level 0;
  бит 0 индекса = x-offset, бит 1 = y-offset, бит 2 = z-offset), `minBlockX/Y/Z()`,
  статический `fromBlockCoord(level, blockX, blockY, blockZ)` (floorDiv, не обычное
  деление — важно для отрицательных координат). Зависимостей нет, чистый JDK.
  Файл: `ev-api/src/main/java/dev/ev/api/SectionPos.java`.
  Тесты: `ev-api/src/test/java/dev/ev/api/SectionPosTest.java` (encode/decode round-trip
  на граничных значениях, parent↔child, sizeInBlocks по всем уровням, fromBlockCoord
  включая отрицательные координаты и floorDiv-границу, конструктор вне диапазона level).

### ev-api — `dev.ev.api.storage` (тикет 02)
- `VoxelStorage` — интерфейс, `extends Closeable`. `schemaVersion()`,
  `acquire(SectionPos)`/`acquireIfExists(SectionPos)` (retain/release семантика через
  возвращаемый `WorldSectionHandle`), `markDirty(WorldSectionHandle, DirtyFlags)` (см.
  ⚠️ ниже), `save(WorldSectionHandle) -> boolean`, `metrics() -> StorageMetrics`.
  Только контракт, реализации нет (появится в тикетах 06-09, модуль `ev-storage`).
- `DirtyFlags` — non-instantiable holder, `static final int` битовые константы
  (`BLOCK_CHANGED=1`, `CHILD_EXISTENCE_CHANGED=2`, `SKIP_PERSIST=4`,
  `DEFAULT=BLOCK_CHANGED|CHILD_EXISTENCE_CHANGED`), статический `has(int flags, int flag)`.
- `WorldSectionHandle` — интерфейс, ref-counted handle на данные секции. `position()`,
  `getVoxel/setVoxel(localX,localY,localZ,...)` (локальные координаты 0..31, секция всегда
  логическая сетка 32³ независимо от LOD уровня), `isEmpty()`, `retain()`/`release()`,
  `refCount()`.
- `StorageMetrics` — record (`cacheHits`, `cacheMisses`, `activeSectionCount`,
  `secondaryCacheSize`, `bytesOnDisk`), метод `hitRate()` (0.0 при total=0, не NaN).
  Тест: `StorageMetricsTest`.

**⚠️ Известное противоречие в тексте тикета 02 (не исправлено самостоятельно, см.
PROGRESS.md и Javadoc `VoxelStorage.markDirty`)**: сигнатура
`markDirty(WorldSectionHandle handle, DirtyFlags flags)` берёт `DirtyFlags` как тип
параметра, но `DirtyFlags` — non-instantiable класс (приватный конструктор, только
статические `int`-константы), создать значение этого типа невозможно. Компилируется
литерально, но практически невызываем как есть. Естественная правка — `int flags` — не
внесена самостоятельно, ждёт решения в следующей сессии/от пользователя. Любой тикет,
вызывающий `markDirty` (в первую очередь meshing/storage реализация), должен сначала
разрешить это несоответствие.

### ev-api — `dev.ev.api.meshing` (тикет 03)
- `MeshingContext` — интерфейс. `getNeighborBoundaryVoxel(faceDirection, a, b)` (значение
  соседнего вокселя за границей секции, для greedy-merge через границы),
  `resolveMaterialId(paletteIndex)`.
- `Quad` — record: `faceDirection` (0=+X,1=-X,2=+Y,3=-Y,4=+Z,5=-Z), `x,y,z` (origin corner
  в voxel-boundary units, 0..32 включительно), `width,height`, `materialId`.
- `Meshlet` — record: `List<Quad> quads` + bounding box (`boundsMinX/Y/Z`,
  `boundsMaxX/Y/Z`). Compact-constructor бросает `IllegalArgumentException`, если
  `quads.size() > MeshletBatch.MAX_QUADS_PER_MESHLET`. Тест: `MeshletTest`.
- `MeshletBatch` — record: `SectionPos section`, `List<Meshlet> meshlets`.
  `MAX_QUADS_PER_MESHLET = 128`. `totalQuadCount()` суммирует по всем meshlet'ам. Тест:
  `MeshletBatchTest`.
- `MeshBuilder` — интерфейс. `build(WorldSectionHandle, MeshingContext) -> MeshletBatch`.
  Только контракт — CPU-side stages (occupancy/greedy-mesh/material-bin/meshlet-pack)
  реализуются тикетами 10-13 в модуле `ev-meshing`.

### ev-api — `dev.ev.api.gpu` (тикет 04)
Самый важный архитектурный контракт проекта — единственная граница между доменной
логикой и реальным GPU API. Ни один класс вне `ev-gpu` не должен вызывать LWJGL напрямую.
13 типов, каждый в отдельном файле:
- `RenderBackend` — главный интерфейс: `createBuffer`, `createTexture`,
  `compilePipeline`/`compileGraphicsPipeline`, `submit(CommandList)`, fence API
  (`insertFence`/`isSignaled`/`waitForFence`), `shutdown()`.
- `BufferUsage` (enum: `STATIC_DRAW`, `DYNAMIC_DRAW`, `STORAGE`, `STAGING_UPLOAD`,
  `STAGING_DOWNLOAD`), `GpuBuffer` (интерфейс: `sizeBytes`, `usage`, `mappedAddress`
  — только для `STAGING_UPLOAD`, иначе `UnsupportedOperationException`, `free`).
- `TextureDesc` (record + factory `texture2D` — проставляет `depth=1`), `TextureFormat`
  (enum: `R32F`, `RGBA8`, `RGBA16F`, `DEPTH32F`, `R32UI`), `GpuTexture` (интерфейс).
- `ShaderSource` (record: путь, raw-текст, `Map<String,String>` defines),
  `PipelineLayout` (record: `Map<String,Integer>` bindings по имени).
- `ComputePipeline` / `GraphicsPipeline` — интерфейсы: dispatch/draw (в т.ч. indirect
  варианты), `bindBuffer`/`bindTexture` по строковому имени биндинга, `free()`.
- `CommandList` — интерфейс: `memoryBarrier`, `uploadToBuffer`, `copyBuffer`,
  `clearBuffer`, `dispatchCompute[Indirect]`, `draw`. `BarrierScope` (enum:
  `SHADER_STORAGE`, `COMMAND`, `BUFFER_UPDATE`, `ALL`).
- `FenceHandle` — пустой marker-интерфейс (opaque handle).

Тесты: `ShaderSourceAndPipelineLayoutTest` (accessors + immutability `Map.of(...)`-карт),
`TextureDescTest` (`texture2D()` проставляет `depth=1`).

**Пункт 4 требований тикета 04** (добавить недостающий метод для persistent-kernel
traversal, если обнаружится нехватка) — сознательно НЕ применён в этой сессии: тикет
`21-gpu-persistent-traversal-shader-opt.md` ещё не выполнялся, нет спроектированной
реализации, которая обосновала бы конкретное дополнение контракта. Если нехватка
обнаружится при выполнении тикета 21-opt — контракт `RenderBackend`/`CommandList`
дополнится в рамках того тикета.

### ev-api — `dev.ev.api.metrics` (тикет 05)
Последний из пяти api-тикетов — модуль `ev-api` теперь полностью укомплектован
контрактами.
- `MetricsRegistry` — интерфейс, thread-safe по контракту (вызывается и с worker-,
  и с render-потока). `recordQueueDepth`, `recordCacheAccess`, `recordGpuPassDuration`,
  `recordCounter`, `recordImportStageStatus(ImportStageStatus)`, `snapshot()`. Только
  контракт — реализация сознательно не пишется этим тикетом (появится либо отдельным
  будущим тикетом, либо встроится в 27/29).
- `MetricsSnapshot` — record: 4 карты (`queueDepths`, `cacheHitRates`,
  `gpuPassDurationsNanos`, `counters`) + `importStageStatus`. `empty()` factory.
- `ImportStageStatus` — record: staged breakdown холодного старта (`queuedForRead`,
  `retryingAfterFailure`, `activelyBuilding`, `completed`, `totalKnown`). `empty()`,
  `completionFraction()` (0.0 при `totalKnown=0`, не NaN). Мотивирован эмпирическим
  уроком из `Exceptional-Vision` (`/ev status` — плоское число глубины очереди менее
  полезно для диагностики застрявшего импорта, чем staged breakdown); тот же урок
  явно упомянут в MVP_INDEX.md для тикета 29.

Тесты: `MetricsSnapshotTest` (`empty()` — все карты non-null и пустые, вложенный
`importStageStatus` равен `ImportStageStatus.empty()`), `ImportStageStatusTest`
(`completionFraction()` — 0/0 не NaN, обычная дробь, `empty()`, полное завершение).
Оба явно требуются критериями приёмки 3 и 4 тикета.

## Межмодульные контракты, зафиксированные де-факто

- `dev.ev.api.SectionPos` (тикет 01) — стабильный контракт `ev-api`, на него будут
  опираться `ev-storage` (ключ кэша секций, тикет 08), `ev-meshing` (приоритет/адресация,
  тикет 14) и `ev-gpu` (кодирование позиции в SSBO, тикет 20). Сигнатура зафиксирована,
  менять между MVP/opt волнами нельзя (см. правило в MVP_INDEX.md).

## Известные архитектурные инварианты

- `SectionPos.encode()` bit layout (тикет 01): биты 60-63 = level (4 бита, 0..6),
  52-59 = y (8 бит signed), 26-51 = z (26 бит signed), 0-25 = x (26 бит signed).
  Диапазон x/z: ±33.5M секций (с запасом покрывает мировую границу ±30M блоков на
  level 0). Диапазон y: ±128 секций (при 32 блока/секция на level 0 — ±4096 блоков по
  высоте, достаточно с запасом для build-limit ванильного мира). Любой код, работающий
  с `long`-идентификатором секции напрямую (а не через `SectionPos.encode/decode`),
  обязан использовать именно этот layout — следующая точка ожидаемого использования:
  тикет 20 (layout node buffer на GPU).
- `WorldSectionHandle` local voxel coordinates (тикет 02): каждая секция — логическая
  сетка 32³ вне зависимости от LOD уровня; `localX/Y/Z` всегда в диапазоне [0,31].
  Мировой размер, который покрывает эта сетка на конкретном уровне, определяется
  `SectionPos.sizeInBlocks()` (32 << level), а не размером locals-диапазона — те не
  меняются между уровнями.
- `Quad` local coordinate range (тикет 03): в отличие от voxel-локальных координат
  ([0,31]), координаты `Quad.x/y/z` — voxel-**boundary** координаты, диапазон [0,32]
  включительно (quad лежит на границе вокселей, а не в их центре). Не путать эти два
  разных диапазона при передаче данных между `WorldSectionHandle`/`MeshingContext` и
  `Quad`.

## Чего пока не существует

- Из `ev-api` реализованы `SectionPos` (тикет 01), storage-контракты `VoxelStorage`,
  `DirtyFlags`, `WorldSectionHandle`, `StorageMetrics` (тикет 02, с известной проблемой
  сигнатуры `markDirty`, см. выше), meshing-контракты `MeshingContext`, `Quad`,
  `Meshlet`, `MeshletBatch`, `MeshBuilder` (тикет 03), весь GPU-абстракции слой
  (13 типов, тикет 04) и metrics-контракты `MetricsRegistry`/`MetricsSnapshot`/
  `ImportStageStatus` (тикет 05). **Модуль `ev-api` полностью укомплектован** (все 5
  api-тикетов выполнены) — дальше только реализации в `ev-storage`/`ev-meshing`/
  `ev-gpu`/`ev-render`, ни один новый интерфейс в `ev-api` больше не ожидается по
  плану (кроме потенциального дополнения `RenderBackend`/`CommandList` из тикета 21-opt,
  см. выше).
- Ни одной реализации хранилища, мешинга, GPU-бэкенда или рендер-оркестрации.
- В `ev-test` (модуль для интеграционных/кросс-модульных тестов) по-прежнему ни одного
  тестового класса — `SectionPosTest` (тикет 01) лежит в `ev-api/src/test`, не в
  `ev-test`, так как тикет 01 самодостаточен и не требует зависимостей других модулей.
- `neoforge.mods.toml` существует только как шаблон в `src/main/templates`
  (разворачивается таском `generateModMetadata` в `build/generated/...`), не как
  статичный файл в `resources`.
- Конфиг мода, debug-команды (`/ev ...`), FakeRenderBackend — не существуют.

## Сборка/тесты — ограничения для AI-сессий

- Песочница агента, выполнявшего тикет 00, разрешает сетевой доступ только к
  ограниченному списку доменов. `services.gradle.org` (дистрибутив Gradle) и
  `maven.neoforged.net` (репозиторий NeoForge/ModDevGradle) НЕ входят в этот список —
  `./gradlew build` в такой песочнице падает на сетевом уровне (403 при скачивании
  дистрибутива Gradle), до стадии компиляции дело не доходит.
- `gradle-wrapper.jar` пришлось восстанавливать вручную (отсутствовал в исходном
  репозитории) — если он снова отсутствует в новой сессии, взять с GitHub
  (`raw.githubusercontent.com` обычно разрешён) или сгенерировать через `gradle wrapper`
  на машине с обычным доступом.
- Критерии приёмки тикета 00 (`./gradlew build` успешен, `ev-neoforge` производит jar,
  `ev-api`/`ev-storage`/`ev-meshing` не тянут NeoForge/LWJGL транзитивно) подтверждены
  только по структуре конфигурации, НЕ фактическим прогоном — требуется первый реальный
  прогон на машине с полным доступом в интернет.
- В песочнице этой сессии (тикет 01) дополнительно проверено: доступен только
  `openjdk-21-jre-headless` (команда `java`), но не JDK/`javac`. Попытка
  `apt-get install openjdk-21-jdk-headless` падает с 404 при скачивании пакета с
  `security.ubuntu.com` (не сетевая блокировка allowlist — `archive.ubuntu.com` отвечает,
  но конкретный пакет для этой связки репозиториев недоступен). Значит компиляция/тесты
  для любого будущего `ev-api`/`ev-storage`/... тикета невозможны в этой песочнице ни
  через Gradle, ни напрямую через `javac` — верификация каждого тикета откладывается до
  сессии с полным доступом в интернет или предустановленным JDK.
