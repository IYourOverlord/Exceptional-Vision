package dev.ev.api.storage;

import dev.ev.api.SectionPos;

import java.io.Closeable;

/**
 * Persistent storage for voxelized world sections across all LOD levels.
 * A single VoxelStorage instance corresponds to one Minecraft world/dimension save.
 * Thread-safe: acquire/release may be called concurrently from multiple worker threads.
 */
public interface VoxelStorage extends Closeable {

    /** Schema version of the on-disk format currently in use (see SchemaVersion). */
    int schemaVersion();

    /**
     * Acquires a handle to the section at the given position, loading it from disk
     * or creating an empty one if it does not exist yet. Increments the handle's
     * reference count; caller MUST call {@link WorldSectionHandle#release()} when done.
     */
    WorldSectionHandle acquire(SectionPos pos);

    /**
     * Like {@link #acquire(SectionPos)} but returns null instead of creating a new
     * section if none exists on disk or in cache.
     */
    WorldSectionHandle acquireIfExists(SectionPos pos);

    /**
     * Marks a section as modified, scheduling it for persistence. Does not block.
     *
     * <p><b>Contract note:</b> the ticket text for this interface specifies the parameter
     * type as {@code DirtyFlags}, but {@link DirtyFlags} is a non-instantiable constant
     * holder (private constructor, only {@code static final int} bit flags — see its
     * Javadoc). Taken completely literally the signature below compiles, but no caller can
     * ever construct a {@code DirtyFlags} value to pass in, since the type has no public
     * constructor and no factory method. This is reproduced verbatim from the ticket
     * rather than silently "fixed" to {@code int flags} (which would be the natural
     * correction, consistent with {@link DirtyFlags#has(int, int)} taking an {@code int}) —
     * flagged here instead of guessed at, per the session instructions to stop and report
     * rather than invent a signature. Implementations of this interface in later tickets
     * (module {@code ev-storage}) will need this resolved before {@code markDirty} can be
     * called with an actual flag value.
     */
    void markDirty(WorldSectionHandle handle, DirtyFlags flags);

    /**
     * Forces synchronous persistence of a section to disk.
     * @return true if the save succeeded.
     */
    boolean save(WorldSectionHandle handle);

    /** Snapshot of storage-level metrics (cache hit rate, active section count, etc). */
    StorageMetrics metrics();

    @Override
    void close();
}
