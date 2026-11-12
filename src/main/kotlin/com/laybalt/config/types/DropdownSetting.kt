package com.laybalt.config.types

import com.laybalt.config.ConfigHolder
import com.laybalt.config.Savable
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive

class DropdownSetting(name: String, defaultValue: Int = 0, val options: List<String>): ConfigHolder<Int>(name, defaultValue), Savable {
    override fun write() = JsonPrimitive(value)
    override fun read(element: JsonElement) {
        value = element.asInt.coerceIn(0, options.lastIndex)
    }
}