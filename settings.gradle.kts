pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.neoforged.net/releases")
    }

    // Версия net.neoforged.moddev резолвится здесь централизованно (не в
    // ev-neoforge/build.gradle.kts) — settings-контекст поддерживает property() полностью,
    // в отличие от блока plugins {} внутри build.gradle.kts подмодуля, где property() и
    // локальные val недоступны из-за изолированной компиляции этого блока.
    plugins {
        id("net.neoforged.moddev") version providers.gradleProperty("moddevgradle_version").get()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "ev"

include("ev-api")
include("ev-storage")
include("ev-meshing")
include("ev-gpu")
include("ev-render")
include("ev-neoforge")
include("ev-test")
