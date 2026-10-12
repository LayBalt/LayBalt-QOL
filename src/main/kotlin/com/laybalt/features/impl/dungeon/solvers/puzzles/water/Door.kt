package com.laybalt.features.impl.dungeon.solvers.puzzles.water

/**
 * The five doors in the pit in front of the reward chest. [digit] is the digit used by the keys of
 * `waterSolutions.json` ("024" = Purple, Blue and Red closed), [z] the room-relative z of the door block at x = 0.
 */
enum class Door(val digit: Int, val displayName: String, val color: String, val z: Int) {
    PURPLE(0, "Purple", "§5", 4),
    ORANGE(1, "Orange", "§6", 3),
    BLUE(2, "Blue", "§9", 2),
    GREEN(3, "Green", "§a", 1),
    RED(4, "Red", "§c", 0);

    val bit get() = 1 shl digit
    val colored get() = "$color$displayName"

    companion object {
        const val ALL = 0b11111

        /** Door bits of a solution key such as "024". Characters that are not door digits are ignored. */
        fun bitsOf(key: String): Int = key.fold(0) { acc, c ->
            val d = c - '0'
            if (d in 0 .. 4) acc or (1 shl d) else acc
        }

        /** Solution key of [bits], digits ascending ("024"). */
        fun keyOf(bits: Int): String = entries.filter { bits and it.bit != 0 }.joinToString("") { it.digit.toString() }

        fun of(bits: Int): List<Door> = entries.filter { bits and it.bit != 0 }

        /** "§9Blue§7, §cRed" (or "none"). */
        fun names(bits: Int, separator: String = "§7, "): String =
            of(bits).joinToString(separator) { it.colored }.ifEmpty { "none" }

        fun count(bits: Int) = Integer.bitCount(bits and ALL)
    }
}
