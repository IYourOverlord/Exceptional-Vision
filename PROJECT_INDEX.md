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
| 00-project-skeleton | DONE | Multi-module Gradle-структура в корне репозитория (заменила старый одномодульный проект Exceptional Vision — старые `src/`, `build.gradle`, `settings.gradle` удалены). Версии NeoForge/parchment взяты из `gradle.properties` старого проекта перед удалением (см. PROGRESS.md — изначальный web-search дал неверную версию, было исправлено). **`./gradlew build` верифицирован реальным прогоном пользователя (2026-08-16): `BUILD SUCCESSFUL`, все модули собираются, `ev-neoforge` производит jar.** Потребовало 5 багфиксов конфигурации/кода поверх исходной версии тикета — полный список симптом→причина→фикс см. в секции "Сборка/тесты" ниже, детальный разбор каждого — в PROGRESS.md. |
| 32-project-index | DONE | Этот файл. Выполнен вторым, после 00 — ретроактивных пробелов нет, весь существующий код на момент выполнения отражён ниже. |
| 01-api-sectionpos | DONE | `SectionPos` record реализован 1-в-1 по контракту тикета. **Тесты реально прогнаны (2026-08-16)**: `SectionPosTest` изначально падал на `encodeDecodeRoundTrip()` — баг sign-extension в `decode()`, исправлен (Багфикс 5, см. секцию "Сборка/тесты" ниже и PROGRESS.md); после фикса все 37 тестов проходят. |
| 02-api-storage-interfaces | DONE | `VoxelStorage`/`DirtyFlags`/`WorldSectionHandle`/`StorageMetrics` созданы. **Известная проблема**: `markDirty(handle, DirtyFlags flags)` берёт non-instantiable тип как параметр — см. "Известные архитектурные инварианты" ниже и PROGRESS.md. |
| 03-api-meshing-interfaces | DONE | `MeshingContext`, `Quad`, `Meshlet`, `MeshletBatch`, `MeshBuilder` созданы дословно по контракту. |
| 04-api-renderbackend-interfaces | DONE | 13 типов в `dev.ev.api.gpu` созданы дословно по контракту (по одному публичному типу на файл). Никаких LWJGL/NeoForge импортов — проверено. Пункт 4 требований (доп. методы для persistent-traversal) сознательно не применён — см. PROGRESS.md. |
| 05-api-metrics-interfaces | DONE | `MetricsRegistry`, `MetricsSnapshot`, `ImportStageStatus` созданы дословно. Последний из пяти api-тикетов — весь `ev-api` теперь укомплектован контрактами (01-05). |
| 06-storage-palette-codec | DONE | `PaletteCodec` (+ `MortonCode`, `BitPackedArray`, `RunLengthCodec`) в `dev.ev.storage.codec`. Формат: `SINGLE_VALUE` (tag 0, 8 байт) для однородных секций, `PALETTE_RLE` (tag 1) — палитра (индекс 0 = значение 0/воздух, если оно присутствует) + Z-order (Morton) обход + RLE + плотная битовая упаковка run-значений. `PaletteCodecTest` (JUnit 5) написан. **Компиляция/тесты НЕ прогнаны реальным Gradle-билдом в этой сессии** (песочница без `javac`/Gradle-доступа — см. `EV_SESSION_PROMPT.md`, раздел про компиляцию); логика алгоритма (round-trip, Morton bit-interleaving, isUniform, обработка ошибок размера, компрессия однородного случая = 8 байт) отдельно верифицирована вручную вне репозитория идентичной копией кода через `java` (single-file launcher), все проверки прошли. Требует подтверждения `./gradlew :ev-storage:test` в следующей сессии.|
| 07-storage-schema-migration | DONE | `SchemaVersion` (CURRENT=MIN_SUPPORTED=1), `SchemaMigrator` (интерфейс шага), `SchemaMigrationChain` (`migrateToCurrent` + обобщённый `migrateTo(from,to,data)` для тестирования цепочки независимо от CURRENT), `UnsupportedSchemaException`, `RegionFileHeader` (record, 12-байтный заголовок региона: magic "HZR\0" + schemaVersion u16 + compressionCodec u8 + reserved u8 + sectionCount i32 — единственный источник истины для раскладки, задокументирован в Javadoc) — всё в `dev.ev.storage.schema`. Реальных миграторов версий пока нет (CURRENT=1, мигрировать не из чего) — цепочка проверена гипотетическими `TestV1ToV2Migrator`/`TestV2ToV3Migrator`, определёнными только в `SchemaMigrationChainTest`. `RegionFileHeaderTest` — round-trip/bad-magic/too-short/null/zero-sections. **Компиляция/тесты не прогнаны реальным Gradle-билдом** (та же причина, что и в тикете 06 — нет `javac` в песочнице), логика отдельно верифицирована вручную вне репозитория идентичной копией кода через `java` single-file launcher, все 10 проверок прошли. Требует подтверждения `./gradlew :ev-storage:test`.|
| 08-storage-section-cache (mvp/opt) | DONE (mvp) | **SectionCache — MVP (нешардированная) версия активна, opt-версия (шардированная) в банке, не применена.** Реализовано в `dev.ev.storage.cache`: `SectionLoader` (интерфейс), `EvictionPolicy` (интерфейс, идентичен opt-контракту), `LruEvictionPolicy` (LinkedHashMap accessOrder=true, O(1) onInsert/onAccess, не вытесняет refCount>0), `SectionCache` (единый `ConcurrentHashMap<Long,WorldSectionHandle>` + один `ReentrantLock` только вокруг вызовов EvictionPolicy; `shardCountPowerOfTwo` в конструкторе принимается, но игнорируется — задокументировано в Javadoc и на самом параметре, ради сигнатурной совместимости с будущим 08-opt). `acquire` не делает допущений о вызывающем потоке (см. Javadoc, со ссылкой на эмпирический урок Exceptional Vision про render-thread reload). Тесты: `SectionCacheTest` (6 сценариев из тикета, включая конкурентный с 16 потоками/64 позициями/200 итераций через ExecutorService+CountDownLatch) + фейки `FakeWorldSectionHandle`/`FakeSectionLoader`/`NoopMetricsRegistry`. **Компиляция/тесты не прогнаны реальным Gradle-билдом** (по-прежнему нет `javac` в песочнице) — вся логика, включая конкурентный сценарий и LRU-пропуск held-записей, отдельно верифицирована вручную вне репозитория через `java` single-file launcher, все 9 проверок прошли. Требует подтверждения `./gradlew :ev-storage:test`.|
| 09-storage-heightmap-coarse-gen | DONE | `HeightmapSource` (интерфейс), `CoarseSectionGenerator` в `dev.ev.storage.coarsegen`. Один сэмпл heightmap на voxel-column (32×32 на секцию, `O(1)`/воксель, не зависит от LOD уровня) в центре покрываемого квадрата. Материал берётся из той же центральной точки (упрощённый вариант, разрешённый текстом тикета — не honest area-majority, задокументировано в Javadoc класса). `isAvailable()==false` → колонка не трогается (оставлена как есть у target), а весь вызов помечается `GenerationResult.complete()==false` — выбранная политика вместо тихого дефолта в воздух, задокументирована в Javadoc `generate()`. Однородность проверяется `PaletteCodec.isUniform` на собранном `flat`-массиве до записи через `setVoxel`; опциональный `MetricsRegistry` в конструкторе инкрементирует `coarsegen.uniformSections` при однородном результате. Тесты: `CoarseSectionGeneratorTest` (9 сценариев, покрывают все 6 пунктов тикета, включая проверку числа вызовов `surfaceHeight`/`surfaceMaterial` == `32*32` на LOD 0 и LOD 6 одинаково — гарантия `O(1)`, не `O(area)`). **Компиляция/тесты не прогнаны реальным Gradle-билдом** (по-прежнему нет `javac` в песочнице) — идентичная копия алгоритма отдельно верифицирована вручную вне репозитория через `java` single-file launcher, 21 проверка, все прошли. Требует подтверждения `./gradlew :ev-storage:test`. **Обнаружено, не исправлено в рамках этого тикета**: у `ev-storage` по-прежнему нет собственного `build.gradle.kts` (см. "Чего пока не существует" ниже) — не блокирует написание кода тикета 09, но заблокирует реальную сборку модуля целиком, включая уже существующие тикеты 06-08.|
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
| ev-storage | `dev.ev.storage.codec`, `dev.ev.storage.schema`, `dev.ev.storage.cache`, `dev.ev.storage.coarsegen` | `PaletteCodec`+кодеки (06), `SchemaVersion`/`SchemaMigrationChain`/`RegionFileHeader` (07), `SectionCache`-MVP (08), `HeightmapSource`/`CoarseSectionGenerator` (09). **⚠️ Не соответствовало этому файлу до тикета 09** — таблица "Статус тикетов" отмечала 06-08 как DONE, но эта строка ошибочно оставалась "Пусто" (не обновлена в своё время); исправлено сейчас, заодно см. следующее примечание про `build.gradle.kts`. |
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

