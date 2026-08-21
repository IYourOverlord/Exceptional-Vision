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
                SimpleMeshingContext.INSTANCE
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
     * Filters a list of loaded sections down to only those within
     * {@code maxDistanceBlocks} of the camera, measured center-to-camera against each
     * section's own bounding sphere (so a large/high-LOD section isn't dropped just
     * because its center sits slightly past the cutoff while part of its volume is
     * still within budget — the comparison uses {@code maxDistanceBlocks + radius}).
     * <p>
     * Exists as a standalone static/pure function (no field access, only the passed-in
     * {@code sectionBounds} function) specifically so this filtering logic — the fix for
     * stale, far-away sections geometrically intersecting the current view frustum by
     * direction alone after a long-distance teleport (see {@link #renderFarLod}'s
     * "Distance-based cutoff" step) — is unit-testable without a GL context or a live
     * {@link Minecraft} instance.
     *
     * @param loadedSections   every section currently resident, non-null
     * @param cameraX          camera world X
     * @param cameraY          camera world Y
     * @param cameraZ          camera world Z
     * @param maxDistanceBlocks non-negative distance budget in blocks (see {@code
     *                          EVConfig.maxRenderDistanceBlocks()})
     * @param sectionBounds    world-space bounding sphere [x, y, z, radius] for a SectionPos
     * @return sections within budget, in the same relative order as {@code loadedSections}
     */
    static List<SectionPos> filterByDistance(
            List<SectionPos> loadedSections,
            float cameraX, float cameraY, float cameraZ,
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
            float cutoff = maxDistanceBlocks + bounds[3];
            if (distSq <= cutoff * cutoff) {
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

            // 4. Build frustum from view-projection matrix
            Matrix4f projMatrix = event.getProjectionMatrix();
            Matrix4f modelViewMatrix = event.getPoseStack() != null
                    ? event.getPoseStack().last().pose()
                    : new Matrix4f();
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
            List<SectionPos> loadedSections = geometryMap.loadedSections();
            List<SectionPos> nearbySections = filterByDistance(
                    loadedSections, cameraX, cameraY, cameraZ, maxDistanceBlocks, this::sectionBoundingSphere
            );
            List<SectionPos> visibleSections = traversal.computeVisible(
                    nearbySections, frustum, this::sectionBoundingSphere
            );

            // 6. Execute frame graph with visible sections
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
                    FarLodPassRenderer.record(
                            commands, visibleSections, EVInstance.this::sectionBoundingSphere,
                            viewProjColumnMajor);

                    metricsRegistry.recordCounter("visible-sections", visibleSections.size());
                    metricsRegistry.recordCounter("loaded-sections", loadedSections.size());
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