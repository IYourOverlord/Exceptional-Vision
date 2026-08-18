package dev.ev.gpu.gl;

import dev.ev.api.gpu.BufferUsage;
import dev.ev.api.gpu.CommandList;
import dev.ev.api.gpu.CommandListFactory;
import dev.ev.api.gpu.ComputePipeline;
import dev.ev.api.gpu.FenceHandle;
import dev.ev.api.gpu.GpuBuffer;
import dev.ev.api.gpu.GpuTexture;
import dev.ev.api.gpu.GraphicsPipeline;
import dev.ev.api.gpu.PipelineLayout;
import dev.ev.api.gpu.RenderBackend;
import dev.ev.api.gpu.ShaderSource;
import dev.ev.api.gpu.TextureDesc;

import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL45;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

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
 * <p><b>Scope so far:</b> {@link #createBuffer}, {@link #createTexture} (ticket 17), {@link
 * #compilePipeline}/{@link #compileGraphicsPipeline} (ticket 18, GLSL compilation via {@link
 * ShaderCompiler}), and {@link #submit}/{@link #insertFence}/{@link #isSignaled}/{@link
 * #waitForFence}/{@link #shutdown} (ticket 31, see each method's Javadoc — MVP-scoped,
 * synchronous, no staging-ring-buffer batching, see {@link GLCommandList}'s class Javadoc
 * for why this is the correct scope for Wave 1) are all implemented.
 */
public final class GLRenderBackend implements RenderBackend, CommandListFactory {

    /** Every GLSync handle created by {@link #insertFence()}, tracked for {@link #shutdown()}. */
    private final Set<Long> outstandingFences = Collections.synchronizedSet(new java.util.HashSet<>());
    private final AtomicLong fenceCount = new AtomicLong(0);
    private boolean shutDown;

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

    /**
     * Test/integration-only addition (not part of the {@link RenderBackend} contract —
     * see {@link CommandListFactory}'s Javadoc for why this exists and how callers that
     * only hold a {@link RenderBackend} reference, like {@code FrameGraphBuilder}, use it
     * without depending on this concrete class). Returns a fresh {@link GLCommandList}
     * — every call on it executes immediately as a real GL call (see that class's
     * Javadoc), so by the time this method's caller passes the result to {@link #submit},
     * the actual GPU work has already happened.
     */
    @Override
    public CommandList newCommandList() {
        return new GLCommandList();
    }

    /**
     * Accepts a submitted {@link CommandList}. Ticket 31 scope note: this backend's
     * {@link GLCommandList} executes every operation immediately as it is recorded (see
     * that class's Javadoc for why — no staging/batching in MVP), so by the time any
     * {@code CommandList} reaches this method, the GL work it represents has either
     * already happened (if it's a {@link GLCommandList}, or a
     * {@code dev.ev.render.framegraph.RecordingCommandList} wrapping one via
     * {@link CommandListFactory}) or — for any other {@code CommandList} implementation
     * this backend doesn't recognize — cannot be executed at all, since this backend has
     * no way to introspect an arbitrary implementation's recorded operations (nothing in
     * the {@link CommandList} contract exposes them for replay). This method therefore
     * does not attempt to execute unrecognized command lists; it accepts them as a no-op,
     * on the assumption (true for every actual caller in this codebase — see
     * {@code FrameGraphBuilder}) that real work was already dispatched through
     * {@link #newCommandList()}'s immediate-execution model, not deferred to this call.
     */
    @Override
    public void submit(CommandList commands) {
        // Intentionally a no-op beyond accepting the call — see Javadoc above.
    }

    /**
     * Inserts a GL fence sync object ({@code glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0)})
     * into the current command stream and wraps its native handle in a {@link GLFenceHandle}.
     */
    @Override
    public FenceHandle insertFence() {
        long syncHandle = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        if (syncHandle == 0L) {
            throw new IllegalStateException("glFenceSync failed (returned 0)");
        }
        outstandingFences.add(syncHandle);
        fenceCount.incrementAndGet();
        return new GLFenceHandle(syncHandle);
    }

    /**
     * Non-blocking poll: {@code glClientWaitSync} with a zero timeout. Per the GL spec,
     * {@code GL_ALREADY_SIGNALED} and {@code GL_CONDITION_SATISFIED} both mean the fence
     * has been reached; {@code GL_TIMEOUT_EXPIRED} (expected with a zero timeout on an
     * unsignaled fence) and {@code GL_WAIT_FAILED} both mean "not yet"/"error", treated
     * identically here since {@link RenderBackend#isSignaled} has no way to report a
     * separate error state.
     */
    @Override
    public boolean isSignaled(FenceHandle fence) {
        long syncHandle = requireGlFence(fence);
        int result = GL32.glClientWaitSync(syncHandle, 0, 0L);
        return result == GL32.GL_ALREADY_SIGNALED || result == GL32.GL_CONDITION_SATISFIED;
    }

    /**
     * Blocks up to {@code timeoutNanos} via {@code glClientWaitSync}'s own timeout
     * parameter (which is already nanoseconds, per the GL spec — no unit conversion
     * needed), with {@code GL_SYNC_FLUSH_COMMANDS_BIT} set so a fence on a command
     * stream that hasn't been flushed yet doesn't spuriously time out waiting for work
     * the driver hasn't even been told to start.
     */
    @Override
    public boolean waitForFence(FenceHandle fence, long timeoutNanos) {
        long syncHandle = requireGlFence(fence);
        int result = GL32.glClientWaitSync(syncHandle, GL32.GL_SYNC_FLUSH_COMMANDS_BIT, timeoutNanos);
        return result == GL32.GL_ALREADY_SIGNALED || result == GL32.GL_CONDITION_SATISFIED;
    }

    private static long requireGlFence(FenceHandle fence) {
        if (!(fence instanceof GLFenceHandle glFence)) {
            throw new IllegalArgumentException(
                    "GLRenderBackend only recognizes GLFenceHandle instances created by its own "
                            + "insertFence(), got " + fence.getClass());
        }
        return glFence.syncHandle();
    }

    /**
     * Releases every outstanding fence sync object created by this backend via
     * {@link #insertFence()} and not yet individually deleted. Buffers/textures/pipelines
     * are NOT tracked or freed here — per {@link GpuBuffer}/{@link GpuTexture}/
     * {@link ComputePipeline}/{@link GraphicsPipeline}'s own contracts, each is
     * individually owned and freed by its creator via its own {@code free()}, this
     * backend does not maintain a registry of every handle it ever created (unlike the
     * in-memory {@code FakeRenderBackend}, ticket 30, whose {@code activeBuffers()}/
     * {@code activeTextures()} exist specifically to support leak-detection assertions in
     * tests — that bookkeeping has no equivalent need here against a real GL context,
     * which the driver tears down wholesale on context loss regardless). Idempotent:
     * safe to call more than once, matching {@code FakeRenderBackend.shutdown()}'s
     * documented idempotency for the same reason (see that class's Javadoc) — a second
     * call finds {@link #outstandingFences} already empty and does nothing.
     */
    @Override
    public void shutdown() {
        if (shutDown) {
            return;
        }
        shutDown = true;
        synchronized (outstandingFences) {
            for (Long syncHandle : outstandingFences) {
                GL32.glDeleteSync(syncHandle);
            }
            outstandingFences.clear();
        }
    }
}
