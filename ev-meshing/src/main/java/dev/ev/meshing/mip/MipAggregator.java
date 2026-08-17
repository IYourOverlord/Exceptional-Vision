package dev.ev.meshing.mip;

/**
 * MVP (Wave 1) implementation: aggregates child voxel palette indices into parent (coarser)
 * mip-level voxels using a plain scalar Java loop computing majority-vote (most frequent value
 * among the 8 children becomes the parent's value). No SIMD/Vector API — see {@code
 * MVP_INDEX.md} for why this simple version comes first; {@code
 * 16-meshing-simd-mipgen-opt.md} replaces this only if profiling ({@code
 * P0-profiling-checkpoint.md}) actually shows mip-aggregation as a meaningful fraction of
 * cold-start time — not preemptively.
 *
 * <p>The public method signature intentionally matches the opt version's {@code
 * MipAggregator} so that swapping implementations later (if profiling justifies it) does not
 * require changing calling code.
 *
 * <p><b>Tie-break rule (must stay identical between this MVP version and the opt version):</b>
 * when multiple candidate values among the 8 children share the same maximum count, the value
 * at the lowest child-position index wins — i.e. positions are scanned in order 0..7, and the
 * first value to reach the maximum count observed so far is kept. This is deterministic,
 * independent of the numeric value of the palette indices themselves (which would be an
 * arbitrary, less intuitive criterion), and is straightforward to implement identically in
 * both the scalar (this class) and SIMD (opt) forms, since SIMD candidate lanes naturally
 * process children in the same fixed positional order.
 */
public final class MipAggregator {

    private static final int CHILDREN_PER_PARENT = 8;

    /**
     * @param childPalette flat array of {@code 8*parentCount} values, where each contiguous
     *        run of 8 represents the children of one parent voxel (octree child index 0..7
     *        order matching {@code SectionPos.child(childIndex)}'s bit convention from ticket
     *        01)
     * @param parentPalette output array of parent values; must be pre-allocated by the caller
     *        with length &gt;= {@code parentCount}. Only indices {@code [0, parentCount)} are
     *        written; nothing beyond that range is touched.
     * @param parentCount number of parent voxels to produce, N. If 0, this method is a no-op:
     *        it does not throw and does not write anything to {@code parentPalette}.
     */
    public void aggregateMajorityVote(int[] childPalette, int[] parentPalette, int parentCount) {
        for (int i = 0; i < parentCount; i++) {
            int base = i * CHILDREN_PER_PARENT;
            parentPalette[i] = majorityVoteOf8(childPalette, base);
        }
    }

    /**
     * Computes the majority-vote winner among the 8 children starting at {@code base} in
     * {@code childPalette}, using a direct O(8*8)=O(64) pairwise count per parent — deliberately
     * avoiding a per-call {@code HashMap} allocation to keep GC pressure low even in this MVP
     * version, per requirement 2 of the ticket.
     *
     * <p>For each of the 8 candidate positions, counts how many of the 8 children (including
     * itself) share its value, then keeps the first candidate (lowest position index) whose
     * count strictly exceeds the best count seen so far — this naturally implements the
     * documented lowest-position tie-break rule: later positions with an equal (not strictly
     * greater) count never displace an earlier winner.
     */
    private static int majorityVoteOf8(int[] childPalette, int base) {
        int bestValue = childPalette[base];
        int bestCount = 0;

        for (int j = 0; j < CHILDREN_PER_PARENT; j++) {
            int candidate = childPalette[base + j];
            int count = 0;
            for (int k = 0; k < CHILDREN_PER_PARENT; k++) {
                if (childPalette[base + k] == candidate) {
                    count++;
                }
            }
            if (count > bestCount) {
                bestCount = count;
                bestValue = candidate;
            }
        }

        return bestValue;
    }
}
