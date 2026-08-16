# Тикет 27 — NeoForge Mod Entrypoint и события

## Контекст
EV — мод дальней прорисовки для NeoForge 1.21.1. Модуль `ev-neoforge` — платформенный
слой, единственный модуль, зависящий от NeoForge API. Реализует главный класс мода и
подписку на публичные события NeoForge для интеграции с рендер-пайплайном ванильной игры
(предпочитая публичные события над mixins там, где это возможно — см. ARCHITECTURE.md
раздел 7, это снижает хрупкость при обновлениях версии Minecraft по сравнению с прототипом
Voxy, у которого было ~200K строк миксинов).

Тикет 00 уже создал МИНИМАЛЬНЫЙ `EV.java` (пустой, только логирование). Этот тикет
РАСШИРЯЕТ тот же класс, подключая реальную инициализацию и рендер-хук — не создаёт новый
класс с нуля.

## Готовый контракт из зависимостей (тикеты 04, 17, 24 — уже реализованы; полагаем, что
тикеты 21-23, 25-26 тоже завершены к моменту интеграции, но этот конкретный тикет не
обязан использовать их API в деталях — фокус на правильной подписке на события и жизненном
цикле, детальная оркестрация всех GPU-проходов — предмет тикета 31, интеграционного)
```java
package dev.ev.api.gpu;
public interface RenderBackend {
    void shutdown();
    // ...
}

package dev.ev.gpu.gl;
public final class GLRenderBackend implements RenderBackend { /* ... */ }

package dev.ev.render.framegraph;
public final class FrameGraphBuilder {
    FrameGraphBuilder(RenderBackend backend, dev.ev.api.metrics.MetricsRegistry metrics);
    PassBuilder addPass(String name, java.util.function.Supplier<FramePass> passFactory);
    void execute();
}
```

## 📌 MVP-архитектура `renderFarLod` (см. `MVP_INDEX.md`)

Этот тикет реализуется в контексте Волны 1 (MVP) — если ты выполняешь его до применения
каких-либо `*-opt` тикетов (типичный порядок, см. `MVP_INDEX.md`), `renderFarLod` НЕ должен
использовать GPU persistent-kernel traversal (`21-gpu-persistent-traversal-shader-opt.md`)
или indirect multi-draw (`23-gpu-indirect-multidraw-opt.md`) — эти тикеты в MVP-волне не
применены. Вместо этого `renderFarLod` в MVP-версии должен:

1. Получить список загруженных секций (из `SectionCache`, тикет `08-storage-section-cache-mvp.md`,
   через посредство meshing pipeline — точный источник списка "что загружено и готово к
   рендеру" определи здесь, если ни один предыдущий тикет явно не выставил такой список
   наружу — вероятно, нужен небольшой реестр "готовых к рендеру секций", который заполняется
   по мере завершения meshing pipeline, если такого ещё нет).
2. Извлечь 6 плоскостей frustum из `projectionMatrix` (или эквивалентного параметра из
   реального типа NeoForge, подтверждённого через web search) — стандартная, хорошо известная
   техника (Gribb/Hartmann plane extraction из view-projection матрицы) — реализуй её либо
   прямо здесь, либо как отдельный небольшой вспомогательный метод в `ev-render`, если
   более уместно.
3. Вызвать `SimpleTraversal.computeVisible(...)` (тикет `21-gpu-simple-traversal-mvp.md`) с
   построенным `FrustumTester`, получить список видимых `SectionPos`.
4. Для каждой видимой секции — выполнить обычный (не indirect) draw call через
   `RenderBackend`/`GraphicsPipeline` (тикет 04/18) — один `CommandList.draw`-подобный вызов
   на секцию, используя geometry, ранее загруженную для неё через meshing+upload pipeline.
5. Использовать `FrameGraphBuilder` (тикет 24 — этот тикет НЕ упрощается между волнами, см.
   `MVP_INDEX.md`, категория "инфраструктурные тикеты") для оформления этой последовательности
   как декларативного графа passes, даже если сами passes в MVP просты (один pass —
   "culling+draw", не разбит на persistent-traversal/indirect-build/indirect-draw подпроходы,
   как это было бы после применения opt-тикетов).

