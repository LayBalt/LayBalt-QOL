package com.laybalt.config

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.laybalt.LayBaltQOL
import com.laybalt.commands.CommandBuilder
import com.laybalt.config.types.*
import com.laybalt.event.EventBus
import com.laybalt.event.EventListener
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.Feature
import com.laybalt.features.FeatureManager
import com.laybalt.features.impl.floor7.terminals.TerminalPBs
import com.laybalt.features.impl.general.SlotBinding
import com.laybalt.features.impl.kuudra.KuudraTracker
import com.laybalt.features.impl.visual.RunSplits
import com.laybalt.init.types.ICommandProvider
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.ThreadUtils
import com.mojang.blaze3d.platform.InputConstants
import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/*
 * One-time import of a player's NoammAddons, Odin and OdinClient settings into LayBaltQOL.
 *
 * 1. NoammAddons (LayBaltQOL is a fork, so file formats, feature names and setting names are the same):
 *    [ForeignConfigPreLaunch] is a Fabric `preLaunch` entrypoint. It runs before Minecraft's main class is loaded, so
 *    before any LayBaltQOL object (ConfigManager, PogObjects, features) can exist. On a fresh install (no
 *    `config/LayBaltQOL/config.json`) it copies NoammAddons' files into `config/LayBaltQOL/`, never overwriting a file.
 *    ConfigManager then loads them as its own; features and settings LayBaltQOL no longer has are ignored by the loader.
 *    The pre-launch code below ([ForeignImportMarker], [NoammAddonsFileImport]) only uses java.io, Gson and Fabric Loader.
 * 2. Odin + OdinClient: [ForeignConfigImport.runAfterConfigLoad] runs after the features are registered, the config is
 *    loaded and [LegacyConfigImport] ran. Each Odin module is mapped to the LayBaltQOL feature that now carries it
 *    (explicit table below) and each setting is matched by exact name, then by the alias table, then by normalised name,
 *    and converted to the LayBaltQOL setting type.
 * 3. Bookkeeping: `config/LayBaltQOL/foreignImport.json` (one flag + timestamp per source), `import-report.txt`, one chat
 *    message on the first world join after an import, and `/lbq import <na|odin|odinclient|all>` to re-run a source.
 * Nothing here throws: missing or malformed files are logged and written to the report.
 */

/** Fabric `preLaunch` entrypoint (see fabric.mod.json5): copies the NoammAddons files before anything reads the config. */
object ForeignConfigPreLaunch: PreLaunchEntrypoint {
    override fun onPreLaunch() = NoammAddonsFileImport.run()
}

/** `config/LayBaltQOL/foreignImport.json`. Safe to use at pre-launch (no Minecraft or LayBaltQOL classes). */
internal object ForeignImportMarker {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val logger = LoggerFactory.getLogger("LayBalt QOL")

    val configDir: File get() = FabricLoader.getInstance().configDir.toFile()
    val modDir: File get() = File(configDir, LayBaltQOL.CONFIG_DIR)
    private val file get() = File(modDir, "foreignImport.json")

    @Synchronized
    fun read(): JsonObject = runCatching { JsonParser.parseString(file.readText()).asJsonObject }.getOrElse { JsonObject() }

    @Synchronized
    fun update(block: (JsonObject) -> Unit) {
        val root = read()
        block(root)
        runCatching {
            modDir.mkdirs()
            file.writeText(gson.toJson(root))
        }.onFailure { logger.warn("[ForeignConfigImport] could not write ${file.name}: ${it.message}") }
    }

    fun source(key: String): JsonObject? = read().get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    fun isDone(key: String) = source(key)?.get("done")?.asBoolean == true

    fun strings(list: List<String>) = JsonArray().apply { list.forEach(::add) }
}

/** Copies NoammAddons' files into `config/LayBaltQOL/` on a fresh install. Runs at pre-launch. */
internal object NoammAddonsFileImport {
    private val logger = LoggerFactory.getLogger("LayBalt QOL")
    val naDir: File get() = File(ForeignImportMarker.configDir, "NoammAddons")

    private val sameName = setOf(
        "config.json", "currentConfig.json", "personal_bests.json", "commandShortcuts.json", "customHiders.json",
        "dungeonWaypoints.json", "party_command_blacklist.json", "petCache.json", "mojang_cache.json"
    )
    private val renamed = mapOf("noammaddons_sounds.json" to "lbqmod_sounds.json")
    private val dropped = mapOf(
        "item_protection.json" to "Protect Item is not part of LayBaltQOL",
        "data" to "NoammAddons' downloaded data; LayBaltQOL bundles its own"
    )

    fun run() {
        try {
            copy()
        }
        catch (e: Throwable) {
            logger.warn("[ForeignConfigImport] copying the NoammAddons config failed", e)
        }
    }

    private fun copy() {
        val src = naDir
        if (! src.isDirectory || ForeignImportMarker.isDone("na")) return
        val dst = ForeignImportMarker.modDir
        if (File(dst, "config.json").exists()) {
            // Not a fresh install: the player already has LayBaltQOL settings, nothing is copied automatically.
            ForeignImportMarker.update {
                it.add("na", JsonObject().apply {
                    addProperty("done", true)
                    addProperty("imported", false)
                    addProperty("at", Instant.now().toString())
                    addProperty("note", "LayBaltQOL already had a config; run /lbq import na to apply NoammAddons' settings")
                })
            }
            return
        }

        val copied = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        fun copyFile(from: File, to: File, label: String) {
            if (to.exists()) return run { skipped += "$label: a LayBaltQOL file already exists, kept it" }
            runCatching {
                to.parentFile?.mkdirs()
                from.copyTo(to)
            }.onSuccess { copied += label }.onFailure { skipped += "$label: copy failed (${it.message})" }
        }

        dst.mkdirs()
        for (entry in src.listFiles().orEmpty().sortedBy { it.name.lowercase() }) {
            val name = entry.name
            when {
                entry.isFile && (name in sameName || (name.startsWith("slotbindings") && name.endsWith(".json"))) -> copyFile(entry, File(dst, name), name)
                entry.isFile && name in renamed -> copyFile(entry, File(dst, renamed.getValue(name)), "$name -> ${renamed.getValue(name)}")
                entry.isDirectory && name == "configs" -> entry.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".json") }
                    .forEach { copyFile(it, File(dst, "configs/${it.name}"), "configs/${it.name}") }

                entry.isDirectory && name == "storage" -> entry.walkTopDown().filter { it.isFile }.forEach {
                    val rel = it.relativeTo(entry).invariantSeparatorsPath
                    copyFile(it, File(dst, "storage/$rel"), "storage/$rel")
                }

                name in dropped -> skipped += "$name: ${dropped.getValue(name)}"
                else -> skipped += "$name: not used by LayBaltQOL"
            }
        }

        ForeignImportMarker.update {
            it.add("na", JsonObject().apply {
                addProperty("done", true)
                addProperty("imported", true)
                addProperty("at", Instant.now().toString())
                add("copied", ForeignImportMarker.strings(copied))
                add("skipped", ForeignImportMarker.strings(skipped))
                addProperty("reportPending", true)
            })
        }
        logger.info("[ForeignConfigImport] copied ${copied.size} NoammAddons files into config/${LayBaltQOL.CONFIG_DIR}")
    }
}

object ForeignConfigImport: ICommandProvider {
    private val logger get() = LayBaltQOL.logger
    private val configDir get() = ForeignImportMarker.configDir
    private val reportFile get() = File(ForeignImportMarker.modDir, "import-report.txt")
    private const val REPORT_PATH = "config/${LayBaltQOL.CONFIG_DIR}/import-report.txt"

    enum class Source(val key: String, val label: String) {
        NA("na", "NoammAddons"), ODIN("odin", "Odin"), ODINCLIENT("odinclient", "OdinClient")
    }

    // region module mapping table

    private sealed interface Rule
    private class To(val target: String, val invert: Boolean): Rule
    private class Skip(val reason: String): Rule
    private class Custom(val block: (Ctx, JsonElement) -> Unit): Rule

    private class Spec(val source: Source, val name: String, val features: List<String>) {
        val rules = LinkedHashMap<String, Rule>()

        /** LayBaltQOL toggles that mirror the module's on/off switch (for modules merged into part of a feature). */
        var switches = emptyList<String>()

