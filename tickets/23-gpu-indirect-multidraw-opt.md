# Тикет 23-opt — GPU-Driven Indirect Multi-Draw

## ⚠️ Когда это применять

Это тикет Волны 2 (см. `MVP_INDEX.md`). MVP-версия (реализуется в тикете 27, интеграция
рендер-хука) использует обычный, отдельный draw call на каждую видимую секцию, полученную из
`SimpleTraversal.computeVisible()` (тикет 21-mvp). **Не выполняй этот тикет**, пока не
пройден `P0-profiling-checkpoint.md` и результаты не показали:
- Метрика "Draw call count и время, потраченное на них" (Сценарий B) показывает большое
  число одновременно видимых секций (типично при широком render distance) И заметную долю
  frame time именно на submission draw calls (не на сам рендеринг геометрии внутри них) —
  этот тикет требует также `20-opt` (или как минимум расширения буфера узлов geometry-offset
  информацией) как зависимость, см. ниже.

Этот тикет технически проще внедрить сразу поверх GPU persistent-kernel traversal (`21-opt`),
так как там render list уже строится на GPU естественным образом — при MVP (CPU-side)
traversal этот тикет тоже применим, но CPU-стороне придётся самой заполнять indirect command
buffer (не GPU-стороне через compute-шейдер, как описано ниже для GPU-driven варианта) — если
применяешь этот тикет ДО `21-opt`, адаптируй "Часть 1" ниже: вместо compute-шейдера,
транслирующего GPU-side RenderList, используй прямую CPU-side запись indirect commands из
уже посчитанного `SimpleTraversal` результата в staging-буфер, затем `copyBuffer` в целевой
GPU-буфер — тот же итоговый эффект (один indirect draw call вместо N обычных), другой путь
заполнения командного буфера.

## Контекст
EV — мод дальней прорисовки для NeoForge 1.21.1. Модуль `ev-gpu`. Реализует
финальную отрисовку видимых узлов, обоснованную в PERFORMANCE_MATH.md разделе B.5: раз
traversal (тикет 21) уже строит `RenderList` прямо на GPU, финальный рендер должен идти
через ОДИН `glMultiDrawElementsIndirectCount`-подобный вызов, а не через отдельный draw call
на каждую видимую секцию — CPU-стороне не нужно даже знать точное число видимых объектов
(`N` читается GPU-драйвером из отдельного GPU-счётчика, который сам заполнил traversal).

## Готовый контракт из зависимостей (тикеты 04, 18, 20, 21 — уже реализованы)
```java
package dev.ev.api.gpu;
public interface GraphicsPipeline {
    void drawIndirect(GpuBuffer indirectBuffer, long offsetBytes, int drawCount);
    void drawIndirectCount(GpuBuffer indirectBuffer, long offsetBytes, GpuBuffer countBuffer, long countOffsetBytes, int maxDrawCount);
    void bindBuffer(String bindingName, GpuBuffer buffer);
    void bindTexture(String bindingName, GpuTexture texture);
    void free();
}
// RenderList SSBO from ticket 21 (traversal.comp):
//   layout(std430, binding = 6) buffer RenderList {
//       uint renderCount;
//       uint renderNodeIds[];
//   };
```

## Задача

### Часть 1 — compute shader, собирающий indirect draw commands из RenderList

Traversal (тикет 21) заполняет `RenderList` списком `nodeId`, но indirect draw требует
структуры `DrawElementsIndirectCommand` (стандартный GL layout: `count, instanceCount,
firstIndex, baseVertex, baseInstance` — все `uint`, 20 байт) на каждый видимый объект. Нужен
промежуточный compute-проход, транслирующий `renderNodeIds[]` в indirect commands, беря
геометрические данные (offset/count в общем geometry-буфере) каждого узла из
соответствующего meshlet-хранилища (связь `nodeId -> geometry offset/count` — это тот же
тип связи, что упоминался как "рабочее предположение, может быть пересмотрено в тикете 25"
в тикете 21; используй ту же схему здесь для консистентности, либо, если тикет 25 к
моменту выполнения этого тикета уже финализировал схему — используй финальную).

Создать `ev-gpu/src/main/resources/shaders/build_indirect_commands.comp`:

```glsl
#version 450 core
layout(local_size_x = 64) in;

layout(std430, binding = 6) buffer RenderList {
    uint renderCount;
    uint renderNodeIds[];
};

layout(std430, binding = 10) buffer NodeGeometryInfo {
    // per-nodeId: where its geometry lives in the shared vertex/index buffer
    uvec2 geometryOffsetAndCount[]; // x = firstIndex, y = indexCount
};

struct DrawElementsIndirectCommand {
    uint count;
    uint instanceCount;
    uint firstIndex;
    uint baseVertex;
    uint baseInstance;
};

layout(std430, binding = 11) buffer IndirectCommands {
    DrawElementsIndirectCommand commands[];
};

layout(std430, binding = 12) buffer IndirectDrawCount {
    uint drawCount; // written = renderCount, read back by drawIndirectCount on the CPU side
};

void main() {
    uint idx = gl_GlobalInvocationID.x;
    if (idx >= renderCount) return;

    uint nodeId = renderNodeIds[idx];
    uvec2 geomInfo = geometryOffsetAndCount[nodeId];

    commands[idx].count = geomInfo.y;
    commands[idx].instanceCount = 1u;
    commands[idx].firstIndex = geomInfo.x;
    commands[idx].baseVertex = 0u;
    commands[idx].baseInstance = idx;

    if (idx == 0u) {
        drawCount = renderCount;
    }
}
```

