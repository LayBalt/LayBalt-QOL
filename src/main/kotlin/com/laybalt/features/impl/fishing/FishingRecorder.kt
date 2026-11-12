package com.laybalt.features.impl.fishing

//#if CHEAT

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.laybalt.LayBaltQOL
import com.laybalt.event.EventBus
import com.laybalt.event.impl.ChatMessageEvent
import com.laybalt.event.impl.ContainerEvent
import com.laybalt.event.impl.MessageSentEvent
import com.laybalt.event.impl.ShutdownEvent
import com.laybalt.event.impl.TickEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.Feature
import com.laybalt.features.impl.fishing.SeaCreatureTracker.TrackedCreature
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.ChatUtils.unformattedText
import com.laybalt.utils.items.ItemUtils.skyblockId
//#if !RECORDER
import com.laybalt.config.types.ButtonSetting
import com.laybalt.config.types.KeybindSetting
import com.laybalt.event.impl.KeyboardEvent
import com.laybalt.event.impl.MouseClickEvent
import com.laybalt.ui.notification.NotificationManager
import com.mojang.blaze3d.platform.InputConstants
//#endif
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.util.Mth
import net.minecraft.util.Util
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.io.BufferedWriter
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Records how you fish and fight your sea creatures yourself: casts, bites and reels, catches, your creatures' health and
 * how they died, what your bobber is hooked in, every click with its target, the hotbar slot and the items in your
 * hotbar, loadout and wardrobe menu clicks, and your rotation and movement: every tick of a fight, and outside fights
 * whenever your view changed and once a second (see [RotGate]).
 * Each recording is one JSON-lines file in `config/LayBaltQOL/fishing/recordings`; when a recording stops,
 * [BehaviorProfile] learns your timings, aim and per-creature habits from all of them (used by Auto Mob Killer).
 *
 * Chat is only recorded while you fish (the bobber is out or was reeled in moments ago), apart from loadout
 * confirmations and double hook lines, which always are; other players' messages never are.
 *
 * The same file builds the recorder-only jar (`./gradlew jarRecorder`, the `RECORDER` flag) for other players: no
 * settings, Auto Fishing or learning there, and of chat only the two lines [alwaysChat] matches. It saves to
 * `config/LayBaltRecorder/fishing/recordings` and marks its session line `"build": "recorder"`, so such a recording is
 * never learned into your own profile by accident.
 */
//#if RECORDER
//$object FishingRecorder: Feature("Records how you fish and fight your sea creatures yourself.") {
//#else
object FishingRecorder: Feature("Records how you fish and kill sea creatures yourself, so Auto Mob Killer can copy your behaviour.") {
//#endif
    //#if !RECORDER
    private val toggleKey by KeybindSetting("Toggle Key")
        .withDescription("Starts or stops a recording. Works in game only (not while a screen is open). Unbound by default.")
    private val openFolder by ButtonSetting("Open Recordings Folder") { Util.getPlatform().openFile(recordingsDir.apply { mkdirs() }) }
        .withDescription("Opens the folder with your recordings (one .jsonl file per recording). Delete a file there to forget it.")
    private val relearn by ButtonSetting("Relearn From Recordings") { BehaviorProfile.rebuildAsync(report = true) }
        .withDescription("Learns your behaviour again from every recording in the folder.")
    //#endif

    /**
     * The recording format, the session line's `v`. 2: `rot` lines outside fights too (only when the view changed, and a
     * heartbeat; a missing tick was still), each with the client tick `tk`, and `tp` on a teleport.
     */
    const val FORMAT = 2
    /** The session line's `build` in a recording made with the recorder-only jar (absent in this mod's own). */
    const val RECORDER_BUILD = "recorder"

    val recordingsDir get() = File("config/${LayBaltQOL.CONFIG_DIR}/fishing/recordings")

    private const val ENGAGE_TAIL_MS = 3000L
    private const val CHAT_AFTER_REEL_MS = 3000L
    private const val FLUSH_TICKS = 100
    /** The hotbar is compared with the last `hotbar` line this often. */
    private const val HOTBAR_TICKS = 20
    private const val HOTBAR_SLOTS = 9
    private const val HOLD_MIN_GAP_MS = 150L
    /** A rod use click this soon before a cast made it (the cast shows up 50-350 ms after its click). */
    private const val CAST_CLICK_MS = 400L
    /** A hit point this close to the top or bottom of the box is on that face. */
    private const val FACE_EPSILON = 1e-3

