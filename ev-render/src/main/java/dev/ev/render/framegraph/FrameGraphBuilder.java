package dev.ev.render.framegraph;

import dev.ev.api.gpu.BarrierScope;
import dev.ev.api.gpu.CommandList;
import dev.ev.api.gpu.RenderBackend;
import dev.ev.api.metrics.MetricsRegistry;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Collects {@link FramePass} declarations with their read/write {@link FrameResource} dependencies,
 * topologically orders them, inserts memory barriers between passes with write-then-read or
 * write-then-write dependencies, and executes them via {@link RenderBackend#submit}.
 * <p>
 * <b>Barrier Insertion Policy:</b>
 * A barrier of scope {@link BarrierScope#ALL} (conservative policy for MVP) is inserted
 * directly before recording a pass {@code P} if there is any previously executed pass {@code Q}
 * that wrote a resource that {@code P} reads or writes. If multiple prior passes {@code Q1, Q2...}
 * wrote resources consumed by {@code P}, a single barrier placed immediately before {@code P}
 * covers all of them.
 * <p>
 * <b>Reuse Model:</b>
 * A {@code FrameGraphBuilder} instance is designed to be instantiated per frame (single-use per execute).
 * Calling {@link #execute()} marks the builder as executed. Subsequent calls to {@link #execute()}
 * or {@link #addPass} on the same instance will throw {@link IllegalStateException}.
 * Callers (such as the render hook in ticket 27) build a fresh {@code FrameGraphBuilder} instance
 * each frame, declare passes via {@link #addPass}, and invoke {@link #execute()}.
 */
public final class FrameGraphBuilder {

    private final RenderBackend backend;
    private final MetricsRegistry metrics;
    private final List<PassNode> passes = new ArrayList<>();
    private boolean executed = false;

    /**
     * Constructs a FrameGraphBuilder.
     *
     * @param backend render backend used to submit commands, non-null
     * @param metrics metrics registry used to record GPU pass durations, non-null
     */
    public FrameGraphBuilder(RenderBackend backend, MetricsRegistry metrics) {
        this.backend = Objects.requireNonNull(backend, "backend cannot be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics cannot be null");
    }

    /**
     * Adds a pass declaration to the frame graph.
     *
     * @param name human-readable name of the pass, non-null
     * @param passFactory supplier for creating the FramePass instance at execution time, non-null
     * @return a {@link PassBuilder} handle to declare reads and writes
     * @throws IllegalStateException if this builder has already been executed
     */
    public PassBuilder addPass(String name, Supplier<FramePass> passFactory) {
        if (executed) {
            throw new IllegalStateException("FrameGraphBuilder cannot be modified after execute()");
        }
        Objects.requireNonNull(name, "name cannot be null");
        Objects.requireNonNull(passFactory, "passFactory cannot be null");

        int index = passes.size();
        PassNode node = new PassNode(index, name, passFactory);
        passes.add(node);
        return node;
    }

    /**
     * Validates the graph for cycle detection, computes topological execution order using Kahn's algorithm,
     * inserts necessary memory barriers, records commands via a single pass, and submits them via {@link RenderBackend#submit}.
     *
     * @throws IllegalStateException if a cyclic dependency is detected or if execute() was already called on this builder instance
     */
    public void execute() {
        if (executed) {
            throw new IllegalStateException("FrameGraphBuilder execute() can only be called once per instance");
        }
        executed = true;

        if (passes.isEmpty()) {
            return;
        }

        // 1. Build dependency graph based on resources
        // For each resource, track which passes write to it.
        Map<FrameResource, List<PassNode>> writersByResource = new HashMap<>();
        for (PassNode pass : passes) {
            for (FrameResource res : pass.writtenResources) {
                writersByResource.computeIfAbsent(res, r -> new ArrayList<>()).add(pass);
            }
        }

        // Edges: u -> v means u must execute before v
        Map<PassNode, Set<PassNode>> adj = new LinkedHashMap<>();
        Map<PassNode, Integer> inDegree = new HashMap<>();
        for (PassNode pass : passes) {
            adj.put(pass, new LinkedHashSet<>());
            inDegree.put(pass, 0);
        }

        for (PassNode readerOrWriter : passes) {
            Set<FrameResource> accessed = new HashSet<>();
            accessed.addAll(readerOrWriter.readResources);
            accessed.addAll(readerOrWriter.writtenResources);

            for (FrameResource res : accessed) {
                List<PassNode> writers = writersByResource.getOrDefault(res, Collections.emptyList());
                for (PassNode writer : writers) {
                    if (writer != readerOrWriter) {
                        // writer must run before readerOrWriter
                        if (adj.get(writer).add(readerOrWriter)) {
                            inDegree.put(readerOrWriter, inDegree.get(readerOrWriter) + 1);
                        }
                    }
                }
            }
        }

        // 2. Topological Sort via Kahn's Algorithm
        // To preserve insertion order when multiple nodes have inDegree 0, use insertion order priority.
        List<PassNode> sortedOrder = new ArrayList<>();
        Deque<PassNode> readyQueue = new ArrayDeque<>();

        for (PassNode node : passes) {
            if (inDegree.get(node) == 0) {
                readyQueue.add(node);
            }
        }

        while (!readyQueue.isEmpty()) {
            PassNode curr = readyQueue.poll();
            sortedOrder.add(curr);

            for (PassNode neighbor : adj.get(curr)) {
                int newDeg = inDegree.get(neighbor) - 1;
                inDegree.put(neighbor, newDeg);
                if (newDeg == 0) {
                    readyQueue.add(neighbor);
                }
            }
        }

        if (sortedOrder.size() < passes.size()) {
            // Find cycle nodes for informative exception
            List<String> cycleNodeNames = new ArrayList<>();
            for (PassNode node : passes) {
                if (inDegree.get(node) > 0) {
                    cycleNodeNames.add(node.name);
                }
            }
            throw new IllegalStateException("Cyclic dependency detected in FrameGraph involving passes: " + cycleNodeNames);
        }

        // 3. Determine barrier placement
        // Barrier before pass P if some earlier executed pass Q wrote a resource that P reads or writes.
        Set<PassNode> requiresBarrierBefore = new HashSet<>();
        Set<FrameResource> writtenSoFar = new HashSet<>();

        for (PassNode p : sortedOrder) {
            boolean needsBarrier = false;

            for (FrameResource r : p.readResources) {
                if (writtenSoFar.contains(r)) {
                    needsBarrier = true;
                    break;
                }
            }
            if (!needsBarrier) {
                for (FrameResource r : p.writtenResources) {
                    if (writtenSoFar.contains(r)) {
                        needsBarrier = true;
                        break;
                    }
                }
            }

            if (needsBarrier) {
                requiresBarrierBefore.add(p);
            }

            writtenSoFar.addAll(p.writtenResources);
        }

        // 4. Record and Submit Commands
        RecordingCommandList cmdList = new RecordingCommandList();

        for (PassNode node : sortedOrder) {
            if (requiresBarrierBefore.contains(node)) {
                cmdList.memoryBarrier(BarrierScope.ALL);
            }

            FramePass passInstance = node.passFactory.get();
            long startNanos = System.nanoTime();
            passInstance.record(cmdList);
            long elapsedNanos = System.nanoTime() - startNanos;

            metrics.recordGpuPassDuration(passInstance.name(), elapsedNanos);
        }

        backend.submit(cmdList);
    }

    private static final class PassNode implements PassBuilder {
        final int index;
        final String name;
        final Supplier<FramePass> passFactory;
        final Set<FrameResource> readResources = new LinkedHashSet<>();
        final Set<FrameResource> writtenResources = new LinkedHashSet<>();
        private FrameResource primaryOutput;

        PassNode(int index, String name, Supplier<FramePass> passFactory) {
            this.index = index;
            this.name = name;
            this.passFactory = passFactory;
        }

        @Override
        public PassBuilder reads(FrameResource... resources) {
            if (resources != null) {
                for (FrameResource r : resources) {
                    if (r != null) {
                        readResources.add(r);
                    }
                }
            }
            return this;
        }

        @Override
        public PassBuilder writes(FrameResource... resources) {
            if (resources != null) {
                for (FrameResource r : resources) {
                    if (r != null) {
                        writtenResources.add(r);
                    }
                }
            }
            return this;
        }

        @Override
        public FrameResource output() {
            if (primaryOutput == null) {
                primaryOutput = new FrameResource(name + "-output");
                writtenResources.add(primaryOutput);
            }
            return primaryOutput;
        }

        @Override
        public String toString() {
            return "PassNode{" + name + ", idx=" + index + '}';
        }
    }
}
