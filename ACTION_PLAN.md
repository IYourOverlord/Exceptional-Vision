# Последовательность действий по реализации проекта Horizon / EV

## Этап 1: Волна 1 (MVP) — Скелет и Контракты API
1. `tickets/00-project-skeleton.md` — Создание структуры Gradle-проекта (`ev-api`, `ev-storage`, `ev-meshing`, `ev-gpu`, `ev-render`, `ev-neoforge`, `ev-test`).
2. `tickets/32-project-index.md` — Создание и инициализация `PROJECT_INDEX.md`.
3. `tickets/01-api-sectionpos.md` — Реализация базовых координат `SectionPos`.
4. `tickets/02-api-storage-interfaces.md` — Базовые контракты хранилища (`SectionCache`, `PaletteCodec`).
5. `tickets/03-api-meshing-interfaces.md` — Интерфейсы мешинга (`MeshGenerator`, `MeshTaskQueue`).
6. `tickets/04-api-renderbackend-interfaces.md` — Интерфейсы GPU-бэкенда (`RenderBackend`, `BufferHandle`).
7. `tickets/05-api-metrics-interfaces.md` — Интерфейсы метрик и телеметрии.

## Этап 2: Волна 1 (MVP) — Хранилище и Мешинг
8. `tickets/06-storage-palette-codec.md` — Кодек палитрового сжатия блоков.
9. `tickets/07-storage-schema-migration.md` — Версионирование и миграция схемы хранилища.
10. `tickets/08-storage-section-cache-mvp.md` — MVP кэш секций на `ConcurrentHashMap` и `ReentrantLock`.
11. `tickets/09-storage-heightmap-coarse-gen.md` — Генерация Coarse Heightmap.
12. `tickets/10-meshing-occupancy-stage.md` — Стадия масок занятости блоков.
13. `tickets/11-meshing-greedy-mesh-stage.md` — Алгоритм Greedy Meshing.
14. `tickets/12-meshing-material-bin-stage.md` — Группировка мешей по материалам.
15. `tickets/13-meshing-meshlet-pack-stage.md` — Упаковка мешлетов.
16. `tickets/14-meshing-priority-function.md` — Функция приоритета и Screen-Space Error.
17. `tickets/15-meshing-priority-queue-mvp.md` — MVP очередь задач на `PriorityBlockingQueue`.
18. `tickets/16-meshing-mipgen-mvp.md` — MVP скалярная агрегация MIP-уровней.

## Этап 3: Волна 1 (MVP) — GPU, Рендеринг и Интеграция
19. `tickets/17-gpu-backend-gl-buffers.md` — Управление OpenGL буферами (VBO, SSBO).
20. `tickets/18-gpu-backend-gl-shaders.md` — Загрузка и компиляция GLSL шейдеров.
21. `tickets/20-gpu-node-buffer-mvp.md` — MVP AoS layout буфера узлов.
22. `tickets/21-gpu-simple-traversal-mvp.md` — MVP обход сцены на стороне CPU.
23. `tickets/24-render-frame-graph.md` — Построение рендер-графа (FrameGraph).
24. `tickets/26-render-dirty-tracking-mvp.md` — MVP отслеживание изменённых блоков (rebuild секции).
25. `tickets/27-neoforge-mod-entrypoint.md` — Точка входа NeoForge мода, инициализация worker pool.
26. `tickets/28-neoforge-config.md` — Конфигурация мода.
27. `tickets/29-neoforge-commands.md` — Команды отладки и управления.
28. `tickets/30-test-fake-render-backend.md` — Фейковый рендер-бэкенд для модульных тестов.
29. `tickets/31-integration-checklist-mvp.md` — Интеграционная сборка и проверка всей Волны 1.

## Этап 4: Профилирование и замеры (Чекпоинт P0)
30. `tickets/P0-profiling-checkpoint.md` — Запуск мода, замер времени холодного старта, FPS, задержек и узких мест.

## Этап 5: Волна 2 — Оптимизации (Opt-in по результатам P0)
Применять строго по одному тикету в зависимости от результатов замера P0:
- `tickets/08-storage-section-cache-opt.md` — Шардированный кэш (при contention).
- `tickets/15-meshing-work-stealing-queue-opt.md` — Work-stealing очередь (при задержках мешинга).
- `tickets/16-meshing-simd-mipgen-opt.md` — SIMD MIP-генерация.
- `tickets/19-gpu-upload-batching-opt.md` — Батчинг загрузки на GPU.
- `tickets/20-gpu-node-buffer-soa-opt.md` & `21-gpu-persistent-traversal-shader-opt.md` — SoA и GPU persistent traversal.
- `tickets/22-gpu-hiz-occlusion-opt.md` — Hi-Z Occlusion Culling.
- `tickets/23-gpu-indirect-multidraw-opt.md` — Indirect Multi-Draw.
- `tickets/25-render-temporal-reprojection-opt.md` — Temporal Reprojection.
- `tickets/26-render-dirty-subregion-opt.md` — Гранулярное обновление суб-регионов.
- `tickets/31-integration-checklist-full.md` — Итоговая интеграция после применения оптимизаций.
