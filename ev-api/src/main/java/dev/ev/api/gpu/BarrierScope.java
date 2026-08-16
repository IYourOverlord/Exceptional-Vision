package dev.ev.api.gpu;

/** Scope of a GPU memory barrier inserted via {@link CommandList#memoryBarrier(BarrierScope)}. */
public enum BarrierScope {
    SHADER_STORAGE, COMMAND, BUFFER_UPDATE, ALL
}
