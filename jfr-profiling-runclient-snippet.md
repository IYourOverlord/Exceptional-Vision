# Как включить JFR для `runClient` (ModDevGradle) — для тикета P0

Проект использует плагин `net.neoforged.moddev` (ModDevGradle), не старый NeoGradle. Синтаксис
для JVM-аргументов конкретного run'а — `jvmArgument(...)` / `jvmArguments = [...]` внутри блока
`runs { create("client") { ... } }`. Источник: официальный README ModDevGradle
(https://github.com/neoforged/ModDevGradle) и docs.neoforged.net/toolchain/docs/plugins/mdg/.

## Вариант A — временно, через `gradle.properties` / командную строку

Быстрее всего для разового профилирующего прогона, не трогая `build.gradle.kts`:

```bash
./gradlew runClient -Dneoforge.runs.client.jvmArgs="-XX:+FlightRecorder -XX:StartFlightRecording=duration=120s,filename=ev-coldstart.jfr"
```

Если такая system-property не подхватится конкретной версией плагина (это не гарантированный
публичный API, только сам синтаксис `jvmArgument` в скрипте гарантирован README) — используй
Вариант B, он надёжнее.

## Вариант B — постоянно, через build.gradle.kts (рекомендуется)

Правь `ev-neoforge/build.gradle.kts`, блок `runs { create("client") { ... } }`:

```kotlin
runs {
    create("client") {
        client()
        systemProperty("neoforge.enabledGameTestNamespaces", property("mod_id") as String)

        // --- добавлено для P0-profiling-checkpoint ---
        // JFR не требует -XX:+FlightRecorder начиная с JDK 9+ (флаг больше не нужен,
        // но безвреден на JDK 21 — оставлен для явности с более старой документацией).
        // duration=120s покрывает и холодный старт (Сценарий A), и захват начала
        // steady-state (Сценарий B) в одной записи; при необходимости раздели на два
        // отдельных запуска с более коротким duration под каждый сценарий отдельно.
        jvmArgument("-XX:StartFlightRecording=duration=120s,filename=ev-coldstart.jfr,settings=profile")
        // settings=profile — встроенный профиль JFR с более детальными событиями
        // (включая lock contention/method profiling), чем default; overhead всё ещё
        // минимален (обычно <2%), но чуть выше чем settings=default — оправдано, так как
        // тикет P0 явно просит cache/queue contention через JFR.
    }

    create("server") {
        server()
        programArgument("--nogui")
        systemProperty("neoforge.enabledGameTestNamespaces", property("mod_id") as String)
    }

    // ... остальные runs без изменений
}
```

После правки — обычный запуск:

```bash
./gradlew runClient
```

Файл `ev-coldstart.jfr` появится в рабочей директории run'а — по умолчанию это
`ev-neoforge/run/` (или `runs/client/`, если `gameDirectory` переопределён; в текущем
`build.gradle.kts` он не переопределён, значит — `ev-neoforge/run/ev-coldstart.jfr`).

## Что делать дальше

1. Внутри игры выполни сценарии из тикета (холодный старт: `/tp` на большую дистанцию;
   steady-state: постоять/подвигаться/резко развернуться).
2. Закрой клиент (или дождись `duration=120s`) — JFR-файл сбрасывается на диск.
3. Открой `ev-coldstart.jfr` в JDK Mission Control (JMC, отдельная загрузка от
   https://jdk.java.net/jmc/) — там смотри breakdown по потокам (`MeshWorkerPool` воркеры),
   lock contention (`ReentrantLock` в `SectionCache`, `PriorityBlockingQueue` в очереди
   тикета 15), CPU по методам/классам.
4. Не забудь также включить `enableDebugOverlay: true` в конфиге EV перед прогоном — это
   первый дешёвый шаг из Части 2 тикета, до JFR/RenderDoc.

## Если понадобится раздельный профиль на каждый сценарий

Два отдельных запуска короче и чище читаются в JMC, чем один смешанный 120s-файл:

```kotlin
// холодный старт — короткая запись сразу от старта JVM
jvmArgument("-XX:StartFlightRecording=duration=30s,filename=ev-coldstart.jfr,settings=profile")

// ИЛИ steady-state — запись, начинающаяся с задержкой, после того как мир уже прогружен
jvmArgument("-XX:StartFlightRecording=delay=90s,duration=30s,filename=ev-steadystate.jfr,settings=profile")
```

`delay=90s` — ориентировочно, подбери под фактическое время загрузки мира в твоём
окружении (см. `Client level loaded` timestamp в логах прошлых запусков).
