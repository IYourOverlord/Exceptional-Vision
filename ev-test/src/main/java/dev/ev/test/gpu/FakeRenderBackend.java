package dev.ev.test.gpu;

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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fully in-memory implementation of {@link RenderBackend}, for unit-testing domain logic
 * ({@code ev-render}, parts of {@code ev-meshing}/{@code ev-storage} that interact with
 * the GPU abstraction) without a real GPU context. Compute/graphics "dispatch" is
 * simulated by <b>not</b> actually executing any shader logic — GLSL source passed to
 * {@link #compilePipeline}/{@link #compileGraphicsPipeline} is stored but never
 * interpreted. This backend validates the <b>calling code's</b> usage of the
 * {@link RenderBackend} contract (correct buffer creation, correct binding names,
 * correct submit/fence sequencing, correct barrier placement) but <b>cannot validate
 * GLSL shader correctness itself</b> — that requires a real GPU (see tickets 21-23's
 * integration-test notes). Do not mistake this fake for a full GPU simulator.
 *
 * <h2>Fence semantics — read before use</h2>
 * All work submitted via {@link #submit} executes synchronously, so every fence
 * returned by {@link #insertFence()} is considered signaled immediately:
 * {@link #isSignaled} always returns {@code true}, {@link #waitForFence} returns
 * {@code true} without blocking. <b>This makes this class unsuitable for testing any
 * logic that depends on a fence not being signaled yet</b> (backpressure, ring-buffer
 * reuse protection pending GPU completion, and similar patterns) — see
 * {@code 19-gpu-upload-batching-opt.md} for a concrete example of code that must be
 * tested against a plain function instead. Full detail in {@link FakeFenceHandle}'s
 * Javadoc.
 *
 * <h2>{@code mappedAddress()} for STAGING_UPLOAD buffers</h2>
 * See {@link FakeGpuBuffer}'s Javadoc: {@code STAGING_UPLOAD} buffers get a real
 * off-heap allocation via LWJGL's {@code MemoryUtil}, kept separate from (and not
 * synchronized with) the {@code byte[]} this class uses for
 * {@link #readBufferContents}/{@link #writeBufferContents} and for
 * {@link FakeCommandList}'s copy/upload/clear execution.
 *
 * <h2>Instance-scoped, no shared state</h2>
 * All state (buffers, textures, fence counter, operation log) lives on the instance.
 * Each test should create a fresh {@code FakeRenderBackend}; instances are not shared or
 * reused across tests.
 */
public final class FakeRenderBackend implements RenderBackend {

    private final List<GpuBuffer> activeBuffers = Collections.synchronizedList(new ArrayList<>());
    private final List<GpuTexture> activeTextures = Collections.synchronizedList(new ArrayList<>());
    private final List<String> operationLog = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong fenceCounter = new AtomicLong(0);
    private boolean shutDown;

    @Override
    public GpuBuffer createBuffer(long sizeBytes, BufferUsage usage) {
        FakeGpuBuffer buffer = new FakeGpuBuffer(sizeBytes, usage);
        activeBuffers.add(buffer);
        return buffer;
    }

    @Override
    public GpuTexture createTexture(TextureDesc desc) {
        FakeGpuTexture texture = new FakeGpuTexture(desc);
        activeTextures.add(texture);
        return texture;
    }

    @Override
    public ComputePipeline compilePipeline(ShaderSource source, PipelineLayout layout) {
        return new FakeComputePipeline(this, source, layout);
    }

    @Override
    public GraphicsPipeline compileGraphicsPipeline(ShaderSource vertexSrc, ShaderSource fragmentSrc,
                                                      PipelineLayout layout) {
        return new FakeGraphicsPipeline(this, vertexSrc, fragmentSrc, layout);
    }

    /**
     * Test-only addition, not part of the {@link RenderBackend} contract (ticket 04 does
     * not expose a way to construct a {@link CommandList} — callers of the real backend
     * are expected to obtain one some other way not yet specified by that ticket). This
     * method is how test code records operations before calling {@link #submit}.
     */
    public CommandList newCommandList() {
        return new FakeCommandList();
    }

    @Override
    public void submit(CommandList commands) {
        if (!(commands instanceof FakeCommandList fakeCommands)) {
            throw new IllegalArgumentException(
                    "FakeRenderBackend.submit() only accepts CommandList instances created by "
                            + "this backend's own newCommandList(), got " + commands.getClass()
                            + ". Real GL-backed CommandLists cannot be executed by this fake.");
        }
        fakeCommands.executeAgainst(operationLog);
    }

    @Override
    public FenceHandle insertFence() {
        return new FakeFenceHandle(fenceCounter.getAndIncrement());
    }

    @Override
    public boolean isSignaled(FenceHandle fence) {
        requireFakeFence(fence);
        return true;
    }

    @Override
    public boolean waitForFence(FenceHandle fence, long timeoutNanos) {
        requireFakeFence(fence);
        return true;
    }

    private void requireFakeFence(FenceHandle fence) {
        if (!(fence instanceof FakeFenceHandle)) {
            throw new IllegalArgumentException(
                    "FakeRenderBackend only recognizes FakeFenceHandle instances created by its "
                            + "own insertFence(), got " + fence.getClass());
        }
    }

    /**
     * Releases backend resources. Idempotent: calling this more than once has no
     * additional effect and does not throw — a fake with no real GPU handles to release
     * has nothing that can meaningfully fail on a second call, and requiring callers to
     * track whether they already called shutdown() would add friction with no test
     * value. This differs from {@link GpuBuffer#free()}/{@link GpuTexture#free()}, which
     * are also idempotent but for a different reason (avoiding double-free bugs in
     * calling code under test).
     */
    @Override
    public void shutdown() {
        shutDown = true;
    }

    public boolean isShutDown() {
        return shutDown;
    }

    // ---- Test-inspection API (not part of the RenderBackend contract) ----

    /** All buffers currently allocated (not yet {@code free()}'d), for leak-detection assertions. */
    public List<GpuBuffer> activeBuffers() {
        synchronized (activeBuffers) {
            return activeBuffers.stream().filter(b -> !((FakeGpuBuffer) b).isFreed()).toList();
        }
    }

    /** All textures currently allocated (not yet {@code free()}'d). */
    public List<GpuTexture> activeTextures() {
        synchronized (activeTextures) {
            return activeTextures.stream().filter(t -> !((FakeGpuTexture) t).isFreed()).toList();
        }
    }

    /**
     * Raw byte contents of a buffer created by this backend, for test assertions on
     * uploaded/copied data. For {@code STAGING_UPLOAD} buffers, this reflects only bytes
     * written via {@link #writeBufferContents}/{@link FakeCommandList}'s
     * {@code uploadToBuffer}/{@code copyBuffer}/{@code clearBuffer} execution — it does
     * NOT reflect writes made directly through the native pointer returned by
     * {@link GpuBuffer#mappedAddress()}; see {@link FakeGpuBuffer}'s Javadoc.
     */
    public byte[] readBufferContents(GpuBuffer buffer) {
        return requireOwned(buffer).backingStore().clone();
    }

    /**
     * Directly writes bytes into a buffer's backing store, bypassing any upload
     * mechanism, for test setup convenience (e.g. seeding a "GPU" buffer with known data
     * before running domain logic that reads it). {@code data.length} must not exceed
     * the buffer's remaining capacity from offset 0; this writes starting at offset 0.
     */
    public void writeBufferContents(GpuBuffer buffer, byte[] data) {
        byte[] store = requireOwned(buffer).backingStore();
        if (data.length > store.length) {
            throw new IllegalArgumentException(
                    "data.length (" + data.length + ") exceeds buffer size (" + store.length + ")");
        }
        System.arraycopy(data, 0, store, 0, data.length);
    }

    /**
     * Chronological log of recorded {@link CommandList} operations across all
     * {@link #submit(CommandList)} calls (plus any direct pipeline dispatch/bind calls
     * made outside of a {@code CommandList}, see {@link FakeComputePipeline}), for
     * sequencing assertions. Returns a live, unmodifiable snapshot at call time.
     */
    public List<String> recordedOperationLog() {
        synchronized (operationLog) {
            return List.copyOf(operationLog);
        }
    }

    /** Package-private: used by {@link FakeComputePipeline}/{@link FakeGraphicsPipeline}. */
    void log(String entry) {
        operationLog.add(entry);
    }

    private FakeGpuBuffer requireOwned(GpuBuffer buffer) {
        if (!(buffer instanceof FakeGpuBuffer fake)) {
            throw new IllegalArgumentException(
                    "Not a buffer created by FakeRenderBackend: " + buffer.getClass());
        }
        return fake;
    }
}
