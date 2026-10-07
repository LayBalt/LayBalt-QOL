package com.laybalt.mixin;

import com.laybalt.features.impl.misc.sound.MonoAudio;
import com.laybalt.interfaces.IMonoAudioChannel;
import com.mojang.blaze3d.audio.Channel;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.openal.AL10;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Mono Audio: every source is placed straight ahead of the listener at its real distance, so both ears get the same signal. */
@Mixin(Channel.class)
public abstract class MixinChannelMonoAudio implements IMonoAudioChannel {
    @Shadow @Final private int source;
    @Unique private Vec3 lbqmod$lastPosition = new Vec3(0.0, 0.0, 0.0);
    @Unique private boolean lbqmod$relative;

    @Inject(method = "setSelfPosition", at = @At("HEAD"), cancellable = true)
    private void lbqmod$forceMonoPosition(Vec3 pos, CallbackInfo ci) {
        if (!MonoAudio.INSTANCE.enabled) return;
        lbqmod$lastPosition = pos;
        lbqmod$refreshPosition();
        ci.cancel();
    }

    @Inject(method = "setRelative", at = @At("HEAD"), cancellable = true)
    private void lbqmod$forceRelative(boolean relative, CallbackInfo ci) {
        if (!MonoAudio.INSTANCE.enabled) return;
        lbqmod$relative = relative;
        AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
        lbqmod$refreshPosition();
        ci.cancel();
    }

    @Override
    public void lbqmod$refreshPosition() {
        if (!MonoAudio.INSTANCE.enabled) return;
        MonoAudio.applyCenteredPosition(
            source,
            lbqmod$relative ? lbqmod$lastPosition.length() : MonoAudio.distanceToListener(lbqmod$lastPosition)
        );
    }
}
