# Contributing

LayBalt QOL is a Fabric mod for Minecraft 26.2 and Hypixel SkyBlock. It is written in Kotlin, with mixins and a few helpers in Java, and is built on the NoammAddons core. [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) lists what comes from other projects.

## Prerequisites

- **JDK 25.** Gradle itself must run on it: set `JAVA_HOME` to a JDK 25, or choose one as the Gradle JVM in IntelliJ. The Gradle wrapper downloads the Gradle version it needs.
- **JetBrains Runtime (JBR) 25 as the Gradle JVM** if you want to start the game. The game runs on the same JVM as Gradle, and both the run tasks and the `LayBaltQOL [runClient]` run configuration add `-XX:+AllowEnhancedClassRedefinition` (better hotswap). JBR supports this option, but other JDKs such as Temurin refuse to start with it. Building and running the tests work on any JDK 25.
- **IntelliJ IDEA** with the Kotlin plugin, which comes bundled with IntelliJ.

## Setup

1. Clone the repository: `git clone https://github.com/LayBalt/LayBalt-QOL.git`
2. Open the folder in IntelliJ and let it import the Gradle project.
3. Set the Gradle JVM (Settings > Build, Execution, Deployment > Build Tools > Gradle) and the project SDK to JDK 25. To start the game, the Gradle JVM must be JBR 25 (or set `JAVA_HOME` to JBR 25 when you use `./gradlew runClient`). IntelliJ can download JBR 25 under Project Structure > SDKs.
4. Start the game with the `LayBaltQOL [runClient]` run configuration (it is in `.run/`) or with `./gradlew runClient`. The dev client runs in `run/`, which git ignores. DevAuth is on the dev classpath, so you can sign in with your own account. Its README explains how to turn it on.

Minecraft 26.x is not obfuscated, so the names in the game classes are Mojang's own names.

## Project layout

```
src/main/kotlin/com/laybalt/
  LayBaltQOL.kt              client entrypoint
  features/                  Feature base class and FeatureManager
  features/impl/<category>/  the features
  config/                    config manager, setting types (config/types/) and config migrations
  ui/                        the LayBalt GUI (clickgui/), HUD editor, notifications and sub-screens
  commands/                  /lbq (also /laybalt) and its subcommands
  event/                     event bus and events (event/impl/)
  init/                      start-up: finds features, commands and mixins
  utils/                     helpers (dungeons, kuudra, items, network, render, ...)
  debug/                     debug HUD and test helpers for development
src/main/java/com/laybalt/
  mixin/                     mixins and accessors
  interfaces/                interfaces that mixins add to Minecraft classes
  features/, utils/          a few Java helpers (terminal simulator screen, GifDecoder)
src/main/resources/
  fabric.mod.json5           mod metadata
  lbqmod.mixins.json5        mixin config
  lbqmod.accesswidener       access widener
  assets/lbqmod/, data/      textures, font, icon, sea creature data, solver data
src/recorder/                LayBalt Recorder (the Fishing Recorder as its own jar)
src/test/kotlin/             unit tests
buildSrc/                    build logic: the preprocessor, variants, Loom runs and the recorder jar
```

### Features

Each feature is one Kotlin `object` that extends `Feature`, in `features/impl/<category>/`. The package sets the category: `dungeon`, `floor7`, `fishing`, `general`, `kuudra`, `misc`, `render`, `rift`, `visual`, or `dev` (shown as Client). The list is in `ui/clickgui/enums/CategoryType.kt`. At start-up, ClassGraph finds every feature (`init/ClassGraphInitializer.kt`), so there is no list to add yours to.

Settings are delegated properties that use the types in `config/types/` (`ToggleSetting`, `SliderSetting`, `ColorSetting`, `DropdownSetting`, `KeybindSetting`, ...). Read a setting with `.value`. Register event listeners in `init()`. They only run while the feature is on.

```kotlin
object ExampleFeature: Feature("What it does, shown in the GUI.") {
    private val onlyInWater by ToggleSetting("Only In Water")

    override fun init() {
        register<TickEvent.Start> {
            if (onlyInWater.value && ! player.isInWater) return@register
            // ...
        }
    }
}
```

The class name sets the feature's name, and the name is also the feature's key in the saved config. If you rename a feature, pass the old name as `jsonName` so that saved settings still load.

### Mixins

Put mixins in `src/main/java/com/laybalt/mixin/`. `init/AutoMixinDiscovery.kt` registers every class in that package, so `lbqmod.mixins.json5` has no list to edit. To widen access to a Minecraft member, add it to `lbqmod.accesswidener`.

### Cheat and legit code

One set of sources builds several variants. The preprocessor in `buildSrc/src/main/kotlin/Preprocessing.kt` keeps or drops blocks:

```kotlin
//#if CHEAT
clickTheSlot()
//#else
//$showAHint()
//#endif
```

- The flags are `CHEAT`, `LEGIT` and `RECORDER`. `!FLAG` negates a flag. An unknown flag or a misspelt directive fails the build.
- In a block that is kept, the `//$` marker is removed. A `//$` line is code that is live only in the variants that keep its block.
- The dev client and the tests compile the raw sources, so they behave like the cheat build: `//#if CHEAT` code runs and `//$` lines stay comments.
- Write every line of a block that the cheat build drops (the `//#else` part of `//#if CHEAT`, and `//#if LEGIT`, `//#if !CHEAT` and `//#if RECORDER` blocks) as a `//$` line. The raw sources compile every line that is not a comment, so plain code in such a block would also run in the dev client and the tests. The build does not check this.
- The one-line form `//#if LEGIT <code>` is only allowed for conditions that are false in the raw sources (`LEGIT`, `!CHEAT`, `RECORDER`).
- The same directives work in `fabric.mod.json5` and `lbqmod.mixins.json5`. The build turns these into `fabric.mod.json` and `lbqmod.mixins.json`. The dev client and the tests use the legit version of these two files, so a `//#if CHEAT` block in them is missing there.
- Placeholders such as `@MOD_ID@` and `@MOD_VERSION@` are filled in from `gradle.properties`.
- `./gradlew :buildSrc:test` runs the preprocessor's own tests.

