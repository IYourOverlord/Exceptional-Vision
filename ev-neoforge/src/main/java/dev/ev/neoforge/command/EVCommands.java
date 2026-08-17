package dev.ev.neoforge.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.ev.api.metrics.MetricsRegistry;
import dev.ev.api.metrics.MetricsSnapshot;
import dev.ev.neoforge.EVInstance;
import dev.ev.neoforge.config.EVConfigLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * Registers /ev debug commands using NeoForge's Brigadier-based client command API.
 * <p>
 * All commands are client-only (registered via {@code RegisterClientCommandsEvent}).
 * No mutable static state — transient state (debug overlay toggle) is stored on
 * {@link EVInstance}, not in static fields here.
 * <p>
 * Subcommands:
 * <ul>
 *   <li>{@code /ev debug} — prints a one-shot MetricsSnapshot summary to chat</li>
 *   <li>{@code /ev debug watch} — toggles persistent debug overlay (state on EVInstance)</li>
 *   <li>{@code /ev profile <passName>} — shows GPU/CPU pass timing for a named pass</li>
 *   <li>{@code /ev reload-config} — re-reads EVConfig from disk, reports hot-reloadable fields</li>
 * </ul>
 */
public final class EVCommands {

    private static final Logger LOGGER = LoggerFactory.getLogger(EVCommands.class);

    private EVCommands() {
    }

    /**
     * Registers all /ev subcommands on the given dispatcher.
     * Called from {@code RegisterClientCommandsEvent} handler in {@link dev.ev.neoforge.EV}.
     *
     * @param dispatcher    the Brigadier command dispatcher
     * @param instanceAccess supplier providing the current EVInstance, or null if no world loaded
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                                Supplier<EVInstance> instanceAccess) {
        dispatcher.register(
                net.minecraft.commands.Commands.literal("ev")
                        .then(net.minecraft.commands.Commands.literal("debug")
                                .executes(ctx -> executeDebug(ctx.getSource(), instanceAccess))
                                .then(net.minecraft.commands.Commands.literal("watch")
                                        .executes(ctx -> executeDebugWatch(ctx.getSource(), instanceAccess))
                                )
                        )
                        .then(net.minecraft.commands.Commands.literal("profile")
                                .then(net.minecraft.commands.Commands.argument("passName", StringArgumentType.word())
                                        .executes(ctx -> executeProfile(ctx.getSource(), instanceAccess,
                                                StringArgumentType.getString(ctx, "passName")))
                                )
                        )
                        .then(net.minecraft.commands.Commands.literal("reload-config")
                                .executes(ctx -> executeReloadConfig(ctx.getSource()))
                        )
        );
    }

    /**
     * /ev debug — prints a one-shot MetricsSnapshot summary to the issuing player's chat.
     */
    private static int executeDebug(CommandSourceStack source, Supplier<EVInstance> instanceAccess) {
        EVInstance inst = instanceAccess.get();
        if (inst == null) {
            source.sendSuccess(() -> Component.literal("§c[EV] No active world — EVInstance not initialized."), false);
            return 0;
        }

        MetricsRegistry registry = inst.metrics();
        MetricsSnapshot snapshot = registry.snapshot();
        String formatted = MetricsSnapshotFormatter.format(snapshot);

        // Send each line separately to chat for readability
        for (String line : formatted.split("\n")) {
            final String lineRef = line;
            source.sendSuccess(() -> Component.literal("§7" + lineRef), false);
        }
        return 1;
    }

    /**
     * /ev debug watch — toggles the persistent debug overlay flag on EVInstance.
     * The actual overlay rendering is a TODO (requires dedicated HUD render event infrastructure);
     * this command toggles the state flag and provides feedback.
     */
    private static int executeDebugWatch(CommandSourceStack source, Supplier<EVInstance> instanceAccess) {
        EVInstance inst = instanceAccess.get();
        if (inst == null) {
            source.sendSuccess(() -> Component.literal("§c[EV] No active world — EVInstance not initialized."), false);
            return 0;
        }

        boolean newState = !inst.isDebugOverlayEnabled();
        inst.setDebugOverlayEnabled(newState);

        String stateStr = newState ? "§aENABLED" : "§cDISABLED";
        source.sendSuccess(() -> Component.literal("§7[EV] Debug overlay: " + stateStr
                + "§7. (Visual overlay rendering is TODO — data is being collected.)"), false);
        return 1;
    }

