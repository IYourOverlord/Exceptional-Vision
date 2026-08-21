package dev.ev.render.scheduling;

import dev.ev.api.SectionPos;
import dev.ev.api.meshing.MeshletBatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Prior to the "stale far-LOD sections never evicted" bugfix, {@link
 * SectionGeometryMap#remove(SectionPos)} was implemented correctly but had no caller
 * anywhere in production code — {@code EV.onChunkUnload} did not exist. These tests cover
 * the primitive itself in isolation; the wiring (calling remove() from the actual
 * chunk-unload event) is NeoForge-event-based and lives in {@code EV.java}, not unit-tested
 * here since it requires a live {@code ChunkEvent.Unload}/{@code ClientLevel}.
 */
class SectionGeometryMapTest {

    private static MeshletBatch emptyBatch(SectionPos pos) {
        return new MeshletBatch(pos, List.of());
    }

    @Test
    @DisplayName("remove() evicts a previously put() section from loadedSections()")
    void testRemoveEvictsSection() {
        SectionGeometryMap map = new SectionGeometryMap();
        SectionPos pos = new SectionPos(0, 1, 0, 1);
        map.put(emptyBatch(pos));

        assertEquals(1, map.size());
        assertTrue(map.loadedSections().contains(pos));

        map.remove(pos);

        assertEquals(0, map.size());
        assertFalse(map.loadedSections().contains(pos));
        assertNull(map.get(pos));
    }

    @Test
    @DisplayName("remove() only evicts the targeted section, siblings remain")
    void testRemoveOnlyTargetedSection() {
        SectionGeometryMap map = new SectionGeometryMap();
        SectionPos target = new SectionPos(0, 1, 0, 1);
        SectionPos sibling = new SectionPos(0, 2, 0, 1);
        map.put(emptyBatch(target));
        map.put(emptyBatch(sibling));

        map.remove(target);

        assertEquals(1, map.size());
        assertTrue(map.loadedSections().contains(sibling));
        assertFalse(map.loadedSections().contains(target));
    }

    @Test
    @DisplayName("remove() on a section never put() is a safe no-op")
    void testRemoveNeverPutIsNoop() {
        SectionGeometryMap map = new SectionGeometryMap();
        SectionPos pos = new SectionPos(0, 5, 0, 5);

        assertDoesNotThrow(() -> map.remove(pos));
        assertEquals(0, map.size());
    }

    @Test
    @DisplayName("remove() reproduces the chunk-unload eviction pattern: all 4 level-0 "
            + "Y-slices of a column can be independently removed, matching EV.onChunkUnload's "
            + "loop over section Y range for one chunk column")
    void testRemoveAllYSlicesOfColumn() {
        SectionGeometryMap map = new SectionGeometryMap();
        int x = 3, z = 7;
        SectionPos[] column = new SectionPos[]{
                new SectionPos(0, x, -2, z),
                new SectionPos(0, x, -1, z),
                new SectionPos(0, x, 0, z),
                new SectionPos(0, x, 1, z),
        };
        for (SectionPos pos : column) {
            map.put(emptyBatch(pos));
        }
        assertEquals(4, map.size());

        for (SectionPos pos : column) {
            map.remove(pos);
        }

        assertEquals(0, map.size(), "unloading a chunk column must evict every one of its level-0 Y-slices");
    }
}
