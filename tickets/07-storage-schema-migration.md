# Тикет 07 — Версионированный формат хранения и миграции

## Контекст
EV — мод дальней прорисовки для NeoForge 1.21.1. Модуль `ev-storage`, чистый Java.
Прототип, который анализировался при проектировании этого мода (Voxy), переписывал свою
систему сохранения минимум дважды с нуля (класс назывался `SaveLoadSystem3` — суффикс "3"
говорит сам за себя), без версионируемого формата с миграциями. EV должен избежать
этой проблемы с первого дня: любой персистентный файл на диске несёт версию схемы в
заголовке, и есть механизм миграции старых версий в текущую.

## Эмпирический урок из независимой реализации (Exceptional Vision) — цена отсутствия миграций

Другой независимо реализованный мод той же концепции (Exceptional Vision,
`IYourOverlord/Exceptional-Vision`) имеет поле `CacheIndex.formatVersion`/
`CURRENT_FORMAT_VERSION` — то есть версия схемы формально присутствует на диске — но
**механизма миграции между версиями в коде не обнаружено**. На практике это означает: при
следующем изменении формата хранения у них старые кэши, скорее всего, придётся просто
удалять и пересчитывать с нуля целиком, а не мигрировать инкрементально. Судя по их
`PROGRESS.md`, полный импорт крупной прегенерированной территории (сценарии с Chunky)
занимает заметное время (от минут до заметно дольше в зависимости от размера мира) — то
есть цена отсутствия миграции здесь не абстрактная, а конкретная: каждое будущее изменение
формата стоит пользователю полного повторного прохода через весь этот процесс.

Это прямое, эмпирическое подтверждение того, зачем данный тикет существует с первого дня
EV, а не откладывается "пока формат не изменится в первый раз" — к моменту, когда
формат меняется в реальном проекте, обычно уже поздно вводить механизм миграции безболезненно
(нужно писать миграцию "из версии без миграций", что сложнее, чем миграция между версиями,
обе из которых изначально проектировались с этим механизмом).

## Готовый контракт из зависимости (тикет 06, уже реализован)
```java
package dev.ev.storage.codec;
public final class PaletteCodec {
    public static byte[] encode(int[] voxelsFlat);
    public static int[] decode(byte[] data);
    public static boolean isUniform(int[] voxelsFlat);
}
```

## Задача

### 1. `SchemaVersion`
```java
package dev.ev.storage.schema;

/** Registry of all on-disk schema versions this codebase understands. */
public final class SchemaVersion {
    /** Current version written by this build. Increment when the on-disk format changes. */
    public static final int CURRENT = 1;

    /** Oldest version this build can still read (via migration chain). */
    public static final int MIN_SUPPORTED = 1;

    private SchemaVersion() {}
}
```

### 2. Формат файла региона (спроектируй и задокументируй)
Определи бинарный формат заголовка региона (аналог "region file", содержащего несколько
секций):
```
[magic: 4 bytes = "HZR\0"]
[schemaVersion: 2 bytes, big-endian unsigned short]
[compressionCodec: 1 byte]   // 0 = none, 1 = deflate — только зарезервируй значение, реализация опциональна в этом тикете
[reserved: 1 byte = 0]
[sectionCount: 4 bytes]
[... per-section index entries ...]
[... per-section PaletteCodec-encoded payloads ...]
```
Уточни точную раскладку по своему усмотрению (это твой дизайн-выбор в рамках требований
ниже), но зафиксируй её в Javadoc класса, который читает/пишет заголовок, и держи это
единственным источником истины для формата (не дублируй магические числа по коду).

### 3. `SchemaMigrator`
```java
package dev.ev.storage.schema;

/**
 * A single migration step from one schema version to the next (fromVersion -> fromVersion+1).
 * Migrators are chained: to go from version 1 to version 3, the loader applies the
 * migrator for 1->2, then the migrator for 2->3, in sequence.
 */
public interface SchemaMigrator {
    int fromVersion();

    /** Transforms raw region file bytes from `fromVersion()` format to `fromVersion()+1` format. */
    byte[] migrate(byte[] regionData);
}
```

