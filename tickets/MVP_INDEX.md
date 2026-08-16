# EV — MVP-first план реализации

Это единственный документ, который нужно вставлять в новую сессию Клода первым сообщением —
он самодостаточен и не ссылается на другие индексные файлы.

## Что такое EV

Мод дальней прорисовки (far render / LOD) для **NeoForge 1.21.1** (Java 21). Идея: мир хранится в
собственном воксельном LOD-хранилище (октодерево, уровни детализации 0..6), рендерится через
GPU-driven pipeline (compute shaders, OpenGL 4.5+, LWJGL — уже включён в Minecraft/NeoForge).
Две оптимизационные цели: (1) быстро построить огромную дистанцию с нуля (throughput холодного
старта), (2) сделать установившийся кадр дешёвым и независимым от полного объёма мира
(steady-state, привязанный к видимому+изменившемуся множеству, а не ко всему дереву).

Полное архитектурное обоснование и математика — в справочных документах `ARCHITECTURE.md` и
`PERFORMANCE_MATH.md` (не обязательны для выполнения отдельного тикета — тикет самодостаточен,
но если что-то непонятно "почему так", там есть объяснение).

## Эмпирические уроки из независимой реализации

Другой независимо реализованный мод той же концепции — **Exceptional Vision**
(`github.com/IYourOverlord/Exceptional-Vision`, NeoForge 1.21.1, другая, более простая
2D-heightmap архитектура, но реально плейтестированная) дал ряд эмпирически подтверждённых
уроков, которые встроены в некоторые тикеты ниже как дополнительные требования. Если тикет
содержит раздел "Эмпирический урок из независимой реализации (Exceptional Vision)" — это
не гипотетическая осторожность, а реально произошедший, задокументированный, воспроизведённый
и исправленный баг в другом проекте, решающем ту же задачу. Такие разделы встречаются в
тикетах: 05 (стадийный статус импорта), 07 (цена отсутствия миграций), 08 (полная
перезагрузка кэша на рендер-потоке), 14 (двухъярусный near-player приоритет), 25 (константная
стоимость кадра без temporal coherence), 26 (дедупликация по геометрическому хэшу), 27 (√2
near-cutoff геометрия), 29 (формат вывода стадийного статуса).

## Почему этот документ существует и что изменилось

Первая версия этого плана (32 тикета, каждый — полная "целевая" архитектура сразу: persistent
GPU traversal, шардированный кэш, lock-free work-stealing очередь, SoA-буферы, Hi-Z occlusion,
temporal coherence, SIMD) была спроектирована теоретически — ни строчки кода не было
скомпилировано, не запущено, не измерено. Каждое отдельное решение обосновано (это стандартные
техники GPU-driven rendering), но **совокупность всех оптимизаций сразу, без единого
промежуточного измерения** — это не инженерная стратегия, это ставка. GPU-driven архитектуры
(в частности persistent-kernel work-stealing) исторически капризны к конкретным
драйверам/вендорам; сам прототип, из разбора которого родился этот план, не смог довести
persistent traversal до конца. Нет гарантии, что весь набор оптимизаций окажется одновременно
нужным, совместимым друг с другом на практике и не создающим неожиданных проблем именно в
комбинации.

**Новый принцип: сначала измеримый работающий минимум, потом оптимизации — по одной, каждая
только если профилирование на РЕАЛЬНОМ билде подтверждает, что она решает РЕАЛЬНО
существующее узкое место.** Это не отказ от архитектурных идей из первой версии плана — это
изменение порядка: сложность вводится по мере того, как измерение доказывает необходимость,
а не заранее по теоретическому обоснованию.

## Структура: две волны

### 🌊 Волна 1 — MVP-ядро (тикеты с суффиксом `-mvp`)
Минимальная, архитектурно простая, но полностью рабочая версия каждой подсистемы. Каждый
MVP-тикет реализует **тот же контракт** (интерфейсы из `ev-api`, не меняются между
волнами), но самой прямолинейной внутренней реализацией — без persistent kernels, без
шардирования, без SIMD, без Hi-Z, без temporal coherence. Цель Волны 1 — получить реально
компилирующийся, реально запускающийся, реально рисующий что-то на экране мод как можно
быстрее, с минимумом мест, где может тонко что-то пойти не так.

