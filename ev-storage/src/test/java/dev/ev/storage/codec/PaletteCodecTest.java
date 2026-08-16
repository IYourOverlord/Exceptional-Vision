package dev.ev.storage.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class PaletteCodecTest {

    private static final int SIZE = PaletteCodec.SECTION_SIZE;
    private static final int DIM = PaletteCodec.DIM;

    // -------------------------------------------------------------------
    // Round-trip tests
    // -------------------------------------------------------------------

    @Test
    void roundTrip_allZeros() {
        int[] voxels = new int[SIZE]; // all zeros (air)
        assertRoundTrip(voxels);
    }

    @Test
    void roundTrip_uniformNonZero() {
        int[] voxels = new int[SIZE];
        java.util.Arrays.fill(voxels, 42);
        assertRoundTrip(voxels);
    }

    @Test
    void roundTrip_randomFewUniqueValues() {
        Random rnd = new Random(1234L);
        int[] palette = {0, 3, 7, 11, 19, 25, 100, 4242};
        int uniqueCount = 5 + rnd.nextInt(6); // 5-10
        int[] voxels = new int[SIZE];
        for (int i = 0; i < SIZE; i++) {
            voxels[i] = palette[rnd.nextInt(uniqueCount)];
        }
        assertRoundTrip(voxels);
    }

    @Test
    void roundTrip_checkerboard() {
        // Worst case for RLE: alternate between two values as much as possible.
        int[] voxels = new int[SIZE];
        for (int x = 0; x < DIM; x++) {
            for (int y = 0; y < DIM; y++) {
                for (int z = 0; z < DIM; z++) {
                    int idx = x + y * DIM + z * DIM * DIM;
                    voxels[idx] = ((x + y + z) % 2 == 0) ? 0 : 1;
                }
            }
        }
        assertRoundTrip(voxels);
    }

    @Test
    void roundTrip_twoUniqueValues_bitsPerIndexOne() {
        int[] voxels = new int[SIZE];
        for (int i = 0; i < SIZE; i++) {
            voxels[i] = (i < SIZE / 2) ? 0 : 7;
        }
        assertRoundTrip(voxels);

        byte[] encoded = PaletteCodec.encode(voxels);
        // Sanity: tag must be PALETTE_RLE (not uniform).
        int tag = java.nio.ByteBuffer.wrap(encoded).getInt();
        assertEquals(1, tag);
    }

    private static void assertRoundTrip(int[] voxels) {
        byte[] encoded = PaletteCodec.encode(voxels);
        int[] decoded = PaletteCodec.decode(encoded);
        assertArrayEquals(voxels, decoded);
    }

    // -------------------------------------------------------------------
    // isUniform
    // -------------------------------------------------------------------

    @Test
    void isUniform_trueForAllSameValue() {
        int[] voxels = new int[SIZE];
        java.util.Arrays.fill(voxels, 5);
        assertTrue(PaletteCodec.isUniform(voxels));
    }

    @Test
    void isUniform_falseWhenOneDiffers() {
        int[] voxels = new int[SIZE];
        java.util.Arrays.fill(voxels, 5);
        voxels[SIZE - 1] = 6;
        assertFalse(PaletteCodec.isUniform(voxels));
    }

    @Test
    void isUniform_falseForCheckerboard() {
        int[] voxels = new int[SIZE];
        for (int i = 0; i < SIZE; i++) {
            voxels[i] = i % 2;
        }
        assertFalse(PaletteCodec.isUniform(voxels));
    }

    // -------------------------------------------------------------------
    // Input validation
    // -------------------------------------------------------------------

    @Test
    void encode_throwsOnWrongSize() {
        int[] tooShort = new int[100];
        assertThrows(IllegalArgumentException.class, () -> PaletteCodec.encode(tooShort));

        int[] tooLong = new int[SIZE + 1];
        assertThrows(IllegalArgumentException.class, () -> PaletteCodec.encode(tooLong));
    }

    // -------------------------------------------------------------------
    // Morton code round-trip / correctness
    // -------------------------------------------------------------------

    @Test
    void morton_roundTripAllCoordinates() {
        for (int x = 0; x < 32; x++) {
            for (int y = 0; y < 32; y++) {
                for (int z = 0; z < 32; z++) {
                    int code = MortonCode.mortonEncode3(x, y, z);
                    int[] decoded = MortonCode.mortonDecode3(code);
                    assertEquals(x, decoded[0], "x mismatch for code " + code);
                    assertEquals(y, decoded[1], "y mismatch for code " + code);
                    assertEquals(z, decoded[2], "z mismatch for code " + code);
                }
            }
        }
    }

    @Test
    void morton_knownValues() {
        // (0,0,0) -> 0
        assertEquals(0, MortonCode.mortonEncode3(0, 0, 0));
        // x=1,y=0,z=0 -> bit 0 set -> 1
        assertEquals(1, MortonCode.mortonEncode3(1, 0, 0));
        // x=0,y=1,z=0 -> bit 1 set -> 2
        assertEquals(2, MortonCode.mortonEncode3(0, 1, 0));
        // x=0,y=0,z=1 -> bit 2 set -> 4
        assertEquals(4, MortonCode.mortonEncode3(0, 0, 1));
        // x=1,y=1,z=1 -> bits 0,1,2 set -> 7
        assertEquals(7, MortonCode.mortonEncode3(1, 1, 1));
        // x=31,y=0,z=0 -> bits 0,3,6,9,12 set -> 0b001001001001001 = 4681
        assertEquals(4681, MortonCode.mortonEncode3(31, 0, 0));
        // Max coordinate on all axes -> all 15 bits set -> 32767
        assertEquals(32767, MortonCode.mortonEncode3(31, 31, 31));

        assertArrayEquals(new int[] {31, 0, 0}, MortonCode.mortonDecode3(4681));
        assertArrayEquals(new int[] {31, 31, 31}, MortonCode.mortonDecode3(32767));
    }

    // -------------------------------------------------------------------
    // Compression sanity check
    // -------------------------------------------------------------------

    @Test
    void compression_uniformSectionIsTiny() {
        int[] voxels = new int[SIZE];
        java.util.Arrays.fill(voxels, 9);
        byte[] encoded = PaletteCodec.encode(voxels);

        int naiveSize = SIZE * 4; // 32768 * 4 bytes
        assertTrue(encoded.length < 16,
                "uniform section should encode to well under 16 bytes, was " + encoded.length);
        assertTrue(encoded.length < naiveSize);
    }
}
