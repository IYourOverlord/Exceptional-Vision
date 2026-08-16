package dev.ev.storage.schema;

/**
 * Thrown when a region file's stored schema version cannot be brought to the requested target
 * version — either because the stored version is older than {@link SchemaVersion#MIN_SUPPORTED},
 * newer than the target, or because no continuous chain of {@link SchemaMigrator} steps connects
 * the stored version to the target.
 */
public final class UnsupportedSchemaException extends RuntimeException {

    public UnsupportedSchemaException(String message) {
        super(message);
    }
}
