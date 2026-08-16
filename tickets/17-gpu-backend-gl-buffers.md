# Тикет 17 — GLRenderBackend: буферы и текстуры (OpenGL/LWJGL)

## Контекст
EV — мод дальней прорисовки для NeoForge 1.21.1. Модуль `ev-gpu` — **единственный**
модуль во всём проекте, которому разрешено импортировать `org.lwjgl.*`. Реализует
`dev.ev.api.gpu.RenderBackend` (контракт из тикета 04) поверх LWJGL/OpenGL 4.5+.
Minecraft/NeoForge уже подключает LWJGL как транзитивную зависимость в финальной сборке —
но для компиляции и тестов этого модуля отдельно нужна explicit-зависимость (уже настроена
в тикете 00, `ev-gpu/build.gradle.kts`).

Этот тикет — первая часть реализации `GLRenderBackend`: создание буферов и текстур. Компиляция
шейдеров — отдельный тикет 18. Traversal-шейдер и остальная логика — тикеты 19-23.

## Готовый контракт из зависимости (тикет 04, уже реализован)
```java
package dev.ev.api.gpu;

public interface RenderBackend {
    GpuBuffer createBuffer(long sizeBytes, BufferUsage usage);
    GpuTexture createTexture(TextureDesc desc);
    ComputePipeline compilePipeline(ShaderSource source, PipelineLayout layout);
    GraphicsPipeline compileGraphicsPipeline(ShaderSource vertexSrc, ShaderSource fragmentSrc, PipelineLayout layout);
    void submit(CommandList commands);
    FenceHandle insertFence();
    boolean isSignaled(FenceHandle fence);
    boolean waitForFence(FenceHandle fence, long timeoutNanos);
    void shutdown();
}

public enum BufferUsage { STATIC_DRAW, DYNAMIC_DRAW, STORAGE, STAGING_UPLOAD, STAGING_DOWNLOAD }

public interface GpuBuffer {
    long sizeBytes();
    BufferUsage usage();
    long mappedAddress(); // only for STAGING_UPLOAD, else UnsupportedOperationException
    void free();
}

public record TextureDesc(int width, int height, int depth, TextureFormat format, int mipLevels) {
    public static TextureDesc texture2D(int width, int height, TextureFormat format, int mipLevels);
}
public enum TextureFormat { R32F, RGBA8, RGBA16F, DEPTH32F, R32UI }
public interface GpuTexture {
    TextureDesc descriptor();
    void free();
}
```

## Задача
Создать `dev.ev.gpu.gl.GLRenderBackend` implementing `RenderBackend` — но **только** методы
`createBuffer` и `createTexture` полностью реализуй в этом тикете; остальные методы
(`compilePipeline`, `compileGraphicsPipeline`, `submit`, `insertFence`, `isSignaled`,
`waitForFence`, `shutdown`) — заглушки, бросающие `UnsupportedOperationException("implemented
in a later ticket")`, чтобы класс компилировался и частично тестировался уже сейчас (следующие
тикеты дополнят этот же класс — не создавай отдельный класс-конкурент, дополняй этот).

### `GLBuffer`
```java
package dev.ev.gpu.gl;

import dev.ev.api.gpu.BufferUsage;
import dev.ev.api.gpu.GpuBuffer;

/**
 * OpenGL buffer object wrapper. For STAGING_UPLOAD usage, uses persistent-mapped
 * buffers (glMapBufferRange with GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT | GL_MAP_WRITE_BIT)
 * so mappedAddress() returns a stable CPU-writable pointer for the buffer's lifetime,
 * avoiding repeated map/unmap overhead on every upload (see PERFORMANCE_MATH.md A.6 —
 * batch uploads via a persistently mapped staging buffer).
 */
public final class GLBuffer implements GpuBuffer {
    // constructor takes the raw GL buffer handle (int), sizeBytes, usage, and
    // (if STAGING_UPLOAD) the mapped pointer address obtained at creation time.
}
```

### `GLTexture`
```java
package dev.ev.gpu.gl;

import dev.ev.api.gpu.GpuTexture;
import dev.ev.api.gpu.TextureDesc;

/** OpenGL texture object wrapper (glTexStorage2D/3D-backed, immutable storage). */
public final class GLTexture implements GpuTexture {
    // constructor takes raw GL texture handle (int) and TextureDesc.
}
```

## Требования к реализации

