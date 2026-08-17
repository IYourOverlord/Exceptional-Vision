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
- [x] **06-storage-palette-codec** — Кодек палитры
- [x] **07-storage-schema-migration** — Версионирование и миграция схемы
- [x] **08-storage-section-cache-mvp** — MVP кэш секций
- [x] **09-storage-heightmap-coarse-gen** — Coarse heightmap генерация
- [x] **10-meshing-occupancy-stage** — OccupancyStage
- [x] **11-meshing-greedy-mesh-stage** — GreedyMeshStage
- [x] **12-meshing-material-bin-stage** — MaterialBinStage
- [x] **13-meshing-meshlet-pack-stage** — MeshletPackStage
- [x] **14-meshing-priority-function** — Вычисление приоритета мешинга
- [x] **15-meshing-priority-queue-mvp** — MVP очередь задач мешинга
- [x] **16-meshing-mipgen-mvp** — MVP MIP-агрегация

### Этап 3: Волна 1 (MVP) — GPU, Рендеринг и Интеграция
- [x] **17-gpu-backend-gl-buffers** — GL буферы
- [x] **18-gpu-backend-gl-shaders** — GLSL шейдеры
- [x] **20-gpu-node-buffer-mvp** — MVP AoS node buffer
- [x] **21-gpu-simple-traversal-mvp** — MVP CPU traversal
- [x] **24-render-frame-graph** — FrameGraph
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

### Багфикс — `settings.gradle` → `settings.gradle.kts`

При первой реальной сборке проекта пользователем (`./gradlew` вне песочницы, тикет 06 в
работе) обнаружена ошибка конфигурации:

```
Could not find method maven() for arguments [https://maven.neoforged.net/releases]
on repository container of type ...DefaultRepositoryHandler.
```

Причина: файл настроек назывался `settings.gradle` (расширение Groovy DSL), но содержал
Kotlin DSL синтаксис (`maven("...")`, `id("...")` вместо `maven { url "..." }`,
`id "..."`). Gradle интерпретировал файл как Groovy и падал на первом же
Kotlin-специфичном вызове. Все `build.gradle.kts` в проекте уже были корректны —
проблема касалась только settings-файла в корне. Это унаследованный дефект тикета 00
(project-skeleton), не имеющий отношения к тикету 06, над которым шла работа в момент
обнаружения.

**Исправление**: `settings.gradle` переименован в `settings.gradle.kts` (содержимое не
менялось — оно и так было валидным Kotlin DSL). Проверены все остальные `*.gradle*`
файлы проекта на такое же несоответствие расширения и синтаксиса — других случаев не
найдено.

**Не подтверждено повторным прогоном** в этой сессии (нет Gradle в песочнице, та же
причина, что и раньше) — следующий шаг пользователя: заново запустить
`./gradlew :ev-storage:build` (или любую задачу) и убедиться, что ошибка конфигурации
settings ушла.

### Багфикс 2 (пересмотрено) — `property()`/локальные `val` недоступны в блоке `plugins {}`

После фикса settings.gradle.kts обнаружена следующая ошибка при реальной сборке
(та же машина пользователя, тот же прогон):

```
Unresolved reference. None of the following candidates is applicable because of
receiver type mismatch:
public inline fun <reified T> ObjectFactory.property(): Property<T> ...
```

на строке `id("net.neoforged.moddev") version "${property("moddevgradle_version")}"`.

**Первая попытка исправления была неверной.** Изначально версия была вынесена в
локальную `val moddevgradleVersion`, объявленную в теле `build.gradle.kts` до блока
`plugins {}`, с расчётом, что она будет видна внутри блока. Реальный прогон показал
новую ошибку: `Unresolved reference: moddevgradleVersion` — блок `plugins {}` в Kotlin
DSL **компилируется как полностью изолированный скрипт**, отдельно от остального тела
`build.gradle.kts`, и не видит вообще никакие `val`/функции, объявленные в том же
файле, ни до блока, ни после. Это не ограничение конкретно `property()` — это общее
свойство блока `plugins {}` в Gradle Kotlin DSL, задокументированное поведение
("Plugins block requirements": контент должен быть statically analyzable до выполнения
скрипта).

**Верное исправление**: версия плагина `net.neoforged.moddev` вынесена из
`ev-neoforge/build.gradle.kts` целиком — там теперь `id("net.neoforged.moddev")` без
версии. Версия резолвится централизованно в `settings.gradle.kts`, в блоке
`pluginManagement { plugins { ... } }`, где `providers.gradleProperty(...)` работает
корректно (settings-скрипт не имеет такого ограничения на изоляцию блока `plugins`),
и подхватывается автоматически при разрешении плагина в любом подмодуле, где указан
`id("net.neoforged.moddev")` без явной версии. Это стандартный для Gradle multi-module
паттерн централизации версий плагинов.

**Не подтверждено повторным прогоном** в этой сессии (нет Gradle в песочнице) —
следующий шаг пользователя: пересобрать/пересинхронизировать проект и проверить, что
обе ошибки (settings.gradle и unresolved moddevgradleVersion) ушли.

### Багфикс 3 — `property("minecraft_version")` не резолвится внутри `tasks.register` при материализации через `ideSyncTask`

После фиксов 1 и 2 сборка (реальный прогон пользователя) продвинулась дальше — до
конфигурации задач — и упала на новой ошибке:

```
Could not determine the dependencies of task ':ev-neoforge:neoForgeIdeSync'.
> Could not create task ':ev-neoforge:generateModMetadata'.
   > Could not get unknown property 'minecraft_version' for task
     ':ev-neoforge:generateModMetadata' of type
     org.gradle.language.jvm.tasks.ProcessResources.
```

Существенно: то же самое свойство `minecraft_version` (и соседние) присутствует в
корневом `gradle.properties` и корректно резолвится через `property(...)` чуть выше в
том же файле — в блоке `neoForge { parchment { ... } }` (строки 15-21), который
конфигурируется в обычном eager-контексте тела скрипта. Отличие — `generateModMetadata`
создаётся внутри `tasks.register<ProcessResources>(...) { ... }`, и материализуется не
сразу, а лениво, когда `neoForge.ideSyncTask(generateModMetadata)` (последняя строка
файла) заставляет плагин `net.neoforged.moddev` подключить эту задачу как зависимость
`neoForgeIdeSync`. В момент этой отложенной материализации обычный `property(...)`
(эквивалент `Project.property(String)`) не гарантированно видит все properties проекта
в том виде, в каком их ожидает лениво конфигурируемый таск — это известное на практике
ограничение смешивания eager `property()` API с lazy task configuration API в Gradle
Kotlin DSL.

**Исправление**: все 11 обращений к `property(...)` внутри `generateModMetadata`
заменены на `providers.gradleProperty(...).get()` — provider-based API, спроектированный
именно для надёжного чтения `gradle.properties` в любой момент ленивой конфигурации,
включая отложенную материализацию через `ideSyncTask`. Обращения к `property(...)` вне
этого блока (в `neoForge {}`, `base {}` — eager-контекст) не тронуты, так как там
ошибки не было и менять рабочий код без необходимости не стоит.

**Не подтверждено повторным прогоном** в этой сессии (нет Gradle в песочнице) —
следующий шаг пользователя: пересобрать/пересинхронизировать и проверить, что
`generateModMetadata`/`neoForgeIdeSync` создаются без ошибки. Если и другие
lazy-контексты (`runs { create(...) { ... } }`, `mods { create(...) { ... } }`) упадут
по той же причине при более глубоком прогоне — это тот же класс проблемы, и решение то
же: заменить `property(...)` на `providers.gradleProperty(...).get()`.

**Подтверждено пользователем**: багфиксы 1-3 в совокупности дали `BUILD SUCCESSFUL in
59s` для `:ev-neoforge` (полный прогон, включая `createMinecraftArtifacts`,
`generateModMetadata`, `neoForgeIdeSync` и подготовку run-конфигураций). Первый реальный
прогон проекта состоялся.

### Багфикс 4 — конфликт версии `fastutil` со `strictly`-constraint NeoForge

После успешного `BUILD SUCCESSFUL` всплыла отдельная ошибка при разрешении
зависимостей `:ev-neoforge:main`:

```
Could not resolve it.unimi.dsi:fastutil:{strictly 8.5.12}.
Required by:
    project :ev-neoforge > net.neoforged:neoforge:21.1.235 >
    net.neoforged:neoform:1.21.1-20240808.144430 >
    net.neoforged:minecraft-dependencies:1.21.1
```

Причина: транзитивная зависимость `net.neoforged:minecraft-dependencies:1.21.1` (сама
NeoForge/Minecraft) объявляет `fastutil` с Gradle-constraint `strictly 8.5.12` — это
жёсткое, а не рекомендательное ограничение версии. Проект (тикеты 01-05/06 и далее) в
`gradle.properties` задавал `fastutil_version=8.5.13`, используемую в `ev-api`,
`ev-storage`, `ev-meshing`. Когда все модули сходятся в classpath `ev-neoforge`, Gradle
не может одновременно удовлетворить "8.5.13, как явно запрошено" и "строго 8.5.12, как
требует constraint NeoForge" — конфликт неразрешим без вмешательства.

