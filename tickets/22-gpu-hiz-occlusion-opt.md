# Тикет 22-opt — Hi-Z Occlusion Pyramid

## ⚠️ Когда это применять

Это тикет Волны 2 (см. `MVP_INDEX.md`). В Волне 1 (MVP) occlusion culling **отсутствует
полностью** — `21-gpu-simple-traversal-mvp.md` делает только frustum test. **Не выполняй
этот тикет**, пока не пройден `P0-profiling-checkpoint.md` и результаты не показали:
- Метрика "Overdraw / GPU fragment shader cost в сценах с сильным взаимным перекрытием
  геометрии" (Сценарий B, `P0-profiling-checkpoint.md`) заметно высока в характерных для
  контента мода сценах (горная местность, узкие долины, плотная застройка на дальних LOD) —
  то есть GPU реально тратит существенное время на рендер геометрии, которая формально
  проходит frustum test, но фактически полностью перекрыта другой геометрией и невидима.

Если эта метрика невысока (например, тестовый мир преимущественно открытая местность без
сильного взаимного перекрытия) — этот тикет не нужен, MVP-версия без occlusion culling
достаточна. Не внедряй occlusion culling "про запас" — это единственная оптимизация из всей
Волны 2, эффект которой сильно зависит от КОНТЕНТА мира, не только от общего объёма данных,
поэтому особенно важно опираться на измерение в характерном для реального использования
контенте, а не на общий принцип "occlusion culling всегда полезен".

Этот тикет добавляется ПОВЕРХ traversal (либо `21-gpu-simple-traversal-mvp.md`, либо
`21-gpu-persistent-traversal-shader-opt.md`, если тот уже применён) — интегрируется как
дополнительный тест внутри существующего traversal-прохода, не заменяет его целиком.

## Контекст
EV — мод дальней прорисовки для NeoForge 1.21.1. Модуль `ev-gpu`. Реализует
Hierarchical-Z (Hi-Z) occlusion culling, обоснованный в PERFORMANCE_MATH.md разделе B.3:
тест видимости bounding box узла дерева сводится к ОДНОЙ текстурной выборке на правильно
выбранном mip-уровне пирамиды минимальной глубины (`textureLod`), вместо линейного прохода
по каждому экранному пикселю проекции bounding box — стоимость теста узла становится
`O(log(размер AABB в пикселях))` вместо `O(число пикселей)`.

## Готовый контракт из зависимостей (тикеты 04, 17, 18 — уже реализованы)
```java
package dev.ev.api.gpu;
public interface RenderBackend {
    GpuTexture createTexture(TextureDesc desc);
    ComputePipeline compilePipeline(ShaderSource source, PipelineLayout layout);
    // ...
}
public record TextureDesc(int width, int height, int depth, TextureFormat format, int mipLevels) {
    public static TextureDesc texture2D(int width, int height, TextureFormat format, int mipLevels);
}
public enum TextureFormat { R32F, RGBA8, RGBA16F, DEPTH32F, R32UI }
public interface GpuTexture {
    TextureDesc descriptor();
    void free();
}
```

## Задача

### Часть 1 — построение Hi-Z пирамиды (GLSL compute + Java обвязка)

Создать `ev-gpu/src/main/resources/shaders/hiz_build.comp` — compute shader,
последовательно строящий каждый следующий mip-уровень пирамиды из предыдущего, беря МИНИМУМ
(не среднее — occlusion-пирамида хранит наибольшую глубину, за которой точно ничего не
видно, поэтому агрегация должна быть консервативной: минимум глубины среди 2×2 блока
исходного mip'а, что соответствует "ближайшей" точке блока, обеспечивая, что тест на этом
mip-уровне не даст ложноположительного occlusion) из 2×2 блока текселей предыдущего уровня:

```glsl
#version 450 core
layout(local_size_x = 8, local_size_y = 8) in;

layout(binding = 0) uniform sampler2D previousMip;
layout(binding = 1, r32f) uniform writeonly image2D currentMip;

void main() {
    ivec2 outCoord = ivec2(gl_GlobalInvocationID.xy);
    ivec2 outSize = imageSize(currentMip);
    if (outCoord.x >= outSize.x || outCoord.y >= outSize.y) return;

    ivec2 inCoord = outCoord * 2;
    // Sample the 2x2 source block, clamping to input texture bounds for odd
    // dimensions (when previousMip size is not evenly divisible by 2).
    float d00 = texelFetch(previousMip, inCoord, 0).r;
    float d10 = texelFetch(previousMip, clampToBounds(inCoord + ivec2(1,0), previousMip), 0).r;
    float d01 = texelFetch(previousMip, clampToBounds(inCoord + ivec2(0,1), previousMip), 0).r;
    float d11 = texelFetch(previousMip, clampToBounds(inCoord + ivec2(1,1), previousMip), 0).r;

    float minDepth = min(min(d00, d10), min(d01, d11));
    imageStore(currentMip, outCoord, vec4(minDepth, 0, 0, 0));
}
```

