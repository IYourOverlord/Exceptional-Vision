package dev.ev.storage.cache;

import dev.ev.api.storage.WorldSectionHandle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Classic least-recently-used eviction policy, backed by a {@link LinkedHashMap} in
 * access-order mode: {@link #onInsert} and {@link #onAccess} are both O(1) amortized (a single
 * {@code LinkedHashMap} put/get, which internally relinks the touched entry to the end of the
 * iteration order); {@link #selectEvictionCandidates} is O(n) in the number of tracked handles
 * in the worst case (it may need to walk past held entries to find enough evictable ones).
 *
 * <p><b>Not thread-safe by itself</b> — see {@link EvictionPolicy}'s class Javadoc. Callers
 * (namely {@link SectionCache}) are responsible for serializing all access to a given
 * {@code LruEvictionPolicy} instance via an external lock.
 *
 * <p>Only evicts handles with {@link WorldSectionHandle#refCount()} {@code == 0} — a section
 * currently retained by any caller is never selected, no matter how stale its LRU position.
 * If the least-recently-used entries are all held, this policy walks further down the LRU
 * order looking for an evictable one; if none exist at all, it returns an empty list rather
 * than throwing — running temporarily over capacity because everything is in use is a normal,
 * expected condition, not an error.
 */
public final class LruEvictionPolicy implements EvictionPolicy {

    // accessOrder=true: iteration order tracks least-recently-used (head) to
    // most-recently-used (tail), reordered automatically on get()/put() of an existing key.
    private final Map<WorldSectionHandle, Boolean> lruOrder = new LinkedHashMap<>(16, 0.75f, true);

    @Override
    public void onInsert(WorldSectionHandle handle) {
        lruOrder.put(handle, Boolean.TRUE);
    }

    @Override
    public void onAccess(WorldSectionHandle handle) {
        // get() on an accessOrder LinkedHashMap moves the entry to the most-recently-used end.
        lruOrder.get(handle);
    }

    @Override
    public List<WorldSectionHandle> selectEvictionCandidates(int shardCapacity, int currentShardSize) {
        int overCapacity = currentShardSize - shardCapacity;
        if (overCapacity <= 0) {
            return List.of();
        }

        List<WorldSectionHandle> candidates = new ArrayList<>(overCapacity);
        for (WorldSectionHandle handle : lruOrder.keySet()) {
            if (candidates.size() >= overCapacity) {
                break;
            }
            if (handle.refCount() == 0) {
                candidates.add(handle);
            }
        }
        return candidates;
    }
}
