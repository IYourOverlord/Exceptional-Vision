package dev.ev.meshing.stage;

import dev.ev.api.SectionPos;
import dev.ev.api.meshing.Meshlet;
import dev.ev.api.meshing.MeshletBatch;
import dev.ev.api.meshing.Quad;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeshletPackStageTest {

    private static final SectionPos SECTION = new SectionPos(0, 1, 2, 3);
    private static final int MAX = MeshletBatch.MAX_QUADS_PER_MESHLET;

    private static Quad quad(int faceDirection, int x, int y, int z, int width, int height, int materialId) {
        return new Quad(faceDirection, x, y, z, width, height, materialId);
    }

    @Test
    void emptyBins_producesEmptyBatchWithZeroTotalQuadCount() {
        MeshletBatch batch = new MeshletPackStage().process(SECTION, List.of());

        assertTrue(batch.meshlets().isEmpty());
        assertEquals(0, batch.totalQuadCount());
        assertEquals(SECTION, batch.section());
    }

    @Test
    void oneBinUnderLimit_producesExactlyOneMeshletWithAllQuads() {
        List<Quad> quads = List.of(
            quad(0, 0, 0, 0, 1, 1, 5),
            quad(0, 0, 0, 1, 1, 1, 5),
            quad(0, 0, 0, 2, 1, 1, 5),
            quad(0, 0, 0, 3, 1, 1, 5),
            quad(0, 0, 0, 4, 1, 1, 5)
        );
        MaterialBin bin = new MaterialBin(5, quads);

        MeshletBatch batch = new MeshletPackStage().process(SECTION, List.of(bin));

        assertEquals(1, batch.meshlets().size());
        assertEquals(quads, batch.meshlets().get(0).quads());
    }

    @Test
    void oversizedBin_splitsIntoOrderedChunksOfMaxSizePlusRemainder() {
        int total = MAX * 2 + 10;
        List<Quad> quads = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            // Vary x so quads are distinguishable when checking order.
            quads.add(quad(0, i % 32, 0, 0, 1, 1, 9));
        }
        MaterialBin bin = new MaterialBin(9, quads);

        MeshletBatch batch = new MeshletPackStage().process(SECTION, List.of(bin));

        assertEquals(3, batch.meshlets().size());
        assertEquals(MAX, batch.meshlets().get(0).quads().size());
        assertEquals(MAX, batch.meshlets().get(1).quads().size());
        assertEquals(10, batch.meshlets().get(2).quads().size());

        assertEquals(quads.subList(0, MAX), batch.meshlets().get(0).quads());
        assertEquals(quads.subList(MAX, MAX * 2), batch.meshlets().get(1).quads());
        assertEquals(quads.subList(MAX * 2, total), batch.meshlets().get(2).quads());
    }

    @Test
    void boundingBox_singleQuad_matchesManuallyComputedRangeForThreeFaceDirections() {
        // +X: normal=X (thickness 0), width along Y, height along Z.
        Quad plusX = quad(0, 5, 2, 3, 4, 6, 1);
        Meshlet plusXMeshlet = new MeshletPackStage().process(SECTION, List.of(new MaterialBin(1, List.of(plusX))))
            .meshlets().get(0);
        assertBounds(plusXMeshlet, 5, 2, 3, 5, 6, 9);

        // +Y: normal=Y (thickness 0), width along Z, height along X.
        Quad plusY = quad(2, 1, 7, 2, 3, 5, 1);
        Meshlet plusYMeshlet = new MeshletPackStage().process(SECTION, List.of(new MaterialBin(1, List.of(plusY))))
            .meshlets().get(0);
        assertBounds(plusYMeshlet, 1, 7, 2, 6, 7, 5);

        // +Z: normal=Z (thickness 0), width along X, height along Y.
        Quad plusZ = quad(4, 0, 1, 9, 2, 8, 1);
        Meshlet plusZMeshlet = new MeshletPackStage().process(SECTION, List.of(new MaterialBin(1, List.of(plusZ))))
            .meshlets().get(0);
        assertBounds(plusZMeshlet, 0, 1, 9, 2, 9, 9);
    }

    @Test
    void boundingBox_multipleScatteredQuads_coversMinAndMaxAcrossAllOfThem() {
        // Three +X quads at very different positions within the section; the correct
        // bounding box must be the union, not just first/last in the list.
        Quad low = quad(0, 5, 1, 1, 1, 1, 2);   // Y in [1,2), Z in [1,2)
        Quad mid = quad(0, 5, 10, 10, 1, 1, 2); // Y in [10,11), Z in [10,11)
        Quad high = quad(0, 5, 30, 2, 1, 1, 2); // Y in [30,31), Z in [2,3)
        MaterialBin bin = new MaterialBin(2, List.of(mid, low, high)); // deliberately not sorted

        Meshlet meshlet = new MeshletPackStage().process(SECTION, List.of(bin)).meshlets().get(0);

        assertBounds(meshlet, 5, 1, 1, 5, 31, 11);
    }

    @Test
    void totalQuadCount_onNonEmptyBatch_equalsSumOfAllBinSizes() {
        MaterialBin bin1 = new MaterialBin(1, List.of(
            quad(0, 0, 0, 0, 1, 1, 1),
            quad(0, 0, 0, 1, 1, 1, 1),
            quad(0, 0, 0, 2, 1, 1, 1)
        ));
        MaterialBin bin2 = new MaterialBin(2, List.of(
            quad(2, 0, 0, 0, 1, 1, 2),
            quad(2, 0, 0, 1, 1, 1, 2)
        ));

        MeshletBatch batch = new MeshletPackStage().process(SECTION, List.of(bin1, bin2));

        assertEquals(5, batch.totalQuadCount());
    }

    private static void assertBounds(Meshlet meshlet, float minX, float minY, float minZ,
                                      float maxX, float maxY, float maxZ) {
        assertEquals(minX, meshlet.boundsMinX(), "minX");
        assertEquals(minY, meshlet.boundsMinY(), "minY");
        assertEquals(minZ, meshlet.boundsMinZ(), "minZ");
        assertEquals(maxX, meshlet.boundsMaxX(), "maxX");
        assertEquals(maxY, meshlet.boundsMaxY(), "maxY");
        assertEquals(maxZ, meshlet.boundsMaxZ(), "maxZ");
    }
}