### 📊 Профилирующий чекпоинт (тикет `P0-profiling-checkpoint.md`)
Обязательный шаг между волнами. Не пропускай его и не переходи к Волне 2 "потому что кажется,
что понадобится X" — весь смысл этого плана в том, чтобы решения об усложнении принимались на
основе данных, а не интуиции (которая уже один раз привела к переусложнению без проверки).

### 🌊 Волна 2 — банк оптимизаций (тикеты с суффиксом `-opt`, opt-in)
Те же архитектурные идеи, что были в первой версии плана (persistent traversal, sharding,
work-stealing, SoA, Hi-Z, temporal coherence, SIMD) — но теперь каждый тикет начинается с
явного раздела **"Когда это применять"**, ссылающегося на конкретный диагностический признак
из `P0-profiling-checkpoint.md`. Если профилирование не показало этот признак — тикет не
нужен, пропусти его. Устанавливай оптимизации по одной, перепрофилируя после каждой, чтобы
видеть реальный эффект именно этого изменения, а не смеси нескольких сразу.

## Таблица: MVP vs оптимизация, по подсистемам

| Подсистема | Волна 1 (MVP) — делай сейчас | Волна 2 (opt) — делай только если профилирование покажет нужду |
|---|---|---|
| Traversal / culling | `21-gpu-simple-traversal-mvp.md`: CPU-side frustum cull, простой линейный проход по видимым секциям, обычный draw call на секцию | `21-gpu-persistent-traversal-shader-opt.md`: GPU persistent-kernel traversal — только если CPU traversal сам стал узким местом |
| Кэш секций | `08-storage-section-cache-mvp.md`: обычная `ConcurrentHashMap<Long, WorldSectionHandle>` с одним `ReentrantLock` | `08-storage-section-cache-opt.md`: 64-shard bit-mixed кэш — только если профилирование покажет contention на локе кэша при высокой конкурентности |
| Очередь задач мешинга | `15-meshing-priority-queue-mvp.md`: `PriorityBlockingQueue` (простая, встроенная в JDK) | `15-meshing-work-stealing-queue-opt.md`: Chase-Lev work-stealing bucket queue — только если очередь показывает высокий contention/latency на холодном старте |
| Node buffer layout | `20-gpu-node-buffer-mvp.md`: AoS (один SSBO, одна структура на узел) | `20-gpu-node-buffer-soa-opt.md`: разделение на SoA (5 буферов) — только если профилирование покажет, что traversal memory-bound и bandwidth — узкое место |
| Occlusion culling | Только frustum test, никакого occlusion culling вообще в MVP | `22-gpu-hiz-occlusion-opt.md`: Hi-Z occlusion pyramid — только если сцены со значительным взаимным перекрытием геометрии показывают высокую overdraw-стоимость |
| Между-кадровая когерентность | Полный traversal каждый кадр (простой, предсказуемый) | `25-render-temporal-reprojection-opt.md`: temporal reprojection working set — только если профилирование покажет, что traversal cost на кадр значителен даже при неподвижной камере |
| Mip-агрегация для LOD | `16-meshing-mipgen-mvp.md`: обычный скалярный Java-цикл majority-vote | `16-meshing-simd-mipgen-opt.md`: SIMD Vector API — только если профилирование покажет, что именно mip-агрегация занимает существенную долю времени холодного старта |
| Draw calls | Обычный draw call на видимую секцию (не indirect) | `23-gpu-indirect-multidraw-opt.md`: GPU-driven indirect multi-draw — только если число draw calls само стало узким местом (много мелких видимых секций) |
| Dirty-tracking при правке блока | `26-render-dirty-tracking-mvp.md`: полная пересборка секции целиком + дедупликация по хэшу | `26-render-dirty-subregion-opt.md`: sub-region dirty tracking + расширение GreedyMeshStage — только если частые правки блоков реально создают заметные фризы |
| GPU upload | Обычный upload на каждую готовую секцию (часть тикета 17) | `19-gpu-upload-batching-opt.md`: батч-upload через staging ring buffer — только если холодный старт показывает высокую долю времени в driver call overhead на upload |
| Coarse-to-fine heightmap generation | **Оставлен в MVP как есть** (тикет `09`, не переименован) — см. обоснование ниже | — |
| Screen-space error LOD selection | **Оставлен в MVP как есть** (тикет `14`, не переименован) — см. обоснование ниже | — |
| Двухъярусный near-player приоритет | **Оставлен в MVP как есть** (часть тикета `14`) | — |
| √2 near-cutoff geometry fix | **Оставлен в MVP как есть** (часть тикета `27`) | — |