### ev-storage — `dev.ev.storage.coarsegen` (тикет 09)
- `HeightmapSource` — интерфейс, адаптер к внешнему источнику высоты/материала по
  колонке (worldX, worldZ): `surfaceHeight`, `surfaceMaterial`, `isAvailable`. Реализация
  под реальный Minecraft `Heightmap`/`ChunkAccess` — вне этого тикета, будет в
  `ev-neoforge`.
- `CoarseSectionGenerator(HeightmapSource[, MetricsRegistry])` — `generate(WorldSectionHandle)
  -> GenerationResult(complete, uniform)`. Один сэмпл heightmap в центре каждого из 32×32
  voxel-column квадратов секции (не по углу — без систематического смещения), `O(1)` на
  воксель, не зависит от `sizeInBlocks()`. Вертикальное заполнение: material от `localY=0`
  до `quantizedSurfaceLocalY` включительно, выше — воздух; клампится в `-1..32` как
  сентинелы "вся секция воздух"/"вся секция material". `isAvailable()==false` для сэмплируемой
  точки → колонка не перезаписывается (оставлена как есть у `target`), `GenerationResult
  .complete()` становится `false` для всего вызова — явный сигнал вызывающему коду для
  повторной генерации позже, вместо неотличимого от честного результата дефолта в воздух.
  Файлы: `ev-storage/src/main/java/dev/ev/storage/coarsegen/{HeightmapSource,
  CoarseSectionGenerator}.java`. Тесты: `CoarseSectionGeneratorTest`,
  `FakeHeightmapSource`, `FakeWorldSectionHandle` (в `ev-storage/src/test/.../coarsegen`).

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
  тикет 20 (layout node buffer на GPU). **Sign-extension предупреждение**: изначальная
  реализация `decode()` содержала баг — сдвиг вправо для 26-битных x/z полей считался
  относительно 64-битного `long` (`64-26=38`), но применялся к уже приведённому к `int`
  значению, где Java маскирует величину сдвига по модулю 32 (JLS §15.19); исправлено
  (см. "Сборка/тесты" ниже, багфикс 5). Любой будущий код с похожей битовой упаковкой
  (в первую очередь GPU node buffer, тикет 20, где формат данных концептуально похож)
  должен считать величину сдвига относительно типа операнда **в момент сдвига**, не
  относительно исходной ширины поля до приведения типов — и обязан иметь golden-тест на
  граничных значениях, как `SectionPosTest.ROUND_TRIP_CASES`.
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
- Реализация хранилища частично существует (`ev-storage`: тикеты 06-09, см. модульную карту
  и раздел "Ключевые классы" выше) — ни мешинга, ни GPU-бэкенда, ни рендер-оркестрации всё
  ещё нет.
