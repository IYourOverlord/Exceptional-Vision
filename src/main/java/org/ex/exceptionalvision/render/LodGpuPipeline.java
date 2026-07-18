package org.ex.exceptionalvision.render;

import org.ex.exceptionalvision.ExceptionalVision;
import org.ex.exceptionalvision.cache.LodCacheData;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.List;

import static org.lwjgl.opengl.GL46C.*;

/**
 * Orchestrates one frame of LOD terrain rendering: frustum/LOD-level culling on the
 * GPU, then an indirect multi-draw of whatever survived. See
 * {@code 04_gpu_pipeline.md}.
 * <p>
 * <b>Stage-4 scope:</b> this uses the <em>explicit CPU-readback</em> path — after the
 * compute dispatch, the visible-node count is read back to the CPU and passed as an
 * explicit {@code drawcount} to {@code glMultiDrawArraysIndirect}. That readback is a
 * CPU/GPU sync point (a potential stall), which is exactly why the roadmap treats the
 * fully GPU-resident {@code glMultiDrawArraysIndirectCount} path (no readback, no
 * stall) as a separate, later stage (stage 5) rather than part of this "basic" one.
 */
public final class LodGpuPipeline implements AutoCloseable {

    private static final String CULL_SHADER = "assets/exceptional_vision/shaders/lod/quad_cull.comp";
    private static final String VERT_SHADER = "assets/exceptional_vision/shaders/lod/lod_quad.vert";
    private static final String FRAG_SHADER = "assets/exceptional_vision/shaders/lod/lod_quad.frag";

    private static final int CULL_WORKGROUP_SIZE = 64; // must match "local_size_x" in quad_cull.comp

    private final GlStateBackup stateBackup = new GlStateBackup();
    private final LodGpuBuffers buffers = new LodGpuBuffers();

    private GpuCapabilities capabilities;
    private ShaderProgram cullProgram;
    private ShaderProgram drawProgram;
    private int dummyVertexArray;
    private boolean initialized;

    private float baseLodDistance = 128.0f;
    private int maxLodLevel = 5; // must match LodBuilder.MAX_LEVEL

    /** Must be called once on the render thread, with a current GL context. Safe to call even if unsupported - just leaves the pipeline disabled. */
    public void init() {
        capabilities = GpuCapabilities.detect();
        if (!capabilities.supported()) {
            ExceptionalVision.LOGGER.warn("LOD GPU pipeline disabled: {}", capabilities.unsupportedReason());
            initialized = false;
            return;
        }

        cullProgram = ShaderProgram.compute(CULL_SHADER);
        drawProgram = ShaderProgram.vertexFragment(VERT_SHADER, FRAG_SHADER);
        buffers.create();
        dummyVertexArray = glGenVertexArrays(); // no vertex attributes are used - geometry is generated from SSBOs by gl_VertexID/gl_DrawID

        initialized = true;
        ExceptionalVision.LOGGER.info("LOD GPU pipeline initialized (OpenGL {})", capabilities.glVersionString());
    }

    public boolean isActive() {
        return initialized;
    }

    public void setLodDistanceSettings(float baseLodDistance, int maxLodLevel) {
        this.baseLodDistance = baseLodDistance;
        this.maxLodLevel = maxLodLevel;
    }

    /** Uploads a fresh cache snapshot (e.g. at world load, or after a batch of regions finished building) to the GPU. */
    public void uploadCache(LodCacheData cacheData, List<Integer> materialColors) {
        if (!initialized) {
            return;
        }
        buffers.upload(cacheData, materialColors);
    }

    /**
     * Stops drawing whatever was last uploaded (e.g. on leaving a world/dimension)
     * without tearing down GL objects — {@link #uploadCache} for the next world will
     * repopulate them from scratch. No-op if {@link #isActive()} is false.
     */
    public void clearCache() {
        if (initialized) {
            buffers.clear();
        }
    }