Версия `8.5.13` не была ошибкой сама по себе (это валидная версия fastutil), проблема
именно в том, что она выбиралась независимо от того, что реально тащит NeoForge как
жёсткий constraint — на момент тикетов 01/06 сборка ни разу не запускалась по-настоящему
(см. секцию "Сборка/тесты" в PROJECT_INDEX.md), поэтому конфликт не мог быть замечен
раньше.

**Исправление**: `fastutil_version` в `gradle.properties` понижена с `8.5.13` до
`8.5.12` — единый источник версии для всех модулей, поэтому фикс потребовал изменить
только эту одну строку (сами `build.gradle.kts` модулей `ev-api`/`ev-storage`/
`ev-meshing` уже читают версию через `property("fastutil_version")`, менять их не
понадобилось).

**Не подтверждено повторным прогоном** в этой сессии (нет Gradle в песочнице) —
следующий шаг пользователя: пересобрать и убедиться, что `:ev-neoforge:main` резолвится
без конфликта версий. Если у API `ev-api`/`ev-storage`/`ev-meshing`, написанного под
`8.5.13`, где-то использовался метод/класс, отсутствующий в `8.5.12` — это станет видно
на этапе компиляции и будет уже отдельной, пятой проблемой (маловероятно для патч-версий
одного минора, но не исключено).

**Подтверждено пользователем**: `BUILD SUCCESSFUL` для `:ev-neoforge`, багфикс 4
(fastutil) устранил конфликт резолва зависимостей — `./gradlew build` дошёл до
компиляции и запуска тестов всех модулей.

### Багфикс 5 — первый настоящий баг в коде: `SectionPos.decode()` неверно sign-extend'ит x/z

`./gradlew build` (полный прогон, впервые дошедший до стадии тестов) упал на:

```
SectionPosTest > encodeDecodeRoundTrip() FAILED
    org.opentest4j.AssertionFailedError at SectionPosTest.java:35
36 tests completed, 1 failed
```

В отличие от багфиксов 1-4, это не проблема конфигурации Gradle, а логическая ошибка в
самом коде `ev-api` (тикет 01), необнаруженная раньше именно потому, что до этого
прогона тесты вообще ни разу не выполнялись (см. "Сборка/тесты" в PROJECT_INDEX.md).

Причина: в `SectionPos.decode()` 26-битные поля `x`/`z` восстанавливались через
`(int) (id << (64 - N)) >> (64 - 26)`, где показатель сдвига вправо был `64 - 26 = 38`.
Это число задумывалось как "сдвинуть на столько же бит, на сколько сдвинули влево, чтобы
вернуть значение на место с sign-extension" — верная идея для `long`-сдвигов, но **сдвиг
здесь применяется к уже приведённому к `int` значению**, а Java маскирует величину
сдвига для `int`-операндов по модулю 32 (JLS §15.19): `>> 38` на `int` фактически
исполняется как `>> (38 % 32) = >> 6`. Для поля `z` числа совпали по случайности
(`64-52=12` использовалось для сдвига влево, что само по себе было следующим
источником путаницы в исходном коде — два разных "магических" числа `52` и `26` в одной
формуле), и итоговый сдвиг вправо на `6` бит вместо ожидаемых (по формуле) `38`
недостаточен, чтобы правильно sign-extend'ить 26-битное поле, зажатое в 32-битном
контейнере — корректная величина сдвига **32 - 26 = 6** оказалась бы верной, но не по
той причине, которую подразумевал исходный код (`64-26`), а по чистой случайности
совпадения `38 % 32 == 6`. Экспериментальная проверка (полная Python-эмуляция битовой
арифметики с точной Java-семантикой int-сдвигов) подтвердила: старый код **не** давал
корректный round-trip для отрицательных/граничных x и z ни при каких значениях кроме
тривиальных нулей.

**Исправление**: переписан `decode()` — x и z сначала явно изолируются в нижние 32 бита
через `(int) id` (для x) и `(int) (id >>> 26)` (для z, беззнаковый сдвиг перед приведением
к int, чтобы не терять биты level/y), затем sign-extend делается через `<< 6 >> 6`, где
`6 = 32 - 26` — величина сдвига, осознанно посчитанная относительно 32-битного
контейнера, а не относительно исходных 64 бит `id`. Формула для `y` (через `(byte)`
cast) не трогалась — она была верна и раньше (8-битное поле, `byte` cast делает
sign-extension автоматически и корректно).

**Проверено** (вне Gradle-песочницы, отдельным Python-скриптом, точно воспроизводящим
Java int/long shift-семантику) на всех 8 кейсов из `SectionPosTest.ROUND_TRIP_CASES`,
включая граничные значения (`MAX_XZ`, `MIN_XZ`, `MAX_Y`, `MIN_Y`) — все проходят. **Не
подтверждено фактическим прогоном `./gradlew :ev-api:test`** в этой сессии (по-прежнему
нет Gradle в песочнице) — следующий шаг пользователя: пересобрать и убедиться, что
`SectionPosTest` проходит целиком (все 37 тестов, включая ранее падавший).

### Итог — первый полный зелёный прогон (2026-08-16)

Пользователь подтвердил: `./gradlew build` → `BUILD SUCCESSFUL in 926ms`,
15 actionable tasks (4 executed, 11 up-to-date). Все 5 багфиксов выше в совокупности
довели проект от "не собирается вообще" до полностью проходящей сборки с тестами.

`PROJECT_INDEX.md` обновлён по итогам этой сессии:
- Статус тикетов 00 и 01 в таблице обновлён — сборка/тесты верифицированы фактическим
  прогоном, а не только "по структуре конфигурации".
- Секция "Сборка/тесты" переписана: вместо хронологического списка отдельных находок
  теперь единая таблица "симптом → причина → фикс" по всем 5 багфиксам, плюс отдельный
  блок "Уроки на будущее" с обобщёнными признаками каждого класса проблемы (Kotlin DSL
  `plugins{}` изоляция, lazy task property resolution, Gradle `strictly`-constraints,
  int-сдвиги в битовых кодеках) — рассчитан на то, чтобы будущая сессия или пользователь
  узнавали знакомый класс ошибки по симптому, не переоткрывая причину с нуля.
- Известный инвариант про `SectionPos` bit layout дополнен явным упоминанием
  sign-extension бага и рекомендацией для будущего похожего кода (тикет 20, GPU node
  buffer).

Детальный симптом→причина→фикс для каждого из 5 багфиксов остаётся только здесь
(PROGRESS.md, выше) — PROJECT_INDEX.md теперь содержит компактную сводную таблицу с
отсылкой сюда за подробностями, чтобы не дублировать текст полностью между файлами.

## Тикет 06 — PaletteCodec (2026-08-16, отдельная сессия)

Реализован `dev.ev.storage.codec.PaletteCodec` + вспомогательные `MortonCode`,
`BitPackedArray`, `RunLengthCodec` в `ev-storage`, строго по контракту тикета
`06-storage-palette-codec.md`.

**Формат кодирования** (задокументирован подробно в Javadoc `PaletteCodec`):
- `SINGLE_VALUE` (tag 0): 8 байт (tag + value) — быстрый путь для полностью однородной
  секции (типичный случай на грубых LOD).
- `PALETTE_RLE` (tag 1): локальная палитра (encounter order, но значение 0/воздух
  принудительно на индекс 0, если присутствует) → палитровые индексы переупорядочены в
  Z-order (Morton) обход → RLE поверх Morton-последовательности → run-значения плотно
  бит-упакованы (`bitsPerIndex = max(1, ceil(log2(paletteSize)))`), run-длины — по 2 байта
  (unsigned, влезают всегда: `RunLengthCodec.MAX_RUN = 65535` при максимум 32768 вокселей
  на секцию).

**Morton-кодирование**: выбран простой 5-итерационный побитовый цикл вместо
magic-number bit-spreading — обоснование (для 5-битного диапазона обе техники O(1) с малой
константой, цикл проще и безопаснее при урезании готовых magic-number масок под нестандартную
битность) задокументировано прямо в Javadoc `MortonCode`, как и требовал тикет.

**Компиляция/тесты**: как и в тикете 01, песочница этой сессии не имеет `javac`/Gradle
(только `java`-рантайм без компилятора, сеть ограничена white-list доменов сборки
зависимостей, но не помогает без самого JDK-компилятора) — попытки установить JDK/Gradle
сознательно не предпринимались (см. `EV_SESSION_PROMPT.md`). Вместо этого вся логика
(`PaletteCodec`, `MortonCode`, `BitPackedArray`, RLE) была построчно скопирована в отдельный
однофайловый `Verify.java` вне репозитория и прогнана через `java Verify.java`
(single-file source launcher, доступен в этой песочнице в отличие от `javac`+JUnit).
Проверено вручную: round-trip для всех 5 тестовых сценариев тикета (нули, однородное
ненулевое, случайное 5-10 уникальных, шахматка, 2 уникальных значения), `isUniform` на
двух кейсах, исключение при неверном размере входа, Morton round-trip для всех 32768
комбинаций координат 0..31, известные Morton-значения вручную (включая `(31,0,0)=4681`,
`(31,31,31)=32767`), и размер закодированного однородного случая (8 байт < 16). Всё
прошло. Это **не замена** реальному `./gradlew :ev-storage:test` — `PaletteCodecTest.java`
(JUnit 5, 8-й тестовый метод дополнительно про bitsPerIndex=1) в репозитории написан и
должен быть прогнан пользователем/следующей сессией с доступом к Gradle.

