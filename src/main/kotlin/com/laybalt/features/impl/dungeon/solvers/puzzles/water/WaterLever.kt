package com.laybalt.features.impl.dungeon.solvers.puzzles.water

/**
 * The seven Water Board levers, keyed like `waterSolutions.json`. Pure: no Minecraft types.
 *
 * The six block levers move every block of their material between the board plane and the plane behind it; their
 * [ordinal] is the bit used in lever parity masks. [WATER] opens and closes the water source.
 */
enum class WaterLever(val key: String, val displayName: String) {
    QUARTZ("quartz_block", "Quartz"),
    GOLD("gold_block", "Gold"),
    COAL("coal_block", "Coal"),
    DIAMOND("diamond_block", "Diamond"),
    EMERALD("emerald_block", "Emerald"),
    CLAY("hardened_clay", "Clay"),
    WATER("water", "Water");

    val isBlockLever get() = this != WATER

    /** Bit of this lever in a parity mask (block levers only). */
    val bit get() = 1 shl ordinal

    companion object {
        fun fromKey(key: String) = entries.find { it.key == key }

        /** The six block levers, in mask-bit order. */
        val blockLevers = entries.filter { it != WATER }

        /** Levers whose bit is set in [mask], in mask-bit order. */
        fun inMask(mask: Int) = blockLevers.filter { mask and it.bit != 0 }

        /** "Gold, Coal" style list of the levers in [mask]. */
        fun names(mask: Int, separator: String = ", ") = inMask(mask).joinToString(separator) { it.displayName }
    }
}
