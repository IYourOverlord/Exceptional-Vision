package org.ex.exceptionalvision.world.lod;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Assigns a stable, small integer index to each distinct {@link BlockState} that ends
 * up in a LOD column, alongside its RGBA8 color — the {@code MaterialPalette} SSBO/UBO
 * from {@code 03_data_formats.md}.
 * <p>
 * Colors come from the block's map color ({@link BlockState#getMapColor}), queried with
 * {@link EmptyBlockGetter#INSTANCE} as a context-free {@code BlockGetter}: LOD columns
 * are built directly from region-file NBT (see {@code 05_world_data_ingestion.md}),
 * independent of any loaded {@code Level}, so there is no real world context available
 * for context-sensitive colors (e.g. biome grass/foliage tint) — those fall back to
 * their block's default map color instead of the biome-tinted one. Revisiting this
 * (e.g. sampling the region's biome data too) is a reasonable future improvement, not
 * something this stage depends on.
 * <p>
 * Thread-safe: {@link LodBuilder} instances processing different regions concurrently
 * (see {@link LodBuilderExecutor}) are expected to share one {@code MaterialPalette} so
 * palette indices stay globally consistent across the whole dimension's cache.
 */
public final class MaterialPalette {

    private final Map<BlockState, Integer> stateToIndex = new HashMap<>();
    private final List<Integer> packedColors = new ArrayList<>();

    public synchronized int indexFor(BlockState state) {
        Integer existing = stateToIndex.get(state);
        if (existing != null) {
            return existing;
        }
        int color = computeColor(state);
        int index = packedColors.size();
        packedColors.add(color);
        stateToIndex.put(state, index);
        return index;
    }

    public synchronized int size() {
        return packedColors.size();
    }

    /** Snapshot of the palette's RGBA8 colors, index-aligned; safe to upload to the MaterialPalette SSBO/UBO. */
    public synchronized List<Integer> colorsSnapshot() {
        return List.copyOf(packedColors);
    }

    private int computeColor(BlockState state) {
        MapColor mapColor = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        int rgb = mapColor.col & 0xFFFFFF;
        return 0xFF000000 | rgb; // RGBA8, fully opaque
    }
}
