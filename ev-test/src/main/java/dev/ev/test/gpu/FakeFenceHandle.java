package dev.ev.test.gpu;

import dev.ev.api.gpu.FenceHandle;

/**
 * Opaque, monotonically increasing fence handle used by {@link FakeRenderBackend}.
 *
 * <p><b>Fence semantics (ticket 30, requirement 4) — read before relying on this in a
 * test:</b> {@link FakeRenderBackend} executes all submitted work synchronously inside
 * {@link FakeRenderBackend#submit}, so there is no real asynchronous GPU execution to
 * wait for. As a consequence, every {@code FakeFenceHandle} is considered signaled
 * immediately: {@link FakeRenderBackend#isSignaled} always returns {@code true} for any
 * fence created by the same backend, and {@link FakeRenderBackend#waitForFence} returns
 * {@code true} immediately without blocking.
 *
 * <p><b>This is not a harmless simplification.</b> It makes {@link FakeRenderBackend}
 * unsuitable for testing any logic that depends on a fence <i>not</i> being signaled
 * yet — backpressure, protecting a ring-buffer region from reuse before the GPU has
 * finished reading it, and similar patterns. See {@code 19-gpu-upload-batching-opt.md}
 * for a concrete example of code that must not be tested against this fake for exactly
 * this reason; that ticket instead tests its fence-waiting backpressure logic against a
 * pure function taking fence status as an explicit parameter, not against a real or fake
 * {@code RenderBackend}. Follow the same pattern for any other logic that relies on
 * deferred fence signaling.
 */
final class FakeFenceHandle implements FenceHandle {

    private final long sequence;

    FakeFenceHandle(long sequence) {
        this.sequence = sequence;
    }

    long sequence() {
        return sequence;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof FakeFenceHandle other && other.sequence == sequence;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(sequence);
    }

    @Override
    public String toString() {
        return "FakeFenceHandle[" + sequence + "]";
    }
}
