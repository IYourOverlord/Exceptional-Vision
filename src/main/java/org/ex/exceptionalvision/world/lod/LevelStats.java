package org.ex.exceptionalvision.world.lod;

/**
 * Node/quad counts for one quadtree level of a {@link LodBuildResult}. Exists so the
 * stage-2 readiness criterion from {@code 08_roadmap_milestones.md} — "для тестового
 * региона строится корректное дерево узлов с ожидаемым количеством квадов на каждом
 * уровне" — is directly checkable (in logs, or a test) without having to walk the
 * flattened node/quad lists back into levels by hand.
 */
public record LevelStats(int level, int nodeCount, int quadCount) {
}
