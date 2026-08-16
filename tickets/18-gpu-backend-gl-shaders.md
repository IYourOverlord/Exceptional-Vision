# Тикет 18 — GLRenderBackend: компиляция шейдеров и автобиндинг

## Контекст
EV — мод дальней прорисовки для NeoForge 1.21.1. Модуль `ev-gpu`. Продолжение
`GLRenderBackend` (создан в тикете 17 с `createBuffer`/`createTexture` — этот тикет
дополняет ТОТ ЖЕ класс методами `compilePipeline`/`compileGraphicsPipeline`, не создаёт
новый класс). Реализует компиляцию GLSL-шейдеров (compute и graphics) и связывание
именованных биндингов (`PipelineLayout.bindingsByName()`) с реальными GL binding points, без
необходимости вызывающему коду знать конкретные числовые индексы.

## Готовый контракт из зависимостей (тикеты 04, 17 — уже реализованы)
```java
package dev.ev.api.gpu;

public record ShaderSource(String sourcePath, String rawSource, Map<String, String> defines) {}
public record PipelineLayout(Map<String, Integer> bindingsByName) {}

public interface ComputePipeline {
    void dispatch(int groupsX, int groupsY, int groupsZ);
    void dispatchIndirect(GpuBuffer indirectBuffer, long offsetBytes);
    void bindBuffer(String bindingName, GpuBuffer buffer);
    void bindTexture(String bindingName, GpuTexture texture);
    void free();
}
public interface GraphicsPipeline {
    void drawIndirect(GpuBuffer indirectBuffer, long offsetBytes, int drawCount);
    void drawIndirectCount(GpuBuffer indirectBuffer, long offsetBytes, GpuBuffer countBuffer, long countOffsetBytes, int maxDrawCount);
    void bindBuffer(String bindingName, GpuBuffer buffer);
    void bindTexture(String bindingName, GpuTexture texture);
    void free();
}

// GLRenderBackend (ticket 17) already implements createBuffer/createTexture; this ticket
// adds compilePipeline/compileGraphicsPipeline to the same class.
```

## Задача

### `GLComputePipeline` / `GLGraphicsPipeline`
```java
package dev.ev.gpu.gl;

/**
 * Wraps a compiled GL program (compute or graphics) plus a name-to-binding-point
 * map resolved once at compile time from PipelineLayout, so bindBuffer/bindTexture
 * calls at runtime are simple array/map lookups, not string parsing per call.
 */
public final class GLComputePipeline implements dev.ev.api.gpu.ComputePipeline { /* ... */ }
public final class GLGraphicsPipeline implements dev.ev.api.gpu.GraphicsPipeline { /* ... */ }
```

### `ShaderCompiler` (внутренний helper)
```java
package dev.ev.gpu.gl;

/**
 * Compiles GLSL source into GL shader objects and links programs, applying
 * ShaderSource.defines() as #define preprocessor injections before the main
 * source body (inserted immediately after the #version line — GLSL requires
 * #version to be the first non-comment line, so defines cannot simply be
 * prepended to the raw string).
 */
final class ShaderCompiler {
    static int compileComputeProgram(dev.ev.api.gpu.ShaderSource source);
    static int compileGraphicsProgram(dev.ev.api.gpu.ShaderSource vertexSrc, dev.ev.api.gpu.ShaderSource fragmentSrc);
}
```

## Требования к реализации

1. **Define injection**: `ShaderSource.rawSource()` начинается с `#version ...\n` (стандартное
   требование GLSL). Вставь `#define KEY VALUE` строки сразу после первой строки (`#version`),
   для каждой записи в `ShaderSource.defines()`. Если карта `defines()` пуста — просто
   компилируй `rawSource()` как есть, без изменений (не создавай лишний string-processing
   overhead без необходимости).

2. **Компиляция шейдера**: используй `glCreateShader(GL_COMPUTE_SHADER)` (или `GL_VERTEX_SHADER`
   / `GL_FRAGMENT_SHADER`), `glShaderSource`, `glCompileShader`, проверь
   `glGetShaderi(shader, GL_COMPILE_STATUS)` — при неудаче извлеки `glGetShaderInfoLog` и
   брось информативное `RuntimeException`, включающее `ShaderSource.sourcePath()` (для
   отладки — чтобы было понятно, какой именно шейдер-файл не скомпилировался) и полный
   лог ошибки от драйвера.