    private val trackedMenus = listOf(Regex("""^\(\d+/\d+\) Loadouts?$"""), Regex("""^Wardrobe \(\d+/\d+\)$"""), Regex("""^Pets(?: \(\d+/\d+\))?$"""))
    private val trackedCommands = setOf("loadout", "loadouts", "wardrobe", "wd", "pets", "pet", "equipment", "eq")
    /** Player chat, DMs, channel messages and friend notices all carry a "name: text" colon or a prefix; system catch lines do not. */
    private val playerChat = Regex("""\w: |^(?:Party|Guild|Officer|Co-op|Friend) >|^(?:From|To) """)
    /** Recorded while you are not fishing too: the loadout confirmation, and the double hook line long after the last reel. */
    private val alwaysChat = Regex("""^You equipped .+!$|^It's a Double Hook!""")
    /** Auto Fishing waits for your fights during a recording, but not for creatures you leave alone longer than this. */
    private const val HOLD_MAX_MS = 45_000L

    private var writer: BufferedWriter? = null
    private var file: File? = null
    private var startedAt = 0L
    private var ticks = 0
    private var rotGate = RotGate()

    private var lastSlot = - 1
    /** The item ids of the last `hotbar` line, null before the first. */
    private var lastHotbar: List<String>? = null
    private var lastScreenTitle: String? = null
    /** The bobber was out when the recording started: the tracker may announce it as a cast, but you did not cast it now. */
    private var skipCast = false
    private var lastHooked: Entity? = null
    private var biteLogged = 0L
    private var lastOwnEventAt = 0L

    /** Your last rod use click: it reels or casts, which shows a few ticks later, so its line waits for the cast. */
    private var castClick: JsonObject? = null
    private var castClickAt = 0L
    /** Lines written while [castClick] waits, so the file stays in time order. */
    private val heldLines = mutableListOf<JsonObject>()

    private var catches = 0
    private var ownKills = 0
    private var clicks = 0
    private var lastRightClickAt = 0L

    //#if !RECORDER
    /** While you fight your own creatures in a recording, Auto Fishing waits (and Lock Mouse lets you turn). */
    @JvmStatic
    fun holdsAutoFishing(): Boolean {
        if (! enabled) return false
        val now = System.currentTimeMillis()
        return SeaCreatureTracker.ownAlive.any { now - it.spawnedAt < HOLD_MAX_MS }
    }
    //#endif

