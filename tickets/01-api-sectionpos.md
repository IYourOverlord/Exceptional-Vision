# Тикет 01 — SectionPos (кодирование позиции LOD-секции)

## Контекст
EV — мод дальней прорисовки для NeoForge 1.21.1. Мир хранится в виде разреженного
воксельного октодерева с уровнями детализации (LOD) 0..6. Каждый узел дерева ("секция") имеет
позицию: уровень LOD + координаты X/Y/Z в единицах секций этого уровня. Нужен компактный
long-кодированный идентификатор для использования как ключ в hash-картах и на GPU (SSBO).

Это первый тикет модуля `ev-api` (чистый Java, БЕЗ зависимостей на NeoForge/LWJGL,
только JDK). Модуль `ev-api` уже существует (создан тикетом 00), путь
`ev-api/src/main/java/dev/ev/api/`.

## Задача
Создать класс `dev.ev.api.SectionPos`.

## Точный контракт (обязательно реализовать именно так — другие тикеты зависят от этих сигнатур)

```java
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

    public long encode() {
        long lv = ((long) level & 0xF) << 60;
        long yy = ((long) y & 0xFF) << 52;
        long zz = ((long) z & 0x3FF_FFFFL) << 26;
        long xx = ((long) x & 0x3FF_FFFFL);
        return lv | yy | zz | xx;
    }

    public static SectionPos decode(long id) {
        int level = (int) (id >>> 60) & 0xF;
        int y = (byte) (id >>> 52); // sign-extends automatically via byte cast
        int z = (int) (id << (64 - 52)) >> (64 - 26); // sign-extend 26-bit field
        int x = (int) (id << (64 - 26)) >> (64 - 26); // sign-extend 26-bit field
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
```

## Требования к реализации
1. Реализуй ровно этот класс, этот пакет, эти сигнатуры — без отклонений (это контракт,
   на который опираются все последующие тикеты storage/meshing/gpu).
2. Добавь полный Javadoc (уже частично дан выше — сохрани и дополни, если нужно).
3. Никаких дополнительных публичных методов, кроме перечисленных, без крайней необходимости —
   если считаешь, что не хватает метода, добавь его, но задокументируй зачем.

## Юнит-тесты (обязательно, JUnit 5, модуль `ev-test` или `ev-api/src/test`)
Создай `SectionPosTest`, минимум эти кейсы:
1. `encode()` → `decode()` — round-trip для набора значений, включая отрицательные x/y/z и
   граничные (level=0, level=MAX_LOD_LEVEL, x/y/z на границах диапазона битовых полей).
2. `parent()` затем `child(correctIndex)` возвращает исходную позицию.
3. `sizeInBlocks()` корректен для всех уровней 0..6 (32, 64, 128, 256, 512, 1024, 2048).
4. `fromBlockCoord` для блока (0,0,0) на уровне 0 даёт `SectionPos(0,0,0,0)`.
5. `fromBlockCoord` корректно обрабатывает отрицательные координаты блока (floorDiv, не
   обычное деление — протестируй, например, blockX = -1 должен дать section x = -1, не 0).
6. Конструктор бросает `IllegalArgumentException` при `level` вне диапазона.

## Критерии приёмки
1. Файл `ev-api/src/main/java/dev/ev/api/SectionPos.java` создан точно по контракту.
2. Все юнит-тесты проходят: `./gradlew :ev-api:test` (или где физически лежат тесты).
3. Модуль `ev-api` компилируется без предупреждений компилятора.
4. Нет зависимостей на NeoForge/LWJGL в этом файле (только JDK).