3. **Линковка программы**: `glCreateProgram`, `glAttachShader`, `glLinkProgram`, проверь
   `glGetProgrami(program, GL_LINK_STATUS)`, аналогично бросай информативное исключение при
   неудаче с `glGetProgramInfoLog`. После успешной линковки — `glDetachShader` +
   `glDeleteShader` для промежуточных шейдер-объектов (не нужны после линковки, не течём
   ресурсы).

4. **Разрешение биндингов**: `PipelineLayout.bindingsByName()` — карта "имя ресурса в шейдере"
   → "GL binding point index" (то есть индекс, на который шейдер ссылается через `layout(std430,
   binding = N)` в GLSL-коде — этот индекс задаётся автором шейдера в самом GLSL-исходнике,
   `PipelineLayout` лишь документирует соответствие имени этому уже существующему числу для
   Java-стороны, не переопределяет его на GPU). При создании `GLComputePipeline`/
   `GLGraphicsPipeline`, сохрани эту карту как поле; `bindBuffer(String bindingName, GpuBuffer
   buffer)` смотрит `bindingsByName.get(bindingName)` (бросает `IllegalArgumentException`,
   если имя не найдено — опечатка в имени должна падать сразу, не молча игнорироваться) и
   вызывает `glBindBufferBase(GL_SHADER_STORAGE_BUFFER, bindingPoint,
   ((GLBuffer)buffer).handle())` (или `GL_UNIFORM_BUFFER`, если тип буфера подразумевает
   uniform — для этого проекта, ориентированного на compute/SSBO-heavy pipeline, можно по
   умолчанию считать `GL_SHADER_STORAGE_BUFFER`, задокументируй это допущение).

5. **`dispatch`/`dispatchIndirect`**: `glUseProgram(programHandle)` затем
   `glDispatchCompute(groupsX, groupsY, groupsZ)` / `glDispatchComputeIndirect(offsetBytes)`
   (последний требует, чтобы `indirectBuffer` был забинжен как `GL_DISPATCH_INDIRECT_BUFFER`
   перед вызовом — сделай это явно внутри метода, не полагайся на внешний код).

6. **`free()`**: `glDeleteProgram`.

7. Дополни `GLRenderBackend.compilePipeline`/`compileGraphicsPipeline` вызовом
   `ShaderCompiler`, оборачиванием результата в `GLComputePipeline`/`GLGraphicsPipeline` с
   переданным `PipelineLayout`.

8. Никакого мутируемого статического состояния.

## Юнит-тесты
Как и в тикете 17, полноценное GL-тестирование требует контекста. Обязательно протестируй
без GL-контекста:
1. **Define injection логика** — вынеси её в отдельный чистый метод (например,
   `static String injectDefines(String rawSource, Map<String,String> defines)`), тестируемый
   без GL: проверь, что `#define` строки вставлены СРАЗУ после `#version` строки (не в конец
   файла, не перед `#version`), для нескольких defines — все вставлены, для пустой карты —
   исходный текст не изменён.
2. Тест на некорректный входной shader source (не начинающийся с `#version`) — задокументируй
   и протестируй выбранное поведение (например, `IllegalArgumentException` — исходный код
   без `#version` в первой строке нарушает GLSL-требование, лучше упасть с понятной ошибкой
   на Java-стороне до отправки в драйвер, чем получить малопонятную ошибку компиляции от GL).

## Критерии приёмки
1. Файлы в `ev-gpu/src/main/java/dev/ev/gpu/gl/`: `GLComputePipeline.java`,
   `GLGraphicsPipeline.java`, `ShaderCompiler.java`; `GLRenderBackend.java` дополнен (не
   пересоздан с нуля).
2. Тест на `injectDefines` проходит.
3. Модуль компилируется без ошибок.
4. Ошибки компиляции/линковки GLSL дают информативные исключения с полным логом драйвера и
   путём исходного файла — критично для отладки будущих шейдеров (тикеты 21-23).
