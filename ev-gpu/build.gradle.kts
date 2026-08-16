plugins {
    id("java-library")
}

val lwjglVersion = property("lwjgl_version")

dependencies {
    api(project(":ev-api"))

    // LWJGL приходит транзитивно через NeoForge/Minecraft в финальной сборке мода
    // (ev-neoforge). Для отдельной компиляции этого модуля и для тестов в ev-test
    // нужна явная compileOnly/testImplementation зависимость.
    compileOnly("org.lwjgl:lwjgl:${lwjglVersion}")
    compileOnly("org.lwjgl:lwjgl-opengl:${lwjglVersion}")

    testImplementation("org.lwjgl:lwjgl:${lwjglVersion}")
    testImplementation("org.lwjgl:lwjgl-opengl:${lwjglVersion}")
    testImplementation(platform("org.junit:junit-bom:${property("junit_version")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}
