package com.laybalt.event.impl

import com.laybalt.LayBaltQOL
import com.laybalt.event.Event

abstract class LayBaltDebugFlagEvent(val flag: String): Event(false) {
    class Add(flag: String): LayBaltDebugFlagEvent(flag)
    class Remove(flag: String): LayBaltDebugFlagEvent(flag)

    override fun cancel() {
        LayBaltQOL.debugFlags.remove(flag)
    }
}