Если этот тикет выполняется ПОСЛЕ того, как какие-либо `*-opt` тикеты уже применены —
адаптируй `renderFarLod` под актуальное на тот момент состояние (используй GPU traversal вместо
`SimpleTraversal`, indirect draw вместо обычных draw calls, и т.д.) — сверься с
`PROJECT_INDEX.md` (тикет 32) за текущим статусом, какие opt-тикеты уже применены.

## Задача

### `EVInstance` (жизненный цикл одного мира/рендер-контекста)
```java
package dev.ev.neoforge;

/**
 * Owns the lifecycle of EV's rendering subsystems for one active world/dimension
 * context: the RenderBackend (GL), the FrameGraphBuilder, storage/meshing subsystems
 * (wired up here or in a later ticket — this ticket establishes the container and
 * lifecycle, not necessarily every subsystem's full wiring if some ticket dependencies
 * are still pending at time of writing this class; use clearly marked TODOs referencing
 * specific ticket numbers for anything deferred).
 *
 * Exactly one EVInstance should exist per loaded client world at a time — created
 * on world/dimension load, torn down on unload, never reused across worlds (this
 * enforces the "no mutable static state, no cross-world resource leakage" architectural
 * principle from ARCHITECTURE.md).
 */
public final class EVInstance implements AutoCloseable {

    public static EVInstance bootstrap(EVConfig config) {
        // creates GLRenderBackend, FrameGraphBuilder, and any other top-level
        // subsystems whose constructors are already available from completed tickets.
    }

    /** 
     * Called once per client frame during the appropriate render stage (see below). 
     * 
     * SODIUM / EMBEDDIUM COMPATIBILITY GUARANTEE:
     * 1. Must run within RenderLevelStageEvent (Stage.AFTER_SOLID_BLOCKS or AFTER_TRANSLUCENT_BLOCKS).
     * 2. MUST strictly restore OpenGL pipeline state at the end of rendering:
     *    glUseProgram(0), glBindVertexArray(0), glBindBuffer(GL_ARRAY_BUFFER, 0),
     *    glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0), glDepthMask(true), glEnable(GL_DEPTH_TEST).
     * 3. Compute nearCutoffBlocks using Math.sqrt(2.0) margin to seamlessly bound Sodium chunk rendering.
     */
    public void renderFarLod(Object levelRenderState, Object projectionMatrix) {
        // Signature uses Object placeholders here because the exact NeoForge
        // RenderLevelStageEvent API types for 1.21.1 should be verified via web
        // search at implementation time (NeoForge's rendering event API has
        // changed across versions) rather than assumed from training data —
        // replace Object with the correct concrete types once confirmed.
    }

    @Override
    public void close() {
        // releases RenderBackend and any other GPU resources, in reverse
        // acquisition order; must be safe to call even if bootstrap() partially
        // failed (defensive null-checks), and must never leave GPU resources
        // leaked on the happy path.
    }
}
```

### `EV` (расширение существующего класса из тикета 00)
```java
package dev.ev.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
// NOTE: verify exact NeoForge 1.21.1 import paths via web search before finalizing —
// package structure for FML/event APIs has shifted between Forge and NeoForge and
// across NeoForge versions; do not assume the paths above are exactly correct,
// confirm them.

@Mod(EV.MODID)
public final class EV {
    public static final String MODID = "ev";

    private EVInstance instance;

    public EV(IEventBus modBus) {
        modBus.addListener(this::onClientSetup);
        // NeoForge.EVENT_BUS (the global game event bus, distinct from modBus) is
        // where world load/unload and render-stage events are typically registered —
        // verify current NeoForge convention via web search, register accordingly.
    }

    private void onClientSetup(/* FMLClientSetupEvent event, verify exact type via search */ Object event) {
        // load EVConfig (ticket 28), but DO NOT call EVInstance.bootstrap()
        // here yet if bootstrap requires a live GL context — verify via search/NeoForge
        // docs whether FMLClientSetupEvent runs with a valid GL context available, or
        // whether instance creation should instead happen on the first world-load event
        // (safer default if uncertain: defer EVInstance.bootstrap() to the
        // level-load event, not client setup, since client setup can run before a
        // world/GL surface is fully ready — confirm this via search rather than guessing).
    }

    private void onLevelLoad(/* verify exact NeoForge event type via search, e.g. LevelEvent.Load */ Object event) {
        // create this.instance = EVInstance.bootstrap(...) if not already active
        // for this world; only act on the CLIENT side (EV has no meaningful
        // server-side rendering role) — verify how to distinguish client vs server
        // level events in current NeoForge API via search.
    }

    private void onRenderLevelStage(/* verify exact type, e.g. RenderLevelStageEvent, via search */ Object event) {
        // if instance != null and the event's stage matches the intended hook point
        // (e.g. AFTER_SOLID_BLOCKS, or whatever stage is appropriate for far-LOD
        // rendering relative to vanilla terrain — verify available stage enum values
        // via search), call instance.renderFarLod(...).
    }

    private void onLevelUnload(/* verify exact type, e.g. LevelEvent.Unload, via search */ Object event) {
        // if instance != null and this unload corresponds to the world instance owns,
        // call instance.close() and set instance = null.
    }
}
```

