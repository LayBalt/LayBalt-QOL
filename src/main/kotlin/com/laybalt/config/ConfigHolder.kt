package com.laybalt.config

import com.laybalt.LayBaltQOL

abstract class ConfigHolder<T>(val name: String, val defaultValue: T) {
    var jsonName = name
    var description: String? = null
    var section: String? = null
    var visibility: () -> Boolean = { true }
    var changeListener: ((T) -> Unit)? = null

    var value: T = defaultValue
        set(value) {
            field = value
            if (LayBaltQOL.isLoaded) {
                changeListener?.invoke(value)
            }
        }

    open fun reset() = ::value.set(defaultValue)
}