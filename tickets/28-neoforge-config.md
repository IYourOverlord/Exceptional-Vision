# Тикет 28 — EVConfig (конфигурация мода)

## Контекст
EV — мод дальней прорисовки для NeoForge 1.21.1. Модуль `ev-neoforge`. Реализует
конфигурацию мода: render distance, VRAM-бюджет (используемый для адаптивного расчёта
размеров очередей — см. ARCHITECTURE.md раздел 6.3, `QueueBudget`), пороги screen-space
error (тикет 14 — актуально в обеих волнах, не разделён на mvp/opt), и прочие настраиваемые
параметры, накопленные в предыдущих тикетах как "не хардкодь, вынеси в конфиг".

**Важно про MVP/opt структуру плана** (см. `MVP_INDEX.md`): поля
`coherenceMaxPositionalDeltaBlocks`/`coherenceMaxAngularDeltaRadians` ниже относятся к
temporal coherence (`25-render-temporal-reprojection-opt.md`) — это функциональность Волны 2
(opt), которой в MVP-версии проекта ещё не существует (`21-gpu-simple-traversal-mvp.md` не
использует эти пороги вообще). Включай эти поля в `EVConfig` уже сейчас (это дёшево —
неиспользуемые поля конфига не вредят, а избавляет от необходимости менять record позже,
когда/если `25-opt` будет применён), но явно задокументируй в Javadoc самих полей, что они
пока не используются никаким MVP-кодом — не создавай впечатление, что temporal coherence уже
часть базовой функциональности мода.

## Задача

### `EVConfig`
```java
package dev.ev.neoforge.config;

/**
 * Immutable, validated configuration for all EV subsystems. Loaded once at
 * startup (see ticket 27's onClientSetup/level-load flow) via NeoForge's config
 * API (verify current recommended approach — ModConfigSpec/Builder pattern — via
 * web search for NeoForge 1.21.1, as config APIs have shifted from older Forge
 * conventions), with sane defaults for every field so the mod works out of the
 * box without requiring the user to touch a config file.
 */
public record EVConfig(
    int maxRenderDistanceBlocks,       // default: e.g. 4096
    long vramBudgetBytes,               // default: auto-detect via GL query, see requirement 3; 0 = auto
    float screenSpaceErrorThresholdPx,  // default: 1.5f, see ticket 14 — used by MVP code today
    float coherenceMaxPositionalDeltaBlocks, // default: e.g. 8.0f — NOT used by any MVP code
                                               // as of this ticket; only consumed if/when
                                               // 25-render-temporal-reprojection-opt.md is applied
    float coherenceMaxAngularDeltaRadians,   // default: e.g. toRadians(15) — same caveat as above
    int workerThreadCount,              // default: e.g. Runtime.getRuntime().availableProcessors() / 2, min 2
    boolean enableDebugOverlay          // default: false
) {
    public static EVConfig defaults() {
        return new EVConfig(
            4096, 0L, 1.5f, 8.0f, (float) Math.toRadians(15), 
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2), false
        );
    }

    /**
     * Validates field ranges (e.g. maxRenderDistanceBlocks > 0, screenSpaceErrorThresholdPx > 0,
     * workerThreadCount >= 1) and returns a corrected copy with any out-of-range values
     * clamped to safe bounds, logging a warning for each correction made (rather than
     * throwing — a malformed user config file should degrade gracefully to safe defaults
     * for the offending field, not crash mod loading).
     */
    public EVConfig validated();
}
```

### `EVConfigLoader`
```java
package dev.ev.neoforge.config;

/**
 * Bridges EVConfig to NeoForge's config file system (TOML-backed, via
 * ModConfigSpec or the current NeoForge 1.21.1 equivalent — verify exact API via
 * web search before implementing, do not assume specifics from older Forge
 * versions or from memory, as this area of NeoForge has changed).
 */
public final class EVConfigLoader {
    /** Registers the config spec with NeoForge's config system during mod construction. */
    public static void register(/* IEventBus modBus or ModContainer, per current NeoForge API — verify via search */ Object modBus);

    /** Reads current values from the registered config into a EVConfig snapshot. Call after config is loaded (verify correct timing hook via search — e.g. a ModConfigEvent.Loading/Reloading listener). */
    public static EVConfig current();
}
```

## Требования к реализации

