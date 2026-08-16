# Тикет 20-opt — SoA Node Buffer Layout

## ⚠️ Когда это применять

Это тикет Волны 2 (см. `MVP_INDEX.md`). MVP-версия (`20-gpu-node-buffer-mvp.md`) использует
обычный AoS-буфер (одна структура на узел, все поля подряд в одном SSBO) — проще для первой
реализации, меньше движущихся частей (1 буфер вместо 5). **Не выполняй этот тикет**, пока не
пройден `P0-profiling-checkpoint.md` и результаты не показали:
- "CPU/GPU breakdown" (Сценарий B) указывает на GPU-side долю как значимую, И профилирование
  через RenderDoc (или аналог) показывает высокую memory bandwidth utilization именно во
  время traversal/culling прохода (не во время рендера самой геометрии) — это специфичный
  диагностический признак именно memory-bound характера работы, отличимый от, например,
  compute-bound или latency-bound характера, для которых SoA не даст выигрыша.

Если traversal/culling не является GPU-стороны узким местом вообще (например, если по
результатам профилирования узкое место — CPU-side traversal, что указывает скорее на
`21-opt`/`25-opt`, не на этот тикет) — этот тикет не нужен.

**⚠️ Этот тикет НЕ является drop-in заменой `20-gpu-node-buffer-mvp.md`**: MVP-версия
(`NodeBuffer`) предоставляет один метод `buffer()`, эта opt-версия (`NodeBufferSoA`) —
пять раздельных геттеров (`boundsBuffer()`, `flagsBuffer()`, и т.д.). Любой вызывающий код
(скорее всего `traversal.comp`, тикет 21, и/или `EVInstance.renderFarLod`, тикет 27)
потребует правки биндингов при переходе — заложи это в оценку объёма работы этого тикета,
не считай его изолированной заменой одного класса без последствий для вызывающего кода.

## Контекст
EV — мод дальней прорисовки для NeoForge 1.21.1. Модуль `ev-gpu`. Реализует
GPU-резидентную структуру хранения узлов октодерева в раскладке Structure-of-Arrays (SoA),
обоснованную в PERFORMANCE_MATH.md разделе B.4: traversal-шейдер на каждом шаге читает у
узла только 3 "горячих" поля (bounds, occlusion-флаг, child-mask), тогда как материалы и
streaming-состояние нужны реже. AoS-раскладка (все поля узла подряд) тянет в кэш/GPU L2
лишние байты на каждый доступ; SoA хранит каждое поле в отдельном плотном буфере, повышая
эффективную полосу пропускания памяти на traversal hot-path (traversal обычно
memory-bound, не compute-bound).

## Готовый контракт из зависимости (тикет 17, уже реализован)
```java
package dev.ev.api.gpu;
public interface GpuBuffer {
    long sizeBytes();
    BufferUsage usage();
    void free();
}
public interface RenderBackend {
    GpuBuffer createBuffer(long sizeBytes, BufferUsage usage);
    // ...
}
```

## Задача

### Java-сторона: `NodeBufferSoA`
```java
package dev.ev.gpu.nodes;

import dev.ev.api.gpu.GpuBuffer;
import dev.ev.api.gpu.RenderBackend;

/**
 * Manages the GPU-resident Structure-of-Arrays layout for octree node data.
 * Five separate GPU buffers, each a dense array indexed by the same nodeId:
 *
 *   boundsBuffer   : vec4 per node (xyz = center, w = radius) — read every traversal step
 *   flagsBuffer    : uint per node (bit 0 = occluded-last-frame, bit 1 = resident, ...) — read every step
 *   childMaskBuffer: uint per node (8-bit mask of which of 8 children exist/are resident) — read every step
 *   materialBuffer : uint per node (material palette reference) — read only at render time, not traversal
 *   streamBuffer   : uint per node (streaming state: NOT_REQUESTED/REQUESTED/RESIDENT) — read only for streaming decisions
 *
 * Splitting into 5 buffers (rather than one struct-per-node buffer) means the
 * traversal compute shader only touches boundsBuffer/flagsBuffer/childMaskBuffer,
 * keeping its working set small and cache/bandwidth-efficient (see
 * PERFORMANCE_MATH.md section B.4). materialBuffer/streamBuffer are bound
 * separately and only touched by passes that need them.
 *
 * Node capacity is fixed at construction time (see ticket on adaptive queue sizing
 * in ARCHITECTURE.md section 6.3 for how the caller should derive this from a VRAM
 * budget — that derivation itself is out of scope here, this class just takes a
 * capacity and allocates buffers of that size).
 */
public final class NodeBufferSoA implements AutoCloseable {

    public static final int BYTES_PER_BOUNDS = 16;   // vec4: 4 floats
    public static final int BYTES_PER_FLAGS = 4;      // uint
    public static final int BYTES_PER_CHILD_MASK = 4; // uint
    public static final int BYTES_PER_MATERIAL = 4;   // uint
    public static final int BYTES_PER_STREAM_STATE = 4; // uint

    public NodeBufferSoA(RenderBackend backend, int nodeCapacity) { /* allocates 5 STORAGE buffers */ }

    public int nodeCapacity();

    public GpuBuffer boundsBuffer();
    public GpuBuffer flagsBuffer();
    public GpuBuffer childMaskBuffer();
    public GpuBuffer materialBuffer();
    public GpuBuffer streamBuffer();

    /** Total GPU memory footprint across all 5 buffers, for metrics/budget accounting. */
    public long totalSizeBytes();

    @Override
    void close(); // frees all 5 buffers
}
```

