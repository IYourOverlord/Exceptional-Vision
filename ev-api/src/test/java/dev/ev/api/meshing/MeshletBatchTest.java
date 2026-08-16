package dev.ev.api.meshing;

import dev.ev.api.SectionPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Unit test for {@link MeshletBatch#totalQuadCount()} summation across meshlets. */
class MeshletBatchTest {

    private static Quad dummyQuad() {
        return new Quad(0, 0, 0, 0, 1, 1, 0);
    }

    @Test
    void totalQuadCountSumsAcrossMeshlets() {
        Meshlet a = new Meshlet(List.of(dummyQuad(), dummyQuad()), 0, 0, 0, 1, 1, 1);
        Meshlet b = new Meshlet(List.of(dummyQuad()), 0, 0, 0, 1, 1, 1);
        MeshletBatch batch = new MeshletBatch(new SectionPos(0, 0, 0, 0), List.of(a, b));

        assertEquals(3, batch.totalQuadCount());
    }

    @Test
    void totalQuadCountZeroForEmptyMeshletList() {
        MeshletBatch batch = new MeshletBatch(new SectionPos(0, 0, 0, 0), List.of());
        assertEquals(0, batch.totalQuadCount());
    }
}
