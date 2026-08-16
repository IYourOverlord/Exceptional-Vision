package dev.ev.storage.schema;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves and applies the sequence of {@link SchemaMigrator} steps needed to bring a region
 * file from its stored version up to a target version.
 *
 * <p>{@link #migrateToCurrent(int, byte[])} is the entry point used in production — it always
 * migrates up to {@link SchemaVersion#CURRENT}. The more general {@link #migrateTo(int, int, byte[])}
 * accepts an explicit target version; it exists so migration-chain logic (ordering, gap
 * detection) can be unit-tested against hypothetical multi-step chains without needing to bump
 * {@link SchemaVersion#CURRENT} itself. {@code migrateToCurrent} is simply
 * {@code migrateTo(fromVersion, SchemaVersion.CURRENT, regionData)}.
 */
public final class SchemaMigrationChain {

    private final Map<Integer, SchemaMigrator> migratorsByFromVersion;

    public SchemaMigrationChain(List<SchemaMigrator> availableMigrators) {
        Map<Integer, SchemaMigrator> byFrom = new HashMap<>();
        for (SchemaMigrator migrator : availableMigrators) {
            SchemaMigrator existing = byFrom.putIfAbsent(migrator.fromVersion(), migrator);
            if (existing != null) {
                throw new IllegalArgumentException(
                        "Duplicate SchemaMigrator for fromVersion=" + migrator.fromVersion());
            }
        }
        this.migratorsByFromVersion = byFrom;
    }

    /**
     * Migrates {@code regionData}, stored at {@code fromVersion}, up to
     * {@link SchemaVersion#CURRENT}.
     *
     * @throws UnsupportedSchemaException if {@code fromVersion} is below
     *         {@link SchemaVersion#MIN_SUPPORTED}, above {@link SchemaVersion#CURRENT}, or if no
     *         continuous migrator chain exists from {@code fromVersion} to {@code CURRENT}.
     */
    public byte[] migrateToCurrent(int fromVersion, byte[] regionData) {
        return migrateTo(fromVersion, SchemaVersion.CURRENT, regionData);
    }

    /**
     * Migrates {@code regionData}, stored at {@code fromVersion}, up to an explicit
     * {@code toVersion}. General-purpose form of {@link #migrateToCurrent(int, byte[])}, used in
     * production with {@code toVersion == SchemaVersion.CURRENT} and directly in tests with
     * hypothetical target versions to exercise multi-step chains.
     *
     * @throws UnsupportedSchemaException if {@code fromVersion} is below
     *         {@link SchemaVersion#MIN_SUPPORTED}, above {@code toVersion}, or if no continuous
     *         migrator chain exists from {@code fromVersion} to {@code toVersion}.
     */
    public byte[] migrateTo(int fromVersion, int toVersion, byte[] regionData) {
        if (fromVersion < SchemaVersion.MIN_SUPPORTED) {
            throw new UnsupportedSchemaException(
                    "Schema version " + fromVersion + " is older than MIN_SUPPORTED="
                            + SchemaVersion.MIN_SUPPORTED);
        }
        if (fromVersion > toVersion) {
            throw new UnsupportedSchemaException(
                    "Schema version " + fromVersion + " is newer than target version "
                            + toVersion + " — this build cannot read it");
        }
        if (fromVersion == toVersion) {
            return regionData;
        }

        byte[] data = regionData;
        int version = fromVersion;
        while (version < toVersion) {
            SchemaMigrator migrator = migratorsByFromVersion.get(version);
            if (migrator == null) {
                throw new UnsupportedSchemaException(
                        "No SchemaMigrator registered for fromVersion=" + version
                                + " (needed to reach target version " + toVersion + ")");
            }
            data = migrator.migrate(data);
            version++;
        }
        return data;
    }
}
