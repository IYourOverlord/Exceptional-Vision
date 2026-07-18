# Формат дискового кэша LOD-данных

## Назначение

Избежать пересчёта LOD-данных из `.mca` при каждом запуске игры.
Кэш хранится отдельно от файлов мира и никогда их не модифицирует.

## Расположение

```
<world>/lodcache/<dimension>/nodes.bin
<world>/lodcache/<dimension>/quads.bin
<world>/lodcache/<dimension>/index.json   — метаданные версии формата
```

## Файл `index.json`

```json
{
  "formatVersion": 1,
  "modVersion": "0.1.0",
  "dimension": "minecraft:overworld",
  "regionsProcessed": [
    { "x": 0, "z": 0, "mtimeMs": 1731000000000, "sourceHash": "..." }
  ]
}
```

Поле `mtimeMs`/`sourceHash` для каждого региона позволяет при запуске
быстро определить: изменился ли `.mca` со времени последней обработки
(например Chunky догенерировал что-то новое) — тогда только этот регион
пересчитывается заново, а не весь мир.

## Файл `nodes.bin` (последовательность записей `NodeData`)

Бинарный layout один в один соответствует GPU-структуре
(см. `03_data_formats.md`), что позволяет загружать файл прямо в SSBO
без промежуточного парсинга:

```
[uint32 nodeCount]
[NodeData * nodeCount]   // по 48 байт каждая, как в GPU-структуре
```

## Файл `quads.bin`

```
[uint64 quadCount]
[PackedQuad * quadCount]  // по 8 байт каждая (packed0 + packed1)
```

## Загрузка при старте мира

```java
public final class LodCacheLoader {
    public LodCacheData load(Path cacheDir, String dimensionId) throws IOException {
        Path indexFile = cacheDir.resolve(dimensionId).resolve("index.json");
        if (!Files.exists(indexFile)) {
            return LodCacheData.empty(); // кэша нет, всё будет построено с нуля
        }
        CacheIndex index = parseIndex(indexFile);
        if (index.formatVersion() != CURRENT_FORMAT_VERSION) {
            return LodCacheData.empty(); // версия формата устарела, пересчитать
        }
        // Memory-map nodes.bin и quads.bin напрямую в ByteBuffer,
        // передать указатели в GPU Data Manager для прямой загрузки в SSBO.
        return mapCacheFiles(cacheDir, dimensionId);
    }
}
```

## Инкрементальное обновление

При обнаружении изменённого/нового региона (см.
`05_world_data_ingestion.md`, `WorldDirectoryWatcher`):

1. Пересчитать LOD только для затронутых узлов quadtree.
2. Дописать новые квады в конец `quads.bin` (append-only), обновить
   `quadOffset`/`quadCount` соответствующей записи в `nodes.bin`.
3. Периодически (например раз в несколько минут игры или при выходе
   из мира) выполнять компакцию файлов — убирать "осиротевшие" квады
   от старых версий узлов, чтобы файл не рос бесконечно.

## Формат-версионирование

Поле `formatVersion` в `index.json` должно инкрементироваться при любом
изменении бинарной структуры `NodeData`/`PackedQuad` между версиями
мода — старый кэш при несовпадении версии просто игнорируется и
перестраивается, без попытки миграции "на лету" (что резко упрощает
поддержку по сравнению с попыткой писать конвертеры версий).
