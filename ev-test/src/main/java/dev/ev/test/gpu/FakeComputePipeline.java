package dev.ev.test.gpu;

import dev.ev.api.gpu.ComputePipeline;
import dev.ev.api.gpu.GpuBuffer;
import dev.ev.api.gpu.GpuTexture;
import dev.ev.api.gpu.PipelineLayout;
import dev.ev.api.gpu.ShaderSource;

/**
 * Fake {@link ComputePipeline} returned by {@link FakeRenderBackend#compilePipeline}.
 *
 * <p>No real GLSL compilation happens (there is no GL context to compile against) — the
 * {@link ShaderSource} and {@link PipelineLayout} it was created with are stored as-is
 * for inspection, and its {@code dispatch}/{@code bindBuffer}/etc. methods simply record
 * the call into the owning {@link FakeRenderBackend}'s
 * {@link FakeRenderBackend#recordedOperationLog()}. Note that calling this pipeline's own
 * {@code dispatch(...)} directly (as opposed to going through
 * {@link dev.ev.api.gpu.CommandList#dispatchCompute}) is a separate code path from
 * {@link FakeCommandList}'s recording — both funnel into the same operation log, but
 * only {@code CommandList}-recorded operations are ordered relative to buffer copy/upload
 * operations from the same submit() call. Calling this pipeline's methods directly
 * outside of a {@code CommandList}/{@code submit()} cycle logs immediately.
 */
final class FakeComputePipeline implements ComputePipeline {

    private final FakeRenderBackend owner;
    private final ShaderSource source;
    private final PipelineLayout layout;
    private boolean freed;

    FakeComputePipeline(FakeRenderBackend owner, ShaderSource source, PipelineLayout layout) {
        this.owner = owner;
        this.source = source;
        this.layout = layout;
    }

    ShaderSource source() {
        return source;
    }

    PipelineLayout layout() {
        return layout;
    }

    boolean isFreed() {
        return freed;
    }

    @Override
    public void dispatch(int groupsX, int groupsY, int groupsZ) {
        owner.log("ComputePipeline.dispatch(" + groupsX + "," + groupsY + "," + groupsZ + ")");
    }

    @Override
    public void dispatchIndirect(GpuBuffer indirectBuffer, long offsetBytes) {
        owner.log("ComputePipeline.dispatchIndirect(offset=" + offsetBytes + ")");
    }

    @Override
    public void bindBuffer(String bindingName, GpuBuffer buffer) {
        requireKnownBinding(bindingName);
        owner.log("ComputePipeline.bindBuffer(" + bindingName + ")");
    }

    @Override
    public void bindTexture(String bindingName, GpuTexture texture) {
        requireKnownBinding(bindingName);
        owner.log("ComputePipeline.bindTexture(" + bindingName + ")");
    }

    private void requireKnownBinding(String bindingName) {
        if (!layout.bindingsByName().containsKey(bindingName)) {
            throw new IllegalArgumentException(
                    "Unknown binding name \"" + bindingName + "\" for this pipeline's "
                            + "PipelineLayout, known bindings: " + layout.bindingsByName().keySet());
        }
    }

    @Override
    public void free() {
        freed = true;
    }
}