    override fun init() {
        //#if !RECORDER
        // a profile from an older learner is relearned once you are in a world (whatever is enabled)
        EventBus.register<WorldChangeEvent> { BehaviorProfile.relearnIfOutdated() }

        // The toggle key must work while the feature is off, so it is registered globally (not feature-scoped).
        EventBus.register<KeyboardEvent.KeyPressed> {
            if (mc.gui.screen() != null || event.action != InputConstants.PRESS) return@register
            if (! toggleKey.matches(event.keyEvent.key, mouse = false)) return@register
            toggle()
            event.isCanceled = true
        }

        EventBus.register<MouseClickEvent> {
            if (mc.gui.screen() != null || event.action != InputConstants.PRESS) return@register
            if (! toggleKey.matches(event.button, mouse = true)) return@register
            toggle()
            event.isCanceled = true
        }
        //#endif

        SeaCreatureTracker.onSpawn += { if (enabled) logSpawn(it) }
        SeaCreatureTracker.onOwnerChange += { c ->
            if (enabled) write("own") {
                addProperty("id", c.id)
                addProperty("own", c.own)
                if (c.parent.isNotEmpty()) addProperty("parent", c.parent)
            }
        }
        SeaCreatureTracker.onHealth += { c, hp ->
            if (enabled && c.own) write("hp") {
                addProperty("id", c.id)
                addProperty("hp", hp)
            }
        }
        SeaCreatureTracker.onDeath += { c ->
            if (enabled) {
                if (c.own) {
                    // one that went out of view (unloaded, far away) was not killed; the line below keeps its cause
                    if (c.deathCause != "gone") ownKills ++
                    lastOwnEventAt = System.currentTimeMillis()
                }
                write("death") {
                    addProperty("id", c.id)
                    addProperty("cause", c.deathCause)
                    addProperty("hp", c.hp)
                }
            }
        }
        SeaCreatureTracker.onCatchMessage += { creature, double ->
            if (enabled) {
                catches ++
                lastOwnEventAt = System.currentTimeMillis()
                write("catch") {
                    addProperty("name", creature.name)
                    addProperty("double", double)
                }
            }
        }
        SeaCreatureTracker.onCast += { if (enabled) logCast() }
        SeaCreatureTracker.onReel += { if (enabled) logReel() }

        register<TickEvent.Start> { onTick() }

        register<ChatMessageEvent> {
            val text = event.unformattedText.trim()
            if (text.isEmpty() || playerChat.containsMatchIn(text) || SeaCreatures.byCatchMessage(text) != null) return@register
            //#if RECORDER
            //$// someone else's recording: only the lines below, as other system lines (party invites, friend
            //$// requests, Party Finder) name other players, and nothing learns from them
            //$if (alwaysChat.containsMatchIn(text)) write("chat") { addProperty("text", text.take(200)) }
            //#else
            val fishing = SeaCreatureTracker.hookOut || System.currentTimeMillis() - SeaCreatureTracker.reelAt <= CHAT_AFTER_REEL_MS
            if (fishing || alwaysChat.containsMatchIn(text)) write("chat") { addProperty("text", text.take(200)) }
            //#endif
        }

        register<MessageSentEvent> {
            val msg = event.message.trim()
            if (! event.isCommand && ! msg.startsWith("/")) return@register
            val command = msg.removePrefix("/").substringBefore(" ").lowercase()
            if (command in trackedCommands) write("cmd") { addProperty("cmd", command) }
        }

        register<ContainerEvent.SlotClick> {
            val title = event.screen.title.unformattedText
            if (trackedMenus.none { it.matches(title) }) return@register
            val stack = event.screen.menu.slots.getOrNull(event.slotId)?.item
            write("menuClick") {
                addProperty("title", title)
                addProperty("slot", event.slotId)
                addProperty("button", event.button)
                addProperty("item", stack?.takeUnless(ItemStack::isEmpty)?.hoverName?.unformattedText?.take(60) ?: "")
            }
        }

        register<WorldChangeEvent> { write("world") }

        // the last buffered lines and the "end" line are not lost when the game closes mid-recording
        EventBus.register<ShutdownEvent> { if (enabled) close() }
    }

    override fun toggle() {
        super.toggle()
        if (mc.player != null && LayBaltQOL.isLoaded && enabled) {
            //#if RECORDER
            //$ChatUtils.modMessage("§7Fishing Recorder §astarted§7. Fish and kill your sea creatures yourself, the way you always do. §f/lbrec§7 stops it.")
            //#else
            ChatUtils.modMessage("§7Fishing Recorder §astarted§7. Fish and kill your sea creatures yourself, the way Auto Mob Killer should do it. 30-40 minutes are enough.")
            if (AutoMobKiller.enabled) ChatUtils.modMessage("§eAuto Mob Killer does nothing while you record.")
            //#endif
        }
    }

    override fun onEnable() {
        if (mc.player == null || mc.level == null) {
            enabled = false
            //#if RECORDER
            //$if (LayBaltQOL.isLoaded) ChatUtils.modMessage("§cJoin a world before starting the $name.")
            //#else
            if (LayBaltQOL.isLoaded) NotificationManager.push(LayBaltQOL.MOD_NAME, "Join a world before starting the $name.")
            //#endif
            return
        }
        if (! open()) {
            enabled = false
            return
        }
        super.onEnable()
    }

    override fun onDisable() {
        super.onDisable()
        val minutes = (System.currentTimeMillis() - startedAt) / 60000.0
        if (close()) {
            //#if RECORDER
            //$ChatUtils.modMessage("§7Fishing Recorder §cstopped§7: %.0f min, %d catches, %d of your creatures killed. Saved as §f%s§7 (§f/lbrec folder§7 opens the folder).".format(minutes, catches, ownKills, file?.name))
            //#else
            ChatUtils.modMessage("§7Fishing Recorder §cstopped§7: %.0f min, %d catches, %d of your creatures killed. Learning from your recordings...".format(minutes, catches, ownKills))
            BehaviorProfile.rebuildAsync(report = true)
            //#endif
        }
    }

