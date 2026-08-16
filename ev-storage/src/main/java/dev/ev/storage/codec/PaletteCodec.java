package dev.ev.storage.codec;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lossless codec for a flat {@code 32x32x32} (32768-element) array of non-negative palette
 * indices, as produced by a single world section (see {@code WorldSectionHandle}, ticket 02).
 * This class is agnostic of blockstates/Minecraft — it only ever sees already-resolved integer
 * indices; the mapping from those indices to actual block materials lives in a higher-level
 * global palette, outside this codec's concern.
 *
 * <h2>Wire format</h2>
 * All multi-byte integers are big-endian (standard {@link ByteBuffer} order). The first 4 bytes
 * of every encoded blob are always a tag identifying which sub-format follows, so future codecs
 * can be added (see ticket 07, schema versioning) without breaking existing readers:
 * <ul>
 *   <li>{@code SINGLE_VALUE = 0}</li>
 *   <li>{@code PALETTE_RLE = 1}</li>
 * </ul>
 *
 * <h3>SINGLE_VALUE (tag 0) — fast path for a fully uniform section</h3>
 * The single most common case, especially on coarse LOD levels where a section aggregates a
 * huge amount of world into one voxel value (typically all-air, or all-one-material). Format is
 * fixed 8 bytes total:
 * <pre>
 *   [0..3]  int  tag = 0
 *   [4..7]  int  value  (the single repeated voxel value)
 * </pre>
 *
 * <h3>PALETTE_RLE (tag 1) — general case</h3>
 * <ol>
 *   <li><b>Local palette:</b> the list of distinct values occurring in the section, in
 *       encounter order over the flat input array — except that if value {@code 0} (air)
 *       occurs anywhere in the section, it is always forced to palette index 0, regardless of
 *       where it was first encountered. This keeps "voxel value 0" and "palette index 0" in
 *       correspondence whenever air is present, which downstream code can rely on.</li>
 *   <li><b>bitsPerIndex</b> = {@code max(1, ceil(log2(paletteSize)))} bits are used to encode
 *       each palette index (see {@link BitPackedArray#bitsForPaletteSize(int)}). Not stored on
 *       the wire — the decoder recomputes it from {@code paletteSize}.</li>
 *   <li><b>Traversal order:</b> before run-length encoding, the palette-index grid is
 *       re-ordered from flat XYZ order into Z-order (Morton) order via
 *       {@link MortonCode#mortonEncode3(int, int, int)}. Z-order keeps 3D-adjacent voxels close
 *       together in the 1D traversal, which lengthens runs of identical values for typical
 *       "blocky" world structure compared to row-major (XYZ) order, improving RLE compression.</li>
 *   <li><b>RLE:</b> the Morton-ordered index sequence is run-length encoded
 *       (see {@link RunLengthCodec}) into parallel {@code (value, length)} arrays.</li>
 * </ol>
 * Layout:
 * <pre>
 *   [0..3]                              int    tag = 1
 *   [4..7]                              int    paletteSize (P)
 *   [8 .. 8+4P)                         int[P]  palette values, in the order described above
 *   [8+4P .. 12+4P)                     int    numRuns (R)
 *   [12+4P .. 12+4P+8W)                 long[W] bit-packed run values, bitsPerIndex bits each,
 *                                               W = ceil(R * bitsPerIndex / 64.0) words
 *   [12+4P+8W .. 12+4P+8W+2R)           u16[R]  run lengths (unsigned 16-bit big-endian; a run
 *                                               produced by {@link RunLengthCodec} never exceeds
 *                                               {@link RunLengthCodec#MAX_RUN} = 65535, so this
 *                                               always fits)
 * </pre>
 *
 * <p>Both paths are fully lossless: {@code decode(encode(x))} is guaranteed to be bitwise
 * identical to {@code x} for any valid input.
 */
public final class PaletteCodec {

    /** Section edge length in voxels. */
    public static final int DIM = 32;

    /** Total voxel count per section ({@code DIM^3}). */
    public static final int SECTION_SIZE = DIM * DIM * DIM;

    private static final int TAG_SINGLE_VALUE = 0;
    private static final int TAG_PALETTE_RLE = 1;

    private PaletteCodec() {
    }

    /**
     * Encodes a raw {@code 32x32x32} voxel grid (flat array, index = {@code x + y*32 + z*32*32})
     * into bytes.
     *
     * @throws IllegalArgumentException if {@code voxelsFlat.length != 32768}
     */
    public static byte[] encode(int[] voxelsFlat) {
        requireValidSize(voxelsFlat);

        if (isUniform(voxelsFlat)) {
            return encodeSingleValue(voxelsFlat[0]);
        }
        return encodePaletteRle(voxelsFlat);
    }

    /**
     * Decodes bytes produced by {@link #encode(int[])} back into a flat {@code 32x32x32} int
     * array.
     */
    public static int[] decode(byte[] data) {
        ByteBuffer buf = ByteBuffer.wrap(data);
        int tag = buf.getInt();
        switch (tag) {
            case TAG_SINGLE_VALUE:
                return decodeSingleValue(buf);
            case TAG_PALETTE_RLE:
                return decodePaletteRle(buf);
            default:
                throw new IllegalArgumentException("Unknown PaletteCodec tag: " + tag);
        }
    }

    /** True if the given voxel array is a single repeated value (fast-path check before encode()). */
    public static boolean isUniform(int[] voxelsFlat) {
        int first = voxelsFlat[0];
        for (int i = 1; i < voxelsFlat.length; i++) {
            if (voxelsFlat[i] != first) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------------
    // SINGLE_VALUE
    // ---------------------------------------------------------------------

    private static byte[] encodeSingleValue(int value) {
        ByteBuffer buf = ByteBuffer.allocate(8);
        buf.putInt(TAG_SINGLE_VALUE);
        buf.putInt(value);
        return buf.array();
    }

    private static int[] decodeSingleValue(ByteBuffer buf) {
        int value = buf.getInt();
        int[] out = new int[SECTION_SIZE];
        java.util.Arrays.fill(out, value);
        return out;
    }

    // ---------------------------------------------------------------------
    // PALETTE_RLE
    // ---------------------------------------------------------------------

    private static byte[] encodePaletteRle(int[] voxelsFlat) {
        // 1. Build local palette in encounter order, forcing value 0 (if present) to index 0.
        Map<Integer, Integer> valueToIndex = new LinkedHashMap<>();
        for (int v : voxelsFlat) {
            valueToIndex.putIfAbsent(v, valueToIndex.size());
        }
        List<Integer> palette = new ArrayList<>(valueToIndex.keySet());
        if (palette.contains(0) && palette.get(0) != 0) {
            palette.remove(Integer.valueOf(0));
            palette.add(0, 0);
        }
        // Rebuild the value->index map to match the (possibly reordered) palette list.
        valueToIndex.clear();
        for (int i = 0; i < palette.size(); i++) {
            valueToIndex.put(palette.get(i), i);
        }

        int paletteSize = palette.size();
        int bitsPerIndex = BitPackedArray.bitsForPaletteSize(paletteSize);

        // 2. Re-order palette indices into Morton (Z-order) traversal order.
        int[] mortonSeq = new int[SECTION_SIZE];
        for (int m = 0; m < SECTION_SIZE; m++) {
            int[] xyz = MortonCode.mortonDecode3(m);
            int flatIdx = xyz[0] + xyz[1] * DIM + xyz[2] * DIM * DIM;
            mortonSeq[m] = valueToIndex.get(voxelsFlat[flatIdx]);
        }

        // 3. Run-length encode the Morton-ordered index sequence.
        RunLengthCodec.Encoded rle = RunLengthCodec.encode(mortonSeq);
        int numRuns = rle.values.length;

        // 4. Bit-pack the run values (palette indices), bitsPerIndex bits each.
        long[] packedRunValues = BitPackedArray.pack(rle.values, bitsPerIndex);

        // 5. Serialize.
        int size = 4 // tag
                + 4 // paletteSize
                + 4 * paletteSize // palette values
                + 4 // numRuns
                + packedRunValues.length * 8 // packed run values
                + numRuns * 2; // run lengths (u16 each)

        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.putInt(TAG_PALETTE_RLE);
        buf.putInt(paletteSize);
        for (int v : palette) {
            buf.putInt(v);
        }
        buf.putInt(numRuns);
        for (long w : packedRunValues) {
            buf.putLong(w);
        }
        for (int len : rle.lengths) {
            buf.putShort((short) (len & 0xFFFF));
        }
        return buf.array();
    }

    private static int[] decodePaletteRle(ByteBuffer buf) {
        int paletteSize = buf.getInt();
        int[] palette = new int[paletteSize];
        for (int i = 0; i < paletteSize; i++) {
            palette[i] = buf.getInt();
        }

        int numRuns = buf.getInt();
        int bitsPerIndex = BitPackedArray.bitsForPaletteSize(paletteSize);
        int wordCount = (int) (((long) numRuns * bitsPerIndex + 63) / 64);
        long[] packedRunValues = new long[wordCount];
        for (int i = 0; i < wordCount; i++) {
            packedRunValues[i] = buf.getLong();
        }
        int[] runValues = BitPackedArray.unpack(packedRunValues, numRuns, bitsPerIndex);

        int[] runLengths = new int[numRuns];
        for (int i = 0; i < numRuns; i++) {
            runLengths[i] = buf.getShort() & 0xFFFF;
        }

        int[] mortonSeq = RunLengthCodec.decode(runValues, runLengths, SECTION_SIZE);

        int[] flat = new int[SECTION_SIZE];
        for (int m = 0; m < SECTION_SIZE; m++) {
            int[] xyz = MortonCode.mortonDecode3(m);
            int flatIdx = xyz[0] + xyz[1] * DIM + xyz[2] * DIM * DIM;
            flat[flatIdx] = palette[mortonSeq[m]];
        }
        return flat;
    }

    private static void requireValidSize(int[] voxelsFlat) {
        if (voxelsFlat == null || voxelsFlat.length != SECTION_SIZE) {
            throw new IllegalArgumentException(
                    "voxelsFlat must have exactly " + SECTION_SIZE + " elements, got "
                            + (voxelsFlat == null ? "null" : voxelsFlat.length));
        }
    }
}
