# Тикет 29 — Debug-команды (/ev ...)

## Контекст
EV — мод дальней прорисовки для NeoForge 1.21.1. Модуль `ev-neoforge`. Реализует
игровые команды для наблюдаемости, использующие `MetricsRegistry` (тикет 05) — архитектурный
принцип проекта "наблюдаемость с первого дня, не постфактум под debug-флагом" (см.
ARCHITECTURE.md раздел 8). Команды позволяют игроку/разработчику получить снимок текущих
метрик (queue depth, cache hit rate, GPU pass timing) без пересборки мода и без включения
специального debug-флага компиляции.

## Готовый контракт из зависимостей (тикеты 05, 27 — уже реализованы)
```java
package dev.ev.api.metrics;
public interface MetricsRegistry {
    MetricsSnapshot snapshot();
}
public record MetricsSnapshot(
    java.util.Map<String, Integer> queueDepths,
    java.util.Map<String, Double> cacheHitRates,
    java.util.Map<String, Long> gpuPassDurationsNanos,
    java.util.Map<String, Long> counters,
    ImportStageStatus importStageStatus
) {}
public record ImportStageStatus(
    int queuedForRead, int retryingAfterFailure, int activelyBuilding,
    int completed, int totalKnown
) {
    public double completionFraction();
}

package dev.ev.neoforge;
public final class EVInstance implements AutoCloseable {
    // Assume (per ticket 27) this class exposes, or can be extended to expose,
    // access to a MetricsRegistry instance — if ticket 27's implementation does
    // not yet have a getter for it, add one (e.g. `MetricsRegistry metrics()`)
    // as a small, clearly-documented extension to that class in this ticket.
}
```

## Задача

### `EVCommands`
```java
package dev.ev.neoforge.command;

/**
 * Registers /ev debug commands using NeoForge's Brigadier-based command API
 * (verify exact registration entrypoint/event for NeoForge 1.21.1 via web search —
 * command registration typically hooks into RegisterCommandsEvent or equivalent;
 * confirm current class/package names rather than assuming from memory).
 *
 * Subcommands:
 *   /ev debug            - prints a one-shot MetricsSnapshot summary to the
 *                                issuing player's chat (or command source feedback).
 *   /ev debug watch      - toggles a persistent on-screen debug overlay
 *                                (rendered via ticket 27's render hook or a
 *                                dedicated HUD render event — implementation of
 *                                the actual overlay rendering may be deferred to
 *                                a future ticket if it requires significant new
 *                                rendering infrastructure; this command's scope
 *                                is to toggle the state flag and provide the data,
 *                                even if the visual overlay itself is a stub/TODO
 *                                clearly marked as such).
 *   /ev profile <passName> - enables detailed GPU timer-query profiling for
 *                                one named frame-graph pass (see ARCHITECTURE.md
 *                                section 8 — "detailed GPU-timing enabled point-wise
 *                                via /ev profile <pass> without shader
 *                                recompilation"). MVP NOTE: as of the MVP wave
 *                                (see MVP_INDEX.md), there is no GPU-side
 *                                multi-pass pipeline yet — 21-gpu-simple-traversal-mvp.md's
 *                                CPU-side traversal already reports its own timing
 *                                under the pass name "cpu-traversal" via
 *                                MetricsRegistry.recordGpuPassDuration (reusing that
 *                                method for CPU wall-clock time, per that ticket's
 *                                own Javadoc) — so `/ev profile cpu-traversal`
 *                                should work out of the box (the data is already in
 *                                MetricsSnapshot.gpuPassDurationsNanos()) without
 *                                needing any actual GPU timer query mechanism;
 *                                genuine GPU timer queries only become relevant
 *                                once GPU-side passes exist (post 21-opt/22-opt/etc).
 *                                If the underlying RenderBackend/
 *                                CommandList contract (ticket 04) does not yet expose
 *                                a way to enable per-pass GPU timer queries, implement
 *                                this command's plumbing up to the point where it
 *                                would call such a mechanism, with a clearly marked
 *                                TODO noting the missing lower-level capability
 *                                (do not silently no-op without feedback to the user —
 *                                the command should at least acknowledge the request
 *                                and state that detailed timing is not yet available,
 *                                if that's the current state of the dependency).
 *   /ev reload-config     - re-reads EVConfig (ticket 28) and applies any
 *                                hot-reloadable settings (document which settings
 *                                are safe to hot-reload — e.g. screenSpaceErrorThresholdPx
 *                                is probably safe; workerThreadCount likely requires
 *                                a full restart/instance recreation — be explicit
 *                                about which is which rather than pretending
 *                                everything hot-reloads cleanly).
 */
public final class EVCommands {
    public static void register(/* CommandDispatcher<CommandSourceStack> dispatcher, verify exact type via search */ Object dispatcher);
}
```

### `MetricsSnapshotFormatter`
```java
package dev.ev.neoforge.command;

import dev.ev.api.metrics.MetricsSnapshot;

/**
 * Formats a MetricsSnapshot into a human-readable multi-line string for chat/log
 * output. Pure function, no NeoForge dependency, independently testable.
 */
public final class MetricsSnapshotFormatter {
    public static String format(MetricsSnapshot snapshot);
}
```

## Требования к реализации

1. **Обязательно используй web search** для подтверждения актуального способа регистрации
   команд в NeoForge 1.21.1 (событие/интерфейс для `RegisterCommandsEvent` или его текущий
   эквивалент, точный тип `CommandDispatcher<CommandSourceStack>` или его актуальный
   NeoForge-специфичный эквивалент) — не полагайся на память, Brigadier-интеграция могла
   измениться в деталях между версиями.

