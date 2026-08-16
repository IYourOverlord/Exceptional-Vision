# Тикет 20-mvp — AoS Node Buffer Layout

## Контекст
Часть Волны 1 (MVP) плана EV — см. `MVP_INDEX.md` за полным обоснованием MVP-first
подхода. Это упрощённая версия GPU-резидентного хранения данных секций для culling/рендера:
ОДИН SSBO со всеми полями узла подряд (Array-of-Structures, AoS) — все поля секции (bounds,
флаги, materialRef и т.д.) читаются вместе как одна структура. Проще в реализации и
использовании (один буфер вместо пяти), чем "целевая" SoA-раскладка
(`20-gpu-node-buffer-soa-opt.md`), которая вводится только если профилирование покажет, что
traversal реально memory-bound и bandwidth — узкое место.

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

### Java-сторона: `NodeBuffer`
```java
package dev.ev.gpu.nodes;

import dev.ev.api.gpu.GpuBuffer;
import dev.ev.api.gpu.RenderBackend;

/**
 * MVP implementation: manages a single GPU-resident Array-of-Structures buffer
 * for section node data. Each node occupies BYTES_PER_NODE contiguous bytes:
 *
 *   vec4 bounds (16 bytes: xyz = center, w = radius)
 *   uint flags (4 bytes)
 *   uint materialRef (4 bytes)
 *   uint streamState (4 bytes)
 *   uint padding (4 bytes, for 16-byte alignment of the whole struct, required
 *                 by std430 layout rules for arrays of structs containing a vec4)
 *
 * Total: 32 bytes per node.
 *
 * See MVP_INDEX.md for why this simple single-buffer version comes first —
 * 20-gpu-node-buffer-soa-opt.md replaces this only if profiling shows this
 * layout's memory bandwidth characteristics are actually a bottleneck during
 * traversal.
 *
 * NOTE: this MVP version does NOT include a separate childMask field used by
 * hierarchical GPU traversal (ticket 21-opt) — since 21-gpu-simple-traversal-mvp.md
 * does traversal on the CPU side over a flat list of loaded sections, not a GPU
 * tree walk, there is no need for GPU-resident parent/child linkage in the MVP
 * architecture. If 21-opt is later applied, this buffer's schema will need
 * revisiting (likely adopting 20-opt's SoA layout at that point, or extending
 * this AoS layout with a childMask field first as an intermediate step —
 * document whichever path is actually taken at that time in PROJECT_INDEX.md).
 *
 * UNLIKE most MVP/opt pairs in this project (e.g. SectionCache in ticket 08),
 * this class's public API is NOT contract-compatible with 20-gpu-node-buffer-soa-opt.md's
 * NodeBufferSoA: this class exposes a single buffer() getter, while NodeBufferSoA
 * exposes five separate getters (boundsBuffer(), flagsBuffer(), etc.) — one buffer
 * cannot be split into five without changing every call site that binds it (e.g.
 * shader binding code in traversal.comp, ticket 21). Any caller of this class
 * (most likely ticket 27's EVInstance.renderFarLod) WILL need to be updated
 * if/when 20-opt is applied — do not assume a drop-in replacement here.
 */
public final class NodeBuffer implements AutoCloseable {

    public static final int BYTES_PER_NODE = 32;

    public NodeBuffer(RenderBackend backend, int nodeCapacity) { /* allocates one STORAGE buffer */ }

    public int nodeCapacity();

    public GpuBuffer buffer();

    public long totalSizeBytes();

    @Override
    void close();
}
```

### GLSL-сторона: `node_buffer_aos.glsl`
Создай `ev-gpu/src/main/resources/shaders/include/node_buffer_aos.glsl`:

```glsl
// node_buffer_aos.glsl — MVP Array-of-Structures node buffer declaration.
// See NodeBuffer.java for the authoritative byte layout this must match exactly.

struct Node {
    vec4 bounds;      // xyz = center, w = radius
    uint flags;
    uint materialRef;
    uint streamState;
    uint _padding;
};

layout(std430, binding = 0) buffer NodeBufferAoS {
    Node nodes[];
};

#define NODE_FLAG_RESIDENT (1u << 0)

#define STREAM_STATE_NOT_REQUESTED 0u
#define STREAM_STATE_REQUESTED     1u
#define STREAM_STATE_RESIDENT      2u
```

## Требования к реализации
1. Один `BufferUsage.STORAGE` буфер, размер `nodeCapacity * BYTES_PER_NODE`.
2. Убедись, что GLSL `std430` layout для `struct Node` действительно даёт 32 байта на
   элемент — `vec4` (16 байт, выравнивание 16) + 3×`uint` (12 байт) + `_padding` (4 байта) =
   32 байта, кратно 16 (обязательное требование std430 для массивов структур, содержащих
   vec4) — задокументируй это явно в комментарии GLSL-файла.
3. `close()` освобождает единственный буфер.
4. Никакого мутируемого статического состояния.

## Юнит-тесты (обязательно, JUnit 5)
1. Если headless GL доступен — интеграционный тест: создать `NodeBuffer` с малой ёмкостью,
   проверить `nodeCapacity()`, `totalSizeBytes() == nodeCapacity * 32`, `close()` не бросает
   исключений.
2. Без GL — юнит-тест на `totalSizeBytes()` с fake `RenderBackend` (аналогично тикету 20-opt) —
   проверь корректную арифметику.
3. Тест, что `close()` вызывает `free()` на буфере ровно один раз.

## Критерии приёмки
1. Java-файл `ev-gpu/src/main/java/dev/ev/gpu/nodes/NodeBuffer.java`.
2. GLSL-файл `ev-gpu/src/main/resources/shaders/include/node_buffer_aos.glsl`.
3. Юнит-тесты проходят.
4. Модуль компилируется без ошибок.
5. Javadoc `NodeBuffer` явно документирует MVP-статус и условие перехода на SoA (`20-opt`).
6. `PROJECT_INDEX.md` (тикет 32) обновлён с пометкой "Node buffer — MVP (AoS, единый буфер)
   версия активна, SoA opt-версия в банке, не применена".
