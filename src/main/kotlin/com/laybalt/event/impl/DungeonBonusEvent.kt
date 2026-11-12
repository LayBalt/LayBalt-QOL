package com.laybalt.event.impl

import com.laybalt.event.Event

/**
 * Posted by [com.laybalt.utils.dungeons.map.handlers.ScoreCalculation] when this client itself detects a bonus-score
 * event (mimic / prince / bat). Not posted when the bonus is only learned from a teammate's party message.
 */
class DungeonBonusEvent(val type: Type): Event(cancelable = false) {
    enum class Type { MIMIC, PRINCE, BAT }
}
