package org.ex.exceptionalvision.cache;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory model of {@code index.json}, plus its JSON (de)serialization. Immutable —
 * every "mutation" ({@link #withRegion}) returns a new instance. See
 * {@code 06_disk_cache_format.md}.
 *
 * <pre>
 * {
 *   "formatVersion": 1,
 *   "modVersion": "0.1.0",
 *   "dimension": "minecraft:overworld",
 *   "regionsProcessed": [
 *     { "x": 0, "z": 0, "mtimeMs": 1731000000000, "sourceHash": "...",
 *       "nodeIndexStart": 0, "nodeCount": 1365 }
 *   ]
 * }
 * </pre>
 *
 * JSON is read/written with Gson's low-level {@link JsonObject}/{@link JsonWriter} API
 * (rather than reflective binding) so the exact on-disk schema above is explicit and a
 * malformed/foreign JSON file fails predictably instead of silently binding partial data.
 */
public record CacheIndex(int formatVersion, String modVersion, String dimension, List<RegionCacheEntry> regionsProcessed) {

    public CacheIndex {
        regionsProcessed = List.copyOf(regionsProcessed);
    }

    public static CacheIndex empty(String modVersion, String dimension) {
        return new CacheIndex(LodCacheFormat.CURRENT_FORMAT_VERSION, modVersion, dimension, List.of());
    }

    public Optional<RegionCacheEntry> find(int regionX, int regionZ) {
        return regionsProcessed.stream()
                .filter(e -> e.x() == regionX && e.z() == regionZ)
                .findFirst();
    }

    /** Returns a copy of this index with {@code entry} added, or replacing any existing entry for the same region. */
    public CacheIndex withRegion(RegionCacheEntry entry) {
        Map<Long, RegionCacheEntry> byRegion = new LinkedHashMap<>();
        for (RegionCacheEntry existing : regionsProcessed) {
            byRegion.put(key(existing.x(), existing.z()), existing);
        }
        byRegion.put(key(entry.x(), entry.z()), entry);
        return new CacheIndex(formatVersion, modVersion, dimension, List.copyOf(byRegion.values()));
    }

    private static long key(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xFFFFFFFFL);
    }

    // ---- JSON I/O -------------------------------------------------------------------

    public static CacheIndex readFrom(Path indexFile) throws IOException {
        String text = Files.readString(indexFile, StandardCharsets.UTF_8);
        JsonElement root;
        try {
            root = JsonParser.parseString(text);
        } catch (JsonParseException e) {
            throw new IOException("Malformed cache index JSON: " + indexFile, e);
        }
        if (!root.isJsonObject()) {
            throw new IOException("Cache index is not a JSON object: " + indexFile);
        }
        JsonObject obj = root.getAsJsonObject();
        int formatVersion = requireInt(obj, "formatVersion", indexFile);
        String modVersion = requireString(obj, "modVersion", indexFile);
        String dimension = requireString(obj, "dimension", indexFile);

        List<RegionCacheEntry> regions = new ArrayList<>();
        JsonElement regionsElement = obj.get("regionsProcessed");
        if (regionsElement != null && regionsElement.isJsonArray()) {
            JsonArray array = regionsElement.getAsJsonArray();
            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject regionObj = element.getAsJsonObject();
                regions.add(new RegionCacheEntry(
                        requireInt(regionObj, "x", indexFile),
                        requireInt(regionObj, "z", indexFile),
                        requireLong(regionObj, "mtimeMs", indexFile),
                        requireString(regionObj, "sourceHash", indexFile),
                        optionalInt(regionObj, "nodeIndexStart", 0),
                        optionalInt(regionObj, "nodeCount", 0)));
            }
        }
        return new CacheIndex(formatVersion, modVersion, dimension, regions);
    }

    public void writeTo(Path indexFile) throws IOException {
        CacheIo.writeAtomic(indexFile, channel -> {
            // Deliberately not try-with-resources: closing the JsonWriter/Writer would close
            // the underlying channel, but CacheIo.writeAtomic still needs to fsync() it after
            // this lambda returns. flush() is enough — the channel itself is closed by the caller.
            try {
                Writer writer = new OutputStreamWriter(Channels.newOutputStream(channel), StandardCharsets.UTF_8);
                JsonWriter json = new JsonWriter(writer);
                json.setIndent("  ");
                json.beginObject();
                json.name("formatVersion").value(formatVersion);
                json.name("modVersion").value(modVersion);
                json.name("dimension").value(dimension);
                json.name("regionsProcessed").beginArray();
                for (RegionCacheEntry entry : regionsProcessed) {
                    json.beginObject();
                    json.name("x").value(entry.x());
                    json.name("z").value(entry.z());
                    json.name("mtimeMs").value(entry.mtimeMs());
                    json.name("sourceHash").value(entry.sourceHash());
                    json.name("nodeIndexStart").value(entry.nodeIndexStart());
                    json.name("nodeCount").value(entry.nodeCount());
                    json.endObject();
                }
                json.endArray();
                json.endObject();
                json.flush();
            } catch (IOException e) {
                throw new CacheIo.UncheckedIoException(e);
            }
        });
    }

    private static int optionalInt(JsonObject obj, String field, int fallback) {
        JsonElement element = obj.get(field);
        return (element != null && element.isJsonPrimitive()) ? element.getAsInt() : fallback;
    }

    private static int requireInt(JsonObject obj, String field, Path source) throws IOException {
        JsonElement element = obj.get(field);
        if (element == null || !element.isJsonPrimitive()) {
            throw new IOException("Cache index missing field '" + field + "': " + source);
        }
        return element.getAsInt();
    }

    private static long requireLong(JsonObject obj, String field, Path source) throws IOException {
        JsonElement element = obj.get(field);
        if (element == null || !element.isJsonPrimitive()) {
            throw new IOException("Cache index missing field '" + field + "': " + source);
        }
        return element.getAsLong();
    }

    private static String requireString(JsonObject obj, String field, Path source) throws IOException {
        JsonElement element = obj.get(field);
        if (element == null || !element.isJsonPrimitive()) {
            throw new IOException("Cache index missing field '" + field + "': " + source);
        }
        return element.getAsString();
    }
}
