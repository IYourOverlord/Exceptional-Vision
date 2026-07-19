package org.ex.exceptionalvision.render;

import org.ex.exceptionalvision.cache.LodCacheData;
import org.ex.exceptionalvision.world.lod.PackedQuad;

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

    // FIX (incremental upload): allocated GPU storage size, separate from the *logical*
    // size (nodeCount/quadCount above) - lets growth use spare capacity instead of
    // reallocating on every call. See uploadIncremental()'s javadoc for the strategy.
    private long nodeCapacityBytes;
    private long quadCapacityBytes;

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
     * Full (re)upload from a {@link LodCacheData} snapshot: allocates exactly the new
     * size for every buffer, discarding whatever was there before. Used the first time
     * a world's cache is uploaded (nothing to build on yet), and as the safety fallback
     * inside {@link #uploadIncremental} for the rare cases that can't be expressed as a
     * pure append (see its javadoc).
     */
    public void upload(LodCacheData cacheData, List<Integer> materialColors) {
        this.nodeCount = cacheData.nodeCount();
        this.quadCount = cacheData.quadCount();
        this.maxDrawCommands = Math.max(nodeCount, 1);

        ByteBuffer nodes = cacheData.nodesBuffer();
        this.nodeCapacityBytes = nodes.remaining();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, nodeBuffer);
        glBufferData(GL_SHADER_STORAGE_BUFFER, nodes, GL_STATIC_DRAW);

        ByteBuffer quads = cacheData.quadsBuffer();
        this.quadCapacityBytes = quads.remaining();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, quadBuffer);
        glBufferData(GL_SHADER_STORAGE_BUFFER, quads, GL_STATIC_DRAW);

        uploadMaterialsFull(materialColors);
        resizeDrawScratch(maxDrawCommands);

        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
    }

    /**
     * Applies a fresh {@link LodCacheData} snapshot incrementally instead of
     * re-transferring everything: {@code quads.bin} is append-only by construction
     * ({@link org.ex.exceptionalvision.cache.LodCacheWriter}'s {@code appendQuads} only
     * ever adds to the end - a patched region's fresh quads land after the prior end
     * too, its old range is merely orphaned, never overwritten) and quads are the
     * overwhelming majority of the byte volume in practice (millions of 8-byte quads
     * vs thousands of 48-byte nodes - see PROGRESS.md's numbers from a real run), so
     * the quads SSBO only ever needs its new tail uploaded.
     * <p>
     * {@code nodes.bin}'s uploaded content can be <em>reordered</em> between calls
     * (see {@link LodCacheData}'s "orphaned node" filtering) so, unlike quads, it isn't
     * safe to assume append-only - it's small enough (order of 1000s of 48-byte
     * records) that resubmitting it in full every time is still cheap, so that's what
     * this does rather than diffing it too.
     * <p>
     * Falls back to {@link #upload} (full reallocation) the first time this is called
     * (nothing to build on yet) or if {@code quadCount} ever goes <em>backwards</em>
     * (e.g. the whole cache was rebuilt from scratch out from under us - a format
     * version bump, or a fresh world at the same GL objects after {@link #clear()}) -
     * that's the one situation append-only doesn't hold, so don't assume it does.
     */
    public void uploadIncremental(LodCacheData cacheData, List<Integer> materialColors) {
        if (nodeCapacityBytes == 0 || cacheData.quadCount() < this.quadCount) {
            upload(cacheData, materialColors);
            return;
        }

        ByteBuffer nodes = cacheData.nodesBuffer();
        long nodeBytes = nodes.remaining();
        ensureNodeCapacity(nodeBytes);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, nodeBuffer);
        glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0L, nodes);
        this.nodeCount = cacheData.nodeCount();

        long newQuadCount = cacheData.quadCount();
        if (newQuadCount > this.quadCount) {
            long oldBytes = this.quadCount * (long) PackedQuad.BYTES;
            long newBytes = newQuadCount * (long) PackedQuad.BYTES;
            ensureQuadCapacity(newBytes);

            ByteBuffer quads = cacheData.quadsBuffer();
            quads.position((int) oldBytes); // only the new tail - everything before is already on the GPU
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, quadBuffer);
            glBufferSubData(GL_SHADER_STORAGE_BUFFER, oldBytes, quads);
            this.quadCount = newQuadCount;
        }

        uploadMaterialsFull(materialColors); // small, not worth diffing

        this.maxDrawCommands = Math.max(nodeCount, 1);
        resizeDrawScratch(maxDrawCommands); // pure GPU scratch, content never needs preserving

        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
    }

    /** Grows the node SSBO's capacity if needed. Content isn't preserved on growth - callers that need it (only {@link #upload}) resubmit in full immediately after. */
    private void ensureNodeCapacity(long neededBytes) {
        if (neededBytes <= nodeCapacityBytes) {
            return;
        }
        long newCapacity = Math.max(neededBytes, nodeCapacityBytes * 2);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, nodeBuffer);
        glBufferData(GL_SHADER_STORAGE_BUFFER, newCapacity, GL_STATIC_DRAW);
        nodeCapacityBytes = newCapacity;
    }

    /**
     * Grows the quad SSBO's capacity if needed, preserving existing content via a
     * GPU-side copy (no CPU round trip) into a new, larger buffer object - unlike
     * {@link #ensureNodeCapacity}, {@link #uploadIncremental} only uploads quads' new
     * *tail* afterwards, so whatever was already there must survive the resize.
     */
    private void ensureQuadCapacity(long neededBytes) {
        if (neededBytes <= quadCapacityBytes) {
            return;
        }
        long newCapacity = Math.max(neededBytes, Math.max(quadCapacityBytes * 2, 1024));
        int newBuffer = glGenBuffers();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, newBuffer);
        glBufferData(GL_SHADER_STORAGE_BUFFER, newCapacity, GL_STATIC_DRAW);

        long existingUsedBytes = this.quadCount * (long) PackedQuad.BYTES;
        if (existingUsedBytes > 0) {
            glBindBuffer(GL_COPY_READ_BUFFER, quadBuffer);
            glBindBuffer(GL_COPY_WRITE_BUFFER, newBuffer);
            glCopyBufferSubData(GL_COPY_READ_BUFFER, GL_COPY_WRITE_BUFFER, 0L, 0L, existingUsedBytes);
            glBindBuffer(GL_COPY_READ_BUFFER, 0);
            glBindBuffer(GL_COPY_WRITE_BUFFER, 0);
        }

        glDeleteBuffers(quadBuffer);
        quadBuffer = newBuffer;
        quadCapacityBytes = newCapacity;
    }

    /** (Re)allocates the pure-scratch visible-index / indirect-command buffers to fit {@code drawCommandCapacity} - their content is fully rewritten by the cull compute shader every frame, so nothing to preserve on resize. */
    private void resizeDrawScratch(int drawCommandCapacity) {
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, visibleNodeIndicesBuffer);
        glBufferData(GL_SHADER_STORAGE_BUFFER, (long) drawCommandCapacity * 4L, GL_DYNAMIC_DRAW);

        glBindBuffer(GL_SHADER_STORAGE_BUFFER, indirectCommandBuffer);
        glBufferData(GL_SHADER_STORAGE_BUFFER, (long) drawCommandCapacity * INDIRECT_COMMAND_BYTES, GL_DYNAMIC_DRAW);
    }

    private void uploadMaterialsFull(List<Integer> materialColors) {
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, materialBuffer);
        IntBuffer colors = ByteBuffer.allocateDirect(Math.max(materialColors.size(), 1) * 4)
                .order(ByteOrder.nativeOrder()).asIntBuffer();
        for (int color : materialColors) {
            colors.put(color);
        }
        colors.flip();
        glBufferData(GL_SHADER_STORAGE_BUFFER, colors, GL_STATIC_DRAW);
    }

    /**
     * Marks the currently-uploaded data as empty without touching any GL objects -
     * cheap way to stop drawing stale geometry (e.g. when leaving a world) until the
     * next {@link #upload}, which will resize everything correctly again.
     */
    public void clear() {
        nodeCount = 0;
        quadCount = 0L;
        // FIX (incremental upload): also forget capacity/counts, not just the logical
        // size - otherwise the next uploadIncremental() (for what could be a totally
        // different world/dimension's cache) would wrongly treat its data as a
        // continuation of whatever used to be here and try to diff against it. The
        // underlying GL buffer objects are left as-is (still sized from before); that's
        // fine, the next full upload() will glBufferData them to the new exact size
        // regardless of what's currently allocated.
        nodeCapacityBytes = 0;
        quadCapacityBytes = 0;
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