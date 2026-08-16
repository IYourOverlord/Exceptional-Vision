package dev.ev.storage.schema;

import java.nio.ByteBuffer;

/**
 * Header of an EV "region file" — a single on-disk file holding multiple encoded sections
 * (see {@code dev.ev.storage.codec.PaletteCodec} for the per-section payload format, ticket 06).
 * This record (together with {@link #parse(byte[])} / {@link #toBytes()}) is the single source
 * of truth for the header byte layout; no other class should re-derive or duplicate these
 * offsets/magic bytes.
 *
 * <h2>Wire format</h2>
 * All multi-byte integer fields are big-endian. Total header size is exactly
 * {@link #HEADER_SIZE_BYTES} (12) bytes, followed elsewhere (outside this class's concern) by a
 * per-section index and the {@code PaletteCodec}-encoded section payloads themselves:
 * <pre>
 *   [0..3]   byte[4]  magic = {@link #MAGIC} ("HZR\0")
 *   [4..5]   u16      schemaVersion (big-endian unsigned short, matches {@link SchemaVersion})
 *   [6]      u8       compressionCodec — 0 = none, 1 = deflate (reserved; only "none" is
 *                      actually written/read by this ticket, deflate support is a future
 *                      extension that reuses this same field)
 *   [7]      u8       reserved, always 0 on write; readers must not assume any particular value
 *                      on read (future flags may repurpose this byte, gated by schemaVersion)
 *   [8..11]  i32      sectionCount — number of section index entries / payloads that follow the
 *                      header in the rest of the file
 * </pre>
 *
 * <p>This class only parses/serializes the fixed 12-byte header itself; the section index and
 * payload layout that follow it are out of scope for this record.
 */
public record RegionFileHeader(int schemaVersion, int compressionCodec, int sectionCount) {

    /** Magic bytes identifying an EV region file: {@code "HZR\0"}. */
    public static final byte[] MAGIC = {'H', 'Z', 'R', 0};

    /** Fixed header size in bytes: magic(4) + version(2) + codec(1) + reserved(1) + count(4). */
    public static final int HEADER_SIZE_BYTES = 12;

    private static final int COMPRESSION_NONE = 0;
    private static final int COMPRESSION_DEFLATE = 1;

    public RegionFileHeader {
        if (schemaVersion < 0 || schemaVersion > 0xFFFF) {
            throw new IllegalArgumentException("schemaVersion out of u16 range: " + schemaVersion);
        }
        if (compressionCodec != COMPRESSION_NONE && compressionCodec != COMPRESSION_DEFLATE) {
            throw new IllegalArgumentException("Unknown compressionCodec: " + compressionCodec);
        }
        if (sectionCount < 0) {
            throw new IllegalArgumentException("sectionCount must be non-negative: " + sectionCount);
        }
    }

    /**
     * Parses the header from the start of a region file byte array.
     *
     * @throws IllegalArgumentException if {@code data} is shorter than {@link #HEADER_SIZE_BYTES}
     *         or if the leading bytes do not match {@link #MAGIC} (file is corrupt or not an EV
     *         region file)
     */
    public static RegionFileHeader parse(byte[] data) {
        if (data == null || data.length < HEADER_SIZE_BYTES) {
            throw new IllegalArgumentException(
                    "Region file data too short for header: need at least " + HEADER_SIZE_BYTES
                            + " bytes, got " + (data == null ? "null" : data.length));
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (data[i] != MAGIC[i]) {
                throw new IllegalArgumentException(
                        "Bad region file magic bytes at offset " + i + ": expected " + MAGIC[i]
                                + " got " + data[i] + " — not an EV region file or file is corrupt");
            }
        }

        ByteBuffer buf = ByteBuffer.wrap(data, MAGIC.length, HEADER_SIZE_BYTES - MAGIC.length);
        int version = buf.getShort() & 0xFFFF;
        int codec = buf.get() & 0xFF;
        buf.get(); // reserved, ignored on read
        int sectionCount = buf.getInt();
        return new RegionFileHeader(version, codec, sectionCount);
    }

    /** Serializes this header (does not include the section index/payload that follow it). */
    public byte[] toBytes() {
        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE_BYTES);
        buf.put(MAGIC);
        buf.putShort((short) schemaVersion);
        buf.put((byte) compressionCodec);
        buf.put((byte) 0); // reserved
        buf.putInt(sectionCount);
        return buf.array();
    }
}
