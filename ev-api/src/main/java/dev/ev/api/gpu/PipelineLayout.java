package dev.ev.api.gpu;

import java.util.Map;

/** Declares the binding points a pipeline expects (buffers, textures, uniforms), by name. */
public record PipelineLayout(Map<String, Integer> bindingsByName) {}
