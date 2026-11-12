// Menu keybinds (unequip, next/previous page, disable unequip, close if equipped) ported from Odin PetKeybinds
// (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.visual

import com.laybalt.LayBaltQOL
import com.laybalt.utils.ChatUtils
import com.laybalt.config.types.*
import com.laybalt.event.impl.*
import com.laybalt.features.Feature
import com.laybalt.features.impl.dev.ClickGui
import com.laybalt.init.types.ICustomMenu
import com.laybalt.mixin.IKeyMapping
import com.laybalt.ui.utils.Resolution
import com.laybalt.utils.ChatUtils.formattedText
import com.laybalt.utils.ChatUtils.removeFormatting
import com.laybalt.utils.ChatUtils.unformattedText
import com.laybalt.utils.ColorUtils.withAlpha
import com.laybalt.utils.GuiUtils
import com.laybalt.utils.items.ItemUtils.lore
import com.laybalt.utils.render.ItemRenderer
import com.laybalt.utils.render.Render2D.drawAnnularSegment
import com.laybalt.utils.render.Render2D.drawCenteredString
import com.laybalt.utils.render.Render2D.drawLine
import com.laybalt.utils.render.Render2D.drawRect
import com.laybalt.utils.render.RenderHelper.width
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.protocol.game.*
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.awt.Color
import kotlin.math.*

object PetMenu: Feature("Replaces the Pets inventory with a custom pet wheel and adds pet keybinds (also usable in the vanilla menu)."), ICustomMenu {
    private val wheelEnabled by ToggleSetting("Pet Wheel", true).withDescription("Replaces the Pets menu with the pet wheel. When off, the vanilla Pets menu is kept and only the keybinds are active.").section("Settings")
    private val menuScale by SliderSetting("Wheel Scale", 100, 70, 135, 5, "%").showIf { wheelEnabled.value }
    private val showKeyLabels by ToggleSetting("Show Key Labels", true)
    private val favouritePetsOnly by ToggleSetting("Favourite Pets Only").withDescription("Only shows pets favourited in Hypixel's Pets Menu.")

    private val segmentColor by ColorSetting("Segment Color", Color(15, 15, 15, 200)).section("Colors")
    private val hoverColor by ColorSetting("Hover Color", Color(255, 255, 255, 30))
    private val separatorColor by ColorSetting("Separator Color", Color(255, 255, 255, 40))

    private val unequipKey by KeybindSetting("Unequip").withDescription("Despawns the currently equipped pet while the Pets menu is open.").section("Menu Keybinds")
    private val nextPageKey by KeybindSetting("Next Page").withDescription("Goes to the next page: the next wheel page first, then Hypixel's next Pets page (slot 53).")
    private val previousPageKey by KeybindSetting("Previous Page").withDescription("Goes to the previous page: the previous wheel page first, then Hypixel's previous Pets page (slot 45).")
    private val disableUnequip by ToggleSetting("Disable Unequip").withDescription("Pressing a pet keybind on the pet that is already equipped does nothing instead of despawning it. Does not affect the Unequip key or normal clicking.")
    private val closeIfEquipped by ToggleSetting("Close If Equipped").withDescription("Pressing a pet keybind on the pet that is already equipped closes the Pets menu instead.")

    private val useHotbarBinds by ToggleSetting("Use Hotbar Binds").withDescription("Uses your vanilla hotbar keys (1-9) instead of the Pet Slot keybinds below.").section("Keybinds")
    private val keybinds = (1 .. PETS_PER_WHEEL).mapIndexed { index, slot ->
        KeybindSetting("Pet Slot $slot", InputConstants.KEY_1 + index)
            .hideIf { useHotbarBinds.value }.apply(configSettings::add)
    }

