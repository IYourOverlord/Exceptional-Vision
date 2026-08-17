package dev.ev.gpu.gl;

import dev.ev.api.gpu.ShaderSource;

import org.lwjgl.opengl.GL45;

import java.util.Map;

/**
 * Compiles GLSL source into GL shader objects and links programs, applying {@link
 * ShaderSource#defines()} as {@code #define} preprocessor injections before the main source
 * body. Defines are inserted immediately after the {@code #version} line — GLSL requires
 * {@code #version} to be the first non-comment line of the source, so defines cannot simply be
 * prepended to the raw string.
 *
 * <p>All methods here, like all GL calls in {@code ev-gpu}, must only be called from the render
 * thread (the thread holding the current GL context) — see {@link GLRenderBackend}'s class
 * Javadoc for the full requirement.
 */
final class ShaderCompiler {

    private ShaderCompiler() {
    }

    /**
     * Compiles a compute shader and links it into its own program.
     *
     * @return the raw GL program object name (already linked, intermediate shader object
     *         detached and deleted)
     * @throws RuntimeException if compilation or linking fails; the message includes {@link
     *         ShaderSource#sourcePath()} and the full driver info log
     */
    static int compileComputeProgram(ShaderSource source) {
        int shader = compileShader(GL45.GL_COMPUTE_SHADER, source);
        int program = GL45.glCreateProgram();
        GL45.glAttachShader(program, shader);
        linkProgram(program, source.sourcePath());
        GL45.glDetachShader(program, shader);
        GL45.glDeleteShader(shader);
        return program;
    }

    /**
     * Compiles a vertex + fragment shader pair and links them into a single graphics program.
     *
     * @return the raw GL program object name (already linked, intermediate shader objects
     *         detached and deleted)
     * @throws RuntimeException if compilation or linking fails; the message includes the
     *         relevant {@link ShaderSource#sourcePath()} and the full driver info log
     */
    static int compileGraphicsProgram(ShaderSource vertexSrc, ShaderSource fragmentSrc) {
        int vertexShader = compileShader(GL45.GL_VERTEX_SHADER, vertexSrc);
        int fragmentShader = compileShader(GL45.GL_FRAGMENT_SHADER, fragmentSrc);

        int program = GL45.glCreateProgram();
        GL45.glAttachShader(program, vertexShader);
        GL45.glAttachShader(program, fragmentShader);
        linkProgram(program, vertexSrc.sourcePath() + " + " + fragmentSrc.sourcePath());

        GL45.glDetachShader(program, vertexShader);
        GL45.glDetachShader(program, fragmentShader);
        GL45.glDeleteShader(vertexShader);
        GL45.glDeleteShader(fragmentShader);
        return program;
    }

    private static int compileShader(int glShaderType, ShaderSource source) {
        String finalSource = injectDefines(source.rawSource(), source.defines());

        int shader = GL45.glCreateShader(glShaderType);
        GL45.glShaderSource(shader, finalSource);
        GL45.glCompileShader(shader);

        int status = GL45.glGetShaderi(shader, GL45.GL_COMPILE_STATUS);
        if (status == GL45.GL_FALSE) {
            String log = GL45.glGetShaderInfoLog(shader);
            GL45.glDeleteShader(shader);
            throw new RuntimeException(
                "GLSL shader compilation failed for '" + source.sourcePath() + "':\n" + log);
        }
        return shader;
    }

    private static void linkProgram(int program, String sourcePathForErrors) {
        GL45.glLinkProgram(program);

        int status = GL45.glGetProgrami(program, GL45.GL_LINK_STATUS);
        if (status == GL45.GL_FALSE) {
            String log = GL45.glGetProgramInfoLog(program);
            GL45.glDeleteProgram(program);
            throw new RuntimeException(
                "GLSL program linking failed for '" + sourcePathForErrors + "':\n" + log);
        }
    }

    /**
     * Inserts {@code #define KEY VALUE} lines immediately after the first line of {@code
     * rawSource} (expected to be the {@code #version} directive), one per entry in {@code
     * defines}. If {@code defines} is empty, returns {@code rawSource} unchanged (no
     * string-processing overhead when there is nothing to inject).
     *
     * @throws IllegalArgumentException if {@code rawSource} does not start with a {@code
     *         #version} line — GLSL requires {@code #version} to be the first non-comment line,
     *         so failing fast here on the Java side is more diagnosable than letting the driver
     *         reject it with a less specific compiler error
     */
    static String injectDefines(String rawSource, Map<String, String> defines) {
        if (!rawSource.startsWith("#version")) {
            throw new IllegalArgumentException(
                "Shader source must start with a '#version' line, as required by GLSL; got: "
                    + firstLine(rawSource));
        }
        if (defines.isEmpty()) {
            return rawSource;
        }

        int newlineIndex = rawSource.indexOf('\n');
        String versionLine = newlineIndex >= 0 ? rawSource.substring(0, newlineIndex) : rawSource;
        String rest = newlineIndex >= 0 ? rawSource.substring(newlineIndex + 1) : "";

        StringBuilder sb = new StringBuilder(rawSource.length() + 32 * defines.size());
        sb.append(versionLine).append('\n');
        for (Map.Entry<String, String> entry : defines.entrySet()) {
            sb.append("#define ").append(entry.getKey()).append(' ').append(entry.getValue()).append('\n');
        }
        sb.append(rest);
        return sb.toString();
    }

    private static String firstLine(String s) {
        int newlineIndex = s.indexOf('\n');
        return newlineIndex >= 0 ? s.substring(0, newlineIndex) : s;
    }
}
