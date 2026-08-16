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
| 01-api-sectionpos | NOT_STARTED | |
| 02-api-storage-interfaces | NOT_STARTED | |
| 03-api-meshing-interfaces | NOT_STARTED | |
| 04-api-renderbackend-interfaces | NOT_STARTED | |
| 05-api-metrics-interfaces | NOT_STARTED | |
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
| ev-api | — | Пусто, ни одного класса — интерфейсы появятся в тикетах 01-05. |
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

## Межмодульные контракты, зафиксированные де-факто

(пусто — ни один межмодульный контракт ещё не реализован; появится начиная с тикетов 01-05)

## Известные архитектурные инварианты

(пусто — ещё нет кода, накладывающего инварианты; в первую очередь ожидать записи здесь от
тикетов 01 — layout `SectionPos.encode()` — и 20 — layout node buffer)

## Чего пока не существует

- Ни одного интерфейса из `ev-api` — `SectionPos`, `VoxelStorage`, `MeshBuilder`,
  `RenderBackend`, `MetricsRegistry` и т.д. отсутствуют полностью.
- Ни одной реализации хранилища, мешинга, GPU-бэкенда или рендер-оркестрации.
- Ни одного теста — `sourceSets.test` в `ev-test` формально настроен (JUnit 5 в
  зависимостях), но ни один тестовый класс не написан.
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
