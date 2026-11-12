package com.laybalt.event.impl

import com.laybalt.event.Event

class MouseClickEvent(val button: Int, val action: Int, val modifiers: Int): Event(true)