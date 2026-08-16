# Прогресс реализации проекта Horizon / EV

> **Правило обновления файла**: При завершении или изменении любого шага из `ACTION_PLAN.md` вносить краткую запись о сделанных изменениях, статус задачи (`[x]` — готово, `[/]` — в процессе, `[ ]` — не начато) и краткое резюме результатов.

---

## Статус выполнения задач

### Этап 1: Волна 1 (MVP) — Скелет и Контракты API
- [x] **00-project-skeleton** — Создание структуры Gradle-проекта
- [x] **32-project-index** — Инициализация `PROJECT_INDEX.md`
- [x] **01-api-sectionpos** — Реализация `SectionPos`
- [x] **02-api-storage-interfaces** — Контракты `SectionCache` и `PaletteCodec`
- [x] **03-api-meshing-interfaces** — Контракты `MeshGenerator` и `MeshTaskQueue`
- [x] **04-api-renderbackend-interfaces** — Контракты `RenderBackend`
- [x] **05-api-metrics-interfaces** — Контракты `MetricsRegistry`

### Этап 2: Волна 1 (MVP) — Хранилище и Мешинг
- [ ] **06-storage-palette-codec** — Кодек палитры
- [ ] **07-storage-schema-migration** — Версионирование и миграция схемы
- [ ] **08-storage-section-cache-mvp** — MVP кэш секций
- [ ] **09-storage-heightmap-coarse-gen** — Coarse heightmap генерация
- [ ] **10-meshing-occupancy-stage** — OccupancyStage
- [ ] **11-meshing-greedy-mesh-stage** — GreedyMeshStage
- [ ] **12-meshing-material-bin-stage** — MaterialBinStage
- [ ] **13-meshing-meshlet-pack-stage** — MeshletPackStage
- [ ] **14-meshing-priority-function** — Вычисление приоритета мешинга
- [ ] **15-meshing-priority-queue-mvp** — MVP очередь задач мешинга
- [ ] **16-meshing-mipgen-mvp** — MVP MIP-агрегация

### Этап 3: Волна 1 (MVP) — GPU, Рендеринг и Интеграция
- [ ] **17-gpu-backend-gl-buffers** — GL буферы
- [ ] **18-gpu-backend-gl-shaders** — GLSL шейдеры
- [ ] **20-gpu-node-buffer-mvp** — MVP AoS node buffer
- [ ] **21-gpu-simple-traversal-mvp** — MVP CPU traversal
- [ ] **24-render-frame-graph** — FrameGraph
- [ ] **26-render-dirty-tracking-mvp** — MVP dirty tracking
- [ ] **27-neoforge-mod-entrypoint** — NeoForge Mod Entrypoint
- [ ] **28-neoforge-config** — Конфигурация мода
- [ ] **29-neoforge-commands** — Команды управления
- [ ] **30-test-fake-render-backend** — FakeRenderBackend тесты
- [ ] **31-integration-checklist-mvp** — Сборка и проверка MVP

### Этап 4: Профилирование (Чекпоинт P0)
- [ ] **P0-profiling-checkpoint** — Замеры холодного старта, FPS и задержек

### Этап 5: Волна 2 — Оптимизации (Opt-in)
- [ ] **08-storage-section-cache-opt** — Шардированный кэш
- [ ] **15-meshing-work-stealing-queue-opt** — Work-stealing очередь
- [ ] **16-meshing-simd-mipgen-opt** — SIMD MIP-генерация
- [ ] **19-gpu-upload-batching-opt** — Upload batching
- [ ] **20-gpu-node-buffer-soa-opt** — SoA node buffer
- [ ] **21-gpu-persistent-traversal-shader-opt** — GPU persistent traversal
- [ ] **22-gpu-hiz-occlusion-opt** — Hi-Z occlusion
- [ ] **23-gpu-indirect-multidraw-opt** — Indirect multi-draw
- [ ] **25-render-temporal-reprojection-opt** — Temporal reprojection
- [ ] **26-render-dirty-subregion-opt** — Dirty subregions
- [ ] **31-integration-checklist-full** — Итоговая сборка

