package com.laybalt.features.impl.fishing

//#if CHEAT

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.laybalt.LayBaltQOL
import com.laybalt.LayBaltQOL.mc
import com.laybalt.event.EventBus
import com.laybalt.event.impl.ShutdownEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.impl.fishing.FishingRecorder.RotLine
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.items.ItemUtils.skyblockId
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3
import java.io.BufferedWriter
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Auto Mob Killer's own trace (its Trace setting, DESIGN-HUMAN §5): the bot's view, creatures, clicks and fishing in the
 * [FishingRecorder] format, one file per session in `config/LayBaltQOL/fishing/traces`. The session line says
 * `"bot": true` and every `rot` line carries the killer's phase (`ph`) and its view motion (`aim`: `move`, `stop` or
 * `still`, with the planned `T` in ms on the tick a move started; `glance` while a look around is out), so the scripts
 * that measure your recordings measure the bot the same way. The learner never reads this folder (and skips `bot`
 * sessions anywhere).
 */
internal object KillerTrace {
    val tracesDir get() = File("config/${LayBaltQOL.CONFIG_DIR}/fishing/traces")

    /** Fights are written every tick, like a recording's: a creature of yours alive, or this long after the last one. */
    private const val ENGAGE_TAIL_MS = 3000L
    private const val FLUSH_TICKS = 100

    private var writer: BufferedWriter? = null
    private var startedAt = 0L
    private var ticks = 0
    private var gate = FishingRecorder.RotGate()
    private var lastOwnAt = 0L
    private var installed = false
    /** The trace file could not be opened. */
    private var failed = false

    /** A trace is being written. */
    val open get() = writer != null

    /** Hooks the tracker's events once (they are written only while a trace is open). */
    fun install() {
        if (installed) return
        installed = true
        SeaCreatureTracker.onSpawn += { c ->
            if (open) {
                if (c.own) lastOwnAt = System.currentTimeMillis()
                write("spawn") {
                    addProperty("id", c.id)
                    addProperty("name", c.name)
                    addProperty("known", c.creature != null)
                    addProperty("own", c.own)
                    if (c.parent.isNotEmpty()) addProperty("parent", c.parent)
                    addProperty("hp", c.hp)
                    addProperty("max", c.maxHp)
                    addPos(c.position)
                    mc.player?.let { addProperty("dist", round2(it.position().distanceTo(c.position))) }
                }
            }
        }
        SeaCreatureTracker.onOwnerChange += { c ->
            if (open) write("own") {
                addProperty("id", c.id)
                addProperty("own", c.own)
                if (c.parent.isNotEmpty()) addProperty("parent", c.parent)
            }
        }
        SeaCreatureTracker.onHealth += { c, hp ->
            if (open && c.own) write("hp") {
                addProperty("id", c.id)
                addProperty("hp", hp)
            }
        }
        SeaCreatureTracker.onDeath += { c ->
            if (open) {
                if (c.own) lastOwnAt = System.currentTimeMillis()
                write("death") {
                    addProperty("id", c.id)
                    addProperty("cause", c.deathCause)
                    addProperty("hp", c.hp)
                }
            }
        }
        SeaCreatureTracker.onCatchMessage += { creature, double ->
            if (open) {
                lastOwnAt = System.currentTimeMillis()
                write("catch") {
                    addProperty("name", creature.name)
                    addProperty("double", double)
                }
            }
        }
        SeaCreatureTracker.onCast += { if (open) rod("cast") }
        SeaCreatureTracker.onReel += {
            if (open) rod("reel") { SeaCreatureTracker.reelPos?.let { addPos(it) } }
        }
        EventBus.register<ShutdownEvent> { close() }
        // out of a world no tick writes or flushes: what is buffered reaches the file now (the trace goes on in the next one)
        EventBus.register<WorldChangeEvent> { runCatching { writer?.flush() } }
    }

    /** Opens a trace when [want] and one can be written (in a world), closes it otherwise. */
    fun update(want: Boolean) {
        if (! want) failed = false
        if (want && writer == null && ! failed && mc.player != null) start()
        else if (! want && writer != null) close()
    }

    private fun start() {
        val dir = tracesDir
        dir.mkdirs()
        val f = File(dir, LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".jsonl")
        writer = runCatching { f.bufferedWriter() }.getOrElse {
            // not tried again every tick (a message each time) until the setting is turned off and on
            failed = true
            ChatUtils.modMessage("§cAuto Mob Killer: cannot write the trace ${f.path} (${it.message}).")
            return
        }
        startedAt = System.currentTimeMillis()
        ticks = 0
        gate = FishingRecorder.RotGate()
        lastOwnAt = 0L
        write("session") {
            addProperty("v", FishingRecorder.FORMAT)
            addProperty("mod", LayBaltQOL.MOD_VERSION)
            addProperty("bot", true)
            addProperty("start", LocalDateTime.now().toString())
            addProperty("sensitivity", mc.options.sensitivity().get())
            addProperty("autoFishing", AutoFishing.enabled)
        }
    }

    fun close() {
        val w = writer ?: return
        write("end")
        runCatching { w.close() }
        writer = null
    }

