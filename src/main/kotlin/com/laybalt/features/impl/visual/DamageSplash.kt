package com.laybalt.features.impl.visual

import com.laybalt.config.types.ColorCodeSetting
import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.MainThreadPacketReceivedEvent
import com.laybalt.features.Feature
import com.laybalt.utils.ChatUtils.formattedText
import com.laybalt.utils.ChatUtils.removeFormatting
import com.laybalt.utils.NumbersUtils
import com.laybalt.utils.containsOneOf
import com.laybalt.utils.location.LocationUtils
import gg.essential.universal.ChatColor
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand
import java.util.*
import kotlin.jvm.optionals.getOrNull

/**
 * Reformats SkyBlock's floating damage numbers (armor stands with a damage name).
 * The entity data packet is applied by hand with the new name and then cancelled, so vanilla never shows the old text.
 */
object DamageSplash: Feature("Reformat Skyblock's Damage Indicators.") {
    private val hideDamage by ToggleSetting("Hide Damage Nametag").withDescription("Hides all damage indicators.")
    private val uppercase by ToggleSetting("Uppercase Formatting").withDescription("Changes the damage number to uppercase.").hideIf { hideDamage.value }
    private val disableinClear by ToggleSetting("Hide in Clear").withDescription("Hides all damage indicators in dungeon clear.").hideIf { hideDamage.value }
    private val disableinBoss by ToggleSetting("Hide in Boss").withDescription("Hides all damage indicators in dungeon boss room.").hideIf { hideDamage.value }

    private val numberStyle by DropdownSetting("Number Style", 0, listOf("Short", "Full"))
        .withDescription("Short turns 1,234,567 into 1.2M, Full keeps every digit with separators.").hideIf { hideDamage.value }.section("Style")
    private val normalColor by ColorCodeSetting("Normal Color", ChatColor.DARK_AQUA)
        .withDescription("Colour of normal (non-critical) hits.").hideIf { hideDamage.value }
    private val critStyle by DropdownSetting("Crit Style", 0, listOf("Rainbow", "Gradient", "Single Color"))
        .withDescription("Rainbow gives every digit a random warm colour, Gradient fades violet to pink across the number, Single Color uses Crit Color.").hideIf { hideDamage.value }
    private val critColor by ColorCodeSetting("Crit Color", ChatColor.GOLD)
        .withDescription("Colour of critical hits when Crit Style is Single Color.").showIf { ! hideDamage.value && critStyle.value == 2 }
    private val critMarks by ToggleSetting("Crit Marks", true)
        .withDescription("Wraps critical hits in ✧ marks.").hideIf { hideDamage.value }

    private val damageRegex = Regex("[✧✯]?(\\d{1,3}(?:,\\d{3})*[⚔+✧❤♞☄✷ﬗ✯]*)")
    private val colors = listOf("§6", "§c", "§e", "§f")
    private val gradient = listOf("§5", "§d", "§d", "§c", "§6")

    @Suppress("UNCHECKED_CAST")
    override fun init() {
        register<MainThreadPacketReceivedEvent.Pre> {
            if (! LocationUtils.inSkyblock) return@register
            if (event.packet !is ClientboundSetEntityDataPacket) return@register
            val entity = level.getEntity(event.packet.id) as? ArmorStand ?: return@register
            for (entry in event.packet.packedItems) {
                val value = entry.value() as? Optional<Component> ?: continue
                val content = value.getOrNull() ?: continue
                val rawText = content.formattedText.takeIf { it.contains("§") } ?: continue
                val damageNum = damageRegex.matchEntire(rawText.removeFormatting())?.destructured?.component1() ?: continue

                val hide = hideDamage.value
                val inClear = LocationUtils.inDungeon && ! LocationUtils.inBoss && disableinClear.value
                val inBoss = LocationUtils.inBoss && disableinBoss.value

                if (hide || inClear || inBoss) {
                    entity.remove(Entity.RemovalReason.DISCARDED)
                    event.isCanceled = true
                    return@register
                }

                val number = if (numberStyle.value == 0) NumbersUtils.format(damageNum) else damageNum.takeWhile { it.isDigit() || it == ',' }
                val formattedDamage = if (uppercase.value) number.uppercase() else number

                val newNameString = if (rawText.containsOneOf("✧", "✯")) {
                    val body = when (critStyle.value) {
                        0 -> addRandomColorCodes(formattedDamage)
                        1 -> addGradient(formattedDamage)
                        else -> "§${critColor.value.char}$formattedDamage"
                    }
                    if (critMarks.value) "§f✧$body§f✧" else body
                }
                else "§${normalColor.value.char}$formattedDamage"

                entity.entityData.assignValues(event.packet.packedItems)
                entity.customName = Component.literal(newNameString)
                event.isCanceled = true
            }
        }
    }

    private fun addRandomColorCodes(inputString: String): String {
        val result = StringBuilder()
        var lastColor: String? = null

        for (char in inputString) {
            val availableColors = colors.filter { it != lastColor }
            val randomColor = availableColors.random()
            result.append(randomColor).append(char).append("§r")
            lastColor = randomColor
        }

        return result.toString()
    }

    private fun addGradient(inputString: String): String {
        val result = StringBuilder()
        val last = (inputString.length - 1).coerceAtLeast(1)
        inputString.forEachIndexed { i, char ->
            result.append(gradient[(i * (gradient.size - 1) + last / 2) / last]).append(char)
        }
        return result.toString()
    }
}
