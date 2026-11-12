package com.laybalt.config.types

import com.laybalt.config.ConfigHolder
import com.laybalt.config.Savable
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive

class TextInputSetting(name: String, defaultValue: String): ConfigHolder<String>(name, defaultValue), Savable {
    override fun write() = JsonPrimitive(value)
    override fun read(element: JsonElement) {
        value = element.asString
    }
}