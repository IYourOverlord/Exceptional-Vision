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

            // --- добавлено для P0-profiling-checkpoint ---
            // JFR не требует -XX:+FlightRecorder начиная с JDK 9+, флаг больше не нужен.
            // duration=120s покрывает и холодный старт (Сценарий A), и захват начала
            // steady-state (Сценарий B) в одной записи; при необходимости раздели на два
            // отдельных запуска с более коротким duration под каждый сценарий отдельно
            // (см. jfr-profiling-runclient-snippet.md).
            // settings=profile — встроенный профиль JFR с более детальными событиями
            // (включая lock contention/method profiling), чем default; overhead всё ещё
            // минимален (обычно <2%), но чуть выше чем settings=default — оправдано, так как
            // тикет P0 явно просит cache/queue contention через JFR.
            jvmArgument("-XX:StartFlightRecording=duration=120s,filename=ev-coldstart.jfr,settings=profile")
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

    // ВАЖНО: ModDevGradle не выводит classpath рана автоматически из обычных Gradle
    // project-зависимостей (implementation(project(":..."))) для multi-module setup — он
    // собирает classpath рана только из sourceSet'ов, явно зарегистрированных здесь через
    // sourceSet(...). Без явной регистрации sourceSet каждого подмодуля runClient видит
    // только сам ev-neoforge и падает с NoClassDefFoundError на первом же классе из
    // ev-api/ev-storage/ev-meshing/ev-gpu/ev-render при попытке зайти в мир (эмпирически
    // подтверждено: сборка через `./gradlew build` компилирует все модули без ошибок, но
    // debug-лог runClient показывает "Got mod coordinates" только для ev-neoforge/build/
    // classes/java/main — ни один другой модуль в classpath рана не попадает).
    mods {
        create(property("mod_id") as String) {
            sourceSet(sourceSets.main.get())
            sourceSet(project(":ev-api").extensions.getByType<SourceSetContainer>()["main"])
            sourceSet(project(":ev-storage").extensions.getByType<SourceSetContainer>()["main"])
            sourceSet(project(":ev-meshing").extensions.getByType<SourceSetContainer>()["main"])
            sourceSet(project(":ev-gpu").extensions.getByType<SourceSetContainer>()["main"])
            sourceSet(project(":ev-render").extensions.getByType<SourceSetContainer>()["main"])
        }
    }

    // Без этого блока `test` source set компилируется (ev-neoforge:compileTestJava успешен),
    // но у task'а `test` при рантайме нет Minecraft/NeoForge classpath'а — любой тестовый
    // класс, вызывающий метод класса, который сам (в любом *другом* своём методе, не
    // обязательно вызываемом тестом) ссылается на net.minecraft.*/net.neoforged.neoforge.*
    // (здесь — EVInstance, из-за импортов Minecraft/ClientLevel/RenderLevelStageEvent),
    // падает с NoClassDefFoundError/ClassNotFoundException при загрузке класса — верификация
    // байткода JVM резолвит все типы, на которые ссылается класс, не только вызываемый метод.
    // Официальный паттерн ModDevGradle для JUnit-тестов, ссылающихся на Minecraft-классы —
    // именно `unitTest { enable(); testedMod = mods.<name> }` (см. README ModDevGradle).
    unitTest {
        enable()
        testedMod = mods[property("mod_id") as String]
    }
}

sourceSets.main.get().resources.srcDir("src/generated/resources")

dependencies {
    // ВАЖНО: обычный implementation(project(":...")) на sibling-подпроект компилирует
    // ev-neoforge (класс-типы видны на compile classpath через Gradle project-зависимость),
    // но НЕ добавляет классы этого подпроекта на РАНТАЙМ-classpath реальной запущенной игры.
    // FML грузит на classpath рантайма только (а) сами моды (jar с META-INF/neoforge.mods.
    // toml) и (б) явно embedded jarJar-зависимости — обычная project-зависимость не
    // подпадает ни под один из этих случаев. Без jarJar игра запускается и даже проходит
    // экран загрузки модов (jar с META-INF/neoforge.mods.toml валиден), но крашится в
    // рантайме с NoClassDefFoundError на первом же классе из ev-api/ev-storage/ev-meshing/
    // ev-gpu/ev-render (в этой сессии — dev.ev.api.metrics.MetricsRegistry при загрузке
    // мира, EV.onLevelLoad).
    //
    // Официальный синтаксис для subproject-ов (README ModDevGradle, раздел "Jar-in-Jar" →
    // "Subprojects") — именно `jarJar project(":subproject")`, БЕЗ обёртки в
    // implementation(...): "For subprojects, the group id is the root project name, while
    // the artifact id is the name of the subproject" — FML сам определяет group/artifact
    // id и генерирует нужный module name для embedded подпроектов, никакого ручного
    // FMLModType-манифеста не требуется (та инструкция в README — для отдельного source
    // set "plugin", не для обычных sibling-подпроектов). Обычный implementation(project(
    // ...)) оставлен рядом — jarJar НЕ гарантирует compile-classpath видимость сам по себе,
    // это две независимые задачи (compile-time и runtime-embedding), закрываемые raздельно.
    implementation(project(":ev-api"))
    implementation(project(":ev-storage"))
    implementation(project(":ev-meshing"))
    implementation(project(":ev-gpu"))
    implementation(project(":ev-render"))

    jarJar(project(":ev-api"))
    jarJar(project(":ev-storage"))
    jarJar(project(":ev-meshing"))
    jarJar(project(":ev-gpu"))
    jarJar(project(":ev-render"))

    testImplementation(platform("org.junit:junit-bom:${property("junit_version")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.9")
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