## Эмпирический урок из независимой реализации (Exceptional Vision) — near-cutoff геометрия

Этот тикет (и/или тикет 21/22, где реализуется реальный near-cutoff test в traversal-шейдере
— перепроверь, где именно в финальной интеграции этот расчёт физически происходит, и примени
там) должен учесть конкретный, эмпирически найденный и исправленный баг из другого независимо
реализованного мода той же концепции (Exceptional Vision, `IYourOverlord/Exceptional-Vision`,
их `PROGRESS.md` пункт 0.11):

**Проблема:** зона, которую ванильный рендерер Minecraft уже покрывает (загруженные чанки
вокруг игрока), геометрически — **квадрат** (Chebyshev-расстояние ≤ `renderDistanceChunks`
чанков от игрока по осям X/Z), а не круг. Если near-cutoff расстояние (граница, ближе которой
EV НЕ рисует LOD-геометрию, полагаясь на то, что ванильный рендерер уже покрыл эту зону)
считается как простая ОКРУЖНОСТЬ радиусом `renderDistanceChunks * 16` (блоков) — эта окружность
покрывает стороны квадрата, но НЕ достаёт до его угловых зон (по теореме Пифагора угол
квадрата находится на евклидовом расстоянии `renderDistanceChunks * 16 * √2` от центра, что
больше стороны). Результат — узкие клиновидные дыры строго по диагоналям от игрока (не
кольцевые, что важно для диагностики — если баг воспроизведётся снова, характерная
диагональная, а не кольцевая форма дыры — прямой диагностический признак именно этой причины,
не путать с другими причинами "дыр у игрока"). Проблема отсутствует при малых
`renderDistanceChunks` (запас в 1 чанк почти компенсирует разницу) и становится заметной
при типичных пользовательских настройках (8-12+ чанков render distance), где разница
`renderDistanceChunks * 16 * (√2 - 1)` существенно превышает любой фиксированный запас в
чанках.

**Требование:** near-cutoff расстояние, используемое для решения "не рисовать LOD ближе X
блоков к камере" (независимо от того, где именно этот расчёт физически размещён — в
`EVInstance`, в uniform-параметре traversal-шейдера, или в отдельном классе,
конфигурируемом здесь) должно вычисляться как:

```java
// nearCutoffBlocks должен ГАРАНТИРОВАННО описывать (circumscribe) квадратную зону
// vanilla-загрузки целиком, включая её угловые зоны — не только стороны. Простая
// окружность радиусом renderDistanceChunks*16 касается сторон квадрата, но не достаёт
// до углов (диагональ квадрата длиннее его стороны в √2 раз) — см. this ticket's Javadoc
// "Эмпирический урок" section for the real bug this prevents (diagonal wedge-shaped gaps,
// confirmed via real playtesting in an independent implementation of this same mod concept).
float nearCutoffBlocks = renderDistanceChunks * 16.0f * (float) Math.sqrt(2.0)
                        + nearCutoffMarginChunks * 16.0f;
```

где `renderDistanceChunks` — РЕАЛЬНО загруженный на данный момент радиус (не настройка
игрока напрямую, см. смежный, тоже эмпирически найденный в том же проекте баг: настройка
рендер-дистанции может быть больше того, что физически успело прогрузиться на первых кадрах
после захода в мир/телепорта — используй фактически загруженный радиус, определяемый
обходом чанков кольцами от камеры и проверкой их статуса загрузки, если такая информация
доступна через NeoForge API — подтверди через web search подходящий способ получить статус
загрузки чанка (`FULL` или аналог) для NeoForge 1.21.1).

