package dev.ev.meshing.stage;

import dev.ev.api.meshing.Quad;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GreedyMeshStageTest {

    @Test
    void emptySection_fastPathSkipsSliceLoopAndContextCalls() {
        FakeWorldSectionHandle section = new FakeWorldSectionHandle();
        OccupancySet occupancy = new OccupancyStage().process(section);
        FakeMeshingContext ctx = new FakeMeshingContext();

        List<Quad> quads = new GreedyMeshStage().process(section, occupancy, ctx);

        assertTrue(quads.isEmpty());
        assertEquals(0, ctx.getNeighborBoundaryVoxelCallCount(),
            "isEmpty() fast path must avoid touching the context entirely");
        assertEquals(0, ctx.resolveMaterialIdCallCount(),
            "isEmpty() fast path must avoid touching the context entirely");
    }

    @Test
    void singleCenterVoxel_producesExactlySixUnitQuads() {
        FakeWorldSectionHandle section = new FakeWorldSectionHandle();
        section.setVoxel(5, 5, 5, 7);
        OccupancySet occupancy = new OccupancyStage().process(section);
        FakeMeshingContext ctx = new FakeMeshingContext();

        List<Quad> quads = new GreedyMeshStage().process(section, occupancy, ctx);

        List<Quad> expected = List.of(
            new Quad(0, 6, 5, 5, 1, 1, 7), // +X
            new Quad(1, 5, 5, 5, 1, 1, 7), // -X
            new Quad(2, 5, 6, 5, 1, 1, 7), // +Y
            new Quad(3, 5, 5, 5, 1, 1, 7), // -Y
            new Quad(4, 5, 5, 6, 1, 1, 7), // +Z
            new Quad(5, 5, 5, 5, 1, 1, 7)  // -Z
        );
        assertEquals(expected, quads);
    }

    @Test
    void solidCornerBlock_mergesIntoSixQuadsInsteadOfPerVoxelFaces() {
        FakeWorldSectionHandle section = new FakeWorldSectionHandle();
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    section.setVoxel(x, y, z, 3);
                }
            }
        }
        OccupancySet occupancy = new OccupancyStage().process(section);
        FakeMeshingContext ctx = new FakeMeshingContext();

        List<Quad> quads = new GreedyMeshStage().process(section, occupancy, ctx);

        // Naive per-voxel meshing would produce 4*4*4*6 = 384 faces; greedy meshing of
        // a solid, fully-internally-homogeneous 4x4x4 block against empty surroundings
        // collapses each of the 6 external faces into exactly one 4x4 quad.
        List<Quad> expected = List.of(
            new Quad(0, 4, 0, 0, 4, 4, 3), // +X
            new Quad(1, 0, 0, 0, 4, 4, 3), // -X
            new Quad(2, 0, 4, 0, 4, 4, 3), // +Y
            new Quad(3, 0, 0, 0, 4, 4, 3), // -Y
            new Quad(4, 0, 0, 4, 4, 4, 3), // +Z
            new Quad(5, 0, 0, 0, 4, 4, 3)  // -Z
        );
        assertEquals(expected, quads);
    }

    @Test
    void twoAdjacentVoxelsOfDifferentMaterial_areNotMergedIntoOneQuad() {
        FakeWorldSectionHandle section = new FakeWorldSectionHandle();
        section.setVoxel(0, 0, 0, 1);
        section.setVoxel(1, 0, 0, 2);
        OccupancySet occupancy = new OccupancyStage().process(section);
        FakeMeshingContext ctx = new FakeMeshingContext();

        List<Quad> quads = new GreedyMeshStage().process(section, occupancy, ctx);

        // Both voxels expose a +Y (top) face, coplanar and side-by-side in the mask —
        // the exact case greedy merging would wrongly combine if it ignored material.
        List<Quad> topFaces = quads.stream().filter(q -> q.faceDirection() == 2).toList();

        assertEquals(2, topFaces.size(), "different materials must not merge into one quad");
        for (Quad quad : topFaces) {
            assertEquals(1, quad.width());
            assertEquals(1, quad.height());
        }
        assertTrue(topFaces.stream().anyMatch(q -> q.materialId() == 1));
        assertTrue(topFaces.stream().anyMatch(q -> q.materialId() == 2));
    }

    @Test
    void fullHorizontalLayer_mergesIntoOneFullSizeQuadPerSide() {
        FakeWorldSectionHandle section = new FakeWorldSectionHandle();
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                section.setVoxel(x, 0, z, 9);
            }
        }
        OccupancySet occupancy = new OccupancyStage().process(section);
        FakeMeshingContext ctx = new FakeMeshingContext();

        List<Quad> quads = new GreedyMeshStage().process(section, occupancy, ctx);

        List<Quad> topFaces = quads.stream().filter(q -> q.faceDirection() == 2).toList();
        List<Quad> bottomFaces = quads.stream().filter(q -> q.faceDirection() == 3).toList();

        assertEquals(1, topFaces.size(), "a full 32x32 layer must merge into a single top quad");
        assertEquals(32, topFaces.get(0).width());
        assertEquals(32, topFaces.get(0).height());

        assertEquals(1, bottomFaces.size(), "a full 32x32 layer must merge into a single bottom quad");
        assertEquals(32, bottomFaces.get(0).width());
        assertEquals(32, bottomFaces.get(0).height());
    }

    @Test
    void boundaryVoxelWithOccupiedCrossSectionNeighbor_hidesTheFace() {
        FakeWorldSectionHandle section = new FakeWorldSectionHandle();
        section.setVoxel(31, 5, 5, 4);
        OccupancySet occupancy = new OccupancyStage().process(section);
        FakeMeshingContext ctx = new FakeMeshingContext();
        ctx.setBoundaryLookup((faceDirection, a, b) -> {
            if (faceDirection == 0 && a == 5 && b == 5) {
                return 9; // occupied voxel in the adjacent section
            }
            return 0;
        });

        List<Quad> quads = new GreedyMeshStage().process(section, occupancy, ctx);

        boolean hasPlusXFace = quads.stream().anyMatch(q -> q.faceDirection() == 0);
        assertFalse(hasPlusXFace,
            "an occupied neighbor across the section boundary must hide the +X face");
    }

    @Test
    void widthHeightConvention_plusXFace_widthAlongYHeightAlongZ() {
        FakeWorldSectionHandle section = new FakeWorldSectionHandle();
        for (int y = 0; y < 5; y++) {
            for (int z = 0; z < 3; z++) {
                section.setVoxel(10, y, z, 6);
            }
        }
        OccupancySet occupancy = new OccupancyStage().process(section);
        FakeMeshingContext ctx = new FakeMeshingContext();

        List<Quad> quads = new GreedyMeshStage().process(section, occupancy, ctx);
        List<Quad> plusXFaces = quads.stream().filter(q -> q.faceDirection() == 0).toList();

        assertEquals(1, plusXFaces.size());
        Quad quad = plusXFaces.get(0);
        assertEquals(5, quad.width(), "width must grow along Y for faceDirection 0 (+X)");
        assertEquals(3, quad.height(), "height must grow along Z for faceDirection 0 (+X)");
    }

    @Test
    void widthHeightConvention_plusYFace_widthAlongZHeightAlongX() {
        FakeWorldSectionHandle section = new FakeWorldSectionHandle();
        for (int z = 0; z < 5; z++) {
            for (int x = 0; x < 3; x++) {
                section.setVoxel(x, 10, z, 7);
            }
        }
        OccupancySet occupancy = new OccupancyStage().process(section);
        FakeMeshingContext ctx = new FakeMeshingContext();

        List<Quad> quads = new GreedyMeshStage().process(section, occupancy, ctx);
        List<Quad> plusYFaces = quads.stream().filter(q -> q.faceDirection() == 2).toList();

        assertEquals(1, plusYFaces.size());
        Quad quad = plusYFaces.get(0);
        assertEquals(5, quad.width(), "width must grow along Z for faceDirection 2 (+Y)");
        assertEquals(3, quad.height(), "height must grow along X for faceDirection 2 (+Y)");
    }

    @Test
    void widthHeightConvention_plusZFace_widthAlongXHeightAlongY() {
        FakeWorldSectionHandle section = new FakeWorldSectionHandle();
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 3; y++) {
                section.setVoxel(x, y, 10, 8);
            }
        }
        OccupancySet occupancy = new OccupancyStage().process(section);
        FakeMeshingContext ctx = new FakeMeshingContext();

        List<Quad> quads = new GreedyMeshStage().process(section, occupancy, ctx);
        List<Quad> plusZFaces = quads.stream().filter(q -> q.faceDirection() == 4).toList();

        assertEquals(1, plusZFaces.size());
        Quad quad = plusZFaces.get(0);
        assertEquals(5, quad.width(), "width must grow along X for faceDirection 4 (+Z)");
        assertEquals(3, quad.height(), "height must grow along Y for faceDirection 4 (+Z)");
    }
}
