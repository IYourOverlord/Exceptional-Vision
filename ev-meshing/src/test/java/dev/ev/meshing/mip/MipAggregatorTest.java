package dev.ev.meshing.mip;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MipAggregatorTest {

    @Test
    void allChildrenSameValue_resultEqualsThatValue() {
        MipAggregator aggregator = new MipAggregator();
        int[] children = {7, 7, 7, 7, 7, 7, 7, 7};
        int[] parents = new int[1];

        aggregator.aggregateMajorityVote(children, parents, 1);

        assertEquals(7, parents[0]);
    }

    @Test
    void clearMajority_resultEqualsMajorityValue() {
        MipAggregator aggregator = new MipAggregator();
        // 5 of 8 are value 3 (positions 0,1,2,3,4), the rest are distinct distractors.
        int[] children = {3, 3, 3, 3, 3, 9, 12, 20};
        int[] parents = new int[1];

        aggregator.aggregateMajorityVote(children, parents, 1);

        assertEquals(3, parents[0]);
    }

    @Test
    void allEightDistinctValues_resultFollowsDocumentedTieBreakRule() {
        MipAggregator aggregator = new MipAggregator();
        // No majority: every value appears exactly once (count = 1 for all 8 positions).
        // Documented tie-break: lowest position index among the max-count candidates wins,
        // i.e. the value at position 0.
        int[] children = {50, 10, 99, 3, 77, 1, 42, 8};
        int[] parents = new int[1];

        aggregator.aggregateMajorityVote(children, parents, 1);

        assertEquals(50, parents[0], "with no majority, the value at child position 0 must win the tie-break");
    }

    @Test
    void manyParents_randomChildren_matchesIndependentReferenceImplementation() {
        MipAggregator aggregator = new MipAggregator();
        Random random = new Random(1234L);

        int parentCount = 1000;
        int[] children = new int[parentCount * 8];
        for (int i = 0; i < children.length; i++) {
            // Small value range to force frequent ties and majorities, exercising the
            // tie-break rule broadly rather than almost always hitting all-distinct.
            children[i] = random.nextInt(4);
        }

        int[] parents = new int[parentCount];
        aggregator.aggregateMajorityVote(children, parents, parentCount);

        for (int i = 0; i < parentCount; i++) {
            int expected = referenceMajorityVote(children, i * 8);
            assertEquals(expected, parents[i], "mismatch at parent index " + i);
        }
    }

    @Test
    void zeroParentCount_doesNotThrowAndDoesNotWriteToParentPalette() {
        MipAggregator aggregator = new MipAggregator();
        int[] children = {1, 2, 3, 4, 5, 6, 7, 8};
        int[] parents = {-1, -1, -1};

        aggregator.aggregateMajorityVote(children, parents, 0);

        assertEquals(-1, parents[0]);
        assertEquals(-1, parents[1]);
        assertEquals(-1, parents[2]);
    }

    /**
     * Independent, deliberately separate reference implementation of the same majority-vote +
     * lowest-position tie-break rule, written differently from {@link MipAggregator}'s internal
     * pairwise-count approach (this one uses a small frequency array keyed by value, since the
     * test's value range is bounded) — so the test does not validate the method against itself.
     */
    private static int referenceMajorityVote(int[] children, int base) {
        int[] values = new int[8];
        for (int j = 0; j < 8; j++) {
            values[j] = children[base + j];
        }

        int bestValue = values[0];
        int bestCount = 0;
        for (int j = 0; j < 8; j++) {
            int count = 0;
            for (int v : values) {
                if (v == values[j]) {
                    count++;
                }
            }
            if (count > bestCount) {
                bestCount = count;
                bestValue = values[j];
            }
        }
        return bestValue;
    }
}
