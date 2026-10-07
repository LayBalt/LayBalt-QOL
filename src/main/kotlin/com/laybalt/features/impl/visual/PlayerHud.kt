// Colours, icons, number formatting, overflow-mana options and Hide XP Level ported from Odin PlayerDisplay
// (https://github.com/odtheking/Odin), BSD-3-Clause, Copyright (c) 2025 odtheking
package com.laybalt.features.impl.visual

import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.MultiCheckboxSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.ActionBarMessageEvent
import com.laybalt.features.Feature
import com.laybalt.utils.ActionBarParser
import com.laybalt.utils.NumbersUtils
import com.laybalt.utils.location.LocationUtils
import com.laybalt.utils.render.Render2D.drawString
import com.laybalt.utils.render.RenderHelper.height
import com.laybalt.utils.render.RenderHelper.width
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.awt.Color
import java.util.Locale

object PlayerHud: Feature(name = "Player HUD", description = "Displays your stats as moveable HUD elements.") {
    private val elements by MultiCheckboxSetting("Elements", mutableMapOf(
        "Health" to false,
        "Defense" to false,
        "Mana" to false,
        "Overflow Mana" to false,
        "Vitality" to false,
        "Effective HP" to false,
        "Speed" to false
    ))

    private val hideFromActionbar by MultiCheckboxSetting("Hide from Actionbar", mutableMapOf(
        "Health" to false,
        "Defense" to false,
        "Mana" to false,
        "Overflow Mana" to false,
        "Vitality" to false,
        "Dungeon Room Secrets" to false,
        "Armor Stacks" to false,
        "Terminator Stacks" to false
    ))

    @JvmStatic val hideFoodbar by ToggleSetting("Hide Food bar").withDescription("Hides the food bar.").section("Extras")
    @JvmStatic val hideHealthbar by ToggleSetting("Hide Health bar").withDescription("Hides the health bar.")
    @JvmStatic val hideArmorbar by ToggleSetting("Hide Armor bar").withDescription("Hides the defense bar.").hideIf { hideHealthbar.value }
    @JvmStatic val hideXpLevel by ToggleSetting("Hide XP Level").withDescription("Hides the vanilla experience bar and level number.")

    private val showIcons by ToggleSetting("Show Icons", true).withDescription("Adds the stat icon (❤ ✎ ʬ ❈ ♨ ✦) after each number.").section("Display")
    private val thousandsSeparators by ToggleSetting("Thousands Separators", true).withDescription("Formats numbers with commas, e.g. 12,345 instead of 12345.")
    private val separateOverflow by ToggleSetting("Separate Overflow Mana", true).withDescription("Shows overflow mana in its own element. When off, it is appended to the Mana element.")
    private val hideZeroOverflow by ToggleSetting("Hide 0 Overflow", true).withDescription("Hides overflow mana while it is 0.")

    private val healthColor by ColorSetting("Health Color", Color(255, 85, 85), false).withDescription("Colour of the Health element.").section("Colors")
    private val absorptionColor by ColorSetting("Absorption Color", Color(255, 255, 85), false).withDescription("Colour of the current health while it is above your max health (absorption).")
    private val manaColor by ColorSetting("Mana Color", Color(85, 255, 255), false).withDescription("Colour of the Mana element.")
    private val overflowColor by ColorSetting("Overflow Mana Color", Color(0, 170, 170), false).withDescription("Colour of the overflow mana.")
    private val defenseColor by ColorSetting("Defense Color", Color(85, 255, 85), false).withDescription("Colour of the Defense element.")
    private val vitalityColor by ColorSetting("Vitality Color", Color(170, 0, 0), false).withDescription("Colour of the Vitality element.")
    private val ehpColor by ColorSetting("Effective HP Color", Color(0, 170, 0), false).withDescription("Colour of the Effective HP element.")
    private val speedColor by ColorSetting("Speed Color", Color.WHITE, false).withDescription("Colour of the Speed element.")

