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
 * {@code 04_gpu_pipeline.md} (stage 4) and {@code 08_roadmap_milestones.md} (stage 5)
 * for the two paths this now supports.
 * <p>
 * <b>Stage 5:</b> when {@link GpuCapabilities#indirectCountSupported()} is {@code true}
 * (the common case - see that method's javadoc for why), {@link #renderLodFrame} takes
 * the fully GPU-resident path: {@code glMultiDrawArraysIndirectCount} reads the compute
 * shader's atomic draw counter directly out of a GPU buffer when issuing the multi-draw
 * (see {@link #runDrawPassIndirectCount}), so there is no CPU readback and no CPU/GPU
 * sync point in the per-frame path at all.
 * <p>
 * <b>Stage-4 fallback:</b> {@link #runDrawPassWithReadback} preserves the original
 * behavior - the visible-node count is read back to the CPU and passed as an explicit
 * {@code drawcount} to {@code glMultiDrawArraysIndirect}, incurring a CPU/GPU sync
 * point every frame - kept for the (in practice rare) context where {@link
 * GpuCapabilities#indirectCountSupported()} is {@code false}.
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

    // FIX (stage-4 gap, see PROGRESS.md "baseLodDistance vs lodRenderDistance"): these used
    // to be the same value - ExceptionalVisionClient passed the "lodRenderDistance" config
    // straight through as baseLodDistance, so the full-detail level-0 band spanned the
    // *entire* configured render distance instead of a band comparable to vanilla's own
    // chunk render distance, and the top (region-root) level had no far cutoff at all (drew
    // to infinity, ignoring the config completely). Now lodRenderDistance is the single
    // source of truth for "how far LOD should be visible" (see #setLodDistanceSettings,
    // which derives baseLodDistance from it), and both are sent to the shader separately -
    // see quad_cull.comp for how each is actually used.
    private float lodRenderDistance = 2048.0f;
    private float baseLodDistance = 128.0f;
    private int maxLodLevel = 5; // must match LodBuilder.MAX_LEVEL

    // FIX (stage 6 gap): distance below which we don't draw LOD geometry at all, because
    // vanilla's own chunk rendering already covers it (see quad_cull.comp). Kept as a
    // per-frame value rather than baked into baseLodDistance because the vanilla render
    // distance it should track can change at runtime (F3 menu / options), while
    // baseLodDistance/maxLodLevel are config-driven and only change at (re)load. Defaults
    // to 0 (no cutoff) until LodRenderManager supplies a real value every frame.
    private float nearCutoffDistance = 0.0f;

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
        buffers.create(capabilities);
        dummyVertexArray = glGenVertexArrays(); // no vertex attributes are used - geometry is generated from SSBOs by gl_VertexID/gl_DrawID

        initialized = true;
        ExceptionalVision.LOGGER.info(
                "LOD GPU pipeline initialized (OpenGL {}, indirectCount={}, persistentMapping={})",
                capabilities.glVersionString(), capabilities.indirectCountSupported(),
                capabilities.persistentMappingSupported());
    }

    public boolean isActive() {
        return initialized;
    }

    /**
     * Current {@code lodRenderDistance} (blocks) - see {@link #setLodDistanceSettings}.
     * {@link LodRenderManager} needs this to make sure the projection matrix it builds
     * for the LOD pass has a far plane that reaches at least this far; see that class's
     * javadoc on {@code buildLodProjection} for why the vanilla one on its own doesn't.
     */
    public float lodRenderDistance() {
        return lodRenderDistance;
    }

    /**
     * @param lodRenderDistance total distance (blocks) out to which LOD geometry should be
     *                          visible at all - this is exactly {@code ExceptionalVisionConfig
     *                          .LOD_RENDER_DISTANCE}'s meaning, and is now also enforced as a
     *                          hard cutoff in the shader (see quad_cull.comp), not just a band
     *                          boundary that the top level ignored.
     * @param maxLodLevel       see {@code LodBuilder.MAX_LEVEL} - the region-root level.
     */
    public void setLodDistanceSettings(float lodRenderDistance, int maxLodLevel) {
        this.lodRenderDistance = lodRenderDistance;
        this.maxLodLevel = maxLodLevel;
        // Bands double per level starting from baseLodDistance at level 0 (see quad_cull.comp).
        // Regular levels are 0..maxLodLevel-1; the top (region-root) level is maxLodLevel
        // itself and, like every level below it, is exactly as wide as the level before it
        // doubled - i.e. the doubling series continues one more step for the top level rather
        // than stopping dead at the last regular level's upper bound. So the *whole* series,
        // top level included, spans [0, baseLodDistance * 2^maxLodLevel) in total; solving for
        // baseLodDistance so that upper bound lands on lodRenderDistance keeps "how far LOD
        // reaches" tied to the config regardless of maxLodLevel.
        //
        // FIX (found via a real playtest log, not just reasoning - see PROGRESS.md "top-level
        // band collapses to zero width"): dividing by 2^(maxLodLevel-1) instead of 2^maxLodLevel
        // makes the top level's own band [baseLodDistance*2^(maxLodLevel-1), lodRenderDistance)
        // mathematically empty, since baseLodDistance*2^(maxLodLevel-1) == lodRenderDistance
        // exactly in that version - the region-root level would *never* draw, and LOD would
        // stop dead at whatever the last regular level's distance is, with nothing beyond it.
        int levelsBelowTop = Math.max(0, maxLodLevel);
        this.baseLodDistance = lodRenderDistance / (float) (1 << levelsBelowTop);
    }

    /**
     * Called once per frame from {@link LodRenderManager} with the current vanilla
     * render distance (in blocks, plus margin) - see that class for why this can't just
     * be a config value like {@link #setLodDistanceSettings}.
     */
    public void setNearCutoffDistance(float nearCutoffDistance) {
        this.nearCutoffDistance = nearCutoffDistance;
    }

    /**
     * Uploads a fresh cache snapshot (e.g. at world load, or after a batch of regions
     * finished building) to the GPU - incrementally where possible (see
     * {@link LodGpuBuffers#uploadIncremental}'s javadoc for what that means and why
     * it's safe), transparently falling back to a full reupload for the first call and
     * the rare cases that need one.
     */
    public void uploadCache(LodCacheData cacheData, List<Integer> materialColors) {
        if (!initialized) {
            return;
        }
        buffers.uploadIncremental(cacheData, materialColors);
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
            if (!loggedFirstNoOpFrame) {
                loggedFirstNoOpFrame = true;
                ExceptionalVision.LOGGER.info(
                        "[EV-DIAG] renderLodFrame no-op: initialized={}, buffers.nodeCount()={}",
                        initialized, buffers.nodeCount());
            }
            return;
        }

        stateBackup.capture();
        try {
            runCullPass(viewProjectionMatrix, cameraWorldPos);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_COMMAND_BARRIER_BIT);

            if (capabilities.indirectCountSupported()) {
                runDrawPassIndirectCount(viewProjectionMatrix, cameraWorldPos);
            } else {
                runDrawPassWithReadback(viewProjectionMatrix, cameraWorldPos);
            }
        } finally {
            stateBackup.restore();
        }
    }

    /**
     * Stage 5's "full path" (see {@code GpuCapabilities#indirectCountSupported()}): the
     * GPU reads its own draw counter when issuing the multi-draw via {@code
     * glMultiDrawArraysIndirectCount} - no CPU readback, no CPU/GPU sync point. The
     * memory barrier issued by the caller right after the cull dispatch (see {@code
     * GL_COMMAND_BARRIER_BIT} in {@link #renderLodFrame}) already covers both the
     * indirect-command buffer this reads and the counter buffer bound as {@code
     * GL_PARAMETER_BUFFER} below - {@code GL_COMMAND_BARRIER_BIT} specifically applies
     * to data sourced from buffer objects via commands like this one.
     * <p>
     * Always issues the draw (up to {@link LodGpuBuffers#maxDrawCommands()}), even when
     * the actual count could be zero - unlike the readback path, there is no CPU-visible
     * count here to skip the call on, and issuing a multi-draw with a GPU-side count of
     * zero is a defined, cheap no-op rather than an error.
     */
    private void runDrawPassIndirectCount(Matrix4f viewProjectionMatrix, Vector3f cameraWorldPos) {
        if (!loggedFirstDrawCall) {
            loggedFirstDrawCall = true;
            ExceptionalVision.LOGGER.info(
                    "[EV-DIAG] first indirect-count draw pass issued (no CPU readback): nodeCount={}, maxDrawCommands={}",
                    buffers.nodeCount(), buffers.maxDrawCommands());
        }
        prepareDrawState(viewProjectionMatrix, cameraWorldPos);
        buffers.multiDrawIndirectCount(GL_TRIANGLES, buffers.maxDrawCommands());
    }

    /**
     * Stage-4 fallback path, kept for {@link GpuCapabilities#indirectCountSupported()}
     * {@code == false} contexts (see that method's javadoc - in practice this means a
     * denylisted driver rather than a real GL-version gap, since {@code
     * glMultiDrawArraysIndirectCount} is core at the same GL 4.6 floor this whole
     * pipeline already requires). Reads the visible-node count back to the CPU (a
     * CPU/GPU sync point) and passes it explicitly to {@code glMultiDrawArraysIndirect}.
     */
    private void runDrawPassWithReadback(Matrix4f viewProjectionMatrix, Vector3f cameraWorldPos) {
        int drawCount = buffers.readDrawCount(); // CPU/GPU sync point - see class javadoc
        if (!loggedFirstCullResult) {
            loggedFirstCullResult = true;
            ExceptionalVision.LOGGER.info(
                    "[EV-DIAG] first cull result (readback fallback path): nodeCount={}, drawCount={}, baseLodDistance={}, lodRenderDistance={}, nearCutoffDistance={}, maxLodLevel={}",
                    buffers.nodeCount(), drawCount, baseLodDistance, lodRenderDistance, nearCutoffDistance, maxLodLevel);
        }
        if (drawCount > 0) {
            prepareDrawState(viewProjectionMatrix, cameraWorldPos);
            glMultiDrawArraysIndirect(GL_TRIANGLES, 0L, drawCount, 0);
            if (!loggedFirstDrawCall) {
                loggedFirstDrawCall = true;
                ExceptionalVision.LOGGER.info("[EV-DIAG] first draw pass issued (readback fallback): drawCount={}", drawCount);
            }
        } else if (!loggedFirstZeroDraw) {
            loggedFirstZeroDraw = true;
            ExceptionalVision.LOGGER.warn(
                    "[EV-DIAG] cull pass produced drawCount=0 - nothing to draw this frame (nodeCount={})",
                    buffers.nodeCount());
        }
    }

    // DIAGNOSTIC (temporary - see PROGRESS.md "nothing renders" investigation): one-shot
    // flags so a single playtest log tells us where in the per-frame path things stand,
    // without spamming every frame.
    private boolean loggedFirstNoOpFrame = false;
    private boolean loggedFirstCullResult = false;
    private boolean loggedFirstDrawCall = false;
    private boolean loggedFirstZeroDraw = false;

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
        cullProgram.setUniform1f("lodRenderDistance", lodRenderDistance);
        cullProgram.setUniform1f("nearCutoffDistance", nearCutoffDistance);
        cullProgram.setUniform1ui("nodeCount", buffers.nodeCount());
        cullProgram.setUniform1ui("maxLodLevel", maxLodLevel);
        cullProgram.setUniform1ui("maxDrawCommands", buffers.maxDrawCommands());

        int workGroups = (buffers.nodeCount() + CULL_WORKGROUP_SIZE - 1) / CULL_WORKGROUP_SIZE;
        glDispatchCompute(workGroups, 1, 1);
    }

    /**
     * Sets up everything the draw call needs (GL state, program, buffer bindings,
     * uniforms) but does not itself issue the draw - callers ({@link
     * #runDrawPassIndirectCount}, {@link #runDrawPassWithReadback}) each issue their own
     * multi-draw variant right after calling this.
     */
    private void prepareDrawState(Matrix4f viewProjectionMatrix, Vector3f cameraWorldPos) {
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
        drawProgram.setUniform1f("lodRenderDistance", lodRenderDistance);

        // GL_DRAW_INDIRECT_BUFFER is bound (see LodGpuBuffers#bindForDraw) - both multi-draw
        // variants read commands from it via a BYTE OFFSET, not a client-memory pointer.
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