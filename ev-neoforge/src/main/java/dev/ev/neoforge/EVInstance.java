package dev.ev.neoforge;

import dev.ev.api.SectionPos;
import dev.ev.api.gpu.RenderBackend;
import dev.ev.api.metrics.MetricsRegistry;
import dev.ev.api.meshing.MeshletBatch;
import dev.ev.gpu.gl.GLRenderBackend;
import dev.ev.meshing.queue.MeshTaskQueue;
import dev.ev.neoforge.adapter.BlockPalette;
import dev.ev.neoforge.config.EVConfig;
import dev.ev.neoforge.config.EVConfigLoader;
import dev.ev.neoforge.FarLodPassRenderer;
import dev.ev.neoforge.metrics.DefaultMetricsRegistry;
import dev.ev.render.culling.FrustumTester;
import dev.ev.render.culling.SimpleTraversal;
import dev.ev.render.dirty.DirtySectionTracker;
import dev.ev.render.dirty.GeometryChangeDeduplicator;
import dev.ev.render.framegraph.FrameGraphBuilder;
import dev.ev.render.framegraph.FramePass;
import dev.ev.render.scheduling.MeshSchedulingCoordinator;
import dev.ev.render.scheduling.MeshTask;
import dev.ev.render.scheduling.MeshWorkerPool;
import dev.ev.render.scheduling.MeshingPipelineRunner;
import dev.ev.render.scheduling.SectionGeometryMap;
import dev.ev.render.scheduling.SectionGenerationPolicy;
import dev.ev.render.scheduling.SimpleMeshingContext;
import dev.ev.storage.cache.InMemorySectionLoader;
import dev.ev.storage.cache.LruEvictionPolicy;
import dev.ev.storage.cache.SectionCache;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL45;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * Owns the lifecycle of EV's rendering subsystems for one active world/dimension
 * context: the RenderBackend (GL), the meshing pipeline, traversal, and far-LOD
 * orchestration.
 * <p>
 * Exactly one EVInstance should exist per loaded client world at a time — created
 * on world/dimension load, torn down on unload, never reused across worlds.
 */