2. **`MetricsSnapshotFormatter.format`**: выведи каждую категорию метрик
   (`queueDepths`/`cacheHitRates`/`gpuPassDurationsNanos`/`counters`) отдельной секцией с
   заголовком, отсортированной по ключу (для детерминированного, воспроизводимого вывода —
   важно для тестируемости и для того, чтобы вывод не "прыгал" между вызовами из-за
   произвольного порядка итерации `Map`). Формат — простой человекочитаемый текст (не JSON),
   пригодный для вставки в чат Minecraft: например,
   ```
   === EV Debug Snapshot ===
   Import Progress: 1234/5000 (24.7%)
     queued: 3200  retrying: 12  building: 554  done: 1234
   Queue Depths:
     mesh-build: 1234
     upload-staging: 56
   Cache Hit Rates:
     section-cache: 87.3%
   GPU Pass Durations:
     hiz-build: 0.45ms
     traversal: 1.20ms
   Counters:
     sections-loaded: 98765
   ```
   (конкретное форматирование — на твоё усмотрение, главное: детерминированный порядок,
   читаемость, разумные единицы измерения — переведи наносекунды в миллисекунды для
   удобства чтения, проценты для hit rate вместо сырой дроби 0..1). Секция "Import Progress"
   (из `MetricsSnapshot.importStageStatus()`) должна идти ПЕРВОЙ, перед остальными
   категориями — это explicit staged breakdown, а не просто ещё одна категория метрик,
   смоделированная по образцу `/ev status` из независимой реализации (Exceptional Vision,
   см. также тикет 05 для полного обоснования), где именно этот вид разбивки оказался
   наиболее полезным для диагностики "застрял ли импорт" на практике — не переставляй его
   вниз списка вместе с прочими метриками.

3. **`/ev debug`** — получает `MetricsRegistry` через `EVInstance` (текущего
   активного мира — если `EVInstance` недоступен, например, мир не загружен/мод ещё не
   инициализирован, команда должна вернуть понятное сообщение об ошибке пользователю, не
   упасть с NPE).

4. **Permission level**: используй разумный уровень прав для debug-команд (например,
   `требуется OP` для сервера/интеграции с multiplayer, если применимо — для чисто
   клиентского far-render мода это может быть не так критично, но задокументируй решение;
   уточни через поиск, является ли команда client-only и какой permission-механизм уместен
   в этом случае).

5. Никакого мутируемого статического состояния, кроме единственного,
   задокументированного и архитектурно неизбежного случая — если состояние "включён ли
   watch overlay" нужно хранить где-то доступном между вызовами команды и рендер-хуком, это
   должно быть instance-полем `EVInstance` (или аналогичного non-static
   controller-объекта), не голым `static boolean` в `EVCommands`.

## Юнит-тесты (обязательно, JUnit 5)

Создай `MetricsSnapshotFormatterTest`:
1. Пустой `MetricsSnapshot` (все карты пустые) → форматированная строка не бросает
   исключение, содержит заголовки секций (даже если под ними ничего нет), не содержит
   мусора/null-строк.
2. `MetricsSnapshot` с несколькими значениями в каждой категории → вывод содержит все
   ожидаемые значения, отформатированные согласно требованию 2 (детерминированный
   отсортированный порядок — протестируй, вставив ключи в НЕ отсортированном порядке в
   исходную `Map`, проверь, что вывод всё равно отсортирован).
3. Значение `gpuPassDurationsNanos` конвертируется в миллисекунды с разумной точностью
   отображения (например, `1_200_000` наносекунд → что-то вроде "1.20ms", протестируй
   точное ожидаемое форматирование, которое ты выберешь).
4. Значение `cacheHitRates` (например, `0.873`) конвертируется в проценты (например,
   "87.3%").
5. `MetricsSnapshot` с непустым `importStageStatus` (например, `queuedForRead=3200,
   retryingAfterFailure=12, activelyBuilding=554, completed=1234, totalKnown=5000`) → вывод
   содержит секцию "Import Progress" ПЕРВОЙ (перед Queue Depths/Cache Hit Rates/прочими
   секциями), с корректно посчитанным процентом (`24.7%` для приведённых чисел — сверь с
   `completionFraction()`) и всеми четырьмя стадийными числами.
6. `MetricsSnapshot` с `importStageStatus = ImportStageStatus.empty()` (`totalKnown == 0`) —
   секция "Import Progress" всё равно присутствует (не пропущена), процент отображается как
   `0.0%` или аналогичное разумное представление "нечего показывать", не `NaN%`/exception.

## Критерии приёмки
1. Файлы в `ev-neoforge/src/main/java/dev/ev/neoforge/command/`:
   `EVCommands.java`, `MetricsSnapshotFormatter.java`.
2. Все юнит-тесты на `MetricsSnapshotFormatter` проходят (не требуют NeoForge runtime).
3. `EVCommands` использует подтверждённый через web search актуальный NeoForge 1.21.1
   API регистрации команд.
4. Модуль компилируется успешно.
5. Каждая под-команда, чья полная функциональность зависит от ещё не завершённой части
   другого тикета (например, GPU timer queries для `/ev profile`), явно
   задокументирована как частичная/TODO с указанием, чего не хватает, а не тихо
   реализована как no-op без обратной связи пользователю.