1. **`createBuffer`**:
   - `STATIC_DRAW`/`DYNAMIC_DRAW`/`STORAGE`: create via `glCreateBuffers` +
     `glNamedBufferStorage` (DSA-style calls, GL 4.5+, avoids bind-to-edit pattern) with
     appropriate flags (`STORAGE` → `GL_DYNAMIC_STORAGE_BIT` if CPU writes are ever needed
     via `glNamedBufferSubData`, otherwise no flags for pure GPU-resident; `STATIC_DRAW`/
     `DYNAMIC_DRAW` → `GL_DYNAMIC_STORAGE_BIT`).
   - `STAGING_UPLOAD`: `glNamedBufferStorage` with `GL_MAP_WRITE_BIT | GL_MAP_PERSISTENT_BIT |
     GL_MAP_COHERENT_BIT`, then `glMapNamedBufferRange` immediately to obtain the persistent
     pointer, store it for `mappedAddress()`.
   - `STAGING_DOWNLOAD`: similarly but with `GL_MAP_READ_BIT | GL_MAP_PERSISTENT_BIT |
     GL_MAP_COHERENT_BIT`.
   - Use LWJGL's `org.lwjgl.opengl.GL45` static methods (or `GL46` if you determine via web
     search that Minecraft 1.21.1's LWJGL version and driver requirements safely support
     it — otherwise stay with GL45 for broader compatibility; verify via search, don't
     assume from memory).
   - Validate `sizeBytes > 0`, throw `IllegalArgumentException` otherwise.

2. **`createTexture`**:
   - Use `glCreateTextures` + `glTextureStorage2D` (or 3D if `desc.depth() > 1`) with a GL
     internal format mapped from `TextureFormat` (e.g. `R32F` → `GL_R32F`, `RGBA8` →
     `GL_RGBA8`, `RGBA16F` → `GL_RGBA16F`, `DEPTH32F` → `GL_DEPTH_COMPONENT32F`, `R32UI` →
     `GL_R32UI`) — write this mapping as a small private static method, keep it exhaustive
     (throw on unmapped enum values rather than silently defaulting, to catch future
     `TextureFormat` additions that forget to update this mapping).
   - `mipLevels` — passed directly to `glTextureStorage2D`'s levels parameter (must be >= 1).

3. **`GLBuffer.mappedAddress()`** throws `UnsupportedOperationException` if `usage() !=
   STAGING_UPLOAD` (per the API contract from ticket 04).

4. **`GLBuffer.free()` / `GLTexture.free()`**: call `glDeleteBuffers`/`glDeleteTextures`
   respectively; for persistently mapped buffers, `glUnmapNamedBuffer` before deletion is not
   strictly required by the GL spec (deletion implicitly unmaps) but do it explicitly for
   clarity and driver-compatibility safety — verify this via web search if uncertain, some
   drivers have historically had bugs here.

5. All GL calls MUST happen on the render thread (the thread with the current GL context)
   — document this requirement prominently in `GLRenderBackend`'s class Javadoc; this class
   does not need to enforce it via assertions in this ticket (that could be added later), but
   must not silently attempt cross-thread GL calls.

6. Никакого мутируемого статического состояния — `GLRenderBackend` instance-scoped, никаких
   `static` полей, хранящих GL handles/scratch-буферы (это прямое architectural
   исправление проблемы прототипа Voxy, задокументированной в исходном анализе — статический
   scratch-буфер ломает поддержку нескольких миров/пересоздания контекста).

## Юнит-тесты
GPU-код, требующий реального OpenGL-контекста, невозможно полноценно юнит-тестировать без
headless GL (например, через LWJGL's offscreen context или Mesa software rendering) — это
не входит в объём данного тикета. Вместо этого:
1. Создай тест(ы), НЕ требующие GL-контекста: например, тест приватного маппинга
   `TextureFormat -> GL internal format constant`, если этот маппинг вынесен в отдельный
   чистый метод/класс без прямого вызова GL API (просто возвращает int-константу) —
   протестируй, что каждое значение `TextureFormat` enum имеет соответствующий маппинг
   (используй `EnumSet`/цикл по `TextureFormat.values()`, проверь отсутствие исключения для
   каждого — это ловит забытые случаи при будущем расширении enum).
2. Если есть возможность настроить headless GL context в тестовом окружении CI (проверь,
   доступен ли на используемой платформе, например через LWJGL + EGL/OSMesa) — можешь
   добавить интеграционный тест реального создания/освобождения буфера, но это опционально
   и не блокирует приёмку тикета, если недоступно в среде выполнения.

## Критерии приёмки
1. Файлы в `ev-gpu/src/main/java/dev/ev/gpu/gl/`: `GLRenderBackend.java`,
   `GLBuffer.java`, `GLTexture.java`.
2. `createBuffer`/`createTexture` полностью реализованы согласно требованиям; остальные
   методы интерфейса — явные `UnsupportedOperationException`-заглушки с комментарием, какой
   тикет их реализует.
3. Модуль компилируется. Тест на полноту `TextureFormat`-маппинга проходит.
4. Никакого мутируемого статического состояния в `GLRenderBackend`.
5. Класс `GLRenderBackend` документирован Javadoc, явно указывающим требование "все вызовы
   на render thread".
