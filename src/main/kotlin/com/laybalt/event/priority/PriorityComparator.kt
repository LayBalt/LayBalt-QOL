package com.laybalt.event.priority

import com.laybalt.event.EventListener

object PriorityComparator: Comparator<EventListener<*>> {
    override fun compare(o1: EventListener<*>, o2: EventListener<*>): Int =
        o1.priority.ordinal - o2.priority.ordinal
}