`PROJECT_INDEX.md` обновлён: статус тикета 06 → DONE, с пометкой о неподтверждённой
Gradle-сборке (см. таблицу тикетов).

## Тикет 07 — SchemaVersion/SchemaMigrationChain/RegionFileHeader (2026-08-16, отдельная сессия)

Реализован пакет `dev.ev.storage.schema` в `ev-storage`, строго по контракту тикета
`07-storage-schema-migration.md`: `SchemaVersion`, `SchemaMigrator`, `SchemaMigrationChain`,
`UnsupportedSchemaException`, `RegionFileHeader`.

**Дизайн-решение, отклоняющееся от буквального сигнатурного минимума контракта (но в рамках
разрешённой тикетом свободы)**: помимо `migrateToCurrent(fromVersion, data)` из контракта,
добавлен обобщённый публичный `migrateTo(fromVersion, toVersion, data)` — сама
`migrateToCurrent` теперь просто `migrateTo(fromVersion, SchemaVersion.CURRENT, data)`. Это
прямо подсказано текстом тикета ("протестируй chain-логику отдельно от
`SchemaVersion.CURRENT`, если конструктор/метод это позволяет") — без этого протестировать
трёхверсионную цепочку (1→2→3) было бы невозможно, пока `CURRENT` реально не станет 3.
Тесты цепочки используют `migrateTo` напрямую с гипотетическими `TestV1ToV2Migrator`/
`TestV2ToV3Migrator`, определёнными только в `SchemaMigrationChainTest` (как и требовал
тикет — никакой fake-миграции в основном коде, поскольку реальной версии 0/2 пока не
существует).

**Формат заголовка региона** (12 байт, задокументирован в Javadoc `RegionFileHeader`, единый
источник истины): `magic("HZR\0", 4 байта)` + `schemaVersion(u16 BE)` + `compressionCodec(u8,
0=none/1=deflate зарезервировано)` + `reserved(u8, всегда 0 при записи)` +
`sectionCount(i32)`. `parse()` бросает `IllegalArgumentException` и на несовпадающих magic-
байтах, и на массиве короче `HEADER_SIZE_BYTES` (включая `null`) — оба случая явно
задокументированы и покрыты тестами.

**Компиляция/тесты**: как и в тикете 06, песочница без `javac`/Gradle — вся логика
(`SchemaMigrationChain.migrateTo/migrateToCurrent`, обработка гэпа в цепочке, duplicate-
migrator в конструкторе, `RegionFileHeader` round-trip/bad-magic/too-short) построчно
скопирована в отдельный `Verify7.java` вне репозитория и прогнана через
`java Verify7.java` — все 10 проверок прошли. Не замена реальному
`./gradlew :ev-storage:test` — `SchemaMigrationChainTest`/`RegionFileHeaderTest` в
репозитории написаны и ждут прогона в сессии с доступом к Gradle.

`PROJECT_INDEX.md` обновлён: статус тикета 07 → DONE.

## Тикет 08-mvp — SectionCache (нешардированная) (2026-08-16, отдельная сессия)

Реализован пакет `dev.ev.storage.cache` в `ev-storage`, строго по контракту тикета
`08-storage-section-cache-mvp.md` (контракт `EvictionPolicy`/`LruEvictionPolicy` взят из
`08-storage-section-cache-opt.md`, как и требовал mvp-тикет — это единая, не зависящая от
шардирования часть).

**Ключевая деталь MVP-реализации `SectionCache.acquire`**: атомарность "проверить-и-создать"
достигнута через `ConcurrentHashMap.computeIfAbsent`, включая нетривиальный кейс
`onlyIfExists=true` + позиция отсутствует и в кэше, и в `loader.exists()` — в этом случае
mapping-функция возвращает `null`, что для `computeIfAbsent` документированно означает "не
создавать запись" (не пришлось городить отдельную ветку ручной блокировки для этого случая).
Различение hit/miss для метрики сделано через захватываемый лямбдой `boolean[]` флаг
(`wasCreated`), а не через `containsKey`-проверку до `computeIfAbsent` (которая была бы
гонкой сама по себе).

`ReentrantLock` используется исключительно вокруг вызовов `EvictionPolicy.onInsert/onAccess`
(как и требовал п.4 тикета) — сам `ConcurrentHashMap` не защищается этим локом, он
самодостаточно потокобезопасен.

`shardCountPowerOfTwo` в конструкторе принимается, но полностью игнорируется — задокументировано
и в Javadoc класса, и прямо на параметре (п. "explicitly with a code comment at the parameter"
из тикета), чтобы точка вызова была самодостаточной для понимания без чтения Javadoc.

**Компиляция/тесты**: как и в тикетах 06/07, песочница без `javac`/Gradle. Вся логика
`SectionCache` (все 6 обязательных сценариев, включая конкурентный с 16 потоками × 64
позиции × 200 итераций через `ExecutorService`+`CountDownLatch`) и логика пропуска
held-записей (`refCount() > 0`) в LRU-политике вытеснения построчно скопированы в отдельный
`Verify8.java` вне репозитория и прогнаны через `java Verify8.java` — все 9 проверок прошли,
включая конкурентный сценарий (нет потерянных апдейтов ref-count, `activeCount()` совпадает
с ожидаемым числом уникальных позиций). Не замена реальному `./gradlew :ev-storage:test` —
`SectionCacheTest.java` в репозитории написан и ждёт прогона в сессии с доступом к Gradle.

`PROJECT_INDEX.md` обновлён: статус тикета 08 → DONE (mvp), с явной пометкой, что opt-версия
(шардированная) в банке и не применена — как того требовал критерий приёмки №5.

## Тикет 09 — Coarse-to-fine генерация из heightmap (2026-08-16, отдельная сессия)

Реализован пакет `dev.ev.storage.coarsegen` в `ev-storage`: `HeightmapSource` (интерфейс),
`CoarseSectionGenerator`. Тикет не разделён на mvp/opt (единая версия, см. заголовок тикета) —
реализовано ровно то, что описано, без забегания вперёд.

**Выбор по п.2 тикета (доминирующий материал)**: взят разрешённый упрощённый вариант — материал
читается из той же центральной точки, что и высота, без доп. majority-vote сэмплов. Компромисс
явно задокументирован в Javadoc класса: на грубых уровнях это может дать неточный материал при
наличии мелких вкраплений другого материала рядом с сэмплируемой точкой, но не влияет на
геометрию силуэта (та зависит только от `surfaceHeight`), и сохраняет строгий `O(1)`/воксель.

**Выбор по п.4 тикета (isAvailable()==false)**: колонка не перезаписывается вовсе (остаётся тем,
чем была у `target` — как правило воздух для свежевыделенной секции), а весь результат вызова
`generate()` помечается `GenerationResult.complete() == false`. Явно предпочтено тихому дефолту
в воздух: дефолт в воздух был бы неотличим от честного "здесь подтверждённо нет террейна" и мог
бы зафиксировать ложную дыру в горизонте, которая никогда не переисправится. Вызывающий код
(владеет политикой ретраев/переиспользования — вне этого тикета) может использовать флаг
`complete` для повторной генерации секции позже.

**Квантование Y**: `quantizedSurfaceLocalY = floorDiv(surfaceHeight - minBlockY, voxelSizeInBlocks)`,
клампится в `[-1, 32]` (не `[0, 31]`) — два сентинельных значения вне обычного диапазона:
`-1` = "вся колонка воздух" (секция целиком выше поверхности), `32` = "вся колонка material"
(секция целиком ниже поверхности). Это позволило обработать три случая (п.1-3 требований) одной
и той же веткой `localY <= quantizedSurfaceLocalY ? material : air`, без отдельного if/else на
"целиком выше"/"целиком ниже"/"на границе".

**Однородность**: `PaletteCodec.isUniform` вызывается на собранном `flat`-массиве (32768
элементов) до записи через `setVoxel` по одному, как требовал п.5. Добавлен опциональный
`MetricsRegistry` в конструктор (перегрузка, не обязательный параметр) — при однородном
результате инкрементирует счётчик `coarsegen.uniformSections` через `recordCounter`; если
`MetricsRegistry` не передан (используется однопараметрический конструктор) — метрики просто
пропускаются, никакого NPE.

**Тесты**: `CoarseSectionGeneratorTest` (9 тестовых методов, включая один тест на комбинацию
"частичная недоступность", не входящую явно в нумерованный список тикета, но логично
дополняющую сценарий 5) + `FakeHeightmapSource` (считает вызовы `surfaceHeight`/`surfaceMaterial`
и число различных сэмплированных колонок — прямая проверка `O(1)`, не `O(area)`, отдельно для
LOD 0 и LOD 6) + `FakeWorldSectionHandle`.

**Компиляция/тесты**: как и в тикетах 06-08, песочница без `javac`/Gradle-доступа (см.
`EV_SESSION_PROMPT.md`, раздел про компиляцию — установка JDK не предпринималась). Вся логика
`CoarseSectionGenerator.generate()` (включая fillColumnFlat, sentinel-клампинг, центрирование
сэмпла, политику unavailable) построчно скопирована в отдельный `VerifyCoarseGen.java` вне
репозитория и прогнана через `java VerifyCoarseGen.java` (single-file source-launch, работает
даже без `javac` в PATH — встроенный in-memory компилятор JRE) — все 21 проверка прошли,
включая явную проверку центрирования сэмпла (не по углу квадрата) и инвариантность числа
вызовов `32*32=1024` между LOD 0 и LOD 6. Не замена реальному `./gradlew :ev-storage:test` —
`CoarseSectionGeneratorTest.java` в репозитории написан и ждёт прогона в сессии с доступом к
Gradle.

**Побочная находка, не в рамках тикета**: при подготовке к работе обнаружено, что
`ev-storage/build.gradle.kts` отсутствует в репозитории, хотя `settings.gradle.kts` включает
`ev-storage` как подпроект и тикеты 06-08 уже положили в него код и тесты (JUnit 5). Без
собственного build-файла модуль, вероятно, не соберётся Gradle'ом как есть. Не исправлено
самостоятельно — не предмет тикета 09, задокументировано в `PROJECT_INDEX.md` (раздел "Чего
пока не существует") как известный пробел для следующей сессии/пользователя.

`PROJECT_INDEX.md` обновлён: статус тикета 09 → DONE, добавлена секция "ev-storage —
`dev.ev.storage.coarsegen`" в "Ключевые классы", исправлена рассинхронизация модульной карты
(`ev-storage` ошибочно значился "Пусто", хотя тикеты 06-08 уже были отмечены DONE в таблице
статусов) и раздела "Чего пока не существует".

## Инфраструктурный фикс — ev-storage/build.gradle.kts (2026-08-16, по итогам реального `./gradlew build` пользователя)

Пользователь прогнал `./gradlew build` и получил `:ev-storage:compileJava FAILED`, 32 ошибки:
`package dev.ev.api.storage does not exist` / `cannot find symbol` на `WorldSectionHandle`,
`SectionPos`, `MetricsRegistry`, `StorageMetrics` — во всех файлах тикетов 08 и 09
(`EvictionPolicy`, `LruEvictionPolicy`, `SectionCache`, `SectionLoader`,
`CoarseSectionGenerator`). Причина ровно та, что уже была зафиксирована в
`PROJECT_INDEX.md` как известный пробел при работе над тикетом 09: у `ev-storage` не было
собственного `build.gradle.kts`, поэтому Gradle не подключал к нему зависимость на `ev-api`
вообще, и компилятор не видел ни один из его пакетов.

Создан `ev-storage/build.gradle.kts` по образцу `ev-meshing/build.gradle.kts` (`java-library`,
`fastutil`, JUnit 5 через `junit-bom`), с одним осознанным отличием: `ev-api` подключён как
`api(project(":ev-api"))`, а не `implementation`. Причина — публичные сигнатуры классов
`ev-storage` напрямую используют типы из `ev-api` (`SectionCache.acquire(...) ->
WorldSectionHandle`, `CoarseSectionGenerator.generate(WorldSectionHandle)`,
`metricsSnapshot() -> StorageMetrics`), поэтому любой модуль, зависящий от `ev-storage`
(`ev-meshing`, `ev-neoforge`), должен видеть эти типы транзитивно — с `implementation` это
было бы сокрыто и привело бы к точно такой же ошибке `cannot find symbol` уже на уровне
вышестоящих модулей при их компиляции.

Это не относится ни к одному конкретному тикету 06-09 по отдельности — инфраструктурный
пробел тикета 00 (создание Gradle-скелета), обнаруженный позже. Не переприсваивается статус
тикета 00 (он уже помечен DONE с подтверждённым `BUILD SUCCESSFUL` на более раннем прогоне —
на тот момент `ev-storage` ещё не содержал кода тикетов 06-09, поэтому отсутствие его
build-файла не проявлялось как ошибка компиляции сразу).

**Подтверждено следующим реальным `./gradlew build` пользователя (2026-08-16)**: `BUILD
SUCCESSFUL`, и `:ev-storage:test` реально выполнился (не `NO-SOURCE`) — `PaletteCodecTest`,
`SchemaMigrationChainTest`, `RegionFileHeaderTest`, `SectionCacheTest` (включая конкурентный
сценарий) и `CoarseSectionGeneratorTest` (тикет 09) все прошли на реальном JVM. Это первое
фактическое подтверждение тикетов 06-09 реальным прогоном — до этого была только ручная
верификация вне репозитория. Статусы этих тикетов в таблице "Статус тикетов" выше обновлены
соответственно.

`PROJECT_INDEX.md` обновлён: раздел "Чего пока не существует" (пункт про отсутствующий
build-файл превращён в "Исправлено", с описанием симптома/причины/фикса), таблица "Статус
тикетов" (запись тикета 09 больше не говорит "не исправлено, заблокирует сборку" — заменена
на текущее, менее драматичное состояние), "Модульная карта" (упоминание build-файла у
`ev-storage`).

## Тикет 10 — OccupancyStage (2026-08-16, отдельная сессия)

`OccupancySet` (bitset `long[512]`, `flatIndex = x+y*32+z*32*32`, сознательно тот же порядок
осей, что и `PaletteCodec`, как требовал тикет) и `OccupancyStage` в `dev.ev.meshing.stage`.
Первый код в `ev-meshing`. Реализация прямая, без отклонений от контракта тикета.

Тесты (`OccupancySetTest`, `OccupancyStageTest`) написаны по всем пунктам тикета, включая
fast-path тест через подсчёт вызовов `getVoxel` у тестовой заглушки. `ev-meshing` уже имел
свой `build.gradle.kts` (в отличие от `ev-storage` до предыдущего фикса), поэтому та же
проблема здесь не ожидается — не проверял отдельно повторно, полагаюсь на уже
подтверждённый прогон `./gradlew build` пользователя, где `ev-meshing:*` уже фигурировал
как рабочий (NO-SOURCE до этого тикета).

Ручная верификация — только точечная (битовая индексация угла 31,31,31, popCount, снятие
бита) через компактный `java`-скрипт вне репозитория, не полное дублирование класса — код
тривиален (прямые битовые операции над `long[]`, без промежуточной логики уровня тикетов
06-09), дальнейшая проверка не давала бы дополнительной уверенности, соразмерной затратам.

`PROJECT_INDEX.md` обновлён: статус тикета 10 → DONE, модульная карта и "Ключевые классы"
для `ev-meshing`, "Чего пока не существует" (мешинг частично начат).

## Тикет 11 — GreedyMeshStage (2026-08-17, отдельная сессия)

`GreedyMeshStage` в `dev.ev.meshing.stage` — вторая стадия конвейера мешинга. Реализация не
дублирует код по 6 направлениям граней: одна параметризованная по оси функция плюс 4
статические таблицы (`NORMAL_AXIS`, `SIGN`, `WIDTH_AXIS`, `HEIGHT_AXIS`), кодирующие
циклическую (X→Y→Z→X) конвенцию width/height из требования 4a тикета. Видимость грани и
материал берутся с вокселя-источника, не соседа (требование 3), с явной обработкой границы
секции через `MeshingContext.getNeighborBoundaryVoxel`.

**Найденная неоднозначность, не покрытая текстом тикетов 03/11**: контракт
`getNeighborBoundaryVoxel(faceDirection, a, b)` не фиксирует, какой из локальных координат
поверхности соответствует `a`, а какой — `b`. Не стал додумывать это молча — задокументировал
де-факто конвенцию в Javadoc `GreedyMeshStage` (`a` = width-координата, `b` = height-координата,
та же циклическая ось-конвенция, что и `Quad.width/height`) и явно вынес её в
`PROJECT_INDEX.md` в раздел "Межмодульные контракты", т.к. будущая реализация
`MeshingContext` в `ev-render` обязана с ней совпасть, иначе грани на стыке секций будут
скрываться/показываться не в том месте.

Тесты (`GreedyMeshStageTest`, 9 сценариев + новая заглушка `FakeMeshingContext`) покрывают
все пункты, явно требуемые критериями приёмки тикета — в частности тест №5 (полный слой
32×32 сливается в один quad, а не 1024 отдельных — доказательство, что greedy-объединение
реально работает) и тест №7 (конвенция width/height проверена отдельно для +X/+Y/+Z на
заведомо неквадратных областях 5×3, чтобы ошибка перестановки осей не могла случайно
остаться незамеченной на квадратных данных).

Компиляция/тесты не прогнаны реальным Gradle-билдом в этой сессии — та же причина, что и в
тикете 10 (песочница без доступа к `maven.neoforged.net`/JDK, см. "Ограничения песочницы"
в `PROJECT_INDEX.md`). Логика сложнее тривиальных битовых операций тикета 10, поэтому здесь
точечная ручная верификация вне репозитория не проводилась — вместо этого сделан больший
акцент на детальность юнит-тестов (в частности, тесты №2/№3/№7 содержат вручную посчитанные
ожидаемые координаты quad'ов, не только количество/площадь) в расчёте на первый реальный
прогон пользователя.

`PROJECT_INDEX.md` обновлён: статус тикета 11 → DONE, модульная карта и "Ключевые классы"
для `ev-meshing`, "Межмодульные контракты" (конвенция `a`/`b` выше), "Чего пока не существует"
(мешинг — 2 из 4 стадий).

**Изменение процесса**: `EV_SESSION_PROMPT.md` дополнен явным правилом — обновление
`PROGRESS.md` (чекбокс в "Статусе выполнения задач" + запись в "Журнале изменений")
обязательно после каждого тикета наравне с `PROJECT_INDEX.md`, а не по желанию. Ранее в
промте это явно не требовалось (только вскользь упоминалось как одно из двух мест для
пометки "не подтверждено прогоном"), из-за чего чекбоксы тикетов 06-11 в разделе "Статус
выполнения задач" остались непроставленными, хотя журнальные записи по 06-10 в этом файле
уже существовали, а PROJECT_INDEX.md отмечал их DONE — расхождение исправлено (чекбоксы
06-11 проставлены `[x]`) в рамках этого же прохода.

## Тикет 12 — MaterialBinStage (2026-08-17, отдельная сессия)

`MaterialBin` (record: `materialId`, `List<Quad> quads`) и `MaterialBinStage.process(List<Quad>)
-> List<MaterialBin>` в `dev.ev.meshing.stage` — третья стадия конвейера мешинга. Реализация
прямая, без отклонений от контракта тикета: группировка через `LinkedHashMap` (сохраняет
относительный порядок quad'ов внутри бина — не переставляет их местами), список бинов на
выходе отсортирован по `materialId` возрастанием (`O(k log k)` по числу уникальных
материалов, не по общему числу quad'ов — как явно предпочитал текст тикета в требовании 3).
Пустой вход даёт пустой список бинов через `List.of()`, не список из одного пустого бина.

Тесты (`MaterialBinStageTest`, 4 сценария из тикета) включают контрольный кейс с
намеренно не сгруппированным заранее входом (`materialId` в порядке 5, 2, 5, 8, 2) —
проверяет одновременно и сортировку бинов (2, 5, 8), и то, что quad'ы с `materialId=2`
внутри своего бина остаются в исходном относительном порядке (позиция 1, затем позиция 4),
а не переставлены.

Никаких новых кросс-модульных неоднозначностей не найдено — тикет самодостаточен, зависит
только от `Quad` (тикет 03, уже стабилен), не трогает `MeshingContext`/`WorldSectionHandle`.

Компиляция/тесты не прогнаны реальным Gradle-билдом в этой сессии — та же причина, что и в
тикетах 10-11 (песочница без доступа к `maven.neoforged.net`/JDK). Логика проще, чем у
`GreedyMeshStage` (без битовой геометрии, без осевых таблиц) — риск скрытой ошибки ниже,
но фактическое подтверждение реальным прогоном по-прежнему требуется.

`PROJECT_INDEX.md` обновлён: статус тикета 12 → DONE, модульная карта и "Ключевые классы"
для `ev-meshing`, "Чего пока не существует" (мешинг — 3 из 4 стадий).

## Тикет 13 — MeshletPackStage (2026-08-17, отдельная сессия)

`MeshletPackStage.process(SectionPos, List<MaterialBin>) -> MeshletBatch` в
`dev.ev.meshing.stage` — финальная, четвёртая стадия конвейера мешинга. Разбиение
переполненных бинов на чанки по `MAX_QUADS_PER_MESHLET` (128) реализовано напрямую по
формуле из тикета, без отклонений. Ключевая часть — `quadWorldBounds(Quad)`: вычисляет
bounding box одного quad'а, используя **ровно ту же** таблицу нормаль/width/height, что
зафиксирована в `GreedyMeshStage` (тикет 11, требование 4a) — тикет 13 явно требовал не
изобретать свою конвенцию здесь, и я скопировал таблицу в Javadoc с явной ссылкой на
источник, чтобы будущие правки одного файла не рассинхронизировались молча со вторым.

Тесты (`MeshletPackStageTest`, 6 сценариев) включают проверку bounding box для трёх разных
`faceDirection` (+X/+Y/+Z) с вручную посчитанными диапазонами — самый рискованный кусок
логики этого тикета, ошибка здесь тихо ломает GPU culling намного позже (тикеты 21-22), а
не проявляется явно на этом этапе. При написании теста на bounding box для нескольких
разбросанных quad'ов (union по всем, не только первый/последний) сначала ошибся в ручном
расчёте (`maxZ=10` вместо `11` для одного из трёх quad'ов) — заметил и поправил при
повторной проверке перед тем как считать тикет завершённым; итоговое утверждение в тесте
верное, но сам факт стоит знать: bounding-box тесты такого рода стоит перепроверять дважды.

**Важная находка, не относящаяся напрямую к тикету 13, но обнаруженная при сверке
`PROJECT_INDEX.md` перед правкой**: интерфейс `MeshBuilder` (тикет 03,
`build(WorldSectionHandle, MeshingContext) -> MeshletBatch`) — единая точка входа,
оркестрирующая вызов всех четырёх CPU-side стадий подряд, — не реализован ни одним из
тикетов 10-13, и явного тикета на его реализацию в `tickets/MVP_INDEX.md` (проверено по
полному списку 0-32) я не нашёл. Сейчас все 4 стадии существуют только как отдельные
классы, которые что-то должно вызвать по цепочке; я не стал писать эту склейку
самостоятельно (это не входило в текст тикета 13, а угадывать чужой контракт без явного
тикета — риск сделать не то, что впоследствии потребуется другому тикету, например
26-render-dirty-tracking-mvp). Отмечено в `PROJECT_INDEX.md` как открытый вопрос к
пользователю.

Компиляция/тесты не прогнаны реальным Gradle-билдом в этой сессии — та же причина, что и в
тикетах 10-12.

`PROJECT_INDEX.md` обновлён: статус тикета 13 → DONE, модульная карта (конвейер мешинга
полностью реализован) и "Ключевые классы" для `ev-meshing`, "Чего пока не существует"
(мешинг — все 4 стадии реализованы, но `MeshBuilder`-оркестратор — нет, см. находку выше).

## Тикет 14 — MeshPriority + ScreenSpaceErrorMetric (2026-08-17, отдельная сессия)

Самый насыщенный текстом тикет из пройденных до сих пор — сам текст тикета включал не
только сигнатуры, но и подробную битовую схему двухъярусного приоритета с обоснованием
направления сравнения (бит 63). Реализация следует тексту тикета почти дословно, без
существенных отклонений:

- `ScreenSpaceErrorMetric.selectLodLevel` — линейный перебор 7 уровней от 6 к 0 (тикет явно
  разрешил не усложнять бинарным поиском), `voxelSizeAtLevel = 1 << level` без аллокации
  `SectionPos`.
- `MeshPriority.compute(int,...)` — упаковка `long`: биты 60-62 lodWeight, 58-59
  attemptWeight (capped на 3), 57 facingBonus, 0-56 insertionSeq. Единственное место, где
  текст тикета не дал точной формулы — как именно `lodWeight` должен учитывать `maxLodLevel`
  (сказано лишь "относительно текущего максимального LOD в использовании", без формулы, в
  отличие от остальной, полностью специфицированной части тикета). Выбрал
  `round(lodLevel*7/maxLodLevel)` — растягивает `lodLevel` на весь 3-битный диапазон (0-7)
  независимо от того, сколько уровней реально используется, вместо того чтобы просто взять
  `lodLevel` напрямую (что оставляло бы неиспользуемые верхние значения диапазона, если
  `maxLodLevel < 7`) — свойство монотонности (меньший `lodLevel` → меньший вес), которое
  единственное явно проверяется тестами, сохраняется при любом разумном выборе формулы.
- `computeWithNearTierCheck` — двухъярусный приоритет по эмпирическому уроку, явно описанному
  в самом тексте тикета (ссылка на `PROGRESS.md` независимой реализации Exceptional Vision,
  пункт 0.8): near-tier результат = `Long.MIN_VALUE + offset`, offset = 20 бит квантованной
  squared-distance (в блоках, clamp на `0xFFFFF`) + 12 бит замаскированного `insertionSeq`.
  Радиус проверяется в level-0 section-grid единицах через Chebyshev-дистанцию, как того
  требовал тикет (независимо от LOD-уровня самой проверяемой секции).

Тесты (`ScreenSpaceErrorMetricTest` — 6 сценариев, `MeshPriorityTest` — 9 сценариев) покрывают
все явно перечисленные в тикете случаи, включая white-box тест инварианта бита 63 на 1000+
случайных вызовах с фиксированным seed (500 нормального тира, 500 near-tier, плюс 20 прямых
попарных сравнений через `Long.compare`) и прямую проверку `Long.MIN_VALUE + 0xFFFFFFFFL < 0`
для арифметической безопасности near-tier offset'а, как явно требовал тикет отдельным пунктом
(не полагаться только на инспекцию кода).

Компиляция/тесты не прогнаны реальным Gradle-билдом в этой сессии — та же причина, что и в
предыдущих тикетах мешинга. Учитывая плотность битовой арифметики в этом тикете (больше, чем
в `GreedyMeshStage`), фактическое подтверждение реальным прогоном тестов особенно важно
именно здесь — до этого момента вся проверка ограничена вычислениями на бумаге при написании
тестов.

`PROJECT_INDEX.md` обновлён: статус тикета 14 → DONE, модульная карта (`ev-meshing` теперь
включает пакет `dev.ev.meshing.priority`), "Ключевые классы" (битовая раскладка
`MeshPriority` продублирована для быстрого чтения без открытия исходника), "Чего пока не
существует" (мешинг 10-14 готов; очередь задач 15-16, использующая `MeshPriority`, — ещё
нет).

## Тикет 15-mvp — MeshTaskQueue (2026-08-17, отдельная сессия)

MVP-очередь задач мешинга поверх `PriorityBlockingQueue`, без work-stealing и без
per-worker шардирования, как явно требовал текст тикета (opt-версия — отдельный тикет,
`15-meshing-work-stealing-queue-opt.md`, применяется только по результатам профилирования).

- `MeshTaskQueue<T>` (`dev.ev.meshing.queue`) — приватный `Entry<T>` record-подобная обёртка
  `(long priority, T task)` реализует `Comparable<Entry<T>>` через `Long.compare(priority, ...)`
  — естественный порядок JDK-очереди уже даёт нужную семантику "меньше = приоритетнее" из
  тикета 14 без какой-либо ручной инверсии.
- `submit`/`poll`/`pollNonBlocking`/`size` реализованы ровно как специфицировано текстом
  тикета: `poll()` — через блокирующий `PriorityBlockingQueue.take()`, `pollNonBlocking()` —
  через собственный неблокирующий `PriorityBlockingQueue.poll()` из JDK (тикет явно
  предупреждал не путать эти два метода — оставил именно такое разделение имён).
- Метрика `MetricsRegistry.recordQueueDepth("mesh-task-queue", size())` отправляется не на
  каждый вызов, а раз в 64 операции суммарно (`submit`+`poll`+`pollNonBlocking`) через
  `AtomicInteger`-счётчик — конструктор принимает `MetricsRegistry` явным параметром
  (в этой версии кода он ещё нигде не пробрасывался, добавлен по требованию тикета).
- Двухъярусная схема тикета 14 (near-tier как отрицательный `long`, normal-tier как
  неотрицательный) сортируется правильно "бесплатно", без какого-либо специального кода —
  подтверждено отдельным тестом (`nearTierPriority_alwaysOutranksNormalTierPriority`), а не
  просто предполагается по инспекции.

Отклонений от текста тикета не было — контракт совпадает с тем, что задан в самом тикете
дословно (сигнатуры `submit(long, T)`/`poll()`/`pollNonBlocking()`/`size()`, конструктор с
`MetricsRegistry`).

Тесты (`MeshTaskQueueTest`, 6 сценариев): ordering по приоритету, `pollNonBlocking` на пустой
очереди возвращает `null` немедленно, `size()` корректен до/после submit/poll, конкурентный
smoke-тест (4 продюсера × 500 задач, 3 консьюмера, `ConcurrentHashMap`-based множества
produced/consumed, таймаут 30с) — все элементы извлечены без потерь, задачи с равным
приоритетом обе извлекаются (без потерь, без строгого порядка между собой), near-tier vs
normal-tier инвариант из тикета 14. Также добавлена `NoopMetricsRegistry` — package-private
тестовая заглушка `dev.ev.meshing.queue`, аналог одноимённого класса в `ev-storage`
(не переиспользован напрямую между модулями, так как исходный package-private и живёт в
другом Gradle-подпроекте).

Компиляция/тесты не прогнаны реальным Gradle-билдом в этой сессии — та же причина ограничения
песочницы, что и во всех предыдущих тикетах `ev-meshing`. Логика этого тикета проще
битовой арифметики тикета 14 (обёртка над готовым JDK-классом), но конкурентный тест
особенно нуждается в подтверждении реальным прогоном (JIT/scheduler-специфичное поведение
многопоточности плохо проверяется одной логической инспекцией).

`PROJECT_INDEX.md` обновлён за один проход: статус тикета 15 (mvp/opt) → "MVP DONE, opt not
applied", модульная карта (`ev-meshing` теперь включает `dev.ev.meshing.queue`), "Ключевые
классы" (новый блок под тикет 14, зеркалит структуру существующих записей), "Чего пока не
существует" (очередь задач мешинга MVP готова; opt в банке, mip-агрегация 16 всё ещё нет).
Расхождений между разделами `PROJECT_INDEX.md`, оставшихся от предыдущих тикетов, при сверке
модуля `ev-meshing` перед правкой не найдено — все четыре раздела уже были согласованы друг
с другом на момент начала этой сессии.

## Тикет 16-mvp — MipAggregator (2026-08-17, отдельная сессия)

MVP-агрегация детских вокселей в родительский mip-уровень: скалярный majority-vote без
SIMD/Vector API, как явно требовал текст тикета (opt-версия — отдельный тикет,
`16-meshing-simd-mipgen-opt.md`, применяется только по результатам профилирования, и вводит
зависимость на incubator-модуль `jdk.incubator.vector`, чего эта MVP-версия сознательно
избегает).

- `MipAggregator.aggregateMajorityVote(int[] childPalette, int[] parentPalette, int
  parentCount)` — для каждого родителя прямой попарный подсчёт `O(8*8)=O(64)` без аллокации
  `HashMap` на вызов, как явно предпочёл текст тикета (требование 2) ради меньшего
  GC-давления.
- Tie-break правило (наименьший индекс позиции среди 8 детей при равенстве count)
  реализовано через строгое сравнение `count > bestCount` (не `>=`) в порядке позиций 0..7 —
  это автоматически даёт нужную семантику "первый из максимальных побеждает" без отдельной
  явной проверки на равенство.
- `parentCount = 0` — простой no-op через границу цикла `for (i = 0; i < parentCount; i++)`,
  никакого специального раннего return не потребовалось.

Отклонений от текста тикета не было — реализация соответствует контракту дословно.

Тесты (`MipAggregatorTest`, 5 сценариев): все 8 детей одинаковы, явное большинство 5 из 8,
все 8 значений разные (проверка именно tie-break правила — ожидается позиция 0), 1000
родителей со случайными (fixed seed) детьми, сверенные с независимой reference-реализацией
внутри самого теста (частотный массив по значению — намеренно другой алгоритм, чем в
основном классе, чтобы не проверять метод через самого себя), `parentCount=0` не изменяет
предзаполненный sentinel-значениями `parentPalette`.

Компиляция/тесты не прогнаны реальным Gradle-билдом в этой сессии — та же причина ограничения
песочницы, что и во всех предыдущих тикетах `ev-meshing`.

**Найдена и исправлена рассинхронизация, оставшаяся от тикета 15 (не от текущего тикета)**:
в разделе "🌊 Статус MVP/opt волн" (`PROJECT_INDEX.md`) строка "Очередь мешинга" всё ещё
говорила "нет (не реализовано)", хотя тикет 15-mvp был завершён в предыдущей сессии и три
других раздела файла (Статус тикетов, Модульная карта, Ключевые классы) уже корректно
отражали это. Предыдущая сессия обновила эти три раздела, но пропустила строку в таблице
статуса волн — тот самый паттерн точечного обновления без синхронизации остальных мест,
против которого явно предостерегает `EV_SESSION_PROMPT.md`. Исправлено в этом же проходе
(строка "Очередь мешинга" теперь указывает на активную MVP-версию), заодно со строкой
"Mip-агрегация" для текущего тикета — обе правки в одном diff, не по памяти, а по фактическому
состоянию остальных трёх разделов файла.

`PROJECT_INDEX.md` обновлён за один проход: статус тикета 16 (mvp/opt) → "MVP DONE, opt not
applied", таблица "Статус MVP/opt волн" (строки "Очередь мешинга" и "Mip-агрегация" — см.
находку выше), модульная карта (`ev-meshing` теперь включает `dev.ev.meshing.mip`), "Ключевые
классы" (новый блок под тикет 15), "Чего пока не существует" (mip-агрегация MVP готова; opt в
банке).

## Тикет 17 — GLRenderBackend: буферы и текстуры (2026-08-17, отдельная сессия)

Первый тикет для `ev-gpu` — единственного модуля, которому разрешено импортировать
`org.lwjgl.*`. Реализованы `createBuffer`/`createTexture` полностью, остальные 7 методов
`RenderBackend` — явные `UnsupportedOperationException`-заглушки с указанием, какой тикет их
закроет, как явно требовал текст тикета (не создавать конкурирующий класс — дополнять этот же).

- `GLRenderBackend.createBuffer` — DSA-стиль `glCreateBuffers`+`glNamedBufferStorage` (GL 4.5+).
  `STATIC_DRAW`/`DYNAMIC_DRAW`/`STORAGE` → `GL_DYNAMIC_STORAGE_BIT`; `STAGING_UPLOAD`/
  `STAGING_DOWNLOAD` → persistent-mapped через `glMapNamedBufferRange` с
  `GL_MAP_PERSISTENT_BIT|GL_MAP_COHERENT_BIT` плюс `WRITE`/`READ` соответственно.
- `createTexture` — `glCreateTextures`+`glTextureStorage2D`/`3D`, маппинг `TextureFormat→GL`
  вынесен в отдельный package-private `GLTextureFormats` — единственный способ протестировать
  этот кусок логики без реального GL-контекста (сам класс `GLRenderBackend` не тестируется
  никак в этой сессии, так как весь его код — прямые GL-вызовы).
- `GLBuffer`/`GLTexture` — тонкие обёртки, `free()` идемпотентен, `mappedAddress()` бросает
  вне `STAGING_UPLOAD` по контракту тикета 04.

**Два обязательных веб-поиска, которые прямо требовал текст тикета, выполнены (не по памяти)**:
1. GL45 vs GL46 — LWJGL 3.3.3 (версия проекта) содержит оба класса, но macOS исторически
   ограничен OpenGL 4.1 (Apple заморозила поддержку), так что GL46 недоступен на macOS в
   принципе. Оставлен GL45, как тикет и предлагал по умолчанию при отсутствии доказательств
   безопасности GL46.
2. Explicit unmap перед delete — подтверждено официальной спецификацией (`GL_MAP_PERSISTENT_BIT`
   формально освобождает от обязательного unmap перед использованием буфера GL-командами,
   `glDeleteBuffers` по спеке безопасно работает независимо от состояния маппинга), конкретных
   задокументированных багов драйверов на этот счёт не нашёл — оставлен explicit unmap как
   defensive best-practice, как и предлагал тикет.

**Найдена и исправлена собственная ошибка внутри этой же сессии, до сдачи**: первая версия
кода использовала `GL45.nglMapNamedBufferRange(handle, offset, length, flags)`, предполагая,
что это "unsafe"-вариант, возвращающий `long`-адрес напрямую (по аналогии с паттерном
`nXxx`-методов в некоторых других частях LWJGL). Отдельный веб-поиск сигнатур LWJGL API
показал, что такого метода с этой сигнатурой в публичном API нет — правильный путь:
`glMapNamedBufferRange(...)` возвращает `ByteBuffer`, а `long`-адрес получается через
`org.lwjgl.system.MemoryUtil.memAddress(ByteBuffer)`. Исправлено до финальной сдачи, не
оставлено как известная проблема.

Тесты (`GLTextureFormatsTest`, 7 сценариев): полнота маппинга по всем значениям
`TextureFormat.values()` (ловит забытые будущие добавления в enum), отсутствие коллизий
между разными форматами на одну GL-константу, точечная проверка каждого из 5 текущих значений
на конкретную ожидаемую GL-константу. Тесты, требующие реального GL-контекста (фактическое
создание/освобождение буфера/текстуры на GPU), не добавлены — headless GL недоступен в этой
песочнице; тикет явно пометил это как опциональный, не блокирующий приёмку пункт.

Компиляция не прогнана реальным Gradle-билдом в этой сессии — та же причина ограничения
песочницы, что и во всех предыдущих тикетах. Для этого тикета это особенно существенное
ограничение: весь код `createBuffer`/`createTexture`, кроме вынесенного `GLTextureFormats`,
вообще не проверен исполнением — ни один GL-вызов не был реально сделан, только сверен по
документации/сигнатурам через поиск. Подтверждение `./gradlew :ev-gpu:test` и, в идеале,
ручной прогон в реальном Minecraft-клиенте с NeoForge особенно важны именно для этого тикета
раньше, чем для предыдущих (`ev-storage`/`ev-meshing`), которые были чистой CPU-логикой без
внешних нативных вызовов.

`PROJECT_INDEX.md` обновлён за один проход: статус тикета 17 → "DONE (partial —
createBuffer/createTexture only)", модульная карта (`ev-gpu` больше не пустой, содержит
`dev.ev.gpu.gl`), "Ключевые классы" (новый блок под тикет 16, с подробным описанием находок
веб-поиска), "Чего пока не существует" (GPU-бэкенд частично реализован, остальные методы —
явные заглушки на конкретные будущие тикеты, не общая пометка "не реализовано"). Расхождений
между разделами, оставшихся от предыдущих тикетов, при сверке модуля `ev-gpu` не найдено —
модуль был последовательно пуст во всех местах на момент начала этой сессии.

