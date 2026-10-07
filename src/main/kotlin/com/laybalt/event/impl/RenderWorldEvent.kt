package com.laybalt.event.impl

import com.laybalt.event.Event
import com.laybalt.utils.render.world.RenderContext

class RenderWorldEvent(val ctx: RenderContext): Event(cancelable = false)