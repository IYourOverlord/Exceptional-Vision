package dev.ev.storage.cache;

import dev.ev.api.storage.WorldSectionHandle;

/**
 * Loads section data from persistent storage. Implemented elsewhere (region file I/O,
 * separate ticket) — {@link SectionCache} only depends on this interface, never touches disk
 * itself.
 */
public interface SectionLoader {

    boolean exists(long encodedPos);

    /** Loads the section, or creates a new empty one if it doesn't exist on disk. */
    WorldSectionHandle load(long encodedPos);
}