1. **Обязательно используй web search** для подтверждения актуального NeoForge 1.21.1 API
   для mod-конфигурации (класс `ModConfigSpec`/`ModConfigSpec.Builder` или его текущий
   эквивалент, способ регистрации, способ подписки на событие загрузки/перезагрузки
   конфига) — не полагайся на память, конфигурационный API периодически меняется между
   версиями Forge/NeoForge.

2. **`vramBudgetBytes` auto-detect**: если пользователь оставил значение `0` (авто), опиши
   (можно как TODO с чётким указанием, к какому тикету это относится, если сам GL-запрос
   VRAM недоступен на момент этого тикета) логику получения объёма доступной видеопамяти
   через GL-запрос (например, `GL_NVX_gpu_memory_info` для NVIDIA или
   `WGL_AMD_gpu_association`/аналоги для AMD — такие расширения не универсальны для всех
   вендоров; задокументируй, что универсального кросс-вендорного способа узнать точный VRAM
   через чистый OpenGL нет, и предложи разумный fallback: если расширение недоступно,
   используй консервативную дефолтную оценку, например 2 ГБ, с логированием предупреждения).
   Реализация самого GL-запроса, если она требует изменений в `ev-gpu`
   (`GLRenderBackend`), может быть оформлена как явно помеченное расширение контракта,
   либо оставлена как TODO, ссылающийся на будущий тикет — не блокируй этот тикет полной
   реализацией auto-detect, если это требует значительной работы в другом модуле; главное,
   чтобы `EVConfig` как структура данных и `validated()` логика были полностью готовы.

3. **`validated()`**: реализуй конкретные разумные границы для каждого поля (например,
   `maxRenderDistanceBlocks` — минимум 128, максимум, скажем, 65536; `workerThreadCount` —
   минимум 1, максимум `Runtime.getRuntime().availableProcessors()`) — выбери сам разумные
   границы, задокументируй в Javadoc constants, откуда они взялись (не обязаны быть
   "научно" обоснованы, но не должны быть абсурдными, например разрешающими
   `workerThreadCount = -5` без коррекции).

4. Никакого мутируемого статического состояния в `EVConfig` (record — уже immutable по
   природе). `EVConfigLoader` может иметь внутреннее static-состояние ТОЛЬКО в той
   мере, в какой это неизбежно требуется самим NeoForge config API (например, статическая
   ссылка на `ModConfigSpec`, которую сам фреймворк ожидает как platform-level singleton,
   аналогично `EV`-класса синглтону из тикета 27) — задокументируй, если это
   неизбежно, почему это архитектурно приемлемо (платформенное требование фреймворка, не
   произвольное дизайн-решение проекта).

## Юнит-тесты (обязательно, JUnit 5, независимо от реального NeoForge config API)

Создай `EVConfigTest`:
1. `defaults()` возвращает валидный (`validated()` не меняет ни одно поле) конфиг.
2. `validated()` на конфиге с `maxRenderDistanceBlocks = -1` возвращает конфиг с
   исправленным (клампнутым в допустимый минимум) значением, не бросает исключение.
3. `validated()` на конфиге с `workerThreadCount = 0` возвращает конфиг с минимум 1.
4. `validated()` на конфиге с `screenSpaceErrorThresholdPx = -5.0f` возвращает исправленное
   положительное значение.
5. `validated()` на уже валидном конфиге не изменяет значения (idempotent на валидных
   входах).

`EVConfigLoader` тестируется только в той мере, в какой его логика не завязана
напрямую на реальный NeoForge runtime (например, если ты вынесешь чистую
"TOML-value-to-EVConfig" маппинг-логику в отдельный тестируемый метод, отдельно от
самой регистрации с NeoForge API) — если весь класс неразрывно связан с NeoForge API и не
может быть протестирован без полного mod-loading окружения, задокументируй это ограничение
явно, тесты для него не обязательны в этом тикете.

## Критерии приёмки
1. Файлы в `ev-neoforge/src/main/java/dev/ev/neoforge/config/`:
   `EVConfig.java`, `EVConfigLoader.java`.
2. Все юнит-тесты на `EVConfig`/`validated()` проходят.
3. `EVConfigLoader` использует подтверждённый через web search актуальный NeoForge
   1.21.1 config API (не устаревший Forge-паттерн).
4. Модуль `ev-neoforge` компилируется успешно.
5. Границы валидации в `validated()` задокументированы в Javadoc с обоснованием выбранных
   значений.
