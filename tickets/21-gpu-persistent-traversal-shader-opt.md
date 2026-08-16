# Тикет 21-opt — Persistent-Kernel Hierarchical Traversal (GLSL Compute)

## ⚠️ Когда это применять

Это тикет Волны 2 (см. `MVP_INDEX.md`) — вероятно, самый рискованный из всей Волны 2. Сам
прототип, из разбора которого родилась эта идея (Voxy), не довёл persistent-kernel traversal
до конца — задокументированный TODO в их коде, не реализация. **Не выполняй этот тикет**,
пока не пройден `P0-profiling-checkpoint.md` и результаты не показали ОБА следующих признака
одновременно (не один — оба, так как это дорогая и рискованная замена):
- CPU-side traversal (реализованный в `21-gpu-simple-traversal-mvp.md`) занимает заметную
  долю frame time даже при неподвижной камере (Сценарий B, метрика "Frame time при
  неподвижной камере" в `P0-profiling-checkpoint.md`) — то есть проблема именно в стоимости
  самого обхода/culling, не в чём-то другом.
- Эта стоимость масштабируется с общим объёмом загруженных данных (numNodes), а не с видимым
  множеством — что подтверждает, что дело именно в архитектуре traversal (не в чём-то ещё,
  например в самом рендере геометрии).

Если профилирование указывает на CPU-side traversal как узкое место, но `25-opt` (temporal
reprojection) ещё не применён — **сначала попробуй `25-opt`** поверх CPU-side traversal из
MVP-версии: инкрементальный CPU-обход с working set может закрыть тот же разрыв дешевле и с
меньшим риском, чем полный перенос traversal на GPU. Переходи к этому тикету только если
`25-opt` уже применён и профилирование ПОСЛЕ него всё ещё показывает CPU traversal как узкое
место (то есть даже инкрементальный CPU-обход остаётся дорогим — типичный случай при очень
большом render distance, где даже "малый" видимый+изменившийся набор всё ещё велик).

Это единственный тикет во всей Волне 2, где я явно рекомендую промежуточный шаг
(`25-opt` до `21-opt`), а не прямое решение "внедрять/не внедрять" по одному диагностическому
признаку — риск и сложность этого конкретного тикета оправдывают дополнительную осторожность.

## Контекст
EV — мод дальней прорисовки для NeoForge 1.21.1. Модуль `ev-gpu`. Это
центральный архитектурный компонент steady-state производительности (см.
PERFORMANCE_MATH.md раздел B.1, ARCHITECTURE.md раздел 6.1). Прототип, изученный при
проектировании этого мода (Voxy), делает до `MAX_LOD_LEVEL+1` (обычно 5-7) последовательных
`glDispatchComputeIndirect` вызовов с `glMemoryBarrier` между каждым — по одному dispatch на
уровень глубины дерева, каждый со своей CPU-GPU синхронизационной точкой. Их собственный код
содержит TODO "swap to persistent gpu threads instead of dispatching layers" — они это не
сделали. EV делает это с первого дня: **один** compute dispatch запускает
персистентные workgroup'ы, которые сами вычитывают работу из atomic-очереди в SSBO и сами
добавляют туда дочерние узлы (self-feeding queue), обходя всю глубину дерева без единого
дополнительного CPU-side dispatch/barrier.

## Готовый контракт из зависимостей (тикеты 04, 18, 20 — уже реализованы)
```java
// ticket 04
package dev.ev.api.gpu;
public interface ComputePipeline {
    void dispatch(int groupsX, int groupsY, int groupsZ);
    void dispatchIndirect(GpuBuffer indirectBuffer, long offsetBytes);
    void bindBuffer(String bindingName, GpuBuffer buffer);
    void bindTexture(String bindingName, GpuTexture texture);
    void free();
}
public record ShaderSource(String sourcePath, String rawSource, Map<String,String> defines) {}
public record PipelineLayout(Map<String,Integer> bindingsByName) {}

// ticket 18: GLRenderBackend.compilePipeline(ShaderSource, PipelineLayout) -> ComputePipeline
// (compiles GLSL compute shaders with #define injection and named-binding resolution)

// ticket 20: node_buffers.glsl provides NodeBoundsSoA/NodeFlagsSoA/NodeChildMaskSoA at
// bindings 0/1/2 respectively (see that file for exact declarations).
```

## Задача

### GLSL: `traversal.comp`
Создать `ev-gpu/src/main/resources/shaders/traversal.comp` — файл начинается с
`#version 450 core`, включает `node_buffers.glsl` (тикет 20) через строку, которую твой
Java-side define-injection (тикет 18) или отдельный простой `#include`-preprocessing шаг
должен разрешить (GLSL не имеет нативного `#include` — либо реализуй простую
текстовую конкатенацию на Java-стороне перед компиляцией — если это удобнее сделать здесь,
чем менять `ShaderCompiler` из тикета 18, сделай это как небольшое дополнение к
`ShaderCompiler`, задокументировав изменение — либо просто скопируй содержимое
`node_buffers.glsl` вручную в начало `traversal.comp`, если предпочитаешь не усложнять
build pipeline; выбери и обоснуй подход).

**Структура work queue (дополнительный SSBO, специфичный для этого шейдера, не часть
`node_buffers.glsl`)**:
```glsl
layout(std430, binding = 5) buffer WorkQueue {
    uint head;        // atomic: index of next item to read
    uint tail;        // atomic: index of next free slot to write
    uint activeCount; // atomic: number of workgroups NOT currently idle (for termination detection)
    uint capacity;
    uint items[];     // ring buffer of node ids
};

layout(std430, binding = 6) buffer RenderList {
    uint renderCount; // atomic counter, reset to 0 by CPU before dispatch
    uint renderNodeIds[];
};

layout(std430, binding = 7) buffer StreamingRequests {
    uint requestCount; // atomic counter, reset to 0 by CPU before dispatch
    uint requestedNodeIds[];
};

layout(std430, binding = 8) uniform FrameUniforms {
    mat4 viewProjMatrix;
    vec3 cameraPos;
    float _pad0;
    vec3 frustumPlanes[6]; // or pack as vec4 planes (xyz=normal, w=distance) — choose a
                             // concrete representation and use it consistently
};
```

**Персистентный kernel цикл** (псевдокод-каркас — реализуй полностью рабочий GLSL,
адаптируя структуру, приведённую здесь как отправная точка, не как финальный код для
дословного копирования — проверь корректность синтаксиса GLSL 450 самостоятельно):

```glsl
layout(local_size_x = 64) in;

bool testOcclusion(vec4 bounds) {
    // placeholder in this ticket: return false (no occlusion culling yet — Hi-Z
    // occlusion testing is ticket 22, which will extend this shader to call a
    // real Hi-Z test here). Document this clearly with a comment so ticket 22
    // knows exactly what to replace.
    return false;
}

bool testFrustum(vec4 bounds) {
    // implement a standard sphere-vs-frustum-planes test using frustumPlanes
    // from FrameUniforms and bounds.xyz (center) / bounds.w (radius)
}

void enqueueChild(uint childNodeId) {
    uint slot = atomicAdd(tail, 1u) % capacity;
    items[slot] = childNodeId;
}

void main() {
    atomicAdd(activeCount, 1u); // this invocation starts "active"

    while (true) {
        uint idx = atomicAdd(head, 1u);
        uint currentTail = atomicLoad(tail); // GLSL doesn't have atomicLoad on plain
                                               // uint the same way as atomicAdd(x,0) does —
                                               // use atomicAdd(tail, 0u) as the read idiom
                                               // if targeting GLSL versions without
                                               // native atomicLoad-equivalent; verify
                                               // correct GLSL 450 syntax during implementation.
        if (idx >= currentTail) {
            // Queue appears empty. Signal this invocation is idle, then check for
            // global termination (all workgroups idle AND queue still empty) before
            // deciding whether to spin-wait or exit. This termination detection is
            // the trickiest part of persistent-kernel design — implement carefully:
            // a naive "just exit" here would cause premature termination while
            // sibling workgroups are still about to enqueue new children.
            atomicAdd(activeCount, -1u);
            memoryBarrierBuffer();
            // busy-wait with re-check loop, bounded by a max iteration count to
            // avoid GPU hangs on a driver/logic bug — pick a generous but finite
            // bound (document the chosen value and why) and break out (treating
            // it as queue-exhausted) if exceeded even if activeCount suggests
            // otherwise, as a safety valve.
            bool rejoined = false;
            for (int spin = 0; spin < MAX_SPIN_ITERATIONS; spin++) {
                if (atomicAdd(tail, 0u) > idx) {
                    atomicAdd(activeCount, 1u);
                    rejoined = true;
                    break;
                }
                if (atomicAdd(activeCount, 0u) == 0u) {
                    break; // genuinely done: nobody active, queue still empty
                }
            }
            if (!rejoined) break;
            continue;
        }

        uint nodeId = items[idx % capacity];
        vec4 bounds = nodeBounds[nodeId];

        if (testFrustum(bounds) == false) continue;
        if (testOcclusion(bounds)) continue;

        uint flags = nodeFlags[nodeId];
        uint childMask = nodeChildMask[nodeId];
        uint streamState = nodeStreamState[nodeId];

        if (streamState != STREAM_STATE_RESIDENT) {
            uint reqSlot = atomicAdd(requestCount, 1u);
            requestedNodeIds[reqSlot] = nodeId;
        }

        if (childMask == 0u) {
            // leaf: no children to descend into, add to render list if resident
            if (streamState == STREAM_STATE_RESIDENT) {
                uint slot = atomicAdd(renderCount, 1u);
                renderNodeIds[slot] = nodeId;
            }
        } else {
            for (uint c = 0u; c < 8u; c++) {
                if ((childMask & (1u << c)) != 0u) {
                    // childNodeId lookup: how child node ids map to parent+childIndex
                    // must be resolved via a scheme established in ticket 25/render-side
                    // hierarchy construction — for THIS ticket, assume a helper is
                    // available (e.g. a childNodeIds SoA buffer, or an arithmetic mapping)
                    // and document the assumption explicitly with a TODO/comment if the
                    // concrete mechanism isn't finalized yet, so ticket 25 can wire it up.
                    uint childNodeId = resolveChildNodeId(nodeId, c);
                    enqueueChild(childNodeId);
                }
            }
        }
    }
}
```

## Требования к реализации

1. Реализуй ПОЛНОСТЬЮ рабочий, синтаксически корректный GLSL 450 compute shader — псевдокод
   выше — отправная точка и объяснение алгоритма, не финальный код; проверь и исправь любые
   синтаксические неточности (например, `atomicLoad` не существует как отдельная функция в
   стандартном GLSL для buffer-переменных — используй `atomicAdd(x, 0u)` как idiom для
   atomic-чтения, как и отмечено в комментарии псевдокода, либо другой корректный подход —
   проверь через официальную GLSL-спецификацию/документацию, если не уверен).

2. **Termination detection** — это самая тонкая часть корректности. Задокументируй
   выбранный алгоритм подробно в комментариях GLSL-файла: как гарантируется, что ни один
   workgroup не завершится преждевременно, пока другие ещё могут добавить работу в очередь
   (что привело бы к недообходу дерева — часть геометрии не попадёт в render list), и как
   гарантируется финальное завершение (что цикл не зависнет вечно, если очередь действительно
   пуста и все неактивны). Используй `MAX_SPIN_ITERATIONS` как safety valve — выбери
   конкретное числовое значение и обоснуй его (например, в терминах ожидаемого worst-case
   времени одного traversal-шага).

3. **`resolveChildNodeId(nodeId, childIndex)`** — в этом тикете допустимо оставить как
   явно помеченный TODO/заглушку (например, возвращающую заведомо невалидный индекс с
   комментарием), ЕСЛИ конкретный механизм связи parent/child node id ещё не определён на
   момент выполнения этого тикета (он окончательно закрепляется в тикете 25, где строится
   реальная GPU-резидентная иерархия). Если считаешь целесообразным — предложи и
   реализуй простую рабочую схему уже здесь (например, отдельный SSBO `childNodeIds[nodeId *
   8 + childIndex]`), задокументировав её как "рабочее предположение, может быть
   пересмотрено в тикете 25". Не блокируй весь тикет ожиданием финальной схемы — важно,
   чтобы остальная traversal-логика (очередь, frustum test, termination) была реализована и
   тестируема независимо от этой детали.

4. **Java-сторона**: создай `dev.ev.gpu.traversal.TraversalDispatcher` —
   тонкий класс, оборачивающий `ComputePipeline`, скомпилированный из `traversal.comp` через
   `RenderBackend.compilePipeline`, с методом `void dispatch(int workgroupCount)`,
   вызывающим `pipeline.dispatch(workgroupCount, 1, 1)`. Держи этот класс маленьким — вся
   сложность в GLSL, Java-сторона на этом этапе — просто интеграционная обвязка.

5. Никакого мутируемого статического состояния на Java-стороне.

## Тесты
Полноценное тестирование GLSL compute-логики требует GPU-исполнения. Для этого тикета:
1. Если headless GL доступен — напиши интеграционный тест: заполни небольшое синтетическое
   дерево (например, 1 корень + 8 листьев) в `NodeBufferSoA`-подобных буферах вручную через
   CPU-upload, задай simple `WorkQueue` с одним элементом (корнем), диспетчеризуй traversal,
   прочитай `RenderList` обратно — проверь, что все резидентные листья, проходящие
   frustum-тест, оказались в `renderNodeIds`.
2. Если headless GL недоступен в среде выполнения — как минимум напиши тест на
   `TraversalDispatcher` с fake `ComputePipeline`/`RenderBackend` (проверка, что
   `dispatch(N)` вызывает `pipeline.dispatch(N, 1, 1)` ровно один раз) — это не проверяет
   корректность самого шейдера, но гарантирует корректность интеграционной обвязки; явно
   отметь в комментарии/README тикета, что полная валидация шейдерной логики требует
   ручного/интеграционного тестирования на реальном GPU при первом запуске мода целиком
   (тикет 31).

## Критерии приёмки
1. Файл `ev-gpu/src/main/resources/shaders/traversal.comp`.
2. Файл `ev-gpu/src/main/java/dev/ev/gpu/traversal/TraversalDispatcher.java`.
3. Termination detection алгоритм подробно задокументирован в комментариях шейдера.
4. Доступные тесты (интеграционные или fake-based, согласно среде) проходят.
5. `resolveChildNodeId` — либо рабочая реализация, либо явно помеченная заглушка с
   комментарием, указывающим на тикет 25 как место финализации.
6. Модуль компилируется без ошибок на Java-стороне (компиляция самого GLSL-шейдера
   драйвером — runtime-проверка, не часть `./gradlew build`, но по возможности прогони её
   вручную/через доступный headless-путь, если он есть в среде выполнения, для раннего
   обнаружения синтаксических ошибок GLSL).