        /**
         * Sub-module merge: Odin settings in [gate] are the module's own on/off parts (e.g. Secret Hitboxes > Lever);
         * while the module was off they are imported as off. Other settings (sub-options) keep their value.
         */
        var gate = emptySet<String>()

        /** false: the module's on/off state never toggles the LayBaltQOL feature. */
        var enableFeature = true

        fun alias(odin: String, target: String) = rules.set(odin, To(target, false))
        fun invert(odin: String, target: String) = rules.set(odin, To(target, true))
        fun skip(reason: String, vararg odin: String) = odin.forEach { rules[it] = Skip(reason) }
        fun custom(odin: String, block: (Ctx, JsonElement) -> Unit) = rules.set(odin, Custom(block))
    }

    private val specs = mutableListOf<Spec>()
    private fun odin(name: String, vararg features: String, block: Spec.() -> Unit = {}) = specs.add(Spec(Source.ODIN, name, features.toList()).apply(block))
    private fun odinClient(name: String, vararg features: String, block: Spec.() -> Unit = {}) = specs.add(Spec(Source.ODINCLIENT, name, features.toList()).apply(block))

    private const val HUD = "HUD position/scale is not imported"
    private const val SOUND = "Odin sound ids/pitch/volume are not imported; LayBaltQOL uses its own sounds"
    private const val NO_EQUIVALENT = "no LayBaltQOL equivalent"

    /** Modules that are deliberately not imported, with the reason written to the report. Keys are lowercase. */
    private val unsupported = mapOf(
        Source.ODIN to mapOf(
            "waypoints" to NO_EQUIVALENT, "player size" to NO_EQUIVALENT,
            "click gui" to "Odin's own GUI settings",
            "no cursor reset" to NO_EQUIVALENT, "hide players" to NO_EQUIVALENT,
            "dungeon waypoints" to "settings not converted; import the waypoint packs with /lbq dwp importodin (or Dungeon Waypoints > Import Odin Waypoints)",
        ),
        Source.ODINCLIENT to mapOf(
            "--- odinclient settings ---" to "OdinClient's own settings", "etherwarp helper" to NO_EQUIVALENT,
            "spirit bear (c)" to NO_EQUIVALENT, "cheater wardrobe" to NO_EQUIVALENT, "triggerbot" to NO_EQUIVALENT,
            "queue terms" to NO_EQUIVALENT, "inventory walk (!!!)" to NO_EQUIVALENT, "auto dojo (!!!)" to NO_EQUIVALENT,
        ),
    )