    /**
     * The mod's own last rod use (`auto` on clicks, handled in the tick of the mod's click), and whether the cast or reel
     * showing up now came from the mod's use (`auto` on casts and reels: the bobber comes a round trip after the click,
     * so no time limit tells the mod's from yours). None in the recorder-only jar, every rod click is yours.
     */
    //#if RECORDER
    //$private fun lastModRodUse() = 0L
    //$private fun modRodUse(cast: Boolean) = false
    //#else
    private fun lastModRodUse() = AutoFishing.lastRodUseAt
    private fun modRodUse(cast: Boolean) = AutoMobKiller.rodUseIsMods(cast)
    //#endif

    private fun open(): Boolean {
        val dir = recordingsDir
        dir.mkdirs()
        val name = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
        val f = File(dir, "$name.jsonl")
        writer = runCatching { f.bufferedWriter() }.getOrElse {
            ChatUtils.modMessage("§cFishing Recorder: cannot write ${f.path} (${it.message}).")
            return false
        }
        file = f
        startedAt = System.currentTimeMillis()
        ticks = 0
        rotGate = RotGate()
        catches = 0
        ownKills = 0
        clicks = 0
        lastSlot = - 1
        lastHotbar = null
        lastScreenTitle = null
        skipCast = mc.player?.fishing != null
        lastHooked = null
        biteLogged = 0L
        lastOwnEventAt = 0L
        castClick = null
        heldLines.clear()
        write("session") {
            addProperty("v", FORMAT)
            addProperty("mod", LayBaltQOL.MOD_VERSION)
            //#if RECORDER
            //$addProperty("build", RECORDER_BUILD)
            //#endif
            addProperty("start", LocalDateTime.now().toString())
            addProperty("sensitivity", mc.options.sensitivity().get())
            //#if !RECORDER
            addProperty("autoFishing", AutoFishing.enabled)
            //#endif
        }
        mc.player?.let(::logHotbar)
        return true
    }

    private fun close(): Boolean {
        val w = writer ?: return false
        releaseCastClick(cast = false)
        write("end") {
            addProperty("catches", catches)
            addProperty("kills", ownKills)
            addProperty("clicks", clicks)
        }
        runCatching { w.close() }
        writer = null
        return true
    }

    private fun write(type: String, build: JsonObject.() -> Unit = {}) {
        if (writer == null) return
        emit(line(type, build))
    }

    /** A line stamped with the current time; [emit] writes it. */
    private fun line(type: String, build: JsonObject.() -> Unit) = JsonObject().apply {
        addProperty("e", type)
        addProperty("t", System.currentTimeMillis() - startedAt)
        build()
    }

    private fun emit(o: JsonObject) {
        if (castClick != null) heldLines += o else writeLine(o)
    }

    private fun writeLine(o: JsonObject) {
        val w = writer ?: return
        runCatching {
            w.write(o.toString())
            w.newLine()
        }
    }

    /** Writes the waiting rod click, marked `cast` when it made a cast, and the lines held back behind it. */
    private fun releaseCastClick(cast: Boolean) {
        val click = castClick ?: return
        if (cast) click.addProperty("cast", true)
        castClick = null
        writeLine(click)
        heldLines.forEach(::writeLine)
        heldLines.clear()
    }

