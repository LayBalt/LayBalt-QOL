package com.laybalt.GUI

import gg.essential.elementa.UIComponent
import net.minecraft.client.Minecraft
import net.minecraft.client.settings.KeyBinding
import net.minecraftforge.fml.client.registry.ClientRegistry
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.InputEvent
import org.lwjgl.input.Keyboard

class GuiKeyBind(gui: Gui) : UIComponent() {
    private val keyBinding = KeyBinding("Open GUI", Keyboard.KEY_G, "LayBalt")

    init {
        ClientRegistry.registerKeyBinding(keyBinding)
    }

    @SubscribeEvent
    fun onKeyInput(event: InputEvent.KeyInputEvent) {
        if (keyBinding.isPressed) {
            // Открыть GUI
            Minecraft.getMinecraft().displayGuiScreen(Gui())
        }
    }
}