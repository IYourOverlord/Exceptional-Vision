plugins {
    id("java-library")
}

dependencies {
    api(project(":ev-api"))
    implementation(project(":ev-gpu"))
    implementation(project(":ev-storage"))
    implementation(project(":ev-meshing"))
    implementation("it.unimi.dsi:fastutil:${property("fastutil_version")}")

    testImplementation(platform("org.junit:junit-bom:${property("junit_version")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}
