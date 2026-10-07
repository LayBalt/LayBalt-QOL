package com.laybalt.mixin;

import com.laybalt.features.impl.misc.sound.MonoAudio;
import com.laybalt.interfaces.IMonoAudioChannel;
import net.minecraft.client.Camera;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Mono Audio: re-centres every playing channel after the listener moved. */
@Mixin(SoundEngine.class)
public abstract class MixinSoundEngineMonoAudio {
    @Shadow @Final private ChannelAccess channelAccess;

    @Inject(method = "updateSource", at = @At("TAIL"))
    private void lbqmod$refreshMonoChannels(Camera camera, CallbackInfo ci) {
        if (!MonoAudio.INSTANCE.enabled) return;

        channelAccess.executeOnChannels(stream ->
            stream.forEach(channel -> ((IMonoAudioChannel) channel).lbqmod$refreshPosition())
        );
    }
}
