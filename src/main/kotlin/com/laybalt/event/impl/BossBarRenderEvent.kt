package com.laybalt.event.impl

import com.laybalt.event.Event
import net.minecraft.client.gui.components.LerpingBossEvent
import net.minecraft.network.chat.Component

/**
 * Posted every frame for each boss bar while the boss health overlay is drawn.
 * Listeners may replace [name] to change the text shown above the bar (the boss event itself is untouched).
 */
class BossBarRenderEvent(val bossEvent: LerpingBossEvent, var name: Component): Event(false)
