package org.ex.exceptionalvision.world.lod;

/**
 * A single greedy-meshed LOD quad, packed into 8 bytes (two {@code uint32}s) with a
 * layout that mirrors the GPU-side {@code QuadBuffer} entry exactly (std430), so a
 * {@code PackedQuad} can be written to / read from disk and uploaded straight into an
 * SSBO with no intermediate transformation. See {@code 03_data_formats.md}.
 *
 * <pre>
 * packed0 (uint32):
 *   bits  0-5   (6 бит)  x        — локальная позиция внутри узла, 0..63
 *   bits  6-17  (12 бит) y        — высота, диапазон покрывает -64..320
 *   bits 18-23  (6 бит)  z        — локальная позиция внутри узла, 0..63
 *   bits 24-26  (3 бита) axis     — грань куба: 0=+X,1=-X,2=+Y,3=-Y,4=+Z,5=-Z
 *   bits 27-31  (5 бит)  reserved
 *
 * packed1 (uint32):
 *   bits  0-5   (6 бит)  width    — размер квада по первой оси после мешинга
 *   bits  6-11  (6 бит)  height   — размер квада по второй оси
 *   bits 12-27  (16 бит) material — индекс в палитру материалов/цветов
 *   bits 28-31  (4 бита) light    — упрощённое освещение / ambient occlusion
 * </pre>
 */
public record PackedQuad(int packed0, int packed1) {

    /** Size of one record when serialized to {@code quads.bin}. See {@code 06_disk_cache_format.md}. */
    public static final int BYTES = 8;

    public static PackedQuad of(int x, int y, int z, int axis,
                                 int width, int height,
                                 int material, int light) {
        int p0 = (x & 0x3F)
                | ((y & 0xFFF) << 6)
                | ((z & 0x3F) << 18)
                | ((axis & 0x7) << 24);
        int p1 = (width & 0x3F)
                | ((height & 0x3F) << 6)
                | ((material & 0xFFFF) << 12)
                | ((light & 0xF) << 28);
        return new PackedQuad(p0, p1);
    }

    public int x() {
        return packed0 & 0x3F;
    }

    public int y() {
        return (packed0 >>> 6) & 0xFFF;
    }

    public int z() {
        return (packed0 >>> 18) & 0x3F;
    }

    public int axis() {
        return (packed0 >>> 24) & 0x7;
    }

    public int width() {
        return packed1 & 0x3F;
    }

    public int height() {
        return (packed1 >>> 6) & 0x3F;
    }

    public int material() {
        return (packed1 >>> 12) & 0xFFFF;
    }

    public int light() {
        return (packed1 >>> 28) & 0xF;
    }
}
