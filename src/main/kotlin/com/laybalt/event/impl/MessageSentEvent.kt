package com.laybalt.event.impl

import com.laybalt.event.Event

/**
 * Posted before a chat message or a command is sent.
 * [isCommand] is true for commands (sent through sendCommand, [message] has no leading slash).
 */
class MessageSentEvent @JvmOverloads constructor(var message: String, val isCommand: Boolean = false): Event(cancelable = true)