    init {
        // ---------------- Odin ----------------
        odin("Kuudra Info", "Kuudra Info")
        odin("Melody Message", "Melody Alert") {
            custom("Melody Message") { c, v ->
                val send = c.settings.get("Send Melody Message")?.let { runCatching { it.asBoolean }.getOrNull() } ?: true
                set(c, "Melody Message", "Melody Message", if (send) v else JsonPrimitive(""))
            }
            skip("folded into Melody Message (an empty message sends nothing)", "Send Melody Message")
            alias("Melody Progress", "Send Progress")
            alias("Melody Send Coords", "Send Coords")
            alias("Progress GUI", "Progress HUD")
            skip("Odin's websocket broadcast; LayBaltQOL reads party chat", "Broadcast Progress")
        }
        odin("Loadout Keybinds", "Loadout Keybinds") { (1 .. 12).forEach { alias("Loadout $it", "Loadout Slot $it") } }
        odin("Arrow Align", "Arrow Align") { skip(NO_EQUIVALENT, "Prevent Blocking", "Invert Key") }
        odin("Leap Menu", "Leap Menu") {
            switches = listOf("Leap Menu")
            alias("Sorting", "Leap Order")
            alias("Top Left", "Slot 1")
            alias("Top Right", "Slot 2")
            alias("Bottom Left", "Slot 3")
            alias("Bottom Right", "Slot 4")
            alias("Berserker", "Berserk")
            alias("Leap Announce", "Announce Leap")
            skip(NO_EQUIVALENT, "On Key Release", "Only Classes", "Color Style", "Background Color", "Render Scale")
        }
        odin("King Relic", "M7 Relics") {
            alias("Relic Time", "Place Timer")
            custom("Relics") { c, v -> importPBs(c, v) { "M7_relic_${it.uppercase()}" } }
        }
        odin("Command Keybinds", "Command Keybinds")
        odin("Mimic", "Mimic")
        odin("Pre-Spot Alert", "No Pre")
        odin("Ragnarock", "Ragnarock") {
            alias("Cancel alert", "Alert Cancelled")
            skip(NO_EQUIVALENT, "Cast alert", "Announce strength")
        }
        odin("Terracotta Timer", "Terracotta Timer")
        odin("Simon Says", "Simon Says") {
            alias("Third Color", "Other Color")
            skip(NO_EQUIVALENT, "Style", "Announce Progress", "Block Wrong on Start", "Max Start Clicks", "Custom Click Sounds")
            skip(SOUND, "Correct Sound", "Correct Sound Pitch", "Correct Sound Volume", "Wrong Sound", "Wrong Sound Pitch", "Wrong Sound Volume")
        }
        odin("Quiver Display", "Quiver Display")
        odin("Gyro Wand", "Gyro Helper") { alias("Color", "Ring Color") }
        odin("Wardrobe Keybinds", "Wardrobe Keybinds") {
            (1 .. 9).forEach { alias("Wardrobe $it", "Wardrobe Slot $it") }
            alias("Disable Unequip", "Prevent Unequip")
            skip("LayBaltQOL Wardrobe Keybinds has no page/unequip keys", "Next Page", "Previous Page", "Unequip")
        }
        odin("Dungeon Map", "Dungeon Map") {
            alias("Dungeon Map", "Map Enabled")
            alias("Disable in Boss", "Hide In Boss")
            alias("Background Outline", "Map Border Color")
            alias("Background Color", "Map Background Color")
            alias("Player Names Scaling", "Player Name Scale")
            alias("Champion Room", "Miniboss Room")
            alias("Unknown Room", "Unopened Room")
            alias("Unknown Door", "Unopened Door")
            skip(NO_EQUIVALENT, "Room Text", "Text Scaling", "Player Name Color", "Own Player Head", "Fairy Door", "Disable Prediction", "Parse map websocket")
        }
        odin("Terminal Solver", "Terminal Solver") {
            alias("Normal Term Size", "Vanilla Term Size")
            alias("Resolve timeout", "Break Timeout")
            invert("Stop Melody Solver", "Melody")
            alias("First Click Prot", "First Click Protection")
            alias("Background", "Background Color")
            alias("Text", "Overlay Text Color")
            alias("Panes", "Solution Color")
            alias("Rubix 1", "Rubix: Positive (+)")
            alias("Rubix -1", "Rubix: Negative (-)")
            alias("Order 1", "1st Click Color")
            alias("Order 2", "2nd Click Color")
            alias("Order 3", "3rd Click Color")
            alias("Melody Column", "Melody: Column")
            alias("Melody Pointer", "Melody: Indicator")
            skip("Odin render types differ from LayBaltQOL's Render Mode", "Render type")
            skip(NO_EQUIVALENT, "Term Size", "Roundness", "Melody Size", "Rubix 2", "Rubix -2", "Starts With", "Select", "Melody Background", "Debug")
        }
        odin("Terminal Sounds", "Terminal Solver") {
            gate = setOf("Click Sounds", "Complete Sounds")
            alias("Click Sounds", "Terminal Sounds")
            skip(SOUND, "Click Sound", "Click Sound Pitch", "Click Sound Volume", "Completion Sound", "Completion Sound Pitch", "Completion Sound Volume")
        }
        odin("Better Party Finder", "Party Finder") {
            alias("Stats display", "Join Stats")
            alias("Minimum PB", "Maximum Seconds")
            alias("Magical Power", "Minimum MP")
            skip(NO_EQUIVALENT, "Send Kick Line", "Api Off Kick", "Kick Cache")
        }
        odin("Arrows Device", "Arrows Device")
        odin("Slot Binds", "Slot Binding") {
            alias("Bind set key", "Binding key")
            alias("Bind Color", "Line Color")
            custom("Line Display") { c, v ->
                when (v.asString.uppercase()) {
                    "HOVER" -> set(c, "Line Display", "Hover Only", JsonPrimitive(true))
                    "ON_HOVER_SHIFT" -> {
                        set(c, "Line Display", "Hover Only", JsonPrimitive(true))
                        set(c, "Line Display", "Require Shift", JsonPrimitive(true))
                    }

                    "NONE" -> set(c, "Line Display", "Draw Line", JsonPrimitive(false))
                    else -> c.report.line("    - Line Display: unknown value $v")
                }
            }
            custom("ProfileData") { c, v -> importSlotBinds(c, v) }
        }
        odin("Tick Timers", "Tick Timers") {
            custom("Display in Ticks") { c, v -> set(c, "Display in Ticks", "Format", JsonPrimitive(if (v.asBoolean) "Ticks" else "Seconds")) }
            alias("Display Symbol", "Suffix")
            alias("Show Prefix", "Prefix")
            alias("Start timer", "Maxor Start")
            alias("Necron Hud", "Necron Start")
            alias("Goldor Hud", "Goldor Start")
            alias("Storm Pad Hud", "Storm Pad Timer")
            alias("Storm PY Hud", "Storm PY Timer")
            alias("Secrets Hud", "Secret Tick")
            skip(NO_EQUIVALENT, "Storm Lightning Hud", "Storm Tick Hud", "Fire Freeze Hud")
        }
        odin("Chat Commands", "Party Helper") {
            val commands = mapOf(
                "Warp" to "!w", "Coords (coords)" to "!coords", "Allinvite" to "!ai", "Boop" to "!boop", "Kick" to "!kick",
                "Coinflip (cf)" to "!cf", "Eightball" to "!8ball", "Dice" to "!dice", "Party transfer (pt)" to "!pt",
                "Reinvite" to "!reinvite", "Ping" to "!ping", "Tps" to "!tps", "FPS" to "!fps", "DT" to "!dt",
                "Invite" to "!invite (DM)", "Time" to "!time", "Demote" to "!demote", "Promote" to "!promote",
                "Kick Offline" to "!kickoffline", "Location" to "!location", "Holding" to "!holding"
            )
            commands.forEach { (odin, cmd) -> alias(odin, "Enabled Commands >> $cmd") }
            custom("Queue instance cmds") { c, v -> listOf("!f", "!m", "!t").forEach { set(c, "Queue instance cmds", "Enabled Commands >> $it", v) } }
            alias("Auto Confirm", "Auto Confirm Invite")
            skip(NO_EQUIVALENT, "Odin", "Racism")
            skip("LayBaltQOL's emotes are Chat > Chat Emoji (a separate feature)", "Chat Emotes")
        }
        odin("Kuudra Tracker", "Kuudra Tracker") {
            custom("Single Items") { c, _ -> importKuudraTracker(c) }
            skip("imported together with Single Items", "Multi Items", "Paid Chests", "Free Chests", "Total Keys")
        }
        odin("Terminal Simulator", "Terminal Simulator") {
            enableFeature = false // its toggle opens the simulator
            invert("First Click Prot", "Disable First Click Prot")
            custom("TermsimPBs") { c, v -> importTerminalPBs(c, v, simulator = true) }
        }
        odin("Wither Dragons", "Wither Dragons") {
            alias("Dragon Boxes", "Dragon Skip Box")
            alias("Target Tracer", "Dragon Tracer")
            custom("DragonPBs") { c, v -> importPBs(c, v) { "M7_dragon_${it.lowercase().replaceFirstChar(Char::uppercase)}" } }
        }
        odin("Invincibility Timer", "Mask Timers") {
            alias("Only In Dungeons", "Dungeons Only")
            alias("Invincibility Alert", "Proc Notification")
            alias("Announce Invincibility", "Proc Message")
            alias("Invincibility Timer", "Invulnerability Timers")
        }
        odin("Highlight", "Star Mob ESP") {
            alias("Highlight color", "Star Mob Color")
            alias("Render Style", "Box Style")
            skip("starred mobs are always highlighted in LayBaltQOL", "Highlight Starred Mobs")
        }
        odin("Terminal Titles", "F7 Titles") {
            switches = listOf("Terminal Titles")
            custom("Use Subtitle") { c, v -> set(c, "Use Subtitle", "Display", JsonPrimitive(if (v.asBoolean) "Vanilla Subtitle" else "Vanilla Title")) }
            alias("Custom Title", "Format")
        }
        odin("Spring Boots", "Spring Boots") { alias("Spring Boots", "Show in HUD") }
        odin("Camera", "Camera Helper", "Camera") {
            switches = listOf("Disable Front Camera")
        }
        odin("Build Helper", "Build Helper")
        odin("Fresh Tools", "Fresh Tools")
        odin("Misc", "Kuudra Misc")
        odin("Vesuvius", "Vesuvius")
        odin("Performance HUD", "Info Display") {
            alias("Show FPS", "FPS Display")
            alias("Show TPS", "TPS Display")
            alias("Show Ping", "Ping Display")
            skip(NO_EQUIVALENT, "Name Color", "Value Color", "Direction")
        }
        odin("Blessing Display", "Blessing Display") { alias("Life Color", "Red Color") }
        odin("Auto Sprint", "Auto Sprint")
        odin("Etherwarp", "Etherwarp") {
            alias("Show Guess", "Etherwarp Overlay")
            custom("Color") { c, v -> set(c, "Color", "Outline Color", v); set(c, "Color", "Fill Color", v) }
            alias("Show when failed", "Show Fail")
            custom("Fail Color") { c, v -> set(c, "Fail Color", "Invalid Outline Color ", v); set(c, "Fail Color", "Invalid Fill Color ", v) }
            alias("Render Style", "Mode")
            alias("Depth", "Phase")
            alias("Custom Sounds", "Etherwarp Sound")
            skip(SOUND, "Etherwarp Sound", "Etherwarp Sound Pitch", "Etherwarp Sound Volume")
            skip("Odin menu header, no value", "Sounds")
        }
        odin("Mage Beam", "Mage Beam") { alias("Color", "Primary Color") }
        odin("Render Optimizer", "Render Optimizer") {
            alias("Hide Lightning", "Hide Lightning Bolts")
            alias("Hide Experience Orbs", "Hide XP Orbs")
            alias("Hide Death Animation", "Hide Dead Mobs")
            alias("Hide Armor Stands", "Hide Dying Armor Stands")
            alias("Hide Tentacle Head", "Hide P5 Tentacles")
            skip("in No Render > Explosions", "Hide Explosion Particles")
            skip("in Camera Helper > Hide Fire Overlay", "Hide Fire Overlay")
        }
        odin("Puzzle Solvers", "Puzzle Solvers") {
            alias("Blaze Solver", "@section:Blaze Solver")
            alias("Boulder Solver", "@section:Boulder Solver")
            alias("Creeper Beams Solver", "@section:Creeper Beam Solver")
            alias("Quiz Solver", "@section:Quiz Solver")
            alias("TP Maze Solver", "@section:Teleport Maze Solver")
            alias("Weirdos Solver", "@section:Three Weirdos Solver")
            alias("Water Board Solver", "@section:Water Board Solver")
            alias("Ice Fill Solver", "@section:Ice Fill Solver")
            alias("Blaze Solver Next Line", "Blaze Next Line")
            alias("Blaze Solver Lines", "Blaze Lines")
            alias("Blaze Solver Lines Width", "Blaze Line Width")
            alias("Blaze Style", "Blaze Box Style")
            alias("First Color", "First Blaze Color")
            alias("Second Color", "Second Blaze Color")
            alias("Third Color", "Third Blaze Color")
            alias("Other Color", "Other Blazes Color")
            alias("Send Complete", "Send Blaze Complete")
            alias("Show All Boulder Clicks", "Show All Moves")
            alias("Boulder Color", "Boulder Box Color")
            alias("Quiz Color", "Answer Color")
            alias("Quiz Timer Hud", "Quiz Timer")
            alias("Weirdos Correct Color", "Correct Chest Color")
            alias("Weirdos Wrong Color", "Wrong Chest Color")
            alias("Color for one", "Correct Pad Color")
            alias("Tracer Color Second", "Next Click Color")
            skip("LayBaltQOL has several \"Click Color\" settings in this feature; not imported to avoid writing the wrong one", "Tracer Color First", "Ice Fill Color")
            skip(NO_EQUIVALENT, "Optimized Solutions", "Water Tracer", "Color for multiple", "Color for visited", "Maze Tracer", "Tracer Color",
                "Use Optimized Patterns", "Beams Style", "Beams Tracer", "Beams Color Alpha", "Weirdos Style", "Quiz Depth", "Boulder Style")
            custom("PuzzlePBs") { c, v -> importPBs(c, v) { "puzzle_${it.lowercase().replace(' ', '_')}" } }
        }
        odin("Blood Camp", "Blood Camp") {
            alias("Blood Camp Assist", "Blood Camp Helper")
            alias("Spawn Color", "Box Color")
            alias("Line", "Draw Line")
            alias("Time Left", "Draw Timer")
            alias("Offset", "Time Offset")
            alias("Tick", "Spawn Tick")
            alias("Move HUD", "Move Timer HUD")
            skip(NO_EQUIVALENT, "Move Prediction", "Move Message", "Party Move Message")
        }
        odin("Croesus", "Chest Profit") {
            alias("Highlight Profitable", "Highlight Croesus Chests")
            alias("Include Essence", "Includes Essence")
            alias("Hide Claimed", "Hide Opened Chests")
            skip(NO_EQUIVALENT, "Highlight State", "Include Key", "Minimized")
        }
        odin("Pearl Waypoints", "Pearl Waypoints")
        odin("Supply Helper", "Supply Helper")
        odin("Remove Perks", "Remove Perks")
        odin("Pet Keybinds", "Pet Menu") {
            (1 .. 9).forEach { alias("Pet $it", "Pet Slot $it") }
            alias("Close If Already Equipped", "Close If Equipped")
        }
        odin("Terminal Times", "Terminal Times") { custom("TerminalPBs") { c, v -> importTerminalPBs(c, v, simulator = false) } }
        odin("Player Display", "Player HUD") {
            alias("Hide Armor", "Hide Armor bar")
            alias("Hide Food", "Hide Food bar")
            alias("Hide Hearts", "Hide Health bar")
            alias("EHP color", "Effective HP Color")
            listOf("Health", "Mana", "Overflow Mana", "Defense", "Vitality").forEach { alias("Hide $it", "Hide from Actionbar >> $it") }
            listOf("Health", "Mana", "Overflow Mana", "Defense", "Vitality", "Speed").forEach { alias("$it HUD", "Elements >> $it") }
            alias("EHP HUD", "Elements >> Effective HP")
        }
        odin("Secret Clicked", "Secrets") {
            gate = setOf("Secret Boxes", "Secret Chime")
            alias("Secret Boxes", "Highlight Clicked Secret")
            alias("Box style", "Render Mode")
            alias("Box color", "Highlight Color")
            invert("Depth check", "See Through Walls")
            alias("Time To Stay", "Highlight Time")
            alias("Box In Boss", "Highlight In Boss")
            alias("Secret Chime", "Secret Sound")
            alias("Chime In Boss", "Sound In Boss")
            skip(SOUND, "Chime Sound", "Chime Sound Pitch", "Chime Sound Volume")
        }
        odin("Livid Solver", "Livid Solver")
        odin("Door Highlight", "Door Keys")
        odin("Map Info", "Score Calculator") {
            alias("300 Score Alert", "300 score Title")
            alias("Room Secrets", "Room Secrets HUD")
            alias("Full Hud", "Full HUD")
        }
        odin("Secrets Counter", "Secrets Counter")
        odin("Positional Messages", "Positional Messages") {
            custom("Pos Messages") { c, v -> set(c, "Pos Messages", "Pos Messages", odinPosMessages(v)) }
        }
        odin("Puzzle HUD", "Puzzle HUD")
        odin("Room Clear", "Room Clear")
        odin("Dungeon Queue", "Dungeon Queue") { alias("Warp Cooldown", "Warp Cooldown HUD") }
        odin("Inactive Waypoints", "Inactive Waypoints") {
            val shows = listOf("All", "TermsAndLevers", "Terminals", "Devices", "Levers")
            val styles = listOf("Full", "BoxText", "Box", "Text")
            custom("Show") { c, v -> set(c, "Show", "Show", JsonPrimitive(shows.indexOf(v.asString).coerceAtLeast(0))) }
            custom("Style") { c, v -> set(c, "Style", "Style", JsonPrimitive(styles.indexOf(v.asString).coerceAtLeast(0))) }
            alias("Term Info", "Term Info HUD")
        }
        odin("Extra Stats", "Extra Stats")
        odin("Splits", "Run Splits") {
            alias("Current Split HUD", "Current Split HUD")
            custom("Location") { c, v -> set(c, "Location", "Kuudra Splits", JsonPrimitive(v.asString != "DUNGEONS_ONLY")) }
            skip(NO_EQUIVALENT, "Fixed Width", "Boss Entry Split", "Show 0 splits")
            val groups = listOf("E", "F1", "F2", "F3", "F4", "F5", "F6", "F7", "M1", "M2", "M3", "M4", "M5", "M6", "M7").map { "Dungeon$it" } +
                (1 .. 5).map { "KuudraT$it" }
            for (group in groups) custom(group) { c, v ->
                val pbs = v.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()
                    ?.mapNotNull { (k, time) -> runCatching { k to time.asDouble }.getOrNull() }?.toMap().orEmpty()
                if (pbs.isNotEmpty()) RunSplits.importOdinPBs(group, pbs) { c.report.line(it) }
            }
        }

        // ---------------- OdinClient ----------------
        odinClient("Auto Harp", "Auto Harp")
        odinClient("Auto superboom", "Auto Superboom")
        odinClient("Trajectories", "Trajectories")
        odinClient("No Glow", "No Glow")
        odinClient("Auto Experiments", "Auto Experiments", "Auto Enchanting") {
            alias("Delay variety", "Delay Variance")
            alias("Serum Count", "Serums Consumed")
        }
        odinClient("Close Chest", "Secrets") {
            switches = listOf("Close Chest")
            alias("Mode", "Close Chest Mode")
        }
        odinClient("Auto Abilities", "Abilities") { alias("Auto Ult", "Auto Use Ultimate") }
        odinClient("Ghost Block", "Ghost Block") { skip("OdinClient warning toggle", "Use at your own risk") }
        odinClient("Farm keys", "Farm Keys")
        odinClient("Breaker Helper", "Breaker Helper") {
            alias("Prevent mining secrets", "Prevent Secret Mine")
            alias("Insta-mine when fatigue", "Zero Ping")
            alias("Display charges", "Charges HUD")
        }
        odinClient("Highlight (C)", "Star Mob ESP") {
            alias("Highlight color", "Star Mob Color")
            alias("Render Style", "Box Style")
            invert("Depth Check", "Boxes Through Walls")
            skip("starred mobs are always highlighted in LayBaltQOL", "Highlight Starred Mobs")
            skip("withers are Wither ESP in LayBaltQOL (a separate feature)", "Highlight Withers", "Wither ESP Color", "Wither Tracer")
            skip(NO_EQUIVALENT, "Starred mobs tracers", "Custom tracer", "highlightMap")
        }
        odinClient("Simon Says Additions", "Simon Says") { gate = setOf("Auto start") }
        odinClient("Livid Solver (C)", "Livid Solver") { skip(NO_EQUIVALENT, "Depth Check") }
        odinClient("Escrow Fix", "Escrow Fix")
        odinClient("Cheater map", "Dungeon Map") {
            switches = listOf("Cheater Map")
            alias("Show hidden rooms", "Show Hidden Rooms")
            alias("Darken tiles", "Darken Hidden Tiles")
            alias("Show names", "Show Hidden Names")
        }
        odinClient("Fuck Diorite", "I Hate Diorite") {
            alias("Pillar Based", "Pillar Based Color")
            alias("Color", "Glass Color")
            alias("Schitzo mode", "Random Colors")
        }
        odinClient("Camera helper", "Camera Helper", "Camera") {
            gate = setOf("Camera Clip", "Custom distance")
            alias("Custom distance", "Custom Camera Distance")
            alias("Distance", "Camera Distance")
        }
        odinClient("Secret Hitboxes", "Secrets") {
            gate = setOf("Lever", "Button", "Skulls", "Chests")
            custom("1.8 lever hitbox") { c, v -> set(c, "1.8 lever hitbox", "Lever Hitbox Style", JsonPrimitive(if (v.asBoolean) "1.8 Style" else "Scaled")) }
            custom("Flat button hitbox") { c, v -> set(c, "Flat button hitbox", "Button Hitbox Style", JsonPrimitive(if (v.asBoolean) "Flat" else "Scaled")) }
        }
        odinClient("Auto GFS", "Auto GFS") {
            custom("In Skyblock") { c, v ->
                fun flag(name: String) = c.settings.get(name)?.let { runCatching { it.asBoolean }.getOrNull() } ?: false
                val scope = when {
                    v.asBoolean -> "Everywhere in SkyBlock"
                    flag("In Kuudra") -> "Dungeons + Kuudra"
                    else -> "Dungeons"
                }
                set(c, "In Skyblock / In Kuudra / In Dungeon", "Scope", JsonPrimitive(scope))
            }
            skip("folded into Scope (see In Skyblock)", "In Kuudra", "In Dungeon")
            alias("Timer Increments", "Check Delay")
            skip("Draft Prompt is a Puzzle Solvers setting in LayBaltQOL", "Auto Get Draft")
        }
        odinClient("Auto Sell", "Auto Sell")
        odinClient("Ghosts", "Ghosts")
        odinClient("Auto Terms", "Auto Terminal")
        odinClient("Door Highlight (C)", "Door Keys") {
            switches = listOf("Wither Key", "Blood Key")
            alias("Wither Color", "Wither Key Color")
            alias("Blood Color", "Blood Key Color")
            skip(NO_EQUIVALENT, "Announce Key Spawn", "Door Highlight Color", "Openable Door Color", "Depth check")
        }
        odinClient("World Scanner", "World Scanner")
        odinClient("Auto Clicker", "Auto Clicker")
    }

