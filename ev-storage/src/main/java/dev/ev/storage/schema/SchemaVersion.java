package dev.ev.storage.schema;

/** Registry of all on-disk schema versions this codebase understands. */
public final class SchemaVersion {

    /** Current version written by this build. Increment when the on-disk format changes. */
    public static final int CURRENT = 1;

    /** Oldest version this build can still read (via migration chain). */
    public static final int MIN_SUPPORTED = 1;

    private SchemaVersion() {
    }
}
