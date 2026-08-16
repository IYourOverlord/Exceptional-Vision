package dev.ev.api.gpu;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Sanity tests for the {@code java.util.Map}-backed records in {@code dev.ev.api.gpu}:
 * confirms accessors expose the data given at construction and that the maps returned
 * by {@link java.util.Map#of} (the expected caller pattern) are immutable, so backend
 * implementations can safely treat them as read-only without defensive copying.
 */
class ShaderSourceAndPipelineLayoutTest {

    @Test
    void shaderSourceExposesConstructorValues() {
        Map<String, String> defines = Map.of("MAX_LOD", "6");
        ShaderSource source = new ShaderSource("shaders/traversal.comp", "void main() {}", defines);

        assertEquals("shaders/traversal.comp", source.sourcePath());
        assertEquals("void main() {}", source.rawSource());
        assertEquals(defines, source.defines());
    }

    @Test
    void shaderSourceDefinesMapIsImmutableWhenConstructedFromMapOf() {
        ShaderSource source = new ShaderSource("x", "y", Map.of("A", "1"));
        assertThrows(UnsupportedOperationException.class, () -> source.defines().put("B", "2"));
    }

    @Test
    void pipelineLayoutExposesBindings() {
        Map<String, Integer> bindings = Map.of("nodeBuffer", 0, "sceneUniforms", 1);
        PipelineLayout layout = new PipelineLayout(bindings);

        assertEquals(bindings, layout.bindingsByName());
    }

    @Test
    void pipelineLayoutBindingsMapIsImmutableWhenConstructedFromMapOf() {
        PipelineLayout layout = new PipelineLayout(Map.of("a", 0));
        assertThrows(UnsupportedOperationException.class, () -> layout.bindingsByName().put("b", 1));
    }
}
