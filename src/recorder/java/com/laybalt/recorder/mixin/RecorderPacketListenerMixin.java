package com.laybalt.recorder.mixin;

import com.laybalt.recorder.RecorderHooks;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Server chat lines (catch messages, double hooks, loadout confirmations) before any mod can hide them, and the
 * messages and commands you send.
 */
@Mixin(ClientPacketListener.class)
public abstract class RecorderPacketListenerMixin {
    @Inject(
        method = "handleSystemChat",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
            shift = At.Shift.AFTER
        )
    )
    private void laybaltrecorder$onSystemChat(ClientboundSystemChatPacket packet, CallbackInfo ci) {
        if (! packet.overlay()) RecorderHooks.systemChat(packet.content());
    }

    @Inject(method = "sendChat", at = @At("HEAD"))
    private void laybaltrecorder$onSendChat(String content, CallbackInfo ci) {
        RecorderHooks.messageSent(content, false);
    }

    @Inject(method = "sendCommand", at = @At("HEAD"))
    private void laybaltrecorder$onSendCommand(String command, CallbackInfo ci) {
        RecorderHooks.messageSent(command, true);
    }
}