    /**
     * The tick that just ended (TickEvent.End, so the view is the one this tick sent): its `rot` line with the killer's
     * phase [ph], its view motion [aim] and, when a move started in it, the move's planned [plannedMs]; [fight]: the
     * killer fights, so every tick gets a line. [glance]: where a look around is (`out`, `dwell`, `back`, or `away` after
     * one that did not come back), null without one.
     */
    fun tick(ph: String, aim: String, plannedMs: Double?, fight: Boolean, glance: String?) {
        val p = mc.player ?: return
        val now = System.currentTimeMillis()
        ticks++
        val own = SeaCreatureTracker.ownAlive
        if (own.isNotEmpty()) lastOwnAt = now
        val yaw = round2(p.yRot.toDouble())
        val pitch = round2(p.xRot.toDouble())
        val pos = p.position()
        val line = gate.next(ticks, yaw, pitch, pos, fight || now - lastOwnAt <= ENGAGE_TAIL_MS)
        if (line != RotLine.NONE) write("rot") {
            addProperty("tk", ticks)
            if (line == RotLine.TELEPORT) addProperty("tp", true)
            addProperty("yaw", yaw)
            addProperty("pitch", pitch)
            addPos(pos)
            addProperty("keys", keyMask())
            addProperty("hp", round2(p.health.toDouble()))
            if (own.isNotEmpty()) add("c", JsonArray().apply {
                own.forEach { c ->
                    add(JsonArray().apply {
                        add(c.id)
                        val at = c.position
                        add(round2(at.x)); add(round2(at.y)); add(round2(at.z))
                        val mob = c.mob?.takeUnless { it.isRemoved }
                        add(mob?.let { round2(it.bbHeight.toDouble()) } ?: - 1.0)
                        add(mob?.let { round2(it.bbWidth.toDouble()) } ?: - 1.0)
                    })
                }
            })
            addProperty("ph", ph)
            addProperty("aim", aim)
            plannedMs?.let { addProperty("T", Math.round(it)) }
            glance?.let { addProperty("glance", it) }
        }
        if (ticks % FLUSH_TICKS == 0) runCatching { writer?.flush() }
    }

    /** A click of the killer: [left] attack or use, [hold] a repeat of the use key it holds, [target] the creature meant (-1: none). */
    fun click(p: Player, left: Boolean, hold: Boolean, target: Int) {
        if (! open) return
        write("click") {
            addProperty("b", if (left) "L" else "R")
            if (hold) addProperty("hold", true)
            addProperty("auto", true)
            addProperty("slot", p.inventory.selectedSlot)
            addItem(p.mainHandItem)
            if (target >= 0) addProperty("target", target)
        }
    }

    /** You took a fight over ([kind]: keys, button, slot or mouse). */
    fun takeover(kind: String) {
        if (open) write("takeover") { addProperty("kind", kind) }
    }

    /**
     * The turn back after a fight was planned: [kind] (none, now or late) for a view [deg] off your fishing view, whose
     * cast the bobber flight judged [cast] (same, or the check that failed: mean, fan, short, long; undecided).
     */
    fun backPlanned(kind: String, deg: Double, cast: String) {
        if (open) write("back") {
            addProperty("kind", kind)
            addProperty("deg", round2(deg))
            addProperty("cast", cast)
        }
    }

    /**
     * A move of the killer's looks ([ViewPolicy.Looks]) started: [kind] `look` (at creature [id]) or `return` (to the view
     * the look started from), [deg] degrees long.
     */
    fun look(kind: String, id: Int, deg: Double) {
        if (open) write("look") {
            addProperty("kind", kind)
            if (id >= 0) addProperty("id", id)
            addProperty("deg", round2(deg))
        }
    }

    /** The turn back's move started, [short] degrees short of your fishing view where your usual error was [want]. */
    fun backStarted(want: Double, short: Double) {
        if (open) write("backMove") {
            addProperty("want", round2(want))
            addProperty("short", round2(short))
        }
    }

    private fun rod(type: String, extra: JsonObject.() -> Unit = {}) {
        val p = mc.player ?: return
        write(type) {
            // the mod's cast or reel: Auto Fishing's rod click or the killer's press or hold, whatever the ping
            addProperty("auto", AutoMobKiller.rodUseIsMods(cast = type == "cast"))
            extra()
            val pos = p.position()
            addProperty("px", round2(pos.x))
            addProperty("py", round2(pos.y))
            addProperty("pz", round2(pos.z))
            addProperty("yaw", round2(p.yRot.toDouble()))
            addProperty("pitch", round2(p.xRot.toDouble()))
        }
    }

    private fun write(type: String, build: JsonObject.() -> Unit = {}) {
        val w = writer ?: return
        val o = JsonObject().apply {
            addProperty("e", type)
            addProperty("t", System.currentTimeMillis() - startedAt)
            build()
        }
        runCatching {
            w.write(o.toString())
            w.newLine()
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

    private fun JsonObject.addItem(stack: ItemStack) {
        addProperty("item", if (stack.isEmpty) "" else stack.skyblockId.ifEmpty { BuiltInRegistries.ITEM.getKey(stack.item).path.uppercase() })
        if (! stack.isEmpty) addProperty("rod", stack.item == Items.FISHING_ROD)
    }

    private fun round2(v: Double) = Math.round(v * 100.0) / 100.0
}

//#endif
