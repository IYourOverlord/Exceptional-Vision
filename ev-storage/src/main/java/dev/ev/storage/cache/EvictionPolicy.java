package dev.ev.storage.cache;

import dev.ev.api.storage.WorldSectionHandle;
import java.util.List;

/**
 * Decides which cached sections to evict when a shard (or, in the MVP/non-sharded
 * {@link SectionCache}, the whole cache) exceeds its capacity. Called by {@code SectionCache}
 * internals; implementations must be cheap (called on every cache insert) and are NOT required
 * to be thread-safe on their own — {@code SectionCache} guarantees calls into an
 * {@code EvictionPolicy} instance are already serialized by an external lock, so a simple
 * non-thread-safe implementation (e.g. backed by {@link java.util.LinkedHashMap}) is acceptable.
 * Document this assumption in implementing classes.
 */
public interface EvictionPolicy {

    void onInsert(WorldSectionHandle handle);

    void onAccess(WorldSectionHandle handle);

    /** Returns handles that should be evicted, or empty if under capacity. */
    List<WorldSectionHandle> selectEvictionCandidates(int shardCapacity, int currentShardSize);
}