### Совместимость контрактов между mvp/opt парами — не одинакова для всех подсистем

Степень API-совместимости между MVP- и opt-версией одной подсистемы **различается по
подсистемам**, не универсальна:

- **Полностью совместимы** (одинаковые сигнатуры конструктора/методов, переход прозрачен
  для вызывающего кода): `SectionCache` (08), `MipAggregator` (16).
- **Частично совместимы, с явно задокументированным отличием одного параметра**:
  `MeshTaskQueue` (15) — opt-версия добавляет параметр `workerHint`, отсутствующий в MVP.
- **Архитектурно несовместимы, вызывающий код требует переписывания** (разные классы, разные
  наборы методов — это ожидаемо, не недосмотр): `NodeBuffer`(mvp)/`NodeBufferSoA`(opt) — 20;
  `SimpleTraversal`(mvp)/GPU compute pipeline(opt) — 21; `DirtySectionTracker`(mvp)/
  `DirtyRegionTracker`+`SubRegion`(opt) — 26. Для этих трёх пар "замена реализации" на
  практике означает переписать код, который их вызывает (в первую очередь
  `EVInstance.renderFarLod`, тикет 27) — учитывай это в оценке объёма работы при
  переходе на соответствующий opt-тикет, не ожидай drop-in замены.

### Почему некоторые тикеты НЕ разделены на MVP/opt

Три категории тикетов остаются как в первой версии плана, без MVP-упрощения:

1. **Чистые контракты без внутренней сложности** (тикеты 00-05: skeleton, API-интерфейсы) —
   не имеют "сложной" и "простой" версии, это просто интерфейсы, реализуются один раз.
2. **Тикеты, где эмпирически подтверждённая проблема слишком дёшево предотвратить заранее,
   чтобы откладывать до профилирования** (тикет 09 — coarse-to-fine, тикет 14 — screen-space
   error и двухъярусный приоритет, часть тикета 27 — √2 near-cutoff): это не "оптимизации
   производительности" в смысле CPU/GPU cost, а исправления, предотвращающие видимые,
   пользователь-ощутимые баги (дыры в геометрии, неверный выбор LOD, кольцо необработанных
   секций вокруг игрока) — цена их реализации сразу мала, а цена их отсутствия — заметный
   баг, не просто просевший FPS. Разделять эти на "MVP без них / opt с ними" не имеет смысла:
   версия "без них" будет не просто медленнее, а *визуально сломана*.
3. **Инфраструктурные тикеты, не про рантайм-производительность** (07 — schema migration,
   17-18 — базовые GL буферы/шейдеры, 24 — FrameGraph, 27-30 — NeoForge интеграция/конфиг/
   команды/тесты, 31 — интеграция, 32 — project index) — либо инфраструктура, нужная в любом
   случае, либо архитектурный паттерн (FrameGraph), полезный независимо от того, простая или
   сложная логика внутри его passes.

## Порядок выполнения