    /**
     * /ev profile <passName> — shows GPU/CPU pass timing for a named frame-graph pass.
     * <p>
     * MVP NOTE: as of Wave 1, there is no GPU-side multi-pass pipeline. The CPU-side
     * traversal (ticket 21-mvp) reports its timing under "cpu-traversal" via
     * MetricsRegistry.recordGpuPassDuration. Genuine GPU timer queries become relevant
     * only once GPU-side passes exist (post 21-opt/22-opt).
     */
    private static int executeProfile(CommandSourceStack source, Supplier<EVInstance> instanceAccess,
                                      String passName) {
        EVInstance inst = instanceAccess.get();
        if (inst == null) {
            source.sendSuccess(() -> Component.literal("§c[EV] No active world — EVInstance not initialized."), false);
            return 0;
        }

        MetricsSnapshot snapshot = inst.metrics().snapshot();
        Long nanos = snapshot.gpuPassDurationsNanos().get(passName);

        if (nanos == null) {
            source.sendSuccess(() -> Component.literal(
                    "§e[EV] No timing data for pass '" + passName + "'. "
                            + "Available passes: " + snapshot.gpuPassDurationsNanos().keySet()
                            + ". Note: detailed GPU timer queries are not yet available in MVP — "
                            + "only CPU-side pass timings (e.g. 'cpu-traversal') are tracked."), false);
            return 0;
        }

        double ms = nanos / 1_000_000.0;
        source.sendSuccess(() -> Component.literal(
                String.format("§7[EV] Pass '%s': §f%.2fms §7(%.0f µs)", passName, ms, nanos / 1000.0)), false);
        return 1;
    }

    /**
     * /ev reload-config — re-reads EVConfig from the NeoForge config spec and applies changes.
     * <p>
     * <b>Hot-reloadable settings</b> (take effect immediately without restart):
     * <ul>
     *   <li>{@code screenSpaceErrorThresholdPx} — adjusts LOD selection threshold</li>
     *   <li>{@code maxRenderDistanceBlocks} — adjusts far render distance</li>
     *   <li>{@code enableDebugOverlay} — toggles debug overlay visibility</li>
     *   <li>{@code vramBudgetBytes} — adjusts VRAM budget cap (actual reallocation deferred)</li>
     * </ul>
     * <b>NOT hot-reloadable</b> (require world rejoin / mod restart):
     * <ul>
     *   <li>{@code workerThreadCount} — thread pool size cannot change at runtime</li>
     * </ul>
     * Coherence settings ({@code coherenceMaxPositionalDeltaBlocks}, {@code coherenceMaxAngularDeltaRadians})
     * are reserved for Wave 2 and currently unused.
     */
    private static int executeReloadConfig(CommandSourceStack source) {
        try {
            EVConfigLoader.reloadFromSpec();
            var config = EVConfigLoader.current();
            source.sendSuccess(() -> Component.literal(
                    "§a[EV] Config reloaded. Active values:\n"
                            + "§7  maxRenderDistanceBlocks: §f" + config.maxRenderDistanceBlocks() + "\n"
                            + "§7  screenSpaceErrorThresholdPx: §f" + config.screenSpaceErrorThresholdPx() + "\n"
                            + "§7  workerThreadCount: §f" + config.workerThreadCount() + " §e(requires restart to change)\n"
                            + "§7  enableDebugOverlay: §f" + config.enableDebugOverlay() + "\n"
                            + "§7  vramBudgetBytes: §f" + config.vramBudgetBytes()
            ), false);
            LOGGER.info("EV config reloaded via /ev reload-config command");
            return 1;
        } catch (Exception e) {
            LOGGER.error("Failed to reload EV config", e);
            source.sendSuccess(() -> Component.literal("§c[EV] Config reload failed: " + e.getMessage()), false);
            return 0;
        }
    }
}
