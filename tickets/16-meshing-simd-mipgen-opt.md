# Тикет 16-opt — SIMD Mip-Level Aggregation (Vector API)

## ⚠️ Когда это применять

Это тикет Волны 2 (см. `MVP_INDEX.md`). MVP-версия (`16-meshing-mipgen-mvp.md`) использует
обычный скалярный Java-цикл для majority-vote агрегации mip-уровней. **Не выполняй этот
тикет**, пока не пройден `P0-profiling-checkpoint.md` и результаты не показали:
- Разбивка холодного старта по стадиям (Сценарий A, "Разбивка времени по стадиям") указывает
  на meshing (конкретно — mip-агрегацию внутри него, если удаётся выделить через JFR
  method-profiling отдельно от остальных стадий мешинга) как на существенную долю общего
  времени холодного старта.

Java 21 Vector API — incubator-модуль; введение этой зависимости имеет свою цену (флаги
компиляции/рантайма, потенциальная нестабильность API между патч-версиями JDK) — не вводи её
без измеренного обоснования, особенно с учётом того, что MVP-версия уже покрывает
функциональную потребность (просто медленнее в потенциале, не обязательно на практике).

## Контекст
EV — мод дальней прорисовки для NeoForge 1.21.1 (Java 21). Модуль `ev-meshing`,
чистый Java. Реализует ускорение агрегации детских вокселей в родительский mip-уровень
(например, 8 соседних вокселей уровня L объединяются majority-vote'ом в 1 воксель уровня
L+1) через Java Vector API (JEP 448, `jdk.incubator.vector`, incubator-модуль, доступен в
Java 21 — см. PERFORMANCE_MATH.md раздел A.5). Это плотный числовой цикл, вызываемый на
десятках тысяч узлов во время холодного старта — прямой кандидат на SIMD-векторизацию без
JNI/ручных intrinsics.

## Готовый контракт (используется только концептуально — этот тикет не зависит напрямую от
кода других тикетов, работает с сырыми `int[]` массивами палитровых индексов, как описано в
тикете 06 `PaletteCodec`, но не требует его класса напрямую)

## Задача

### Настройка модуля
Добавь в `ev-meshing/build.gradle.kts` (если ещё не добавлено тикетом 00) флаг
компиляции и рантайма для incubator-модуля:
```kotlin
tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("--add-modules", "jdk.incubator.vector"))
}
tasks.withType<Test> {
    jvmArgs("--add-modules", "jdk.incubator.vector")
}
// Если требуется явный флаг --enable-preview для конкретной версии JDK 21 — проверь через
// web search текущий статус Vector API в Java 21 (в некоторых версиях JDK это all еще
// incubator без --enable-preview, в некоторых требует дополнительный флаг) и добавь при
// необходимости. Не полагайся на память по этому вопросу — статус incubator-модулей мог
// измениться между версиями JDK 21.x.
```

### `MipAggregator`
```java
package dev.ev.meshing.mip;

/**
 * Aggregates child voxel palette indices into parent (coarser) mip-level voxels
 * using majority-vote (most frequent value among the 8 children becomes the
 * parent's value), vectorized via the Java Vector API for throughput during
 * bulk mip generation at cold start (see PERFORMANCE_MATH.md section A.5).
 *
 * Falls back to a scalar implementation automatically for the tail elements
 * that don't fill a full SIMD lane, and for any platform where Vector API
 * SIMD execution is unavailable at runtime (defensive fallback — do not assume
 * the incubator module is always usable in every deployment environment;
 * document how the fallback is triggered).
 */
public final class MipAggregator {

    /**
     * childPalette: flat array of 8*N values, where each contiguous run of 8
     * represents the children of one parent voxel (any grouping order is
     * acceptable as long as it's consistent with how the caller assembled the
     * array — document your chosen order, e.g. octree child index 0..7 order
     * matching SectionPos.child(childIndex) bit convention from ticket 01).
     * parentPalette: output array of N values, must be pre-allocated by caller
     * with length >= N.
     * parentCount: N, number of parent voxels to produce.
     */
    public void aggregateMajorityVote(int[] childPalette, int[] parentPalette, int parentCount);
}
```

## Требования к реализации

1. Используй `jdk.incubator.vector.IntVector` и `VectorSpecies<Integer>` (предпочтительно
   `IntVector.SPECIES_PREFERRED`, которая сама выбирает оптимальную ширину под доступное
   железо во время выполнения).

