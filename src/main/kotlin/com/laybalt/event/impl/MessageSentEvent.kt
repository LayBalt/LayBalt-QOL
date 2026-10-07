package com.laybalt.event.impl

import com.laybalt.event.Event

class MessageSentEvent(var message: String): Event(cancelable = true)