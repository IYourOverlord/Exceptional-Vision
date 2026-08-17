package dev.ev.render.framegraph;

import dev.ev.api.gpu.BarrierScope;
import dev.ev.api.gpu.CommandList;
import dev.ev.api.gpu.ComputePipeline;
import dev.ev.api.gpu.GpuBuffer;
import dev.ev.api.gpu.GraphicsPipeline;

import java.util.ArrayList;
import java.util.List;

/**
 * Package-private fake CommandList used by FrameGraphBuilder to record barriers and pass execution order.
 */
class RecordingCommandList implements CommandList {

    final List<String> recordLog = new ArrayList<>();
    int barrierCount = 0;

    @Override
    public void memoryBarrier(BarrierScope scope) {
        barrierCount++;
        recordLog.add("BARRIER:" + scope);
    }

    @Override
    public void uploadToBuffer(GpuBuffer target, long targetOffsetBytes, long sourceAddress, long sizeBytes) {
    }

    @Override
    public void copyBuffer(GpuBuffer src, long srcOffset, GpuBuffer dst, long dstOffset, long sizeBytes) {
    }

    @Override
    public void clearBuffer(GpuBuffer buffer, long offsetBytes, long sizeBytes, int fillValue) {
    }

    @Override
    public void dispatchCompute(ComputePipeline pipeline, int groupsX, int groupsY, int groupsZ) {
    }

    @Override
    public void dispatchComputeIndirect(ComputePipeline pipeline, GpuBuffer indirectBuffer, long offsetBytes) {
    }

    @Override
    public void draw(GraphicsPipeline pipeline, GpuBuffer indirectBuffer, long offsetBytes, int drawCount) {
    }
}
