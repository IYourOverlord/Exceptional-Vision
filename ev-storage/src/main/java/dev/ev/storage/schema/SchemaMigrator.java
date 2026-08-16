package dev.ev.storage.schema;

/**
 * A single migration step from one schema version to the next ({@code fromVersion} ->
 * {@code fromVersion + 1}). Migrators are chained: to go from version 1 to version 3, the
 * loader applies the migrator for {@code 1->2}, then the migrator for {@code 2->3}, in
 * sequence. See {@link SchemaMigrationChain}.
 */
public interface SchemaMigrator {

    /** The schema version this migrator reads. It produces {@code fromVersion() + 1}. */
    int fromVersion();

    /** Transforms raw region file bytes from {@code fromVersion()} format to {@code fromVersion()+1} format. */
    byte[] migrate(byte[] regionData);
}
