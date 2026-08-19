package dev.ev.gpu.gl;

import dev.ev.api.gpu.GpuBuffer;
import dev.ev.api.gpu.GpuTexture;
import dev.ev.api.gpu.GraphicsPipeline;
import dev.ev.api.gpu.PipelineLayout;

import org.lwjgl.opengl.GL45;
import org.lwjgl.opengl.ARBIndirectParameters;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;
import java.util.Map;

/**
 * Wraps a compiled GL graphics (vertex+fragment) program plus a name-to-binding-point map
 * resolved once at compile time from {@link PipelineLayout}, so {@link #bindBuffer}/{@link
 * #bindTexture} calls at runtime are simple map lookups, not string parsing per call.
 *
 * <p>All methods on this class, like all GL calls in {@code ev-gpu}, must only be called from
 * the render thread (the thread holding the current GL context) — see {@link GLRenderBackend}'s
 * class Javadoc for the full requirement.
 *
 * <p><b>Binding target assumption:</b> see {@link GLComputePipeline}'s Javadoc — the same
 * SSBO-by-default assumption applies here.
 */
public final class GLGraphicsPipeline implements GraphicsPipeline {

    private final int programHandle;
    private final Map<String, Integer> bindingsByName;

    private boolean freed;

    GLGraphicsPipeline(int programHandle, PipelineLayout layout) {
        this.programHandle = programHandle;
        this.bindingsByName = layout.bindingsByName();
    }

    @Override
    public void drawIndirect(GpuBuffer indirectBuffer, long offsetBytes, int drawCount) {
        GL45.glUseProgram(programHandle);
        GL45.glBindBuffer(GL45.GL_DRAW_INDIRECT_BUFFER, ((GLBuffer) indirectBuffer).handle());
        GL45.glMultiDrawArraysIndirect(GL45.GL_TRIANGLES, offsetBytes, drawCount, 0);
    }

    @Override
    public void drawIndirectCount(GpuBuffer indirectBuffer, long offsetBytes, GpuBuffer countBuffer,
                                  long countOffsetBytes, int maxDrawCount) {
        GL45.glUseProgram(programHandle);
        GL45.glBindBuffer(GL45.GL_DRAW_INDIRECT_BUFFER, ((GLBuffer) indirectBuffer).handle());
        GL45.glBindBuffer(ARBIndirectParameters.GL_PARAMETER_BUFFER_ARB, ((GLBuffer) countBuffer).handle());
        ARBIndirectParameters.glMultiDrawArraysIndirectCountARB(GL45.GL_TRIANGLES, offsetBytes, countOffsetBytes,
                maxDrawCount, 0);
    }

    /**
     * MVP-only helper (not part of the {@link GraphicsPipeline} contract): binds this
     * program and uploads a 4x4 column-major matrix to uniform location 0. The
     * {@code GraphicsPipeline}/{@code PipelineLayout} contract (ticket 04) has no notion
     * of plain uniform values, only named buffer/texture bindings — this is a stopgap
     * for {@code far-lod-pass}'s view-projection matrix until a proper uniform-binding
     * mechanism is designed. Callers must cast to this concrete class to use it, which
     * is intentional: it signals this is a temporary, backend-specific escape hatch.
     *
     * @param columnMajor16 16 floats, column-major 4x4 matrix
     */
    public void useProgramAndSetViewProj(float[] columnMajor16) {
        GL45.glUseProgram(programHandle);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buf = stack.mallocFloat(16);
            buf.put(columnMajor16).flip();
            GL45.glUniformMatrix4fv(0, false, buf);
        }
    }

    @Override
    public void bindBuffer(String bindingName, GpuBuffer buffer) {
        int bindingPoint = resolveBinding(bindingName);
        GL45.glBindBufferBase(GL45.GL_SHADER_STORAGE_BUFFER, bindingPoint, ((GLBuffer) buffer).handle());
    }

    @Override
    public void bindTexture(String bindingName, GpuTexture texture) {
        int bindingPoint = resolveBinding(bindingName);
        GL45.glBindTextureUnit(bindingPoint, ((GLTexture) texture).handle());
    }

    @Override
    public void free() {
        if (freed) {
            return;
        }
        GL45.glDeleteProgram(programHandle);
        freed = true;
    }

    private int resolveBinding(String bindingName) {
        Integer bindingPoint = bindingsByName.get(bindingName);
        if (bindingPoint == null) {
            throw new IllegalArgumentException(
                    "No binding named '" + bindingName + "' in this pipeline's layout; known bindings: "
                            + bindingsByName.keySet());
        }
        return bindingPoint;
    }
}