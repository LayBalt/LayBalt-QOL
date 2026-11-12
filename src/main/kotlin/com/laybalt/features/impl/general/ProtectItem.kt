package com.laybalt.features.impl.general

import com.mojang.blaze3d.platform.InputConstants
import com.laybalt.commands.CommandBuilder
import com.laybalt.config.PogObject
import com.laybalt.config.types.KeybindSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.ContainerEvent
import com.laybalt.event.impl.KeyboardEvent
import com.laybalt.features.Feature
import com.laybalt.init.types.ICommandProvider
import com.laybalt.mixin.IAbstractContainerScreen
import com.laybalt.ui.notification.NotificationManager
import com.laybalt.utils.ChatUtils
import com.laybalt.utils.ChatUtils.formattedText
import com.laybalt.utils.ChatUtils.unformattedText
import com.laybalt.utils.PlayerUtils
import com.laybalt.utils.equalsOneOf
import com.laybalt.utils.dungeons.DungeonListener
import com.laybalt.utils.items.ItemUtils.customData
import com.laybalt.utils.items.ItemUtils.itemUUID
import com.laybalt.utils.items.ItemUtils.lore
import com.laybalt.utils.items.ItemUtils.skyblockId
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.render.Render2D.drawString
import gg.essential.universal.UMinecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import kotlin.jvm.optionals.getOrDefault

object ProtectItem: Feature("Prevents dropping or selling important items via /protectitem or keybind."), ICommandProvider {
    private var data = PogObject("item_protection", object {
        val uuids = mutableSetOf<String>()
        val ids = mutableSetOf<String>()
    })

    private val protectNotification by ToggleSetting("Protect Notification", true).withDescription("Shows a notification on the bottom right side of the screen when the feature saved your item")
    private val protectBind by KeybindSetting("Protect Key", InputConstants.KEY_L).section("Keybind").withDescription("Press while hovering an item in an inventory to protect/unprotect it via UUID.")
    private val showProtected by ToggleSetting("Show Protected Items").withDescription("Shows protected items in container GUIs with a small indicator.")
    private val protectUUID by ToggleSetting("Protect UUID", true).withDescription("Protects the exact items you marked (matched by their unique item UUID)")
    private val protectID by ToggleSetting("Protect Skyblock ID", true).withDescription("Protects every item of a type you marked (matched by SkyBlock ID), used for items without a UUID")
    private val protectStarred by ToggleSetting("Protect Starred", true).withDescription("Automatically protects starred (dungeon-upgraded) items")
    private val protectRarity by ToggleSetting("Protect Recombobulated", true).withDescription("Automatically protects recombobulated items")

    override fun init() {
        register<ContainerEvent.SlotClick> {
            if (! enabled) return@register
            val menu = player.containerMenu

            val stack = when (event.slotId) {
                - 999 -> menu.carried
                in menu.slots.indices -> menu.slots[event.slotId].item
                else -> ItemStack.EMPTY
            }

            if (stack.isEmpty) return@register

            // NoammAddons 8ed019ee / 47d782b6: salvaging moves the item into the menu (allowed) and then clicks a button
            // that is not protected itself, so the button is blocked while a protected item sits in the menu's slots.
            if (isSalvageMenu(event.screen, stack)) {
                val rows = (menu as? ChestMenu)?.rowCount ?: return@register
                val protectedStack = menu.slots.filter { it.index < rows * 9 }.firstOrNull { slot ->
                    getProtectType(slot.item).let { it != ProtectType.None && it != ProtectType.HypixelBullshit }
                }?.item ?: return@register

                event.isCanceled = true
                if (protectNotification.value) NotificationManager.push(
                    "Salvage Blocked", "${protectedStack.hoverName.formattedText} §fis protected!", 2500L
                )

                return@register
            }

            val type = getProtectType(stack).takeUnless { it == ProtectType.None } ?: return@register

            val isThrowing = event.clickType == ContainerInput.THROW || event.slotId == - 999
            val isSelling = isSellMenu() && event.slotId in menu.slots.indices

            if (isThrowing || isSelling || type == ProtectType.HypixelBullshit) {
                if (protectNotification.value) NotificationManager.push("Action Blocked", "This item is protected!", 1500L)
                event.isCanceled = true
            }
        }

        register<ContainerEvent.Keyboard> {
            if (! protectBind.matches(event.key, mouse = false)) return@register
            if (! protectHoveredItem(event.screen)) return@register
            event.isCanceled = true
        }

        register<ContainerEvent.MouseClick> {
            if (! protectBind.matches(event.button, mouse = true)) return@register
            if (! protectHoveredItem(event.screen)) return@register
            event.isCanceled = true
        }

        register<KeyboardEvent.KeyPressed> {
            if (LocationUtils.inDungeon && DungeonListener.dungeonStarted && ! DungeonListener.dungeonEnded) return@register
            if (UMinecraft.currentScreenObj != null) return@register
            if (! mc.options.keyDrop.matches(event.keyEvent)) return@register

            if (getProtectType(player.inventory.selectedItem) != ProtectType.None) {
                if (protectNotification.value) NotificationManager.push("Action Blocked", "This item is protected!", 1500L)
                event.isCanceled = true
            }
        }

        register<ContainerEvent.Render.Tooltip> {
            if (event.stack.isEmpty) return@register
            if (event.lore.isEmpty()) return@register
            val type = getProtectType(event.stack)
            if (type != ProtectType.None) {
                event.lore.add(1, Component.literal("§aItem Protected §7(${type.name})"))
            }
        }

        register<ContainerEvent.Render.Slot.Post> {
            onSlotDraw(event.context, event.slot.item, event.slot.x, event.slot.y)
        }
    }

