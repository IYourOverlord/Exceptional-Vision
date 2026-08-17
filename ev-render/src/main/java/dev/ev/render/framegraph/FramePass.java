package dev.ev.render.framegraph;

import dev.ev.api.gpu.CommandList;

/**
 * A single unit of GPU work within a frame, e.g. one compute dispatch or one draw call batch.
 */
public interface FramePass {

    /**
     * Records this pass's GPU commands. Called by FrameGraphBuilder.execute() in dependency order.
     *
     * @param commands command list to record GPU commands into, non-null
     */
    void record(CommandList commands);

    /**
     * Short identifier used for metrics (see MetricsRegistry.recordGpuPassDuration) and debug labeling.
     *
     * @return pass name
     */
    String name();
}
