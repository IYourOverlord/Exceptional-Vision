package dev.ev.test.gpu;

import dev.ev.api.gpu.BarrierScope;
import dev.ev.api.gpu.ComputePipeline;
import dev.ev.api.gpu.GpuBuffer;
import dev.ev.api.gpu.GraphicsPipeline;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * In-memory {@link dev.ev.api.gpu.CommandList} implementation used by
 * {@link FakeRenderBackend#submit}.
 *
 * <p>Operations are recorded into an internal ordered list when called (this class is
 * simply a recorder — it has no side effects of its own), and only actually executed
 * against the owning {@link FakeRenderBackend}'s in-memory buffers when the command
 * list is passed to {@link FakeRenderBackend#submit(dev.ev.api.gpu.CommandList)}. Byte-
 * moving operations ({@link #uploadToBuffer}, {@link #copyBuffer}, {@link #clearBuffer})
 * really copy/fill bytes in the target {@link FakeGpuBuffer}'s backing store when
 * executed. {@link #dispatchCompute}, {@link #dispatchComputeIndirect}, {@link #draw},
 * and {@link #memoryBarrier} do not simulate shader execution or memory ordering — they
 * are only appended to {@link FakeRenderBackend#recordedOperationLog()} when executed,
 * for sequencing assertions in tests. See {@link FakeRenderBackend}'s class Javadoc for
 * the full scope-limitation statement.
 *
 * <p>Not part of the public {@code dev.ev.test.gpu} API surface beyond the
 * {@code CommandList} interface it implements — instances are obtained via
 * {@link FakeRenderBackend#newCommandList()}, a test-only addition documented on that
 * method (the {@code RenderBackend} contract from ticket 04 does not itself expose a way
 * to construct a {@code CommandList}).
 */
final class FakeCommandList implements dev.ev.api.gpu.CommandList {

    private sealed interface Op {
        record Barrier(BarrierScope scope) implements Op {}

        record Upload(GpuBuffer target, long targetOffsetBytes, long sourceAddress, long sizeBytes) implements Op {}

        record Copy(GpuBuffer src, long srcOffset, GpuBuffer dst, long dstOffset, long sizeBytes) implements Op {}

        record Clear(GpuBuffer buffer, long offsetBytes, long sizeBytes, int fillValue) implements Op {}

        record DispatchCompute(ComputePipeline pipeline, int groupsX, int groupsY, int groupsZ) implements Op {}

        record DispatchComputeIndirect(ComputePipeline pipeline, GpuBuffer indirectBuffer, long offsetBytes) implements Op {}

        record Draw(GraphicsPipeline pipeline, GpuBuffer indirectBuffer, long offsetBytes, int drawCount) implements Op {}
    }

    private final List<Op> ops = new ArrayList<>();

    @Override
    public void memoryBarrier(BarrierScope scope) {
        ops.add(new Op.Barrier(scope));
    }

    @Override
    public void uploadToBuffer(GpuBuffer target, long targetOffsetBytes, long sourceAddress, long sizeBytes) {
        ops.add(new Op.Upload(target, targetOffsetBytes, sourceAddress, sizeBytes));
    }

    @Override
    public void copyBuffer(GpuBuffer src, long srcOffset, GpuBuffer dst, long dstOffset, long sizeBytes) {
        ops.add(new Op.Copy(src, srcOffset, dst, dstOffset, sizeBytes));
    }

    @Override
    public void clearBuffer(GpuBuffer buffer, long offsetBytes, long sizeBytes, int fillValue) {
        ops.add(new Op.Clear(buffer, offsetBytes, sizeBytes, fillValue));
    }

    @Override
    public void dispatchCompute(ComputePipeline pipeline, int groupsX, int groupsY, int groupsZ) {
        ops.add(new Op.DispatchCompute(pipeline, groupsX, groupsY, groupsZ));
    }

    @Override
    public void dispatchComputeIndirect(ComputePipeline pipeline, GpuBuffer indirectBuffer, long offsetBytes) {
        ops.add(new Op.DispatchComputeIndirect(pipeline, indirectBuffer, offsetBytes));
    }

    @Override
    public void draw(GraphicsPipeline pipeline, GpuBuffer indirectBuffer, long offsetBytes, int drawCount) {
        ops.add(new Op.Draw(pipeline, indirectBuffer, offsetBytes, drawCount));
    }

    /**
     * Executes all recorded operations in order against the owning backend's in-memory
     * state, appending to {@code operationLog} as it goes. Package-private: called only
     * from {@link FakeRenderBackend#submit}.
     */
    void executeAgainst(List<String> operationLog) {
        for (Op op : ops) {
            switch (op) {
                case Op.Barrier b -> operationLog.add("memoryBarrier(" + b.scope() + ")");
                case Op.Upload u -> {
                    executeUpload(u);
                    operationLog.add("uploadToBuffer(targetOffset=" + u.targetOffsetBytes()
                            + ", sizeBytes=" + u.sizeBytes() + ")");
                }
                case Op.Copy c -> {
                    executeCopy(c);
                    operationLog.add("copyBuffer(srcOffset=" + c.srcOffset() + ", dstOffset="
                            + c.dstOffset() + ", sizeBytes=" + c.sizeBytes() + ")");
                }
                case Op.Clear c -> {
                    executeClear(c);
                    operationLog.add("clearBuffer(offset=" + c.offsetBytes() + ", sizeBytes="
                            + c.sizeBytes() + ", fillValue=" + c.fillValue() + ")");
                }
                case Op.DispatchCompute d -> operationLog.add("dispatchCompute(" + d.groupsX()
                        + "," + d.groupsY() + "," + d.groupsZ() + ")");
                case Op.DispatchComputeIndirect d -> operationLog.add(
                        "dispatchComputeIndirect(offset=" + d.offsetBytes() + ")");
                case Op.Draw d -> operationLog.add("draw(offset=" + d.offsetBytes()
                        + ", drawCount=" + d.drawCount() + ")");
            }
        }
    }

    private static void executeUpload(Op.Upload u) {
        byte[] target = asFake(u.target()).backingStore();
        int size = requireIntSize(u.sizeBytes());
        // sourceAddress is a raw native pointer (as required by the real CommandList
        // contract, ticket 04) — typically obtained from FakeGpuBuffer.mappedAddress()
        // for a STAGING_UPLOAD buffer. Read directly from off-heap memory via MemoryUtil,
        // matching what a real backend would do.
        var sourceView = MemoryUtil.memByteBuffer(u.sourceAddress(), size);
        sourceView.get(target, (int) u.targetOffsetBytes(), size);
    }

    private static void executeCopy(Op.Copy c) {
        byte[] src = asFake(c.src()).backingStore();
        byte[] dst = asFake(c.dst()).backingStore();
        int size = requireIntSize(c.sizeBytes());
        System.arraycopy(src, (int) c.srcOffset(), dst, (int) c.dstOffset(), size);
    }

    private static void executeClear(Op.Clear c) {
        byte[] buf = asFake(c.buffer()).backingStore();
        int size = requireIntSize(c.sizeBytes());
        int offset = (int) c.offsetBytes();
        byte fill = (byte) c.fillValue();
        for (int i = 0; i < size; i++) {
            buf[offset + i] = fill;
        }
    }

    private static FakeGpuBuffer asFake(GpuBuffer buffer) {
        if (!(buffer instanceof FakeGpuBuffer fake)) {
            throw new IllegalArgumentException(
                    "FakeCommandList can only operate on buffers created by the same "
                            + "FakeRenderBackend (via createBuffer), got " + buffer.getClass());
        }
        return fake;
    }

    private static int requireIntSize(long sizeBytes) {
        if (sizeBytes < 0 || sizeBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "FakeRenderBackend only supports sizes within int range, got " + sizeBytes);
        }
        return (int) sizeBytes;
    }
}