- **⚠️ `ev-storage/build.gradle.kts` отсутствует** (обнаружено при работе над тикетом 09,
  не в его рамках — не создан ни в один из тикетов 06-09). `settings.gradle.kts` включает
  `ev-storage` как подпроект, и другие модули (`ev-meshing`, `ev-neoforge`, `ev-test`)
  уже ссылаются на него через `implementation(project(":ev-storage"))`/`api(...)`, но без
  собственного build-файла Gradle не сможет применить `java-library`/зависимости
  (`fastutil`, JUnit) к этому подпроекту — `./gradlew build` в текущем виде репозитория,
  скорее всего, упадёт на конфигурации `ev-storage`. Не исправлено самостоятельно (не
  предмет тикета 09), фиксируется здесь как известный пробел до отдельного решения.
- В `ev-test` (модуль для интеграционных/кросс-модульных тестов) по-прежнему ни одного
  тестового класса — `SectionPosTest` (тикет 01) лежит в `ev-api/src/test`, не в
  `ev-test`, так как тикет 01 самодостаточен и не требует зависимостей других модулей.
- `neoforge.mods.toml` существует только как шаблон в `src/main/templates`
  (разворачивается таском `generateModMetadata` в `build/generated/...`), не как
  статичный файл в `resources`.
- Конфиг мода, debug-команды (`/ev ...`), FakeRenderBackend — не существуют.

## Сборка/тесты — состояние и справочник по известным проблемам