(Реализуй `clampToBounds` как реальную вспомогательную функцию GLSL, определяющую размер
исходной текстуры через `textureSize(previousMip, 0)` и ограничивающую координату в
допустимый диапазон — псевдокод выше указывает намерение, доведи до синтаксически корректного
GLSL.)

Mip 0 пирамиды — это сам depth-буфер сцены (не строится этим шейдером, копируется/используется
напрямую как источник). Этот compute shader вызывается многократно (по одному dispatch на
каждый следующий mip-уровень, начиная с уровня 1), последовательно уменьшая разрешение вдвое
по каждой оси, пока не будет построена вся пирамида (обычно `log2(max(width,height))+1`
уровней). Это единственное место в проекте, где ПОСЛЕДОВАТЕЛЬНЫЕ dispatch'ы с барьером
между ними оправданы и приемлемы (в отличие от traversal, тикет 21) — построение пирамиды
принципиально последовательно (каждый уровень зависит от предыдущего), число уровней мало
(порядка 10-12 для типичных разрешений экрана, не растёт с глубиной октодерева мира как было
у traversal), и это одноразовая операция раз в кадр, не по одной на узел дерева.

### Java: `HiZPyramid`
```java
package dev.ev.gpu.occlusion;

import dev.ev.api.gpu.*;

/**
 * Manages a Hi-Z (hierarchical minimum-depth) mip pyramid built from the scene's
 * depth buffer each frame, used for O(1)-per-node occlusion testing during
 * traversal (see PERFORMANCE_MATH.md section B.3). Rebuild each frame after the
 * opaque depth pre-pass, before the traversal compute dispatch that consumes it.
 */
public final class HiZPyramid implements AutoCloseable {

    public HiZPyramid(RenderBackend backend, int screenWidth, int screenHeight) {
        // allocates a GpuTexture with mipLevels = ceil(log2(max(width,height))) + 1,
        // format R32F, and compiles the hiz_build.comp pipeline.
    }

    /**
     * Rebuilds all mip levels from the given scene depth texture (mip 0 = a direct
     * view/copy of sceneDepth, subsequent levels built via successive hiz_build.comp
     * dispatches). Must be called once per frame before traversal.
     */
    public void rebuild(CommandList commands, GpuTexture sceneDepth);

    public GpuTexture texture();

    @Override
    void close();
}
```

### Часть 2 — GLSL-функция occlusion теста (для использования в `traversal.comp`, тикет 21)

Создать `ev-gpu/src/main/resources/shaders/include/hiz_test.glsl` с реализацией
реальной функции `testOcclusion`, которую тикет 21 оставил заглушкой:

```glsl
// hiz_test.glsl — real Hi-Z occlusion test, replaces the ticket-21 placeholder.
// Include this AFTER node_buffers.glsl and the hiZPyramid sampler binding is
// declared, e.g.:
//   layout(binding = 9) uniform sampler2D hiZPyramid;
//   uniform mat4 viewProjMatrix; (already in FrameUniforms from ticket 21, reuse it)
//   uniform vec2 screenSize;

bool testOcclusionHiZ(vec4 bounds, sampler2D hiZPyramid, mat4 viewProjMatrix, vec2 screenSize) {
    // 1. Project the bounding sphere (bounds.xyz center, bounds.w radius) to screen
    //    space: transform center by viewProjMatrix, perspective-divide, compute an
    //    approximate screen-space AABB by projecting center +/- radius along view-aligned
    //    axes (a common simplification: project 2 or more points offset by radius along
    //    camera right/up vectors, or use a simpler conservative screen-radius estimate
    //    from clip-space w). Implement a correct, reasonably conservative approach —
    //    document the approximation choice in a comment.
    // 2. Compute mip level: mipLevel = ceil(log2(max(screenAABB.width, screenAABB.height)))
    //    clamped to [0, textureQueryLevels(hiZPyramid)-1].
    // 3. Sample: float occluderDepth = textureLod(hiZPyramid, screenAABB.center01, mipLevel).r;
    //    (screenAABB.center01 = screen-space center converted to [0,1] UV range)
    // 4. Compare: if the bounding sphere's nearest point's depth is farther than
    //    occluderDepth (i.e. definitely behind existing geometry), return true (occluded).
    //    THIS PROJECT USES STANDARD (NOT REVERSED) DEPTH: EV renders into the SAME
    //    depth buffer as vanilla Minecraft terrain (see ticket 27's RenderLevelStageEvent
    //    hook — EV's geometry must depth-test correctly against vanilla terrain
    //    already in the buffer, so it cannot use a different depth convention than
    //    vanilla). Vanilla Minecraft (and the broader Java/LWJGL ecosystem it's built on)
    //    uses standard OpenGL depth: smaller value = nearer, larger = farther, default
    //    GL_LESS depth test — confirmed via investigation into related performance mods
    //    (e.g. Sodium's own tracking issue for ADDING reversed-Z as a future improvement,
    //    which would not be a pending feature request if vanilla already had it). Do NOT
    //    implement reversed-Z here — use the standard comparison: occluded when
    //    (nearestPointDepth > occluderDepth), i.e. the candidate's nearest depth is
    //    numerically GREATER (farther) than what the Hi-Z pyramid already recorded as the
    //    nearest occluder at that screen location. If a future ticket introduces
    //    ARB_clip_control / reversed-Z for the WHOLE rendering pipeline (as e.g. Sodium
    //    has discussed doing for its own terrain rendering), this comparison direction
    //    would need to be revisited in lockstep with that change — do not flip it
    //    unilaterally here without also changing how the scene depth buffer itself is
    //    produced.
    // Return false (not occluded / cannot determine) for any case where the bounding
    // sphere is partially or fully in front of the camera near plane or otherwise not
    // cleanly projectable — false negatives (treating as visible when actually occluded)
    // only cost some wasted rendering; false positives (culling something actually
    // visible) cause visible pop-in bugs and must be avoided by being conservative here.
}
```

