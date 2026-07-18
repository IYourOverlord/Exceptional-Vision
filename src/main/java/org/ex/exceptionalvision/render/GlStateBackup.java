package org.ex.exceptionalvision.render;

import static org.lwjgl.opengl.GL46C.*;

/**
 * Saves and restores the handful of GL state values {@link LodGpuPipeline} touches.
 * Minecraft's own renderer (Blaze3D / vanilla immediate-ish state management) assumes
 * it fully owns GL state between its own draw calls; injecting a raw-OpenGL draw into
 * that pipeline (see {@code LodRenderManager}) means we're responsible for leaving
 * everything exactly as we found it, or subsequent vanilla passes can render garbage
 * (wrong shader program, wrong VAO, depth/blend state left in the wrong mode, etc).
 * <p>
 * Deliberately narrow in scope - only the state this mod's draw call actually changes.
 * If {@code LodGpuPipeline} starts touching more state (e.g. a different blend mode),
 * extend this alongside it rather than reaching for a "save everything" approach.
 */
final class GlStateBackup {

    private int program;
    private int vertexArray;
    private boolean depthTestEnabled;
    private int depthFunc;
    private boolean depthMask;
    private boolean cullFaceEnabled;
    private boolean blendEnabled;

    void capture() {
        program = glGetInteger(GL_CURRENT_PROGRAM);
        vertexArray = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        depthTestEnabled = glIsEnabled(GL_DEPTH_TEST);
        depthFunc = glGetInteger(GL_DEPTH_FUNC);
        depthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
        cullFaceEnabled = glIsEnabled(GL_CULL_FACE);
        blendEnabled = glIsEnabled(GL_BLEND);
    }

    void restore() {
        glUseProgram(program);
        glBindVertexArray(vertexArray);
        setEnabled(GL_DEPTH_TEST, depthTestEnabled);
        glDepthFunc(depthFunc);
        glDepthMask(depthMask);
        setEnabled(GL_CULL_FACE, cullFaceEnabled);
        setEnabled(GL_BLEND, blendEnabled);
    }

    private static void setEnabled(int capability, boolean enabled) {
        if (enabled) {
            glEnable(capability);
        } else {
            glDisable(capability);
        }
    }
}
