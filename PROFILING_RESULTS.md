# PROFILING_RESULTS.md

> Черновик. Раздел "Тестовое окружение" заполнен по факту из `debug.log`/`latest.log`
> (сессия 20 августа 2026, 20:27:17–20:30:16). Все метрики Части 1/2 тикета `P0-profiling-
> checkpoint.md` — TODO, потому что в проанализированных логах нет ни одного из требуемых
> источников (MetricsRegistry/ImportStageStatus, JFR, `/ev profile`, ручной секундомер на
> `/tp`). Не заполняй эти поля по догадке — тикет прямо это запрещает (см. "Требования к
> реализации", п.3). См. `jfr-profiling-runclient-snippet.md` за инструкцией, как получить
> реальные числа со следующего прогона.

Дата профилирования: TODO (дата фактического целевого прогона; лог от 20 Aug 2026 был
обычным запуском без включённого JFR/оверлея, для чекпоинта не годится как источник чисел)

Тестовое окружение:
- GPU: NVIDIA GeForce RTX 4060 Ti (драйвер 610.88, adapter type 0x0000030B)
- ОС: Windows 10 (arch amd64, version 10.0)
- JDK: Microsoft OpenJDK 64-Bit Server VM 21.0.7+6-LTS
- NeoForge: 21.1.233 (Minecraft 1.21.1, NeoForm 20240808.144430)
- Мод EV: ev-0.1.0-SNAPSHOT
- Размер/тип тестового мира: TODO — из логов виден мир "New World" с ванильной генерацией
  (overworld/nether/end/aeroworld/DIM1 присутствуют), но нет данных о степени прегенерации
  (Chunky и т.п.) или разнообразии рельефа в зоне теста; тикет явно требует достаточно
  большой мир с разнообразным рельефом — подтвердить перед профилирующим прогоном
- Конфиг EV на момент последнего известного лога: `maxRenderDistanceBlocks: 4096`,
  `screenSpaceErrorThresholdPx: 1.5`, `workerThreadCount: 8`, `enableDebugOverlay: false`,
  `vramBudgetBytes: 0` — ⚠ `enableDebugOverlay` нужно включить (`true`) перед прогоном для
  профилирования, см. Часть 2 п.3 тикета
- Прочее окружение сессии: заметное число сторонних модов (Sodium, Iris, C2ME, Create,
  ferritecore и др.) — потенциальный источник шума при интерпретации CPU/GPU breakdown,
  учитывай при анализе JFR/RenderDoc результатов

## Сценарий A — холодный старт
- Time-to-first-frame: TODO мс (требует ручного секундомера/видеозаписи при `/tp` на
  большую дистанцию — не встречается в текстовых логах)
- Time-to-visually-complete: TODO сек (аналогично, субъективное визуальное наблюдение)
- Throughput: TODO секций/сек (требует `MetricsRegistry.recordCounter`/`ImportStageStatus`;
  ни одного вызова этих компонентов не найдено в проверенных логах — либо тикеты 05/29 ещё
  не логируют эти метрики на INFO/DEBUG уровне, либо профилирующий прогон с ними не
  проводился)
- Разбивка по стадиям: storage I/O TODO%, meshing TODO%, GPU upload TODO%, render TODO%
  (требует JFR-запись, см. `jfr-profiling-runclient-snippet.md`)

## Сценарий B — steady-state
- Frame time (неподвижная камера): TODO мс
- Frame time (плавное движение): TODO мс
- Frame time (резкий разворот): TODO мс
- CPU/GPU breakdown: CPU traversal TODO%, GPU render TODO%
- Draw call count (типичный видимый кадр): TODO
- Cache/queue contention: TODO (да/нет, с конкретными числами из JFR)

## Решение по opt-тикетам

| opt-тикет | Диагностический признак присутствует? | Решение | Обоснование |
|---|---|---|---|
| `08-storage-section-cache-opt.md` | неизвестно | отложить | Нет JFR-данных по contention на `ReentrantLock` кэша секций — нечем подтвердить или опровергнуть |
| `15-meshing-work-stealing-queue-opt.md` | неизвестно | отложить | Нет JFR-данных по contention на `PriorityBlockingQueue` |
| `16-meshing-simd-mipgen-opt.md` | неизвестно | отложить | Нет разбивки времени холодного старта по стадиям — неизвестно, является ли mip-агрегация узким местом |
| `19-gpu-upload-batching-opt.md` | неизвестно | отложить | Нет разбивки по стадиям (GPU upload %) |
| `20-gpu-node-buffer-soa-opt.md` | неизвестно | отложить | Нет GPU-side memory bandwidth данных (требует RenderDoc или `/ev profile`) |
| `21-gpu-persistent-traversal-shader-opt.md` | неизвестно | отложить | Нет CPU/GPU breakdown за кадр |
| `22-gpu-hiz-occlusion-opt.md` | неизвестно | отложить | Нет данных по overdraw/fragment shader cost в сценах с перекрытием |
| `23-gpu-indirect-multidraw-opt.md` | неизвестно | отложить | Нет данных по draw call count и времени на их submission |
| `25-render-temporal-reprojection-opt.md` | неизвестно | отложить | Нет frame time при неподвижной камере/движении/развороте для сравнения |
| `26-render-dirty-subregion-opt.md` | неизвестно | отложить | Отдельный ad-hoc замер правок блоков не проводился |

**Ни одно решение не принято "внедрять" или "пропустить"** — по всем 10 пунктам стоит явное
"отложить", так как ни одна метрика фактически не измерена. Это соответствует требованию
тикета не гадать на основе интуиции. Следующий шаг — фактический профилирующий прогон по
инструкции в `jfr-profiling-runclient-snippet.md`, после чего эта таблица должна быть
переписана с конкретными числами и итоговыми решениями (внедрять/пропустить), не оставлена
в состоянии "отложить" повторно, если только конкретная метрика снова не окажется
пограничной.