### 4. `SchemaMigrationChain`
```java
package dev.ev.storage.schema;

import java.util.List;

/**
 * Resolves and applies the sequence of SchemaMigrator steps needed to bring a region
 * file from its stored version up to SchemaVersion.CURRENT.
 */
public final class SchemaMigrationChain {
    public SchemaMigrationChain(List<SchemaMigrator> availableMigrators) { /* ... */ }

    /**
     * @throws UnsupportedSchemaException if fromVersion is below MIN_SUPPORTED, above
     *         CURRENT, or if no continuous migrator chain exists from fromVersion to CURRENT.
     */
    public byte[] migrateToCurrent(int fromVersion, byte[] regionData);
}
```

### 5. `UnsupportedSchemaException`
```java
package dev.ev.storage.schema;

public final class UnsupportedSchemaException extends RuntimeException {
    public UnsupportedSchemaException(String message) { super(message); }
}
```

### 6. `RegionFileHeader` — reader/writer для заголовка формата, описанного в п.2
```java
package dev.ev.storage.schema;

public record RegionFileHeader(int schemaVersion, int compressionCodec, int sectionCount) {
    public static final byte[] MAGIC = {'H', 'Z', 'R', 0};

    /** Parses the header from the start of a region file byte array. */
    public static RegionFileHeader parse(byte[] data);

    /** Serializes this header (does not include section index/payload). */
    public byte[] toBytes();

    public static final int HEADER_SIZE_BYTES = 12; // magic(4) + version(2) + codec(1) + reserved(1) + count(4)
}
```

## Требования к реализации
1. Поскольку `SchemaVersion.CURRENT == 1` и это первый тикет схемы — реальных миграторов
   между версиями пока не существует (нет версии 0). Не изобретай fake-миграцию "из
   ниоткуда" — вместо этого создай **тестовый** миграционный сценарий в юнит-тестах:
   определи гипотетический `TestV1ToV2Migrator` только в тестовом коде, чтобы проверить, что
   `SchemaMigrationChain` корректно строит цепочку и корректно бросает исключение при разрыве
   цепочки — таким образом механизм проверен, не дожидаясь реальной второй версии формата.
2. `RegionFileHeader.parse` должен бросать понятное исключение (например,
   `IllegalArgumentException` или `UnsupportedSchemaException`, выбери подходящее и
   задокументируй), если magic-байты не совпадают (файл повреждён/не тот формат).
3. Никаких зависимостей на NeoForge/LWJGL.

## Юнит-тесты (обязательно, JUnit 5)
Создай `SchemaMigrationChainTest`:
1. `migrateToCurrent` с `fromVersion == CURRENT` возвращает данные без изменений (identity,
   миграторы не нужны и не вызываются).
2. С определённым в тесте `TestV1ToV2Migrator` (гипотетический, только для теста) и
   гипотетическим `TestV2ToV3Migrator`: цепочка из v1 в v3 (при временно поднятом
   `CURRENT`-подобном сценарии в тесте, либо протестируй chain-логику отдельно от
   `SchemaVersion.CURRENT`, если конструктор/метод это позволяет — подбери дизайн теста так,
   чтобы не редактировать `SchemaVersion.CURRENT` ради теста) применяет оба миграционных
   шага по порядку.
3. `UnsupportedSchemaException` бросается, если `fromVersion < MIN_SUPPORTED`.
4. `UnsupportedSchemaException` бросается, если нет непрерывной цепочки миграторов
   (например, есть миграция 1→2, но нет 2→3, а нужно перейти из 1 в 3).

Создай `RegionFileHeaderTest`:
1. Round-trip: `toBytes()` → `parse()` → идентичные поля.
2. `parse` бросает исключение на данных с неверными magic-байтами.
3. `parse` бросает исключение на слишком коротком массиве байт (меньше `HEADER_SIZE_BYTES`).

## Критерии приёмки
1. Файлы в `ev-storage/src/main/java/dev/ev/storage/schema/`.
2. Все юнит-тесты проходят.
3. Модуль компилируется без ошибок, без NeoForge/LWJGL зависимостей.
4. Формат заголовка задокументирован Javadoc-ом в `RegionFileHeader`, это единственный
   источник истины для раскладки байт (никаких магических чисел, дублирующих эту раскладку,
   в других классах).
