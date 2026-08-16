# Тикет 16-mvp — Scalar Mip-Level Aggregation

## Контекст
Часть Волны 1 (MVP) плана EV — см. `MVP_INDEX.md` за полным обоснованием MVP-first
подхода. Это упрощённая версия агрегации детских вокселей в родительский mip-уровень:
обычный скалярный Java-цикл majority-vote, БЕЗ SIMD/Vector API. Более быстрая версия
(`16-meshing-simd-mipgen-opt.md`) вводится только если профилирование
(`P0-profiling-checkpoint.md`) реально покажет mip-агрегацию как заметную долю времени
холодного старта — не заранее, и не как "естественная" оптимизация без измерения (Vector
API — incubator-модуль JDK 21, введение этой зависимости не бесплатно с точки зрения
стабильности между патч-версиями JDK).

## Задача

### `MipAggregator`
```java
package dev.ev.meshing.mip;

/**
 * MVP implementation: aggregates child voxel palette indices into parent
 * (coarser) mip-level voxels using a plain scalar Java loop computing
 * majority-vote (most frequent value among the 8 children becomes the
 * parent's value). No SIMD/Vector API — see MVP_INDEX.md for why this simple
 * version comes first; 16-meshing-simd-mipgen-opt.md replaces this only if
 * profiling shows mip-aggregation is a meaningful fraction of cold-start time.
 *
 * The public method signature intentionally matches
 * 16-meshing-simd-mipgen-opt.md's MipAggregator so that swapping
 * implementations later (if profiling justifies it) does not require changing
 * calling code.
 */
public final class MipAggregator {

    /**
     * childPalette: flat array of 8*N values, where each contiguous run of 8
     * represents the children of one parent voxel (octree child index 0..7
     * order matching SectionPos.child(childIndex) bit convention from ticket 01).
     * parentPalette: output array of N values, must be pre-allocated by caller
     * with length >= N.
     * parentCount: N, number of parent voxels to produce.
     */
    public void aggregateMajorityVote(int[] childPalette, int[] parentPalette, int parentCount);
}
```

## Требования к реализации
1. Для каждого родительского вокселя `i` в `[0, parentCount)`: посчитай, сколько раз каждое
   из 8 значений `childPalette[i*8 .. i*8+7]` встречается среди этих же 8 значений (включая
   себя), выбери значение с максимальным count. **При равенстве count между несколькими
   значениями — окончательное, обязательное правило (не выбирай своё — используй именно
   это, идентичное для `16-mvp` и `16-opt`, чтобы избежать молчаливого расхождения между
   версиями при возможной будущей замене реализации): выбирается значение с наименьшим
   индексом позиции среди 8 детей (т.е. `childPalette[i*8 + 0]`, `[i*8 + 1]`, ..., в этом
   порядке — первое из значений, достигших максимального count, просматривая позиции
   0..7 по порядку, побеждает)**. Это правило детерминировано, не зависит от численного
   значения самих палитровых индексов (что было бы произвольным и менее интуитивным
   критерием), и просто реализуемо как в скалярном, так и в векторизованном виде (SIMD-путь
   `16-opt` естественно обрабатывает кандидатов в фиксированном позиционном порядке).
2. Простейшая корректная реализация: вложенный цикл `for j in 0..7: count[childPalette[i*8+j]]
   ++` через локальную `HashMap<Integer,Integer>` или, эффективнее и без аллокаций на
   критическом пути, через прямой `O(8*8)=O(64)` попарный подсчёт (для каждого из 8 кандидатов
   — сколько из оставшихся 7 равны ему) — оба варианта корректны и достаточно просты для MVP;
   предпочти второй (без аллокации `HashMap` на каждый вызов) для меньшего GC-давления даже
   в MVP-версии, так как это не усложняет код существенно.
3. `parentCount = 0` → метод не бросает исключение, ничего не пишет в `parentPalette`.
4. Никаких зависимостей на NeoForge/LWJGL, никакого `jdk.incubator.vector` — чистый,
   обычный Java без специальных модулей/флагов компиляции.

## Юнит-тесты (обязательно, JUnit 5)

Создай `MipAggregatorTest`:
1. Простой случай: один родитель, 8 детей все с одинаковым значением → результат равен этому
   значению.
2. Один родитель, 8 детей, явное большинство (например, 5 из 8 равны X, остальные разные) →
   результат равен X.
3. Один родитель, 8 детей — все 8 разных значений (нет явного большинства) → результат
   соответствует задокументированному tie-break правилу.
4. Много родителей (например, 1000) со случайно сгенерированными (фиксированный seed)
   детьми — каждый результат корректен относительно независимо посчитанного вручную/через
   отдельную наивную reference-реализацию в самом тесте (не самого метода — двойная проверка
   через независимую логику, пусть даже такую же простую, но написанную отдельно в тесте,
   чтобы не проверять метод сам через себя).
5. `parentCount = 0` → метод не бросает исключение, `parentPalette` не изменяется относительно
   исходного состояния (если был предзаполнен каким-то sentinel-значением до вызова).

## Критерии приёмки
1. Файл `ev-meshing/src/main/java/dev/ev/meshing/mip/MipAggregator.java`.
2. Все юнит-тесты проходят.
3. Модуль компилируется и тестируется без специальных JVM-флагов (`--add-modules` и
   подобное не требуется для этой версии).
4. Javadoc класса явно документирует, что это MVP/Волна-1 реализация и что `16-opt`
   существует как более быстрая замена, если профилирование покажет нужду, и что
   tie-break правило должно оставаться согласованным между версиями.
5. `PROJECT_INDEX.md` (тикет 32) обновлён с пометкой "MipAggregator — MVP (скалярная)
   версия активна, SIMD opt-версия в банке, не применена".
