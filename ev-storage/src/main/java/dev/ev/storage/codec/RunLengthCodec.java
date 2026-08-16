package dev.ev.storage.codec;

import java.util.ArrayList;
import java.util.List;

/**
 * Simple run-length encoding over a sequence of palette indices. Encodes a sequence into
 * parallel arrays of run values and run lengths; a run's length is capped at {@link #MAX_RUN}
 * (65535) so it always fits in an unsigned 16-bit field on the wire — for the 32768-voxel
 * sections this codec is used with, a single run can therefore never overflow the field, but the
 * cap is enforced defensively in case of reuse with larger inputs.
 */
public final class RunLengthCodec {

    /** Maximum representable run length (fits in an unsigned 16-bit wire field). */
    public static final int MAX_RUN = 65535;

    private RunLengthCodec() {
    }

    /** Immutable result of {@link #encode(int[])}. */
    public static final class Encoded {
        public final int[] values;
        public final int[] lengths;

        Encoded(int[] values, int[] lengths) {
            this.values = values;
            this.lengths = lengths;
        }
    }

    /**
     * Run-length encodes {@code sequence}. Adjacent equal values are collapsed into a single
     * (value, length) run; a run longer than {@link #MAX_RUN} is split into multiple runs.
     */
    public static Encoded encode(int[] sequence) {
        List<Integer> values = new ArrayList<>();
        List<Integer> lengths = new ArrayList<>();

        int i = 0;
        while (i < sequence.length) {
            int v = sequence[i];
            int runLen = 1;
            int j = i + 1;
            while (j < sequence.length && sequence[j] == v && runLen < MAX_RUN) {
                runLen++;
                j++;
            }
            values.add(v);
            lengths.add(runLen);
            i += runLen;
        }

        int[] valuesArr = new int[values.size()];
        int[] lengthsArr = new int[lengths.size()];
        for (int k = 0; k < valuesArr.length; k++) {
            valuesArr[k] = values.get(k);
            lengthsArr[k] = lengths.get(k);
        }
        return new Encoded(valuesArr, lengthsArr);
    }

    /**
     * Reconstructs the original sequence from run values/lengths produced by
     * {@link #encode(int[])}.
     *
     * @param totalLength expected total length of the reconstructed sequence (sanity-checked)
     */
    public static int[] decode(int[] values, int[] lengths, int totalLength) {
        int[] out = new int[totalLength];
        int pos = 0;
        for (int r = 0; r < values.length; r++) {
            int v = values[r];
            int len = lengths[r];
            for (int k = 0; k < len; k++) {
                out[pos++] = v;
            }
        }
        if (pos != totalLength) {
            throw new IllegalStateException(
                    "RLE decode length mismatch: expected " + totalLength + " got " + pos);
        }
        return out;
    }
}
