# Тикет 31-mvp — Реализация недостающих компонентов интеграции

Реализация всех ❌-пунктов из пользовательского анализа: оркестрация кадра, storage→meshing связка, Minecraft-адаптеры, SectionPos→GpuBuffer маппинг, INTEGRATION_NOTES.md.

## Proposed Changes

### ev-render: MeshSchedulingCoordinator (Шаг 3)

#### [NEW] [MeshSchedulingCoordinator.java](file:///c:/MYmods/1.21.1/NeoForge/Exceptional%20Vision/ev-render/src/main/java/dev/ev/render/scheduling/MeshSchedulingCoordinator.java)

Координатор, вызываемый раз в тик из EVInstance. Для каждой известной, но ещё не смешенной секции:
- Вычисляет приоритет через `MeshPriority.computeWithNearTierCheck`
- Вызывает `MeshTaskQueue.submit(...)`
- Использует `SectionGenerationPolicy` для решения full vs coarse

#### [NEW] [SectionGenerationPolicy.java](file:///c:/MYmods/1.21.1/NeoForge/Exceptional%20Vision/ev-render/src/main/java/dev/ev/render/scheduling/SectionGenerationPolicy.java)

Решает по SectionPos и расстоянию, использовать coarse (CoarseSectionGenerator) или full voxelization.

---

### ev-render: MeshWorkerPool (Шаг 2 — worker thread pool)

#### [NEW] [MeshWorkerPool.java](file:///c:/MYmods/1.21.1/NeoForge/Exceptional%20Vision/ev-render/src/main/java/dev/ev/render/scheduling/MeshWorkerPool.java)

ExecutorService через `newFixedThreadPool(config.workerThreadCount())`. Каждый worker в цикле:
- `MeshTaskQueue.poll()` (blocking)
- Прогон через OccupancyStage → GreedyMeshStage → MaterialBinStage → MeshletPackStage
- GeometryChangeDeduplicator проверка
- Результат → ConcurrentLinkedQueue для upload на render thread

---

### ev-render: SectionGeometryMap (SectionPos → GpuBuffer)

#### [NEW] [SectionGeometryMap.java](file:///c:/MYmods/1.21.1/NeoForge/Exceptional%20Vision/ev-render/src/main/java/dev/ev/render/scheduling/SectionGeometryMap.java)

Java-side `ConcurrentHashMap<Long, MeshletBatch>` — маппинг `SectionPos.encode()` → готовый к рендеру MeshletBatch. Используется SimpleTraversal для определения, что рисовать.

---

### ev-render: MeshingPipelineRunner (сводит pipeline stages)

#### [NEW] [MeshingPipelineRunner.java](file:///c:/MYmods/1.21.1/NeoForge/Exceptional%20Vision/ev-render/src/main/java/dev/ev/render/scheduling/MeshingPipelineRunner.java)

Объединяет OccupancyStage → GreedyMeshStage → MaterialBinStage → MeshletPackStage в один вызов. Implements `MeshBuilder`.

---

### ev-neoforge: Minecraft-адаптеры (Шаг 4)

#### [NEW] [MinecraftHeightmapSource.java](file:///c:/MYmods/1.21.1/NeoForge/Exceptional%20Vision/ev-neoforge/src/main/java/dev/ev/neoforge/adapter/MinecraftHeightmapSource.java)

`implements HeightmapSource` — делегирует к `LevelReader.getHeight(Heightmap.Types.WORLD_SURFACE, x, z)`.

#### [NEW] [MinecraftMeshingContext.java](file:///c:/MYmods/1.21.1/NeoForge/Exceptional%20Vision/ev-neoforge/src/main/java/dev/ev/neoforge/adapter/MinecraftMeshingContext.java)

`implements MeshingContext` — палитра BlockState → paletteIndex, neighbor lookup через VoxelStorage.

#### [NEW] [BlockPalette.java](file:///c:/MYmods/1.21.1/NeoForge/Exceptional%20Vision/ev-neoforge/src/main/java/dev/ev/neoforge/adapter/BlockPalette.java)

Global BlockState → int paletteIndex mapping.

---

### ev-neoforge: Подписка на block-change event (Шаг 4)

#### [MODIFY] [EV.java](file:///c:/MYmods/1.21.1/NeoForge/Exceptional%20Vision/ev-neoforge/src/main/java/dev/ev/neoforge/EV.java)

Добавить подписку на `BlockEvent.BreakEvent` и `BlockEvent.EntityPlaceEvent` → `DirtySectionTracker.markSectionDirty`.

---

### ev-neoforge: Полная оркестрация в EVInstance (Шаг 2)

#### [MODIFY] [EVInstance.java](file:///c:/MYmods/1.21.1/NeoForge/Exceptional%20Vision/ev-neoforge/src/main/java/dev/ev/neoforge/EVInstance.java)

- Создание worker thread pool
- Создание MeshTaskQueue, DirtySectionTracker, SimpleTraversal, SectionGeometryMap
- `renderFarLod`: FrustumTester → SimpleTraversal.computeVisible → draw calls
- `tickMeshing()` вызываемый раз в кадр — drain dirty → schedule meshing
- Graceful shutdown в close()

---

### ev-neoforge: INTEGRATION_NOTES.md (Шаг 6)

#### [NEW] [INTEGRATION_NOTES.md](file:///c:/MYmods/1.21.1/NeoForge/Exceptional%20Vision/ev-neoforge/INTEGRATION_NOTES.md)

---

## Verification Plan

### Manual Verification
- `./gradlew build` — компиляция всего проекта
- Визуальная проверка отсутствия UnsupportedOperationException-заглушек на рабочих путях

> [!IMPORTANT]
> Компиляция и тесты в текущей среде невозможны (нет JDK/Gradle) — отмечено как "не подтверждено фактическим прогоном".