Дополни `traversal.comp` (тикет 21): замени тело-заглушку `testOcclusion(vec4 bounds)` на
вызов `testOcclusionHiZ(bounds, hiZPyramid, viewProjMatrix, screenSize)`, добавь
соответствующий `sampler2D hiZPyramid` binding и `screenSize` uniform в
`FrameUniforms`/отдельный uniform блок traversal-шейдера.

## Требования к реализации
1. `HiZPyramid.rebuild` — mip 0 должен быть создан из `sceneDepth` (либо прямым
   `glCopyImageSubData`-подобным копированием через `CommandList.copyBuffer`-аналог для
   текстур — если в контракте `CommandList` из тикета 04 нет метода для copy текстур,
   добавь его туда, задокументировав, что расширяешь контракт `RenderBackend`/`CommandList`
   из тикета 04 для нужд этого тикета, и обнови соответствующий файл тикета 04, если он уже
   существует в проекте — либо, как более простой альтернативный подход, рендерить сцену
   depth напрямую В mip 0 текстуры пирамиды через framebuffer attachment, минуя отдельное
   копирование — выбери и обоснуй один подход).
2. Число mip-уровней — `floor(log2(max(width,height))) + 1`, посчитанное в конструкторе
   `HiZPyramid`, использованное и для аллокации текстуры, и для клампа `mipLevel` в GLSL
   `hiz_test.glsl`.
3. Последовательные dispatch'ы `hiz_build.comp` (по одному на mip-уровень 1..N) с барьером
   `GL_TEXTURE_FETCH_BARRIER_BIT` (или соответствующим `BarrierScope` из контракта тикета 04)
   между каждым, так как каждый следующий уровень читает результат предыдущего.
4. Никакого мутируемого статического состояния.

## Тесты
1. Если headless GL доступен: интеграционный тест — создай синтетический depth-буфер малого
   размера с известным паттерном (например, половина экрана "близко", половина "далеко"),
   построй пирамиду, прочитай верхний (самый грубый) mip-уровень обратно, проверь, что
   значение соответствует минимуму по всей области (агрегация действительно консервативна —
   берёт минимум, не среднее/максимум).
2. Без GL: юнит-тест на чистую арифметику расчёта числа mip-уровней и выбора mip level в
   `testOcclusionHiZ`-подобной формуле, если эту часть логики можно вынести в тестируемый
   Java-эквивалент для проверки формулы отдельно от GLSL (например, портируй формулу расчёта
   `mipLevel = ceil(log2(max(w,h)))` в маленький Java-метод, используемый и как референс для
   документации GLSL-кода, и протестируй его на нескольких значениях).

## Критерии приёмки
1. Файлы: `ev-gpu/src/main/resources/shaders/hiz_build.comp`,
   `ev-gpu/src/main/resources/shaders/include/hiz_test.glsl`,
   `ev-gpu/src/main/java/dev/ev/gpu/occlusion/HiZPyramid.java`.
2. `traversal.comp` (тикет 21) дополнен реальным вызовом `testOcclusionHiZ` вместо заглушки.
3. Доступные тесты проходят.
4. Направление сравнения глубины (какое значение считается "ближе") задокументировано явно
   и согласовано с остальным rendering pipeline (обычным depth-тестом сцены).
5. Модуль компилируется без ошибок на Java-стороне.
