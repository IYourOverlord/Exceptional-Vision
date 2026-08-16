package dev.ev.storage.codec;

/**
 * Densely packs a fixed-width array of non-negative integers into a {@code long[]}, using
 * exactly {@code bitsPerIndex} bits per entry with no padding between entries (values may
 * straddle a {@code long} boundary). This mirrors the well-known technique used by Minecraft's
 * own chunk section packing, re-implemented independently here.
 *
 * <p>Entry {@code i} occupies bits {@code [i * bitsPerIndex, (i + 1) * bitsPerIndex)} of the
 * conceptual bit stream formed by concatenating the {@code long} words in array order,
 * least-significant bit first within each word.
 */
public final class BitPackedArray {

    private BitPackedArray() {
    }

    /**
     * Packs {@code values} using {@code bitsPerIndex} bits each.
     *
     * @param values       values to pack, each must fit in {@code bitsPerIndex} bits
     *                     (i.e. {@code 0 <= values[i] < (1 << bitsPerIndex)})
     * @param bitsPerIndex bits per entry, in [1, 32]
     * @return packed long words, sized to hold exactly {@code values.length} entries
     */
    public static long[] pack(int[] values, int bitsPerIndex) {
        if (bitsPerIndex < 1 || bitsPerIndex > 32) {
            throw new IllegalArgumentException("bitsPerIndex out of range: " + bitsPerIndex);
        }
        long totalBits = (long) values.length * bitsPerIndex;
        int wordCount = (int) ((totalBits + 63) / 64);
        long[] words = new long[Math.max(wordCount, 0)];
        long mask = (bitsPerIndex == 32) ? 0xFFFFFFFFL : ((1L << bitsPerIndex) - 1);

        for (int i = 0; i < values.length; i++) {
            long bitIndex = (long) i * bitsPerIndex;
            int wordIndex = (int) (bitIndex / 64);
            int bitOffset = (int) (bitIndex % 64);
            long v = values[i] & mask;

            words[wordIndex] |= (v << bitOffset);
            int bitsWrittenInFirstWord = 64 - bitOffset;
            if (bitsWrittenInFirstWord < bitsPerIndex) {
                // Entry straddles into the next word.
                words[wordIndex + 1] |= (v >>> bitsWrittenInFirstWord);
            }
        }
        return words;
    }

    /**
     * Unpacks {@code count} entries of {@code bitsPerIndex} bits each from {@code words},
     * produced by {@link #pack(int[], int)}.
     *
     * @param words        packed long words
     * @param count        number of entries to extract
     * @param bitsPerIndex bits per entry, in [1, 32]
     * @return unpacked values, length {@code count}
     */
    public static int[] unpack(long[] words, int count, int bitsPerIndex) {
        if (bitsPerIndex < 1 || bitsPerIndex > 32) {
            throw new IllegalArgumentException("bitsPerIndex out of range: " + bitsPerIndex);
        }
        long mask = (bitsPerIndex == 32) ? 0xFFFFFFFFL : ((1L << bitsPerIndex) - 1);
        int[] result = new int[count];

        for (int i = 0; i < count; i++) {
            long bitIndex = (long) i * bitsPerIndex;
            int wordIndex = (int) (bitIndex / 64);
            int bitOffset = (int) (bitIndex % 64);

            long v = words[wordIndex] >>> bitOffset;
            int bitsReadFromFirstWord = 64 - bitOffset;
            if (bitsReadFromFirstWord < bitsPerIndex) {
                v |= (words[wordIndex + 1] << bitsReadFromFirstWord);
            }
            result[i] = (int) (v & mask);
        }
        return result;
    }

    /** Number of bits needed to represent {@code paletteSize} distinct indices, minimum 1. */
    public static int bitsForPaletteSize(int paletteSize) {
        if (paletteSize <= 1) {
            return 1;
        }
        return 32 - Integer.numberOfLeadingZeros(paletteSize - 1);
    }
}
