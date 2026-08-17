package dev.ev.render.culling;

import dev.ev.api.SectionPos;
import dev.ev.api.metrics.MetricsRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * MVP traversal: linear O(numLoadedSections) CPU-side pass over every currently
 * loaded section, testing frustum visibility for each, with no occlusion culling
 * and no temporal coherence (full pass every frame, even if the camera hasn't
 * moved — see MVP_INDEX.md for why this is an intentional, accepted simplification
 * for Wave 1, to be replaced by 21-gpu-persistent-traversal-shader-opt.md,
 * 22-gpu-hiz-occlusion-opt.md and/or 25-render-temporal-reprojection-opt.md only
 * if profiling (P0-profiling-checkpoint.md) shows this pass is actually a meaningful
 * fraction of frame time).
 * <p>
 * This class does NOT touch the GPU directly — it produces a plain list of
 * visible SectionPos, which the caller (likely in ev-neoforge's render hook,
 * ticket 27) uses to issue ordinary (non-indirect) draw calls, one per visible
 * section, via RenderBackend (ticket 04).
 */
public final class SimpleTraversal {

    private final MetricsRegistry metrics;

    /**
     * Constructs a SimpleTraversal instance.
     *
     * @param metrics used to report per-call timing via
     *        {@code MetricsRegistry.recordGpuPassDuration("cpu-traversal", nanos)} — despite
     *        the method name (shared with actual GPU pass timing for consistency
     *        of the debug overlay/P0 profiling data), this records CPU-side wall-clock
     *        time for this MVP's traversal pass. Must not be null.
     */
    public SimpleTraversal(MetricsRegistry metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics cannot be null");
    }

    /**
     * Filters currently loaded sections against the frustum, returning a list of visible sections.
     *
     * @param loadedSections every section currently resident, non-null
     * @param frustum frustum visibility tester, non-null
     * @param sectionBounds function providing world-space bounding sphere [x, y, z, radius] for a SectionPos, non-null
     * @return list of visible section positions in the same order as in loadedSections
     */
    public List<SectionPos> computeVisible(
            List<SectionPos> loadedSections,
            FrustumTester frustum,
            Function<SectionPos, float[]> sectionBounds
    ) {
        Objects.requireNonNull(loadedSections, "loadedSections cannot be null");
        Objects.requireNonNull(frustum, "frustum cannot be null");
        Objects.requireNonNull(sectionBounds, "sectionBounds cannot be null");

        long startNanos = System.nanoTime();

        List<SectionPos> visible = new ArrayList<>();
        int size = loadedSections.size();
        for (int i = 0; i < size; i++) {
            SectionPos pos = loadedSections.get(i);
            float[] bounds = sectionBounds.apply(pos);
            if (frustum.isVisible(bounds[0], bounds[1], bounds[2], bounds[3])) {
                visible.add(pos);
            }
        }

        long elapsedNanos = System.nanoTime() - startNanos;
        metrics.recordGpuPassDuration("cpu-traversal", elapsedNanos);

        return visible;
    }
}
