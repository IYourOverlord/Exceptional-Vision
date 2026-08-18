package dev.ev.render.scheduling;

import dev.ev.api.meshing.MeshBuilder;
import dev.ev.api.meshing.MeshingContext;
import dev.ev.api.meshing.MeshletBatch;
import dev.ev.api.meshing.Quad;
import dev.ev.api.storage.WorldSectionHandle;
import dev.ev.meshing.stage.GreedyMeshStage;
import dev.ev.meshing.stage.MaterialBin;
import dev.ev.meshing.stage.MaterialBinStage;
import dev.ev.meshing.stage.MeshletPackStage;
import dev.ev.meshing.stage.OccupancySet;
import dev.ev.meshing.stage.OccupancyStage;

import java.util.List;

/**
 * Full meshing pipeline: OccupancyStage → GreedyMeshStage → MaterialBinStage → MeshletPackStage.
 * Implements {@link MeshBuilder} for use by worker threads. Thread-safe — each stage is
 * stateless and produces fresh output per call.
 */
public final class MeshingPipelineRunner implements MeshBuilder {

    private final OccupancyStage occupancyStage = new OccupancyStage();
    private final GreedyMeshStage greedyMeshStage = new GreedyMeshStage();
    private final MaterialBinStage materialBinStage = new MaterialBinStage();
    private final MeshletPackStage meshletPackStage = new MeshletPackStage();

    @Override
    public MeshletBatch build(WorldSectionHandle section, MeshingContext ctx) {
        OccupancySet occupancy = occupancyStage.process(section);
        List<Quad> quads = greedyMeshStage.process(section, occupancy, ctx);
        List<MaterialBin> bins = materialBinStage.process(quads);
        return meshletPackStage.process(section.position(), bins);
    }
}
