package dev.ev.storage.cache;

import dev.ev.api.SectionPos;
import dev.ev.api.storage.WorldSectionHandle;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory {@link SectionLoader} fake for tests — no real disk I/O. Positions in
 * {@code existingPositions} simulate sections already present in persistent storage; all
 * others are treated as "not yet on disk" for {@link #exists(long)}, but {@link #load(long)}
 * always creates a fresh empty section for them regardless (matching the real contract's
 * "loads, or creates a new empty one if it doesn't exist on disk" semantics).
 */
final class FakeSectionLoader implements SectionLoader {

    private final Set<Long> existingPositions;
    private final AtomicInteger loadCount = new AtomicInteger(0);

    FakeSectionLoader(Set<Long> existingPositions) {
        this.existingPositions = ConcurrentHashMap.newKeySet();
        this.existingPositions.addAll(existingPositions);
    }

    FakeSectionLoader() {
        this(Set.of());
    }

    @Override
    public boolean exists(long encodedPos) {
        return existingPositions.contains(encodedPos);
    }

    @Override
    public WorldSectionHandle load(long encodedPos) {
        loadCount.incrementAndGet();
        return new FakeWorldSectionHandle(SectionPos.decode(encodedPos));
    }

    int loadCount() {
        return loadCount.get();
    }
}
