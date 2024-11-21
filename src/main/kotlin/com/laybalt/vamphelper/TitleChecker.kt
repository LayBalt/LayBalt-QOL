package com.laybalt.vamphelper

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiIngame
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.TickEvent
import net.minecraftforge.fml.relauncher.ReflectionHelper
import java.lang.reflect.Field

class TitleChecker {
    private val mc: Minecraft = Minecraft.getMinecraft()
    private var overlayMessageField: Field? = null
    private var displayedTitleField: Field? = null
    private var displayedSubTitleField: Field? = null

    init {
        try {
            overlayMessageField = ReflectionHelper.findField(GuiIngame::class.java, "field_175199_z", "overlayMessage")
        } catch (e: Exception) {
            println("Ошибка при поиске поля ActionBar (overlayMessage): ${e.message}")
        }

        try {
            displayedTitleField = ReflectionHelper.findField(GuiIngame::class.java, "field_175201_x", "displayedTitle")
        } catch (e: Exception) {
            println("Ошибка при поиске поля Title (displayedTitle): ${e.message}")
        }

        try {
            displayedSubTitleField = ReflectionHelper.findField(GuiIngame::class.java, "field_175200_y", "displayedSubTitle")
        } catch (e: Exception) {
            println("Ошибка при поиске поля Subtitle (displayedSubTitle): ${e.message}")
        }
    }

    private fun getFieldText(field: Field?): String? {
        return try {
            field?.get(mc.ingameGUI) as? String
        } catch (e: Exception) {
            println("Ошибка при чтении значения поля: ${e.message}")
            null
        }
    }

    private fun checkTitles() {
        val actionBarText = getFieldText(overlayMessageField)
        val titleText = getFieldText(displayedTitleField)
        val subtitleText = getFieldText(displayedSubTitleField)

        if (!titleText.isNullOrEmpty()) println("Текущий Title: $titleText")
        if (!subtitleText.isNullOrEmpty()) println("Текущий Subtitle: $subtitleText")
        if (!actionBarText.isNullOrEmpty()) println("Текущий ActionBar: $actionBarText")
    }

    @SubscribeEvent
    fun onClientTick(event: TickEvent.ClientTickEvent) {
        if (event.phase == TickEvent.Phase.START) {
            checkTitles()
        }
    }
}