    private fun onTick() {
        val now = System.currentTimeMillis()
        // a rod click without a cast in time reeled in (or did nothing)
        if (castClick != null && now - castClickAt > CAST_CLICK_MS) releaseCastClick(cast = false)
        val p = mc.player ?: return
        ticks ++

        // bobber (casts and reels come from the tracker)
        val bite = SeaCreatureTracker.biteAt
        if (bite != 0L && bite != biteLogged) {
            biteLogged = bite
            write("bite")
        }
        // what the bobber is hooked in: whether Hypixel sends this at all is for the recordings to tell
        val hook = p.fishing
        val hooked = hook?.hookedIn
        if (hooked !== lastHooked) {
            if (hooked != null) write("hooked") {
                val c = creatureOf(hooked)
                if (c != null) addProperty("id", c.id) else addProperty("entity", BuiltInRegistries.ENTITY_TYPE.getKey(hooked.type).toString())
            }
            // let go while the bobber is still out (a reel lets go anyway)
            else if (hook != null) write("unhooked")
            lastHooked = hooked
        }

        // hotbar slot
        val slot = p.inventory.selectedSlot
        if (slot != lastSlot) {
            lastSlot = slot
            write("slot") {
                addProperty("slot", slot)
                addItem(p.inventory.getItem(slot))
            }
        }
        if (ticks % HOTBAR_TICKS == 0) logHotbar(p)

        // menus
        val title = (mc.gui.screen() as? AbstractContainerScreen<*>)?.title?.unformattedText
        if (title != lastScreenTitle) {
            if (title == null) write("menuClose")
            else write("menu") { addProperty("title", if (trackedMenus.any { it.matches(title) }) title else "other") }
            lastScreenTitle = title
        }

        // rotation, movement and your creatures: every tick of a fight, otherwise when the view changed (and a heartbeat)
        val own = SeaCreatureTracker.ownAlive
        if (own.isNotEmpty()) lastOwnEventAt = now
        val yaw = round2(p.yRot)
        val pitch = round2(p.xRot)
        val position = p.position()
        val rot = rotGate.next(ticks, yaw, pitch, position, fight = now - lastOwnEventAt <= ENGAGE_TAIL_MS)
        if (rot != RotLine.NONE) write("rot") {
            addProperty("tk", ticks)
            if (rot == RotLine.TELEPORT) addProperty("tp", true)
            addProperty("yaw", yaw)
            addProperty("pitch", pitch)
            addPos(position)
            addProperty("keys", keyMask())
            addProperty("hp", round2(p.health))
            if (own.isNotEmpty()) add("c", JsonArray().apply {
                own.forEach { c ->
                    add(JsonArray().apply {
                        add(c.id)
                        val pos = c.position
                        add(round2(pos.x)); add(round2(pos.y)); add(round2(pos.z))
                        // height and width -1: no mob linked, the position is a guess below the nametag
                        val mob = c.mob?.takeUnless { it.isRemoved }
                        add(mob?.let { round2(it.bbHeight) } ?: - 1.0)
                        add(mob?.let { round2(it.bbWidth) } ?: - 1.0)
                    })
                }
            })
        }

        if (ticks % FLUSH_TICKS == 0) runCatching { writer?.flush() }
    }

    private fun logCast() {
        val p = mc.player ?: return
        if (skipCast) {
            skipCast = false
            return
        }
        val now = System.currentTimeMillis()
        releaseCastClick(cast = now - castClickAt <= CAST_CLICK_MS)
        write("cast") {
            addProperty("auto", modRodUse(cast = true))
            addPlayer(p)
        }
    }

    /** The items in your hotbar (the weapons you had at hand, used or not), written when they changed. */
    private fun logHotbar(p: Player) {
        val items = List(HOTBAR_SLOTS) { itemId(p.inventory.getItem(it)) }
        if (items == lastHotbar) return
        lastHotbar = items
        write("hotbar") { add("items", JsonArray().apply { items.forEach { add(it) } }) }
    }

    private fun logReel() {
        val p = mc.player ?: return
        skipCast = false
        write("reel") {
            addProperty("auto", modRodUse(cast = false))
            // where the bobber was reeled in
            SeaCreatureTracker.reelPos?.let { addPos(it) }
            addPlayer(p)
        }
    }

    /** Called from [com.laybalt.mixin.MixinMinecraft] for every attack ([left]) or use click, yours or simulated. */
    @JvmStatic
    fun onClick(left: Boolean) = guarded { click(left, held = false) }

    /** Called from [com.laybalt.mixin.MixinMinecraft] for each use repeat while the use key is held (every 4 ticks). */
    @JvmStatic
    fun onHeldUse() = guarded { click(left = false, held = true) }

