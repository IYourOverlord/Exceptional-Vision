package dev.ev.neoforge;

import dev.ev.api.SectionPos;
import dev.ev.api.gpu.BufferUsage;
import dev.ev.api.gpu.CommandList;
import dev.ev.api.gpu.GpuBuffer;
import dev.ev.api.gpu.GraphicsPipeline;
import dev.ev.api.gpu.PipelineLayout;
import dev.ev.api.gpu.RenderBackend;
import dev.ev.api.gpu.ShaderSource;

import org.lwjgl.system.MemoryUtil;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Minimal MVP draw path for {@code far-lod-pass}: renders each visible section as a
 * unit cube (vertex-pulling, no VAO/VBO — vertices are procedurally generated in the
 * vertex shader from {@code gl_VertexID}/{@code gl_InstanceID}), scaled/positioned from a
 * per-frame Node storage buffer laid out per {@code node_buffer_aos.glsl}.
 * <p>
 * Purpose: prove the draw path (shader compile → buffer bind → indirect draw call)
 * actually produces visible geometry on screen. This is intentionally crude — no real
 * meshlet/quad geometry from the meshing pipeline is consumed here yet, only each
 * visible section's bounding box. Replacing this with real per-section meshlet
 * rendering is future work, out of scope for this change.
 * <p>
 * Lazily compiles its {@link GraphicsPipeline} and allocates its GPU buffers on first
 * use (requires an active GL context, unavailable at {@link EVInstance} construction
 * time), then reuses them across frames; buffers are resized (recreated) only if a
 * frame needs more capacity than currently allocated.
 */


final class FarLodPassRenderer implements AutoCloseable {

    /** Must match {@code node_buffer_aos.glsl}'s {@code Node} struct exactly. */
    private static final int BYTES_PER_NODE = 32;
    /** 6 faces * 2 triangles * 3 vertices, generated procedurally in the vertex shader. */
    private static final int VERTICES_PER_CUBE = 36;
    /** {@code DrawArraysIndirectCommand}: count, instanceCount, first, baseInstance (4x u32). */
    private static final int BYTES_PER_INDIRECT_DRAW = 16;

    private static final String VERTEX_SHADER = """
            #version 450

            struct Node {
                vec4 bounds;      // xyz = center, w = radius
                uint flags;
                uint materialRef;
                uint streamState;
                uint _padding;
            };

            layout(std430, binding = 0) readonly buffer NodeBufferAoS {
                Node nodes[];
            };

            layout(location = 0) uniform mat4 uViewProj;

            out vec3 vColor;

            // Unit cube corners, reused via gl_VertexID % 8 through a fixed index table below.
            const vec3 CUBE_CORNERS[8] = vec3[8](
                vec3(-1.0, -1.0, -1.0), vec3( 1.0, -1.0, -1.0),
                vec3( 1.0,  1.0, -1.0), vec3(-1.0,  1.0, -1.0),
                vec3(-1.0, -1.0,  1.0), vec3( 1.0, -1.0,  1.0),
                vec3( 1.0,  1.0,  1.0), vec3(-1.0,  1.0,  1.0)
            );

            // 12 triangles (36 indices) covering all 6 faces of the cube, CCW when viewed
            // from outside.
            const int CUBE_INDICES[36] = int[36](
                0, 1, 2,  2, 3, 0, // -Z
                4, 6, 5,  6, 4, 7, // +Z
                0, 4, 5,  5, 1, 0, // -Y
                3, 2, 6,  6, 7, 3, // +Y
                1, 5, 6,  6, 2, 1, // +X
                4, 0, 3,  3, 7, 4  // -X
            );

            void main() {
                Node node = nodes[gl_InstanceID];
                int cornerIndex = CUBE_INDICES[gl_VertexID % 36];
                vec3 localCorner = CUBE_CORNERS[cornerIndex] * node.bounds.w;
                vec3 worldPos = node.bounds.xyz + localCorner;
                gl_Position = uViewProj * vec4(worldPos, 1.0);

                // Cheap per-instance color so distinct sections are visually distinguishable
                // during MVP verification; not tied to material yet.
                float hue = float(gl_InstanceID % 8) / 8.0;
                vColor = vec3(hue, 1.0 - hue, 0.5);
            }
            """;

    private static final String FRAGMENT_SHADER = """
            #version 450

            in vec3 vColor;
            out vec4 fragColor;

            void main() {
                fragColor = vec4(vColor, 1.0);
            }
            """;

    private final RenderBackend backend;

    private GraphicsPipeline pipeline;
    private GpuBuffer nodeBuffer;
    private GpuBuffer indirectBuffer;
    private GpuBuffer stagingUpload;
    private int nodeCapacity = 0;

    FarLodPassRenderer(RenderBackend backend) {
        this.backend = backend;
    }

