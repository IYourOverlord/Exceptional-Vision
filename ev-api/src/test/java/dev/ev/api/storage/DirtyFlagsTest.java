package dev.ev.api.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link DirtyFlags} bit flag arithmetic. */
class DirtyFlagsTest {

    @Test
    void defaultIncludesBlockAndChildExistence() {
        assertTrue(DirtyFlags.has(DirtyFlags.DEFAULT, DirtyFlags.BLOCK_CHANGED));
        assertTrue(DirtyFlags.has(DirtyFlags.DEFAULT, DirtyFlags.CHILD_EXISTENCE_CHANGED));
        assertFalse(DirtyFlags.has(DirtyFlags.DEFAULT, DirtyFlags.SKIP_PERSIST));
    }

    @Test
    void hasDetectsSingleFlag() {
        int flags = DirtyFlags.BLOCK_CHANGED | DirtyFlags.SKIP_PERSIST;
        assertTrue(DirtyFlags.has(flags, DirtyFlags.BLOCK_CHANGED));
        assertTrue(DirtyFlags.has(flags, DirtyFlags.SKIP_PERSIST));
        assertFalse(DirtyFlags.has(flags, DirtyFlags.CHILD_EXISTENCE_CHANGED));
    }

    @Test
    void hasReturnsFalseForZeroFlags() {
        assertFalse(DirtyFlags.has(0, DirtyFlags.BLOCK_CHANGED));
    }
}