    override fun CommandBuilder.command() {
        setName("protectitem")
        alsoUnderLbq("protectitem")
        category(com.laybalt.commands.LbqHelp.Category.ITEMS)
        description("Protects the held item from being dropped or sold (run again to unprotect)")
        requires("Enable $name to protect items.") { enabled }
        runs {
            val heldItem = player.mainHandItem.takeUnless { it.isEmpty }
                ?: return@runs ChatUtils.modMessage("&cYou need to be holding an item.")
            protect(heldItem)
        }
    }

    private fun getProtectType(stack: ItemStack): ProtectType {
        if (stack.isEmpty) return ProtectType.None

        if (protectUUID.value) {
            val uuid = stack.itemUUID
            if (uuid.isNotBlank() && uuid in data.get().uuids) return ProtectType.UUID
        }

        if (protectID.value) {
            val id = stack.skyblockId
            if (id.isNotBlank() && id in data.get().ids) return ProtectType.SkyblockID
        }

        val data = stack.customData
        val name = stack.hoverName.unformattedText
        if (protectStarred.value && (data.getInt("upgrade_level").getOrDefault(0) > 0 || name.contains("✪"))) return ProtectType.Starred
        if (protectRarity.value && data.getInt("rarity_upgrades").getOrDefault(0) > 0) return ProtectType.RarityUpgraded
        // NoammAddons 92518b0c: the quiver display item in the last hotbar slot stays usable
        if (data.getStringOr("quiver_arrow", "false").toBoolean() && PlayerUtils.getHotbarSlot(8) != stack) return ProtectType.HypixelBullshit

        return ProtectType.None
    }

    private fun isSellMenu(): Boolean {
        return player.containerMenu.slots.take(54).any { slot ->
            if (slot.item.isEmpty) return@any false

            val isHopper = slot.item.`is`(Items.HOPPER) && slot.item.hoverName.string.contains("Sell Item")
            val hasBuyback = slot.item.lore.any { it.contains("Click to buyback") }

            isHopper || hasBuyback
        }
    }

    /** NoammAddons 8ed019ee / 47d782b6: the salvage / altar confirm button of the Salvage Items or Draconic Sacrifice menu. */
    private fun isSalvageMenu(screen: Screen, stack: ItemStack): Boolean {
        val inGui = screen.title.string.equalsOneOf("Salvage Items", "Draconic Sacrifice")
        val confirmStack = stack.hoverName.unformattedText.equalsOneOf("Salvage Items", "Confirm Salvage", "Draconic Altar")

        return inGui && confirmStack
    }

    fun onSlotDraw(context: GuiGraphicsExtractor, stack: ItemStack, x: Int, y: Int) {
        if (! enabled || ! showProtected.value || stack.isEmpty) return
        val type = getProtectType(stack)
        if (type != ProtectType.None && type != ProtectType.HypixelBullshit) {
            context.drawString("§aP", x + 1, y + 1, scale = 0.75)
        }
    }

    private fun protectHoveredItem(screen: AbstractContainerScreen<*>): Boolean {
        val stack = (screen as IAbstractContainerScreen).hoveredSlot?.item?.takeUnless { it.isEmpty } ?: return false
        protect(stack)
        return true
    }

    private fun protect(stack: ItemStack) {
        val label = stack.hoverName.formattedText
        val data = data.get()

        val (list, id) = when {
            // NoammAddons d50d1b38: a list that is not enforced would only pretend to protect the item
            stack.itemUUID.isNotBlank() -> if (protectUUID.value) data.uuids to stack.itemUUID else {
                NotificationManager.push("&cProtection Disabled", "Enable ${protectUUID.name} to protect this item.")
                return
            }
            stack.skyblockId.isNotBlank() -> if (protectID.value) data.ids to stack.skyblockId else {
                NotificationManager.push("&cProtection Disabled", "Enable ${protectID.name} to protect this item.")
                return
            }
            else -> return NotificationManager.push("Error", "Item has no protectable ID/UUID.")
        }

        if (list.remove(id)) NotificationManager.push("&cProtection Removed", "No longer protecting $label.")
        else {
            list.add(id)
            NotificationManager.push("&aProtection Added", "Now protecting $label.")
        }
    }

    private enum class ProtectType { UUID, SkyblockID, Starred, RarityUpgraded, None, HypixelBullshit }
}