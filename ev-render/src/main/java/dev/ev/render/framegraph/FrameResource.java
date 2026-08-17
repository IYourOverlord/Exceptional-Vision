package dev.ev.render.framegraph;

import java.util.Objects;

/**
 * Opaque handle identifying a logical resource (buffer, texture) that flows
 * between frame graph passes. Two passes that reference the same FrameResource
 * instance (one via writes(), another via reads()) establish an ordering
 * dependency resolved by FrameGraphBuilder.
 */
public final class FrameResource {

    private final String debugName;

    /**
     * Constructs a FrameResource with a human-readable debug name.
     *
     * @param debugName human-readable identifier for debugging, non-null
     */
    public FrameResource(String debugName) {
        this.debugName = Objects.requireNonNull(debugName, "debugName cannot be null");
    }

    /**
     * Returns the human-readable debug name of this resource.
     *
     * @return debug name
     */
    public String debugName() {
        return debugName;
    }

    @Override
    public String toString() {
        return "FrameResource{" + debugName + '}';
    }
}
