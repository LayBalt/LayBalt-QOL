package com.laybalt.features.impl.fishing

//#if CHEAT

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin

/**
 * Where a cast lands on Hypixel: the flight of the 1.8.9 `EntityFishHook` the server runs (the client only shows the hook
 * the server moves), against a block query. Its constants fit your recordings to 0.2 m at the median, sideways to 0.00 m
 * and within 2 % of your reach per pitch; the 26.2 client's are 2 m off and reach half as far. The mean flight has no
 * launch noise, and a stalled hook keeps 0.1 of its speed (the mean of the server's U(0, 0.2)).
 *
 * [sameFluid] tells whether a view casts into the fluid your fishing view casts into, with a margin for what the mean
 * flight leaves out (the sideways spread, creatures in the line). Pure: the world is a [Blocks], so it is unit tested;
 * [BobberWorld] reads the client's level.
 */
object BobberFlight {
    /** What a block holds for the hook. */
    enum class Medium {
        AIR,
        /** Anything with a shape: the hook stops on it, against it or in front of it. */
        SOLID,
        WATER,
        LAVA;

        val fluid get() = this == WATER || this == LAVA
    }

    /** The world: what fills block ([x], [y], [z]), and a fluid's surface within its block ([top], 1 for a source). */
    fun interface Blocks {
        fun at(x: Int, y: Int, z: Int): Medium
        fun top(x: Int, y: Int, z: Int): Double = 1.0
    }

    /**
     * Where the hook came to rest (its box's bottom centre): [medium] is the fluid it floats in, SOLID when it lies on or
     * against a block, AIR when it still flies or the server dropped it beyond 32 blocks. [surface] is the top of the
     * fluid column it floats in (NaN unless a fluid); [contactTick] the first tick in a fluid (-1: none).
     */
    data class Landing(val medium: Medium, val x: Double, val y: Double, val z: Double, val surface: Double, val contactTick: Int)

    /** The answer of [judge]: [same] for [sameFluid], and which check failed (for the killer's trace). */
    enum class Verdict(val same: Boolean?) {
        SAME(true),
        /** The mean flight lands elsewhere: out of the fluid, in the other one or in a pool at another level. */
        MEAN(false),
        /** A flight [YAW_FAN] to a side does. */
        FAN(false),
        /** The surface [SHORT] short of the landing is not the same fluid (a creature in the line stops the hook that early). */
        SHORT(false),
        /** The surface [LONG] beyond it is not. */
        LONG(false),
        /** The cast from your fishing view itself lands in no fluid: nothing to compare with. */
        UNDECIDED(null),
    }

    /** The 1.8 eye height, standing and sneaking; the hook spawns this much below the eye... */
    const val EYE = 1.62
    const val SNEAK_EYE = 1.54
    private const val SPAWN_DROP = 0.1
    /** ...and this far to the right of the view. */
    private const val SIDE = 0.16
    private const val SPEED = 1.5
    /** The server's launch noise per axis of the unit view vector (sd); the mean flight leaves it out. */
    const val LAUNCH_NOISE = 0.0075
    /** Half the width (and height) of the hook's box, and the fifths its submerged share is counted in. */
    private const val HALF = 0.125
    private const val SLICES = 5
    private const val SLICE = 0.05
    private const val GRAVITY = 0.04
    private const val AIR_DRAG = 0.92
    /** Drag on a tick the box bumped into a block. */
    private const val BUMP_DRAG = 0.5
    /** In a fluid: 0.92 × 0.9 = 0.828 sideways, the drag a grid search over your recordings found too. */
    private const val FLUID_DRAG = 0.9
    private const val FLUID_VY = 0.8
    private const val STALL_KEEP = 0.1
    /** Long lobs touch the surface after 36 ticks and slide for 20 more. */
    private const val MAX_TICKS = 100
    /** Squared: the server drops a hook beyond 32 blocks. */
    private const val MAX_RANGE_SQ = 1024.0
    /** A hook that touched a block this few ticks before the end lies on it. */
    private const val SUPPORT_TICKS = 3
    private const val MAX_CELLS = 64

