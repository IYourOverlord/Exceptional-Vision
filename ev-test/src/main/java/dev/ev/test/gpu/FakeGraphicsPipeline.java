package dev.ev.test.gpu;

import dev.ev.api.gpu.GpuBuffer;
import dev.ev.api.gpu.GpuTexture;
import dev.ev.api.gpu.GraphicsPipeline;
import dev.ev.api.gpu.PipelineLayout;
import dev.ev.api.gpu.ShaderSource;

/**
 * Fake {@link GraphicsPipeline} returned by {@link FakeRenderBackend#compileGraphicsPipeline}.
 *
 * <p>Same scope limitation as {@link FakeComputePipeline}: no real GLSL compilation, no
 * simulated rendering. {@code drawIndirect}/{@code drawIndirectCount}/{@code bindBuffer}/
 * {@code bindTexture} are recorded into the owning {@link FakeRenderBackend}'s
 * {@link FakeRenderBackend#recordedOperationLog()}.
 */
final class FakeGraphicsPipeline implements GraphicsPipeline {

    private final FakeRenderBackend owner;
    private final ShaderSource vertexSource;
    private final ShaderSource fragmentSource;
    private final PipelineLayout layout;
    private boolean freed;

    FakeGraphicsPipeline(FakeRenderBackend owner, ShaderSource vertexSource,
                          ShaderSource fragmentSource, PipelineLayout layout) {
        this.owner = owner;
        this.vertexSource = vertexSource;
        this.fragmentSource = fragmentSource;
        this.layout = layout;
    }

    ShaderSource vertexSource() {
        return vertexSource;
    }

    ShaderSource fragmentSource() {
        return fragmentSource;
    }

    PipelineLayout layout() {
        return layout;
    }

    boolean isFreed() {
        return freed;
    }

    @Override
    public void drawIndirect(GpuBuffer indirectBuffer, long offsetBytes, int drawCount) {
        owner.log("GraphicsPipeline.drawIndirect(offset=" + offsetBytes
                + ", drawCount=" + drawCount + ")");
    }

    @Override
    public void drawIndirectCount(GpuBuffer indirectBuffer, long offsetBytes,
                                   GpuBuffer countBuffer, long countOffsetBytes, int maxDrawCount) {
        owner.log("GraphicsPipeline.drawIndirectCount(offset=" + offsetBytes
                + ", maxDrawCount=" + maxDrawCount + ")");
    }

    @Override
    public void bindBuffer(String bindingName, GpuBuffer buffer) {
        requireKnownBinding(bindingName);
        owner.log("GraphicsPipeline.bindBuffer(" + bindingName + ")");
    }

    @Override
    public void bindTexture(String bindingName, GpuTexture texture) {
        requireKnownBinding(bindingName);
        owner.log("GraphicsPipeline.bindTexture(" + bindingName + ")");
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
