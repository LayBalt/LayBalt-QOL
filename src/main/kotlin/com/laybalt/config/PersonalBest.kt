package com.laybalt.config

object PersonalBest {
    private val pbData = PogObject("personal_bests", mutableMapOf<String, Number>())

    /**
     * @return true if this was a new Personal Best, false otherwise.
     */
    fun checkAndSetPB(key: String, value: Number, lowerIsBetter: Boolean = true): Boolean {
        val pbData = pbData.get()
        val currentPB = pbData[key]

        val isNewPB = when {
            currentPB == null -> true

            lowerIsBetter && value.toDouble() < currentPB.toDouble() -> true
            ! lowerIsBetter && value.toDouble() > currentPB.toDouble() -> true

            else -> false
        }

        return isNewPB.also { if (it) pbData[key] = value }
    }

    fun getPB(key: String) = pbData.get()[key]

    /**
     * Imports a time (lower is better) from another mod: kept only when it is positive and beats the stored one.
     * A stored value <= 0 is treated as invalid and replaced. Used by [ForeignConfigImport].
     */
    fun importTimePB(key: String, seconds: Double): Boolean {
        if (! seconds.isFinite() || seconds <= 0.0) return false
        val data = pbData.get()
        val current = data[key]?.toDouble()
        if (current != null && current > 0.0 && current <= seconds) return false
        data[key] = seconds
        pbData.save()
        return true
    }
}
