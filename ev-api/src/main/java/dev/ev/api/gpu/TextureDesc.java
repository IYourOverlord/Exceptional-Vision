package dev.ev.api.gpu;

/** Describes the shape and format of a GPU texture to be created via {@link RenderBackend#createTexture}. */
public record TextureDesc(int width, int height, int depth, TextureFormat format, int mipLevels) {
    public static TextureDesc texture2D(int width, int height, TextureFormat format, int mipLevels) {
        return new TextureDesc(width, height, 1, format, mipLevels);
    }
}
