# Прогресс реализации проекта Horizon / EV

> **Правило обновления файла**: При завершении или изменении любого шага из `ACTION_PLAN.md` вносить краткую запись о сделанных изменениях, статус задачи (`[x]` — готово, `[/]` — в процессе, `[ ]` — не начато) и краткое резюме результатов.

---

## Статус выполнения задач

### Этап 1: Волна 1 (MVP) — Скелет и Контракты API
- [x] **00-project-skeleton** — Создание структуры Gradle-проекта
- [ ] **32-project-index** — Инициализация `PROJECT_INDEX.md`
- [ ] **01-api-sectionpos** — Реализация `SectionPos`
- [ ] **02-api-storage-interfaces** — Контракты `SectionCache` и `PaletteCodec`
- [ ] **03-api-meshing-interfaces** — Контракты `MeshGenerator` и `MeshTaskQueue`
- [ ] **04-api-renderbackend-interfaces** — Контракты `RenderBackend`
- [ ] **05-api-metrics-interfaces** — Контракты `MetricsRegistry`

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

Создан multi-module Gradle-проект в `ev/` (settings.gradle.kts, корневой
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
