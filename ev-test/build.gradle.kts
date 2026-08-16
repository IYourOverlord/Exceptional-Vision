plugins {
    id("java-library")
}

val lwjglVersion = property("lwjgl_version")

dependencies {
    implementation(project(":ev-api"))
    implementation(project(":ev-storage"))
    implementation(project(":ev-meshing"))
    implementation(project(":ev-gpu"))
    implementation(project(":ev-render"))

    testImplementation("org.lwjgl:lwjgl:${lwjglVersion}")
    testImplementation("org.lwjgl:lwjgl-opengl:${lwjglVersion}")
    testImplementation(platform("org.junit:junit-bom:${property("junit_version")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}
