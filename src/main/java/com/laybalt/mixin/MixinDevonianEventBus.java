package com.laybalt.mixin;

import com.laybalt.interfaces.IMutedSoundPacket;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * NoammAddons b9b41f83: Devonian (Synnerz) mutes a sound by cancelling the whole sound packet on the network thread, so
 * F7Listener would never see Necron's death sound. Its cancel of a sound packet is turned into a mute: the packet is
 * still handled on the main thread and only the sound is skipped ({@link MixinClientboundSoundPacket}).
 * See https://github.com/Synnerz/devonian/blob/8e5d13b0ba0fdbcf1e98673e156c3bc19678f63b/src/main/kotlin/com/github/synnerz/devonian/api/events/EventBus.kt#L136-L147
 * Applied only when Devonian is installed; require = 0 so a Devonian update cannot stop the game from starting.
 */
@Pseudo
@Mixin(targets = "com.github.synnerz.devonian.api.events.EventBus", remap = false)
public class MixinDevonianEventBus {
    @WrapOperation(
        method = "*",
        at = @At(value = "INVOKE", target = "Lcom/github/synnerz/devonian/api/events/PacketReceivedEvent;cancel()V"),
        require = 0
    )
    private static void laybalt$deferSoundCancellation(@Coerce Object event, Operation<Void> original, @Local(name = "packet") Packet<?> packet) {
        if (packet instanceof ClientboundSoundPacket) ((IMutedSoundPacket) packet).laybalt$mute();
        else original.call(event);
    }
}
