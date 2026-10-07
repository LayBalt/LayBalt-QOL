package com.laybalt.config.types

import com.laybalt.config.ConfigHolder

class ButtonSetting(name: String, val clickSound: Boolean = true, private val action: () -> Unit): ConfigHolder<Unit>(name, Unit) {
    operator fun invoke() = action.invoke()
}