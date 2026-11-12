package com.laybalt.features.impl.misc.sound

import com.laybalt.config.types.SoundSetting
import com.laybalt.features.Feature
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundEvents


/**
 * @see com.laybalt.mixin.MixinSoundManager
 */
object ArrowHitSound: Feature() {
    private val soundConfig = createSoundSettings("Sound", SoundEvents.NOTE_BLOCK_HARP.value())

    @JvmStatic
    fun onSoundPlay(soundInstance: SoundInstance): Boolean {
        if (! enabled) return false
        // NoammAddons 4e32e428: the replacement sound would be intercepted again when it is the arrow hit sound itself
        if ((getSettingByName("Sound") as? SoundSetting)?.value?.location == soundInstance.identifier) return false
        if (soundInstance.identifier != SoundEvents.ARROW_HIT_PLAYER.location) return false
        soundConfig.invoke()
        return true
    }
}