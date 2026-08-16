plugins {
    id("java-library")
}

dependencies {
    implementation("it.unimi.dsi:fastutil:${property("fastutil_version")}")

    testImplementation(platform("org.junit:junit-bom:${property("junit_version")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}
