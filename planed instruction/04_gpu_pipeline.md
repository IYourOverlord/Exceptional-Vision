# GPU-driven рендер-пайплайн

## Принцип

CPU не строит геометрию и не решает, что видимо. Каждый кадр CPU делает
ровно три вызова: обновить uniform-данные камеры, запустить compute
shader, вызвать один indirect multidraw. Вся работа по отбору видимых
узлов и генерации геометрии выполняется на GPU.

## 1. Compute shader: culling видимости узлов

Каждый поток обрабатывает один узел quadtree, проверяет пересечение
с frustum камеры, и если узел видим — атомарно резервирует слот в
буфере команд отрисовки.

```glsl
#version 460
layout(local_size_x = 64) in;

struct NodeData {
    vec4 aabbMin;
    vec4 aabbMax;
    uint quadOffset;
    uint quadCount;
    uint lodLevel;
    uint _pad;
};

struct DrawCmd {
    uint count;          // всегда 4 (triangle strip на квад)
    uint instanceCount;   // = quadCount видимого узла
    uint first;
    uint baseInstance;    // = quadOffset видимого узла
};

layout(std430, binding = 1) readonly buffer NodeBuffer   { NodeData nodes[]; };
layout(std430, binding = 2) writeonly buffer IndirectCmd { DrawCmd cmds[]; };
layout(std430, binding = 3) buffer DrawCounter           { uint drawCount; };

uniform vec4 frustumPlanes[6];
uniform vec3 camPos;
uniform float lodDistanceThresholds[16]; // дистанция переключения LOD-уровней

bool aabbInFrustum(vec3 mn, vec3 mx) {
    for (int i = 0; i < 6; i++) {
        vec3 p = mix(mn, mx, greaterThan(frustumPlanes[i].xyz, vec3(0.0)));
        if (dot(frustumPlanes[i].xyz, p) + frustumPlanes[i].w < 0.0) {
            return false;
        }
    }
    return true;
}

bool lodLevelAppropriate(vec3 center, uint level) {
    float dist = distance(center, camPos);
    return dist >= (level == 0u ? 0.0 : lodDistanceThresholds[level - 1u])
        && dist <  lodDistanceThresholds[level];
}

void main() {
    uint idx = gl_GlobalInvocationID.x;
    if (idx >= nodes.length()) return;

    NodeData node = nodes[idx];
    if (node.quadCount == 0u) return;
    if (!aabbInFrustum(node.aabbMin.xyz, node.aabbMax.xyz)) return;

    vec3 center = (node.aabbMin.xyz + node.aabbMax.xyz) * 0.5;
    if (!lodLevelAppropriate(center, node.lodLevel)) return;

    uint slot = atomicAdd(drawCount, 1u);
    cmds[slot].count = 4u;
    cmds[slot].instanceCount = node.quadCount;
    cmds[slot].first = 0u;
    cmds[slot].baseInstance = node.quadOffset;
}
```

## 2. Vertex shader: генерация геометрии квада на лету

Вершинных буферов с позициями нет вообще — 4 угла квада вычисляются
математически из `gl_VertexID` (0..3) и упакованных данных в SSBO.

```glsl
#version 460
struct QuadData { uint packed0; uint packed1; };
layout(std430, binding = 0) readonly buffer QuadBuffer { QuadData quads[]; };

uniform mat4 viewProj;
uniform vec3 nodeOrigin; // мировое начало координат текущего узла

out flat uint vMaterial;
out vec3 vNormal;

vec3 axisNormal(uint axis) {
    vec3 n[6] = vec3[](vec3(1,0,0), vec3(-1,0,0), vec3(0,1,0),
                        vec3(0,-1,0), vec3(0,0,1), vec3(0,0,-1));
    return n[axis];
}

vec3 quadCorner(uint vertexId, uint axis, float w, float h) {
    vec2 uv = vec2(vertexId == 1u || vertexId == 2u ? 1.0 : 0.0,
                    vertexId >= 2u ? 1.0 : 0.0);
    vec2 size = uv * vec2(w, h);
    if (axis == 0u || axis == 1u) return vec3(0.0, size.x, size.y);
    if (axis == 2u || axis == 3u) return vec3(size.x, 0.0, size.y);
    return vec3(size.x, size.y, 0.0);
}

void main() {
    QuadData q = quads[gl_BaseInstanceARB + gl_InstanceID];

    uint x    =  q.packed0        & 0x3Fu;
    uint y    = (q.packed0 >> 6)  & 0xFFFu;
    uint z    = (q.packed0 >> 18) & 0x3Fu;
    uint axis = (q.packed0 >> 24) & 0x7u;
    uint w    =  q.packed1        & 0x3Fu;
    uint h    = (q.packed1 >> 6)  & 0x3Fu;
    uint mat  = (q.packed1 >> 12) & 0xFFFFu;

    vec3 corner = quadCorner(uint(gl_VertexID), axis, float(w), float(h));
    vec3 worldPos = nodeOrigin + vec3(float(x), float(y), float(z)) + corner;

    gl_Position = viewProj * vec4(worldPos, 1.0);
    vMaterial = mat;
    vNormal = axisNormal(axis);
}
```