public final class EVInstance implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(EVInstance.class);

    private boolean glErrorLoggedOnce = false;

    private final RenderBackend backend;
    private final MetricsRegistry metricsRegistry;
    private final EVConfig config;

    // Storage
    private final SectionCache sectionCache;
    private final BlockPalette blockPalette;
    private final SectionGenerationPolicy generationPolicy;

    // Meshing pipeline
    private final MeshTaskQueue<MeshTask> meshTaskQueue;
    private final MeshWorkerPool meshWorkerPool;
    private final MeshingPipelineRunner pipelineRunner;
    private final GeometryChangeDeduplicator deduplicator;

    // Dirty tracking & scheduling
    private final DirtySectionTracker dirtyTracker;
    private final MeshSchedulingCoordinator schedulingCoordinator;

    // Render-time state
    private final SimpleTraversal traversal;
    private final SectionGeometryMap geometryMap;
    private final FarLodPassRenderer FarLodPassRenderer;

    private boolean closed = false;
    private volatile boolean debugOverlayEnabled = false;

    private EVInstance(RenderBackend backend, EVConfig config) {
        this.backend = Objects.requireNonNull(backend);
        this.config = Objects.requireNonNull(config);
        this.metricsRegistry = new DefaultMetricsRegistry();

        // Storage subsystem
        this.sectionCache = new SectionCache(
                1, // shardCountPowerOfTwo ignored in MVP impl
                new InMemorySectionLoader(),
                new LruEvictionPolicy(),
                metricsRegistry
        );
        this.blockPalette = new BlockPalette();
        this.generationPolicy = new SectionGenerationPolicy();

        // Meshing pipeline
        this.meshTaskQueue = new MeshTaskQueue<>(metricsRegistry);
        this.pipelineRunner = new MeshingPipelineRunner();
        this.deduplicator = new GeometryChangeDeduplicator();
        this.meshWorkerPool = new MeshWorkerPool(
                config.workerThreadCount(),
                meshTaskQueue,
                sectionCache,
                pipelineRunner,
                deduplicator,
                SimpleMeshingContext.INSTANCE,
                metricsRegistry
        );

        // Dirty tracking & scheduling
        this.dirtyTracker = new DirtySectionTracker();
        this.schedulingCoordinator = new MeshSchedulingCoordinator(meshTaskQueue, dirtyTracker);

        // Render-time state
        this.traversal = new SimpleTraversal(metricsRegistry);
        this.geometryMap = new SectionGeometryMap();
        this.FarLodPassRenderer = new FarLodPassRenderer(backend);
    }

    /**
     * Bootstraps a new EVInstance with a fresh GLRenderBackend.
     * Must be called on the client thread with an active OpenGL context.
     *
     * @return newly initialized EVInstance
     */
    public static EVInstance bootstrap() {
        EVConfigLoader.reloadFromSpec();
        EVConfig config = EVConfigLoader.current();
        GLRenderBackend backend = new GLRenderBackend();
        return new EVInstance(backend, config);
    }

    /** Returns the MetricsRegistry for this instance, used by /ev debug commands. */
    public MetricsRegistry metrics() {
        return metricsRegistry;
    }

    /** Returns the DirtySectionTracker, called by block-change listener in EV.java. */
    public DirtySectionTracker dirtyTracker() {
        return dirtyTracker;
    }

    /**
     * Returns the SectionCache, used by the chunk-load listener in EV.java to acquire
     * and populate level-0 section handles with real block data (see the "no full
     * voxelization exists yet" gap documented on {@link InMemorySectionLoader}
     * and {@link SectionGenerationPolicy}: this cache only ever
     * creates empty handles on its own, something else has to fill them).
     */
    public SectionCache sectionCache() {
        return sectionCache;
    }

    /** Returns the SectionGeometryMap, used by the chunk-unload listener in EV.java
     * to evict geometry for sections whose backing chunk data left the client's
     * loaded radius (see {@code EV.onChunkUnload}) — without this, geometry for
     * sections outside the current render distance would remain resident forever
     * and keep being tested for visibility every frame regardless of distance to
     * the player. */
    public SectionGeometryMap geometryMap() {
        return geometryMap;
    }

    /** Returns the BlockPalette for Minecraft adapters. */
    public BlockPalette blockPalette() {
        return blockPalette;
    }

    /** Whether the debug overlay is currently enabled. */
    public boolean isDebugOverlayEnabled() {
        return debugOverlayEnabled;
    }

    /** Sets the debug overlay state. */
    public void setDebugOverlayEnabled(boolean enabled) {
        this.debugOverlayEnabled = enabled;
    }

    /**
     * Circumscribes the square vanilla chunk loading zone.
     * Multiplying by sqrt(2) ensures the cutoff radius covers corners of the square.
     */
    public static float calculateNearCutoffBlocks(int renderDistanceChunks, float marginChunks) {
        return renderDistanceChunks * 16.0f * (float) Math.sqrt(2.0) + marginChunks * 16.0f;
    }

    /**
     * Keeps only sections whose bounding sphere lies within {@code [minDistanceBlocks, maxDistanceBlocks]}
     * of the camera.
     * <p>
     * The lower bound exists because EV's far-LOD pass is only meant to cover the area
     * <em>beyond</em> vanilla's own chunk rendering — see {@link #calculateNearCutoffBlocks}.
     * Without it, every loaded section (including ones vanilla is already rendering right next
     * to the player) is a far-LOD draw candidate, which is exactly the "LOD cubes covering my
     * own render distance, right in front of my face" symptom: the unit-cube placeholder
     * geometry (see {@link FarLodPassRenderer}) is large relative to a single section, so at
     * close range it fills the screen instead of appearing only past the horizon.
     * <p>
     * Exists as a standalone static/pure function (no field access, only the passed-in
     * {@code sectionBounds} function) so this filtering logic is unit-testable without a GL
     * context or a live {@link Minecraft} instance.
     *
     * @param loadedSections    every section currently resident, non-null
     * @param cameraX           camera world X
     * @param cameraY           camera world Y
     * @param cameraZ           camera world Z
     * @param minDistanceBlocks inclusive lower bound; sections closer than this are skipped
     *                          (vanilla's own rendering already covers them)
     * @param maxDistanceBlocks inclusive upper bound; sections farther than this are skipped
     * @param sectionBounds     world-space bounding sphere [x, y, z, radius] for a SectionPos
     * @return sections within budget, in the same relative order as {@code loadedSections}
     */
    static List<SectionPos> filterByDistance(
            List<SectionPos> loadedSections,
            float cameraX, float cameraY, float cameraZ,
            float minDistanceBlocks,
            float maxDistanceBlocks,
            java.util.function.Function<SectionPos, float[]> sectionBounds
    ) {
        List<SectionPos> nearbySections = new java.util.ArrayList<>(loadedSections.size());
        for (SectionPos pos : loadedSections) {
            float[] bounds = sectionBounds.apply(pos);
            float dx = bounds[0] - cameraX;
            float dy = bounds[1] - cameraY;
            float dz = bounds[2] - cameraZ;
            float distSq = dx * dx + dy * dy + dz * dz;
            float outerCutoff = maxDistanceBlocks + bounds[3];
            // Inner cutoff: subtract the section's own bounding radius (bounds[3]) rather than
            // add it, so a section is only excluded once it is ENTIRELY inside the vanilla zone
            // — a section straddling the boundary still gets drawn, avoiding a gap. Clamped to
            // 0 so a tiny/negative minDistanceBlocks (e.g. render distance 0) never excludes
            // everything.
            float innerCutoff = Math.max(0f, minDistanceBlocks - bounds[3]);
            if (distSq <= outerCutoff * outerCutoff && distSq >= innerCutoff * innerCutoff) {
                nearbySections.add(pos);
            }
        }
        return nearbySections;
    }

    /**
     * Executes far-LOD rendering for the current frame.
     * Called during {@link RenderLevelStageEvent} (AFTER_SOLID_BLOCKS).
     * <p>
     * Full MVP pipeline: drain completed meshes → schedule dirty sections →
     * build FrustumTester → SimpleTraversal → record pass for visible sections.
     * <p>
     * <b>Sodium / Embeddium Compatibility:</b> restores OpenGL state before returning.
     */
    public void renderFarLod(RenderLevelStageEvent event) {
        if (closed) {
            return;
        }

        try {
            // 1. Drain completed mesh results from worker pool → geometry map
            drainCompletedMeshes();

            // 2. Get camera info
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;

            float cameraX = (float) mc.player.getX();
            float cameraY = (float) mc.player.getEyeY();
            float cameraZ = (float) mc.player.getZ();

            // Camera forward vector from player look angles
            float yawRad = (float) Math.toRadians(mc.player.getYRot());
            float pitchRad = (float) Math.toRadians(mc.player.getXRot());
            float viewDirX = (float) (-Math.sin(yawRad) * Math.cos(pitchRad));
            float viewDirY = (float) (-Math.sin(pitchRad));
            float viewDirZ = (float) (Math.cos(yawRad) * Math.cos(pitchRad));

            // 3. Schedule dirty sections for meshing
            schedulingCoordinator.drainDirtyAndSchedule(
                    cameraX, cameraY, cameraZ, viewDirX, viewDirY, viewDirZ
            );

            // 4. Build frustum from view-projection matrix.
            //
            // IMPORTANT — camera-relative coordinate space:
            // Vanilla/NeoForge's terrain render matrix ("modelView") is camera-relative:
            // it does NOT include a translation by the camera's absolute world position
            // — the engine already renders everything relative to the camera to avoid
            // float-precision blowups far from the origin. sectionBoundingSphere() and
            // the far-LOD cube shader, however, work in absolute world-space blocks
            // (SectionPos.minBlockX()/Y()/Z()). Comparing absolute-world bounding
            // spheres against a camera-relative frustum (or feeding absolute-world
            // positions into the camera-relative MVP in the shader) is a
            // coordinate-space mismatch: harmless near the origin (the two spaces
            // nearly coincide there) but catastrophic far away — at world Z ~200000
            // the mismatch is tens of thousands of blocks, so every section fails the
            // frustum test (visible-sections: 0) or, when it does pass, gets
            // transformed to garbage screen positions.
            //
            // getModelViewMatrix() vs getPoseStack() — this bit otherwise reintroduces
            // the same class of bug in a different guise: RenderLevelStageEvent exposes
            // BOTH event.getPoseStack() AND event.getModelViewMatrix() as separate,
            // independently-constructed values (see the event's constructor). An
            // earlier version of this fix used event.getPoseStack().last().pose(),
            // which compiles fine and is non-null on AFTER_SOLID_BLOCKS, but is NOT
            // guaranteed to be the actual camera-rotation matrix the vanilla terrain
            // pass renders with on every NeoForge version/stage — poseStack here can be
            // an incidental, possibly-identity stack rather than the real view
            // transform. The visible symptom of using the wrong one was exactly what
            // was reported: geometry appearing to be "glued" to the screen and sliding
            // in lock-step with camera pitch/yaw — i.e. an MVP with no real camera
            // rotation applied, so only the projection matrix (a fixed, camera-facing
            // transform) was moving the geometry on screen as the view direction
            // changed. event.getModelViewMatrix() is the value NeoForge's own
            // LevelRenderer patch constructs and passes into this exact event
            // specifically to represent "the model-view matrix used for rendering" —
            // that is the authoritative one to multiply against the projection matrix.
            Matrix4f projMatrix = event.getProjectionMatrix();
            Matrix4f modelViewMatrix = event.getModelViewMatrix();
            Matrix4f mvp = new Matrix4f(projMatrix).mul(modelViewMatrix);
            float[] planes = extractFrustumPlanes(mvp);
            FrustumTester frustum = new FrustumTester(planes);

            // 5. Traverse visible sections
            // Distance-based cutoff FIRST, before the frustum test: FrustumTester only
            // checks whether a section's bounding sphere intersects the current view
            // frustum's 6 planes (derived from the vanilla projection matrix) — it does
            // NOT check distance from the player on its own. Without this filter, any
            // section ever loaded into geometryMap this session (which is never evicted
            // except on world unload — see EV.onChunkUnload for the eviction-on-unload
            // half of this fix) stays a traversal/render candidate forever: after a
            // teleport far away (e.g. /kill respawn), old far-away sections can still
            // geometrically intersect the new frustum by direction alone and get drawn
            // as huge, wrongly-placed unit cubes — the "flickering wall of textures"
            // artifact. maxRenderDistanceBlocks is the config's own stated far-LOD
            // distance budget, so reusing it here (rather than inventing a second
            // distance knob) keeps a single source of truth for how far EV is supposed
            // to draw.
            float maxDistanceBlocks = config.maxRenderDistanceBlocks();
            // marginChunks = 0: sections are excluded exactly at vanilla's own render-distance
            // boundary, with no extra buffer — see calculateNearCutoffBlocks's Javadoc for why
            // sqrt(2) alone (covering the square loading zone's diagonal) is used as the margin.
            //
            // mc.options.renderDistance().get() — confirmed compiling and passing tests
            // against the real 1.21.1/NeoForge 21.1.235 mappings (BUILD SUCCESSFUL, all
            // tests green). Compilation alone does not prove the runtime value is correct
            // in-game (e.g. that it reflects live changes if the player edits render
            // distance mid-session) — that still needs the in-game check below.
            int vanillaRenderDistanceChunks = mc.options.renderDistance().get();
            float minDistanceBlocks = calculateNearCutoffBlocks(vanillaRenderDistanceChunks, 0.0f);
            List<SectionPos> loadedSections = geometryMap.loadedSections();
            List<SectionPos> nearbySections = filterByDistance(
                    loadedSections, cameraX, cameraY, cameraZ,
                    minDistanceBlocks, maxDistanceBlocks, this::sectionBoundingSphere
            );
            // Camera-relative bounding spheres for both the frustum test and the GPU
            // upload below — see the coordinate-space note above. mvp already expects
            // camera-relative (rotation-applied) input, so subtracting cameraPos here
            // (world axes, no rotation needed — mvp's view half supplies the rotation)
            // makes every downstream comparison and shader transform consistent,
            // matching the old absolute-world behavior near the origin exactly and
            // fixing it everywhere else.
            final float camX = cameraX, camY = cameraY, camZ = cameraZ;
            java.util.function.Function<SectionPos, float[]> cameraRelativeBounds =
                    pos -> toCameraRelative(sectionBoundingSphere(pos), camX, camY, camZ);
            List<SectionPos> visibleSections = traversal.computeVisible(
                    nearbySections, frustum, cameraRelativeBounds
            );

            // 6. Execute frame graph with visible sections.
            // Reuse the same mvp (= proj * camera-relative rotation-only view) built
            // above for frustum-plane extraction. Since every node's bounds are now
            // camera-relative too (cameraRelativeBounds), mvp * vec4(cameraRelativeBounds, 1)
            // in the shader is equivalent to the original proj * view * worldPos —
            // just computed with small, camera-local numbers instead of huge
            // absolute-world ones, which is what actually fixes both the culling and
            // the on-screen positions far from the origin.
            float[] viewProjColumnMajor = new float[16];
            mvp.get(viewProjColumnMajor);

            FrameGraphBuilder builder = new FrameGraphBuilder(backend, metricsRegistry);
            builder.addPass("far-lod-pass", () -> new FramePass() {
                @Override
                public void record(dev.ev.api.gpu.CommandList commands) {
                    // MVP draw: each visible section is rendered as a unit cube
                    // (vertex-pulling from a Node storage buffer, no real meshlet
                    // geometry yet — see FarLodPassRenderer's Javadoc) to prove the
                    // draw path actually produces visible geometry on screen.
                    // cameraRelativeBounds (not sectionBoundingSphere) so the vertex
                    // shader's worldPos ends up in the same camera-relative space as
                    // uViewProj — see the coordinate-space note above.
                    FarLodPassRenderer.record(
                            commands, visibleSections, cameraRelativeBounds,
                            viewProjColumnMajor);

                    metricsRegistry.recordGauge("visible-sections", visibleSections.size());
                    metricsRegistry.recordGauge("loaded-sections", loadedSections.size());
                }

                @Override
                public String name() {
                    return "far-lod-pass";
                }
            });
            builder.execute();

        } finally {
            // Restore OpenGL state for Sodium/Embeddium compatibility
            GL20.glUseProgram(0);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GL15.glBindBuffer(GL45.GL_DRAW_INDIRECT_BUFFER, 0);
            GL11.glDepthMask(true);
            GL11.glEnable(GL11.GL_DEPTH_TEST);

            // One-time GL error check to catch regressions like the invalid-enum
            // bug that used to live here (was hardcoded as 0x8F9F instead of
            // GL_DRAW_INDIRECT_BUFFER = 0x8F3F). Logged once, not per-frame,
            // to avoid flooding the log if a new GL error appears.
            if (!glErrorLoggedOnce) {
                int glError = GL11.glGetError();
                if (glError != GL11.GL_NO_ERROR) {
                    LOGGER.error("GL error 0x{} detected in EVInstance frame cleanup (finally block)",
                            Integer.toHexString(glError));
                    glErrorLoggedOnce = true;
                }
            }
        }
    }

    /** Drains completed batches from worker pool into the geometry map. */
    private void drainCompletedMeshes() {
        MeshletBatch batch;
        int drained = 0;
        while ((batch = meshWorkerPool.pollCompleted()) != null) {
            geometryMap.put(batch);
            drained++;
        }
        if (drained > 0) {
            metricsRegistry.recordCounter("meshes-uploaded", drained);
        }
    }

    /**
     * Computes bounding sphere [centerX, centerY, centerZ, radius] for a section.
     * Used by SimpleTraversal for frustum testing.
     */
    private float[] sectionBoundingSphere(SectionPos pos) {
        float size = pos.sizeInBlocks();
        float halfSize = size / 2f;
        float centerX = pos.minBlockX() + halfSize;
        float centerY = pos.minBlockY() + halfSize;
        float centerZ = pos.minBlockZ() + halfSize;
        // Radius = half-diagonal of the cube = halfSize * sqrt(3)
        float radius = halfSize * 1.7320508f;
        return new float[]{centerX, centerY, centerZ, radius};
    }

    /**
     * Translates an absolute-world bounding sphere [centerX, centerY, centerZ, radius]
     * into camera-relative space (subtracts the camera's world position from the
     * center; radius is unaffected by a pure translation).
     * <p>
     * Exists as a standalone static/pure function — same rationale as
     * {@link #filterByDistance}: unit-testable without a GL context or a live
     * {@link Minecraft} instance.
     * <p>
     * Callers must pair this with an MVP matrix built from a camera-relative
     * (rotation-only, no camera-position translation) view matrix — e.g. vanilla/
     * NeoForge's {@code RenderLevelStageEvent#getModelViewMatrix()} — so that both the
     * frustum-plane test and the GPU vertex transform operate in the same coordinate
     * space. See {@link #renderFarLod}'s "Build frustum from view-projection matrix"
     * step for the full explanation of why this conversion is necessary: comparing
     * absolute-world bounds against a camera-relative MVP works fine near the world
     * origin (where the two spaces nearly coincide) but silently fails far away, where
     * the mismatch can be tens of thousands of blocks.
     *
     * @param worldBounds absolute-world [centerX, centerY, centerZ, radius], as
     *                    returned by {@link #sectionBoundingSphere}
     * @param cameraX     camera world X
     * @param cameraY     camera world Y
     * @param cameraZ     camera world Z
     * @return camera-relative [centerX, centerY, centerZ, radius]
     */
    static float[] toCameraRelative(float[] worldBounds, float cameraX, float cameraY, float cameraZ) {
        return new float[]{
                worldBounds[0] - cameraX,
                worldBounds[1] - cameraY,
                worldBounds[2] - cameraZ,
                worldBounds[3]
        };
    }

    /**
     * Extracts 6 frustum planes from a combined view-projection matrix.
     * Each plane: [nx, ny, nz, d]. Total array length = 24.
     * Uses Gribb/Hartmann method.
     */
    static float[] extractFrustumPlanes(Matrix4f m) {
        float[] planes = new float[24];

        // Left:   row3 + row0
        planes[0]  = m.m03() + m.m00();
        planes[1]  = m.m13() + m.m10();
        planes[2]  = m.m23() + m.m20();
        planes[3]  = m.m33() + m.m30();
        normalizePlane(planes, 0);

        // Right:  row3 - row0
        planes[4]  = m.m03() - m.m00();
        planes[5]  = m.m13() - m.m10();
        planes[6]  = m.m23() - m.m20();
        planes[7]  = m.m33() - m.m30();
        normalizePlane(planes, 4);

        // Bottom: row3 + row1
        planes[8]  = m.m03() + m.m01();
        planes[9]  = m.m13() + m.m11();
        planes[10] = m.m23() + m.m21();
        planes[11] = m.m33() + m.m31();
        normalizePlane(planes, 8);

        // Top:    row3 - row1
        planes[12] = m.m03() - m.m01();
        planes[13] = m.m13() - m.m11();
        planes[14] = m.m23() - m.m21();
        planes[15] = m.m33() - m.m31();
        normalizePlane(planes, 12);

        // Near:   row3 + row2
        planes[16] = m.m03() + m.m02();
        planes[17] = m.m13() + m.m12();
        planes[18] = m.m23() + m.m22();
        planes[19] = m.m33() + m.m32();
        normalizePlane(planes, 16);

        // Far:    row3 - row2
        planes[20] = m.m03() - m.m02();
        planes[21] = m.m13() - m.m12();
        planes[22] = m.m23() - m.m22();
        planes[23] = m.m33() - m.m32();
        normalizePlane(planes, 20);

        return planes;
    }

    private static void normalizePlane(float[] planes, int offset) {
        float len = (float) Math.sqrt(
                planes[offset]     * planes[offset] +
                        planes[offset + 1] * planes[offset + 1] +
                        planes[offset + 2] * planes[offset + 2]
        );
        if (len > 1e-8f) {
            planes[offset]     /= len;
            planes[offset + 1] /= len;
            planes[offset + 2] /= len;
            planes[offset + 3] /= len;
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            try {
                meshWorkerPool.close();
            } catch (Exception e) {
                LOGGER.warn("Error closing mesh worker pool", e);
            }
            geometryMap.clear();
            try {
                FarLodPassRenderer.close();
            } catch (Exception e) {
                LOGGER.warn("Error closing FarLodPassRenderer", e);
            }
            if (backend != null) {
                try {
                    backend.shutdown();
                } catch (Exception ignored) {}
            }
            LOGGER.info("EVInstance closed");
        }
    }
}