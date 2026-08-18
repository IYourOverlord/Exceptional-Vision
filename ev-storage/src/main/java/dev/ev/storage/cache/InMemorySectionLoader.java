package dev.ev.storage.cache;

import dev.ev.api.SectionPos;
import dev.ev.api.storage.WorldSectionHandle;

/**
 * {@link SectionLoader} that creates fresh in-memory sections on demand.
 * MVP implementation: no disk persistence — every section starts empty (all air)
 * and is populated by CoarseSectionGenerator or block-change events.
 * <p>
 * {@link #exists(long)} always returns false (nothing persisted on disk),
 * {@link #load(long)} always creates a new empty section.
 */
public final class InMemorySectionLoader implements SectionLoader {

    @Override
    public boolean exists(long encodedPos) {
        // MVP: no disk persistence, nothing pre-exists
        return false;
    }

    @Override
    public WorldSectionHandle load(long encodedPos) {
        SectionPos pos = SectionPos.decode(encodedPos);
        return new InMemorySectionHandle(pos);
    }
}