    /** [judge]: the yaw fan to each side, in degrees (your sideways spread, p95 0.76 m at 7-11 m)... */
    const val YAW_FAN = 5.0
    /** ...the surface probes short of and beyond the mean landing, in blocks (creatures in the line stop it 1.2-1.7 m short)... */
    const val SHORT = 2.0
    const val LONG = 0.5
    /** ...and how far the surface a view lands on may lie from home's. */
    const val SURFACE_TOL = 1.0
    private const val CELL_EPS = 1e-6
    /**
     * [undershoot]: the halvings of its bisection (a 20° undershoot found to a third of a degree, each a [judge] over the
     * same cached blocks)...
     */
    const val BISECT_STEPS = 6
    /** ...and how close to your fishing view a turn may always stop: the launch noise's spread (sd 0.43° per axis). */
    const val NEAR_HOME = 0.5

    /** The hook's move in tick [tick], from ([x0], [y0], [z0]) to ([x1], [y1], [z1]): where it meets entities ([fly]'s track). */
    fun interface Track {
        fun step(tick: Int, x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double)
    }

    /**
     * The flight of a cast from feet ([feetX], [feetY], [feetZ]) with view [yaw]/[pitch] in degrees, [sneaking] or not;
     * [noise] (three values) is added to the unit view vector, the launch noise the mean flight leaves out. [track] gets
     * every tick's move, the one a block stopped as far as it was meant to go (1.8 checks entities along it).
     */
    fun fly(feetX: Double, feetY: Double, feetZ: Double, yaw: Double, pitch: Double, sneaking: Boolean, blocks: Blocks, noise: DoubleArray? = null, track: Track? = null): Landing {
        val a = Math.toRadians(yaw)
        val p = Math.toRadians(pitch)
        var x = feetX - cos(a) * SIDE
        var y = feetY + (if (sneaking) SNEAK_EYE else EYE) - SPAWN_DROP
        var z = feetZ - sin(a) * SIDE
        var vx = (- sin(a) * cos(p) + (noise?.get(0) ?: 0.0)) * SPEED
        var vy = (- sin(p) + (noise?.get(1) ?: 0.0)) * SPEED
        var vz = (cos(a) * cos(p) + (noise?.get(2) ?: 0.0)) * SPEED
        val hook = Hook(blocks)
        var stalled = false
        var lastSupport = - 99
        var share = 0.0
        var fluid: Medium? = null
        var contact = - 1
        for (t in 0 until MAX_TICKS) {
            if (stalled) {
                stalled = false
                vx *= STALL_KEEP
                vy *= STALL_KEEP
                vz *= STALL_KEEP
            }
            // 1.8 traces the move against block shapes first: a hit stops the hook for this tick, no move and no drag
            if (raySolid(blocks, x, y, z, x + vx, y + vy, z + vz)) {
                track?.step(t, x, y, z, x + vx, y + vy, z + vz)
                stalled = true
                lastSupport = t
                continue
            }
            hook.move(x, y, z, vx, vy, vz)
            track?.step(t, x, y, z, x + hook.dx, y + hook.dy, z + hook.dz)
            x += hook.dx
            y += hook.dy
            z += hook.dz
            if (hook.clipX) vx = 0.0
            if (hook.clipZ) vz = 0.0
            var ground = false
            if (hook.clipY) {
                ground = vy < 0
                vy = 0.0
            }
            val bumped = ground || hook.clipX || hook.clipZ
            if (bumped) lastSupport = t
            val fx = x - feetX
            val fy = y - feetY
            val fz = z - feetZ
            if (fx * fx + fy * fy + fz * fz > MAX_RANGE_SQ) return Landing(Medium.AIR, x, y, z, Double.NaN, contact)
            share = hook.submerged(x, y, z)
            fluid = hook.fluid
            if (share > 0 && contact < 0) contact = t
            vy += GRAVITY * (2 * share - 1)
            var f = if (bumped) BUMP_DRAG else AIR_DRAG
            if (share > 0) {
                f *= FLUID_DRAG
                vy *= FLUID_VY
            }
            vx *= f
            vy *= f
            vz *= f
        }
        val medium = fluid?.takeIf { share > 0 } ?: if (MAX_TICKS - lastSupport <= SUPPORT_TICKS) Medium.SOLID else Medium.AIR
        return Landing(medium, x, y, z, if (medium.fluid) surfaceAbove(blocks, x, y, z, medium) else Double.NaN, contact)
    }

