package dev.ev.render.dirty;

import dev.ev.api.SectionPos;
import dev.ev.api.meshing.Quad;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GeometryChangeDeduplicatorTest {

    @Test
    @DisplayName("First recordAndCheckChanged call for a section returns true")
    void testFirstCheckReturnsTrue() {
        GeometryChangeDeduplicator deduplicator = new GeometryChangeDeduplicator();
        SectionPos pos = new SectionPos(0, 10, 20, 30);

        assertTrue(deduplicator.recordAndCheckChanged(pos, 12345L));
    }

    @Test
    @DisplayName("Second recordAndCheckChanged call with same hash returns false (deduplicated)")
    void testSecondCheckWithSameHashReturnsFalse() {
        GeometryChangeDeduplicator deduplicator = new GeometryChangeDeduplicator();
        SectionPos pos = new SectionPos(0, 10, 20, 30);

        assertTrue(deduplicator.recordAndCheckChanged(pos, 12345L));
        assertFalse(deduplicator.recordAndCheckChanged(pos, 12345L));
    }

    @Test
    @DisplayName("Call with different hash returns true (real geometry change)")
    void testCheckWithDifferentHashReturnsTrue() {
        GeometryChangeDeduplicator deduplicator = new GeometryChangeDeduplicator();
        SectionPos pos = new SectionPos(0, 10, 20, 30);

        assertTrue(deduplicator.recordAndCheckChanged(pos, 12345L));
        assertTrue(deduplicator.recordAndCheckChanged(pos, 99999L));
    }

    @Test
    @DisplayName("hashQuads produces value-based identical hash for equal Quad lists created separately")
    void testValueBasedHashQuads() {
        GeometryChangeDeduplicator deduplicator = new GeometryChangeDeduplicator();

        Quad q1 = new Quad(0, 1, 2, 3, 4, 5, 10);
        Quad q2 = new Quad(1, 0, 0, 0, 16, 16, 20);

        Quad q1Copy = new Quad(0, 1, 2, 3, 4, 5, 10);
        Quad q2Copy = new Quad(1, 0, 0, 0, 16, 16, 20);

        List<Quad> listA = List.of(q1, q2);
        List<Quad> listB = List.of(q1Copy, q2Copy);

        long hashA = deduplicator.hashQuads(listA);
        long hashB = deduplicator.hashQuads(listB);

        assertEquals(hashA, hashB);

        // Different content -> different hash
        List<Quad> listDifferent = List.of(q1, new Quad(1, 0, 0, 0, 16, 16, 21));
        assertNotEquals(hashA, deduplicator.hashQuads(listDifferent));
    }
}
