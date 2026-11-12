package com.laybalt.mixin;

import com.laybalt.LayBaltQOL;
import com.laybalt.interfaces.IMutedSoundPacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NoammAddons b9b41f83: a sound packet another mod wanted to cancel (see {@link MixinDevonianEventBus}) still reaches the
 * main thread, where F7Listener reads Necron's death sound, and only playing the sound is skipped there.
 */
@Mixin(ClientboundSoundPacket.class)
public class MixinClientboundSoundPacket implements IMutedSoundPacket {
    @Unique private boolean laybalt$muted;

    @Override
    public void laybalt$mute() {
        laybalt$muted = true;
    }

    @Inject(method = "handle(Lnet/minecraft/network/protocol/game/ClientGamePacketListener;)V", at = @At("HEAD"), cancellable = true)
    private void laybalt$skipMutedSound(ClientGamePacketListener listener, CallbackInfo ci) {
        // handle() first runs on the network thread, which only re-queues the packet to the main thread
        if (laybalt$muted && LayBaltQOL.getMc().packetProcessor().isSameThread()) ci.cancel();
    }
}
