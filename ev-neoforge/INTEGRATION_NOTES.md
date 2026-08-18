# INTEGRATION_NOTES.md — Ticket 31-mvp Integration

## Worker Thread Pool

**Location:** `ev-render/src/main/java/dev/ev/render/scheduling/MeshWorkerPool.java`
**Created/shut down by:** `EVInstance` (constructor / `close()`)
**Thread count source:** `EVConfig.workerThreadCount()`

Workers poll `MeshTaskQueue<MeshTask>` non-blocking (10ms sleep between empty polls), run the full pipeline (`OccupancyStage` → `GreedyMeshStage` → `MaterialBinStage` → `MeshletPackStage`), check `GeometryChangeDeduplicator`, and deposit `MeshletBatch` results into a `ConcurrentLinkedQueue` drained by the render thread.

If "sections aren't being meshed" — debug here first.

---

## SectionPos → Geometry Mapping

**MVP approach:** Java-side `ConcurrentHashMap<Long, MeshletBatch>` in `SectionGeometryMap`.
No GPU-resident indexation (that's `20-opt`/`21-opt`). The render pass queries this map for each visible section returned by `SimpleTraversal`.

---

## Block Change Listeners

**Events used:** `BlockEvent.BreakEvent`, `BlockEvent.EntityPlaceEvent` in `EV.java`.
**Known limitation:** does not catch non-player block changes (pistons, falling blocks, redstone-triggered dispensers). Covering those would require a Mixin into `Level.setBlock` — deferred to a future ticket.

**Coordinate mapping:** `SectionPos.fromBlockCoord(0, x, y, z)` → LOD-0 section.

---

## MeshingContext

**MVP:** `SimpleMeshingContext` — returns air (0) for all neighbor boundary lookups. This means every section-boundary face is emitted as visible, creating redundant geometry at seams. Correct but wasteful; a `MinecraftMeshingContext` using `SectionCache` for neighbor lookups is implemented in `ev-neoforge/adapter/` but not yet wired into the worker pool (requires careful thread-safety consideration since `SectionCache.acquire` may trigger I/O).

---

## SectionGenerationPolicy

LOD level < 2 → full block-level voxelization
LOD level ≥ 2 → `CoarseSectionGenerator` (heightmap-based)

Threshold is fixed at 2 for MVP. Should be tuned after P0 profiling.

---

## Sodium / Embeddium Compatibility

`EVInstance.renderFarLod()` restores OpenGL state in a `finally` block:
- `glUseProgram(0)`
- `glBindBuffer(GL_ARRAY_BUFFER, 0)`
- `glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0)`
- `glDepthMask(true)`
- `glEnable(GL_DEPTH_TEST)`

This prevents EV's GL state leaking into Sodium's render pass.

---

## Known MVP Limitations (expected, NOT bugs)

1. **No temporal coherence** — frame time at rest == frame time during motion.
2. **No occlusion culling** — occluded geometry behind terrain is still drawn.
3. **Many draw calls** — no indirect rendering batching (`20-opt`).
4. **Full-section rebuild on any block change** — no sub-region granularity (`26-opt`).
5. **No LOD rendering shaders yet** — `renderFarLod` records visible section metrics but does not issue actual draw calls (no `.glsl` vertex/fragment source for LOD geometry). Visual rendering requires shader compilation in a future ticket.
6. **Non-player block changes not tracked** — see Block Change Listeners above.

---

## Divergences from Individual Tickets

| Ticket | Expected | Actual |
|--------|----------|--------|
| 15-mvp (MeshTaskQueue) | Blocking `poll()` | Workers use `pollNonBlocking()` + 10ms sleep for clean shutdown |
| 24 (FrameGraphBuilder) | Complex multi-pass GPU pipeline | Single "far-lod-pass" recording metrics only |
| 04/18 (RenderBackend draw) | Real draw calls per visible section | Deferred — no LOD shaders compiled yet |
| 09 (CoarseSectionGenerator) | Automatically invoked for far LOD | Policy exists (`SectionGenerationPolicy`) but initial section population not yet triggered on world load |
