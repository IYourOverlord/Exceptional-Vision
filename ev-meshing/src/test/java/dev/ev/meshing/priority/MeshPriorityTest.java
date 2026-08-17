package dev.ev.meshing.priority;

import dev.ev.api.SectionPos;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeshPriorityTest {

    @Test
    void finerLodLevel_isMorePriorityThanCoarser_otherParamsEqual() {
        long fine = MeshPriority.compute(0, 6, 0, 1f, 0.5f, 100L);
        long coarse = MeshPriority.compute(6, 6, 0, 1f, 0.5f, 100L);

        assertTrue(fine < coarse, "finer (smaller) lodLevel must produce a smaller (higher-priority) value");
    }

    @Test
    void moreAttempts_isMorePriority_andCapsAtThreshold() {
        long zeroAttempts = MeshPriority.compute(3, 6, 0, 1f, 0.5f, 50L);
        long threeAttempts = MeshPriority.compute(3, 6, 3, 1f, 0.5f, 50L);
        long hundredAttempts = MeshPriority.compute(3, 6, 100, 1f, 0.5f, 50L);

        assertTrue(threeAttempts < zeroAttempts, "aging: more prior attempts must increase priority (lower value)");
        assertEquals(threeAttempts, hundredAttempts,
            "attempts must be capped — 3 and 100 attempts must produce the identical component");
    }

    @Test
    void facingCamera_isMorePriorityThanNotFacing_otherParamsEqual() {
        long facing = MeshPriority.compute(3, 6, 0, 0.9f, 0.5f, 10L);
        long notFacing = MeshPriority.compute(3, 6, 0, 0.1f, 0.5f, 10L);

        assertTrue(facing < notFacing, "a section in the view cone must be more priority than one outside it");
    }

    @Test
    void smallerInsertionSeq_isMorePriority_asFifoTieBreak() {
        long earlier = MeshPriority.compute(3, 6, 0, 1f, 0.5f, 5L);
        long later = MeshPriority.compute(3, 6, 0, 1f, 0.5f, 1000L);

        assertTrue(earlier < later, "smaller insertionSeq (submitted earlier) must be more priority");
    }

    @Test
    void vectorOverload_matchesPrimaryOverloadWithManuallyComputedCosine() {
        SectionPos section = new SectionPos(2, 0, 0, 0); // sizeInBlocks = 128, center = (64,64,64)
        float cameraX = 0f;
        float cameraY = 0f;
        float cameraZ = 0f;
        float viewDirX = 1f;
        float viewDirY = 0f;
        float viewDirZ = 0f;
        float facingThreshold = 0.3f;
        long insertionSeq = 42L;
        int maxLodLevel = 6;
        int attempts = 1;

        long viaVectorOverload = MeshPriority.compute(section, maxLodLevel, attempts,
            cameraX, cameraY, cameraZ, viewDirX, viewDirY, viewDirZ, facingThreshold, insertionSeq);

        // Manually replicate normalize(center - camera) . viewDir for viewDir = (1,0,0):
        // direction is (1,1,1)/sqrt(3), so the dot product is 1/sqrt(3).
        float expectedCos = 1f / (float) Math.sqrt(3);
        long viaPrimaryOverload = MeshPriority.compute(section.level(), maxLodLevel, attempts,
            expectedCos, facingThreshold, insertionSeq);

        assertEquals(viaPrimaryOverload, viaVectorOverload);
    }

    @Test
    void nearPlayerTier_alwaysOutranksAnyNormalTierSection_evenWithWorstCaseParams() {
        SectionPos nearSection = new SectionPos(0, 0, 0, 0); // right next to the camera
        SectionPos farSection = new SectionPos(0, 1000, 1000, 1000); // far outside the radius
        float cameraX = 0f;
        float cameraY = 0f;
        float cameraZ = 0f;

        // Near section: deliberately worst secondary params — should not matter, since the
        // near tier ignores them entirely once the radius check passes.
        long nearPriority = MeshPriority.computeWithNearTierCheck(
            nearSection, 6, 0,
            cameraX, cameraY, cameraZ,
            -1f, 0f, 0f, // facing away
            0.99f, // very strict facing threshold
            0L);

        // Far section: deliberately best possible normal-tier params — finest LOD, capped
        // (high) attempts, and a view direction pointing exactly at its center.
        float farCenterX = farSection.minBlockX() + farSection.sizeInBlocks() / 2f;
        float farCenterY = farSection.minBlockY() + farSection.sizeInBlocks() / 2f;
        float farCenterZ = farSection.minBlockZ() + farSection.sizeInBlocks() / 2f;
        float len = (float) Math.sqrt(
            farCenterX * farCenterX + farCenterY * farCenterY + farCenterZ * farCenterZ);
        float viewDirX = farCenterX / len;
        float viewDirY = farCenterY / len;
        float viewDirZ = farCenterZ / len;

        long farPriority = MeshPriority.computeWithNearTierCheck(
            farSection, 6, 100,
            cameraX, cameraY, cameraZ,
            viewDirX, viewDirY, viewDirZ,
            0.0f,
            0L);

        assertTrue(nearPriority < farPriority,
            "a near-player-tier section must always outrank any normal-tier section");
    }

    @Test
    void withinNearTier_closerSectionIsMorePriorityThanFartherSection() {
        SectionPos closeSection = new SectionPos(0, 0, 0, 0);
        SectionPos fartherSection = new SectionPos(0, 3, 0, 0); // still within the radius (<=4)
        float cameraX = 0f;
        float cameraY = 0f;
        float cameraZ = 0f;

        long closePriority = MeshPriority.computeWithNearTierCheck(
            closeSection, 6, 0, cameraX, cameraY, cameraZ, 1f, 0f, 0f, 0.5f, 0L);
        long fartherPriority = MeshPriority.computeWithNearTierCheck(
            fartherSection, 6, 0, cameraX, cameraY, cameraZ, 1f, 0f, 0f, 0.5f, 0L);

        assertTrue(closePriority < fartherPriority,
            "within the near tier, the closer section must still be more priority (nearest-first)");
    }

    @Test
    void tierBitInvariant_holdsAcrossManyRandomInputs() {
        Random random = new Random(1234567L);

        for (int i = 0; i < 500; i++) {
            long normalResult = MeshPriority.compute(
                random.nextInt(7), 6, random.nextInt(10),
                random.nextFloat() * 2f - 1f, 0.5f, Math.abs(random.nextLong()));
            assertTrue(normalResult >= 0, "normal-tier result must be non-negative (bit 63 clear)");
        }

        for (int i = 0; i < 500; i++) {
            // Random offsets from the origin, always within the near-tier radius.
            SectionPos section = new SectionPos(0,
                random.nextInt(9) - 4, random.nextInt(9) - 4, random.nextInt(9) - 4);
            long nearResult = MeshPriority.computeWithNearTierCheck(
                section, 6, random.nextInt(10),
                0f, 0f, 0f,
                1f, 0f, 0f, 0.5f, Math.abs(random.nextLong()));
            assertTrue(nearResult < 0, "near-tier result must be negative (bit 63 set)");
        }

        // Direct comparison-direction check for several pairs.
        for (int i = 0; i < 20; i++) {
            long nearResult = MeshPriority.computeWithNearTierCheck(
                new SectionPos(0, 0, 0, 0), 6, random.nextInt(10),
                0f, 0f, 0f, 1f, 0f, 0f, 0.5f, Math.abs(random.nextLong()));
            long normalResult = MeshPriority.compute(
                random.nextInt(7), 6, random.nextInt(10),
                random.nextFloat() * 2f - 1f, 0.5f, Math.abs(random.nextLong()));

            assertTrue(Long.compare(nearResult, normalResult) < 0,
                "a near-tier value must always compare as smaller (more priority) than a normal-tier value");
        }
    }

    @Test
    void nearTierOffsetArithmetic_neverOverflowsPastZero() {
        // Directly verify the documented worst case: Long.MIN_VALUE + the maximum possible
        // 32-bit offset must remain negative (bit 63 stays set) — this is what guarantees
        // near-tier values never collide with the normal tier's non-negative value space.
        assertTrue(Long.MIN_VALUE + 0xFFFFFFFFL < 0);

        // Also exercise the real code path with a section at the edge of the near-tier
        // radius and a very large insertionSeq, to check the actual packing doesn't
        // silently overflow either.
        SectionPos section = new SectionPos(0, MeshPriority.NEAR_PLAYER_UNCONDITIONAL_RADIUS_SECTIONS, 0, 0);
        long priority = MeshPriority.computeWithNearTierCheck(
            section, 6, 0,
            0f, 0f, 0f,
            1f, 0f, 0f,
            0.5f,
            Long.MAX_VALUE);

        assertTrue(priority < 0, "near-tier result must remain negative even at maximum offset inputs");
    }
}
