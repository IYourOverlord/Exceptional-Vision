# Тикет 24 — FrameGraphBuilder (декларативная оркестрация кадра)

## Контекст
EV — мод дальней прорисовки для NeoForge 1.21.1. Модуль `ev-render` — использует
`ev-gpu` ТОЛЬКО через интерфейс `RenderBackend` (тикет 04), никогда не импортирует
`org.lwjgl.*` напрямую. Реализует декларативное описание последовательности GPU-проходов
кадра (Hi-Z build → traversal → mesh upload → indirect draw), обоснованное в
ARCHITECTURE.md разделе 6.2: вместо ручной последовательности вызовов
`bindings()`/`glMemoryBarrier()`/`glDispatchCompute()`, разбросанной по методам (как это было
у прототипа Voxy), `FrameGraphBuilder` разрешает зависимости между проходами (по тому, что
они читают/пишут), автоматически вставляет минимально необходимые барьеры, и даёт единую
точку для GPU-таймингов на пасс.

## Готовый контракт из зависимости (тикет 04, уже реализован)
```java
package dev.ev.api.gpu;
public interface RenderBackend {
    void submit(CommandList commands);
    FenceHandle insertFence();
    // ... (см. полный контракт, тикет 04)
}
public interface CommandList {
    void memoryBarrier(BarrierScope scope);
    // ... (см. полный контракт, тикет 04)
}
public enum BarrierScope { SHADER_STORAGE, COMMAND, BUFFER_UPDATE, ALL }
```

## Задача

### `FrameResource` (маркер зависимости между passes)
```java
package dev.ev.render.framegraph;

/**
 * Opaque handle identifying a logical resource (buffer, texture) that flows
 * between frame graph passes. Two passes that reference the same FrameResource
 * instance (one via writes(), another via reads()) establish an ordering
 * dependency resolved by FrameGraphBuilder.
 */
public final class FrameResource {
    public FrameResource(String debugName) { /* ... */ }
    public String debugName();
}
```

### `FramePass`
```java
package dev.ev.render.framegraph;

/** A single unit of GPU work within a frame, e.g. one compute dispatch or one draw call batch. */
public interface FramePass {
    /** Records this pass's GPU commands. Called by FrameGraphBuilder.execute() in dependency order. */
    void record(dev.ev.api.gpu.CommandList commands);

    /** Short identifier used for metrics (see MetricsRegistry.recordGpuPassDuration) and debug labeling. */
    String name();
}
```

### `PassBuilder` (fluent handle returned by addPass, used to declare reads/writes)
```java
package dev.ev.render.framegraph;

public interface PassBuilder {
    PassBuilder reads(FrameResource... resources);
    PassBuilder writes(FrameResource... resources);
    /** Returns a FrameResource representing this pass's primary output, for chaining
     * into subsequent passes' reads() without needing a separately declared resource
     * when there's exactly one natural output. */
    FrameResource output();
}
```

### `FrameGraphBuilder`
```java
package dev.ev.render.framegraph;

import dev.ev.api.gpu.RenderBackend;
import dev.ev.api.metrics.MetricsRegistry;
import java.util.function.Supplier;

/**
 * Collects FramePass declarations with their read/write FrameResource dependencies,
 * topologically orders them, inserts memory barriers between passes with a
 * write-then-read (or write-then-write) dependency on a shared resource, and
 * executes them via a single RenderBackend.submit() call per frame (or per
 * logical frame-graph execution — see requirement 3).
 *
 * Barrier insertion policy: a barrier is inserted before any pass P if some
 * earlier-executing pass Q writes a FrameResource that P reads or writes.
 * Passes with no dependency relationship may be recorded in any relative order
 * (this class picks one valid topological order deterministically — document
 * which, e.g. insertion order among passes with no forced ordering).
 */
public final class FrameGraphBuilder {

    public FrameGraphBuilder(RenderBackend backend, MetricsRegistry metrics) { /* ... */ }

    /**
     * @param passFactory supplies a fresh FramePass instance; called once per
     *        execute() invocation (not once per addPass call), so the same
     *        FrameGraphBuilder graph structure can be reused across frames while
     *        each pass captures fresh per-frame data via closures/state passed
     *        to the factory — decide and document the exact re-use contract.
     */
    PassBuilder addPass(String name, Supplier<FramePass> passFactory);

    /**
     * Validates the graph (detects cycles — throws IllegalStateException if found,
     * since a cyclic dependency between passes is a programming error, not a
     * runtime condition to recover from), computes a topological execution order,
     * records all passes' commands into a single CommandList with barriers inserted
     * per the policy above, and submits it via RenderBackend.submit(). Also records
     * each pass's GPU duration via MetricsRegistry.recordGpuPassDuration if the
     * RenderBackend/CommandList surfaces per-pass timing (if not directly available
     * from the ticket-04 contract, wrap each pass's CPU-side recording time as a
     * best-effort proxy and document this limitation — real GPU timer queries may
     * require extending the ticket-04 contract in a future ticket, out of scope here).
     */
    void execute();
}
```

