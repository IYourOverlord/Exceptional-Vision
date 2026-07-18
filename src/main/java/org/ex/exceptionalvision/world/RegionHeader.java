package org.ex.exceptionalvision.world;

/**
 * Parsed 8KiB header of an Anvil ({@code .mca}) region file.
 * <p>
 * Layout (see {@code 05_world_data_ingestion.md}):
 * <ul>
 *   <li>bytes 0..4095 — 1024 location entries (3-byte sector offset + 1-byte sector count)</li>
 *   <li>bytes 4096..8191 — 1024 big-endian int timestamps</li>
 * </ul>
 * Entry index for local chunk coordinates (0..31, 0..31) is {@code localX + localZ * 32}.
 */
public final class RegionHeader {

    public static final int SECTOR_BYTES = 4096;
    public static final int HEADER_SECTORS = 2;
    public static final int ENTRIES = 1024;

    /** Sector offset (in 4096-byte sectors) of each chunk's data; 0 = not generated. */
    private final int[] sectorOffset = new int[ENTRIES];
    /** Number of 4096-byte sectors used by each chunk; 0 = not generated. */
    private final int[] sectorCount = new int[ENTRIES];
    /** Last-modified unix timestamp (seconds) per chunk, as written by the vanilla writer. */
    private final int[] timestamp = new int[ENTRIES];

    static int indexOf(int localX, int localZ) {
        if (localX < 0 || localX > 31 || localZ < 0 || localZ > 31) {
            throw new IllegalArgumentException(
                    "Local chunk coordinates must be within 0..31, got (" + localX + ", " + localZ + ")");
        }
        return (localX & 31) + (localZ & 31) * 32;
    }

    void setLocation(int index, int sectorOffsetValue, int sectorCountValue) {
        this.sectorOffset[index] = sectorOffsetValue;
        this.sectorCount[index] = sectorCountValue;
    }

    void setTimestamp(int index, int timestampValue) {
        this.timestamp[index] = timestampValue;
    }

    public boolean isPresent(int localX, int localZ) {
        int idx = indexOf(localX, localZ);
        return sectorOffset[idx] != 0 || sectorCount[idx] != 0;
    }

    public int sectorOffset(int localX, int localZ) {
        return sectorOffset[indexOf(localX, localZ)];
    }

    public int sectorCount(int localX, int localZ) {
        return sectorCount[indexOf(localX, localZ)];
    }

    public int timestamp(int localX, int localZ) {
        return timestamp[indexOf(localX, localZ)];
    }
}
