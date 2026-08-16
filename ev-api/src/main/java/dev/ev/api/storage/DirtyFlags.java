package dev.ev.api.storage;

/** Bit flags describing why a section was marked dirty. */
public final class DirtyFlags {
    public static final int BLOCK_CHANGED = 1;
    public static final int CHILD_EXISTENCE_CHANGED = 2;
    public static final int SKIP_PERSIST = 4; // e.g. transient debug/preview data

    public static final int DEFAULT = BLOCK_CHANGED | CHILD_EXISTENCE_CHANGED;

    private DirtyFlags() {}

    public static boolean has(int flags, int flag) {
        return (flags & flag) != 0;
    }
}
