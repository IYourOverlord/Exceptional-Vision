package dev.ev.render.dirty;

import dev.ev.api.SectionPos;
import dev.ev.api.meshing.Quad;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32;

/**
 * After a dirty section rebuild produces new geometry, compares a cheap hash of
 * the result against the hash from the last successful rebuild of the same
 * section, to avoid propagating a rebuild further down the pipeline (persistent
 * write, GPU upload) when the geometry is byte-identical to what's already
 * resident — see this ticket's "Эмпирический урок" section for why this matters
 * in practice.
 * <p>
 * <b>Thread-safety:</b> a single instance of this class is shared across all
 * {@code MeshWorkerPool} worker threads (created once in {@code EVInstance}).
 * {@code Long2LongOpenHashMap} is not thread-safe — concurrent {@code put()}
 * calls from different threads can race on internal resize/rehash, corrupting
 * the backing array (observed as {@code ArrayIndexOutOfBoundsException} from
 * concurrent puts). {@link #recordAndCheckChanged} is therefore fully
 * synchronized: the read-then-write (get/put) sequence must be atomic anyway,
 * since two threads finishing the same section concurrently would otherwise
 * race on which hash "wins".
 */
public final class GeometryChangeDeduplicator {

    private static final long SENTINEL_NO_HASH = Long.MIN_VALUE;

    private final Long2LongOpenHashMap lastHashesByEncodedPos = new Long2LongOpenHashMap();

    public GeometryChangeDeduplicator() {
        lastHashesByEncodedPos.defaultReturnValue(SENTINEL_NO_HASH);
    }

    /**
     * Computes a fast CRC32-based hash of a Quad list's contents.
     *
     * @param quads list of quads, non-null
     * @return 64-bit hash value representing quad list content
     */
    public long hashQuads(List<Quad> quads) {
        Objects.requireNonNull(quads, "quads cannot be null");
        CRC32 crc = new CRC32();
        int size = quads.size();

        // Update with size first
        updateInt(crc, size);

        for (int i = 0; i < size; i++) {
            Quad q = quads.get(i);
            crc.update(q.faceDirection());
            updateInt(crc, q.x());
            updateInt(crc, q.y());
            updateInt(crc, q.z());
            updateInt(crc, q.width());
            updateInt(crc, q.height());
            updateInt(crc, q.materialId());
        }

        return crc.getValue();
    }

    /**
     * Records the new geometry hash for a section and returns whether the geometry has changed
     * relative to the last recorded build for this section.
     *
     * @param section section position, non-null
     * @param newHash geometry content hash
     * @return true if newHash differs from the last recorded hash or if the section is seen for the first time;
     *         false if the hash is identical to the previously recorded hash
     */
    public synchronized boolean recordAndCheckChanged(SectionPos section, long newHash) {
        Objects.requireNonNull(section, "section cannot be null");
        long encoded = section.encode();
        long prevHash = lastHashesByEncodedPos.get(encoded);

        if (prevHash == SENTINEL_NO_HASH || prevHash != newHash) {
            lastHashesByEncodedPos.put(encoded, newHash);
            return true;
        }

        return false;
    }

    private static void updateInt(CRC32 crc, int val) {
        crc.update((val >>> 24) & 0xFF);
        crc.update((val >>> 16) & 0xFF);
        crc.update((val >>> 8) & 0xFF);
        crc.update(val & 0xFF);
    }
}
