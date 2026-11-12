// Ported from OdinClient (https://github.com/skies-starred/OdinClient), BSD-3-Clause, Copyright (c) 2026 Starred
/**
 * Taken from OdinClient 1.8.9
 * odtheking's BSD-3 Clause License applies to this file.
 *
 * BSD 3-Clause License
 *
 * Copyright (c) 2023-2025, odtheking
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its
 *    contributors may be used to endorse or promote products derived from
 *    this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.laybalt.features.impl.general

//#if CHEAT

import com.laybalt.config.types.KeybindSetting
import com.laybalt.config.types.TextInputSetting
import com.laybalt.config.types.ToggleSetting
import com.laybalt.features.Feature
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping
import net.minecraft.client.Options

object FarmKeys: Feature("Temporarily rebinds Attack (block breaking) and Jump while enabled, for farming, and can lock the camera. Your original binds are restored when the feature is turned off.", name = "Farm Keys") {
    private val attackKey by KeybindSetting("Block Breaking")
        .withDescription("Key that replaces your Attack/Destroy bind while Farm Keys is enabled. Leave unbound to keep your normal bind. Takes effect the next time the feature is enabled.")
    private val jumpKey by KeybindSetting("Jump")
        .withDescription("Key that replaces your Jump bind while Farm Keys is enabled. Leave unbound to keep your normal bind. Takes effect the next time the feature is enabled.")
    private val lockCamera by ToggleSetting("Lock Camera", true)
        .withDescription("Mouse movement no longer turns your camera while Farm Keys is enabled.")

    // Original binds (KeyMapping.saveString form, e.g. "key.mouse.left"), persisted so they survive a restart while enabled.
    private val savedAttack by TextInputSetting("Saved Attack Bind", "").hideIf { true }
    private val savedJump by TextInputSetting("Saved Jump Bind", "").hideIf { true }

    @JvmStatic
    val lock: Boolean get() = enabled && lockCamera.value

    override fun onEnable() {
        super.onEnable()
        val options = optionsOrNull() ?: return // early startup: options.txt still holds the farm binds from last session
        var changed = false

        if (attackKey.value != InputConstants.UNKNOWN.value) {
            if (savedAttack.value.isEmpty()) savedAttack.value = options.keyAttack.saveString()
            options.keyAttack.setKey(keyOf(attackKey))
            changed = true
        }
        if (jumpKey.value != InputConstants.UNKNOWN.value) {
            if (savedJump.value.isEmpty()) savedJump.value = options.keyJump.saveString()
            options.keyJump.setKey(keyOf(jumpKey))
            changed = true
        }

        if (changed) apply(options)
    }

    override fun onDisable() {
        super.onDisable()
        val options = optionsOrNull() ?: return
        var changed = false

        if (savedAttack.value.isNotEmpty()) {
            options.keyAttack.setKey(restoredKey(savedAttack.value, options.keyAttack))
            savedAttack.value = ""
            changed = true
        }
        if (savedJump.value.isNotEmpty()) {
            options.keyJump.setKey(restoredKey(savedJump.value, options.keyJump))
            savedJump.value = ""
            changed = true
        }

        if (changed) apply(options)
    }

    private fun optionsOrNull(): Options? = runCatching { mc.options as Options? }.getOrNull()

    private fun keyOf(setting: KeybindSetting): InputConstants.Key =
        (if (setting.isMouse) InputConstants.Type.MOUSE else InputConstants.Type.KEYSYM).getOrCreate(setting.value)

    private fun restoredKey(saved: String, mapping: KeyMapping): InputConstants.Key =
        runCatching { InputConstants.getKey(saved) }.getOrNull() ?: mapping.defaultKey

    private fun apply(options: Options) {
        options.save()
        KeyMapping.resetMapping()
    }
}
//#endif