```
00-project-skeleton.md
  → 32-project-index.md (веди PROJECT_INDEX.md с этого момента)
  → 01-api-sectionpos.md, 02-api-storage-interfaces.md, 03-api-meshing-interfaces.md,
    04-api-renderbackend-interfaces.md, 05-api-metrics-interfaces.md (api contracts)
  → 06-storage-palette-codec.md, 07-storage-schema-migration.md
  → 08-storage-section-cache-mvp.md (простой кэш)
  → 09-storage-heightmap-coarse-gen.md (coarse-to-fine, без изменений)
  → 10-meshing-occupancy-stage.md, 11-meshing-greedy-mesh-stage.md,
    12-meshing-material-bin-stage.md, 13-meshing-meshlet-pack-stage.md
  → 14-meshing-priority-function.md (priority + screen-space error + двухъярусный приоритет)
  → 15-meshing-priority-queue-mvp.md (простая очередь)
  → 17-gpu-backend-gl-buffers.md, 18-gpu-backend-gl-shaders.md
  → 20-gpu-node-buffer-mvp.md (AoS node buffer)
  → 21-gpu-simple-traversal-mvp.md (CPU-side traversal, простой culling)
  → 24-render-frame-graph.md (FrameGraph, без изменений)
  → 26-render-dirty-tracking-mvp.md (whole-section rebuild + geometry hash dedup)
  → 27-neoforge-mod-entrypoint.md, 28-neoforge-config.md, 29-neoforge-commands.md,
    30-test-fake-render-backend.md
  → 31-integration-checklist-mvp.md (интеграция ТОЛЬКО MVP-версий)

  === ЗАПУСТИ, ПОИГРАЙ, ЗАМЕРЬ ===

  → P0-profiling-checkpoint.md (обязателен перед любым opt-тикетом)

  === НА ОСНОВЕ РЕЗУЛЬТАТОВ P0, ВЫБОРОЧНО (файлы с суффиксом -opt): ===
  → 08-storage-section-cache-opt.md
  → 15-meshing-work-stealing-queue-opt.md
  → 16-meshing-simd-mipgen-opt.md
  → 19-gpu-upload-batching-opt.md
  → 20-gpu-node-buffer-soa-opt.md
  → 21-gpu-persistent-traversal-shader-opt.md (см. явную рекомендацию: сначала 25-opt)
  → 22-gpu-hiz-occlusion-opt.md
  → 23-gpu-indirect-multidraw-opt.md
  → 25-render-temporal-reprojection-opt.md
  → 26-render-dirty-subregion-opt.md
  → после каждого применённого opt-тикета: сокращённая переинтеграция
    (см. 31-integration-checklist-full.md, раздел "Промежуточная интеграция")
```

Примечание: `16-meshing-mipgen-mvp.md` — MVP-версия mip-агрегации, реализуется как часть
Волны 1 наравне с прочими MVP-тикетами (не указана отдельно в основном списке выше только
для краткости; выполни её в естественном месте — до или вместе с остальными
`meshing`-тикетами, зависимости у неё нет ни на что, кроме JDK).

## Как использовать в новой сессии

1. Новая сессия Клода (Claude Code рекомендуется).
2. Первым сообщением — этот файл (`MVP_INDEX.md`) целиком.
3. Вторым — текст одного тикета (например, `08-storage-section-cache-mvp.md`).
4. **Явно указывай, какую волну ты сейчас выполняешь** — если модель видит только текст
   MVP-тикета, она не будет пытаться сама решить, что "лучше сразу сделать сложную версию".
   Названия файлов (`-mvp`/`-opt` суффиксы) сами по себе служат этим сигналом.

## Модульная структура

```
ev/
├── ev-api/        # чистые интерфейсы, ноль зависимостей на LWJGL/NeoForge — ОДНИ И ТЕ ЖЕ
│                          контракты для MVP и opt версий, это и есть смысл разделения
├── ev-storage/     # персистентное воксельное хранилище, без GL
├── ev-meshing/     # генерация геометрии из вокселей, без GL
├── ev-gpu/         # ЕДИНСТВЕННЫЙ модуль с LWJGL/OpenGL кодом
├── ev-render/      # оркестрация кадра, использует ev-gpu через интерфейс
├── ev-neoforge/    # entrypoint, события NeoForge, конфиг
└── ev-test/        # тесты, включая FakeRenderBackend
```

Правило зависимостей (однонаправленно, снизу вверх):
`api ← storage ← meshing ← render ← neoforge`, отдельно `api ← gpu ← render`.
`gpu` не знает о вокселях. `storage`/`meshing` не знают о GL.

## Таблица тикетов, модулей и зависимостей

Тикеты внутри одного слоя можно выполнять в любом порядке или параллельно (в разных
сессиях). Слои — строго по порядку: каждый следующий слой полагается на **интерфейсы** (не
реализацию) тикетов предыдущих слоёв. Колонка "Зависит от" указывает зависимости на уровне
`ev-api`-контрактов — они одинаковы для mvp- и opt-версии одного и того же номера тикета.

