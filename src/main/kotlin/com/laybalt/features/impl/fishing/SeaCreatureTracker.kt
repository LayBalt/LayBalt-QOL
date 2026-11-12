package com.laybalt.features.impl.fishing

//#if CHEAT

import com.laybalt.event.EventBus
import com.laybalt.event.impl.ChatMessageEvent
import com.laybalt.event.impl.TickEvent
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.Shortcuts
import com.laybalt.features.impl.fishing.SeaCreatures.SeaCreature
import com.laybalt.init.types.ISelfInit
import com.laybalt.utils.ChatUtils.removeFormatting
import com.laybalt.utils.ChatUtils.unformattedText
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.FishingHook
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.Optional
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Follows the sea creatures around you and tells which of them are your own catches.
 *
 * A creature is yours when its nametag comes up right above your bobber ([ClaimRules.aboveBobber]) shortly after its
 * catch message ("You caught a Sea Walker."). The message arrives while the caught bobber still looks out (the client
 * sees the reel 50-170 ms later), so both that bobber and a reel just before the message are catch spots. A nametag seen
 * up to [LATE_CLAIM_MS] before its message is claimed by it too. Creatures without a catch message are yours when they
 * come up right above the bobber ([TrackedCreature.early]). "It's a Double Hook!" allows two creatures for one catch.
 * A creature that splits off one of yours as it dies (Bayou Sludglings off a Bayou Sludge) is yours too, whatever its
 * name ([ClaimRules.isChild], [TrackedCreature.parent]); it is not a catch.
 * Other players' creatures are tracked too (the recorder logs them), but they are never yours.
 *
 * Only runs while Auto Mob Killer, Auto Fishing (its rod clicks and casts tell your creatures from anyone else's) or the
 * Fishing Recorder is enabled ([ClaimRules.runs]); your creatures still alive when it stops are yours again when it runs
 * again in the same world ([KeptClaims]).
 */
object SeaCreatureTracker: ISelfInit, Shortcuts {
    class TrackedCreature(
        val stand: ArmorStand,
        val creature: SeaCreature?,
        val rawName: String,
        /** A Corrupted variant: [creature] and [name] are the base creature's, so its rule and habit cover both. */
        val corrupted: Boolean,
        own: Boolean,
        val spawnedAt: Long,
        /** Where the nametag was first seen (ownership is decided by where a creature appeared, not where it walked). */
        val spawnPos: Vec3
    ) {
        /**
         * Your catch, or a child of one ([parent]). Can change once: a catch message right after the nametag appeared
         * claims it, or one of your creatures dying right next to it just after it appeared.
         */
        var own = own
            internal set
        /**
         * The name of your creature this one split off as it died ([ClaimRules.isChild]); empty when it is no child. Set
         * when its nametag comes up, or when that death is seen a moment after it (also for an [early] claim).
         */
        var parent = ""
            internal set
        /** How many children ([parent]) were claimed for this creature's death, at most [ClaimRules.CHILD_MAX]. */
        internal var children = 0
        val id get() = stand.id
        val name get() = creature?.name ?: rawName
        var mob: LivingEntity? = null
        var hp = 0.0
        var maxHp = 0.0
        /** hp / max hp when the nametag appeared, NaN when it showed no max then. */
        var spawnRatio = Double.NaN
            internal set
        var deadAt = 0L
        val dead get() = deadAt != 0L
        /**
         * Why it ended, set before [onDeath]: "stand" (the nametag went away), "mob" (its linked mob died), "hp0" (the
         * nametag showed 0 hp) or "gone" (out of view, not a death: unloaded, or farther than [SCAN_RADIUS]).
         */
        var deathCause = ""
            internal set
        /** Its [position] when it died, null while it lives. */
        var deathPos: Vec3? = null
            internal set
        /**
         * Claimed by place alone: its creature has no catch message, and it came up right above your bobber. Cleared when
         * it turns out to be a child ([parent]): no catch after all.
         */
        var early = false
            internal set
        /**
         * Claimed before the tracker last stopped (every feature that needs it was off a while) and seen again since, in
         * this world ([KeptClaims]): yours, but no new catch.
         */
        var kept = false
            internal set

        /** Where to aim: the mob when it is linked, its nametag otherwise. */
        val position: Vec3 get() = mob?.takeUnless { it.isRemoved }?.position() ?: stand.position().subtract(0.0, 1.8, 0.0)
    }

    /** What a nametag names: the [creature] (null when unknown) and whether it is a Corrupted variant. */
    class NameRead(val name: String, val creature: SeaCreature?, val corrupted: Boolean)

    /**
     * Your creatures still alive when the tracker stopped (every feature that needs it turned off), by nametag id: the
     * tracker forgets everything else then, but one of these seen again in the same world (Auto Fishing turned on again
     * with a rare catch still standing there) is yours again, not another player's. At most [max] (the latest), dropped
     * once seen again, all at a world change. No game state, so it is unit tested.
     */
    internal class KeptClaims(private val max: Int = MAX_KEPT) {
        /** A creature of yours as it was claimed: its [name], the creature it split off ([parent]), claimed by place alone ([early]). */
        class Claim(val name: String, val parent: String, val early: Boolean)

        private val claims = LinkedHashMap<Int, Claim>()

        val size get() = claims.size

        fun keep(id: Int, claim: Claim) {
            claims.remove(id)
            claims[id] = claim
            while (claims.size > max) claims.remove(claims.keys.first())
        }

        /** The claim kept for nametag [id] when it still names [name] (null: none), dropped as it is taken. */
        fun take(id: Int, name: String): Claim? {
            val c = claims[id] ?: return null
            claims.remove(id)
            return c.takeIf { it.name == name }
        }

        fun clear() = claims.clear()

        companion object {
            /** More of yours than ever stood at once (a Bayou Sludge's children and a few catches). */
            const val MAX_KEPT = 64
        }
    }

    /** The ownership rules, apart from the game so they can be tested. */
    internal object ClaimRules {
        /**
         * The tracker runs (and claims your creatures from the catch on) while any of these needs them told apart: the
         * [killer], the [rod] clicks and casts of the fishing feature, which never touch another player's creature but
         * may meet yours (it runs on its own too), and the [recorder].
         */
        fun runs(killer: Boolean, rod: Boolean, recorder: Boolean) = killer || rod || recorder

        /** Own nametags came up within 0.45 m of the bobber sideways, a neighbour's 1.13 m and more. */
        private const val COLUMN_RADIUS = 1.0
        /** Own nametags came up 0.81-5.34 m above the bobber. */
        private const val COLUMN_BELOW = 1.0
        private const val COLUMN_ABOVE = 6.5
        /** Claims by place alone use a narrower column. */
        const val EARLY_RADIUS = 0.8

        /** [dx], [dy], [dz] (nametag minus bobber) lie in the column above the bobber, [radius] wide sideways. */
        fun aboveBobber(dx: Double, dy: Double, dz: Double, radius: Double = COLUMN_RADIUS) =
            dx * dx + dz * dz <= radius * radius && dy >= - COLUMN_BELOW && dy <= COLUMN_ABOVE

        /**
         * Where a catch can come up: the bobber while it is out (a catch message comes before the client sees its reel)
         * and the spot of a reel at most [window] ms ago.
         */
        fun catchSpots(hookOut: Boolean, bobber: Vec3?, reel: Vec3?, sinceReel: Long, window: Long): List<Vec3> {
            val spots = ArrayList<Vec3>(2)
            if (hookOut && bobber != null) spots += bobber
            if (reel != null && sinceReel <= window) spots += reel
            return spots
        }

        fun inColumn(pos: Vec3, spots: List<Vec3>, radius: Double = COLUMN_RADIUS) =
            spots.any { aboveBobber(pos.x - it.x, pos.y - it.y, pos.z - it.z, radius) }

        /** Sideways distance to the nearest of [spots], so the nametag closest to a catch wins. */
        fun offset(pos: Vec3, spots: List<Vec3>) = spots.minOfOrNull { hypot(pos.x - it.x, pos.z - it.z) } ?: 0.0

        /** A nametag seen [age] ms before a catch message may still be that catch (a neighbour's came 448 ms early). */
        fun lateClaim(age: Long, dead: Boolean, inColumn: Boolean) = ! dead && age <= LATE_CLAIM_MS && inColumn

        /**
         * A new nametag claimed by place alone: only creatures that have no catch message (own nametags always came
         * after theirs, so one before it is a neighbour's), while no catch waits for nametags.
         */
        fun earlyClaim(creature: SeaCreature?, waiting: Boolean, atSpot: Boolean) = creature != null && creature.catchMessage == null && ! waiting && atSpot

        /**
         * A child's nametag may come up at most this long before its parent's death is seen, a tick or a few early (all 91
         * children in the recordings came in the tick of that death or after; deaths are seen before new nametags).
         */
        const val CHILD_EARLY_MS = 300L
        /** 28 of 28 Bayou Sludglings came up at most this long after their Bayou Sludge died (600 ms caught 9 of them). */
        const val CHILD_MS = 800L
        /** ... and this close (3D) to where it died. */
        const val CHILD_RADIUS = 7.0
        /** Children claimed for one death at most (Sludglings came in batches of up to 3). */
        const val CHILD_MAX = 3

        private val numberName = Regex("""^[\d,.]+$""")

        /**
         * A nametag that came up [msSinceDeath] ms after (negative: before) one of your creatures died, [distance] blocks
         * from where it died, split off it.
         */
        fun isChild(msSinceDeath: Long, distance: Double) = msSinceDeath in - CHILD_EARLY_MS .. CHILD_MS && distance <= CHILD_RADIUS

        /**
         * Only a creature that cannot be caught can be a child (unknown names, Baby Magma Slug), and its nametag must show
         * health and a name that is not just a number: without this check, 121 claims in the recordings were wrong.
         */
        fun childName(name: String, creature: SeaCreature?, maxHp: Double) =
            (creature == null || creature.catchMessage == null) && maxHp > 0.0 && ! numberName.matches(name)

        /**
         * Only your creature that died ([deathCause] empty while it lives; "gone" is out of view, not a death) and is no
         * child itself has children: Bayou Sludglings and Baby Magma Slugs never split, so a dead child taken for a
         * parent would only mislabel its siblings and open more claims for nametags around it. One claimed [early] (a
         * Baby Magma Slug that came up at the bobber) is a child whose parent was not seen, not a parent either.
         */
        fun canParent(own: Boolean, parent: String, deathCause: String, early: Boolean = false) =
            own && ! early && parent.isEmpty() && deathCause.isNotEmpty() && deathCause != "gone"

        /**
         * A tracked creature that a death seen after its nametag came up can still claim as a child: someone else's so
         * far, or one claimed by place alone ([earlyClaim]) without a parent (a Baby Magma Slug right above your bobber
         * a tick before its Magma Slug's death was seen is a child, not a catch). A child keeps the parent it has.
         */
        fun childClaimable(own: Boolean, early: Boolean, parent: String, dead: Boolean) = ! dead && (! own || early && parent.isEmpty())

        /**
         * The death a nametag split off, of [deaths] that [isChild] allows: the nearest in time, then in place; null for
         * none. [msSinceDeath] and [distance] measure the nametag against each death.
         */
        fun <T> parentOf(deaths: Iterable<T>, msSinceDeath: (T) -> Long, distance: (T) -> Double): T? =
            deaths.filter { isChild(msSinceDeath(it), distance(it)) }.minWithOrNull(compareBy<T>({ abs(msSinceDeath(it)) }, { distance(it) }))
    }

    /**
     * Your bobber between ticks, from the hook seen each tick (apart from the game so it can be tested). A new hook id
     * while one is out is a reel and a recast that fell between two ticks.
     */
    internal class HookState {
        enum class Change { NONE, CAST, REEL, RECAST }

        var out = false
            private set
        var id = 0
            private set
        var castAt = 0L
            private set
        var reelAt = 0L
            private set
        var reelPos: Vec3? = null
            private set
        /** Where the bobber was last seen (kept after the reel). */
        var bobberPos: Vec3? = null
            private set

        /** The hook seen this tick: its entity id (null when there is none) and position. */
        fun update(hookId: Int?, pos: Vec3?, now: Long): Change {
            if (hookId == null) {
                if (! out) return Change.NONE
                out = false
                reel(now)
                return Change.REEL
            }
            val change = when {
                ! out -> Change.CAST
                hookId != id -> Change.RECAST
                else -> Change.NONE
            }
            if (change == Change.RECAST) reel(now)
            if (change != Change.NONE) castAt = now
            out = true
            id = hookId
            bobberPos = pos
            return change
        }

        private fun reel(now: Long) {
            reelAt = now
            reelPos = bobberPos
        }

        fun reset() {
            out = false
            reelPos = null
            bobberPos = null
        }
    }

    private class PendingCatch(val creature: SeaCreature, val at: Long, val spots: List<Vec3>, var remaining: Int)

    /** Where and when a nametag was first seen. */
    private class Sighting(val at: Long, val pos: Vec3)

    private class FreshStand(val stand: ArmorStand, val text: String, val read: NameRead, val seen: Sighting) {
        var claimed = false
    }

    private fun hasHeart(name: String) = name.any { it in HEARTS }

    const val SCAN_RADIUS = 32.0
    /** Own nametags came up 6-117 ms after their catch message; a catch waits this long for them. */
    private const val CATCH_WINDOW_MS = 1500L
    /** A catch message also claims a nametag that showed up at most this long before it. */
    private const val LATE_CLAIM_MS = 300L
    /** "It's a Double Hook!" came 9-37 ms before its catch message. */
    private const val DOUBLE_HOOK_WINDOW_MS = 1000L
    /** A double hook line this soon after a catch message belongs to that catch. */
    private const val DOUBLE_HOOK_LATE_MS = 300L
    /** A creature without a catch message may come up this soon after a reel (or while the bobber is out). */
    private const val EARLY_CLAIM_MS = 300L
    /** A nametag with an unknown name is read again while it is this young, its name may still change. */
    private const val UNKNOWN_RETRY_MS = 1000L
    private const val ID_CARRIER_DISTANCE = 4.5
    private const val DEAD_KEEP_MS = 2000L

    /** The health heart: `❤`, or the private-use glyph Hypixel's resource pack draws as one (SkyHanni matches ``). */
    private const val HEARTS = "❤"
    private val healthRegex = Regex("""([\d.,]+[kKmMbB]?)(?:/([\d.,]+[kKmMbB]?))?[$HEARTS]""")
    private val leadingDecoration = Regex("""^[^\p{L}\p{N}\[]+""")
    private val levelPrefix = Regex("""^\[Lv\s*[\d,]+]\s*""")
    private val healthSuffix = Regex("""\s+[\d.,]+[kKmMbB]?(?:/[\d.,]+[kKmMbB]?)?[$HEARTS].*$""")
    private val decoration = Regex("""^[^\p{L}\p{N}]+|[^\p{L}\p{N}')!]+$""")
    /** Also takes an obfuscated glyph glued to the word, as plain text shows it (`aCorrupted Frozen Stevea`). */
    private val corruptedPrefix = Regex("""^\S?Corrupted\s+""")
    private val doubleHook = Regex("""^It's a Double Hook!(?: Woot woot!)?$""")

    /** Every tracked nametag by entity id; dead ones stay [DEAD_KEEP_MS] so listeners can still read them. */
    val creatures = LinkedHashMap<Int, TrackedCreature>()
    private val seenStands = HashSet<Int>()
    /** Untracked nametags with an unknown name that are read again, by entity id. */
    private val unnamed = HashMap<Int, Sighting>()
    private val pending = mutableListOf<PendingCatch>()
    /** Your creatures alive when the tracker last stopped in this world. */
    private val kept = KeptClaims()

    /** A new nametag, after its mob is linked. */
    val onSpawn = mutableListOf<(TrackedCreature) -> Unit>()
    val onDeath = mutableListOf<(TrackedCreature) -> Unit>()
    /** [TrackedCreature.own] or [TrackedCreature.parent] changed after the spawn. */
    val onOwnerChange = mutableListOf<(TrackedCreature) -> Unit>()
    /** Catch message of a known creature, before its nametag appears: (creature, double hook). */
    val onCatchMessage = mutableListOf<(SeaCreature, Boolean) -> Unit>()
    /** A tracked creature's hp changed after its spawn: (creature, new hp). */
    val onHealth = mutableListOf<(TrackedCreature, Double) -> Unit>()
    /** Your bobber went out ([castAt]); a reel and recast between two ticks fires [onReel] first. */
    val onCast = mutableListOf<() -> Unit>()
    /** Your bobber was reeled in at [reelPos] ([lastBobberPos] is already the new cast's when a recast followed). */
    val onReel = mutableListOf<() -> Unit>()

    /** Bobber state, read by the recorder and the killer. */
    private val hook = HookState()
    val hookOut get() = hook.out
    val castAt get() = hook.castAt
    val reelAt get() = hook.reelAt
    val reelPos get() = hook.reelPos
    val lastBobberPos get() = hook.bobberPos
    /** When the "!!!" bite marker above your own bobber appeared (0 while there is none). */
    var biteAt = 0L
        private set
    private var lastDoubleHookAt = 0L

    //#if RECORDER
    //$val active get() = ClaimRules.runs(false, false, FishingRecorder.enabled)
    //#else
    val active get() = ClaimRules.runs(AutoMobKiller.enabled, AutoFishing.enabled, FishingRecorder.enabled)
    //#endif

    val ownAlive get() = creatures.values.filter { it.own && ! it.dead }

    override fun init() {
        EventBus.register<TickEvent.Start> {
            if (! active) return@register stop()
            tick()
        }

        EventBus.register<ChatMessageEvent> {
            if (! active) return@register
            val text = event.unformattedText.trim()
            val now = System.currentTimeMillis()
            if (doubleHook.matches(text)) {
                // the double hook line comes before its catch message; right after one it belongs to that catch
                val late = pending.lastOrNull()?.takeIf { now - it.at < DOUBLE_HOOK_LATE_MS && it.remaining == 1 }
                if (late != null) late.remaining = 2 else lastDoubleHookAt = now
                return@register
            }
            val creature = SeaCreatures.byCatchMessage(text) ?: return@register
            val double = now - lastDoubleHookAt < DOUBLE_HOOK_WINDOW_MS
            // one double hook, one catch
            lastDoubleHookAt = 0L
            // a catch without a recent bobber (its hook was lost between ticks) can only be the last spot
            val spots = ClaimRules.catchSpots(hookOut, lastBobberPos, reelPos, now - reelAt, CATCH_WINDOW_MS).ifEmpty { listOfNotNull(lastBobberPos) }
            var remaining = if (double) 2 else 1
            // nametags that showed up just before this message, measured from where they appeared (they may have
            // walked off since), nearest to a catch spot first
            creatures.values.filter { ! it.own && it.creature === creature && ClaimRules.lateClaim(now - it.spawnedAt, it.dead, ClaimRules.inColumn(it.spawnPos, spots)) }
                .sortedBy { ClaimRules.offset(it.spawnPos, spots) }.take(remaining).forEach { c ->
                    c.own = true
                    remaining --
                    onOwnerChange.forEach { it(c) }
                }
            if (remaining > 0) pending.add(PendingCatch(creature, now, spots, remaining))
            onCatchMessage.forEach { it(creature, double) }
        }

        EventBus.register<WorldChangeEvent> {
            reset()
            kept.clear()
        }
    }

    /** No feature needs the tracker now: your creatures still alive are kept for this world ([KeptClaims]), all else is forgotten. */
    private fun stop() {
        for (c in creatures.values) if (c.own && ! c.dead) kept.keep(c.id, KeptClaims.Claim(c.name, c.parent, c.early))
        reset()
    }

    private fun reset() {
        creatures.clear()
        seenStands.clear()
        unnamed.clear()
        pending.clear()
        hook.reset()
        biteAt = 0L
        lastDoubleHookAt = 0L
    }

    private fun tick() {
        val p = mc.player ?: return
        val world = mc.level ?: return
        val now = System.currentTimeMillis()

        val fishing = p.fishing ?: ownHook(p)
        when (hook.update(fishing?.id, fishing?.position(), now)) {
            HookState.Change.CAST -> cast()
            HookState.Change.REEL -> reel()
            HookState.Change.RECAST -> {
                reel()
                cast()
            }
            HookState.Change.NONE -> Unit
        }
        if (fishing != null) {
            val bite = world.getEntitiesOfClass(ArmorStand::class.java, fishing.boundingBox.inflate(4.0)) {
                it.isInvisible && it.isCustomNameVisible && it.customName?.unformattedText?.trim() == "!!!"
            }.isNotEmpty()
            if (bite && biteAt == 0L) biteAt = now
            else if (! bite) biteAt = 0L
        }

        pending.removeAll { now - it.at > CATCH_WINDOW_MS || it.remaining <= 0 }

        // This tick's deaths come before its new nametags: a child that comes up in the tick its parent dies is claimed for
        // that parent, not for another of yours that died a moment before nor as an early catch.
        val alive = ArrayList<TrackedCreature>(creatures.size)
        val iterator = creatures.values.iterator()
        while (iterator.hasNext()) {
            val c = iterator.next()
            if (c.dead) {
                if (now - c.deadAt > DEAD_KEEP_MS) iterator.remove()
                continue
            }
            if (c.stand.isRemoved || ! c.stand.isAlive) {
                markDead(c, now, if (leftView(c.stand, p)) "gone" else "stand")
                continue
            }
            // checked before relinking: a dying mob no longer counts as carrying its nametag and would be dropped unseen
            // Only the id-neighbour link is sure enough to take its death for the creature's; a guessed link that dies is
            // just dropped (the nametag going away or its health reaching 0 still ends the creature).
            val linked = c.mob
            if (linked != null && linked.isDeadOrDying && linked.id == c.stand.id - 1) {
                markDead(c, now, "mob")
                continue
            }
            c.stand.customName?.let { name -> if (readHealth(c, visibleText(name))) onHealth.forEach { it(c, c.hp) } }
            if (c.hp <= 0.0 && c.maxHp > 0.0) markDead(c, now, "hp0")
            else alive += c
        }

        val stands = world.getEntitiesOfClass(ArmorStand::class.java, p.boundingBox.inflate(SCAN_RADIUS)) {
            it.hasCustomName() && it.customName?.string?.let(::hasHeart) == true
        }

        val fresh = stands.filter { seenStands.add(it.id) }.mapNotNull { stand ->
            val text = stand.customName?.let(::visibleText) ?: return@mapNotNull null
            FreshStand(stand, text, readName(text), unnamed[stand.id] ?: Sighting(now, stand.position()))
        }
        // pending catches take the nearest new nametags of their creature, so a neighbour's catch of the same kind loses
        for (catch in pending) {
            if (catch.remaining <= 0) continue
            fresh.filter { ! it.claimed && it.read.creature === catch.creature && it.seen.at >= catch.at - LATE_CLAIM_MS && ClaimRules.inColumn(it.seen.pos, catch.spots) }
                .sortedBy { ClaimRules.offset(it.seen.pos, catch.spots) }
                .take(catch.remaining).forEach {
                    it.claimed = true
                    catch.remaining --
                }
        }
        val waiting = pending.any { it.remaining > 0 }
        val spots = ClaimRules.catchSpots(hookOut, lastBobberPos, reelPos, now - reelAt, CATCH_WINDOW_MS)
        val earlySpots = ClaimRules.catchSpots(hookOut, lastBobberPos, reelPos, now - reelAt, EARLY_CLAIM_MS)
        // your creatures that died moments ago, this tick's too (dead ones are kept longer than a child may come after them)
        val deaths = creatures.values.filter { ClaimRules.canParent(it.own, it.parent, it.deathCause, it.early) }
        for (f in fresh) {
            val creature = f.read.creature
            val tracked = TrackedCreature(f.stand, creature, f.read.name, f.read.corrupted, f.claimed, f.seen.at, f.seen.pos)
            // one of yours from before the tracker last stopped is yours again, nothing else of it is decided anew
            val claim = kept.take(f.stand.id, tracked.name)
            // a child is placed by its mob and needs its health, so while one of yours just died, a creature that cannot be
            // caught is linked before the checks below
            val linked = claim == null && deaths.isNotEmpty() && creature?.catchMessage == null
            if (linked) link(tracked, f.text)
            val parent = if (linked) parentDeath(tracked, deaths) else null
            // Unknown names are only kept when they split off your creature or came up right above your bobber (the
            // recorder logs them as unknown catches); a young one is read again next tick
            if (claim == null && parent == null && creature == null && ! ClaimRules.inColumn(f.seen.pos, spots)) {
                if (now - f.seen.at < UNKNOWN_RETRY_MS) {
                    unnamed[f.stand.id] = f.seen
                    seenStands.remove(f.stand.id)
                }
                else unnamed.remove(f.stand.id)
                continue
            }
            unnamed.remove(f.stand.id)
            if (claim != null) {
                tracked.own = true
                tracked.parent = claim.parent
                tracked.early = claim.early
                tracked.kept = true
            }
            else if (parent != null) {
                tracked.own = true
                tracked.parent = parent.name
                parent.children ++
            }
            else if (! f.claimed && ClaimRules.earlyClaim(creature, waiting, ClaimRules.inColumn(f.seen.pos, earlySpots, ClaimRules.EARLY_RADIUS))) {
                tracked.own = true
                tracked.early = true
            }
            // linked before the spawn is announced, so listeners get the mob's position right away
            if (! linked) link(tracked, f.text)
            creatures[f.stand.id] = tracked
            onSpawn.forEach { it(tracked) }
        }
        unnamed.values.removeIf { now - it.at >= UNKNOWN_RETRY_MS }

        // relinked after the new nametags took their mobs (the new ones were just linked; a mob dying as its nametag
        // came up is seen next tick, before any relink)
        for (c in alive) c.mob = idCarrier(c.stand) ?: c.mob?.takeIf { stillCarries(it, c.stand) && ! heldByOther(it, c.stand) } ?: linkMob(c.stand)
        seenStands.retainAll { id -> world.getEntity(id) != null }
    }

    private fun cast() {
        biteAt = 0L
        onCast.forEach { it() }
    }

    private fun reel() {
        biteAt = 0L
        onReel.forEach { it() }
    }

    /** Your hook when [Player.fishing] lost it (catches came with no client hook). */
    private fun ownHook(p: Player): FishingHook? =
        mc.level?.getEntitiesOfClass(FishingHook::class.java, p.boundingBox.inflate(SCAN_RADIUS)) { it.playerOwner === p && ! it.isRemoved }?.maxByOrNull { it.id }

    private fun markDead(c: TrackedCreature, now: Long, cause: String) {
        if (c.dead) return
        c.deadAt = now
        c.deathCause = cause
        c.deathPos = c.position
        onDeath.forEach { it(c) }
        if (ClaimRules.canParent(c.own, c.parent, cause, c.early)) claimChildren(c)
    }

    /** Links [c]'s mob and reads the health in its nametag [text] as it appeared. */
    private fun link(c: TrackedCreature, text: String) {
        c.mob = idCarrier(c.stand) ?: linkMob(c.stand)
        readHealth(c, text, spawn = true)
    }

    /** Which of your recent [deaths] the new nametag [c] split off, null when it is no child. */
    private fun parentDeath(c: TrackedCreature, deaths: List<TrackedCreature>): TrackedCreature? {
        if (! ClaimRules.childName(c.name, c.creature, c.maxHp)) return null
        val pos = c.position
        return ClaimRules.parentOf(deaths.filter { it.children < ClaimRules.CHILD_MAX }, { c.spawnedAt - it.deadAt }, { d -> d.deathPos?.let(pos::distanceTo) ?: Double.POSITIVE_INFINITY })
    }

    /**
     * Claims the nametags that came up a tick or a few before [parent]'s death was seen as its children: tracked as
     * someone else's by then, or claimed by place alone ([ClaimRules.childClaimable]). One that comes up in the death's
     * tick is claimed as it is tracked (deaths are seen first); an unknown name is not tracked yet and is claimed when
     * it is read again.
     */
    private fun claimChildren(parent: TrackedCreature) {
        val at = parent.deathPos ?: return
        creatures.values.filter { ClaimRules.childClaimable(it.own, it.early, it.parent, it.dead) && ClaimRules.childName(it.name, it.creature, it.maxHp) }
            .map { it to it.position.distanceTo(at) }
            .filter { (c, distance) -> ClaimRules.isChild(c.spawnedAt - parent.deadAt, distance) }
            .sortedWith(compareBy({ abs(it.first.spawnedAt - parent.deadAt) }, { it.second }))
            .take((ClaimRules.CHILD_MAX - parent.children).coerceAtLeast(0))
            .forEach { (c, _) ->
                c.own = true
                c.parent = parent.name
                c.early = false
                parent.children ++
                onOwnerChange.forEach { it(c) }
            }
    }

    /** The nametag went out of view rather than away: unloaded, or removed farther off than nametags are scanned. */
    private fun leftView(stand: ArmorStand, p: Player): Boolean {
        val reason = stand.removalReason
        return reason != null && reason != Entity.RemovalReason.DISCARDED && reason != Entity.RemovalReason.KILLED || stand.distanceTo(p) > SCAN_RADIUS
    }

    private fun carrierBox(stand: ArmorStand) = AABB(stand.x - 1.0, stand.y - 4.5, stand.z - 1.0, stand.x + 1.0, stand.y + 0.2, stand.z + 1.0)

    private fun canCarry(e: LivingEntity) = e !is ArmorStand && e !== mc.player && e.isAlive && ! (e is Player && e.uuid.version() == 4)

    /** A linked mob stays linked while it lives right below its nametag, so a crowd cannot swap it for someone else's. */
    private fun stillCarries(mob: LivingEntity?, stand: ArmorStand) = mob != null && ! mob.isRemoved && canCarry(mob) && carrierBox(stand).intersects(mob.boundingBox)

    /** [e] is the mob of another tracked nametag, or sits right before another nametag (any, tracked or not) in id order. */
    private fun heldByOther(e: LivingEntity, stand: ArmorStand): Boolean {
        if (creatures.values.any { it.stand !== stand && it.mob === e }) return true
        val next = mc.level?.getEntity(e.id + 1)
        return next is ArmorStand && next !== stand && next.hasCustomName()
    }

    /**
     * Hypixel's own pairing: the mob spawned right before its nametag, so it has the previous entity id (SkyHanni links
     * the same way). It wins over any other link while it lives near the nametag, even when it is briefly knocked out of
     * the box right below it.
     */
    private fun idCarrier(stand: ArmorStand): LivingEntity? =
        (mc.level?.getEntity(stand.id - 1) as? LivingEntity)?.takeIf { canCarry(it) && it.position().distanceTo(stand.position()) <= ID_CARRIER_DISTANCE }

    /**
     * The closest living entity right below [stand] when its id neighbour is not there (never an armor stand, a real
     * player, the mob of another tracked nametag, or the id neighbour of another nametag).
     */
    private fun linkMob(stand: ArmorStand): LivingEntity? {
        val world = mc.level ?: return null
        return world.getEntitiesOfClass(LivingEntity::class.java, carrierBox(stand)) { e -> canCarry(e) && ! heldByOther(e, stand) }.minByOrNull {
            val dx = it.x - stand.x
            val dz = it.z - stand.z
            dx * dx + dz * dz + (stand.y - it.boundingBox.maxY).let { dy -> dy * dy } * 0.25
        }
    }

    /** Reads the health shown in [text] into [c]; true when the hp changed. The [spawn] read also sets the spawn ratio. */
    private fun readHealth(c: TrackedCreature, text: String, spawn: Boolean = false): Boolean {
        val match = healthRegex.find(text) ?: return false
        val hp = parseNumber(match.groupValues[1]) ?: return false
        val max = match.groupValues[2].takeIf(String::isNotEmpty)?.let(::parseNumber)
        // only a shown max gives a ratio (without one, the max is the highest hp seen)
        if (spawn && max != null && max > 0.0) c.spawnRatio = hp / max
        val changed = hp != c.hp
        c.hp = hp
        c.maxHp = max ?: c.maxHp.coerceAtLeast(hp)
        return changed
    }

    private fun parseNumber(text: String): Double? {
        val t = text.replace(",", "")
        val mult = when (t.lastOrNull()?.lowercaseChar()) {
            'k' -> 1e3
            'm' -> 1e6
            'b' -> 1e9
            else -> 1.0
        }
        return (if (mult == 1.0) t else t.dropLast(1)).toDoubleOrNull()?.times(mult)
    }

    /**
     * The text you see in [c]: obfuscated parts and legacy `§k` runs (the glyphs Hypixel puts around a Corrupted
     * creature's name) are left out, formatting codes removed.
     */
    fun visibleText(c: Component): String {
        val out = StringBuilder()
        c.visit({ style, text ->
            appendVisible(out, text, style.isObfuscated)
            Optional.empty<Unit>()
        }, Style.EMPTY)
        return out.toString()
    }

    /** [text] without its `§` codes and the characters they obfuscate, starting from a part style that is [obfuscated] or not. */
    private fun appendVisible(out: StringBuilder, text: String, obfuscated: Boolean) {
        var hidden = obfuscated
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '§') {
                // like the font: a colour ends a §k run and §r goes back to the part's own style
                when (text.getOrNull(i + 1)?.lowercaseChar()) {
                    'k' -> hidden = true
                    'r' -> hidden = obfuscated
                    in '0'..'9', in 'a'..'f' -> hidden = false
                }
                i += 2
                continue
            }
            if (! hidden) out.append(ch)
            i ++
        }
    }

    /**
     * Reads a nametag's [visible text][visibleText]. A Corrupted variant names its base creature; when an obfuscated
     * glyph still sticks to the name (`aCorrupted Frozen Stevea`), the glued last letter is dropped as well.
     */
    fun readName(text: String): NameRead {
        val clean = cleanName(text)
        val base = clean.replace(corruptedPrefix, "")
        val corrupted = base.length < clean.length
        val creature = SeaCreatures.match(base) ?: base.dropLast(1).takeIf { corrupted && it.isNotEmpty() }?.let(SeaCreatures::match)
        return NameRead(if (corrupted) "Corrupted ${creature?.name ?: base}" else clean, creature, corrupted)
    }

    /** `§8[§7Lv20§8] §c⚓ Corrupted Sea Walker ᛤ §a1,500§f/§a1,500§c❤` -> `Corrupted Sea Walker`. */
    fun cleanName(raw: String): String {
        var s = raw.removeFormatting().trim()
        s = s.replace(leadingDecoration, "")
        s = s.replace(levelPrefix, "")
        s = s.replace(healthSuffix, "")
        s = s.replace("ᛤ", "").trim()
        s = s.replace(decoration, "").trim()
        return s
    }
}

//#endif
