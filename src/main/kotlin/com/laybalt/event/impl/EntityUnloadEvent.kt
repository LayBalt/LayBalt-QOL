package com.laybalt.event.impl

import com.laybalt.event.Event
import net.minecraft.world.entity.Entity

class EntityUnloadEvent(val entity: Entity): Event(false)