**Текущий статус (обновлено 2026-08-16, первый реальный прогон на машине пользователя,
Windows, IntelliJ IDEA + Gradle из wrapper'а):** `./gradlew build` → **BUILD SUCCESSFUL**.
Все модули собираются, `ev-neoforge` производит jar, тесты `ev-api` проходят (37/37).
Путь до этого состояния занял 5 последовательных багфиксов поверх исходной версии
тикетов 00/01 — ни один не был очевиден заранее, каждый всплывал только на следующем
шаге реального прогона (песочница агента не имела доступа ни к Gradle, ни к JDK — см.
"Ограничения песочницы AI-сессий" ниже). Таблица ниже — быстрый lookup по симптому,
если сборка снова упадёт на что-то похожее (например, после отката изменений, на другой
машине, или после апгрейда версии Gradle/NeoForge/зависимости).

### Хронология багфиксов (симптом → причина → фикс)

| # | Симптом (ключевые слова ошибки) | Причина | Фикс | Файл(ы) |
|---|---|---|---|---|
| 1 | `Could not find method maven() for arguments [...] on repository container` в `settings.gradle` | Файл назывался `settings.gradle` (Groovy DSL), но содержал Kotlin DSL синтаксис (`maven("url")`) | Переименован в `settings.gradle.kts` (содержимое не менялось) | `settings.gradle` → `settings.gradle.kts` |
| 2 | `Unresolved reference` на `property(...)` внутри блока `plugins { id(...) version ... }` | Блок `plugins {}` в Kotlin DSL компилируется изолированно от остального скрипта — не видит ни `property()` в его обычном значении, ни локальные `val`, объявленные в том же файле (ни до, ни после блока) | Версия плагина `net.neoforged.moddev` вынесена из `build.gradle.kts` подмодуля в `settings.gradle.kts` → `pluginManagement { plugins { id(...) version providers.gradleProperty(...).get() } }`; в подмодуле остаётся `id("net.neoforged.moddev")` без версии | `settings.gradle.kts`, `ev-neoforge/build.gradle.kts` |
| 3 | `Could not get unknown property 'minecraft_version' for task ... of type ProcessResources` при создании `generateModMetadata` | Таск регистрируется лениво (`tasks.register<ProcessResources>`), материализуется ещё позже — при подключении к `neoForgeIdeSync` через `neoForge.ideSyncTask(...)`. В этот отложенный момент обычный `property(...)` не гарантированно резолвит properties проекта | Все обращения к `property(...)` внутри `generateModMetadata` заменены на `providers.gradleProperty(...).get()` — provider-based API, рассчитанный на надёжную работу в любой точке ленивой конфигурации | `ev-neoforge/build.gradle.kts` |
| 4 | `Could not resolve it.unimi.dsi:fastutil:{strictly 8.5.12}` | NeoForge (через транзитивную `minecraft-dependencies:1.21.1`) жёстко фиксирует `fastutil` на `8.5.12` через Gradle `strictly`-constraint; проект использовал `8.5.13` | `fastutil_version` в `gradle.properties` понижена `8.5.13` → `8.5.12` (единая точка, читается всеми модулями через `property("fastutil_version")`) | `gradle.properties` |
| 5 | `SectionPosTest > encodeDecodeRoundTrip() FAILED` | В `SectionPos.decode()` 26-битные поля x/z sign-extend'ились сдвигом `>> (64-26)=38` на уже приведённом к `int` значении. Java маскирует величину сдвига для `int` по модулю 32 (JLS §15.19) — `>>38` реально исполняется как `>>6`, что даёт неверный результат для отрицательных/граничных x, z | `decode()` переписан: x/z сначала явно изолируются в 32-битный контейнер (`(int) id` для x, `(int) (id >>> 26)` для z), затем sign-extend через `<< 6 >> 6`, где `6 = 32-26` — посчитано относительно правильной (32-битной) разрядности | `ev-api/src/main/java/dev/ev/api/SectionPos.java` |

Полный разбор каждого пункта (с точными сообщениями об ошибках, пошаговым объяснением
и, для №5, проверкой на всех граничных тест-кейсах) — в `PROGRESS.md`, ищи заголовки
"Багфикс 1"–"Багфикс 5".

### Уроки на будущее — куда смотреть в первую очередь

- **Ошибка внутри блока `plugins {}` любого `build.gradle.kts`** (`Unresolved reference`
  на `property`, `moddevgradleVersion` или любую другую локальную ссылку) → почти
  наверняка попытка использовать что-то, объявленное в теле того же файла. Блок
  `plugins {}` — statically analyzable, изолирован от остального скрипта. Версии
  плагинов для подмодулей задавать через `pluginManagement.plugins` в корневом
  `settings.gradle.kts`, не пытаться передавать их из `build.gradle.kts`.
- **`Could not get unknown property '...'` внутри лениво регистрируемого таска**
  (`tasks.register<T>(...) { ... }`, особенно если таск подключается позже через
  `ideSyncTask`/`dependsOn`/аналогичный механизм плагина) → заменить `property(...)` на
  `providers.gradleProperty(...).get()`. Общее правило: в любом lazy-контексте
  (`register`, `configureEach`, provider-цепочки) предпочитать provider-based API вместо
  eager `property()`/`project.property()`.
- **`Could not resolve X:{strictly VERSION}`** → это не "версия недоступна в
  репозитории", а жёсткий constraint от транзитивной зависимости (часто сам
  NeoForge/Minecraft). Искать, кто требует `strictly` (в тексте ошибки указана цепочка
  `Required by: ...`), и синхронизировать версию в `gradle.properties`, а не пытаться
  форсировать свою версию через `resolutionStrategy` (это скроет проблему, а не решит
  её, и может привести к рантайм-несовместимости с реальным Minecraft classpath).
