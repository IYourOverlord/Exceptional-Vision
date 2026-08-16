package dev.ev.api.gpu;

/** A compiled graphics (vertex+fragment) pipeline with bound resources, ready to be drawn. */
public interface GraphicsPipeline {
    void drawIndirect(GpuBuffer indirectBuffer, long offsetBytes, int drawCount);
    void drawIndirectCount(GpuBuffer indirectBuffer, long offsetBytes, GpuBuffer countBuffer, long countOffsetBytes, int maxDrawCount);
    void bindBuffer(String bindingName, GpuBuffer buffer);
    void bindTexture(String bindingName, GpuTexture texture);
    void free();
}
