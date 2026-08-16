# Тикет 09 — Coarse-to-fine генерация дальних LOD из heightmap

## 📌 Статус относительно MVP/opt разделения

Этот тикет реализуется **одинаково в обеих волнах** (см. `MVP_INDEX.md`) — не разделён на
`-mvp`/`-opt` версии. Причина: это не оптимизация производительности в смысле "может подождать
профилирования", а предотвращение видимого, пользователь-ощутимого бага (полное отсутствие
геометрии на дальних LOD-уровнях, пока не завершится дорогая полная вокселизация) — цена
реализации сразу мала (алгоритм не сложнее полной вокселизации, только дешевле по стоимости),
а цена отсутствия — заметная "дыра" в горизонте при каждом холодном старте.

## Контекст
EV — мод дальней прорисовки для NeoForge 1.21.1. Модуль `ev-storage`, чистый Java
(без NeoForge/LWJGL — этот модуль не видит Minecraft-классы напрямую; heightmap/biome данные
приходят как простые примитивные массивы через интерфейс, который позже в `ev-neoforge`
адаптирует реальные Minecraft `Heightmap`/`Biome` объекты к этому контракту — адаптер НЕ
входит в этот тикет).

Ключевая оптимизация холодного старта (см. PERFORMANCE_MATH.md, раздел A.2): чтобы увидеть
грубый силуэт горизонта на дальних LOD-уровнях (4-6), не нужна полная вокселизация блок за
блоком (`O(32³)` на секцию) — воксель на грубом уровне можно построить напрямую из
heightmap + доминирующего биома в покрываемой области, за `O(1)` на воксель вместо `O(32³)`
блоков. Это даёт мгновенный грубый рендер горизонта, пока детализация подгружается поверх.

## Готовый контракт из зависимостей (тикеты 01, 02, 06 — уже реализованы)
```java
package dev.ev.api;
public record SectionPos(int level, int x, int y, int z) {
    public int sizeInBlocks(); // 32 << level
    public long minBlockX(); public long minBlockY(); public long minBlockZ();
}

package dev.ev.api.storage;
public interface WorldSectionHandle {
    dev.ev.api.SectionPos position();
    void setVoxel(int localX, int localY, int localZ, int paletteIndex);
}

package dev.ev.storage.codec;
public final class PaletteCodec {
    public static byte[] encode(int[] voxelsFlat);
    public static boolean isUniform(int[] voxelsFlat);
}
```

## Задача

### `HeightmapSource` (интерфейс — адаптер к реальному Minecraft heightmap пишется отдельно, не здесь)
```java
package dev.ev.storage.coarsegen;

/**
 * Abstract source of surface height + dominant surface material, queried per
 * block column (world X/Z). Implemented elsewhere by adapting Minecraft's
 * Heightmap/ChunkAccess/BiomeSource to this interface — this package must not
 * depend on Minecraft/NeoForge classes.
 */
public interface HeightmapSource {
    /** Highest non-air block Y coordinate at this world-space column, or Integer.MIN_VALUE if unknown/unloaded. */
    int surfaceHeight(int worldX, int worldZ);

    /** Palette index of the dominant surface material (e.g. grass, sand, stone) at this column. */
    int surfaceMaterial(int worldX, int worldZ);

    /** True if data for this column is available (chunk generated/loaded); false = treat as unknown, caller decides fallback. */
    boolean isAvailable(int worldX, int worldZ);
}
```

### `CoarseSectionGenerator`
```java
package dev.ev.storage.coarsegen;

import dev.ev.api.SectionPos;
import dev.ev.api.storage.WorldSectionHandle;

/**
 * Generates approximate voxel content for a coarse (high-LOD) section directly from
 * a HeightmapSource, without reading individual blocks. Intended for LOD levels where
 * per-block accuracy is not visually distinguishable (see PERFORMANCE_MATH.md section
 * A.2/A.3) — the caller decides the LOD threshold above which this generator is used
 * instead of full voxelization; this class only implements the sampling/aggregation
 * logic, not the threshold policy.
 */
public final class CoarseSectionGenerator {

    public CoarseSectionGenerator(HeightmapSource source) { /* ... */ }

    /**
     * Populates the given section handle by sampling one heightmap column per voxel
     * column (32x32 columns per section, regardless of LOD level — each voxel column
     * covers sizeInBlocks()/32 world blocks per axis), taking the dominant surface
     * material and filling voxels from bedrock-relative bottom up to the sampled
     * surface height (quantized to this section's voxel resolution).
     */
    public void generate(WorldSectionHandle target);
}
```

## Требования к реализации