| # | Файл(ы) | Модуль | Что делает | Зависит от |
|---|---|---|---|---|
| 0 | `00-project-skeleton.md` | (root) | Gradle multi-module, NeoForge toolchain, mods.toml | — |
| 32 | `32-project-index.md` | (root) | **PROJECT_INDEX.md** — живая карта проекта, ведётся всеми последующими тикетами. Выполни сразу после 00, до остальных | тикет 0 |
| 1 | `01-api-sectionpos.md` | api | Кодирование позиции LOD-секции в long | — |
| 2 | `02-api-storage-interfaces.md` | api | VoxelStorage, WorldSectionHandle | тикет 1 |
| 3 | `03-api-meshing-interfaces.md` | api | MeshBuilder, MeshletBatch | тикет 1, 2 |
| 4 | `04-api-renderbackend-interfaces.md` | api | RenderBackend, GpuBuffer, ComputePipeline | — |
| 5 | `05-api-metrics-interfaces.md` | api | MetricsRegistry | — |
| 6 | `06-storage-palette-codec.md` | storage | Палитровое кодирование + RLE вокселей | тикет 2 |
| 7 | `07-storage-schema-migration.md` | storage | Версионированный формат + миграции | тикет 2 |
| 8 | `08-storage-section-cache-mvp.md` / `-opt.md` | storage | Кэш секций: mvp — `ConcurrentHashMap`+один лок; opt — шардированный (lock-striping) | тикет 1, 2, 5 |
| 9 | `09-storage-heightmap-coarse-gen.md` | storage | Coarse-to-fine генерация из heightmap (не разделён на mvp/opt) | тикет 1, 2, 6 |
| 10 | `10-meshing-occupancy-stage.md` | meshing | Occupancy-маска вокселей секции | тикет 2 |
| 11 | `11-meshing-greedy-mesh-stage.md` | meshing | Greedy 2D-quad meshing | тикет 10 |
| 12 | `12-meshing-material-bin-stage.md` | meshing | Группировка quad'ов по материалу | тикет 11 |
| 13 | `13-meshing-meshlet-pack-stage.md` | meshing | Упаковка в GPU meshlet-формат | тикет 3, 12 |
| 14 | `14-meshing-priority-function.md` | meshing | Screen-space error + двухъярусный приоритет (не разделён на mvp/opt) | тикет 1 |
| 15 | `15-meshing-priority-queue-mvp.md` / `-work-stealing-queue-opt.md` | meshing | mvp — `PriorityBlockingQueue`; opt — Chase-Lev deque bucket queue | тикет 14 |
| 16 | `16-meshing-mipgen-mvp.md` / `-simd-mipgen-opt.md` | meshing | mvp — скалярная агрегация; opt — Vector API SIMD | тикет 6 |
| 17 | `17-gpu-backend-gl-buffers.md` | gpu | GLRenderBackend: буферы/текстуры (не разделён на mvp/opt) | тикет 4 |
| 18 | `18-gpu-backend-gl-shaders.md` | gpu | Компиляция шейдеров, автобиндинг (не разделён на mvp/opt) | тикет 4, 17 |
| 19 | `19-gpu-upload-batching-opt.md` | gpu | Staging buffer батч-заливка (только opt; в MVP — обычный upload, часть тикета 17) | тикет 17 |
| 20 | `20-gpu-node-buffer-mvp.md` / `-soa-opt.md` | gpu | mvp — AoS, один SSBO; opt — SoA layout (5 буферов) | тикет 17 |
| 21 | `21-gpu-simple-traversal-mvp.md` / `-persistent-traversal-shader-opt.md` | gpu | mvp — CPU-side frustum cull; opt — GLSL persistent-kernel traversal | тикет 18, 20 |
| 22 | `22-gpu-hiz-occlusion-opt.md` | gpu | Hi-Z пирамида + occlusion тест (только opt; в MVP — только frustum test) | тикет 17, 18 |
| 23 | `23-gpu-indirect-multidraw-opt.md` | gpu | Indirect command buffer рендер (только opt; в MVP — обычный draw call на секцию) | тикет 17, 20 |
| 24 | `24-render-frame-graph.md` | render | FrameGraphBuilder, авто-барьеры (не разделён на mvp/opt) | тикет 4 |
| 25 | `25-render-temporal-reprojection-opt.md` | render | Working set + delta pass (только opt; в MVP — полный traversal каждый кадр) | тикет 21, 24 |
| 26 | `26-render-dirty-tracking-mvp.md` / `-dirty-subregion-opt.md` | render | mvp — whole-section rebuild + hash dedup; opt — sub-region dirty tracking | тикет 2, 11 |
| 27 | `27-neoforge-mod-entrypoint.md` | neoforge | Mod-класс, регистрация событий, инициализация worker pool | тикет 0, 24 |
| 28 | `28-neoforge-config.md` | neoforge | Конфиг (render distance, VRAM budget) | тикет 0 |
| 29 | `29-neoforge-commands.md` | neoforge | Debug-команды (/ev ...) | тикет 5, 27 |
| 30 | `30-test-fake-render-backend.md` | test | In-memory RenderBackend для тестов | тикет 4 |
| 31 | `31-integration-checklist-mvp.md` / `-full.md` | (root) | mvp — интеграция ТОЛЬКО MVP-версий; full — итоговая интеграция после применения opt-тикетов | все, включая 32 |