    private val petMenuRegex = Regex("^(?:\\(\\d+/\\d+\\) )?Pets(?: \\(\\d+/\\d+\\))?$")
    private val pageRegex = Regex("\\((\\d+)/(\\d+)\\)")
    private const val EQUIPPED_LORE = "Click to despawn!"
    private var openLastWheelPage = false
    private val petSlots = (10 .. 43).filter { it % 9 in 1 .. 7 }

    private const val PETS_PER_WHEEL = 9

    private var wheelPage = 0
    private var lastContainerId = - 1
    private var lastClickAt = 0L
    private var tempDisabled = false
    private var clicked = false

    override fun init() {
        register<MainThreadPacketReceivedEvent.Pre> {
            if (event.packet is ClientboundOpenScreenPacket) {
                if (! event.packet.title.unformattedText.matches(petMenuRegex)) return@register
                val currentTittle = mc.gui.screen()?.title?.unformattedText.orEmpty()
                if (currentTittle.startsWith("Loadout") || currentTittle.endsWith("Loadouts")) return@register
                lastContainerId = event.packet.containerId
                wheelPage = 0
            }
            else if (event.packet is ClientboundContainerClosePacket && lastContainerId != - 1) {
                lastContainerId = - 1
                wheelPage = 0
                tempDisabled = false
                clicked = false
            }
        }

        // after "Previous Page" switched to the previous Hypixel page, open the wheel on that page's last wheel page
        register<ContainerFullyOpenedEvent> {
            if (! openLastWheelPage || event.windowId != lastContainerId) return@register
            openLastWheelPage = false
            val count = petSlots.count { index -> event.items[index]?.let(::isListedPet) == true }
            wheelPage = pageCount(count) - 1
        }

        register<WorldChangeEvent> {
            openLastWheelPage = false
        }

        register<PacketEvent.Sent> {
            if (event.packet is ServerboundContainerClosePacket && lastContainerId != - 1) {
                openLastWheelPage = false
                lastContainerId = - 1
                wheelPage = 0
                tempDisabled = false
                clicked = false
            }
        }

        register<ScreenEvent.PreRender> {
            val screen = event.screen as? AbstractContainerScreen<*> ?: return@register
            if (! isActive()) return@register
            event.isCanceled = true

            renderWheel(event.context, screen, event.mouseX, event.mouseY)
        }

        register<ContainerEvent.MouseClick> {
            if (! isActive()) {
                // vanilla Pets menu (wheel turned off or "Vanilla Menu" pressed): only the keybinds are handled
                if (! inPetMenu(event.screen)) return@register
                if (event.button == InputConstants.MOUSE_BUTTON_LEFT || event.button == InputConstants.MOUSE_BUTTON_RIGHT) return@register
                if (handleKeybind(event.screen, event.button, mouse = true, wheel = false)) event.cancel()
                return@register
            }

            val buttonText = "Vanilla Menu"
            val buttonWidth = buttonText.width() + 16f
            val buttonHeight = 18f
            val buttonX = Resolution.width - buttonWidth - 5f
            val buttonY = Resolution.height - buttonHeight - 5f

            val rx = Resolution.getMouseX(event.mouseX)
            val ry = Resolution.getMouseY(event.mouseY)
            if (rx >= buttonX && rx <= buttonX + buttonWidth && ry >= buttonY && ry <= buttonY + buttonHeight) {
                event.cancel()
                tempDisabled = true
                return@register
            }

            event.cancel()

            val visiblePets = petsOnCurrentPage(petSlots(event.screen))
            val layout = wheelLayout(visiblePets.size)
            val pet = hoveredWheelIndex(event.mouseX, event.mouseY, layout)?.let(visiblePets::getOrNull)

            if (event.button == InputConstants.MOUSE_BUTTON_RIGHT && InputConstants.isKeyDown(mc.window, InputConstants.KEY_LSHIFT) && pet != null) {
                val now = System.currentTimeMillis()
                if (now - lastClickAt >= 300) {
                    lastClickAt = now
                    GuiUtils.clickSlot(pet.index, GuiUtils.ButtonType.LEFT, shift = true)
                }
                return@register
            }

            if (handleKeybind(event.screen, event.button, mouse = true, wheel = true)) return@register
            if (event.button == InputConstants.MOUSE_BUTTON_LEFT && pet != null) click(event.screen, pet.index)
        }

        register<ContainerEvent.Keyboard> {
            if (! isActive()) {
                if (! inPetMenu(event.screen)) return@register
                if (handleKeybind(event.screen, event.key, mouse = false, wheel = false)) event.cancel()
                return@register
            }
            if (clicked) {
                clicked = false
                player.closeContainer()
            }

            if (handleKeybind(event.screen, event.key, mouse = false, wheel = true)) event.cancel()
        }

        register<ContainerEvent.MouseScroll> {
            if (! isActive() || event.verticalAmount == 0.0) return@register
            event.cancel()

            val pages = pageCount(petSlots(event.screen).size)
            if (pages <= 1) return@register
            wheelPage = Math.floorMod(wheelPage + if (event.verticalAmount < 0.0) 1 else - 1, pages)
        }

        register<ContainerEvent.Close> {
            if (! event.screen.title.unformattedText.matches(petMenuRegex)) return@register
            // Changing Hypixel page opens a new container: setScreen() then calls removed() on the OLD page's screen,
            // which posts this event after lastContainerId already points at the new page. Only reset for the tracked one.
            if (event.screen.menu.containerId != lastContainerId) return@register
            lastContainerId = - 1
            wheelPage = 0
            tempDisabled = false
            clicked = false
        }
    }