    // endregion

    // region runtime

    private class Module(val source: Source, val name: String, val enabled: Boolean, val settings: JsonObject)

    /** NoammAddons' saved config: per feature, its on/off state and the settings it saved. */
    private class NaConfig(val enabled: Map<String, Boolean>, val settings: Map<String, JsonObject>)

    private class Report {
        val sb = StringBuilder()
        fun line(text: String) = sb.appendLine(text)
    }

    private class Ctx(val module: Module, val feature: Feature, val na: NaConfig?, val report: Report, val gate: Set<String>) {
        val settings get() = module.settings
        var changed = 0

        /**
         * Conflict policy (NoammAddons was imported first): an Odin value is applied only if the setting was not in
         * NoammAddons' config for this feature (it came with the Odin merge), or if the Odin module was enabled while
         * the NoammAddons feature was disabled. A NoammAddons re-import ([na] == null) always applies.
         */
        fun allowed(setting: ConfigHolder<*>): Boolean {
            if (module.source == Source.NA) return true
            val na = na ?: return true
            val naSettings = na.settings[feature.jsonName] ?: return true
            if (! naSettings.has(setting.jsonName)) return true
            return module.enabled && na.enabled[feature.jsonName] == false
        }
    }

    private class ConversionException(message: String): Exception(message)

