package dev.ev.api.gpu;

/** A compiled compute shader pipeline with bound resources, ready to be dispatched. */
public interface ComputePipeline {
    void dispatch(int groupsX, int groupsY, int groupsZ);
    void dispatchIndirect(GpuBuffer indirectBuffer, long offsetBytes);
    void bindBuffer(String bindingName, GpuBuffer buffer);
    void bindTexture(String bindingName, GpuTexture texture);
    void free();
}
