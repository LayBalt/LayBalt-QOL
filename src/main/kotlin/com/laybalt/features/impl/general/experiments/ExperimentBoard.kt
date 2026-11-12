package com.laybalt.features.impl.general.experiments

//#if CHEAT

/** What sits in a slot of an Experimentation Table menu, reduced to what the solvers need. */
enum class ItemKind { EMPTY, GLASS, PANE, TERRACOTTA, OTHER }

/**
 * Read-only view of an Experimentation Table add-on menu (a 6-row chest). Pure - no Minecraft types - so the solvers can
 * be replayed against simulated Hypixel sequences in unit tests; [com.laybalt.features.impl.general.AutoExperiments]
 * adapts the live menu to it.
 */
interface ExperimentBoard {
    /** Plain, trimmed name of the status item in slot 49 ("Remember the pattern!", "Timer: 5s", ...), "" when empty. */
    val status: String

    fun kind(slot: Int): ItemKind

    /** Dye colour id (vanilla `DyeColor.id`) of stained glass, stained glass panes and dyed terracotta, -1 otherwise. */
    fun color(slot: Int): Int

    /** The item has an enchantment glint (a lit Chronomatron note). */
    fun isLit(slot: Int): Boolean

    /** Plain, trimmed hover name. */
    fun name(slot: Int): String

    fun count(slot: Int): Int

    /** Identity of the item for Superpairs pairing (equal keys = the same reward), null for an empty slot. */
    fun cardKey(slot: Int): Any?
}

/** Experimentation Table constants shared by the solvers and the tests. */
object Experiments {
    const val STATUS_SLOT = 49
    const val REMEMBER_TEXT = "Remember the pattern!"
    const val TIMER_PREFIX = "Timer:"

    /** `DyeColor.CYAN.id` / `DyeColor.BLACK.id`. */
    const val DYE_CYAN = 9
    const val DYE_BLACK = 15

    /** Rows 1-4 without the outer columns are the playing field; the notes live in there. */
    val CHRONOMATRON_BOARD = 10 .. 43
    val ULTRASEQUENCER_BOARD = 9 .. 44

    fun isRoundStatus(status: String) = status == REMEMBER_TEXT || status.startsWith(TIMER_PREFIX)
}

enum class ExperimentGame(val prefix: String) {
    CHRONOMATRON("Chronomatron ("),
    ULTRASEQUENCER("Ultrasequencer ("),
    SUPERPAIRS("Superpairs (");

    companion object {
        fun of(title: String) = entries.firstOrNull { title.startsWith(it.prefix) }
    }
}

//#endif