### GLSL-сторона: `node_buffers.glsl` (общий include-файл для шейдеров тикетов 21-23)
Создай файл `ev-gpu/src/main/resources/shaders/include/node_buffers.glsl` с
GLSL-декларациями SSBO, СОГЛАСОВАННЫМИ с Java-стороной byte layout выше:

```glsl
// node_buffers.glsl — shared SSBO declarations for the SoA node buffer layout.
// Binding indices below MUST match the PipelineLayout.bindingsByName() entries
// used when compiling any shader that includes this file (see ticket 18 for how
// binding resolution works on the Java side) — the numeric values here are the
// authoritative GLSL-side binding points; Java-side PipelineLayout maps names to
// these same numbers.

layout(std430, binding = 0) buffer NodeBoundsSoA {
    vec4 nodeBounds[]; // xyz = center, w = radius
};

layout(std430, binding = 1) buffer NodeFlagsSoA {
    uint nodeFlags[];
};

layout(std430, binding = 2) buffer NodeChildMaskSoA {
    uint nodeChildMask[];
};

layout(std430, binding = 3) buffer NodeMaterialSoA {
    uint nodeMaterial[];
};

layout(std430, binding = 4) buffer NodeStreamSoA {
    uint nodeStreamState[];
};

// Flag bit constants (keep in sync with any Java-side constants you define in
// NodeBufferSoA or a sibling NodeFlags.java class):
#define NODE_FLAG_OCCLUDED_LAST_FRAME (1u << 0)
#define NODE_FLAG_RESIDENT            (1u << 1)

#define STREAM_STATE_NOT_REQUESTED 0u
#define STREAM_STATE_REQUESTED     1u
#define STREAM_STATE_RESIDENT      2u
```

## Требования к реализации

1. Каждый из 5 буферов — `BufferUsage.STORAGE`, размер `nodeCapacity * BYTES_PER_X`
   соответственно.
2. `close()` освобождает все 5 буферов (вызывает `free()` на каждом); если один из
   `free()` бросает исключение — постарайся всё равно попытаться освободить остальные
   (не бросай на первом же исключении, потеряв остальные ресурсы — собери исключения и
   выбрось агрегированное, либо залогируй и продолжи; выбери и задокументируй подход).
3. Опиши в Javadoc класса точное соответствие `nodeId` (используемого как индекс во всех
   5 буферах) с реальными позициями секций (`SectionPos`) — это связь устанавливается
   ВНЕ этого класса (в тикете 21/25, где строится сама иерархия), `NodeBufferSoA` лишь
   предоставляет индексированное по `nodeId` хранилище, не знает, что означает конкретный
   `nodeId`.
4. Убедись, что GLSL `std430` layout действительно даёт ожидаемый Java-side byte size
   (`vec4` = 16 байт с выравниванием 16, `uint` = 4 байта с выравниванием 4 — для этих
   конкретных простых массивов примитивов/vec4 выравнивание не создаёт padding-сюрпризов,
   но задокументируй это явно в комментарии GLSL-файла, чтобы будущие изменения структуры
   (например, добавление поля в `vec4`-подобный элемент) не сломали layout незаметно).
5. Никакого мутируемого статического состояния.

## Юнит-тесты
1. Если headless GL доступен в тестовой среде — интеграционный тест: создать
   `NodeBufferSoA` с малой ёмкостью (например, 100), проверить `nodeCapacity()`,
   `totalSizeBytes()` равен ожидаемой сумме, `close()` не бросает исключений при нормальном
   освобождении.
2. Без GL — как минимум юнит-тест на чистую арифметику `totalSizeBytes()` (может быть
   протестирован с помощью fake/mock `RenderBackend`, возвращающего buffer-заглушки с
   заданным `sizeBytes()`, без реального GL) — проверь, что сумма корректно равна
   `nodeCapacity * (16+4+4+4+4)`.
3. Тест, что `close()` вызывает `free()` на всех 5 буферах ровно по одному разу (через
   fake `GpuBuffer`, считающий вызовы `free()`).

## Критерии приёмки
1. Java-файл `ev-gpu/src/main/java/dev/ev/gpu/nodes/NodeBufferSoA.java`.
2. GLSL-файл `ev-gpu/src/main/resources/shaders/include/node_buffers.glsl`.
3. Юнит-тесты проходят (как минимум п.2-3 из списка выше, не требующие GL).
4. Модуль компилируется без ошибок.
5. Соответствие Java byte-layout и GLSL `std430` layout задокументировано явно в обоих
   файлах (комментарии перекрёстно ссылаются друг на друга по имени файла, чтобы будущие
   правки одной стороны напомнили проверить другую).