    /**
     * Whether a cast with view [yaw]/[pitch] lands in the fluid a cast with your fishing view [homeYaw]/[homePitch] lands
     * in, both from these feet; null when the cast from your fishing view lands in no fluid ([judge]).
     */
    fun sameFluid(feetX: Double, feetY: Double, feetZ: Double, sneaking: Boolean, homeYaw: Double, homePitch: Double, yaw: Double, pitch: Double, blocks: Blocks) =
        judge(feetX, feetY, feetZ, sneaking, homeYaw, homePitch, yaw, pitch, blocks).same

    /**
     * [sameFluid] with the check that failed. The view counts only when its mean flight, the flights [YAW_FAN] to each
     * side, and the surface [SHORT] short of and [LONG] beyond its mean landing all hold the fluid home's cast floats in,
     * at a surface within [SURFACE_TOL] of home's. What the flight leaves out makes it fail, never pass, wrongly.
     */
    fun judge(feetX: Double, feetY: Double, feetZ: Double, sneaking: Boolean, homeYaw: Double, homePitch: Double, yaw: Double, pitch: Double, blocks: Blocks): Verdict {
        val home = fly(feetX, feetY, feetZ, homeYaw, homePitch, sneaking, blocks)
        if (! home.medium.fluid) return Verdict.UNDECIDED
        val mean = fly(feetX, feetY, feetZ, yaw, pitch, sneaking, blocks)
        if (! landsLike(mean, home)) return Verdict.MEAN
        for (side in doubleArrayOf(- YAW_FAN, YAW_FAN)) {
            if (! landsLike(fly(feetX, feetY, feetZ, yaw + side, pitch, sneaking, blocks), home)) return Verdict.FAN
        }
        val a = Math.toRadians(yaw)
        val fx = - sin(a)
        val fz = cos(a)
        if (! surfaceCell(blocks, mean.x - fx * SHORT, mean.z - fz * SHORT, mean.surface, home.medium)) return Verdict.SHORT
        if (! surfaceCell(blocks, mean.x + fx * LONG, mean.z + fz * LONG, mean.surface, home.medium)) return Verdict.LONG
        return Verdict.SAME
    }

    /**
     * How far short of your fishing view a turn towards it stops: [short] (the caller's draw from your errors) when its
     * cast [lands] like home's (it gets the undershoot in degrees), else a draw [under] the largest undershoot that still
     * does, found by bisection between home and [short] in [BISECT_STEPS] halvings: the turn ends neither piled at one
     * undershoot nor on your fishing view itself, which all motion in whole mouse steps would hit to the bit, turn after
     * turn. Your own casts after the view went 8° or more away from where you cast before and came back came within
     * [NEAR_HOME] of it 2 times in 95 and never onto it, so with no undershoot landing at all it is drawn under
     * [NEAR_HOME] (or [short], if smaller): the server's launch noise spreads a cast that much anyway. Null when [lands]
     * cannot tell.
     */
    fun undershoot(short: Double, lands: (Double) -> Boolean?, under: (Double) -> Double): Double? {
        if (lands(short) ?: return null) return short
        var lo = 0.0
        var hi = short
        repeat(BISECT_STEPS) {
            val mid = (lo + hi) / 2
            if (lands(mid) == true) lo = mid else hi = mid
        }
        return under(max(lo, min(short, NEAR_HOME)))
    }

    private fun landsLike(l: Landing, home: Landing) = l.medium == home.medium && abs(l.surface - home.surface) <= SURFACE_TOL

