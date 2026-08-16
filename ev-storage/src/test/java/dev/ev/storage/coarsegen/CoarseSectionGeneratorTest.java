package dev.ev.storage.coarsegen;

import dev.ev.api.SectionPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoarseSectionGeneratorTest {

    private static final int MATERIAL = 7;
    private static final int AIR = 0;

    /** Requirement 1: section entirely above a constant surface height -> all air. */
    @Test
    void sectionEntirelyAboveSurface_isAllAir() {
        // Section at level 0 (32 blocks, minBlockY = 0..31), surface far below at Y = -1000.
        SectionPos pos = new SectionPos(0, 0, 0, 0);
        FakeHeightmapSource source = new FakeHeightmapSource(-1000, MATERIAL);
        FakeWorldSectionHandle handle = new FakeWorldSectionHandle(pos);

        new CoarseSectionGenerator(source).generate(handle);

        assertAllVoxelsEqual(handle, AIR);
    }

    /** Requirement 2: section entirely below a constant surface height -> all material. */
    @Test
    void sectionEntirelyBelowSurface_isAllMaterial() {
        // Section at level 0 (32 blocks, minBlockY = 0..31), surface far above at Y = 1000.
        SectionPos pos = new SectionPos(0, 0, 0, 0);
        FakeHeightmapSource source = new FakeHeightmapSource(1000, MATERIAL);
        FakeWorldSectionHandle handle = new FakeWorldSectionHandle(pos);

        new CoarseSectionGenerator(source).generate(handle);

        assertAllVoxelsEqual(handle, MATERIAL);
    }

    /** Requirement 3: section straddling the surface -> bottom filled, top air, split near the quantized Y. */
    @Test
    void sectionStraddlingSurface_splitsAtQuantizedY() {
        // Level 0 section (32 blocks tall), minBlockY = 0. Surface at Y = 15 -> quantizedSurfaceLocalY = 15.
        SectionPos pos = new SectionPos(0, 0, 0, 0);
        FakeHeightmapSource source = new FakeHeightmapSource(15, MATERIAL);
        FakeWorldSectionHandle handle = new FakeWorldSectionHandle(pos);

        new CoarseSectionGenerator(source).generate(handle);

        for (int localY = 0; localY <= 15; localY++) {
            assertEquals(MATERIAL, handle.getVoxel(0, localY, 0),
                    "localY=" + localY + " should be material (at/below quantized surface)");
        }
        for (int localY = 16; localY < 32; localY++) {
            assertEquals(AIR, handle.getVoxel(0, localY, 0),
                    "localY=" + localY + " should be air (above quantized surface)");
        }
    }

    /** Requirement 4: consistent above/below-surface direction at LOD 0 and LOD 6 for the same world. */
    @Test
    void consistentAcrossLodLevels_aboveAndBelowSurface() {
        int surfaceHeight = 100;
        FakeHeightmapSource source = new FakeHeightmapSource(surfaceHeight, MATERIAL);

        // A section clearly below the surface at level 0 (32 blocks: Y 0..31).
        SectionPos level0Below = new SectionPos(0, 0, 0, 0);
        FakeWorldSectionHandle handle0Below = new FakeWorldSectionHandle(level0Below);
        new CoarseSectionGenerator(source).generate(handle0Below);
        assertAllVoxelsEqual(handle0Below, MATERIAL);

        // A section clearly above the surface at level 0, well above Y=100 (e.g. section y-index 10 -> Y 320..351).
        SectionPos level0Above = new SectionPos(0, 0, 10, 0);
        FakeWorldSectionHandle handle0Above = new FakeWorldSectionHandle(level0Above);
        new CoarseSectionGenerator(source).generate(handle0Above);
        assertAllVoxelsEqual(handle0Above, AIR);

        // Level 6 (2048 blocks tall). Section y-index 0 -> Y 0..2047, clearly below (surface at 100 well inside
        // -> straddles at coarse resolution, so check dominant direction near the bottom instead) — use a
        // section index guaranteed fully below (y = -1 -> Y -2048..-1) and fully above (y = 1 -> Y 2048..4095).
        SectionPos level6Below = new SectionPos(6, 0, -1, 0);
        FakeWorldSectionHandle handle6Below = new FakeWorldSectionHandle(level6Below);
        new CoarseSectionGenerator(source).generate(handle6Below);
        assertAllVoxelsEqual(handle6Below, MATERIAL);

        SectionPos level6Above = new SectionPos(6, 0, 1, 0);
        FakeWorldSectionHandle handle6Above = new FakeWorldSectionHandle(level6Above);
        new CoarseSectionGenerator(source).generate(handle6Above);
        assertAllVoxelsEqual(handle6Above, AIR);
    }

    /** Requirement 5: isAvailable() == false is handled per the documented policy (skip column, report incomplete). */
    @Test
    void unavailableColumns_areSkippedAndReportedIncomplete() {
        SectionPos pos = new SectionPos(0, 0, 0, 0);
        // Mark every column unavailable.
        FakeHeightmapSource source = new FakeHeightmapSource(15, MATERIAL, (x, z) -> false);
        FakeWorldSectionHandle handle = new FakeWorldSectionHandle(pos);

        CoarseSectionGenerator.GenerationResult result = new CoarseSectionGenerator(source).generate(handle);

        assertFalse(result.complete(), "result should report incomplete when all columns are unavailable");
        // Per policy: unavailable columns are left untouched -> stay at their pre-existing (air) default.
        assertAllVoxelsEqual(handle, AIR);
    }

    /** Requirement 5b: a mix of available/unavailable columns still marks the result incomplete overall,
     *  while available columns are generated normally. */
    @Test
    void partiallyUnavailableColumns_generatesAvailableOnesAndReportsIncomplete() {
        SectionPos pos = new SectionPos(0, 0, 0, 0);
        // Unavailable only for worldX == 0 (i.e. localX == 0, since section starts at block 0, voxel size 1).
        FakeHeightmapSource source = new FakeHeightmapSource(15, MATERIAL, (x, z) -> x != 0);
        FakeWorldSectionHandle handle = new FakeWorldSectionHandle(pos);

        CoarseSectionGenerator.GenerationResult result = new CoarseSectionGenerator(source).generate(handle);

        assertFalse(result.complete());
        // localX=1 column (available) should be generated normally.
        assertEquals(MATERIAL, handle.getVoxel(1, 0, 0));
        assertEquals(AIR, handle.getVoxel(1, 20, 0));
    }

    /** Requirement 6: exactly one heightmap sample per voxel-column (32*32), not proportional to sizeInBlocks(). */
    @Test
    void samplesExactlyOnePointPerVoxelColumn_regardlessOfLodLevel() {
        FakeHeightmapSource sourceLevel0 = new FakeHeightmapSource(15, MATERIAL);
        new CoarseSectionGenerator(sourceLevel0).generate(new FakeWorldSectionHandle(new SectionPos(0, 0, 0, 0)));
        assertEquals(32 * 32, sourceLevel0.surfaceHeightCallCount());
        assertEquals(32 * 32, sourceLevel0.surfaceMaterialCallCount());
        assertEquals(32 * 32, sourceLevel0.distinctSampledColumnCount());

        FakeHeightmapSource sourceLevel6 = new FakeHeightmapSource(15, MATERIAL);
        new CoarseSectionGenerator(sourceLevel6).generate(new FakeWorldSectionHandle(new SectionPos(6, 0, 0, 0)));
        assertEquals(32 * 32, sourceLevel6.surfaceHeightCallCount(),
                "call count must stay 32*32 at LOD 6 too, not scale with sizeInBlocks()");
        assertEquals(32 * 32, sourceLevel6.surfaceMaterialCallCount());
        assertEquals(32 * 32, sourceLevel6.distinctSampledColumnCount());
    }

    /** Sanity check on the uniform-section flag surfaced in GenerationResult (ticket requirement 5). */
    @Test
    void generationResult_flagsUniformSection() {
        SectionPos pos = new SectionPos(0, 0, 0, 0);
        FakeHeightmapSource source = new FakeHeightmapSource(-1000, MATERIAL); // all air -> uniform
        FakeWorldSectionHandle handle = new FakeWorldSectionHandle(pos);

        CoarseSectionGenerator.GenerationResult result = new CoarseSectionGenerator(source).generate(handle);

        assertTrue(result.uniform());
        assertTrue(result.complete());
    }

    private static void assertAllVoxelsEqual(FakeWorldSectionHandle handle, int expected) {
        for (int x = 0; x < 32; x++) {
            for (int y = 0; y < 32; y++) {
                for (int z = 0; z < 32; z++) {
                    assertEquals(expected, handle.getVoxel(x, y, z),
                            "voxel (" + x + "," + y + "," + z + ") mismatch");
                }
            }
        }
    }
}
