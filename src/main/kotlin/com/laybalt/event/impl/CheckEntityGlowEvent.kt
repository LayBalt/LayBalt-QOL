package com.laybalt.event.impl

import com.laybalt.event.Event
import net.minecraft.world.entity.Entity
import java.awt.Color

class CheckEntityGlowEvent(val entity: Entity): Event(cancelable = true) {
    var shouldGlow = false
    var color = Color.WHITE
        set(value) {
            this.shouldGlow = true
            field = value
        }
}