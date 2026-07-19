package org.ex.exceptionalvision.world;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.state.BlockState;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Reads Anvil ({@code .mca}) region files directly from disk, independent of any
 * {@code ServerLevel}/{@code ChunkAccess}. See {@code 05_world_data_ingestion.md}.
 * <p>
 * Instances are stateless and thread-safe (each call opens its own file channel);
 * a single instance can be shared across the I/O thread pool.
 */
public final class RegionFileReader {

    private static final int LOCATION_TABLE_BYTES = 4096;
    private static final int TIMESTAMP_TABLE_BYTES = 4096;

    private static final int COMPRESSION_GZIP = 1;
    private static final int COMPRESSION_ZLIB = 2;
    private static final int COMPRESSION_NONE = 3;
    private static final int EXTERNAL_FLAG = 0x80;

    /** Reads and parses the 8KiB region header (location + timestamp tables). */
    public RegionHeader readHeader(Path mcaFile) throws IOException {
        RegionHeader header = new RegionHeader();
        ByteBuffer buffer = ByteBuffer.allocate(LOCATION_TABLE_BYTES + TIMESTAMP_TABLE_BYTES);
        try (FileChannel channel = FileChannel.open(mcaFile, StandardOpenOption.READ)) {
            readFully(channel, buffer, 0);
        }
        buffer.flip();

        for (int i = 0; i < RegionHeader.ENTRIES; i++) {
            int packed = buffer.getInt(i * 4);
            int sectorOffset = packed >>> 8;
            int sectorCount = packed & 0xFF;
            header.setLocation(i, sectorOffset, sectorCount);
        }
        for (int i = 0; i < RegionHeader.ENTRIES; i++) {
            int timestamp = buffer.getInt(LOCATION_TABLE_BYTES + i * 4);
            header.setTimestamp(i, timestamp);
        }
        return header;
    }

    /**
     * Reads and decompresses a single chunk's NBT by local coordinates (0..31, 0..31).
     * Returns {@code null} if the chunk has not been generated yet.
     */
    public CompoundTag readChunk(Path mcaFile, int localX, int localZ) throws IOException {
        try (FileChannel channel = FileChannel.open(mcaFile, StandardOpenOption.READ)) {
            ByteBuffer locationEntry = ByteBuffer.allocate(4);
            int index = RegionHeader.indexOf(localX, localZ);
            readFully(channel, locationEntry, index * 4L);
            locationEntry.flip();
            int packed = locationEntry.getInt();
            int sectorOffset = packed >>> 8;
            int sectorCount = packed & 0xFF;
            if (sectorOffset == 0 && sectorCount == 0) {
                return null; // chunk not generated
            }

            long dataStart = (long) sectorOffset * RegionHeader.SECTOR_BYTES;
            ByteBuffer lengthAndType = ByteBuffer.allocate(5);
            readFully(channel, lengthAndType, dataStart);
            lengthAndType.flip();
            int length = lengthAndType.getInt();
            int rawType = lengthAndType.get() & 0xFF;

            if (length <= 0) {
                return null;
            }

            boolean external = (rawType & EXTERNAL_FLAG) != 0;
            int compressionType = rawType & ~EXTERNAL_FLAG;

            byte[] payload;
            if (external) {
                // Large chunks (>1MB) are stored in a sibling c.<x>.<z>.mcc file;
                // the sector data itself carries no payload beyond the type byte.
                Path externalFile = mcaFile.resolveSibling(
                        "c." + (localX + regionMinChunkX(mcaFile)) + "." + (localZ + regionMinChunkZ(mcaFile)) + ".mcc");
                if (!Files.exists(externalFile)) {
                    throw new IOException("External chunk file missing: " + externalFile);
                }
                payload = Files.readAllBytes(externalFile);
            } else {
                ByteBuffer dataBuffer = ByteBuffer.allocate(length - 1);
                readFully(channel, dataBuffer, dataStart + 5);
                payload = dataBuffer.array();
            }

            try (InputStream decompressed = decompress(payload, compressionType);
                 DataInputStream in = new DataInputStream(decompressed)) {
                return NbtIo.read(in, NbtAccounter.unlimitedHeap());
            }
        }
    }

    private InputStream decompress(byte[] payload, int compressionType) throws IOException {
        return switch (compressionType) {
            case COMPRESSION_GZIP -> new GZIPInputStream(new ByteArrayInputStream(payload));
            case COMPRESSION_ZLIB -> new InflaterInputStream(new ByteArrayInputStream(payload));
            case COMPRESSION_NONE -> new ByteArrayInputStream(payload);
            default -> throw new IOException("Unsupported chunk compression type: " + compressionType
                    + " (LZ4 external compression is not supported by this reader)");
        };
    }

    // Region filename encodes the region's chunk-space origin; used only for the
    // (rare) external .mcc chunk lookup.
    private int regionMinChunkX(Path mcaFile) {
        return RegionCoordinate.fromFileName(mcaFile.getFileName().toString())
                .map(RegionCoordinate::minChunkX).orElse(0);
    }

    private int regionMinChunkZ(Path mcaFile) {
        return RegionCoordinate.fromFileName(mcaFile.getFileName().toString())
                .map(RegionCoordinate::minChunkZ).orElse(0);
    }

