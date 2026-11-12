package com.laybalt.event.impl

import com.laybalt.event.Event
import net.minecraft.world.entity.Entity

class CheckEntityRenderEvent(val entity: Entity): Event(cancelable = true)