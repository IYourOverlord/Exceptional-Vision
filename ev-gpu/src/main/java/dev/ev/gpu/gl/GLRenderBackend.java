package dev.ev.gpu.gl;

import dev.ev.api.gpu.BufferUsage;
import dev.ev.api.gpu.CommandList;
import dev.ev.api.gpu.ComputePipeline;
import dev.ev.api.gpu.FenceHandle;
import dev.ev.api.gpu.GpuBuffer;
import dev.ev.api.gpu.GpuTexture;
import dev.ev.api.gpu.GraphicsPipeline;
import dev.ev.api.gpu.PipelineLayout;
import dev.ev.api.gpu.RenderBackend;
import dev.ev.api.gpu.ShaderSource;
import dev.ev.api.gpu.TextureDesc;

import org.lwjgl.opengl.GL45;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * {@link RenderBackend} implementation on top of OpenGL 4.5+ via LWJGL. This is the
 * <b>only</b> class in the entire EV project (together with {@link GLBuffer} and {@link
 * GLTexture} in this same package) permitted to import {@code org.lwjgl.*} — everything above
 * {@code ev-gpu} goes through the {@link RenderBackend} abstraction instead.
 *
 * <p><b>Render-thread requirement:</b> every method on this class, and on every {@link
 * GpuBuffer}/{@link GpuTexture} instance it creates, MUST be called from the render thread —
 * i.e. the single thread that holds the current OpenGL context. OpenGL contexts are not
 * thread-safe to use concurrently from multiple threads, and this class does not attempt to
 * make them so. This ticket does not add runtime assertions enforcing that (a future ticket
 * may), but callers must never route GL work through any other thread (e.g. a worker thread
 * from {@code ev-meshing}'s {@code MeshTaskQueue}) — doing so is undefined behavior at the
 * driver level, not merely a logic bug caught by an exception here.
 *
 * <p><b>Instance-scoped state only:</b> this class holds no mutable {@code static} fields (no
 * static GL handles, no static scratch buffers) — every piece of state needed lives on the
 * instance. This is a direct architectural fix for a problem documented in the original
 * prototype analysis (Voxy): a static scratch buffer there broke support for multiple worlds
 * or GL-context recreation, since static state outlives any single world/context's lifetime.
 *
 * <p><b>Scope so far:</b> {@link #createBuffer}, {@link #createTexture} (ticket 17), and {@link
 * #compilePipeline}/{@link #compileGraphicsPipeline} (ticket 18, GLSL compilation via {@link
 * ShaderCompiler}) are fully implemented. {@link #submit}, {@link #insertFence}, {@link
 * #isSignaled}, {@link #waitForFence}, and {@link #shutdown} remain stubs — see each method's
 * Javadoc for which later ticket implements it. This same class is extended in place by those
 * later tickets, not replaced by a competing class.
 */
public final class GLRenderBackend implements RenderBackend {

    @Override
    public GpuBuffer createBuffer(long sizeBytes, BufferUsage usage) {
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be > 0, got " + sizeBytes);
        }

        int handle = GL45.glCreateBuffers();

        return switch (usage) {
            case STATIC_DRAW, DYNAMIC_DRAW, STORAGE -> {
                // GL_DYNAMIC_STORAGE_BIT allows later glNamedBufferSubData CPU writes.
                // STATIC_DRAW/DYNAMIC_DRAW are both CPU-writable-over-time in this API
                // (the distinction is about write frequency, not whether writes happen at
                // all), so both need the flag. STORAGE buffers are compute-shader-owned and
                // rarely touched by the CPU per BufferUsage's Javadoc, but "rarely" is not
                // "never" (e.g. one-time initial upload of a node buffer) — keep the flag set
                // for STORAGE too, so an occasional CPU-side glNamedBufferSubData remains legal;
                // this costs nothing on drivers when the buffer is in fact never CPU-written.
                GL45.glNamedBufferStorage(handle, sizeBytes, GL45.GL_DYNAMIC_STORAGE_BIT);
                yield new GLBuffer(handle, sizeBytes, usage, 0L);
            }
            case STAGING_UPLOAD -> {
                int flags = GL45.GL_MAP_WRITE_BIT | GL45.GL_MAP_PERSISTENT_BIT | GL45.GL_MAP_COHERENT_BIT;
                GL45.glNamedBufferStorage(handle, sizeBytes, flags);
                ByteBuffer mapped = GL45.glMapNamedBufferRange(handle, 0L, sizeBytes, flags);
                yield new GLBuffer(handle, sizeBytes, usage, MemoryUtil.memAddress(mapped));
            }
            case STAGING_DOWNLOAD -> {
                int flags = GL45.GL_MAP_READ_BIT | GL45.GL_MAP_PERSISTENT_BIT | GL45.GL_MAP_COHERENT_BIT;
                GL45.glNamedBufferStorage(handle, sizeBytes, flags);
                ByteBuffer mapped = GL45.glMapNamedBufferRange(handle, 0L, sizeBytes, flags);
                yield new GLBuffer(handle, sizeBytes, usage, MemoryUtil.memAddress(mapped));
            }
        };
    }

    @Override
    public GpuTexture createTexture(TextureDesc desc) {
        if (desc.mipLevels() < 1) {
            throw new IllegalArgumentException("mipLevels must be >= 1, got " + desc.mipLevels());
        }

        int handle;
        int internalFormat = GLTextureFormats.toGlInternalFormat(desc.format());

        if (desc.depth() > 1) {
            handle = GL45.glCreateTextures(GL45.GL_TEXTURE_3D);
            GL45.glTextureStorage3D(handle, desc.mipLevels(), internalFormat,
                desc.width(), desc.height(), desc.depth());
        } else {
            handle = GL45.glCreateTextures(GL45.GL_TEXTURE_2D);
            GL45.glTextureStorage2D(handle, desc.mipLevels(), internalFormat,
                desc.width(), desc.height());
        }

        return new GLTexture(handle, desc);
    }

    @Override
    public ComputePipeline compilePipeline(ShaderSource source, PipelineLayout layout) {
        int programHandle = ShaderCompiler.compileComputeProgram(source);
        return new GLComputePipeline(programHandle, layout);
    }

    @Override
    public GraphicsPipeline compileGraphicsPipeline(ShaderSource vertexSrc, ShaderSource fragmentSrc, PipelineLayout layout) {
        int programHandle = ShaderCompiler.compileGraphicsProgram(vertexSrc, fragmentSrc);
        return new GLGraphicsPipeline(programHandle, layout);
    }

    /** Implemented in a later ticket (command list recording/execution, tickets 19-23). */
    @Override
    public void submit(CommandList commands) {
        throw new UnsupportedOperationException("implemented in a later ticket (19-23, command submission)");
    }

    /** Implemented in a later ticket (fence/sync handling, tickets 19-23). */
    @Override
    public FenceHandle insertFence() {
        throw new UnsupportedOperationException("implemented in a later ticket (19-23, fence handling)");
    }

    /** Implemented in a later ticket (fence/sync handling, tickets 19-23). */
    @Override
    public boolean isSignaled(FenceHandle fence) {
        throw new UnsupportedOperationException("implemented in a later ticket (19-23, fence handling)");
    }

    /** Implemented in a later ticket (fence/sync handling, tickets 19-23). */
    @Override
    public boolean waitForFence(FenceHandle fence, long timeoutNanos) {
        throw new UnsupportedOperationException("implemented in a later ticket (19-23, fence handling)");
    }

    /** Implemented in a later ticket (full resource lifecycle/teardown, tickets 19-23). */
    @Override
    public void shutdown() {
        throw new UnsupportedOperationException("implemented in a later ticket (19-23, resource teardown)");
    }
}
