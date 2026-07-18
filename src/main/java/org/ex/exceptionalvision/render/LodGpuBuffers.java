package org.ex.exceptionalvision.render;

import org.ex.exceptionalvision.cache.LodCacheData;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.List;

import static org.lwjgl.opengl.GL46C.*;

/**
 * Owns the GL buffer objects (SSBOs) the LOD GPU pipeline reads from / writes to, and
 * knows how to (re)populate them from a {@link LodCacheData} snapshot plus a material
 * color list. See "SSBO Layout" in {@code 04_gpu_pipeline.md} for the binding-index
 * contract this, {@code quad_cull.comp}, {@code lod_quad.vert} and
 * {@code lod_quad.frag} all share.
 * <p>
 * {@link LodCacheData#nodesBuffer()}/{@link LodCacheData#quadsBuffer()} are already
 * byte-for-byte in the GPU struct layout (little-endian, tightly packed, header
 * already stripped), so uploading them is a single {@code glBufferData} call each - no
 * CPU-side re-packing. That direct-upload property was a deliberate stage-3 design goal.
 */
public final class LodGpuBuffers implements AutoCloseable {

    private static final int BINDING_NODES = 0;
    private static final int BINDING_QUADS = 1;
    private static final int BINDING_MATERIALS = 2;
    private static final int BINDING_VISIBLE_NODE_INDICES = 3;
    private static final int BINDING_INDIRECT_COMMANDS = 4;
    private static final int BINDING_DRAW_COUNTER = 5;

    /** bytes per DrawArraysIndirectCommand: 4 x uint32 (count, instanceCount, first, baseInstance). */
    private static final int INDIRECT_COMMAND_BYTES = 16;

    private int nodeBuffer;
    private int quadBuffer;
    private int materialBuffer;
    private int visibleNodeIndicesBuffer;
    private int indirectCommandBuffer;
    private int drawCounterBuffer;

    private int nodeCount;
    private long quadCount;
    private int maxDrawCommands;

    public void create() {
        nodeBuffer = glGenBuffers();
        quadBuffer = glGenBuffers();
        materialBuffer = glGenBuffers();
        visibleNodeIndicesBuffer = glGenBuffers();
        indirectCommandBuffer = glGenBuffers();
        drawCounterBuffer = glGenBuffers();

        glBindBuffer(GL_SHADER_STORAGE_BUFFER, drawCounterBuffer);
        glBufferData(GL_SHADER_STORAGE_BUFFER, 4L, GL_DYNAMIC_DRAW);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
    }

    /**
     * Uploads node/quad data from a fresh {@link LodCacheData} snapshot and a material
     * color list, and (re)sizes the visible-index / indirect-command buffers to the new
     * worst case ({@code nodeCount}, every node visible at once).
     */
    public void upload(LodCacheData cacheData, List<Integer> materialColors) {
        this.nodeCount = cacheData.nodeCount();
        this.quadCount = cacheData.quadCount();
        this.maxDrawCommands = Math.max(nodeCount, 1);

        glBindBuffer(GL_SHADER_STORAGE_BUFFER, nodeBuffer);
        glBufferData(GL_SHADER_STORAGE_BUFFER, cacheData.nodesBuffer(), GL_STATIC_DRAW);

        glBindBuffer(GL_SHADER_STORAGE_BUFFER, quadBuffer);
        glBufferData(GL_SHADER_STORAGE_BUFFER, cacheData.quadsBuffer(), GL_STATIC_DRAW);

        glBindBuffer(GL_SHADER_STORAGE_BUFFER, materialBuffer);
        IntBuffer colors = ByteBuffer.allocateDirect(Math.max(materialColors.size(), 1) * 4)
                .order(ByteOrder.nativeOrder()).asIntBuffer();
        for (int color : materialColors) {
            colors.put(color);
        }
        colors.flip();
        glBufferData(GL_SHADER_STORAGE_BUFFER, colors, GL_STATIC_DRAW);

        glBindBuffer(GL_SHADER_STORAGE_BUFFER, visibleNodeIndicesBuffer);
        glBufferData(GL_SHADER_STORAGE_BUFFER, (long) maxDrawCommands * 4L, GL_DYNAMIC_DRAW);

        glBindBuffer(GL_SHADER_STORAGE_BUFFER, indirectCommandBuffer);
        glBufferData(GL_SHADER_STORAGE_BUFFER, (long) maxDrawCommands * INDIRECT_COMMAND_BYTES, GL_DYNAMIC_DRAW);

        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
    }

    /**
     * Marks the currently-uploaded data as empty without touching any GL objects -
     * cheap way to stop drawing stale geometry (e.g. when leaving a world) until the
     * next {@link #upload}, which will resize everything correctly again.
     */
    public void clear() {
        nodeCount = 0;
        quadCount = 0L;
    }

    public void bindForCompute() {
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, BINDING_NODES, nodeBuffer);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, BINDING_VISIBLE_NODE_INDICES, visibleNodeIndicesBuffer);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, BINDING_INDIRECT_COMMANDS, indirectCommandBuffer);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, BINDING_DRAW_COUNTER, drawCounterBuffer);
    }

    public void bindForDraw() {
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, BINDING_NODES, nodeBuffer);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, BINDING_QUADS, quadBuffer);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, BINDING_MATERIALS, materialBuffer);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, BINDING_VISIBLE_NODE_INDICES, visibleNodeIndicesBuffer);
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, indirectCommandBuffer);
    }

    public void resetDrawCounter() {
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, drawCounterBuffer);
        glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0L, new int[] {0});
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
    }

    /**
     * CPU-readback of the draw count {@code quad_cull.comp} wrote - the stage-4 fallback
     * path (see "Explicit Count Readback" in {@code 04_gpu_pipeline.md}). Caller must
     * have issued a {@code GL_SHADER_STORAGE_BARRIER_BIT} memory barrier after the
     * compute dispatch and before calling this, or the read may race the write.
     */
    public int readDrawCount() {
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, drawCounterBuffer);
        int[] result = new int[1];
        glGetBufferSubData(GL_SHADER_STORAGE_BUFFER, 0L, result);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
        return Math.min(result[0], maxDrawCommands);
    }

    public int nodeCount() {
        return nodeCount;
    }

    public long quadCount() {
        return quadCount;
    }

    public int maxDrawCommands() {
        return maxDrawCommands;
    }

    @Override
    public void close() {
        glDeleteBuffers(nodeBuffer);
        glDeleteBuffers(quadBuffer);
        glDeleteBuffers(materialBuffer);
        glDeleteBuffers(visibleNodeIndicesBuffer);
        glDeleteBuffers(indirectCommandBuffer);
        glDeleteBuffers(drawCounterBuffer);
    }
}
