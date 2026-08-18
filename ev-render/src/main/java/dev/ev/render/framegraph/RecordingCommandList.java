package dev.ev.render.framegraph;

import dev.ev.api.gpu.BarrierScope;
import dev.ev.api.gpu.CommandList;
import dev.ev.api.gpu.ComputePipeline;
import dev.ev.api.gpu.GpuBuffer;
import dev.ev.api.gpu.GraphicsPipeline;

import java.util.ArrayList;
import java.util.List;

/**
 * Package-private {@link CommandList} used by {@link FrameGraphBuilder} to record pass
 * execution order/barriers for testing, and — starting with ticket 31's integration work
 * — to actually forward every operation to a real backend-provided {@link CommandList}
 * so a frame produced through {@code FrameGraphBuilder} actually does something on the
 * GPU, not just bookkeeping.
 *
 * <h2>Why this class exists instead of using the backend's CommandList directly (ticket
 * 31 integration note)</h2>
 * {@link dev.ev.api.gpu.RenderBackend} (ticket 04) has no method to obtain a
 * {@code CommandList} instance — {@link dev.ev.api.gpu.RenderBackend#submit} only
 * accepts one, it does not vend one. Every concrete backend in this project has had to
 * work around that same gap with its own backend-specific addition:
 * {@code GLRenderBackend.newCommandList()} on the real GL backend (ticket 31) and
 * {@code FakeRenderBackend.newCommandList()} on the in-memory test backend (ticket 30).
 * {@code FrameGraphBuilder} needs a {@code CommandList} to hand to
 * {@link FramePass#record} <i>before</i> it knows which concrete backend it's talking
 * to (it only holds the {@code RenderBackend} interface type) — so this class exists as
 * a thin pass-through: it forwards every call to an optional {@code delegate}
 * {@code CommandList} obtained from the backend via reflection-free duck typing (see
 * {@link FrameGraphBuilder#obtainDelegateCommandList}), while always recording into
 * {@link #recordLog}/{@link #barrierCount} regardless of whether a delegate is present,
 * so existing barrier/ordering tests (which pass no delegate) keep working unchanged.
 *
 * <p>{@code delegate == null} (the historical, pre-ticket-31 behavior, still exercised by
 * {@code FrameGraphBuilderTest}) means every operation is recorded only, with no actual
 * GPU/backend side effect — appropriate for a {@link dev.ev.api.gpu.RenderBackend} test
 * double that doesn't expose a real command-execution path (e.g. this class's own unit
 * tests' {@code TestRenderBackend}). {@code delegate != null} (the real integrated path,
 * used by {@code EVInstance.renderFarLod} against a real {@code GLRenderBackend}) means
 * every operation is both recorded AND forwarded to the delegate, which is where the
 * actual OpenGL calls happen (via {@code GLCommandList}).
 */
class RecordingCommandList implements CommandList {

    final List<String> recordLog = new ArrayList<>();
    int barrierCount = 0;

    private final CommandList delegate;

    /** No delegate — pure recorder, matches this class's original (pre-ticket-31) behavior. */
    RecordingCommandList() {
        this(null);
    }

    /**
     * @param delegate if non-null, every operation is forwarded to it (in addition to
     *                 being recorded into {@link #recordLog}) — see class Javadoc.
     */
    RecordingCommandList(CommandList delegate) {
        this.delegate = delegate;
    }

    @Override
    public void memoryBarrier(BarrierScope scope) {
        barrierCount++;
        recordLog.add("BARRIER:" + scope);
        if (delegate != null) {
            delegate.memoryBarrier(scope);
        }
    }

    @Override
    public void uploadToBuffer(GpuBuffer target, long targetOffsetBytes, long sourceAddress, long sizeBytes) {
        if (delegate != null) {
            delegate.uploadToBuffer(target, targetOffsetBytes, sourceAddress, sizeBytes);
        }
    }

    @Override
    public void copyBuffer(GpuBuffer src, long srcOffset, GpuBuffer dst, long dstOffset, long sizeBytes) {
        if (delegate != null) {
            delegate.copyBuffer(src, srcOffset, dst, dstOffset, sizeBytes);
        }
    }

    @Override
    public void clearBuffer(GpuBuffer buffer, long offsetBytes, long sizeBytes, int fillValue) {
        if (delegate != null) {
            delegate.clearBuffer(buffer, offsetBytes, sizeBytes, fillValue);
        }
    }

    @Override
    public void dispatchCompute(ComputePipeline pipeline, int groupsX, int groupsY, int groupsZ) {
        if (delegate != null) {
            delegate.dispatchCompute(pipeline, groupsX, groupsY, groupsZ);
        }
    }

    @Override
    public void dispatchComputeIndirect(ComputePipeline pipeline, GpuBuffer indirectBuffer, long offsetBytes) {
        if (delegate != null) {
            delegate.dispatchComputeIndirect(pipeline, indirectBuffer, offsetBytes);
        }
    }

    @Override
    public void draw(GraphicsPipeline pipeline, GpuBuffer indirectBuffer, long offsetBytes, int drawCount) {
        if (delegate != null) {
            delegate.draw(pipeline, indirectBuffer, offsetBytes, drawCount);
        }
    }
}