    private fun renderWheel(ctx: GuiGraphicsExtractor, screen: AbstractContainerScreen<*>, vanillaMouseX: Int, vanillaMouseY: Int) {
        Resolution.push(ctx)

        val pets = petSlots(screen)
        val pages = pageCount(pets.size)
        wheelPage = wheelPage.coerceIn(0, pages - 1)

        val visiblePets = petsOnCurrentPage(pets)
        val layout = wheelLayout(visiblePets.size)

        val hoveredIndex = hoveredWheelIndex(vanillaMouseX.toDouble(), vanillaMouseY.toDouble(), layout)
        val activePet = visiblePets.firstOrNull { slot -> slot.item.lore.any { it.removeFormatting().contains("Click to despawn!", ignoreCase = true) } }
        val selectedPet = hoveredIndex?.let(visiblePets::get) ?: activePet

        ctx.drawRect(0, 0, Resolution.width, Resolution.height, Color.BLACK.withAlpha(150))

        visiblePets.forEachIndexed { index, pet ->
            drawRingSegment(ctx, layout, index, segmentColor.value)
            if (pet === activePet) drawRingSegment(ctx, layout, index, ClickGui.accentColor.value)
            if (index == hoveredIndex) drawRingSegment(ctx, layout, index, hoverColor.value)
        }

        drawSegmentSeparators(ctx, layout)

        visiblePets.forEachIndexed { index, pet ->
            drawPetInSegment(ctx, pet, index, layout, index == hoveredIndex)
        }
        if (selectedPet != null) drawCenter(ctx, selectedPet, selectedPet === activePet, layout)

        ItemRenderer.endItemRendererBatch(ctx)

        ctx.drawCenteredString(
            "Pets ${wheelPage + 1}/$pages",
            layout.centerX,
            layout.centerY - layout.outerRadius - 19f,
            scale = 0.85f
        )

        val buttonText = "Vanilla Menu"
        val buttonWidth = buttonText.width() + 16f
        val buttonHeight = 18f
        val buttonX = Resolution.width - buttonWidth - 5f
        val buttonY = Resolution.height - buttonHeight - 5f

        val rx = Resolution.getMouseX(vanillaMouseX.toDouble())
        val ry = Resolution.getMouseY(vanillaMouseY.toDouble())
        val hoveredButton = rx >= buttonX && rx <= buttonX + buttonWidth && ry >= buttonY && ry <= buttonY + buttonHeight

        ctx.drawRect(buttonX, buttonY, buttonWidth, buttonHeight, if (hoveredButton) hoverColor.value else segmentColor.value)
        ctx.drawCenteredString(buttonText, buttonX + buttonWidth / 2f, buttonY + buttonHeight / 2f - 3.5f)

        Resolution.pop(ctx)
    }

