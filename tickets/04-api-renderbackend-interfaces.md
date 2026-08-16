# Тикет 04 — Интерфейсы RenderBackend (абстракция над GPU)

## Контекст
EV — мод дальней прорисовки для NeoForge 1.21.1. Модуль `ev-api`, чистый Java, без
NeoForge/LWJGL зависимостей. Это САМЫЙ ВАЖНЫЙ архитектурный контракт проекта: единственная
граница между доменной логикой (storage/meshing/render-оркестрация) и реальным GPU API
(OpenGL через LWJGL, реализуется в модуле `ev-gpu`). Ни один класс вне модуля
`ev-gpu` не должен вызывать LWJGL напрямую — весь доступ к GPU идёт через эти интерфейсы.

Эта абстракция также позволяет писать юнит-тесты доменной логики с `FakeRenderBackend`
(тикет 30) без реального GPU-контекста/окна.

## Задача
Создать в пакете `dev.ev.api.gpu` следующие типы. Здесь только контракты — реализация
на LWJGL/OpenGL в тикетах 17-23 (модуль `ev-gpu`).

### `RenderBackend`
```java
package dev.ev.api.gpu;

/**
 * Abstraction over the GPU API in use (OpenGL via LWJGL for now). No class outside
 * the ev-gpu module may call LWJGL/OpenGL directly — everything goes through
 * this interface, so that ev-render, ev-storage, ev-meshing remain
 * testable without a real GPU context and portable to a future backend if needed.
 */
public interface RenderBackend {
    GpuBuffer createBuffer(long sizeBytes, BufferUsage usage);
    GpuTexture createTexture(TextureDesc desc);
    ComputePipeline compilePipeline(ShaderSource source, PipelineLayout layout);
    GraphicsPipeline compileGraphicsPipeline(ShaderSource vertexSrc, ShaderSource fragmentSrc, PipelineLayout layout);

    /** Submits a recorded command list for execution. May be asynchronous. */
    void submit(CommandList commands);

    /** Inserts a GPU fence into the current command stream; returns a handle to poll later. */
    FenceHandle insertFence();

    /** Non-blocking check: has the GPU work up to this fence completed? */
    boolean isSignaled(FenceHandle fence);

    /** Blocks the calling thread until the fence is signaled or timeoutNanos elapses. */
    boolean waitForFence(FenceHandle fence, long timeoutNanos);

    /** Releases all backend resources. Must be called exactly once, on the render thread. */
    void shutdown();
}
```

### `BufferUsage`
```java
package dev.ev.api.gpu;

public enum BufferUsage {
    /** Written rarely from CPU, read often by GPU shaders (e.g. static geometry). */
    STATIC_DRAW,
    /** Written every frame from CPU (e.g. uniform/scene data). */
    DYNAMIC_DRAW,
    /** Read/written by compute shaders, rarely touched by CPU (e.g. node buffer, queues). */
    STORAGE,
    /** CPU-visible persistent-mapped staging buffer for streaming uploads. */
    STAGING_UPLOAD,
    /** GPU-to-CPU readback buffer (e.g. streaming request queue results). */
    STAGING_DOWNLOAD
}
```

### `GpuBuffer`
```java
package dev.ev.api.gpu;

/** Opaque handle to a GPU buffer. Lifecycle owned by whoever created it via RenderBackend. */
public interface GpuBuffer {
    long sizeBytes();
    BufferUsage usage();

    /**
     * For STAGING_UPLOAD buffers only: returns a CPU-writable memory address
     * (persistent mapped pointer) valid until free(). Throws UnsupportedOperationException
     * for other usages.
     */
    long mappedAddress();

    void free();
}
```

### `TextureDesc` / `GpuTexture`
```java
package dev.ev.api.gpu;

public record TextureDesc(int width, int height, int depth, TextureFormat format, int mipLevels) {
    public static TextureDesc texture2D(int width, int height, TextureFormat format, int mipLevels) {
        return new TextureDesc(width, height, 1, format, mipLevels);
    }
}

public enum TextureFormat {
    R32F, RGBA8, RGBA16F, DEPTH32F, R32UI
}

public interface GpuTexture {
    TextureDesc descriptor();
    void free();
}
```

### `ShaderSource` / `PipelineLayout` / `ComputePipeline` / `GraphicsPipeline`
```java
package dev.ev.api.gpu;

import java.util.Map;

/** Raw shader source text plus preprocessor defines to inject before compilation. */
public record ShaderSource(String sourcePath, String rawSource, Map<String, String> defines) {}

/** Declares the binding points a pipeline expects (buffers, textures, uniforms), by name. */
public record PipelineLayout(Map<String, Integer> bindingsByName) {}

public interface ComputePipeline {
    void dispatch(int groupsX, int groupsY, int groupsZ);
    void dispatchIndirect(GpuBuffer indirectBuffer, long offsetBytes);
    void bindBuffer(String bindingName, GpuBuffer buffer);
    void bindTexture(String bindingName, GpuTexture texture);
    void free();
}

public interface GraphicsPipeline {
    void drawIndirect(GpuBuffer indirectBuffer, long offsetBytes, int drawCount);
    void drawIndirectCount(GpuBuffer indirectBuffer, long offsetBytes, GpuBuffer countBuffer, long countOffsetBytes, int maxDrawCount);
    void bindBuffer(String bindingName, GpuBuffer buffer);
    void bindTexture(String bindingName, GpuTexture texture);
    void free();
}
```

### `CommandList` / `FenceHandle`
```java
package dev.ev.api.gpu;

/** A recorded sequence of GPU operations (dispatches, draws, barriers, uploads). */
public interface CommandList {
    void memoryBarrier(BarrierScope scope);
    void uploadToBuffer(GpuBuffer target, long targetOffsetBytes, long sourceAddress, long sizeBytes);
    void copyBuffer(GpuBuffer src, long srcOffset, GpuBuffer dst, long dstOffset, long sizeBytes);
    void clearBuffer(GpuBuffer buffer, long offsetBytes, long sizeBytes, int fillValue);
    void dispatchCompute(ComputePipeline pipeline, int groupsX, int groupsY, int groupsZ);
    void dispatchComputeIndirect(ComputePipeline pipeline, GpuBuffer indirectBuffer, long offsetBytes);
    void draw(GraphicsPipeline pipeline, GpuBuffer indirectBuffer, long offsetBytes, int drawCount);
}

public enum BarrierScope {
    SHADER_STORAGE, COMMAND, BUFFER_UPDATE, ALL
}

/** Opaque handle representing a point in the GPU command stream. */
public interface FenceHandle {}
```

## Требования
1. Ровно эти сигнатуры, пакет `dev.ev.api.gpu`.
2. Полные Javadoc.
3. Никакой реализации, никаких `import org.lwjgl.*` где бы то ни было в этом тикете —
   модуль `ev-api` не должен знать о существовании LWJGL.
4. Если по ходу проектирования обнаружишь, что для реализации persistent-kernel traversal
   (см. тикет 21) не хватает какого-то метода в `RenderBackend`/`CommandList` — добавь его
   сюда с Javadoc-обоснованием, не изобретай его позже в другом модуле в обход контракта.

## Критерии приёмки
1. Все файлы созданы в `ev-api/src/main/java/dev/ev/api/gpu/`.
2. Модуль компилируется без ошибок, без единого импорта из `org.lwjgl.*` или NeoForge-классов.
3. Это чисто контрактный тикет — юнит-тестов не требуется, кроме, возможно, простого теста
   на `PipelineLayout`/`ShaderSource` (immutability record'ов), если считаешь целесообразным.