- **Round-trip/симметричные баги в битовой арифметике с `int`-сдвигами** → если формула
  сдвига вправо использует число, посчитанное относительно 64-битного `long` (например,
  `64 - N`), но применяется к значению, уже приведённому к `int` — это почти всегда баг.
  Java маскирует величину сдвига для `int` по модулю 32 (JLS §15.19) и для `long` по
  модулю 64 — величина сдвига должна считаться относительно **типа операнда в момент
  сдвига**, не относительно исходной ширины поля до приведения типов. Golden-тест на
  граничных значениях (как `SectionPosTest.ROUND_TRIP_CASES`) — обязателен для любого
  битового кодека, который здесь появится дальше (в первую очередь — layout node buffer
  на GPU, тикет 20, где эта же ошибка типа привела бы к трудноуловимому багу в рендере,
  а не к падающему тесту).
- **Первый реальный прогон почти никогда не проходит с первой попытки после серии
  AI-сессий без доступа к компилятору.** Это не повод считать код тикета плохо
  написанным — Gradle-конфигурация и битовая арифметика особенно чувствительны к
  нюансам, не видным при чтении кода без исполнения. Ожидай несколько раундов
  fix→rebuild, не одного.

### Ограничения песочницы AI-сессий (актуально при возобновлении работы без реального Gradle/JDK)

- Песочница агента, выполнявшего тикет 00, разрешает сетевой доступ только к
  ограниченному списку доменов. `services.gradle.org` (дистрибутив Gradle) и
  `maven.neoforged.net` (репозиторий NeoForge/ModDevGradle) НЕ входят в этот список —
  `./gradlew build` в такой песочнице падает на сетевом уровне (403 при скачивании
  дистрибутива Gradle), до стадии компиляции дело не доходит.
- `gradle-wrapper.jar` пришлось восстанавливать вручную (отсутствовал в исходном
  репозитории) — если он снова отсутствует в новой сессии, взять с GitHub
  (`raw.githubusercontent.com` обычно разрешён) или сгенерировать через `gradle wrapper`
  на машине с обычным доступом.
- В песочнице этой сессии (тикет 01) дополнительно проверено: доступен только
  `openjdk-21-jre-headless` (команда `java`), но не JDK/`javac`. Попытка
  `apt-get install openjdk-21-jdk-headless` падает с 404 при скачивании пакета с
  `security.ubuntu.com` (не сетевая блокировка allowlist — `archive.ubuntu.com` отвечает,
  но конкретный пакет для этой связки репозиториев недоступен). Значит компиляция/тесты
  для любого будущего `ev-api`/`ev-storage`/... тикета невозможны в этой песочнице ни
  через Gradle, ни напрямую через `javac` — верификация каждого тикета откладывается до
  сессии с полным доступом в интернет или предустановленным JDK, либо до передачи
  изменений пользователю для реального прогона (как произошло с багфиксами 1-5 выше).
- **Практический вывод**: при работе в песочнице без Gradle/JDK — не считать код
  автоматически рабочим только потому, что он "выглядит правильно" и следует контракту
  тикета дословно. Явно помечать каждый тикет как "не подтверждено фактическим
  прогоном" (как это уже делается) и, что важнее, при следующей возможности реального
  прогона — ожидать и спокойно проходить несколько раундов fix→rebuild, а не
  воспринимать первую же ошибку как повод для паники или как признак того, что тикет
  был выполнен неверно в целом.
