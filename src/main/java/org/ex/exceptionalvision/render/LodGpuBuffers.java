package org.ex.exceptionalvision.render;

import org.ex.exceptionalvision.ExceptionalVision;
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
 * <p>
 * <b>Stage 5:</b> the quad buffer (by far the largest and most frequently appended-to
 * one - see {@link #uploadIncremental}'s javadoc) now uses a <em>persistent mapped
 * ring</em> via {@code glBufferStorage(GL_MAP_PERSISTENT_BIT | GL_MAP_WRITE_BIT |
 * GL_MAP_COHERENT_BIT)} when {@link GpuCapabilities#persistentMappingSupported()} is
 * true (roadmap: "Persistent mapped buffers for streaming new data without stalls").
 * The pointer returned by {@code glMapBufferRange} stays valid for the buffer's entire
 * lifetime, so appending new quads is a plain {@code memcpy} into already-mapped client
 * memory instead of a {@code glBufferSubData} call that must synchronize with whatever
 * draw call last read that buffer range. Growth still requires a new allocation (you
 * cannot resize a persistent-mapped buffer in place) - see {@link #ensureQuadCapacity}.
 * Falls back transparently to the stage-4 {@code glBufferData}/{@code glBufferSubData}
 * path when persistent mapping isn't available.
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

    // Stage 5: whether the currently-allocated quadBuffer was created with
    // glBufferStorage(GL_MAP_PERSISTENT_BIT) (true) or plain glBufferData (false, the
    // stage-4 fallback path). Decided once at create()-time from GpuCapabilities and
    // re-decided every time ensureQuadCapacity() has to allocate a new buffer object,
    // so a mid-session capability change (there isn't one in practice - detect() runs
    // once at init()) can't leave this out of sync with the buffer that actually exists.
    private boolean quadBufferPersistent;
    // Valid only when quadBufferPersistent is true - the client-memory pointer returned
    // by glMapBufferRange, stable for the mapped buffer's entire lifetime. GL_MAP_COHERENT_BIT
    // means writes through this pointer are visible to the GPU without an explicit
    // glFlushMappedBufferRange call, at the cost of the driver handling that coherency
    // itself rather than us controlling exactly when it happens - an acceptable trade for
    // a quad ring buffer, which is written by exactly one thread (the render thread, via
    // Minecraft.execute in ExceptionalVisionClient#onRegionCached) and never at the same
    // time as a draw reads it (both happen serially within the same frame's render-thread
    // work, never concurrently on separate threads).
    private ByteBuffer quadBufferMapped;

    private GpuCapabilities capabilities;

    public void create() {
        create(GpuCapabilities.detect());
    }

    /**
     * @param capabilities capability snapshot to size/allocate buffers appropriately -
     *                     see {@link #quadBufferPersistent}. Passed in explicitly
     *                     (rather than calling {@link GpuCapabilities#detect()} again
     *                     here) so this class doesn't need its own GL-context-current
     *                     assumption beyond what its caller already established, and so
     *                     {@link LodGpuPipeline#init()} - which already called
     *                     {@code detect()} once for its own gating - doesn't do so twice.
     */
    public void create(GpuCapabilities capabilities) {
        this.capabilities = capabilities;
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
        allocateQuadBuffer(quadBuffer, quadCapacityBytes);
        writeQuadBytes(0L, quads);

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
            ExceptionalVision.LOGGER.info(
                    "[EV-DIAG] uploadIncremental falling back to full upload: nodeCapacityBytes={}, cacheData.quadCount()={}, this.quadCount={}",
                    nodeCapacityBytes, cacheData.quadCount(), this.quadCount);
            upload(cacheData, materialColors);
            return;
        }

        ByteBuffer nodes = cacheData.nodesBuffer();
        long nodeBytes = nodes.remaining();
        ensureNodeCapacity(nodeBytes);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, nodeBuffer);
        glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0L, nodes);
        int nodeCountBefore = this.nodeCount;
        this.nodeCount = cacheData.nodeCount();

        // DIAGNOSTIC (temporary - see PROGRESS.md "periodic holes on autosave" investigation):
        // nodes.bin's freshly-uploaded records may already carry quadOffsets that point past
        // this.quadCount if cacheData's quads haven't been appended below yet - logged so we
        // can confirm whether a cull/draw pass can observe this in-between state.
        if (cacheData.quadCount() == this.quadCount && cacheData.nodeCount() != nodeCountBefore) {
            ExceptionalVision.LOGGER.info(
                    "[EV-DIAG] uploadIncremental: node count changed {} -> {} with quadCount unchanged at {} (region patch with no new quads this call)",
                    nodeCountBefore, cacheData.nodeCount(), this.quadCount);
        }

        long newQuadCount = cacheData.quadCount();
        if (newQuadCount > this.quadCount) {
            long oldBytes = this.quadCount * (long) PackedQuad.BYTES;
            long newBytes = newQuadCount * (long) PackedQuad.BYTES;
            ensureQuadCapacity(newBytes);

            ByteBuffer quads = cacheData.quadsBuffer();
            quads.position((int) oldBytes); // only the new tail - everything before is already on the GPU
            writeQuadBytes(oldBytes, quads);
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
     * <p>
     * Growing a persistent-mapped buffer in place isn't possible (its storage is
     * immutable once {@code glBufferStorage} allocates it - that's the whole point of
     * the "persistent" contract), so growth always means: allocate a new, larger
     * buffer (mapped or not, matching {@link #quadBufferPersistent}), GPU-side-copy the
     * existing content across, unmap/delete the old one. This is exactly as expensive
     * as the stage-4 growth path was - the *steady-state* append case (the overwhelming
     * majority of calls, since capacity typically outpaces one region's worth of new
     * quads) is what persistent mapping actually speeds up, via {@link #writeQuadBytes}.
     */
    private void ensureQuadCapacity(long neededBytes) {
        if (neededBytes <= quadCapacityBytes) {
            return;
        }
        long newCapacity = Math.max(neededBytes, Math.max(quadCapacityBytes * 2, 1024));
        int newBuffer = glGenBuffers();
        allocateQuadBuffer(newBuffer, newCapacity);

        long existingUsedBytes = this.quadCount * (long) PackedQuad.BYTES;
        if (existingUsedBytes > 0) {
            glBindBuffer(GL_COPY_READ_BUFFER, quadBuffer);
            glBindBuffer(GL_COPY_WRITE_BUFFER, newBuffer);
            glCopyBufferSubData(GL_COPY_READ_BUFFER, GL_COPY_WRITE_BUFFER, 0L, 0L, existingUsedBytes);
            glBindBuffer(GL_COPY_READ_BUFFER, 0);
            glBindBuffer(GL_COPY_WRITE_BUFFER, 0);
        }

        unmapQuadBufferIfMapped();
        glDeleteBuffers(quadBuffer);
        quadBuffer = newBuffer;
        quadCapacityBytes = newCapacity;
    }

    /**
     * (Re)allocates {@code bufferId}'s storage to exactly {@code capacityBytes}, either
     * as a persistent-mapped store (if {@link GpuCapabilities#persistentMappingSupported()})
     * or as an ordinary mutable store (the stage-4 fallback). Updates
     * {@link #quadBufferMapped}/{@link #quadBufferPersistent} to describe whichever
     * path was taken - callers must not assume the previous call's path still applies.
     */
    private void allocateQuadBuffer(int bufferId, long capacityBytes) {
        unmapQuadBufferIfMapped();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, bufferId);
        if (capabilities != null && capabilities.persistentMappingSupported()) {
            int storageFlags = GL_MAP_WRITE_BIT | GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT;
            glBufferStorage(GL_SHADER_STORAGE_BUFFER, capacityBytes, storageFlags);
            int mapFlags = GL_MAP_WRITE_BIT | GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT;
            quadBufferMapped = glMapBufferRange(GL_SHADER_STORAGE_BUFFER, 0L, capacityBytes, mapFlags);
            quadBufferPersistent = true;
        } else {
            glBufferData(GL_SHADER_STORAGE_BUFFER, capacityBytes, GL_STATIC_DRAW);
            quadBufferMapped = null;
            quadBufferPersistent = false;
        }
    }

    /**
     * Writes {@code src.remaining()} bytes at {@code offsetBytes} into the quad buffer -
     * a {@code memcpy} into the persistent-mapped pointer when available (no GL call, no
     * sync point), or an ordinary {@code glBufferSubData} otherwise.
     */
    private void writeQuadBytes(long offsetBytes, ByteBuffer src) {
        if (quadBufferPersistent && quadBufferMapped != null) {
            ByteBuffer dst = quadBufferMapped.duplicate();
            dst.order(src.order());
            dst.position((int) offsetBytes);
            dst.put(src);
            // GL_MAP_COHERENT_BIT means no glFlushMappedBufferRange/glMemoryBarrier is
            // needed here for the write to become visible to a subsequent GPU read -
            // that visibility guarantee is exactly what COHERENT buys over a plain
            // PERSISTENT mapping (which would need an explicit flush).
        } else {
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, quadBuffer);
            glBufferSubData(GL_SHADER_STORAGE_BUFFER, offsetBytes, src);
        }
    }

    /** Unmaps {@link #quadBufferMapped} if a mapping is currently active - must happen before deleting or reallocating the buffer object it points into. */
    private void unmapQuadBufferIfMapped() {
        if (quadBufferMapped != null) {
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, quadBuffer);
            glUnmapBuffer(GL_SHADER_STORAGE_BUFFER);
            quadBufferMapped = null;
        }
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
        // fine, the next full upload() will glBufferData/glBufferStorage them to the new
        // exact size regardless of what's currently allocated - which, for a persistent-
        // mapped quad buffer, requires unmapping first (glBufferStorage on an
        // already-mapped buffer is invalid), hence the unmap here rather than leaving it
        // to upload()'s allocateQuadBuffer() to discover it's still mapped.
        nodeCapacityBytes = 0;
        quadCapacityBytes = 0;
        unmapQuadBufferIfMapped();
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

    /**
     * Issues the GPU-resident, zero-readback multi-draw (stage 5's "full path" - see
     * {@link GpuCapabilities#indirectCountSupported()}): the GPU itself reads the
     * current draw count out of {@link #drawCounterBuffer} when executing the indirect
     * draw, rather than the CPU reading it back first and passing it explicitly (that's
     * {@link #readDrawCount()} + the caller's own {@code glMultiDrawArraysIndirect} -
     * the stage-4 fallback). Requires {@code GL_PARAMETER_BUFFER_ARB}/{@code
     * GL_PARAMETER_BUFFER} to be bound to {@link #drawCounterBuffer} - see LWJGL's
     * {@code glMultiDrawArraysIndirectCount} binding, which takes it as an explicit
     * buffer bind rather than a separate parameter.
     *
     * @param mode        primitive mode (e.g. {@code GL_TRIANGLES})
     * @param maxDrawCount upper bound on how many draw commands the GPU may read -
     *                     {@link #maxDrawCommands} (buffers are sized for the worst case
     *                     of every node being visible), same bound {@link #readDrawCount()}
     *                     clamps against on the fallback path.
     */
    public void multiDrawIndirectCount(int mode, int maxDrawCount) {
        glBindBuffer(GL_PARAMETER_BUFFER, drawCounterBuffer);
        glMultiDrawArraysIndirectCount(mode, 0L, 0L, maxDrawCount, 0);
    }

    @Override
    public void close() {
        unmapQuadBufferIfMapped();
        glDeleteBuffers(nodeBuffer);
        glDeleteBuffers(quadBuffer);
        glDeleteBuffers(materialBuffer);
        glDeleteBuffers(visibleNodeIndicesBuffer);
        glDeleteBuffers(indirectCommandBuffer);
        glDeleteBuffers(drawCounterBuffer);
    }
}