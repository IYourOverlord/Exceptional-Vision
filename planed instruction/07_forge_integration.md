# Интеграция с рендер-пайплайном Forge 1.21.1

## Точки внедрения через Mixin

Основная цель — добавить собственный проход рендеринга LOD-геометрии,
не трогая логику ванильного рендеринга ближних чанков.

```java
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void onRenderLevelTail(GraphicsResourceAllocator allocator,
                                    DeltaTracker deltaTracker,
                                    boolean renderBlockOutline,
                                    Camera camera,
                                    GameRenderer gameRenderer,
                                    LightTexture lightTexture,
                                    Matrix4f projectionMatrix,
                                    Matrix4f frustumMatrix,
                                    CallbackInfo ci) {
        // Вызов своего рендер-менеджера ПОСЛЕ ванильного прохода чанков,
        // чтобы ближняя геометрия уже была в depth-buffer'е — это даёт
        // корректный depth-test на границе ближний/LOD рендер.
        LodRenderManager.get().renderFrame(camera, projectionMatrix, frustumMatrix);
    }
}
```

**Важно:** внедряться в `TAIL` инъекцию (после завершения метода), а не
переопределять сам метод — это минимизирует конфликты с другими модами,
которые тоже используют Mixin на `LevelRenderer`.

## Отдельный `RenderStateShard` / `RenderType` не подходит

Ванильный `RenderType`/`RenderStateShard` API рассчитан на пакетную
геометрию через vanilla `BufferBuilder`, что противоречит идее
GPU-driven рендера без CPU-геометрии. Поэтому LOD-рендер выполняется
как полностью отдельный OpenGL-проход вне системы `RenderType`:

```java
public final class LodRenderManager {
    public void renderFrame(Camera camera, Matrix4f proj, Matrix4f view) {
        if (!shouldRender()) return;

        // Сохранить текущее состояние GL, которое ожидает вернуть
        // Minecraft после инъекции (активная программа, буферы и т.д.)
        GlStateBackup backup = GlStateBackup.capture();

        gpuPipeline.renderLodFrame(
            proj.mul(view, new Matrix4f()),
            camera.getPosition().toVector3f(),
            frustumExtractor.extract(proj, view)
        );

        backup.restore();
    }
}
```

Возврат состояния OpenGL после своего прохода (`backup.restore()`)
обязателен — иначе последующий ванильный код (HUD, частицы, GUI) может
получить сломанное состояние GL и визуальные артефакты.

## Синхронизация системы конфигурации

```java
@Mod(YourMod.MODID)
public final class YourMod {
    public static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec.IntValue LOD_RENDER_DISTANCE =
        BUILDER.comment("Дистанция LOD-рендера в блоках")
               .defineInRange("lodRenderDistance", 2048, 256, 16384);
    public static final ModConfigSpec.BooleanValue GPU_DRIVEN_CULLING =
        BUILDER.comment("Использовать GPU compute culling (требует OpenGL 4.3+)")
               .define("gpuDrivenCulling", true);
}
```

## Обнаружение возможностей GPU при старте

```java
public final class GpuCapabilities {
    public static RenderPathChoice detect() {
        GLCapabilities caps = GL.getCapabilities();
        if (caps.OpenGL46 || caps.GL_ARB_indirect_parameters) {
            return RenderPathChoice.FULL_GPU_DRIVEN;
        }
        if (caps.OpenGL43) {
            return RenderPathChoice.COMPUTE_CULLING_CPU_READBACK;
        }
        return RenderPathChoice.UNSUPPORTED; // предложить отключить мод/фичу
    }
}
```

При `UNSUPPORTED` — явно уведомить игрока в чате/тултипе конфига, что
GPU не поддерживает нужные расширения, вместо тихого краша.

## Стыковка ближнего и дальнего рендера

- Ванильный chunk render рисует зону в пределах обычного render distance.
- LOD-рендер рисует зону начиная чуть **дальше** этой границы, с
  небольшим перекрытием (например 1-2 чанка), чтобы избежать видимого
  зазора при округлении границ.
- Depth-test должен быть включён в обоих проходах с одинаковой матрицей
  проекции — иначе на стыке возможен z-fighting.
- Рекомендуется добавить лёгкий dithering/fade на границе (плавное
  уменьшение alpha LOD-геометрии по мере приближения к ванильной зоне)
  для визуального сглаживания шва.

## Взаимодействие с шейдерпаками (Iris/OptiFine)

Полноценная поддержка шейдерпаков потребует, чтобы конкретный шейдерпак
явно знал о существовании вашего LOD-буфера (аналогично тому, как и DH,
и Voxy требуют explicit shader support) — это отдельная большая задача
интеграции с Iris API и не входит в базовый MVP.