---

## Журнал изменений

### 00-project-skeleton — [x] готово

Создан multi-module Gradle-проект в корне репозитория (settings.gradle.kts, корневой
build.gradle.kts, gradle.properties, gradle wrapper 8.8) со всеми 7 модулями точно
по структуре, требуемой тикетом: `ev-api`, `ev-storage`, `ev-meshing` (чистые
java-library, без NeoForge/LWJGL в classpath, только JDK + fastutil 8.5.13),
`ev-gpu` (LWJGL 3.3.3 через `compileOnly`/`testImplementation`, единственный модуль
с GL-зависимостью), `ev-render` (зависит от `ev-api` и `ev-gpu`), `ev-neoforge`
(плагин `net.neoforged.moddev` 2.0.141, единственный модуль с финальным jar),
`ev-test` (JUnit 5, зависит от всех модулей).

**Исправление после ревью**: версии NeoForge/parchment/loader изначально были взяты
через web search и оказались неверными (`neo_version=21.1.172` вместо актуального
`21.1.235`). Переделано — за основу взяты значения из уже присутствующего в этом же
репозитории `gradle.properties`/`build.gradle` проекта Exceptional Vision (сосед по
репо, тот же стек NeoForge/1.21.1), как более надёжный и явно актуальный источник,
чем поиск: `neo_version=21.1.235`, `neo_version_range=[21,)`,
`loader_version_range=[4,)`, `parchment_minecraft_version=1.21.11`,
`parchment_mappings_version=2025.12.20`. `ev-neoforge/build.gradle.kts` переписан по
структуре эталонного `build.gradle` (runs client/server/gameTestServer/data,
parchment-блок, `generateModMetadata` task, разворачивающий шаблон
`neoforge.mods.toml` из `src/main/templates` вместо статичного файла в `resources`).

Создан `ev-neoforge/src/main/templates/META-INF/neoforge.mods.toml` (шаблон с
`${...}`-плейсхолдерами, разворачиваемый `generateModMetadata`) и entrypoint
`dev.ev.neoforge.EV` (`@Mod("ev")`, логирует "EV mod loaded" через SLF4J, без
функциональности — как и требует MVP).

**Не проверено — критерий приёмки №1 (`./gradlew build` завершается успешно)**:
песочница, в которой выполнялся тикет, разрешает сетевой доступ только к
ограниченному списку доменов; `services.gradle.org` (откуда `gradlew` тянет
дистрибутив Gradle 8.8) и `maven.neoforged.net` (репозиторий NeoForge) в этот
список не входят, поэтому реальная сборка здесь невозможна технически, а не из-за
ошибки в конфигурации. `gradle-wrapper.jar` восстановлен вручную (отсутствовал в
исходном репозитории), сам wrapper корректно стартует и падает именно на сетевом
403 при попытке скачать дистрибутив — то есть до стадии компиляции дело не доходит
в принципе. **Требуется первый прогон `./gradlew build` и `./gradlew :ev-api:dependencies`
на машине с обычным доступом в интернет**, прежде чем считать критерии приёмки 1, 2
и 4 подтверждёнными фактически, а не только по структуре файлов.

### 32-project-index — [x] готово

Создан `PROJECT_INDEX.md` в корне репозитория. Выполнен вторым (сразу после тикета 00),
ретроактивных пробелов нет — единственный существующий на данный момент класс
(`dev.ev.neoforge.EV`) отражён в секции "Ключевые классы", таблица статуса тикетов
заполнена целиком (00 и 32 — DONE, 27 — IN_PROGRESS из-за уже созданного скелета
entrypoint-класса, остальные — NOT_STARTED), секция "Сборка/тесты — ограничения для
AI-сессий" переносит наблюдение из тикета 00 про недоступность
`services.gradle.org`/`maven.neoforged.net` в песочнице агента, чтобы следующая сессия
не тратила время на повторную попытку того же. Секция "🌊 Статус MVP/opt волн"
заполнена как "нет (не реализовано)" по всем подсистемам — ещё рано для реальных
записей.