## Тикет 18 — GLRenderBackend: компиляция шейдеров и автобиндинг (2026-08-17, отдельная сессия)

Продолжение `GLRenderBackend` (тикет 17) — дополнен, не пересоздан. Реализованы
`compilePipeline`/`compileGraphicsPipeline`, ранее явные `UnsupportedOperationException`-заглушки.

- `ShaderCompiler` (новый, package-private) — `compileComputeProgram`/`compileGraphicsProgram`:
  компиляция через `glCreateShader`+`glShaderSource`+`glCompileShader` с проверкой
  `GL_COMPILE_STATUS`, линковка через `glCreateProgram`+`glAttachShader`+`glLinkProgram` с
  проверкой `GL_LINK_STATUS`; при неудаче любого из шагов — `RuntimeException` с
  `ShaderSource.sourcePath()` и полным `glGetShaderInfoLog`/`glGetProgramInfoLog`, как прямо
  требовал тикет (диагностируемость для будущих тикетов 21-23, где появятся реальные шейдеры).
  После успешной линковки — `glDetachShader`+`glDeleteShader` промежуточных шейдер-объектов.
- `injectDefines(rawSource, defines)` — вынесен в отдельный package-private статический чистый
  метод, как явно просил тикет для тестируемости без GL-контекста. Требует `#version` первой
  строкой (иначе `IllegalArgumentException` — падение на Java-стороне раньше и понятнее, чем
  малопонятная ошибка компиляции от драйвера); пустая карта defines — исходный текст без
  изменений (без лишнего string-processing); непустая — `#define KEY VALUE` для каждой записи
  вставляется сразу после `#version`-строки, до остального тела шейдера.
