# Contributing

LayBaltQOL is a **Fabric** mod for **Minecraft 26.2**, built with [Fabric Loom](https://github.com/FabricMC/fabric-loom) and written in Java + Kotlin.

## Requirements

- **JDK 25** (Minecraft 26.x and Fabric Loom require it). Gradle itself must also run on JDK 25:
  either set `JAVA_HOME` to a JDK 25 or pick it as the *Gradle JVM* in IntelliJ.
  The wrapper downloads the correct Gradle version (9.5.x) automatically.
- IntelliJ IDEA 2025.3 or newer (older versions do not understand the unobfuscated 26.x jars / Mixin plugin).

## Setup

1. Clone the repository and open it in IntelliJ as a Gradle project.
2. Set *Gradle JVM* and *Project SDK* to JDK 25.
3. Run `./gradlew genSources` once if you want browsable Minecraft sources (26.x is unobfuscated, so the names
   you see are the real Mojang names).
4. Use the generated **Minecraft Client** run configuration (or `./gradlew runClient`) to start the game with the mod.

## Project layout

- `src/main/java/com/laybalt/Main.java` – client entrypoint; every feature exposes `register()` and is wired here.
- `src/main/java/com/laybalt/util/` – `KeyBinds` (key mapping registration), `ChatUtil` (chat / action bar),
  `ClientActions` (simulated clicks, client-thread helper).
- `src/main/kotlin/com/laybalt/GUI/` – configuration (`LBQConfigData` is what gets saved to
  `config/LBQConfig.json`, `LBQConfig` is the property facade + the YACL config screen).
- `src/main/java/com/laybalt/skyblock/...`, `src/main/kotlin/com/laybalt/...` – the features.
- `src/main/resources/fabric.mod.json`, `lbqmod.accesswidener`, `assets/lbqmod/lang/en_us.json`.

## Conventions

- Anything that touches game state (`Minecraft.getInstance().player`, rotations, screens, options) must run on the
  client thread. Timer / executor callbacks go through `ClientActions.runOnClientThread(...)`.
- Simulated clicks always use `ClientActions.leftClick()` / `rightClick()`.
- New config values go into `LBQConfigData` **and** get a facade property + screen option in `LBQConfig`.
- New key binds: `KeyBinds.register(...)` plus a translation in `assets/lbqmod/lang/en_us.json`.

## Building a release

Run `./gradlew build`. The distributable jar is `build/libs/LayBaltQOL-<version>.jar`
(the `-sources.jar` next to it is not needed by players). Players need Fabric Loader, Fabric API and
Fabric Language Kotlin; YACL is bundled in the jar.