    private fun drawRingSegment(ctx: GuiGraphicsExtractor, layout: WheelLayout, index: Int, color: Color) {
        val centerAngle = - PI / 2.0 + index * layout.segmentAngle
        ctx.drawAnnularSegment(
            layout.centerX, layout.centerY, layout.innerRadius, layout.outerRadius,
            centerAngle - layout.segmentAngle / 2.0, centerAngle + layout.segmentAngle / 2.0, color
        )
    }

    private fun drawSegmentSeparators(ctx: GuiGraphicsExtractor, layout: WheelLayout) {
        if (layout.segmentCount <= 1) return
        repeat(layout.segmentCount) { index ->
            val angle = - PI / 2.0 - layout.segmentAngle / 2.0 + index * layout.segmentAngle
            val cosAngle = cos(angle).toFloat()
            val sinAngle = sin(angle).toFloat()
            val innerX = layout.centerX + cosAngle * (layout.innerRadius - 1f)
            val innerY = layout.centerY + sinAngle * (layout.innerRadius - 1f)
            val outerX = layout.centerX + cosAngle * (layout.outerRadius + 1f)
            val outerY = layout.centerY + sinAngle * (layout.outerRadius + 1f)
            ctx.drawLine(innerX, innerY, outerX, outerY, separatorColor.value, 2f)
        }
    }

    private fun drawPetInSegment(ctx: GuiGraphicsExtractor, slot: Slot, index: Int, layout: WheelLayout, hovered: Boolean) {
        val angle = - PI / 2.0 + index * layout.segmentAngle
        val cosAngle = cos(angle).toFloat()
        val sinAngle = sin(angle).toFloat()
        val itemRadius = (layout.innerRadius + layout.outerRadius) / 2f
        val itemScale = 1.5f * 1.5f * if (hovered) 1.12f else 1f
        drawCenteredItem(ctx, slot.item, layout.centerX + cosAngle * itemRadius, layout.centerY + sinAngle * itemRadius, itemScale)

        if (showKeyLabels.value) {
            val keyName = run {
                if (useHotbarBinds.value) {
                    val keybind = mc.options.keyHotbarSlots.getOrNull(index) as? IKeyMapping
                    keybind?.key?.let { key ->
                        val name = key.displayName.string.uppercase()
                        if (key.type == InputConstants.Type.MOUSE) name.replace("BUTTON ", "M") else name
                    }
                }
                else keybinds.getOrNull(index)?.displayName()
            } ?: return

            val keyRadius = layout.outerRadius - 12f
            val keyX = layout.centerX + cosAngle * keyRadius
            val keyY = layout.centerY + sinAngle * keyRadius
            val keyWidth = keyName.width()
            val textScale = min(0.68f, 24f / keyWidth.coerceAtLeast(1))
            val badgeWidth = (keyWidth * textScale + 8f).coerceAtLeast(12f)
            ctx.drawRect(keyX - badgeWidth / 2f, keyY - 6, badgeWidth, 12, Color(15, 15, 15, 200))
            if (hovered) ctx.drawRect(keyX - badgeWidth / 2f, keyY - 6, badgeWidth, 12, hoverColor.value)
            ctx.drawCenteredString(keyName, keyX, keyY - 3.5f, scale = textScale)
        }
    }