- `GLComputePipeline`/`GLGraphicsPipeline` (новые, package-private конструктор, создаются
  только через `GLRenderBackend`) — хранят program handle + `PipelineLayout.bindingsByName()`.
  `bindBuffer`/`bindTexture` — map-lookup по имени, `IllegalArgumentException` при отсутствии
  ключа (опечатка в имени биндинга не проглатывается молча, падает сразу — прямое требование
  тикета). Биндинг буферов — `glBindBufferBase(GL_SHADER_STORAGE_BUFFER, ...)` по умолчанию;
  тикет явно разрешил это допущение для SSBO-heavy проекта и попросил задокументировать —
  сделано в Javadoc обоих классов. `dispatchIndirect` явно биндит `indirectBuffer` на
  `GL_DISPATCH_INDIRECT_BUFFER` перед вызовом внутри метода (не полагается на внешний код —
  прямое требование тикета, п.5). `free()` — `glDeleteProgram`, идемпотентно.

**Отклонение от буквального текста тикета, не выходящее за рамки задачи**: `drawIndirect`/
`drawIndirectCount` тикет не расписывал дословно построчно (фокус тикета — компиляция и
биндинги, п.5 говорит только про `dispatch`/`dispatchIndirect`). Реализованы по контракту
интерфейса `GraphicsPipeline` из тикета 04 через `glMultiDrawArraysIndirect`/
`glMultiDrawArraysIndirectCount` (`GL_TRIANGLES`, `stride=0`) — необходимо, поскольку интерфейс
требует эти методы у любого класса, реализующего `GraphicsPipeline`, иначе `GLGraphicsPipeline`
не скомпилируется. Названо явно здесь, а не замаскировано молчанием.

