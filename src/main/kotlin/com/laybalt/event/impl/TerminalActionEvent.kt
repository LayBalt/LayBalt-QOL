package com.laybalt.event.impl

import com.laybalt.event.Event
import com.laybalt.features.impl.floor7.terminals.TerminalClick
import com.laybalt.features.impl.floor7.terminals.impl.Terminal

/**
 * Terminal actions that [TerminalEvent] does not cover (ported from Odin's `TerminalEvent.Click` / `TerminalEvent.Solve`).
 *
 * @property simulated true when the action happened inside the local terminal simulator (`/lbq termsim`).
 */
abstract class TerminalActionEvent(val handler: Terminal, val simulated: Boolean): Event(cancelable = false) {
    /** Posted after a solver/auto click was sent (or routed to the simulator). */
    class Click(handler: Terminal, val click: TerminalClick, simulated: Boolean): TerminalActionEvent(handler, simulated)

    /** Posted when one of your own terminals was completed. [openTime] is the [System.currentTimeMillis] of the first open. */
    class Solve(handler: Terminal, val openTime: Long, simulated: Boolean): TerminalActionEvent(handler, simulated)
}
