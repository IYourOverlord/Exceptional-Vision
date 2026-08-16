package dev.ev.storage.coarsegen;

import java.util.HashSet;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Test {@link HeightmapSource} backed by a constant height/material, with an optional
 * predicate marking specific columns unavailable, and call counters for verifying the
 * generator samples at most one point per voxel-column (the O(1)-per-voxel guarantee).
 */
final class FakeHeightmapSource implements HeightmapSource {

    private final int constantHeight;
    private final int constantMaterial;
    private final BiPredicate<Integer, Integer> availablePredicate;

    private int surfaceHeightCalls = 0;
    private int surfaceMaterialCalls = 0;
    private final Set<Long> sampledColumns = new HashSet<>();

    FakeHeightmapSource(int constantHeight, int constantMaterial) {
        this(constantHeight, constantMaterial, (x, z) -> true);
    }

    FakeHeightmapSource(int constantHeight, int constantMaterial, BiPredicate<Integer, Integer> availablePredicate) {
        this.constantHeight = constantHeight;
        this.constantMaterial = constantMaterial;
        this.availablePredicate = availablePredicate;
    }

    @Override
    public int surfaceHeight(int worldX, int worldZ) {
        surfaceHeightCalls++;
        sampledColumns.add(columnKey(worldX, worldZ));
        return constantHeight;
    }

    @Override
    public int surfaceMaterial(int worldX, int worldZ) {
        surfaceMaterialCalls++;
        return constantMaterial;
    }

    @Override
    public boolean isAvailable(int worldX, int worldZ) {
        return availablePredicate.test(worldX, worldZ);
    }

    int surfaceHeightCallCount() {
        return surfaceHeightCalls;
    }

    int surfaceMaterialCallCount() {
        return surfaceMaterialCalls;
    }

    /** Number of distinct (worldX, worldZ) columns ever queried for height — used to confirm
     *  each voxel-column is sampled at exactly one point, not re-sampling the same point twice. */
    int distinctSampledColumnCount() {
        return sampledColumns.size();
    }

    private static long columnKey(int worldX, int worldZ) {
        return (((long) worldX) << 32) ^ (worldZ & 0xFFFFFFFFL);
    }
}