    override fun init() {
        hudElement(
            this.name + " Health",
            { elements["Health"] },
            { LocationUtils.inSkyblock }
        ) { context, example ->
            val current = if (example) 3452 else ActionBarParser.currentHealth
            val max = if (example) 2452 else ActionBarParser.maxHealth
            drawParts(context,
                num(current) to if (current > max) absorptionColor.value else healthColor.value,
                "/${num(max)}${icon("❤")}" to healthColor.value
            )
        }

        hudElement(
            this.name + " Defense",
            { elements["Defense"] },
            { LocationUtils.inSkyblock }
        ) { context, example ->
            val def = if (example) 5001 else ActionBarParser.currentDefense
            drawParts(context, "${num(def)}${icon("❈")}" to defenseColor.value)
        }

        hudElement(
            this.name + " Mana",
            { elements["Mana"] },
            { LocationUtils.inSkyblock }
        ) { context, example ->
            val current = if (example) 2452 else ActionBarParser.currentMana
            val max = if (example) 2452 else ActionBarParser.maxMana
            val overflow = if (example) 600 else ActionBarParser.overflowMana
            val parts = mutableListOf("${num(current)}/${num(max)}${icon("✎")}" to manaColor.value)
            if (! separateOverflow.value && (overflow > 0 || ! hideZeroOverflow.value)) parts += " ${num(overflow)}${icon("ʬ")}" to overflowColor.value
            drawParts(context, *parts.toTypedArray())
        }

        hudElement(
            this.name + " Overflow Mana",
            { elements["Overflow Mana"] && separateOverflow.value },
            { LocationUtils.inSkyblock && (ActionBarParser.overflowMana > 0 || ! hideZeroOverflow.value) }
        ) { context, example ->
            val overflow = if (example) 600 else ActionBarParser.overflowMana
            drawParts(context, "${num(overflow)}${icon("ʬ")}" to overflowColor.value)
        }

        hudElement(
            this.name + " Vitality",
            { elements["Vitality"] },
            { LocationUtils.inSkyblock && ActionBarParser.isVitalityShown }
        ) { context, example ->
            val current = if (example) 82 else ActionBarParser.currentVitality
            val max = if (example) 122 else ActionBarParser.maxVitality
            drawParts(context, "${num(current)}/${num(max)}${icon("§l♨")}" to vitalityColor.value)
        }

        hudElement(
            this.name + " Effective HP",
            { elements["Effective HP"] },
            { LocationUtils.inSkyblock }
        ) { context, example ->
            val text = if (example) "7.3m" else NumbersUtils.format(ActionBarParser.effectiveHP)
            drawParts(context, text to ehpColor.value)
        }

        hudElement(
            this.name + " Speed",
            { elements["Speed"] },
            { LocationUtils.inSkyblock }
        ) { context, example ->
            val speed = if (example) 400 else ActionBarParser.currentSpeed
            drawParts(context, "${num(speed)}${icon("✦")}" to speedColor.value)
        }

        register<ActionBarMessageEvent> {
            if (! LocationUtils.inSkyblock) return@register
            var result = event.formattedText

            if (hideFromActionbar["Health"]) result = result.replace(ActionBarParser.HP_REGEX, "")
            if (hideFromActionbar["Defense"]) result = result.replace(ActionBarParser.DEF_REGEX, "")
            if (hideFromActionbar["Mana"]) result = result.replace(ActionBarParser.MANA_REGEX, "")
            if (hideFromActionbar["Overflow Mana"]) result = result.replace(ActionBarParser.OVERFLOW_REGEX, "")
            if (hideFromActionbar["Vitality"]) result = result.replace(ActionBarParser.VITALITY_REGEX, "")
            if (hideFromActionbar["Dungeon Room Secrets"]) result = result.replace(ActionBarParser.SECRETS_REGEX, "")
            if (hideFromActionbar["Armor Stacks"]) result = result.replace(ActionBarParser.STACKS_REGEX, "")
            if (hideFromActionbar["Terminator Stacks"]) result = result.replace(ActionBarParser.SALVATION_REGEX, "")

            event.message = result
        }
    }

    /** Called from MixinHudPlayerHud / MixinExperienceBarPlayerHud. */
    @JvmStatic
    fun shouldHideXp() = enabled && hideXpLevel.value

    private fun icon(icon: String) = if (showIcons.value) icon else ""

    private fun num(n: Int) = if (thousandsSeparators.value) String.format(Locale.US, "%,d", n) else n.toString()

    /** Draws each part right after the previous one in its own colour, returns the element size. */
    private fun drawParts(context: GuiGraphicsExtractor, vararg parts: Pair<String, Color>): Pair<Number, Number> {
        var x = 0
        for ((text, color) in parts) {
            context.drawString(text, x, 0, color)
            x += text.width()
        }
        return x to parts.first().first.height()
    }
}
