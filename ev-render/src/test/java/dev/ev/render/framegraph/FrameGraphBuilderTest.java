package dev.ev.render.framegraph;

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
import dev.ev.api.metrics.ImportStageStatus;
import dev.ev.api.metrics.MetricsRegistry;
import dev.ev.api.metrics.MetricsSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FrameGraphBuilderTest {

    @Test
    @DisplayName("Test 1: Linear graph A (writes R) -> B (reads R) records A before B with barrier between them")
    void testLinearDependencyWithBarrier() {
        TestRenderBackend backend = new TestRenderBackend();
        TestMetricsRegistry metrics = new TestMetricsRegistry();
        FrameGraphBuilder builder = new FrameGraphBuilder(backend, metrics);

        FrameResource res = new FrameResource("BufferR");

        builder.addPass("PassA", () -> new TestPass("PassA")).writes(res);
        builder.addPass("PassB", () -> new TestPass("PassB")).reads(res);

        builder.execute();

        assertNotNull(backend.lastSubmitted);
        RecordingCommandList cmdList = backend.lastSubmitted;

        List<String> expectedLog = List.of(
                "PASS:PassA",
                "BARRIER:ALL",
                "PASS:PassB"
        );
        assertEquals(expectedLog, cmdList.recordLog);
        assertEquals(1, cmdList.barrierCount);
        assertEquals(2, metrics.recordedGpuPasses.size());
    }

    @Test
    @DisplayName("Test 2: Independent passes execute without false-positive barrier between them")
    void testIndependentPassesNoBarrier() {
        TestRenderBackend backend = new TestRenderBackend();
        TestMetricsRegistry metrics = new TestMetricsRegistry();
        FrameGraphBuilder builder = new FrameGraphBuilder(backend, metrics);

        FrameResource res1 = new FrameResource("Res1");
        FrameResource res2 = new FrameResource("Res2");

        builder.addPass("PassA", () -> new TestPass("PassA")).writes(res1);
        builder.addPass("PassB", () -> new TestPass("PassB")).writes(res2);

        builder.execute();

        RecordingCommandList cmdList = backend.lastSubmitted;
        List<String> expectedLog = List.of(
                "PASS:PassA",
                "PASS:PassB"
        );
        assertEquals(expectedLog, cmdList.recordLog);
        assertEquals(0, cmdList.barrierCount);
    }

    @Test
    @DisplayName("Test 3: Chain of 3 passes (A -> B -> C) records in order A, B, C")
    void testThreePassChain() {
        TestRenderBackend backend = new TestRenderBackend();
        TestMetricsRegistry metrics = new TestMetricsRegistry();
        FrameGraphBuilder builder = new FrameGraphBuilder(backend, metrics);

        FrameResource resAB = new FrameResource("AB");
        FrameResource resBC = new FrameResource("BC");

        builder.addPass("PassA", () -> new TestPass("PassA")).writes(resAB);
        builder.addPass("PassB", () -> new TestPass("PassB")).reads(resAB).writes(resBC);
        builder.addPass("PassC", () -> new TestPass("PassC")).reads(resBC);

        builder.execute();

        RecordingCommandList cmdList = backend.lastSubmitted;
        List<String> expectedLog = List.of(
                "PASS:PassA",
                "BARRIER:ALL",
                "PASS:PassB",
                "BARRIER:ALL",
                "PASS:PassC"
        );
        assertEquals(expectedLog, cmdList.recordLog);
    }

    @Test
    @DisplayName("Test 4: Cyclic dependency throws IllegalStateException")
    void testCyclicDependencyThrows() {
        TestRenderBackend backend = new TestRenderBackend();
        TestMetricsRegistry metrics = new TestMetricsRegistry();
        FrameGraphBuilder builder = new FrameGraphBuilder(backend, metrics);

        FrameResource resAB = new FrameResource("AB");
        FrameResource resBA = new FrameResource("BA");

        builder.addPass("PassA", () -> new TestPass("PassA")).reads(resBA).writes(resAB);
        builder.addPass("PassB", () -> new TestPass("PassB")).reads(resAB).writes(resBA);

        IllegalStateException ex = assertThrows(IllegalStateException.class, builder::execute);
        assertTrue(ex.getMessage().contains("Cyclic dependency detected"));
    }

    @Test
    @DisplayName("Test 4a: Intermediate unrelated pass B between A (writes R) and C (reads R) has barrier before C, not before B")
    void testUnrelatedPassNoBarrierBeforeIt() {
        TestRenderBackend backend = new TestRenderBackend();
        TestMetricsRegistry metrics = new TestMetricsRegistry();
        FrameGraphBuilder builder = new FrameGraphBuilder(backend, metrics);

        FrameResource resR = new FrameResource("R");
        FrameResource resUnrelated = new FrameResource("U");

        builder.addPass("PassA", () -> new TestPass("PassA")).writes(resR);
        builder.addPass("PassB", () -> new TestPass("PassB")).writes(resUnrelated);
        builder.addPass("PassC", () -> new TestPass("PassC")).reads(resR);

        builder.execute();

        RecordingCommandList cmdList = backend.lastSubmitted;
        List<String> expectedLog = List.of(
                "PASS:PassA",
                "PASS:PassB",
                "BARRIER:ALL",
                "PASS:PassC"
        );
        assertEquals(expectedLog, cmdList.recordLog);
        assertEquals(1, cmdList.barrierCount);
    }

    @Test
    @DisplayName("Test 4b: Multiple sources Q1, Q2 for consumer P executes Q1 and Q2 before P with barrier")
    void testMultipleWritersSingleConsumer() {
        TestRenderBackend backend = new TestRenderBackend();
        TestMetricsRegistry metrics = new TestMetricsRegistry();
        FrameGraphBuilder builder = new FrameGraphBuilder(backend, metrics);

        FrameResource resR1 = new FrameResource("R1");
        FrameResource resR2 = new FrameResource("R2");

        builder.addPass("PassQ1", () -> new TestPass("PassQ1")).writes(resR1);
        builder.addPass("PassQ2", () -> new TestPass("PassQ2")).writes(resR2);
        builder.addPass("PassP", () -> new TestPass("PassP")).reads(resR1, resR2);

        builder.execute();

        RecordingCommandList cmdList = backend.lastSubmitted;
        int idxQ1 = cmdList.recordLog.indexOf("PASS:PassQ1");
        int idxQ2 = cmdList.recordLog.indexOf("PASS:PassQ2");
        int idxP = cmdList.recordLog.indexOf("PASS:PassP");
        int idxBarrier = cmdList.recordLog.indexOf("BARRIER:ALL");

        assertTrue(idxQ1 < idxP);
        assertTrue(idxQ2 < idxP);
        assertTrue(idxBarrier > idxQ1);
        assertTrue(idxBarrier > idxQ2);
        assertTrue(idxBarrier < idxP);
    }

    @Test
    @DisplayName("Test 5: Reusing the same FrameGraphBuilder instance for a second execute() throws IllegalStateException")
    void testReusingBuilderInstanceThrows() {
        TestRenderBackend backend = new TestRenderBackend();
        TestMetricsRegistry metrics = new TestMetricsRegistry();
        FrameGraphBuilder builder = new FrameGraphBuilder(backend, metrics);

        builder.addPass("PassA", () -> new TestPass("PassA"));
        builder.execute();

        assertThrows(IllegalStateException.class, builder::execute);
        assertThrows(IllegalStateException.class, () -> builder.addPass("PassB", () -> new TestPass("PassB")));
    }

    private static class TestPass implements FramePass {
        private final String name;

        TestPass(String name) {
            this.name = name;
        }

        @Override
        public void record(CommandList commands) {
            if (commands instanceof RecordingCommandList rcl) {
                rcl.recordLog.add("PASS:" + name);
            }
        }

        @Override
        public String name() {
            return name;
        }
    }

    private static class TestRenderBackend implements RenderBackend {
        RecordingCommandList lastSubmitted;

        @Override
        public GpuBuffer createBuffer(long sizeBytes, BufferUsage usage) {
            throw new UnsupportedOperationException();
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
            if (commandList instanceof RecordingCommandList rcl) {
                this.lastSubmitted = rcl;
            }
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

    private static class TestMetricsRegistry implements MetricsRegistry {
        final List<String> recordedGpuPasses = new ArrayList<>();

        @Override
        public void recordQueueDepth(String queueName, int depth) {
        }

        @Override
        public void recordCacheAccess(String cacheName, boolean hit) {
        }

        @Override
        public void recordGpuPassDuration(String passName, long nanos) {
            recordedGpuPasses.add(passName + ":" + nanos);
        }

        @Override
        public void recordCounter(String counterName, long delta) {
        }

        @Override
        public void recordImportStageStatus(ImportStageStatus status) {
        }

        @Override
        public MetricsSnapshot snapshot() {
            return MetricsSnapshot.empty();
        }
    }
}
