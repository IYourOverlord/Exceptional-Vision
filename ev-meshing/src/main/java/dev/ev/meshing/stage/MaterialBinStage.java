package dev.ev.meshing.stage;

import dev.ev.api.meshing.Quad;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Third stage of the meshing pipeline: groups a flat list of Quads into MaterialBins
 * by materialId, preserving the original relative order of quads within each bin
 * (stable grouping — do not reorder quads that share a material, only separate
 * quads with different materials into different bins).
 *
 * <p>Bins are returned sorted by materialId ascending, for deterministic output
 * (useful for testing and for consistent GPU buffer layout across rebuilds of
 * the same section, which helps minimize buffer diffing/upload churn — though
 * that optimization itself is out of scope for this stage).
 *
 * <p>Complexity: {@code O(n)} to group quads (insertion order preserved per bin via
 * {@link LinkedHashMap}), plus {@code O(k log k)} to sort the resulting bins by
 * materialId, where {@code k} is the number of distinct materials — typically far
 * smaller than {@code n}, the total quad count.
 */
public final class MaterialBinStage {

    public List<MaterialBin> process(List<Quad> quads) {
        if (quads.isEmpty()) {
            return List.of();
        }

        // LinkedHashMap preserves insertion order within each materialId's list and,
        // incidentally, first-seen order across keys — the latter is irrelevant since
        // we sort the final bin list by materialId below regardless.
        Map<Integer, List<Quad>> byMaterial = new LinkedHashMap<>();
        for (Quad quad : quads) {
            byMaterial.computeIfAbsent(quad.materialId(), id -> new ArrayList<>()).add(quad);
        }

        List<Integer> materialIds = new ArrayList<>(byMaterial.keySet());
        materialIds.sort(null);

        List<MaterialBin> bins = new ArrayList<>(materialIds.size());
        for (int materialId : materialIds) {
            bins.add(new MaterialBin(materialId, byMaterial.get(materialId)));
        }

        return bins;
    }
}
