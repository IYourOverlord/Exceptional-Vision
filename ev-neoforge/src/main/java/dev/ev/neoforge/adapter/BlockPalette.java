package dev.ev.neoforge.adapter;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Global palette mapping Minecraft {@link BlockState} to EV integer palette indices.
 * Palette index 0 is always air, matching the convention used by
 * {@link dev.ev.storage.codec.PaletteCodec} and {@link dev.ev.meshing.stage.OccupancySet}.
 * <p>
 * Thread-safe: palette entries are assigned lazily via CAS on first encounter. Once
 * assigned, an index never changes for the lifetime of the palette instance (which
 * matches the lifetime of one world/dimension, since EVInstance owns this palette and
 * recreates it on world load).
 */
public final class BlockPalette {

    private final ConcurrentHashMap<BlockState, Integer> stateToIndex = new ConcurrentHashMap<>();
    private final AtomicInteger nextIndex = new AtomicInteger(1); // 0 = air, start user values at 1

    public BlockPalette() {
        // Air is always palette index 0
        stateToIndex.put(Blocks.AIR.defaultBlockState(), 0);
        stateToIndex.put(Blocks.CAVE_AIR.defaultBlockState(), 0);
        stateToIndex.put(Blocks.VOID_AIR.defaultBlockState(), 0);
    }

    /**
     * Returns the palette index for a block state, assigning a new one if this state
     * hasn't been seen before.
     *
     * @param state non-null block state
     * @return palette index (0 for air variants, positive for everything else)
     */
    public int getOrAssign(BlockState state) {
        Integer existing = stateToIndex.get(state);
        if (existing != null) {
            return existing;
        }

        if (state.isAir()) {
            stateToIndex.put(state, 0);
            return 0;
        }

        return stateToIndex.computeIfAbsent(state, s -> nextIndex.getAndIncrement());
    }

    /** Current palette size (number of distinct states seen so far). */
    public int size() {
        return stateToIndex.size();
    }
}