    private static void readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        long pos = position;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, pos);
            if (read < 0) {
                throw new EOFException("Unexpected end of region file at position " + pos);
            }
            pos += read;
        }
    }

    /**
     * Extracts only what LOD needs: per-column top-opaque-block height and material,
     * without constructing a full ChunkAccess. See {@code 03_data_formats.md} / {@code 05_world_data_ingestion.md}.
     */
    public ChunkHeightData extractHeightData(CompoundTag chunkNbt) {
        int chunkX = chunkNbt.getInt("xPos");
        int chunkZ = chunkNbt.getInt("zPos");

        String status = chunkNbt.getString("Status");
        boolean complete = isFullStatus(status);

        int[] topY = new int[ChunkHeightData.COLUMNS_PER_CHUNK];
        BlockState[] topState = new BlockState[ChunkHeightData.COLUMNS_PER_CHUNK];
        Arrays.fill(topY, Integer.MIN_VALUE);

        List<CompoundTag> sections = sortedSectionsTopDown(chunkNbt);
        if (sections.isEmpty()) {
            complete = false;
        }

        boolean[] resolved = new boolean[ChunkHeightData.COLUMNS_PER_CHUNK];
        int remaining = ChunkHeightData.COLUMNS_PER_CHUNK;

        for (CompoundTag section : sections) {
            if (remaining == 0) {
                break;
            }
            if (!section.contains("block_states", Tag.TAG_COMPOUND)) {
                continue;
            }
            int sectionY = section.getByte("Y");
            BlockState[] blocks = decodeBlockStates(section.getCompound("block_states"));
            if (blocks == null) {
                continue;
            }

            for (int localY = 15; localY >= 0 && remaining > 0; localY--) {
                int worldY = sectionY * 16 + localY;
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int col = ChunkHeightData.columnIndex(x, z);
                        if (resolved[col]) {
                            continue;
                        }
                        BlockState state = blocks[(localY << 8) | (z << 4) | x];
                        if (isConsideredOpaque(state)) {
                            topY[col] = worldY;
                            topState[col] = state;
                            resolved[col] = true;
                            remaining--;
                        }
                    }
                }
            }
        }

        return new ChunkHeightData(chunkX, chunkZ, topY, topState, complete);
    }

    private static boolean isFullStatus(String status) {
        if (status == null) {
            return false;
        }
        String bare = status.startsWith("minecraft:") ? status.substring("minecraft:".length()) : status;
        return "full".equals(bare);
    }

    private static boolean isConsideredOpaque(BlockState state) {
        if (state == null || state.isAir()) {
            return false;
        }
        // FIX: water (and lava) previously fell through to canOcclude() below, which is
        // false for fluids (you can see the lakebed through water in vanilla rendering,
        // so it doesn't occlude neighboring faces) - so the scan kept going *past* every
        // water column looking for the next "occluding" block, landing on the seafloor.
        // The LOD then rendered that seafloor's color (sand/gravel/whatever) as if it
        // were dry land, with no indication a lake or ocean was ever there - confirmed by
        // a user screenshot where a whole sea rendered as a tan/green "island" identical
        // in appearance to the surrounding beach. A fluid's own top surface is what a
        // distant LOD should show (a flat water-colored plane), not what's underneath it -
        // this is the "water surface vs. floor" special-casing the old comment here
        // anticipated but that never actually got implemented in LodBuilder.
        if (!state.getFluidState().isEmpty()) {
            return true;
        }
        // Approximation of "opaque" for the purposes of a distant LOD silhouette.
        return state.canOcclude();
    }

    private List<CompoundTag> sortedSectionsTopDown(CompoundTag chunkNbt) {
        List<CompoundTag> sections = new ArrayList<>();
        if (!chunkNbt.contains("sections", Tag.TAG_LIST)) {
            return sections;
        }
        ListTag sectionList = chunkNbt.getList("sections", Tag.TAG_COMPOUND);
        for (int i = 0; i < sectionList.size(); i++) {
            sections.add(sectionList.getCompound(i));
        }
        sections.sort((a, b) -> Byte.compare(b.getByte("Y"), a.getByte("Y"))); // descending Y
        return sections;
    }

    /**
     * Decodes a section's {@code block_states} compound (palette + packed data) into
     * a flat 4096-entry array indexed by {@code (localY << 8) | (localZ << 4) | localX}.
     * Returns {@code null} if the section has no usable palette.
     */
    private BlockState[] decodeBlockStates(CompoundTag blockStates) {
        if (!blockStates.contains("palette", Tag.TAG_LIST)) {
            return null;
        }
        ListTag paletteTag = blockStates.getList("palette", Tag.TAG_COMPOUND);
        int paletteSize = paletteTag.size();
        if (paletteSize == 0) {
            return null;
        }

        BlockState[] palette = new BlockState[paletteSize];
        for (int i = 0; i < paletteSize; i++) {
            palette[i] = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), paletteTag.getCompound(i));
        }

        BlockState[] out = new BlockState[4096];
        if (paletteSize == 1) {
            Arrays.fill(out, palette[0]);
            return out;
        }

        if (!blockStates.contains("data", Tag.TAG_LONG_ARRAY)) {
            // Malformed/partial section (e.g. interrupted worldgen) — treat as absent.
            return null;
        }
        long[] data = ((LongArrayTag) blockStates.get("data")).getAsLongArray();

        int bitsPerEntry = Math.max(4, 32 - Integer.numberOfLeadingZeros(paletteSize - 1));
        int valuesPerLong = 64 / bitsPerEntry;
        long mask = (1L << bitsPerEntry) - 1L;

        for (int i = 0; i < 4096; i++) {
            int longIndex = i / valuesPerLong;
            int bitOffset = (i % valuesPerLong) * bitsPerEntry;
            if (longIndex >= data.length) {
                // Truncated data array — leave remaining entries as palette[0] (best effort).
                out[i] = palette[0];
                continue;
            }
            int paletteIndex = (int) ((data[longIndex] >>> bitOffset) & mask);
            out[i] = paletteIndex < paletteSize ? palette[paletteIndex] : palette[0];
        }
        return out;
    }
}