## Building

- `./gradlew build` compiles the mod, runs the tests and builds `build/libs/LayBaltQOL-<mod_version>-26.2.jar`. This jar is the cheat variant, the one that is released. `mod_version` is set in `gradle.properties`.
- Players need Fabric Loader 0.19.3 or newer, Fabric API, and Fabric Language Kotlin 1.13.13+kotlin.2.4.10 or newer (see `depends` in `fabric.mod.json5`). The other libraries are bundled in the jar.
- Legit variant: `jarLegit` builds `LayBaltQOL-<mod_version>-26.2-legit.jar` from the sources preprocessed with `LEGIT`. However, `jarLegit` and the legit compile tasks are switched off at the end of `build.gradle.kts`, so `./gradlew jarLegit` alone skips them. To build the legit jar, remove those tasks from the `enabled = false` list in your local copy, and keep that change out of your pull request.
- LayBalt Recorder: `./gradlew jarRecorder` builds `build/libs/LayBaltRecorder-<recorder_version>-26.2.jar`. It is not part of `build`. After it is built, `verifyRecorderJar` checks that the jar holds only the recorder. The recorder is built from the files listed in `recorderShared` in `buildSrc/src/main/kotlin/lbq.recorder.gradle.kts` plus `src/recorder/`. If one of those shared files starts to use another LayBalt QOL class, the recorder build fails.

## Tests

- `./gradlew test` runs the unit tests, which `build` also runs. To run only some of them, use a filter such as `./gradlew test --tests 'puzzles.water.*'`.
- Tests that need a fishing recording read its path from an environment variable and are skipped when that variable is not set. The variables are `LBQ_RECORDING`, `LBQ_RECORDING_BAYOU` and `LBQ_RECORDER_RECORDING` (each a single file) and `LBQ_RECORDINGS_DIR` (a folder). `LBQ_REPLAY_KEEP` (optional) names a folder where the replay tests keep the bots' recordings. The Water Board fixture tests read `src/test/resources/waterboard/layouts/` and `.../runs/` (copied from `config/LayBaltQOL/waterboard/`) and are skipped when those folders are empty.
- Never commit fishing recordings (the files the `LBQ_*` variables point to): they contain chat lines from your game. Keep them outside the repository. Water Board fixtures hold only board states. Add only layouts and runs you recorded yourself, never the `trace-*.log` files.

## Conventions

- **Client thread.** Code that reads or changes game state (the player, the world, screens, options, rotations) must run on the client thread. Tick, chat, render and `MainThreadPacketReceivedEvent` listeners already run there. `PacketEvent.Received` does not: it is posted on the network thread. `ThreadUtils.async`, `ThreadUtils.setTimeout`, `ThreadUtils.loop` and `LayBaltQOL.scope` also run on background threads, which is where network calls, file I/O and heavy work belong. To get back to the client thread, use `mc.execute { ... }`, or `ThreadUtils.scheduledTask(ticks) { ... }` to wait for client ticks first (it only counts ticks while a world is loaded).
- **Ported code.** Only port code whose licence allows it. A file with ported code starts with a comment that names the project (with its URL), its licence and its copyright holder, for example:
  `// Ported from Odin (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking`
  Then add the project to `THIRD_PARTY_NOTICES.md`, or add a line to its existing entry. If the file is also one of the recorder's shared files, update `src/recorder/resources/THIRD_PARTY_NOTICES_LayBaltRecorder.md` as well. NoammAddons' own code (CC0-1.0) needs no header. Code that NoammAddons itself took from another project, such as Odin's text input handler, keeps that project's licence and needs the header above. When you sync a change from upstream, name the upstream commit in a comment, for example `// NoammAddons 20a78018: ...`.
- **Re-implemented features.** Do not copy code, assets or data from projects that `THIRD_PARTY_NOTICES.md` lists as re-implemented only, such as Skyblocker and ThunderHack-Recode.
- **Style.** Follow the style of the code around your change: a space after `!` (`if (! enabled)`) and no space before `:` in declarations (`object Foo: Feature()`). The scheme in `.idea/codeStyles` is the Kotlin official style, which differs on these points, so do not run Reformat Code on whole files.
- **Feature list.** When you add a feature, also add it to the Features list in `README.md`.

## Pull requests

1. Fork the repository and create a branch from `main`.
2. Keep each pull request to one change.
3. Run `./gradlew build` before you push. If you changed one of the recorder's shared files, also run `./gradlew jarRecorder`.
4. If the change affects the game, test it in the dev client.
5. Write commit messages as `type(scope): summary`, for example `fix(water): ...`. Common types are `feat`, `fix`, `refactor`, `test`, `docs` and `chore`.
6. Open the pull request against `main`. Say what changed and how you tested it, and note any ported code. CI (`.github/workflows/gradle.yml`) runs `./gradlew build` on JDK 25 for every pull request to `main`.

Your contribution is released under the project's licence, the Unlicense (see [LICENSE](LICENSE)). Ported code keeps its own licence, as listed in `THIRD_PARTY_NOTICES.md`.

For questions or bug reports, open an issue at https://github.com/LayBalt/LayBalt-QOL/issues.