    /**
     * Runs one frame: cull, read back the visible count, draw. No-op if
     * {@link #isActive()} is false or there's nothing cached yet.
     *
     * @param viewProjectionMatrix combined view * projection matrix for the current camera
     * @param cameraWorldPos       camera position in world space (quads are drawn camera-relative for precision, see lod_quad.vert)
     */
    public void renderLodFrame(Matrix4f viewProjectionMatrix, Vector3f cameraWorldPos) {
        if (!initialized || buffers.nodeCount() == 0) {
            return;
        }

        stateBackup.capture();
        try {
            runCullPass(viewProjectionMatrix, cameraWorldPos);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_COMMAND_BARRIER_BIT);

            int drawCount = buffers.readDrawCount(); // CPU/GPU sync point - see class javadoc
            if (drawCount > 0) {
                runDrawPass(viewProjectionMatrix, cameraWorldPos, drawCount);
            }
        } finally {
            stateBackup.restore();
        }
    }

    private void runCullPass(Matrix4f viewProjectionMatrix, Vector3f cameraWorldPos) {
        buffers.resetDrawCounter();

        cullProgram.use();
        buffers.bindForCompute();

        float[][] planes = FrustumExtractor.extractPlanes(viewProjectionMatrix);
        FloatBuffer planeBuffer = MemoryUtil.memAllocFloat(6 * 4);
        try {
            for (float[] plane : planes) {
                planeBuffer.put(plane);
            }
            planeBuffer.flip();
            cullProgram.setUniform4fv("frustumPlanes", planeBuffer);
        } finally {
            MemoryUtil.memFree(planeBuffer);
        }

        cullProgram.setUniform3f("cameraWorldPos", cameraWorldPos.x(), cameraWorldPos.y(), cameraWorldPos.z());
        cullProgram.setUniform1f("baseLodDistance", baseLodDistance);
        cullProgram.setUniform1ui("nodeCount", buffers.nodeCount());
        cullProgram.setUniform1ui("maxLodLevel", maxLodLevel);
        cullProgram.setUniform1ui("maxDrawCommands", buffers.maxDrawCommands());

        int workGroups = (buffers.nodeCount() + CULL_WORKGROUP_SIZE - 1) / CULL_WORKGROUP_SIZE;
        glDispatchCompute(workGroups, 1, 1);
    }

    private void runDrawPass(Matrix4f viewProjectionMatrix, Vector3f cameraWorldPos, int drawCount) {
        // BUGFIX: GlStateBackup only saves/restores whatever state was already set - it
        // never established the state OUR draw actually needs. At Stage.AFTER_LEVEL, GL
        // state is whatever vanilla's translucent pass left behind (typically
        // glDepthMask(false), blending enabled, and GL_CULL_FACE enabled with a winding
        // that only matches vanilla's own geometry). Our procedurally generated quads
        // don't guarantee one consistent winding across the top-face vs. wall-face
        // branches in lod_quad.vert, so leaving culling on can silently drop faces
        // depending on view direction - a "some terrain missing" bug that's easy to
        // miss visually. Set exactly what this opaque, single-sided-agnostic pass
        // needs; stateBackup.restore() (called from the caller's finally block) puts
        // everything back afterward regardless of what we change here.
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        glDepthMask(true);
        glDisable(GL_BLEND);
        glDisable(GL_CULL_FACE);

        glBindVertexArray(dummyVertexArray);
        drawProgram.use();
        buffers.bindForDraw();

        FloatBuffer matrixBuffer = MemoryUtil.memAllocFloat(16);
        try {
            viewProjectionMatrix.get(matrixBuffer);
            drawProgram.setUniformMatrix4f("viewProjectionMatrix", matrixBuffer);
        } finally {
            MemoryUtil.memFree(matrixBuffer);
        }
        drawProgram.setUniform3f("cameraWorldPos", cameraWorldPos.x(), cameraWorldPos.y(), cameraWorldPos.z());

        // GL_DRAW_INDIRECT_BUFFER is bound (see LodGpuBuffers#bindForDraw) - the "indirect"
        // argument here is a BYTE OFFSET into that buffer, not a client-memory pointer.
        glMultiDrawArraysIndirect(GL_TRIANGLES, 0L, drawCount, 0);
    }

    @Override
    public void close() {
        if (!initialized) {
            return;
        }
        cullProgram.close();
        drawProgram.close();
        buffers.close();
        glDeleteVertexArrays(dummyVertexArray);
        initialized = false;
    }
}
