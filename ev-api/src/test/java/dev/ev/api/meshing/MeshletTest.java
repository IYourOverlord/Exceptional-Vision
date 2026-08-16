package dev.ev.api.meshing;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Unit tests for {@link Meshlet}'s quad-count invariant. */
class MeshletTest {

    private static Quad dummyQuad() {
        return new Quad(0, 0, 0, 0, 1, 1, 0);
    }

    private static List<Quad> quads(int count) {
        List<Quad> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(dummyQuad());
        }
        return list;
    }

    @Test
    void constructorThrowsWhenExceedingMaxQuadsPerMeshlet() {
        List<Quad> tooMany = quads(MeshletBatch.MAX_QUADS_PER_MESHLET + 1);
        assertThrows(IllegalArgumentException.class,
            () -> new Meshlet(tooMany, 0, 0, 0, 1, 1, 1));
    }

    @Test
    void constructorAcceptsExactlyMaxQuadsPerMeshlet() {
        List<Quad> exactlyMax = quads(MeshletBatch.MAX_QUADS_PER_MESHLET);
        assertDoesNotThrow(() -> new Meshlet(exactlyMax, 0, 0, 0, 1, 1, 1));
    }

    @Test
    void constructorAcceptsEmptyQuadList() {
        assertDoesNotThrow(() -> new Meshlet(List.of(), 0, 0, 0, 0, 0, 0));
    }
}
