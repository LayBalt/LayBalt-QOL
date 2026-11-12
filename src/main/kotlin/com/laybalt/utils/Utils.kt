package com.laybalt.utils

import com.laybalt.LayBaltQOL.mc
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ServerGamePacketListener
import gg.essential.universal.UDesktop
import java.awt.Color
import java.net.URI

object Utils {
    val favoriteColor = Color(0, 134, 255)

    fun openDiscordLink() = UDesktop.browse(URI("https://discord.gg/555QWxv6nS"))

    fun Packet<ServerGamePacketListener>.send() = mc.connection?.send(this)
}