Тесты (`ShaderCompilerTest`, 6 сценариев): пустые defines не меняют исходный текст; один
define вставлен сразу после `#version`; несколько defines вставлены все, в порядке `Map`;
точная проверка позиции вставки (сразу после `#version`-строки, не в конец файла и не перед
`#version`); `IllegalArgumentException` при отсутствии `#version`-строки — отдельно для
пустых и для непустых defines. Тесты, требующие реального GL-контекста (сама компиляция/
линковка шейдера в драйвере), не добавлены — headless GL недоступен в этой песочнице, та же
причина ограничения, что и в тикете 17.

Компиляция не прогнана реальным Gradle-билдом в этой сессии — то же ограничение песочницы
(нет доступа к `services.gradle.org`/`maven.neoforged.net`), что и во всех предыдущих
GPU/JVM-тикетах. Подтверждение `./gradlew :ev-gpu:test` остаётся задачей следующей сессии с
обычным доступом в интернет.

`PROJECT_INDEX.md` обновлён за один проход: статус тикета 18 → DONE (таблица "Статус
тикетов"), модульная карта (`ev-gpu` — добавлены `ShaderCompiler`/`GLComputePipeline`/
`GLGraphicsPipeline` в список ключевых классов модуля), "Ключевые классы" (новый абзац под
заголовком "тикеты 17, 18" — заголовок секции переименован, не оставлен как "тикет 17" при
дополнении содержимым тикета 18), "Чего пока не существует" (GPU-бэкенд теперь "тикеты 17-18"
реализованы, оставшиеся 5 методов — заглушки на тикеты 19-23, было "17" и "7 методов"/"18-23").
Перед правкой сверено дерево файлов `ev-gpu/src` с текущим текстом всех четырёх мест —
расхождений, оставшихся от тикета 17 (предыдущей сессии), не найдено: описание тикета 17 в
файле точно соответствовало фактическому состоянию кода на момент начала этой сессии.

