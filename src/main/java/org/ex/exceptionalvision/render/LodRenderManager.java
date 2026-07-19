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
 * Not yet registered anywhere - see {@code PROGRESS.md} for the remaining
 * {@code ExceptionalVision} wiring (subscribing this to the mod event bus, calling
 * {@link LodGpuPipeline#init()} at the right point in the client lifecycle, and
 * connecting {@code LodPipeline}'s {@code onRegionCached} hook to
 * {@link LodGpuPipeline#uploadCache}).
 */
public final class LodRenderManager {

    // FIX (stage 6 gap): vanilla's own chunk rendering already covers everything out to
    // the player's render-distance setting - drawing LOD geometry inside that radius too
    // is exactly what produced the z-fighting/overlapping-terrain artifacts seen
    // in-game. One extra chunk of margin absorbs the (normal) mismatch between vanilla's
    // render-distance culling and our own frustum culling, so nothing pops at the seam
    // as the camera turns.
    private static final int NEAR_CUTOFF_MARGIN_CHUNKS = 1;

    private final LodGpuPipeline pipeline;

    public LodRenderManager(LodGpuPipeline pipeline) {
        this.pipeline = pipeline;
    }

    public void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            return;
        }
        if (!pipeline.isActive()) {
            return;
        }

        try {
            Camera camera = event.getCamera();
            var cameraPos = camera.getPosition(); // net.minecraft.world.phys.Vec3
            Vector3f cameraWorldPos = new Vector3f((float) cameraPos.x, (float) cameraPos.y, (float) cameraPos.z);

            // Minecraft's modelViewMatrix here is rotation-only (no camera translation) -
            // everything is already rendered camera-relative, which is exactly what
            // lod_quad.vert also assumes (see its "cameraWorldPos" subtraction). Combining
            // it with the projection matrix the same way vanilla does keeps our geometry
            // aligned with everything else in the frame.
            Matrix4f viewProjection = new Matrix4f(event.getProjectionMatrix()).mul(event.getModelViewMatrix());

            // Read every frame, not cached at init - the player can change this in the
            // options menu (or via a server-imposed clamp) mid-session.
            int renderDistanceChunks = Minecraft.getInstance().options.getEffectiveRenderDistance();
            float nearCutoffBlocks = (renderDistanceChunks + NEAR_CUTOFF_MARGIN_CHUNKS) * 16.0f;
            pipeline.setNearCutoffDistance(nearCutoffBlocks);

            pipeline.renderLodFrame(viewProjection, cameraWorldPos);
        } catch (RuntimeException e) {
            // A rendering-path exception must never crash the game - log once loudly and
            // disable ourselves rather than spamming every frame.
            ExceptionalVision.LOGGER.error("LOD render pass failed, disabling for this session", e);
            pipeline.close();
        }
    }
}