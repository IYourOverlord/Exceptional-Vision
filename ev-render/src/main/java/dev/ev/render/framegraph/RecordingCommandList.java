package dev.ev.render.framegraph;

import dev.ev.api.gpu.BarrierScope;
import dev.ev.api.gpu.CommandList;
import dev.ev.api.gpu.GpuBuffer;
import dev.ev.api.gpu.GpuTexture;

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
    public void uploadToBuffer(GpuBuffer destination, long destOffsetBytes, long srcDataAddress, long lengthBytes) {
    }

    @Override
    public void copyBuffer(GpuBuffer source, GpuBuffer destination, long sourceOffset, long destOffset, long lengthBytes) {
    }

    @Override
    public void clearBuffer(GpuBuffer buffer, long offsetBytes, long lengthBytes, int fillValue) {
    }

    @Override
    public void dispatchCompute(int numGroupsX, int numGroupsY, int numGroupsZ) {
    }

    @Override
    public void dispatchComputeIndirect(GpuBuffer indirectBuffer, long offsetBytes) {
    }

    @Override
    public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
    }
}
