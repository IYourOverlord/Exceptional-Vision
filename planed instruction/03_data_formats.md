# Форматы данных

## 1. Формат "квада" (результат greedy meshing) — 8 байт

Один квад описывает прямоугольную грань LOD-геометрии после объединения
соседних одинаковых колонок (greedy meshing).

```
packed0 (uint32):
  bits  0-5   (6 бит)  x        — локальная позиция внутри узла, 0..63
  bits  6-17  (12 бит) y        — высота, диапазон покрывает -64..320
  bits 18-23  (6 бит)  z        — локальная позиция внутри узла, 0..63
  bits 24-26  (3 бита) axis     — грань куба: 0=+X,1=-X,2=+Y,3=-Y,4=+Z,5=-Z
  bits 27-31  (5 бит)  reserved — резерв под будущее расширение

packed1 (uint32):
  bits  0-5   (6 бит)  width    — размер квада по первой оси после мешинга
  bits  6-11  (6 бит)  height   — размер квада по второй оси
  bits 12-27  (16 бит) material — индекс в палитру материалов/цветов
  bits 28-31  (4 бита) light    — упрощённое освещение / ambient occlusion
```

Структура на стороне Java (соответствует layout в шейдере):

```java
public record PackedQuad(int packed0, int packed1) {
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
}
```

## 2. Структура узла quadtree (зеркалируется на CPU и GPU)

Узел покрывает область NxN чанков в зависимости от уровня детализации:
LOD0 = 1 чанк (16×16), LOD1 = 2×2 чанка, LOD2 = 4×4 чанка и так далее
(размер стороны узла = `16 * 2^level` блоков).

GPU-структура (std430, 48 байт на узел):

```glsl
struct NodeData {
    vec4 aabbMin;    // xyz = мировые координаты минимума, w = lodLevel
    vec4 aabbMax;    // xyz = мировые координаты максимума, w не используется
    uint quadOffset; // смещение первого квада узла в QuadBuffer
    uint quadCount;  // количество квадов узла
    uint lodLevel;
    uint _pad;       // выравнивание до 48 байт
};
```

Java-сторона (только для CPU-логики стриминга, не передаётся в шейдер
напрямую как объект — сериализуется в тот же layout при записи в SSBO):

```java
public final class LodNode {
    final AABB bounds;
    final int level;              // 0 = самый детальный
    final int chunkSpan;          // 1, 2, 4, 8 ... чанков на сторону
    int quadOffset = -1;          // -1 = данные ещё не построены
    int quadCount = 0;
    LodNode[] children;           // null для листа с построенными данными
    volatile boolean dirty;       // требует пересчёта из-за изменения региона
}
```

## 3. Layout SSBO-буферов на GPU

| Binding | Буфер            | Назначение                                    |
|---------|------------------|------------------------------------------------|
| 0       | QuadBuffer       | Все упакованные квады всех загруженных узлов   |
| 1       | NodeBuffer       | Метаданные узлов quadtree (AABB, offset, count)|
| 2       | IndirectCommands | Команды indirect draw, заполняются compute shader |
| 3       | DrawCounter      | Атомарный счётчик количества видимых узлов     |
| 4       | MaterialPalette  | Таблица material_index → цвет/текстурный слой  |

## 4. Палитра материалов (MaterialPalette)

Вместо полного набора текстур блоков на дальней дистанции используется
ограниченная палитра (аналогично тому, как LOD-моды усредняют цвет
блока): каждому уникальному материалу присваивается индекс, при первом
встраивании блока в LOD-данные:

```java
public final class MaterialPalette {
    private final Map<BlockState, Integer> stateToIndex = new HashMap<>();
    private final List<Integer> packedColors = new ArrayList<>(); // RGBA8

    public synchronized int indexFor(BlockState state) {
        return stateToIndex.computeIfAbsent(state, s -> {
            int color = computeAverageColor(s); // например через getMapColor()
            packedColors.add(color);
            return packedColors.size() - 1;
        });
    }
}
```

Палитра целиком грузится в `MaterialPalette` SSBO/UBO и обновляется
только при появлении нового материала — не каждый кадр.

## Оценка объёма памяти

- 8 байт/квад. При ~200 000 видимых квадов на экране (грубая оценка для
  дистанции в несколько тысяч блоков) — это ≈1.6 МБ данных в QuadBuffer.
- NodeBuffer при глубине quadtree ~10 уровней и активном стриминге
  порядка 50 000 узлов — 48 байт × 50 000 ≈ 2.4 МБ.
- Итого рабочий набор в VRAM — единицы-десятки мегабайт даже при большой
  дистанции прорисовки, что на порядки меньше, чем полный вершинный меш
  того же объёма геометрии.
