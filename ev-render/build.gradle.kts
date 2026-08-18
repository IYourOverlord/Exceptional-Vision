plugins {
    id("java-library")
}

dependencies {
    api(project(":ev-api"))
    implementation(project(":ev-gpu"))
    implementation(project(":ev-storage"))
    implementation(project(":ev-meshing"))
    implementation("it.unimi.dsi:fastutil:${property("fastutil_version")}")

    // slf4j-api: MeshWorkerPool (dev.ev.render.scheduling) logs via org.slf4j.Logger.
    // Unlike ev-neoforge, this module is a plain java-library with no NeoForge/Minecraft
    // classpath to provide slf4j transitively, so it needs its own explicit compile
    // dependency. api (not implementation) because Logger/LoggerFactory types could leak
    // through this module's public API in the future; compileOnly would be wrong since
    // ev-neoforge's runtime (the only real consumer so far) needs an actual slf4j-api jar
    // on its runtime classpath too, and this module doesn't control what ev-neoforge
    // pulls in. Version pinned to match the slf4j-simple version already used in
    // ev-neoforge/build.gradle.kts's testRuntimeOnly, so the project stays on one slf4j
    // line instead of resolving two.
    api("org.slf4j:slf4j-api:2.0.9")

    testImplementation(platform("org.junit:junit-bom:${property("junit_version")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}
