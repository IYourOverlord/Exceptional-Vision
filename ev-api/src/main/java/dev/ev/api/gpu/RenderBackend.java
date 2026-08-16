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
