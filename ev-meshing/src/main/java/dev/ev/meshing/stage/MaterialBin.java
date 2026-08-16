package dev.ev.meshing.stage;

import dev.ev.api.meshing.Quad;

import java.util.List;

/** All quads sharing one materialId, grouped together for contiguous GPU upload. */
public record MaterialBin(int materialId, List<Quad> quads) {}