Начиная с этого момента, любой следующий тикет обязан завершаться обновлением
`PROJECT_INDEX.md` — инструкция об этом зафиксирована в шапке самого файла, не
полагаясь на то, что каждый будущий тикет процитирует тикет 32 явно.

### Реструктуризация — перенос ev/ в корень репозитория

По уточнению пользователя: изначальная цель была не завести новый проект EV рядом со
старым Exceptional Vision, а **полностью заменить** старый одномодульный код новым
multi-module проектом в том же репозитории. Исправлено:
- Удалены старые корневые файлы одномодульного проекта: `src/` (класс-заглушка
  `ExceptionalVision`), `build.gradle`, `settings.gradle`, `gradle.properties`,
  `gradlew`/`gradlew.bat`, `gradle/` (старый wrapper).
- Всё содержимое папки `ev/` (settings.gradle.kts, build.gradle.kts, все 7 модулей,
  gradle.properties, wrapper) перемещено в корень репозитория.
- Обновлены упоминания пути `ev/` на "в корне репозитория" в PROGRESS.md и
  PROJECT_INDEX.md.

Старый `build.gradle`/`gradle.properties` использовались как эталон конфигурации
(актуальные версии NeoForge/parchment, стиль `neoForge { runs {...} }` блока) ещё до
их удаления — это уже было сделано при исправлении версий после первого прогона
тикета 00, само содержимое перенесено в новую конфигурацию корректно.

### 01-api-sectionpos — [x] готово

Создан `ev-api/src/main/java/dev/ev/api/SectionPos.java` — реализация 1-в-1 по контракту
тикета (record, битовый layout `level(4)/y(8)/z(26)/x(26)`, `MAX_LOD_LEVEL=6`,
`encode`/`decode`, `parent`/`child`, `sizeInBlocks`, `minBlockX/Y/Z`, `fromBlockCoord`,
`toString`) — без единого отклонения от текста тикета, лишних публичных методов не
добавлялось.

Создан `ev-api/src/test/java/dev/ev/api/SectionPosTest.java` (JUnit 5, без параметризации —
`junit-jupiter-params` не требуется отдельно объявлять и не хотелось полагаться на неявную
транзитивность agregator-артефакта `junit-jupiter` без возможности это фактически
прогнать). Покрыты все 6 обязательных кейсов из тикета:
1. `encodeDecodeRoundTrip` — round-trip на наборе значений, включая отрицательные x/y/z и
   граничные значения битовых полей (`±2^25-1` для x/z, `±128/127` для y, level 0 и
   `MAX_LOD_LEVEL`).
2. `parentThenChildReturnsOriginalPosition` / `parentThenChildRoundTripNegativeCoords` —
   `parent()` → `child(корректный индекс)` возвращает исходную позицию, включая случай
   отрицательных координат (индекс вычислен через `Math.floorMod`, а не `& 1`, чтобы не
   зависеть от знака при вычислении бита chield-индекса).
3. `sizeInBlocksForAllLevels` — 32/64/128/256/512/1024/2048 для уровней 0..6.
4. `fromBlockCoordZeroAtLevelZero` — блок (0,0,0) → `SectionPos(0,0,0,0)`.
5. `fromBlockCoordNegativeUsesFloorDiv` + `fromBlockCoordNegativeBoundary` — blockX=-1 даёт
   x=-1 (не 0), плюс проверка точной границы -32/-33.
6. `constructorRejectsLevelBelowRange` / `constructorRejectsLevelAboveRange` —
   `IllegalArgumentException` вне диапазона `[0, MAX_LOD_LEVEL]`.

Дополнительно (не входит в обязательный минимум тикета, но напрямую следует из описанных в
тексте тикета инвариантов): `parentThrowsAtMaxLodLevel`, `childThrowsAtLevelZero`,
`childThrowsOnInvalidIndex`, `minBlockCoordsMatchSectionOrigin`.

