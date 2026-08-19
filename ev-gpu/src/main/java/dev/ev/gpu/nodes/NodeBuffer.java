package dev.ev.gpu.nodes;

import dev.ev.api.gpu.BufferUsage;
import dev.ev.api.gpu.GpuBuffer;
import dev.ev.api.gpu.RenderBackend;
import java.util.Objects;

/**
 * MVP implementation: manages a single GPU-resident Array-of-Structures buffer
 * for section node data. Each node occupies {@link #BYTES_PER_NODE} (32) contiguous bytes:
 * <ul>
 *   <li>{@code vec4 bounds} (16 bytes: xyz = center, w = radius)</li>
 *   <li>{@code uint flags} (4 bytes)</li>
 *   <li>{@code uint materialRef} (4 bytes)</li>
 *   <li>{@code uint streamState} (4 bytes)</li>
 *   <li>{@code uint _padding} (4 bytes, for 16-byte alignment of the whole struct, required
 *       by std430 layout rules for arrays of structs containing a vec4)</li>
 * </ul>
 *
 * Total: 32 bytes per node.
 * <p>
 * See {@code MVP_INDEX.md} for why this simple single-buffer version comes first —
 * {@code 20-gpu-node-buffer-soa-opt.md} replaces this only if profiling shows this
 * layout's memory bandwidth characteristics are actually a bottleneck during
 * traversal.
 * <p>
 * NOTE: this MVP version does NOT include a separate childMask field used by
 * hierarchical GPU traversal (ticket 21-opt) — since {@code 21-gpu-simple-traversal-mvp.md}
 * does traversal on the CPU side over a flat list of loaded sections, not a GPU
 * tree walk, there is no need for GPU-resident parent/child linkage in the MVP
 * architecture. If 21-opt is later applied, this buffer's schema will need
 * revisiting (likely adopting 20-opt's SoA layout at that point, or extending
 * this AoS layout with a childMask field first as an intermediate step —
 * document whichever path is actually taken at that time in PROJECT_INDEX.md).
 * <p>
 * UNLIKE most MVP/opt pairs in this project (e.g. SectionCache in ticket 08),
 * this class's public API is NOT contract-compatible with {@code 20-gpu-node-buffer-soa-opt.md}'s
 * NodeBufferSoA: this class exposes a single {@link #buffer()} getter, while NodeBufferSoA
 * exposes five separate getters (boundsBuffer(), flagsBuffer(), etc.) — one buffer
 * cannot be split into five without changing every call site that binds it (e.g.
 * shader binding code in traversal.comp, ticket 21). Any caller of this class
 * (most likely ticket 27's EVInstance.renderFarLod) WILL need to be updated
 * if/when 20-opt is applied — do not assume a drop-in replacement here.
 */
public final class NodeBuffer implements AutoCloseable {

    public static final int BYTES_PER_NODE = 32;

    private final int nodeCapacity;
    private final GpuBuffer buffer;
    private boolean closed;

    /**
     * Allocates a single GPU storage buffer for node data in AoS layout.
     *
     * @param backend the render backend used to allocate the buffer, non-null
     * @param nodeCapacity capacity in terms of maximum number of nodes, must be positive
     * @throws IllegalArgumentException if nodeCapacity is <= 0
     */
    public NodeBuffer(RenderBackend backend, int nodeCapacity) {
        Objects.requireNonNull(backend, "backend cannot be null");
        if (nodeCapacity <= 0) {
            throw new IllegalArgumentException("nodeCapacity must be positive: " + nodeCapacity);
        }
        this.nodeCapacity = nodeCapacity;
        long totalSizeBytes = (long) nodeCapacity * BYTES_PER_NODE;
        this.buffer = backend.createBuffer(totalSizeBytes, BufferUsage.STORAGE);
    }

    /**
     * Returns the maximum node capacity of this buffer.
     *
     * @return node capacity
     */
    public int nodeCapacity() {
        return nodeCapacity;
    }

    /**
     * Returns the underlying GPU buffer.
     *
     * @return GPU buffer instance
     */
    public GpuBuffer buffer() {
        return buffer;
    }

    /**
     * Returns the total size of the allocated buffer in bytes (nodeCapacity * 32).
     *
     * @return size in bytes
     */
    public long totalSizeBytes() {
        return (long) nodeCapacity * BYTES_PER_NODE;
    }

    /**
     * Releases the GPU buffer allocated for node storage. Idempotent.
     */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            buffer.free();
        }
    }
}