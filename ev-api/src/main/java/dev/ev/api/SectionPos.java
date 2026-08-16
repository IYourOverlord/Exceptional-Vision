package dev.ev.api;

/**
 * Immutable position of a LOD section in the sparse voxel octree.
 * Encodes to a single long for use as a hash map key and in GPU SSBOs.
 *
 * Bit layout of the encoded long (64 bits total):
 *   bits 60-63 (4 bits)  : level      (0..6, MAX_LOD_LEVEL)
 *   bits 52-59 (8 bits)  : y          (signed, -128..127, section-grid Y)
 *   bits 26-51 (26 bits) : z          (signed, section-grid Z)
 *   bits 0-25  (26 bits) : x          (signed, section-grid X)
 *
 * 26 signed bits give a coordinate range of approximately ±33.5 million sections,
 * which at level 0 (32-block sections) covers a world radius far beyond the
 * Minecraft world border (±30,000,000 blocks) with margin.
 */
public record SectionPos(int level, int x, int y, int z) {

    public static final int MAX_LOD_LEVEL = 6;

    public SectionPos {
        if (level < 0 || level > MAX_LOD_LEVEL) {
            throw new IllegalArgumentException("level out of range [0," + MAX_LOD_LEVEL + "]: " + level);
        }
    }

    /** Side length of this section in blocks: 32 * 2^level. */
    public int sizeInBlocks() {
        return 32 << level;
    }

    /**
     * Encodes this position into a single {@code long} per the bit layout documented
     * on the class. Coordinates are truncated (masked) to their field width; callers
     * are expected to stay within the documented ±33.5M section range for x/z and
     * ±128 for y.
     */
    public long encode() {
        long lv = ((long) level & 0xF) << 60;
        long yy = ((long) y & 0xFF) << 52;
        long zz = ((long) z & 0x3FF_FFFFL) << 26;
        long xx = ((long) x & 0x3FF_FFFFL);
        return lv | yy | zz | xx;
    }

    /**
     * Decodes a {@code long} previously produced by {@link #encode()} back into a
     * {@link SectionPos}. Sign-extends the y (8-bit), z (26-bit) and x (26-bit) fields.
     *
     * <p>Note on the shift amounts for x/z: each field is first isolated into the low 32
     * bits of an {@code int} (by an unsigned right-shift of the 64-bit id, or none for x),
     * then sign-extended within that 32-bit int via {@code << 6 >> 6} — 6 = 32 - 26, the
     * width of the field's own 32-bit container, not 64 - 26. Using 64 - 26 = 38 here would
     * be wrong: Java masks int shift amounts to their low 5 bits (amount % 32), so a shift
     * of 38 on an int silently becomes a shift of 6 anyway, but only by coincidence of
     * matching bit widths elsewhere — relying on that wrap is fragile and was in fact the
     * source of a real round-trip bug fixed here (see PROGRESS.md, багфикс 5).
     */
    public static SectionPos decode(long id) {
        int level = (int) (id >>> 60) & 0xF;
        int y = (byte) (id >>> 52); // sign-extends automatically via byte cast
        int z = (int) (id >>> 26) << 6 >> 6; // isolate 26-bit z field into int, sign-extend
        int x = (int) id << 6 >> 6;          // isolate 26-bit x field into int, sign-extend
        return new SectionPos(level, x, y, z);
    }

    /** Parent section one LOD level coarser (level+1), or throws if already at MAX_LOD_LEVEL. */
    public SectionPos parent() {
        if (level >= MAX_LOD_LEVEL) {
            throw new IllegalStateException("Cannot get parent of max LOD level section");
        }
        return new SectionPos(level + 1, Math.floorDiv(x, 2), Math.floorDiv(y, 2), Math.floorDiv(z, 2));
    }

    /**
     * Returns the 8 child sections one LOD level finer (level-1).
     * childIndex bit 0 = x offset, bit 1 = y offset, bit 2 = z offset.
     */
    public SectionPos child(int childIndex) {
        if (level <= 0) {
            throw new IllegalStateException("Cannot get child of level 0 section");
        }
        if (childIndex < 0 || childIndex > 7) {
            throw new IllegalArgumentException("childIndex must be 0..7: " + childIndex);
        }
        int cx = x * 2 + (childIndex & 1);
        int cy = y * 2 + ((childIndex >> 1) & 1);
        int cz = z * 2 + ((childIndex >> 2) & 1);
        return new SectionPos(level - 1, cx, cy, cz);
    }

    /** World-space coordinate (in blocks) of the minimum corner of this section. */
    public long minBlockX() { return (long) x * sizeInBlocks(); }
    public long minBlockY() { return (long) y * sizeInBlocks(); }
    public long minBlockZ() { return (long) z * sizeInBlocks(); }

    /** Converts an absolute block coordinate to a SectionPos at the given level. */
    public static SectionPos fromBlockCoord(int level, long blockX, long blockY, long blockZ) {
        int size = 32 << level;
        return new SectionPos(
            level,
            (int) Math.floorDiv(blockX, size),
            (int) Math.floorDiv(blockY, size),
            (int) Math.floorDiv(blockZ, size)
        );
    }

    @Override
    public String toString() {
        return level + "@[" + x + ", " + y + ", " + z + "]";
    }
}
