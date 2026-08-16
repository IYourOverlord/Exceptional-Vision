package dev.ev.api.gpu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Unit test for {@link TextureDesc#texture2D} depth-defaulting factory. */
class TextureDescTest {

    @Test
    void texture2DDefaultsDepthToOne() {
        TextureDesc desc = TextureDesc.texture2D(512, 256, TextureFormat.RGBA8, 1);

        assertEquals(512, desc.width());
        assertEquals(256, desc.height());
        assertEquals(1, desc.depth());
        assertEquals(TextureFormat.RGBA8, desc.format());
        assertEquals(1, desc.mipLevels());
    }
}