    /** The clicks come straight from the game's input handling, outside the event bus: an error there would crash the game. */
    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        }
        catch (e: Exception) {
            LayBaltQOL.logger.error("Fishing Recorder: could not record a click", e)
        }
    }

    private fun click(left: Boolean, held: Boolean) {
        if (! enabled) return
        val p = mc.player ?: return
        val now = System.currentTimeMillis()
        // the real hold repeat is every 4 ticks; when the game gives up on a use it would fire every tick
        if (held && now - lastRightClickAt < HOLD_MIN_GAP_MS) return
        if (! left) lastRightClickAt = now
        clicks ++
        val hit = mc.hitResult
        val stack = p.mainHandItem
        val o = line("click") {
            addProperty("b", if (left) "L" else "R")
            if (held) addProperty("hold", true)
            addProperty("auto", System.currentTimeMillis() - lastModRodUse() < 100)
            addProperty("slot", p.inventory.selectedSlot)
            addItem(stack)
            addProperty("hit", when (hit?.type) {
                HitResult.Type.ENTITY -> "entity"
                HitResult.Type.BLOCK -> "block"
                else -> "air"
            })
            val eye = p.eyePosition
            if (hit is EntityHitResult) {
                val entity = hit.entity
                creatureOf(entity)?.let { addProperty("target", it.id) }
                addProperty("dist", round2(eye.distanceTo(hit.location)))
                // the box the game picks entities by; with the eye inside it the hit point is the eye: no face, no height
                val box = entity.boundingBox.inflate(entity.pickRadius.toDouble())
                if (box.contains(eye)) addProperty("inside", true)
                else {
                    addProperty("ay", round2(((hit.location.y - box.minY) / box.ysize).coerceIn(0.0, 1.0)))
                    addProperty("face", faceOf(hit.location, box))
                    // signed: positive while the crosshair is right of or below the centre of the box
                    val (yaw, pitch) = rotationTo(eye, box.center)
                    addProperty("offYaw", round2(Mth.wrapDegrees(p.yRot - yaw)))
                    addProperty("offPitch", round2(p.xRot - pitch))
                }
            }
            // how far off your crosshair was from your closest creature (right click abilities do not need a hit)
            SeaCreatureTracker.ownAlive.minByOrNull { it.position.distanceToSqr(p.position()) }?.let { c ->
                addProperty("near", c.id)
                addProperty("nearDist", round2(eye.distanceTo(c.position)))
                addProperty("err", round2(angleTo(p.getViewVector(1f), c.position.add(0.0, (c.mob?.bbHeight ?: 1.8f) * 0.6, 0.0).subtract(eye))))
            }
        }
        if (! left && stack.item == Items.FISHING_ROD) {
            // the previous rod click did not cast before this one
            releaseCastClick(cast = false)
            castClick = o
            castClickAt = now
        }
        else emit(o)
    }

    private fun creatureOf(entity: Entity): TrackedCreature? =
        SeaCreatureTracker.creatures.values.firstOrNull { it.mob === entity || it.stand === entity }

    private fun logSpawn(c: TrackedCreature) {
        if (c.own) lastOwnEventAt = System.currentTimeMillis()
        val p = mc.player
        write("spawn") {
            addProperty("id", c.id)
            addProperty("name", c.name)
            addProperty("known", c.creature != null)
            c.creature?.let { addProperty("rarity", it.rarity.name) }
            addProperty("own", c.own)
            if (c.parent.isNotEmpty()) addProperty("parent", c.parent)
            if (c.early) addProperty("early", true)
            // yours from before the tracker stopped, seen again: no spawn now
            if (c.kept) addProperty("kept", true)
            if (c.corrupted) addProperty("corrupted", true)
            addProperty("hp", c.hp)
            addProperty("max", c.maxHp)
            // the share of its max health it appeared with
            if (! c.spawnRatio.isNaN()) addProperty("ratio", Math.round(c.spawnRatio * 1000.0) / 1000.0)
            addPos(c.position)
            p?.let { addProperty("dist", round2(it.position().distanceTo(c.position))) }
            c.mob?.let { addProperty("mob", BuiltInRegistries.ENTITY_TYPE.getKey(it.type).toString()) }
        }
    }

    private fun keyMask(): Int {
        val o = mc.options
        var m = 0
        if (o.keyUp.isDown) m = m or 1
        if (o.keyLeft.isDown) m = m or 2
        if (o.keyDown.isDown) m = m or 4
        if (o.keyRight.isDown) m = m or 8
        if (o.keyJump.isDown) m = m or 16
        if (o.keyShift.isDown) m = m or 32
        if (o.keySprint.isDown) m = m or 64
        if (o.keyAttack.isDown) m = m or 128
        if (o.keyUse.isDown) m = m or 256
        return m
    }

    private fun JsonObject.addPos(v: Vec3) {
        addProperty("x", round2(v.x))
        addProperty("y", round2(v.y))
        addProperty("z", round2(v.z))
    }

    /** Where you stood and looked; `px`, `py`, `pz` because a reel's `x`, `y`, `z` are its bobber's. */
    private fun JsonObject.addPlayer(p: Player) {
        val pos = p.position()
        addProperty("px", round2(pos.x))
        addProperty("py", round2(pos.y))
        addProperty("pz", round2(pos.z))
        addProperty("yaw", round2(p.yRot))
        addProperty("pitch", round2(p.xRot))
    }

    private fun JsonObject.addItem(stack: ItemStack) {
        addProperty("item", itemId(stack))
        if (! stack.isEmpty) addProperty("rod", stack.item == Items.FISHING_ROD)
    }

    /** The SkyBlock id of [stack], the vanilla item's for other items, "" for none. */
    private fun itemId(stack: ItemStack) =
        if (stack.isEmpty) "" else stack.skyblockId.ifEmpty { BuiltInRegistries.ITEM.getKey(stack.item).path.uppercase() }

    /** The face of [box] that [hit] lies on: `top`, `bottom` or `side`. */
    private fun faceOf(hit: Vec3, box: AABB) = when {
        abs(hit.y - box.maxY) < FACE_EPSILON -> "top"
        abs(hit.y - box.minY) < FACE_EPSILON -> "bottom"
        else -> "side"
    }

    /** Yaw and pitch looking from [from] at [to] (Minecraft angle convention). */
    private fun rotationTo(from: Vec3, to: Vec3): Pair<Double, Double> {
        val d = to.subtract(from)
        return Math.toDegrees(atan2(d.z, d.x)) - 90.0 to - Math.toDegrees(atan2(d.y, sqrt(d.x * d.x + d.z * d.z)))
    }

    private fun angleTo(look: Vec3, dir: Vec3): Double {
        val len = dir.length().takeIf { it > 1e-6 } ?: return 0.0
        val cos = (look.dot(dir) / (look.length() * len)).coerceIn(- 1.0, 1.0)
        return Math.toDegrees(acos(cos))
    }

    private fun round2(v: Double) = Math.round(v * 100.0) / 100.0
    private fun round2(v: Float) = round2(v.toDouble())

    /** What a client tick gets from [RotGate]: no `rot` line, a line, or a line marked `"tp": true`. */
    enum class RotLine { NONE, LINE, TELEPORT }

    /**
     * Which client ticks get a `rot` line. Every tick of a fight does; outside fights a tick does when its view differs
     * from the last line's, and a still view gets a heartbeat line (keys, position) every [HEARTBEAT_TICKS]. So a tick
     * without a line had the last line's view, and the change up to the next line happened in that line's own tick (`tk`).
     *
     * A move of more than [TELEPORT_M] in one tick forces a line marked as a teleport, so a warp is not read as a fast
     * turn or walk; it is checked from tick to tick, not from line to line, which would flag sprinting across a heartbeat.
     */
    class RotGate {
        private var written = false
        private var lastTick = 0
        private var lastYaw = 0.0
        private var lastPitch = 0.0
        private var lastPos: Vec3? = null

        /**
         * The line for client tick [tick] (called once every tick, line or not), with the view as it is written; [fight]:
         * every tick gets a line.
         */
        fun next(tick: Int, yaw: Double, pitch: Double, pos: Vec3, fight: Boolean): RotLine {
            val teleport = lastPos?.let { pos.distanceToSqr(it) > TELEPORT_M * TELEPORT_M } ?: false
            lastPos = pos
            val line = fight || teleport || ! written || yaw != lastYaw || pitch != lastPitch || tick - lastTick >= HEARTBEAT_TICKS
            if (! line) return RotLine.NONE
            written = true
            lastTick = tick
            lastYaw = yaw
            lastPitch = pitch
            return if (teleport) RotLine.TELEPORT else RotLine.LINE
        }

        companion object {
            /** Outside fights a still view gets a line this often anyway: one second. */
            const val HEARTBEAT_TICKS = 20
            /** Walking, sprinting and falling (at most 3.92 m a tick) stay within this in one tick; a teleport does not. */
            const val TELEPORT_M = 4.0
        }
    }
}

//#endif
