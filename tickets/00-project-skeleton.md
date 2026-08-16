# Тикет 00 — Скелет проекта

Заменяй все слова ev на сокращения ev. Exceptional-Vision Я переименовал проект после создания плана.

## Контекст
EV — мод дальней прорисовки для NeoForge 1.21.1 (Java 21). Многомодульный Gradle-проект.
Это первый тикет: нужно создать структуру, которая компилируется и запускается (пустой мод,
без функциональности), чтобы все последующие тикеты могли добавлять код в готовый скелет.

## Задача
Создать multi-module Gradle-проект со следующей структурой:

```
ev/
├── settings.gradle.kts
├── build.gradle.kts                 # общие настройки для всех подмодулей
├── gradle.properties
├── ev-api/
│   └── build.gradle.kts
├── ev-storage/
│   └── build.gradle.kts
├── ev-meshing/
│   └── build.gradle.kts
├── ev-gpu/
│   └── build.gradle.kts
├── ev-render/
│   └── build.gradle.kts
├── ev-neoforge/
│   ├── build.gradle.kts
│   └── src/main/resources/META-INF/neoforge.mods.toml
└── ev-test/
    └── build.gradle.kts
```

## Требования

1. **Toolchain**: Java 21, Gradle 8.8+, используй официальный NeoForge Gradle-плагин
   (`net.neoforged.moddev` — ModDevGradle, актуальный на 2025-2026 для NeoForge 1.21.x).
   Если название/версия плагина неизвестны с уверенностью — используй **web search**,
   чтобы найти актуальную версию `net.neoforged.moddev` gradle plugin для NeoForge 1.21.1,
   не полагайся на память, экосистема Forge/NeoForge Gradle меняется часто.

2. **`ev-neoforge`** — единственный модуль, зависящий от NeoForge API и производящий
   финальный jar мода. Он зависит от всех остальных модулей проекта.

3. **`ev-api`, `ev-storage`, `ev-meshing`** — чистые Java-модули (`java-library`
   plugin), БЕЗ зависимости на NeoForge и БЕЗ зависимости на LWJGL. Только JDK + `fastutil`
   (для примитивных коллекций, `it.unimi.dsi:fastutil:8.5.13` или актуальная версия).

4. **`ev-gpu`** — Java-модуль с зависимостью на LWJGL (`org.lwjgl:lwjgl`,
   `org.lwjgl:lwjgl-opengl`, версия должна совпадать с той, что использует Minecraft 1.21.1 —
   уточни через web search, обычно LWJGL 3.3.x). LWJGL уже приходит транзитивно через
   NeoForge/Minecraft в финальной сборке мода, но для компиляции модуля `ev-gpu` отдельно
   (и для тестов в `ev-test`) нужна `compileOnly`/`testImplementation` зависимость явно.

5. **`ev-render`** — зависит от `ev-api` и `ev-gpu` (через интерфейс
   `RenderBackend`, не через прямые LWJGL-вызовы).

6. **`ev-test`** — зависит от всех модулей, использует JUnit 5
   (`org.junit.jupiter:junit-jupiter`).

7. Корневой `build.gradle.kts` задаёт общие настройки через `subprojects { }`: Java 21 toolchain,
   `group = "dev.ev"`, `version = "0.1.0-SNAPSHOT"`, UTF-8 кодировка, репозитории
   (Maven Central, NeoForge maven `https://maven.neoforged.net/releases`).

8. **`ev-neoforge/src/main/resources/META-INF/neoforge.mods.toml`** — минимальный валидный
   TOML для NeoForge 1.21.1 мода:
   - `modLoader = "javafml"`
   - `loaderVersion` — совместимый диапазон, уточни через поиск актуальную схему версий для 1.21.1
   - один `[[mods]]` блок: `modId = "ev"`, `version = "${file.jarVersion}"`,
     `displayName = "EV"`
   - секция `[[dependencies.ev]]` с зависимостью на `neoforge` и `minecraft`
     (версии диапазонов для 1.21.1 — уточни через поиск актуальный формат)

9. **`ev-neoforge/src/main/java/dev/ev/neoforge/EV.java`** — минимальный
   entrypoint класс с аннотацией `@Mod("ev")`, конструктор принимает `IEventBus`, пока
   пустой (просто логирует "EV mod loaded" через SLF4J `LoggerFactory.getLogger`).

10. **`gradle.properties`**: `org.gradle.jvmargs=-Xmx3G`, `org.gradle.parallel=true`.

## Важно
- Точные версии NeoForge/ModDevGradle/LWJGL для Minecraft 1.21.1 нужно проверить через
  web search на момент выполнения тикета — экосистема обновляется, не полагайся на
  версии из training data, они могут быть устаревшими или неверными.
- Не добавляй Mixin-зависимости в этом тикете — они появятся позже, только если понадобятся
  (в идеале избегаем миксинов там, где есть публичные NeoForge-события, см. INDEX.md).

## Критерии приёмки
1. `./gradlew build` завершается успешно (0 ошибок компиляции) на пустых модулях.
2. `./gradlew :ev-neoforge:build` производит jar-файл в `ev-neoforge/build/libs/`.
3. Структура директорий строго соответствует указанной выше — это важно, т.к. следующие
   тикеты добавляют файлы по этим путям и полагаются на существование `build.gradle.kts`
   в каждом модуле.
4. `ev-api`, `ev-storage`, `ev-meshing` НЕ имеют в classpath ни NeoForge,
   ни LWJGL (проверить: `./gradlew :ev-api:dependencies` не должен показывать эти
   зависимости даже транзитивно).