    private fun fail(reason: String): Nothing = throw ConversionException(reason)

    /** Called from [com.laybalt.init.ClassGraphInitializer] after [ConfigManager.load] and [LegacyConfigImport.run]. */
    fun runAfterConfigLoad() {
        try {
            val report = Report()
            val counts = linkedMapOf<Source, Int>()

            val naMarker = ForeignImportMarker.source("na")
            if (naMarker?.get("reportPending")?.asBoolean == true) {
                val features = reportNaCopy(report, naMarker)
                counts[Source.NA] = features
                ForeignImportMarker.update { root ->
                    root.getAsJsonObject("na")?.apply {
                        remove("reportPending")
                        addProperty("features", features)
                    }
                }
            }

            val pending = listOf(Source.ODIN, Source.ODINCLIENT).filter { ! ForeignImportMarker.isDone(it.key) && isAvailable(it) }
            if (pending.isNotEmpty()) counts.putAll(importOdin(pending, report))

            if (counts.isNotEmpty()) {
                if (pending.isNotEmpty()) ConfigManager.save()
                writeReport(report, "automatic import on launch")
                ForeignImportMarker.update { it.addProperty("notice", noticeText(counts)) }
            }
        }
        catch (e: Throwable) {
            logger.warn("[ForeignConfigImport] import failed, skipping", e)
        }
        scheduleNotice()
    }

    private fun noticeText(counts: Map<Source, Int>): String {
        val parts = counts.map { (source, n) -> "${source.label} ($n ${if (source == Source.NA) "features" else "modules"})" }
        return "Imported settings from ${parts.joinToString(", ")}. Details: $REPORT_PATH"
    }

    private var noticeListener: EventListener<WorldChangeEvent>? = null
    private val noticeSent = AtomicBoolean(false)

    /** Sends the pending import notice once, on the first world join. */
    private fun scheduleNotice() {
        val notice = runCatching { ForeignImportMarker.read().get("notice")?.asString }.getOrNull() ?: return
        if (noticeListener != null) return
        noticeSent.set(false)
        noticeListener = EventBus.register<WorldChangeEvent> {
            if (LayBaltQOL.mc.level == null || noticeSent.get()) return@register
            ThreadUtils.scheduledTask(40) {
                if (LayBaltQOL.mc.player == null || ! noticeSent.compareAndSet(false, true)) return@scheduledTask
                ChatUtils.modMessage("§a$notice")
                ForeignImportMarker.update { it.remove("notice") }
                noticeListener?.unregister()
                noticeListener = null
            }
        }
    }

    // region NoammAddons

    private fun naConfigFile(): File? {
        val dir = NoammAddonsFileImport.naDir
        val selected = runCatching { JsonParser.parseString(File(dir, "currentConfig.json").readText()).asString }.getOrNull() ?: "default"
        val named = File(dir, "configs/$selected.json").takeIf { selected != "default" && it.isFile }
        return named ?: File(dir, "config.json").takeIf { it.isFile }
    }

    private fun readNaRoot(report: Report?): JsonObject? {
        val file = naConfigFile() ?: return null
        return runCatching { JsonParser.parseString(file.readText()).asJsonObject }
            .onFailure { report?.line("  ! ${file.name} is malformed: ${it.message}") }
            .getOrNull()
    }

    private fun readNaConfig(): NaConfig? {
        val root = readNaRoot(null) ?: return null
        val enabled = HashMap<String, Boolean>()
        val settings = HashMap<String, JsonObject>()
        root.getAsJsonArray("config")?.forEach { el ->
            val obj = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val name = obj.get("name")?.asString ?: return@forEach
            enabled[name] = obj.get("enabled")?.asBoolean ?: false
            settings[name] = obj.getAsJsonObject("configSettings") ?: JsonObject()
        }
        return NaConfig(enabled, settings)
    }

