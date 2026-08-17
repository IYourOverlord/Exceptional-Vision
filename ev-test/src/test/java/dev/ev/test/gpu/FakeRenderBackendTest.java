package dev.ev.test.gpu;

import dev.ev.api.gpu.BufferUsage;
import dev.ev.api.gpu.CommandList;
import dev.ev.api.gpu.FenceHandle;
import dev.ev.api.gpu.GpuBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link FakeRenderBackend} itself (meta-level — testing the test
 * infrastructure), per ticket 30's "Юнит-тесты" section.
 */
class FakeRenderBackendTest {

    @Test
    @DisplayName("createBuffer returns a buffer with the expected sizeBytes()/usage()")
    void createBufferReturnsExpectedSizeAndUsage() {
        FakeRenderBackend backend = new FakeRenderBackend();

        GpuBuffer buffer = backend.createBuffer(256, BufferUsage.STORAGE);

        assertEquals(256, buffer.sizeBytes());
        assertEquals(BufferUsage.STORAGE, buffer.usage());
    }

    @Test
    @DisplayName("writeBufferContents then readBufferContents round-trips identical bytes")
    void writeThenReadRoundTrips() {
        FakeRenderBackend backend = new FakeRenderBackend();
        GpuBuffer buffer = backend.createBuffer(8, BufferUsage.STORAGE);
        byte[] data = {1, 2, 3, 4, 5, 6, 7, 8};

        backend.writeBufferContents(buffer, data);
        byte[] result = backend.readBufferContents(buffer);

        assertArrayEquals(data, result);
    }

    @Test
    @DisplayName("free() on a buffer removes it from activeBuffers()")
    void freeRemovesFromActiveBuffers() {
        FakeRenderBackend backend = new FakeRenderBackend();
        GpuBuffer buffer = backend.createBuffer(16, BufferUsage.STORAGE);

        assertTrue(backend.activeBuffers().contains(buffer));

        buffer.free();

        assertFalse(backend.activeBuffers().contains(buffer));
    }

    @Test
    @DisplayName("submit with uploadToBuffer really copies bytes into the target buffer")
    void submitUploadCopiesBytes() {
        FakeRenderBackend backend = new FakeRenderBackend();
        GpuBuffer staging = backend.createBuffer(4, BufferUsage.STAGING_UPLOAD);
        GpuBuffer target = backend.createBuffer(4, BufferUsage.STORAGE);
        byte[] payload = {10, 20, 30, 40};

        long address = staging.mappedAddress();
        writeNative(address, payload);

        CommandList commands = backend.newCommandList();
        commands.uploadToBuffer(target, 0, address, 4);
        backend.submit(commands);

        assertArrayEquals(payload, backend.readBufferContents(target));
    }

    @Test
    @DisplayName("submit with copyBuffer between two fake buffers copies data honoring offsets")
    void submitCopyBufferHonorsOffsets() {
        FakeRenderBackend backend = new FakeRenderBackend();
        GpuBuffer src = backend.createBuffer(8, BufferUsage.STORAGE);
        GpuBuffer dst = backend.createBuffer(8, BufferUsage.STORAGE);
        backend.writeBufferContents(src, new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        backend.writeBufferContents(dst, new byte[8]);

        CommandList commands = backend.newCommandList();
        // copy 3 bytes starting at src offset 2 (values 3,4,5) into dst starting at offset 5
        commands.copyBuffer(src, 2, dst, 5, 3);
        backend.submit(commands);

        byte[] expected = {0, 0, 0, 0, 0, 3, 4, 5};
        assertArrayEquals(expected, backend.readBufferContents(dst));
    }

    @Test
    @DisplayName("dispatchCompute/draw calls land in recordedOperationLog() in relative order")
    void dispatchAndDrawRecordedInOrder() {
        FakeRenderBackend backend = new FakeRenderBackend();
        var pipeline = backend.compilePipeline(
                new dev.ev.api.gpu.ShaderSource("test.glsl", "#version 450\n", java.util.Map.of()),
                new dev.ev.api.gpu.PipelineLayout(java.util.Map.of()));

        CommandList commands = backend.newCommandList();
        commands.memoryBarrier(dev.ev.api.gpu.BarrierScope.ALL);
        commands.dispatchCompute(pipeline, 1, 2, 3);
        commands.dispatchCompute(pipeline, 4, 5, 6);
        backend.submit(commands);

        List<String> log = backend.recordedOperationLog();
        assertEquals(3, log.size());
        assertTrue(log.get(0).startsWith("memoryBarrier"));
        assertTrue(log.get(1).contains("1,2,3"));
        assertTrue(log.get(2).contains("4,5,6"));
    }

    @Test
    @DisplayName("insertFence/isSignaled/waitForFence behave per the always-immediately-signaled contract")
    void fencesAlwaysImmediatelySignaled() {
        FakeRenderBackend backend = new FakeRenderBackend();

        FenceHandle fence = backend.insertFence();

        assertTrue(backend.isSignaled(fence));
        assertTrue(backend.waitForFence(fence, 0));
        assertTrue(backend.waitForFence(fence, 1_000_000_000L));
    }

    @Test
    @DisplayName("shutdown() does not throw, including when called twice")
    void shutdownIsIdempotentAndSafe() {
        FakeRenderBackend backend = new FakeRenderBackend();

        assertDoesNotThrow(backend::shutdown);
        assertDoesNotThrow(backend::shutdown);
        assertTrue(backend.isShutDown());
    }

    /** Writes {@code data} into the native memory block at {@code address}. */
    private static void writeNative(long address, byte[] data) {
        var view = org.lwjgl.system.MemoryUtil.memByteBuffer(address, data.length);
        view.put(data);
    }
}
