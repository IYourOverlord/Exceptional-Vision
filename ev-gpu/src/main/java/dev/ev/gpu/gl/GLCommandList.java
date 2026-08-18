package dev.ev.gpu.gl;

import dev.ev.api.gpu.BarrierScope;
import dev.ev.api.gpu.CommandList;
import dev.ev.api.gpu.ComputePipeline;
import dev.ev.api.gpu.GpuBuffer;
import dev.ev.api.gpu.GraphicsPipeline;

import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL45;
import org.lwjgl.system.MemoryUtil;

/**
 * {@link CommandList} implementation that issues real OpenGL calls immediately as each
 * method is called, rather than recording operations for later replay.
 *
 * <h2>Why immediate, not deferred/batched (ticket 31 integration note)</h2>
 * Ticket 17's own scope explicitly left {@link GLRenderBackend#submit} as a stub for
 * "a later ticket (19-23)". {@code MVP_INDEX.md}'s subsystem table, however, states that
 * ordinary (non-batched) GPU upload is "part of ticket 17", and reserves only the
 * staging-ring-buffer <i>batching</i> optimization for {@code
 * 19-gpu-upload-batching-opt.md} (an opt-only ticket, never scheduled for Wave 1). No
 * MVP ticket actually closed that gap — {@link GLRenderBackend#submit} was still throwing
 * {@link UnsupportedOperationException} going into this integration ticket, which would
 * make every real frame throw the moment {@code FrameGraphBuilder.execute()} called
 * {@code backend.submit(...)}. This class (and the corresponding methods on
 * {@link GLRenderBackend}) close that gap as an MVP-appropriate implementation: every
 * {@code CommandList} method below performs its GL call directly and synchronously,
 * on the calling (render) thread, exactly as the un-batched "part of ticket 17" upload
 * path implies. There is no staging ring buffer, no deferred replay, no batching of
 * multiple uploads into one driver call — that remains {@code 19-gpu-upload-batching-opt}'s
 * job, to be taken up only if {@code P0-profiling-checkpoint.md} shows upload driver-call
 * overhead is actually significant.
 *
 * <p>All methods, like all GL calls in {@code ev-gpu}, must only be called from the
 * render thread. Not thread-safe, holds no state beyond nothing (stateless — every call
 * is a direct GL call), safe to share a single instance across an entire frame or to
 * construct a fresh one per frame; {@link GLRenderBackend#submit} constructs one
 * implicitly (see that method).
 */
final class GLCommandList implements CommandList {

    @Override
    public void memoryBarrier(BarrierScope scope) {
        GL42.glMemoryBarrier(toGlBarrierBits(scope));
    }

    @Override
    public void uploadToBuffer(GpuBuffer target, long targetOffsetBytes, long sourceAddress, long sizeBytes) {
        int handle = ((GLBuffer) target).handle();
        // sourceAddress is a raw native pointer (e.g. a STAGING_UPLOAD buffer's persistent
        // mapped address, per GpuBuffer/CommandList contract) — wrap it as a ByteBuffer view
        // without copying, matching what a real driver-facing call needs.
        var sourceView = MemoryUtil.memByteBuffer(sourceAddress, requireIntSize(sizeBytes));
        GL45.glNamedBufferSubData(handle, targetOffsetBytes, sourceView);
    }

    @Override
    public void copyBuffer(GpuBuffer src, long srcOffset, GpuBuffer dst, long dstOffset, long sizeBytes) {
        int srcHandle = ((GLBuffer) src).handle();
        int dstHandle = ((GLBuffer) dst).handle();
        GL45.glCopyNamedBufferSubData(srcHandle, dstHandle, srcOffset, dstOffset, sizeBytes);
    }

    @Override
    public void clearBuffer(GpuBuffer buffer, long offsetBytes, long sizeBytes, int fillValue) {
        int handle = ((GLBuffer) buffer).handle();
        // GL_R8/GL_UNSIGNED_BYTE: fillValue's low byte is repeated across every byte of the
        // cleared range — the CommandList contract's fillValue is a plain int fill pattern,
        // not a typed pixel value, so the simplest byte-repeat interpretation (R8/UNSIGNED_BYTE)
        // is the correct one here, not e.g. RGBA32UI (which would only repeat every 4th byte).
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            var data = stack.bytes((byte) fillValue);
            GL45.glClearNamedBufferSubData(handle, GL45.GL_R8, offsetBytes, sizeBytes,
                    GL45.GL_RED, GL45.GL_UNSIGNED_BYTE, data);
        }
    }

    @Override
    public void dispatchCompute(ComputePipeline pipeline, int groupsX, int groupsY, int groupsZ) {
        pipeline.dispatch(groupsX, groupsY, groupsZ);
    }

    @Override
    public void dispatchComputeIndirect(ComputePipeline pipeline, GpuBuffer indirectBuffer, long offsetBytes) {
        pipeline.dispatchIndirect(indirectBuffer, offsetBytes);
    }

    @Override
    public void draw(GraphicsPipeline pipeline, GpuBuffer indirectBuffer, long offsetBytes, int drawCount) {
        // CommandList.draw's shape (indirectBuffer + offsetBytes + drawCount) matches
        // GraphicsPipeline.drawIndirect exactly (see ticket 04's CommandList/GraphicsPipeline
        // contracts) — this project has no non-indirect draw path in either interface, so
        // routing draw() to drawIndirect() is not a simplification, it is the only option
        // the contract offers.
        pipeline.drawIndirect(indirectBuffer, offsetBytes, drawCount);
    }

    private static int toGlBarrierBits(BarrierScope scope) {
        return switch (scope) {
            // GL_SHADER_STORAGE_BARRIER_BIT lives in GL43 (introduced with GL 4.3's SSBO
            // core support), not GL42 — verified via web search, not assumed, since
            // GL_ALL_BARRIER_BITS/GL_COMMAND_BARRIER_BIT/GL_BUFFER_UPDATE_BARRIER_BIT (GL42,
            // from ARB_shader_image_load_store/GL 4.2 core) predate it.
            case SHADER_STORAGE -> GL43.GL_SHADER_STORAGE_BARRIER_BIT;
            case COMMAND -> GL42.GL_COMMAND_BARRIER_BIT;
            case BUFFER_UPDATE -> GL42.GL_BUFFER_UPDATE_BARRIER_BIT;
            case ALL -> GL42.GL_ALL_BARRIER_BITS;
        };
    }

    private static int requireIntSize(long sizeBytes) {
        if (sizeBytes < 0 || sizeBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "GLCommandList only supports sizes within int range, got " + sizeBytes);
        }
        return (int) sizeBytes;
    }
}