    /** Reports what the pre-launch copy did. @return the number of NoammAddons features LayBaltQOL took over. */
    private fun reportNaCopy(report: Report, marker: JsonObject): Int {
        report.line("[NoammAddons] files copied from config/NoammAddons to config/${LayBaltQOL.CONFIG_DIR} before the config loaded:")
        marker.getAsJsonArray("copied")?.forEach { report.line("  + ${it.asString}") }
        marker.getAsJsonArray("skipped")?.forEach { report.line("  - ${it.asString}") }

        val root = readNaRoot(report) ?: return 0.also { report.line("  ! no NoammAddons config.json found") }
        var kept = 0
        val features = root.getAsJsonArray("config")?.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }.orEmpty()
        report.line("[NoammAddons] features (feature and setting names are the same in LayBaltQOL):")
        for (obj in features) {
            val name = obj.get("name")?.asString ?: continue
            val on = obj.get("enabled")?.asBoolean == true
            val feature = FeatureManager.getFeatureByName(name)
            if (feature == null) report.line("  - $name: skipped (not part of LayBaltQOL; ignored by the loader)")
            else {
                kept ++
                val unknown = obj.getAsJsonObject("configSettings")?.keySet()?.filter { feature.getSettingByName(it) == null }.orEmpty()
                report.line("  + $name (${if (on) "on" else "off"}${if (feature.wip) ", WIP: stays off" else ""})" +
                    if (unknown.isEmpty()) "" else "; settings not in LayBaltQOL, ignored: ${unknown.joinToString()}")
            }
        }
        return kept
    }

    /** `/lbq import na`: applies NoammAddons' config value by value instead of copying files. */
    private fun reimportNa(report: Report): Int {
        val root = readNaRoot(report) ?: return 0.also { report.line("[NoammAddons] no config found in config/NoammAddons") }
        report.line("[NoammAddons] re-import of ${naConfigFile()?.name}: values are applied setting by setting (features are only switched on, never off)")
        var count = 0
        for (el in root.getAsJsonArray("config") ?: JsonArray()) {
            val obj = el.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val name = obj.get("name")?.asString ?: continue
            val feature = FeatureManager.getFeatureByName(name) ?: run { report.line("  - $name: skipped (not part of LayBaltQOL)"); null } ?: continue
            val module = Module(Source.NA, name, obj.get("enabled")?.asBoolean == true, obj.getAsJsonObject("configSettings") ?: JsonObject())
            report.line("  [NoammAddons] $name (${if (module.enabled) "on" else "off"})")
            val ctx = Ctx(module, feature, null, report, emptySet())
            applyEnabled(ctx, true)
            for ((key, value) in module.settings.entrySet()) set(ctx, key, key, value)
            count ++
        }

        // personal bests and slot binds are merged, never overwritten
        runCatching { JsonParser.parseString(File(NoammAddonsFileImport.naDir, "personal_bests.json").readText()).asJsonObject }.getOrNull()?.let { pbs ->
            var n = 0
            for ((key, value) in pbs.entrySet()) if (runCatching { PersonalBest.importTimePB(key, value.asDouble) }.getOrDefault(false)) n ++
            report.line("  personal bests: $n improved (a stored time is only replaced by a better positive one)")
        }
        runCatching { JsonParser.parseString(File(NoammAddonsFileImport.naDir, "slotbindings.json").readText()).asJsonObject }.getOrNull()?.let { binds ->
            val pairs = binds.entrySet().mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to v.asInt } }.toMap()
            report.line("  slot binds (Profile 1): ${SlotBinding.importBinds(0, pairs)} added (existing binds kept)")
        }
        return count
    }

    // endregion

    // region Odin / OdinClient

    private fun odinFile() = File(configDir, "odin/odin-config.json")

    private fun odinClientFile(): File? = File(configDir, "odin/addons").listFiles()
        ?.firstOrNull { it.isFile && it.name.lowercase().removeSuffix(".json") == "odinclient" }

    private fun odinClientDir(): File? = configDir.listFiles()?.firstOrNull { it.isDirectory && it.name.equals("odinclient", true) }

    private fun isAvailable(source: Source) = when (source) {
        Source.ODIN -> odinFile().isFile
        Source.ODINCLIENT -> odinClientFile() != null || odinClientDir() != null
        Source.NA -> NoammAddonsFileImport.naDir.isDirectory
    }

    private fun readModules(file: File, source: Source, report: Report): List<Module>? {
        val array = runCatching { JsonParser.parseString(file.readText()).asJsonArray }
            .onFailure { report.line("[${source.label}] ! ${file.path} is malformed, skipped: ${it.message}") }
            .getOrNull() ?: return null
        return array.mapNotNull { el ->
            val obj = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val name = obj.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
            val enabled = runCatching { obj.get("enabled")?.asBoolean }.getOrNull() ?: false
            Module(source, name, enabled, obj.get("settings")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject())
        }
    }

    private fun importOdin(sources: List<Source>, report: Report): Map<Source, Int> {
        val na = readNaConfig()
        report.line("[Odin/OdinClient] conflict policy: NoammAddons was imported first. An Odin value is applied only if the setting was not in")
        report.line("  NoammAddons' config for that feature, or if the Odin module was on while the NoammAddons feature was off.")
        report.line("  A feature is switched on if the NoammAddons feature or the Odin module was on (WIP features never).")
        if (na == null) report.line("  (no NoammAddons config found, so every matching Odin value is applied)")

        val modules = mutableListOf<Module>()
        val counts = linkedMapOf<Source, Int>()
        for (source in sources) {
            counts[source] = 0
            val file = if (source == Source.ODIN) odinFile() else odinClientFile()
            if (file == null || ! file.isFile) {
                report.line("[${source.label}] no module config file found")
                continue
            }
            report.line("[${source.label}] reading ${file.path}")
            readModules(file, source, report)?.let(modules::addAll)
        }

        // Modules that were off go first, so an enabled module's values win when two modules feed the same setting.
        for (module in modules.sortedBy { it.enabled }) {
            val spec = specs.firstOrNull { it.source == module.source && it.name.equals(module.name, true) }
            if (spec == null) {
                val reason = unsupported[module.source]?.get(module.name.lowercase()) ?: NO_EQUIVALENT
                report.line("[${module.source.label}] ${module.name} (${if (module.enabled) "on" else "off"}): skipped ($reason)")
                continue
            }
            if (importModule(module, spec, na, report)) counts[module.source] = (counts[module.source] ?: 0) + 1
        }

        if (Source.ODINCLIENT in sources) importOdinClientStores(modules, na, report)

        val now = Instant.now().toString()
        ForeignImportMarker.update { root ->
            for (source in sources) root.add(source.key, JsonObject().apply {
                addProperty("done", true)
                addProperty("at", now)
                addProperty("modules", counts[source] ?: 0)
            })
        }
        return counts
    }

    /** OdinClient's JsonStore files (config/OdinClient/features/autoClicker.json): the Auto Clicker whitelists. */
    private fun importOdinClientStores(modules: List<Module>, na: NaConfig?, report: Report) {
        val dir = odinClientDir() ?: return
        val file = File(dir, "features/autoClicker.json").takeIf { it.isFile } ?: return
        val data = runCatching { JsonParser.parseString(file.readText()).asJsonObject.getAsJsonObject("@odinClient:data") }
            .onFailure { report.line("[OdinClient] ! ${file.path} is malformed: ${it.message}") }
            .getOrNull() ?: return
        val feature = findFeature(listOf("Auto Clicker")) ?: return
        val enabled = modules.firstOrNull { it.source == Source.ODINCLIENT && it.name.equals("Auto Clicker", true) }?.enabled ?: false
        report.line("[OdinClient] ${file.path} -> ${feature.name}")
        val ctx = Ctx(Module(Source.ODINCLIENT, "Auto Clicker", enabled, data), feature, na, report, emptySet())
        data.get("leftWhitelist")?.let { set(ctx, "leftWhitelist", "Left Whitelist", it) }
        data.get("rightWhitelist")?.let { set(ctx, "rightWhitelist", "Right Whitelist", it) }
    }

    private fun importModule(module: Module, spec: Spec, na: NaConfig?, report: Report): Boolean {
        val label = "[${module.source.label}] ${module.name} (${if (module.enabled) "on" else "off"})"
        val feature = findFeature(spec.features) ?: return false.also { report.line("$label: skipped (feature ${spec.features.first()} not in this build)") }
        report.line("$label -> ${feature.name}")
        if (feature.wip) return false.also { report.line("    - skipped: ${feature.name} is work in progress") }

        val ctx = Ctx(module, feature, na, report, spec.gate)
        applyEnabled(ctx, spec.enableFeature)
        for (switch in spec.switches) set(ctx, "(module on/off)", switch, JsonPrimitive(module.enabled))

        for ((key, value) in module.settings.entrySet()) {
            val rule = spec.rules[key] ?: spec.rules.entries.firstOrNull { it.key.equals(key, true) }?.value
            when {
                rule is Skip -> report.line("    - $key: skipped (${rule.reason})")
                rule is Custom -> runCatching { rule.block(ctx, value) }.onFailure { report.line("    ! $key: failed (${it.message})") }
                rule is To -> set(ctx, key, rule.target, value, rule.invert)
                key == "Keybind" -> if (value.isJsonPrimitive && value.asString != "key.keyboard.unknown")
                    report.line("    - Keybind: skipped (module toggle keys are not supported)")

                key.contains("HOVER HERE", true) -> Unit
                else -> set(ctx, key, key, value)
            }
        }
        return true
    }

    /** Switches the feature on when the source had it on ("NA or Odin" rule); never switches anything off. */
    private fun applyEnabled(ctx: Ctx, enableFeature: Boolean) {
        if (! ctx.module.enabled || ! enableFeature) return
        val feature = ctx.feature
        when {
            feature.wip -> ctx.report.line("    - not enabled: ${feature.name} is work in progress")
            feature.enabled -> ctx.report.line("    = ${feature.name} already on")
            else -> {
                feature.toggle()
                ctx.report.line(if (feature.enabled) "    + switched ${feature.name} on" else "    ! could not switch ${feature.name} on")
            }
        }
    }

    // endregion

    // region special data

    private fun importPBs(c: Ctx, value: JsonElement, keyOf: (String) -> String) {
        val obj = value.takeIf { it.isJsonObject }?.asJsonObject ?: return
        for ((name, time) in obj.entrySet()) {
            val key = keyOf(name)
            val seconds = runCatching { time.asDouble }.getOrNull()
            when {
                seconds == null || seconds <= 0.0 -> c.report.line("    - PB $key: skipped (invalid value $time)")
                PersonalBest.importTimePB(key, seconds) -> c.report.line("    + PB $key = $seconds")
                else -> c.report.line("    = PB $key: kept ${PersonalBest.getPB(key)} (better than $seconds)")
            }
        }
    }

    /** Odin positional messages with their "#RRGGBBAA" colours turned into LayBaltQOL's ARGB ints. */
    private fun odinPosMessages(raw: JsonElement): JsonElement {
        if (! raw.isJsonArray) fail("expected a list, got $raw")
        val out = JsonArray()
        for (el in raw.asJsonArray) {
            val obj = el.takeIf { it.isJsonObject }?.asJsonObject?.deepCopy() ?: continue
            obj.get("color")?.let { obj.addProperty("color", runCatching { color(it) }.getOrDefault(-1)) }
            out.add(obj)
        }
        return out
    }

    private val terminalNames = mapOf(
        "SELECT" to "Colors", "PANES" to "Red Green", "STARTS_WITH" to "Starts With",
        "RUBIX" to "Rubix", "NUMBERS" to "Numbers", "MELODY" to "Melody"
    )

    private fun importTerminalPBs(c: Ctx, value: JsonElement, simulator: Boolean) {
        val obj = value.takeIf { it.isJsonObject }?.asJsonObject ?: return
        for ((type, time) in obj.entrySet()) {
            val name = terminalNames[type.uppercase()] ?: run { c.report.line("    - terminal PB $type: unknown terminal type"); null } ?: continue
            val key = if (simulator) TerminalPBs.simKey(name) else name
            val seconds = runCatching { time.asDouble }.getOrNull()
            when {
                seconds == null || seconds <= 0.0 -> c.report.line("    - terminal PB $key: skipped (invalid value $time)")
                TerminalPBs.importPB(key, seconds) -> c.report.line("    + terminal PB $key = $seconds")
                else -> c.report.line("    = terminal PB $key: kept ${TerminalPBs.get(key)} (better than $seconds)")
            }
        }
    }

    private fun importSlotBinds(c: Ctx, value: JsonElement) {
        val profiles = value.takeIf { it.isJsonObject }?.asJsonObject ?: return
        for ((profile, binds) in profiles.entrySet()) {
            val index = Regex("\\d+").find(profile)?.value?.toIntOrNull()?.minus(1)
            if (index == null || index !in 0 .. 5 || ! binds.isJsonObject) {
                c.report.line("    - slot binds \"$profile\": skipped (unknown profile)")
                continue
            }
            val pairs = binds.asJsonObject.entrySet().mapNotNull { (k, v) -> k.toIntOrNull()?.let { slot -> runCatching { slot to v.asInt }.getOrNull() } }.toMap()
            val added = SlotBinding.importBinds(index, pairs)
            c.report.line("    + slot binds \"$profile\": $added added (NoammAddons binds on the same slot kept)")
        }
    }

    private fun importKuudraTracker(c: Ctx) {
        fun map(name: String) = c.settings.getAsJsonObject(name)?.entrySet()?.mapNotNull { (k, v) -> runCatching { k to v.asInt }.getOrNull() }?.toMap().orEmpty()
        fun int(name: String) = runCatching { c.settings.get(name).asInt }.getOrDefault(0)
        val keys = runCatching { c.settings.getAsJsonArray("Total Keys").map { it.asInt } }.getOrDefault(emptyList())
        val ok = KuudraTracker.importTotals(map("Single Items"), map("Multi Items"), int("Paid Chests"), int("Free Chests"), keys)
        c.report.line(if (ok) "    + Kuudra Tracker totals imported" else "    - Kuudra Tracker totals: skipped (Odin has none, or LayBaltQOL already tracked chests)")
    }

    // endregion

    // region setting matching and conversion

    private fun findFeature(names: List<String>): Feature? {
        val wanted = names.map(::normalize)
        return FeatureManager.features.firstOrNull { it.jsonName in names || it.name in names }
            ?: FeatureManager.features.firstOrNull { normalize(it.jsonName) in wanted || normalize(it.name) in wanted || normalize(it::class.simpleName.orEmpty()) in wanted }
    }

    private sealed interface Lookup
    private class Found(val setting: ConfigHolder<*>): Lookup
    private object Ambiguous: Lookup
    private object Missing: Lookup

    /** Exact json name, then exact name, then normalised name; `@section:X` is the first setting of section X. */
    private fun findSetting(feature: Feature, name: String): Lookup {
        val savable = feature.configSettings.filter { it is Savable }
        if (name.startsWith("@section:")) {
            val section = name.removePrefix("@section:")
            return savable.firstOrNull { it.section == section }?.let(::Found) ?: Missing
        }
        val stages = listOf<(ConfigHolder<*>) -> Boolean>(
            { it.jsonName == name },
            { it.name == name },
            { normalize(it.jsonName) == normalize(name) || normalize(it.name) == normalize(name) },
        )
        for (stage in stages) {
            val matches = savable.filter(stage)
            if (matches.size == 1) return Found(matches.first())
            if (matches.size > 1) return Ambiguous
        }
        return Missing
    }

    /**
     * Applies one value: finds the LayBaltQOL setting ([target], optionally `Setting >> checkbox key`), checks the
     * conflict policy, converts the value to the setting's type and reads it in. Every outcome goes to the report.
     */
    private fun set(c: Ctx, sourceName: String, target: String, raw: JsonElement, invert: Boolean = false) {
        val settingName = target.substringBefore(" >> ")
        val subKey = target.substringAfter(" >> ", "").ifEmpty { null }
        val shown = if (sourceName == target) sourceName else "$sourceName -> $target"

        val setting = when (val lookup = findSetting(c.feature, settingName)) {
            is Found -> lookup.setting
            Ambiguous -> {
                c.report.line("    - $shown: skipped (several LayBaltQOL settings match)")
                return
            }

            Missing -> {
                c.report.line("    - $sourceName: skipped (${if (isHud(raw)) HUD else "no matching LayBaltQOL setting"})")
                return
            }
        }

        if (! c.allowed(setting)) {
            c.report.line("    = $shown: kept NoammAddons value (setting existed in NoammAddons and the Odin module was not used instead)")
            return
        }

        val native = try {
            var v = toNative(setting, raw, subKey, c.module.source)
            if (invert) v = JsonPrimitive(! v.asBoolean)
            if (sourceName in c.gate && ! c.module.enabled && v.isJsonPrimitive && v.asJsonPrimitive.isBoolean && v.asBoolean) v = JsonPrimitive(false)
            v
        }
        catch (e: ConversionException) {
            c.report.line("    - $shown: skipped (${e.message})")
            return
        }
        catch (e: Exception) {
            c.report.line("    - $shown: skipped (cannot convert $raw: ${e.message})")
            return
        }

        val savable = setting as Savable
        val before = savable.write()
        try {
            if (subKey != null) {
                val obj = before.deepCopy().asJsonObject
                obj.add(subKey, native)
                savable.read(obj)
            }
            else savable.read(native)
        }
        catch (e: Exception) {
            runCatching { savable.read(before) }
            c.report.line("    ! $shown: could not apply $native (${e.message})")
            return
        }
        val after = savable.write()
        if (after == before) c.report.line("    = $shown: unchanged")
        else {
            c.changed ++
            c.report.line("    + $shown = ${if (subKey != null) after.asJsonObject.get(subKey) else after}")
        }
    }

    private fun isHud(raw: JsonElement) = raw.isJsonObject && raw.asJsonObject.let { it.has("x") && it.has("y") && it.has("scale") }

    private fun toNative(setting: ConfigHolder<*>, raw: JsonElement, subKey: String?, source: Source): JsonElement {
        if (isHud(raw)) {
            val supportsFlag = setting is ToggleSetting || (setting is MultiCheckboxSetting && subKey != null)
            if (! supportsFlag) fail(HUD)
            return JsonPrimitive(raw.asJsonObject.get("enabled")?.asBoolean ?: false)
        }
        if (subKey != null) {
            if (setting !is MultiCheckboxSetting) fail("not a checkbox list")
            if (subKey !in setting.value) fail("no option \"$subKey\"")
            return JsonPrimitive(bool(raw))
        }
        return when (setting) {
            is ToggleSetting -> JsonPrimitive(bool(raw))
            is SliderSetting<*> -> JsonPrimitive(number(raw))
            is DropdownSetting -> JsonPrimitive(dropdownIndex(setting.options, raw))
            is ColorSetting -> JsonPrimitive(color(raw))
            is TextInputSetting -> JsonPrimitive(string(raw))
            is KeybindSetting -> keybind(raw)
            is ListSetting<*> -> mergeList(setting, raw)
            is MultiCheckboxSetting -> mergeChecks(setting, raw)
            is ColorCodeSetting -> JsonPrimitive(string(raw))
            is SoundSetting -> JsonPrimitive(string(raw).let { if (':' in it) it else "minecraft:$it" })
            is MapSetting<*, *> -> if (source == Source.NA && raw.isJsonObject) raw else fail("map data is not converted")
            else -> fail("unsupported setting type ${setting::class.simpleName}")
        }
    }

    private fun primitive(raw: JsonElement) = raw.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: fail("expected a single value, got $raw")

    private fun bool(raw: JsonElement): Boolean {
        val p = primitive(raw)
        return when {
            p.isBoolean -> p.asBoolean
            p.isString && p.asString.equals("true", true) -> true
            p.isString && p.asString.equals("false", true) -> false
            else -> fail("expected on/off, got $raw")
        }
    }

    private fun number(raw: JsonElement): Number {
        val p = primitive(raw)
        if (p.isNumber) return p.asDouble
        if (p.isString) p.asString.toDoubleOrNull()?.let { return it }
        fail("expected a number, got $raw")
    }

    private fun string(raw: JsonElement): String {
        val p = primitive(raw)
        return p.asString
    }

    /** Index or option label; labels compare case-insensitively with "_", spaces and punctuation ignored. */
    private fun dropdownIndex(options: List<String>, raw: JsonElement): Int {
        val p = primitive(raw)
        if (p.isNumber) return p.asInt.takeIf { it in options.indices } ?: fail("option index ${p.asInt} out of range")
        if (! p.isString) fail("expected an option, got $raw")
        val wanted = normalize(p.asString)
        if (wanted.isEmpty()) fail("empty option")
        val opts = options.map(::normalize)
        opts.indexOf(wanted).takeIf { it >= 0 }?.let { return it }
        fun stem(s: String) = s.removeSuffix("ed").removeSuffix("s")
        opts.indexOfFirst { stem(it) == stem(wanted) }.takeIf { it >= 0 }?.let { return it }
        opts.indices.filter { opts[it].contains(wanted) || wanted.contains(opts[it]) }.singleOrNull()?.let { return it }
        fail("no option matching \"${p.asString}\" (options: ${options.joinToString()})")
    }

    /** Odin "#RRGGBBAA" (alpha last), "#RRGGBB" or an ARGB int. */
    private fun color(raw: JsonElement): Int {
        val p = primitive(raw)
        if (p.isNumber) return p.asLong.toInt()
        val hex = p.asString.trim().removePrefix("#")
        val v = hex.toLongOrNull(16) ?: fail("not a colour: $raw")
        return when (hex.length) {
            8 -> (((v and 0xFF) shl 24) or (v ushr 8)).toInt()
            6 -> (0xFF000000L or v).toInt()
            else -> fail("not a colour: $raw")
        }
    }

    /** Odin "key.keyboard.x" / "key.mouse.x" names ("key.keyboard.unknown" = unbound), a key code, or the native object. */
    private fun keybind(raw: JsonElement): JsonElement {
        if (raw.isJsonObject) {
            val obj = raw.asJsonObject
            val key = obj.get("key")?.asInt ?: fail("keybind without key")
            return keyObject(key, obj.get("scan")?.asInt ?: 0, obj.get("isMouse")?.asBoolean ?: false)
        }
        val p = primitive(raw)
        if (p.isNumber) return if (p.asInt > 0) keyObject(p.asInt, 0, false) else keyObject(InputConstants.UNKNOWN.value, 0, false)
        val name = p.asString.trim()
        if (name.isEmpty() || name == "key.keyboard.unknown") return keyObject(InputConstants.UNKNOWN.value, 0, false)
        val key = runCatching { InputConstants.getKey(name) }.getOrNull() ?: fail("unknown key \"$name\"")
        if (key == InputConstants.UNKNOWN) return keyObject(InputConstants.UNKNOWN.value, 0, false)
        return keyObject(key.value, 0, key.type == InputConstants.Type.MOUSE)
    }

    private fun keyObject(key: Int, scan: Int, mouse: Boolean) = JsonObject().apply {
        addProperty("key", key)
        addProperty("scan", scan)
        addProperty("isMouse", mouse)
    }

    /** Lists are merged: the current entries stay and new ones are appended. */
    private fun mergeList(setting: ListSetting<*>, raw: JsonElement): JsonElement {
        if (! raw.isJsonArray) fail("expected a list, got $raw")
        val merged = JsonArray()
        val seen = HashSet<String>()
        fun add(el: JsonElement) {
            val id = if (el.isJsonPrimitive) el.asString.lowercase() else el.toString()
            if (seen.add(id)) merged.add(el)
        }
        runCatching { (setting as Savable).write().asJsonArray.forEach(::add) }
        raw.asJsonArray.forEach(::add)
        return merged
    }

    private fun mergeChecks(setting: MultiCheckboxSetting, raw: JsonElement): JsonElement {
        if (! raw.isJsonObject) fail("expected a set of checkboxes, got $raw")
        val out = JsonObject()
        for ((k, v) in raw.asJsonObject.entrySet()) if (k in setting.value) out.addProperty(k, bool(v))
        if (out.size() == 0) fail("no matching checkboxes")
        return out
    }

    private fun normalize(s: String) = s.lowercase().filter(Char::isLetterOrDigit)

    // endregion

    // region report and command

    private fun writeReport(report: Report, trigger: String) {
        runCatching {
            val file = reportFile
            file.parentFile?.mkdirs()
            val header = buildString {
                appendLine("==== ${Instant.now()} - $trigger ====")
                appendLine("+ imported/changed   = unchanged or kept   - skipped (reason)   ! error")
            }
            file.appendText(header + report.sb.toString() + "\n")
        }.onFailure { logger.warn("[ForeignConfigImport] could not write the import report: ${it.message}") }
    }

    private fun runOnDemand(arg: String) {
        val sources = when (arg.lowercase()) {
            "na", "noammaddons" -> listOf(Source.NA)
            "odin" -> listOf(Source.ODIN)
            "odinclient" -> listOf(Source.ODINCLIENT)
            "all" -> Source.entries.toList()
            else -> return ChatUtils.modMessage("§cUsage: /lbq import <na|odin|odinclient|all>")
        }
        try {
            val report = Report()
            val counts = linkedMapOf<Source, Int>()
            if (Source.NA in sources) {
                if (! isAvailable(Source.NA)) report.line("[NoammAddons] config/NoammAddons not found")
                else {
                    counts[Source.NA] = reimportNa(report)
                    ForeignImportMarker.update { root ->
                        val obj = root.getAsJsonObject("na") ?: JsonObject().also { root.add("na", it) }
                        obj.addProperty("done", true)
                        obj.addProperty("reimportedAt", Instant.now().toString())
                    }
                }
            }
            val odin = sources.filter { it != Source.NA }
            val available = odin.filter(::isAvailable)
            odin.filterNot(::isAvailable).forEach { report.line("[${it.label}] no config found") }
            if (available.isNotEmpty()) counts.putAll(importOdin(available, report))

            ConfigManager.save()
            writeReport(report, "/lbq import $arg")
            if (counts.isEmpty()) ChatUtils.modMessage("§eNothing to import from ${sources.joinToString { it.label }}. Details: $REPORT_PATH")
            else ChatUtils.modMessage("§a${noticeText(counts)}")
        }
        catch (e: Throwable) {
            logger.warn("[ForeignConfigImport] /lbq import $arg failed", e)
            ChatUtils.modMessage("§cImport failed: ${e.message}")
        }
    }

    override fun CommandBuilder.command() {
        setName("lbq", "laybalt")

        literal("import") {
            description("Re-imports settings from another mod: /lbq import <na|odin|odinclient|all>")
            runs { ChatUtils.modMessage("§cUsage: /lbq import <na|odin|odinclient|all>") }

            argument("source", StringArgumentType.word()) {
                suggests { listOf("na", "odin", "odinclient", "all") }
                runs { runOnDemand(StringArgumentType.getString(it, "source")) }
            }
        }
    }

    // endregion
}