    /** The surface cell of the column at [x]/[z], at the level of [surface], holds [medium] with nothing solid above it. */
    private fun surfaceCell(blocks: Blocks, x: Double, z: Double, surface: Double, medium: Medium): Boolean {
        val bx = floor(x).toInt()
        val bz = floor(z).toInt()
        val by = floor(surface - CELL_EPS).toInt()
        return blocks.at(bx, by, bz) == medium && blocks.at(bx, by + 1, bz) != Medium.SOLID
    }

    /** The top of the [medium] column the hook at [x]/[y]/[z] floats in: from its cell (or the one below) up. */
    private fun surfaceAbove(blocks: Blocks, x: Double, y: Double, z: Double, medium: Medium): Double {
        val bx = floor(x).toInt()
        val bz = floor(z).toInt()
        var by = floor(y).toInt()
        if (blocks.at(bx, by, bz) != medium) by--
        if (blocks.at(bx, by, bz) != medium) return Double.NaN
        var n = 0
        while (n++ < MAX_CELLS && blocks.at(bx, by + 1, bz) == medium) by++
        return by + blocks.top(bx, by, bz)
    }

    /** A cell on the segment from (x0, y0, z0) to (x1, y1, z1) is SOLID: a voxel walk over full cubes, the start cell included. */
    private fun raySolid(blocks: Blocks, x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double): Boolean {
        var ix = floor(x0).toInt()
        var iy = floor(y0).toInt()
        var iz = floor(z0).toInt()
        val ex = floor(x1).toInt()
        val ey = floor(y1).toInt()
        val ez = floor(z1).toInt()
        val dx = x1 - x0
        val dy = y1 - y0
        val dz = z1 - z0
        val sx = sign(dx).toInt()
        val sy = sign(dy).toInt()
        val sz = sign(dz).toInt()
        val tdx = if (dx != 0.0) abs(1 / dx) else Double.POSITIVE_INFINITY
        val tdy = if (dy != 0.0) abs(1 / dy) else Double.POSITIVE_INFINITY
        val tdz = if (dz != 0.0) abs(1 / dz) else Double.POSITIVE_INFINITY
        var tmx = if (dx > 0) (ix + 1 - x0) * tdx else if (dx < 0) (x0 - ix) * tdx else Double.POSITIVE_INFINITY
        var tmy = if (dy > 0) (iy + 1 - y0) * tdy else if (dy < 0) (y0 - iy) * tdy else Double.POSITIVE_INFINITY
        var tmz = if (dz > 0) (iz + 1 - z0) * tdz else if (dz < 0) (z0 - iz) * tdz else Double.POSITIVE_INFINITY
        repeat(MAX_CELLS) {
            if (blocks.at(ix, iy, iz) == Medium.SOLID) return true
            if (ix == ex && iy == ey && iz == ez) return false
            if (tmx <= tmy && tmx <= tmz) {
                if (tmx > 1) return false
                ix += sx
                tmx += tdx
            }
            else if (tmy <= tmz) {
                if (tmy > 1) return false
                iy += sy
                tmy += tdy
            }
            else {
                if (tmz > 1) return false
                iz += sz
                tmz += tdz
            }
        }
        return false
    }

    /**
     * The hook's box in one flight: [move] is 1.8's `moveEntity` against SOLID cells as full cubes (clipped in y, then x,
     * then z, no stepping), leaving the clipped move in [dx]/[dy]/[dz] and which axes were clipped; [submerged] is its
     * share under a fluid's surface.
     */
    private class Hook(private val blocks: Blocks) {
        var dx = 0.0
        var dy = 0.0
        var dz = 0.0
        var clipX = false
        var clipY = false
        var clipZ = false
        /** The fluid of the first slice the last [submerged] counted, null with none. */
        var fluid: Medium? = null
        private var cubes = IntArray(3 * 128)
        private var count = 0

