package dev.ev.storage.codec;

/**
 * Z-order (Morton) curve encoding/decoding for 3D coordinates in the range {@code [0, 32)},
 * i.e. 5 bits per axis, producing a 15-bit interleaved Morton code in {@code [0, 32768)}.
 *
 * <p>Bit layout of the resulting code (bit 0 = least significant): for coordinate bit index
 * {@code i} (0..4), the interleaved code places {@code x}'s bit at position {@code 3*i},
 * {@code y}'s bit at position {@code 3*i + 1}, and {@code z}'s bit at position {@code 3*i + 2}.
 * This is the standard "bit interleaving" scheme used for Z-order curves.
 *
 * <p><b>Implementation choice:</b> a simple 5-iteration bit-extraction loop is used rather than
 * the classic magic-number "bit spreading" trick (e.g. Fixed-point spreading via
 * {@code (x | (x << 8)) & mask} chains). For a 10-bit-wide magic-number spread applied to only
 * 5 significant bits, the loop and the magic-number approach are both O(1) with a small constant
 * factor; the loop avoids the risk of subtly wrong masks when truncating a spread designed for a
 * wider range down to 5 bits, and JIT compilation trivially unrolls a fixed 5-iteration loop.
 * Given the input range is fixed and small (each axis 0..31), the readability/correctness
 * trade-off favors the loop. This choice is documented here per the codec's design requirements.
 */
public final class MortonCode {

    private MortonCode() {
    }

    /**
     * Interleaves the low 5 bits of {@code x}, {@code y}, {@code z} into a single Morton code.
     *
     * @param x coordinate in [0, 32)
     * @param y coordinate in [0, 32)
     * @param z coordinate in [0, 32)
     * @return interleaved 15-bit Morton code in [0, 32768)
     */
    public static int mortonEncode3(int x, int y, int z) {
        int m = 0;
        for (int i = 0; i < 5; i++) {
            m |= ((x >>> i) & 1) << (3 * i);
            m |= ((y >>> i) & 1) << (3 * i + 1);
            m |= ((z >>> i) & 1) << (3 * i + 2);
        }
        return m;
    }

    /**
     * Inverse of {@link #mortonEncode3(int, int, int)}.
     *
     * @param morton Morton code in [0, 32768)
     * @return a 3-element array {@code {x, y, z}}, each in [0, 32)
     */
    public static int[] mortonDecode3(int morton) {
        int x = 0;
        int y = 0;
        int z = 0;
        for (int i = 0; i < 5; i++) {
            x |= ((morton >>> (3 * i)) & 1) << i;
            y |= ((morton >>> (3 * i + 1)) & 1) << i;
            z |= ((morton >>> (3 * i + 2)) & 1) << i;
        }
        return new int[] {x, y, z};
    }
}