### Часть 2 — Java: `IndirectDrawCoordinator`
```java
package dev.ev.gpu.render;

import dev.ev.api.gpu.*;

/**
 * Orchestrates the two-step GPU-driven render path: (1) dispatch
 * build_indirect_commands.comp to translate the traversal's RenderList into a
 * DrawElementsIndirectCommand buffer, (2) issue ONE drawIndirectCount call
 * consuming that buffer, letting the GPU itself determine the actual draw count
 * from IndirectDrawCount (no CPU readback of renderCount needed — see
 * PERFORMANCE_MATH.md section B.5).
 */
public final class IndirectDrawCoordinator {

    public IndirectDrawCoordinator(RenderBackend backend, int maxDrawCount) {
        // allocates the IndirectCommands buffer (maxDrawCount * 20 bytes) and the
        // IndirectDrawCount buffer (4 bytes), compiles build_indirect_commands.comp.
    }

    /**
     * Call after traversal (ticket 21) has populated RenderList for this frame,
     * before the actual draw. Dispatches the command-building compute shader.
     */
    public void buildCommands(CommandList commands, GpuBuffer renderListBuffer, GpuBuffer nodeGeometryInfoBuffer);

    /**
     * Issues the single indirect multi-draw call using the commands built by
     * buildCommands(). Must be called after buildCommands() in the same or a
     * subsequent CommandList, with an appropriate memory barrier between the
     * compute write and this indirect draw read (see requirement 2 below).
     */
    public void draw(GraphicsPipeline pipeline, CommandList commands);
}
```

## Требования к реализации

1. **Ограничение размера**: `maxDrawCount` — верхняя граница на число одновременно видимых
   объектов, буферы аллоцируются под неё статически (как и `RenderList`/`WorkQueue` в тикете
   21 — согласуй это значение с той же адаптивной логикой расчёта бюджета, упомянутой в
   ARCHITECTURE.md раздел 6.3 / тикете 19 требование 4, а не хардкодь отдельное магическое
   число здесь).

2. **Барьер между compute и indirect draw**: после `build_indirect_commands.comp` записывает
   в `IndirectCommands`/`IndirectDrawCount`, ПЕРЕД `drawIndirectCount` обязателен memory
   barrier (`GL_COMMAND_BARRIER_BIT` / соответствующий `BarrierScope.COMMAND` из контракта
   тикета 04) — иначе GPU может начать чтение indirect-буфера для draw до завершения записи
   compute-шейдером (real hazard, не гипотетический — задокументируй это явно в Javadoc
   `IndirectDrawCoordinator.draw`).

3. **`GraphicsPipeline.drawIndirectCount`** (уже определён в контракте тикета 04) —
   `IndirectDrawCoordinator.draw` вызывает именно этот метод (не `drawIndirect` с фиксированным
   `drawCount`), передавая `IndirectCommands` буфер и `IndirectDrawCount` буфер — это и есть
   механизм "CPU не знает точное число объектов, GPU решает сам".

4. `DrawElementsIndirectCommand` layout (20 байт: 5 × uint) должен точно совпадать между
   GLSL `struct` определением и любым Java-side константным описанием размера/страйда,
   если такое понадобится (например, при аллокации буфера `maxDrawCount * 20` байт) —
   задокументируй магическое число 20 явной именованной константой на Java-стороне
   (`BYTES_PER_INDIRECT_COMMAND = 20`), не голым числом.

5. Никакого мутируемого статического состояния.

## Тесты
1. Если headless GL доступен: интеграционный тест — заполни синтетический `RenderList` с
   несколькими известными `nodeId` и соответствующим `NodeGeometryInfo`, вызови
   `buildCommands`, прочитай `IndirectCommands`/`IndirectDrawCount` буферы обратно, проверь,
   что содержимое соответствует ожидаемому (count/firstIndex корректно скопированы из
   geometry info, `drawCount` равен числу элементов в `RenderList`).
2. Без GL: юнит-тест на `IndirectDrawCoordinator` с fake `RenderBackend`/`ComputePipeline`/
   `GraphicsPipeline` — проверь правильную последовательность вызовов (`buildCommands`
   диспетчеризует compute с ожидаемыми параметрами, `draw` вызывает `drawIndirectCount` с
   правильными буферами/офсетами) без необходимости проверять реальное содержимое GPU-памяти.

## Критерии приёмки
1. Файлы: `ev-gpu/src/main/resources/shaders/build_indirect_commands.comp`,
   `ev-gpu/src/main/java/dev/ev/gpu/render/IndirectDrawCoordinator.java`.
2. Доступные тесты проходят.
3. Барьер между compute-записью и indirect-чтением задокументирован и присутствует в
   реализации `draw`.
4. Модуль компилируется без ошибок на Java-стороне.
