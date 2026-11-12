package com.laybalt.features.impl.general

//#if CHEAT
//#endif
import com.laybalt.config.types.KeybindSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.*
import com.laybalt.features.Feature
import com.laybalt.mixin.IKeyMapping
import com.laybalt.utils.*
import com.laybalt.utils.ChatUtils.unformattedText
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.network.protocol.game.*
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

object WardrobeKeybinds: Feature("Make it possible to bind armor slots to your keyboard.") {
    //#if CHEAT
    private val closeAfterUse by ToggleSetting("Auto Close On Use")

    //#endif
    private val preventUnequip by ToggleSetting("Prevent Unequip")
    private val useHotbarBinds by ToggleSetting("Use Hotbar Binds")
    private val keybinds = (1 .. 9).mapIndexed { index, slot ->
        KeybindSetting("Wardrobe Slot $slot", InputConstants.KEY_1 + index)
            .hideIf { useHotbarBinds.value }.apply(configSettings::add)
    }

    private val wardrobeMenuRegex = Regex("""^\(\d+/\d+\) Armor Sets$""")
    private var lastClick = System.currentTimeMillis()
    private var inWardrobeMenu = false

    //#if CHEAT
    private var pendingAutoClose = false

    //#endif
    private val keyMap = listOf(36, 37, 38, 39, 40, 41, 42, 43, 44)

    /**
     * The hotbar slot bound to [code], read live so rebinding takes effect without a restart.
     * An unbound hotbar key (InputConstants.UNKNOWN) never matches, and a mouse bind never matches a keyboard code (Odin d2d267cd).
     */
    private fun hotbarIndex(code: Int, mouse: Boolean): Int? {
        val type = if (mouse) InputConstants.Type.MOUSE else InputConstants.Type.KEYSYM
        return mc.options.keyHotbarSlots.indexOfFirst {
            val key = (it as IKeyMapping).key
            key != InputConstants.UNKNOWN && key.type == type && key.value == code
        }.takeUnless { it == - 1 }
    }

    override fun init() {
        register<MainThreadPacketReceivedEvent.Pre> {
            if (event.packet is ClientboundOpenScreenPacket) {
                inWardrobeMenu = event.packet.title.unformattedText.matches(wardrobeMenuRegex)
            }
            else if (event.packet is ClientboundContainerClosePacket && inWardrobeMenu) {
                inWardrobeMenu = false
            }
        }

        register<PacketEvent.Sent> {
            if (event.packet is ServerboundContainerClosePacket && inWardrobeMenu) {
                inWardrobeMenu = false
                //#if CHEAT
                pendingAutoClose = false
                //#endif
            }
        }

        //#if CHEAT
        register<MainThreadPacketReceivedEvent.Post> {
            if (! pendingAutoClose) return@register
            val packet = event.packet as? ClientboundOpenScreenPacket ?: return@register
            if (! packet.title.unformattedText.matches(wardrobeMenuRegex)) return@register

            pendingAutoClose = false
            player.closeContainer()
        }
        //#endif

        register<ContainerEvent.Keyboard> {
            if (! inWardrobeMenu) return@register
            if (System.currentTimeMillis() - lastClick < 300) return@register
            if (event.key.equalsOneOf(InputConstants.KEY_ESCAPE, KeyMappingHelper.getBoundKeyOf(mc.options.keyInventory).value)) return@register
            val index = if (useHotbarBinds.value) hotbarIndex(event.key, mouse = false) ?: return@register
            else keybinds.withIndex().find { (_, key) -> key.isDown() }?.index ?: return@register
            val slot = keyMap.getOrNull(index)?.takeUnless { player.containerMenu.getSlot(it).item == ItemStack.EMPTY } ?: return@register
            event.isCanceled = true

            if (isSlotEquipped(slot) && preventUnequip.value) return@register

            GuiUtils.clickSlot(slot, GuiUtils.ButtonType.LEFT)

            lastClick = System.currentTimeMillis()
            //#if CHEAT
            if (closeAfterUse.value) closeAfterReopen()
            //#endif
        }

        register<ContainerEvent.MouseClick> {
            if (! inWardrobeMenu) return@register
            if (System.currentTimeMillis() - lastClick < 300) return@register
            if (event.button.equalsOneOf(InputConstants.MOUSE_BUTTON_LEFT, InputConstants.MOUSE_BUTTON_RIGHT, InputConstants.MOUSE_BUTTON_MIDDLE)) return@register
            val index = if (useHotbarBinds.value) hotbarIndex(event.button, mouse = true) ?: return@register
            else keybinds.withIndex().find { (_, key) -> key.isDown() }?.index ?: return@register
            val slot = keyMap.getOrNull(index)?.takeUnless { player.containerMenu.getSlot(it).item == ItemStack.EMPTY } ?: return@register
            event.isCanceled = true

            if (isSlotEquipped(slot) && preventUnequip.value) return@register

            GuiUtils.clickSlot(slot, GuiUtils.ButtonType.LEFT)

            lastClick = System.currentTimeMillis()
            //#if CHEAT
            if (closeAfterUse.value) closeAfterReopen()
            //#endif
        }
    }

    private fun isSlotEquipped(slot: Int) = player.containerMenu.slots[slot].item.`is`(Items.DYE.lime())

    //#if CHEAT
    fun closeAfterReopen() {
        player.closeContainer()
        ThreadUtils.setTimeout(3000) { pendingAutoClose = false }
        pendingAutoClose = true
    }
    //#endif
}