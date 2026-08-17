// Версия плагина net.neoforged.moddev резолвится централизованно в
// settings.gradle.kts -> pluginManagement { plugins { ... } }, поэтому здесь версия не
// указывается. Указывать версию прямо здесь нельзя: блок plugins {} компилируется
// изолированно от остального скрипта и не видит val/property(), объявленные в теле
// этого же файла (ни до, ни после).
plugins {
    id("java-library")
    id("net.neoforged.moddev")
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

    testImplementation(platform("org.junit:junit-bom:${property("junit_version")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

// Разворачивает шаблон neoforge.mods.toml (в src/main/templates) в build/generated —
// повторяет паттерн generateModMetadata из эталонного build.gradle Exceptional Vision.
//
// Свойства читаются через providers.gradleProperty(...).get(), а не через property(...):
// внутри tasks.register<T>("name") { ... } конфигурация может выполняться в момент, когда
// обычный property() ещё не гарантированно резолвит properties проекта (в частности, при
// материализации таска через ideSyncTask), тогда как provider-based API создан именно для
// надёжного ленивого чтения gradle.properties в любой точке конфигурации.
val generateModMetadata = tasks.register<ProcessResources>("generateModMetadata") {
    val replaceProperties = mapOf(
        "minecraft_version" to providers.gradleProperty("minecraft_version").get(),
        "minecraft_version_range" to providers.gradleProperty("minecraft_version_range").get(),
        "neo_version" to providers.gradleProperty("neo_version").get(),
        "neo_version_range" to providers.gradleProperty("neo_version_range").get(),
        "loader_version_range" to providers.gradleProperty("loader_version_range").get(),
        "mod_id" to providers.gradleProperty("mod_id").get(),
        "mod_name" to providers.gradleProperty("mod_name").get(),
        "mod_license" to providers.gradleProperty("mod_license").get(),
        "mod_version" to providers.gradleProperty("mod_version").get(),
        "mod_authors" to providers.gradleProperty("mod_authors").get(),
        "mod_description" to providers.gradleProperty("mod_description").get()
    )
    inputs.properties(replaceProperties)
    expand(replaceProperties)
    from("src/main/templates")
    into("build/generated/sources/modMetadata")
}

sourceSets.main.get().resources.srcDir(generateModMetadata)
neoForge.ideSyncTask(generateModMetadata)
