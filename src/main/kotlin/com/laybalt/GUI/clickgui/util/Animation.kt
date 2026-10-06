package com.laybalt.GUI.clickgui.util

/** Time-based ease-out tween towards the last requested target. */
class Animation(var duration: Long = 200, initialValue: Float = 0f) {
    var value: Float = initialValue
        private set

    private var startValue = initialValue
    private var targetValue = initialValue
    private var startTime = 0L

    fun update(newTarget: Float) {
        if (newTarget != targetValue) {
            targetValue = newTarget
            startValue = value
            startTime = System.currentTimeMillis()
        }

        val progress = ((System.currentTimeMillis() - startTime).toDouble() / duration).coerceIn(0.0, 1.0)
        value = if (progress >= 1.0) targetValue
        else startValue + (targetValue - startValue) * easeOutQuad(progress).toFloat()
    }

    fun set(v: Float) {
        value = v
        startValue = v
        targetValue = v
        startTime = 0L
    }

    private fun easeOutQuad(t: Double) = 1.0 - (1.0 - t) * (1.0 - t)
}