    /**
     * Records draw commands for the given visible sections into {@code commands}.
     * No-op (and no allocation) if {@code visibleSections} is empty.
     *
     * @param commands           command list to record into
     * @param visibleSections    sections to draw this frame
     * @param boundingSphereFn   maps a section to [centerX, centerY, centerZ, radius]
     * @param viewProjColumnMajor 16-element column-major view-projection matrix
     */
    void record(CommandList commands, List<SectionPos> visibleSections,
                Function<SectionPos, float[]> boundingSphereFn, float[] viewProjColumnMajor) {
        int count = visibleSections.size();
        if (count == 0) {
            return;
        }

        ensureCapacity(count);
        uploadNodes(commands, visibleSections, boundingSphereFn);
        uploadIndirectDrawCommand(commands, count);

        pipeline.bindBuffer("NodeBufferAoS", nodeBuffer);
        // uViewProj is set as a plain GL uniform (location 0), not a binding-name lookup —
        // GraphicsPipeline's bindBuffer/bindTexture contract has no uniform-matrix method,
        // so this one value is applied directly via the underlying GL program before draw.
        ((dev.ev.gpu.gl.GLGraphicsPipeline) pipeline).useProgramAndSetViewProj(viewProjColumnMajor);

        commands.draw(pipeline, indirectBuffer, 0L, 1);
    }

    private void ensureCapacity(int requiredNodeCapacity) {
        if (pipeline == null) {
            PipelineLayout layout = new PipelineLayout(Map.of("NodeBufferAoS", 0));
            pipeline = backend.compileGraphicsPipeline(
                    new ShaderSource("far_lod_cube.vert", VERTEX_SHADER, Map.of()),
                    new ShaderSource("far_lod_cube.frag", FRAGMENT_SHADER, Map.of()),
                    layout);
        }
        if (indirectBuffer == null) {
            indirectBuffer = backend.createBuffer(BYTES_PER_INDIRECT_DRAW, BufferUsage.STORAGE);
        }
        if (nodeBuffer == null || requiredNodeCapacity > nodeCapacity) {
            if (nodeBuffer != null) {
                nodeBuffer.free();
            }
            if (stagingUpload != null) {
                stagingUpload.free();
            }
            // Small growth margin to avoid reallocating every frame the visible count changes by one.
            nodeCapacity = Math.max(requiredNodeCapacity, nodeCapacity * 2);
            long totalBytes = (long) nodeCapacity * BYTES_PER_NODE;
            nodeBuffer = backend.createBuffer(totalBytes, BufferUsage.STORAGE);
            stagingUpload = backend.createBuffer(totalBytes, BufferUsage.STAGING_UPLOAD);
        }
    }

    private void uploadNodes(CommandList commands, List<SectionPos> visibleSections,
                             Function<SectionPos, float[]> boundingSphereFn) {
        long address = stagingUpload.mappedAddress();
        for (int i = 0; i < visibleSections.size(); i++) {
            float[] sphere = boundingSphereFn.apply(visibleSections.get(i));
            long nodeOffset = address + (long) i * BYTES_PER_NODE;
            MemoryUtil.memPutFloat(nodeOffset, sphere[0]);
            MemoryUtil.memPutFloat(nodeOffset + 4, sphere[1]);
            MemoryUtil.memPutFloat(nodeOffset + 8, sphere[2]);
            MemoryUtil.memPutFloat(nodeOffset + 12, sphere[3]);
            MemoryUtil.memPutInt(nodeOffset + 16, 1); // flags: NODE_FLAG_RESIDENT
            MemoryUtil.memPutInt(nodeOffset + 20, 0); // materialRef (unused by this MVP shader)
            MemoryUtil.memPutInt(nodeOffset + 24, 2); // streamState: STREAM_STATE_RESIDENT
            MemoryUtil.memPutInt(nodeOffset + 28, 0); // _padding
        }
        long sizeBytes = (long) visibleSections.size() * BYTES_PER_NODE;
        commands.uploadToBuffer(nodeBuffer, 0L, address, sizeBytes);
    }

    private void uploadIndirectDrawCommand(CommandList commands, int instanceCount) {
        long scratch = MemoryUtil.nmemAlloc(BYTES_PER_INDIRECT_DRAW);
        try {
            MemoryUtil.memPutInt(scratch, VERTICES_PER_CUBE);   // count (vertices per instance)
            MemoryUtil.memPutInt(scratch + 4, instanceCount);   // instanceCount
            MemoryUtil.memPutInt(scratch + 8, 0);                // first
            MemoryUtil.memPutInt(scratch + 12, 0);               // baseInstance
            commands.uploadToBuffer(indirectBuffer, 0L, scratch, BYTES_PER_INDIRECT_DRAW);
        } finally {
            MemoryUtil.nmemFree(scratch);
        }
    }

    @Override
    public void close() {
        if (pipeline != null) {
            pipeline.free();
            pipeline = null;
        }
        if (nodeBuffer != null) {
            nodeBuffer.free();
            nodeBuffer = null;
        }
        if (indirectBuffer != null) {
            indirectBuffer.free();
            indirectBuffer = null;
        }
        if (stagingUpload != null) {
            stagingUpload.free();
            stagingUpload = null;
        }
    }
}