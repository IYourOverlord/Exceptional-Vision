package dev.ev.meshing.priority;

import dev.ev.api.SectionPos;

/**
 * Computes a total-order priority value for a mesh-build task, per PERFORMANCE_MATH.md
 * section A.1. Lower returned value = higher priority (processed first) — this
 * convention matches typical priority-queue/bucket-index usage where bucket 0 is
 * drained first (see MeshTaskQueue, ticket 15).
 *
 * <h2>Two-tier priority</h2>
 * Every result is one of two tiers, distinguished by the sign bit (bit 63) of the
 * returned {@code long}, per standard Java signed-long comparison (used by both
 * {@code Comparable<Long>}/{@code PriorityBlockingQueue} in ticket 15's MVP queue, and
 * unsigned-shift bucket extraction in its opt variant — see the constant Javadocs below
 * for the full argument for why this direction is correct):
 * <ul>
 *   <li><b>Near-player tier</b> (bit 63 SET, i.e. the packed value is a large-magnitude
 *       <i>negative</i> signed long): sections within {@link #NEAR_PLAYER_UNCONDITIONAL_RADIUS_SECTIONS}
 *       of the camera, at level-0 section-grid granularity. See
 *       {@link #computeWithNearTierCheck}. This guarantees such sections outrank
 *       <b>every</b> normal-tier section, regardless of the normal tier's own score —
 *       see the "empirical lesson" note on {@link #NEAR_PLAYER_PRIORITY_BASE}.</li>
 *   <li><b>Normal tier</b> (bit 63 CLEAR, i.e. a non-negative signed long): everything
 *       else, packed from four components as described below.</li>
 * </ul>
 *
 * <h2>Normal-tier bit layout (63 bits, bit 63 always clear)</h2>
 * <pre>
 *   bit  63       : tier flag — always 0 for normal tier (see above)
 *   bits 60-62 (3): lodWeight       — 0..7, smaller = finer/nearer = higher priority
 *   bits 58-59 (2): attemptWeight   — 0..3, smaller = more prior attempts (aging) = higher priority
 *   bit  57    (1): facingBonus     — 0 = in view cone (higher priority), 1 = not
 *   bits 0-56 (57): insertionSeq    — low bits of the submission counter, FIFO tie-break
 * </pre>
 * This exact layout is what ticket 15's bucket-queue implementation extracts a bucket
 * index from via unsigned right-shift of the high bits — do not change shift amounts
 * without updating that consumer.
 *
 * <h2>Near-tier packing</h2>
 * See {@link #NEAR_PLAYER_PRIORITY_BASE} and {@link #computeWithNearTierCheck}.
 */
public final class MeshPriority {

    private MeshPriority() {}

    /** attempts is capped to this value before packing — see requirement 4 of ticket 14. */
    private static final int ATTEMPT_CAP = 3;

    private static final int LOD_WEIGHT_SHIFT = 60;
    private static final int ATTEMPT_WEIGHT_SHIFT = 58;
    private static final int FACING_BONUS_SHIFT = 57;
    /** Low 57 bits (0-56) — leaves bits 57-63 (7 bits) for the other 3 normal-tier components. */
    private static final long INSERTION_SEQ_MASK = (1L << 57) - 1;

    /**
     * Radius (in sections at the finest LOD level) around the camera within which
     * every section is guaranteed to be prioritized above ANY section outside this
     * radius, regardless of that outside section's own priority score — see the
     * "two-tier priority" empirical lesson from an independent implementation of
     * this same mod concept (Exceptional Vision), which found via real playtesting
     * that a pure distance/angle-weighted formula alone leaves a ring of unprocessed
     * holes immediately around the player when the outer (farther) region backlog
     * is large, because "near player" and "far but high angular priority" can
     * otherwise compete on close-enough scores for the near tier to lose sometimes.
     */
    public static final int NEAR_PLAYER_UNCONDITIONAL_RADIUS_SECTIONS = 4; // tune via config, not hardcoded in production

