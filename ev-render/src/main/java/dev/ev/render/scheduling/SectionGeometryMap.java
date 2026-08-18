package dev.ev.render.scheduling;

import dev.ev.api.SectionPos;
import dev.ev.api.meshing.MeshletBatch;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Java-side mapping from {@code SectionPos} (via {@link SectionPos#encode()}) to the
 * most recently built {@link MeshletBatch} for that section. Thread-safe: worker threads
 * write completed batches, the render thread reads them.
 * <p>
 * This is the MVP substitute for GPU-resident geometry indexation (ticket 20-opt/21-opt).
 * The render pass iterates {@link #loadedSections()}, feeds them into
 * {@link dev.ev.render.culling.SimpleTraversal}, then looks up geometry here for each
 * visible section.
 */
public final class SectionGeometryMap {

    private final ConcurrentHashMap<Long, MeshletBatch> batchByEncodedPos = new ConcurrentHashMap<>();

    /**
     * Stores or replaces the geometry for a section.
     *
     * @param batch completed mesh result; {@code batch.section()} identifies the position
     */
    public void put(MeshletBatch batch) {
        batchByEncodedPos.put(batch.section().encode(), batch);
    }

    /**
     * Returns the current geometry for a section, or null if not yet meshed.
     */
    public MeshletBatch get(SectionPos pos) {
        return batchByEncodedPos.get(pos.encode());
    }

    /**
     * Removes geometry for a section (e.g. on unload).
     */
    public void remove(SectionPos pos) {
        batchByEncodedPos.remove(pos.encode());
    }

    /**
     * Snapshot of all sections that currently have geometry. Used by the render pass
     * to feed into SimpleTraversal.computeVisible.
     */
    public List<SectionPos> loadedSections() {
        List<SectionPos> result = new ArrayList<>(batchByEncodedPos.size());
        for (Long encoded : batchByEncodedPos.keySet()) {
            result.add(SectionPos.decode(encoded));
        }
        return result;
    }

    /** Number of sections with resident geometry. */
    public int size() {
        return batchByEncodedPos.size();
    }

    /** Clears all geometry (world unload). */
    public void clear() {
        batchByEncodedPos.clear();
    }
}
