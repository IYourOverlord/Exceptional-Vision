# EV — полный пакет документов проекта

## С чего начать

1. **`tickets/MVP_INDEX.md`** — главный навигатор. Вставляй этот файл целиком в новую
   сессию Клода первым сообщением, вместе с текстом одного нужного тикета из папки `tickets/`.
2. **`tickets/MODEL_ASSIGNMENT.md`** — какие тикеты можно доверить Sonnet, какие требуют Opus
   (и почему), с учётом двухволновой MVP/opt структуры.
3. **`tickets/P0-profiling-checkpoint.md`** — обязательный чекпоинт между Волной 1 (MVP) и
   Волной 2 (opt-оптимизации) — не пропускай его.

## Структура архива

```
ev-full-package/
├── README.md                          — этот файл
├── ARCHITECTURE.md                    — исходное архитектурное обоснование (справочно)
├── PERFORMANCE_MATH.md                — математика холодного старта / steady-state (справочно)
├── notes_voxy_analysis.md             — разбор прототипа Voxy, с которого начался проект
├── exceptional-vision-vs-ev.md   — сравнительный анализ независимой реализации
└── tickets/                           — 44 файла: рабочий план реализации
    ├── MVP_INDEX.md                   — ⭐ ГЛАВНЫЙ навигатор, используй его
    ├── MODEL_ASSIGNMENT.md            — разметка сложности тикетов по уровню модели
    ├── P0-profiling-checkpoint.md     — чекпоинт профилирования между волнами
    ├── 00-project-skeleton.md         — Волна 1 (MVP): скелет Gradle-проекта
    ├── 01-05-api-*.md                 — Волна 1: чистые контракты ev-api
    ├── 06-07-storage-*.md             — Волна 1: палитровый кодек, версионирование схемы
    ├── 08-storage-section-cache-mvp.md    — Волна 1: простой кэш
    ├── 08-storage-section-cache-opt.md    — Волна 2: шардированный кэш (opt-in)
    ├── 09-storage-heightmap-coarse-gen.md — единая версия (не разделена на mvp/opt)
    ├── 10-13-meshing-*.md              — Волна 1: конвейер мешинга
    ├── 14-meshing-priority-function.md — единая версия (screen-space error + приоритет)
    ├── 15-meshing-priority-queue-mvp.md      — Волна 1: простая очередь
    ├── 15-meshing-work-stealing-queue-opt.md — Волна 2: lock-free work-stealing (opt-in)
    ├── 16-meshing-mipgen-mvp.md              — Волна 1: скалярная агрегация
    ├── 16-meshing-simd-mipgen-opt.md         — Волна 2: SIMD Vector API (opt-in)
    ├── 17-18-gpu-backend-gl-*.md       — Волна 1: GL буферы и шейдеры
    ├── 19-gpu-upload-batching-opt.md   — Волна 2: батч-upload (opt-in)
    ├── 20-gpu-node-buffer-mvp.md       — Волна 1: AoS node buffer
    ├── 20-gpu-node-buffer-soa-opt.md   — Волна 2: SoA layout (opt-in)
    ├── 21-gpu-simple-traversal-mvp.md              — Волна 1: CPU-side traversal
    ├── 21-gpu-persistent-traversal-shader-opt.md   — Волна 2: GPU persistent-kernel (opt-in)
    ├── 22-gpu-hiz-occlusion-opt.md     — Волна 2: Hi-Z occlusion (opt-in, в MVP отсутствует)
    ├── 23-gpu-indirect-multidraw-opt.md — Волна 2: indirect multi-draw (opt-in)
    ├── 24-render-frame-graph.md        — единая версия (FrameGraph)
    ├── 25-render-temporal-reprojection-opt.md — Волна 2: temporal coherence (opt-in)
    ├── 26-render-dirty-tracking-mvp.md      — Волна 1: whole-section rebuild + dedup
    ├── 26-render-dirty-subregion-opt.md     — Волна 2: sub-region granular (opt-in)
    ├── 27-30-neoforge-*.md              — Волна 1: entrypoint, конфиг, команды, тесты
    ├── 31-integration-checklist-mvp.md  — интеграция ПОСЛЕ Волны 1
    ├── 31-integration-checklist-full.md — интеграция ПОСЛЕ применения opt-тикетов
    └── 32-project-index.md              — создание и ведение PROJECT_INDEX.md
```

## Порядок работы

1. Выполни все тикеты Волны 1 (MVP) — см. точный порядок в `tickets/MVP_INDEX.md`.
2. Собери, запусти, поиграй.
3. Выполни `P0-profiling-checkpoint.md` — реально измерь холодный старт и steady-state.
4. На основе измерений — выборочно примени нужные `*-opt` тикеты из Волны 2, по одному,
   с переинтеграцией после каждого.

## История ревизий этого пакета

Пакет прошёл несколько раундов пересмотра:
- Проведён сравнительный анализ с независимой реализацией той же концепции (Exceptional
  Vision) — эмпирические уроки оттуда встроены в соответствующие тикеты.
- План полностью переработан под MVP-first подход: сначала простая рабочая версия каждой
  подсистемы, оптимизации — только по результатам реального профилирования, не заранее.
- Проведена систематическая перепроверка всех 44 файлов на предмет логических противоречий,
  битых ссылок, недоопределённых мест и скрытых зависимостей между тикетами. Найдено и
  исправлено несколько содержательных дефектов, включая: инвертированное направление
  сравнения в двухъярусной приоритетной схеме (тикет 14), несогласованную интеграцию этой
  схемы с bucket-based work-stealing очередью (тикет 15-opt), недоопределённую конвенцию
  осей width/height в геометрии greedy-mesh (тикет 11), методологическую ошибку в подходе
  к тестированию fence-backpressure логики (тикет 19-opt), отсутствие явного создания
  worker thread pool в интеграционных чеклистах (тикеты 31-mvp/-full).

Это не гарантия, что все дефекты найдены.
Систематическая проверка снижает риск, но не заменяет реальный запуск и измерение.
