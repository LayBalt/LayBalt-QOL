package com.laybalt.config.types

import com.laybalt.config.ConfigHolder
import com.laybalt.config.Savable
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import gg.essential.universal.ChatColor

class ColorCodeSetting(name: String, defaultValue: ChatColor = ChatColor.WHITE): ConfigHolder<ChatColor>(name, defaultValue), Savable {
    companion object {
        val COLORS = ChatColor.entries.filter { it.isColor() }
    }

    override fun write() = JsonPrimitive(value.char.toString())
    override fun read(element: JsonElement) {
        element.asString?.firstOrNull()?.let { char ->
            COLORS.firstOrNull { it.char == char }?.let { value = it }
        }
    }
}