package dev.ev.gpu.gl;

import dev.ev.api.gpu.TextureFormat;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GLTextureFormatsTest {

    /**
     * Every {@link TextureFormat} enum value must have a mapping — catches future additions to
     * the enum that forget to update {@link GLTextureFormats#toGlInternalFormat}, per
     * requirement 2 of the ticket.
     */
    @Test
    void everyTextureFormatValue_hasAMapping_noExceptionThrown() {
        for (TextureFormat format : TextureFormat.values()) {
            assertDoesNotThrow(() -> GLTextureFormats.toGlInternalFormat(format),
                "TextureFormat." + format + " is missing a GL internal format mapping");
        }
    }

    /**
     * Distinct {@link TextureFormat} values must map to distinct GL constants — a collision
     * here would silently alias two logically different formats to the same GPU storage
     * format.
     */
    @Test
    void differentTextureFormats_mapToDifferentGlConstants() {
        TextureFormat[] values = TextureFormat.values();
        for (int i = 0; i < values.length; i++) {
            for (int j = i + 1; j < values.length; j++) {
                int a = GLTextureFormats.toGlInternalFormat(values[i]);
                int b = GLTextureFormats.toGlInternalFormat(values[j]);
                org.junit.jupiter.api.Assertions.assertNotEquals(a, b,
                    values[i] + " and " + values[j] + " must not map to the same GL constant");
            }
        }
    }

    @Test
    void r32f_mapsToGlR32f() {
        assertEquals(org.lwjgl.opengl.GL45.GL_R32F, GLTextureFormats.toGlInternalFormat(TextureFormat.R32F));
    }

    @Test
    void rgba8_mapsToGlRgba8() {
        assertEquals(org.lwjgl.opengl.GL45.GL_RGBA8, GLTextureFormats.toGlInternalFormat(TextureFormat.RGBA8));
    }

    @Test
    void rgba16f_mapsToGlRgba16f() {
        assertEquals(org.lwjgl.opengl.GL45.GL_RGBA16F, GLTextureFormats.toGlInternalFormat(TextureFormat.RGBA16F));
    }

    @Test
    void depth32f_mapsToGlDepthComponent32f() {
        assertEquals(org.lwjgl.opengl.GL45.GL_DEPTH_COMPONENT32F, GLTextureFormats.toGlInternalFormat(TextureFormat.DEPTH32F));
    }

    @Test
    void r32ui_mapsToGlR32ui() {
        assertEquals(org.lwjgl.opengl.GL45.GL_R32UI, GLTextureFormats.toGlInternalFormat(TextureFormat.R32UI));
    }
}
