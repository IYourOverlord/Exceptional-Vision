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
