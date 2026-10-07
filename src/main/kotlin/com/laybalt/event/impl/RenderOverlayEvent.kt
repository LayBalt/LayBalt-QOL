package com.laybalt.event.impl

import com.laybalt.event.Event
import net.minecraft.client.gui.GuiGraphicsExtractor

class RenderOverlayEvent(val context: GuiGraphicsExtractor): Event(cancelable = false)