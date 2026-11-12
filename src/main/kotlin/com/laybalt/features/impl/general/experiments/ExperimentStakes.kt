package com.laybalt.features.impl.general.experiments

//#if CHEAT

/**
 * The stake you pick in the "Chronomatron ➜ Stakes" / "Ultrasequencer ➜ Stakes" menu, read from its lore (lines without
 * formatting). The last "Chain of N:" (Chronomatron) or "Series of N:" (Ultrasequencer) reward line is the round count
 * that gives the most Superpairs clicks, with any Metaphysical Serums already counted in by Hypixel; SkyHanni's
 * Superpairs clicks alert stops at the same line. Minecraft-free, so it is unit tested.
 */
sealed interface Stake {
    /** The most Superpairs clicks come with [rounds] rounds. */
    data class Rewards(val rounds: Int): Stake

    /** Practice mode has no rewards: no round count to stop at. */
    data object Practice: Stake

    companion object {
        /** A reward line of the stake: "Chain of 12:" in Chronomatron, "Series of 15:" in Ultrasequencer. */
        private val rewardLine = Regex("""(?:Chain|Series) of (\d+):""")

        /**
         * The stake whose item has these lore lines: practice mode if any line says so, null when it cannot be played
         * (level or experience too low) or names no reward line, else the rounds of its last reward line.
         */
        fun of(lore: List<String>): Stake? {
            var practice = false
            var locked = false
            var rounds: Int? = null
            for (line in lore) {
                if ("Practice mode has no rewards" in line) practice = true
                if ("Enchanting level too low!" in line || "Not enough experience!" in line) locked = true
                rewardLine.find(line)?.groupValues?.get(1)?.toIntOrNull()?.let { rounds = it }
            }
            return when {
                practice -> Practice
                locked -> null
                else -> rounds?.let(::Rewards)
            }
        }

        /**
         * The solvers' stop target ("size > target") for a stake of [rounds]:
         * - Chronomatron stops once that many rounds were answered: its "Round:" counter then shows one more (SkyHanni's
         *   rule, the same moment its alert comes).
         * - Ultrasequencer stops once the Series of [rounds] was answered (the sequence after it shows). With
         *   [ultrasequencerEarly] it stops as soon as that sequence shows, one round early: SkyHanni subtracts 1 since
         *   Hypixel paid the full Series for one round less in 2025, which no source confirms for the current game (the
         *   wiki puts the same bug on Chronomatron, OdinClient answers the full Series).
         */
        fun target(game: ExperimentGame, rounds: Int, ultrasequencerEarly: Boolean = false) = when (game) {
            ExperimentGame.ULTRASEQUENCER -> if (ultrasequencerEarly) rounds - 1 else rounds
            else -> rounds - 1
        }
    }
}

//#endif