**Не подтверждено фактическим прогоном — критерии приёмки 2 и 3 (`./gradlew :ev-api:test`,
компиляция без предупреждений)**: та же сетевая песочница, что и в тикете 00
(`services.gradle.org`/`maven.neoforged.net` не в allowlist), не позволяет скачать Gradle
distribution. Дополнительно проверено в этой сессии: в системе есть только
`openjdk-21-jre-headless` (`java`), но не `javac` — `apt-get install openjdk-21-jdk-headless`
падает с 404 на `security.ubuntu.com` (пакет недоступен в репозитории, а не сетевая
блокировка — `archive.ubuntu.com` в allowlist есть и отвечает, но `security.ubuntu.com`
для этого конкретного пакета возвращает 404). То есть даже прямая компиляция через `javac`
в обход Gradle технически невозможна в этой песочнице. Код построчно сверен с контрактом,
данным в тексте тикета (скопирован дословно, без изменений сигнатур), риск ошибки
компиляции минимален, но фактическая верификация (`./gradlew :ev-api:test`) остаётся
обязательным первым шагом для следующей сессии с обычным доступом в интернет — как и для
тикета 00.

### 02-api-storage-interfaces — [x] готово

Создан пакет `ev-api/src/main/java/dev/ev/api/storage/` с четырьмя типами дословно по
контракту тикета: `VoxelStorage` (интерфейс, `extends Closeable`), `DirtyFlags`
(non-instantiable holder с битовыми константами + `has(int,int)`), `WorldSectionHandle`
(интерфейс, retain/release с ref-counting), `StorageMetrics` (record с `hitRate()`).
Никакой реализации — только контракты, как требует тикет.

**Найдено и явно задокументировано, не "исправлено молча" — внутреннее противоречие в
самом тексте тикета**: сигнатура `VoxelStorage.markDirty(WorldSectionHandle handle,
DirtyFlags flags)` использует `DirtyFlags` как **тип параметра**, но тот же тикет
определяет `DirtyFlags` как non-instantiable класс-неймспейс (`private DirtyFlags() {}`,
только `static final int` константы, без публичного конструктора/фабрики). Литерально
сигнатура компилируется (имя класса — валидный тип), но ни один вызывающий код не
сможет создать значение `DirtyFlags` для передачи в параметр — экземпляр этого класса
создать невозможно. Естественное исправление — `void markDirty(WorldSectionHandle
handle, int flags)`, что согласуется с `DirtyFlags.has(int flags, int flag)`, но я НЕ
стал самостоятельно вносить это исправление в контракт: воспроизвёл сигнатуру дословно,
подробно задокументировал противоречие в Javadoc метода (см. `VoxelStorage.java`) и
здесь — по инструкции сессионного промпта, при обнаруженном противоречии в тексте
тикета нужно остановиться и сообщить, а не додумывать сигнатуру. Требуется решение
пользователя/следующей сессии: либо изменить тип параметра на `int` (тогда это
отклонение от буквального текста тикета, но самое вероятное намерение автора плана),
либо ввести отдельный instantiable value-класс `DirtyFlags` (record/wrapper над `int`),
что было бы более крупным изменением контракта. До этого решения `VoxelStorage`
компилируется, но `markDirty` практически невызываем.

Тесты (`ev-api/src/test/java/dev/ev/api/storage/`): `StorageMetricsTest`
(`hitRate()` — 0/0 даёт 0.0 не NaN, обычное отношение, все hits, все misses) и
`DirtyFlagsTest` (`has()` — DEFAULT содержит BLOCK_CHANGED и CHILD_EXISTENCE_CHANGED, но
не SKIP_PERSIST; произвольная комбинация флагов; нулевые флаги). Оба явно допущены
текстом тикета ("если тесты нужны для record'ов... допустимо добавить простой юнит-тест").

**Не подтверждено фактическим прогоном** — та же причина, что и в тикете 01 (нет
Gradle/`javac` в песочнице, см. секцию "Сборка/тесты" в PROJECT_INDEX.md).

### 03-api-meshing-interfaces — [x] готово

