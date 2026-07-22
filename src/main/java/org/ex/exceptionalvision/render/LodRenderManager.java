package org.ex.exceptionalvision.render;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.ex.exceptionalvision.ExceptionalVision;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Hooks {@link LodGpuPipeline} into Minecraft's frame render.
 * <p>
 * <b>Deliberate deviation from {@code 07_forge_integration.md}:</b> the spec sketches a
 * raw Mixin {@code @Inject} into {@code LevelRenderer#renderLevel}'s tail. Verified
 * against NeoForge for 1.21.x, that method's signature has changed across versions
 * (params/order differ even between recent 1.21.x releases) and mixing into a
 * private, obfuscated method without a real compile-and-run environment to check the
 * mapping against is exactly the kind of thing likely to silently break. NeoForge
 * instead ships {@link RenderLevelStageEvent} specifically for adding custom render
 * passes at defined points in the vanilla frame - {@code Stage.AFTER_LEVEL} sits at the
 * same point in the frame a tail injection would, but through a documented, versioned
 * event instead of a mixin into internals. This is a strictly safer choice for
 * something that can't be verified by compiling against the real jar here.
 * <p>
 * Registered on {@code NeoForge.EVENT_BUS} by {@code ExceptionalVisionClient}, which
 * also owns calling {@link LodGpuPipeline#init()} at the right point in the client
 * lifecycle and connecting {@code LodPipeline}'s {@code onRegionCached} hook to
 * {@link LodGpuPipeline#uploadCache} - see that class.
 */
public final class LodRenderManager {

    // FIX (stage 6 gap): vanilla's own chunk rendering already covers everything out to
    // the player's render-distance setting - drawing LOD geometry inside that radius too
    // is exactly what produced the z-fighting/overlapping-terrain artifacts seen
    // in-game. One extra chunk of margin absorbs the (normal) mismatch between vanilla's
    // render-distance culling and our own frustum culling, so nothing pops at the seam
    // as the camera turns.
    private static final int NEAR_CUTOFF_MARGIN_CHUNKS = 1;

    // FIX (found via a real playtest log/screenshots - see PROGRESS.md "LOD clipped by
    // vanilla's own far plane"): vanilla's per-frame projection matrix has its far
    // clipping plane tied to the player's configured render distance. Reusing it as-is
    // for the LOD pass (both for gl_Position in lod_quad.vert and for the frustum
    // planes fed to quad_cull.comp) silently clipped LOD geometry at roughly that
    // vanilla distance no matter what lodRenderDistance/nearCutoffDistance said - at 7
    // chunks, that left almost no valid window between nearCutoffDistance and vanilla's
    // own far plane, so nothing drew near the ground; from height, only a small disc
    // directly below showed, because the far plane is measured along the view ray, and
    // a downward ray to the ground is short even from modest altitude. See
    // FrustumExtractor#withExtendedFarPlane for the fix and its own caveats.
    //
    // assumedNearPlane: vanilla GameRenderer's long-standing near-plane constant - not
    // independently re-verified against a real build in this session, worth a debug
    // print of the original projection matrix's m22/m32 on first real playtest to
    // confirm (see FrustumExtractor#withExtendedFarPlane's javadoc).
    private static final float ASSUMED_VANILLA_NEAR_PLANE = 0.05f;
    // Extra buffer beyond whatever the far plane needs to reach, purely to avoid an
    // LOD quad's far edge landing exactly on the new clip plane and flickering.
    private static final float FAR_PLANE_SAFETY_MARGIN_BLOCKS = 64.0f;

    private final LodGpuPipeline pipeline;

    // DIAGNOSTIC (temporary - see PROGRESS.md "nothing renders" investigation): logs
    // exactly once per condition so a single playtest log tells us whether this handler
    // is even being invoked/reached at all, without spamming every frame.
    private boolean loggedFirstInvocation = false;
    private boolean loggedFirstSkipInactive = false;
    private boolean loggedFirstRenderCall = false;
    private boolean loggedFrameParamsOnce = false;

    public LodRenderManager(LodGpuPipeline pipeline) {
        this.pipeline = pipeline;
    }

    public void onRenderLevelStage(RenderLevelStageEvent event) {
        if (!loggedFirstInvocation) {
            loggedFirstInvocation = true;
            ExceptionalVision.LOGGER.info("[EV-DIAG] onRenderLevelStage first invoked, stage={}", event.getStage());
        }
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            return;
        }
        if (!pipeline.isActive()) {
            if (!loggedFirstSkipInactive) {
                loggedFirstSkipInactive = true;
                ExceptionalVision.LOGGER.info("[EV-DIAG] AFTER_LEVEL reached but pipeline.isActive()==false, skipping render");
            }
            return;
        }
        if (!loggedFirstRenderCall) {
            loggedFirstRenderCall = true;
            ExceptionalVision.LOGGER.info("[EV-DIAG] AFTER_LEVEL reached, pipeline active, proceeding to renderLodFrame");
        }

        try {
            Camera camera = event.getCamera();
            var cameraPos = camera.getPosition(); // net.minecraft.world.phys.Vec3
            Vector3f cameraWorldPos = new Vector3f((float) cameraPos.x, (float) cameraPos.y, (float) cameraPos.z);

            // Read every frame, not cached at init - the player can change this in the
            // options menu (or via a server-imposed clamp) mid-session.
            int renderDistanceChunks = Minecraft.getInstance().options.getEffectiveRenderDistance();
            float nearCutoffBlocks = (renderDistanceChunks + NEAR_CUTOFF_MARGIN_CHUNKS) * 16.0f;
            pipeline.setNearCutoffDistance(nearCutoffBlocks);

            // The far plane needs to reach at least lodRenderDistance (the whole point of
            // this pass) - and, as a floor, at least as far as vanilla's own render
            // distance already reaches (nearCutoffBlocks is a close approximation of that,
            // see its own comment above), so this never accidentally SHRINKS vanilla's far
            // plane on setups where lodRenderDistance has been configured smaller than the
            // player's vanilla render distance.
            float minFarBlocks = Math.max(pipeline.lodRenderDistance(), nearCutoffBlocks) + FAR_PLANE_SAFETY_MARGIN_BLOCKS;
            Matrix4f lodProjection = FrustumExtractor.withExtendedFarPlane(
                    event.getProjectionMatrix(), minFarBlocks, ASSUMED_VANILLA_NEAR_PLANE);

            if (!loggedFrameParamsOnce) {
                loggedFrameParamsOnce = true;
                ExceptionalVision.LOGGER.info(
                        "[EV-DIAG] frame params: renderDistanceChunks={}, nearCutoffBlocks={}, lodRenderDistance={}, minFarBlocks={}, cameraWorldPos=({}, {}, {})",
                        renderDistanceChunks, nearCutoffBlocks, pipeline.lodRenderDistance(), minFarBlocks,
                        cameraWorldPos.x(), cameraWorldPos.y(), cameraWorldPos.z());
            }

            // Minecraft's modelViewMatrix here is rotation-only (no camera translation) -
            // everything is already rendered camera-relative, which is exactly what
            // lod_quad.vert also assumes (see its "cameraWorldPos" subtraction). Combining
            // it with the projection matrix the same way vanilla does keeps our geometry
            // aligned with everything else in the frame - using lodProjection instead of
            // event.getProjectionMatrix() directly is exactly the fix described above.
            Matrix4f viewProjection = lodProjection.mul(event.getModelViewMatrix());

            pipeline.renderLodFrame(viewProjection, cameraWorldPos);
        } catch (RuntimeException e) {
            // A rendering-path exception must never crash the game - log once loudly and
            // disable ourselves rather than spamming every frame.
            ExceptionalVision.LOGGER.error("LOD render pass failed, disabling for this session", e);
            pipeline.close();
        }
    }
}