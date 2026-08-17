import org.gradle.internal.os.OperatingSystem

plugins {
    id("java-library")
}

val lwjglVersion = property("lwjgl_version")

// FakeGpuBuffer (dev.ev.test.gpu) calls MemoryUtil.nmemAlloc/nmemFree at actual runtime
// (not just in ev-test's own tests — see build note below), which needs the LWJGL core
// *native* library on the runtime classpath, not just the Java classes. This does not
// require any GL/display natives (lwjgl-opengl), only the lwjgl core natives artifact.
// Classifier is resolved per build-machine OS/arch, matching the common LWJGL Gradle
// pattern, since this sandbox cannot know the eventual CI/dev machine's platform ahead
// of time.
val lwjglNatives = when {
    OperatingSystem.current().isWindows -> "natives-windows"
    OperatingSystem.current().isMacOsX ->
        if (System.getProperty("os.arch").startsWith("aarch64")) "natives-macos-arm64" else "natives-macos"
    else ->
        if (System.getProperty("os.arch").startsWith("aarch64")) "natives-linux-arm64" else "natives-linux"
}

dependencies {
    // api, не implementation: FakeRenderBackend (dev.ev.test.gpu) implements
    // dev.ev.api.gpu.RenderBackend and exposes dev.ev.api.gpu.* types on its public
    // surface (createBuffer -> GpuBuffer, etc). Consumers of ev-test as a test
    // dependency (ev-gpu, ev-render, ...) need these types visible transitively.
    api(project(":ev-api"))
    implementation(project(":ev-storage"))
    implementation(project(":ev-meshing"))
    implementation(project(":ev-gpu"))
    implementation(project(":ev-render"))

    // FakeRenderBackend lives in the main source set (see ticket 30, acceptance
    // criterion 1: reusable as an ordinary test-dependency library by other modules,
    // not confined to ev-test's own test sources) and uses LWJGL's MemoryUtil for
    // off-heap allocation backing STAGING_UPLOAD buffers' mappedAddress() (see
    // FakeGpuBuffer Javadoc for why). MemoryUtil is pure native-memory bookkeeping,
    // it does not require a GL/GPU context, so this stays usable in headless CI.
    // Must be `implementation` (not compileOnly like ev-gpu's real GL backend),
    // because this module — unlike ev-gpu — actually executes LWJGL calls at
    // runtime in normal (non-test) code, not only in its own tests.
    implementation("org.lwjgl:lwjgl:${lwjglVersion}")
    runtimeOnly("org.lwjgl:lwjgl:${lwjglVersion}:${lwjglNatives}")

    testImplementation("org.lwjgl:lwjgl:${lwjglVersion}")
    testImplementation("org.lwjgl:lwjgl-opengl:${lwjglVersion}")
    testRuntimeOnly("org.lwjgl:lwjgl:${lwjglVersion}:${lwjglNatives}")
    testImplementation(platform("org.junit:junit-bom:${property("junit_version")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}
