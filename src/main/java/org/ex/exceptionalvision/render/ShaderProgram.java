package org.ex.exceptionalvision.render;

import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL46C.*;

/**
 * Compiles and links a shader program from GLSL source loaded off the classpath (under
 * {@code assets/exceptional_vision/shaders/lod/...}), with uniform-location caching.
 * <p>
 * Not routed through Minecraft's {@code ResourceManager} - so it isn't resource-pack
 * overridable and won't hot-reload with {@code F3+T}. A reasonable scope cut for a
 * "basic" stage-4 pipeline; revisit with proper resource-manager integration later if
 * shader-pack overrides for LOD terrain turn out to matter.
 */
public final class ShaderProgram implements AutoCloseable {

    private final int programId;
    private final Map<String, Integer> uniformLocations = new HashMap<>();

    private ShaderProgram(int programId) {
        this.programId = programId;
    }

    /** Links a compute-only program from a single {@code .comp} source. */
    public static ShaderProgram compute(String resourcePath) {
        int shader = compile(resourcePath, GL_COMPUTE_SHADER);
        int program = link(shader);
        glDeleteShader(shader);
        return new ShaderProgram(program);
    }

    /** Links a vertex+fragment program. */
    public static ShaderProgram vertexFragment(String vertResourcePath, String fragResourcePath) {
        int vert = compile(vertResourcePath, GL_VERTEX_SHADER);
        int frag = compile(fragResourcePath, GL_FRAGMENT_SHADER);
        int program = link(vert, frag);
        glDeleteShader(vert);
        glDeleteShader(frag);
        return new ShaderProgram(program);
    }

    private static int compile(String resourcePath, int type) {
        String source = readResource(resourcePath);
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            String log = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new IllegalStateException("Failed to compile shader " + resourcePath + ":\n" + log);
        }
        return shader;
    }

    private static int link(int... shaders) {
        int program = glCreateProgram();
        for (int shader : shaders) {
            glAttachShader(program, shader);
        }
        glLinkProgram(program);
        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
            String log = glGetProgramInfoLog(program);
            glDeleteProgram(program);
            throw new IllegalStateException("Failed to link shader program:\n" + log);
        }
        for (int shader : shaders) {
            glDetachShader(program, shader);
        }
        return program;
    }

    private static String readResource(String resourcePath) {
        try (InputStream in = ShaderProgram.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("Shader resource not found on classpath: " + resourcePath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read shader resource: " + resourcePath, e);
        }
    }

    public void use() {
        glUseProgram(programId);
    }

    public static void unbind() {
        glUseProgram(0);
    }

    public int uniformLocation(String name) {
        return uniformLocations.computeIfAbsent(name, n -> glGetUniformLocation(programId, n));
    }

    public void setUniform1i(String name, int value) {
        glUniform1i(uniformLocation(name), value);
    }

    public void setUniform1ui(String name, int value) {
        glUniform1ui(uniformLocation(name), value);
    }

    public void setUniform1f(String name, float value) {
        glUniform1f(uniformLocation(name), value);
    }

    public void setUniform3f(String name, float x, float y, float z) {
        glUniform3f(uniformLocation(name), x, y, z);
    }

    public void setUniformMatrix4f(String name, FloatBuffer matrix16) {
        glUniformMatrix4fv(uniformLocation(name), false, matrix16);
    }

    public void setUniform4fv(String name, FloatBuffer values) {
        glUniform4fv(uniformLocation(name), values);
    }

    @Override
    public void close() {
        glDeleteProgram(programId);
    }
}
