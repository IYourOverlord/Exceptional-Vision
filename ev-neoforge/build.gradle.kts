plugins {
    id("java-library")
    id("net.neoforged.moddev") version "${property("moddevgradle_version")}"
}

base {
    archivesName.set(property("mod_id") as String)
}

neoForge {
    version = property("neo_version") as String

    parchment {
        mappingsVersion = property("parchment_mappings_version") as String
        minecraftVersion = property("parchment_minecraft_version") as String
    }

    runs {
        create("client") {
            client()
            systemProperty("neoforge.enabledGameTestNamespaces", property("mod_id") as String)
        }

        create("server") {
            server()
            programArgument("--nogui")
            systemProperty("neoforge.enabledGameTestNamespaces", property("mod_id") as String)
        }

        create("gameTestServer") {
            type = "gameTestServer"
            systemProperty("neoforge.enabledGameTestNamespaces", property("mod_id") as String)
        }

        create("data") {
            data()
            programArguments.addAll(
                "--mod", property("mod_id") as String,
                "--all",
                "--output", file("src/generated/resources/").absolutePath,
                "--existing", file("src/main/resources/").absolutePath
            )
        }

        configureEach {
            systemProperty("forge.logging.markers", "REGISTRIES")
            logLevel.set(org.slf4j.event.Level.DEBUG)
        }
    }

    mods {
        create(property("mod_id") as String) {
            sourceSet(sourceSets.main.get())
        }
    }
}

sourceSets.main.get().resources.srcDir("src/generated/resources")

dependencies {
    implementation(project(":ev-api"))
    implementation(project(":ev-storage"))
    implementation(project(":ev-meshing"))
    implementation(project(":ev-gpu"))
    implementation(project(":ev-render"))
}

// Разворачивает шаблон neoforge.mods.toml (в src/main/templates) в build/generated —
// повторяет паттерн generateModMetadata из эталонного build.gradle Exceptional Vision.
val generateModMetadata = tasks.register<ProcessResources>("generateModMetadata") {
    val replaceProperties = mapOf(
        "minecraft_version" to property("minecraft_version"),
        "minecraft_version_range" to property("minecraft_version_range"),
        "neo_version" to property("neo_version"),
        "neo_version_range" to property("neo_version_range"),
        "loader_version_range" to property("loader_version_range"),
        "mod_id" to property("mod_id"),
        "mod_name" to property("mod_name"),
        "mod_license" to property("mod_license"),
        "mod_version" to property("mod_version"),
        "mod_authors" to property("mod_authors"),
        "mod_description" to property("mod_description")
    )
    inputs.properties(replaceProperties)
    expand(replaceProperties)
    from("src/main/templates")
    into("build/generated/sources/modMetadata")
}

sourceSets.main.get().resources.srcDir(generateModMetadata)
neoForge.ideSyncTask(generateModMetadata)
