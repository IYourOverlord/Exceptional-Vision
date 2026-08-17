package dev.ev.render.dirty;

import dev.ev.api.SectionPos;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * MVP implementation: tracks which SECTIONS (not sub-regions) have been modified
 * since their last mesh rebuild. Any block change marks the entire containing
 * section dirty; the mesh rebuild step reprocesses the whole section via
 * GreedyMeshStage.process (ticket 11), no partial/sub-region-scoped rebuild.
 * <p>
 * See MVP_INDEX.md for why this simple version comes first — the more granular
 * 26-render-dirty-subregion-opt.md, which tracks dirty state at 8^3 sub-region
 * granularity within a section AND extends GreedyMeshStage to support
 * partial-scope rebuilds, is only worth the added complexity if profiling
 * (P0-profiling-checkpoint.md) shows frequent block edits causing noticeable
 * frame time cost from full-section rebuilds.
 * <p>
 * Not thread-safe by design — intended to be driven from the main world-tick
 * thread where block change events originate (Minecraft's block update events
 * are main-thread-only), with the accumulated dirty set drained and handed off
 * to worker threads (via the meshing queue, ticket 15) once per tick/frame.
 */
public final class DirtySectionTracker {

    private final LongSet dirtyPosEncoded = new LongOpenHashSet();

    /**
     * Records a block change anywhere within the given section — marks the
     * whole section dirty (no finer granularity in this MVP version). Idempotent.
     *
     * @param section position of the modified section, non-null
     */
    public void markSectionDirty(SectionPos section) {
        Objects.requireNonNull(section, "section cannot be null");
        dirtyPosEncoded.add(section.encode());
    }

    /**
     * Returns all sections currently marked dirty, needing a full rebuild.
     *
     * @return set of dirty SectionPos instances
     */
    public Set<SectionPos> getDirtySections() {
        Set<SectionPos> result = new HashSet<>(dirtyPosEncoded.size());
        for (long encoded : dirtyPosEncoded) {
            result.add(SectionPos.decode(encoded));
        }
        return result;
    }

    /**
     * Clears dirty state for a section after its rebuild has been dispatched.
     *
     * @param section position of the section to clear, non-null
     */
    public void clearSection(SectionPos section) {
        Objects.requireNonNull(section, "section cannot be null");
        dirtyPosEncoded.remove(section.encode());
    }

    /**
     * True if any section is currently marked dirty.
     *
     * @return true if there are dirty sections
     */
    public boolean hasPendingWork() {
        return !dirtyPosEncoded.isEmpty();
    }

    /**
     * Number of sections currently marked dirty, for metrics.
     *
     * @return pending dirty section count
     */
    public int pendingSectionCount() {
        return dirtyPosEncoded.size();
    }
}
