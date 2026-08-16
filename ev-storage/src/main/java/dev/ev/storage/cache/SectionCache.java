package dev.ev.storage.cache;

import dev.ev.api.metrics.MetricsRegistry;
import dev.ev.api.storage.StorageMetrics;
import dev.ev.api.storage.WorldSectionHandle;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * MVP (Wave 1) implementation: a single {@link ConcurrentHashMap}-backed cache of active
 * {@link WorldSectionHandle} instances, keyed by {@code SectionPos.encode()}, with a single
 * {@link ReentrantLock} guarding eviction-policy bookkeeping only (the map itself is
 * concurrent-safe on its own via {@code ConcurrentHashMap}, but eviction candidate selection
 * needs external synchronization since {@link EvictionPolicy} implementations are documented as
 * not thread-safe by themselves).
 *
 * <p><b>This is deliberately the simple Wave 1 (MVP) version — NOT sharded.</b> See
 * {@code MVP_INDEX.md} for the two-wave rationale. A sharded replacement,
 * {@code 08-storage-section-cache-opt.md}, exists "in the bank" and should only be applied if
 * {@code P0-profiling-checkpoint.md} actually measures meaningful lock contention on this cache
 * under high concurrent load (many worker threads acquiring sections simultaneously during
 * cold-start bulk loading) — not applied speculatively ahead of that measurement. The public
 * contract (method signatures) of this class intentionally matches the opt version exactly, so
 * swapping the internal implementation later requires no change to calling code.
 *
 * <p><b>Thread-safety guarantee:</b> {@link #acquire(long, boolean)} makes no assumptions about,
 * and imposes no requirements on, the calling thread — it may be called freely from any worker
 * thread, and callers must never route it through the render/main thread "for safety". This
 * mirrors the guarantee made by the opt version and exists for the same reason: an independently
 * implemented mod of the same concept (Exceptional Vision) once wrapped a full synchronous cache
 * reload in a render-thread dispatch, causing large multi-megabyte disk reads to run on the
 * render thread and produce visible frame-time spikes during bulk import. Nothing in this class
 * needs, or should ever be given, render-thread affinity.
 */
public final class SectionCache {

    private static final String CACHE_NAME = "section_cache";

    private final ConcurrentHashMap<Long, WorldSectionHandle> sections = new ConcurrentHashMap<>();
    private final ReentrantLock evictionLock = new ReentrantLock();

    private final SectionLoader loader;
    private final EvictionPolicy eviction;
    private final MetricsRegistry metrics;

    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();

    /**
     * @param shardCountPowerOfTwo IGNORED in this MVP implementation — accepted only for
     *        constructor-signature compatibility with the opt version (so calling code does not
     *        need to change when swapping implementations later). There is no sharding here;
     *        this parameter has no effect whatsoever.
     */
    public SectionCache(int shardCountPowerOfTwo, SectionLoader loader,
                         EvictionPolicy eviction, MetricsRegistry metrics) {
        // shardCountPowerOfTwo intentionally unused: see Javadoc above and on the parameter.
        this.loader = loader;
        this.eviction = eviction;
        this.metrics = metrics;
    }

    /**
     * Acquires a handle, incrementing its ref count. If {@code onlyIfExists} is true and the
     * section is not cached and not present in storage, returns {@code null} instead of creating
     * one.
     *
     * <p>On a cache miss this calls {@code loader.exists}/{@code loader.load}, which may block on
     * I/O; in this MVP implementation that load happens inside a
     * {@link ConcurrentHashMap#computeIfAbsent} call for the target key, so other threads
     * concurrently acquiring the *same* key will block waiting for it — but threads acquiring
     * *different* keys are unaffected (they proceed independently through the concurrent map).
     * This is less parallel than the shard-per-lock opt version under heavy concurrent load on
     * the same key; that is the intended, understood difference between MVP and opt, not a bug.
     */
    public WorldSectionHandle acquire(long encodedPos, boolean onlyIfExists) {
        boolean[] wasCreated = {false};

        WorldSectionHandle handle = sections.computeIfAbsent(encodedPos, key -> {
            wasCreated[0] = true;
            if (onlyIfExists && !loader.exists(key)) {
                // Returning null from the computeIfAbsent mapping function means "no mapping
                // recorded" — this is documented ConcurrentHashMap behavior, not a special case
                // we have to handle manually.
                return null;
            }
            WorldSectionHandle loaded = loader.load(key);
            evictionLock.lock();
            try {
                eviction.onInsert(loaded);
            } finally {
                evictionLock.unlock();
            }
            return loaded;
        });

        if (handle == null) {
            cacheMisses.incrementAndGet();
            metrics.recordCacheAccess(CACHE_NAME, false);
            return null;
        }

        handle.retain();
        evictionLock.lock();
        try {
            eviction.onAccess(handle);
        } finally {
            evictionLock.unlock();
        }

        boolean hit = !wasCreated[0];
        if (hit) {
            cacheHits.incrementAndGet();
        } else {
            cacheMisses.incrementAndGet();
        }
        metrics.recordCacheAccess(CACHE_NAME, hit);
        return handle;
    }

    public int activeCount() {
        return sections.size();
    }

    public StorageMetrics metricsSnapshot() {
        return new StorageMetrics(cacheHits.get(), cacheMisses.get(), sections.size(), 0, 0L);
    }

    /** For shutdown: asserts {@code activeCount() == 0}, throws {@link IllegalStateException} otherwise. */
    public void assertEmpty() {
        int count = sections.size();
        if (count != 0) {
            throw new IllegalStateException(
                    "SectionCache.assertEmpty: expected 0 active sections, found " + count);
        }
    }
}