1. Для секции на уровне `L` с `sizeInBlocks() = 32 << L`, каждый локальный voxel-column
   (localX, localZ в 0..31) покрывает квадрат `sizeInBlocks()/32` блоков в мире (при L=6 это
   64 блока на воксель по X/Z). Сэмплируй heightmap **в центре** этого квадрата (не в углу —
   меньше систематического смещения), по одной точке на voxel-column (не по всем блокам
   внутри — в этом весь смысл `O(1)` на воксель).

2. Определение "доминирующего материала" для грубого вокселя: в этом тикете упрощённо
   допустимо использовать материал **в той же центральной сэмплируемой точке** (не honest
   majority-vote по всей области — это было бы `O(area)`, что противоречит цели `O(1)`).
   Если считаешь нужным сделать точнее без потери асимптотики (например, сэмплировать
   фиксированное малое число точек, скажем 4-9, вместо честного majority по всей области) —
   можешь так и сделать, задокументируй компромисс в Javadoc, но не переходи к `O(area)`.

3. Вертикальное заполнение: для каждого voxel-column определи `surfaceHeight`, переведи в
   локальную Y-координату вокселя (`quantizedSurfaceLocalY = (surfaceHeight -
   section.minBlockY()) / voxelSizeInBlocks`, с клампом в диапазон 0..31), заполни все
   вокселы от `localY = 0` до `quantizedSurfaceLocalY` (включительно) материалом,
   `localY` выше — воздух (`paletteIndex = 0`). Если `quantizedSurfaceLocalY < 0` (секция
   целиком выше поверхности) — вся колонка воздух. Если `quantizedSurfaceLocalY > 31`
   (секция целиком под поверхностью) — вся колонка заполнена материалом.

4. Если `HeightmapSource.isAvailable(worldX, worldZ)` возвращает `false` для сэмплируемой
   точки — не бросай исключение, задокументируй и реализуй разумное поведение по умолчанию
   (например: пропустить генерацию этой voxel-column, оставить как есть/воздух; или
   пометить секцию как "требует повторной генерации позже" через возвращаемое значение —
   выбери один подход и обоснуй в Javadoc метода `generate`).

5. После заполнения всех вокселей — используй `PaletteCodec.isUniform` на собранном плоском
   массиве **до** записи через `setVoxel` по одному (если вся секция окажется однородной,
   это частый и дешёвый случай, который стоит зафиксировать явно в реализации — например,
   залогировать/учесть в метриках, если `MetricsRegistry` доступен в конструкторе; добавление
   `MetricsRegistry` в конструктор — на твоё усмотрение, не обязательно для этого тикета,
   если не считаешь нужным).

6. Никаких зависимостей на NeoForge/LWJGL/Minecraft-классы.

## Юнит-тесты (обязательно, JUnit 5)

Создай тестовую реализацию `HeightmapSource` в тестовом коде (простая, на основе
предзаданной функции высоты/материала, например константа или плоская функция от
worldX/worldZ) и `HeightmapSource`, создай `CoarseSectionGeneratorTest`:

1. Плоский мир на константной высоте: сгенерированная секция целиком выше этой высоты —
   вся секция воздух.
2. Плоский мир на константной высоте: секция целиком ниже этой высоты — вся секция
   заполнена материалом.
3. Секция, пересекающая уровень поверхности: нижняя часть заполнена, верхняя — воздух,
   граница примерно соответствует ожидаемому квантованному Y.
4. Секция на LOD уровне 0 (`sizeInBlocks()=32`) и на LOD уровне 6 (`sizeInBlocks()=2048`) для
   одного и того же мира дают консистентно корректные (не обязательно идентичные —
   ожидаемо разное квантование) результаты по направлению "выше/ниже поверхности".
5. Тест поведения при `isAvailable() == false` — согласно выбранному в п.4 требований
   поведению, задокументированному в Javadoc.
6. Тест, что метод действительно не читает более одной (или согласованного малого
   фиксированного числа, если выбран вариант с несколькими сэмплами) точки heightmap на
   voxel-column — реализуй через тестовую `HeightmapSource`, считающую число вызовов
   `surfaceHeight`/`surfaceMaterial`, и проверь, что общее число вызовов равно `32*32*(1 или
   выбранное фиксированное число)`, а НЕ пропорционально `sizeInBlocks()` — это ключевая
   проверка того, что сложность действительно `O(1)` на воксель, а не `O(area)`.

## Критерии приёмки
1. Файлы в `ev-storage/src/main/java/dev/ev/storage/coarsegen/`.
2. Все юнит-тесты проходят, включая тест на число вызовов heightmap-источника (п.6) —
   это единственная проверка, гарантирующая, что реализация не деградировала в `O(area)`.
3. Модуль компилируется без ошибок, без NeoForge/LWJGL зависимостей.