### PROJECT_INDEX.md — обязательно для всех тикетов начиная с 32

После выполнения тикета 32 в корне репозитория существует `PROJECT_INDEX.md` — живая карта
проекта (какие классы уже существуют, где лежат, финальные сигнатуры, если они отклонились
от контракта тикета). **Каждый тикет из списка выше, выполняемый ПОСЛЕ тикета 32, обязан
завершиться обновлением `PROJECT_INDEX.md`** согласно инструкции внутри самого тикета 32 —
это отдельный, обязательный финальный шаг, аналогичный "Критериям приёмки" данного тикета,
даже если конкретный текст тикета не упоминает `PROJECT_INDEX.md` явно. Если тикет 32 ещё не
выполнен на момент работы над каким-либо другим тикетом — пропусти этот шаг для сейчас, но
обрати внимание исполнителя тикета 32 (когда до него дойдёт очередь) на необходимость
ретроактивно задокументировать уже сделанное (см. требование 4 в тикете 32).

Критически важно: **интерфейсы из `ev-api` не меняются между волнами**. Это жёсткая
гарантия. MVP-реализация `SectionCache` и opt-реализация `SectionCache` (шардированная) —
пример класса, где ДОПОЛНИТЕЛЬНО (сверх обязательной стабильности `ev-api`) сохранена
идентичная сигнатура конструктора и методов между mvp/opt парой, чтобы переход не требовал
менять вызывающий код — это разумная цель для большинства mvp/opt пар, но НЕ универсальная
гарантия для каждой без исключения: `MeshTaskQueue` (тикет 15) — явное, задокументированное
исключение (сигнатура `submit` отличается на один параметр, `workerHint`, которого не
существует в MVP-версии — см. `MODEL_ASSIGNMENT.md`-подобную сверку или сами тексты
`15-meshing-priority-queue-mvp.md`/`15-meshing-work-stealing-queue-opt.md`). Перед переходом
с любой mvp-версии на её opt-пару — сверься с обоими текстами тикетов на предмет подобных
точечных расхождений сигнатур внутренних (не `ev-api`) классов, а не полагайся на этот
абзац как на абсолютную гарантию для каждого конкретного класса.

## Общие технические договорённости (действуют для всех тикетов, MVP и opt одинаково)

- Java 21, NeoForge 1.21.1, Gradle (NeoGradle/ModDevGradle).
- Пакет верхнего уровня: `dev.ev`.
- Никакого мутируемого статического состояния (`static` изменяемые поля) — всё через явно
  передаваемый контекст/инстанс.
- Каждый публичный класс/метод — Javadoc на английском (код-конвенция), комментарии в теле
  можно на русском, если поясняют неочевидную математику/причину решения.
- Файлы держим компактными: если класс превышает ~300 строк, это сигнал разбить на несколько.
- Каждый тикет, где это применимо, требует юнит-тестов (JUnit 5) без GPU-контекста.
- Не использовать `Vector`/`Hashtable`/устаревшие synchronized-коллекции; предпочитать
  `java.util.concurrent`, `it.unimi.dsi.fastutil` (для примитивных коллекций — уже есть в
  экосистеме подобных модов, лёгкая зависимость) там, где это снижает аллокации.
