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
| 06-storage-palette-codec | DONE | `PaletteCodec` (+ `MortonCode`, `BitPackedArray`, `RunLengthCodec`) в `dev.ev.storage.codec`. Формат: `SINGLE_VALUE` (tag 0, 8 байт) для однородных секций, `PALETTE_RLE` (tag 1) — палитра (индекс 0 = значение 0/воздух, если оно присутствует) + Z-order (Morton) обход + RLE + плотная битовая упаковка run-значений. **`PaletteCodecTest` реально прогнан реальным Gradle-билдом пользователя (2026-08-16): `:ev-storage:test` → `BUILD SUCCESSFUL`.**|
| 07-storage-schema-migration | DONE | `SchemaVersion` (CURRENT=MIN_SUPPORTED=1), `SchemaMigrator` (интерфейс шага), `SchemaMigrationChain` (`migrateToCurrent` + обобщённый `migrateTo(from,to,data)` для тестирования цепочки независимо от CURRENT), `UnsupportedSchemaException`, `RegionFileHeader` (record, 12-байтный заголовок региона: magic "HZR\0" + schemaVersion u16 + compressionCodec u8 + reserved u8 + sectionCount i32 — единственный источник истины для раскладки, задокументирован в Javadoc) — всё в `dev.ev.storage.schema`. Реальных миграторов версий пока нет (CURRENT=1, мигрировать не из чего) — цепочка проверена гипотетическими `TestV1ToV2Migrator`/`TestV2ToV3Migrator`, определёнными только в `SchemaMigrationChainTest`. `RegionFileHeaderTest` — round-trip/bad-magic/too-short/null/zero-sections. **Реально прогнано реальным Gradle-билдом пользователя (2026-08-16): `:ev-storage:test` → `BUILD SUCCESSFUL`.**|
| 08-storage-section-cache (mvp/opt) | DONE (mvp) | **SectionCache — MVP (нешардированная) версия активна, opt-версия (шардированная) в банке, не применена.** Реализовано в `dev.ev.storage.cache`: `SectionLoader` (интерфейс), `EvictionPolicy` (интерфейс, идентичен opt-контракту), `LruEvictionPolicy` (LinkedHashMap accessOrder=true, O(1) onInsert/onAccess, не вытесняет refCount>0), `SectionCache` (единый `ConcurrentHashMap<Long,WorldSectionHandle>` + один `ReentrantLock` только вокруг вызовов EvictionPolicy; `shardCountPowerOfTwo` в конструкторе принимается, но игнорируется — задокументировано в Javadoc и на самом параметре, ради сигнатурной совместимости с будущим 08-opt). `acquire` не делает допущений о вызывающем потоке (см. Javadoc, со ссылкой на эмпирический урок Exceptional Vision про render-thread reload). Тесты: `SectionCacheTest` (6 сценариев из тикета, включая конкурентный с 16 потоками/64 позициями/200 итераций через ExecutorService+CountDownLatch) + фейки `FakeWorldSectionHandle`/`FakeSectionLoader`/`NoopMetricsRegistry`. **Реально прогнано реальным Gradle-билдом пользователя (2026-08-16), включая конкурентный сценарий: `:ev-storage:test` → `BUILD SUCCESSFUL`.**|
| 09-storage-heightmap-coarse-gen | DONE | `HeightmapSource` (интерфейс), `CoarseSectionGenerator` в `dev.ev.storage.coarsegen`. Один сэмпл heightmap на voxel-column (32×32 на секцию, `O(1)`/воксель, не зависит от LOD уровня) в центре покрываемого квадрата. Материал берётся из той же центральной точки (упрощённый вариант, разрешённый текстом тикета — не honest area-majority, задокументировано в Javadoc класса). `isAvailable()==false` → колонка не трогается (оставлена как есть у target), а весь вызов помечается `GenerationResult.complete()==false` — выбранная политика вместо тихого дефолта в воздух, задокументирована в Javadoc `generate()`. Однородность проверяется `PaletteCodec.isUniform` на собранном `flat`-массиве до записи через `setVoxel`; опциональный `MetricsRegistry` в конструкторе инкрементирует `coarsegen.uniformSections` при однородном результате. Тесты: `CoarseSectionGeneratorTest` (9 сценариев, покрывают все 6 пунктов тикета, включая проверку числа вызовов `surfaceHeight`/`surfaceMaterial` == `32*32` на LOD 0 и LOD 6 одинаково — гарантия `O(1)`, не `O(area)`). **Реально прогнано реальным Gradle-билдом пользователя (2026-08-16), после фикса `ev-storage/build.gradle.kts`: `:ev-storage:test` → `BUILD SUCCESSFUL`.**|
| 10-meshing-occupancy-stage | DONE | `OccupancySet` (bitset `long[512]` над 32³ гридом, `flatIndex = x+y*32+z*32*32`, тот же порядок осей, что и `PaletteCodec`/`WorldSectionHandle`), `OccupancyStage.process(WorldSectionHandle) -> OccupancySet` — первая стадия конвейера мешинга в `dev.ev.meshing.stage`. Fast-path через `WorldSectionHandle.isEmpty()` — не обходит 32768 вокселей для пустой секции. Тесты: `OccupancySetTest` (get/set по углам и произвольным координатам, isEmpty, popCount, снятие бита), `OccupancyStageTest` (fast-path проверен подсчётом вызовов `getVoxel`, sparse-секция, полностью заполненная секция). **Компиляция/тесты не прогнаны реальным Gradle-билдом в этой сессии** — логика тривиальна (прямые битовые операции над `long[]`), критическая часть (индексация угловых координат, popCount, снятие бита) точечно проверена вручную вне репозитория через `java` single-file launcher, без полного дублирования класса — не требовалось ввиду простоты. Требует подтверждения `./gradlew :ev-meshing:test`.|
| 11-meshing-greedy-mesh-stage | DONE | `GreedyMeshStage.process(WorldSectionHandle, OccupancySet, MeshingContext) -> List<Quad>` — вторая стадия конвейера мешинга, `dev.ev.meshing.stage`. Единая параметризованная по оси реализация на все 6 направлений (не 6 копий кода): таблицы `NORMAL_AXIS`/`SIGN`/`WIDTH_AXIS`/`HEIGHT_AXIS` кодируют циклическую конвенцию width/height из требования 4a тикета (X→Y→Z→X). Face-видимость и материал берутся с вокселя-источника (не соседа), per требование 3. Fast-path на `occupancy.isEmpty()`. **Задокументирована (в Javadoc класса) конвенция для `MeshingContext.getNeighborBoundaryVoxel(faceDirection, a, b)`, которую сам текст тикетов 03/11 не фиксирует однозначно**: `a` = локальная координата по width-оси, `b` — по height-оси (та же циклическая конвенция) — см. "Межмодульные контракты" ниже, это будет важно для реализации `MeshingContext` в `ev-render`. Тесты: `GreedyMeshStageTest` (9 сценариев: fast-path с подсчётом вызовов контекста, одиночный воксель → 6 quad'ов 1×1, сплошной блок 4×4×4 → 6 quad'ов 4×4 вместо 384 граней, два соседних вокселя разного материала не сливаются, полный слой 32×32 → один quad на сторону, граница секции через `getNeighborBoundaryVoxel` скрывает грань, и 3 отдельных теста на конвенцию width/height для +X/+Y/+Z с заведомо неквадратными областями). `FakeMeshingContext` — новая тестовая заглушка (`ev-meshing/src/test/.../stage`), по умолчанию воздух за границей и identity-материал, со счётчиками вызовов и переопределяемым `BoundaryLookup`. **Компиляция/тесты не прогнаны реальным Gradle-билдом в этой сессии** (см. ограничения песочницы ниже) — требует подтверждения `./gradlew :ev-meshing:test`.|
| 12-meshing-material-bin-stage | DONE | `MaterialBin(int materialId, List<Quad> quads)` (record) + `MaterialBinStage.process(List<Quad>) -> List<MaterialBin>` — третья стадия конвейера мешинга, `dev.ev.meshing.stage`. `groupBy(Quad::materialId)` через `LinkedHashMap` (стабильный порядок quad'ов внутри бина), бины на выходе отсортированы по `materialId` возрастанию. Сложность `O(n)` группировка + `O(k log k)` сортировка бинов (k = число уникальных материалов), как предпочтено требованием 3 тикета. Пустой вход → пустой список (не список из одного пустого бина). Тесты: `MaterialBinStageTest` (4 сценария из тикета: пустой вход, один материал — порядок сохранён, вперемешку 3 материала 5/2/5/8/2 → бины отсортированы 2/5/8 с сохранением относительного порядка внутри каждого, один quad → один бин). **Компиляция/тесты не прогнаны реальным Gradle-билдом в этой сессии** — требует подтверждения `./gradlew :ev-meshing:test`.|
| 13-meshing-meshlet-pack-stage | DONE | `MeshletPackStage.process(SectionPos, List<MaterialBin>) -> MeshletBatch` — финальная (четвёртая) стадия конвейера мешинга, `dev.ev.meshing.stage`. Разбивает каждый `MaterialBin` на чанки по `MAX_QUADS_PER_MESHLET=128` (последний — остаток), порядок бинов и порядок quad'ов внутри бина сохранён. `quadWorldBounds(Quad) -> float[6]` — единственное место, где считается bounding box quad'а; **явно использует ту же таблицу нормаль/width/height, что и `GreedyMeshStage` (тикет 11, требование 4a)** — не переизобретает конвенцию, Javadoc содержит прямую копию таблицы с явной пометкой источника. Пустой список бинов → `MeshletBatch` с пустым списком meshlet'ов, не исключение. Тесты: `MeshletPackStageTest` (6 сценариев: пустой вход, один бин под лимитом → 1 meshlet, бин `128*2+10` quad'ов → ровно 3 meshlet'а с сохранённым порядком, bounding box одиночного quad'а для +X/+Y/+Z с вручную посчитанными диапазонами, bounding box для нескольких разбросанных quad'ов — union по всем, не только первый/последний, `totalQuadCount()` равен сумме размеров бинов). **Компиляция/тесты не прогнаны реальным Gradle-билдом в этой сессии** — требует подтверждения `./gradlew :ev-meshing:test`.|
| 14-meshing-priority-function | DONE | `dev.ev.meshing.priority`: `ScreenSpaceErrorMetric` (`computeProjectionScale`, `projectedErrorPx`, `selectLodLevel` — линейный перебор 7 уровней 6→0, `voxelSizeAtLevel=1<<level` без лишних аллокаций) и `MeshPriority` (`compute(int,...)`, `compute(SectionPos,...)`, `computeWithNearTierCheck(...)`). Битовая упаковка нормального тира: биты 60-62 lodWeight (0-7, масштабировано от `lodLevel/maxLodLevel`), 58-59 attemptWeight (capped на 3), 57 facingBonus, 0-56 insertionSeq — бит 63 всегда чист. **Двухъярусный near-player приоритет** реализован по эмпирическому уроку из текста тикета: near-tier результат = `Long.MIN_VALUE + offset` (offset = 20 бит квантованной squared-distance + 12 бит insertionSeq, максимум `0xFFFFFFFF` — арифметически безопасно относительно `Long.MIN_VALUE`), гарантированно отрицателен и потому всегда меньше (=приоритетнее) любого нормального результата под стандартным `Long`-сравнением; радиус проверяется в level-0 section-grid единицах (Chebyshev) независимо от LOD-уровня самой секции, как того требует тикет. Тесты: `ScreenSpaceErrorMetricTest` (6 сценариев) и `MeshPriorityTest` (9 сценариев, включая white-box проверку инварианта бита 63 на случайных данных с фиксированным seed и прямую проверку арифметической безопасности near-tier offset'а). **Компиляция/тесты не прогнаны реальным Gradle-билдом в этой сессии** — требует подтверждения `./gradlew :ev-meshing:test`.|
| 15-meshing-priority-queue (mvp/opt) | MVP DONE, opt not applied | `MeshTaskQueue<T>` (`dev.ev.meshing.queue`) — MVP (PriorityBlockingQueue) version active, opt-версия (Chase-Lev work-stealing, `15-meshing-work-stealing-queue-opt.md`) в банке, не применена. Single shared `PriorityBlockingQueue<Entry<T>>`, no per-worker sharding. `submit(priority, task)`/`poll()` (blocking, via `take()`)/`pollNonBlocking()` (via JDK's own non-blocking `poll()`)/`size()`. Queue-depth metric (`MetricsRegistry.recordQueueDepth("mesh-task-queue", size())`) reported periodically (every 64 ops via an `AtomicInteger` counter), not on every call, per requirement 4. Near-tier priorities (negative `long`, sign bit set, per ticket 14) naturally outrank normal-tier (non-negative `long`) via plain `Long.compare` in `Entry.compareTo` — no special-casing needed in this MVP, unlike the opt version's bucket-index XOR extraction. Тесты: `MeshTaskQueueTest` (6 сценариев: ordering by priority, pollNonBlocking on empty returns null, size tracking, concurrent multi-producer/multi-consumer smoke test with no lost elements, equal-priority tasks both retrievable, near-tier vs normal-tier ordering invariant). **Компиляция/тесты не прогнаны реальным Gradle-билдом в этой сессии** — требует подтверждения `./gradlew :ev-meshing:test`.|
| 16-meshing-mipgen (mvp/opt) | MVP DONE, opt not applied | `MipAggregator` (`dev.ev.meshing.mip`) — MVP (скалярная) версия активна, SIMD opt-версия (`16-meshing-simd-mipgen-opt.md`) в банке, не применена. `aggregateMajorityVote(int[] childPalette, int[] parentPalette, int parentCount)` — для каждого из `parentCount` родителей считает majority-vote среди 8 детей прямым попарным подсчётом O(8*8)=O(64) без аллокации `HashMap` на вызов. Tie-break при равенстве count: побеждает значение с наименьшим индексом позиции среди 8 детей (первое из значений, достигших максимального count при проходе позиций 0..7 по порядку) — задокументировано как обязательное для согласованности с будущей `16-opt`. `parentCount = 0` — no-op, не бросает исключение, не пишет в `parentPalette`. Тесты: `MipAggregatorTest` (5 сценариев: все 8 детей одинаковы, явное большинство 5/8, все 8 разных значений — проверка tie-break правила, 1000 родителей со случайными (fixed seed) детьми — сверка с отдельной reference-реализацией внутри теста, `parentCount=0` не трогает `parentPalette`). **Компиляция/тесты не прогнаны реальным Gradle-билдом в этой сессии** — требует подтверждения `./gradlew :ev-meshing:test`.|
| 17-gpu-backend-gl-buffers | DONE (partial — createBuffer/createTexture only) | `GLRenderBackend implements RenderBackend` (`dev.ev.gpu.gl`) — единственный модуль во всём проекте, импортирующий `org.lwjgl.*`. `createBuffer`/`createTexture` полностью реализованы через DSA-стиль вызовы (GL 4.5+): `glCreateBuffers`+`glNamedBufferStorage` (STATIC_DRAW/DYNAMIC_DRAW/STORAGE → `GL_DYNAMIC_STORAGE_BIT`; STAGING_UPLOAD → `GL_MAP_WRITE_BIT|GL_MAP_PERSISTENT_BIT|GL_MAP_COHERENT_BIT` + `glMapNamedBufferRange`+`MemoryUtil.memAddress(...)` для получения `long`-адреса; STAGING_DOWNLOAD аналогично с `GL_MAP_READ_BIT`), `glCreateTextures`+`glTextureStorage2D`/`3D` (3D если `depth()>1`) с маппингом `TextureFormat→GL internal format`, вынесенным в отдельный package-private `GLTextureFormats.toGlInternalFormat` (тестируем без GL-контекста, исчерпывающий `switch` без default — бросает на будущих неучтённых значениях enum). Остальные 7 методов интерфейса (`compilePipeline`, `compileGraphicsPipeline`, `submit`, `insertFence`, `isSignaled`, `waitForFence`, `shutdown`) — явные `UnsupportedOperationException`-заглушки с комментарием, какой тикет реализует. `GLBuffer`/`GLTexture` (`dev.ev.gpu.gl`) — обёртки над raw GL handle; `GLBuffer.mappedAddress()` бросает `UnsupportedOperationException` если `usage() != STAGING_UPLOAD`; `free()` на обоих — идемпотентен (флаг `freed`), для замапленных буферов явный `glUnmapNamedBuffer` перед `glDeleteBuffers` (defensive, не строго обязательно по спеке GL, но безопасно для драйверов). Никакого мутируемого `static`-состояния в `GLRenderBackend` (архитектурное исправление проблемы прототипа Voxy со статическим scratch-буфером). Верифицировано через веб-поиск (не по памяти): (1) LWJGL 3.3.3 предоставляет и `GL45`, и `GL46`, но macOS исторически ограничен OpenGL 4.1 — оставлен `GL45` для широкой совместимости, как тикет и предлагал по умолчанию; (2) точная сигнатура `GL45.glNamedBufferStorage(int, long, int)` и то, что `glMapNamedBufferRange` в LWJGL возвращает `ByteBuffer`, а не `long` напрямую — адрес получается через `MemoryUtil.memAddress(ByteBuffer)`, отдельного `nglMapNamedBufferRange`-метода с такой сигнатурой не существует в публичном API (первоначальный вариант кода это использовал ошибочно и был исправлен после поиска). Тесты: `GLTextureFormatsTest` (полнота маппинга по всем значениям `TextureFormat.values()`, отсутствие коллизий между разными форматами, точечная проверка каждого из 5 значений на конкретную GL-константу). **Тесты, требующие реального GL-контекста (создание/освобождение буфера), не добавлены** — headless GL недоступен в этой песочнице, тикет явно делает это опциональным и не блокирующим приёмку. **Компиляция подтверждена фактическим прогоном пользователем (2026-08-17, `./gradlew build` — `BUILD SUCCESSFUL`, включая `ev-gpu:compileJava`/`test`/`check`/`build`)**.|
| 18-gpu-backend-gl-shaders | DONE | `GLRenderBackend.compilePipeline`/`compileGraphicsPipeline` реализованы через новый package-private `ShaderCompiler` (`dev.ev.gpu.gl`): компиляция GLSL (`glCreateShader`+`glShaderSource`+`glCompileShader`, проверка `GL_COMPILE_STATUS`), линковка (`glCreateProgram`+`glAttachShader`+`glLinkProgram`, проверка `GL_LINK_STATUS`, `glDetachShader`+`glDeleteShader` после успешной линковки), информативные `RuntimeException` с `sourcePath()` и полным `glGetShaderInfoLog`/`glGetProgramInfoLog` при неудаче. `ShaderCompiler.injectDefines(rawSource, defines)` — чистый статический метод, вставляет `#define KEY VALUE` сразу после первой строки (`#version ...`), пустая карта defines → исходный текст не изменён; бросает `IllegalArgumentException`, если `rawSource` не начинается с `#version`. Новые классы `GLComputePipeline`/`GLGraphicsPipeline` (`dev.ev.gpu.gl`, package-private конструктор) оборачивают скомпилированный program handle + `PipelineLayout.bindingsByName()`; `bindBuffer`/`bindTexture` резолвят имя биндинга через map-lookup (`IllegalArgumentException` при опечатке в имени), биндинг буферов по умолчанию через `glBindBufferBase(GL_SHADER_STORAGE_BUFFER, ...)` (задокументированное допущение — проект compute/SSBO-heavy, `PipelineLayout` пока не различает SSBO/UBO). `dispatchIndirect` явно биндит `indirectBuffer` на `GL_DISPATCH_INDIRECT_BUFFER` перед `glDispatchComputeIndirect`, как требует тикет. `drawIndirect`/`drawIndirectCount` реализованы через `glMultiDrawArraysIndirect`/`ARBIndirectParameters.glMultiDrawArraysIndirectCountARB` (детали не были расписаны в тикете дословно, дописаны по интерфейсу `GraphicsPipeline` из тикета 04 — не выходит за рамки контракта; **исправлено пост-фактум**: изначально `drawIndirectCount` ошибочно использовал `GL45.glMultiDrawArraysIndirectCount`/`GL45.GL_PARAMETER_BUFFER`, которых не существует в core GL45 — это часть `ARB_indirect_parameters`, core только с GL 4.6; так как проект зафиксирован на GL45 ради macOS-совместимости, заменено на ARB-суффиксные `ARBIndirectParameters.glMultiDrawArraysIndirectCountARB`/`ARBIndirectParameters.GL_PARAMETER_BUFFER_ARB` из того же jar `lwjgl-opengl`, дополнительная Gradle-зависимость не потребовалась). `free()` на обоих — `glDeleteProgram`, идемпотентно (флаг `freed`). Тесты: `ShaderCompilerTest` — 6 тестов на `injectDefines` (пустые defines, один define, несколько defines с сохранением порядка, точная позиция вставки сразу после `#version`-строки, `IllegalArgumentException` при отсутствии `#version` с пустыми/непустыми defines). Тесты, требующие реального GL-контекста (сама компиляция/линковка шейдера), не добавлены — headless GL недоступен в этой песочнице, как и в тикете 17. **Компиляция подтверждена фактическим прогоном пользователем (2026-08-17, `./gradlew build` — `BUILD SUCCESSFUL`, включая `ev-gpu:compileJava`/`test`/`check`/`build`; это же подтверждение покрывает и багфикс `ARBIndirectParameters` из `drawIndirectCount`)**.|
| 19-gpu-upload-batching-opt | NOT_STARTED | |
| 20-gpu-node-buffer (mvp/opt) | DONE (mvp) | **Node buffer — MVP (AoS, единый буфер) версия активна, SoA opt-версия в банке, не применена.** `dev.ev.gpu.nodes.NodeBuffer` оборачивает один GPU-буфер `BufferUsage.STORAGE` размером `nodeCapacity * 32`. `ev-gpu/src/main/resources/shaders/include/node_buffer_aos.glsl` кодирует выровненную по `std430` структуру `Node` (32 байта/элемент). |
| 21-gpu-traversal (simple-mvp/persistent-opt) | DONE (mvp) | **Traversal — MVP (CPU-side linear frustum-only) версия активна; GPU persistent-kernel traversal (21-opt), Hi-Z occlusion (22-opt), temporal reprojection (25-opt) в банке, не применены.** `dev.ev.render.culling.FrustumTester` (sphere-vs-6-planes test) и `dev.ev.render.culling.SimpleTraversal` (линейный O(N) проход по loadedSections с замером wall-clock времени через `MetricsRegistry.recordGpuPassDuration("cpu-traversal", nanos)`). |
| 22-gpu-hiz-occlusion-opt | NOT_STARTED | |
| 23-gpu-indirect-multidraw-opt | NOT_STARTED | |
| 24-render-frame-graph | DONE | `dev.ev.render.framegraph.FrameGraphBuilder` — декларативный билдер графа GPU-проходов кадра. Использование: single-use per frame instance. Проводит топологическую сортировку (алгоритм Кана) по ресурсам `FrameResource`, проверяет циклы (`IllegalStateException`), вставляет консервативный memory barrier (`BarrierScope.ALL`) непосредственно перед проходом `P`, если какой-либо из предшествующих проходов `Q` писал в ресурс, читаемый или пишимый `P`. Исполняет все команды через единственный `RenderBackend.submit(CommandList)` за кадр и записывает длительность каждого прохода в `MetricsRegistry`. |
| 25-render-temporal-reprojection-opt | NOT_STARTED | |
| 26-render-dirty (tracking-mvp/subregion-opt) | DONE (mvp) | **Dirty tracking — MVP (whole-section) версия активна, sub-region opt-версия в банке, не применена.** `dev.ev.render.dirty.DirtySectionTracker` (отслеживание изменений на уровне всей секции целиком через `LongOpenHashSet` закодированных `SectionPos`) и `dev.ev.render.dirty.GeometryChangeDeduplicator` (дедупликация по CRC32-хэшу `Quad`-списка секции, предотвращающая лишние выгрузки на GPU/записи на диск при неизменившейся геометрии). |
| 27-neoforge-mod-entrypoint | DONE | `dev.ev.neoforge.EV` и `dev.ev.neoforge.EVInstance`. Полная интеграция с NeoForge 1.21.1: жизненный цикл мира (`LevelEvent.Load`/`Unload` с `instanceof ClientLevel` проверкой), рендер-этап (`RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS`), client-only регистрация (`FMLEnvironment.dist == Dist.CLIENT`), гарантированное восстановление OpenGL состояния (`glUseProgram(0)`, `glBindBuffer(GL_ARRAY_BUFFER, 0)`, `glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0)`, `glDepthMask(true)`, `glEnable(GL_DEPTH_TEST)`), расчёт nearCutoffBlocks с учётом `Math.sqrt(2.0)` для покрытия углов квадратной зоны загрузки ваниллы. |
| 28-neoforge-config | DONE | `dev.ev.neoforge.config.EVConfig` (immutable record, диапазоны валидации/clamping в `validated()`) и `dev.ev.neoforge.config.EVConfigLoader` (интеграция с NeoForge `ModConfigSpec` / `ModContainer.registerConfig(ModConfig.Type.CLIENT)`). Настройки: `maxRenderDistanceBlocks` (4096), `vramBudgetBytes` (0=auto), `screenSpaceErrorThresholdPx` (1.5), `coherenceMaxPositionalDeltaBlocks`/`coherenceMaxAngularDeltaRadians` (зарезервированы под Волна-2 opt), `workerThreadCount` (availableProcessors/2), `enableDebugOverlay` (false). |
| 29-neoforge-commands | DONE | `dev.ev.neoforge.command.EVCommands` (Brigadier client commands via `RegisterClientCommandsEvent`) и `MetricsSnapshotFormatter` (чистый форматтер, тестируемый без NeoForge runtime). Подкоманды: `/ev debug` (one-shot MetricsSnapshot в чат), `/ev debug watch` (toggle debug overlay flag на EVInstance, визуал — TODO), `/ev profile <passName>` (timing для named pass, MVP: только cpu-traversal), `/ev reload-config` (hot-reload EVConfig из TOML с указанием какие параметры safe/unsafe). `EVInstance` расширен: `metrics()` getter, `isDebugOverlayEnabled()`/`setDebugOverlayEnabled()` (volatile boolean на instance, без static state). `MetricsSnapshotFormatterTest` — 6 сценариев из тикета. **BUILD SUCCESSFUL, все тесты проходят.** |
| 30-test-fake-render-backend | DONE | `FakeRenderBackend` (`dev.ev.test.gpu`, `ev-test/src/main/java` — placed in `main`, not `test`, so other modules can depend on `ev-test` as an ordinary test-dependency library) — fully in-memory `RenderBackend`. `FakeGpuBuffer` backed by `byte[]`; `STAGING_UPLOAD.mappedAddress()` returns a real off-heap pointer via `MemoryUtil.nmemAlloc` (chosen over throwing `UnsupportedOperationException`, per ticket's explicit callout that `StagingUploadRing` (ticket 19) may need a working `mappedAddress()` — this pointer is **not** synchronized with the `byte[]` used by `readBufferContents`/`writeBufferContents`, documented on `FakeGpuBuffer`). `FakeCommandList.newCommandList()` — test-only addition on `FakeRenderBackend` (ticket 04's `RenderBackend` contract has no method to construct a `CommandList`); `uploadToBuffer`/`copyBuffer`/`clearBuffer` really move bytes when `submit()`'d, `dispatchCompute`/`dispatchComputeIndirect`/`draw`/`memoryBarrier` only append to `recordedOperationLog()`. **Fence semantics — always immediately signaled** (synchronous execution model, no deferred GPU work to wait for): documented prominently on both `FakeRenderBackend` and `FakeFenceHandle` class Javadoc, with an explicit cross-reference to ticket 19's warning not to use this fake for fence-backpressure logic (same reasoning as ticket 19 itself already states). `activeBuffers()`/`activeTextures()` track live (non-`free()`'d) handles for leak-detection asserts. No mutable static state — fully instance-scoped. `ev-test/build.gradle.kts` updated: dependency on `ev-api` changed `implementation`→`api` (fake's public methods return `dev.ev.api.gpu.*` types, must be visible transitively to consumers of `ev-test`), added `implementation("org.lwjgl:lwjgl")` + OS-detected `runtimeOnly(...:natives-*)` (real runtime dependency now, not just `testImplementation` — `MemoryUtil.nmemAlloc` executes in `main` source set code, needs LWJGL's native library on the runtime classpath, not just Java classes; no GL/display natives needed, this is pure native-heap bookkeeping). Tests: `FakeRenderBackendTest` — all 8 scenarios from the ticket's unit-test list (buffer size/usage accessors, write→read round-trip, `free()` removes from `activeBuffers()`, `submit`+`uploadToBuffer` real byte copy via native-address write, `submit`+`copyBuffer` honoring src/dst offsets, `dispatchCompute` ordering in `recordedOperationLog()`, fence always-signaled contract, `shutdown()` idempotent/safe on double call). **Compilation/tests not run by a real Gradle/JDK build in this session** — sandbox has no `javac` (same `security.ubuntu.com` 404 on `openjdk-21-jdk-headless` as prior sessions, re-verified this session), only a JRE. Code reviewed manually line-by-line for type/signature correctness against the actual `ev-api/src/main/java/dev/ev/api/gpu/*.java` sources (not from memory) instead. Requires confirmation via `./gradlew :ev-test:test` — in particular the LWJGL natives classifier logic in `build.gradle.kts` (untested assumption, follows the common LWJGL-Gradle pattern but the exact classifier string per OS/arch combination has not been verified against a real resolve in this sandbox).|
| 31-integration-checklist (mvp) | DONE (mvp) — full ещё не начат | `GLRenderBackend.submit`/`insertFence`/`isSignaled`/`waitForFence`/`shutdown` (`dev.ev.gpu.gl`) реализованы: `insertFence` через `GL32.glFenceSync`, оборачивает native handle в record `GLFenceHandle` (`dev.ev.gpu.gl`, обёртка над `long syncHandle`, implements `FenceHandle`) — этот класс отсутствовал в репозитории при использовании (компилировался `new GLFenceHandle(...)`/`instanceof GLFenceHandle`, но файла не было — `ev-gpu:compileJava FAILED`, "cannot find symbol") и был добавлен как файл `GLFenceHandle.java` в этой сессии (багфикс 11), без изменения остального кода `GLRenderBackend`. `isSignaled`/`waitForFence` — `glClientWaitSync` (non-blocking / с таймаутом + `GL_SYNC_FLUSH_COMMANDS_BIT`). `submit` — no-op (`GLCommandList` в этой MVP-версии исполняет операции немедленно при записи, не при `submit`). `shutdown` — идемпотентно освобождает fence sync объекты через `glDeleteSync`. Отдельно обнаружен и задокументирован уже реализованный (не этой сессией — существовавший, но не описанный в этом файле) worker thread pool `dev.ev.render.scheduling.MeshWorkerPool` в `ev-render` — см. запись `ev-render` в "Модульной карте" ниже. В этой сессии дополнительно исправлены две сборочные ошибки, не связанные с GL-кодом напрямую, но блокировавшие полный `./gradlew build` всего интеграционного набора: отсутствовавшая зависимость `org.slf4j:slf4j-api` в `ev-render/build.gradle.kts` (багфикс 12) и отсутствовавший блок `neoForge { unitTest { enable() } }` в `ev-neoforge/build.gradle.kts`, из-за которого `EVInstanceTest` падал `NoClassDefFoundError` на классах Minecraft (багфикс 13). **Подтверждено реальным прогоном пользователя (2026-08-19, `./gradlew build` — `BUILD SUCCESSFUL`, все модули включая `ev-neoforge:test`)** — весь набор MVP-тикетов Волны 1 теперь собирается и проходит тесты одним билдом. `31-integration-checklist-full.md` (opt-переинтеграция после Волны 2) — не начат, к нему переходить рано: сама игра (`runClient`) ещё ни разу не запускалась и не тестировалась интерактивно, что является явным предусловием `P0-profiling-checkpoint.md` по тексту самого этого тикета — см. раздел "Что дальше" в конце этого файла. |
| P0-profiling-checkpoint | NOT_STARTED | |
| far-lod-pass draw (session, no ticket) | DONE | `far-lod-pass`'s `record()` (`EVInstance.renderFarLod`) больше не только пишет метрики — теперь реально рисует. Новый `dev.ev.neoforge.FarLodPassRenderer` (`ev-neoforge`): компилирует vertex+fragment GLSL пару (vertex pulling, без VAO/VBO — вершины куба генерируются процедурно в vertex shader из `gl_VertexID`/`gl_InstanceID`), заливает per-visible-section AABB (bounding sphere из `sectionBoundingSphere`) в Node storage-буфер (raскладка 1-в-1 с `node_buffer_aos.glsl`, 32 байта/нода) через `STAGING_UPLOAD`-буфер + `CommandList.uploadToBuffer`, собирает `DrawArraysIndirectCommand` (count=36, instanceCount=visibleSections.size()) и вызывает `CommandList.draw(...)` (единственный draw-путь по контракту `CommandList`/`GraphicsPipeline` — indirect-only, см. `GLCommandList.draw`). View-projection матрица передаётся как plain-uniform через новый MVP-only метод `GLGraphicsPipeline.useProgramAndSetViewProj(float[16])` (contract-обход: `GraphicsPipeline`/`PipelineLayout` не имеет понятия обычного uniform, только именованные буфер/текстур-биндинги — задокументировано в Javadoc метода как временный backend-specific escape hatch). Каждая видимая секция рисуется как цветной unit-куб, отмасштабированный/спозиционированный по своей bounding sphere — не настоящая meshlet-геометрия из конвейера мешинга (тот пока никуда на GPU не заливается), цель — только подтвердить, что draw call действительно работает и что-то попадает на экран. Буферы (`nodeBuffer`, `stagingUpload`, `indirectBuffer`) и pipeline лениво создаются при первом кадре с видимыми секциями и переиспользуются между кадрами; `nodeBuffer`/`stagingUpload` пересоздаются (удваиваясь) только если требуемая ёмкость превышает текущую. **Компиляция не подтверждена реальным Gradle-билдом в этой сессии** (см. ограничения песочницы в `EV_SESSION_PROMPT.md`) — код вычитан вручную построчно против сигнатур `CommandList`/`GraphicsPipeline`/`RenderBackend`/`GpuBuffer` в `ev-api`, но требует подтверждения `./gradlew build`. Другие пассы/подсистемы не тронуты, профилирование не начиналось — оба вне рамок этой сессии. |

## 🌊 Статус MVP/opt волн

| Подсистема | Активная версия | Тикет | Дата/повод перехода (если opt) |
|---|---|---|---|
| Кэш секций | нет (не реализовано) | — | — |
| Очередь мешинга | MVP (PriorityBlockingQueue) | 15-meshing-priority-queue-mvp | — |
| Node buffer | MVP (AoS, единый буфер) | 20-gpu-node-buffer-mvp | — |
| Traversal | MVP (CPU linear frustum) | 21-gpu-simple-traversal-mvp | — |
| Occlusion culling | нет (MVP не имеет) | — | — |
| Temporal coherence | нет (MVP не имеет) | — | — |
| Mip-агрегация | MVP (скалярная) | 16-meshing-mipgen-mvp | — |
| GPU upload | нет (не реализовано) | — | — |
| Draw calls | нет (не реализовано) | — | — |
| Dirty tracking | MVP (whole-section + deduplicator) | 26-render-dirty-tracking-mvp | — |

## Модульная карта (`ev-*/src/main/java/dev/ev/...`)

| Модуль | Пакет | Назначение |
|---|---|---|
| ev-neoforge | `dev.ev.neoforge`, `dev.ev.neoforge.config`, `dev.ev.neoforge.command` | `EV` (@Mod entrypoint), `EVInstance` (lifecycle + `metrics()` + debug overlay toggle), `EVConfig` (immutable validated config record), `EVConfigLoader` (NeoForge ModConfigSpec bridge), `EVCommands` (Brigadier client commands: /ev debug, /ev debug watch, /ev profile, /ev reload-config), `MetricsSnapshotFormatter` (pure function formatter). |
| ev-api | `dev.ev.api`, `dev.ev.api.storage`, `dev.ev.api.meshing`, `dev.ev.api.gpu`, `dev.ev.api.metrics` | Все 5 api-тикетов (01-05) выполнены — `SectionPos`; storage-, meshing-, gpu- и metrics-контракты. Модуль `ev-api` полностью укомплектован контрактами, дальше только реализация в `ev-storage`/`ev-meshing`/`ev-gpu`/`ev-render`. |
| ev-storage | `dev.ev.storage.codec`, `dev.ev.storage.schema`, `dev.ev.storage.cache`, `dev.ev.storage.coarsegen` | `PaletteCodec`+кодеки (06), `SchemaVersion`/`SchemaMigrationChain`/`RegionFileHeader` (07), `SectionCache`-MVP (08), `HeightmapSource`/`CoarseSectionGenerator` (09). `build.gradle.kts` присутствует (см. "Сборка/тесты" ниже — был случайно пропущен в тикете 00, восстановлен отдельной правкой после реального прогона пользователя). |
| ev-meshing | `dev.ev.meshing.stage`, `dev.ev.meshing.priority`, `dev.ev.meshing.queue`, `dev.ev.meshing.mip` | `OccupancySet`/`OccupancyStage` (10), `GreedyMeshStage` (11), `MaterialBin`/`MaterialBinStage` (12) и `MeshletPackStage` (13) — весь конвейер Occupancy→GreedyMesh→MaterialBin→MeshletPack реализован. `ScreenSpaceErrorMetric`/`MeshPriority` (14) — выбор LOD-уровня и приоритет задачи мешинга. `MeshTaskQueue` (15) — MVP (PriorityBlockingQueue) очередь задач мешинга активна. `MipAggregator` (16) — MVP (скалярная) агрегация mip-уровней активна. |
| ev-gpu | `dev.ev.gpu.gl`, `dev.ev.gpu.nodes` | `GLRenderBackend` (17, 18, 31) — `createBuffer`/`createTexture` (17), `compilePipeline`/`compileGraphicsPipeline` (18, через `ShaderCompiler`), и `submit`/`insertFence`/`isSignaled`/`waitForFence`/`shutdown` (31, MVP fence/submit/shutdown wiring) все реализованы. `GLFenceHandle` (`dev.ev.gpu.gl`, record, implements `FenceHandle`) — обёртка над raw GL sync handle, добавлена в этой сессии (была использована в `GLRenderBackend`, но отсутствовала как файл). Опт-тикеты 19 (upload batching), 21-23 (traversal/occlusion/indirect multidraw за пределами MVP-`SimpleTraversal`) — не применены. `NodeBuffer` (20-mvp) — MVP AoS раскладка GPU-буфера узлов секций (`BYTES_PER_NODE = 32`). |
| ev-render | `dev.ev.render.culling`, `dev.ev.render.framegraph`, `dev.ev.render.dirty`, `dev.ev.render.scheduling` | `FrustumTester` и `SimpleTraversal` (21-mvp) — culling. `FrameResource`, `FramePass`, `PassBuilder`, `FrameGraphBuilder` (24) — frame graph. `DirtySectionTracker` и `GeometryChangeDeduplicator` (26-mvp) — MVP whole-section dirty tracking с CRC32 дедупликацией геометрии. `dev.ev.render.scheduling` (`MeshWorkerPool`, `MeshTask`, `MeshingPipelineRunner`, `MeshSchedulingCoordinator`, `SectionGeometryMap`, `SimpleMeshingContext`, `SectionGenerationPolicy`) — worker thread pool, дренирующий `MeshTaskQueue`, запускающий полный конвейер мешинга и складывающий готовые `MeshletBatch` для render-потока; это, по-видимому, реализация недостающего явного создания worker thread pool, о котором предупреждает `EV_SESSION_PROMPT.md` в разделе про интеграционные чеклисты 31-mvp/-full, ранее не отражённая в этом файле ни в одном из четырёх разделов — найдено при исправлении багфикса про `org.slf4j`, детальная сверка каждого класса этого пакета с текстом соответствующих тикетов не проводилась в рамках этого багфикса и остаётся на будущую сессию. |
| ev-test | `dev.ev.test.gpu` | `FakeRenderBackend` (30) — fully in-memory `RenderBackend` for testing domain logic without a real GPU. Lives in `main` source set so it's reusable as an ordinary test-dependency by other modules. |

## Ключевые классы

### ev-neoforge — `dev.ev.neoforge`, `dev.ev.neoforge.config` (тикеты 00, 27, 28)
- `EV` — entrypoint класс мода, аннотация `@Mod("ev")`, подписывается на `FMLClientSetupEvent`, `LevelEvent.Load`/`Unload`, `RenderLevelStageEvent`. Регистрирует `EVConfigLoader` в NeoForge `ModContainer`.
- `EVInstance` — жизненный цикл одного мира/рендер-контекста. `bootstrap()` создает `GLRenderBackend` при наличии GL-контекста. Метод `renderFarLod` очищает GL state и рассчитывает nearCutoffBlocks с умножением на `Math.sqrt(2.0)`.
- `EVConfig` (`dev.ev.neoforge.config`) — immutable record конфигурации мода с методом `validated()`, производящим безопасный clamping параметров при выходе за пределы допусков.
- `EVConfigLoader` (`dev.ev.neoforge.config`) — мост конфигурации с NeoForge `ModConfigSpec`. Клиентский файл конфигурации регистрируется через `container.registerConfig(ModConfig.Type.CLIENT, SPEC)`.

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
  Только контракт. Все 4 CPU-side стадии, из которых должна собираться реализация
  (`OccupancyStage`→`GreedyMeshStage`→`MaterialBinStage`→`MeshletPackStage`, тикеты 10-13),
  теперь реализованы как отдельные классы в `ev-meshing`, но **ни один из тикетов 10-13 не
  реализует сам `MeshBuilder`** — оркестрирующая реализация, вызывающая все 4 стадии подряд
  и удовлетворяющая этому интерфейсу, в явном списке тикетов 0-32 не найдена (проверено по
  `tickets/MVP_INDEX.md`). Возможно, предполагается как часть другого тикета (например,
  26-render-dirty-tracking-mvp, который зависит от тикетов 2 и 11) или как отдельный
  пропущенный тикет — стоит уточнить у пользователя перед тем, как писать эту реализацию
  по своей инициативе.

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

### ev-meshing — `dev.ev.meshing.stage` (тикет 10)
- `OccupancySet` — bitset `long[512]` над 32³ гридом, `flatIndex(x,y,z) = x+y*32+z*32*32`
  (совпадает с порядком осей `PaletteCodec`/`WorldSectionHandle` намеренно, см. требование
  тикета). `get`/`set`/`isEmpty`/`popCount`, без `java.util.BitSet` (hot path).
- `OccupancyStage.process(WorldSectionHandle) -> OccupancySet` — первая стадия конвейера
  мешинга. Fast-path на `WorldSectionHandle.isEmpty()`.
  Файлы: `ev-meshing/src/main/java/dev/ev/meshing/stage/{OccupancySet,OccupancyStage}.java`.

### ev-meshing — `dev.ev.meshing.stage` (тикет 11)
- `GreedyMeshStage.process(WorldSectionHandle, OccupancySet, MeshingContext) -> List<Quad>` —
  вторая стадия конвейера мешинга. Для каждого из 6 `faceDirection` и каждого из 32 срезов
  вдоль нормали строит 32×32 маску видимости+материала, затем жадно покрывает её
  прямоугольниками одного материала (стандартный greedy-meshing, без квадратичного прохода
  по уже обработанным ячейкам — сложность `O(6 * 32³)`, как требует тикет). Одна
  параметризованная по оси реализация на все 6 направлений (таблицы `NORMAL_AXIS`, `SIGN`,
  `WIDTH_AXIS`, `HEIGHT_AXIS`), не 6 копий кода.
- Циклическая width/height конвенция (X→Y→Z→X, требование 4a тикета 11): для грани с
  нормалью по оси N, `width` растёт вдоль следующей оси по циклу, `height` — вдоль оси после
  неё; `+`/`-` направления одной и той же оси нормали используют одну и ту же конвенцию.
  `Quad.x/y/z` — координата угла с наименьшими значениями по width/height среди покрытых
  вокселей, по оси нормали — граница вокселя со стороны грани (`n+1` для `+`-направлений,
  `n` для `-`-направлений).
  Файл: `ev-meshing/src/main/java/dev/ev/meshing/stage/GreedyMeshStage.java`.
  Тесты: `GreedyMeshStageTest`, `FakeMeshingContext` (в `ev-meshing/src/test/.../stage`).

### ev-meshing — `dev.ev.meshing.stage` (тикет 12)
- `MaterialBin(int materialId, List<Quad> quads)` — record, все quad'ы одного материала.
- `MaterialBinStage.process(List<Quad>) -> List<MaterialBin>` — третья стадия конвейера.
  Группировка через `LinkedHashMap<Integer, List<Quad>>` (сохраняет относительный порядок
  quad'ов внутри бина), финальный список бинов отсортирован по `materialId` возрастанию для
  детерминированного вывода. Пустой вход → пустой список бинов. `O(n)` группировка + `O(k
  log k)` сортировка бинов, где `k` — число уникальных материалов.
  Файлы: `ev-meshing/src/main/java/dev/ev/meshing/stage/{MaterialBin,MaterialBinStage}.java`.
  Тесты: `MaterialBinStageTest`.

### ev-meshing — `dev.ev.meshing.stage` (тикет 13)
- `MeshletPackStage.process(SectionPos, List<MaterialBin>) -> MeshletBatch` — финальная
  стадия конвейера. Каждый `MaterialBin` разбивается на чанки по `MAX_QUADS_PER_MESHLET`
  (128, из `MeshletBatch`), последний чанк — остаток; порядок бинов и порядок quad'ов внутри
  бина сохраняются без перестановки.
- `quadWorldBounds(Quad) -> float[6]` (package-private, `{minX,minY,minZ,maxX,maxY,maxZ}`) —
  единственное место в кодовой базе, где считается пространственный охват `Quad`. Ось нормали
  (та же, что у `faceDirection` в `GreedyMeshStage`) имеет нулевую толщину в координате самого
  quad'а; width/height-оси используют **ту же самую** таблицу нормаль/width/height, что
  зафиксирована в тикете 11 (требование 4a) — таблица продублирована в Javadoc с явной
  пометкой источника, чтобы не разъехаться с `GreedyMeshStage` при будущих правках любого из
  двух файлов.
  Файл: `ev-meshing/src/main/java/dev/ev/meshing/stage/MeshletPackStage.java`.
  Тесты: `MeshletPackStageTest`.

- `ScreenSpaceErrorMetric.selectLodLevel(distance, projectionScale, errorThresholdPx)` —
  линейный перебор уровней 6→0 (не бинарный поиск — 7 значений не требуют этого), первый
  удовлетворяющий порогу и есть искомый; если даже уровень 0 не проходит порог — fallback на
  0 (максимальная детализация — лучшее, что можно предложить). `voxelSizeAtLevel = 1 <<
  level` вычисляется напрямую, без создания `SectionPos` только чтобы прочитать константу.
- `MeshPriority` — приоритет задачи мешинга, `long`, меньше = приоритетнее.
  **Битовая раскладка нормального тира** (бит 63 всегда 0): биты 60-62 `lodWeight` (0-7,
  `round(lodLevel*7/maxLodLevel)`), 58-59 `attemptWeight` (`3 - min(attempts,3)` —
  attempts capped на 3), бит 57 `facingBonus` (0=в конусе обзора, 1=нет), биты 0-56
  `insertionSeq` (маска). Тикет 15 извлекает номер корзины сдвигом старших бит по этой же
  раскладке — менять сдвиги нельзя без синхронной правки тикета 15.
  **Двухъярусный near-player приоритет** (`computeWithNearTierCheck`, по эмпирическому
  уроку из независимой реализации Exceptional Vision, задокументированному прямо в тексте
  тикета 14): секции в радиусе `NEAR_PLAYER_UNCONDITIONAL_RADIUS_SECTIONS=4` (в
  level-0 section-grid единицах, Chebyshev-дистанция, независимо от LOD-уровня самой
  секции) получают `Long.MIN_VALUE + offset` — гарантированно отрицательное значение (бит
  63 установлен), поэтому по стандартному знаковому `long`-сравнению всегда меньше
  (=приоритетнее) любого нормального (неотрицательного) результата, независимо от того,
  насколько "хорош" нормальный результат по своим компонентам. `offset` = 20 бит
  квантованной squared-distance (в блоках, clamp на `0xFFFFF`) + 12 бит замаскированного
  `insertionSeq` — максимум `0xFFFFFFFF` (~4.3 млрд), что оставляет огромный запас
  относительно `Long.MIN_VALUE` (~9.2 квинтиллиона) и не может переполниться обратно в
  положительную область.
  Файлы: `ev-meshing/src/main/java/dev/ev/meshing/priority/{ScreenSpaceErrorMetric,MeshPriority}.java`.
  Тесты: `ScreenSpaceErrorMetricTest`, `MeshPriorityTest`.

### ev-meshing — `dev.ev.meshing.queue` (тикет 15-mvp)
- `MeshTaskQueue<T>` — MVP реализация очереди задач мешинга поверх `PriorityBlockingQueue<Entry<T>>`
  (приватный `Entry<T>` — обёртка `(long priority, T task)`, `Comparable` по `priority`, порядок
  через `Long.compare`). Один общий shared queue на все worker-потоки, без шардирования и без
  work-stealing (opt-версия, `15-meshing-work-stealing-queue-opt.md`, в банке — не применена).
  `submit(priority, task)` → `queue.put(...)`; `poll()` — блокирующий, через `queue.take()`;
  `pollNonBlocking()` — через собственный неблокирующий `PriorityBlockingQueue.poll()` из JDK;
  `size()` — прямой делегат.
  **Совместимость с двухъярусной схемой тикета 14 — "бесплатная"**: near-tier приоритеты (бит 63
  установлен → отрицательный `long`) естественно сортируются раньше normal-tier (бит 63 чист →
  неотрицательный `long`) через стандартное знаковое сравнение `Long`, без каких-либо специальных
  преобразований — в отличие от opt-версии, которой для того же инварианта нужен XOR при извлечении
  bucket index (см. Javadoc тикета 14 и `15-opt`).
  **Метрика**: `MetricsRegistry.recordQueueDepth("mesh-task-queue", size())` отправляется не на
  каждый вызов, а раз в `METRIC_REPORT_INTERVAL=64` операции (`submit`/`poll`/`pollNonBlocking`
  суммарно), через `AtomicInteger`-счётчик — соблюдает требование 4 тикета не заваливать
  registry на hot path.
  **Отличие контракта от opt-версии (задокументировано в Javadoc класса, а не только здесь)**:
  MVP-методы `submit`/`poll`/`pollNonBlocking` не имеют параметра `workerHint` — в MVP только
  один общий queue, а не per-worker deques этого параметра просто не с чем связать. Адаптация
  вызывающего кода при переходе на opt-версию потребует добавления этого аргумента на каждом
  call site, не является drop-in заменой один-в-один.
  Файл: `ev-meshing/src/main/java/dev/ev/meshing/queue/MeshTaskQueue.java`.
  Тесты: `MeshTaskQueueTest` (ordering, non-blocking empty-poll, size tracking, concurrent
  multi-producer/multi-consumer no-loss smoke test, equal-priority no-loss, near-tier vs
  normal-tier ordering invariant), `NoopMetricsRegistry` — тестовая заглушка (package-private,
  `ev-meshing/src/test/.../queue`, аналог одноимённой заглушки в `ev-storage`, не переиспользуется
  напрямую между модулями, так как в исходной `ev-storage`-версии класс package-private).

### ev-meshing — `dev.ev.meshing.mip` (тикет 16-mvp)
- `MipAggregator` — MVP скалярная агрегация детских вокселей в родительский mip-уровень,
  `aggregateMajorityVote(int[] childPalette, int[] parentPalette, int parentCount)`. Для
  каждого родителя `i` в `[0, parentCount)` берёт 8 значений `childPalette[i*8..i*8+7]`
  (порядок child index 0..7 совпадает с конвенцией `SectionPos.child(childIndex)` из тикета
  01) и выбирает majority-vote значение прямым попарным подсчётом `O(8*8)=O(64)` без
      аллокации `HashMap` на вызов (требование 2 тикета — минимизация GC-давления даже в MVP).
      **Tie-break правило (зафиксировано как обязательное и идентичное между `16-mvp` и будущей
      `16-opt`)**: при равенстве максимального count побеждает значение с наименьшим индексом
      позиции среди 8 детей — реализовано естественно через строгое `count > bestCount` (не
      `>=`), так что более поздняя позиция с равным (не большим) count никогда не вытесняет уже
      найденного победителя. `parentCount = 0` — no-op, не бросает исключение и не трогает
      `parentPalette` за пределами уже записанных индексов.
      Файл: `ev-meshing/src/main/java/dev/ev/meshing/mip/MipAggregator.java`.
      Тесты: `MipAggregatorTest` (5 сценариев: все 8 детей одинаковы, явное большинство 5/8, все
      8 разных значений — проверка конкретно tie-break правила, 1000 родителей со случайными
      (fixed seed) детьми сверенные с отдельной reference-реализацией, написанной иначе
      (частотный массив по значению вместо попарного подсчёта) прямо внутри теста — метод не
      проверяется сам через себя, `parentCount=0` не изменяет предзаполненный `parentPalette`).

### ev-gpu — `dev.ev.gpu.gl`, `dev.ev.gpu.nodes` (тикеты 17, 18, 20-mvp)
- `NodeBuffer` (`dev.ev.gpu.nodes`) — MVP (AoS, Array-of-Structures) буфер узлов секций на GPU. Создаёт единственный `BufferUsage.STORAGE` буфер размером `nodeCapacity * 32` байт. GLSL-сторона (`ev-gpu/src/main/resources/shaders/include/node_buffer_aos.glsl`) описывает выровненную по `std430` структуру `Node` (bounds vec4, flags uint, materialRef uint, streamState uint, _padding uint = 32 байта). Node buffer — MVP (AoS, единый буфер) версия активна, SoA opt-версия в банке, не применена.
- `GLRenderBackend implements RenderBackend` — единственный класс проекта (вместе с `GLBuffer`/
  `GLTexture`/`GLTextureFormats`/`ShaderCompiler`/`GLComputePipeline`/`GLGraphicsPipeline` в том
  же пакете), которому разрешено импортировать `org.lwjgl.*`. Полностью реализованы
  `createBuffer`/`createTexture` (17) и `compilePipeline`/`compileGraphicsPipeline` (18);
  остальные 5 методов интерфейса — `UnsupportedOperationException`-заглушки с комментарием,
  какой тикет их реализует (19-23 — submit/fences/shutdown). Этот же класс дополняется
  последующими тикетами, не заменяется отдельным конкурирующим классом.
  **Никакого мутируемого `static`-состояния** — прямое архитектурное исправление проблемы,
  задокументированной в анализе прототипа Voxy (статический scratch-буфер там ломал
  поддержку нескольких миров/пересоздания GL-контекста).
- `createBuffer(sizeBytes, usage)` — DSA-стиль (`glCreateBuffers`+`glNamedBufferStorage`,
  GL 4.5+, без bind-to-edit паттерна). `STATIC_DRAW`/`DYNAMIC_DRAW`/`STORAGE` →
  `GL_DYNAMIC_STORAGE_BIT` (разрешает будущие `glNamedBufferSubData`, включая для `STORAGE` —
  трактовка требования 1 тикета: "редко" CPU-touched не значит "никогда"). `STAGING_UPLOAD`/
  `STAGING_DOWNLOAD` — persistent-mapped через `glMapNamedBufferRange` с
  `GL_MAP_PERSISTENT_BIT|GL_MAP_COHERENT_BIT` + `GL_MAP_WRITE_BIT`/`GL_MAP_READ_BIT`
  соответственно; `long`-адрес получен через `MemoryUtil.memAddress(ByteBuffer)`, так как
  LWJGL-биндинг `glMapNamedBufferRange` возвращает `ByteBuffer`, не `long` напрямую (см.
  находку ниже). Валидация `sizeBytes > 0` → `IllegalArgumentException`.
- `createTexture(desc)` — `glCreateTextures`+`glTextureStorage2D`/`3D` (3D если
  `desc.depth() > 1`), `mipLevels` передаётся напрямую (валидация `>= 1`).
  `TextureFormat → GL internal format` маппинг вынесен в отдельный package-private
  `GLTextureFormats.toGlInternalFormat` — чистый метод без вызовов GL, поэтому тестируем без
  GL-контекста; исчерпывающий `switch` без `default` — future-proof против забытых новых
  значений `TextureFormat` (компилятор/рантайм укажут на пропуск явно).
- `GLBuffer.mappedAddress()` — `UnsupportedOperationException`, если `usage() != STAGING_UPLOAD`
  (контракт тикета 04). `GLBuffer.free()`/`GLTexture.free()` — идемпотентны (флаг `freed`);
  для замапленных буферов явный `glUnmapNamedBuffer` перед `glDeleteBuffers` (не строго
  обязательно по спеке GL — удаление неявно снимает маппинг, — но сделано explicit для
  ясности и driver-compatibility safety, как прямо просил тикет).
  **Верифицировано веб-поиском, не по памяти** (обе точки, которые тикет явно требовал
  проверить): (1) GL45 vs GL46 — LWJGL 3.3.3 (версия, зафиксированная в проекте) содержит оба
  класса, но macOS исторически ограничен OpenGL 4.1 (Apple заморозила поддержку на этом
  уровне) — оставлен GL45 для широкой кроссплатформенной совместимости, как тикет и предлагал
  по умолчанию при отсутствии явных доказательств безопасности GL46; (2) сигнатуры LWJGL API —
  `GL45.glNamedBufferStorage(int buffer, long size, int flags)` подтверждена; изначально
  написанный код ошибочно предполагал существование `GL45.nglMapNamedBufferRange(...)`,
  возвращающего `long` напрямую — такого метода с этой сигнатурой в публичном API нет,
  правильный путь — `glMapNamedBufferRange` (возвращает `ByteBuffer`) +
  `org.lwjgl.system.MemoryUtil.memAddress(ByteBuffer)`; ошибка найдена и исправлена в процессе
  этой же сессии до финальной сдачи, не оставлена как известный баг.
  Файлы: `ev-gpu/src/main/java/dev/ev/gpu/gl/{GLRenderBackend,GLBuffer,GLTexture,GLTextureFormats}.java`.
  Тесты: `GLTextureFormatsTest` (полнота маппинга по `TextureFormat.values()`, отсутствие
  коллизий GL-констант между разными форматами, точечная проверка всех 5 значений).
  Тесты, требующие реального GL-контекста (создание/освобождение буфера/текстуры), не
  добавлены — headless GL недоступен в этой песочнице; тикет явно делает это опциональным
  пунктом 2 раздела юнит-тестов, не блокирующим приёмку.
- **Тикет 18** — `GLRenderBackend.compilePipeline`/`compileGraphicsPipeline` дополнены (тот же
  класс, не пересоздан). `ShaderCompiler` (package-private) — `compileComputeProgram(source)`/
  `compileGraphicsProgram(vertexSrc, fragmentSrc)`: `glCreateShader`+`glShaderSource`+
  `glCompileShader` с проверкой `GL_COMPILE_STATUS` → `RuntimeException` с `sourcePath()` +
  `glGetShaderInfoLog` при неудаче; линковка через `glCreateProgram`+`glAttachShader`+
  `glLinkProgram` с проверкой `GL_LINK_STATUS` → аналогичный `RuntimeException` с
  `glGetProgramInfoLog`; после успешной линковки — `glDetachShader`+`glDeleteShader` для
  промежуточных шейдер-объектов. `injectDefines(rawSource, defines)` — package-private
  статический чистый метод (тестируется без GL): требует `rawSource.startsWith("#version")`
  (иначе `IllegalArgumentException` — падение на Java-стороне до отправки в драйвер, как просил
  тикет), для пустой карты возвращает исходную строку без изменений, иначе вставляет
  `#define KEY VALUE` для каждой записи сразу после первой строки (`#version ...`), сохраняя
  порядок `Map`. `GLComputePipeline`/`GLGraphicsPipeline` (package-private конструктор,
  создаются только из `GLRenderBackend`) хранят program handle + `bindingsByName` из
  `PipelineLayout`; `bindBuffer`/`bindTexture` — `bindingsByName.get(name)` →
  `IllegalArgumentException` при отсутствии ключа (опечатка не проглатывается молча), иначе
  `glBindBufferBase(GL_SHADER_STORAGE_BUFFER, point, handle)` / `glBindTextureUnit(point,
  handle)`. **Допущение (задокументировано в Javadoc обоих классов)**: биндинг буферов всегда
  идёт как SSBO (`GL_SHADER_STORAGE_BUFFER`), не UBO — `PipelineLayout` пока не несёт
  информации о типе ресурса, проект по архитектуре compute/SSBO-heavy (см. PERFORMANCE_MATH.md).
  `dispatch`/`dispatchIndirect` — `glUseProgram` перед вызовом; `dispatchIndirect` явно биндит
  `indirectBuffer` на `GL_DISPATCH_INDIRECT_BUFFER` внутри метода (не полагается на внешний
  код, как требовал тикет). `drawIndirect`/`drawIndirectCount` реализованы через
  `glMultiDrawArraysIndirect`/`ARBIndirectParameters.glMultiDrawArraysIndirectCountARB`
  (`GL_TRIANGLES`, `baseInstance/stride=0`) — тикет не расписывал эти два метода дословно
  (фокус тикета — компиляция и биндинги), реализация дописана по контракту интерфейса
  `GraphicsPipeline` из тикета 04, не выходит за рамки задачи. **Пост-фактум исправление**:
  `drawIndirectCount` изначально ссылался на `GL45.glMultiDrawArraysIndirectCount` и
  `GL45.GL_PARAMETER_BUFFER` — их не существует в core GL45 (LWJGL), это часть расширения
  `ARB_indirect_parameters`, ставшего core только в OpenGL 4.6; проект зафиксирован на GL45
  ради macOS-совместимости (потолок GL 4.1), поэтому заменено на ARB-суффиксные
  `ARBIndirectParameters.GL_PARAMETER_BUFFER_ARB`/`glMultiDrawArraysIndirectCountARB` — тот же
  jar `lwjgl-opengl`, новой Gradle-зависимости не потребовалось. `free()` на обоих —
  `glDeleteProgram`, идемпотентно.
  Файлы: `ev-gpu/src/main/java/dev/ev/gpu/gl/{ShaderCompiler,GLComputePipeline,
  GLGraphicsPipeline}.java` (новые), `GLRenderBackend.java` (дополнен, не пересоздан).
  Тесты: `ShaderCompilerTest` (6 тестов на `injectDefines`: пустые defines без изменений,
  один/несколько defines с сохранением порядка вставки, точная позиция вставки сразу после
  `#version`-строки — не в конец файла и не перед `#version`, `IllegalArgumentException` при
  отсутствии `#version`-строки как с пустыми, так и с непустыми defines). Тесты, требующие
  реального GL-контекста (сама компиляция/линковка шейдера в драйвере), не добавлены —
  headless GL недоступен в этой песочнице, аналогично тикету 17. **Компиляция подтверждена
  фактическим прогоном пользователем (2026-08-17, `./gradlew build` — `BUILD SUCCESSFUL`,
  включая `ev-gpu:compileJava`/`test`/`check`/`build`)**.

### ev-render — `dev.ev.render.culling`, `dev.ev.render.framegraph`, `dev.ev.render.dirty` (тикеты 21-mvp, 24, 26-mvp)
- `FrustumTester` (`dev.ev.render.culling`) — математический сферно-плоскостной тест видимости (sphere-vs-6-planes). Метод `isVisible(worldX, worldY, worldZ, radius)` делает консервативную проверку (`signedDistance < -radius` -> reject).
- `SimpleTraversal` (`dev.ev.render.culling`) — MVP (Волна-1) CPU-side проход линейной сложности `O(N)` по списку `loadedSections`. Замеряет wall-clock время каждого вызова в наносекундах и записывает метрику `MetricsRegistry.recordGpuPassDuration("cpu-traversal", nanos)`.
- `FrameResource` / `FramePass` / `PassBuilder` / `FrameGraphBuilder` (`dev.ev.render.framegraph`, тикет 24) — декларативная оркестрация кадра. Использование: single-use per frame instance (`execute()` можно вызвать строго один раз). Топологическая сортировка (алгоритм Кана по читаемым/пишимым ресурсам), проверка циклов (`IllegalStateException`), вставка барьеров памяти `BarrierScope.ALL` перед проходом `P`, если какой-либо ранний проход `Q` писал в ресурс, используемый `P`. Единая отправка через `RenderBackend.submit(CommandList)` за кадр и замер времени через `MetricsRegistry.recordGpuPassDuration`.
- `DirtySectionTracker` (`dev.ev.render.dirty`, тикет 26-mvp) — MVP отслеживание dirty-состояния на уровне всей секции целиком через `LongOpenHashSet` закодированных `SectionPos`. Не потокбезопасен по дизайну (main-thread tick).
- `GeometryChangeDeduplicator` (`dev.ev.render.dirty`, тикет 26-mvp) — дедупликация пересборки геометрии по CRC32-хэшу `Quad`-списка секции, предотвращает лишние GPU upload / дисковые записи при байт-в-байт идентичном результате. **`recordAndCheckChanged` — `synchronized`** (пост-26-mvp багфикс): единственный инстанс этого класса шарится между всеми потоками `MeshWorkerPool` (по умолчанию 8), а внутренний `Long2LongOpenHashMap` (fastutil) не потокобезопасен — конкурентные `put()` из разных воркеров гонялись за внутренним resize/rehash и приводили к `ArrayIndexOutOfBoundsException` в проде. `hashQuads()` синхронизации не требует — работает только с локальными для вызывающего потока данными.
  Тесты: `DirtySectionTrackerTest` (5 сценариев), `GeometryChangeDeduplicatorTest` (5 сценариев, включая многопоточный regression-тест на гонку из багфикса выше).

### ev-test — `dev.ev.test.gpu` (тикет 30)
- `FakeRenderBackend implements RenderBackend` — полностью in-memory реализация,
  единственный класс проекта в `ev-test`. Размещён в `main` source set (`ev-test/src/main
  /java/dev/ev/test/gpu`), а не в `test`, чтобы другие модули (`ev-gpu`, `ev-render`) могли
  подключить его как обычную test-зависимость на `ev-test`, а не только использовать
  внутри собственных тестов `ev-test`. **Компилирует/исполняет CommandList синхронно**
  внутри `submit()` — нет отложенного GPU-исполнения.
- `FakeGpuBuffer` — backed by `byte[]` (int-индексируемый, поэтому `sizeBytes >
  Integer.MAX_VALUE` бросает `IllegalArgumentException` — задокументированное
  fake-специфичное ограничение, не относится к реальным реализациям). Для
  `BufferUsage.STAGING_UPLOAD` — `mappedAddress()` возвращает **реальный** off-heap адрес
  через `MemoryUtil.nmemAlloc` (выбрано вместо простого `UnsupportedOperationException`,
  так как тикет явно указал, что `StagingUploadRing` из тикета 19 может нуждаться в
  рабочем `mappedAddress()` для полноценного теста без реального GL). **Важно**: этот
  off-heap блок НЕ синхронизирован с `byte[]`, который использует
  `readBufferContents`/`writeBufferContents`/`FakeCommandList` — запись через сырой
  указатель не отражается в `readBufferContents` и наоборот; задокументировано на классе.
- `FakeCommandList` (package-private) — записывает операции в список, реально исполняет
  их только при передаче в `FakeRenderBackend.submit(...)`:
  `uploadToBuffer`/`copyBuffer`/`clearBuffer` реально двигают байты между
  `FakeGpuBuffer`-объектами; `dispatchCompute`/`dispatchComputeIndirect`/`draw`/
  `memoryBarrier` только пишутся в `recordedOperationLog()`. Получение экземпляра —
  `FakeRenderBackend.newCommandList()`, тестовое дополнение сверх контракта
  `RenderBackend` (тикет 04 не предоставляет способа создать `CommandList`).
- **Fence-семантика — все fence считаются сигнализированными немедленно** (`isSignaled`
  всегда `true`, `waitForFence` возвращает `true` без блокировки) — прямое следствие
  синхронного исполнения. Явно и заметно задокументировано и на `FakeRenderBackend`, и на
  `FakeFenceHandle` (не только в тексте тикета) со ссылкой на предупреждение тикета
  19-gpu-upload-batching-opt, которое уже само по себе предостерегает не использовать этот
  fake для тестирования fence-зависимой backpressure-логики.
- `FakeComputePipeline`/`FakeGraphicsPipeline` — не компилируют GLSL (нет GL-контекста),
  хранят `ShaderSource`/`PipelineLayout` как есть, `dispatch`/`bindBuffer`/`bindTexture`/
  `drawIndirect`/etc. пишут вызовы в `recordedOperationLog()` родительского backend'а через
  package-private `FakeRenderBackend.log(String)`. `bindBuffer`/`bindTexture` валидируют
  имя биндинга против `PipelineLayout.bindingsByName()`, бросая `IllegalArgumentException`
  на опечатку — полезная проверка вызывающего кода, которую тикет прямо перечисляет как
  одну из целей fake'а ("correct binding names").
- `activeBuffers()`/`activeTextures()` — живой снимок неосвобождённых handle'ов, для
  leak-detection assert'ов в тестах, потребляющих этот fake.
  Файлы: `ev-test/src/main/java/dev/ev/test/gpu/{FakeRenderBackend,FakeGpuBuffer,
  FakeGpuTexture,FakeCommandList,FakeComputePipeline,FakeGraphicsPipeline,
  FakeFenceHandle}.java`.
  Тесты: `FakeRenderBackendTest` (`ev-test/src/test/java/dev/ev/test/gpu`) — 8 сценариев
  из тикета (accessors, write→read round-trip, `free()`→`activeBuffers()`, `submit`+
  `uploadToBuffer` реальное копирование через нативный адрес, `submit`+`copyBuffer` с
  учётом offset'ов, порядок `dispatchCompute` в логе, always-signaled fence-контракт,
  `shutdown()` идемпотентен).
  **⚠️ Build-файл изменён**: `ev-test/build.gradle.kts` — зависимость на `ev-api`
  сменена `implementation`→`api` (публичные методы fake'а возвращают типы
  `dev.ev.api.gpu.*`, должны быть видны транзитивно потребителям `ev-test`), добавлена
  `implementation("org.lwjgl:lwjgl")` + OS-детектируемый `runtimeOnly(natives-*)`
  (реальная runtime-зависимость теперь, не только `testImplementation` — `nmemAlloc`
  исполняется в коде `main` source set'а, нужна нативная библиотека LWJGL на runtime
  classpath, не только Java-классы; GL/display natives не требуются, это чистая
  работа с off-heap памятью). **Точная классификатор-строка per OS/arch не проверена
  реальным резолвом зависимостей в этой песочнице** — следует стандартному LWJGL-Gradle
  паттерну, но это непроверенное допущение до реального `./gradlew` прогона.

## Межмодульные контракты, зафиксированные де-факто

- `dev.ev.api.SectionPos` (тикет 01) — стабильный контракт `ev-api`, на него будут
  опираться `ev-storage` (ключ кэша секций, тикет 08), `ev-meshing` (приоритет/адресация,
  тикет 14) и `ev-gpu` (кодирование позиции в SSBO, тикет 20). Сигнатура зафиксирована,
  менять между MVP/opt волнами нельзя (см. правило в MVP_INDEX.md).
- `MeshingContext.getNeighborBoundaryVoxel(faceDirection, a, b)` — тексты тикетов 03 и 11
  определяют `a`/`b` только как "две локальные координаты, покрывающие эту грань", не
  фиксируя порядок. `GreedyMeshStage` (тикет 11, единственный текущий вызывающий код)
  зафиксировал де-факто конвенцию: `a` = координата по width-оси, `b` = координата по
  height-оси, по той же циклической конвенции (X→Y→Z→X), что и `Quad.width/height`.
  Будущая реализация `MeshingContext` в `ev-render` обязана следовать этой же конвенции —
  иначе грани на стыке секций будут скрываться/показываться в неверном месте.

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
- Реализация хранилища частично существует (`ev-storage`: тикеты 06-09) и мешинга
  (`ev-meshing`: тикеты 10-14 — все четыре CPU-side стадии конвейера и приоритет задачи
  мешинга/screen-space error реализованы). **Реализации самого `MeshBuilder`** (интерфейс
  из тикета 03, оркестрирующий вызов всех четырёх стадий подряд) **пока нет** — см. заметку
  в разделе "Ключевые классы" (`dev.ev.api.meshing`, тикет 03) выше. Очередь задач мешинга
  MVP (тикет 15-mvp, `MeshTaskQueue`, использующая `MeshPriority` из тикета 14 для сортировки)
  реализована; opt-версия (work-stealing, 15-opt) в банке, не применена. Mip-агрегация MVP
  (тикет 16-mvp, `MipAggregator`, скалярный majority-vote) реализована; SIMD opt-версия
  (16-opt) в банке, не применена. GPU-бэкенд (тикеты 17-18: `GLRenderBackend.createBuffer`/
  `createTexture`/`compilePipeline`/`compileGraphicsPipeline`) частично реализован —
  оставшиеся 5 методов `RenderBackend` (submit/insertFence/isSignaled/waitForFence/shutdown)
  реализованы (тикет 31, MVP-scope — см. запись 31-integration-checklist в таблице статуса
  тикетов выше, включая багфикс отсутствовавшего `GLFenceHandle` в этой сессии). Рендер-
  оркестрация тоже ещё не реализована.
- **✅ Исправлено (2026-08-16, отдельная сессия по итогам реального `./gradlew build` пользователя)**:
  `ev-storage/build.gradle.kts` создан. Симптом на реальном билде совпал с тем, что было
  предсказано здесь заранее: `:ev-storage:compileJava FAILED`, 32 ошибки, все одного корня —
  `package dev.ev.api.storage does not exist` / `cannot find symbol` на `WorldSectionHandle`,
  `SectionPos`, `MetricsRegistry`, `StorageMetrics` во всех файлах тикетов 08/09
  (`EvictionPolicy`, `LruEvictionPolicy`, `SectionCache`, `SectionLoader`,
  `CoarseSectionGenerator`) — без build-файла подпроект не имел зависимости на `ev-api`,
  компилятор не видел его пакеты вообще. Файл создан по образцу `ev-meshing/build.gradle.kts`
  (`java-library`, `fastutil`, JUnit 5), но с `api(project(":ev-api"))`, не `implementation` —
  публичные сигнатуры классов `ev-storage` (`SectionCache.acquire` → `WorldSectionHandle`,
  `CoarseSectionGenerator.generate(WorldSectionHandle)`, и т.д.) возвращают/принимают типы
  `ev-api` напрямую, поэтому вышестоящие модули (`ev-meshing`, `ev-neoforge`), зависящие от
  `ev-storage`, обязаны видеть эти типы транзитивно — с `implementation` это было бы
  сокрыто и привело бы к аналогичной ошибке компиляции уже на их уровне.
- В `ev-test` (модуль для интеграционных/кросс-модульных тестов) по-прежнему ни одного
  тестового класса — `SectionPosTest` (тикет 01) лежит в `ev-api/src/test`, не в
  `ev-test`, так как тикет 01 самодостаточен и не требует зависимостей других модулей.
- `neoforge.mods.toml` существует только как шаблон в `src/main/templates`
  (разворачивается таском `generateModMetadata` в `build/generated/...`), не как
  статичный файл в `resources`.
- Конфиг мода, debug-команды (`/ev ...`) реализованы (тикеты 28-29). `FakeRenderBackend`
  (тикет 30) теперь тоже реализован — см. раздел `ev-test` выше.

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

## Что дальше (по состоянию на 2026-08-19)

Волна 1 (MVP) полностью реализована и подтверждена: `./gradlew build` — `BUILD SUCCESSFUL`,
все модули компилируются, все юнит-тесты во всех модулях (`ev-api`, `ev-storage`,
`ev-meshing`, `ev-gpu`, `ev-render`, `ev-test`, `ev-neoforge`) проходят. Три сборочных
дефекта, блокировавших это (недостающий `GLFenceHandle`, недостающая зависимость
`slf4j-api` в `ev-render`, недостающий `neoForge.unitTest` в `ev-neoforge`), исправлены и
задокументированы как багфиксы 11-13 в `PROGRESS.md`.

**Следующий шаг по `README.md`/`tickets/MVP_INDEX.md` — НЕ ещё один тикет, а ручной этап**:
> "ЗАПУСТИ, ПОИГРАЙ, ЗАМЕРЬ" — пользователь должен собрать и реально запустить мод в
> dev-окружении (`./gradlew runClient` или аналог), убедиться, что он работает на реальном
> мире, и только после этого переходить к `tickets/P0-profiling-checkpoint.md`.

`P0-profiling-checkpoint.md` требует: реального игрового запуска, JFR-профилирования,
GPU-профилирования (RenderDoc или `/ev profile`), визуального наблюдения за холодным
стартом/steady-state на реальном (не игрушечном) мире — это принципиально не может быть
выполнено в песочнице без GPU/display/интерактивного игрового клиента. Он требует действий
пользователя за пределами Gradle-билда.

**Если следующая сессия получает на вход какой-то `-opt` тикет из Волны 2 (08-opt, 15-opt,
16-opt, 19-opt, 20-opt, 21-opt, 22-opt, 23-opt, 25-opt, 26-opt) до того, как в репозитории
появится заполненный `PROFILING_RESULTS.md`** (см. критерии приёмки `P0-profiling-checkpoint.md`)
— это отклонение от порядка, заданного `MVP_INDEX.md`/`README.md`, и стоит явно переспросить
пользователя, действительно ли он хочет пропустить профилирование, а не молча выполнять
opt-тикет вслепую.

`31-integration-checklist-full.md` (переинтеграция после точечного применения opt-тикетов)
и сам `P0-profiling-checkpoint.md` — оба NOT_STARTED, ожидают реального запуска мода.