        fun move(x: Double, y: Double, z: Double, vx: Double, vy: Double, vz: Double) {
            collect(minOf(x - HALF, x - HALF + vx), maxOf(x + HALF, x + HALF + vx), minOf(y, y + vy), maxOf(y + 2 * HALF, y + 2 * HALF + vy), minOf(z - HALF, z - HALF + vz), maxOf(z + HALF, z + HALF + vz))
            var minX = x - HALF
            var maxX = x + HALF
            var minY = y
            var maxY = y + 2 * HALF
            val minZ = z - HALF
            val maxZ = z + HALF
            dy = vy
            for (i in 0 until count) {
                val cx = cubes[3 * i]
                val cy = cubes[3 * i + 1]
                val cz = cubes[3 * i + 2]
                if (! overlaps(minX, maxX, cx) || ! overlaps(minZ, maxZ, cz)) continue
                if (dy > 0 && cy >= maxY) dy = minOf(dy, cy - maxY) else if (dy < 0 && cy + 1 <= minY) dy = maxOf(dy, cy + 1 - minY)
            }
            minY += dy
            maxY += dy
            dx = vx
            for (i in 0 until count) {
                val cx = cubes[3 * i]
                val cy = cubes[3 * i + 1]
                val cz = cubes[3 * i + 2]
                if (! overlaps(minY, maxY, cy) || ! overlaps(minZ, maxZ, cz)) continue
                if (dx > 0 && cx >= maxX) dx = minOf(dx, cx - maxX) else if (dx < 0 && cx + 1 <= minX) dx = maxOf(dx, cx + 1 - minX)
            }
            minX += dx
            maxX += dx
            dz = vz
            for (i in 0 until count) {
                val cx = cubes[3 * i]
                val cy = cubes[3 * i + 1]
                val cz = cubes[3 * i + 2]
                if (! overlaps(minX, maxX, cx) || ! overlaps(minY, maxY, cy)) continue
                if (dz > 0 && cz >= maxZ) dz = minOf(dz, cz - maxZ) else if (dz < 0 && cz + 1 <= minZ) dz = maxOf(dz, cz + 1 - minZ)
            }
            clipX = dx != vx
            clipY = dy != vy
            clipZ = dz != vz
        }

        /**
         * The share of the box at [x]/[y]/[z] under a fluid's surface, in fifths (1.8's `isAABBInMaterial` per 0.05
         * slice): a slice counts when a fluid cell in the box's columns reaches its bottom.
         */
        fun submerged(x: Double, y: Double, z: Double): Double {
            var slices = 0
            fluid = null
            val x0 = floor(x - HALF).toInt()
            val x1 = floor(x + HALF).toInt()
            val z0 = floor(z - HALF).toInt()
            val z1 = floor(z + HALF).toInt()
            for (k in 0 until SLICES) {
                val lo = y + SLICE * k
                val hit = sliceFluid(x0, x1, z0, z1, lo, lo + SLICE) ?: continue
                slices++
                if (fluid == null) fluid = hit
            }
            return slices.toDouble() / SLICES
        }

        private fun sliceFluid(x0: Int, x1: Int, z0: Int, z1: Int, lo: Double, hi: Double): Medium? {
            for (bx in x0 .. x1) for (bz in z0 .. z1) for (by in floor(lo).toInt() .. floor(hi).toInt()) {
                val m = blocks.at(bx, by, bz)
                if (m.fluid && by + blocks.top(bx, by, bz) >= lo) return m
            }
            return null
        }

        /** The SOLID cells the swept box may touch (one more layer below, as 1.8 collects them). */
        private fun collect(minX: Double, maxX: Double, minY: Double, maxY: Double, minZ: Double, maxZ: Double) {
            count = 0
            for (bx in floor(minX).toInt() .. floor(maxX).toInt()) {
                for (by in floor(minY).toInt() - 1 .. floor(maxY).toInt()) {
                    for (bz in floor(minZ).toInt() .. floor(maxZ).toInt()) {
                        if (blocks.at(bx, by, bz) != Medium.SOLID) continue
                        if (3 * count + 3 > cubes.size) cubes = cubes.copyOf(cubes.size * 2)
                        cubes[3 * count] = bx
                        cubes[3 * count + 1] = by
                        cubes[3 * count + 2] = bz
                        count++
                    }
                }
            }
        }

        /** The box's span [lo]..[hi] on an axis overlaps the unit cube from [c]. */
        private fun overlaps(lo: Double, hi: Double, c: Int) = hi > c && lo < c + 1
    }
}

//#endif
