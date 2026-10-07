package com.laybalt.features.impl.visual

import com.laybalt.config.types.ColorSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.event.impl.WorldChangeEvent
import com.laybalt.features.Feature
import com.laybalt.utils.NumbersUtils.toFixed
import com.laybalt.utils.ServerUtils
import com.laybalt.utils.render.Render2D.drawString
import com.laybalt.utils.render.RenderHelper.width
import java.awt.Color
import java.time.LocalTime
import java.time.format.DateTimeFormatter

object InfoDisplay: Feature("Displays the system time, clicks per second, FPS, TPS, and ping on screen.") {
    private val clockDisplay by ToggleSetting("Clock Display").section("Clock")
    private val seconds by ToggleSetting("Show Seconds").showIf { clockDisplay.value }
    private val clockColor by ColorSetting("Clock Color", Color(255, 134, 0), false).showIf { clockDisplay.value }

    private val cpsDisplay by ToggleSetting("CPS Display").section("CPS")

    private val fpsDisplay by ToggleSetting("FPS Display").section("FPS")
    private val fpsColor by ColorSetting("FPS Color", Color(230, 114, 230), false).showIf { fpsDisplay.value }

    private val tpsDisplay by ToggleSetting("TPS Display").section("TPS")
    private val tpsColor by ColorSetting("TPS Color", Color(0, 114, 255), false).showIf { tpsDisplay.value }

    private val pingDisplay by ToggleSetting("Ping Display").section("Ping")
    private val pingColor by ColorSetting("Ping Color", Color(85, 255, 85), false).showIf { pingDisplay.value }

    private val leftClicks = mutableListOf<Long>()
    private val rightClicks = mutableListOf<Long>()

    override fun init() {
        hudElement("ClockDisplay", { clockDisplay.value }) { ctx, _ ->
            val text = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm${if (seconds.value) ":ss" else ""}"))
            ctx.drawString(text, 0, 0, clockColor.value)
            return@hudElement text.width() to 12
        }

        hudElement("CPS Display", { cpsDisplay.value }) { ctx, _ ->
            val l = getCps(leftClicks)
            val r = getCps(rightClicks)
            val text = "§f$l §7| §f$r §bCPS"
            ctx.drawString(text, 2f, 2f)
            return@hudElement text.width() + 4f to 12
        }

        hudElement("FpsDisplay", { fpsDisplay.value }) { ctx, _ ->
            val text = "${mc.fps} fps"
            ctx.drawString(text, 0, 0, fpsColor.value)
            return@hudElement text.width() to 12
        }

        hudElement("TpsDisplay", { tpsDisplay.value }) { ctx, example ->
            val text = "TPS: &f${if (example) 20 else ServerUtils.tps.toFixed(1)}"
            ctx.drawString(text, 0, 0, tpsColor.value)
            return@hudElement text.width() to 12
        }

        hudElement("PingDisplay", { pingDisplay.value }) { ctx, example ->
            val text = "Ping: &f${if (example) 50 else ServerUtils.averagePing}ms"
            ctx.drawString(text, 0, 0, pingColor.value)
            return@hudElement text.width() to 12
        }

        register<WorldChangeEvent> {
            leftClicks.clear()
            rightClicks.clear()
        }
    }

    private fun getCps(list: MutableList<Long>): Int {
        val now = System.currentTimeMillis()
        list.removeIf { now - it > 1000 }
        return list.size
    }

    @JvmStatic
    fun addLeftClick() {
        if (! enabled || ! cpsDisplay.value) return
        leftClicks.add(System.currentTimeMillis())
    }

    @JvmStatic
    fun addRightClick() {
        if (! enabled || ! cpsDisplay.value) return
        rightClicks.add(System.currentTimeMillis())
    }
}