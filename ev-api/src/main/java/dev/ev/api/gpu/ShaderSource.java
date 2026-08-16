package dev.ev.api.gpu;

import java.util.Map;

/** Raw shader source text plus preprocessor defines to inject before compilation. */
public record ShaderSource(String sourcePath, String rawSource, Map<String, String> defines) {}
