package dev.ev.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entrypoint of the EV mod (far-render / LOD rendering for NeoForge).
 *
 * <p>This is the MVP skeleton entrypoint (ticket 00-project-skeleton): it registers
 * the mod with NeoForge and logs that it loaded successfully. No functionality yet —
 * subsequent tickets add event listeners, worker pool initialization, config, and
 * commands on top of this class (see ticket 27-neoforge-mod-entrypoint).</p>
 */
@Mod("ev")
public final class EV {

    private static final Logger LOGGER = LoggerFactory.getLogger(EV.class);

    public EV(IEventBus modEventBus) {
        LOGGER.info("EV mod loaded");
    }
}
