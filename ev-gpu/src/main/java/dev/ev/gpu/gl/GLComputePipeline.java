package dev.ev.gpu.gl;

import dev.ev.api.gpu.ComputePipeline;
import dev.ev.api.gpu.GpuBuffer;
import dev.ev.api.gpu.GpuTexture;
import dev.ev.api.gpu.PipelineLayout;

import org.lwjgl.opengl.GL45;

import java.util.Map;

/**
 * Wraps a compiled GL compute program plus a name-to-binding-point map resolved once at compile
 * time from {@link PipelineLayout}, so {@link #bindBuffer}/{@link #bindTexture} calls at runtime
 * are simple map lookups, not string parsing per call.
 *
 * <p>All methods on this class, like all GL calls in {@code ev-gpu}, must only be called from
 * the render thread (the thread holding the current GL context) — see {@link GLRenderBackend}'s
 * class Javadoc for the full requirement.
 *
 * <p><b>Binding target assumption:</b> {@link #bindBuffer} always binds via {@code
 * glBindBufferBase(GL_SHADER_STORAGE_BUFFER, ...)}. This project is compute/SSBO-heavy by
 * design (see PERFORMANCE_MATH.md), so shader storage buffers are the default assumption for
 * named bindings; there is currently no per-binding metadata distinguishing SSBO from UBO
 * usage. If a future ticket needs uniform buffer bindings through this same API, {@link
 * PipelineLayout} will need to carry that distinction explicitly.
 */
public final class GLComputePipeline implements ComputePipeline {

    private final int programHandle;
    private final Map<String, Integer> bindingsByName;

    private boolean freed;

    GLComputePipeline(int programHandle, PipelineLayout layout) {
        this.programHandle = programHandle;
        this.bindingsByName = layout.bindingsByName();
    }

    @Override
    public void dispatch(int groupsX, int groupsY, int groupsZ) {
        GL45.glUseProgram(programHandle);
        GL45.glDispatchCompute(groupsX, groupsY, groupsZ);
    }

    @Override
    public void dispatchIndirect(GpuBuffer indirectBuffer, long offsetBytes) {
        GL45.glUseProgram(programHandle);
        GL45.glBindBuffer(GL45.GL_DISPATCH_INDIRECT_BUFFER, ((GLBuffer) indirectBuffer).handle());
        GL45.glDispatchComputeIndirect(offsetBytes);
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