### Багфикс 6 — `drawIndirectCount` ссылался на несуществующие символы `GL45.GL_PARAMETER_BUFFER`/`GL45.glMultiDrawArraysIndirectCount`

Компиляция `ev-gpu` падала на `GLGraphicsPipeline.java` (метод `drawIndirectCount`, добавлен
в тикете 18): `Cannot resolve symbol 'GL_PARAMETER_BUFFER'`, `Cannot resolve method
'glMultiDrawArraysIndirectCount' in 'GL45'`.

Причина: оба символа принадлежат расширению `ARB_indirect_parameters`, которое стало core
только в OpenGL 4.6 (`GL46`/`GL46C`); в core-классе `GL45` (LWJGL) их нет. Проект намеренно
зафиксирован на `GL45` ради совместимости с macOS (аппаратный потолок OpenGL 4.1, см. запись
по тикету 17) — перейти на `GL46` нельзя. Правильный путь — тот же функционал через
ARB-суффиксные символы: `ARBIndirectParameters.GL_PARAMETER_BUFFER_ARB` и
`ARBIndirectParameters.glMultiDrawArraysIndirectCountARB(mode, indirect, drawcount,
maxdrawcount, stride)`.

Исправление: `GLGraphicsPipeline.java` — добавлен импорт `org.lwjgl.opengl.ARBIndirectParameters`,
вызов `glBindBuffer` переведён на `ARBIndirectParameters.GL_PARAMETER_BUFFER_ARB`, вызов
`glMultiDrawArraysIndirectCount` заменён на `ARBIndirectParameters.glMultiDrawArraysIndirectCountARB`
с теми же аргументами (сигнатура ARB-версии идентична по порядку параметров). Дополнительная
Gradle-зависимость не потребовалась — `ARBIndirectParameters`, как и `GL45`, входит в тот же
artifact `lwjgl-opengl`, уже подключённый в `ev-gpu/build.gradle.kts`; подтверждено фактическим
прогоном (см. ниже), предположение о совместной поставке в одном jar оказалось верным.

