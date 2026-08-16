package dev.ev.storage.schema;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class SchemaMigrationChainTest {

    /** Hypothetical test-only migrator 1 -> 2: appends a marker byte to the payload. */
    private static final class TestV1ToV2Migrator implements SchemaMigrator {
        @Override
        public int fromVersion() {
            return 1;
        }

        @Override
        public byte[] migrate(byte[] regionData) {
            return appendMarker(regionData, (byte) 'A');
        }
    }

    /** Hypothetical test-only migrator 2 -> 3: appends a different marker byte. */
    private static final class TestV2ToV3Migrator implements SchemaMigrator {
        @Override
        public int fromVersion() {
            return 2;
        }

        @Override
        public byte[] migrate(byte[] regionData) {
            return appendMarker(regionData, (byte) 'B');
        }
    }

    private static byte[] appendMarker(byte[] data, byte marker) {
        byte[] out = new byte[data.length + 1];
        System.arraycopy(data, 0, out, 0, data.length);
        out[data.length] = marker;
        return out;
    }

    @Test
    void migrateToCurrent_identityWhenAlreadyCurrent() {
        SchemaMigrationChain chain = new SchemaMigrationChain(List.of());
        byte[] original = "payload".getBytes(StandardCharsets.UTF_8);

        byte[] result = chain.migrateToCurrent(SchemaVersion.CURRENT, original);

        assertArrayEquals(original, result);
    }

    @Test
    void migrateTo_appliesChainInOrder() {
        // Chain-logic test, deliberately independent of SchemaVersion.CURRENT (which is 1):
        // uses the general migrateTo(from, to, data) form with a hypothetical target of 3.
        SchemaMigrationChain chain =
                new SchemaMigrationChain(List.of(new TestV1ToV2Migrator(), new TestV2ToV3Migrator()));
        byte[] original = "x".getBytes(StandardCharsets.UTF_8);

        byte[] result = chain.migrateTo(1, 3, original);

        // Expect original bytes, then marker 'A' (1->2), then marker 'B' (2->3), in that order.
        byte[] expected = {'x', 'A', 'B'};
        assertArrayEquals(expected, result);
    }

    @Test
    void migrateToCurrent_throwsWhenBelowMinSupported() {
        SchemaMigrationChain chain = new SchemaMigrationChain(List.of());
        byte[] data = new byte[0];

        assertThrows(UnsupportedSchemaException.class,
                () -> chain.migrateToCurrent(SchemaVersion.MIN_SUPPORTED - 1, data));
    }

    @Test
    void migrateTo_throwsOnGapInChain() {
        // Only 1->2 is registered; asking to reach 3 requires a missing 2->3 step.
        SchemaMigrationChain chain = new SchemaMigrationChain(List.of(new TestV1ToV2Migrator()));
        byte[] data = "x".getBytes(StandardCharsets.UTF_8);

        assertThrows(UnsupportedSchemaException.class, () -> chain.migrateTo(1, 3, data));
    }

    @Test
    void migrateTo_throwsWhenFromVersionAboveTarget() {
        SchemaMigrationChain chain = new SchemaMigrationChain(List.of());
        byte[] data = "x".getBytes(StandardCharsets.UTF_8);

        assertThrows(UnsupportedSchemaException.class, () -> chain.migrateTo(3, 1, data));
    }

    @Test
    void constructor_throwsOnDuplicateFromVersion() {
        assertThrows(IllegalArgumentException.class, () -> new SchemaMigrationChain(
                List.of(new TestV1ToV2Migrator(), new TestV1ToV2Migrator())));
    }
}