## 3. Fragment shader: выборка цвета из палитры

```glsl
#version 460
layout(std430, binding = 4) readonly buffer MaterialPalette { uint colors[]; };
in flat uint vMaterial;
in vec3 vNormal;
out vec4 fragColor;

void main() {
    uint packedColor = colors[vMaterial];
    vec4 base = vec4(
        float((packedColor >>  0) & 0xFFu) / 255.0,
        float((packedColor >>  8) & 0xFFu) / 255.0,
        float((packedColor >> 16) & 0xFFu) / 255.0,
        1.0
    );
    float lightFactor = 0.6 + 0.4 * max(dot(vNormal, normalize(vec3(0.3,1.0,0.2))), 0.0);
    fragColor = vec4(base.rgb * lightFactor, 1.0);
}
```

## 4. Java-сторона: последовательность вызовов в кадре

```java
public void renderLodFrame(Matrix4f viewProj, Vector3f camPos, float[] frustumPlanes) {
    // 1. Обновить uniform-данные
    computeProgram.use();
    computeProgram.setUniform("frustumPlanes", frustumPlanes);
    computeProgram.setUniform("camPos", camPos);

    // 2. Обнулить счётчик видимых узлов
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, drawCounterBuffer);
    glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, new int[]{0});

    // 3. Запустить culling на GPU
    int workGroups = (nodeCount + 63) / 64;
    glDispatchCompute(workGroups, 1, 1);
    glMemoryBarrier(GL_COMMAND_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);

    // 4. Один отрисовочный вызов на все видимые узлы
    renderProgram.use();
    renderProgram.setUniform("viewProj", viewProj);
    glBindBuffer(GL_DRAW_INDIRECT_BUFFER, indirectCmdBuffer);
    glBindBuffer(GL_PARAMETER_BUFFER_ARB, drawCounterBuffer);
    glMultiDrawArraysIndirectCount(GL_TRIANGLE_STRIP, 0, 0, maxNodeCount, 0);
}
```

## 5. Обновление данных без остановки рендера (persistent mapped buffers)

```java
public final class QuadBufferManager {
    private final long bufferId;
    private final ByteBuffer persistentMapping;

    public void init(int capacityBytes) {
        int flags = GL_MAP_WRITE_BIT | GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT;
        glBufferStorage(GL_SHADER_STORAGE_BUFFER, capacityBytes,
            GL_MAP_WRITE_BIT | GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT);
        persistentMapping = glMapBufferRange(GL_SHADER_STORAGE_BUFFER,
            0, capacityBytes, flags);
    }

    public void writeQuads(int offsetBytes, List<PackedQuad> quads) {
        persistentMapping.position(offsetBytes);
        for (PackedQuad q : quads) {
            persistentMapping.putInt(q.packed0());
            persistentMapping.putInt(q.packed1());
        }
    }
}
```

## Требования к драйверу/GPU

| Функциональность                       | Минимальная версия OpenGL |
|-----------------------------------------|---------------------------|
| Compute shaders                         | 4.3                       |
| SSBO (Shader Storage Buffer Object)     | 4.3                       |
| `glMultiDrawArraysIndirect`             | 4.3 (ARB с 4.0)           |
| `glMultiDrawArraysIndirectCount`        | 4.6 / `ARB_indirect_parameters` |
| Persistent mapped buffers               | 4.4 / `ARB_buffer_storage`|

**Fallback:** если `ARB_indirect_parameters` недоступен (GPU без OpenGL
4.6), можно читать `drawCount` обратно на CPU через `glGetBufferSubData`
и вызывать `glMultiDrawArraysIndirect` с явным количеством — теряется
часть выигрыша (один readback синхронизации на кадр), но пайплайн
продолжает работать на 4.3+.