    private fun drawCenter(ctx: GuiGraphicsExtractor, selected: Slot, active: Boolean, layout: WheelLayout) {
        val contentScale = (layout.innerRadius / 54f).coerceIn(0.78f, 1.12f)
        val availableTextWidth = (layout.innerRadius * 2f - 14f).coerceAtLeast(36f)
        val centerIconScale = min(1.65f * 1.5f, layout.innerRadius * 0.62f / 16f)
        drawCenteredItem(ctx, selected.item, layout.centerX, layout.centerY - 14f * contentScale, centerIconScale)

        fun drawText(text: String, y: Float, color: Color, maxScale: Float) = ctx.drawCenteredString(
            text, layout.centerX, y, color, min(maxScale * contentScale, availableTextWidth / text.width().coerceAtLeast(1))
        )

        val nameY = layout.centerY + 3f * contentScale
        drawText(selected.item.hoverName.formattedText, nameY, Color.WHITE, 0.78f)

        val heldItemY = nameY + 9.5f * contentScale
        val heldItem = selected.item.lore.firstNotNullOfOrNull {
            it.substringAfter("Held Item:", "").trim().takeIf(String::isNotEmpty)
        } ?: "None"
        drawText("§fPet Item: $heldItem", heldItemY, Color.WHITE, 0.62f)
        if (active) drawText("ACTIVE", heldItemY + 10f * contentScale, Color.GREEN, 0.6f)
    }

    /**
     * @param wheel true while the wheel replaces the menu (pet keys are relative to the current wheel page,
     * page keys move through wheel pages first); false for the vanilla Pets menu.
     */
    private fun handleKeybind(screen: AbstractContainerScreen<*>, code: Int, mouse: Boolean, wheel: Boolean): Boolean {
        if (nextPageKey.matches(code, mouse)) return changePage(screen, next = true, wheel)
        if (previousPageKey.matches(code, mouse)) return changePage(screen, next = false, wheel)

        if (unequipKey.matches(code, mouse)) {
            val equipped = (10 .. 42).firstOrNull { screen.menu.slots.getOrNull(it)?.let(::isEquipped) == true }
            if (equipped == null) ChatUtils.modMessage("&cCouldn't find an equipped pet on this page.")
            else click(screen, equipped)
            return true
        }

        val index = if (useHotbarBinds.value) {
            val type = if (mouse) InputConstants.Type.MOUSE else InputConstants.Type.KEYSYM
            mc.options.keyHotbarSlots.take(PETS_PER_WHEEL).withIndex().find {
                (it.value as IKeyMapping).key.let { key -> key.type == type && key.value == code }
            }?.index ?: - 1
        }
        else keybinds.indexOfFirst { it.matches(code, mouse) }

        if (index < 0) return false

        val page = if (wheel) wheelPage else 0
        val slot = petSlots(screen).getOrNull(page * PETS_PER_WHEEL + index) ?: return true

        if (isEquipped(slot) && (closeIfEquipped.value || disableUnequip.value)) {
            ChatUtils.modMessage("&cThat pet is already equipped!")
            if (closeIfEquipped.value) player.closeContainer()
            return true
        }

        click(screen, slot.index)
        return true
    }

    private fun changePage(screen: AbstractContainerScreen<*>, next: Boolean, wheel: Boolean): Boolean {
        if (wheel) {
            val pages = pageCount(petSlots(screen).size)
            wheelPage = wheelPage.coerceIn(0, pages - 1)
            if (next && wheelPage < pages - 1) return true.also { wheelPage ++ }
            if (! next && wheelPage > 0) return true.also { wheelPage -- }
        }

        val match = pageRegex.find(screen.title.unformattedText)
        val current = match?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val total = match?.groupValues?.get(2)?.toIntOrNull() ?: 1

        if (next && current >= total) return true.also { ChatUtils.modMessage("&cYou are already on the last page.") }
        if (! next && current <= 1) return true.also { ChatUtils.modMessage("&cYou are already on the first page.") }

        val now = System.currentTimeMillis()
        if (now - lastClickAt < 300) return true
        lastClickAt = now
        openLastWheelPage = wheel && ! next
        GuiUtils.clickSlot(if (next) 53 else 45, GuiUtils.ButtonType.LEFT)
        return true
    }