    /**
     * Priority base for the near-player unconditional tier: {@code Long.MIN_VALUE}.
     *
     * <p><b>IMPLEMENTATION REQUIREMENT</b> (not just a design note — this is how correctness
     * is actually guaranteed, independent of the normal tier's own lodWeight/attemptWeight/
     * facingBonus/insertionSeq packing): bit 63 (the sign bit of the returned {@code long})
     * is the TIER FLAG, with this EXACT direction (verified against standard Java signed
     * long comparison, which is what both {@code MeshTaskQueue} implementations — ticket 15
     * MVP's {@code PriorityBlockingQueue} via {@code Comparable}, and the opt version's
     * bucket-index extraction via unsigned right-shift — ultimately rely on for ordering):
     * <ul>
     *   <li>NEAR-TIER results: bit 63 SET (produced here by adding a small non-negative
     *       offset to {@code Long.MIN_VALUE}, keeping the packed value a large-magnitude
     *       NEGATIVE signed long).</li>
     *   <li>NORMAL-TIER results: bit 63 CLEAR (a non-negative signed long, produced
     *       naturally by the normal-tier packing above, none of whose fields extend into
     *       bit 63 — see the bit layout table on the class Javadoc).</li>
     * </ul>
     *
     * <p>Why this direction, not the reverse: under standard Java signed long comparison,
     * any negative long is LESS THAN any non-negative long. Since this class's convention
     * is "lower = higher priority", a near-tier value (negative) therefore always compares
     * as higher priority than ANY normal-tier value (non-negative), regardless of the
     * specific numeric value either side's remaining 63 bits happen to encode. This is the
     * opposite of what a naive reading of "reserve a flag bit" might suggest — do not swap
     * the direction without re-deriving this argument from scratch.
     *
     * <p><b>Arithmetic safety</b>: the offset added to {@code Long.MIN_VALUE} in
     * {@link #computeWithNearTierCheck} is built from the low 32 bits only (20 bits of
     * quantized squared-distance-in-blocks, 12 bits of masked insertionSeq — see that
     * method), so its maximum possible value is {@code 0xFFFFFFFFL} (~4.29e9). Against
     * {@code Long.MIN_VALUE}'s magnitude of {@code 2^63} (~9.22e18), this leaves an
     * enormous safety margin — {@code Long.MIN_VALUE + 0xFFFFFFFFL} cannot wrap back into
     * positive territory. Verified explicitly in {@code MeshPriorityTest}, not just by
     * inspection.
     */
    private static final long NEAR_PLAYER_PRIORITY_BASE = Long.MIN_VALUE;

    /** Bits used for the quantized squared-distance component of the near-tier offset. */
    private static final long NEAR_TIER_DISTANCE_MASK = 0xFFFFFL; // low 20 bits
    private static final int NEAR_TIER_DISTANCE_SHIFT = 12;
    /** Bits used for the insertionSeq tie-break component of the near-tier offset. */
    private static final long NEAR_TIER_SEQ_MASK = 0xFFFL; // low 12 bits

    /**
     * @param lodLevel this section's LOD level (0 = finest)
     * @param maxLodLevel the coarsest LOD level currently in use for this world (usually SectionPos.MAX_LOD_LEVEL)
     * @param attempts number of prior attempts at building this section (0 for first try)
     * @param cosAngleToViewDir dot product of normalized(sectionCenter - camera) and camera's
     *        forward vector; range [-1, 1], 1 = dead center of view, -1 = directly behind
     * @param facingThreshold cosine threshold below which a section is considered "not facing"
     *        (e.g. cos(60 degrees) as a generous view-cone bound — tune via config, not hardcoded here)
     * @param insertionSeq monotonically increasing counter assigned at task-submission time
     */
    public static long compute(int lodLevel, int maxLodLevel, int attempts,
                                float cosAngleToViewDir, float facingThreshold, long insertionSeq) {
        long lodWeight;
        if (maxLodLevel <= 0) {
            lodWeight = 0;
        } else {
            // Scale lodLevel (0..maxLodLevel) into the fixed 3-bit range (0..7) so the
            // priority queue's bucket distribution (ticket 15) isn't limited to only
            // maxLodLevel+1 out of 8 possible bucket values when maxLodLevel < 7. Monotonic
            // in lodLevel for fixed maxLodLevel, preserving "finer = smaller = higher priority".
            lodWeight = Math.round((lodLevel * 7.0) / maxLodLevel);
        }
        lodWeight = Math.max(0, Math.min(lodWeight, 7));

        int cappedAttempts = Math.min(Math.max(attempts, 0), ATTEMPT_CAP);
        long attemptWeight = ATTEMPT_CAP - cappedAttempts;

        long facingBonus = (cosAngleToViewDir >= facingThreshold) ? 0L : 1L;

        long seqBits = insertionSeq & INSERTION_SEQ_MASK;

        return (lodWeight << LOD_WEIGHT_SHIFT)
            | (attemptWeight << ATTEMPT_WEIGHT_SHIFT)
            | (facingBonus << FACING_BONUS_SHIFT)
            | seqBits;
    }