## Требования к реализации

1. **Граф зависимостей**: внутреннее представление — например, `Map<FrameResource,
   List<FramePassNode>>` (кто пишет каждый ресурс) + `Map<FramePassNode, List<FrameResource>>`
   (что каждый pass читает/пишет), из которого строится topological sort (Kahn's algorithm
   или DFS-based — выбери и реализуй один, стандартный, корректный подход).
2. **Обнаружение циклов**: если topological sort не может упорядочить все pass'ы (типичный
   признак — DFS находит back-edge, либо Kahn's algorithm оставляет узлы с ненулевой
   in-degree после обработки) — брось `IllegalStateException` с понятным сообщением,
   перечисляющим involved passes, если возможно.
3. **Вставка барьеров**: рассматривай КАЖДУЮ пару pass'ов `(Q, P)` в топологическом порядке,
   где `Q` исполняется раньше `P` (не обязательно непосредственно перед ним — между ними в
   линеаризованном порядке может быть один или несколько других, несвязанных pass'ов `R1,
   R2, ...`, вставленных туда просто потому, что у них нет взаимной зависимости с `Q`/`P`,
   определяющей порядок между ними самими). Если `P` читает или пишет любой ресурс,
   записанный `Q` — вставь `commands.memoryBarrier(BarrierScope)` НЕПОСРЕДСТВЕННО перед
   записью команд `P` (не перед первым промежуточным `R1`, если такой есть — барьер защищает
   конкретно чтение/запись `P`, не что-либо между ними). Если несколько разных `Q1, Q2, ...`
   пишут ресурсы, которые читает один и тот же `P` — один барьер перед `P` покрывает все эти
   зависимости сразу (не нужно N отдельных барьеров на N разных Q, если все Qi уже исполнены
   к моменту перед P — сам факт "уже исполнены" гарантирован топологическим порядком).
   Выбор конкретного `BarrierScope` (например, `SHADER_STORAGE` для SSBO-to-SSBO зависимостей
   между compute-проходами, `COMMAND` для зависимостей на indirect-draw буферы, как в тикете
   23) — реализуй консервативно простым способом на этом этапе: используй `BarrierScope.ALL`
   всегда, если не хочешь усложнять с per-resource-type различением scope — задокументируй
   этот компромисс явно (сначала корректность, оптимизация конкретных scope — возможное
   будущее улучшение, не блокирует этот тикет).
4. **Повторное использование графа между кадрами**: решение о том, пересоздаётся ли граф
   каждый кадр заново (`addPass` вызывается заново каждый раз) или структура переиспользуется
   с обновляемым состоянием через `passFactory` — прими явное архитектурное решение и
   задокументируй его в Javadoc `FrameGraphBuilder`. Рекомендация (не обязательна, но
   рассмотри): простая и понятная модель — вызывающий код (тикет 27, интеграция с NeoForge)
   создаёт новый `FrameGraphBuilder`, вызывает `addPass` нужное число раз, затем `execute()`,
   один раз за кадр (граф не переиспользуется как persistent-структура) — это проще для
   первой реализации, хоть и не самое дешёвое по аллокациям решение; если считаешь важным
   оптимизировать через переиспользование — реализуй, но не в ущерб корректности и ясности
   кода.
5. Никакого мутируемого статического состояния.

## Юнит-тесты (обязательно, JUnit 5, БЕЗ реального GPU — используй fake `RenderBackend`/
`CommandList`, реализованные прямо в тестовом коде этого тикета или переиспользуй
`FakeRenderBackend` из тикета 30, если он уже существует в проекте на момент выполнения)

Создай `FrameGraphBuilderTest`:
1. Простой линейный граф: pass A пишет ресурс R, pass B читает R (добавлены в порядке A, затем
   B) → `execute()` записывает команды A перед B, с барьером между ними (проверь через fake
   `CommandList`, считающий вызовы `memoryBarrier`/порядок вызовов `record` на разных passes —
   используй, например, список записанных имён pass'ов в порядке вызова их `record`).
2. Независимые pass'ы (ни один не читает/не пишет ресурс другого) — оба выполняются без
   барьера между ними напрямую по этой причине (могут иметь барьер, если оба зависят от
   общего родителя, но не друг от друга напрямую — тестируй именно отсутствие
   ложноположительного барьера между независимыми pass'ами).
3. Цепочка из 3+ passes (A→B→C по зависимостям) — `execute()` вызывает `record` в порядке
   A, B, C.
4. Циклическая зависимость (сконструируй искусственно через ресурсы: pass A читает то, что
   пишет B, а B читает то, что пишет A) → `execute()` бросает `IllegalStateException`.
4a. **Промежуточный несвязанный pass**: три pass'а A, B, C, где A пишет ресурс R, C читает R
    (зависимость A→C), а B не связан ни с A, ни с C никаким ресурсом, но по порядку
    добавления/топологической линеаризации оказывается между ними (A, затем B, затем C) —
    барьер перед `C.record` вставлен (проверка зависимости A→C сработала), но НЕ вставлен
    непосредственно перед `B.record` (B ни от чего не зависит) — это прямая проверка
    уточнённого требования 3: барьер защищает конкретно то, что читает/пишет P, а не любой
    промежуточный pass между Q и P в линеаризованном порядке.
4b. **Несколько источников на одного потребителя**: два pass'а Q1 и Q2, каждый пишет свой
    отдельный ресурс (R1 и R2 соответственно), третий pass P читает оба R1 и R2 — `execute()`
    вставляет барьер(ы) перед `P.record`, покрывающие обе зависимости (Q1→P и Q2→P), и оба
    Q1, Q2 исполняются до P в топологическом порядке — не обязательно проверять точное число
    вызовов `memoryBarrier` (один общий барьер или два подряд — оба варианта корректны, если
    выбранный `BarrierScope` консервативен, например `ALL`), но обязательно проверить, что
    ОБА Q1 и Q2 действительно исполнены (их `record` вызван) до `P.record`.
5. `addPass` с одинаковым `passFactory`, вызванным дважды за счёт двух отдельных `execute()`
   вызовов на одном и том же `FrameGraphBuilder` instance (если модель переиспользования это
   поддерживает согласно выбранному в требовании 4 подходу) — либо, если выбрана модель
   "новый builder на кадр" — этот тест не применим, замени его на проверку, что повторное
   использование ОДНОГО builder'а для двух `execute()` подряд либо явно запрещено
   (задокументированное исключение), либо явно поддержано и корректно — выбери и протестируй
   соответствующее выбранной модели поведение.

## Критерии приёмки
1. Файлы в `ev-render/src/main/java/dev/ev/render/framegraph/`: `FrameResource.java`,
   `FramePass.java`, `PassBuilder.java`, `FrameGraphBuilder.java`.
2. Все юнит-тесты проходят.
3. Модуль `ev-render` компилируется без ошибок и БЕЗ единого импорта из `org.lwjgl.*`
   (проверь явно — весь доступ к GPU идёт исключительно через `dev.ev.api.gpu.*`
   интерфейсы).
4. Политика вставки барьеров и модель переиспользования графа между кадрами задокументированы
   явно в Javadoc `FrameGraphBuilder`.