    private fun isEquipped(slot: Slot) = slot.item.lore.any { it.removeFormatting().contains(EQUIPPED_LORE, ignoreCase = true) }

    private fun inPetMenu(screen: AbstractContainerScreen<*>) =
        lastContainerId != - 1 && player.containerMenu === screen.menu && screen.menu.containerId == lastContainerId

    private fun click(screen: AbstractContainerScreen<*>, slotIndex: Int) {
        if (player.containerMenu !== screen.menu) return
        val slot = screen.menu.slots.getOrNull(slotIndex) ?: return
        if (slot.item.isEmpty || slot.index != slotIndex) return
        val now = System.currentTimeMillis()
        if (now - lastClickAt < 300) return

        lastClickAt = now
        GuiUtils.clickSlot(slotIndex, GuiUtils.ButtonType.LEFT)
        if (LayBaltQOL.isCheat) player.closeContainer() else clicked = true
    }

    private fun wheelLayout(segmentCount: Int): WheelLayout {
        val desiredRadius = 138f * (menuScale.value / 100f)
        val maxRadius = min((Resolution.height - 96f) / 2f, (Resolution.width - 220f) / 2f).coerceAtLeast(82f)
        val outerRadius = min(desiredRadius, maxRadius)
        return WheelLayout(
            centerX = Resolution.width / 2f,
            centerY = Resolution.height / 2f - 8f,
            innerRadius = outerRadius * 0.55f,
            outerRadius = outerRadius,
            segmentCount = segmentCount
        )
    }

    private fun hoveredWheelIndex(mouseX: Double, mouseY: Double, layout: WheelLayout): Int? {
        if (layout.segmentCount == 0) return null
        val x = Resolution.getMouseX(mouseX) - layout.centerX
        val y = Resolution.getMouseY(mouseY) - layout.centerY
        if (hypot(x.toDouble(), y.toDouble()) <= layout.innerRadius) return null
        return Math.floorMod(
            floor(((atan2(y, x) + PI / 2.0 + layout.segmentAngle / 2.0) / layout.segmentAngle)).toInt(),
            layout.segmentCount
        )
    }

    private fun drawCenteredItem(ctx: GuiGraphicsExtractor, item: ItemStack, x: Float, y: Float, scale: Float) {
        ItemRenderer.drawBatchedItemStack(ctx, item, (x - 8f).roundToInt(), (y - 8f).roundToInt(), scale)
    }

    private fun isListedPet(item: ItemStack): Boolean {
        val isHead = ! item.isEmpty && item.`is`(Items.PLAYER_HEAD)
        val favorite = item.hoverName.unformattedText.startsWith("⭐ ")
        return isHead && if (favouritePetsOnly.value) favorite else true
    }

    private fun petSlots(screen: AbstractContainerScreen<*>) = petSlots.mapNotNull { index ->
        screen.menu.slots.getOrNull(index)?.takeIf { isListedPet(it.item) }
    }

    private fun petsOnCurrentPage(pets: List<Slot>) = pets.drop(wheelPage * PETS_PER_WHEEL).take(PETS_PER_WHEEL)
    private fun pageCount(petCount: Int) = Math.ceilDiv(petCount, PETS_PER_WHEEL).coerceAtLeast(1)
    override fun isActive() = wheelEnabled.value && ! tempDisabled && lastContainerId != - 1

    private class WheelLayout(
        val centerX: Float,
        val centerY: Float,
        val innerRadius: Float,
        val outerRadius: Float,
        val segmentCount: Int
    ) {
        val segmentAngle = if (segmentCount == 0) 0.0 else PI * 2.0 / segmentCount
    }
}