package dev.ev.gpu.gl;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests {@link ShaderCompiler#injectDefines}, the only piece of ticket 18's logic testable
 * without a live GL context (per the ticket's "Юнит-тесты" section).
 */
class ShaderCompilerTest {

    @Test
    void emptyDefines_returnsSourceUnchanged() {
        String source = "#version 450 core\nvoid main() {}\n";
        String result = ShaderCompiler.injectDefines(source, Map.of());
        assertEquals(source, result);
    }

    @Test
    void singleDefine_insertedImmediatelyAfterVersionLine() {
        String source = "#version 450 core\nvoid main() {}\n";
        Map<String, String> defines = Map.of("MAX_LOD", "8");

        String result = ShaderCompiler.injectDefines(source, defines);

        String expected = "#version 450 core\n#define MAX_LOD 8\nvoid main() {}\n";
        assertEquals(expected, result);
    }

    @Test
    void multipleDefines_allInsertedInOrder_rightAfterVersionLine() {
        String source = "#version 450 core\nvoid main() {}\n";
        Map<String, String> defines = new LinkedHashMap<>();
        defines.put("MAX_LOD", "8");
        defines.put("WORKGROUP_SIZE", "64");

        String result = ShaderCompiler.injectDefines(source, defines);

        String expected = "#version 450 core\n"
            + "#define MAX_LOD 8\n"
            + "#define WORKGROUP_SIZE 64\n"
            + "void main() {}\n";
        assertEquals(expected, result);
    }

    @Test
    void defines_notInsertedAtEndOfFile_norBeforeVersionLine() {
        String source = "#version 450 core\nlayout(local_size_x = 1) in;\nvoid main() {}\n";
        String result = ShaderCompiler.injectDefines(source, Map.of("FOO", "1"));

        assertEquals(0, result.indexOf("#version"), "version line must stay first");

        int versionEnd = result.indexOf('\n');
        int defineIndex = result.indexOf("#define FOO 1");
        assertEquals(versionEnd + 1, defineIndex,
            "#define must be inserted immediately after the #version line, not appended at the end");
    }

    @Test
    void missingVersionLine_throwsIllegalArgumentException() {
        String source = "void main() {}\n";
        assertThrows(IllegalArgumentException.class,
            () -> ShaderCompiler.injectDefines(source, Map.of()));
    }

    @Test
    void missingVersionLine_withNonEmptyDefines_alsoThrows() {
        String source = "layout(local_size_x = 1) in;\nvoid main() {}\n";
        assertThrows(IllegalArgumentException.class,
            () -> ShaderCompiler.injectDefines(source, Map.of("FOO", "1")));
    }
}
