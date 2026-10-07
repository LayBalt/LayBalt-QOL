package com.laybalt.event.impl

import com.laybalt.event.Event

abstract class WebSocketEvent: Event(cancelable = false) {
    class Payload(val message: String): WebSocketEvent()
    object Connect: WebSocketEvent()
    object Disconnect: WebSocketEvent()
}