Создан пакет `ev-api/src/main/java/dev/ev/api/meshing/` дословно по контракту тикета:
`MeshingContext` (интерфейс, `getNeighborBoundaryVoxel`/`resolveMaterialId`), `Quad`
(record, axis-aligned quad в section-local integer координатах), `Meshlet` (record,
bounded batch quad'ов + bounding box, конструктор бросает `IllegalArgumentException` при
превышении `MeshletBatch.MAX_QUADS_PER_MESHLET`), `MeshletBatch` (record, `SectionPos` +
`List<Meshlet>`, `totalQuadCount()`), `MeshBuilder` (интерфейс, `build(WorldSectionHandle,
MeshingContext) -> MeshletBatch`). Никакой реализации — только контракты, реализация
стадий (occupancy/greedy-mesh/material-bin/meshlet-pack) — тикеты 10-13.

Заметка о порядке объявления: в тексте тикета `MeshletBatch` (использует `Meshlet`)
приведён раньше `Meshlet` в документе — это не проблема для Java (порядок классов в
пакете не важен для компиляции), файлы созданы в порядке, естественном для чтения
(`MeshingContext` → `Quad` → `Meshlet` → `MeshletBatch` → `MeshBuilder`).

Тесты: `MeshletTest` (обязателен критериями приёмки — конструктор `Meshlet` бросает
`IllegalArgumentException` при `quads.size() > MAX_QUADS_PER_MESHLET`, плюс граничный
случай "ровно MAX" не бросает, и пустой список не бросает) и дополнительно
`MeshletBatchTest` (`totalQuadCount()` суммирует по нескольким meshlet'ам корректно,
включая случай пустого списка meshlet'ов) — последний не требуется явно текстом тикета,
но проверяет простую арифметику record'а по тому же принципу, что уже допущено для
`StorageMetrics`/`DirtyFlags` в тикете 02.

**Не подтверждено фактическим прогоном** — та же причина, что в тикетах 01-02 (нет
Gradle/`javac` в песочнице).

### 04-api-renderbackend-interfaces — [x] готово

Создан пакет `ev-api/src/main/java/dev/ev/api/gpu/` — 13 публичных top-level типов,
каждый в своём файле (Java требует один публичный top-level тип на файл, поэтому блоки
кода тикета, где несколько типов даны в одном фрагменте, разнесены по отдельным
файлам): `RenderBackend`, `BufferUsage`, `GpuBuffer`, `TextureDesc`, `TextureFormat`,
`GpuTexture`, `ShaderSource`, `PipelineLayout`, `ComputePipeline`, `GraphicsPipeline`,
`CommandList`, `BarrierScope`, `FenceHandle`. Все сигнатуры воспроизведены дословно по
контракту тикета. Enum'ам и interface'ам без исходного class-level Javadoc в тексте
тикета (`BufferUsage`, `GpuBuffer`, `GpuTexture`, `ComputePipeline`, `GraphicsPipeline`,
`CommandList`, `BarrierScope`) добавлен краткий class-level Javadoc от себя, так как
у каждого public-типа он требуется общими договорённостями проекта (EV_SESSION_PROMPT.md:
"Javadoc на английском для каждого публичного класса/метода") — метод-level Javadoc,
данный в тексте тикета дословно, сохранён без изменений везде, где он был.

Проверено: `grep -r "import"` по всему пакету `gpu` — только `java.util.Map` (в
`ShaderSource`/`PipelineLayout`), ни одного `org.lwjgl.*`/NeoForge/Minecraft импорта.

**Пункт 4 требований тикета** ("если обнаружишь нехватку метода в RenderBackend/
CommandList для persistent-kernel traversal — добавь его") — оставлен без действия
сознательно, не по невнимательности: тикет `21-gpu-persistent-traversal-shader-opt.md`
(opt-волна) ещё не выполнялся, у меня нет данных/опыта проектирования этой конкретной
реализации, чтобы обоснованно утверждать, что текущего контракта не хватает. Добавление
гипотетических методов "на всякий случай" без реального проектирования — это ровно то
забегание вперёд в сложность, которое EV_SESSION_PROMPT.md явно запрещает ("не
реализуй заодно более сложную версию, даже если кажется, что она лучше"). Если при
выполнении тикета 21-opt обнаружится нехватка — это будет явное дополнение контракта в
рамках того тикета, а не задним числом здесь.

Тесты: `ShaderSourceAndPipelineLayoutTest` (явно допущено критериями приёмки —
`ShaderSource`/`PipelineLayout` возвращают переданные в конструктор значения;
дополнительно проверена immutability `Map.of(...)`-карт, которые естественно
использовать при построении этих record'ов, — `UnsupportedOperationException` при
попытке мутации) и `TextureDescTest` (`texture2D()` корректно проставляет `depth=1`).

**Не подтверждено фактическим прогоном** — та же причина, что в тикетах 01-03 (нет
Gradle/`javac` в песочнице).

### 05-api-metrics-interfaces — [x] готово

Создан пакет `ev-api/src/main/java/dev/ev/api/metrics/` дословно по контракту тикета:
`MetricsRegistry` (интерфейс — `recordQueueDepth`, `recordCacheAccess`,
`recordGpuPassDuration`, `recordCounter`, `recordImportStageStatus`, `snapshot()`),
`MetricsSnapshot` (record — 4 карты метрик + `ImportStageStatus`, `empty()` factory),
`ImportStageStatus` (record — staged breakdown холодного старта: queuedForRead/
retryingAfterFailure/activelyBuilding/completed/totalKnown, `empty()`,
`completionFraction()`). Только контракты, реализация `MetricsRegistry` осознанно не
писалась — тикет явно требует оставить это интерфейсом (реализация не входит в объём
тикета 05, появится либо отдельным будущим тикетом, либо будет встроена в 27/29).

Это последний из пяти api-тикетов (01-05) — весь модуль `ev-api` теперь укомплектован
контрактами: `SectionPos`, storage (`VoxelStorage`/`DirtyFlags`/`WorldSectionHandle`/
`StorageMetrics`), meshing (`MeshingContext`/`Quad`/`Meshlet`/`MeshletBatch`/
`MeshBuilder`), gpu (13 типов во главе с `RenderBackend`), metrics (3 типа выше).
Порядок выполнения по MVP_INDEX.md дальше переходит к тикетам 06-07
(`ev-storage`: palette-codec, schema-migration) — первым тикетам с реальной логикой, а
не только контрактами.

Проверено: `grep -r "import"` по пакету `metrics` — только `java.util.Map`, ни одного
`org.lwjgl.*`/NeoForge-импорта.

Тесты (оба явно требуются критериями приёмки 3 и 4 тикета): `MetricsSnapshotTest`
(`empty()` — все 4 карты non-null и пустые, `importStageStatus()` равен
`ImportStageStatus.empty()`) и `ImportStageStatusTest` (`completionFraction()` — 0.0 при
`totalKnown=0` не NaN, корректная дробь `50/200=0.25`, `empty()` даёт 0.0, полное
завершение даёт 1.0).

**Не подтверждено фактическим прогоном** — та же причина, что в тикетах 01-04 (нет
Gradle/`javac` в песочнице). Как и раньше, следующая сессия с обычным доступом в
интернет должна первым делом прогнать `./gradlew :ev-api:build` (или хотя бы
`:ev-api:test`) — весь модуль `ev-api` теперь достаточно велик (5 тикетов, 21
production-класс, 10 тестовых классов), чтобы имело смысл проверить его целиком одним
прогоном, а не тикет за тикетом.

### Изменение процесса — обязательная выдача архива после каждой задачи

По явному указанию пользователя, в `EV_SESSION_PROMPT.md` добавлен пункт 7 в раздел
"Как работать над тикетом" и отдельный раздел "Обязательная выдача архива после каждой
задачи": теперь любая сессия, работающая по этому промпту, обязана после завершения
каждого тикета (без напоминания и без явной просьбы в конкретном сообщении) собирать
`.tar.gz` со всеми файлами, изменёнными/созданными с начала сессии, и предоставлять его
пользователю через `present_files`. Это не тикет из основного плана (00-32/P0), а
постоянное правило процесса, зафиксированное в системном промпте сессии, поэтому
запись здесь — не статус тикета, а фиксация факта изменения инструкции.
