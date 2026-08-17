package dev.ev.gpu.nodes;

import dev.ev.api.gpu.BufferUsage;
import dev.ev.api.gpu.CommandList;
import dev.ev.api.gpu.ComputePipeline;
import dev.ev.api.gpu.FenceHandle;
import dev.ev.api.gpu.GpuBuffer;
import dev.ev.api.gpu.GpuTexture;
import dev.ev.api.gpu.GraphicsPipeline;
import dev.ev.api.gpu.PipelineLayout;
import dev.ev.api.gpu.RenderBackend;
import dev.ev.api.gpu.ShaderSource;
import dev.ev.api.gpu.TextureDesc;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class NodeBufferTest {

    @Test
    @DisplayName("Calculates totalSizeBytes and capacity correctly")
    void testCapacityAndTotalSizeBytesArithmetic() {
        AtomicInteger createBufferCallCount = new AtomicInteger(0);
        RenderBackend fakeBackend = new FakeRenderBackend() {
            @Override
            public GpuBuffer createBuffer(long sizeBytes, BufferUsage usage) {
                createBufferCallCount.incrementAndGet();
                assertEquals(BufferUsage.STORAGE, usage);
                assertEquals(3200L, sizeBytes); // 100 * 32
                return new FakeGpuBuffer(sizeBytes, usage);
            }
        };

        try (NodeBuffer nodeBuffer = new NodeBuffer(fakeBackend, 100)) {
            assertEquals(100, nodeBuffer.nodeCapacity());
            assertEquals(3200L, nodeBuffer.totalSizeBytes());
            assertNotNull(nodeBuffer.buffer());
            assertEquals(1, createBufferCallCount.get());
        }
    }

    @Test
    @DisplayName("close() calls free() on buffer exactly once")
    void testCloseFreesBufferExactlyOnce() {
        AtomicInteger freeCallCount = new AtomicInteger(0);
        GpuBuffer fakeBuffer = new FakeGpuBuffer(320L, BufferUsage.STORAGE) {
            @Override
            public void free() {
                freeCallCount.incrementAndGet();
            }
        };

        RenderBackend fakeBackend = new FakeRenderBackend() {
            @Override
            public GpuBuffer createBuffer(long sizeBytes, BufferUsage usage) {
                return fakeBuffer;
            }
        };

        NodeBuffer nodeBuffer = new NodeBuffer(fakeBackend, 10);
        assertEquals(0, freeCallCount.get());

        nodeBuffer.close();
        assertEquals(1, freeCallCount.get());

        // Second close must be idempotent
        nodeBuffer.close();
        assertEquals(1, freeCallCount.get());
    }

    @Test
    @DisplayName("Constructor throws IllegalArgumentException for invalid capacity")
    void testInvalidCapacityThrows() {
        RenderBackend fakeBackend = new FakeRenderBackend();
        assertThrows(IllegalArgumentException.class, () -> new NodeBuffer(fakeBackend, 0));
        assertThrows(IllegalArgumentException.class, () -> new NodeBuffer(fakeBackend, -5));
        assertThrows(NullPointerException.class, () -> new NodeBuffer(null, 10));
    }

    private static class FakeGpuBuffer implements GpuBuffer {
        private final long sizeBytes;
        private final BufferUsage usage;

        FakeGpuBuffer(long sizeBytes, BufferUsage usage) {
            this.sizeBytes = sizeBytes;
            this.usage = usage;
        }

        @Override
        public long sizeBytes() {
            return sizeBytes;
        }

        @Override
        public BufferUsage usage() {
            return usage;
        }

        @Override
        public long mappedAddress() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void free() {
        }
    }

    private static class FakeRenderBackend implements RenderBackend {
        @Override
        public GpuBuffer createBuffer(long sizeBytes, BufferUsage usage) {
            return new FakeGpuBuffer(sizeBytes, usage);
        }

        @Override
        public GpuTexture createTexture(TextureDesc desc) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ComputePipeline compilePipeline(ShaderSource computeShader, PipelineLayout layout) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GraphicsPipeline compileGraphicsPipeline(ShaderSource vertexShader, ShaderSource fragmentShader, PipelineLayout layout) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void submit(CommandList commandList) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FenceHandle insertFence() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isSignaled(FenceHandle fence) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean waitForFence(FenceHandle fence, long timeoutNanos) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void shutdown() {
            throw new UnsupportedOperationException();
        }
    }
}