Добавь юнит-тест (чистая арифметика, не требует NeoForge/GL) на эту формулу: для нескольких
значений `renderDistanceChunks` (малых и больших, включая типичные 8-12+) проверь, что
`nearCutoffBlocks >= renderDistanceChunks * 16 * sqrt(2)` (гарантированно описывает диагональ
квадрата), а не просто `>= renderDistanceChunks * 16` (что было бы недостаточно и воспроизвело
бы найденный баг).

## Требования к реализации

1. **Обязательно используй web search** для подтверждения точных путей импорта и сигнатур
   NeoForge событий для версии 1.21.1 (`FMLClientSetupEvent`, `LevelEvent.Load`/`Unload`,
   `RenderLevelStageEvent` и его enum значений стадий, `IEventBus` vs `NeoForge.EVENT_BUS`
   различие между mod-specific и глобальной шиной) — экосистема NeoForge меняется между
   версиями, не полагайся на память/training data для точных сигнатур. Если в процессе
   поиска выясняется, что какой-то из указанных выше классов называется иначе в 1.21.1 —
   используй актуальное правильное имя, задокументировав в комментарии, что оно было
   подтверждено поиском (не оставляй `Object`-заглушки в финальном коде — они должны быть
   заменены на конкретные типы NeoForge API).

2. **Client-only регистрация**: рендеринг-логика (`onRenderLevelStage`) должна выполняться
   только на клиенте. Убедись, что регистрация событий происходит корректно относительно
   client/server раздельной загрузки классов в NeoForge (обычно через `@Mod` конструктор с
   проверкой `FMLEnvironment.dist == Dist.CLIENT` или через отдельный client-only
   инициализатор — проверь актуальный рекомендованный паттерн NeoForge 1.21.1 через поиск,
   не изобретай подход без проверки, так как неверная регистрация client-only кода на
   dedicated server ломает серверную часть модпака).

3. **Порядок инициализации GL-ресурсов**: как отмечено в комментариях к `onClientSetup`
   выше — определи через поиск/документацию NeoForge, в какой момент безопасно создавать
   `GLRenderBackend` (нужен активный GL-контекст) — вероятно, не в `FMLClientSetupEvent`
   (слишком рано), а при первой загрузке мира или первом рендер-кадре. Прими явное решение,
   задокументируй его в Javadoc.

4. `EVInstance.close()` должен быть idempotent-safe (повторный вызов не должен падать
   с NPE/exception, если ресурсы уже освобождены) — полезно для defensive cleanup при
   ошибках инициализации.

5. Никакого мутируемого статического состояния, кроме самого `EV.instance` поля,
   которое по своей природе — instance-поле экземпляра `@Mod`-класса (NeoForge создаёт ровно
   один экземпляр мод-класса per JVM, это архитектурно неизбежный, задокументированный
   NeoForge-платформой синглтон, отличный от произвольных статических полей внутри
   бизнес-логики, которых проект избегает).

## Тесты
Тестирование NeoForge event-интеграции требует NeoForge test harness/game test framework,
что выходит за рамки чистого JUnit unit-тестирования и не является обязательным для этого
тикета. Вместо этого:
1. Убедись, что модуль компилируется против реальных NeoForge 1.21.1 API (это само по себе
   валидирует правильность найденных через поиск сигнатур событий).
2. Если возможно — напиши минимальный unit-тест на `EVInstance.close()` idempotency
   (создай `EVInstance` с fake/mock `RenderBackend`, если конструктор это позволяет
   без реального GL — если `bootstrap()` жёстко требует реальный GL контекст, этот тест
   может быть невозможен в чистом виде; в таком случае пропусти его и задокументируй
   ограничение).

## Критерии приёмки
1. Файлы: `ev-neoforge/src/main/java/dev/ev/neoforge/EV.java` (расширен, не
   пересоздан с нуля из тикета 00), `EVInstance.java`.
2. Все `Object`-заглушки типов событий заменены на реальные, подтверждённые через web search,
   типы NeoForge 1.21.1 API.
3. `./gradlew :ev-neoforge:build` компилируется успешно против реального NeoForge API.
4. Решение о моменте создания `GLRenderBackend` (client setup vs level load) явно
   задокументировано и обосновано в коде.
5. Client-only регистрация рендер-логики корректна (не ломает dedicated server сборку —
   если у тебя нет возможности реально протестировать на dedicated server, как минимум
   убедись, что используемый паттерн регистрации соответствует официально документированному
   NeoForge способу разделения client/server кода, найденному через поиск).
