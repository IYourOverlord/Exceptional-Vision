package dev.ev.storage.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RegionFileHeaderTest {

    @Test
    void roundTrip_toBytesThenParse() {
        RegionFileHeader original = new RegionFileHeader(SchemaVersion.CURRENT, 0, 42);

        byte[] bytes = original.toBytes();
        RegionFileHeader parsed = RegionFileHeader.parse(bytes);

        assertEquals(original.schemaVersion(), parsed.schemaVersion());
        assertEquals(original.compressionCodec(), parsed.compressionCodec());
        assertEquals(original.sectionCount(), parsed.sectionCount());
        assertEquals(RegionFileHeader.HEADER_SIZE_BYTES, bytes.length);
    }

    @Test
    void roundTrip_zeroSections() {
        RegionFileHeader original = new RegionFileHeader(1, 0, 0);

        RegionFileHeader parsed = RegionFileHeader.parse(original.toBytes());

        assertEquals(original, parsed);
    }

    @Test
    void parse_throwsOnBadMagic() {
        byte[] bytes = new RegionFileHeader(1, 0, 5).toBytes();
        bytes[0] = 'X'; // corrupt the first magic byte

        assertThrows(IllegalArgumentException.class, () -> RegionFileHeader.parse(bytes));
    }

    @Test
    void parse_throwsOnTooShortArray() {
        byte[] tooShort = new byte[RegionFileHeader.HEADER_SIZE_BYTES - 1];

        assertThrows(IllegalArgumentException.class, () -> RegionFileHeader.parse(tooShort));
    }

    @Test
    void parse_throwsOnNull() {
        assertThrows(IllegalArgumentException.class, () -> RegionFileHeader.parse(null));
    }
}