2. **Majority vote среди 8 значений** — это не тривиальная бинарная операция (в отличие от
   упрощённого примера "сравнить 2 кандидата" из архитектурного документа-черновика,
   который был лишь иллюстрацией концепции, а не финальным алгоритмом). Реализуй корректный
   majority vote для 8 произвольных int-значений на воксель:
   - Простой корректный подход: для каждого из 8 значений посчитать, сколько раз оно
     встречается среди всех 8 (включая себя), выбрать значение с максимальным count.
     **При равенстве count — ОБЯЗАТЕЛЬНОЕ, идентичное `16-meshing-mipgen-mvp.md` правило
     (не выбирай своё): выбирается значение с наименьшим индексом позиции среди 8 детей**
     (первое из значений, достигших максимального count, просматривая позиции 0..7 по
     порядку, побеждает) — это правило детерминировано и, что важно именно для SIMD-пути,
     естественно реализуется через позиционно-упорядоченные попарные сравнения (см. пункт
     ниже про попарные сравнения), а не через произвольный критерий вроде "меньшее
     числовое значение", который потребовал бы дополнительного явного сравнения самих
     значений поверх подсчёта count.
   - Векторизуй это по оси "N родителей", а не по оси "8 детей" — то есть SIMD-регистр
     должен параллельно обрабатывать `SPECIES.length()` РАЗНЫХ родительских вокселей
     одновременно (каждый со своими 8 детьми), а не 8 детей одного вокселя (что дало бы
     SIMD-ширину только 8, тогда как `SPECIES_PREFERRED` обычно шире и мы хотим использовать
     всю ширину на независимые родительские voxel'ы, которых обычно много больше 8).
   - Один из практичных способов: организуй попарные сравнения count'ов через несколько
     проходов SIMD-сравнений между "кандидат i" и "кандидат j" по всем парам (i,j) из 8
     детей, аккумулируя счётчики совпадений в SIMD-регистрах, затем финальный
     `IntVector.compare`/`blend` для выбора максимума по 8 counted-кандидатам, всё это
     параллельно по `SPECIES.length()` родителям сразу. Спроектируй конкретную реализацию
     сам — это открытая инженерная задача в рамках тикета, главное соблюдай: (а) корректность
     (совпадает со scalar reference implementation на всех тестовых случаях), (б)
     реальную векторизацию по оси родителей, а не просто "векторный API поверх, по сути,
     скалярного цикла".

3. Обязательно реализуй scalar reference implementation (`scalarMajorityVote(int[]
   childPalette, int offset)`) как приватный/пакетный метод — используется и как fallback
   для хвостовых элементов (`SPECIES.loopBound`), и как эталон корректности в тестах
   (тестируй SIMD-путь на согласованность со scalar-путём на большом числе случайных входов,
   не только на нескольких ручных примерах).

4. Проверка доступности Vector API в рантайме: если по какой-то причине `SPECIES_PREFERRED`
   недоступна или возникает `UnsupportedOperationException`/аналог при попытке
   векторизованных операций на конкретной платформе — задокументируй ожидаемое поведение
   (например, можно просто не ловить исключение специально, раз JDK 21 Vector API достаточно
   стабилен на всех основных платформах x86-64/ARM64, которые поддерживает NeoForge/Minecraft
   — если считаешь defensive try-catch избыточным, аргументированно пропусти его, но явно
   укажи в Javadoc это решение).

5. Никаких зависимостей на NeoForge/LWJGL, кроме incubator-модуля `jdk.incubator.vector`
   самого JDK.

## Юнит-тесты (обязательно, JUnit 5)

Создай `MipAggregatorTest`:
1. Простой случай: один родитель, 8 детей все с одинаковым значением → результат равен этому
   значению.
2. Один родитель, 8 детей, явное большинство (например, 5 из 8 равны X, остальные разные) →
   результат равен X.
3. Один родитель, 8 детей — все 8 разных значений (нет явного большинства, каждое
   встречается по разу) → результат соответствует задокументированному tie-break правилу
   (проверь именно то поведение, которое задокументировано в реализации).
4. Много родителей (например, 1000, чтобы гарантированно пересечь границу
   `SPECIES.loopBound` и упражнять и SIMD-путь, и scalar-хвост) со случайно сгенерированными
   (фиксированный seed) детьми — результат SIMD-пути **идентичен** результату вызова
   `scalarMajorityVote` независимо для каждого родителя (это главный тест корректности
   векторизации — сравнение с эталонной скалярной реализацией на большом объёме случайных
   данных).
5. `parentCount = 0` → метод не бросает исключение, ничего не пишет в `parentPalette`.
6. Граничный случай: `parentCount`, не кратный `SPECIES.length()` (например, `SPECIES.length()
   + 3`) — проверяет, что хвостовая scalar-обработка корректно покрывает оставшиеся элементы
   (это отдельный от теста №4 сценарий, специально проверяющий границу SIMD/scalar перехода).

## Критерии приёмки
1. Файл `ev-meshing/src/main/java/dev/ev/meshing/mip/MipAggregator.java`.
2. `ev-meshing/build.gradle.kts` обновлён с флагами для incubator-модуля.
3. Все юнит-тесты проходят, особенно тест №4 (SIMD результат идентичен scalar reference на
   большом объёме случайных данных) — это единственная надёжная проверка корректности
   векторизации.
4. Модуль компилируется и тестируется успешно с флагом `--add-modules jdk.incubator.vector`.
5. Никаких зависимостей на NeoForge/LWJGL.
