package dev.ev.api.meshing;

/**
 * A single axis-aligned quad produced by greedy meshing, in section-local integer
 * coordinates (0..32 inclusive, since a quad spans voxel boundaries, not voxel centers).
 */
public record Quad(
    int faceDirection, // 0=+X, 1=-X, 2=+Y, 3=-Y, 4=+Z, 5=-Z
    int x, int y, int z,     // origin corner, in local voxel-boundary units
    int width, int height,   // extent along the two axes perpendicular to faceDirection
    int materialId
) {}