    /**
     * Convenience overload computing cosAngleToViewDir internally from raw vectors,
     * for callers that have not already computed it (avoids duplicating the dot-product
     * math at every call site).
     *
     * <p>{@code (viewDirX, viewDirY, viewDirZ)} is assumed already normalized.
     */
    public static long compute(SectionPos section, int maxLodLevel, int attempts,
                                float cameraX, float cameraY, float cameraZ,
                                float viewDirX, float viewDirY, float viewDirZ,
                                float facingThreshold, long insertionSeq) {
        float cosAngleToViewDir = cosAngleToViewDir(section, cameraX, cameraY, cameraZ,
            viewDirX, viewDirY, viewDirZ);
        return compute(section.level(), maxLodLevel, attempts, cosAngleToViewDir, facingThreshold, insertionSeq);
    }

    /**
     * Primary entry point for callers that already have a camera position and section
     * center in world space (e.g. ticket 27's render/scheduling code) — wraps both the
     * near-player unconditional tier check AND delegates to the appropriate compute()
     * overload above for the normal-tier path. Callers should use THIS method, not the
     * two compute() overloads directly, unless they have a specific reason to bypass
     * the near-tier check (document any such reason at the call site if it occurs).
     */
    public static long computeWithNearTierCheck(
        SectionPos section, int maxLodLevel, int attempts,
        float cameraX, float cameraY, float cameraZ,
        float viewDirX, float viewDirY, float viewDirZ,
        float facingThreshold, long insertionSeq
    ) {
        float centerX = section.minBlockX() + section.sizeInBlocks() / 2f;
        float centerY = section.minBlockY() + section.sizeInBlocks() / 2f;
        float centerZ = section.minBlockZ() + section.sizeInBlocks() / 2f;

        // Level-0 section-grid distance, per requirement: convert both camera and section
        // center to level-0 (32-block) section-grid units before comparing, regardless of
        // the candidate section's own LOD level, so the radius stays meaningful independent
        // of which level a given section happens to be at.
        long sectionGridX = Math.floorDiv((long) Math.floor(centerX), 32L);
        long sectionGridY = Math.floorDiv((long) Math.floor(centerY), 32L);
        long sectionGridZ = Math.floorDiv((long) Math.floor(centerZ), 32L);

        long camGridX = Math.floorDiv((long) Math.floor(cameraX), 32L);
        long camGridY = Math.floorDiv((long) Math.floor(cameraY), 32L);
        long camGridZ = Math.floorDiv((long) Math.floor(cameraZ), 32L);

        long dGridX = Math.abs(sectionGridX - camGridX);
        long dGridY = Math.abs(sectionGridY - camGridY);
        long dGridZ = Math.abs(sectionGridZ - camGridZ);
        long chebyshevDistanceSections = Math.max(dGridX, Math.max(dGridY, dGridZ));

        if (chebyshevDistanceSections <= NEAR_PLAYER_UNCONDITIONAL_RADIUS_SECTIONS) {
            float dx = centerX - cameraX;
            float dy = centerY - cameraY;
            float dz = centerZ - cameraZ;
            long distanceSquaredBlocks = (long) (dx * dx + dy * dy + dz * dz);

            long distanceComponent = Math.min(distanceSquaredBlocks, NEAR_TIER_DISTANCE_MASK);
            long seqComponent = insertionSeq & NEAR_TIER_SEQ_MASK;
            long offset = (distanceComponent << NEAR_TIER_DISTANCE_SHIFT) | seqComponent;

            return NEAR_PLAYER_PRIORITY_BASE + offset;
        }

        return compute(section, maxLodLevel, attempts, cameraX, cameraY, cameraZ,
            viewDirX, viewDirY, viewDirZ, facingThreshold, insertionSeq);
    }

    private static float cosAngleToViewDir(SectionPos section,
                                            float cameraX, float cameraY, float cameraZ,
                                            float viewDirX, float viewDirY, float viewDirZ) {
        float centerX = section.minBlockX() + section.sizeInBlocks() / 2f;
        float centerY = section.minBlockY() + section.sizeInBlocks() / 2f;
        float centerZ = section.minBlockZ() + section.sizeInBlocks() / 2f;

        float dx = centerX - cameraX;
        float dy = centerY - cameraY;
        float dz = centerZ - cameraZ;
        float lenSq = dx * dx + dy * dy + dz * dz;

        if (lenSq < 1e-8f) {
            // Camera is (numerically) at the section's own center; direction is undefined,
            // so treat it as fully facing rather than dividing by ~zero.
            return 1f;
        }

        float invLen = 1f / (float) Math.sqrt(lenSq);
        return dx * invLen * viewDirX + dy * invLen * viewDirY + dz * invLen * viewDirZ;
    }
}
