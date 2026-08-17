package dev.ev.render.dirty;

import dev.ev.api.SectionPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DirtySectionTrackerTest {

    @Test
    @DisplayName("markSectionDirty adds section to getDirtySections and updates counters")
    void testMarkSectionDirty() {
        DirtySectionTracker tracker = new DirtySectionTracker();
        assertFalse(tracker.hasPendingWork());
        assertEquals(0, tracker.pendingSectionCount());

        SectionPos pos = new SectionPos(0, 1, 2, 3);
        tracker.markSectionDirty(pos);

        assertTrue(tracker.hasPendingWork());
        assertEquals(1, tracker.pendingSectionCount());
        assertEquals(Set.of(pos), tracker.getDirtySections());
    }

    @Test
    @DisplayName("Repeated markSectionDirty for same section is idempotent")
    void testRepeatedMarkSectionDirtyIsIdempotent() {
        DirtySectionTracker tracker = new DirtySectionTracker();
        SectionPos pos = new SectionPos(0, 1, 2, 3);

        tracker.markSectionDirty(pos);
        tracker.markSectionDirty(pos);

        assertEquals(1, tracker.pendingSectionCount());
        assertEquals(Set.of(pos), tracker.getDirtySections());
    }

    @Test
    @DisplayName("markSectionDirty for different sections tracks both independently")
    void testMultipleDifferentSections() {
        DirtySectionTracker tracker = new DirtySectionTracker();
        SectionPos pos1 = new SectionPos(0, 1, 2, 3);
        SectionPos pos2 = new SectionPos(1, -5, 10, 20);

        tracker.markSectionDirty(pos1);
        tracker.markSectionDirty(pos2);

        assertEquals(2, tracker.pendingSectionCount());
        assertEquals(Set.of(pos1, pos2), tracker.getDirtySections());
    }

    @Test
    @DisplayName("clearSection removes specified section while leaving others untouched")
    void testClearSection() {
        DirtySectionTracker tracker = new DirtySectionTracker();
        SectionPos pos1 = new SectionPos(0, 1, 2, 3);
        SectionPos pos2 = new SectionPos(1, -5, 10, 20);

        tracker.markSectionDirty(pos1);
        tracker.markSectionDirty(pos2);

        tracker.clearSection(pos1);

        assertEquals(1, tracker.pendingSectionCount());
        assertEquals(Set.of(pos2), tracker.getDirtySections());

        tracker.clearSection(pos2);
        assertFalse(tracker.hasPendingWork());
        assertEquals(0, tracker.pendingSectionCount());
        assertTrue(tracker.getDirtySections().isEmpty());
    }
}
