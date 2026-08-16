package dev.ev.meshing.stage;

import dev.ev.api.meshing.Quad;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaterialBinStageTest {

    private static Quad quad(int materialId) {
        return new Quad(0, 0, 0, 0, 1, 1, materialId);
    }

    @Test
    void emptyInput_producesEmptyOutput() {
        List<MaterialBin> bins = new MaterialBinStage().process(List.of());

        assertTrue(bins.isEmpty());
    }

    @Test
    void singleMaterial_producesOneBinWithOriginalOrderPreserved() {
        Quad q1 = quad(4);
        Quad q2 = quad(4);
        Quad q3 = quad(4);
        List<Quad> input = List.of(q1, q2, q3);

        List<MaterialBin> bins = new MaterialBinStage().process(input);

        assertEquals(1, bins.size());
        assertEquals(4, bins.get(0).materialId());
        assertEquals(List.of(q1, q2, q3), bins.get(0).quads());
    }

    @Test
    void interleavedMaterials_groupIntoSortedBinsWithStableRelativeOrder() {
        // materialId order in the input: 5, 2, 5, 8, 2 — deliberately not pre-grouped.
        Quad q0 = quad(5);
        Quad q1 = quad(2);
        Quad q2 = quad(5);
        Quad q3 = quad(8);
        Quad q4 = quad(2);
        List<Quad> input = List.of(q0, q1, q2, q3, q4);

        List<MaterialBin> bins = new MaterialBinStage().process(input);

        assertEquals(3, bins.size());
        assertEquals(List.of(2, 5, 8), bins.stream().map(MaterialBin::materialId).toList(),
            "bins must be sorted by materialId ascending");

        MaterialBin bin2 = bins.get(0);
        assertEquals(2, bin2.materialId());
        assertEquals(List.of(q1, q4), bin2.quads(),
            "quads sharing materialId=2 must keep their original relative order (position 1, then 4)");

        MaterialBin bin5 = bins.get(1);
        assertEquals(5, bin5.materialId());
        assertEquals(List.of(q0, q2), bin5.quads());

        MaterialBin bin8 = bins.get(2);
        assertEquals(8, bin8.materialId());
        assertEquals(List.of(q3), bin8.quads());
    }

    @Test
    void singleQuad_producesOneBinWithOneElement() {
        Quad q = quad(7);

        List<MaterialBin> bins = new MaterialBinStage().process(List.of(q));

        assertEquals(1, bins.size());
        assertEquals(7, bins.get(0).materialId());
        assertEquals(List.of(q), bins.get(0).quads());
    }
}