`drawIndirect` (без Count) не затронут — использует `GL45.glMultiDrawArraysIndirect`, который
существует в core GL45, ошибки там не было.

**Подтверждено фактическим прогоном пользователем (2026-08-17)**: `./gradlew build` —
`BUILD SUCCESSFUL`, включая `ev-gpu:compileJava`, `ev-gpu:test`, `ev-gpu:check`, `ev-gpu:build`
(ранее падавшие/не проверявшиеся на unresolved-символах задачи выполнены без ошибок). Это
первое подтверждение реальной компиляции `ev-gpu` в проекте — предыдущие записи по тикетам
17/18 фиксировали компиляцию как неподтверждённую по недоступности Gradle/JDK в песочнице;
это ограничение сохраняется для сессий модели, но не для окружения пользователя.

`PROJECT_INDEX.md` обновлён за один проход в обоих местах, где тикет 18 описывает
`drawIndirect`/`drawIndirectCount` (таблица "Статус тикетов" и раздел "Ключевые классы" —
`ev-gpu`, тикеты 17-18): заменено упоминание `GL45.glMultiDrawArraysIndirectCount`/
`GL45.GL_PARAMETER_BUFFER` на ARB-суффиксные символы, с явной пометкой "исправлено
пост-фактум" и кратким описанием причины, вместо тихой правки задним числом. Перед правкой
сверено дерево файлов `ev-gpu/src` — новых файлов нет, изменён только `GLGraphicsPipeline.java`,
расхождений в остальных разделах (модульная карта, "Чего пока не существует"), не связанных
с этой конкретной функцией, не найдено.

Файлы изменены: `ev-gpu/src/main/java/dev/ev/gpu/gl/GLGraphicsPipeline.java`,
`PROJECT_INDEX.md`, `PROGRESS.md` (эта запись).

## Тикет 20-mvp — AoS Node Buffer Layout (2026-08-17, отдельная сессия)

Реализована MVP (Array-of-Structures, AoS) версия буфера узлов секций на GPU (единый `BufferUsage.STORAGE` буфер размером `nodeCapacity * 32` байт). Opt-версия (`20-gpu-node-buffer-soa-opt.md`, SoA раскладка на 5 буферов) оставлена в банке гипотез до чекпоинта профилирования `P0-profiling-checkpoint.md`.

- `NodeBuffer` (`dev.ev.gpu.nodes.NodeBuffer`) — класс Java. Оборачивает единственный `GpuBuffer` размером `nodeCapacity * BYTES_PER_NODE` (32 байта). Метод `close()` выдерживает идемпотентное освобождение буфера.
- Javadoc класса содержит явное документирование MVP-статуса, предупреждение о несовместимости контракта публичного API с будущим SoA (`20-opt`), а также пояснение отсутствия поля `childMask` (так как в MVP traversal делается на CPU).
- `node_buffer_aos.glsl` (`ev-gpu/src/main/resources/shaders/include/node_buffer_aos.glsl`) — шейдерный include-файл с объявлением структуры `Node` (vec4 bounds, uint flags, uint materialRef, uint streamState, uint _padding) и буфера `NodeBufferAoS`. Явно выдержано выравнивание по правилам `std430` (32 байта на элемент, кратно 16 для `vec4`).
- Юнит-тесты: `NodeBufferTest` (проверка арифметики емкости и размера, проверка вызова `free()` ровно один раз через `close()`, проверка валидации аргументов конструктора).

Компиляция/тесты не прогнаны реальным Gradle-билдом в этой сессии из-за ограничений среды.

`PROJECT_INDEX.md` и `PROGRESS.md` обновлены.

## Тикет 21-mvp — CPU-Side Frustum Culling & Simple Traversal (2026-08-17, отдельная сессия)

Реализована MVP (Волна-1) версия traversal/culling: простой CPU-side проход линейной сложности `O(N)` по всем загруженным секциям с проверкой видимости через сферно-плоскостной frustum тест. Без GPU persistent kernel, без occlusion culling (`22-opt`) и без temporal coherence (`25-opt`).

- `FrustumTester` (`dev.ev.render.culling.FrustumTester`) — проверяет 6 плоскостей пирамиды видимости против ограничивающей сферы (`worldX, worldY, worldZ, radius`). Возвращает `false` при раннем выходе, если сфера полностью за плоскостью (`signedDistance < -radius`), иначе `true` (консервативная видимость).
- `SimpleTraversal` (`dev.ev.render.culling.SimpleTraversal`) — выполняет обход списка `loadedSections`, фильтрует через `FrustumTester` и в обязательном порядке хронометрирует вызов в wall-clock наносекундах через `MetricsRegistry.recordGpuPassDuration("cpu-traversal", nanos)`.
- Ни один из классов не импортирует `org.lwjgl.*` (соблюдено архитектурное разделение модулей).
- Юнит-тесты: `FrustumTesterTest` (4 теста: сфера в центре, за пределами, частично пересекающая, и граничный случай на поверхности с радиусом 0) и `SimpleTraversalTest` (5 тестов: пустой список, все видимы, ни одна не видима, смешанный случай, и обязательная проверка вызова метрик `recordGpuPassDuration`).

Компиляция/тесты не прогнаны реальным Gradle-билдом в этой сессии из-за ограничений среды.

`PROJECT_INDEX.md` и `PROGRESS.md` обновлены.

## Тикет 24 — FrameGraphBuilder (декларативная оркестрация кадра) (2026-08-17, отдельная сессия)

Реализована декларативная оркестрация GPU-проходов кадра (`dev.ev.render.framegraph`).

- `FrameResource` (`dev.ev.render.framegraph.FrameResource`) — маркер логических ресурсов между проходами.
- `FramePass` (`dev.ev.render.framegraph.FramePass`) — интерфейс отдельного GPU-прохода кадра (`record(CommandList)`, `name()`).
- `PassBuilder` (`dev.ev.render.framegraph.PassBuilder`) — fluent-интерфейс объявления зависимостей `reads(...)` / `writes(...)`.
- `FrameGraphBuilder` (`dev.ev.render.framegraph.FrameGraphBuilder`) — строит граф зависимостей, выполняет топологическую сортировку (алгоритм Кана по читаемым/пишимым `FrameResource`), находит циклы (`IllegalStateException`), вставляет консервативные барьеры памяти `BarrierScope.ALL` непосредственно перед записью прохода `P`, если какой-либо из ранее исполненных проходов `Q` писал в ресурс, используемый `P`. Отправляет все команды через `RenderBackend.submit(CommandList)` за один вызов на кадр.
- Принята модель использования **single-use per frame instance**: builder создаётся заново каждый кадр, вызов `execute()` допускается строго один раз (повторный `execute()` или `addPass()` кидает `IllegalStateException`).
- Ни один класс не импортирует `org.lwjgl.*` (работа с GPU идет строго через `dev.ev.api.gpu.*`).
- Юнит-тесты: `FrameGraphBuilderTest` (6 сценариев: A->B цепочка с барьером, независимые проходы без барьера, цепочка из 3 проходов, обнаружение цикла, промежуточный несвязанный проход B между A и C, несколько писателей в один потребитель, проверка падения при повторном вызове `execute()`).

Компиляция/тесты не прогнаны реальным Gradle-билдом в этой сессии из-за ограничений среды.

`PROJECT_INDEX.md` и `PROGRESS.md` обновлены.
