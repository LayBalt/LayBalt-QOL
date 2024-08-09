package com.laybalt.experiments

import com.laybalt.GUI.LBQConfig
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.inventory.GuiChest
import net.minecraft.inventory.ContainerChest
import net.minecraft.inventory.Slot
import net.minecraft.item.Item
import net.minecraft.init.Blocks
import net.minecraft.init.Items
import net.minecraft.inventory.IInventory
import net.minecraftforge.client.event.GuiOpenEvent
import net.minecraftforge.client.event.GuiScreenEvent
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import java.lang.reflect.Field

class AExperiment(
    private val config: LBQConfig
) {
    private val mc = Minecraft.getMinecraft()
    private var currentExperiment = ExperimentType.NONE
    private var hasAdded = false
    private var clicks = 0
    private var lastClickTime: Long = 0
    private val chronomatronOrder = ArrayList<Pair<Int, String>>(28)
    private var lastAdded = 0
    private val ultrasequencerOrder = HashMap<Int, Int>()

    private var isEnabled: Boolean
        get() = config.AutoExperimentSwitch
        set(value) {
            config.AutoExperimentSwitch = value
        }

    private val delay: Long
        get() = config.AutoExperimentSlider.toLong()

    fun reset() {
        currentExperiment = ExperimentType.NONE
        hasAdded = false
        chronomatronOrder.clear()
        lastAdded = 0
        ultrasequencerOrder.clear()
    }

    @SubscribeEvent
    fun onGuiOpenEvent(event: GuiOpenEvent) {
        val gui = event.gui
        if (gui is GuiChest) {
            onGuiOpen(gui)
        }
    }

    fun onGuiOpen(gui: GuiChest) {
        if (!isEnabled) return

        reset()
        val chestInventory = getLowerChestInventory(gui)
        val chestName = chestInventory?.displayName?.unformattedText
        currentExperiment = when {
            chestName?.startsWith("Chronomatron") == true -> ExperimentType.CHRONOMATRON
            chestName?.startsWith("Ultrasequencer") == true -> ExperimentType.ULTRASEQUENCER
            else -> ExperimentType.NONE
        }
    }

    fun getLowerChestInventory(guiChest: GuiChest): IInventory? {
        return try {
            val field: Field = GuiChest::class.java.getDeclaredField("lowerChestInventory")
            field.isAccessible = true
            field.get(guiChest) as IInventory
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    @SubscribeEvent
    fun onGuiDrawEvent(event: GuiScreenEvent.DrawScreenEvent.Post) {
        val gui = event.gui
        if (gui is GuiChest) {
            onGuiDraw(gui)
        }
    }

    fun onGuiDraw(gui: GuiChest) {
        if (!isEnabled) return

        val invSlots = (gui.inventorySlots as? ContainerChest)?.inventorySlots ?: return
        when (currentExperiment) {
            ExperimentType.CHRONOMATRON -> solveChronomatron(invSlots)
            ExperimentType.ULTRASEQUENCER -> solveUltraSequencer(invSlots)
            else -> return
        }
    }

    private fun solveChronomatron(invSlots: List<Slot>) {
        if (invSlots[49].stack?.item == Item.getItemFromBlock(Blocks.glowstone) && invSlots[lastAdded].stack?.isItemEnchanted == false) {
            hasAdded = false
            if (chronomatronOrder.size > 11) {
                mc.thePlayer?.closeScreen()
            }
        }

        if (!hasAdded && invSlots[49].stack?.item == Items.clock) {
            invSlots.filter { it.slotNumber in 10..43 }.find { it.stack?.isItemEnchanted == true }?.let {
                chronomatronOrder.add(Pair(it.slotNumber, it.stack.displayName))
                lastAdded = it.slotNumber
                hasAdded = true
                clicks = 0
            }
        }

        if (hasAdded && invSlots[49].stack?.item == Items.clock && chronomatronOrder.size > clicks && System.currentTimeMillis() - lastClickTime > delay) {
            mc.playerController.windowClick(mc.thePlayer.openContainer.windowId, chronomatronOrder[clicks].first, 2, 3, mc.thePlayer)
            lastClickTime = System.currentTimeMillis()
            clicks++
        }
    }

    private fun solveUltraSequencer(invSlots: List<Slot>) {
        if (invSlots[49].stack?.item == Items.clock) hasAdded = false

        if (!hasAdded && invSlots[49].stack?.item == Item.getItemFromBlock(Blocks.glowstone)) {
            if (!invSlots[44].hasStack) return
            ultrasequencerOrder.clear()

            invSlots.filter { it.slotNumber in 9..44 && it.stack?.item == Items.dye }
                .sortedBy { it.stack?.stackSize }
                .forEachIndexed { index, slot ->
                    ultrasequencerOrder[index] = slot.slotNumber
                }
            hasAdded = true
            clicks = 0
            if (ultrasequencerOrder.size > 9) mc.thePlayer?.closeScreen()
        }

        if (invSlots[49].stack?.item == Items.clock && ultrasequencerOrder.containsKey(clicks) && System.currentTimeMillis() - lastClickTime > delay) {
            ultrasequencerOrder[clicks]?.let {
                mc.playerController.windowClick(mc.thePlayer.openContainer.windowId, it, 2, 3, mc.thePlayer)
            }
            lastClickTime = System.currentTimeMillis()
            clicks++
        }
    }

    private enum class ExperimentType {
        CHRONOMATRON,
        ULTRASEQUENCER,
        NONE
    }
}
