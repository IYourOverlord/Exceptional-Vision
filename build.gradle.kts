plugins {
    id("java")
}

allprojects {
    group = "dev.ev"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "java")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
    }

    tasks.withType<Javadoc> {
        options.encoding = "UTF-8"
    }

    repositories {
        mavenCentral()
        maven("https://maven.neoforged.net/releases")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
