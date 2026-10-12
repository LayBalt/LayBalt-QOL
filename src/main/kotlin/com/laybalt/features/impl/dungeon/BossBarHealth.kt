package com.laybalt.features.impl.dungeon

import com.laybalt.config.types.ColorCodeSetting
import com.laybalt.config.types.DropdownSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.BossBarRenderEvent
import com.laybalt.features.Feature
import com.laybalt.mixin.ILerpingBossEvent
import com.laybalt.utils.ChatUtils.unformattedText
import com.laybalt.utils.location.LocationUtils
import gg.essential.universal.ChatColor
import net.minecraft.network.chat.Component
import java.util.*
import kotlin.math.roundToInt

/**
 * Adds the boss health to the dungeon boss bar name.
 *
 * The name is changed through [BossBarRenderEvent] (posted by MixinBossHealthOverlayName); Blood Camp's
 * "Watcher Bar" uses the same event, and whichever runs first wins because the other no longer sees the plain name.
 *
 * @see com.laybalt.mixin.MixinBossHealthOverlayBossBarHealth (Hide BossBar)
 */
object BossBarHealth: Feature(name = "Bossbar Health", description = "Shows the health number of the bossbar boss.") {
    private val theWatcher by ToggleSetting("The Watcher", true).withDescription("Shows how many of The Watcher's mobs are left.")
    private val f4Thorn by ToggleSetting("Thorn", true).withDescription("Shows Thorn's remaining lives in Floor 4 and Master Mode 4.")
    private val f7Withers by ToggleSetting("F7 Withers", true).withDescription("Shows the health of Maxor, Storm, Goldor and Necron.")
    @JvmStatic val hideBossBar by ToggleSetting("Hide BossBar").withDescription("Hides only the health bar under the name; the name and health text stay.")

    private val format by DropdownSetting("Format", 0, listOf("Current / Max", "Current", "Percent", "Current / Max + Percent"))
        .withDescription("What the health text shows.").section("Style")
    private val healthColor by ColorCodeSetting("Health Color", ChatColor.GREEN)
        .withDescription("Colour of the health numbers.")
    private val showHeart by ToggleSetting("Heart Symbol", true)
        .withDescription("Adds a red heart after the health.")

    @JvmStatic
    fun shouldHideBar() = enabled && hideBossBar.value

    override fun init() {
        register<BossBarRenderEvent> {
            if (! LocationUtils.inDungeon) return@register
            val maxHealth = getMaxHealth(event.name) ?: return@register

            val percent = (event.bossEvent as ILerpingBossEvent).getTargetPrecent()
            val currentHealth = (percent * maxHealth).roundToInt().toFloat()
            val c = "§${healthColor.value.char}"
            val pct = "${(percent * 100).roundToInt()}%"
            val text = when (format.value) {
                1 -> c + formatHealth(currentHealth)
                2 -> c + pct
                3 -> c + formatHealth(currentHealth) + "§7/" + c + formatHealth(maxHealth) + " §8(" + c + pct + "§8)"
                else -> c + formatHealth(currentHealth) + "§7/" + c + formatHealth(maxHealth)
            }

            event.name = event.name.copy().append(Component.literal(" §r§8- $text" + if (showHeart.value) "§c❤" else ""))
        }
    }

    @JvmStatic
    fun getMaxHealth(nameComponent: Component): Float? {
        val name = nameComponent.unformattedText
        val isMaster = LocationUtils.isMasterMode

        return when (name) {
            "The Watcher" if theWatcher.value -> 8F + (LocationUtils.dungeonFloorNumber?.toFloat() ?: 0F)
            "Thorn" if f4Thorn.value -> if (isMaster) 6F else 4F
            "Maxor" if f7Withers.value -> if (isMaster) 800_000_000F else 100_000_000F
            "Storm" if f7Withers.value -> if (isMaster) 1_000_000_000F else 400_000_000F
            "Goldor" if f7Withers.value -> if (isMaster) 1_200_000_000F else 750_000_000F
            "Necron" if f7Withers.value -> if (isMaster) 1_400_000_000F else 1_000_000_000F
            else -> null
        }
    }

    @JvmStatic
    fun formatHealth(health: Float): String {
        if (health >= 1_000_000_000) {
            val h = health / 1_000_000_000F
            if (h % 1.0f == 0f) return h.toInt().toString() + "B"
            return String.format(Locale.US, "%.1fB", h)
        }
        else if (health >= 1_000_000) return (health / 1_000_000F).toInt().toString() + "M"
        else if (health >= 1000) return (health / 1000f).toInt().toString() + "k"
        else return health.toInt().toString()
    }
}
